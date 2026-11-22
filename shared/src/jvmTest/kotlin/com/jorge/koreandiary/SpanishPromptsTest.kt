package com.jorge.koreandiary

import com.jorge.koreandiary.ai.PromptItem
import com.jorge.koreandiary.ai.Prompts
import com.jorge.koreandiary.ai.WorkbookExercise
import com.jorge.koreandiary.conversation.ConversationPrompts
import com.jorge.koreandiary.conversation.Line
import com.jorge.koreandiary.conversation.Speaker
import com.jorge.koreandiary.listen.ListenPrompts
import com.jorge.koreandiary.listen.ListenScript
import com.jorge.koreandiary.listen.ScriptSegment
import com.jorge.koreandiary.review.Cloze
import com.jorge.koreandiary.review.ClozeCard
import com.jorge.koreandiary.review.ClozeKind
import com.jorge.koreandiary.review.ClozeTarget
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Todo prompt que pide español lleva la regla del español de Perú, así un modelo no responde con
 * palabras de España («guay», «vale»). Un prompt nuevo que pida español va en esta lista.
 */
class SpanishPromptsTest {

    private val items = listOf(PromptItem("w1", "꿈꾸다", "verbo"), PromptItem("g1", "-느라고", "gramática"))
    private val lines = listOf(Line(Speaker.AI, "뭐 했어요?"), Line(Speaker.USER, "친구를 만났어요."))
    private val script = ListenScript("제목", "Título", listOf(ScriptSegment("안녕하세요.", "Hola.")), emptyList())

    private val inSpanish = mapOf(
        "corrección" to Prompts.correction("", "2급", 2, items, "오늘 친구를 만났어요.", hasImage = false),
        "¿cómo digo?" to Prompts.howToSay("quiero dormir", "2급", 2),
        "explicación" to Prompts.explainItems("", "2급", 2, listOf("꿈꾸다" to false)),
        "libro: pistas" to Prompts.workbookHints("2급", 2, null, emptyList()),
        "libro: revisión" to Prompts.workbookReview("2급", 2, listOf(WorkbookExercise("1", "completa", emptyList(), "..."))),
        "importar" to Prompts.importList("꿈꾸다 - soñar"),
        "repaso rápido" to Cloze.prompt("", "2급", 2, listOf(ClozeTarget("w1", "선선하다", ClozeKind.VERB))),
        "repaso rápido: traducir" to Cloze.translatePrompt(ClozeCard("w1", "선선하다", ClozeKind.VERB, null, "날씨가 선선해요.", 4, "선선해요")),
        "conversación: pista" to ConversationPrompts.hint("2급", 2, lines, items),
        "conversación: ¿cómo digo?" to ConversationPrompts.howToSay("quiero dormir", "2급", 2, lines),
        "conversación: palabra" to ConversationPrompts.word("만났어요", "친구를 만났어요."),
        "conversación: objetivo" to ConversationPrompts.item("-느라고", "gramática", 2),
        "conversación: traducción" to ConversationPrompts.sentence("뭐 했어요?", 2),
        "conversación: revisión" to ConversationPrompts.review("", "2급", 2, items, lines),
        "escuchar: guion" to ListenPrompts.script("", "2급", 2, "el clima", items, 5, search = false),
    )

    private val voice = mapOf(
        "conversación por voz" to ConversationPrompts.voiceSystem("", "2급", 2, "el clima", null, items, 5),
        "escuchar por voz" to ListenPrompts.liveSystem("", "2급", 2, script, items, "suave"),
    )

    @Test
    fun losPromptsEnEspanolPidenElDePeru() {
        inSpanish.forEach { (name, prompt) -> assertTrue("$name no pide el español de Perú", Prompts.SPANISH in prompt) }
    }

    @Test
    fun losModelosDeVozHablanEspanolDePeru() {
        voice.forEach { (name, prompt) -> assertTrue("$name no pide el español de Perú", Prompts.SPANISH_EN in prompt) }
    }

    @Test
    fun laReglaNombraLoQueNoSeUsaAqui() {
        assertTrue("guay" in Prompts.SPANISH)
        assertTrue("Perú" in Prompts.SPANISH)
        assertTrue("Peru" in Prompts.SPANISH_EN)
    }
}
