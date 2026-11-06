package com.jorge.koreandiary.ui.workbook

import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
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
            "Toma una foto de una página: la IA te da pistas por ejercicio (gramática, conectores, sustantivos) con su uso y un ejemplo. " +
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

    val session = state.session
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppTextButton(onClick = onBack) { Text("‹ Workbook") }
            Text(session?.let { formatDate(it.createdAt) } ?: "", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            AppTextButton(onClick = { vm.delete(onBack) }) { Text("Borrar") }
        }

        // Proveedor elegible en cada paso.
        Text("REVISAR CON", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            vm.providerOptions.forEach { (id, label) -> AppToggle(label, state.provider == id) { vm.setProvider(id) } }
        }

        // Paso 1: la página.
        StepHeader("1. La página")
        val pagePhoto = vm.photoFile(session?.pagePhoto)
        if (pagePhoto != null) PhotoPreview(pagePhoto)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppButton(onClick = { takePhoto(WorkbookStage.HINTS) }, enabled = state.busy == null) {
                Text(if (pagePhoto == null) "Tomar foto" else "Otra foto")
            }
            AppOutlinedButton(onClick = { pickPhoto(WorkbookStage.HINTS) }, enabled = state.busy == null) { Text("Galería") }
            if (pagePhoto != null && state.hints == null) {
                AppOutlinedButton(onClick = { vm.request(WorkbookStage.HINTS) }, enabled = state.busy == null) { Text("Pedir pistas") }
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
            hints.exercises.forEach { ex -> ExerciseCard(ex, reviewByNumber[ex.number]) }
            if (hints.vocab.isNotEmpty()) {
                Text(
                    "Vocabulario de la página: " + hints.vocab.joinToString(" · ") { v -> v.form + (v.meaning?.let { ": $it" } ?: "") },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }

            // Paso 2: revisar lo escrito.
            StepHeader("2. Tus respuestas")
            Text(
                "Resuelve los ejercicios en tu workbook y tómales una foto. Lo que escribiste cuenta para tu repaso" +
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
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StepHeader(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
}


@Composable
private fun PhotoPreview(file: File) {
    val bitmap by produceState<ImageBitmap?>(null, file.path, file.lastModified()) {
        value = withContext(Dispatchers.IO) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(file.path, opts)?.asImageBitmap()
        }
    }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = "Foto",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().height(180.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

@Composable
private fun ExerciseCard(ex: WorkbookExercise, review: WorkbookReviewItem?) {
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
                        Text(h.kind, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
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
