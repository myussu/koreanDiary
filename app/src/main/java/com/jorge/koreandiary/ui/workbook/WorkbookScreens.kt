package com.jorge.koreandiary.ui.workbook

import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import com.jorge.koreandiary.data.db.GrammarPoint
import com.jorge.koreandiary.workbook.GrammarSearch
import androidx.compose.ui.semantics.Role
import com.jorge.koreandiary.ai.WorkbookGrammar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jorge.koreandiary.AppContainer
import com.jorge.koreandiary.ai.ReviewStatus
import com.jorge.koreandiary.ai.WorkbookExercise
import com.jorge.koreandiary.ai.WorkbookReviewItem
import com.jorge.koreandiary.ui.AppButton
import com.jorge.koreandiary.ui.AppOutlinedButton
import com.jorge.koreandiary.ui.AppTag
import com.jorge.koreandiary.ui.AppTextButton
import com.jorge.koreandiary.ui.AppToggle
import com.jorge.koreandiary.ui.formatDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Lista de páginas de workbook trabajadas. */
@Composable
fun WorkbookListScreen(container: AppContainer, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val sessions by container.workbook.sessions.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { container.workbook.refresh() }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppTextButton(onClick = onBack) { Text("‹ Diario") }
            Text("Workbook", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "Sirve para cualquier página de tu libro (student book o workbook). La IA detecta su gramática y tú eliges cuál agregar a tu lista. " +
                "Si tiene ejercicios, te da pistas para combinar la gramática de la lección con las que ya estudiaste. " +
                "Después fotografías lo que escribiste y te dice qué está bien, qué corregir y cómo lo diría un coreano.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        AppButton(
            onClick = { scope.launch { onOpen(container.workbook.create().id) } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Nueva página") }
        sessions.forEach { s ->
            Card(Modifier.fillMaxWidth().padding(top = 8.dp).clickable { onOpen(s.id) }) {
                Column(Modifier.padding(14.dp)) {
                    Text(formatDate(s.createdAt), style = MaterialTheme.typography.titleSmall)
                    val status = when {
                        s.reviewRaw != null -> "revisada · ${s.exerciseCount} ejercicios"
                        s.hintsRaw != null -> "con pistas · ${s.exerciseCount} ejercicios"
                        s.pagePhoto != null -> "foto lista"
                        else -> "sin foto"
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun WorkbookSessionScreen(container: AppContainer, sessionId: Long, onBack: () -> Unit) {
    val vm: WorkbookViewModel = viewModel(key = "workbook-$sessionId") { WorkbookViewModel(container, sessionId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var pendingStage by rememberSaveable { mutableStateOf(WorkbookStage.HINTS) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(vm) { vm.shareIntents.collect { context.startActivity(it) } }

    val captureUri = remember(sessionId) {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", container.workbook.captureFile(sessionId))
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) vm.onPhoto(pendingStage, captureUri)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) vm.onPhoto(pendingStage, uri)
    }
    fun takePhoto(stage: WorkbookStage) {
        pendingStage = stage
        camera.launch(captureUri)
    }
    fun pickPhoto(stage: WorkbookStage) {
        pendingStage = stage
        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Borrar esta página del workbook?") },
            text = { Text("Se borran las fotos, las pistas y la revisión. Lo que ya contó para tu repaso se conserva.") },
            confirmButton = { AppButton(onClick = { confirmDelete = false; vm.delete(onBack) }) { Text("Borrar") } },
            dismissButton = { AppTextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }

    val session = state.session
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppTextButton(onClick = onBack) { Text("‹ Workbook") }
            Text(session?.let { formatDate(it.createdAt) } ?: "", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            AppTextButton(onClick = { confirmDelete = true }) { Text("Borrar") }
        }

        // Proveedor elegible en cada paso.
        Text("REVISAR CON", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            vm.providerOptions.forEach { (id, label) -> AppToggle(label, state.provider == id) { vm.setProvider(id) } }
        }

        // Paso 1: la página.
        StepHeader("1. La página")
        LessonPicker(
            focus = state.focus,
            recent = vm.recentGrammar,
            all = state.myGrammar,
            enabled = state.busy == null,
            onSelect = vm::setFocus,
        )
        val pagePhoto = vm.photoFile(session?.pagePhoto)
        if (pagePhoto != null) PhotoPreview(pagePhoto)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppButton(onClick = { takePhoto(WorkbookStage.HINTS) }, enabled = state.busy == null) {
                Text(if (pagePhoto == null) "Tomar foto" else "Otra foto")
            }
            AppOutlinedButton(onClick = { pickPhoto(WorkbookStage.HINTS) }, enabled = state.busy == null) { Text("Galería") }
        }
        // Sin pistas aún, o cambiaste la gramática de la lección después de pedirlas.
        if (pagePhoto != null && (state.hints == null || state.focus != session?.focus)) {
            AppOutlinedButton(onClick = { vm.request(WorkbookStage.HINTS) }, enabled = state.busy == null) {
                Text(if (state.hints == null) "Pedir pistas" else "Pedir pistas otra vez con esta gramática")
            }
        }

        state.busy?.let {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = scheme.primary, trackColor = scheme.surfaceContainerHighest)
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        state.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().border(1.dp, scheme.outlineVariant).clickable { vm.dismissMessage() }.padding(10.dp),
            )
        }
        if (state.awaitingPaste != null) {
            AppOutlinedButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let(vm::submitPaste)
            }) { Text("Pegar respuesta del chat") }
        }

        // Pistas por ejercicio (y la revisión de cada uno, si ya la hay).
        state.hints?.let { hints ->
            val reviewByNumber = state.review?.items?.associateBy { it.number } ?: emptyMap()
            if (hints.grammar.isNotEmpty()) {
                PageGrammarCard(
                    grammar = hints.grammar,
                    known = state.knownGrammar,
                    selected = state.grammarSelection,
                    unit = hints.unit,
                    onToggle = vm::toggleGrammar,
                    onAdd = vm::addSelectedGrammar,
                )
            }
            hints.exercises.forEach { ex -> ExerciseCard(ex, reviewByNumber[ex.number], state.knownGrammar) }
            if (hints.vocab.isNotEmpty()) {
                Text(
                    "Vocabulario de la página: " + hints.vocab.joinToString(" · ") { v -> v.form + (v.meaning?.let { ": $it" } ?: "") },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }

            if (hints.exercises.isEmpty()) {
                Text(
                    "Esta página no tiene ejercicios: solo se detectó su gramática y vocabulario.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            } else {
                // Paso 2: revisar lo escrito.
                StepHeader("2. Tus respuestas")
                Text(
                    "Resuelve los ejercicios en tu libro y tómales una foto. Lo que escribiste cuenta para tu repaso" +
                        if (session?.fsrsApplied == true) " (ya se contó en la primera revisión)." else ".",
                    style = MaterialTheme.typography.bodySmall,
                )
                val reviewPhoto = vm.photoFile(session?.reviewPhoto)
                if (reviewPhoto != null) PhotoPreview(reviewPhoto)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppButton(onClick = { takePhoto(WorkbookStage.REVIEW) }, enabled = state.busy == null) {
                        Text(if (state.review == null) "Foto de mis respuestas" else "Revisar otra vez")
                    }
                    AppOutlinedButton(onClick = { pickPhoto(WorkbookStage.REVIEW) }, enabled = state.busy == null) { Text("Galería") }
                }
                // Respuestas que la IA no pudo asociar a un ejercicio de las pistas.
                state.review?.items?.filter { it.number !in hints.exercises.map { e -> e.number } }?.forEach { item ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { ReviewBlock(item) } }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StepHeader(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
}


@Composable
private fun PhotoPreview(file: File) {
    var zoomOpen by remember { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(null, file.path, file.lastModified()) {
        value = withContext(Dispatchers.IO) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(file.path, opts)?.asImageBitmap()
        }
    }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = "Foto (toca para ampliar)",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().height(180.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                .clickable { zoomOpen = true },
        )
        Text("Toca la foto para ampliarla.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (zoomOpen) ZoomDialog(file) { zoomOpen = false }
}

/** Foto a pantalla completa con zoom (pellizcar) y desplazamiento, para leer la página con detalle. */
@Composable
private fun ZoomDialog(file: File, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        // Versión con centroide (la de 3 parámetros quedó obsoleta).
        val transform = rememberTransformableState { _, zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 6f)
            offset = if (scale == 1f) Offset.Zero else offset + pan
        }
        val bitmap by produceState<ImageBitmap?>(null, file.path) {
            value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
        }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim)) {
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = "Foto ampliada",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().transformable(transform).graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                )
            }
            AppButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) { Text("Cerrar") }
        }
    }
}

/**
 * Todas las gramáticas impresas en la página. Solo entran a tu lista las que elijas: la de la lección
 * sí, la de las instrucciones o los textos de alrededor no. Las marcadas ★ son las que la IA cree que enseña la lección.
 */
@Composable
private fun PageGrammarCard(
    grammar: List<WorkbookGrammar>,
    known: Set<String>,
    selected: Set<String>,
    unit: Int?,
    onToggle: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // Primero las de la lección, luego las que te faltan, al final las que ya tienes.
    val ordered = remember(grammar, known) { grammar.sortedWith(compareBy({ !it.focus }, { it.form in known })) }
    val toAdd = selected.count { it !in known }
    Column(
        Modifier.fillMaxWidth().border(1.dp, scheme.outlineVariant).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "GRAMÁTICA DE LA PÁGINA" + (unit?.let { " · UNIDAD $it" } ?: ""),
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            "Elige la que estás aprendiendo para agregarla a tu lista; el diario te la sugerirá. ★ = parece ser la de esta lección.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        ordered.forEach { g ->
            val isKnown = g.form in known
            Row(
                Modifier.fillMaxWidth()
                    .toggleable(value = isKnown || g.form in selected, enabled = !isKnown, role = Role.Checkbox) { onToggle(g.form) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = isKnown || g.form in selected, onCheckedChange = null, enabled = !isKnown)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(g.form, fontSize = 17.sp, fontWeight = if (g.focus) FontWeight.SemiBold else FontWeight.Normal)
                        if (g.focus) Text("★", color = scheme.secondary)
                    }
                    val detail = listOfNotNull(g.meaning, g.where, if (isKnown) "ya en tu lista" else null).joinToString(" · ")
                    if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
        }
        if (toAdd > 0) {
            AppButton(onClick = onAdd, modifier = Modifier.padding(top = 4.dp)) {
                Text(if (toAdd == 1) "Agregar 1 a mi lista" else "Agregar $toAdd a mi lista")
            }
        }
    }
}

/**
 * "¿Qué gramática practicas?": viene elegida la última; se cambia con un toque entre las recientes,
 * con el buscador (ignora guiones, A/V/N y (으)) o dejando que la IA la detecte en la página.
 */
@Composable
private fun LessonPicker(
    focus: String?,
    recent: List<GrammarPoint>,
    all: List<GrammarPoint>,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val scheme = MaterialTheme.colorScheme
    val current = remember(focus, all) { focus?.let { f -> all.firstOrNull { it.pattern == f } } }
    fun choose(pattern: String?) {
        onSelect(pattern)
        open = false
        query = ""
    }
    Column(
        Modifier.fillMaxWidth().border(1.dp, scheme.outlineVariant).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("GRAMÁTICA QUE PRACTICAS", style = MaterialTheme.typography.labelMedium)
                Text(
                    focus ?: "Detectar en la página",
                    fontSize = 18.sp,
                    fontWeight = if (focus != null) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (focus != null) scheme.onSurface else scheme.onSurfaceVariant,
                )
                current?.meaning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
            }
            AppTextButton(onClick = { open = !open }, enabled = enabled) { Text(if (open) "Cerrar" else "Cambiar") }
        }
        if (open) {
            Text(
                "Las pistas no te la repiten: la combinan con gramáticas que ya estudiaste.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppTag("Detectar en la página", selected = focus == null, fontSize = 15.sp) { choose(null) }
                recent.forEach { g -> AppTag(g.pattern, selected = g.pattern == focus, fontSize = 15.sp) { choose(g.pattern) } }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Buscar en tu lista (느라, 텐데, causa…)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (query.isNotBlank()) {
                val results = remember(query, all) { GrammarSearch.search(query, all, limit = 8) }
                if (results.isEmpty()) {
                    Text(
                        "No está en tu lista. Elige “Detectar en la página” y agrégala desde la foto.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                results.forEach { g ->
                    Row(
                        Modifier.fillMaxWidth().clickable { choose(g.pattern) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(g.pattern, fontSize = 17.sp)
                            g.meaning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                        }
                        Text(
                            listOfNotNull(g.level, g.unit?.let { "u. $it" }).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExerciseCard(ex: WorkbookExercise, review: WorkbookReviewItem?, known: Set<String>) {
    var showAnswer by remember(ex.number) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ejercicio ${ex.number}", style = MaterialTheme.typography.titleSmall)
            if (ex.ask.isNotBlank()) Text(ex.ask, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            ex.hints.forEach { h ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppTag(h.form, accent = h.isGrammar, fontSize = 16.sp)
                        Text(
                            h.kind + if (h.form in known) " · de tu lista" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (h.form in known) scheme.secondary else scheme.onSurfaceVariant,
                        )
                    }
                    if (h.use.isNotBlank()) Text(h.use, style = MaterialTheme.typography.bodySmall)
                    if (h.example.isNotBlank()) Text(h.example, fontSize = 17.sp)
                    if (h.english.isNotBlank()) Text(h.english, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            if (ex.answer.isNotBlank()) {
                AppTextButton(onClick = { showAnswer = !showAnswer }) { Text(if (showAnswer) "Ocultar respuesta" else "Ver respuesta") }
                if (showAnswer) Text(ex.answer, fontSize = 17.sp, color = scheme.tertiary)
            }
            review?.let { ReviewBlock(it) }
        }
    }
}

@Composable
private fun ReviewBlock(item: WorkbookReviewItem) {
    val scheme = MaterialTheme.colorScheme
    val (label, color) = when (item.status) {
        ReviewStatus.OK -> "✓ Bien" to scheme.tertiary
        ReviewStatus.FIX -> "Corregir" to scheme.error
        ReviewStatus.NATURAL -> "Correcto · más natural así" to scheme.secondary
    }
    Column(
        Modifier.fillMaxWidth().border(1.dp, scheme.outlineVariant).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("TU RESPUESTA ${item.number} · $label", style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Medium)
        if (item.written.isNotBlank()) Text(item.written, fontSize = 17.sp)
        item.corrected?.let { Text("→ $it", fontSize = 17.sp, color = scheme.tertiary, fontWeight = FontWeight.SemiBold) }
        item.natural?.let { Text("Natural: $it", fontSize = 16.sp, color = scheme.secondary) }
        if (item.explanation.isNotBlank()) Text(item.explanation, style = MaterialTheme.typography.bodySmall)
    }
}
