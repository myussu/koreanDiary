package com.jorge.koreandiary.backup

import android.content.Context
import com.jorge.koreandiary.data.db.AppDatabase
import com.jorge.koreandiary.data.db.create
import kotlinx.coroutines.runBlocking

/**
 * Importar una copia reemplaza la base y los archivos de la app. Eso no se puede hacer con la base
 * abierta, así que la pantalla de Ajustes solo deja la copia preparada y reinicia la app; aquí,
 * al arrancar y antes de abrir nada, se pone en su lugar.
 *
 * Dos redes de seguridad, porque una base que no abre dejaría la app sin poder arrancar:
 * - después de reemplazar se abre la base de verdad con Room; si falla, vuelven los datos de antes
 *   (el .zip que se guardó justo antes de importar);
 * - si la app muere mientras hace esa comprobación, el arranque siguiente también vuelve atrás.
 */
object StartupRestore {

    /** Se llama en `Application.onCreate`, antes de crear el `AppContainer`. */
    fun run(context: Context) {
        val files = BackupFiles(context)
        try {
            if (BackupStateStore.isUnverified(context)) rollback(context, files)

            val pending = files.pendingRestore()
            if (pending == null) {
                // Una preparación sin terminar (se cerró la app antes de confirmar) se descarta.
                files.discardStaged()
                return
            }
            files.applyStaged()
            BackupStateStore.setUnverified(context, true)
            if (!opens(context)) {
                rollback(context, files)
                return
            }
            BackupStateStore.setUnverified(context, false)
            if (pending.kind == BackupFiles.KIND_UNDO) {
                files.undo.delete()
                BackupStateStore.setNotice(context, "Listo: volvieron los datos que tenías antes de importar.")
            } else {
                val date = if (pending.exportedAt > 0) " del ${BackupFormat.dateLabel(pending.exportedAt)}" else ""
                BackupStateStore.setNotice(
                    context,
                    "Listo: ahora ves la copia$date (${BackupFormat.pagesLabel(pending.pages)}). " +
                        "Si era de otro celular, vuelve a escribir las claves de API en Ajustes.",
                )
            }
        } catch (e: Exception) {
            // Pase lo que pase, la app tiene que arrancar: no se reintenta en bucle.
            files.discardStaged()
            BackupStateStore.setUnverified(context, false)
            BackupStateStore.setNotice(
                context,
                "No se pudo terminar de restaurar la copia (${e.message ?: e.javaClass.simpleName}). Vuelve a importarla desde Ajustes.",
            )
        }
    }

    /** Vuelve a los datos guardados justo antes de importar. */
    private fun rollback(context: Context, files: BackupFiles) {
        // Primero se baja la bandera: si esto también falla, no se intenta en cada arranque.
        BackupStateStore.setUnverified(context, false)
        if (!files.undo.exists()) {
            BackupStateStore.setNotice(context, "La copia importada no se pudo abrir y no quedó una copia anterior para volver atrás.")
            return
        }
        files.undo.inputStream().use { files.extract(it) }
        files.applyStaged()
        if (opens(context)) files.undo.delete()
        BackupStateStore.setNotice(
            context,
            "La copia importada no se pudo abrir con esta versión de la app. Volvieron los datos que tenías antes.",
        )
    }

    /**
     * Abre la base con Room (identidad del esquema y migraciones incluidas) y hace una consulta.
     * Bloquea el hilo principal unos milisegundos, y solo en el arranque que sigue a una importación.
     */
    private fun opens(context: Context): Boolean {
        val database = AppDatabase.create(context)
        return try {
            runBlocking { database.diaryDao().pageCount() }
            true
        } catch (e: Exception) {
            false
        } finally {
            runCatching { database.close() }
        }
    }
}
