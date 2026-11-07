package com.jorge.koreandiary.ui.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jorge.koreandiary.AppContainer
import com.jorge.koreandiary.ai.CorrectionRequest
import com.jorge.koreandiary.ai.HowToSayParser
import com.jorge.koreandiary.ai.HowToSayResponse
import com.jorge.koreandiary.ai.ParseException
import com.jorge.koreandiary.ai.PromptItem
import com.jorge.koreandiary.ai.ProviderException
import com.jorge.koreandiary.conversation.ConversationMath
import com.jorge.koreandiary.conversation.ConversationParser
import com.jorge.koreandiary.conversation.ConversationPrompts
import com.jorge.koreandiary.conversation.ConversationRecord
import com.jorge.koreandiary.conversation.Drill
import com.jorge.koreandiary.conversation.DrillItem
import com.jorge.koreandiary.conversation.DrillResult
import com.jorge.koreandiary.conversation.ConversationReview
import com.jorge.koreandiary.conversation.Hint
import com.jorge.koreandiary.conversation.ItemExplanation
import com.jorge.koreandiary.conversation.Line
import com.jorge.koreandiary.conversation.SentenceGloss
import com.jorge.koreandiary.conversation.Speaker
import com.jorge.koreandiary.conversation.WordGloss
import com.jorge.koreandiary.data.ItemRef
import com.jorge.koreandiary.data.db.ItemType
import com.jorge.koreandiary.settings.ProviderId
import com.jorge.koreandiary.settings.SecretStore
import com.jorge.koreandiary.voice.KoreanSpeech
import com.jorge.koreandiary.voice.LiveConfig
import com.jorge.koreandiary.voice.LiveEvent
import com.jorge.koreandiary.voice.LiveProtocol
import com.jorge.koreandiary.voice.LiveSession
import com.jorge.koreandiary.voice.Microphone
import com.jorge.koreandiary.voice.SessionSignal
import com.jorge.koreandiary.voice.Speaker as AudioOut
import com.jorge.koreandiary.voice.TurnMode
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate

sealed interface ConvStage {
    data object Setup : ConvStage
    data object Live : ConvStage
    data object Review : ConvStage
}

object Topic {
    const val FREE = "libre"
    const val DIARY = "diario"
    const val OTHER = "otro"
}

/** Una línea en pantalla. [open] = todavía llega texto de la transcripción. */
data class UiLine(val id: Int, val speaker: Speaker, val text: String, val open: Boolean)

/** Panel del coach, encima de los botones. */
sealed interface CoachPanel {
    data object None : CoachPanel
    data class Loading(val what: String) : CoachPanel
    /** [level]: 1 = palabra o patrón, 2 = media oración, 3 = oración completa. */
    data class HintShown(val hint: Hint, val level: Int, val auto: Boolean = false) : CoachPanel
    data class WordShown(val gloss: WordGloss) : CoachPanel
    data class SentenceShown(val gloss: SentenceGloss) : CoachPanel
    /** Un objetivo de repaso: lo que dice tu lista y, si la pides, la explicación de la IA. */
    data class TargetShown(val item: PromptItem, val detail: String?, val explanation: ItemExplanation? = null) : CoachPanel
    /** "¿Cómo digo?": escribes o dictas en español lo que quieres decir. */
    data class HowToInput(val text: String = "", val listening: Boolean = false, val error: String? = null) : CoachPanel
    data class HowToShown(val idea: String, val answer: HowToSayResponse) : CoachPanel
    data class Error(val message: String) : CoachPanel
}

/** Práctica en voz alta de las correcciones, una por una. */
data class DrillState(
    val items: List<DrillItem>,
    val index: Int = 0,
    val listening: Boolean = false,
    val heard: String = "",
    val result: DrillResult? = null,
    val error: String? = null,
    /** Índices que ya dijiste bien (cuentan una vez aunque repitas). */
    val passed: Set<Int> = emptySet(),
) {
    val current get() = items.getOrNull(index)
}

