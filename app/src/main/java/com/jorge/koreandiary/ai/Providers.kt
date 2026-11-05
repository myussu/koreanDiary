package com.jorge.koreandiary.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class CorrectionRequest(val prompt: String, val imagePng: ByteArray? = null)

/**
 * Capa intermedia: cada proveedor (Gemini, Azure, Claude, un servidor propio...) implementa esto
 * y devuelve el texto crudo de la respuesta. El parseo es común (CorrectionParser).
 */
interface CorrectionProvider {
    val id: String
    suspend fun complete(request: CorrectionRequest): String
}

class ProviderException(message: String, val offline: Boolean = false) : Exception(message)

/** Gemini por la Interactions API (v1beta). La capa gratuita de Google AI Studio basta para un diario. */
class GeminiProvider(
    private val apiKey: String,
    private val model: String,
) : CorrectionProvider {
    override val id = "gemini"

    override suspend fun complete(request: CorrectionRequest): String = withContext(Dispatchers.IO) {
        val input = JSONArray().put(JSONObject().put("type", "text").put("text", request.prompt))
        request.imagePng?.let {
            input.put(
                JSONObject().put("type", "image").put("mime_type", "image/png")
                    .put("data", android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP))
            )
        }
        val body = JSONObject()
            .put("model", model)
            .put("input", input)
            // No guardar la conversación en los servidores de Google.
            .put("store", false)

        val conn = try {
            (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 90_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", apiKey)
                setRequestProperty("Api-Revision", API_REVISION)
            }
        } catch (e: IOException) {
            throw ProviderException("No se pudo conectar: ${e.message}", offline = true)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
                throw ProviderException("Gemini respondió $code: ${message ?: text.take(300)}")
            }
            extractText(JSONObject(text)) ?: throw ProviderException("Gemini no devolvió texto.")
        } catch (e: IOException) {
            throw ProviderException("Sin conexión: ${e.message}", offline = true)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
        private const val API_REVISION = "2026-05-20"

        /** Interactions API: steps[].content[] con type "text". Acepta también el formato de generateContent. */
        internal fun extractText(json: JSONObject): String? {
            json.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
            val steps = json.optJSONArray("steps")
            if (steps != null) {
                for (i in steps.length() - 1 downTo 0) {
                    val content = steps.optJSONObject(i)?.optJSONArray("content") ?: continue
                    val text = buildString {
                        for (j in 0 until content.length()) {
                            val block = content.optJSONObject(j) ?: continue
                            if (block.optString("type") == "text") append(block.optString("text"))
                        }
                    }
                    if (text.isNotBlank()) return text
                }
            }
            val parts = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            if (parts != null) {
                val text = buildString { for (j in 0 until parts.length()) append(parts.optJSONObject(j)?.optString("text").orEmpty()) }
                if (text.isNotBlank()) return text
            }
            return null
        }
    }
}

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
}

fun Bitmap.toPngBytes(): ByteArray =
    java.io.ByteArrayOutputStream().use { out ->
        compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }
