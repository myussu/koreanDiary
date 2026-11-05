package com.jorge.koreandiary.ui.page

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import com.jorge.koreandiary.data.AnnotatedItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jorge.koreandiary.AppContainer
import com.jorge.koreandiary.data.db.Correction
import com.jorge.koreandiary.data.db.CorrectionStatus
import com.jorge.koreandiary.data.db.PageStatus
import com.jorge.koreandiary.ink.InkCanvas
import com.jorge.koreandiary.ink.InkTool
import com.jorge.koreandiary.ink.rememberInkScrollState
import com.jorge.koreandiary.settings.ProviderId
import com.jorge.koreandiary.ui.AppButton
import com.jorge.koreandiary.ui.AppOutlinedButton
import com.jorge.koreandiary.ui.AppTag
import com.jorge.koreandiary.ui.AppTextButton
import com.jorge.koreandiary.ui.AppToggle
import com.jorge.koreandiary.ui.LocalPaperStyle
import com.jorge.koreandiary.ui.formatDate
import com.jorge.koreandiary.ui.paperBackground

@Composable
fun PageScreen(container: AppContainer, pageId: Long, onBack: () -> Unit) {
    val vm: PageViewModel = viewModel(key = "page-$pageId") { PageViewModel(container, pageId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val corrections by vm.corrections.collectAsStateWithLifecycle()
    val howTo by vm.howTo.collectAsStateWithLifecycle()
    var howToOpen by remember { mutableStateOf(false) }
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val paper = LocalPaperStyle.current
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    var tool by remember { mutableStateOf(InkTool.PEN) }
    val lineHeightPx = with(LocalDensity.current) { 64.dp.toPx() }
    val inkScroll = rememberInkScrollState()

    LaunchedEffect(vm) {
        vm.shareIntents.collect { context.startActivity(it) }
    }

    val page = state.page
    val pendingCorrections = corrections.count { it.status == CorrectionStatus.PENDING }

    // Al llegar correcciones (o al abrir una página revisada), baja hasta donde toca reescribir.
    LaunchedEffect(page?.id, corrections.size, page?.reviewedLineCount) {
        if (page != null && page.status != PageStatus.DRAFT && corrections.isNotEmpty()) {
            inkScroll.scrollToLine(page.reviewedLineCount)
        }
    }

    Column(Modifier.fillMaxSize().background(scheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTextButton(onClick = onBack) { Text("‹ Diario") }
            Text(
                page?.let { formatDate(it.createdAt) } ?: "",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            page?.let { AppTag(it.status, accent = it.status == PageStatus.PENDING, fontSize = 12.sp) }
        }

        SuggestionsBar(state.suggestions, onHowTo = { howToOpen = !howToOpen })

        if (howToOpen || howTo != HowTo.Idle) {
            HowToSayPanel(
                state = howTo,
                onAsk = vm::askHowTo,
                onPaste = vm::submitHowToPaste,
                onClose = {
                    howToOpen = false
                    vm.closeHowTo()
                },
            )
        }

        if (corrections.isNotEmpty()) CorrectionsPanel(corrections)

        state.message?.let { msg ->
            Surface(
                color = scheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().clickable { vm.dismissMessage() },
            ) {
                Text(msg, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        HorizontalDivider(color = scheme.outlineVariant)
        Box(Modifier.weight(1f).fillMaxWidth().paperBackground(paper)) {
            InkCanvas(
                strokes = state.strokes,
                tool = tool,
                stylusOnly = settings.stylusOnly,
                lineHeightPx = lineHeightPx,
                reviewedLines = page?.takeIf { it.status != PageStatus.DRAFT }?.reviewedLineCount ?: 0,
                inkColor = paper.ink,
                lineColor = paper.line,
                reviewedTint = paper.reviewedTint,
                onStrokeFinished = vm::addStroke,
                onErase = vm::eraseAt,
                scroll = inkScroll,
                // clipToBounds: si el lienzo se achica (panel abierto), los trazos no se dibujan encima de los botones.
                modifier = Modifier.fillMaxSize().clipToBounds().onSizeChanged {
                    vm.onCanvasSized(it.width.toFloat(), it.height.toFloat(), lineHeightPx)
                },
            )
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
            AppToggle("Lápiz", tool == InkTool.PEN) { tool = InkTool.PEN }
            AppToggle("Borrar", tool == InkTool.ERASER) { tool = InkTool.ERASER }
            AppTextButton(onClick = vm::undo, enabled = state.canUndo) { Text("Deshacer") }
            Spacer(Modifier.weight(1f))
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

@Composable
private fun SuggestionsBar(chips: List<SuggestionChip>, onHowTo: () -> Unit) {
    var expanded by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<SuggestionChip?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SUGERENCIAS", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            AppTextButton(onClick = onHowTo) { Text("¿Cómo digo?") }
            AppTextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Ocultar" else "Mostrar") }
        }
        if (!expanded) return@Column
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
                    onClick = { selected = if (isSelected) null else chip },
                )
            }
        }
        selected?.let {
            Text(it.detail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
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
 * "¿Cómo digo esto?": va arriba, bajo las sugerencias, para que la mano que escribe abajo no lo tape.
 * Al llegar la respuesta se cierra el teclado y queda una tarjeta plegable.
 */
@Composable
private fun HowToSayPanel(
    state: HowTo,
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
    fun ask() {
        focus.clearFocus()
        onAsk(idea)
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
                        placeholder = { Text("Lo que quieres decir, en español") },
                        maxLines = 3,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { ask() }),
                        modifier = Modifier.weight(1f),
                    )
                    AppButton(onClick = ::ask, enabled = idea.isNotBlank() && state != HowTo.Asking) { Text("Preguntar") }
                }
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
                is HowTo.Result -> if (expanded) HowToResult(state)
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
private fun CorrectionsPanel(corrections: List<Correction>) {
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
                    val done = c.status == CorrectionStatus.REWRITTEN
                    Row {
                        Text(
                            if (done) "✓" else "·",
                            color = if (done) scheme.tertiary else scheme.secondary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(18.dp),
                        )
                        Column {
                            Row {
                                Text(c.original, textDecoration = TextDecoration.LineThrough, color = scheme.error)
                                Text("  →  ", color = scheme.onSurfaceVariant)
                                Text(c.suggestion, fontWeight = FontWeight.SemiBold, color = scheme.tertiary)
                            }
                            Text("${c.type} · ${c.explanation}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
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
