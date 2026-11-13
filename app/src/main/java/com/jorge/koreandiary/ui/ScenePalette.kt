package com.jorge.koreandiary.ui

import androidx.compose.ui.graphics.Color

/**
 * Paleta de la escena viva, como `PaperStyle` para el papel: cada clave de `SceneElement.palette`
 * con su color sobre papel claro y sobre papel oscuro. Las pantallas no fijan colores: los piden aquí.
 */
object ScenePalette {
    private val colors: Map<String, Pair<Color, Color>> = mapOf(
        "grass" to (Color(0xFF5FAF4E) to Color(0xFF4E9A45)),
        "wheat" to (Color(0xFFE2B33C) to Color(0xFFC99A2E)),
        "sand" to (Color(0xFFE3C68E) to Color(0xFFB89A62)),
        "flowers" to (Color(0xFFEF8FB1) to Color(0xFFC86A8D)),
        "autumn" to (Color(0xFFE07B39) to Color(0xFFB8602A)),
        "stone" to (Color(0xFF9AA3AD) to Color(0xFF6E7781)),
        "sea" to (Color(0xFF2F8FD8) to Color(0xFF3A7FC0)),
        "water" to (Color(0xFF4FB3C9) to Color(0xFF3E93A6)),
        "mountain" to (Color(0xFF6F8F7A) to Color(0xFF55705E)),
        "forest" to (Color(0xFF2E7D4F) to Color(0xFF2F6E49)),
        "sky" to (Color(0xFF7CC4F2) to Color(0xFF4A86B8)),
        "sun" to (Color(0xFFFFC94A) to Color(0xFFE0A93A)),
        "sunset" to (Color(0xFFF58A5C) to Color(0xFFD06A4A)),
        "clouds" to (Color(0xFFC9D4E0) to Color(0xFF8C97A3)),
        "night" to (Color(0xFF2E3A73) to Color(0xFF4A5AA8)),
        "moon" to (Color(0xFFE9DFAE) to Color(0xFFD9D2B0)),
        "stars" to (Color(0xFFE9C94A) to Color(0xFFFFF1A6)),
        "rainbow" to (Color(0xFFB57EDC) to Color(0xFF9A6CC0)),
        "wind" to (Color(0xFF8BBFD2) to Color(0xFF7FA9B8)),
        "rain" to (Color(0xFF6C97C9) to Color(0xFF5C82AE)),
        "snow" to (Color(0xFFB8CBE0) to Color(0xFFDDE8F3)),
        "fog" to (Color(0xFFB5BDC6) to Color(0xFF7D868F)),
        "sparkle" to (Color(0xFFF2C94C) to Color(0xFFFFE893)),
        "warm" to (Color(0xFFFFB067) to Color(0xFFD98C4E)),
        "cool" to (Color(0xFF8FD3E0) to Color(0xFF6FB2BF)),
    )

    fun color(key: String, dark: Boolean): Color =
        colors[key]?.let { if (dark) it.second else it.first } ?: if (dark) Color(0xFF6E7781) else Color(0xFF9AA3AD)
}
