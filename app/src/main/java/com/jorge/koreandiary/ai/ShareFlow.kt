package com.jorge.koreandiary.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import java.io.File

/**
 * Flujo sin API: comparte el prompt y la imagen de la página con la app de Claude o ChatGPT.
 * El usuario copia la respuesta y la pega en la app. El prompt también queda en el portapapeles,
 * porque algunas apps ignoran el texto cuando se comparte junto con una imagen.
 */
object ShareFlow {
    fun buildIntent(context: Context, prompt: String, page: Bitmap): Intent {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "pagina.png")
        file.outputStream().use { page.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("prompt", prompt))

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, prompt)
            clipData = ClipData.newRawUri("pagina", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Enviar página a…")
    }

    /** Comparte una foto ya guardada (p. ej. la página del workbook) junto con el prompt. */
    fun photoIntent(context: Context, prompt: String, photo: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photo)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("prompt", prompt))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, prompt)
            clipData = ClipData.newRawUri("pagina", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Enviar página a…")
    }

    /** Solo texto (para "¿Cómo digo esto?" sin API). También queda en el portapapeles. */
    fun textIntent(context: Context, prompt: String): Intent {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("prompt", prompt))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, prompt)
        }
        return Intent.createChooser(send, "Preguntar a…")
    }
}

fun Bitmap.toPngBytes(): ByteArray =
    java.io.ByteArrayOutputStream().use { out ->
        compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }
