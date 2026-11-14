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

    /** El segundo color de cada clave: luces, puntas, espuma (en las flores, el verde del tallo). */
    private val secondary: Map<String, Pair<Color, Color>> = mapOf(
        "grass" to (Color(0xFFA8D86A) to Color(0xFF8CC25A)),
        "wheat" to (Color(0xFFF6D98A) to Color(0xFFE8C46A)),
        "sand" to (Color(0xFFF4E2B8) to Color(0xFFD8BE8A)),
        "flowers" to (Color(0xFF5FAF4E) to Color(0xFF4E9A45)),
        "autumn" to (Color(0xFFD94A3A) to Color(0xFFB83E30)),
        "stone" to (Color(0xFFC7CDD3) to Color(0xFF8E979F)),
        "sea" to (Color(0xFF7FD3F0) to Color(0xFF6CB8D8)),
        "water" to (Color(0xFF9FE0E8) to Color(0xFF7CC4CE)),
        "mountain" to (Color(0xFFB9CBC0) to Color(0xFF8FA597)),
        "forest" to (Color(0xFF6FBF6A) to Color(0xFF5EA85A)),
        "sky" to (Color(0xFFCFEAFB) to Color(0xFF7FB2DA)),
        "sun" to (Color(0xFFFFF1B8) to Color(0xFFFFE08A)),
        "sunset" to (Color(0xFFB06FB8) to Color(0xFF8E5A9C)),
        "clouds" to (Color(0xFFFFFFFF) to Color(0xFFC4CCD6)),
        "night" to (Color(0xFF6A5ACD) to Color(0xFF8070D8)),
        "moon" to (Color(0xFFFFFBEA) to Color(0xFFFFF6D6)),
        "stars" to (Color(0xFFFFFFFF) to Color(0xFFFFFFFF)),
        "rainbow" to (Color(0xFFFFFFFF) to Color(0xFFFFFFFF)),
        "wind" to (Color(0xFFE6F4F8) to Color(0xFFC8DEE6)),
        "rain" to (Color(0xFFB8D4F0) to Color(0xFF9CB8D8)),
        "snow" to (Color(0xFFFFFFFF) to Color(0xFFFFFFFF)),
        "fog" to (Color(0xFFE4E8EC) to Color(0xFFA8B0B8)),
        "sparkle" to (Color(0xFFFFFFFF) to Color(0xFFFFFFFF)),
        "warm" to (Color(0xFFFFE0A8) to Color(0xFFF2C07E)),
        "cool" to (Color(0xFFE0F6FA) to Color(0xFFB4DCE4)),
    )

    /** Los dos colores de cada tono (`SceneMoods`): la luz y el color con que se mezcla. */
    private val moods: Map<String, Pair<Color, Color>> = mapOf(
        "love" to (Color(0xFFF08CA8) to Color(0xFFFFC27A)),
        "calm" to (Color(0xFF7FC8C8) to Color(0xFFA9B8E8)),
        "joy" to (Color(0xFFFFD25A) to Color(0xFFFF9F5A)),
        "nostalgia" to (Color(0xFFE0A86A) to Color(0xFFB98A6A)),
        "sad" to (Color(0xFF7E93B8) to Color(0xFF9AA4B2)),
        "awe" to (Color(0xFFA98AE8) to Color(0xFF7FD8F0)),
        "excited" to (Color(0xFFFF7A4A) to Color(0xFFFFD25A)),
        "tense" to (Color(0xFFC04A5A) to Color(0xFF7A4AA8)),
    )

    fun color(key: String, dark: Boolean): Color =
        colors[key]?.let { if (dark) it.second else it.first } ?: if (dark) Color(0xFF6E7781) else Color(0xFF9AA3AD)

    fun secondary(key: String, dark: Boolean): Color =
        secondary[key]?.let { if (dark) it.second else it.first } ?: color(key, dark)

    /** Los mismos colores sobre papel claro y oscuro: el sombreador baja la luz en papel claro. */
    fun mood(id: String): Pair<Color, Color> = moods[id] ?: (Color(0xFFB0B8C0) to Color(0xFFD0D6DC))
}
