package com.jorge.koreandiary.ui.workbook

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jorge.koreandiary.AppContainer
import com.jorge.koreandiary.ai.CorrectionRequest
import com.jorge.koreandiary.ai.ParseException
import com.jorge.koreandiary.ai.Prompts
import com.jorge.koreandiary.ai.ProviderException
import com.jorge.koreandiary.ai.ShareFlow
import com.jorge.koreandiary.ai.WorkbookHints
import com.jorge.koreandiary.ai.WorkbookParser
import com.jorge.koreandiary.ai.WorkbookReview
import com.jorge.koreandiary.data.ItemSource
import com.jorge.koreandiary.data.db.GrammarPoint
import com.jorge.koreandiary.settings.ProviderId
import com.jorge.koreandiary.workbook.WorkbookSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Paso del workbook: pistas de la página o revisión de lo escrito. */
enum class WorkbookStage { HINTS, REVIEW }

data class WorkbookUiState(
    val session: WorkbookSession? = null,
    val hints: WorkbookHints? = null,
    val review: WorkbookReview? = null,
    val provider: String = ProviderId.SHARE,
    val busy: String? = null,
    val message: String? = null,
    /** Sin API: se compartió con un chat y falta pegar la respuesta de este paso. */
    val awaitingPaste: WorkbookStage? = null,
    /** Gramáticas de la página que ya están en tu lista. */
    val knownGrammar: Set<String> = emptySet(),
    /** Gramáticas marcadas para agregar a tu lista. */
    val grammarSelection: Set<String> = emptySet(),
    /** Gramática de la lección que practicas; null = que la IA la detecte en la página. */
    val focus: String? = null,
    /** Tu lista de gramática, para el buscador del selector. */
    val myGrammar: List<GrammarPoint> = emptyList(),
)

class WorkbookViewModel(private val c: AppContainer, private val sessionId: Long) : ViewModel() {

    private val _state = MutableStateFlow(WorkbookUiState())
    val state: StateFlow<WorkbookUiState> = _state.asStateFlow()

    private val _shareIntents = Channel<Intent>(Channel.BUFFERED)
    val shareIntents = _shareIntents.receiveAsFlow()

    /** Proveedores disponibles: las API configuradas y siempre "Compartir con un chat". */
    val providerOptions: List<Pair<String, String>>
        get() = listOf(
            ProviderId.GEMINI to "Gemini",
            ProviderId.AZURE to "Azure OpenAI",
            ProviderId.SHARE to "Compartir con un chat",
        ).filter { c.isConfigured(it.first) }

    init {
        val settings = c.settings.state.value
        val default = settings.defaultProvider.takeIf { c.isConfigured(it) }
            ?: providerOptions.first().first
        _state.update { it.copy(provider = default) }
        viewModelScope.launch {
            val session = c.workbook.load(sessionId) ?: return@launch
            val hints = session.hintsRaw?.let { raw -> runCatching { WorkbookParser.parseHints(raw) }.getOrNull() }
            val myGrammar = c.study.allGrammarPoints()
            _state.update {
                it.copy(
                    session = session,
                    hints = hints,
                    review = session.reviewRaw?.let { raw -> runCatching { WorkbookParser.parseReview(raw) }.getOrNull() },
                    myGrammar = myGrammar,
                    // Con pistas ya pedidas, la de esa vez; si no, la última que practicaste (otra página del mismo capítulo).
                    focus = if (hints != null) session.focus else defaultFocus(myGrammar),
                )
            }
            hints?.let { refreshPageGrammar(it) }
        }
    }

    // ---------- Gramática de la lección ----------

    /** La última elegida; si nunca elegiste, la última que agregaste desde una página; si no, que la IA la detecte. */
    private fun defaultFocus(myGrammar: List<GrammarPoint>): String? =
        c.settings.state.value.lessonGrammar.ifBlank { null }
            ?: myGrammar.filter { it.source == ItemSource.WORKBOOK }.maxByOrNull { it.id }?.pattern

    /** Elegir la gramática que practicas (null = detectar en la página). Se recuerda para las páginas siguientes. */
    fun setFocus(pattern: String?) {
        _state.update { it.copy(focus = pattern) }
        c.settings.update { it.copy(lessonGrammar = pattern ?: "") }
    }

    /** Las agregadas más recientemente, para elegir con un toque. */
    val recentGrammar: List<GrammarPoint>
        get() = _state.value.myGrammar.sortedByDescending { it.id }.take(6)

    // ---------- Gramática de la página ----------

