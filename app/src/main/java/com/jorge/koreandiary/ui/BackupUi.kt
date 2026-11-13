package com.jorge.koreandiary.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jorge.koreandiary.AppContainer
import com.jorge.koreandiary.backup.BackupActivity
import com.jorge.koreandiary.backup.BackupFormat
import com.jorge.koreandiary.backup.BackupStatus

/**
 * Sección de Ajustes: exportar e importar todo a un archivo, y cómo va la copia automática de Google.
 * [beforeExport] guarda lo que esté escrito en los campos de Ajustes para que entre en la copia.
 */
@Composable
fun BackupSettings(container: AppContainer, beforeExport: () -> Unit) {
    val backup = container.backup
    val status by backup.status.state.collectAsStateWithLifecycle()
    val activity by backup.activity.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val current = activity
    val idle = current is BackupActivity.Idle || current is BackupActivity.Done || current is BackupActivity.Failed
    // El archivo para deshacer aparece y desaparece con cada operación.
    val undoAvailable = remember(current) { backup.undoAvailable }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) backup.exportTo(uri)
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) backup.importFrom(uri)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching { backup.setAutoFolder(uri) }
    }
    // Las copias de dentro de la app cambian con cada actualización y cada restauración.
    val inApp = remember(current, status.lastAutoAt) { backup.autoBackupsInApp() }

    Text(
        "Guarda todo en un archivo .zip: páginas, listas, repaso, workbooks con sus fotos, conversaciones, mascota y ajustes. " +
            "Las claves de API no entran. Déjalo fuera del celular (Drive, tu computadora): es la copia que depende de ti.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        if (status.lastExportAt > 0) {
            "Última copia a un archivo: ${BackupFormat.dateLabel(status.lastExportAt)} · ${BackupFormat.sizeLabel(status.lastExportBytes)}"
        } else {
            "Todavía no has exportado ninguna copia."
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AppButton(
            onClick = {
                beforeExport()
                create.launch(BackupFormat.fileName(System.currentTimeMillis()))
            },
            enabled = idle,
        ) { Text("Exportar copia") }
        AppOutlinedButton(
            // Según quién guarde el archivo, un .zip llega con uno de estos tipos.
            onClick = { open.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
            enabled = idle,
        ) { Text("Importar copia") }
    }
    if (undoAvailable) {
        AppTextButton(onClick = backup::undoLastImport, enabled = idle) { Text("Deshacer la última importación") }
    }

    Text("Copia antes de actualizar", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
    Text(
        "Antes de instalar cada versión nueva, la app guarda una copia de todo. Quedan las ${BackupFormat.AUTO_KEEP} últimas. " +
            "En una carpeta tuya sobrevive aunque desinstales la app, y se recupera con «Importar copia».",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        if (status.autoFolder.isNotEmpty()) "Carpeta: ${status.autoFolderName}"
        else "Sin carpeta: se guarda dentro de la app (se borra si desinstalas).",
        style = MaterialTheme.typography.bodyMedium,
    )
    if (status.lastAutoAt > 0) {
        Text(
            "Última: ${BackupFormat.dateLabel(status.lastAutoAt)} · ${BackupFormat.sizeLabel(status.lastAutoBytes)} · " +
                if (status.lastAutoInApp) "dentro de la app" else "en tu carpeta",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AppOutlinedButton(onClick = { pickFolder.launch(null) }, enabled = idle) {
            Text(if (status.autoFolder.isNotEmpty()) "Cambiar carpeta" else "Elegir carpeta")
        }
        if (status.autoFolder.isNotEmpty()) {
            AppTextButton(onClick = { backup.setAutoFolder(null) }, enabled = idle) { Text("Quitar carpeta") }
        }
    }
    inApp.forEach { (name, bytes) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Dentro de la app: " + name.removePrefix(BackupFormat.AUTO_PREFIX).removeSuffix(".zip") + " · " + BackupFormat.sizeLabel(bytes),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            AppTextButton(onClick = { backup.restoreAuto(name) }, enabled = idle) { Text("Restaurar") }
        }
    }

    when (current) {
        BackupActivity.Idle -> Unit
        is BackupActivity.Working -> Text(current.label, style = MaterialTheme.typography.bodyMedium)
        BackupActivity.Restarting -> Text(
            "Cerrando la app para abrirla con la copia… Si no se abre sola, ábrela tú.",
            style = MaterialTheme.typography.bodyMedium,
        )
        is BackupActivity.Done -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(current.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            AppTextButton(onClick = backup::dismiss) { Text("Cerrar") }
        }
        is BackupActivity.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(current.message, color = scheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            AppTextButton(onClick = backup::dismiss) { Text("Cerrar") }
        }
        is BackupActivity.Confirm -> AlertDialog(
            onDismissRequest = backup::cancel,
            title = { Text(if (current.undo) "¿Volver a los datos de antes?" else "¿Reemplazar los datos de este celular?") },
            text = {
                Text(
                    "Copia del ${BackupFormat.dateLabel(current.incoming.exportedAt)}: " +
                        "${BackupFormat.pagesLabel(current.incoming.pages)} (versión ${current.incoming.appVersionName}).\n\n" +
                        "Reemplaza todo lo que hay ahora en la app: ${BackupFormat.pagesLabel(current.currentPages)}, listas, repaso, " +
                        "workbooks, conversaciones, mascota y ajustes. Las claves de API se quedan como están.\n\n" +
                        (if (current.undo) "" else "Antes se guarda lo que hay ahora, para poder deshacer. ") +
                        "La app se cierra y vuelve a abrirse con la copia; si no se abre sola, ábrela tú.",
                )
            },
            confirmButton = { AppButton(onClick = backup::confirm) { Text("Reemplazar") } },
            dismissButton = { AppTextButton(onClick = backup::cancel) { Text("Cancelar") } },
        )
    }

    Text("Copia automática de Google", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
    if (status.overQuota) {
        Text(quotaText(status) + " Mientras no quepan, Google no guarda nada nuevo: tu copia es el archivo de arriba.", color = scheme.error, style = MaterialTheme.typography.bodyMedium)
    } else {
        Text(
            if (status.lastFullBackupAt > 0) "Android la pasó por última vez el ${BackupFormat.dateLabel(status.lastFullBackupAt)}."
            else "Android todavía no la ha pasado desde que la app lo registra.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Text(
        "La hace Android cuando quiere (como mucho una vez al día, con Wi‑Fi y el celular en reposo) y solo se recupera al instalar la app. " +
            "Guarda hasta 25 MB y no lleva las fotos del workbook. A la app no le dice si se subió; solo le avisa cuando no cabe.",
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * Avisos de la copia en el inicio. Solo aparecen cuando hay algo que decir: el resultado de una
 * restauración, o que Google dejó de guardar y tampoco hay un archivo reciente.
 */
@Composable
fun BackupBanner(container: AppContainer, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    val status by container.backup.status.state.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

    if (status.notice.isNotBlank()) {
        Row(
            modifier.fillMaxWidth().border(1.dp, scheme.outlineVariant).padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(status.notice, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            AppTextButton(onClick = container.backup.status::clearNotice) { Text("Cerrar") }
        }
    }
    if (status.needsAttention(System.currentTimeMillis())) {
        Column(
            modifier.fillMaxWidth().border(1.dp, scheme.error).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                quotaText(status) + if (status.lastExportAt > 0) " Tu última copia a un archivo es del ${BackupFormat.dateLabel(status.lastExportAt)}."
                else " Y todavía no has exportado ninguna copia a un archivo.",
                style = MaterialTheme.typography.bodyMedium,
            )
            AppOutlinedButton(onClick = onSettings) { Text("Exportar una copia en Ajustes") }
        }
    }
}

private fun quotaText(status: BackupStatus): String =
    "La copia de Google dejó de guardar tus datos: pesan ${BackupFormat.sizeLabel(status.quotaDataBytes)} y su límite es " +
        "${BackupFormat.sizeLabel(status.quotaLimitBytes)}."
