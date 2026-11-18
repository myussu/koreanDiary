package com.jorge.koreandiary

import com.jorge.koreandiary.ai.ClaudeCutOffException
import com.jorge.koreandiary.ai.ClaudeProtocol
import com.jorge.koreandiary.ai.CorrectionParser
import com.jorge.koreandiary.ai.Prompts
import com.jorge.koreandiary.ai.ProviderException
import com.jorge.koreandiary.scene.SceneDictionary
import com.jorge.koreandiary.scene.SceneZone
import com.jorge.koreandiary.scene.art.CustomDrawing
import com.jorge.koreandiary.scene.art.DrawingFormat
import com.jorge.koreandiary.scene.art.DrawingMotion
import com.jorge.koreandiary.scene.art.DrawingShape
import com.jorge.koreandiary.scene.art.NewSceneThing
import com.jorge.koreandiary.scene.art.PartMotion
import com.jorge.koreandiary.scene.art.SceneArt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dibujos nuevos de la escena: el formato que devuelve Claude, el diccionario con sus palabras y la pintura. */
class SceneDrawingTest {

    private val cat = """
        {"id":"cat","name":"gato","keys":["고양이","냥이"],"zone":"GROUND","size":"SMALL","motion":"NONE","count":1,
         "colors":[{"key":"fur","light":"#8a7461","dark":"#d8c2a8"},{"key":"fur_dark","light":"#5e4c3f","dark":"#a08a74"}],
         "shapes":[
          {"k":"wash","pts":[[38,100],[34,90],[36,76],[42,64],[50,58],[60,60],[68,70],[72,84],[72,100]],"c":"fur","a":0.67},
          {"k":"blob","x":40,"y":50,"r":13,"sq":0.92,"ang":0,"c":"fur","a":0.72},
          {"k":"stroke","pts":[[70,97],[83,95],[89,85],[86,72]],"w":5.5,"c":"fur","a":0.72,"move":"SWAY"}
         ]}
    """.trimIndent()

    private val gulls = """
        {"id":"seagull","name":"gaviota","keys":["갈매기"],"zone":"SKY","size":"SMALL","motion":"DRIFT","count":3,
         "colors":[{"key":"gull","light":"#56636c","dark":"#c3ccd2"}],
         "shapes":[
          {"k":"stroke","pts":[[50,58],[36,47],[20,58]],"w":4,"c":"gull","a":0.65,"move":"FLAP"},
          {"k":"stroke","pts":[[50,58],[64,47],[80,58]],"w":4,"c":"gull","a":0.65,"move":"FLAP"}
         ]}
    """.trimIndent()

    private val bird = """
        {"id":"bird","name":"pájaro","keys":["새"],"zone":"SKY","size":"SMALL","motion":"DRIFT","count":2,
         "colors":[{"key":"b","light":"#56636c","dark":"#c3ccd2"}],
         "shapes":[{"k":"blob","x":50,"y":60,"r":6,"c":"b","a":0.6}]}
    """.trimIndent()

    private fun drawing(json: String) = DrawingFormat.parse(JSONObject(json))

    @Test
    fun aDrawingIsReadAndSavedTheSameWay() {
        val d = drawing(cat)
        assertEquals("x_cat", d.id)
        assertTrue(CustomDrawing.isCustomId(d.id))
        assertEquals("gato", d.name)
        assertEquals(listOf("고양이", "냥이"), d.keys)
        assertEquals(SceneZone.GROUND, d.zone)
        assertEquals(3, d.shapes.size)
        assertEquals(PartMotion.SWAY, d.shapes.last().move)
        // Guardado y leído de nuevo: el mismo dibujo (el id ya trae el prefijo y no se duplica).
        val again = DrawingFormat.parse(DrawingFormat.toJson(d))
        assertEquals("x_cat", again.id)
        assertEquals(DrawingFormat.toJson(d).toString(), DrawingFormat.toJson(again).toString())
        // El elemento de la escena lleva el mismo id y el nombre.
        assertEquals("x_cat", d.element.id)
        assertEquals("gato", d.element.name)
    }

