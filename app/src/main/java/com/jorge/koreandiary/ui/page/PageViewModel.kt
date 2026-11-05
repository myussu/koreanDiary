package com.jorge.koreandiary.ui.page

import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jorge.koreandiary.AppContainer
import com.jorge.koreandiary.ai.CorrectionParser
import com.jorge.koreandiary.ai.CorrectionRequest
import com.jorge.koreandiary.ai.HowToSayParser
import com.jorge.koreandiary.ai.HowToSayResponse
import com.jorge.koreandiary.ai.ParseException
import com.jorge.koreandiary.ai.Prompts
import com.jorge.koreandiary.ai.ProviderException
import com.jorge.koreandiary.ai.ShareFlow
import com.jorge.koreandiary.data.AnnotatedItem
import com.jorge.koreandiary.data.ItemRef
import com.jorge.koreandiary.data.db.Correction
import com.jorge.koreandiary.data.db.CorrectionStatus
import com.jorge.koreandiary.data.db.DiaryPage
import com.jorge.koreandiary.data.db.ItemType
import com.jorge.koreandiary.data.db.PageStatus
import com.jorge.koreandiary.ink.InkSerializer
import com.jorge.koreandiary.ink.InkStroke
import com.jorge.koreandiary.ink.TextMatch
import com.jorge.koreandiary.ink.renderPage
import com.jorge.koreandiary.ink.strokesNear
import com.jorge.koreandiary.settings.ProviderId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Phase {
    data object Writing : Phase
    data class Busy(val message: String) : Phase
    data class Confirm(val text: String) : Phase
    data object AwaitingPaste : Phase
}

/** Estado de "¿Cómo digo esto?". */
sealed interface HowTo {
    data object Idle : HowTo
    data object Asking : HowTo
    /** Sin API: el prompt se compartió con un chat; falta pegar la respuesta. */
    data object AwaitingPaste : HowTo
    data class Result(
        val response: HowToSayResponse,
        val mineTags: List<AnnotatedItem>,
        val naturalTags: List<AnnotatedItem>,
        val wordTags: List<AnnotatedItem>,
    ) : HowTo
    data class Error(val message: String) : HowTo
}

data class SuggestionChip(val label: String, val detail: String, val isGrammar: Boolean, val isGap: Boolean = false)

data class PageUiState(
    val page: DiaryPage? = null,
    val strokes: List<InkStroke> = emptyList(),
    val suggestions: List<SuggestionChip> = emptyList(),
    val phase: Phase = Phase.Writing,
    val message: String? = null,
    val canUndo: Boolean = false,
)

class PageViewModel(private val c: AppContainer, private val pageId: Long) : ViewModel() {

    private val _state = MutableStateFlow(PageUiState())
    val state: StateFlow<PageUiState> = _state.asStateFlow()

