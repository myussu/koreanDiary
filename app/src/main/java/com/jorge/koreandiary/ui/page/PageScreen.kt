package com.jorge.koreandiary.ui.page

import android.Manifest
import android.content.ClipboardManager
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jorge.koreandiary.AppContainer
import com.jorge.koreandiary.data.AnnotatedItem
import com.jorge.koreandiary.data.db.Correction
import com.jorge.koreandiary.data.db.CorrectionStatus
import com.jorge.koreandiary.data.db.PageKind
import com.jorge.koreandiary.data.db.PageStatus
import com.jorge.koreandiary.ink.InkCanvas
import com.jorge.koreandiary.ink.InkTool
import com.jorge.koreandiary.ink.rememberInkScrollState
import com.jorge.koreandiary.mascot.MascotMood
import com.jorge.koreandiary.settings.ProviderId
import com.jorge.koreandiary.ui.AppButton
import com.jorge.koreandiary.ui.AppMascot
import com.jorge.koreandiary.ui.AppOutlinedButton
import com.jorge.koreandiary.ui.AppTag
import com.jorge.koreandiary.ui.AppTextButton
import com.jorge.koreandiary.ui.AppToggle
import com.jorge.koreandiary.ui.LocalPaperStyle
import com.jorge.koreandiary.ui.formatDate
import com.jorge.koreandiary.ui.pageStatusLabel
import com.jorge.koreandiary.ui.paperBackground

