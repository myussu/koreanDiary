package com.jorge.koreandiary

import com.jorge.koreandiary.scene.SceneDictionary
import com.jorge.koreandiary.scene.SceneLayout
import com.jorge.koreandiary.scene.SceneMaterial
import com.jorge.koreandiary.scene.SceneShaders
import com.jorge.koreandiary.scene.SceneSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dónde va cada elemento de la escena viva y que sus sombreadores estén completos. */
class SceneLayoutTest {

    private val w = 1080f
    private val h = 1500f
    private val edge = SceneLayout.edge(w, h)

    @Test
    fun everyElementHasALook() {
        SceneDictionary.elements.forEach { assertNotNull("«${it.id}» no tiene cómo dibujarse", SceneLayout.look(it.id)) }
    }

    @Test
    fun everythingStaysOnItsEdge() {
        val slots = SceneLayout.slots(SceneDictionary.elements, w, h)
        assertEquals(SceneDictionary.elements.size, slots.size)
        val maxDepth = edge * 2f + 0.01f
        for (s in slots) {
            val id = s.element.id
            when (s.side) {
                SceneSide.BOTTOM -> assertTrue(id, s.bottom == h && h - s.top <= maxDepth)
                SceneSide.TOP -> assertTrue(id, s.top == 0f && s.bottom <= maxDepth)
                SceneSide.LEFT -> assertTrue(id, s.left == 0f && s.right <= maxDepth && s.top >= h * 0.25f)
                SceneSide.RIGHT -> assertTrue(id, s.right == w && w - s.left <= maxDepth && s.top >= h * 0.25f)
                SceneSide.FRAME -> assertTrue(id, s.left == 0f && s.top == 0f && s.right == w && s.bottom == h)
            }
        }
    }

    @Test
    fun skyFillsTheTopAndPointsShareIt() {
        val ids = listOf("sky", "sun", "moon", "clouds").map { SceneDictionary.element(it)!! }
        val slots = SceneLayout.slots(ids, w, h).associateBy { it.element.id }
        // Lo ancho se sale de la hoja por los dos lados (se desvanece afuera).
        assertTrue(slots.getValue("sky").left < 0f && slots.getValue("sky").right > w)
        assertTrue(slots.getValue("clouds").left < 0f && slots.getValue("clouds").right > w)
        // El sol y la luna se reparten el ancho, montados un poco en el medio.
        val sun = slots.getValue("sun")
        val moon = slots.getValue("moon")
        assertTrue(sun.left < 0f && sun.right > w / 2 && sun.right < w)
        assertTrue(moon.left < w / 2 && moon.right > w)
    }

    @Test
    fun horizonAlternatesSides() {
        val ids = listOf("sea", "forest", "mountain").map { SceneDictionary.element(it)!! }
        val sides = SceneLayout.slots(ids, w, h).map { it.element.id to it.side }
        assertEquals(listOf("sea" to SceneSide.LEFT, "mountain" to SceneSide.LEFT, "forest" to SceneSide.RIGHT), sides)
    }

    @Test
    fun seedsDoNotDependOnOrder() {
        val a = SceneLayout.slots(listOf("sea", "grass").map { SceneDictionary.element(it)!! }, w, h).associate { it.element.id to it.seed }
        val b = SceneLayout.slots(listOf("grass", "rain", "sea").map { SceneDictionary.element(it)!! }, w, h).associate { it.element.id to it.seed }
        assertEquals(a.getValue("sea"), b.getValue("sea"))
        assertTrue(a.values.all { it in 1f..97f })
        assertTrue(SceneLayout.slots(emptyList(), w, h).isEmpty())
        assertTrue(SceneLayout.slots(SceneDictionary.elements, 0f, 0f).isEmpty())
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
    }
}
