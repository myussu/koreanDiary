package com.jorge.koreandiary.ui

import androidx.compose.ui.graphics.Color

/**
 * Paleta de la escena viva, como `PaperStyle` para el papel: los colores de acuarela de los dibujos a
 * pincel (`scene.art.SceneArt` pide cada mancha por su clave) sobre papel claro y sobre papel oscuro,
 * y los dos colores de cada tono. Las pantallas no fijan colores: los piden aquí.
 * En papel oscuro los tonos son más claros: la acuarela tiñe el papel claro, pero sobre el oscuro
 * tiene que iluminar.
 */
object ScenePalette {
    private val watercolor: Map<String, Pair<Long, Long>> = mapOf(
        "bark" to (0xFF7A5A3C to 0xFFB08A66), "bark_dark" to (0xFF56402C to 0xFF8C6C50),
        "leaf_dark" to (0xFF3E7A4A to 0xFF5E9C66), "leaf" to (0xFF6FA65A to 0xFF8CC07A), "leaf_light" to (0xFFB5D27A to 0xFFC8E0A0),
        "sea_deep" to (0xFF2F6FA8 to 0xFF4A86C0), "sea" to (0xFF5FA8D8 to 0xFF78B8E0), "foam" to (0xFFD8EEF8 to 0xFFE4F2FA),
        "lake" to (0xFF3E8C9C to 0xFF5AA6B4), "lake_light" to (0xFF8CC8D0 to 0xFFA6D8DE),
        "grass_dark" to (0xFF4F8A3A to 0xFF6CA858), "grass" to (0xFF8DBF5A to 0xFFA6D27A), "grass_light" to (0xFFC6DE84 to 0xFFD6E8A2),
        "wheat_stem" to (0xFFC9A24A to 0xFFD8B464), "wheat" to (0xFFE2BC5C to 0xFFEACB78), "wheat_ear" to (0xFFD9A93A to 0xFFE8BE5E),
        "flower_pink" to (0xFFEF8FB1 to 0xFFF2A8C2), "flower_yellow" to (0xFFF4C95A to 0xFFF6D67C), "flower_white" to (0xFFE8E4F0 to 0xFFF2F0F6),
        "sun" to (0xFFF6C453 to 0xFFFBE3A0), "sun_core" to (0xFFFBE3A0 to 0xFFFFF4D6), "sun_ray" to (0xFFF2A93B to 0xFFF8D48A),
        "moon" to (0xFFE9DFAE to 0xFFEFE8C8), "moon_dark" to (0xFFB8AE84 to 0xFFC4BC9C),
        "sky" to (0xFF7CC4F2 to 0xFF6AA6D6), "sky_light" to (0xFFBFE2F8 to 0xFF94C2E6),
        "sunset_a" to (0xFFF5A05C to 0xFFF2AE72), "sunset_b" to (0xFFEE8AA0 to 0xFFE89AAE), "sunset_c" to (0xFFA88AD0 to 0xFFA894D8),
        "night" to (0xFF2E3A73 to 0xFF4A5AA8), "night_light" to (0xFF5A6AB0 to 0xFF6E7EC4),
        "warm" to (0xFFFFB067 to 0xFFF2B07A), "cool" to (0xFF8FD3E0 to 0xFF84C4D2),
        "cloud" to (0xFFD8E0EA to 0xFFD0D8E2), "cloud_shade" to (0xFFA9B6C6 to 0xFF98A6B8),
        "star" to (0xFFE9C94A to 0xFFFFF1A6), "sparkle" to (0xFFF2C94C to 0xFFFFE893),
        "rb_r" to (0xFFE87A7A to 0xFFF09090), "rb_o" to (0xFFF2A65E to 0xFFF4B474), "rb_y" to (0xFFF2D65E to 0xFFF4DE7E),
        "rb_g" to (0xFF8CC47A to 0xFF9ED08E), "rb_b" to (0xFF7AAEE0 to 0xFF8EBCE8), "rb_v" to (0xFFA88AD8 to 0xFFB49CE0),
        "mountain_far" to (0xFF9AB0C0 to 0xFF8AA0B4), "mountain" to (0xFF6F8F7A to 0xFF7E9E8A), "snow" to (0xFFF2F4F8 to 0xFFF6F8FA),
        "city" to (0xFF8A96A6 to 0xFF8C98AA), "city_dark" to (0xFF5E6A7C to 0xFF6C788A), "window" to (0xFFF6D27A to 0xFFFFE09A),
        "sand" to (0xFFE3C68E to 0xFFC8AE7E), "sand_light" to (0xFFF4E2B8 to 0xFFE0CCA0),
        "leaf_orange" to (0xFFE07B39 to 0xFFE8925A), "leaf_red" to (0xFFC9503E to 0xFFD86A58), "leaf_yellow" to (0xFFE8B83C to 0xFFEEC660),
        "stone" to (0xFF9AA3AD to 0xFF8E979F), "stone_dark" to (0xFF6E7781 to 0xFF737C86),
        "wind" to (0xFF9CC8D8 to 0xFFB4D6E2), "rain" to (0xFF6C97C9 to 0xFF8AAED6), "snowflake" to (0xFFF4F8FC to 0xFFFFFFFF),
        "petal" to (0xFFF2A8C2 to 0xFFF6BCD0), "fog" to (0xFFC4CCD4 to 0xFFB0B8C0),
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

    private val cache = HashMap<String, Color>()

    /** El color de una mancha de acuarela por su clave; gris si la clave no existe. */
    fun watercolor(key: String, dark: Boolean): Color {
        val k = if (dark) "o:$key" else "c:$key"
        return cache.getOrPut(k) {
            val pair = watercolor[key] ?: (0xFF9AA3AD to 0xFF8E979F)
            Color(if (dark) pair.second else pair.first)
        }
    }

    /** Los mismos colores sobre papel claro y oscuro: el sombreador baja la luz en papel claro. */
    fun mood(id: String): Pair<Color, Color> = moods[id] ?: (Color(0xFFB0B8C0) to Color(0xFFD0D6DC))
}