    val corrections: StateFlow<List<Correction>> = c.diary.corrections(pageId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _howTo = MutableStateFlow<HowTo>(HowTo.Idle)
    val howTo: StateFlow<HowTo> = _howTo.asStateFlow()

    private val _shareIntents = Channel<Intent>(Channel.BUFFERED)
    val shareIntents = _shareIntents.receiveAsFlow()

    private var width = 0f
    private var height = 0f
    private var lineHeight = 0f
    private var strokesLoaded = false
    /** Cada paso guarda los trazos y la zona revisada (insertar una línea puede moverla). */
    private data class Snapshot(val strokes: List<InkStroke>, val reviewedLines: Int)
    private val undoStack = ArrayDeque<Snapshot>()
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            val page = c.diary.page(pageId) ?: return@launch
            _state.update { it.copy(page = page, suggestions = loadSuggestions(page)) }
            tryLoadStrokes()
        }
    }

    fun onCanvasSized(w: Float, h: Float, line: Float) {
        if (w <= 0f || h <= 0f) return
        width = w
        height = h
        lineHeight = line
        tryLoadStrokes()
    }

    private fun tryLoadStrokes() {
        val page = _state.value.page ?: return
        if (strokesLoaded || width <= 0f) return
        strokesLoaded = true
        _state.update { it.copy(strokes = InkSerializer.fromJson(page.inkJson, width)) }
    }

    private suspend fun loadSuggestions(page: DiaryPage): List<SuggestionChip> {
        val refs = ItemRef.decodeList(page.suggestedItems)
        val dao = c.database.studyDao()
        val words = dao.wordsByIds(refs.filter { it.type == ItemType.WORD }.map { it.id })
            .map { SuggestionChip(it.baseForm, listOfNotNull(it.type, it.meaning).joinToString(" · "), false) }
        val grammar = dao.grammarByIds(refs.filter { it.type == ItemType.GRAMMAR }.map { it.id })
            .map { g -> SuggestionChip(g.pattern, listOfNotNull(g.meaning, g.example).joinToString(" — ").ifEmpty { "Gramática ${g.level}" }, true) }
        val gaps = withContext(Dispatchers.Default) {
            refs.mapNotNull { ref ->
                when (ref.type) {
                    ItemType.STD_WORD -> c.catalog.wordById(ref.id.toInt())?.let {
                        SuggestionChip(it.word, "Posible laguna · nivel ${it.level} del estándar · ${it.type} · ej.: ${it.guide}", false, isGap = true)
                    }
                    ItemType.STD_GRAMMAR -> c.catalog.grammarById(ref.id.toInt())?.let {
                        val extra = listOf(it.category, it.meaning).filter { s -> s.isNotBlank() }.joinToString(" · ")
                        SuggestionChip(it.form, "Posible laguna · nivel ${it.level} del estándar · $extra", true, isGap = true)
                    }
                    else -> null
                }
            }
        }
        return words + grammar + gaps
    }

    // ---------- Trazos ----------

    fun addStroke(stroke: InkStroke) = mutate { it + stroke }

    fun eraseAt(point: Offset) {
        val hits = strokesNear(_state.value.strokes, point, ERASE_RADIUS)
        if (hits.isNotEmpty()) mutate { strokes -> strokes.filterIndexed { i, _ -> i !in hits } }
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        _state.update {
            it.copy(
                strokes = previous.strokes,
                page = it.page?.copy(reviewedLineCount = previous.reviewedLines),
                canUndo = undoStack.isNotEmpty(),
            )
        }
        scheduleSave()
    }

    /**
     * Inserta una línea vacía en [line], como "insertar fila" en Excel: esa línea y todo lo de abajo
     * baja un renglón. Si cae dentro de la zona revisada, la zona crece para seguir cubriendo el original.
     */
    fun insertLine(line: Int) {
        if (lineHeight <= 0f) return
        val page = _state.value.page ?: return
        val shift = lineHeight
        mutate(reviewedLines = if (line < page.reviewedLineCount) page.reviewedLineCount + 1 else page.reviewedLineCount) { strokes ->
            strokes.map { s ->
                if (s.lineIndex(lineHeight) >= line) InkStroke(s.points.map { p -> p.copy(y = p.y + shift) }) else s
            }
        }
    }

    private fun mutate(
        reviewedLines: Int? = null,
        transform: (List<InkStroke>) -> List<InkStroke>,
    ) {
        val current = _state.value.strokes
        val currentReviewed = _state.value.page?.reviewedLineCount ?: 0
        undoStack.addLast(Snapshot(current, currentReviewed))
        if (undoStack.size > 60) undoStack.removeFirst()
        _state.update {
            it.copy(
                strokes = transform(current),
                page = if (reviewedLines != null) it.page?.copy(reviewedLineCount = reviewedLines) else it.page,
                canUndo = true,
            )
        }
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(400)
            persist()
        }
    }

    private suspend fun persist(transform: (DiaryPage) -> DiaryPage = { it }): DiaryPage? {
        val page = _state.value.page ?: return null
        val updated = transform(page).copy(
            inkJson = InkSerializer.toJson(_state.value.strokes, width),
            canvasWidth = width.toInt(),
            updatedAt = System.currentTimeMillis(),
        )
        c.diary.save(updated)
        _state.update { it.copy(page = updated) }
        return updated
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    // ---------- Revisión ----------

    fun review() {
        if (_state.value.strokes.isEmpty()) {
            _state.update { it.copy(message = "La página está vacía.") }
            return
        }
        viewModelScope.launch {
            try {
                if (!c.recognizer.isModelDownloaded()) {
                    setPhase(Phase.Busy("Descargando el modelo de escritura coreana (solo la primera vez)…"))
                    c.recognizer.downloadModel()
                }
                setPhase(Phase.Busy("Leyendo tu letra…"))
                val lines = c.recognizer.recognizeLines(_state.value.strokes, lineHeight, width)
                if (lines.isEmpty()) {
                    setPhase(Phase.Writing, "No se reconoció texto.")
                    return@launch
                }
                val text = lines.joinToString("\n") { it.text }
                persist { it.copy(recognizedText = text, reviewedLineCount = lines.maxOf { l -> l.line } + 1) }
                setPhase(Phase.Confirm(text))
            } catch (e: Exception) {
                setPhase(Phase.Writing, "No se pudo reconocer la escritura: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun cancelConfirm() = setPhase(Phase.Writing)

    // ---------- ¿Cómo digo esto? ----------

    fun askHowTo(idea: String) {
        if (idea.isBlank()) return
        viewModelScope.launch {
            val settings = c.settings.state.value
            val prompt = Prompts.howToSay(idea, settings.level, settings.standardLevel)
            // El proveedor por defecto si es una API; si no, la primera API configurada; si no hay, un chat.
            val providerId = listOf(settings.defaultProvider, ProviderId.GEMINI, ProviderId.AZURE)
                .firstOrNull { it != ProviderId.SHARE && c.isConfigured(it) }
            val api = providerId?.let { c.apiProvider(it) }
            if (providerId == null || api == null) {
                _shareIntents.send(ShareFlow.textIntent(c.app, prompt))
                _howTo.value = HowTo.AwaitingPaste
                return@launch
            }
            if (c.settings.usageToday(providerId) >= settings.dailyLimit(providerId)) {
                _howTo.value = HowTo.Error("Llegaste al límite diario de ${providerName(providerId)}.")
                return@launch
            }
            _howTo.value = HowTo.Asking
            try {
                val raw = api.complete(CorrectionRequest(prompt))
                c.settings.registerUse(providerId)
                handleHowTo(raw)
            } catch (e: ProviderException) {
                _howTo.value = HowTo.Error(if (e.offline) "Sin conexión." else e.message ?: "Error del proveedor.")
            } catch (e: ParseException) {
                _howTo.value = HowTo.Error("No se pudo leer la respuesta: ${e.message}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _howTo.value = HowTo.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun submitHowToPaste(raw: String) {
        viewModelScope.launch {
            try {
                handleHowTo(raw)
            } catch (e: ParseException) {
                _howTo.value = HowTo.Error("No se pudo leer la respuesta: ${e.message}")
            }
        }
    }

    fun closeHowTo() {
        _howTo.value = HowTo.Idle
    }

    private suspend fun handleHowTo(raw: String) {
        val r = HowToSayParser.parse(raw)
        val mineTags = c.study.annotate(r.mine.grammar, emptyList())
        val naturalTags = r.natural?.let { c.study.annotate(it.grammar, emptyList()) } ?: emptyList()
        val wordTags = c.study.annotate(emptyList(), r.words)
        _howTo.value = HowTo.Result(r, mineTags, naturalTags, wordTags)
        // Lo que viste aquí pasa a "sugerido" en esta página: si luego lo usas, FSRS lo cuenta como
        // reconocimiento ("Hard"), no como recuerdo libre.
        addSuggested((mineTags + naturalTags + wordTags).mapNotNull { it.ref })
    }

    private suspend fun addSuggested(refs: List<ItemRef>) {
        val page = _state.value.page ?: return
        if (page.status == PageStatus.REVIEWED) return
        val existing = ItemRef.decodeList(page.suggestedItems)
        val merged = (existing + refs).distinct()
        if (merged.size == existing.size) return
        val updated = persist { it.copy(suggestedItems = merged.joinToString(",") { r -> "${r.type}:${r.id}" }) } ?: return
        _state.update { it.copy(suggestions = loadSuggestions(updated)) }
    }

    fun confirm(text: String, provider: String) {
        viewModelScope.launch {
            val page = persist {
                it.copy(
                    confirmedText = text.trim(),
                    status = if (it.status == PageStatus.REVIEWED) PageStatus.REVIEWED else PageStatus.PENDING,
                )
            } ?: return@launch
            val settings = c.settings.state.value
            val items = c.study.promptItems(ItemRef.decodeList(page.suggestedItems))

            if (provider != ProviderId.SHARE) {
                val name = providerName(provider)
                val api = c.apiProvider(provider)
                if (api == null) {
                    setPhase(Phase.Writing, "Falta configurar $name en Ajustes. La página quedó pendiente.")
                    return@launch
                }
                val limit = settings.dailyLimit(provider)
                if (c.settings.usageToday(provider) >= limit) {
                    setPhase(Phase.Writing, "Llegaste al límite diario de $name ($limit). Usa “Compartir con un chat”.")
                    return@launch
                }
                setPhase(Phase.Busy("$name está revisando tu página…"))
                try {
                    val prompt = Prompts.correction(settings.profile, settings.level, settings.standardLevel, items, text, hasImage = false)
                    val raw = api.complete(CorrectionRequest(prompt))
                    c.settings.registerUse(provider)
                    applyResponse(raw)
                } catch (e: ProviderException) {
                    val msg = if (e.offline) "Sin conexión. La página quedó pendiente de revisión." else e.message
                    setPhase(Phase.Writing, msg)
                } catch (e: ParseException) {
                    setPhase(Phase.Writing, "La respuesta de $name no se pudo leer: ${e.message}")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    setPhase(Phase.Writing, "Error inesperado con $name: ${e.message ?: e.javaClass.simpleName}")
                }
            } else {
                val prompt = Prompts.correction(settings.profile, settings.level, settings.standardLevel, items, text, hasImage = true)
                val bitmap = renderPage(_state.value.strokes, width.toInt(), height.toInt(), lineHeight)
                _shareIntents.send(ShareFlow.buildIntent(c.app, prompt, bitmap))
                setPhase(Phase.AwaitingPaste, "El prompt también quedó copiado. Pégalo en el chat si no aparece.")
            }
        }
    }

    fun openPaste() = setPhase(Phase.AwaitingPaste)

    fun submitPasted(raw: String) {
        viewModelScope.launch {
            try {
                applyResponse(raw)
            } catch (e: ParseException) {
                _state.update { it.copy(message = "No se pudo leer la respuesta: ${e.message}") }
            }
        }
    }

    private suspend fun applyResponse(raw: String) {
        val response = CorrectionParser.parse(raw)
        val page = _state.value.page ?: return
        val summary = c.study.applyCorrection(page, response, System.currentTimeMillis())
        persist { it.copy(status = PageStatus.REVIEWED) }
        val parts = buildList {
            add(if (summary.corrections == 0) "¡Sin errores!" else "${summary.corrections} correcciones")
            if (summary.reviewed > 0) add("${summary.reviewed} ítems repasados")
            if (summary.newWords > 0) add("${summary.newWords} palabras nuevas")
            if (summary.newGrammar > 0) add("${summary.newGrammar} gramáticas nuevas")
            if (summary.improvements > 0) add("${summary.improvements} mejoras sugeridas")
            if (summary.pendingWords > 0) add("${summary.pendingWords} por revisar en Estudio")
        }
        val hint = if (summary.corrections > 0) " Reescribe las correcciones debajo y pulsa “Verificar”." else ""
        setPhase(Phase.Writing, parts.joinToString(" · ") + "." + hint)
    }

    /** Compara lo escrito debajo de la parte revisada con las sugerencias pendientes. Sin IA. */
    fun verifyRewrite() {
        if (_state.value.page == null) return
        viewModelScope.launch {
            val pending = c.diary.correctionsNow(pageId).filter { it.status == CorrectionStatus.PENDING }
            if (pending.isEmpty()) {
                setPhase(Phase.Writing, "No hay correcciones pendientes.")
                return@launch
            }
            setPhase(Phase.Busy("Leyendo tu reescritura…"))
            try {
                // Toda la página: puedes reescribir debajo o borrar y reescribir en el mismo lugar.
                val lines = c.recognizer.recognizeLines(_state.value.strokes, lineHeight, width)
                if (lines.isEmpty()) {
                    setPhase(Phase.Writing, "No se reconoció texto en la página.")
                    return@launch
                }
                // Se unen las líneas para encontrar también correcciones que quedaron partidas en dos renglones.
                val written = normalize(lines.joinToString("") { it.text })
                var ok = 0
                for (corr in pending) {
                    val target = normalize(corr.suggestion)
                    // Tolerancia a un error puntual del reconocimiento (p. ej. una sílaba mal leída).
                    if (target.isNotEmpty() && TextMatch.approxContains(written, target)) {
                        c.diary.markRewritten(corr)
                        ok++
                    }
                }
                val read = lines.joinToString(" / ") { it.text }.let { if (it.length > 160) "…" + it.takeLast(160) else it }
                setPhase(Phase.Writing, "$ok de ${pending.size} correcciones bien reescritas. Leí: “$read”")
            } catch (e: Exception) {
                setPhase(Phase.Writing, "No se pudo reconocer: ${e.message}")
            }
        }
    }

    private fun normalize(s: String) = s.filter { it.isLetterOrDigit() }

    /** Para cuando el reconocimiento falla aunque lo hayas reescrito bien. */
    fun markRewritten(correction: Correction) {
        viewModelScope.launch { c.diary.markRewritten(correction) }
    }

    private fun providerName(id: String) = when (id) {
        ProviderId.GEMINI -> "Gemini"
        ProviderId.AZURE -> "Azure OpenAI"
        else -> id
    }

    private fun setPhase(phase: Phase, message: String? = _state.value.message) =
        _state.update { it.copy(phase = phase, message = message) }

    override fun onCleared() {
        // Guardado final: el scope del ViewModel ya se canceló, así que se usa el de la app.
        val page = _state.value.page ?: return
        val strokes = _state.value.strokes
        val w = width
        if (w > 0f) {
            c.appScope.launch {
                c.diary.save(page.copy(inkJson = InkSerializer.toJson(strokes, w), canvasWidth = w.toInt(), updatedAt = System.currentTimeMillis()))
            }
        }
    }

    companion object {
        private const val ERASE_RADIUS = 28f
    }
}
