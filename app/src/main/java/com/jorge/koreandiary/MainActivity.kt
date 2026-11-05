package com.jorge.koreandiary

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import com.jorge.koreandiary.ui.HomeScreen
import com.jorge.koreandiary.ui.ImportScreen
import com.jorge.koreandiary.ui.KoreanDiaryTheme
import com.jorge.koreandiary.ui.SettingsScreen
import com.jorge.koreandiary.ui.StudyScreen
import com.jorge.koreandiary.ui.page.PageScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as KoreanDiaryApp).container
        setContent {
            KoreanDiaryTheme {
                Box(
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .safeDrawingPadding()
                ) {
                    AppNavigation(container)
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

    val route = stack.last()
    when {
        route.startsWith("page:") -> PageScreen(container, route.removePrefix("page:").toLong(), onBack = ::pop)
        route == "study" -> StudyScreen(container, onBack = ::pop, onImport = { push("import") })
        route == "import" -> ImportScreen(container, onBack = ::pop)
        route == "settings" -> SettingsScreen(container, onBack = ::pop)
        else -> HomeScreen(
            container,
            onOpenPage = { push("page:$it") },
            onStudy = { push("study") },
            onSettings = { push("settings") },
        )
    }
}
