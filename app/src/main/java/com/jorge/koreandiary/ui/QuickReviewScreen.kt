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
import com.jorge.koreandiary.ai.CorrectionRequest
import com.jorge.koreandiary.ai.ParseException
import com.jorge.koreandiary.ai.ProviderException
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
    /** Lo último que escribiste, tal como lo leyó la app. */
    val read: String? = null,
    val checking: Boolean = false,
    val strokes: List<InkStroke> = emptyList(),
    /** Una nota arriba (sin IA, error); no frena el repaso. */
    val note: String? = null,
    /** Mensaje cuando no hay tarjetas. */
    val empty: String? = null,
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
                _state.update { it.copy(loading = false, empty = "Todavía no hay nada para repasar: importa tu lista o escribe en el diario.") }
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

    fun clearInk() = _state.update { it.copy(strokes = emptyList(), read = if (it.outcome == ClozeOutcome.WRONG) it.read else null) }

    fun hint() = _state.update { if (it.outcome == null || it.outcome == ClozeOutcome.WRONG) it.copy(hints = (it.hints + 1).coerceAtMost(3)) else it }

    /** Lee lo escrito a mano ([lineHeight] = alto del recuadro, así todo cae en un renglón) y lo compara. */
    fun checkInk(lineHeight: Float, width: Float) {
        val s = _state.value
        val card = s.card ?: return
        if (s.checking) return
        if (s.strokes.isEmpty()) {
            _state.update { it.copy(note = "Escribe la respuesta en el recuadro primero.") }
            return
        }
        _state.update { it.copy(checking = true) }
        viewModelScope.launch {
            val candidates = try {
                if (!c.recognizer.isModelDownloaded()) c.recognizer.downloadModel()
                val lines = c.recognizer.recognizeLines(s.strokes, lineHeight, width)
                listOf(lines.joinToString("") { it.text }) + lines.firstOrNull()?.alternatives.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(checking = false, note = "No pude leer tu letra: ${e.message ?: e.javaClass.simpleName}") }
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

    fun reveal() = close(ClozeOutcome.REVEALED, Cloze.rating(_state.value.hints, _state.value.wrong, revealed = true))

    private fun close(outcome: ClozeOutcome, rating: Fsrs.Rating) {
        val card = _state.value.card ?: return
        _state.update { it.copy(outcome = outcome, tally = it.tally + (rating to (it.tally[rating] ?: 0) + 1)) }
        // En el alcance de la app: el resultado se guarda aunque cierres la pantalla enseguida.
        c.appScope.launch { c.study.applyQuickReview(card.code, rating, System.currentTimeMillis()) }
    }

    fun next() = _state.update {
        it.copy(index = it.index + 1, hints = 0, wrong = 0, outcome = null, read = null, strokes = emptyList(), note = null)
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
    val paper = LocalPaperStyle.current
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
                ClozeOutcome.RIGHT -> Text(
                    if (state.hints <= 1 && state.wrong == 0) "Bien." else "Bien, con ayuda: vuelve un poco antes.",
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.tertiary,
                )
                ClozeOutcome.WRONG -> Text(
                    "Leí «${state.read}». No es lo del hueco: prueba otra vez, abre una pista o, si tu forma también es correcta, «La mía también vale».",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.error,
                )
                ClozeOutcome.REVEALED -> Text("Era «${card.answer}». Vuelve pronto.", style = MaterialTheme.typography.titleMedium, color = scheme.error)
                null -> {}
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
                    Box(
                        Modifier.fillMaxWidth().height(120.dp).border(1.dp, scheme.outlineVariant)
                            .paperBackground(paper).clipToBounds().onSizeChanged { padWidth = it.width.toFloat() },
                    ) {
                        InkCanvas(
                            strokes = state.strokes,
                            tool = InkTool.PEN,
                            stylusOnly = stylusOnly,
                            lineHeightPx = padHeightPx,
                            reviewedLines = 0,
                            inkColor = paper.ink,
                            lineColor = paper.line,
                            reviewedTint = paper.reviewedTint,
                            strokeWidthPx = with(density) { penWidthDp.dp.toPx() },
                            pressureEnabled = pressure,
                            onStrokeFinished = vm::addStroke,
                            onErase = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (state.strokes.isEmpty()) {
                            Text(
                                "Escribe aquí lo que va en el hueco",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }
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
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.End) {
                AppTextButton(onClick = vm::reveal) { Text("Ver respuesta") }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.End) {
                AppButton(onClick = vm::next) { Text(if (state.index + 1 < state.cards.size) "Siguiente" else "Terminar") }
            }
        }
    }
}
