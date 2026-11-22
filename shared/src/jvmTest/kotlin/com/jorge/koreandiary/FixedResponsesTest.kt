package com.jorge.koreandiary

import com.jorge.koreandiary.ai.CorrectionParser
import com.jorge.koreandiary.ai.ExplainParser
import com.jorge.koreandiary.ai.FixedResponses
import com.jorge.koreandiary.ai.HowToSayParser
import com.jorge.koreandiary.ai.PromptItem
import com.jorge.koreandiary.ai.Prompts
import com.jorge.koreandiary.ai.ReviewStatus
import com.jorge.koreandiary.ai.SceneSuggestionsParser
import com.jorge.koreandiary.ai.WorkbookExercise
import com.jorge.koreandiary.ai.WordParts
import com.jorge.koreandiary.ai.WorkbookParser
import com.jorge.koreandiary.conversation.ConversationParser
import com.jorge.koreandiary.conversation.ConversationPrompts
import com.jorge.koreandiary.conversation.WordGloss
import com.jorge.koreandiary.review.Cloze
import com.jorge.koreandiary.review.ClozeBlank
import com.jorge.koreandiary.review.ClozeCard
import com.jorge.koreandiary.review.ClozeKind
import com.jorge.koreandiary.review.ClozeTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La IA de respuestas fijas de la versión de prueba: cada pedido real (armado con los mismos
 * `Prompts`) recibe un JSON que su parser acepta. Si un prompt cambia y deja de reconocerse, falla aquí.
 */
class FixedResponsesTest {

    @Test
    fun reviewWithTheFixedMistakes() {
        val items = listOf(PromptItem("w1", "공원", "sustantivo"), PromptItem("g2", "-고 싶다", "gramática"))
        val prompt = Prompts.correction("", "3A", 3, items, "어제 친구을 만나요. 공원에 갔어요.", hasImage = false)
        val r = CorrectionParser.parse(FixedResponses.answer(prompt))
        assertEquals(listOf("어제 친구을 만나요.", "공원에 갔어요."), r.sentences.map { it.text })
        assertEquals(listOf("presente", "pasado"), r.sentences.map { it.tense })
        assertEquals(setOf("만나요" to "만났어요", "친구을" to "친구를"), r.corrections.map { it.original to it.suggestion }.toSet())
        assertTrue(r.corrections.all { it.sentence == 1 })
        // La primera queda enganchada a la gramática sugerida de tu lista (para «Te corrigieron»).
        assertEquals("g2", r.corrections.first().itemRef)
        assertEquals(null, r.corrections.drop(1).firstOrNull()?.itemRef)
        // La última oración, sin error, vuelve como «más natural».
        assertEquals(listOf(2), r.naturals.map { it.sentence })
        // Lo sugerido que está en el texto cuenta como usado.
        assertEquals(listOf("w1"), r.used.map { it.itemRef })
        // En una escena trae la escena viva y el tono.
        val scene = CorrectionParser.parse(FixedResponses.answer(Prompts.correction("", "3A", 3, emptyList(), "바다에 갔어요.", hasImage = false, scene = true)))
        assertEquals(listOf("sea"), scene.sceneElements)
        assertEquals("calm", scene.sceneMood?.id)
    }

    @Test
    fun howToSayAndSuggestions() {
        val how = HowToSayParser.parse(FixedResponses.answer(Prompts.howToSay("fui al parque con un amigo", "3A", 3)))
        assertEquals("오늘 친구하고 공원에 갔어요.", how.mine.text)
        assertTrue(!how.hint.isNullOrBlank())
        val candidates = listOf(PromptItem("w1", "공원", "sustantivo"), PromptItem("sg7", "-는 바람에", "gramática"), PromptItem("w9", "바다", "sustantivo"))
        val picked = SceneSuggestionsParser.parse(
            FixedResponses.answer(Prompts.contextSuggestions("3A", 3, "공원에 갔어요", candidates, scene = false)),
            candidates.map { it.ref }.toSet(),
        )
        assertEquals(listOf("w1", "sg7"), picked)
        val explained = ExplainParser.parse(FixedResponses.answer(Prompts.explainItems("", "3A", 3, listOf("공원" to false, "-느라고" to true))))
        assertEquals(listOf("공원", "-느라고"), explained.map { it.form })
        // «Más sobre esto»: de qué partes se forma, y sobrevive al guardarse.
        assertTrue(explained.all { !it.structure.isNullOrBlank() })
        assertTrue(Prompts.explainItems("", "3A", 3, listOf("꿈꾸다" to false)).contains("\"p\": de qué partes se forma"))
        assertEquals(explained[0], ExplainParser.fromJson(ExplainParser.toJson(explained[0])))
    }

    @Test
    fun quickReviewGetsACardForEveryItem() {
        val targets = listOf(
            ClozeTarget("w1", "공부하다", ClozeKind.VERB, null, "시험이 있으니까 도서관에서 공부했어요.", ClozeBlank(15, "공부했어요")),
            ClozeTarget("w2", "먹다", ClozeKind.VERB),
            ClozeTarget("w3", "바다", ClozeKind.WORD),
            ClozeTarget("g4", "-느라고", ClozeKind.GRAMMAR),
        )
        val cards = Cloze.parse(FixedResponses.answer(Cloze.prompt("", "3A", 3, targets)), targets)
        assertEquals(listOf("w1", "w2", "w3", "g4"), cards.map { it.code })
        assertEquals("먹고", cards[1].answer)
        assertTrue(cards.all { it.translation?.contains('[') == true })
        // En gramática, qué expresa (para «Usa:»), marcado como de la IA.
        assertEquals("sentido de prueba", cards[3].aiSense)
        assertTrue(cards[3].senseByAi)
    }

    @Test
    fun quickReviewTranslatesOnDemand() {
        val card = ClozeCard("w1", "덥다", ClozeKind.VERB, null, "날씨가 무척 덥군요.", 7, "덥군요")
        val t = Cloze.parseTranslation(FixedResponses.answer(Cloze.translatePrompt(card)))
        assertTrue(t.contains('['))
    }

    @Test
    fun workbookPhotos() {
        val hints = WorkbookParser.parseHints(FixedResponses.answer(Prompts.workbookHints("3A", 3, "-느라고", listOf("-는데"))))
        assertEquals(2, hints.exercises.size)
        assertEquals("-느라고", hints.grammar.single { it.focus }.form)
        val review = WorkbookParser.parseReview(FixedResponses.answer(Prompts.workbookReview("3A", 3, hints.exercises + WorkbookExercise("3", "x", emptyList(), ""))))
        assertEquals(listOf(ReviewStatus.FIX, ReviewStatus.NATURAL, ReviewStatus.OK), review.items.map { it.status })
    }

    @Test
    fun tappingAWordGivesItsPartsWithoutTheParticle() {
        val gloss = ConversationParser.word(FixedResponses.answer(ConversationPrompts.word("은메달을", "우리 팀은 은메달을 받았어요.")), "은메달을")
        assertEquals("은메달", gloss.form)
        assertEquals("sustantivo", gloss.type)
        assertEquals(true, WordParts.fits(gloss.form, gloss.parts!!))
        assertNull(gloss.note)
        assertFalse(gloss.isGrammar)
        // Una partícula o una terminación no se ofrece para la lista de vocabulario.
        assertTrue(WordGloss("을", "을", "objeto", null, type = "partícula").isGrammar)
        assertTrue(WordGloss("먹어서", "-어서", "porque", null).isGrammar)
    }

    @Test
    fun whatItDoesNotKnowIsEmpty() {
        assertEquals("{}", FixedResponses.answer("otra cosa"))
    }
}
