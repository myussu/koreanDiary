package com.jorge.koreandiary.backup

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Estado de las copias en un SharedPreferences propio (`backup_state`), que no entra en ninguna
 * copia: describe a este celular, no a tus datos.
 *
 * Las funciones del `companion` escriben sin pasar por el `AppContainer`: las usan el agente de
 * copia de Android (que corre sin `KoreanDiaryApp`) y el arranque, antes de que exista el contenedor.
 */
class BackupStateStore(context: Context) {
    private val prefs = open(context)
    private val _state = MutableStateFlow(read(prefs))
    val state: StateFlow<BackupStatus> = _state.asStateFlow()

    fun recordExport(at: Long, bytes: Long) {
        prefs.edit().putLong(LAST_EXPORT_AT, at).putLong(LAST_EXPORT_BYTES, bytes).apply()
        _state.value = read(prefs)
    }

    /** Carpeta para las copias de antes de actualizar; vacío para guardarlas dentro de la app. */
    fun setAutoFolder(uri: String, name: String) {
        prefs.edit().putString(AUTO_FOLDER, uri).putString(AUTO_FOLDER_NAME, name).apply()
        _state.value = read(prefs)
    }

    fun recordAuto(at: Long, bytes: Long, inApp: Boolean) {
        prefs.edit().putLong(LAST_AUTO_AT, at).putLong(LAST_AUTO_BYTES, bytes).putBoolean(LAST_AUTO_IN_APP, inApp).apply()
        _state.value = read(prefs)
    }

    fun clearNotice() {
        prefs.edit().remove(NOTICE).apply()
        _state.value = read(prefs)
    }

    companion object {
        private const val NAME = "backup_state"
        private const val LAST_EXPORT_AT = "lastExportAt"
        private const val LAST_EXPORT_BYTES = "lastExportBytes"
        private const val LAST_FULL_BACKUP_AT = "lastFullBackupAt"
        private const val QUOTA_EXCEEDED_AT = "quotaExceededAt"
        private const val QUOTA_DATA_BYTES = "quotaDataBytes"
        private const val QUOTA_LIMIT_BYTES = "quotaLimitBytes"
        private const val NOTICE = "notice"
        private const val UNVERIFIED = "restoreUnverified"
        private const val AUTO_FOLDER = "autoFolder"
        private const val AUTO_FOLDER_NAME = "autoFolderName"
        private const val LAST_AUTO_AT = "lastAutoAt"
        private const val LAST_AUTO_BYTES = "lastAutoBytes"
        private const val LAST_AUTO_IN_APP = "lastAutoInApp"

        private fun open(context: Context): SharedPreferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

        private fun read(prefs: SharedPreferences) = BackupStatus(
            lastExportAt = prefs.getLong(LAST_EXPORT_AT, 0),
            lastExportBytes = prefs.getLong(LAST_EXPORT_BYTES, 0),
            lastFullBackupAt = prefs.getLong(LAST_FULL_BACKUP_AT, 0),
            quotaExceededAt = prefs.getLong(QUOTA_EXCEEDED_AT, 0),
            quotaDataBytes = prefs.getLong(QUOTA_DATA_BYTES, 0),
            quotaLimitBytes = prefs.getLong(QUOTA_LIMIT_BYTES, 0),
            notice = prefs.getString(NOTICE, "") ?: "",
            autoFolder = prefs.getString(AUTO_FOLDER, "") ?: "",
            autoFolderName = prefs.getString(AUTO_FOLDER_NAME, "") ?: "",
            lastAutoAt = prefs.getLong(LAST_AUTO_AT, 0),
            lastAutoBytes = prefs.getLong(LAST_AUTO_BYTES, 0),
            lastAutoInApp = prefs.getBoolean(LAST_AUTO_IN_APP, false),
        )

        // commit() y no apply(): quien llama puede morir enseguida (el agente de copia, un reinicio).

        fun recordFullBackup(context: Context, at: Long) {
            open(context).edit().putLong(LAST_FULL_BACKUP_AT, at).commit()
        }

        fun recordQuotaExceeded(context: Context, at: Long, dataBytes: Long, quotaBytes: Long) {
            open(context).edit()
                .putLong(QUOTA_EXCEEDED_AT, at)
                .putLong(QUOTA_DATA_BYTES, dataBytes)
                .putLong(QUOTA_LIMIT_BYTES, quotaBytes)
                .commit()
        }

        fun setNotice(context: Context, text: String) {
            open(context).edit().putString(NOTICE, text).commit()
        }

        /** Se puso una copia en su lugar y todavía no se comprobó que la base abre. */
        fun isUnverified(context: Context): Boolean = open(context).getBoolean(UNVERIFIED, false)

        fun setUnverified(context: Context, value: Boolean) {
            open(context).edit().putBoolean(UNVERIFIED, value).commit()
        }
    }
}
