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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
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
import com.jorge.koreandiary.data.ExplanationStore
import com.jorge.koreandiary.ink.HandwritingRecognizer
import com.jorge.koreandiary.ink.InkCanvas
import com.jorge.koreandiary.ink.InkStroke
import com.jorge.koreandiary.ink.InkTool
import com.jorge.koreandiary.review.Cloze
import com.jorge.koreandiary.review.ClozeCard
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
    val tally: Map<Fsrs.Rating, Int> = emptyMap(),
) {
    val card: ClozeCard? get() = cards.getOrNull(index)
    val done: Boolean get() = !loading && cards.isNotEmpty() && index >= cards.size
}

/**
 * Repaso rápido: arma la sesión (tus oraciones y, con IA, una sola llamada para traducirlas y escribir
 * las que faltan), lee lo que escribes a mano y califica cada tarjeta al cerrarla: si sales a la mitad,
 * lo hecho ya contó.
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
            val provider = c.coachProvider()
            var note: String? = null
            val cards = if (provider == null) {
                note = "Sin IA van solo tus oraciones, sin traducción. Con Gemini (Ajustes › IA) también salen oraciones para lo que nunca usaste."
                Cloze.localCards(targets)
            } else if (c.settings.usageToday(provider.first) >= settings.dailyLimit(provider.first)) {
                note = "Llegaste al límite diario de la IA: van solo tus oraciones, sin traducción."
                Cloze.localCards(targets)
            } else {
                try {
                    val raw = provider.second.complete(CorrectionRequest(Cloze.prompt(settings.profile, settings.level, settings.standardLevel, targets)))
                    c.settings.registerUse(provider.first)
                    Cloze.parse(raw, targets)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    note = when (e) {
                        is ProviderException -> if (e.offline) "Sin conexión" else e.message ?: "Error de la IA"
                        is ParseException -> "No se pudo leer la respuesta de la IA"
                        else -> e.message ?: e.javaClass.simpleName
                    } + ": van solo tus oraciones, sin traducción."
                    Cloze.localCards(targets)
                }
            }
            _state.update {
                it.copy(
                    loading = false, cards = cards, note = note,
                    empty = if (cards.isEmpty()) "No encontré oraciones tuyas con lo que te toca repasar. Con la IA configurada, ella las escribe." else null,
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
            _state.update { it.copy(checking = false, read = candidates.firstOrNull()) }
            close(ClozeOutcome.RIGHT, Cloze.rating(_state.value.hints, _state.value.wrong, revealed = false))
        } else {
            _state.update {
                it.copy(checking = false, wrong = it.wrong + 1, outcome = ClozeOutcome.WRONG, read = candidates.firstOrNull() ?: "(nada)")
            }
        }
    }

    /** «Otra vez» después de fallar: limpia el recuadro y sigue la misma tarjeta. */
    fun retry() = _state.update { it.copy(outcome = null, strokes = emptyList()) }

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
            index = it.index + 1, hints = 0, wrong = 0, outcome = null, rating = null, read = null, copied = null, strokes = emptyList(), note = null,
            checking = false, explain = null, explaining = false, explainNote = null,
        )
    }

    /**
     * «Más sobre esto» al cerrar una tarjeta: significado, de qué partes se forma, uso, conjugación y
     * ejemplos. Sale de las explicaciones ya guardadas (las mismas de las sugerencias); si no está, se
     * pide a la IA una vez y queda guardada.
     */
    fun explain() {
        val card = _state.value.card ?: return
        if (_state.value.explaining) return
        val key = ExplanationStore.key(card.label, card.kind == ClozeKind.GRAMMAR)
        // La explicación es de esta tarjeta: si llega cuando ya pasaste a otra, queda guardada pero no
        // se muestra (antes salía bajo el título de la tarjeta siguiente).
        val index = _state.value.index
        _state.update { it.copy(explaining = true, explainNote = null) }
        viewModelScope.launch {
            c.explanations.get(key)?.let { found ->
                _state.update { if (it.index == index) it.copy(explain = found, explaining = false) else it }
                return@launch
            }
            val settings = c.settings.state.value
            val provider = c.coachProvider()
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
                    if (e.offline) "Sin conexión." else e.message ?: "Error de la IA."
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
            // La traducción, desde el inicio, con lo que falta resaltado.
            val translation = card.translation
            if (translation != null) {
                Text(
                    buildAnnotatedString {
                        Cloze.translationParts(translation).forEach { (text, marked) ->
                            if (marked) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = scheme.primary)) { append(text) } else append(text)
                        }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                Text(
                    card.meaning?.let { "Significa: $it" } ?: "Sin traducción",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            // La oración con el hueco; al cerrar la tarjeta, con la respuesta.
            Text(
                buildAnnotatedString {
                    append(card.before)
                    val blank = if (shown) card.answer else "＿".repeat(card.answer.count { !it.isWhitespace() }.coerceAtLeast(2))
                    val color = when (state.outcome) {
                        ClozeOutcome.RIGHT -> scheme.tertiary
                        ClozeOutcome.REVEALED -> scheme.error
                        else -> scheme.primary
                    }
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color)) { append(blank) }
                    append(card.after)
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                (if (card.own) "Tu oración" else "Oración de la IA") + " · " + when (card.kind) {
                    ClozeKind.WORD -> "palabra"
                    ClozeKind.VERB -> "verbo o adjetivo"
                    ClozeKind.GRAMMAR -> "gramática"
                },
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
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
                    state.explain?.let { ExplanationBody(it) }
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
