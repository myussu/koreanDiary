package com.jorge.koreandiary.mascot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

data class MascotImportResult(val imported: List<MascotMood>, val ignored: List<String>) {
    val missing get() = MascotMood.entries - imported.toSet()
}

/**
 * Mascota personalizada: imágenes que importas tú (por ejemplo, renders de Blender).
 * Se guardan en el almacenamiento privado de la app; nunca van al repo ni a ningún servidor.
 * Una imagen por expresión, nombrada como la expresión (idle.png, feliz.png...), sueltas o en un ZIP.
 */
class MascotPack(private val context: Context) {
    private val dir = File(context.filesDir, "mascot")
    private val cache = HashMap<MascotMood, ImageBitmap>()

    /** Cambia cada vez que se importa o se borra, para que la UI recargue. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun hasCustom(): Boolean = File(dir, "${MascotMood.IDLE.storedName}.png").exists()

    /** Imagen de la expresión; si no se importó esa, la de "normal". Null si no hay mascota importada. */
    suspend fun image(mood: MascotMood): ImageBitmap? = withContext(Dispatchers.IO) {
        if (!hasCustom()) return@withContext null
        synchronized(cache) { cache[mood] }?.let { return@withContext it }
        val file = File(dir, "${mood.storedName}.png").takeIf { it.exists() }
            ?: File(dir, "${MascotMood.IDLE.storedName}.png")
        val bitmap = BitmapFactory.decodeFile(file.path)?.asImageBitmap() ?: return@withContext null
        synchronized(cache) { cache[mood] = bitmap }
        bitmap
    }

    suspend fun import(uris: List<Uri>): MascotImportResult = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val imported = linkedSetOf<MascotMood>()
        val ignored = mutableListOf<String>()
        for (uri in uris) {
            val name = displayName(uri) ?: uri.lastPathSegment ?: "?"
            if (name.lowercase().endsWith(".zip")) {
                context.contentResolver.openInputStream(uri)?.use { raw ->
                    ZipInputStream(raw).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            // Solo el nombre base: evita rutas como "../" dentro del ZIP.
                            val base = entry.name.substringAfterLast('/')
                            val mood = if (entry.isDirectory) null else MascotMood.fromFileName(base)
                            if (mood != null && save(mood, zip.readBytes())) imported += mood
                            else if (!entry.isDirectory && base.isNotBlank()) ignored += base
                            entry = zip.nextEntry
                        }
                    }
                }
            } else {
                val mood = MascotMood.fromFileName(name)
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (mood != null && bytes != null && save(mood, bytes)) imported += mood else ignored += name
            }
        }
        synchronized(cache) { cache.clear() }
        _version.value++
        MascotImportResult(imported.toList(), ignored)
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        synchronized(cache) { cache.clear() }
        _version.value++
    }

    /** Decodifica (PNG, WebP o JPG), reduce a 512 px como máximo y guarda como PNG. */
    private fun save(mood: MascotMood, bytes: ByteArray): Boolean {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
        val maxSide = maxOf(decoded.width, decoded.height)
        val bitmap = if (maxSide > MAX_SIDE) {
            val k = MAX_SIDE.toFloat() / maxSide
            Bitmap.createScaledBitmap(decoded, (decoded.width * k).toInt(), (decoded.height * k).toInt(), true)
        } else decoded
        File(dir, "${mood.storedName}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return true
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    companion object {
        private const val MAX_SIDE = 512
    }
}
