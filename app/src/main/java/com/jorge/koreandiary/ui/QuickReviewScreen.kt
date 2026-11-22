package com.jorge.koreandiary.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jorge.koreandiary.AppContainer
import com.jorge.koreandiary.BuildConfig
import com.jorge.koreandiary.ai.CorrectionRequest
import com.jorge.koreandiary.ai.Prompts
import com.jorge.koreandiary.ai.ItemExplanation
import com.jorge.koreandiary.ai.ExplainParser
import com.jorge.koreandiary.ai.ParseException
import com.jorge.koreandiary.ai.ProviderException
import com.jorge.koreandiary.conversation.ConversationParser
import com.jorge.koreandiary.conversation.ConversationPrompts
import com.jorge.koreandiary.conversation.WordGloss
import com.jorge.koreandiary.data.ExplanationStore
import com.jorge.koreandiary.ink.HandwritingRecognizer
import com.jorge.koreandiary.ink.InkCanvas
import com.jorge.koreandiary.ink.InkStroke
import com.jorge.koreandiary.ink.InkTool
import com.jorge.koreandiary.review.Cloze
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.jorge.koreandiary.dictionary.WordLookupResult
import com.jorge.koreandiary.dictionary.DictionaryLookup
import com.jorge.koreandiary.review.ClozeCard
import com.jorge.koreandiary.review.DictionaryCloze
import com.jorge.koreandiary.review.ClozeKind
import com.jorge.koreandiary.srs.Fsrs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Cómo terminó la respuesta de una tarjeta. */
enum class ClozeOutcome { RIGHT, WRONG, REVEALED }

/**
 * Una palabra que tocaste en la oración. Primero el diccionario (sin IA); si no la tiene, la IA,
 * marcada «según la IA» (decidido con Jorge el 2026-11-22).
 */
data class WordLookup(
    val word: String,
    /** Lo que dice el 한국어기초사전; null mientras busca o si no la tiene. */
    val dict: WordLookupResult? = null,
    /** Lo que dijo la IA cuando el diccionario no la tiene. */
    val gloss: WordGloss? = null,
    /** La forma que dio la IA sí está en el diccionario (solo así se puede agregar). */
    val glossKnown: Boolean = false,
    val loading: Boolean = true,
    val note: String? = null,
    /** Formas de diccionario que ya están en tu lista. */
    val inList: Set<String> = emptySet(),
    /** Las que acabas de agregar desde aquí. */
    val added: Set<String> = emptySet(),
    /** De qué partes se forma una palabra nativa (el diccionario no lo dice), según la IA. */
    val aiParts: String? = null,
    val aiPartsLoading: Boolean = false,
) {
    /** Se pueden pedir las partes a la IA: nativa (sin origen en el diccionario) y de más de una sílaba. */
    val canAskParts: Boolean
        get() = dict?.entries?.firstOrNull()?.let { e -> e.origin == null && !e.entry.grammar && e.entry.form.removeSuffix("다").length > 1 } == true
}

data class QuickReviewState(
    val loading: Boolean = true,
    val cards: List<ClozeCard> = emptyList(),
    val index: Int = 0,
    /** Pistas abiertas en la tarjeta (0 a 3). */
    val hints: Int = 0,
    /** Respuestas que no coincidieron antes de acertar. */
    val wrong: Int = 0,
    val outcome: ClozeOutcome? = null,
    /** La calificación con que se cerró la tarjeta: el mensaje sale de ella, no de los fallos. */
    val rating: Fsrs.Rating? = null,
    /** Lo último que escribiste, tal como lo leyó la app. */
    val read: String? = null,
    /** Fallaste pero con la misma palabra y otra terminación (먹으면서 por 먹는 동안): qué cambia. */
    val nearMiss: String? = null,
    /**
     * Después de «Ver respuesta» el recuadro queda para copiarla a mano: si la comprobaste, si
     * coincidió (null = no la comprobaste). Es solo práctica: la calificación ya quedó.
     */
    val copied: Boolean? = null,
    val checking: Boolean = false,
    val strokes: List<InkStroke> = emptyList(),
    /** Una nota arriba (sin IA, error); no frena el repaso. */
    val note: String? = null,
    /** Mensaje cuando no hay tarjetas. */
    val empty: String? = null,
    /** «Más sobre esto»: la explicación del ítem de la tarjeta cerrada, mientras llega y si falla. */
    val explain: ItemExplanation? = null,
    val explaining: Boolean = false,
    val explainNote: String? = null,
    /** «¿De qué partes se forma?» en «Más sobre esto» (palabra nativa): lo que dijo la IA. */
    val explainParts: String? = null,
    val explainPartsLoading: Boolean = false,
    /** La palabra de la oración que tocaste, si hay una abierta. */
    val lookup: WordLookup? = null,
    val tally: Map<Fsrs.Rating, Int> = emptyMap(),
    /** «Traducir» en una tarjeta que quedó sin traducción: lo que tradujo la IA, por tarjeta. */
    val translations: Map<String, String> = emptyMap(),
    /** La tarjeta que se está traduciendo. */
    val translating: String? = null,
    val translateNote: String? = null,
) {
    val card: ClozeCard? get() = cards.getOrNull(index)
    val done: Boolean get() = !loading && cards.isNotEmpty() && index >= cards.size
}

/**
 * Repaso rápido: arma la sesión (tus oraciones, oraciones del diccionario para lo demás y, con IA, una
 * sola llamada para escribir las que faltan), lee lo que escribes a mano y califica cada tarjeta al
 * cerrarla: si sales a la mitad, lo hecho ya contó.
 */
class QuickReviewViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(QuickReviewState())
    val state = _state.asStateFlow()

    init { start() }

    fun start() {
        _state.value = QuickReviewState()
        viewModelScope.launch {
            val settings = c.settings.state.value
            val now = System.currentTimeMillis()
            val targets = c.study.quickReviewTargets(now, settings.grammarLevelList)
            if (targets.isEmpty()) {
                // Sin lista no hay qué repasar; con lista, es que ya repasaste todo lo de hoy (F8 de las pruebas).
                val empty = if (c.study.hasItems.first()) "Ya repasaste todo lo que tocaba hoy. Vuelve mañana, o úsalo escribiendo en el diario."
                else "Todavía no hay nada para repasar: importa tu lista o escribe en el diario."
                _state.update { it.copy(loading = false, empty = empty) }
                return@launch
            }
            // Primero tus oraciones; para lo demás, una del diccionario; la IA solo escribe las que faltan.
            val (own, fromDictionary) = withContext(Dispatchers.Default) {
                // En tus oraciones de gramática, el verbo al que va unida (먹는 → 먹다), para «Usa:».
                val mine = Cloze.localCards(targets).map { card ->
                    if (card.base != null) card else card.copy(base = runCatching { DictionaryCloze.base(c.dictionary, card) }.getOrNull())
                }
                mine to targets.filter { !it.own }.mapNotNull { t -> runCatching { DictionaryCloze.card(c.dictionary, t) }.getOrNull() }
            }
            val missing = targets.filter { t -> !t.own && fromDictionary.none { it.code == t.code } }
            val given = own + fromDictionary
            // Una sola llamada traduce todas (con lo del hueco resaltado), dice qué expresa cada gramática
            // y escribe las que faltan. Con el modelo de las explicaciones: una traducción mal resaltada
            // confunde más de lo que ayuda (pedido por Jorge el 2026-11-22).
            val provider = c.explainProvider()
            var note: String? = null
            val lost = if (missing.isEmpty()) "" else ", y ${missing.size} sin oración tuya ni del diccionario quedaron fuera"
            val cards: List<ClozeCard> = when {
                provider == null -> given.also { note = "Sin IA las oraciones van sin traducción$lost. Con Gemini (Ajustes › IA) se traducen." }
                c.settings.usageToday(provider.first) >= settings.dailyLimit(provider.first) -> given.also {
                    note = "Llegaste al límite diario de la IA: las oraciones van sin traducción$lost."
                }
                else -> try {
                    val raw = provider.second.complete(CorrectionRequest(Cloze.prompt(settings.profile, settings.level, settings.standardLevel, given, missing)))
                    c.settings.registerUse(provider.first)
                    Cloze.parse(raw, given, missing)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    note = when (e) {
                        is ProviderException -> c.explainError(e, provider.first)
                        is ParseException -> "No se pudo leer la respuesta de la IA"
                        else -> e.message ?: e.javaClass.simpleName
                    }.trimEnd('.') + ": las oraciones van sin traducción$lost."
                    given
                }
            }
            _state.update {
                it.copy(
                    loading = false, cards = cards, note = note,
                    empty = if (cards.isEmpty()) "No encontré oraciones con lo que te toca repasar. Con la IA configurada, ella las escribe." else null,
                )
            }
        }
    }

    fun addStroke(stroke: InkStroke) = _state.update { it.copy(strokes = it.strokes + stroke) }

    fun clearInk() = _state.update { it.copy(strokes = emptyList(), read = if (it.outcome == ClozeOutcome.WRONG) it.read else null, copied = null) }

    fun hint() = _state.update { if (it.outcome == null || it.outcome == ClozeOutcome.WRONG) it.copy(hints = (it.hints + 1).coerceAtMost(3)) else it }

    /**
     * Lee lo escrito a mano ([lineHeight] = alto del recuadro, así todo cae en un renglón) y lo compara.
     * Con la respuesta ya vista, comprueba la copia sin tocar la calificación.
     */
    fun checkInk(lineHeight: Float, width: Float) {
        val s = _state.value
        val card = s.card ?: return
        if (s.checking) return
        if (s.strokes.isEmpty()) {
            _state.update { it.copy(note = "Escribe la respuesta en el recuadro primero.") }
            return
        }
        _state.update { it.copy(checking = true) }
        // La tarjeta de este pedido: leer la letra tarda (la primera vez baja el modelo) y mientras
        // tanto puedes pulsar «Ver respuesta» o «Siguiente». Lo leído solo vale para esta tarjeta y
        // solo si sigue abierta; antes caía en la que estuviera en pantalla y la calificaba.
        val index = s.index
        val copying = s.outcome == ClozeOutcome.REVEALED
        fun stillOpen() = _state.value.let {
            it.index == index && it.card === card &&
                (if (copying) it.outcome == ClozeOutcome.REVEALED else it.outcome != ClozeOutcome.RIGHT && it.outcome != ClozeOutcome.REVEALED)
        }
        viewModelScope.launch {
            val candidates = try {
                if (!c.recognizer.isModelDownloaded()) c.recognizer.downloadModel()
                // Lo que va antes del hueco como contexto: sin él leyó «국귀요» donde decía 꿈꿔요.
                val lines = c.recognizer.recognizeLines(s.strokes, lineHeight, width, preContext = card.before)
                listOf(lines.joinToString("") { it.text }) + lines.firstOrNull()?.alternatives.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    if (it.index != index) it
                    else it.copy(checking = false, note = HandwritingRecognizer.inkMessage(e) ?: "No pude leer tu letra: ${e.message ?: e.javaClass.simpleName}")
                }
                return@launch
            }
            if (!stillOpen()) {
                _state.update { if (it.index == index) it.copy(checking = false) else it }
                return@launch
            }
            judge(card, candidates.filter { it.isNotBlank() })
        }
    }

    fun checkTyped(text: String) {
        val card = _state.value.card ?: return
        if (text.isBlank()) {
            _state.update { it.copy(note = "Escribe la respuesta primero.") }
            return
        }
        judge(card, listOf(text))
    }

    private fun judge(card: ClozeCard, candidates: List<String>) {
        if (_state.value.outcome == ClozeOutcome.REVEALED) {
            // La copia después de verla: solo dice si quedó bien escrita.
            _state.update { it.copy(checking = false, read = candidates.firstOrNull() ?: "(nada)", copied = Cloze.matches(card, candidates)) }
            return
        }
        if (Cloze.matches(card, candidates)) {
            _state.update { it.copy(checking = false, read = candidates.firstOrNull(), nearMiss = null) }
            close(ClozeOutcome.RIGHT, Cloze.rating(_state.value.hints, _state.value.wrong, revealed = false))
        } else {
            _state.update {
                it.copy(checking = false, wrong = it.wrong + 1, outcome = ClozeOutcome.WRONG, read = candidates.firstOrNull() ?: "(nada)", nearMiss = null)
            }
            // ¿La misma palabra con otra terminación? Entonces se dice qué cambia, no solo «no es».
            val written = candidates.firstOrNull() ?: return
            viewModelScope.launch {
                val miss = withContext(Dispatchers.Default) { runCatching { DictionaryCloze.nearMiss(c.dictionary, card, written) }.getOrNull() }
                _state.update { if (it.card === card && it.outcome == ClozeOutcome.WRONG && it.read == written) it.copy(nearMiss = miss) else it }
            }
        }
    }

    /** «Otra vez» después de fallar: limpia el recuadro y sigue la misma tarjeta. */
    fun retry() = _state.update { it.copy(outcome = null, strokes = emptyList(), nearMiss = null) }

    /** «La mía también vale»: tu respuesta era otra forma correcta; cuenta como acierto con las pistas que usaste. */
    fun acceptMine() = close(ClozeOutcome.RIGHT, Cloze.rating(_state.value.hints, wrongTries = 0, revealed = false))

    /**
     * «Ver respuesta»: la tarjeta se califica como «vuelve pronto» y el recuadro queda limpio para
     * copiarla a mano, si hay tiempo (pedido por Jorge: que la mano trabaje aunque no la recordara).
     */
    fun reveal() {
        close(ClozeOutcome.REVEALED, Cloze.rating(_state.value.hints, _state.value.wrong, revealed = true))
        _state.update { if (it.outcome == ClozeOutcome.REVEALED) it.copy(strokes = emptyList(), read = null, copied = null) else it }
    }

    private fun close(outcome: ClozeOutcome, rating: Fsrs.Rating) {
        val st = _state.value
        val card = st.card ?: return
        // Una tarjeta se cierra (y se califica) una sola vez.
        if (st.outcome == ClozeOutcome.RIGHT || st.outcome == ClozeOutcome.REVEALED) return
        _state.update { it.copy(outcome = outcome, rating = rating, tally = it.tally + (rating to (it.tally[rating] ?: 0) + 1)) }
        // En el alcance de la app: el resultado se guarda aunque cierres la pantalla enseguida.
        c.appScope.launch { c.study.applyQuickReview(card.code, rating, System.currentTimeMillis()) }
    }

    fun next() = _state.update {
        it.copy(
            index = it.index + 1, hints = 0, wrong = 0, outcome = null, rating = null, read = null, nearMiss = null, copied = null, strokes = emptyList(), note = null,
            checking = false, explain = null, explaining = false, explainNote = null, explainParts = null, explainPartsLoading = false, lookup = null,
            translateNote = null,
        )
    }

    /** Lo ya buscado en esta ronda, por palabra y oración: tocarla otra vez no gasta otra llamada. */
    private val glosses = mutableMapOf<String, WordGloss>()

    /**
     * Tocar una palabra de la oración: lo que dice el diccionario (forma de diccionario, sentidos,
     * origen) y si está en tu lista, sin IA. Si el diccionario no la tiene, la IA, marcada. Tocarla
     * otra vez la cierra. No toca el repaso.
     */
    fun lookup(word: String) {
        val st = _state.value
        val card = st.card ?: return
        if (st.lookup?.word == word) {
            closeLookup()
            return
        }
        val index = st.index
        val key = "$word|${card.sentence}"
        _state.update { it.copy(lookup = WordLookup(word)) }
        viewModelScope.launch {
            val dict = withContext(Dispatchers.Default) {
                DictionaryLookup.lookup(c.dictionary, word, previous = previousWord(card.sentence, word))
            }
            if (dict != null) {
                val inList = dict.entries.filter { !it.entry.grammar }.map { it.entry.form }.distinct().filter { c.study.hasWord(it) }.toSet()
                _state.update { s ->
                    val open = s.lookup
                    if (s.index == index && open != null && open.word == word) s.copy(lookup = open.copy(dict = dict, loading = false, inList = inList)) else s
                }
                return@launch
            }
            var note: String? = null
            val gloss = glosses[key] ?: run {
                val settings = c.settings.state.value
                val provider = c.explainProvider()
                when {
                    provider == null -> null.also { note = "Para ver qué significa, configura Gemini (es gratis) en Ajustes › IA." }
                    c.settings.usageToday(provider.first) >= settings.dailyLimit(provider.first) -> null.also { note = "Llegaste al límite diario de la IA." }
                    else -> try {
                        val raw = provider.second.complete(CorrectionRequest(ConversationPrompts.word(word, card.sentence)))
                        c.settings.registerUse(provider.first)
                        ConversationParser.word(raw, word).also { glosses[key] = it }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ProviderException) {
                        null.also { note = c.explainError(e, provider.first) }
                    } catch (e: ParseException) {
                        null.also { note = "No se pudo leer la respuesta de la IA." }
                    } catch (e: Exception) {
                        null.also { note = e.message ?: e.javaClass.simpleName }
                    }
                }
            }
            val inList = gloss?.takeIf { !it.isGrammar && c.study.hasWord(it.form) }?.let { setOf(it.form) }.orEmpty()
            val known = gloss != null && withContext(Dispatchers.Default) { c.dictionary.isWord(gloss.form) }
            _state.update { s ->
                val open = s.lookup
                if (s.index == index && open != null && open.word == word) {
                    s.copy(lookup = open.copy(gloss = gloss, glossKnown = known, loading = false, note = note, inList = inList))
                } else s
            }
        }
    }

    /** La palabra que va antes de [word] en la oración (para poner primero los contadores: 세 장). */
    private fun previousWord(sentence: String, word: String): String? {
        val tokens = sentence.split(Regex("\\s+")).map { Cloze.tapTarget(it) ?: it }
        val i = tokens.indexOf(word)
        return if (i > 0) tokens[i - 1] else null
    }

    fun closeLookup() = _state.update { it.copy(lookup = null) }

    /**
     * «Agregar a mi lista»: [form] entra como nueva (la app te la va a sugerir), con el tipo y el
     * significado del diccionario. Lo que dio la IA solo si el diccionario conoce la forma: si no,
     * puede estar mal escrita.
     */
    fun addLookup(form: String) {
        val st = _state.value
        val open = st.lookup ?: return
        if (form in open.inList) return
        val entry = open.dict?.entries?.firstOrNull { it.entry.form == form && !it.entry.grammar }
        val gloss = open.gloss?.takeIf { it.form == form && !it.isGrammar }
        if (entry == null && (gloss == null || !open.glossKnown)) return
        val type = entry?.entry?.wordType ?: gloss?.type
        val meaning = entry?.senses?.firstOrNull()?.let { it.lemma ?: it.definition } ?: gloss?.meaning
        val index = st.index
        // En el alcance de la app: queda agregada aunque cierres la pantalla enseguida.
        c.appScope.launch {
            c.study.addLookedUpWord(form, type, meaning)
            _state.update { s ->
                val latest = s.lookup
                if (s.index == index && latest != null && latest.word == open.word) {
                    s.copy(lookup = latest.copy(inList = latest.inList + form, added = latest.added + form))
                } else s
            }
        }
    }

    /**
     * «¿De qué partes se forma?» para una palabra nativa: el diccionario no lo dice, así que se le
     * pregunta a la IA (con el modelo de las explicaciones) y se muestra marcado y revisado.
     */
    fun askParts() {
        val st = _state.value
        val card = st.card ?: return
        val open = st.lookup ?: return
        if (!open.canAskParts || open.aiPartsLoading) return
        val index = st.index
        val key = "${open.word}|${card.sentence}"
        _state.update { it.copy(lookup = open.copy(aiPartsLoading = true, note = null)) }
        viewModelScope.launch {
            var note: String? = null
            val gloss = glosses[key] ?: run {
                val settings = c.settings.state.value
                val provider = c.explainProvider()
                when {
                    provider == null -> null.also { note = "Para pedir las partes, configura Gemini o Azure en Ajustes › IA." }
                    c.settings.usageToday(provider.first) >= settings.dailyLimit(provider.first) -> null.also { note = "Llegaste al límite diario de la IA." }
                    else -> try {
                        val raw = provider.second.complete(CorrectionRequest(ConversationPrompts.word(open.word, card.sentence)))
                        c.settings.registerUse(provider.first)
                        ConversationParser.word(raw, open.word).also { glosses[key] = it }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ProviderException) {
                        null.also { note = c.explainError(e, provider.first) }
                    } catch (e: Exception) {
                        null.also { note = e.message ?: e.javaClass.simpleName }
                    }
                }
            }
            val parts = gloss?.let { it.parts ?: "Es una palabra simple: no se forma de otras partes." }
            _state.update { s ->
                val latest = s.lookup
                if (s.index == index && latest != null && latest.word == open.word) {
                    s.copy(lookup = latest.copy(aiParts = parts, aiPartsLoading = false, note = note))
                } else s
            }
        }
    }

    /**
     * «Más sobre esto» al cerrar una tarjeta: significado, origen, uso, formas y ejemplos. Primero del
     * 한국어기초사전, sin IA; si no lo tiene, de las explicaciones ya guardadas (las mismas de las
     * sugerencias) y si tampoco, se pide a la IA una vez y queda guardada, marcada «Según la IA».
     */
    fun explain() {
        val card = _state.value.card ?: return
        if (_state.value.explaining) return
        val isGrammar = card.kind == ClozeKind.GRAMMAR
        val key = ExplanationStore.key(card.label, isGrammar)
        // La explicación es de esta tarjeta: si llega cuando ya pasaste a otra, queda guardada pero no
        // se muestra (antes salía bajo el título de la tarjeta siguiente).
        val index = _state.value.index
        _state.update { it.copy(explaining = true, explainNote = null) }
        viewModelScope.launch {
            c.knownExplanation(card.label, isGrammar, card.meaning)?.let { found ->
                _state.update { if (it.index == index) it.copy(explain = found, explaining = false) else it }
                return@launch
            }
            val settings = c.settings.state.value
            val provider = c.explainProvider()
            val note = when {
                provider == null -> "Para ver más, configura Gemini (es gratis) en Ajustes › IA."
                c.settings.usageToday(provider.first) >= settings.dailyLimit(provider.first) -> "Llegaste al límite diario de la IA."
                else -> try {
                    val asked = listOf(card.label to (card.kind == ClozeKind.GRAMMAR))
                    val raw = provider.second.complete(CorrectionRequest(Prompts.explainItems(settings.profile, settings.level, settings.standardLevel, asked)))
                    c.settings.registerUse(provider.first)
                    c.explanations.putAll(ExplanationStore.match(asked, ExplainParser.parse(raw)))
                    if (c.explanations.get(key) == null) "La IA no devolvió esta explicación." else null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ProviderException) {
                    c.explainError(e, provider.first)
                } catch (e: ParseException) {
                    "No se pudo leer la respuesta de la IA."
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
            }
            val found = c.explanations.get(key)
            _state.update { if (it.index == index) it.copy(explain = found, explaining = false, explainNote = note) else it }
        }
    }

    /**
     * «Traducir»: para una tarjeta que quedó sin traducción (la llamada de la ronda falló o no la
     * trajo). Queda para esta ronda.
     */
    fun translate() {
        val st = _state.value
        val card = st.card ?: return
        if (card.translation != null || card.code in st.translations || st.translating != null) return
        val provider = c.explainProvider()
        if (provider == null) {
            _state.update { it.copy(translateNote = "Para traducir, configura Gemini (es gratis) en Ajustes › IA.") }
            return
        }
        if (c.settings.usageToday(provider.first) >= c.settings.state.value.dailyLimit(provider.first)) {
            _state.update { it.copy(translateNote = "Llegaste al límite diario de la IA.") }
            return
        }
        _state.update { it.copy(translating = card.code, translateNote = null) }
        viewModelScope.launch {
            var note: String? = null
            val text = try {
                val raw = provider.second.complete(CorrectionRequest(Cloze.translatePrompt(card)))
                c.settings.registerUse(provider.first)
                Cloze.parseTranslation(raw)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderException) {
                null.also { note = c.explainError(e, provider.first) }
            } catch (e: ParseException) {
                null.also { note = "No se pudo leer la respuesta de la IA." }
            } catch (e: Exception) {
                null.also { note = e.message ?: e.javaClass.simpleName }
            }
            _state.update { s ->
                s.copy(
                    translating = null,
                    translations = if (text != null) s.translations + (card.code to text) else s.translations,
                    translateNote = if (s.card?.code == card.code) note else s.translateNote,
                )
            }
        }
    }

    /** «¿De qué partes se forma?» en «Más sobre esto»: el diccionario no desglosa las palabras nativas. */
    fun askExplainParts() {
        val st = _state.value
        val card = st.card ?: return
        val e = st.explain ?: return
        if (st.explainPartsLoading || st.explainParts != null) return
        val index = st.index
        _state.update { it.copy(explainPartsLoading = true, explainNote = null) }
        viewModelScope.launch {
            val (parts, note) = c.askParts(e.form, card.sentence)
            _state.update { if (it.index == index) it.copy(explainParts = parts, explainPartsLoading = false, explainNote = note) else it }
        }
    }
}

