package com.jorge.koreandiary.desktop

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.jorge.koreandiary.backup.BackupException
import com.jorge.koreandiary.backup.BackupFormat
import com.jorge.koreandiary.backup.BackupManifest
import com.jorge.koreandiary.data.db.AppDatabase
import java.io.File
import java.util.zip.ZipFile

/** Dónde guarda sus datos la versión de escritorio. */
object DesktopPaths {
    /**
     * %APPDATA%\KoreanDiary en Windows; ~/.local/share/KoreanDiary en otros sistemas.
     * Los tests la cambian con la propiedad `koreandiary.dataDir`.
     */
    val dataDir: File by lazy {
        val forced = System.getProperty("koreandiary.dataDir")?.takeIf { it.isNotBlank() }
        if (forced != null) return@lazy File(forced).apply { mkdirs() }
        val base = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".local/share")
        File(base, "KoreanDiary").apply { mkdirs() }
    }
    val database: File get() = File(dataDir, AppDatabase.FILE_NAME)
    /** Equivale a filesDir del celular: fotos del cuaderno, conversaciones, mascota… */
    val files: File get() = File(dataDir, "files")
    private val backups: File get() = File(dataDir, "backups")
    /** Lo que había antes de la última importación, por si hay que volver atrás. */
    val beforeImport: File get() = File(backups, "antes-de-importar")
    val staging: File get() = File(dataDir, "restore")
}

/** La misma base que en Android (definida en :shared); solo cambia dónde vive el archivo. */
fun openDatabase(file: File = DesktopPaths.database): AppDatabase =
    Room.databaseBuilder<AppDatabase>(file.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .build()

/** Archivos que SQLite deja junto a la base (modo WAL). */
private val SIDE_FILES = listOf("-wal", "-shm", "-journal")

data class ImportResult(val manifest: BackupManifest, val pages: Int)

/**
 * Importa la copia `.zip` que exporta el celular (Ajustes → Copia de seguridad → Exportar copia).
 * Se usa el mismo formato y las mismas validaciones que en Android ([BackupFormat]). Los ajustes
 * (`settings.json`) no se importan: las claves de API van cifradas con el Keystore del celular.
 */
object BackupImporter {

    /**
     * Valida y extrae la copia en una carpeta de preparación, sin tocar nada de lo actual.
     * Llamar fuera del hilo de la interfaz.
     */
    fun prepare(zip: File): ImportResult {
        val staging = DesktopPaths.staging
        staging.deleteRecursively()
        staging.mkdirs()
        val manifest = ZipFile(zip).use { z ->
            val manifestText = z.getEntry(BackupFormat.MANIFEST)?.let { e ->
                z.getInputStream(e).use { it.readBytes().decodeToString() }
            }.orEmpty()
            // Sin manifiesto, parseManifest avisa que no es una copia de la app.
            val manifest = BackupFormat.parseManifest(manifestText)
            BackupFormat.importProblem(manifest, AppDatabase.VERSION, liveHash = "")?.let { throw BackupException(it) }

            var total = 0L
            val root = staging.canonicalFile
            for (entry in z.entries()) {
                if (entry.isDirectory) continue
                val target = BackupFormat.entryTarget(entry.name) ?: continue
                val file = File(staging, target)
                // entryTarget ya descarta rutas raras; esto es una segunda red.
                if (!file.canonicalPath.startsWith(root.path + File.separator)) continue
                file.parentFile.mkdirs()
                z.getInputStream(entry).use { input ->
                    file.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > BackupFormat.MAX_BYTES) throw BackupException("La copia es demasiado grande.")
                            out.write(buffer, 0, read)
                        }
                    }
                }
            }
            manifest
        }
        val db = File(staging, BackupFormat.DATABASE)
        if (!db.isFile) throw BackupException("La copia no trae una base de datos válida.")
        val pages = inspect(db)
        return ImportResult(manifest, pages)
    }

    /**
     * Pone la copia preparada en su lugar. La base actual tiene que estar cerrada.
     * Lo que había queda en [DesktopPaths.beforeImport].
     */
    fun apply() {
        val staging = DesktopPaths.staging
        val stagedDb = File(staging, BackupFormat.DATABASE)
        check(stagedDb.isFile) { "No hay una copia preparada" }

        val before = DesktopPaths.beforeImport
        before.deleteRecursively()
        before.mkdirs()
        val live = DesktopPaths.database
        for (suffix in listOf("") + SIDE_FILES) {
            val f = File(live.path + suffix)
            if (f.exists()) f.copyTo(File(before, f.name), overwrite = true)
        }
        if (DesktopPaths.files.exists()) DesktopPaths.files.copyRecursively(File(before, "files"), overwrite = true)

        for (suffix in SIDE_FILES) File(live.path + suffix).delete()
        stagedDb.copyTo(live, overwrite = true)

        // Carpetas administradas: la copia manda (como en el celular). Las que no trae, se vacían.
        for (dir in BackupFormat.MANAGED_DIRS) {
            val to = File(DesktopPaths.files, dir)
            to.deleteRecursively()
            val from = File(staging, BackupFormat.FILES + dir)
            if (from.isDirectory) from.copyRecursively(to, overwrite = true)
        }
        staging.deleteRecursively()
    }

    fun discard() {
        DesktopPaths.staging.deleteRecursively()
    }

    /** Revisa que la base no esté dañada y cuenta sus páginas. */
    private fun inspect(file: File): Int {
        val connection = BundledSQLiteDriver().open(file.path)
        try {
            if (connection.text("PRAGMA quick_check") != "ok") {
                throw BackupException("La base de datos de la copia está dañada.")
            }
            val version = connection.long("PRAGMA user_version").toInt()
            if (version > AppDatabase.VERSION) {
                throw BackupException("Esta copia la hizo una versión más nueva de la app. Actualiza la versión de escritorio.")
            }
            return connection.long("SELECT COUNT(*) FROM pages").toInt()
        } finally {
            connection.close()
        }
    }

    private fun SQLiteConnection.text(sql: String): String? =
        prepare(sql).use { if (it.step()) it.getText(0) else null }

    private fun SQLiteConnection.long(sql: String): Long =
        prepare(sql).use { if (it.step()) it.getLong(0) else 0L }
}
