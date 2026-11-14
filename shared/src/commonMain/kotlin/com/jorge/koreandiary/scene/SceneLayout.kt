package com.jorge.koreandiary.scene

import kotlin.math.min

/** Un sombreador de la escena viva (ver [SceneShaders]); varios elementos comparten uno con otra variante. */
enum class SceneMaterial { WATER, FOLIAGE, GROUND, SKYLINE, SKY, LIGHT, CLOUDS, STARS, RAINBOW, WIND, FALL, MIST, MOOD }

/** Desde qué borde nace. El orden es el número que recibe el sombreador (`uSide`): no reordenar. */
enum class SceneSide { BOTTOM, TOP, LEFT, RIGHT, FRAME }

/**
 * Cómo se dibuja un elemento: su sombreador, la variante dentro de él ([kind], `uKind`) y si ocupa
 * todo su borde ([wide]: el cielo, las nubes) o comparte el borde con los demás de su zona.
 */
data class SceneLook(val material: SceneMaterial, val kind: Int, val wide: Boolean = false)

/**
 * Dónde se dibuja un elemento: su caja en píxeles (puede salirse de la hoja para que el borde se
 * desvanezca afuera) y el borde desde el que entra. [seed] es fijo por elemento, para que su dibujo
 * no salte cuando aparece otro.
 */
data class SceneSlot(
    val element: SceneElement,
    val look: SceneLook,
    val side: SceneSide,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val seed: Float,
)

/**
 * Reparto de la escena viva en los bordes, igual en el celular y en la PC: el cielo arriba, el suelo
 * abajo, el horizonte a los costados (alternando izquierda y derecha) y el aire por todo el marco.
 * Nada se dibuja en el centro de la hoja salvo lo que se desvanece desde el borde.
 */
object SceneLayout {

    private val looks: Map<String, SceneLook> = mapOf(
        "grass" to SceneLook(SceneMaterial.FOLIAGE, 0),
        "wheat" to SceneLook(SceneMaterial.FOLIAGE, 1),
        "flowers" to SceneLook(SceneMaterial.FOLIAGE, 2),
        "forest" to SceneLook(SceneMaterial.FOLIAGE, 3),
        "sand" to SceneLook(SceneMaterial.GROUND, 0),
        "leaves" to SceneLook(SceneMaterial.GROUND, 1),
        "street" to SceneLook(SceneMaterial.GROUND, 2),
        "sea" to SceneLook(SceneMaterial.WATER, 0),
        "lake" to SceneLook(SceneMaterial.WATER, 1),
        "mountain" to SceneLook(SceneMaterial.SKYLINE, 0),
        "city" to SceneLook(SceneMaterial.SKYLINE, 1),
        "sky" to SceneLook(SceneMaterial.SKY, 0, wide = true),
        "sunset" to SceneLook(SceneMaterial.SKY, 1, wide = true),
        "night" to SceneLook(SceneMaterial.SKY, 2, wide = true),
        "sun" to SceneLook(SceneMaterial.LIGHT, 0),
        "moon" to SceneLook(SceneMaterial.LIGHT, 1),
        "clouds" to SceneLook(SceneMaterial.CLOUDS, 0, wide = true),
        "stars" to SceneLook(SceneMaterial.STARS, 0, wide = true),
        "rainbow" to SceneLook(SceneMaterial.RAINBOW, 0, wide = true),
        "wind" to SceneLook(SceneMaterial.WIND, 0),
        "rain" to SceneLook(SceneMaterial.FALL, 0),
        "snow" to SceneLook(SceneMaterial.FALL, 1),
        "petals" to SceneLook(SceneMaterial.FALL, 2),
        "fog" to SceneLook(SceneMaterial.MIST, 0),
        "warm" to SceneLook(SceneMaterial.MIST, 1),
        "cool" to SceneLook(SceneMaterial.MIST, 2),
        "sparkle" to SceneLook(SceneMaterial.STARS, 1),
    )

    fun look(id: String): SceneLook? = looks[id]

    /** La unidad de medida de la escena: cuánto entra desde el borde, según el lado corto de la hoja. */
    fun edge(width: Float, height: Float): Float = min(width, height) * 0.12f