    /** Un id largo queda igual al guardarlo y releerlo: antes perdía letras y ya no era el de su archivo. */
    @Test
    fun aLongIdSurvivesSavingAndReading() {
        val long = JSONObject(cat).put("id", "subway_station_entrance_north")
        val d = DrawingFormat.parse(long, ko = "지하철역", es = "entrada del metro")
        assertEquals("x_subway_station_entrance", d.id)
        val again = DrawingFormat.parse(DrawingFormat.toJson(d))
        assertEquals(d.id, again.id)
    }

    @Test
    fun aBadDrawingIsTamedNotTrusted() {
        val bad = JSONObject(
            """{"id":"Faro Rojo!","keys":["등대","lighthouse","등대"],"zone":"nowhere","count":9,
            "colors":[{"key":"a","light":"#fff","dark":"#000000"},{"key":"b","light":"#112233","dark":"#445566"}],
            "shapes":[{"k":"stroke","pts":[[0,0],[500,50]],"w":99,"c":"b","a":3},
                      {"k":"wash","pts":[[1,1]],"c":"b","a":0.5},
                      {"k":"blob","x":50,"y":50,"r":5,"c":"a","a":0.5},
                      {"k":"zap","c":"b","a":0.5}]}""",
        )
        val d = DrawingFormat.parse(bad, "등대", "faro")
        assertEquals("x_faro_rojo", d.id)
        assertEquals(listOf("등대"), d.keys)
        assertEquals("faro", d.name)
        assertEquals(SceneZone.GROUND, d.zone)
        assertEquals(5, d.count)
        // El color "#fff" no vale, así que su toque se cae; la aguada de un punto también.
        assertEquals(1, d.colors.size)
        assertEquals(1, d.shapes.size)
        val stroke = d.shapes.single() as DrawingShape.Stroke
        // Dos puntos: se agrega el del medio (si no, la pincelada no se ve) y todo queda en la caja.
        assertEquals(3, stroke.points.size)
        assertTrue(stroke.points.all { it.x in -10f..110f && it.y in -10f..110f })
        assertEquals(20f, stroke.width)
        assertEquals(0.9f, stroke.alpha)
        // Sin formas no hay dibujo.
        assertThrows(IllegalArgumentException::class.java) {
            DrawingFormat.parse(JSONObject("""{"id":"x","colors":[],"shapes":[]}"""), "집", "casa")
        }
    }

    @Test
    fun newDrawingsJoinTheDictionary() {
        val custom = listOf(drawing(cat), drawing(bird))
        val lex = SceneDictionary.withCustom(custom)
        assertEquals(listOf("sea", "x_cat"), lex.detect("바다에서 고양이를 봤다").map { it.id })
        // Una sílaba: sola o con partícula, como las de la app (새벽 y 새로 no son pájaros; 새벽 es el cielo).
        assertEquals(listOf("x_bird"), lex.detect("새가 날아요").map { it.id })
        assertEquals(listOf("sky"), lex.detect("새벽에 새로 샀어요").map { it.id })
        assertEquals("고양이", lex.detectWithKeys("고양이가").single().second)
        assertEquals("gato", lex.element("x_cat")?.name)
        // Sin dibujos nuevos, el diccionario de siempre.
        assertTrue(SceneDictionary.detect("고양이").isEmpty())
        // Si una palabra nueva ya es de la app, gana la de la app.
        val clash = DrawingFormat.parse(JSONObject(cat.replace("\"냥이\"", "\"바다\"")))
        assertEquals(listOf("sea"), SceneDictionary.withCustom(listOf(clash)).detect("바다").map { it.id })
        // La lista de la revisión suma los nuevos al final.
        assertTrue(SceneDictionary.promptList(custom).endsWith("x_cat (gato), x_bird (pájaro)"))
    }

