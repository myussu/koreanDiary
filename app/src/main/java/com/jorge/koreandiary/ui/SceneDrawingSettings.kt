package com.jorge.koreandiary.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jorge.koreandiary.AppContainer
import com.jorge.koreandiary.ai.ClaudeClient
import com.jorge.koreandiary.scene.SceneDrawings
import com.jorge.koreandiary.settings.SecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Ajustes › Escenas → «Dibujos nuevos (Claude)»: la clave de la API de Claude, el modelo, si dibuja solo al
 * revisar una escena y el acceso a la ventana de dibujos.
 */
@Composable
fun SceneDrawingSettings(container: AppContainer, onOpenDrawings: () -> Unit) {
    val s by container.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var model by remember { mutableStateOf(s.claudeModel) }
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    Text(
        "Al revisar una escena, lo que se ve y la escena viva no sabía pintar (un gato, un faro) lo dibuja Claude en acuarela. " +
            "Claude dibuja, mira cómo quedó y lo corrige. Se dibuja una vez y queda: desde entonces aparece al escribir su palabra. " +
            "Usa tu clave de la API de Claude (se paga por uso: unos 5 a 8 centavos de dólar por dibujo con Sonnet) y como mucho " +
            "${SceneDrawings.DAILY_LIMIT} dibujos por día.",
        style = MaterialTheme.typography.bodySmall,
    )
    SecretKeyField(container, SecretStore.CLAUDE_KEY, "Clave de API de Claude")
    OutlinedTextField(
        value = model,
        onValueChange = { v ->
            model = v
            container.settings.update { it.copy(claudeModel = v.trim().ifEmpty { ClaudeClient.DEFAULT_MODEL }) }
        },
        label = { Text("Modelo") },
        placeholder = { Text(ClaudeClient.DEFAULT_MODEL) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text("claude-sonnet-5-5 dibuja bien y barato; claude-haiku-5-5 es más barato y más simple; claude-opus-5-5 es el mejor.", style = MaterialTheme.typography.bodySmall)
    AppOutlinedButton(onClick = {
        testing = true
        result = "Probando…"
        scope.launch {
            result = try {
                container.sceneDrawings.test()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            } finally {
                testing = false
            }
        }
    }, enabled = !testing) { Text("Probar conexión") }
    result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Dibujar al revisar una escena", modifier = Modifier.weight(1f))
        Switch(checked = s.sceneDrawAuto, onCheckedChange = { v -> container.settings.update { it.copy(sceneDrawAuto = v) } })
    }
    Text(
        "Apagado, lo nuevo queda en espera en la ventana de dibujos y lo dibujas con «Dibujar». Hoy: ${container.sceneDrawings.usedToday()} de ${SceneDrawings.DAILY_LIMIT}.",
        style = MaterialTheme.typography.bodySmall,
    )
    AppOutlinedButton(onClick = onOpenDrawings) { Text("Ver dibujos") }
}
