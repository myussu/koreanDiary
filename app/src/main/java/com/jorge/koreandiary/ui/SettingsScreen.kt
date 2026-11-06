package com.jorge.koreandiary.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.jorge.koreandiary.ai.CorrectionRequest
import com.jorge.koreandiary.mascot.MascotMood
import com.jorge.koreandiary.settings.DarkPaper
import com.jorge.koreandiary.settings.ProviderId
import com.jorge.koreandiary.settings.ThemeMode
import com.jorge.koreandiary.settings.SecretStore
import kotlinx.coroutines.CancellationException
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
    var azureEndpoint by remember { mutableStateOf(s.azureEndpoint) }
    var azureDeployment by remember { mutableStateOf(s.azureDeployment) }
    var azureLimit by remember { mutableStateOf(s.azureDailyLimit.toString()) }
    var modelStatus by remember { mutableStateOf<String?>(null) }

    fun save() = container.settings.update {
        it.copy(
            profile = profile.trim(),
            level = level.trim().ifEmpty { "3A" },
            grammarLevels = grammarLevels,
            geminiModel = model.trim().ifEmpty { "gemini-3.5-flash-lite" },
            geminiDailyLimit = limit.toIntOrNull()?.coerceAtLeast(1) ?: 30,
            azureEndpoint = azureEndpoint.trim(),
            azureDeployment = azureDeployment.trim(),
            azureDailyLimit = azureLimit.toIntOrNull()?.coerceAtLeast(1) ?: 30,
        )
    }

    // Guardar también al salir con el gesto o botón "atrás" del sistema, no solo con los botones.
    DisposableEffect(Unit) { onDispose { save() } }

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

        Section("Mascota") {
            MascotSettings(container)
        }

        Section("Escritura") {
            Text("Grosor del lápiz", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("fino" to "Fino", "medio" to "Medio", "grueso" to "Grueso").forEach { (id, label) ->
                    AppToggle(label, s.penWidth == id) { container.settings.update { it.copy(penWidth = id) } }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Presión del S Pen")
                    Text(
                        "El trazo engrosa o adelgaza según cuánto presionas, como un pincel. Apágalo para un trazo uniforme que ocupa menos espacio.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = s.penPressure, onCheckedChange = { v -> container.settings.update { it.copy(penPressure = v) } })
            }
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                listOf(
                    ProviderId.SHARE to "Compartir con un chat",
                    ProviderId.GEMINI to "Gemini",
                    ProviderId.AZURE to "Azure OpenAI",
                ).forEach { (id, label) ->
                    AppToggle(label, s.defaultProvider == id) { container.settings.update { it.copy(defaultProvider = id) } }
                }
            }
            Text(
                "Al revisar una página solo aparecen los proveedores configurados.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Section("Gemini") {
            Text("Clave gratuita en aistudio.google.com/apikey. En el plan gratuito Google puede usar el contenido.", style = MaterialTheme.typography.bodySmall)
            SecretKeyField(container, SecretStore.GEMINI_KEY, "Clave de API de Gemini")
            OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Modelo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = limit, onValueChange = { limit = it.filter(Char::isDigit) },
                label = { Text("Límite diario de revisiones") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Text("Usadas hoy: ${container.settings.usageToday(ProviderId.GEMINI)}", style = MaterialTheme.typography.bodySmall)
            TestConnection(container, ProviderId.GEMINI, beforeTest = ::save)
        }

        Section("Azure OpenAI") {
            Text("Azure no usa tus datos para entrenar modelos. Recomendado: gpt-5.6-luna en Global Standard.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = azureEndpoint, onValueChange = { azureEndpoint = it },
                label = { Text("Endpoint") },
                placeholder = { Text("https://mi-recurso.openai.azure.com") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = azureDeployment, onValueChange = { azureDeployment = it },
                label = { Text("Nombre del despliegue") },
                placeholder = { Text("luna") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            SecretKeyField(container, SecretStore.AZURE_KEY, "Clave de API de Azure")
            Text("Razonamiento", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("low" to "Bajo", "medium" to "Medio", "high" to "Alto").forEach { (id, label) ->
                    AppToggle(label, s.azureEffort == id) { container.settings.update { it.copy(azureEffort = id) } }
                }
            }
            Text("Bajo es más rápido y barato; sube solo si las correcciones se quedan cortas.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = azureLimit, onValueChange = { azureLimit = it.filter(Char::isDigit) },
                label = { Text("Límite diario de revisiones") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Text("Usadas hoy: ${container.settings.usageToday(ProviderId.AZURE)}", style = MaterialTheme.typography.bodySmall)
            TestConnection(container, ProviderId.AZURE, beforeTest = ::save)
        }

        Section("Fuentes") {
            Text(
                "Niveles de vocabulario y gramática: 국립국어원, «2017년 국제 통용 한국어 표준 교육과정 적용 연구(4단계) " +
                    "어휘, 문법 등급 목록» (rev. 2020-11-17). Licencia 공공누리 제1유형 (uso libre con atribución).",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Tu nivel ${s.level} se compara con el nivel ${s.standardLevel} de 6 del estándar.", style = MaterialTheme.typography.bodySmall)
        }

        AppButton(onClick = { save(); onBack() }, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) { Text("Guardar ajustes") }
    }
}

@Composable
private fun MascotSettings(container: AppContainer) {
    val s by container.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val version by container.mascotPack.version.collectAsStateWithLifecycle()
    val hasCustom = remember(version) { container.mascotPack.hasCustom() }
    var result by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val r = container.mascotPack.import(uris)
            result = buildString {
                if (r.imported.isEmpty()) append("No se reconoció ninguna imagen. ")
                else append("Importadas: ${r.imported.joinToString { it.storedName }}. ")
                if (r.imported.isNotEmpty() && r.missing.isNotEmpty()) append("Sin imagen (usarán “idle”): ${r.missing.joinToString { it.storedName }}. ")
                if (MascotMood.IDLE !in r.imported && !container.mascotPack.hasCustom()) append("Falta idle.png, que es obligatoria. ")
                if (r.ignored.isNotEmpty()) append("Ignoradas: ${r.ignored.take(5).joinToString()}.")
            }.trim()
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        AppMascot(container.mascotPack, MascotMood.HAPPY, 56.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("Mostrar mascota")
            Text("Saluda en coreano en el inicio y reacciona a tus revisiones.", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = s.showMascot, onCheckedChange = { v -> container.settings.update { it.copy(showMascot = v) } })
    }
    Text(
        "Mascota personalizada: elige imágenes PNG o un ZIP. Nómbralas idle, feliz, pensando, sorprendida, animo y dormida " +
            "(solo idle es obligatoria). Se guardan solo en este celular.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AppOutlinedButton(onClick = { picker.launch(arrayOf("image/*", "application/zip")) }) { Text("Importar mascota") }
        if (hasCustom) AppTextButton(onClick = {
            container.mascotPack.clear()
            result = "Volviste a la gotita de tinta."
        }) { Text("Usar la original") }
    }
    result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

/** Campo para una clave cifrada: nunca se muestra, solo se reemplaza o se borra. */
@Composable
private fun SecretKeyField(container: AppContainer, name: String, label: String) {
    var value by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(container.secrets.has(name)) }
    Text(
        if (saved) "Clave guardada (cifrada en el Keystore)." else "Sin clave.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(
        value = value, onValueChange = { value = it },
        label = { Text(if (saved) "Reemplazar clave" else label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AppButton(onClick = {
            container.secrets.put(name, value.trim())
            value = ""
            saved = container.secrets.has(name)
        }, enabled = value.isNotBlank()) { Text("Guardar clave") }
        if (saved) AppTextButton(onClick = {
            container.secrets.put(name, null)
            saved = false
        }) { Text("Borrar clave") }
    }
}

/** Manda una petición mínima para comprobar endpoint, clave y modelo. No cuenta para el límite diario. */
@Composable
private fun TestConnection(container: AppContainer, provider: String, beforeTest: () -> Unit) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    AppOutlinedButton(onClick = {
        beforeTest()
        val api = container.apiProvider(provider)
        if (api == null) {
            result = "Falta completar la configuración."
            return@AppOutlinedButton
        }
        testing = true
        result = "Probando…"
        scope.launch {
            result = try {
                val reply = api.complete(CorrectionRequest("Responde solo con este JSON, sin nada más: {\"ok\": true}"))
                if ("ok" in reply) "Conexión correcta." else "Respondió, pero algo raro: ${reply.take(120)}"
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
