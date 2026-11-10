package com.jorge.koreandiary.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.Build
import android.os.ParcelFileDescriptor

/**
 * Agente de la copia automática de Android. La copia sigue siendo la de siempre (los archivos que
 * dicen `res/xml/data_extraction_rules.xml` y `backup_rules.xml`); el agente existe solo para
 * enterarse de dos cosas que Android no cuenta de otra forma: cuándo pasó la copia y, sobre todo,
 * cuándo Google la rechazó por pesar más de su límite (25 MB por app). Sin esto la copia deja de
 * subir en silencio.
 *
 * Android lo ejecuta con la app en modo restringido: sin `KoreanDiaryApp` ni `AppContainer`.
 * Por eso solo escribe en [BackupStateStore] y no toca nada más.
 */
class DiaryBackupAgent : BackupAgent() {

    // Copia clave-valor: no se usa (el manifiesto pide fullBackupOnly), pero son abstractos.
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) = Unit

    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit

    /** Empieza una pasada (Android la llama para medir y otra vez para copiar). `super` aplica las reglas XML. */
    override fun onFullBackup(data: FullBackupDataOutput) {
        if (!isDeviceTransfer(data)) BackupStateStore.recordFullBackup(this, System.currentTimeMillis())
        super.onFullBackup(data)
    }

    /** Lo que hay que copiar no cabe: Google no guarda nada hasta que vuelva a caber. */
    override fun onQuotaExceeded(backupDataBytes: Long, quotaBytes: Long) {
        BackupStateStore.recordQuotaExceeded(this, System.currentTimeMillis(), backupDataBytes, quotaBytes)
    }

    /** Datos restaurados (al instalar la app en un celular nuevo o reinstalarla). */
    override fun onRestoreFinished() {
        BackupStateStore.setNotice(
            this,
            "Se restauraron tus datos desde la copia de Android. Las claves de API no viajan: vuelve a escribirlas en Ajustes.",
        )
    }

    /** El paso directo de un celular a otro no tiene el límite de la nube ni cuenta como copia. */
    private fun isDeviceTransfer(data: FullBackupDataOutput): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && (data.transportFlags and FLAG_DEVICE_TO_DEVICE_TRANSFER) != 0
}
