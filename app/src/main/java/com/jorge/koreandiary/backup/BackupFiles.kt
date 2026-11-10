package com.jorge.koreandiary.backup

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.jorge.koreandiary.data.db.AppDatabase
import com.jorge.koreandiary.settings.SettingsStore
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Lo que se lee de una base suelta para decidir si se puede importar. */
data class DatabaseFacts(val version: Int, val identityHash: String, val pages: Int)

/** Una copia ya extraída y validada, esperando al próximo arranque para ponerse en su lugar. */
data class PendingRestore(val kind: String, val exportedAt: Long, val pages: Int)

/**
 * Lectura y escritura de copias en disco. Todo es bloqueante: quien llama elige el hilo.
 * No depende del `AppContainer`, porque restaurar ocurre al arrancar, antes de que exista.
 *
 * Un .zip de copia trae: `manifest.json`, `db/korean_diary.db`, `settings.json` y
 * `files/<carpeta>/…` (ver [BackupFormat]). Las claves de API no entran.
 */
class BackupFiles(private val context: Context) {
    private val database: File get() = context.getDatabasePath(AppDatabase.FILE_NAME)

    /** Carpeta de preparación de una copia importada. No entra en la copia de Google. */
    private val staging = File(context.filesDir, "restore")
    private val staged = File(staging, "staged")
    private val marker = File(staging, "READY")

    /** Lo que había justo antes de la última importación, para poder deshacerla. */
    val undo = File(context.filesDir, "backups/antes-de-importar.zip")

    // ---------- Base de datos ----------

    /**
     * Copia consistente de la base aunque Room la tenga abierta y escribiendo: `VACUUM INTO` lee
     * una foto fija (incluye lo que aún está en el WAL) y deja un solo archivo compacto.
     * Se abre con el mismo SQLite que usa Room; el SQLite del sistema es otra copia de la
     * librería y dos copias sobre el mismo archivo pueden dañarlo.
     */
    private fun snapshotDatabase(target: File) {
        target.parentFile?.mkdirs()
        if (target.exists() && !target.delete()) throw IOException("No se pudo preparar la copia de la base.")
        val connection = BundledSQLiteDriver().open(database.path)
        try {
            connection.execSQL("PRAGMA busy_timeout = 10000")
            connection.execSQL("VACUUM INTO '${target.path.replace("'", "''")}'")
        } finally {
            connection.close()
        }
    }

    /** Versión, hash del esquema y páginas de una base. Con [check] revisa además que no esté dañada. */
    private fun inspectDatabase(file: File, check: Boolean): DatabaseFacts {
        val connection = BundledSQLiteDriver().open(file.path)
        try {
            connection.execSQL("PRAGMA busy_timeout = 10000")
            if (check && connection.queryText("PRAGMA quick_check") != "ok") {
                throw BackupException("La base de datos de la copia está dañada.")
            }
            return DatabaseFacts(
                version = connection.queryLong("PRAGMA user_version").toInt(),
                identityHash = connection.queryText("SELECT identity_hash FROM room_master_table WHERE id = 42").orEmpty(),
                pages = connection.queryLong("SELECT COUNT(*) FROM pages").toInt(),
            )
        } finally {
            connection.close()
        }
    }

    /** Datos de la base en uso. Room ya tiene que haberla abierto (así existe y está migrada). */
    fun liveDatabaseFacts(): DatabaseFacts = inspectDatabase(database, check = false)

    // ---------- Exportar ----------

    /**
     * Escribe la copia completa en [output] y lo cierra.
     * @return el manifiesto y los bytes escritos.
     */
    fun writeArchive(output: OutputStream, versionCode: Long, versionName: String, now: Long): Pair<BackupManifest, Long> {
        val snapshot = File(context.cacheDir, "backup/snapshot.db")
        val counter = CountingOutputStream(output)
        try {
            snapshotDatabase(snapshot)
            val facts = inspectDatabase(snapshot, check = false)
            val manifest = BackupManifest(
                exportedAt = now,
                appVersionCode = versionCode,
                appVersionName = versionName,
                dbVersion = facts.version,
                identityHash = facts.identityHash,
                pages = facts.pages,
            )
            ZipOutputStream(BufferedOutputStream(counter)).use { zip ->
                zip.putText(BackupFormat.MANIFEST, BackupFormat.manifestToJson(manifest))
                zip.putFile(BackupFormat.DATABASE, snapshot)
                val settings = context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE).all
                zip.putText(BackupFormat.SETTINGS, BackupFormat.encodeSettings(settings).toString())
                for (dir in BackupFormat.MANAGED_DIRS) {
                    val root = File(context.filesDir, dir)
                    root.walkTopDown().filter { it.isFile && !isTemporary(it) }.forEach { file ->
                        zip.putFile(BackupFormat.FILES + dir + "/" + file.relativeTo(root).invariantSeparatorsPath, file)
                    }
                }
            }
            // El total solo es exacto con el zip ya cerrado (ahí se vacían los búferes).
            return manifest to counter.count
        } finally {
            // Si algo falló antes de crear el zip, o al cerrarlo, el destino no puede quedar abierto.
            runCatching { output.close() }
            snapshot.delete()
        }
    }

    /** Archivos a medio escribir o de paso (foto recién tomada por la cámara). */
    private fun isTemporary(file: File) = file.name.endsWith(".tmp") || file.name == "captura.jpg"

    // ---------- Importar ----------

