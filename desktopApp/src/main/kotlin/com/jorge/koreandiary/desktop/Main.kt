package com.jorge.koreandiary.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.jorge.koreandiary.desktop.ui.DiaryApp
import com.jorge.koreandiary.desktop.ui.KoreanDiaryTheme
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

fun main() = application {
    val state = rememberWindowState(width = 1200.dp, height = 820.dp)
    Window(onCloseRequest = ::exitApplication, title = "Korean Diary", state = state) {
        KoreanDiaryTheme(dark = isSystemInDarkTheme()) {
            DiaryApp(chooseZip = { chooseZip(window) })
        }
    }
}

/** Diálogo nativo para elegir la copia .zip exportada desde el celular. */
private fun chooseZip(parent: Frame): File? {
    val dialog = FileDialog(parent, "Importar copia del celular", FileDialog.LOAD).apply {
        // Windows filtra por este patrón; macOS y Linux por el FilenameFilter.
        file = "*.zip"
        setFilenameFilter { _, name -> name.endsWith(".zip", ignoreCase = true) }
        isVisible = true
    }
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}
