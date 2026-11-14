package com.jorge.koreandiary

import com.jorge.koreandiary.scene.SceneDictionary
import com.jorge.koreandiary.scene.SceneMaterial
import com.jorge.koreandiary.scene.SceneShaders
import com.jorge.koreandiary.scene.art.Brush
import com.jorge.koreandiary.scene.art.Pt
import com.jorge.koreandiary.scene.art.SceneArt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** La escena a pincel: todo en la franja de abajo, cada elemento dibujado y el mismo instante igual. */
class SceneArtTest {

    private val w = 1080f
    private val h = 1900f
    private val stage = SceneArt.stageHeight(w, h)

    @Test
    fun theStageLeavesTheWritingArea() {
        assertTrue("la franja ocupa ${stage / h} de la hoja", stage < h * 0.45f)
        // Tableta acostada: el alto manda.
        assertTrue(SceneArt.stageHeight(2400f, 1500f) <= 1500f * 0.45f)
    }

    @Test
    fun everyElementIsPaintedInsideTheStage() {
        for (e in SceneDictionary.elements) {
            for (t in listOf(0f, 3.7f, 61.2f)) {
                val marks = SceneArt.paint(listOf(e), w, h, t)
                assertTrue("«${e.id}» no dibujó nada en t=$t", marks.isNotEmpty() || e.id in setOf("rain"))
                for (m in marks) {
                    for (i in 1 until m.outline.size step 2) {
                        // Nada sube por encima de la franja (un poco de margen por el borde irregular).
                        assertTrue("«${e.id}» sube a y=${m.outline[i]}", m.outline[i] >= -stage * 0.02f)
                    }
                    assertTrue(m.alpha in 0f..1f)
                }
            }
        }
    }

    @Test
    fun sameInstantSameDrawing() {
        val ids = listOf("sea", "forest", "wind", "sun").map { SceneDictionary.element(it)!! }
        val a = SceneArt.paint(ids, w, h, 12.5f)
        val b = SceneArt.paint(ids, w, h, 12.5f)
        assertEquals(a.size, b.size)
        a.indices.forEach { i -> assertTrue(a[i].outline.contentEquals(b[i].outline)) }
        // Y se mueve con el tiempo.
        val c = SceneArt.paint(ids, w, h, 13.5f)
        assertTrue(a.indices.any { i -> i < c.size && !a[i].outline.contentEquals(c[i].outline) })
    }

    @Test
    fun layersGoFromBackgroundToAir() {
        val ids = listOf("rain", "grass", "sea", "sun", "sky").map { SceneDictionary.element(it)!! }
        val colors = SceneArt.paint(ids, w, h, 2f).map { it.color }
        assertTrue(colors.indexOf("sky") < colors.indexOf("sun"))
        assertTrue(colors.indexOf("sun") < colors.indexOf("sea_deep"))
        assertTrue(colors.indexOf("sea_deep") < colors.indexOf("grass_dark").takeIf { it >= 0 }.let { it ?: colors.indexOf("grass") })
        assertEquals(4, SceneArt.layer("rain"))
        assertEquals(0, SceneArt.layer("sky"))
    }

    @Test
    fun aStrokeIsThinAtTheEnds() {
        val line = listOf(Pt(0f, 0f), Pt(50f, 0f), Pt(100f, 0f))
        val o = Brush.strokeOutline(line, 20f, 0.15f, 0.35f, 0f, 1)
        val n = o.size / 4
        fun width(i: Int) = hypot(o[i * 2] - o[(2 * n - 1 - i) * 2], o[i * 2 + 1] - o[(2 * n - 1 - i) * 2 + 1])
        assertTrue(width(0) < 3f)
        assertTrue(abs(width(n / 2) - 20f) < 1f)
        assertTrue(width(n - 1) < 3f)
        // Contorno de un toque: cerrado alrededor del centro.
        val blob = Brush.blobOutline(10f, 10f, 5f, 0.2f, 3, 1f, 0f)
        for (i in blob.indices step 2) assertTrue(hypot(blob[i] - 10f, blob[i + 1] - 10f) in 3f..7f)
    }

    @Test
    fun shadersDeclareEveryUniformAndStayAscii() {
        for (m in SceneMaterial.entries) {
            val src = SceneShaders.source(m)
            assertTrue("$m sin main", src.contains("half4 main(float2 p)"))
            SceneShaders.UNIFORMS.forEach { assertTrue("$m sin $it", Regex("uniform\\s+\\w+\\s+$it;").containsMatchIn(src)) }
            assertTrue("$m tiene caracteres fuera de ASCII", src.all { it.code < 128 })
            assertTrue("$m tiene comentarios", !src.contains("//"))
        }
        assertTrue(SceneShaders.source(SceneMaterial.WATERCOLOR).contains("uniform shader content;"))
    }
}
