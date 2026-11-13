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
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.jorge.koreandiary.reminder.ReminderScheduler
import com.jorge.koreandiary.settings.AppSettings
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
import com.jorge.koreandiary.conversation.CorrectionStyle
import com.jorge.koreandiary.listen.Narrator
import com.jorge.koreandiary.voice.TurnMode
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
    var voiceModel by remember { mutableStateOf(s.voiceModel) }
    var voiceName by remember { mutableStateOf(s.voiceName) }
    var voiceDaily by remember { mutableStateOf(s.voiceDailyMinutes.toString()) }
    var listenScriptModel by remember { mutableStateOf(s.listenScriptModel) }
    var listenTtsModel by remember { mutableStateOf(s.listenTtsModel) }

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
            voiceModel = voiceModel.trim().ifEmpty { "gemini-3.8-live" },
            voiceName = voiceName.trim().ifEmpty { "Kore" },
            voiceDailyMinutes = voiceDaily.toIntOrNull()?.coerceAtLeast(1) ?: 30,
            listenScriptModel = listenScriptModel.trim().ifEmpty { "gemini-3.8-flash" },
            listenTtsModel = listenTtsModel.trim().ifEmpty { "gemini-3.8-flash-tts" },
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

        Section("Copia de seguridad") {
            BackupSettings(container, beforeExport = ::save)
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

        Section("Recordatorio") {
            ReminderSettings(container)
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
                    Text("Escena viva en los bordes")
                    Text(
                        "En las escenas, los bordes de la página se llenan con lo que escribes: mar, pasto, viento, sol… Lee tu letra mientras escribes, sin gastar cuota de la IA.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = s.liveScene, onCheckedChange = { v -> container.settings.update { it.copy(liveScene = v) } })
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

        Section("Conversación") {
            Text(
                "Usa la Live API de Gemini con la misma clave del diario. Las pistas, traducciones y la revisión final usan tu proveedor de corrección.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Turnos", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppToggle("Pulsar para hablar", s.voiceTurnMode == TurnMode.PUSH_TO_TALK) {
                    container.settings.update { it.copy(voiceTurnMode = TurnMode.PUSH_TO_TALK) }
                }
                AppToggle("Automático", s.voiceTurnMode == TurnMode.AUTO) {
                    container.settings.update { it.copy(voiceTurnMode = TurnMode.AUTO) }
                }
            }
            Text(
                if (s.voiceTurnMode == TurnMode.AUTO) "La IA responde tras un silencio. Si dudas mucho, súbelo; con audífonos funciona mejor."
                else "Pulsas «Hablar» y luego «Listo»: la IA no te corta aunque pienses un minuto.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (s.voiceTurnMode == TurnMode.AUTO) {
                Text("Silencio antes de responder", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1000, 2000, 3000, 4000).forEach { ms ->
                        AppToggle("${ms / 1000} s", s.voiceSilenceMs == ms) { container.settings.update { it.copy(voiceSilenceMs = ms) } }
                    }
                }
            }
            Text("Corrección durante la conversación", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppToggle("Pedirme repetir", s.voiceCorrection == CorrectionStyle.REPEAT) {
                    container.settings.update { it.copy(voiceCorrection = CorrectionStyle.REPEAT) }
                }
                AppToggle("Suave", s.voiceCorrection == CorrectionStyle.SOFT) {
                    container.settings.update { it.copy(voiceCorrection = CorrectionStyle.SOFT) }
                }
            }
            Text(
                if (s.voiceCorrection == CorrectionStyle.SOFT) "Repite tu idea bien dicha dentro de su respuesta, sin pararse."
                else "Errores menores: los repite bien dichos. Si algo cambia el sentido o suena muy raro, te pide repetirlo (máximo cada 3 turnos).",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Pista automática")
                    Text(
                        "Si la IA terminó y no respondes, aparece la primera pista sola. Cada una cuenta para el límite diario de tu proveedor.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = s.voiceAutoHint, onCheckedChange = { v -> container.settings.update { it.copy(voiceAutoHint = v) } })
            }
            if (s.voiceAutoHint) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(6, 8, 12, 20).forEach { sec ->
                        AppToggle("$sec s", s.voiceAutoHintSec == sec) { container.settings.update { it.copy(voiceAutoHintSec = sec) } }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Texto de la IA borroso")
                    Text("Se ve al tocarlo: practicas escuchar en lugar de leer.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = s.voiceBlurText, onCheckedChange = { v -> container.settings.update { it.copy(voiceBlurText = v) } })
            }
            OutlinedTextField(value = voiceModel, onValueChange = { voiceModel = it }, label = { Text("Modelo de voz") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("gemini-3.8-live (rápido y barato) o gemini-3.8-live-extended-thinking (razona más, ~4 veces más caro).", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = voiceName, onValueChange = { voiceName = it }, label = { Text("Voz (Kore, Puck, Charon, Aoede…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = voiceDaily, onValueChange = { voiceDaily = it.filter(Char::isDigit) },
                label = { Text("Minutos de conversación por día") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }

        Section("Escuchar") {
            Text(
                "Un pódcast corto con pausas para preguntar. El guion lo escribe Gemini y lo narra una voz; las preguntas usan el modelo de voz de arriba. Cuenta para los mismos minutos por día.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Voz del narrador", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppToggle("Gemini", s.listenNarrator == Narrator.GEMINI) { container.settings.update { it.copy(listenNarrator = Narrator.GEMINI) } }
                AppToggle("Voz del celular", s.listenNarrator == Narrator.SYSTEM) { container.settings.update { it.copy(listenNarrator = Narrator.SYSTEM) } }
            }
            Text(
                if (s.listenNarrator == Narrator.GEMINI) "Voz natural (usa la voz elegida arriba). Son 2 pedidos por sesión y la capa gratuita da pocos al día: si se acaban, sigue sola con la voz del celular."
                else "Sin conexión ni cuota, menos natural. Necesita la voz coreana instalada en el celular.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Velocidad", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.75f, 0.85f, 0.9f, 1f).forEach { v ->
                    AppToggle("${v}×", kotlin.math.abs(s.listenSpeed - v) < 0.01f) { container.settings.update { it.copy(listenSpeed = v) } }
                }
            }
            Text("Pausa entre segmentos", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 2, 4, 6).forEach { sec ->
                    AppToggle(if (sec == 0) "Seguido" else "$sec s", s.listenGapSec == sec) { container.settings.update { it.copy(listenGapSec = sec) } }
                }
            }
            Text("Después de las preguntas de la IA siempre espera a que digas «sigue».", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = listenScriptModel, onValueChange = { listenScriptModel = it }, label = { Text("Modelo del guion") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Si se acaba su cuota diaria se usa tu modelo de Gemini del diario.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = listenTtsModel, onValueChange = { listenTtsModel = it }, label = { Text("Modelo de voz del narrador") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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

/** Aviso diario a la hora elegida; solo suena si ese día aún no hay página. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderSettings(container: AppContainer) {
    val context = LocalContext.current
    val s by container.settings.state.collectAsStateWithLifecycle()
    var pickingTime by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }

    fun change(transform: (AppSettings) -> AppSettings) {
        container.settings.update(transform)
        ReminderScheduler.apply(context, container.settings.state.value)
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        if (granted) change { it.copy(reminderEnabled = true) }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Recordatorio diario")
            Text("Solo avisa si ese día aún no escribiste. La mascota te dice cuánto llevas de racha.", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = s.reminderEnabled, onCheckedChange = { enable ->
            val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (enable && needsPermission) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else change { it.copy(reminderEnabled = enable) }
        })
    }
    if (denied) {
        Text(
            "Sin permiso para notificaciones no puedo avisarte. Puedes darlo en Ajustes del sistema › Aplicaciones › Korean Diary.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (s.reminderEnabled) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Hora")
            AppOutlinedButton(onClick = { pickingTime = true }) {
                Text("%02d:%02d".format(s.reminderHour, s.reminderMinute))
            }
        }
    }

    if (pickingTime) {
        val state = rememberTimePickerState(initialHour = s.reminderHour, initialMinute = s.reminderMinute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            title = { Text("Hora del recordatorio") },
            text = { TimePicker(state = state) },
            confirmButton = {
                AppButton(onClick = {
                    pickingTime = false
                    change { it.copy(reminderHour = state.hour, reminderMinute = state.minute) }
                }) { Text("Listo") }
            },
            dismissButton = { AppTextButton(onClick = { pickingTime = false }) { Text("Cancelar") } },
        )
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
