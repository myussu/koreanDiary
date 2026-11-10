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
