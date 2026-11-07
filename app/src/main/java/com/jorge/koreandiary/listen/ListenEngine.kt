package com.jorge.koreandiary.listen

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.jorge.koreandiary.AppContainer
import com.jorge.koreandiary.ai.ParseException
import com.jorge.koreandiary.ai.PromptItem
import com.jorge.koreandiary.ai.ProviderException
import com.jorge.koreandiary.conversation.ConversationMath
import com.jorge.koreandiary.conversation.ConversationRecord
import com.jorge.koreandiary.conversation.Line
import com.jorge.koreandiary.conversation.RecordMode
import com.jorge.koreandiary.conversation.Speaker
import com.jorge.koreandiary.settings.SecretStore
import com.jorge.koreandiary.voice.LiveConfig
import com.jorge.koreandiary.voice.LiveEvent
import com.jorge.koreandiary.voice.LiveProtocol
import com.jorge.koreandiary.voice.LiveSession
import com.jorge.koreandiary.voice.Microphone
import com.jorge.koreandiary.voice.SessionSignal
import com.jorge.koreandiary.voice.TurnMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import com.jorge.koreandiary.voice.Speaker as VoiceOut

/** En qué está la sesión de escucha. */
enum class ListenPhase {
    IDLE,
    /** Escribiendo el guion y preparando la voz. */
    PREPARING,
    NARRATING,
    /** Pausa corta entre segmentos sin pregunta: luego sigue sola. */
    GAP,
    /** La pausaste tú (o la IA espera que digas «sigue»). */
    PAUSED,
    /** Punto de pregunta: la IA te pregunta algo. */
    QUESTION,
    /** Preguntas de comprensión del final. */
    COMPREHENSION,
    CLOSING,
    /** Terminó: [ListenState.finishedId] es la sesión guardada. */
    DONE;

    /** Fases en las que puedes hablar con la IA. */
    val interactive get() = this == PAUSED || this == QUESTION || this == COMPREHENSION
    val active get() = this != IDLE && this != DONE
}

object Narrator {
    const val GEMINI = "gemini"
    const val SYSTEM = "sistema"
}

data class ListenLine(val id: Int, val speaker: Speaker, val text: String, val open: Boolean)

data class ListenState(
    val phase: ListenPhase = ListenPhase.IDLE,
    val status: String = "",
    val topic: String = "",
    val script: ListenScript? = null,
    /** Segmento que suena o sonó último. */
    val segment: Int = 0,
    /** Esperando el audio de un segmento (la voz aún no llegó). */
    val buffering: Boolean = false,
    val speed: Float = 0.9f,
    val connected: Boolean = false,
    val lines: List<ListenLine> = emptyList(),
    val talking: Boolean = false,
    val aiSpeaking: Boolean = false,
    val elapsedSec: Int = 0,
    val totalSec: Int = 0,
    val turnMode: String = TurnMode.PUSH_TO_TALK,
    val targets: List<PromptItem> = emptyList(),
    /** Objetivo que la pregunta de la IA te invita a usar. */
    val floating: String? = null,
    /** Veces que escuchaste cada objetivo. */
    val heard: Map<String, Int> = emptyMap(),
    val comprehension: List<ComprehensionResult> = emptyList(),
    /** Segundos que faltan para que siga sola (pausa entre segmentos). */
    val gapLeft: Int = 0,
    val message: String? = null,
    /** No se pudo empezar: vuelve a la preparación con este motivo. */
    val error: String? = null,
    val finishedId: Long? = null,
)

/** Lo que espera a que la IA termine de hablar para no cortarla. */
private sealed interface Pending {
    data object Resume : Pending
    data class Repeat(val slower: Boolean) : Pending
    data object Finish : Pending
}

/**
 * Modo Escuchar: un pódcast corto en coreano con pausas para preguntar.
 *
 * - El guion lo escribe Gemini por texto (con búsqueda opcional) y la voz es el TTS de Gemini, en dos
 *   pedidos por sesión (el primer segmento y el resto, en paralelo); si falla, la voz del celular.
 * - Una sesión Live queda conectada sin hablar: en las pausas responde lo que preguntes, hace las
 *   preguntas de los puntos de pregunta y al final las de comprensión.
 * - Vive en el AppContainer (no en la pantalla) y un servicio en primer plano la mantiene con la
 *   pantalla apagada, con controles en la notificación y en los botones de los audífonos.
 */
