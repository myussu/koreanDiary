package com.jorge.koreandiary.backup

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Error con un mensaje listo para mostrar en pantalla. */
class BackupException(message: String) : Exception(message)

/** Lo que una copia dice de sí misma (`manifest.json` dentro del .zip). */
data class BackupManifest(
    val format: Int = BackupFormat.VERSION,
    val exportedAt: Long,
    val appVersionCode: Long,
    val appVersionName: String,
    /** `PRAGMA user_version` de la base copiada: la versión del esquema de Room. */
    val dbVersion: Int,
    /** Hash con el que Room reconoce el esquema de esa versión. */
    val identityHash: String,
    val pages: Int,
)

/** Lo que la app sabe de sus copias: la del archivo y la automática de Google. */
data class BackupStatus(
    val lastExportAt: Long = 0,
    val lastExportBytes: Long = 0,
    /** Última vez que Android pasó la copia automática por la app (no dice si se subió). */
    val lastFullBackupAt: Long = 0,
    val quotaExceededAt: Long = 0,
    val quotaDataBytes: Long = 0,
    val quotaLimitBytes: Long = 0,
    /** Mensaje pendiente de mostrar en el inicio (p. ej. tras importar una copia). */
    val notice: String = "",
) {
    /**
     * Google rechazó la última copia por tamaño. En cada pasada Android llama primero a
     * `onFullBackup` y, solo si no cabe, a `onQuotaExceeded`: si el rechazo es lo último que pasó,
     * la copia sigue sin subir.
     */
    val overQuota: Boolean get() = quotaExceededAt > 0 && quotaExceededAt >= lastFullBackupAt

    /** Sin copia de Google y sin un archivo reciente: toca avisar en el inicio. */
    fun needsAttention(now: Long): Boolean = overQuota && now - lastExportAt > STALE_EXPORT_MS

    companion object {
        const val STALE_EXPORT_MS = 7L * 24 * 60 * 60 * 1000
    }
}

/** Formato del archivo de copia. Lógica pura (sin Android) para poder probarla. */
object BackupFormat {
    const val VERSION = 1
    const val APP_ID = "com.jorge.koreandiary"

    const val MANIFEST = "manifest.json"
    const val SETTINGS = "settings.json"
    const val DATABASE = "db/korean_diary.db"
    const val FILES = "files/"

    /** Carpetas de `filesDir` que viajan en la copia. Las claves de API nunca. */
    val MANAGED_DIRS = listOf("workbook", "workbook_photos", "conversation", "explanations", "mascot")

    /** Tope de lo que se extrae de un .zip: evita llenar el celular con un archivo malicioso. */
    const val MAX_BYTES = 2L * 1024 * 1024 * 1024

    /** `files/<carpeta>/…`: como mucho estos niveles debajo de `files/`. */
    private const val MAX_DEPTH = 4

    private const val NOT_A_COPY = "Ese archivo no es una copia de Korean Diary."
    private const val NEWER_COPY = "Esta copia la hizo una versión más nueva de la app. Actualiza la app y vuelve a importarla."

    private val spanish = Locale.forLanguageTag("es")
    private val fileStamp = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")
    private val dateStamp = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy, HH:mm", spanish)