    @Test
    fun newDrawingsStayInsideTheStrip() {
        val custom = listOf(drawing(cat), drawing(gulls), drawing(bird))
        val elements = SceneDictionary.withCustom(custom).detect("고양이 갈매기 새가 바다")
        for ((w, h) in listOf(1080f to 2200f, 1600f to 1200f, 720f to 1280f)) {
            val stage = SceneArt.stageHeight(w, h)
            for (t in listOf(0f, 7.3f, 61f, 599f)) {
                val marks = SceneArt.paint(elements, w, h, t, custom)
                val mine = marks.filter { it.color.startsWith("#") }
                assertTrue("No se pintó ningún dibujo nuevo", mine.isNotEmpty())
                for (m in mine) {
                    for (i in 1 until m.outline.size step 2) {
                        assertTrue("Sube más que la franja: ${m.outline[i]}", m.outline[i] >= -stage * 0.05f)
                        assertTrue("Baja más que la hoja", m.outline[i] <= stage * 1.05f)
                    }
                }
            }
        }
        // Las gaviotas cruzan: en otro momento están en otro lugar.
        val a = SceneArt.paint(elements, 1080f, 2200f, 10f, custom).filter { it.color.startsWith("#56636c") }
        val b = SceneArt.paint(elements, 1080f, 2200f, 20f, custom).filter { it.color.startsWith("#56636c") }
        assertFalse(a.first().outline.contentEquals(b.first().outline))
        // El mismo momento da el mismo dibujo.
        assertEquals(
            SceneArt.paint(elements, 1080f, 2200f, 12.5f, custom).map { it.outline.toList() },
            SceneArt.paint(elements, 1080f, 2200f, 12.5f, custom).map { it.outline.toList() },
        )
        assertEquals(DrawingMotion.DRIFT, custom[1].motion)
    }

    @Test
    fun theReviewAsksForWhatHasNoDrawing() {
        val custom = listOf(drawing(cat))
        val prompt = Prompts.correction("", "3A", 3, emptyList(), "고양이가 있어요.", hasImage = false, scene = true, customScene = custom)
        assertTrue(prompt.lines().any { it.trim().startsWith("8. \"nuevos\"") })
        assertTrue(prompt.contains("x_cat (gato)"))
        assertTrue(prompt.contains("\"nuevos\": [{\"ko\": \"고양이\", \"es\": \"gato\"}]"))
        assertFalse(Prompts.correction("", "3A", 3, emptyList(), "가요.", hasImage = false).contains("nuevos"))
        assertTrue(Prompts.howToSay("veo un gato", "3A", 3, scene = true, customScene = custom).contains("x_cat (gato)"))

        val base = """{"oraciones":[{"n":1,"texto":"x","tiempo":"pasado"}],"correcciones":[],"usados":[{"f":"고양이","t":"n","ok":true,"id":null}],"mejoras":[]"""
        val r = CorrectionParser.parse(
            base + """, "escena": ["sea", "x_cat", "nada"],
            "nuevos": [{"ko":"등대","es":"faro"},{"ko":"바다","es":"mar"},{"ko":"bad","es":"x"},{"ko":"등대","es":"faro"},{"ko":"우산","es":"paraguas"},{"ko":"집","es":"casa"}]}""",
        )
        assertEquals(listOf("sea", "x_cat"), r.sceneElements)
        // Ya dibujable (바다), no hangul o repetido: fuera. Como máximo 2.
        assertEquals(listOf(NewSceneThing("등대", "faro"), NewSceneThing("우산", "paraguas")), r.sceneNew)
        assertTrue(CorrectionParser.parse("$base}").sceneNew.isEmpty())
    }