@Composable
fun QuickReviewScreen(container: AppContainer, onBack: () -> Unit) {
    val vm: QuickReviewViewModel = viewModel(key = "repaso") { QuickReviewViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppTextButton(onClick = onBack) { Text("‹ Estudio") }
            Text("Repaso rápido", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (state.cards.isNotEmpty() && !state.done) {
                Text("${state.index + 1} de ${state.cards.size}", style = MaterialTheme.typography.labelLarge)
            }
        }
        val card = state.card
        when {
            state.loading -> Text("Preparando tus oraciones…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp))
            state.empty != null -> Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.empty!!, style = MaterialTheme.typography.bodyMedium)
                state.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                AppOutlinedButton(onClick = onBack) { Text("Volver") }
            }
            state.done -> Summary(state, onAgain = vm::start, onBack = onBack)
            card != null -> CardView(state, card, settings.stylusOnly, settings.penPressure, settings.penWidthDp, vm)
        }
    }
}

/** Lo que la app leyó, en grande: así se ve enseguida si leyó bien tu letra. */
/**
 * «Usa:»: qué va en el hueco sin decir la forma coreana. En una gramática siempre: qué expresa y con
 * qué verbo va («la gramática de «durante el tiempo que», con 먹다»), porque la traducción sola no
 * distingue -는 동안 de -면서. En una palabra o un verbo, solo sin traducción (si no, ya lo dice ella).
 */