class ListenEngine(private val c: AppContainer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ListenState())
    val state: StateFlow<ListenState> = _state.asStateFlow()

    private var session: LiveSession? = null
    private var liveDead = false
    private var closing = false
    private var mic: Microphone? = null
    private var voice: VoiceOut? = null
    private var player: NarrationPlayer? = null
    private var systemVoice: SystemVoice? = null
    private var audio: MutableList<PcmAudio?> = mutableListOf()
    private val jobs = mutableListOf<Job>()
    private var ticker: Job? = null
    private var focusRequest: AudioFocusRequest? = null

    private var startedAtMs = 0L
    private var hardCapSec = 0
    private var nextLineId = 0
    private var userLineId = -1
    private var checkedUserLine = -1
    private var notes = mutableListOf<String>()
    private var highlighted = mutableListOf<String>()
    private var asked = mutableSetOf<Int>()
    private var heardSegments = 0
    private var repeats = 0
    private var pauses = 0
    private var reconnections = 0
    private var tokensTotal = 0L
    private var lastPromptTokens = 0
    private var narratorsUsed = linkedSetOf<String>()
    private var lookups = mutableListOf<String>()
    private var geminiVoiceFailed = false
    private var scriptModel = ""
    private var searched = false

    /** Siguiente segmento al continuar (si la pausa no fue a mitad de uno). */
    private var cursor = 0
    private var waitingFor = -1
    private var pausedMid = false
    /** Tras repetir un segmento en una pausa, se vuelve a esa fase sin repetir la pregunta. */
    private var returnTo: ListenPhase? = null
    private var gapUntil = 0L
    private var pending: Pending? = null
    private var pendingAt = 0L
    private var turnCompleteAt = 0L
    private var lastAiAudioAt = 0L
    private var lastWorkingTone = 0L
    private var allAnsweredAt = 0L
    /** Mensaje para la IA que espera a que se conecte. */
    private var queuedPrompt: String? = null
    /** Primera línea creada desde que el objetivo empezó a flotar: una respuesta tuya desde ahí lo apaga. */
    private var floatingSince = Int.MAX_VALUE

    // ---------------------------------------------------------------- Inicio

    /** Empieza (con el permiso de micrófono ya concedido). Devuelve un motivo si no se puede. */
    fun start(topic: String, topicLabel: String, minutes: Int, search: Boolean, targets: List<PromptItem>): String? {
        if (_state.value.phase.active) return null
        val s = c.settings.state.value
        val key = c.secrets.get(SecretStore.GEMINI_KEY) ?: return "Falta tu clave de Gemini: pégala en Ajustes (es la misma del diario)."
        val today = ConversationMath.stats(c.conversations.sessions.value, LocalDate.now()).secondsToday
        val remaining = s.voiceDailyMinutes * 60 - today
        if (remaining < 120) return "Llegaste a tu límite de ${s.voiceDailyMinutes} min por hoy (conversación y escucha). Puedes subirlo en Ajustes."
        val total = minOf(minutes * 60, remaining)
        val out = try {
            VoiceOut(scope)
        } catch (e: Exception) {
            return "No se pudo abrir el audio del celular: ${e.message}"
        }
        resetSession()
        voice = out
        player = NarrationPlayer(scope).also { it.setSpeed(s.listenSpeed) }
        startedAtMs = System.currentTimeMillis()
        // Las pausas pueden alargar la sesión; el tope evita que quede abierta para siempre.
        hardCapSec = total + maxOf(240, total / 2)
        _state.value = ListenState(
            phase = ListenPhase.PREPARING, status = if (search) "Buscando y escribiendo el guion…" else "Escribiendo el guion…",
            topic = topicLabel, totalSec = total, turnMode = s.voiceTurnMode, targets = targets, speed = s.listenSpeed,
        )
        ListenService.start(c.app)
        requestAudioFocus()
        startTicker()
        jobs += scope.launch { prepare(key, topic, total / 60, search, targets) }
        return null
    }

    private fun resetSession() {
        liveDead = false
        closing = false
        nextLineId = 0
        userLineId = -1
        checkedUserLine = -1
        notes = mutableListOf()
        highlighted = mutableListOf()
        asked = mutableSetOf()
        heardSegments = 0
        repeats = 0
        pauses = 0
        reconnections = 0
        tokensTotal = 0
        lastPromptTokens = 0
        narratorsUsed = linkedSetOf()
        lookups = mutableListOf()
        geminiVoiceFailed = false
        cursor = 0
        waitingFor = -1
        pausedMid = false
        returnTo = null
        pending = null
        turnCompleteAt = 0L
        lastAiAudioAt = 0L
        allAnsweredAt = 0L
        queuedPrompt = null
        floatingSince = Int.MAX_VALUE
        audio = mutableListOf()
    }

    private suspend fun prepare(key: String, topic: String, minutes: Int, search: Boolean, targets: List<PromptItem>) {
        val s = c.settings.state.value
        val api = ListenApi(key)
        val result = try {
            api.script(ListenPrompts.script(s.profile, s.level, s.standardLevel, topic, targets, minutes, search), s.listenScriptModel, s.geminiModel, search)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderException) {
            finish(if (e.offline) "Sin conexión: no se pudo escribir el guion." else "No se pudo escribir el guion: ${e.message}", error = true)
            return
        } catch (e: Exception) {
            finish("No se pudo escribir el guion: ${e.message ?: e.javaClass.simpleName}", error = true)
            return
        }
        val script = try {
            ListenScriptParser.parse(result.raw, targets.map { it.ref }.toSet()).copy(sources = result.sources)
        } catch (e: ParseException) {
            finish("El guion llegó con un formato raro: ${e.message}", error = true)
            return
        }
        notes += result.notes
        scriptModel = result.model
        searched = result.searched
        audio = MutableList(script.segments.size) { null }
        _state.update { it.copy(script = script, status = "Preparando la voz del narrador…") }
        startLive(key, script, targets)
        // El resto se pide a la vez que el primero: cuando termine de sonar el primero ya suele estar listo.
        if (script.segments.size > 1) jobs += scope.launch { synthInto((1 until script.segments.size).toList(), api) }
        synthInto(listOf(0), api)
        if (audio.getOrNull(0) == null) {
            finish("No se pudo preparar la voz del narrador.", error = true)
            return
        }
        earcon(Earcon.START)
        playSegment(0, delayMs = 700)
    }

    /** Voz de varios segmentos en un solo pedido; si falla, la voz del celular segmento por segmento. */
    private suspend fun synthInto(indices: List<Int>, api: ListenApi) {
        val script = _state.value.script ?: return
        val s = c.settings.state.value
        if (s.listenNarrator == Narrator.GEMINI && !geminiVoiceFailed) {
            try {
                val texts = indices.map { script.segments[it].ko }
                val pcm = api.speech(texts.joinToString(PAUSE_BETWEEN), s.listenTtsModel, s.voiceName, ListenPrompts.TTS_STYLE)
                val parts = if (indices.size == 1) listOf(pcm) else withContext(Dispatchers.Default) {
                    SilenceSplitter.split(pcm, texts.map { syllables(it) }).map { pcm.slice(it) }
                }
                narratorsUsed += Narrator.GEMINI
                indices.forEachIndexed { k, i -> setAudio(i, parts[k]) }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!geminiVoiceFailed) {
                    geminiVoiceFailed = true
                    notes += "La voz de Gemini falló (${e.message ?: e.javaClass.simpleName}): se usó la voz del celular."
                    showMessage("La voz de Gemini no respondió: sigue con la voz del celular.")
                }
            }
        }
        val sv = systemVoice ?: SystemVoice(c.app).also { systemVoice = it }
        for (i in indices) {
            if (audio.getOrNull(i) != null) continue
            try {
                setAudio(i, sv.synth(script.segments[i].ko))
                narratorsUsed += Narrator.SYSTEM
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notes += "La voz del celular falló: ${e.message}"
                showMessage(e.message ?: "La voz del celular falló.")
                earcon(Earcon.ERROR)
                return
            }
        }
    }

    private fun setAudio(i: Int, pcm: PcmAudio) {
        if (i !in audio.indices) return
        audio[i] = pcm
        if (waitingFor == i) {
            waitingFor = -1
            playSegment(i)
        }
    }

    private fun startLive(key: String, script: ListenScript, targets: List<PromptItem>) {
        val s = c.settings.state.value
        val system = ListenPrompts.liveSystem(s.profile, s.level, s.standardLevel, script, targets, s.voiceCorrection)
        val vocabulary = (targets.map { it.label } + scriptWords(script)).distinct()
        val live = LiveSession(key, scope) { handle, fallback ->
            LiveConfig(
                model = s.voiceModel, voice = s.voiceName, systemInstruction = system,
                turnMode = s.voiceTurnMode, silenceMs = s.voiceSilenceMs,
                vocabulary = vocabulary, resumeHandle = handle, fallback = fallback,
                highlightTool = targets.isNotEmpty(), listenTools = true,
            )
        }
        session = live
        jobs += scope.launch { live.events.collect { onSignal(it) } }
        live.start()
    }

    // ---------------------------------------------------------------- Narración

    private fun playSegment(i: Int, delayMs: Long = 0) {
        val script = _state.value.script ?: return
        if (i !in script.segments.indices) return
        cursor = i
        closeMic()
        val pcm = audio.getOrNull(i)
        if (pcm == null) {
            // La voz de este segmento todavía no llegó: tonos de espera hasta que llegue.
            waitingFor = i
            _state.update { it.copy(phase = ListenPhase.NARRATING, segment = i, buffering = true, floating = null, status = "Preparando la voz…") }
            return
        }
        _state.update {
            it.copy(
                phase = ListenPhase.NARRATING, segment = i, buffering = false, floating = null, gapLeft = 0,
                status = "Segmento ${i + 1} de ${script.segments.size}",
            )
        }
        player?.play(pcm, delayMs) { scope.launch { onSegmentDone(i) } }
    }

    private fun onSegmentDone(i: Int) {
        val st = _state.value
        val script = st.script ?: return
        if (st.phase != ListenPhase.NARRATING || st.segment != i) return
        heardSegments = maxOf(heardSegments, i + 1)
        val heard = st.heard.toMutableMap()
        script.segments[i].targets.forEach { heard[it] = (heard[it] ?: 0) + 1 }
        _state.update { it.copy(heard = heard) }
        cursor = i + 1
        sendSilent(ListenPrompts.heard(i))
        val back = returnTo
        returnTo = null
        val question = script.segments[i].question
        when {
            back != null -> {
                enterInteractive(back, statusFor(back))
                sendSilent("[APP] Volvió a escuchar el segmento ${i + 1}. Espera lo que te diga.")
            }
            question != null && liveUsable() && i !in asked -> {
                asked += i
                enterInteractive(ListenPhase.QUESTION, statusFor(ListenPhase.QUESTION))
                floatingSince = nextLineId
                _state.update { it.copy(floating = question.target) }
                question.target?.let { highlighted += it }
                sendPrompt(ListenPrompts.askQuestion(i, question))
            }
            i == script.segments.lastIndex -> enterComprehension()
            else -> {
                val gap = c.settings.state.value.listenGapSec
                if (gap <= 0) {
                    playSegment(i + 1)
                } else {
                    gapUntil = System.currentTimeMillis() + gap * 1000L
                    _state.update { it.copy(phase = ListenPhase.GAP, gapLeft = gap, status = "Sigue en $gap s…") }
                }
            }
        }
    }

    private fun enterComprehension() {
        val script = _state.value.script ?: return
        if (!liveUsable()) {
            finish("Terminó la narración.")
            return
        }
        enterInteractive(ListenPhase.COMPREHENSION, statusFor(ListenPhase.COMPREHENSION))
        sendPrompt(if (script.comprehension.isNotEmpty()) ListenPrompts.COMPREHENSION else ListenPrompts.NO_COMPREHENSION)
    }

    private fun statusFor(phase: ListenPhase) = when (phase) {
        ListenPhase.QUESTION -> "Pregunta de la IA: responde, o di «sigue»"
        ListenPhase.COMPREHENSION -> "Preguntas de comprensión"
        else -> "En pausa: pregunta lo que quieras o di «sigue»"
    }

    private fun enterInteractive(phase: ListenPhase, status: String) {
        _state.update { it.copy(phase = phase, status = status, buffering = false, gapLeft = 0) }
        earcon(Earcon.TURN)
        openMic()
    }

    private fun leaveInteractive() {
        if (_state.value.talking) stopTalking(tone = false)
        closeMic()
        voice?.flush()
        pending = null
        _state.update { it.copy(floating = null) }
    }

    private fun liveUsable() = session != null && !liveDead

    // ---------------------------------------------------------------- Controles

    /** Botón de los audífonos (un toque): pausa; en una pausa habla (pulsar para hablar) o sigue. */
    fun button() {
        val st = _state.value
        when {
            st.phase == ListenPhase.NARRATING || st.phase == ListenPhase.GAP -> pause()
            st.phase.interactive && st.turnMode == TurnMode.PUSH_TO_TALK && st.connected -> toggleTalk()
            st.phase.interactive -> resume()
        }
    }

    /** Pausa / Seguir de la pantalla y la notificación. */
    fun togglePause() {
        val st = _state.value
        if (st.phase == ListenPhase.NARRATING || st.phase == ListenPhase.GAP) pause() else if (st.phase.interactive) resume()
    }

    fun pause() {
        val st = _state.value
        when (st.phase) {
            ListenPhase.NARRATING -> if (st.buffering) {
                waitingFor = -1
                pausedMid = false
            } else {
                player?.pause()
                pausedMid = true
            }
            ListenPhase.GAP -> pausedMid = false
            else -> return
        }
        pauses++
        enterInteractive(ListenPhase.PAUSED, statusFor(ListenPhase.PAUSED))
        sendSilent(ListenPrompts.manualPause(st.segment))
    }

    fun resume() {
        val st = _state.value
        when {
            st.phase == ListenPhase.PAUSED && pausedMid -> {
                leaveInteractive()
                pausedMid = false
                _state.update { it.copy(phase = ListenPhase.NARRATING, status = "Segmento ${st.segment + 1} de ${st.script?.segments?.size ?: 0}") }
                // Pausaste justo cuando terminaba: el segmento ya sonó entero, se sigue como si hubiera terminado.
                if (player?.isPlaying != true) {
                    onSegmentDone(st.segment)
                    return
                }
                earcon(Earcon.RESUME)
                scope.launch {
                    delay(250)
                    if (_state.value.phase == ListenPhase.NARRATING) player?.resume()
                }
            }
            st.phase == ListenPhase.PAUSED || st.phase == ListenPhase.QUESTION -> {
                leaveInteractive()
                continueFrom(cursor)
            }
            else -> Unit
        }
    }

    /** Siguiente: en la narración salta al próximo segmento; en una pausa, sigue. */
    fun next() {
        val st = _state.value
        when {
            st.phase == ListenPhase.NARRATING || st.phase == ListenPhase.GAP -> {
                player?.stop()
                waitingFor = -1
                continueFrom(if (st.phase == ListenPhase.GAP) cursor else st.segment + 1)
            }
            st.phase.interactive -> resume()
        }
    }

    private fun continueFrom(i: Int) {
        val script = _state.value.script ?: return
        if (i >= script.segments.size) {
            enterComprehension()
            return
        }
        earcon(Earcon.RESUME)
        playSegment(i, delayMs = 300)
    }

    /** Repite el segmento actual desde el inicio, más lento si lo pides. */
    fun repeat(slower: Boolean = false) {
        val st = _state.value
        if (st.script == null || st.phase == ListenPhase.PREPARING || !st.phase.active || st.phase == ListenPhase.CLOSING) return
        if (slower) setSpeed(st.speed - 0.15f)
        returnTo = if (st.phase.interactive) st.phase else null
        pausedMid = false
        repeats++
        leaveInteractive()
        player?.stop()
        waitingFor = -1
        earcon(Earcon.RESUME)
        playSegment(st.segment, delayMs = 300)
    }

    fun setSpeed(value: Float) {
        val v = (Math.round(value.coerceIn(0.6f, 1.2f) * 20) / 20f)
        player?.setSpeed(v)
        _state.update { it.copy(speed = v) }
        c.settings.update { it.copy(listenSpeed = v) }
    }

    fun toggleTalk() = if (_state.value.talking) stopTalking() else startTalking()

    /** Pulsar para hablar (solo en una pausa). */
    fun startTalking() {
        val st = _state.value
        if (!st.phase.interactive || !st.connected || st.talking || st.turnMode != TurnMode.PUSH_TO_TALK) return
        if (session?.send(LiveProtocol.activityStart()) != true) return
        voice?.flush()
        // Si querías hablar, no se sigue solo aunque la IA lo haya pedido.
        if (pending is Pending.Resume) pending = null
        earcon(Earcon.RESUME)
        _state.update { cur ->
            val id = nextLineId++
            userLineId = id
            cur.copy(talking = true, lines = cur.lines.map { if (it.open) it.copy(open = false) else it } + ListenLine(id, Speaker.USER, "", open = true))
        }
    }

    fun stopTalking(tone: Boolean = true) {
        if (!_state.value.talking) return
        session?.send(LiveProtocol.activityEnd())
        _state.update { it.copy(talking = false) }
        if (tone) earcon(Earcon.WORKING)
    }

    /** Tocaste una palabra para traducirla: queda en la sesión y la IA usa palabras más simples. */
    fun noteLookup(word: String, gloss: String) {
        if (!_state.value.phase.active) return
        lookups += gloss
        sendSilent("[APP] No conocía la palabra «$word». Si te pregunta, explícala con palabras simples.")
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun showMessage(text: String) = _state.update { it.copy(message = text) }

    /** La pantalla ya mostró el resultado (revisión o error): vuelve a reposo. */
    fun acknowledge() {
        if (!_state.value.phase.active) _state.value = ListenState()
    }

    // ---------------------------------------------------------------- IA de voz

    private fun sendSilent(text: String) {
        if (_state.value.connected) session?.send(LiveProtocol.context(text, respond = false))
    }

    /** Mensaje que pide respuesta: si la IA aún no se conectó, se manda al conectar. */
    private fun sendPrompt(text: String) {
        if (_state.value.connected && session?.send(LiveProtocol.context(text, respond = true)) == true) return
        queuedPrompt = text
    }

    private fun onSignal(signal: SessionSignal) {
        when (signal) {
            is SessionSignal.Connected -> {
                _state.update { it.copy(connected = true) }
                if (signal.resumed) {
                    reconnections++
                    if (_state.value.talking) session?.send(LiveProtocol.activityStart())
                }
                queuedPrompt?.let { text ->
                    queuedPrompt = null
                    if (_state.value.phase.interactive) session?.send(LiveProtocol.context(text, respond = true))
                }
            }
            SessionSignal.Reconnecting -> _state.update { it.copy(connected = false) }
            is SessionSignal.Fallback -> {
                val what = if (signal.level == 1) "sin idiomas ni vocabulario en la transcripción" else "sin funciones (las órdenes por voz se reconocen igual en tu transcripción)"
                notes += "Gemini rechazó la configuración de voz; se reintentó $what. Motivo: ${signal.reason}"
            }
            is SessionSignal.Closed -> {
                session = null
                liveDead = true
                _state.update { it.copy(connected = false, talking = false) }
                if (closing) return
                notes += "La IA de voz se desconectó: ${signal.reason}"
                // Sin la IA la narración sigue, pero sin preguntas: se avisa con un tono aunque no mires.
                earcon(Earcon.ERROR)
                showMessage("Se cortó la IA de voz (${signal.reason}): la narración sigue sin preguntas.")
                when (_state.value.phase) {
                    ListenPhase.COMPREHENSION -> finish("Se cortó la IA de voz.")
                    ListenPhase.PAUSED, ListenPhase.QUESTION -> resume()
                    else -> Unit
                }
            }
            is SessionSignal.Event -> onEvent(signal.event)
        }
    }

    private fun onEvent(e: LiveEvent) {
        val st = _state.value
        when (e) {
            // La IA solo suena en las pausas; si responde tarde (ya sigue la narración) se descarta.
            is LiveEvent.Audio -> if (st.phase.interactive && !st.talking) {
                lastAiAudioAt = System.currentTimeMillis()
                voice?.play(e.pcm)
            }
            is LiveEvent.InputText -> appendUser(e.text)
            is LiveEvent.OutputText -> if (st.phase.interactive && !st.talking) appendAi(e.text)
            LiveEvent.Interrupted -> {
                voice?.flush()
                userLineId = -1
                closeLines()
            }
            LiveEvent.TurnComplete -> {
                turnCompleteAt = System.currentTimeMillis()
                userLineId = -1
                closeLines(Speaker.AI)
                checkUserCommand()
                // El objetivo deja de flotar cuando ya respondiste y la IA te contestó.
                if (st.floating != null && st.lines.any { it.speaker == Speaker.USER && it.text.isNotBlank() && it.id >= floatingSince }) {
                    _state.update { it.copy(floating = null) }
                }
            }
            is LiveEvent.ToolCall -> {
                session?.send(LiveProtocol.toolResponse(e.id, e.name))
                onTool(e.name, e.args)
            }
            is LiveEvent.Usage -> {
                tokensTotal += e.total
                lastPromptTokens = maxOf(e.prompt, 0)
            }
            else -> Unit
        }
    }

    private fun onTool(name: String, args: Map<String, String>) {
        when (name) {
            LiveProtocol.TOOL_HIGHLIGHT -> {
                val ref = args["ref"]?.trim()
                if (ref != null && _state.value.targets.any { it.ref == ref }) {
                    if (ref !in highlighted) highlighted += ref
                    if (_state.value.floating != ref) floatingSince = nextLineId
                    _state.update { it.copy(floating = ref) }
                }
            }
            LiveProtocol.TOOL_RESUME -> request(Pending.Resume)
            LiveProtocol.TOOL_REPEAT -> request(Pending.Repeat(args["slower"]?.trim()?.lowercase() == "true"))
            LiveProtocol.TOOL_PAUSE -> pending = null
            LiveProtocol.TOOL_FINISH -> request(Pending.Finish)
            LiveProtocol.TOOL_COMPREHENSION -> {
                val index = args["index"]?.trim()?.toDoubleOrNull()?.toInt() ?: return
                val result = ComprehensionResult(index, args["correct"]?.trim()?.lowercase() == "true", args["note"].orEmpty().trim())
                _state.update { st -> st.copy(comprehension = (st.comprehension.filter { it.index != index } + result).sortedBy { it.index }) }
            }
        }
    }

    /** Por si la IA no llama a la función: tu última línea, si es solo una orden ("sigue", "다시"…). */
    private fun checkUserCommand() {
        val line = _state.value.lines.lastOrNull { it.speaker == Speaker.USER && it.text.isNotBlank() } ?: return
        if (line.id <= checkedUserLine) return
        checkedUserLine = line.id
        when (VoiceCommands.match(line.text)) {
            VoiceCommand.RESUME -> request(Pending.Resume)
            VoiceCommand.REPEAT -> request(Pending.Repeat(false))
            VoiceCommand.SLOWER -> request(Pending.Repeat(true))
            VoiceCommand.PAUSE -> pending = null
            VoiceCommand.FINISH -> request(Pending.Finish)
            null -> Unit
        }
    }

    private fun request(p: Pending) {
        if (!_state.value.phase.interactive && p !is Pending.Finish) return
        pending = p
        pendingAt = System.currentTimeMillis()
    }

    private fun runPending(p: Pending) {
        when (p) {
            Pending.Resume -> if (_state.value.phase == ListenPhase.COMPREHENSION) Unit else resume()
            is Pending.Repeat -> repeat(p.slower)
            Pending.Finish -> finish("Terminó la escucha.")
        }
    }

    // ---------------------------------------------------------------- Transcripción

    private fun appendUser(text: String) = _state.update { st ->
        val last = st.lines.lastOrNull()
        val target = when {
            last != null && last.speaker == Speaker.USER && last.open -> last.id
            userLineId >= 0 && st.lines.any { it.id == userLineId } -> userLineId
            else -> -1
        }
        if (target >= 0) {
            st.copy(lines = st.lines.map { if (it.id == target) it.copy(text = it.text + text) else it })
        } else {
            val id = nextLineId++
            userLineId = id
            st.copy(lines = st.lines.map { if (it.open) it.copy(open = false) else it } + ListenLine(id, Speaker.USER, text.trimStart(), open = true))
        }
    }

    private fun appendAi(text: String) = _state.update { st ->
        val last = st.lines.lastOrNull()
        if (last != null && last.speaker == Speaker.AI && last.open) {
            st.copy(lines = st.lines.dropLast(1) + last.copy(text = last.text + text))
        } else {
            st.copy(lines = st.lines.map { if (it.open) it.copy(open = false) else it } + ListenLine(nextLineId++, Speaker.AI, text.trimStart(), open = true))
        }
    }

    private fun closeLines(only: Speaker? = null) = _state.update { st ->
        st.copy(lines = st.lines.map { if (it.open && (only == null || it.speaker == only)) it.copy(open = false) else it })
    }

    // ---------------------------------------------------------------- Audio

    private fun openMic() {
        if (mic != null || !liveUsable()) return
        val m = Microphone { buffer, n ->
            val st = _state.value
            val send = st.phase.interactive && st.connected && (st.turnMode == TurnMode.AUTO || st.talking)
            if (send) session?.send(LiveProtocol.audio(buffer, n))
        }
        try {
            m.start(scope)
            mic = m
        } catch (e: Exception) {
            notes += "Micrófono: ${e.message}"
            showMessage(e.message ?: "No se pudo abrir el micrófono.")
        }
    }

    private fun closeMic() {
        val m = mic ?: return
        m.stop()
        mic = null
        val st = _state.value
        if (st.turnMode == TurnMode.AUTO && st.connected) session?.send(LiveProtocol.audioStreamEnd())
    }

    private fun earcon(e: Earcon) {
        voice?.play(Earcons.render(e, VoiceOut.RATE))
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(250)
                val st = _state.value
                if (!st.phase.active || st.phase == ListenPhase.CLOSING) break
                val now = System.currentTimeMillis()
                val elapsed = ((now - startedAtMs) / 1000).toInt()
                val speaking = (voice?.pendingMs() ?: 0) > 0
                _state.update {
                    it.copy(
                        elapsedSec = elapsed, aiSpeaking = speaking,
                        gapLeft = if (it.phase == ListenPhase.GAP) ((gapUntil - now + 999) / 1000).toInt().coerceAtLeast(0) else 0,
                    )
                }
                // Tono suave mientras carga: sin mirar la pantalla sabes que no se colgó.
                if ((st.phase == ListenPhase.PREPARING || st.buffering) && now - lastWorkingTone > 2_500) {
                    lastWorkingTone = now
                    earcon(Earcon.WORKING)
                }
                if (st.phase == ListenPhase.GAP && now >= gapUntil) playSegment(cursor)
                // Seguir/repetir/terminar esperan a que la IA termine su frase.
                pending?.let { p ->
                    val quiet = !st.talking && !speaking && now - lastAiAudioAt > 700
                    if (quiet && (turnCompleteAt >= pendingAt || now - pendingAt > 5_000)) {
                        pending = null
                        runPending(p)
                    }
                }
                // Por si la IA no llama a finish_listening tras la última respuesta de comprensión.
                val script = st.script
                if (st.phase == ListenPhase.COMPREHENSION && script != null && script.comprehension.isNotEmpty() &&
                    st.comprehension.size >= script.comprehension.size
                ) {
                    if (allAnsweredAt == 0L) allAnsweredAt = now
                    if (!speaking && now - allAnsweredAt > 15_000) finish("Terminó la escucha.")
                }
                if (elapsed >= hardCapSec) finish("Se cumplió el tiempo.")
            }
        }
    }

    // ---------------------------------------------------------------- Fin

    /** Termina, guarda la sesión y deja [ListenState.finishedId] para abrir la revisión. */
    fun finish(reason: String = "Terminaste la escucha.", error: Boolean = false) {
        val st = _state.value
        if (!st.phase.active || st.phase == ListenPhase.CLOSING) return
        closing = true
        _state.update { it.copy(phase = ListenPhase.CLOSING, status = "Guardando…", talking = false) }
        ticker?.cancel()
        jobs.forEach { it.cancel() }
        jobs.clear()
        player?.stop()
        mic?.stop()
        mic = null
        session?.close()
        session = null
        scope.launch {
            voice?.flush()
            earcon(if (error) Earcon.ERROR else Earcon.END)
            delay(1_100)
            releaseAudio()
            val record = buildRecord(_state.value, reason)
            if (record == null) {
                _state.value = ListenState(error = (listOf(reason) + notes.filter { it != reason }).joinToString("\n"))
            } else {
                // Se guarda antes de avisar: la revisión lo abre desde el archivo.
                c.appScope.launch { c.conversations.save(record) }.join()
                _state.value = ListenState(phase = ListenPhase.DONE, finishedId = record.id)
            }
        }
    }

    private fun buildRecord(st: ListenState, reason: String): ConversationRecord? {
        val script = st.script ?: return null
        val lines = st.lines.filter { it.text.isNotBlank() }.map { Line(it.speaker, it.text.trim()) }
        if (heardSegments == 0 && lines.isEmpty()) return null
        val s = c.settings.state.value
        val extra = buildList {
            add("Guion: $scriptModel${if (searched) " con búsqueda" else ""}")
            if (script.sources.isNotEmpty()) add("Fuentes: ${script.sources.joinToString(" · ")}")
        }
        return ConversationRecord(
            id = startedAtMs,
            startedAt = startedAtMs,
            durationSec = ((System.currentTimeMillis() - startedAtMs) / 1000).toInt(),
            model = s.voiceModel,
            turnMode = st.turnMode,
            topic = st.topic.ifBlank { script.titleEs.ifBlank { script.title } },
            lines = lines,
            targets = st.targets,
            highlighted = highlighted.toList(),
            lookups = lookups.toList(),
            tokensTotal = tokensTotal,
            lastPromptTokens = lastPromptTokens,
            reconnections = reconnections,
            notes = notes.toList() + extra,
            endReason = reason,
            mode = RecordMode.LISTEN,
            scriptRaw = ListenScriptParser.encode(script).toString(),
            comprehension = st.comprehension,
            heard = st.heard,
            segmentsHeard = heardSegments,
            repeats = repeats,
            pauses = pauses,
            narrator = narratorsUsed.joinToString("+"),
        )
    }

    private fun releaseAudio() {
        player?.release()
        player = null
        voice?.release()
        voice = null
        systemVoice?.release()
        systemVoice = null
        abandonAudioFocus()
    }

    /** Pausa la música mientras escuchas; una llamada o la alarma pausan la narración. */
    private fun requestAudioFocus() {
        val am = c.app.getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    scope.launch {
                        if (_state.value.talking) stopTalking(tone = false)
                        pause()
                    }
                }
            }
            .build()
        runCatching { am.requestAudioFocus(request) }
        focusRequest = request
    }

    private fun abandonAudioFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        runCatching { c.app.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request) }
    }

    companion object {
        /** Entre segmentos en un mismo pedido al TTS: silencio largo para poder cortar el audio ahí. */
        private const val PAUSE_BETWEEN = "\n<long pause> <long pause>\n"

        internal fun syllables(text: String) = text.count { it in '가'..'힣' }.coerceAtLeast(1)

        /** Palabras del guion para la transcripción de lo que preguntas (las más repetidas primero). */
        internal fun scriptWords(script: ListenScript): List<String> =
            Regex("[가-힣]{2,}").findAll(script.segments.joinToString(" ") { it.ko })
                .map { it.value }
                .groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }
                .map { it.key }
                .take(LiveProtocol.MAX_VOCABULARY)
    }
}
