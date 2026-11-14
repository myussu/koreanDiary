package com.jorge.koreandiary.scene

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.jorge.koreandiary.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlin.random.Random

/** Qué pasa con la imagen de una escena mientras se pinta. */
sealed interface SceneImageJob {
    data object Painting : SceneImageJob
    data class Failed(val message: String) : SceneImageJob
}

/**
 * La imagen de cada escena: `filesDir/scene_images/<página>.jpg` y su miniatura `<página>_t.jpg`.
 * La pinta tu servidor de ComfyUI (por Tailscale) o la eliges tú (la que trajiste del chat de Gemini).
 * Vive en el `AppContainer`: pintar tarda medio minuto o más y sigue aunque salgas de la página.
 * La carpeta entra en la copia exportada y en el paso de celular a celular, no en la nube de Google.
 */
class SceneImages(
    private val app: Application,
    private val scope: CoroutineScope,
    private val settings: () -> AppSettings,
) {
    private val dir = File(app.filesDir, DIR)

    private val _jobs = MutableStateFlow<Map<Long, SceneImageJob>>(emptyMap())
    val jobs: StateFlow<Map<Long, SceneImageJob>> = _jobs.asStateFlow()

    /** Sube cada vez que una imagen se guarda o se borra: las pantallas vuelven a leer. */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    fun file(pageId: Long) = File(dir, "$pageId.jpg")
    private fun thumbFile(pageId: Long) = File(dir, "${pageId}_t.jpg")

    fun has(pageId: Long): Boolean = file(pageId).exists()

    /** El servidor está configurado (dirección y modelo). */
    fun serverReady(): Boolean = SceneImage.normalizeServer(settings().imageServer) != null && settings().imageCheckpoint.isNotBlank()

    suspend fun thumbnail(pageId: Long): ImageBitmap? = withContext(Dispatchers.IO) {
        val f = thumbFile(pageId).takeIf { it.exists() } ?: return@withContext null
        BitmapFactory.decodeFile(f.path)?.asImageBitmap()
    }

    /** La imagen completa, reducida para no pasar de [maxSide] píxeles en el lado largo. */
    suspend fun full(pageId: Long, maxSide: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val f = file(pageId).takeIf { it.exists() } ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }

    /** Pinta la escena en tu servidor con la descripción que dio la IA al revisar. */
    fun paint(pageId: Long, description: String) {
        if (_jobs.value[pageId] == SceneImageJob.Painting) return
        val s = settings()
        val server = SceneImage.normalizeServer(s.imageServer)
        if (server == null || s.imageCheckpoint.isBlank()) {
            fail(pageId, "Configura tu servidor de imágenes en Ajustes → «Imagen de la escena».")
            return
        }
        if (description.isBlank()) {
            fail(pageId, "Revisa la escena primero: la revisión dice cómo se ve.")
            return
        }
        _jobs.update { it + (pageId to SceneImageJob.Painting) }
        scope.launch {
            try {
                val bytes = Comfy.paint(server, s.imageCheckpoint, description)
                save(pageId, bytes)
                _jobs.update { it - pageId }
            } catch (e: CancellationException) {
                _jobs.update { it - pageId }
                throw e
            } catch (e: Exception) {
                fail(pageId, Comfy.explain(e))
            }
        }
    }

    /** La imagen que elegiste (p. ej. la que pintó Gemini en su app). */
    fun importFrom(pageId: Long, uri: Uri) {
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("No pude abrir esa imagen.")
                }
                save(pageId, bytes)
                _jobs.update { it - pageId }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(pageId, e.message ?: "No pude guardar esa imagen.")
            }
        }
    }

    fun delete(pageId: Long) {
        scope.launch(Dispatchers.IO) {
            file(pageId).delete()
            thumbFile(pageId).delete()
            _version.update { it + 1 }
        }
    }

    fun dismiss(pageId: Long) = _jobs.update { it - pageId }

    private fun fail(pageId: Long, message: String) = _jobs.update { it + (pageId to SceneImageJob.Failed(message)) }

    /** Guarda en JPEG (lo que llega puede ser un PNG de varios MB) y arma la miniatura. */
    private suspend fun save(pageId: Long, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) throw IOException("Eso no parece una imagen.")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Eso no parece una imagen.")
        dir.mkdirs()
        writeJpeg(bitmap, file(pageId), 88)
        val tw = THUMB_WIDTH
        val th = (bitmap.height * tw / bitmap.width.toFloat()).toInt().coerceAtLeast(1)
        val thumb = Bitmap.createScaledBitmap(bitmap, tw, th, true)
        writeJpeg(thumb, thumbFile(pageId), 82)
        if (thumb !== bitmap) thumb.recycle()
        bitmap.recycle()
        _version.update { it + 1 }
    }

    private fun writeJpeg(bitmap: Bitmap, target: File, quality: Int) {
        val tmp = File(target.path + ".tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (!tmp.renameTo(target)) throw IOException("No pude guardar la imagen.")
    }

    companion object {
        const val DIR = "scene_images"
        private const val MAX_SIDE = 1600
        private const val THUMB_WIDTH = 240
    }
}

/** Tu servidor de ComfyUI: pide la imagen, espera a que termine y la descarga. */
private object Comfy {
    private const val CLIENT_ID = "koreandiary"
    private const val TIMEOUT_MS = 6 * 60_000L

    suspend fun checkpoints(server: String): List<String> = withContext(Dispatchers.IO) {
        SceneImage.parseCheckpoints(request("$server/object_info/CheckpointLoaderSimple").decodeToString())
    }

    suspend fun paint(server: String, checkpoint: String, description: String): ByteArray = withContext(Dispatchers.IO) {
        val workflow = SceneImage.workflow(
            checkpoint, SceneImage.localPrompt(description), SceneImage.negativePrompt(), seed = Random.nextLong(0, 1L shl 48),
        )
        val sent = request("$server/prompt", SceneImage.promptBody(workflow, CLIENT_ID), acceptErrors = true)
        val id = SceneImage.parsePromptResponse(sent.decodeToString()).getOrThrow()
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(2_000)
            val out = SceneImage.parseHistory(request("$server/history/$id").decodeToString(), id) ?: continue
            return@withContext request("$server/view?${out.viewQuery()}")
        }
        throw IOException("Tu servidor tardó demasiado en pintar la imagen.")
    }

    /**
     * GET (o POST si hay [body]). Con [acceptErrors], una respuesta 4xx devuelve su cuerpo: ComfyUI
     * explica ahí por qué no aceptó el pedido.
     */
    private fun request(url: String, body: String? = null, acceptErrors: Boolean = false): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 60_000
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            if (code in 200..299) return conn.inputStream.use { it.readBytes() }
            val err = conn.errorStream?.use { it.readBytes() } ?: ByteArray(0)
            if (acceptErrors && code in 400..499) return err
            throw IOException("Tu servidor respondió $code.")
        } finally {
            conn.disconnect()
        }
    }

    fun explain(e: Exception): String = when (e) {
        is ConnectException, is NoRouteToHostException, is UnknownHostException ->
            "No pude conectar con tu servidor. ¿Está prendido y Tailscale encendido en el celular?"
        is SocketTimeoutException -> "Tu servidor no respondió a tiempo. ¿Está prendido y Tailscale encendido en el celular?"
        else -> e.message ?: e.javaClass.simpleName
    }
}

/** Para «Probar conexión» en Ajustes: los modelos instalados en el servidor. */
suspend fun comfyCheckpoints(server: String): Result<List<String>> = try {
    Result.success(Comfy.checkpoints(server))
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(IOException(Comfy.explain(e), e))
}
