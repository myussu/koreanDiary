package com.jorge.koreandiary.ui.page

import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jorge.koreandiary.AppContainer
import com.jorge.koreandiary.ai.CorrectionParser
import com.jorge.koreandiary.ai.CorrectionRequest
import com.jorge.koreandiary.ai.ExplainParser
import com.jorge.koreandiary.ai.HowToSayParser
import com.jorge.koreandiary.ai.HowToSayResponse
import com.jorge.koreandiary.ai.ItemExplanation
import com.jorge.koreandiary.ai.ParseException
import com.jorge.koreandiary.ai.Prompts
import com.jorge.koreandiary.ai.ProviderException
import com.jorge.koreandiary.ai.ShareFlow
import com.jorge.koreandiary.data.AnnotatedItem
import com.jorge.koreandiary.data.ExplanationStore
import com.jorge.koreandiary.data.ItemRef
import com.jorge.koreandiary.data.db.Correction
import com.jorge.koreandiary.data.db.CorrectionStatus
import com.jorge.koreandiary.data.db.DiaryPage
import com.jorge.koreandiary.data.db.ItemType
import com.jorge.koreandiary.data.db.PageKind
import com.jorge.koreandiary.data.db.PageStatus
import com.jorge.koreandiary.ink.InkOps
import com.jorge.koreandiary.ink.InkSerializer
import com.jorge.koreandiary.ink.InkStroke
import com.jorge.koreandiary.ink.TextMatch
import com.jorge.koreandiary.ink.renderPage
import com.jorge.koreandiary.mascot.MascotMood
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** Vibración breve para los momentos clave (como el clic de un botón físico). */
enum class Feedback { SUCCESS, FAIL, TICK }

data class SuggestionChip(val label: String, val detail: String, val isGrammar: Boolean, val isGap: Boolean = false) {
    /** Clave de su explicación guardada. */
    val key: String get() = ExplanationStore.key(label, isGrammar)
}

/**
 * Tarjeta al tocar una sugerencia. Al instante muestra lo que la app ya sabe; el significado y
 * los ejemplos naturales vienen de la IA una sola vez y quedan guardados.
 */
data class ExplainCard(
    val chip: SuggestionChip,
    val explanation: ItemExplanation? = null,
    val loading: Boolean = false,
    val note: String? = null,
    val canRetry: Boolean = false,
)

data class PageUiState(
    val page: DiaryPage? = null,
    val strokes: List<InkStroke> = emptyList(),
    val suggestions: List<SuggestionChip> = emptyList(),
    val phase: Phase = Phase.Writing,
    val message: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)

class PageViewModel(private val c: AppContainer, private val pageId: Long) : ViewModel() {

    private val _state = MutableStateFlow(PageUiState())
    val state: StateFlow<PageUiState> = _state.asStateFlow()

    val corrections: StateFlow<List<Correction>> = c.diary.corrections(pageId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Reacción de la mascota; vuelve a "normal" sola tras unos segundos. */
    private val _mascotMood = MutableStateFlow(MascotMood.IDLE)
    val mascotMood: StateFlow<MascotMood> = _mascotMood.asStateFlow()
    private var mascotJob: Job? = null

    private fun react(mood: MascotMood, holdMs: Long = 7_000) {
        mascotJob?.cancel()
        _mascotMood.value = mood
        mascotJob = viewModelScope.launch {
            delay(holdMs)
            _mascotMood.value = MascotMood.IDLE
        }
    }

    private val _howTo = MutableStateFlow<HowTo>(HowTo.Idle)
    val howTo: StateFlow<HowTo> = _howTo.asStateFlow()

    private val _explain = MutableStateFlow<ExplainCard?>(null)
    val explain: StateFlow<ExplainCard?> = _explain.asStateFlow()
    private var explainJob: Job? = null

    private val _shareIntents = Channel<Intent>(Channel.BUFFERED)
    val shareIntents = _shareIntents.receiveAsFlow()

    private val _feedback = Channel<Feedback>(Channel.CONFLATED)
    val feedback = _feedback.receiveAsFlow()

    private var width = 0f
    private var height = 0f
    private var lineHeight = 0f
    private var strokesLoaded = false
    /** Cada paso guarda los trazos y la zona revisada (insertar una línea puede moverla). */
    private data class Snapshot(val strokes: List<InkStroke>, val reviewedLines: Int)
    private val undoStack = ArrayDeque<Snapshot>()
    /** Lo deshecho, para rehacerlo. Se vacía con cualquier cambio nuevo. */
    private val redoStack = ArrayDeque<Snapshot>()
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            val page = c.diary.page(pageId) ?: return@launch
            _state.update { it.copy(page = page, suggestions = loadSuggestions(page)) }
            tryLoadStrokes()
            // Saliste mientras la IA revisaba y volviste: se espera a que termine y se relee la página,
            // para no pisar con el estado viejo lo que la revisión guarde.
            if (pageId in reviewing.value) {
                setPhase(Phase.Busy("La IA sigue revisando esta página…"))
                reviewing.first { pageId !in it }
                val fresh = c.diary.page(pageId) ?: return@launch
                _state.update { it.copy(page = fresh, suggestions = loadSuggestions(fresh)) }
                setPhase(
                    Phase.Writing,
                    if (fresh.status == PageStatus.REVIEWED) "La revisión terminó mientras no estabas. Reescribe las correcciones y pulsa “Verificar”." else null,
                )
            }
        }
    }

    fun onCanvasSized(w: Float, h: Float, line: Float) {
        if (w <= 0f || h <= 0f) return
        val previous = width
        width = w
        height = h
        lineHeight = line
        // La hoja mide lo mismo en vertical y en horizontal; si aun así cambia (pantalla dividida,
        // otra pantalla), se escala la tinta igual que al guardar, para que nada quede fuera.
        if (strokesLoaded && previous > 0f && abs(w - previous) > 0.5f) {
            val factor = w / previous
            _state.update { it.copy(strokes = InkOps.scale(it.strokes, factor)) }
            listOf(undoStack, redoStack).forEach { stack ->
                val scaled = stack.map { snap -> snap.copy(strokes = InkOps.scale(snap.strokes, factor)) }
                stack.clear()
                stack.addAll(scaled)
            }
        }
        tryLoadStrokes()
    }

    private fun tryLoadStrokes() {
        val page = _state.value.page ?: return
        if (strokesLoaded || width <= 0f) return
        strokesLoaded = true
        val loaded = InkSerializer.fromJson(page.inkJson, width)
        // Páginas que quedaron con tinta fuera de la hoja al girar el celular: se achican para verse enteras.
        val fitted = InkOps.fitToWidth(loaded, width)
        _state.update { it.copy(strokes = fitted ?: loaded) }
        if (fitted != null) scheduleSave()
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
        val hits = strokesNear(_state.value.strokes, point, (lineHeight * 0.18f).coerceAtLeast(MIN_ERASE_RADIUS))
        if (hits.isNotEmpty()) mutate { strokes -> strokes.filterIndexed { i, _ -> i !in hits } }
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(currentSnapshot())
        restore(previous)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(currentSnapshot())
        restore(next)
    }

    private fun currentSnapshot() = Snapshot(_state.value.strokes, _state.value.page?.reviewedLineCount ?: 0)

    private fun restore(snapshot: Snapshot) {
        _state.update {
            it.copy(
                strokes = snapshot.strokes,
                page = it.page?.copy(reviewedLineCount = snapshot.reviewedLines),
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
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
        _feedback.trySend(Feedback.TICK)
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
        redoStack.clear()
        _state.update {
            it.copy(
                strokes = transform(current),
                page = if (reviewedLines != null) it.page?.copy(reviewedLineCount = reviewedLines) else it.page,
                canUndo = true,
                canRedo = false,
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

    /**
     * Un guardado a la vez: cada uno parte de la página que dejó el anterior. Sin esto, un guardado
     * de la tinta que venía atrasado podía pisar un cambio de estado (p. ej. devolver "revisada" a "pendiente").
     */
    private val saveMutex = Mutex()

    private suspend fun persist(transform: (DiaryPage) -> DiaryPage = { it }): DiaryPage? = saveMutex.withLock {
        if (_state.value.page == null) return@withLock null
        val strokes = _state.value.strokes
        val w = width
        // Serializar la página crece con todo lo escrito: va fuera del hilo principal para no trabar el lápiz.
        val inkJson = withContext(Dispatchers.Default) { InkSerializer.toJson(strokes, w) }
        // La página se lee después de serializar, para partir de lo último que haya en pantalla.
        val page = _state.value.page ?: return@withLock null
        val updated = transform(page).copy(
            inkJson = inkJson,
            canvasWidth = w.toInt(),
            updatedAt = System.currentTimeMillis(),
        )
        c.diary.save(updated)
        _state.update { it.copy(page = updated) }
        updated
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
                recognizedForReview = text
                persist { it.copy(reviewedLineCount = lines.maxOf { l -> l.line } + 1) }
                setPhase(Phase.Confirm(text))
            } catch (e: Exception) {
                setPhase(Phase.Writing, "No se pudo reconocer la escritura: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun cancelConfirm() = setPhase(Phase.Writing)

    /**
     * Lo que la app leyó en el "Revisar" en curso. Se guarda en la página recién al confirmar: es la
     * base con la que "Verificar" distingue lo reescrito de lo que ya estaba, y si se guardara al
     * pulsar "Revisar", cancelar el diálogo dejaría las reescrituras ya hechas como texto "de antes".
     */
    private var recognizedForReview: String? = null

    // ---------- Significado y ejemplos de las sugerencias ----------

    /** Tocar una sugerencia abre su tarjeta; tocarla otra vez la cierra. */
    fun toggleExplain(chip: SuggestionChip) {
        if (_explain.value?.chip == chip) {
            _explain.value = null
            return
        }
        viewModelScope.launch {
            val cached = c.explanations.get(chip.key)
            _explain.value = ExplainCard(chip, cached)
            if (cached == null) fetchExplanations()
        }
    }

    fun closeExplain() {
        _explain.value = null
    }

    fun retryExplain() = fetchExplanations()

    private fun updateExplain(transform: (ExplainCard) -> ExplainCard) = _explain.update { it?.let(transform) }

    /**
     * Pide en una sola llamada las explicaciones de todas las sugerencias de la página que aún no
     * tienen: tocar las demás después es instantáneo y no gasta más. Solo con API (no tiene sentido
     * compartir con un chat por cada palabra); sin API se queda lo que la app ya sabe.
     */
    private fun fetchExplanations() {
        if (explainJob?.isActive == true) {
            updateExplain { it.copy(loading = true, note = null, canRetry = false) }
            return
        }
        explainJob = viewModelScope.launch {
            val settings = c.settings.state.value
            val providerId = listOf(settings.defaultProvider, ProviderId.GEMINI, ProviderId.AZURE)
                .firstOrNull { it != ProviderId.SHARE && c.isConfigured(it) }
            val api = providerId?.let { c.apiProvider(it) }
            if (providerId == null || api == null) {
                updateExplain { it.copy(note = "Para ver el significado con ejemplos naturales, configura Gemini (es gratis) en Ajustes.") }
                return@launch
            }
            if (c.settings.usageToday(providerId) >= settings.dailyLimit(providerId)) {
                updateExplain { it.copy(note = "Llegaste al límite diario de ${providerName(providerId)}.") }
                return@launch
            }
            val open = _explain.value?.chip
            val missing = (listOfNotNull(open) + _state.value.suggestions)
                .distinctBy { it.key }
                .filter { c.explanations.get(it.key) == null }
                .take(MAX_EXPLAIN)
                .map { it.label to it.isGrammar }
            if (missing.isEmpty()) return@launch
            updateExplain { it.copy(loading = true, note = null, canRetry = false) }
            val error = try {
                val prompt = Prompts.explainItems(settings.profile, settings.level, settings.standardLevel, missing)
                val raw = api.complete(CorrectionRequest(prompt))
                c.settings.registerUse(providerId)
                c.explanations.putAll(ExplanationStore.match(missing, ExplainParser.parse(raw)))
                null
            } catch (e: ProviderException) {
                if (e.offline) "Sin conexión." else e.message ?: "Error del proveedor."
            } catch (e: ParseException) {
                "No se pudo leer la respuesta: ${e.message}"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            // La tarjeta abierta puede ser otra que la que se tocó primero.
            val current = _explain.value ?: return@launch
            val found = c.explanations.get(current.chip.key)
            _explain.value = current.copy(
                explanation = found,
                loading = false,
                note = error ?: if (found == null) "La IA no devolvió esta sugerencia." else null,
                canRetry = found == null,
            )
        }
    }

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
        react(MascotMood.SURPRISED, holdMs = 4_000)
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
                    recognizedText = recognizedForReview ?: it.recognizedText,
                    confirmedText = text.trim(),
                    status = if (it.status == PageStatus.REVIEWED) PageStatus.REVIEWED else PageStatus.PENDING,
                )
            } ?: return@launch
            val settings = c.settings.state.value
            val items = c.study.promptItems(ItemRef.decodeList(page.suggestedItems))
            val isScene = page.kind == PageKind.SCENE

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
                // En el scope de la app: si sales de la página mientras la IA responde, la corrección
                // igual se guarda (el ViewModel se limpia al volver atrás).
                reviewing.update { it + pageId }
                c.appScope.launch {
                    try {
                        val prompt = Prompts.correction(settings.profile, settings.level, settings.standardLevel, items, text, hasImage = false, scene = isScene)
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
                    } finally {
                        reviewing.update { it - pageId }
                    }
                }
            } else {
                val prompt = Prompts.correction(settings.profile, settings.level, settings.standardLevel, items, text, hasImage = true, scene = isScene)
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
        // Una sola transacción: correcciones, FSRS y el estado "revisada" quedan todos o ninguno.
        val summary = c.study.applyCorrection(page, response, System.currentTimeMillis())
        // El estado ya está en la base; esto lo refleja en pantalla y guarda la tinta más reciente.
        persist { it.copy(status = PageStatus.REVIEWED) }
        val parts = buildList {
            add(if (summary.corrections == 0) "¡Sin errores!" else "${summary.corrections} correcciones")
            if (summary.reviewed > 0) add("${summary.reviewed} ítems repasados")
            if (summary.newWords > 0) add("${summary.newWords} palabras nuevas")
            if (summary.newGrammar > 0) add("${summary.newGrammar} gramáticas nuevas")
            if (summary.improvements > 0) add("${summary.improvements} mejoras sugeridas")
            if (summary.pendingWords > 0) add("${summary.pendingWords} por revisar en Estudio")
        }
        react(
            when {
                summary.corrections == 0 -> MascotMood.HAPPY
                summary.newWords + summary.newGrammar > 0 -> MascotMood.SURPRISED
                else -> MascotMood.ENCOURAGE
            }
        )
        _feedback.trySend(if (summary.corrections == 0) Feedback.SUCCESS else Feedback.TICK)
        val hint = if (summary.corrections > 0) " Reescribe las correcciones debajo y pulsa “Verificar”." else ""
        setPhase(Phase.Writing, parts.joinToString(" · ") + "." + hint)
    }

    /**
     * Busca en la página las sugerencias pendientes ya reescritas. Sin IA. Solo cuenta lo que no
     * estaba cuando se revisó (ver [TextMatch.isRewritten]): ni el original ni repetir el mismo error.
     */
    fun verifyRewrite() {
        val page = _state.value.page ?: return
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
                val written = lines.joinToString("") { it.text }
                val recognizedBefore = page.recognizedText.orEmpty()
                val confirmedBefore = page.confirmedText.orEmpty()
                val done = withContext(Dispatchers.Default) {
                    pending.filter { TextMatch.isRewritten(written, it.suggestion, recognizedBefore, confirmedBefore) }
                }
                done.forEach { c.diary.markRewritten(it) }
                val ok = done.size
                react(
                    when {
                        ok == pending.size -> MascotMood.HAPPY
                        ok > 0 -> MascotMood.ENCOURAGE
                        else -> MascotMood.IDLE
                    }
                )
                _feedback.trySend(if (ok == pending.size) Feedback.SUCCESS else Feedback.FAIL)
                val read = lines.joinToString(" / ") { it.text }.let { if (it.length > 160) "…" + it.takeLast(160) else it }
                setPhase(Phase.Writing, "$ok de ${pending.size} correcciones bien reescritas. Leí: “$read”")
            } catch (e: Exception) {
                setPhase(Phase.Writing, "No se pudo reconocer: ${e.message}")
            }
        }
    }

    /** Para cuando el reconocimiento falla aunque lo hayas reescrito bien. */
    fun markRewritten(correction: Correction) {
        viewModelScope.launch {
            c.diary.markRewritten(correction)
            val left = c.diary.correctionsNow(pageId).count { it.status == CorrectionStatus.PENDING }
            if (left == 0) {
                react(MascotMood.HAPPY)
                _feedback.trySend(Feedback.SUCCESS)
            }
        }
    }

    private fun providerName(id: String) = when (id) {
        ProviderId.GEMINI -> "Gemini"
        ProviderId.AZURE -> "Azure OpenAI"
        else -> id
    }

    private fun setPhase(phase: Phase, message: String? = _state.value.message) =
        _state.update { it.copy(phase = phase, message = message) }

    private var deleted = false

    fun deletePage(onDone: () -> Unit) {
        val page = _state.value.page ?: return
        deleted = true
        saveJob?.cancel()
        viewModelScope.launch {
            c.diary.deletePage(page.id)
            onDone()
        }
    }

    override fun onCleared() {
        // Guardado final: el scope del ViewModel ya se canceló, así que se usa el de la app.
        if (deleted) return
        val page = _state.value.page ?: return
        val strokes = _state.value.strokes
        // Página abierta por error y sin escribir nada: se borra en vez de quedar vacía en la lista.
        // Se exige también que no haya trazos guardados: si sales antes de que cargue una página con
        // escritura, la lista en memoria aún está vacía y no debe borrarse.
        val savedInk = InkSerializer.fromJson(page.inkJson, 1f).isNotEmpty()
        if (strokes.isEmpty() && !savedInk && page.status == PageStatus.DRAFT && page.confirmedText.isNullOrBlank()) {
            c.appScope.launch { c.diary.deletePage(page.id) }
            return
        }
        val w = width
        // Solo la tinta: una revisión que sigue en curso puede cambiar el estado después de esto.
        if (w > 0f && strokesLoaded) {
            c.appScope.launch {
                c.diary.saveInk(page.id, InkSerializer.toJson(strokes, w), w.toInt(), System.currentTimeMillis())
            }
        }
    }

    companion object {
        /** Páginas con una revisión de la IA en curso; sigue aunque salgas de la página. */
        private val reviewing = MutableStateFlow<Set<Long>>(emptySet())

        /** Radio mínimo del borrador (px); el normal es ~18 % del alto de línea. */
        private const val MIN_ERASE_RADIUS = 24f
        /** Máximo de sugerencias por llamada de explicaciones (una página trae menos). */
        private const val MAX_EXPLAIN = 12
    }
}