    /** Nombre sugerido al exportar: `korean-diary-2026-11-10-2015.zip`. */
    fun fileName(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "korean-diary-${fileStamp.format(Instant.ofEpochMilli(millis).atZone(zone))}.zip"

    /** "10 de noviembre de 2026, 20:15". */
    fun dateLabel(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        dateStamp.format(Instant.ofEpochMilli(millis).atZone(zone))

    fun sizeLabel(bytes: Long): String =
        if (bytes < 1_048_576) "${(bytes + 1023) / 1024} KB"
        else String.format(spanish, "%.1f MB", bytes / 1_048_576.0)

    fun pagesLabel(count: Int): String = if (count == 1) "1 página" else "$count páginas"

    fun manifestToJson(manifest: BackupManifest): String = JSONObject()
        .put("format", manifest.format)
        .put("app", APP_ID)
        .put("exportedAt", manifest.exportedAt)
        .put("versionCode", manifest.appVersionCode)
        .put("versionName", manifest.appVersionName)
        .put("dbVersion", manifest.dbVersion)
        .put("identityHash", manifest.identityHash)
        .put("pages", manifest.pages)
        .toString()

    fun parseManifest(text: String): BackupManifest {
        val json = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw BackupException(NOT_A_COPY)
        }
        if (json.optString("app") != APP_ID || json.optInt("format") < 1) throw BackupException(NOT_A_COPY)
        return BackupManifest(
            format = json.optInt("format"),
            exportedAt = json.optLong("exportedAt"),
            appVersionCode = json.optLong("versionCode"),
            appVersionName = json.optString("versionName", "?"),
            dbVersion = json.optInt("dbVersion"),
            identityHash = json.optString("identityHash"),
            pages = json.optInt("pages"),
        )
    }

    /**
     * Por qué esta copia no se puede abrir con la app instalada, o null si se puede.
     * [liveVersion] y [liveHash] son los de la base que Room tiene abierta ahora. Una copia con un
     * esquema anterior sí entra: Room la migra al abrirla. Una más nueva no: Room no sabe bajar.
     */
    fun importProblem(copy: BackupManifest, liveVersion: Int, liveHash: String): String? = when {
        copy.format > VERSION || copy.dbVersion > liveVersion -> NEWER_COPY
        copy.dbVersion < 1 -> "La copia no trae una base de datos válida."
        copy.dbVersion == liveVersion && liveHash.isNotEmpty() && copy.identityHash != liveHash ->
            "La base de esta copia no coincide con la de esta versión de la app."
        else -> null
    }

    /**
     * Ruta (relativa a la carpeta de preparación) donde se extrae una entrada del .zip, o null si
     * se ignora. Solo entran las entradas que la app conoce: nada con rutas absolutas ni "..".
     */
    fun entryTarget(name: String): String? {
        if (name.isEmpty() || name.startsWith("/") || name.endsWith("/")) return null
        if ('\\' in name || '\u0000' in name) return null
        if (name == MANIFEST || name == SETTINGS || name == DATABASE) return name
        if (!name.startsWith(FILES)) return null
        val parts = name.removePrefix(FILES).split('/')
        if (parts.size < 2 || parts.size > MAX_DEPTH) return null
        if (parts.first() !in MANAGED_DIRS) return null
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return name
    }

    /** Ajustes (SharedPreferences) a JSON conservando el tipo de cada valor. */
    fun encodeSettings(values: Map<String, *>): JSONObject {
        val out = JSONObject()
        for ((key, value) in values) {
            val item = JSONObject()
            when (value) {
                is String -> item.put("t", "s").put("v", value)
                is Boolean -> item.put("t", "b").put("v", value)
                is Int -> item.put("t", "i").put("v", value)
                is Long -> item.put("t", "l").put("v", value)
                is Float -> if (value.isFinite()) item.put("t", "f").put("v", value.toDouble()) else continue
                is Set<*> -> item.put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
                else -> continue
            }
            out.put(key, item)
        }
        return out
    }

    /** Inversa de [encodeSettings]. Lo que no se entiende se salta. */
    fun decodeSettings(json: JSONObject): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        json.keys().forEach { key ->
            val item = json.optJSONObject(key) ?: return@forEach
            val value: Any? = try {
                when (item.optString("t")) {
                    "s" -> item.getString("v")
                    "b" -> item.getBoolean("v")
                    "i" -> item.getInt("v")
                    "l" -> item.getLong("v")
                    "f" -> item.getDouble("v").toFloat()
                    "ss" -> item.getJSONArray("v").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
                    else -> null
                }
            } catch (e: JSONException) {
                null
            }
            if (value != null) out[key] = value
        }
        return out
    }
}