data class ConvUiState(
    val stage: ConvStage = ConvStage.Setup,
    // Preparación
    val loadingTargets: Boolean = true,
    val targets: List<PromptItem> = emptyList(),
    val excluded: Set<String> = emptySet(),
    /** Significado y uso de cada objetivo según tu lista o el catálogo, por código ("w12"). */
    val details: Map<String, String> = emptyMap(),
    val diaryToday: String? = null,
    val topic: String = Topic.FREE,
    val customTopic: String = "",
    val minutes: Int = 10,
    val secondsToday: Int = 0,
    val hasVoiceKey: Boolean = false,
    val hasCoach: Boolean = false,
    // En vivo
    val status: String = "",
    val connected: Boolean = false,
    val lines: List<UiLine> = emptyList(),
    val talking: Boolean = false,
    val muted: Boolean = false,
    val aiSpeaking: Boolean = false,
    val elapsedSec: Int = 0,
    val totalSec: Int = 0,
    val panel: CoachPanel = CoachPanel.None,
    val tokensTotal: Long = 0,
    val contextTokens: Int = 0,
    val turnMode: String = TurnMode.PUSH_TO_TALK,
    /** Objetivo que la IA acaba de pedir con su pregunta: flota en pantalla hasta que respondas. */
    val floating: String? = null,
    // Revisión
    val record: ConversationRecord? = null,
    val edits: Map<Int, String> = emptyMap(),
    val reviewing: Boolean = false,
    val review: ConversationReview? = null,
    val reviewError: String? = null,
    val drill: DrillState? = null,
    val message: String? = null,
) {
    val activeTargets get() = targets.filter { it.ref !in excluded }
}

class ConversationViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ConvUiState())
    val state: StateFlow<ConvUiState> = _state.asStateFlow()

    private var session: LiveSession? = null
    private var mic: Microphone? = null
    private var speaker: AudioOut? = null
    private var ticker: Job? = null
    private var record: ConversationRecord? = null
    private var startedAtMs = 0L
    private var nextLineId = 0
    private var wrapUpSent = false
    private var ending = false
    private var lookups = mutableListOf<String>()
    private var askedHowTo = mutableListOf<String>()
    private var hintsUsed = 0
    private var autoHints = 0
    /** Pista automática: desde cuándo la IA terminó y tú no respondes, y para qué línea ya se pidió. */
    private var quietSince = 0L
    private var autoHintLineId = -1
    private val speech by lazy { KoreanSpeech(c.app) }
    private var reconnections = 0
    private var highlighted = mutableListOf<String>()
    /** Id de la primera línea creada después de que el objetivo empezó a flotar. */
    private var floatingSince = Int.MAX_VALUE
    /**
     * Línea tuya del intercambio actual. La transcripción de lo que dices a veces llega tarde,
     * cuando la IA ya empezó a responder: así se suma a tu línea y no aparece después de la IA.
     */
    private var userLineId = -1
    private var notes = mutableListOf<String>()
    private var coachJob: Job? = null
    /** Guardado pendiente: la pantalla de inicio lo espera para contar bien los minutos de hoy. */
    private var saveJob: Job? = null
    private var focusRequest: AudioFocusRequest? = null

    init {
        val s = c.settings.state.value
        _state.update { it.copy(minutes = s.voiceSessionMinutes, turnMode = s.voiceTurnMode) }
    }

    /**
     * Cada vez que entras a la pantalla. El ViewModel vive mientras la app esté abierta, así que sin
     * esto verías los objetivos y los minutos de hoy de la última vez.
     */
    fun onEnter() {
        if (_state.value.stage == ConvStage.Setup) loadSetup()
    }

    // ---------- Preparación ----------

    fun loadSetup() {
        viewModelScope.launch {
            val s = c.settings.state.value
            _state.update {
                it.copy(
                    hasVoiceKey = c.isConfigured(ProviderId.GEMINI),
                    hasCoach = c.coachProvider() != null,
                    turnMode = s.voiceTurnMode,
                )
            }
            saveJob?.join()
            c.conversations.refresh()
            val today = LocalDate.now()
            val todaySec = ConversationMath.stats(c.conversations.sessions.value, today).secondsToday
            val diary = c.diary.pageSummaries.first()
                .filter { it.date == today.toString() }
                .mapNotNull { it.preview?.takeIf { p -> p.isNotBlank() } }
                .joinToString("\n")
                .ifBlank { null }
            // La lista de estudio solo se lee: los objetivos salen de FSRS igual que en el diario.
            val targets = runCatching {
                val sug = c.suggestions.suggest(
                    System.currentTimeMillis(), s.grammarLevelList, s.standardLevel,
                    wordCount = 4, grammarCount = 2, newWordsPerPage = 1, gapWordCount = 0, gapGrammarCount = 1,
                )
                val refs = sug.words.map { ItemRef(ItemType.WORD, it.id) } +
                    sug.grammar.map { ItemRef(ItemType.GRAMMAR, it.id) } +
                    sug.gapGrammar.map { ItemRef(ItemType.STD_GRAMMAR, it.id.toLong()) }
                c.study.promptItems(refs) to c.study.details(refs)
            }.getOrDefault(emptyList<PromptItem>() to emptyMap())
            _state.update {
                it.copy(
                    loadingTargets = false, targets = targets.first, details = targets.second,
                    diaryToday = diary, secondsToday = todaySec,
                    topic = if (diary != null && it.topic == Topic.FREE) Topic.DIARY else it.topic,
                )
            }
        }
    }

    fun setTopic(topic: String) = _state.update { it.copy(topic = topic) }
    fun setCustomTopic(text: String) = _state.update { it.copy(customTopic = text) }
    fun setMinutes(m: Int) = _state.update { it.copy(minutes = m) }
    fun toggleTarget(ref: String) = _state.update {
        it.copy(excluded = if (ref in it.excluded) it.excluded - ref else it.excluded + ref)
    }
    fun dismissMessage() = _state.update { it.copy(message = null) }
    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    /** Empieza la sesión. Llamar con el permiso de micrófono ya concedido. */
    fun start() {
        if (_state.value.stage == ConvStage.Live) return
        val s = c.settings.state.value
        val st = _state.value
        val key = c.secrets.get(SecretStore.GEMINI_KEY)
        if (key == null) {
            _state.update { it.copy(message = "Falta tu clave de Gemini: pégala en Ajustes (es la misma del diario).") }
            return
        }
        val remaining = s.voiceDailyMinutes * 60 - st.secondsToday
        if (remaining < 60) {
            _state.update { it.copy(message = "Llegaste a tu límite de ${s.voiceDailyMinutes} min por hoy. Puedes subirlo en Ajustes.") }
            return
        }
        val total = minOf(st.minutes * 60, remaining)
        val topic = when (st.topic) {
            Topic.DIARY -> "what he wrote in his diary today"
            Topic.OTHER -> st.customTopic.trim().ifBlank { "" }
            else -> ""
        }
        val diary = if (st.topic == Topic.DIARY) st.diaryToday else null
        val targets = st.activeTargets
        val system = ConversationPrompts.voiceSystem(s.profile, s.level, s.standardLevel, topic, diary, targets, total / 60, s.voiceCorrection)
        val vocabulary = ConversationPrompts.vocabulary(targets, st.diaryToday)

        val now = System.currentTimeMillis()
        record = ConversationRecord(
            id = now, startedAt = now, model = s.voiceModel, turnMode = s.voiceTurnMode,
            topic = when (st.topic) { Topic.DIARY -> "Diario de hoy"; Topic.OTHER -> st.customTopic.trim(); else -> "Libre" },
            targets = targets,
        )
        startedAtMs = now
        nextLineId = 0
        wrapUpSent = false
        ending = false
        lookups = mutableListOf()
        askedHowTo = mutableListOf()
        hintsUsed = 0
        autoHints = 0
        quietSince = 0L
        autoHintLineId = -1
        reconnections = 0
        highlighted = mutableListOf()
        floatingSince = Int.MAX_VALUE
        userLineId = -1
        notes = mutableListOf()

        speaker = try {
            AudioOut(viewModelScope)
        } catch (e: Exception) {
            _state.update { it.copy(message = "No se pudo abrir el audio del celular: ${e.message}") }
            return
        }
        val live = LiveSession(key, viewModelScope) { handle, fallback ->
            LiveConfig(
                model = s.voiceModel, voice = s.voiceName, systemInstruction = system,
                turnMode = s.voiceTurnMode, silenceMs = s.voiceSilenceMs,
                vocabulary = vocabulary, resumeHandle = handle, fallback = fallback,
                highlightTool = targets.isNotEmpty(),
            )
        }
        session = live
        _state.update {
            it.copy(
                stage = ConvStage.Live, status = "Conectando…", connected = false, lines = emptyList(),
                talking = false, muted = false, aiSpeaking = false, elapsedSec = 0, totalSec = total,
                panel = CoachPanel.None, tokensTotal = 0, contextTokens = 0, turnMode = s.voiceTurnMode, floating = null,
                review = null, reviewError = null, record = null, message = null,
            )
        }
        viewModelScope.launch { live.events.collect { onSignal(it) } }
        requestAudioFocus()
        live.start()
        startTicker()
    }

    // ---------- En vivo ----------

    private fun onSignal(signal: SessionSignal) {
        when (signal) {
            is SessionSignal.Connected -> {
                _state.update { it.copy(status = "En vivo", connected = true) }
                if (!signal.resumed) {
                    startMic()
                    // Sin esto el modelo espera a que hables primero.
                    session?.send(LiveProtocol.context("[APP] Empieza la conversación.", respond = true))
                } else {
                    reconnections++
                    // La reconexión cerró tu turno a medias: se vuelve a abrir.
                    if (_state.value.talking) session?.send(LiveProtocol.activityStart())
                }
            }
            SessionSignal.Reconnecting -> _state.update { it.copy(status = "Reconectando…", connected = false) }
            is SessionSignal.Fallback -> {
                val what = if (signal.level == 1) "sin idiomas ni vocabulario en la transcripción" else "sin la función de objetivos que flotan"
                notes += "Gemini rechazó la configuración; se reintentó $what. Motivo: ${signal.reason}"
            }
            is SessionSignal.Closed -> if (!ending) end(if (signal.error) "Se cortó: ${signal.reason}" else signal.reason)
            is SessionSignal.Event -> onEvent(signal.event)
        }
    }

    private fun onEvent(e: LiveEvent) {
        when (e) {
            // Mientras hablas (pulsar para hablar) se descarta lo que quedaba en camino de la respuesta anterior.
            is LiveEvent.Audio -> if (!_state.value.talking) speaker?.play(e.pcm)
            is LiveEvent.InputText -> appendUser(e.text)
            is LiveEvent.OutputText -> if (!_state.value.talking) appendAi(e.text)
            LiveEvent.Interrupted -> {
                speaker?.flush()
                userLineId = -1
                closeLines()
            }
            LiveEvent.TurnComplete -> {
                userLineId = -1
                closeLines(Speaker.AI)
                // El objetivo deja de flotar cuando respondiste y la IA terminó de contestarte.
                val st = _state.value
                if (st.floating != null && st.lines.any { it.speaker == Speaker.USER && it.id >= floatingSince && it.text.isNotBlank() }) {
                    _state.update { it.copy(floating = null) }
                    floatingSince = Int.MAX_VALUE
                }
            }
            is LiveEvent.ToolCall -> {
                session?.send(LiveProtocol.toolResponse(e.id, e.name))
                val ref = e.args["ref"]?.trim()
                if (e.name == LiveProtocol.TOOL_HIGHLIGHT && ref != null && _state.value.targets.any { it.ref == ref }) {
                    highlighted += ref
                    floatingSince = nextLineId
                    _state.update { it.copy(floating = ref) }
                }
            }
            is LiveEvent.Usage -> _state.update {
                it.copy(tokensTotal = it.tokensTotal + e.total, contextTokens = maxOf(e.prompt, 0))
            }
            else -> Unit
        }
    }

    /** Lo que dices llega en trozos: se suma a tu línea del intercambio actual, aunque la IA ya responda. */
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
            st.copy(lines = st.lines.map { if (it.open) it.copy(open = false) else it } + UiLine(id, Speaker.USER, text.trimStart(), open = true))
        }
    }

    private fun appendAi(text: String) = _state.update { st ->
        val last = st.lines.lastOrNull()
        if (last != null && last.speaker == Speaker.AI && last.open) {
            st.copy(lines = st.lines.dropLast(1) + last.copy(text = last.text + text))
        } else {
            st.copy(lines = st.lines.map { if (it.open) it.copy(open = false) else it } + UiLine(nextLineId++, Speaker.AI, text.trimStart(), open = true))
        }
    }

    private fun closeLines(only: Speaker? = null) = _state.update { st ->
        st.copy(lines = st.lines.map { if (it.open && (only == null || it.speaker == only)) it.copy(open = false) else it })
    }

    private fun startMic() {
        if (mic != null) return
        val m = Microphone { buffer, n ->
            val st = _state.value
            val send = if (st.turnMode == TurnMode.PUSH_TO_TALK) st.talking else !st.muted
            if (send && st.connected) session?.send(LiveProtocol.audio(buffer, n))
        }
        try {
            m.start(viewModelScope)
            mic = m
        } catch (e: Exception) {
            _state.update { it.copy(message = e.message ?: "No se pudo abrir el micrófono.") }
            end("Sin micrófono.")
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                delay(250)
                val st = _state.value
                if (st.stage != ConvStage.Live) break
                val elapsed = ((System.currentTimeMillis() - startedAtMs) / 1000).toInt()
                val speaking = (speaker?.pendingMs() ?: 0) > 0
                _state.update { it.copy(elapsedSec = elapsed, aiSpeaking = speaking) }
                checkAutoHint(st, speaking)
                if (!wrapUpSent && elapsed >= st.totalSec - 60) {
                    wrapUpSent = session?.send(LiveProtocol.context(ConversationPrompts.WRAP_UP, respond = false)) == true
                }
                // Al cumplirse el tiempo se espera a que la IA termine de despedirse (máximo un minuto extra).
                if (elapsed >= st.totalSec && (!speaking && !st.talking || elapsed >= st.totalSec + 60)) {
                    end("Se cumplió el tiempo.")
                    break
                }
            }
        }
    }

    /**
     * Pista automática: la IA terminó de hablar, no hay nada tuyo después y pasaron N segundos.
     * Una sola vez por cada intervención de la IA, y nunca si se acabó la cuota del coach.
     */
    private fun checkAutoHint(st: ConvUiState, speaking: Boolean) {
        val s = c.settings.state.value
        val last = st.lines.lastOrNull()
        val waiting = s.voiceAutoHint && st.connected && !speaking && !st.talking && st.panel == CoachPanel.None &&
            st.elapsedSec < st.totalSec - 60 &&
            last != null && last.speaker == Speaker.AI && !last.open && last.id != autoHintLineId
        if (!waiting) {
            quietSince = 0L
            return
        }
        val now = System.currentTimeMillis()
        if (quietSince == 0L) quietSince = now
        if (now - quietSince < s.voiceAutoHintSec * 1000L) return
        autoHintLineId = last!!.id
        quietSince = 0L
        val (providerId, _) = c.coachProvider() ?: return
        if (c.settings.usageToday(providerId) >= s.dailyLimit(providerId)) return
        hint(auto = true)
    }

    /** Pulsar para hablar: abre tu turno. Si la IA estaba hablando, la corta. */
    fun startTalking() {
        val st = _state.value
        if (!st.connected || st.talking) return
        if (session?.send(LiveProtocol.activityStart()) != true) return
        speaker?.flush()
        // Línea tuya vacía desde ya: tu transcripción la llena aunque llegue cuando la IA ya contesta.
        _state.update { cur ->
            val id = nextLineId++
            userLineId = id
            cur.copy(
                talking = true,
                lines = cur.lines.map { if (it.open) it.copy(open = false) else it } + UiLine(id, Speaker.USER, "", open = true),
            )
        }
    }

    fun stopTalking() {
        if (!_state.value.talking) return
        session?.send(LiveProtocol.activityEnd())
        _state.update { it.copy(talking = false) }
    }

    fun toggleMute() {
        val muted = !_state.value.muted
        _state.update { it.copy(muted = muted) }
        if (muted) session?.send(LiveProtocol.audioStreamEnd())
    }

    /** La app pasó a segundo plano: sin servicio en primer plano no se puede seguir grabando. */
    fun onBackground() {
        val st = _state.value
        if (st.stage != ConvStage.Live) return
        if (st.talking) stopTalking()
        if (st.turnMode == TurnMode.AUTO && !st.muted) toggleMute()
    }

    fun slower() {
        if (_state.value.talking) return
        session?.send(LiveProtocol.context(ConversationPrompts.SLOWER, respond = true))
    }

    // ---------- Coach: pistas y traducciones (texto, no corta la conversación) ----------

    fun hint() = hint(auto = false)

    private fun hint(auto: Boolean) {
        val st = _state.value
        val current = st.panel
        if (!auto && current is CoachPanel.HintShown && current.level < 3) {
            _state.update { it.copy(panel = current.copy(level = current.level + 1)) }
            return
        }
        val s = c.settings.state.value
        val recent = st.lines.filter { it.text.isNotBlank() }.map { Line(it.speaker, it.text) }
        if (recent.none { it.speaker == Speaker.AI }) return
        coach("Pensando una pista…", ConversationPrompts.hint(s.level, s.standardLevel, recent, record?.targets.orEmpty())) { raw ->
            val hint = ConversationParser.hint(raw)
            hintsUsed++
            if (auto) autoHints++
            CoachPanel.HintShown(hint, level = 1, auto = auto)
        }
    }

    fun translateLast() {
        val last = _state.value.lines.lastOrNull { it.speaker == Speaker.AI && it.text.isNotBlank() } ?: return
        val s = c.settings.state.value
        coach("Traduciendo…", ConversationPrompts.sentence(last.text, s.standardLevel)) { raw ->
            CoachPanel.SentenceShown(ConversationParser.sentence(raw, last.text))
        }
    }

    fun lookup(word: String, sentence: String) {
        val clean = word.trim { !it.isLetterOrDigit() }
        if (clean.isEmpty()) return
        coach("Buscando «$clean»…", ConversationPrompts.word(clean, sentence)) { raw ->
            val gloss = ConversationParser.word(raw, clean)
            lookups += "${gloss.form}: ${gloss.meaning}"
            // El modelo de voz se entera en silencio y baja el nivel.
            session?.send(LiveProtocol.context(ConversationPrompts.unknownWord(clean), respond = false))
            CoachPanel.WordShown(gloss)
        }
    }

    /** Tocar un objetivo: muestra qué significa y para qué sirve (otra vez lo cierra). */
    fun showTarget(ref: String) {
        val st = _state.value
        val current = st.panel
        if (current is CoachPanel.TargetShown && current.item.ref == ref) {
            closePanel()
            return
        }
        val item = (st.targets + record?.targets.orEmpty()).firstOrNull { it.ref == ref } ?: return
        _state.update { it.copy(panel = CoachPanel.TargetShown(item, st.details[ref]?.takeIf { d -> d.isNotBlank() })) }
    }

    /** Explicación de la IA para el objetivo abierto: significado, uso y un ejemplo. */
    fun explainTarget() {
        val shown = _state.value.panel as? CoachPanel.TargetShown ?: return
        val s = c.settings.state.value
        coach("Explicando «${shown.item.label}»…", ConversationPrompts.item(shown.item.label, shown.item.kind, s.standardLevel)) { raw ->
            shown.copy(explanation = ConversationParser.item(raw))
        }
    }

    // ---------- ¿Cómo digo? (texto o dictado en español, sin depender del modelo de voz) ----------

    fun openHowTo() {
        if (_state.value.talking) stopTalking()
        _state.update { it.copy(panel = CoachPanel.HowToInput()) }
    }

    fun setHowToText(text: String) = _state.update {
        val p = it.panel as? CoachPanel.HowToInput ?: return@update it
        it.copy(panel = p.copy(text = text, error = null))
    }

    /**
     * Dictar la pregunta en español con el reconocimiento de Android. El micrófono de la
     * conversación se suelta mientras tanto (dos grabaciones a la vez suelen fallar).
     */
    fun dictateHowTo() {
        val p = _state.value.panel as? CoachPanel.HowToInput ?: return
        if (p.listening) {
            speech.stopListening()
            return
        }
        if (!speech.recognitionAvailable()) {
            _state.update { it.copy(panel = p.copy(error = "Tu celular no tiene reconocimiento de voz: escríbelo.")) }
            return
        }
        mic?.stop()
        mic = null
        _state.update { it.copy(panel = p.copy(listening = true, error = null)) }
        fun done(transform: (CoachPanel.HowToInput) -> CoachPanel.HowToInput) {
            _state.update { st -> (st.panel as? CoachPanel.HowToInput)?.let { st.copy(panel = transform(it)) } ?: st }
            if (_state.value.stage == ConvStage.Live && !ending) startMic()
        }
        speech.listen(
            onPartial = { text -> setHowToText(text) },
            onResult = { results -> done { it.copy(text = results.first(), listening = false) } },
            onError = { message -> done { it.copy(listening = false, error = message) } },
            language = "es-PE",
        )
    }

    fun askHowTo() {
        val p = _state.value.panel as? CoachPanel.HowToInput ?: return
        val idea = p.text.trim()
        if (idea.isEmpty()) return
        val s = c.settings.state.value
        val recent = _state.value.lines.filter { it.text.isNotBlank() }.map { Line(it.speaker, it.text) }
        coach("Buscando cómo decirlo…", ConversationPrompts.howToSay(idea, s.level, s.standardLevel, recent)) { raw ->
            val answer = HowToSayParser.parse(raw)
            askedHowTo += "$idea → ${answer.mine.text}"
            session?.send(LiveProtocol.context(ConversationPrompts.askedHowTo(idea, answer.mine.text), respond = false))
            CoachPanel.HowToShown(idea, answer)
        }
    }

    /**
     * Escuchar la frase: se la pide al modelo de voz (y no a la voz del celular), así en modo
     * automático el micrófono no la toma como si la hubieras dicho tú, y la IA sabe qué esperar.
     */
    fun hear(text: String) {
        if (_state.value.talking) return
        session?.send(LiveProtocol.context("[APP] Di despacio y claro: «$text». Luego espera a que lo repita.", respond = true))
    }

    fun closePanel() {
        if ((_state.value.panel as? CoachPanel.HowToInput)?.listening == true) {
            speech.stopListening()
            if (mic == null && _state.value.stage == ConvStage.Live && !ending) startMic()
        }
        _state.update { it.copy(panel = CoachPanel.None) }
    }

    private fun coach(loading: String, prompt: String, onResult: (String) -> CoachPanel) {
        val (providerId, provider) = c.coachProvider() ?: run {
            _state.update { it.copy(panel = CoachPanel.Error("Configura Gemini o Azure en Ajustes para usar pistas y traducciones.")) }
            return
        }
        val s = c.settings.state.value
        if (c.settings.usageToday(providerId) >= s.dailyLimit(providerId)) {
            _state.update { it.copy(panel = CoachPanel.Error("Llegaste al límite diario de consultas de ${providerName(providerId)}.")) }
            return
        }
        _state.update { it.copy(panel = CoachPanel.Loading(loading)) }
        // Una consulta a la vez: si pides otra, la anterior se descarta y no pisa la nueva.
        coachJob?.cancel()
        coachJob = viewModelScope.launch {
            val panel = try {
                val raw = provider.complete(CorrectionRequest(prompt))
                c.settings.registerUse(providerId)
                // La llamada HTTP no se puede interrumpir: si mientras tanto pediste otra cosa, se ignora.
                if (!isActive) return@launch
                onResult(raw)
            } catch (e: ProviderException) {
                CoachPanel.Error(if (e.offline) "Sin conexión." else e.message ?: "Error del proveedor.")
            } catch (e: ParseException) {
                CoachPanel.Error("No se pudo leer la respuesta: ${e.message}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                CoachPanel.Error(e.message ?: e.javaClass.simpleName)
            }
            if (isActive) _state.update { it.copy(panel = panel) }
        }
    }

    // ---------- Fin y revisión ----------

    /** Termina la sesión y la guarda. */
    fun end(reason: String = "Terminaste la sesión.") {
        if (ending || _state.value.stage != ConvStage.Live) return
        ending = true
        ticker?.cancel()
        coachJob?.cancel()
        abandonAudioFocus()
        mic?.stop()
        mic = null
        speaker?.release()
        speaker = null
        session?.close()
        session = null
        val st = _state.value
        val base = record ?: return
        val lines = st.lines.filter { it.text.isNotBlank() }.map { Line(it.speaker, it.text.trim()) }
        // Nada que guardar (p. ej. no se pudo conectar): de vuelta a la preparación con el motivo,
        // sin crear una sesión vacía que además contaría para los minutos de hoy.
        if (lines.isEmpty()) {
            val why = (listOf(reason) + notes).joinToString("\n")
            _state.update {
                it.copy(stage = ConvStage.Setup, talking = false, connected = false, floating = null, panel = CoachPanel.None, message = why)
            }
            loadSetup()
            return
        }
        val saved = base.copy(
            durationSec = ((System.currentTimeMillis() - startedAtMs) / 1000).toInt(),
            lines = lines,
            lookups = lookups.toList(),
            askedHowTo = askedHowTo.toList(),
            highlighted = highlighted.toList(),
            hintsUsed = hintsUsed,
            autoHints = autoHints,
            tokensTotal = st.tokensTotal,
            lastPromptTokens = st.contextTokens,
            reconnections = reconnections,
            notes = notes.toList(),
            endReason = reason,
        )
        record = saved
        _state.update {
            it.copy(
                stage = ConvStage.Review, record = saved, edits = editsFor(saved), review = null,
                reviewError = null, reviewing = false, panel = CoachPanel.None, talking = false, connected = false, floating = null,
            )
        }
        // appScope: se guarda aunque la pantalla se cierre en este momento.
        saveJob = c.appScope.launch { c.conversations.save(saved) }
    }

    fun openReview(id: Long) {
        viewModelScope.launch {
            val r = c.conversations.load(id) ?: return@launch
            record = r
            val review = r.reviewRaw?.let { runCatching { ConversationParser.review(it) }.getOrNull() }
            _state.update {
                it.copy(
                    stage = ConvStage.Review, record = r, edits = editsFor(r), review = review,
                    reviewError = null, reviewing = false, hasCoach = c.coachProvider() != null,
                )
            }
        }
    }

    private fun editsFor(r: ConversationRecord): Map<Int, String> =
        r.lines.withIndex().filter { it.value.speaker == Speaker.USER }.associate { it.index to it.value.text }

    fun editLine(index: Int, text: String) = _state.update { it.copy(edits = it.edits + (index to text)) }

    /** Corrige lo que dijiste. Solo guarda la revisión: la lista de estudio no cambia. */
    fun runReview() {
        val st = _state.value
        val r = st.record ?: return
        val (providerId, provider) = c.coachProvider() ?: run {
            _state.update { it.copy(reviewError = "Configura Gemini o Azure en Ajustes para revisar la conversación.") }
            return
        }
        val s = c.settings.state.value
        if (c.settings.usageToday(providerId) >= s.dailyLimit(providerId)) {
            _state.update { it.copy(reviewError = "Llegaste al límite diario de ${providerName(providerId)}.") }
            return
        }
        // Tus correcciones a la transcripción reemplazan lo que entendió el reconocimiento de voz.
        val lines = r.lines.mapIndexed { i, line ->
            if (line.speaker == Speaker.USER) line.copy(text = st.edits[i]?.trim() ?: line.text) else line
        }.filter { it.text.isNotBlank() }
        if (lines.none { it.speaker == Speaker.USER }) {
            _state.update { it.copy(reviewError = "No hay nada tuyo que revisar.") }
            return
        }
        _state.update { it.copy(reviewing = true, reviewError = null) }
        viewModelScope.launch {
            try {
                val prompt = ConversationPrompts.review(s.profile, s.level, s.standardLevel, r.targets, lines)
                val raw = provider.complete(CorrectionRequest(prompt))
                c.settings.registerUse(providerId)
                val review = ConversationParser.review(raw)
                val updated = r.copy(lines = lines, reviewRaw = raw)
                c.conversations.save(updated)
                record = updated
                _state.update { it.copy(reviewing = false, review = review, record = updated, edits = editsFor(updated)) }
            } catch (e: ProviderException) {
                _state.update { it.copy(reviewing = false, reviewError = if (e.offline) "Sin conexión." else e.message) }
            } catch (e: ParseException) {
                _state.update { it.copy(reviewing = false, reviewError = "No se pudo leer la respuesta: ${e.message}") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(reviewing = false, reviewError = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    // ---------- Práctica en voz alta ----------

    /** Empieza a practicar las correcciones de la revisión, diciéndolas en voz alta. */
    fun startDrill() {
        val st = _state.value
        val r = st.record ?: return
        val review = st.review ?: return
        val items = Drill.build(r.lines, review.correction.corrections)
        if (items.isEmpty()) {
            _state.update { it.copy(message = "No hay correcciones para practicar.") }
            return
        }
        if (!speech.recognitionAvailable()) {
            _state.update { it.copy(message = "Tu celular no tiene reconocimiento de voz disponible.") }
            return
        }
        _state.update { it.copy(drill = DrillState(items)) }
    }

    fun drillListen() {
        val d = _state.value.drill ?: return
        val item = d.current ?: return
        if (d.listening) {
            speech.stopListening()
            return
        }
        // Que el micrófono no escuche la voz del celular leyendo la oración.
        speech.stopSpeaking()
        _state.update { it.copy(drill = d.copy(listening = true, heard = "", result = null, error = null)) }
        speech.listen(
            onPartial = { text -> updateDrill { it.copy(heard = text) } },
            onResult = { results ->
                val result = Drill.evaluate(item, results)
                updateDrill {
                    it.copy(
                        listening = false,
                        heard = results.first(),
                        result = result,
                        passed = if (result == DrillResult.OK) it.passed + it.index else it.passed,
                    )
                }
            },
            onError = { message -> updateDrill { it.copy(listening = false, error = message) } },
        )
    }

    /** Escuchar la oración corregida con la voz coreana del celular. */
    fun drillHear() {
        _state.value.drill?.current?.let { speech.say(it.target) }
    }

    fun drillNext() {
        val d = _state.value.drill ?: return
        speech.stopListening()
        if (d.index + 1 < d.items.size) {
            updateDrill { it.copy(index = it.index + 1, listening = false, heard = "", result = null, error = null) }
        } else {
            finishDrill(d)
        }
    }

    fun closeDrill() {
        _state.value.drill?.let { finishDrill(it) }
    }

    private fun finishDrill(d: DrillState) {
        speech.stopListening()
        val r = _state.value.record
        _state.update {
            it.copy(drill = null, message = "Dijiste bien ${d.passed.size} de ${d.items.size} correcciones.")
        }
        if (r != null && d.passed.isNotEmpty()) {
            // Se guarda el mejor resultado.
            val updated = r.copy(drillPassed = maxOf(r.drillPassed, d.passed.size), drillTotal = d.items.size)
            record = updated
            _state.update { it.copy(record = updated) }
            c.appScope.launch { c.conversations.save(updated) }
        }
    }

    private fun updateDrill(transform: (DrillState) -> DrillState) =
        _state.update { st -> st.drill?.let { st.copy(drill = transform(it)) } ?: st }

    fun deleteSession(id: Long) {
        viewModelScope.launch {
            c.conversations.delete(id)
            backToSetup()
        }
    }

    fun backToSetup() {
        _state.value.drill?.let { speech.stopListening() }
        _state.update {
            it.copy(stage = ConvStage.Setup, record = null, review = null, reviewError = null, panel = CoachPanel.None, drill = null, message = null)
        }
        loadSetup()
    }

    /** Pausa la música o los podcasts mientras conversas (y los reanuda al terminar). */
    private fun requestAudioFocus() {
        val am = c.app.getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .build()
        runCatching { am.requestAudioFocus(request) }
        focusRequest = request
    }

    private fun abandonAudioFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        runCatching { c.app.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request) }
    }

    private fun providerName(id: String) = when (id) {
        ProviderId.GEMINI -> "Gemini"
        ProviderId.AZURE -> "Azure OpenAI"
        else -> id
    }

    override fun onCleared() {
        if (_state.value.stage == ConvStage.Live) end("Saliste de la pantalla.")
        mic?.stop()
        speaker?.release()
        session?.close()
        abandonAudioFocus()
        speech.release()
    }
}