    /** Cuánto entra cada zona, en unidades de [edge]. */
    const val SKY_DEPTH = 2.0f
    const val GROUND_DEPTH = 1.7f
    const val SIDE_DEPTH = 1.5f
    const val SKYLINE_DEPTH = 1.9f

    /** El horizonte va de aquí (fracción del alto) hasta casi abajo. */
    private const val HORIZON_TOP = 0.30f

    /** En el orden en que se dibujan: cielo, horizonte, suelo y aire encima. */
    fun slots(elements: List<SceneElement>, width: Float, height: Float): List<SceneSlot> {
        if (width <= 0f || height <= 0f) return emptyList()
        val edge = edge(width, height)
        val withLook = elements.mapNotNull { e -> look(e.id)?.let { e to it } }
        val out = ArrayList<SceneSlot>()
        fun add(e: SceneElement, look: SceneLook, side: SceneSide, l: Float, t: Float, r: Float, b: Float) {
            out += SceneSlot(e, look, side, l, t, r, b, seedOf(e.id))
        }

        // Cielo: lo ancho ocupa todo el borde; el sol y la luna se reparten el ancho.
        val sky = withLook.filter { it.first.zone == SceneZone.SKY }
        sky.filter { it.second.wide }.forEach { (e, look) ->
            add(e, look, SceneSide.TOP, -width * 0.3f, 0f, width * 1.3f, edge * SKY_DEPTH)
        }
        spread(sky.filterNot { it.second.wide }, width) { (e, look), l, r -> add(e, look, SceneSide.TOP, l, 0f, r, edge * SKY_DEPTH) }

        // Horizonte: alterna izquierda y derecha; en cada lado se reparten el alto.
        val horizon = withLook.filter { it.first.zone == SceneZone.HORIZON }
        val top = height * HORIZON_TOP
        val bottom = height - edge * 0.6f
        for (sideIndex in 0..1) {
            val onSide = horizon.filterIndexed { i, _ -> i % 2 == sideIndex }
            val n = onSide.size
            onSide.forEachIndexed { k, (e, look) ->
                val span = (bottom - top) / n
                val overlap = span * 0.3f
                val t = top + span * k - if (k > 0) overlap else 0f
                val b = top + span * (k + 1) + if (k < n - 1) overlap else 0f
                val depth = edge * if (look.material == SceneMaterial.SKYLINE) SKYLINE_DEPTH else SIDE_DEPTH
                if (sideIndex == 0) add(e, look, SceneSide.LEFT, 0f, t, depth, b)
                else add(e, look, SceneSide.RIGHT, width - depth, t, width, b)
            }
        }

        // Suelo: se reparten el ancho.
        spread(withLook.filter { it.first.zone == SceneZone.GROUND }, width) { (e, look), l, r ->
            add(e, look, SceneSide.BOTTOM, l, height - edge * GROUND_DEPTH, r, height)
        }

        // Aire: todo el marco.
        withLook.filter { it.first.zone == SceneZone.AIR }.forEach { (e, look) ->
            add(e, look, SceneSide.FRAME, 0f, 0f, width, height)
        }
        return out
    }

    /**
     * Reparte el ancho entre [items]: cada uno su tramo, montado un poco sobre los vecinos (ahí se
     * funden), y los de las puntas se salen de la hoja para no desvanecerse adentro.
     */
    private fun <T> spread(items: List<T>, width: Float, place: (T, Float, Float) -> Unit) {
        val n = items.size
        items.forEachIndexed { k, item ->
            val span = width / n
            val overlap = span * 0.35f
            val l = if (k > 0) span * k - overlap else -overlap
            val r = if (k < n - 1) span * (k + 1) + overlap else width + overlap
            place(item, l, r)
        }
    }

    /** Un número fijo por elemento (1..97): su dibujo no cambia aunque cambie el orden. */
    fun seedOf(id: String): Float = (id.fold(7) { acc, ch -> (acc * 31 + ch.code) and 0x7FFFFFFF } % 97 + 1).toFloat()
}
