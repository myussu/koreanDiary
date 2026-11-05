package com.jorge.koreandiary.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jorge.koreandiary.AppContainer
import com.jorge.koreandiary.settings.DarkPaper
import com.jorge.koreandiary.settings.ProviderId
import com.jorge.koreandiary.settings.ThemeMode
import com.jorge.koreandiary.settings.SecretStore
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val s by container.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf(s.profile) }
    var level by remember { mutableStateOf(s.level) }
    var grammarLevels by remember { mutableStateOf(s.grammarLevels) }
    var model by remember { mutableStateOf(s.geminiModel) }
    var limit by remember { mutableStateOf(s.geminiDailyLimit.toString()) }
    var apiKey by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(container.secrets.has(SecretStore.GEMINI_KEY)) }
    var modelStatus by remember { mutableStateOf<String?>(null) }

    fun save() = container.settings.update {
        it.copy(
            profile = profile.trim(),
            level = level.trim().ifEmpty { "3A" },
            grammarLevels = grammarLevels,
            geminiModel = model.trim().ifEmpty { "gemini-3.5-flash-lite" },
            geminiDailyLimit = limit.toIntOrNull()?.coerceAtLeast(1) ?: 30,
        )
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppTextButton(onClick = { save(); onBack() }) { Text("‹ Diario") }
            Text("Ajustes", style = MaterialTheme.typography.titleLarge)
        }

        Section("Perfil") {
            OutlinedTextField(
                value = profile, onValueChange = { profile = it },
                label = { Text("Sobre ti (contexto para las correcciones)") },
                placeholder = { Text("Estudio ingeniería de software en la UPC, en Lima. Trabajo en marketing…") },
                modifier = Modifier.fillMaxWidth(), minLines = 3,
            )
            OutlinedTextField(value = level, onValueChange = { level = it }, label = { Text("Nivel actual") }, singleLine = true)
            OutlinedTextField(
                value = grammarLevels, onValueChange = { grammarLevels = it },
                label = { Text("Niveles de gramática a sugerir (separados por coma)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Section("Actualizaciones") {
            UpdatePanel(container.updater, alwaysVisible = true)
        }

        Section("Apariencia") {
            Text("Tema", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(ThemeMode.SYSTEM to "Sistema", ThemeMode.LIGHT to "Claro", ThemeMode.DARK to "Oscuro").forEach { (id, label) ->
                    AppToggle(label, s.themeMode == id) { container.settings.update { it.copy(themeMode = id) } }
                }
            }
            Text("Papel en modo oscuro", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PaperOption(
                    label = "Caligrafía",
                    style = CalligraphyPaper,
                    selected = s.darkPaper == DarkPaper.CALLIGRAPHY,
                    modifier = Modifier.weight(1f),
                ) { container.settings.update { it.copy(darkPaper = DarkPaper.CALLIGRAPHY) } }
                PaperOption(
                    label = "Negro puro",
                    style = PureBlackPaper,
                    selected = s.darkPaper == DarkPaper.PURE_BLACK,
                    modifier = Modifier.weight(1f),
                ) { container.settings.update { it.copy(darkPaper = DarkPaper.PURE_BLACK) } }
            }
        }

        Section("Escritura") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Solo el S Pen dibuja")
                    Text("El dedo y la palma se ignoran.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = s.stylusOnly, onCheckedChange = { v -> container.settings.update { it.copy(stylusOnly = v) } })
            }
            AppOutlinedButton(onClick = {
                modelStatus = "Descargando…"
                scope.launch {
                    modelStatus = try {
                        if (container.recognizer.isModelDownloaded()) "El modelo coreano ya está descargado."
                        else {
                            container.recognizer.downloadModel()
                            "Modelo coreano descargado. Ya funciona sin internet."
                        }
                    } catch (e: Exception) {
                        "No se pudo descargar: ${e.message}"
                    }
                }
            }) { Text("Descargar modelo de escritura coreana") }
            modelStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        Section("Corrección") {
            Text("Proveedor por defecto", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(ProviderId.SHARE to "Compartir con un chat", ProviderId.GEMINI to "Gemini (API)").forEach { (id, label) ->
                    AppToggle(label, s.defaultProvider == id) { container.settings.update { it.copy(defaultProvider = id) } }
                }
            }
            Text(
                if (hasKey) "Clave de Gemini guardada (cifrada en el Keystore)." else "Sin clave de Gemini. Créala gratis en aistudio.google.com.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it },
                label = { Text(if (hasKey) "Reemplazar clave" else "Clave de API de Gemini") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppButton(onClick = {
                    container.secrets.put(SecretStore.GEMINI_KEY, apiKey.trim())
                    apiKey = ""
                    hasKey = container.secrets.has(SecretStore.GEMINI_KEY)
                }, enabled = apiKey.isNotBlank()) { Text("Guardar clave") }
                if (hasKey) AppTextButton(onClick = {
                    container.secrets.put(SecretStore.GEMINI_KEY, null)
                    hasKey = false
                }) { Text("Borrar clave") }
            }
            OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Modelo de Gemini") }, singleLine = true)
            OutlinedTextField(
                value = limit, onValueChange = { limit = it.filter(Char::isDigit) },
                label = { Text("Límite diario de revisiones") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Text("Usadas hoy: ${container.settings.usageToday(ProviderId.GEMINI)}", style = MaterialTheme.typography.bodySmall)
        }

        AppButton(onClick = { save(); onBack() }, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) { Text("Guardar ajustes") }
    }
}

/** Muestra del papel: fondo, una línea y texto con el color de tinta. */
@Composable
private fun PaperOption(label: String, style: PaperStyle, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().height(72.dp)
                .border(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant)
                .paperBackground(style)
                .drawBehind {
                    val y = size.height * 0.72f
                    drawLine(style.line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f)
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Text("오늘 일기", color = style.ink, fontSize = 22.sp, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
        }
        Text(
            (if (selected) "● " else "○ ") + label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