    /** Marca cuáles ya tienes y preselecciona las de la lección (las "foco") que aún no están en tu lista. */
    private suspend fun refreshPageGrammar(hints: WorkbookHints) {
        // También las de las pistas, para marcar las que salen de tu lista.
        val forms = hints.grammar.map { it.form } + hints.exercises.flatMap { e -> e.hints.filter { it.isGrammar }.map { it.form } }
        val known = c.study.knownGrammar(forms.distinct())
        _state.update { st ->
            st.copy(
                knownGrammar = known,
                grammarSelection = hints.grammar.filter { it.focus && it.form !in known }.map { it.form }.toSet(),
            )
        }
    }

    fun toggleGrammar(form: String) = _state.update { st ->
        if (form in st.knownGrammar) st
        else st.copy(grammarSelection = if (form in st.grammarSelection) st.grammarSelection - form else st.grammarSelection + form)
    }

    fun addSelectedGrammar() {
        val st = _state.value
        val hints = st.hints ?: return
        val chosen = hints.grammar.filter { it.form in st.grammarSelection && it.form !in st.knownGrammar }
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            val added = c.study.registerWorkbookGrammar(chosen, hints.unit)
            refreshPageGrammar(hints)
            // La de la lección (★) pasa a ser la que practicas: las páginas siguientes ya la traen elegida.
            val lesson = chosen.firstOrNull { it.focus }?.let { added[it.form] }?.pattern
            if (lesson != null) {
                c.settings.update { it.copy(lessonGrammar = lesson) }
                val session = _state.value.session
                if (session != null && session.focus == null) {
                    val updated = session.copy(focus = lesson)
                    c.workbook.save(updated)
                    _state.update { it.copy(session = updated, focus = lesson) }
                }
            }
            val myGrammar = c.study.allGrammarPoints()
            _state.update {
                it.copy(
                    myGrammar = myGrammar,
                    message = when (added.size) {
                        0 -> "Ya estaban en tu lista."
                        1 -> "1 gramática agregada a tu lista. El diario te la va a sugerir."
                        else -> "${added.size} gramáticas agregadas a tu lista. El diario te las va a sugerir."
                    }
                )
            }
        }
    }

    fun setProvider(id: String) = _state.update { it.copy(provider = id) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** Foto de la cámara o la galería. Al guardarla, se pide el paso correspondiente. */
    fun onPhoto(stage: WorkbookStage, source: Uri) {
        viewModelScope.launch {
            val session = _state.value.session ?: return@launch
            _state.update { it.copy(busy = "Preparando la foto…", message = null) }
            try {
                val name = if (stage == WorkbookStage.HINTS) "pagina.jpg" else "respuestas.jpg"
                val file = c.workbook.importPhoto(session.id, source, name)
                c.workbook.captureFile(session.id).delete()
                val updated = if (stage == WorkbookStage.HINTS) session.copy(pagePhoto = file.name) else session.copy(reviewPhoto = file.name)
                c.workbook.save(updated)
                _state.update { it.copy(session = updated, busy = null) }
                request(stage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = null, message = "No se pudo guardar la foto: ${e.message}") }
            }
        }
    }

    fun request(stage: WorkbookStage) {
        viewModelScope.launch {
            val st = _state.value
            val session = st.session ?: return@launch
            val settings = c.settings.state.value
            val photoName = if (stage == WorkbookStage.HINTS) session.pagePhoto else session.reviewPhoto
            if (photoName == null) {
                _state.update { it.copy(message = "Primero toma la foto.") }
                return@launch
            }
            val prompt = when (stage) {
                WorkbookStage.HINTS -> {
                    // Se guarda con qué gramática se pidieron, para la revisión y para reabrir la sesión.
                    if (session.focus != st.focus) {
                        val updated = session.copy(focus = st.focus)
                        c.workbook.save(updated)
                        _state.update { it.copy(session = updated) }
                    }
                    val previous = c.study.previousGrammar(settings.grammarLevelList, st.focus, System.currentTimeMillis())
                    Prompts.workbookHints(settings.level, settings.standardLevel, st.focus, previous)
                }
                WorkbookStage.REVIEW -> {
                    val hints = st.hints ?: run {
                        _state.update { it.copy(message = "Primero pide las pistas de la página.") }
                        return@launch
                    }
                    val lesson = listOfNotNull(_state.value.session?.focus).ifEmpty { hints.grammar.filter { it.focus }.map { it.form } }
                    Prompts.workbookReview(settings.level, settings.standardLevel, hints.exercises, lesson)
                }
            }
            val photo = c.workbook.file(session.id, photoName)
            if (!withContext(Dispatchers.IO) { photo.exists() }) {
                _state.update { it.copy(message = "Esa foto ya no está en este celular (las fotos no viajan en la copia de Google). Toma otra.") }
                return@launch
            }

            if (st.provider == ProviderId.SHARE) {
                _shareIntents.send(ShareFlow.photoIntent(c.app, prompt, photo))
                _state.update { it.copy(awaitingPaste = stage, message = "El prompt también quedó copiado. Copia la respuesta del chat y pégala aquí.") }
                return@launch
            }
            val api = c.apiProvider(st.provider) ?: run {
                _state.update { it.copy(message = "Falta configurar ese proveedor en Ajustes.") }
                return@launch
            }
            val limit = settings.dailyLimit(st.provider)
            if (c.settings.usageToday(st.provider) >= limit) {
                _state.update { it.copy(message = "Llegaste al límite diario de ese proveedor ($limit).") }
                return@launch
            }
            _state.update {
                it.copy(busy = if (stage == WorkbookStage.HINTS) "Leyendo la página y buscando pistas…" else "Revisando tus respuestas…", message = null)
            }
            try {
                val bytes = withContext(Dispatchers.IO) { photo.readBytes() }
                val raw = api.complete(CorrectionRequest(prompt, bytes, "image/jpeg"))
                c.settings.registerUse(st.provider)
                handle(stage, raw)
            } catch (e: ProviderException) {
                _state.update { it.copy(busy = null, message = if (e.offline) "Sin conexión." else e.message) }
            } catch (e: ParseException) {
                _state.update { it.copy(busy = null, message = "No se pudo leer la respuesta: ${e.message}") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = null, message = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun submitPaste(raw: String) {
        val stage = _state.value.awaitingPaste ?: return
        viewModelScope.launch {
            try {
                handle(stage, raw)
                _state.update { it.copy(awaitingPaste = null) }
            } catch (e: ParseException) {
                _state.update { it.copy(message = "No se pudo leer la respuesta: ${e.message}") }
            }
        }
    }

    private suspend fun handle(stage: WorkbookStage, raw: String) {
        val session = _state.value.session ?: return
        when (stage) {
            WorkbookStage.HINTS -> {
                val hints = WorkbookParser.parseHints(raw)
                // Vocabulario impreso: entra a tu base como "visto", sin contar como repaso.
                val added = c.study.registerSeenWords(hints.vocab)
                val updated = session.copy(
                    hintsRaw = raw,
                    vocabAdded = session.vocabAdded + added,
                    exerciseCount = hints.exercises.size,
                )
                c.workbook.save(updated)
                val vocabNote = if (added > 0) " $added palabras de la página pasaron a tu vocabulario." else ""
                val grammarNote = if (hints.grammar.isNotEmpty()) " Elige qué gramática agregar a tu lista." else ""
                val exercisesNote = if (hints.exercises.isEmpty()) "Página sin ejercicios." else "${hints.exercises.size} ejercicios con pistas."
                _state.update {
                    it.copy(session = updated, hints = hints, busy = null, message = "$exercisesNote$vocabNote$grammarNote")
                }
                refreshPageGrammar(hints)
            }
            WorkbookStage.REVIEW -> {
                val review = WorkbookParser.parseReview(raw)
                var updated = session.copy(reviewRaw = raw)
                var fsrsNote = ""
                if (!session.fsrsApplied) {
                    // Solo lo que escribiste cuenta; lo visto en pistas o impreso cuenta como sugerido ("Hard").
                    val hints = _state.value.hints
                    val suggested = if (hints == null) emptySet() else {
                        // Lo impreso en la página también lo viste: si lo usas, cuenta como "Hard".
                        val grammar = hints.exercises.flatMap { e -> e.hints.filter { it.isGrammar }.map { it.form } } +
                            hints.grammar.map { it.form }
                        val words = hints.exercises.flatMap { e -> e.hints.filterNot { it.isGrammar }.map { it.form to "" } } +
                            hints.vocab.map { it.form to "" }
                        c.study.annotate(grammar, words).mapNotNull { it.ref }.toSet()
                    }
                    val usage = c.study.applyUsage(review.used, suggested, System.currentTimeMillis(), pageId = null)
                    updated = updated.copy(fsrsApplied = true, reviewedItems = usage.reviewed)
                    fsrsNote = " ${usage.reviewed} ítems repasados" +
                        (if (usage.newWords + usage.newGrammar > 0) " (${usage.newWords + usage.newGrammar} nuevos)" else "") + "."
                }
                c.workbook.save(updated)
                val ok = review.items.count { it.status == com.jorge.koreandiary.ai.ReviewStatus.OK }
                _state.update {
                    it.copy(session = updated, review = review, busy = null, message = "$ok de ${review.items.size} respuestas bien.$fsrsNote")
                }
            }
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            c.workbook.delete(sessionId)
            onDone()
        }
    }

    /** Foto guardada de la sesión, o null si no hay o ya no está en este celular. */
    fun photoFile(name: String?): File? =
        name?.let { n -> _state.value.session?.let { c.workbook.file(it.id, n) } }?.takeIf { it.exists() }
}
