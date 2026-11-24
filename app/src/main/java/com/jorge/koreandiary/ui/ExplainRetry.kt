package com.jorge.koreandiary.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jorge.koreandiary.KoreanDiaryApp

/**
 * Cuando falla la IA de las explicaciones (saturada, sin cuota, sin acceso al modelo): «Reintentar»
 * con el mismo modelo o «Probar con …» el siguiente modelo registrado (Azure, el de corregir de
 * Gemini…), que queda hasta cerrar la app. Pedido por Jorge el 2026-11-24, cuando
 * gemini-4-hydrogen respondió 503 por demanda alta.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExplainRetryRow(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val container = (LocalContext.current.applicationContext as KoreanDiaryApp).container
    val override by container.explainOverride.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val alternative = remember(override, settings) { container.explainAlternative() }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppOutlinedButton(onClick = onRetry, modifier = Modifier.testTag("ia_reintentar")) { Text("Reintentar") }
        alternative?.let { alt ->
            AppOutlinedButton(
                onClick = {
                    container.useExplainAlternative()
                    onRetry()
                },
                modifier = Modifier.testTag("ia_otro_modelo"),
            ) { Text("Probar con ${alt.label}") }
        }
    }
}
