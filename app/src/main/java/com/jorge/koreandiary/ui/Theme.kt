package com.jorge.koreandiary.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// El lienzo es papel claro, así que la app usa un solo tema claro para que todo combine.
private val Paper = lightColorScheme(
    primary = Color(0xFF3B5BA9),
    onPrimary = Color.White,
    secondary = Color(0xFFB5523B),
    onSecondary = Color.White,
    background = Color(0xFFFAF8F3),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEFEBE3),
)

@Composable
fun KoreanDiaryTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Paper, content = content)
}
