package com.jorge.koreandiary

import com.jorge.koreandiary.ai.CorrectionParser
import com.jorge.koreandiary.ai.HowToSayParser
import com.jorge.koreandiary.ai.PromptItem
import com.jorge.koreandiary.ai.SceneSuggestionsParser
import com.jorge.koreandiary.ai.Prompts
import com.jorge.koreandiary.data.standard.GrammarKeys
import com.jorge.koreandiary.data.standard.StdGrammar
import com.jorge.koreandiary.scene.SceneData
import com.jorge.koreandiary.scene.SceneDictionary
import com.jorge.koreandiary.scene.SceneZone
import com.jorge.koreandiary.scene.SceneIdea
import com.jorge.koreandiary.scene.MoodReading
import com.jorge.koreandiary.scene.PendingScene
import com.jorge.koreandiary.scene.SceneMoods
import com.jorge.koreandiary.srs.SuggestionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SceneTest {

    // ---------- SceneData ----------

    @Test
    fun sceneDataRoundTrip() {
        val data = SceneData(newGrammar = listOf(12, 40), ideas = listOf(SceneIdea("el viento acaricia el pasto", 2)))
        assertEquals(data, SceneData.parse(data.toJson()))
    }

    @Test
    fun sceneDataToleratesMissingOrBrokenJson() {
        assertEquals(SceneData(), SceneData.parse(null))
        assertEquals(SceneData(), SceneData.parse(""))
        assertEquals(SceneData(), SceneData.parse("{no es json"))
        // Pistas fuera de rango se acotan; ideas vacías se descartan.
        val d = SceneData.parse("""{"ng":[3],"ideas":[{"t":"  ","p":2},{"t":"mar azul","p":9}]}""")
        assertEquals(listOf(3), d.newGrammar)
        assertEquals(listOf(SceneIdea("mar azul", 3)), d.ideas)
    }

    @Test
    fun sameIdeaKeepsTheHighestHint() {
        val d = SceneData()
            .withIdea(SceneIdea("El viento acaricia el pasto", 1))
            .withIdea(SceneIdea("el viento acaricia el pasto", 3))
            .withIdea(SceneIdea("el viento acaricia el pasto", 2))
        assertEquals(1, d.ideas.size)
        assertEquals(3, d.ideas.single().hints)
    }

    // ---------- Escena viva: diccionario ----------

    private fun ids(text: String) = SceneDictionary.detect(text).map { it.id }

    @Test
    fun dictionaryBuildsTheSceneInWritingOrder() {
        assertEquals(listOf("sea", "sky", "wind"), ids("바다가 파래요. 바람이 불어요."))
        assertEquals(listOf("grass", "sea"), ids("잔디에 앉아서 바다를 봐요"))
        assertEquals(listOf("sea"), ids("바닷가에서 파도 소리를 들어요"))
        assertEquals(listOf("wheat"), ids("밀밭이 황금빛이에요"))
        assertEquals(listOf("stars", "sparkle"), ids("별이 반짝반짝"))
        assertEquals(listOf("sun", "sky"), ids("맑은 하늘"))
        assertTrue(ids("해가 지고 있어요").containsAll(listOf("sun", "sunset")))
    }

    @Test
    fun oneSyllableNounsNeedToStandAlone() {
        // 해요 (hacer), 별로 (no mucho), 비싸요 (caro), 달라요 (distinto), 강아지 (perro): nada.
        for (text in listOf("해요", "별로 안 좋아요", "비싸요", "달라요", "강아지가 있어요")) {
            assertTrue("«$text» no debería sumar nada", ids(text).isEmpty())
        }
        assertEquals(listOf("sun"), ids("해가 떠요"))
        assertEquals(listOf("rain"), ids("비가 와요"))
        assertEquals(listOf("snow"), ids("눈이 와요"))
    }

    @Test
    fun dictionaryZonesFollowTheView() {
        assertEquals(SceneZone.GROUND, SceneDictionary.element("grass")!!.zone)
        assertEquals(SceneZone.HORIZON, SceneDictionary.element("sea")!!.zone)
        assertEquals(SceneZone.SKY, SceneDictionary.element("sun")!!.zone)
        assertEquals(SceneZone.AIR, SceneDictionary.element("wind")!!.zone)
        assertTrue(ids("").isEmpty())
    }

    @Test
    fun aiElementsAreFilteredAndKeptInOrder() {
        val d = SceneData().withElements(listOf("sea", "unicornio", "sand")).withElements(listOf("sea", "sun"))
        assertEquals(listOf("sea", "sand", "sun"), d.elements)
        assertEquals(d, SceneData.parse(d.toJson()))
        val how = HowToSayParser.parse("""{"tu": {"k": "바람이 불어요", "g": []}, "esc": ["Wind", "dragon", "grass"]}""")
        assertEquals(listOf("wind", "grass"), how.sceneElements)
        val review = CorrectionParser.parse("""{"oraciones": [{"n": 1, "texto": "가요", "tiempo": "presente"}], "escena": ["sea", "sea"]}""")
        assertEquals(listOf("sea"), review.sceneElements)
    }

    // ---------- Gramáticas nuevas por escena ----------

    private fun g(id: Int, level: Int, form: String, related: List<String> = emptyList()) =
        StdGrammar(id, level, "표현", form, related, "")

    private val catalog = listOf(
        g(1, 2, "-아서/어서"),
        g(2, 3, "-는 것 같다"),
        g(3, 3, "-아/어 보이다"),
        g(4, 3, "-(으)ㄹ 때"),
        g(5, 4, "-더라고요"),
        g(6, 4, "-느라고"),
        g(7, 5, "-다시피"),
    )

    @Test
    fun newGrammarIsYourLevelFirstThenTheNext() {
        val picked = SuggestionEngine.pickNewGrammar(catalog, emptySet(), level = 3, count = 2, random = Random(1))
        assertEquals(2, picked.size)
        assertTrue(picked.all { it.level == 3 })
        // Si tu nivel se acaba, sigue con el siguiente; nunca dos niveles arriba.
        val more = SuggestionEngine.pickNewGrammar(catalog, emptySet(), level = 3, count = 10, random = Random(1))
        assertEquals(setOf(2, 3, 4, 5, 6), more.map { it.id }.toSet())
        assertEquals(listOf(3, 3, 3, 4, 4), more.map { it.level })
    }

    @Test
    fun newGrammarSkipsWhatYouKnowAndWhatIsAlreadySuggested() {
        val myKeys = GrammarKeys.keys(listOf("-는 것 같다", "-아/어 보이다"))
        val picked = SuggestionEngine.pickNewGrammar(catalog, myKeys, level = 3, count = 2, exclude = setOf(4), random = Random(1))
        assertTrue(picked.none { it.id in setOf(2, 3, 4) })
        assertTrue(picked.all { it.level == 4 })
    }

    @Test
    fun newGrammarAtTheTopLevelStaysInRange() {
        val picked = SuggestionEngine.pickNewGrammar(catalog + g(8, 6, "-기 마련이다"), emptySet(), level = 6, count = 2)
        assertEquals(listOf(8), picked.map { it.id })
    }

    // ---------- ¿Cómo digo? con pistas ----------

    @Test
    fun howToSayReadsTheHint() {
        val r = HowToSayParser.parse(
            """{"tu": {"k": "바람이 잔디를 쓰다듬어요", "g": []}, "nat": null, "nota": "",
               "w": [{"f": "바람", "s": "viento"}], "pista": "바람 lleva 이 y 잔디 lleva 를."}"""
        )
        assertEquals("바람 lleva 이 y 잔디 lleva 를.", r.hint)
        assertNull(HowToSayParser.parse("""{"tu": {"k": "가요", "g": []}}""").hint)
    }

    // ---------- Actualizar sugerencias ----------

    @Test
    fun sceneSuggestionsKeepOnlyCandidatesOnce() {
        val allowed = setOf("w1", "w2", "sv465", "sg7")
        val picked = SceneSuggestionsParser.parse(
            "Aquí va: {\"items\": [\"w2\", \"sv465\", \"w2\", \"w999\", \" sg7 \"]}",
            allowed,
        )
        assertEquals(listOf("w2", "sv465", "sg7"), picked)
        assertEquals(emptyList<String>(), SceneSuggestionsParser.parse("{\"items\": []}", allowed))
        assertEquals(emptyList<String>(), SceneSuggestionsParser.parse("{}", allowed))
    }

    @Test
    fun sceneSuggestionsPromptListsTheCandidates() {
        val p = Prompts.sceneSuggestions(
            "3A", 3, "바다가 파래요\n바람이 불어요",
            listOf(PromptItem("w12", "모래", "sustantivo"), PromptItem("sv465", "파도", "sustantivo")),
        )
        assertTrue(p.contains("- w12: 모래 (sustantivo)"))
        assertTrue(p.contains("- sv465: 파도 (sustantivo)"))
        assertTrue(p.contains("바다가 파래요 바람이 불어요"))
    }

    // ---------- Notas de sentido ----------

    @Test
    fun correctionReadsMeaningNotes() {
        val r = CorrectionParser.parse(
            """{"oraciones": [{"n": 1, "texto": "바람이 잔디를 때려요.", "tiempo": "presente"}],
               "correcciones": [], "usados": [{"f": "바람", "t": "n", "ok": true, "id": null}],
               "notas": [{"oracion": 1, "original": "때려요", "sugerencia": "쓰다듬어요",
                          "explicacion": "Dice que el viento golpea; querías que acaricia."}]}"""
        )
        val note = r.notes.single()
        assertEquals("때려요", note.original)
        assertEquals("쓰다듬어요", note.suggestion)
        assertEquals(1, note.sentence)
        assertTrue(r.corrections.isEmpty())
        // Sin "notas" (el diario) no pasa nada.
        assertTrue(CorrectionParser.parse("""{"oraciones": [{"n": 1, "texto": "가요", "tiempo": "presente"}]}""").notes.isEmpty())
    }

    @Test
    fun correctionPromptAsksForNotesOnlyWithIdeas() {
        val withIdeas = Prompts.correction("", "3A", 3, emptyList(), "바람이 잔디를 때려요.", hasImage = false, scene = true, ideas = listOf("el viento acaricia el pasto"))
        assertTrue(withIdeas.contains("LO QUE QUISO DECIR"))
        assertTrue(withIdeas.contains("- el viento acaricia el pasto"))
        assertTrue(withIdeas.contains("\"notas\""))
        assertTrue(withIdeas.contains("-아/어 보이다"))
        // El texto queda alineado: trimIndent no se rompe con las líneas agregadas.
        assertTrue(withIdeas.lines().any { it.startsWith("5. \"notas\"") })
        assertTrue(withIdeas.lines().any { it.startsWith("6. \"escena\"") })
        val diary = Prompts.correction("", "3A", 3, emptyList(), "가요.", hasImage = false)
        assertFalse(diary.contains("\"notas\""))
        assertFalse(diary.contains("\"escena\""))
        assertFalse(diary.contains("LO QUE QUISO DECIR"))
        assertTrue(diary.lines().any { it.startsWith("4. \"mejoras\"") })
    }

    // ---------- Tono de la escena ----------

    @Test
    fun correctionReadsTheMood() {
        val base = """{"oraciones": [{"n": 1, "texto": "여보의 손을 잡아요.", "tiempo": "presente"}]"""
        assertEquals(MoodReading("love", 3), CorrectionParser.parse(base + """, "tono": {"id": "Love", "n": 3}}""").sceneMood)
        // Solo el id: intensidad media. Neutro, desconocido o sin tono: nada.
        assertEquals(MoodReading("joy", 2), CorrectionParser.parse(base + """, "tono": "joy"}""").sceneMood)
        assertNull(CorrectionParser.parse(base + """, "tono": null}""").sceneMood)
        assertNull(CorrectionParser.parse(base + """, "tono": {"id": "romance", "n": 2}}""").sceneMood)
        assertNull(CorrectionParser.parse(base + "}").sceneMood)
        // La intensidad se acota a 1..3.
        assertEquals(MoodReading("calm", 3), CorrectionParser.parse(base + """, "tono": {"id": "calm", "n": 9}}""").sceneMood)
        val how = HowToSayParser.parse("""{"tu": {"k": "손을 잡아요", "g": []}, "tono": {"id": "love", "n": 1}}""")
        assertEquals(MoodReading("love", 1), how.sceneMood)
    }

    @Test
    fun sceneKeepsTheLastMood() {
        val love = SceneData().withMood(MoodReading("love", 2))
        assertEquals(MoodReading("love", 2), love.mood)
        // Una lectura sin tono no borra el que había.
        assertEquals(love, love.withMood(null))
        assertEquals(love, SceneData.parse(love.toJson()))
        assertEquals(MoodReading("calm", 1), love.withMood(MoodReading("calm", 1)).mood)
        // JSON viejo (sin tono) o con un tono que ya no existe.
        assertNull(SceneData.parse("""{"ng":[1]}""").mood)
        assertNull(SceneData.parse("""{"tono":{"id":"x","n":2}}""").mood)
        assertTrue(SceneMoods.all.map { it.id }.distinct().size == SceneMoods.all.size)
    }

    @Test
    fun howToSayWaitsUntilYouWriteIt() {
        // «escalar una montaña»: la montaña y el tono esperan hasta que la tinta diga 산 u 오르-.
        val pending = PendingScene(listOf("mountain"), MoodReading("awe", 2), listOf("산", "오르다"))
        val d = SceneData().withPending(pending)
        assertTrue(d.elements.isEmpty())
        assertNull(d.mood)
        assertEquals(d, SceneData.parse(d.toJson()))
        assertEquals(d, d.revealWritten("바다를 보고 있어요"))
        val written = d.revealWritten("높은 산에 올라가요")
        assertEquals(listOf("mountain"), written.elements)
        assertEquals(MoodReading("awe", 2), written.mood)
        assertTrue(written.pending.isEmpty())
        // Sin palabras de diccionario, el comienzo de cada palabra de la frase.
        assertEquals(listOf("산에", "올라"), PendingScene.wordsFrom(emptyList(), "산에 올라가요."))
        // Una idea en la que la IA no vio nada no queda esperando.
        assertEquals(SceneData(), SceneData().withPending(PendingScene(emptyList(), null, listOf("가다"))))
    }

    @Test
    fun promptsAskForTheMoodOnlyInScenes() {
        val withIdeas = Prompts.correction("", "3A", 3, emptyList(), "가요.", hasImage = false, scene = true, ideas = listOf("tomo su mano"))
        assertTrue(withIdeas.lines().any { it.startsWith("7. \"tono\"") })
        assertTrue(withIdeas.contains("love (amor, ternura)"))
        assertTrue(withIdeas.contains("\"tono\": {\"id\": \"love\", \"n\": 2}"))
        val noIdeas = Prompts.correction("", "3A", 3, emptyList(), "가요.", hasImage = false, scene = true)
        assertTrue(noIdeas.lines().any { it.startsWith("6. \"tono\"") })
        assertFalse(Prompts.correction("", "3A", 3, emptyList(), "가요.", hasImage = false).contains("\"tono\""))
        val how = Prompts.howToSay("tomo su mano", "3A", 3, tiered = true)
        assertTrue(how.lines().any { it.startsWith("8. \"tono\"") })
        assertFalse(Prompts.howToSay("mañana voy", "3A", 3).contains("\"tono\""))
    }

    @Test
    fun scenePromptAsksForTheHintAndTheTargets() {
        val scene = Prompts.howToSay("el viento acaricia el pasto", "3A", 3, tiered = true, targets = listOf("-아/어 보이다", "-는 것 같다"))
        assertTrue(scene.contains("\"pista\""))
        assertTrue(scene.contains("\"esc\"") && scene.contains("wind (viento)"))
        assertTrue(scene.contains("-아/어 보이다, -는 것 같다"))
        assertTrue(scene.contains("escena"))
        val diary = Prompts.howToSay("mañana voy", "3A", 3)
        assertFalse(diary.contains("\"pista\""))
        assertFalse(diary.contains("gramáticas nuevas"))
        assertTrue(diary.contains("su diario"))
    }
}
