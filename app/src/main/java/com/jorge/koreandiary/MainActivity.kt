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
import androidx.compose.ui.Alignment
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jorge.koreandiary.settings.ThemeMode
import com.jorge.koreandiary.ui.FeaturesScreen
import com.jorge.koreandiary.ui.HomeScreen
import com.jorge.koreandiary.ui.ImportScreen
import com.jorge.koreandiary.ui.KoreanDiaryTheme
import com.jorge.koreandiary.ui.ProgressScreen
import com.jorge.koreandiary.ui.ScenesScreen
import com.jorge.koreandiary.ui.SettingsScreen
import com.jorge.koreandiary.ui.StudyScreen
import com.jorge.koreandiary.ui.conversation.ConversationScreen
import com.jorge.koreandiary.ui.conversation.ListenMiniBar
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

/**
 * Un ViewModelStore por página abierta, guardado en un ViewModel de la Activity para sobrevivir a
 * girar el celular. Al volver atrás se limpia el de la página que se fue: así corre su `onCleared`
 * (guardado final y borrar la página si quedó vacía) y su tinta y su historial de deshacer salen de
 * la memoria. Sin esto, todos los ViewModels vivían en la Activity hasta cerrar la app.
 * El workbook y la conversación siguen en la Activity: sus pedidos a la IA no deben cortarse al salir.
 */
class RouteViewModelStores : ViewModel() {
    private val stores = HashMap<String, ViewModelStore>()

    fun storeFor(key: String): ViewModelStore = stores.getOrPut(key) { ViewModelStore() }

    /** Limpia los de las pantallas que ya no están en la pila. */
    fun keepOnly(keys: Set<String>) {
        (stores.keys - keys).forEach { stores.remove(it)?.clear() }
    }

    override fun onCleared() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }
}

/** Navegación mínima con una pila de rutas en texto ("home", "page:12", ...), sobrevive a rotaciones. */
@Composable
private fun AppNavigation(container: AppContainer) {
    val stack = rememberSaveable(
        saver = listSaver<SnapshotStateList<String>, String>(save = { it.toList() }, restore = { it.toMutableStateList() })
    ) { mutableStateListOf("home") }
    val routeStores: RouteViewModelStores = viewModel()
    // Clave de cada pantalla: posición + ruta, para que reabrir la misma página sea un ViewModel nuevo.
    val keys = stack.mapIndexed { i, r -> "$i:$r" }
    // Después de componer: la pantalla que se fue ya salió de la composición cuando se limpia su store.
    LaunchedEffect(keys) { routeStores.keepOnly(keys.toSet()) }

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
    val listen by container.listen.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        when {
            route.startsWith("page:") -> {
                val store = routeStores.storeFor(keys.last())
                val owner = remember(store) { object : ViewModelStoreOwner { override val viewModelStore = store } }
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    PageScreen(container, route.removePrefix("page:").toLong(), onBack = ::pop)
                }
            }
            route == "study" -> StudyScreen(container, onBack = ::pop, onImport = { push("import") })
            route == "import" -> ImportScreen(container, onBack = ::pop)
            route == "settings" -> SettingsScreen(container, onBack = ::pop)
            route == "progress" -> ProgressScreen(container, onBack = ::pop)
            route == "features" -> FeaturesScreen(onBack = ::pop)
            route == "conversation" -> ConversationScreen(container, onBack = ::pop)
            route == "scenes" -> ScenesScreen(container, onBack = ::pop, onOpenPage = { push("page:$it") })
            route == "workbook" -> WorkbookListScreen(container, onBack = ::pop, onOpen = { push("workbook:$it") })
            route.startsWith("workbook:") -> WorkbookSessionScreen(container, route.removePrefix("workbook:").toLong(), onBack = ::pop)
            else -> HomeScreen(
                container,
                onOpenPage = { push("page:$it") },
                onStudy = { push("study") },
                onSettings = { push("settings") },
                onWorkbook = { push("workbook") },
                onProgress = { push("progress") },
                onConversation = { push("conversation") },
                onScenes = { push("scenes") },
                onFeatures = { push("features") },
            )
        }
        // Escucha en curso (sigue con la pantalla apagada): una barra para volver a ella desde cualquier pantalla.
        if (listen.phase.active && route != "conversation") {
            ListenMiniBar(listen, container.listen, onOpen = { push("conversation") }, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}
