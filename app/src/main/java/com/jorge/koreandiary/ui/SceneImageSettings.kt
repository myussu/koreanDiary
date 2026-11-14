package com.jorge.koreandiary.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jorge.koreandiary.AppContainer
import com.jorge.koreandiary.scene.SceneImage
import com.jorge.koreandiary.scene.comfyCheckpoints
import kotlinx.coroutines.launch

/**
 * Ajustes → «Imagen de la escena»: tu servidor de ComfyUI (por Tailscale), el modelo con que pinta,
 * si pinta solo al terminar una escena y si la imagen va de fondo.
 */
@Composable
fun SceneImageSettings(container: AppContainer) {
    val s by container.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    var server by remember { mutableStateOf(s.imageServer) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var checkpoints by remember { mutableStateOf<List<String>>(emptyList()) }

    Text(
        "Al terminar una escena (revisada y sin correcciones pendientes), tu servidor de ComfyUI la pinta en acuarela. " +
            "Llega por Tailscale: el celular y el servidor tienen que estar conectados a tu red. No gasta cuota de ninguna IA.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(
        value = server,
        onValueChange = { v ->
            server = v
            container.settings.update { it.copy(imageServer = v.trim()) }
        },
        label = { Text("Dirección del servidor") },
        placeholder = { Text("http://truenas:8188") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    AppOutlinedButton(
        onClick = {
            val url = SceneImage.normalizeServer(server)
            if (url == null) {
                failed = true
                status = "Escribe la dirección, por ejemplo http://truenas:8188 (el nombre o la IP de Tailscale y el puerto de ComfyUI)."
            } else {
                testing = true
                status = null
                scope.launch {
                    comfyCheckpoints(url).fold(
                        onSuccess = { list ->
                            checkpoints = list
                            failed = list.isEmpty()
                            status = if (list.isEmpty()) {
                                "Conectó, pero no encontré modelos instalados en ComfyUI."
                            } else {
                                "Conectó. Elige el modelo con que pinta:"
                            }
                            if (list.isNotEmpty() && s.imageCheckpoint !in list) {
                                container.settings.update { it.copy(imageCheckpoint = list.first()) }
                            }
                        },
                        onFailure = {
                            failed = true
                            status = it.message
                        },
                    )
                    testing = false
                }
            }
        },
        enabled = !testing,
    ) { Text(if (testing) "Probando…" else "Probar conexión") }
    status?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = if (failed) scheme.error else scheme.onSurface)
    }
    if (checkpoints.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            checkpoints.forEach { name ->
                AppToggle(name.substringAfterLast('/').substringAfterLast('\\'), s.imageCheckpoint == name) {
                    container.settings.update { it.copy(imageCheckpoint = name) }
                }
            }
        }
    } else if (s.imageCheckpoint.isNotBlank()) {
        Text("Modelo: ${s.imageCheckpoint}", style = MaterialTheme.typography.bodySmall)
    }
    Text(
        "Conviene un modelo SDXL (Juggernaut XL, por ejemplo): con 8 GB de VRAM tarda más o menos medio minuto. " +
            "Sin servidor también puedes traer la imagen desde el chat de Gemini con el botón «Imagen» de cada escena.",
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Pintar al terminar una escena")
            Text("Sin tocar nada. Si no hay servidor, no pasa nada.", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = s.sceneImageAuto, onCheckedChange = { v -> container.settings.update { it.copy(sceneImageAuto = v) } })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Imagen de fondo en la escena")
            Text("Muy suave, detrás de la tinta.", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = s.sceneImageBackground, onCheckedChange = { v -> container.settings.update { it.copy(sceneImageBackground = v) } })
    }
}
