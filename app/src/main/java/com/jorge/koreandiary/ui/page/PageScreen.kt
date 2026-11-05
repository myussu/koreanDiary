package com.jorge.koreandiary.ui.page

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.jorge.koreandiary.settings.ProviderId
import com.jorge.koreandiary.ui.formatDate

private val PaperColor = Color(0xFFFFFDF7)

@Composable
fun PageScreen(container: AppContainer, pageId: Long, onBack: () -> Unit) {
    val vm: PageViewModel = viewModel(key = "page-$pageId") { PageViewModel(container, pageId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val corrections by vm.corrections.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tool by remember { mutableStateOf(InkTool.PEN) }
    val lineHeightPx = with(LocalDensity.current) { 64.dp.toPx() }

    LaunchedEffect(vm) {
        vm.shareIntents.collect { context.startActivity(it) }
    }

    val page = state.page
    val pendingCorrections = corrections.count { it.status == CorrectionStatus.PENDING }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Diario") }
            Text(
                page?.let { formatDate(it.createdAt) } ?: "",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            page?.let { StatusPill(it.status) }
        }

        SuggestionsBar(state.suggestions)

        if (corrections.isNotEmpty()) CorrectionsPanel(corrections)

        state.message?.let { msg ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().clickable { vm.dismissMessage() },
            ) {
                Text(msg, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth().background(PaperColor)) {
            InkCanvas(
                strokes = state.strokes,
                tool = tool,
                stylusOnly = settings.stylusOnly,
                lineHeightPx = lineHeightPx,
                reviewedLines = page?.takeIf { it.status != PageStatus.DRAFT }?.reviewedLineCount ?: 0,
                onStrokeFinished = vm::addStroke,
                onErase = vm::eraseAt,
                modifier = Modifier.fillMaxSize().onSizeChanged {
                    vm.onCanvasSized(it.width.toFloat(), it.height.toFloat(), lineHeightPx)
                },
            )
            (state.phase as? Phase.Busy)?.let { busy ->
                Surface(
                    color = Color(0xCCFFFFFF),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.padding(8.dp))
                        Text(busy.message, Modifier.padding(horizontal = 32.dp))
                    }
                }
            }
        }

        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ToolButton("Lápiz", tool == InkTool.PEN) { tool = InkTool.PEN }
            ToolButton("Borrar", tool == InkTool.ERASER) { tool = InkTool.ERASER }
            TextButton(onClick = vm::undo, enabled = state.canUndo) { Text("Deshacer") }
            Spacer(Modifier.weight(1f))
            val busy = state.phase is Phase.Busy
            if (page?.status == PageStatus.PENDING) {
                TextButton(onClick = vm::openPaste, enabled = !busy) { Text("Pegar") }
            }
            if (pendingCorrections > 0) {
                OutlinedButton(onClick = vm::review, enabled = !busy) { Text("Revisar") }
                Button(onClick = vm::verifyRewrite, enabled = !busy) { Text("Verificar") }
            } else {
                Button(onClick = vm::review, enabled = !busy) { Text("Revisar") }
            }
        }
    }

    when (val phase = state.phase) {
        is Phase.Confirm -> ConfirmDialog(
            initial = phase.text,
            defaultProvider = settings.defaultProvider,
            onConfirm = vm::confirm,
            onDismiss = vm::cancelConfirm,
        )
        Phase.AwaitingPaste -> PasteDialog(onSubmit = vm::submitPasted, onDismiss = vm::cancelConfirm)
        else -> Unit
    }
}

@Composable
private fun StatusPill(status: String) {
    val color = when (status) {
        PageStatus.REVIEWED -> Color(0xFF2E7D4F)
        PageStatus.PENDING -> Color(0xFFB7791F)
        else -> Color(0xFF6B7280)
    }
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(50)) {
        Text(status, color = color, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun ToolButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp)) { Text(label) }
    else OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp)) { Text(label) }
}

@Composable
private fun SuggestionsBar(chips: List<SuggestionChip>) {
    var expanded by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<SuggestionChip?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sugerencias", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Ocultar" else "Mostrar") }
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
                Surface(
                    shape = RoundedCornerShape(50),
                    color = when {
                        isSelected -> MaterialTheme.colorScheme.primary
                        chip.isGrammar -> Color(0xFFF6E7E2)
                        else -> Color(0xFFE6ECF8)
                    },
                    modifier = Modifier.clickable { selected = if (isSelected) null else chip },
                ) {
                    Text(
                        chip.label,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else Color(0xFF1F2937),
                        fontSize = 17.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
        }
        selected?.let {
            Text(it.detail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.padding(bottom = 4.dp))
    }
}

@Composable
private fun CorrectionsPanel(corrections: List<Correction>) {
    Surface(color = Color(0xFFFFF7E6), modifier = Modifier.fillMaxWidth()) {
        LazyColumn(
            Modifier.heightIn(max = 190.dp).padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(corrections, key = { it.id }) { c ->
                val done = c.status == CorrectionStatus.REWRITTEN
                Row {
                    Text(if (done) "✓" else "•", color = if (done) Color(0xFF2E7D4F) else Color(0xFFB7791F), modifier = Modifier.width(18.dp))
                    Column {
                        Row {
                            Text(c.original, textDecoration = TextDecoration.LineThrough, color = Color(0xFF9B2C2C))
                            Text("  →  ")
                            Text(c.suggestion, fontWeight = FontWeight.SemiBold, color = Color(0xFF22543D))
                        }
                        Text("${c.type} · ${c.explanation}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    initial: String,
    defaultProvider: String,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿Esto es lo que escribiste?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Corrige lo que se haya leído mal antes de enviarlo.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.padding(4.dp))
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            }
        },
        confirmButton = {
            val geminiFirst = defaultProvider == ProviderId.GEMINI
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (geminiFirst) {
                    OutlinedButton(onClick = { onConfirm(text, ProviderId.SHARE) }) { Text("Compartir") }
                    Button(onClick = { onConfirm(text, ProviderId.GEMINI) }) { Text("Gemini") }
                } else {
                    OutlinedButton(onClick = { onConfirm(text, ProviderId.GEMINI) }) { Text("Gemini") }
                    Button(onClick = { onConfirm(text, ProviderId.SHARE) }) { Text("Compartir") }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
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
                TextButton(onClick = {
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
        confirmButton = { Button(onClick = { onSubmit(text) }, enabled = text.isNotBlank()) { Text("Procesar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}
