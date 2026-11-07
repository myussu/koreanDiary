package com.jorge.koreandiary.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Lo que la sesión le cuenta a la pantalla. */
sealed interface SessionSignal {
    /** Conexión lista. [resumed] = true si es una reconexión de la misma conversación. */
    data class Connected(val resumed: Boolean) : SessionSignal
    data class Event(val event: LiveEvent) : SessionSignal
    data object Reconnecting : SessionSignal
    /** El servidor rechazó la configuración y se bajó un nivel (ver [LiveConfig.fallback]). */
    data class Fallback(val level: Int, val reason: String) : SessionSignal
    /** Fin definitivo. [reason] trae el código y motivo del cierre para poder diagnosticarlo. */
    data class Closed(val reason: String, val error: Boolean) : SessionSignal
}

/**
 * Una conversación con la Live API de Gemini por WebSocket.
 *
 * - Cada conexión dura ~10 minutos: con el aviso `goAway` (o si se cae) se reconecta sola con el token
 *   de reanudación, sin perder el contexto. Es lo que el playground de AI Studio no hace.
 * - Si el servidor rechaza la configuración antes de empezar, reintenta una vez con la mínima.
 */
class LiveSession(
    private val apiKey: String,
    private val scope: CoroutineScope,
    /** Configuración para cada conexión: (token de reanudación, nivel de plan B). */
    private val configFor: (resumeHandle: String?, fallback: Int) -> LiveConfig,
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val signals = Channel<SessionSignal>(Channel.UNLIMITED)
    val events: Flow<SessionSignal> = signals.receiveAsFlow()

    private val lock = Any()
    @Volatile private var current: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var closedByUser = false
    @Volatile private var finished = false
    @Volatile private var handle: String? = null
    @Volatile private var fallback = 0
    @Volatile private var everConnected = false
    @Volatile private var attempts = 0
    /** goAway recibido: se reconecta al terminar el turno de la IA (o antes del corte). */
    @Volatile private var swapPending = false

    fun start() = connect()

    private fun connect() {
        val config = configFor(handle, fallback)
        val url = LiveProtocol.ENDPOINT + "?key=" + URLEncoder.encode(apiKey, "UTF-8")
        val request = Request.Builder().url(url).build()
        synchronized(lock) {
            ready = false
            current = client.newWebSocket(request, Listener(LiveProtocol.setup(config)))
        }
    }

    /** Envía solo con la conexión lista; mientras reconecta, el audio se descarta. */
    fun send(message: String): Boolean {
        val ws = current ?: return false
        return ready && ws.send(message)
    }

    fun close() {
        closedByUser = true
        current?.close(1000, "fin")
        finish("Terminaste la sesión.", error = false)
        client.dispatcher.executorService.shutdown()
    }

    private fun finish(reason: String, error: Boolean) {
        synchronized(lock) {
            if (finished) return
            finished = true
        }
        signals.trySend(SessionSignal.Closed(reason, error))
        signals.close()
    }

    private fun reconnect(oldSocket: WebSocket?, reason: String, rejected: Boolean = false) {
        if (closedByUser || finished) return
        // El servidor cerró antes de aceptar la sesión: rechazó algo de la configuración. Se baja un nivel.
        if (!everConnected) {
            if (rejected && fallback < 2) {
                fallback++
                signals.trySend(SessionSignal.Fallback(fallback, reason))
                scope.launch { connect() }
            } else {
                finish(reason, error = true)
            }
            return
        }
        if (handle == null || attempts >= MAX_ATTEMPTS) {
            finish(reason, error = true)
            return
        }
        attempts++
        signals.trySend(SessionSignal.Reconnecting)
        scope.launch {
            delay(800L * attempts)
            if (!closedByUser && !finished) {
                oldSocket?.cancel()
                connect()
            }
        }
    }

    /** Cambia a una conexión nueva (con el token) y cierra la vieja sin que cuente como caída. */
    private fun swap(old: WebSocket) {
        swapPending = false
        if (handle == null || closedByUser || finished) return
        signals.trySend(SessionSignal.Reconnecting)
        connect()
        old.close(1000, "reconexión")
    }

    private inner class Listener(private val setupMessage: String) : WebSocketListener() {
        private fun isCurrent(ws: WebSocket) = ws === current

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (isCurrent(webSocket)) webSocket.send(setupMessage)
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text)

        // La Live API suele mandar el JSON en frames binarios.
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(webSocket, bytes.utf8())

        private fun handle(webSocket: WebSocket, raw: String) {
            if (!isCurrent(webSocket)) return
            for (event in LiveProtocol.parse(raw)) {
                when (event) {
                    LiveEvent.SetupComplete -> {
                        val resumed = everConnected
                        ready = true
                        everConnected = true
                        attempts = 0
                        signals.trySend(SessionSignal.Connected(resumed))
                    }
                    is LiveEvent.ResumeHandle -> this@LiveSession.handle = event.handle
                    // Aviso de corte: se reconecta cuando la IA termine de hablar, para no cortarla a media frase,
                    // o 3 s antes del corte si no termina. connect() reemplaza `current` al instante.
                    is LiveEvent.GoAway -> {
                        signals.trySend(SessionSignal.Event(event))
                        if (this@LiveSession.handle != null && !swapPending) {
                            swapPending = true
                            val wait = ((event.timeLeftMs ?: 10_000L) - 3_000L).coerceAtLeast(0L)
                            scope.launch {
                                delay(wait)
                                if (swapPending && isCurrent(webSocket)) swap(webSocket)
                            }
                        }
                    }
                    LiveEvent.TurnComplete -> {
                        signals.trySend(SessionSignal.Event(event))
                        if (swapPending) swap(webSocket)
                    }
                    else -> signals.trySend(SessionSignal.Event(event))
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            // Un cierre del servidor antes de aceptar la sesión es un rechazo de la configuración (p. ej. 1007).
            if (isCurrent(webSocket)) onDrop(webSocket, "Cierre $code${if (reason.isNotBlank()) ": $reason" else ""}", rejected = true)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent(webSocket)) return
            val detail = when {
                // Respuesta HTTP al conectar: casi siempre la clave o el nombre del modelo.
                response != null -> "El servidor rechazó la conexión (HTTP ${response.code}). Revisa tu clave de Gemini y el modelo de voz."
                else -> "Sin conexión: ${t.message ?: t.javaClass.simpleName}"
            }
            onDrop(webSocket, detail, rejected = false)
        }

        private fun onDrop(webSocket: WebSocket, reason: String, rejected: Boolean) {
            synchronized(lock) {
                if (!isCurrent(webSocket)) return
                ready = false
            }
            if (!closedByUser) reconnect(webSocket, reason, rejected)
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
    }
}
