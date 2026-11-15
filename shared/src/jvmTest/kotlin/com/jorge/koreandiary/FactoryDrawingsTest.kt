package com.jorge.koreandiary

import com.jorge.koreandiary.ai.Prompts
import com.jorge.koreandiary.scene.SceneData
import com.jorge.koreandiary.scene.SceneDictionary
import com.jorge.koreandiary.scene.SceneZone
import com.jorge.koreandiary.scene.art.CustomDrawing
import com.jorge.koreandiary.scene.art.DrawingFormat
import com.jorge.koreandiary.scene.art.SceneArt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Los dibujos de fábrica (`app/src/main/assets/dibujos/`): que todos se lean tal como los lee la app,
 * que ninguna palabra choque y que cada una traiga su dibujo. Corre en cada compilación, así que un
 * JSON roto o una palabra repetida no llega al celular.
 */
class FactoryDrawingsTest {

    /** Gradle corre los tests desde la carpeta del módulo (`shared/`). */
    private val folder = listOf(File("../app/src/main/assets/dibujos"), File("app/src/main/assets/dibujos")).first { it.isDirectory }

    private val files = folder.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }

    private val factory = files.map { DrawingFormat.parseFactory(it.readText()) }

    @Test
    fun everyFactoryDrawingReadsWhole() {
        assertTrue("Faltan los dibujos de fábrica en ${folder.path}", files.size >= 100)
        files.zip(factory).forEach { (file, d) ->
            val raw = JSONObject(file.readText())
            val where = file.name
            assertEquals("$where: el archivo se llama como su id", raw.getString("id") + ".json", file.name)
            assertEquals(where, CustomDrawing.FACTORY_PREFIX + raw.getString("id"), d.id)
            assertTrue(where, d.isFactory && CustomDrawing.isCustomId(d.id))
            // La app no tira nada: si se cayera una forma, un color o una palabra, el JSON tiene un error.
            assertEquals("$where: formas", raw.getJSONArray("shapes").length(), d.shapes.size)
            assertEquals("$where: colores", raw.getJSONArray("colors").length(), d.colors.size)
            assertEquals("$where: palabras", raw.getJSONArray("keys").length(), d.keys.size)
            assertEquals(where, raw.getString("zone"), d.zone.name)
            assertTrue("$where: pocas formas", d.shapes.size >= 6)
            assertTrue("$where: sin nombre", d.name.isNotBlank())
        }
        assertEquals("ids repetidos", factory.size, factory.map { it.id }.distinct().size)
    }

    @Test
    fun everyWordBringsItsDrawing() {
        val lex = SceneDictionary.withCustom(factory)
        val builtIn = SceneDictionary.elements.flatMap { SceneDictionary.keysOf(it.id) }.toSet()
        val seen = HashMap<String, String>()
        for (d in factory) {
            for (key in d.keys) {
                val other = seen.put(key, d.id)
                assertNull("«$key» está en ${d.id} y en $other", other)
                assertFalse("«$key» (${d.id}) ya es de un dibujo de la app", key in builtIn)
                assertTrue("«$key» es de otro: ${SceneDictionary.keysOf(lex.owner(key)?.id ?: "")}", lex.owner(key)?.id == d.id)
                // Con partícula siempre lo trae (sola, las de una sílaba dudosas no cuentan).
                assertEquals("«${key}가»", d.id, lex.detect(key + "가").firstOrNull()?.id)
            }
        }
    }

    @Test
    fun ownDrawingsWinOverFactoryOnes() {
        val dog = factory.single { it.id == "f_dog" }
        val json = JSONObject(File(folder, "dog.json").readText()).put("id", "puppy").put("keys", JSONArray().put("강아지").put("댕댕이"))
        val puppy = DrawingFormat.parse(json)
        assertEquals("x_puppy", puppy.id)
        // Como en la app: primero los tuyos y al final los de fábrica.
        val lex = SceneDictionary.withCustom(listOf(puppy) + factory)
        assertEquals("x_puppy", lex.detect("강아지가 뛰어요").single().id)
        assertEquals(dog.id, lex.detect("개가 짖어요").single().id)
        assertEquals("x_puppy", lex.owner("댕댕이")?.id)
        // La app gana siempre: 바다 es el mar aunque un dibujo lo pida.
        assertEquals("sea", lex.detect("바다").single().id)
    }

    @Test
    fun syllablesThatAreUsuallySomethingElseNeedAParticle() {
        val lex = SceneDictionary.withCustom(factory)
        // 게 sola es 것이; 새 sola es «nuevo»; 절 sola es 저를.
        assertTrue(lex.detect("먹는 게 좋아요").none { it.id == "f_crab" })
        assertTrue(lex.detect("새 옷을 샀어요").none { it.id == "f_bird" })
        assertTrue(lex.detect("절 도와주세요").none { it.id == "f_temple" })
        assertEquals("f_crab", lex.detect("게가 걸어가요").single().id)
        assertEquals("f_bird", lex.detect("새들이 날아요").single().id)
        assertEquals("f_temple", lex.detect("절에 갔어요").single().id)
        // Pero se sabe que tienen dibujo: no se le pide a Claude otro cangrejo.
        assertTrue(lex.knows("게"))
        assertFalse(lex.knows("낙타"))
        // Las demás de una sílaba, como siempre: solas o con partícula.
        assertEquals("f_bear", lex.detect("곰 한 마리").single().id)
        // Una clave más larga gana: 단풍나무 es el arce, no las hojas; 소나무 no es una vaca.
        assertEquals(listOf("f_maple_tree"), lex.detect("단풍나무가").map { it.id })
        assertEquals(listOf("f_pine"), lex.detect("소나무").map { it.id })
    }

    @Test
    fun factoryDrawingsReachThePromptAndTheStrip() {
        val prompt = Prompts.correction("", "3A", 3, emptyList(), "고양이가 있어요.", hasImage = false, scene = true, customScene = factory)
        assertTrue(prompt.contains("f_cat (gato)"))
        assertTrue(prompt.contains("f_lighthouse (faro)"))
        // Lo que elige la IA de la lista se guarda con la escena (lo inventado, no).
        val saved = SceneData().withElements(listOf("sea", "f_cat", "x_puppy", "unicornio"))
        assertEquals(listOf("sea", "f_cat", "x_puppy"), saved.elements)
        // Uno por zona, pintados juntos en la franja.
        val picks = SceneZone.entries.map { z -> factory.first { it.zone == z } }
        val elements = picks.map { it.element }
        val marks = SceneArt.paint(elements, 1080f, 2200f, 5f, picks)
        assertTrue(marks.count { it.color.contains('|') } >= picks.sumOf { it.shapes.size })
    }
}