@Composable
private fun UseLine(card: ClozeCard, hasTranslation: Boolean) {
    if (card.kind != ClozeKind.GRAMMAR && hasTranslation) return
    val scheme = MaterialTheme.colorScheme
    val bold = SpanStyle(fontWeight = FontWeight.Bold, color = scheme.primary)
    val sense = card.sense
    Text(
        buildAnnotatedString {
            append("Usa: ")
            when (card.kind) {
                ClozeKind.GRAMMAR -> {
                    if (sense != null) {
                        append("la gramática de «")
                        withStyle(bold) { append(sense) }
                        append("»")
                    } else {
                        append("una gramática")
                    }
                    card.base?.let {
                        append(", con ")
                        withStyle(bold) { append(it) }
                    }
                }
                ClozeKind.VERB -> if (sense != null) {
                    append("la palabra de «")
                    withStyle(bold) { append(sense) }
                    append("», conjugada")
                } else {
                    append("un verbo o adjetivo, conjugado")
                }
                ClozeKind.WORD -> if (sense != null) {
                    append("la palabra de «")
                    withStyle(bold) { append(sense) }
                    append("»")
                } else {
                    append("una palabra")
                }
            }
        },
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.testTag("repaso_usa"),
    )
}

@Composable
private fun ReadBack(read: String?, color: androidx.compose.ui.graphics.Color) {
    if (read.isNullOrBlank()) return
    Column(Modifier.testTag("repaso_leido")) {
        Text("LEÍ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(read, style = MaterialTheme.typography.headlineMedium, color = color)
    }
}

@Composable
private fun Summary(state: QuickReviewState, onAgain: () -> Unit, onBack: () -> Unit) {
    val good = state.tally[Fsrs.Rating.GOOD] ?: 0
    val hard = state.tally[Fsrs.Rating.HARD] ?: 0
    val again = state.tally[Fsrs.Rating.AGAIN] ?: 0
    Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Listo: ${state.cards.size} ${if (state.cards.size == 1) "oración" else "oraciones"}", style = MaterialTheme.typography.titleMedium)
        Text("Bien: $good · Con pistas: $hard · Vuelven pronto: $again", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Ya cuenta para tu repaso. Lo que viste con la respuesta vuelve pronto; mejor todavía si lo usas en el diario.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppButton(onClick = onAgain) { Text("Otra ronda") }
            AppOutlinedButton(onClick = onBack) { Text("Volver") }
        }
    }
}