    /**
     * Extrae una copia a la carpeta de preparación y revisa que esté completa y sana. No toca los
     * datos en uso. Solo se extraen las entradas conocidas y nada sale de esa carpeta.
     * @return el manifiesto, con la versión, el hash y las páginas leídos de la base extraída.
     */
    fun extract(input: InputStream): BackupManifest {
        discardStaged()
        staged.mkdirs()
        if (!staged.isDirectory) throw IOException("no se pudo crear la carpeta de preparación")
        val base = staged.canonicalPath + File.separator
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val target = if (entry.isDirectory) null else BackupFormat.entryTarget(entry.name)
                if (target != null) {
                    val file = File(staged, target)
                    if (!file.canonicalPath.startsWith(base)) throw BackupException("La copia trae rutas que no son válidas.")
                    file.parentFile?.mkdirs()
                    file.outputStream().use { out ->
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > BackupFormat.MAX_BYTES) throw BackupException("La copia es demasiado grande.")
                            out.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val manifestFile = File(staged, BackupFormat.MANIFEST)
        val db = File(staged, BackupFormat.DATABASE)
        if (!manifestFile.exists() || !db.exists()) throw BackupException("Ese archivo no es una copia de Korean Diary.")
        val manifest = BackupFormat.parseManifest(manifestFile.readText())
        val facts = try {
            inspectDatabase(db, check = true)
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("La base de datos de la copia no se puede leer.")
        }
        // Al abrirla SQLite puede dejar archivos auxiliares; al lugar definitivo solo va la base.
        for (suffix in SIDE_FILES) File(db.path + suffix).delete()
        // Toda carpeta administrada queda preparada aunque venga vacía: al aplicar,
        // "existe en preparación" quiere decir "falta reemplazarla".
        for (dir in BackupFormat.MANAGED_DIRS) File(staged, BackupFormat.FILES + dir).mkdirs()
        manifestFile.delete()
        return manifest.copy(dbVersion = facts.version, identityHash = facts.identityHash, pages = facts.pages)
    }

    fun discardStaged() {
        staging.deleteRecursively()
    }

    /** Deja la copia preparada lista para el próximo arranque. Es lo último que se escribe. */
    fun markReady(kind: String, manifest: BackupManifest) {
        val json = JSONObject().put("kind", kind).put("exportedAt", manifest.exportedAt).put("pages", manifest.pages)
        val tmp = File(staging, "READY.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(marker)) throw IOException("No se pudo dejar lista la copia.")
    }

    fun pendingRestore(): PendingRestore? {
        if (!marker.exists()) return null
        return try {
            val json = JSONObject(marker.readText())
            PendingRestore(json.optString("kind", KIND_IMPORT), json.optLong("exportedAt"), json.optInt("pages"))
        } catch (e: JSONException) {
            PendingRestore(KIND_IMPORT, 0, 0)
        }
    }

    /**
     * Pone en su lugar lo preparado: base, carpetas y ajustes. Solo se llama al arrancar, antes de
     * abrir la base. Cada paso se puede repetir: lo que ya se movió deja de existir en la
     * preparación, así que si la app muere a la mitad el próximo arranque termina el resto.
     */
    fun applyStaged() {
        val db = File(staged, BackupFormat.DATABASE)
        if (db.exists()) {
            val live = database
            live.parentFile?.mkdirs()
            // Primero se va el WAL de la base vieja: SQLite lo aplicaría sobre la nueva y la dañaría.
            for (suffix in SIDE_FILES) {
                val side = File(live.path + suffix)
                if (side.exists() && !side.delete()) throw IOException("no se pudo quitar ${side.name}")
            }
            move(db, live)
        }
        for (dir in BackupFormat.MANAGED_DIRS) {
            val from = File(staged, BackupFormat.FILES + dir)
            if (!from.exists()) continue
            val to = File(context.filesDir, dir)
            if (to.exists() && !to.deleteRecursively()) throw IOException("no se pudo reemplazar la carpeta $dir")
            move(from, to)
        }
        val settings = File(staged, BackupFormat.SETTINGS)
        if (settings.exists()) {
            applySettings(JSONObject(settings.readText()))
            settings.delete()
        }
        discardStaged()
    }

    private fun applySettings(json: JSONObject) {
        val editor = context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear()
        for ((key, value) in BackupFormat.decodeSettings(json)) {
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.map { it.toString() }.toSet())
            }
        }
        if (!editor.commit()) throw IOException("no se pudieron guardar los ajustes")
    }

    /** Mover dentro del almacenamiento de la app es un renombrado; la copia es solo el plan B. */
    private fun move(from: File, to: File) {
        if (from.renameTo(to)) return
        if (!from.copyRecursively(to, overwrite = true)) throw IOException("no se pudo mover ${from.name}")
        from.deleteRecursively()
    }

    companion object {
        const val KIND_IMPORT = "import"
        const val KIND_UNDO = "undo"

        /** Archivos que SQLite crea junto a una base. */
        private val SIDE_FILES = listOf("-wal", "-shm", "-journal")
    }
}

private fun SQLiteConnection.queryText(sql: String): String? {
    val statement = prepare(sql)
    try {
        return if (statement.step() && !statement.isNull(0)) statement.getText(0) else null
    } finally {
        statement.close()
    }
}

private fun SQLiteConnection.queryLong(sql: String): Long {
    val statement = prepare(sql)
    try {
        return if (statement.step() && !statement.isNull(0)) statement.getLong(0) else 0
    } finally {
        statement.close()
    }
}

private fun ZipOutputStream.putText(name: String, text: String) {
    putNextEntry(ZipEntry(name))
    write(text.toByteArray(Charsets.UTF_8))
    closeEntry()
}

private fun ZipOutputStream.putFile(name: String, file: File) {
    putNextEntry(ZipEntry(name))
    file.inputStream().use { it.copyTo(this) }
    closeEntry()
}

/** Cuenta lo escrito para poder decir cuánto pesa la copia. */
private class CountingOutputStream(private val out: OutputStream) : OutputStream() {
    var count = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        count += len
    }

    override fun flush() = out.flush()

    override fun close() = out.close()
}
