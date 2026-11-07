package com.jorge.koreandiary.workbook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Una página de un libro (workbook, student book u otro): foto, pistas de la IA y revisión de lo que escribiste. */
data class WorkbookSession(
    val id: Long,
    val createdAt: Long,
    val pagePhoto: String? = null,
    val reviewPhoto: String? = null,
    /** Respuestas crudas de la IA; se vuelven a leer con WorkbookParser al abrir la sesión. */
    val hintsRaw: String? = null,
    val reviewRaw: String? = null,
    val vocabAdded: Int = 0,
    /** FSRS se actualiza solo en la primera revisión, como en el diario. */
    val fsrsApplied: Boolean = false,
    val reviewedItems: Int = 0,
    val exerciseCount: Int = 0,
    /** Gramática de la lección con la que se pidieron las pistas; null = la detectó la IA. */
    val focus: String? = null,
)

/**
 * Sesiones en archivos dentro del almacenamiento privado (filesDir/workbook/<id>/), no en Room:
 * así esta función no necesita migrar la base de datos de los diarios.
 */
class WorkbookStore(private val context: Context) {
    private val root = File(context.filesDir, "workbook")

    private val _sessions = MutableStateFlow<List<WorkbookSession>>(emptyList())
    val sessions: StateFlow<List<WorkbookSession>> = _sessions.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _sessions.value = root.listFiles()?.mapNotNull { dir -> dir.name.toLongOrNull()?.let { read(it) } }
            ?.sortedByDescending { it.createdAt } ?: emptyList()
    }

    suspend fun create(): WorkbookSession = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val session = WorkbookSession(id = now, createdAt = now)
        dir(session.id).mkdirs()
        write(session)
        refresh()
        session
    }

    suspend fun load(id: Long): WorkbookSession? = withContext(Dispatchers.IO) { read(id) }

    suspend fun save(session: WorkbookSession) = withContext(Dispatchers.IO) {
        write(session)
        refresh()
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        dir(id).deleteRecursively()
        refresh()
    }

    /** Archivo temporal donde la app de cámara guarda la foto (vía FileProvider). */
    fun captureFile(id: Long): File = File(dir(id).apply { mkdirs() }, "captura.jpg")

    fun file(id: Long, name: String): File = File(dir(id), name)

    /**
     * Copia la foto (cámara o galería) a la sesión: la endereza según EXIF, la reduce a 1600 px
     * como máximo y la guarda en JPEG. Una foto más chica gasta menos tokens y la IA la lee igual.
     */
    suspend fun importPhoto(id: Long, source: Uri, name: String): File = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) throw IOException("No se pudo leer la imagen.")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = resolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("No se pudo leer la imagen.")
        val rotation = resolver.openInputStream(source)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val k = minOf(1f, MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            postScale(k, k)
            postRotate(rotation)
        }
        val finalBitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val out = File(dir(id).apply { mkdirs() }, name)
        out.outputStream().use { finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        out
    }

    private fun dir(id: Long) = File(root, id.toString())

    private fun read(id: Long): WorkbookSession? {
        val f = File(dir(id), "session.json")
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            WorkbookSession(
                id = o.getLong("id"),
                createdAt = o.optLong("createdAt", id),
                pagePhoto = o.optString("pagePhoto").ifBlank { null },
                reviewPhoto = o.optString("reviewPhoto").ifBlank { null },
                hintsRaw = o.optString("hintsRaw").ifBlank { null },
                reviewRaw = o.optString("reviewRaw").ifBlank { null },
                vocabAdded = o.optInt("vocabAdded"),
                fsrsApplied = o.optBoolean("fsrsApplied"),
                reviewedItems = o.optInt("reviewedItems"),
                exerciseCount = o.optInt("exerciseCount"),
                focus = o.optString("focus").ifBlank { null },
            )
        } catch (e: org.json.JSONException) {
            null
        }
    }

    private fun write(s: WorkbookSession) {
        val o = JSONObject()
            .put("id", s.id)
            .put("createdAt", s.createdAt)
            .put("pagePhoto", s.pagePhoto ?: "")
            .put("reviewPhoto", s.reviewPhoto ?: "")
            .put("hintsRaw", s.hintsRaw ?: "")
            .put("reviewRaw", s.reviewRaw ?: "")
            .put("vocabAdded", s.vocabAdded)
            .put("fsrsApplied", s.fsrsApplied)
            .put("reviewedItems", s.reviewedItems)
            .put("exerciseCount", s.exerciseCount)
            .put("focus", s.focus ?: "")
        File(dir(s.id).apply { mkdirs() }, "session.json").writeText(o.toString())
    }

    companion object {
        private const val MAX_SIDE = 1600
    }
}
