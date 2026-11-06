package com.jorge.koreandiary

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jorge.koreandiary.settings.ThemeMode
import com.jorge.koreandiary.ui.HomeScreen
import com.jorge.koreandiary.ui.ImportScreen
import com.jorge.koreandiary.ui.KoreanDiaryTheme
import com.jorge.koreandiary.ui.ProgressScreen
import com.jorge.koreandiary.ui.SettingsScreen
import com.jorge.koreandiary.ui.StudyScreen
import com.jorge.koreandiary.ui.page.PageScreen
import com.jorge.koreandiary.ui.workbook.WorkbookListScreen
import com.jorge.koreandiary.ui.workbook.WorkbookSessionScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as KoreanDiaryApp).container
        setContent {
            val settings by container.settings.state.collectAsStateWithLifecycle()
            val dark = when (settings.themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                else -> isSystemInDarkTheme()
            }
            // Iconos de la barra de estado claros u oscuros según el tema elegido, no el del sistema.
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            KoreanDiaryTheme(dark = dark, darkPaper = settings.darkPaper) {
                // Surface (no Box) para que todo el texto herede el color del tema: sin esto,
                // Text usa negro por defecto y en modo oscuro queda casi invisible.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        AppNavigation(container)
                    }
                }
            }
        }
    }
}

/** Navegación mínima con una pila de rutas en texto ("home", "page:12", ...), sobrevive a rotaciones. */
@Composable
private fun AppNavigation(container: AppContainer) {
    val stack = rememberSaveable(
        saver = listSaver<SnapshotStateList<String>, String>(save = { it.toList() }, restore = { it.toMutableStateList() })
    ) { mutableStateListOf("home") }

    fun push(route: String) { stack.add(route) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    BackHandler(enabled = stack.size > 1) { pop() }

    // Busca actualizaciones una vez por apertura, en silencio (sin errores si no hay red).
    LaunchedEffect(Unit) { container.updater.check(silent = true) }
    // Precarga el modelo de escritura coreana por Wi-Fi: así la primera "Revisar" no espera la descarga.
    LaunchedEffect(Unit) {
        runCatching {
            if (!container.recognizer.isModelDownloaded()) container.recognizer.downloadModel(wifiOnly = true)
        }
    }

    val route = stack.last()
    when {
        route.startsWith("page:") -> PageScreen(container, route.removePrefix("page:").toLong(), onBack = ::pop)
        route == "study" -> StudyScreen(container, onBack = ::pop, onImport = { push("import") })
        route == "import" -> ImportScreen(container, onBack = ::pop)
        route == "settings" -> SettingsScreen(container, onBack = ::pop)
        route == "progress" -> ProgressScreen(container, onBack = ::pop)
        route == "workbook" -> WorkbookListScreen(container, onBack = ::pop, onOpen = { push("workbook:$it") })
        route.startsWith("workbook:") -> WorkbookSessionScreen(container, route.removePrefix("workbook:").toLong(), onBack = ::pop)
        else -> HomeScreen(
            container,
            onOpenPage = { push("page:$it") },
            onStudy = { push("study") },
            onSettings = { push("settings") },
            onWorkbook = { push("workbook") },
            onProgress = { push("progress") },
        )
    }
}