@Composable
fun PageScreen(container: AppContainer, pageId: Long, onBack: () -> Unit) {
    val vm: PageViewModel = viewModel(key = "page-$pageId") { PageViewModel(container, pageId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val corrections by vm.corrections.collectAsStateWithLifecycle()
    val howTo by vm.howTo.collectAsStateWithLifecycle()
    val explain by vm.explain.collectAsStateWithLifecycle()
    val voice by vm.voice.collectAsStateWithLifecycle()
    val reaction by vm.mascotMood.collectAsStateWithLifecycle()
    var howToOpen by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val paper = LocalPaperStyle.current
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    var tool by rememberSaveable { mutableStateOf(InkTool.PEN) }
    val density = LocalDensity.current
    val lineHeightPx = with(density) { 64.dp.toPx() }
    val strokeWidthPx = with(density) { settings.penWidthDp.dp.toPx() }
    // Pantalla encendida mientras estás en la página: pensar qué escribir no debería apagarla.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val inkScroll = rememberInkScrollState()

    LaunchedEffect(vm) {
        vm.shareIntents.collect { context.startActivity(it) }
    }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(vm) {
        vm.feedback.collect {
            haptics.performHapticFeedback(
                when (it) {
                    Feedback.SUCCESS -> HapticFeedbackType.Confirm
                    Feedback.FAIL -> HapticFeedbackType.Reject
                    Feedback.TICK -> HapticFeedbackType.SegmentTick
                }
            )
        }
    }

    val page = state.page
    val pendingCorrections = corrections.count { it.status == CorrectionStatus.PENDING }

    // Al llegar correcciones (o al abrir una página revisada), baja hasta donde toca reescribir.
    LaunchedEffect(page?.id, corrections.size, page?.reviewedLineCount) {
        if (page != null && page.status != PageStatus.DRAFT && corrections.isNotEmpty()) {
            inkScroll.scrollToLine(page.reviewedLineCount)
        }
    }

    // Mientras la IA trabaja, la mascota piensa; si no, muestra su última reacción.
    val mascotMood = if (state.phase is Phase.Busy || howTo == HowTo.Asking || explain?.loading == true) MascotMood.THINKING else reaction

    val isScene = page?.kind == PageKind.SCENE
    val header: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTextButton(onClick = onBack) { Text(if (isScene) "‹ Escenas" else "‹ Diario") }
            Text(
                page?.let { formatDate(it.createdAt) } ?: "",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            page?.let { AppTag(pageStatusLabel(it.status), accent = it.status == PageStatus.PENDING, fontSize = 12.sp) }
            AppTextButton(onClick = { confirmDelete = true }) { Text("Borrar") }
        }
    }

    // Sugerencias, "¿Cómo digo?", correcciones y avisos: arriba en vertical, al costado en horizontal.
    val panels: @Composable () -> Unit = {
        SuggestionsBar(
            state.suggestions,
            newGrammar = state.newGrammar,
            // Escenas: sugerencias que siguen a lo que llevas escrito, también después de revisar
            // (se sigue escribiendo).
            onRefresh = if (isScene) vm::refreshSuggestions else null,
            refreshing = state.refreshing,
            explain = explain,
            onChip = vm::toggleExplain,
            onCloseExplain = vm::closeExplain,
            onRetryExplain = vm::retryExplain,
            onHowTo = { howToOpen = !howToOpen },
            mascot = if (settings.showMascot) {
                { AppMascot(container.mascotPack, mascotMood, 36.dp, Modifier.padding(end = 6.dp)) }
            } else null,
        )

        if (howToOpen || howTo != HowTo.Idle) {
            HowToSayPanel(
                state = howTo,
                tiered = isScene,
                voice = voice,
                onDictate = vm::dictateHowTo,
                onMore = vm::moreHowTo,
                onAsk = vm::askHowTo,
                onPaste = vm::submitHowToPaste,
                onClose = {
                    howToOpen = false
                    vm.closeHowTo()
                },
            )
        }

        if (corrections.isNotEmpty()) CorrectionsPanel(corrections, onMarkDone = vm::markRewritten, onDismissNote = vm::dismissNote)

        state.message?.let { msg ->
            Surface(
                color = scheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().clickable { vm.dismissMessage() },
            ) {
                Text(msg, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    val paperAndTools: @Composable ColumnScope.(paperWidth: Dp) -> Unit = { paperWidth ->
        Box(Modifier.weight(1f).fillMaxWidth().paperBackground(paper)) {
            // Escena viva: detrás del lienzo, así la tinta queda siempre encima con su contraste.
            if (isScene && settings.liveScene && (state.sceneElements.isNotEmpty() || state.sceneMood != null)) {
                SceneVignette(state.sceneElements, state.sceneMood, dark = paper.background.luminance() < 0.5f, modifier = Modifier.fillMaxSize())
            }
            InkCanvas(
                strokes = state.strokes,
                tool = tool,
                stylusOnly = settings.stylusOnly,
                lineHeightPx = lineHeightPx,
                reviewedLines = page?.takeIf { it.status != PageStatus.DRAFT }?.reviewedLineCount ?: 0,
                inkColor = paper.ink,
                lineColor = paper.line,
                reviewedTint = paper.reviewedTint,
                strokeWidthPx = strokeWidthPx,
                pressureEnabled = settings.penPressure,
                onStrokeFinished = vm::addStroke,
                onErase = vm::eraseAt,
                onInsertLine = { line ->
                    vm.insertLine(line)
                    // Una inserción por toque, como en Excel; luego se vuelve al lápiz.
                    tool = InkTool.PEN
                },
                scroll = inkScroll,
                // Ancho fijo de hoja (centrada si sobra espacio). clipToBounds: si el lienzo se achica
                // (panel abierto), los trazos no se dibujan encima de los botones.
                modifier = Modifier.align(Alignment.TopCenter).width(paperWidth).fillMaxHeight().clipToBounds().onSizeChanged {
                    vm.onCanvasSized(it.width.toFloat(), it.height.toFloat(), lineHeightPx)
                },
            )
            if (tool == InkTool.INSERT_LINE) {
                // Text con fondo (no Surface): un Surface bloquearía los toques sobre las primeras líneas.
                Text(
                    "Toca la línea donde quieres una línea vacía: esa y las de abajo bajan un renglón.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onPrimary,
                    modifier = Modifier.fillMaxWidth().background(scheme.primary.copy(alpha = 0.9f)).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            (state.phase as? Phase.Busy)?.let { busy ->
                Surface(color = scheme.background.copy(alpha = 0.85f), modifier = Modifier.fillMaxSize()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(color = scheme.primary, strokeWidth = 2.dp)
                        Spacer(Modifier.padding(8.dp))
                        Text(busy.message, Modifier.padding(horizontal = 32.dp))
                    }
                }
            }
        }

        HorizontalDivider(color = scheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Herramientas a la izquierda (se desplazan si no caben); acciones fijas a la derecha.
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppToggle("Lápiz", tool == InkTool.PEN) { tool = InkTool.PEN }
                AppToggle("Borrar", tool == InkTool.ERASER) { tool = InkTool.ERASER }
                AppToggle("+ Línea", tool == InkTool.INSERT_LINE) {
                    tool = if (tool == InkTool.INSERT_LINE) InkTool.PEN else InkTool.INSERT_LINE
                }
                AppTextButton(onClick = vm::undo, enabled = state.canUndo) { Text("Deshacer") }
                AppTextButton(onClick = vm::redo, enabled = state.canRedo) { Text("Rehacer") }
            }
            val busy = state.phase is Phase.Busy
            if (page?.status == PageStatus.PENDING) {
                AppTextButton(onClick = vm::openPaste, enabled = !busy) { Text("Pegar") }
            }
            if (pendingCorrections > 0) {
                AppOutlinedButton(onClick = vm::review, enabled = !busy) { Text("Revisar") }
                AppButton(onClick = vm::verifyRewrite, enabled = !busy) { Text("Verificar") }
            } else {
                AppButton(onClick = vm::review, enabled = !busy) { Text("Revisar") }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(scheme.background)) {
        // La hoja mide siempre el ancho del celular en vertical: lo escrito girado se ve igual al volver.
        val paperWidth = minOf(rememberPaperWidth(), maxWidth)
        // En horizontal sobra ancho: los paneles van a la izquierda (la mano derecha no los tapa)
        // y la hoja usa todo el alto, en vez de quedar aplastada debajo de ellos.
        val sideBySide = maxWidth > maxHeight && maxWidth - paperWidth >= 240.dp
        if (sideBySide) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    header()
                    panels()
                }
                VerticalDivider(color = scheme.outlineVariant)
                Column(Modifier.width(paperWidth).fillMaxHeight()) {
                    paperAndTools(paperWidth)
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                header()
                panels()
                HorizontalDivider(color = scheme.outlineVariant)
                paperAndTools(paperWidth)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (isScene) "¿Borrar esta escena?" else "¿Borrar esta página?") },
            text = { Text("Se borran la escritura, las correcciones y las oraciones. Lo que ya contó para tu repaso se conserva.") },
            confirmButton = { AppButton(onClick = { confirmDelete = false; vm.deletePage(onBack) }) { Text("Borrar") } },
            dismissButton = { AppTextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }

    when (val phase = state.phase) {
        is Phase.Confirm -> {
            // Solo proveedores configurados; el predeterminado primero.
            val options = listOf(
                ProviderId.SHARE to "Compartir con un chat",
                ProviderId.GEMINI to "Gemini",
                ProviderId.AZURE to "Azure OpenAI",
            ).filter { container.isConfigured(it.first) }
                .sortedByDescending { it.first == settings.defaultProvider }
            ConfirmDialog(
                initial = phase.text,
                options = options,
                onConfirm = vm::confirm,
                onDismiss = vm::cancelConfirm,
            )
        }
        Phase.AwaitingPaste -> PasteDialog(onSubmit = vm::submitPasted, onDismiss = vm::cancelConfirm)
        else -> Unit
    }
}

/** Lado corto de la pantalla completa (con barras incluidas): el ancho del celular en vertical. */
@Composable
private fun rememberPaperWidth(): Dp {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    return remember(configuration, density) {
        val wm = context.getSystemService(WindowManager::class.java)
        val px = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            minOf(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
            minOf(metrics.widthPixels, metrics.heightPixels)
        }
        with(density) { px.toDp() }
    }
}

@Composable
private fun SuggestionsBar(
    chips: List<SuggestionChip>,
    newGrammar: List<SuggestionChip>,
    onRefresh: (() -> Unit)?,
    refreshing: Boolean,
    explain: ExplainCard?,
    onChip: (SuggestionChip) -> Unit,
    onCloseExplain: () -> Unit,
    onRetryExplain: () -> Unit,
    onHowTo: () -> Unit,
    mascot: (@Composable () -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(true) }
    val selected = explain?.chip
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            mascot?.invoke()
            Text("SUGERENCIAS", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            onRefresh?.let { refresh ->
                AppTextButton(onClick = refresh, enabled = !refreshing) { Text(if (refreshing) "Buscando…" else "Actualizar") }
            }
            AppTextButton(onClick = onHowTo) { Text("¿Cómo digo?") }
            AppTextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Ocultar" else "Mostrar") }
        }
        if (!expanded) return@Column
        // Escenas: las gramáticas nuevas, un paso por encima de tu nivel, en su propio grupo.
        if (newGrammar.isNotEmpty()) {
            Text(
                "NUEVAS PARA ESTA ESCENA",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                items(newGrammar) { chip ->
                    AppTag(text = "★ ${chip.label}", selected = chip == selected, accent = true, fontSize = 17.sp, onClick = { onChip(chip) })
                }
            }
        }
        if (chips.isEmpty()) {
            Text(
                "Todavía no hay vocabulario ni gramática. Impórtalos en Estudio.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            return@Column
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(chips) { chip ->
                val isSelected = chip == selected
                AppTag(
                    text = if (chip.isGap) "+ ${chip.label}" else chip.label,
                    selected = isSelected,
                    accent = chip.isGrammar,
                    fontSize = 17.sp,
                    onClick = { onChip(chip) },
                )
            }
        }
        explain?.let { SuggestionCard(it, onClose = onCloseExplain, onRetry = onRetryExplain) }
        if (selected == null && chips.any { it.isGap }) {
            Text(
                "“+” = posible laguna: de tu nivel según el estándar, pero no está en tus listas.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.padding(bottom = 4.dp))
    }
}

/**
 * Significado, uso natural y ejemplos de una sugerencia. Va arriba, bajo las sugerencias, para que la
 * mano que escribe no la tape; tiene alto máximo y desplazamiento para no comerse el papel.
 */
@Composable
private fun SuggestionCard(card: ExplainCard, onClose: () -> Unit, onRetry: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val chip = card.chip
    val e = card.explanation
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp)
            .border(1.dp, scheme.outlineVariant)
            .heightIn(max = 280.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(chip.label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (chip.isGrammar) "  gramática" else "  palabra",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            AppTextButton(onClick = onClose) { Text("Cerrar") }
        }
        if (e != null) {
            Text(e.meaning, style = MaterialTheme.typography.bodyLarge)
            e.usage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            e.conjugation?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.secondary) }
            e.examples.forEach { ex ->
                Column(Modifier.padding(top = 4.dp)) {
                    Text(ex.korean, fontSize = 17.sp)
                    ex.english?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                }
            }
        } else {
            // Lo que la app ya sabe, mientras llega la IA o si no hay API.
            Text(chip.detail, style = MaterialTheme.typography.bodySmall)
        }
        if (chip.isGap) {
            Text(
                "“+” = posible laguna: de tu nivel según el estándar, pero no está en tus listas.",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
        if (card.loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            Text("Buscando el significado y ejemplos naturales…", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        card.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.error) }
        if (card.canRetry) AppTextButton(onClick = onRetry) { Text("Reintentar") }
    }
}

/**
 * "¿Cómo digo esto?": va arriba, bajo las sugerencias, para que la mano que escribe abajo no lo tape.
 * Al llegar la respuesta se cierra el teclado y queda una tarjeta plegable.
 */
@Composable
private fun HowToSayPanel(
    state: HowTo,
    /** Escenas: ayuda por partes y dictado. */
    tiered: Boolean,
    voice: HowToVoice,
    onDictate: () -> Unit,
    onMore: () -> Unit,
    onAsk: (String) -> Unit,
    onPaste: (String) -> Unit,
    onClose: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    var idea by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(true) }
    var expanded by remember { mutableStateOf(true) }
    LaunchedEffect(state) {
        if (state is HowTo.Result) {
            editing = false
            expanded = true
            focus.clearFocus()
        }
    }
    // Lo que vas dictando aparece en el campo; al terminar de hablar se pregunta solo.
    LaunchedEffect(voice.text) {
        if (voice.text.isNotEmpty()) idea = voice.text
    }
    fun ask() {
        focus.clearFocus()
        onAsk(idea)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onDictate()
    }
    fun dictate() {
        focus.clearFocus()
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted || voice.listening) onDictate() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    Surface(color = scheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("¿CÓMO DIGO ESTO?", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                if (state is HowTo.Result) {
                    AppTextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Plegar" else "Ver") }
                    AppTextButton(onClick = { editing = true; expanded = true }) { Text("Otra") }
                }
                AppTextButton(onClick = onClose) { Text("Cerrar") }
            }
            if (editing || state !is HowTo.Result) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = idea,
                        onValueChange = { idea = it },
                        placeholder = { Text(if (tiered) "Dila o escríbela, en español" else "Lo que quieres decir, en español") },
                        maxLines = 3,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { ask() }),
                        modifier = Modifier.weight(1f),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (tiered) {
                            AppOutlinedButton(onClick = ::dictate, enabled = state != HowTo.Asking) {
                                Text(if (voice.listening) "Escuchando… (parar)" else "Dictar")
                            }
                        }
                        AppButton(onClick = ::ask, enabled = idea.isNotBlank() && state != HowTo.Asking && !voice.listening) { Text("Preguntar") }
                    }
                }
                voice.error?.let { Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            when (state) {
                HowTo.Asking -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = scheme.primary, trackColor = scheme.surfaceContainerHighest)
                HowTo.AwaitingPaste -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Envié la pregunta a un chat (también quedó copiada). Copia su respuesta y pégala.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    AppOutlinedButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let(onPaste)
                    }) { Text("Pegar") }
                }
                is HowTo.Error -> Text(state.message, color = scheme.error, style = MaterialTheme.typography.bodySmall)
                is HowTo.Result -> if (expanded) {
                    if (tiered) TieredHowToResult(state, onMore) else HowToResult(state)
                }
                HowTo.Idle -> Unit
            }
        }
    }
}

