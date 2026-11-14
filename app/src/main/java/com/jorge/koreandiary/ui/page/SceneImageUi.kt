package com.jorge.koreandiary.ui.page

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jorge.koreandiary.scene.SceneImageJob
import com.jorge.koreandiary.scene.SceneImages
import com.jorge.koreandiary.ui.AppButton
import com.jorge.koreandiary.ui.AppOutlinedButton
import com.jorge.koreandiary.ui.AppTextButton

/**
 * La imagen de la escena de fondo, muy suave y recortada a la forma de la hoja, detrás de la escena
 * viva y de la tinta.
 */
@Composable
fun SceneImageBackground(images: SceneImages, pageId: Long, dark: Boolean, modifier: Modifier = Modifier) {
    val version by images.version.collectAsStateWithLifecycle()
    val bitmap by produceState<ImageBitmap?>(null, pageId, version) { value = images.full(pageId, 1600) }
    val b = bitmap ?: return
    Image(
        b,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alpha = if (dark) 0.18f else 0.14f,
        modifier = modifier.fillMaxSize(),
    )
}

/** Miniatura de la escena (lista de escenas y la ventana de la imagen); nada si no tiene. */
@Composable
fun SceneThumb(images: SceneImages, pageId: Long, width: Dp = 48.dp, height: Dp = 72.dp) {
    val version by images.version.collectAsStateWithLifecycle()
    val thumb by produceState<ImageBitmap?>(null, pageId, version) { value = images.thumbnail(pageId) }
    val t = thumb ?: return
    Image(
        t,
        contentDescription = "Imagen de la escena",
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(width, height).border(1.dp, MaterialTheme.colorScheme.outlineVariant),
    )
}

/**
 * «Imagen»: pintarla en tu servidor, o traerla del chat de Gemini (copiar el prompt, pegarlo allá y
 * elegir la imagen que te dé), y quitarla.
 */
@Composable
fun SceneImageDialog(images: SceneImages, pageId: Long, vm: PageViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val jobs by images.jobs.collectAsStateWithLifecycle()
    val version by images.version.collectAsStateWithLifecycle()
    val hasImage = remember(version) { images.has(pageId) }
    val job = jobs[pageId]
    val serverReady = images.serverReady()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.importSceneImage(uri)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Imagen de la escena") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SceneThumb(images, pageId, width = 96.dp, height = 168.dp)
                Text(
                    when {
                        job == SceneImageJob.Painting -> "Pintando en tu servidor… Tarda medio minuto o un poco más; puedes seguir escribiendo."
                        job is SceneImageJob.Failed -> job.message
                        hasImage -> "Sale de fondo, muy suave, y en la lista de escenas."
                        else -> "Todavía no tiene imagen."
                    },
                    color = if (job is SceneImageJob.Failed) scheme.error else scheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!serverReady) {
                    Text(
                        "Sin servidor: «Copiar prompt para Gemini», pégalo en la app de Gemini y después «Elegir imagen» con la que te dé. " +
                            "Tu servidor de ComfyUI se configura en Ajustes → «Imagen de la escena».",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                AppButton(onClick = vm::paintSceneImage, enabled = serverReady && job != SceneImageJob.Painting) {
                    Text(if (hasImage) "Pintar otra vez" else "Pintar en mi servidor")
                }
                AppOutlinedButton(onClick = { sharePrompt(context, vm.sceneImageChatPrompt()) }) { Text("Copiar prompt para Gemini") }
                AppOutlinedButton(onClick = {
                    images.dismiss(pageId)
                    pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("Elegir imagen") }
                if (hasImage) AppTextButton(onClick = vm::removeSceneImage) { Text("Quitar imagen") }
            }
        },
        confirmButton = { AppTextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

/** Copia el prompt y abre el menú de compartir, para elegir la app de Gemini directo. */
private fun sharePrompt(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(ClipData.newPlainText("Prompt de la escena", text))
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Pegar en Gemini"))
}
