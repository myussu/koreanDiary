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
    /** Configuración para cada conexión: (token de reanudación, configuración mínima). */
    private val configFor: (resumeHandle: String?, minimal: Boolean) -> LiveConfig,
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
    @Volatile private var minimal = false
    @Volatile private var everConnected = false
    private var attempts = 0

    fun start() = connect()

    private fun connect() {
        val config = configFor(handle, minimal)
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

    private fun reconnect(oldSocket: WebSocket?, reason: String) {
        if (closedByUser || finished) return
        // Antes de la primera respuesta del servidor: probablemente rechazó la configuración.
        if (!everConnected && !minimal) {
            minimal = true
            scope.launch { connect() }
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
                    // Reconecta ya, antes de que el servidor corte: la conversación sigue sin perder nada.
                    is LiveEvent.GoAway -> {
                        signals.trySend(SessionSignal.Event(event))
                        if (this@LiveSession.handle != null && !closedByUser && !finished) {
                            signals.trySend(SessionSignal.Reconnecting)
                            // connect() reemplaza `current` al instante: el cierre de la vieja ya no cuenta como caída.
                            connect()
                            webSocket.close(1000, "reconexión")
                        }
                    }
                    else -> signals.trySend(SessionSignal.Event(event))
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            if (isCurrent(webSocket)) onDrop(webSocket, "Cierre $code${if (reason.isNotBlank()) ": $reason" else ""}")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent(webSocket)) return
            val detail = response?.let { "HTTP ${it.code}" } ?: (t.message ?: t.javaClass.simpleName)
            onDrop(webSocket, "Sin conexión: $detail")
        }

        private fun onDrop(webSocket: WebSocket, reason: String) {
            synchronized(lock) {
                if (!isCurrent(webSocket)) return
                ready = false
            }
            if (!closedByUser) reconnect(webSocket, reason)
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
    }
}
