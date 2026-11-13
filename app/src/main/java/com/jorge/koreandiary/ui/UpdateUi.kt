package com.jorge.koreandiary.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jorge.koreandiary.update.ReleaseInfo
import com.jorge.koreandiary.update.UpdateState
import com.jorge.koreandiary.update.Updater
import kotlinx.coroutines.launch

/**
 * Panel de actualización. En el inicio solo aparece cuando hay algo que hacer;
 * en Ajustes ([alwaysVisible]) muestra también "Buscar actualización" y la versión instalada.
 */
@Composable
fun UpdatePanel(updater: Updater, alwaysVisible: Boolean, modifier: Modifier = Modifier) {
    val state by updater.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme

    val actionable = state is UpdateState.Available || state is UpdateState.NeedsPermission ||
        state is UpdateState.Downloading || state is UpdateState.BackingUp || state is UpdateState.Installing ||
        (state is UpdateState.Error && (state as UpdateState.Error).info != null)
    if (!alwaysVisible && !actionable) return

    Column(
        modifier.fillMaxWidth().border(1.dp, scheme.outlineVariant).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (alwaysVisible) {
            Text(
                "Versión instalada ${updater.currentVersionName} (compilación #${updater.currentVersionCode})",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        when (val s = state) {
            UpdateState.Idle -> AppOutlinedButton(onClick = { scope.launch { updater.check() } }) { Text("Buscar actualización") }
            UpdateState.Checking -> Text("Buscando…", style = MaterialTheme.typography.bodyMedium)
            UpdateState.UpToDate -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tienes la última versión.", modifier = Modifier.weight(1f))
                AppTextButton(onClick = { scope.launch { updater.check() } }) { Text("Volver a buscar") }
            }
            is UpdateState.Available -> {
                ReleaseSummary(s.info)
                AppButton(onClick = { updater.startInstall(s.info) }) { Text("Actualizar ahora") }
            }
            is UpdateState.NeedsPermission -> {
                Text(
                    "Para actualizarse sola, la app necesita el permiso “Instalar apps desconocidas”. Solo se pide una vez.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppOutlinedButton(onClick = { context.startActivity(updater.permissionSettingsIntent()) }) { Text("Dar permiso") }
                    AppButton(onClick = { updater.startInstall(s.info) }) { Text("Ya lo di, actualizar") }
                }
            }
            is UpdateState.Downloading -> {
                Text("Descargando ${s.info.versionName}… ${(s.progress * 100).toInt()} %")
                LinearProgressIndicator(
                    progress = { s.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = scheme.primary,
                    trackColor = scheme.surfaceContainerHighest,
                    strokeCap = StrokeCap.Butt,
                )
            }
            is UpdateState.BackingUp -> {
                Text("Guardando una copia de todo antes de actualizar…", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = scheme.primary, trackColor = scheme.surfaceContainerHighest, strokeCap = StrokeCap.Butt)
            }
            is UpdateState.Installing -> {
                s.backupNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                Text(
                    "Instalando ${s.info.versionName}… La app se cerrará sola al terminar; ábrela de nuevo.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            is UpdateState.Error -> {
                Text(s.message, color = scheme.error, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (s.info != null) {
                        AppButton(onClick = { updater.startInstall(s.info) }) { Text("Reintentar") }
                        if (s.backupFailed) {
                            AppOutlinedButton(onClick = { updater.startInstall(s.info, skipBackup = true) }) { Text("Actualizar sin copia") }
                        }
                    } else {
                        AppOutlinedButton(onClick = { scope.launch { updater.check() } }) { Text("Reintentar") }
                    }
                    AppTextButton(onClick = updater::dismiss) { Text("Cerrar") }
                }
            }
        }
    }
}

@Composable
private fun ReleaseSummary(info: ReleaseInfo) {
    Text("Nueva versión ${info.versionName}", style = MaterialTheme.typography.titleSmall)
    if (info.notes.isNotBlank()) {
        Text(
            info.notes,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (info.size > 0) Text("${info.size / 1_048_576} MB", style = MaterialTheme.typography.labelSmall)
}