    @Test
    fun claudeRequestsUseTheSchemaAndNoSampling() {
        val body = ClaudeProtocol.request("claude-sonnet-5-5", "sys", "Draw: «등대» (faro).", DrawingFormat.schema(), 8000)
        assertEquals("claude-sonnet-5-5", body.getString("model"))
        assertEquals("sys", body.getString("system"))
        assertEquals("json_schema", body.getJSONObject("output_config").getJSONObject("format").getString("type"))
        // La versión 2026-10-21 rechaza temperature, top_p y top_k.
        assertFalse(body.has("temperature") || body.has("top_p") || body.has("top_k"))
        assertEquals("2026-10-21", ClaudeProtocol.headers("k")["anthropic-version"])
        assertEquals("k", ClaudeProtocol.headers("k")["x-api-key"])

        // Cada objeto del esquema cierra additionalProperties (lo exige la salida estructurada).
        fun check(o: JSONObject) {
            if (o.optString("type") == "object") assertEquals(false, o.get("additionalProperties"))
            for (k in o.keys()) {
                when (val v = o.get(k)) {
                    is JSONObject -> check(v)
                    is JSONArray -> (0 until v.length()).forEach { i -> v.optJSONObject(i)?.let(::check) }
                }
            }
        }
        check(DrawingFormat.schema())

        val ok = JSONObject()
            .put("stop_reason", "end_turn")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", cat)))
            .put("usage", JSONObject().put("input_tokens", 2100).put("output_tokens", 900))
        val reply = ClaudeProtocol.reply(ok)
        assertEquals(2100, reply.inputTokens)
        assertEquals("gato", DrawingFormat.parse(reply.json).name)
        val cut = JSONObject().put("stop_reason", "max_tokens").put("content", JSONArray())
            .put("usage", JSONObject().put("input_tokens", 3000).put("output_tokens", 16000))
        val e = assertThrows(ClaudeCutOffException::class.java) { ClaudeProtocol.reply(cut) }
        assertEquals(16000, e.outputTokens)
        assertTrue(e.message!!.contains("16000"))
        // El esfuerzo va en output_config solo si se pide.
        assertEquals("low", ClaudeProtocol.request("m", "s", "u", JSONObject(), 100, effort = "low").getJSONObject("output_config").getString("effort"))
        assertFalse(body.getJSONObject("output_config").has("effort"))
        val refused = JSONObject().put("stop_reason", "refusal").put("content", JSONArray())
        assertThrows(ProviderException::class.java) { ClaudeProtocol.reply(refused) }
    }

    @Test
    fun aDrawingAloneFillsItsBox() {
        val d = drawing(cat)
        val marks = d.boxMarks(512f, 10f, 20f)
        assertEquals(d.shapes.size, marks.size)
        for (m in marks) {
            for (i in m.outline.indices step 2) {
                assertTrue(m.outline[i] in (10f - 60f)..(10f + 512f + 60f))
                assertTrue(m.outline[i + 1] in (20f - 60f)..(20f + 512f + 60f))
            }
        }
        // Lo que más baja es el suelo de la caja (y = 100).
        val bottom = marks.maxOf { m -> (1 until m.outline.size step 2).maxOf { m.outline[it] } }
        assertTrue(bottom in (20f + 512f - 15f)..(20f + 512f + 15f))
    }

    @Test
    fun theSecondRoundShowsClaudeItsDrawing() {
        val d = drawing(cat)
        val review = DrawingFormat.review(NewSceneThing("강아지", "cachorro"), DrawingFormat.toJson(d))
        assertTrue(review.startsWith("The image above is how your drawing of «강아지» (cachorro) renders"))
        assertTrue(review.contains("say «cachorro» at a glance"))
        assertTrue(review.endsWith(DrawingFormat.toJson(d).toString()))
        val content = JSONArray().put(ClaudeProtocol.imageBlock(byteArrayOf(1, 2, 3))).put(ClaudeProtocol.textBlock(review))
        val body = ClaudeProtocol.request("claude-sonnet-5-5", DrawingFormat.system, content, DrawingFormat.schema(), 16_000)
        val blocks = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        val source = blocks.getJSONObject(0).getJSONObject("source")
        assertEquals("image", blocks.getJSONObject(0).getString("type"))
        assertEquals("base64", source.getString("type"))
        assertEquals("image/png", source.getString("media_type"))
        assertEquals("AQID", source.getString("data"))
        assertEquals(review, blocks.getJSONObject(1).getString("text"))
    }

    @Test
    fun theRequestCarriesTheSentenceAndWhatAlreadyExists() {
        val req = DrawingFormat.request(NewSceneThing("등대", "faro"), "바다 옆에\n등대가 있다", listOf("mar", "gato"))
        assertTrue(req.startsWith("Draw: «등대» (faro)."))
        assertTrue(req.contains("«바다 옆에 등대가 있다»"))
        assertTrue(req.contains("do not repeat these): mar, gato."))
        assertNull(Regex("\\n\\n").find(req))
        assertNotNull(DrawingFormat.system.lines().firstOrNull { it.startsWith("EXAMPLE 1") })
        assertNotNull(DrawingFormat.system.lines().firstOrNull { it.startsWith("EXAMPLE 2") })
    }
}
