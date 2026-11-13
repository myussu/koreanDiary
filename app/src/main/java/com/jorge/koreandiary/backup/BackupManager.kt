package com.jorge.koreandiary.backup

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.jorge.koreandiary.data.db.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Qué está haciendo la copia de seguridad ahora, para pintarlo en Ajustes. */
sealed interface BackupActivity {
    data object Idle : BackupActivity
    data class Working(val label: String) : BackupActivity
    data class Done(val message: String) : BackupActivity
    data class Failed(val message: String) : BackupActivity

    /** Copia leída y validada: falta que confirmes reemplazar lo que hay en el celular. */
    data class Confirm(val incoming: BackupManifest, val currentPages: Int, val undo: Boolean) : BackupActivity

    /** Todo listo: la app se está cerrando para abrir con la copia. */
    data object Restarting : BackupActivity
}

/**
 * Exportar e importar todo a un archivo .zip que eliges tú (Drive, la computadora, donde quieras).
 * Es la copia principal: la automática de Google se corta en 25 MB sin avisar a nadie más que a la app.
 *
 * El estado vive aquí y no en la pantalla (como el actualizador): exportar sigue aunque salgas de Ajustes.
 */
class BackupManager(
    private val app: Application,
    private val database: AppDatabase,
    private val scope: CoroutineScope,
    private val versionCode: Long,
    private val versionName: String,
) {
    private val files = BackupFiles(app)
    private val mutex = Mutex()

    val status = BackupStateStore(app)

    private val _activity = MutableStateFlow<BackupActivity>(BackupActivity.Idle)
    val activity: StateFlow<BackupActivity> = _activity.asStateFlow()

    /** Hay datos guardados de antes de la última importación. */
    val undoAvailable: Boolean get() = files.undo.exists()

    /** Escribe la copia en el documento que elegiste con el selector de archivos. */
    fun exportTo(uri: Uri) = start("Exportando…") {
        try {
            val output = app.contentResolver.openOutputStream(uri) ?: throw BackupException("No se pudo abrir ese archivo para escribir.")
            val (manifest, bytes) = archive(output)
            status.recordExport(manifest.exportedAt, bytes)
            BackupActivity.Done(
                "Copia guardada: ${BackupFormat.pagesLabel(manifest.pages)}, ${BackupFormat.sizeLabel(bytes)}. " +
                    "Si la guardaste en Drive, la app de Drive termina de subirla.",
            )
        } catch (e: Exception) {
            // Un archivo a medias parece una copia y no lo es: mejor que no quede.
            runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) }
            throw e
        }
    }

    /** Lee y valida una copia. No reemplaza nada: deja el estado en [BackupActivity.Confirm]. */
    fun importFrom(uri: Uri) = start("Leyendo la copia…") {
        try {
            // Asegura que Room ya creó y migró la base: sus datos son la referencia para comparar.
            val currentPages = database.diaryDao().pageCount()
            val input = app.contentResolver.openInputStream(uri) ?: throw BackupException("No se pudo abrir ese archivo.")
            val copy = input.use { files.extract(it) }
            val live = files.liveDatabaseFacts()
            BackupFormat.importProblem(copy, live.version, live.identityHash)?.let { throw BackupException(it) }
            BackupActivity.Confirm(copy, currentPages, undo = false)
        } catch (e: Exception) {
            files.discardStaged()
            throw e
        }
    }

    /** Prepara la vuelta a lo que había antes de la última importación. */
    fun undoLastImport() = start("Leyendo lo que había antes…") {
        try {
            val currentPages = database.diaryDao().pageCount()
            if (!files.undo.exists()) throw BackupException("Ya no hay nada que deshacer.")
            val copy = files.undo.inputStream().use { files.extract(it) }
            BackupActivity.Confirm(copy, currentPages, undo = true)
        } catch (e: Exception) {
            files.discardStaged()
            throw e
        }
    }

    /**
     * Confirma el reemplazo: guarda lo que hay ahora (para poder deshacer), deja la copia lista y
     * reinicia la app. El reemplazo ocurre al arrancar ([StartupRestore]).
     */
    fun confirm() {
        val pending = _activity.value as? BackupActivity.Confirm ?: return
        start(if (pending.undo) "Preparando…" else "Guardando lo que hay ahora, por si quieres deshacer…") {
            try {
                if (!pending.undo) {
                    val tmp = File(files.undo.path + ".tmp")
                    tmp.parentFile?.mkdirs()
                    archive(tmp.outputStream())
                    if (!tmp.renameTo(files.undo)) throw IOException("no se pudo guardar la copia para deshacer")
                }
                files.markReady(if (pending.undo) BackupFiles.KIND_UNDO else BackupFiles.KIND_IMPORT, pending.incoming)
            } catch (e: Exception) {
                files.discardStaged()
                throw e
            }
            _activity.value = BackupActivity.Restarting
            restart()
            BackupActivity.Restarting
        }
    }

    // ---------- Copia antes de actualizar ----------

    /** Copias de antes de actualizar guardadas dentro de la app (si no hay carpeta o no se pudo escribir en ella). */
    private val autoDir = File(app.filesDir, "backups/auto")

    /**
     * Elige la carpeta para las copias de antes de actualizar (selector de carpetas del sistema) y
     * guarda el permiso para escribir ahí sin preguntar otra vez. Null la quita.
     */
    fun setAutoFolder(tree: Uri?) {
        val resolver = app.contentResolver
        val previous = status.state.value.autoFolder.takeIf { it.isNotEmpty() }?.let(Uri::parse)
        if (tree != null) {
            resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            status.setAutoFolder(tree.toString(), folderName(tree))
        } else {
            status.setAutoFolder("", "")
        }
        if (previous != null && previous != tree) {
            runCatching { resolver.releasePersistableUriPermission(previous, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
    }

    /**
     * Antes de instalar una actualización: guarda una copia completa en la carpeta elegida o, si no
     * hay (o no se pudo escribir ahí), dentro de la app. Quedan las [BackupFormat.AUTO_KEEP] últimas.
     * Devuelve qué decir en pantalla. Lanza solo si no se pudo guardar en ningún lado.
     */
    suspend fun backupBeforeUpdate(toVersion: String): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val name = BackupFormat.autoBackupName(toVersion, now)
            val folder = status.state.value
            var folderProblem: String? = null
            if (folder.autoFolder.isNotEmpty()) {
                try {
                    val bytes = writeToFolder(Uri.parse(folder.autoFolder), name)
                    status.recordAuto(now, bytes, inApp = false)
                    // Es un archivo fuera de la app, como el que exportas a mano.
                    status.recordExport(now, bytes)
                    return@withContext "Copia guardada en ${folder.autoFolderName.ifBlank { "tu carpeta" }} (${BackupFormat.sizeLabel(bytes)})."
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    folderProblem = e.message ?: e.javaClass.simpleName
                }
            }
            autoDir.mkdirs()
            val tmp = File(autoDir, "$name.tmp")
            val (_, bytes) = try {
                archive(tmp.outputStream())
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
            if (!tmp.renameTo(File(autoDir, name))) throw IOException("no se pudo guardar la copia")
            BackupFormat.autoBackupsToDelete(autoDir.list().orEmpty().toList()).forEach { File(autoDir, it).delete() }
            status.recordAuto(now, bytes, inApp = true)
            if (folderProblem != null) "No pude escribir en tu carpeta ($folderProblem): la copia quedó dentro de la app."
            else "Copia guardada dentro de la app (${BackupFormat.sizeLabel(bytes)})."
        }
    }

    /** Copias de antes de actualizar que están dentro de la app, la más nueva primero: nombre y tamaño. */
    fun autoBackupsInApp(): List<Pair<String, Long>> =
        // Con keep = 0 devuelve todas las copias automáticas, de la más nueva a la más vieja.
        BackupFormat.autoBackupsToDelete(autoDir.list().orEmpty().toList(), keep = 0)
            .map { it to File(autoDir, it).length() }

    /** Restaurar una copia de antes de actualizar que está dentro de la app (pide confirmar, como importar). */
    fun restoreAuto(name: String) = start("Leyendo la copia…") {
        try {
            val currentPages = database.diaryDao().pageCount()
            val file = File(autoDir, name)
            if (!BackupFormat.isAutoBackup(name) || !file.exists()) throw BackupException("Esa copia ya no está.")
            val copy = file.inputStream().use { files.extract(it) }
            val live = files.liveDatabaseFacts()
            BackupFormat.importProblem(copy, live.version, live.identityHash)?.let { throw BackupException(it) }
            BackupActivity.Confirm(copy, currentPages, undo = false)
        } catch (e: Exception) {
            files.discardStaged()
            throw e
        }
    }

    private suspend fun writeToFolder(tree: Uri, name: String): Long {
        val resolver = app.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(resolver, parent, "application/zip", name)
            ?: throw IOException("no se pudo crear el archivo")
        try {
            val output = resolver.openOutputStream(doc) ?: throw IOException("no se pudo abrir el archivo")
            val (_, bytes) = archive(output)
            pruneFolder(tree)
            return bytes
        } catch (e: Exception) {
            // Una copia a medias parece una copia y no lo es.
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            throw e
        }
    }

    /** Deja en la carpeta solo las últimas copias automáticas; tus otros archivos no se tocan. */
    private fun pruneFolder(tree: Uri) {
        val resolver = app.contentResolver
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val found = HashMap<String, String>()
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val display = c.getString(1) ?: continue
                found[display] = id
            }
        }
        BackupFormat.autoBackupsToDelete(found.keys.toList()).forEach { display ->
            val id = found[display] ?: return@forEach
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
        }
    }

    /** Nombre visible de la carpeta elegida (p. ej. «KoreanDiary»). */
    private fun folderName(tree: Uri): String = runCatching {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        app.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull().orEmpty().ifBlank { "tu carpeta" }

    /** Descarta la copia preparada sin tocar nada. */
    fun cancel() {
        if (_activity.value !is BackupActivity.Confirm) return
        _activity.value = BackupActivity.Idle
        scope.launch(Dispatchers.IO) { mutex.withLock { files.discardStaged() } }
    }

    fun dismiss() {
        if (_activity.value is BackupActivity.Done || _activity.value is BackupActivity.Failed) _activity.value = BackupActivity.Idle
    }

    private suspend fun archive(output: OutputStream): Pair<BackupManifest, Long> {
        // Si Room aún no abrió la base (instalación recién hecha), esto la crea antes de copiarla.
        database.diaryDao().pageCount()
        return files.writeArchive(output, versionCode, versionName, System.currentTimeMillis())
    }

    /** Un trabajo a la vez, fuera del hilo principal, con el resultado como estado. */
    private fun start(label: String, block: suspend () -> BackupActivity) {
        val current = _activity.value
        if (current is BackupActivity.Working || current is BackupActivity.Restarting) return
        _activity.value = BackupActivity.Working(label)
        scope.launch {
            _activity.value = try {
                mutex.withLock { withContext(Dispatchers.IO) { block() } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                BackupActivity.Failed(e.message ?: "No se pudo completar.")
            } catch (e: Exception) {
                BackupActivity.Failed("No se pudo completar: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Cierra el proceso y pide abrir la app otra vez. Hace falta un proceso nuevo: la base, los
     * ajustes y las listas en memoria son los de antes. Si Android no la reabre sola, basta abrirla
     * a mano: lo preparado se aplica en el próximo arranque sea cuando sea.
     */
    private fun restart() {
        runCatching {
            app.packageManager.getLaunchIntentForPackage(app.packageName)?.let { intent ->
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                app.startActivity(intent)
            }
        }
        Runtime.getRuntime().exit(0)
    }
}