@Composable
private fun CardView(
    state: QuickReviewState,
    card: ClozeCard,
    stylusOnly: Boolean,
    pressure: Boolean,
    penWidthDp: Float,
    vm: QuickReviewViewModel,
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    var typing by remember { mutableStateOf(false) }
    var typed by remember(card.code, state.index) { mutableStateOf("") }
    var padWidth by remember { mutableFloatStateOf(0f) }
    val padHeightPx = with(density) { 120.dp.toPx() }
    val answering = state.outcome == null
    val shown = state.outcome == ClozeOutcome.RIGHT || state.outcome == ClozeOutcome.REVEALED

    // Arriba lo que se lee (se desplaza si hace falta); abajo, fijo, el recuadro para escribir.
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Arriba, qué va en el hueco sin la forma coreana (pedido por Jorge el 2026-11-22: sin
            // saberlo la tarjeta era adivinar): la traducción con lo que falta resaltado y, en gramática,
            // «Usa:» con qué expresa y con qué verbo va, para distinguirla de las parecidas.
            val translation = card.translation ?: state.translations[card.code]
            if (translation != null) {
                Text(
                    buildAnnotatedString {
                        Cloze.translationParts(translation).forEach { (text, marked) ->
                            if (marked) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = scheme.primary)) { append(text) } else append(text)
                        }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("repaso_traduccion"),
                )
            }
            UseLine(card, hasTranslation = translation != null)
            val aiLabel = when {
                translation != null && card.senseByAi && card.kind == ClozeKind.GRAMMAR -> "Traducción y pista según la IA"
                translation != null -> "Traducción según la IA"
                card.senseByAi && card.kind == ClozeKind.GRAMMAR -> "Pista según la IA"
                else -> null
            }
            aiLabel?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant) }
            if (translation == null) {
                if (state.translating == card.code) {
                    Text("Traduciendo…", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                } else {
                    AppTextButton(onClick = vm::translate, modifier = Modifier.testTag("repaso_traducir")) { Text("Traducir") }
                }
                state.translateNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.error) }
            }
            // La oración con el hueco; al cerrar la tarjeta, con la respuesta. Cada palabra se toca
            // para ver qué es (la del hueco, solo al cerrar la tarjeta).
            val blankColor = when (state.outcome) {
                ClozeOutcome.RIGHT -> scheme.tertiary
                ClozeOutcome.REVEALED -> scheme.error
                else -> scheme.primary
            }
            val plain = TextLinkStyles(SpanStyle(color = scheme.onSurface))
            val selected = TextLinkStyles(SpanStyle(color = scheme.primary))
            Text(
                buildAnnotatedString {
                    Cloze.words(card, shown).forEach { w ->
                        val pieces: () -> Unit = {
                            w.pieces.forEach { p ->
                                if (!p.blank) append(p.text)
                                else withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = blankColor)) {
                                    append(if (shown) p.text else "＿".repeat(p.text.count { !it.isWhitespace() }.coerceAtLeast(2)))
                                }
                            }
                        }
                        val tap = w.tap
                        if (tap == null) pieces()
                        else withLink(
                            LinkAnnotation.Clickable(
                                tag = tap,
                                styles = if (state.lookup?.word == tap) selected else plain,
                                linkInteractionListener = LinkInteractionListener { vm.lookup(tap) },
                            ),
                        ) { pieces() }
                    }
                },
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.testTag("repaso_oracion"),
            )
            Text(
                (if (card.own) "Tu oración" else if (card.fromDictionary) "Oración del diccionario" else "Oración de la IA") + " · " + when (card.kind) {
                    ClozeKind.WORD -> "palabra"
                    ClozeKind.VERB -> "verbo o adjetivo"
                    ClozeKind.GRAMMAR -> "gramática"
                } + " · toca una palabra para ver qué es",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            state.lookup?.let { WordPanel(it, onAdd = vm::addLookup, onAskParts = vm::askParts, onClose = vm::closeLookup) }
            if (state.hints > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Cloze.hints(card).take(state.hints).forEachIndexed { i, h ->
                        Text("Pista ${i + 1}: $h", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            state.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
            when (state.outcome) {
                ClozeOutcome.RIGHT -> {
                    ReadBack(state.read, scheme.tertiary)
                    Text(
                        if (state.rating == Fsrs.Rating.GOOD) "Bien." else "Bien, con ayuda: vuelve un poco antes.",
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.tertiary,
                    )
                }
                ClozeOutcome.WRONG -> {
                    ReadBack(state.read, scheme.error)
                    state.nearMiss?.let {
                        Text(it, style = MaterialTheme.typography.titleSmall, color = scheme.error, modifier = Modifier.testTag("repaso_casi"))
                    }
                    Text(
                        "No es lo del hueco: prueba otra vez o abre una pista. Si lo escribiste bien y la app leyó mal, o tu forma también es correcta, «La mía también vale».",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.error,
                    )
                }
                ClozeOutcome.REVEALED -> {
                    Text("Era «${card.answer}». Vuelve pronto.", style = MaterialTheme.typography.titleMedium, color = scheme.error)
                    when (state.copied) {
                        null -> Text("Si tienes tiempo, cópiala abajo a mano.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                        true -> {
                            ReadBack(state.read, scheme.tertiary)
                            Text("Bien escrita.", style = MaterialTheme.typography.titleMedium, color = scheme.tertiary)
                        }
                        false -> {
                            ReadBack(state.read, scheme.error)
                            Text(
                                "No coincide: mírala arriba y prueba otra vez, o sigue. Si la escribiste bien y la app leyó mal, no pasa nada.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.error,
                            )
                        }
                    }
                }
                null -> {}
            }
            // «Más sobre esto»: la palabra o la gramática de la tarjeta, por partes y con ejemplos.
            if (shown && (state.explain != null || state.explaining || state.explainNote != null)) {
                Column(
                    Modifier.fillMaxWidth().border(1.dp, scheme.outlineVariant).padding(12.dp).testTag("repaso_mas"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(card.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    state.explain?.let {
                        ExplanationBody(
                            it,
                            parts = state.explainParts,
                            partsLoading = state.explainPartsLoading,
                            onAskParts = vm::askExplainParts.takeIf { card.kind != ClozeKind.GRAMMAR },
                        )
                    }
                    if (state.explaining) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Buscando la explicación…", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                    state.explainNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.error) }
                }
            }
        }

        if (answering || state.outcome == ClozeOutcome.WRONG) {
            if (answering) {
                if (typing) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        placeholder = { Text("Lo que va en el hueco") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    AnswerPad(state.strokes, "Escribe aquí lo que va en el hueco", stylusOnly, pressure, penWidthDp, padHeightPx, vm::addStroke) { padWidth = it }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (answering) {
                    AppTextButton(onClick = { if (typing) typed = "" else vm.clearInk() }) { Text("Borrar") }
                    AppTextButton(onClick = { typing = !typing }) { Text(if (typing) "A mano" else "Teclado") }
                    Box(Modifier.weight(1f))
                    AppOutlinedButton(onClick = vm::hint, enabled = state.hints < 3) { Text("Pista ${state.hints}/3") }
                    AppButton(
                        onClick = { if (typing) vm.checkTyped(typed) else vm.checkInk(padHeightPx, padWidth) },
                        enabled = !state.checking,
                    ) { Text(if (state.checking) "Leyendo…" else "Comprobar") }
                } else {
                    AppButton(onClick = vm::retry) { Text("Otra vez") }
                    AppOutlinedButton(onClick = vm::acceptMine) { Text("La mía también vale") }
                    AppOutlinedButton(onClick = vm::hint, enabled = state.hints < 3) { Text("Pista") }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                // Versión de prueba: responder sin escribir coreano (docs/PRUEBAS.md).
                if (BuildConfig.PRUEBA && answering) {
                    AppTextButton(onClick = { vm.checkTyped(card.answer) }, modifier = Modifier.testTag("repaso_prueba_bien")) { Text("Prueba: bien") }
                    AppTextButton(onClick = { vm.checkTyped("x") }, modifier = Modifier.testTag("repaso_prueba_mal")) { Text("Prueba: mal") }
                    Box(Modifier.weight(1f))
                }
                AppTextButton(onClick = vm::reveal) { Text("Ver respuesta") }
            }
        } else if (state.outcome == ClozeOutcome.REVEALED) {
            // Viste la respuesta: el recuadro sigue en su sitio para copiarla a mano, si hay tiempo.
            // «Comprobar» es opcional y «Siguiente» está siempre.
            AnswerPad(state.strokes, "Si tienes tiempo, cópiala aquí", stylusOnly, pressure, penWidthDp, padHeightPx, vm::addStroke) { padWidth = it }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                AppTextButton(onClick = vm::clearInk) { Text("Borrar") }
                Box(Modifier.weight(1f))
                AppOutlinedButton(
                    onClick = { vm.checkInk(padHeightPx, padWidth) },
                    enabled = !state.checking,
                    modifier = Modifier.testTag("repaso_copia_comprobar"),
                ) { Text(if (state.checking) "Leyendo…" else "Comprobar") }
                AppButton(onClick = vm::next) { Text(if (state.index + 1 < state.cards.size) "Siguiente" else "Terminar") }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (BuildConfig.PRUEBA) {
                    AppTextButton(onClick = { vm.checkTyped(card.answer) }, modifier = Modifier.testTag("repaso_copia_bien")) { Text("Prueba: bien") }
                    AppTextButton(onClick = { vm.checkTyped("x") }, modifier = Modifier.testTag("repaso_copia_mal")) { Text("Prueba: mal") }
                    Box(Modifier.weight(1f))
                }
                if (state.explain == null && !state.explaining) {
                    AppTextButton(onClick = vm::explain, modifier = Modifier.testTag("repaso_mas_boton")) { Text("Más sobre esto") }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                if (state.explain == null && !state.explaining) {
                    AppOutlinedButton(onClick = vm::explain, modifier = Modifier.testTag("repaso_mas_boton")) { Text("Más sobre esto") }
                }
                AppButton(onClick = vm::next) { Text(if (state.index + 1 < state.cards.size) "Siguiente" else "Terminar") }
            }
        }
    }
}

/**
 * La palabra tocada: lo que dice el diccionario (o, si no la tiene, la IA, marcada) y, por cada
 * forma que no está en tu lista, «Agregar a mi lista».
 */
@Composable
private fun WordPanel(lookup: WordLookup, onAdd: (String) -> Unit, onAskParts: () -> Unit, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dict = lookup.dict
    val gloss = lookup.gloss
    val base = dict?.forms?.firstOrNull() ?: gloss?.form
    Column(
        Modifier.fillMaxWidth().border(1.dp, scheme.outlineVariant).padding(start = 12.dp, end = 4.dp, bottom = 12.dp).testTag("repaso_palabra"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (base != null && base != lookup.word) "${lookup.word} → $base" else lookup.word,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            AppTextButton(onClick = onClose) { Text("✕") }
        }
        Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (lookup.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Buscando…", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            if (dict != null) {
                DictionaryEntries(dict)
                if (lookup.canAskParts) {
                    when {
                        lookup.aiParts != null -> Column {
                            Text("Según la IA", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                            PartsLine(dict.entries.first().entry.form, lookup.aiParts)
                        }
                        lookup.aiPartsLoading -> Text("Preguntando a la IA…", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        else -> AppTextButton(onClick = onAskParts, modifier = Modifier.testTag("repaso_palabra_partes")) { Text("¿De qué partes se forma? (IA)") }
                    }
                }
                dict.entries.filter { !it.entry.grammar }.map { it.entry.form }.distinct().forEachIndexed { i, form ->
                    AddRow(form, lookup, onAdd, Modifier.testTag(if (i == 0) "repaso_palabra_agregar" else "repaso_palabra_agregar_$i"))
                }
            }
            gloss?.let { g ->
                Text("No está en el diccionario. Según la IA:", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                Text(g.meaning, style = MaterialTheme.typography.bodyLarge)
                g.parts?.let { PartsLine(g.form, it) }
                g.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (!g.isGrammar) AddRow(g.form, lookup, onAdd, Modifier.testTag("repaso_palabra_agregar"))
            }
            lookup.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.error) }
        }
    }
}

/** «Agregar «X» a mi lista», o que ya está, o que se acaba de agregar. Lo que dio la IA sin estar en el diccionario no se agrega. */
@Composable
private fun AddRow(form: String, lookup: WordLookup, onAdd: (String) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val fromDict = lookup.dict?.entries?.any { it.entry.form == form } == true
    when {
        form in lookup.added -> Text("«$form» quedó en tu lista: la app te la va a sugerir.", style = MaterialTheme.typography.bodySmall, color = scheme.tertiary)
        form in lookup.inList -> Text("«$form» ya está en tu lista.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        fromDict || (lookup.gloss?.form == form && lookup.glossKnown) ->
            AppOutlinedButton(onClick = { onAdd(form) }, modifier = modifier) { Text("Agregar «$form» a mi lista") }
        else -> Text("No la agrego a tu lista: no está en el diccionario y puede estar mal escrita.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
}

/** El recuadro de un renglón donde escribes a mano: para responder y, tras «Ver respuesta», para copiarla. */
@Composable
private fun AnswerPad(
    strokes: List<InkStroke>,
    placeholder: String,
    stylusOnly: Boolean,
    pressure: Boolean,
    penWidthDp: Float,
    lineHeightPx: Float,
    onStroke: (InkStroke) -> Unit,
    onWidth: (Float) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val paper = LocalPaperStyle.current
    val density = LocalDensity.current
    Box(
        Modifier.fillMaxWidth().height(120.dp).border(1.dp, scheme.outlineVariant)
            .paperBackground(paper).clipToBounds().testTag("repaso_recuadro").onSizeChanged { onWidth(it.width.toFloat()) },
    ) {
        InkCanvas(
            strokes = strokes,
            tool = InkTool.PEN,
            stylusOnly = stylusOnly,
            lineHeightPx = lineHeightPx,
            reviewedLines = 0,
            inkColor = paper.ink,
            lineColor = paper.line,
            reviewedTint = paper.reviewedTint,
            strokeWidthPx = with(density) { penWidthDp.dp.toPx() },
            pressureEnabled = pressure,
            onStrokeFinished = onStroke,
            onErase = {},
            modifier = Modifier.fillMaxSize(),
        )
        if (strokes.isEmpty()) {
            Text(
                placeholder,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