@Composable
private fun HowToResult(result: HowTo.Result) {
    val scheme = MaterialTheme.colorScheme
    val r = result.response
    Text(if (r.natural == null) "CON LO QUE SABES (Y ES NATURAL)" else "CON LO QUE SABES", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
    Text(r.mine.text, fontSize = 22.sp)
    AnnotatedTags(result.mineTags)
    r.natural?.let { nat ->
        Text("COMO LO DIRÍA UN COREANO", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        Text(nat.text, fontSize = 22.sp)
        AnnotatedTags(result.naturalTags)
    }
    if (r.note.isNotBlank()) Text(r.note, style = MaterialTheme.typography.bodySmall)
    if (result.wordTags.isNotEmpty()) {
        Text(
            result.wordTags.joinToString("  ·  ") { w ->
                (if (w.known) "" else "+") + w.label + (w.meaning?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
            },
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }
    if ((result.mineTags + result.naturalTags + result.wordTags).any { !it.known }) {
        Text("“+” = no está en tus listas (el número es su nivel en el estándar).", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
    }
}

/**
 * Escenas: la ayuda por partes, para que intentes armar la frase antes de verla.
 * 1) las palabras; 2) cómo unirlas (partículas y terminación, sin la frase); 3) la frase.
 */
@Composable
private fun TieredHowToResult(result: HowTo.Result, onMore: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val r = result.response
    Text("1 · PALABRAS", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
    if (result.wordTags.isEmpty()) {
        Text("La IA no separó las palabras; pide la siguiente pista.", style = MaterialTheme.typography.bodySmall)
    } else {
        result.wordTags.forEach { w ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (w.known) "" else "+ ") + w.label, fontSize = 20.sp, color = if (w.known) scheme.onSurface else scheme.secondary)
                w.meaning?.takeIf { it.isNotBlank() }?.let {
                    Text("  $it", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
    if (result.shown >= 2) {
        Text("2 · CÓMO UNIRLAS", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        Text(r.hint?.takeIf { it.isNotBlank() } ?: "La IA no dio la pista; mira la gramática que usa.", style = MaterialTheme.typography.bodyMedium)
        AnnotatedTags(result.mineTags)
    }
    if (result.shown >= 3) {
        Text("3 · LA FRASE", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        Text(r.mine.text, fontSize = 22.sp)
        r.natural?.let { nat ->
            Text("COMO LO DIRÍA UN COREANO", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Text(nat.text, fontSize = 22.sp)
            AnnotatedTags(result.naturalTags)
        }
        if (r.note.isNotBlank()) Text(r.note, style = MaterialTheme.typography.bodySmall)
        Text("Escríbela a mano en la página.", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (result.shown == 1) "Intenta armar la frase con estas palabras." else "Intenta escribirla antes de verla.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            AppOutlinedButton(onClick = onMore) { Text(if (result.shown == 1) "Más ayuda" else "Ver la frase") }
        }
    }
    if ((result.wordTags + result.mineTags).any { !it.known }) {
        Text("“+” = no está en tus listas.", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun AnnotatedTags(tags: List<AnnotatedItem>) {
    if (tags.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(tags) { t ->
            val label = if (t.known) t.label else "+ ${t.label}" + (t.level?.let { " · $it" } ?: "")
            AppTag(text = label, accent = !t.known)
        }
    }
}

@Composable
private fun CorrectionsPanel(corrections: List<Correction>, onMarkDone: (Correction) -> Unit, onDismissNote: (Correction) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(true) }
    val pending = corrections.count { it.status == CorrectionStatus.PENDING }
    Surface(color = scheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (pending > 0) "CORRECCIONES · $pending por reescribir" else "CORRECCIONES",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                // Plegar libera espacio para escribir; la página igual se desplaza con el dedo.
                AppTextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Plegar" else "Ver") }
            }
            if (expanded) LazyColumn(
                Modifier.heightIn(max = 190.dp).padding(horizontal = 12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(corrections, key = { it.id }) { c ->
                    if (c.status == CorrectionStatus.SUGGESTION) {
                        Row {
                            Text("+", color = scheme.secondary, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp))
                            Column {
                                Text(c.suggestion, fontWeight = FontWeight.SemiBold, color = scheme.secondary)
                                Text("Mejora de tu nivel · ${c.explanation}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            }
                        }
                        return@items
                    }
                    // Escenas: lo escrito dice otra cosa que tu idea. Solo un aviso: no se reescribe.
                    if (c.status == CorrectionStatus.NOTE) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("≈", color = scheme.secondary, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp))
                            Column(Modifier.weight(1f)) {
                                Row {
                                    Text(c.original, color = scheme.onSurface)
                                    Text("  ·  si querías: ", color = scheme.onSurfaceVariant)
                                    Text(c.suggestion, fontWeight = FontWeight.SemiBold, color = scheme.secondary)
                                }
                                Text("Sentido · ${c.explanation}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            }
                            AppTextButton(onClick = { onDismissNote(c) }) { Text("Descartar") }
                        }
                        return@items
                    }
                    val done = c.status == CorrectionStatus.REWRITTEN
                    Row {
                        Text(
                            if (done) "✓" else "·",
                            color = if (done) scheme.tertiary else scheme.secondary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(18.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Row {
                                Text(c.original, textDecoration = TextDecoration.LineThrough, color = scheme.error)
                                Text("  →  ", color = scheme.onSurfaceVariant)
                                Text(c.suggestion, fontWeight = FontWeight.SemiBold, color = scheme.tertiary)
                            }
                            Text("${c.type} · ${c.explanation}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                        // Si "Verificar" no la detecta aunque esté bien reescrita, se marca a mano.
                        if (!done) AppTextButton(onClick = { onMarkDone(c) }) { Text("Hecho") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    initial: String,
    options: List<Pair<String, String>>,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿Esto es lo que escribiste?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Corrige lo que se haya leído mal antes de enviarlo.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                Text("REVISAR CON", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                options.forEachIndexed { i, (id, label) ->
                    if (i == 0) AppButton(onClick = { onConfirm(text, id) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    else AppOutlinedButton(onClick = { onConfirm(text, id) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { AppTextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun PasteDialog(onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pega la respuesta del chat") },
        text = {
            Column {
                Text("Copia toda la respuesta (el JSON) y pégala aquí.", style = MaterialTheme.typography.bodySmall)
                AppTextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { text = it }
                }) { Text("Pegar del portapapeles") }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp),
                )
            }
        },
        confirmButton = { AppButton(onClick = { onSubmit(text) }, enabled = text.isNotBlank()) { Text("Procesar") } },
        dismissButton = { AppTextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}
