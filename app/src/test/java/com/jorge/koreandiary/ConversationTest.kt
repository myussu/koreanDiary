package com.jorge.koreandiary

import com.jorge.koreandiary.ai.ParseException
import com.jorge.koreandiary.ai.PromptItem
import com.jorge.koreandiary.conversation.ConversationMath
import com.jorge.koreandiary.conversation.ConversationParser
import com.jorge.koreandiary.conversation.ConversationPrompts
import com.jorge.koreandiary.conversation.ConversationRecord
import com.jorge.koreandiary.conversation.ConversationStore
import com.jorge.koreandiary.conversation.Line
import com.jorge.koreandiary.conversation.Speaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ConversationTest {
    private val targets = listOf(PromptItem("w12", "등산하다", "verbo"), PromptItem("g3", "-고 싶다", "gramática"))

    @Test
    fun voicePromptHasSpanishHelpFirstAndTargets() {
        val p = ConversationPrompts.voiceSystem("Estudio en Lima", "3A", 3, "", "오늘 친구를 만났어요.", targets, 10)
        assertTrue(p.indexOf("TOP PRIORITY") < p.indexOf("OTHER RULES"))
        assertTrue("등산하다" in p && "-고 싶다" in p)
        assertTrue("오늘 친구를 만났어요." in p)
        assertTrue("[APP]" in p)
    }

    @Test
    fun reviewPromptNumbersOnlyUserLines() {
        val lines = listOf(
            Line(Speaker.AI, "주말에 뭐 했어요?"),
            Line(Speaker.USER, "친구가 만났어요"),
            Line(Speaker.AI, "어디에서요?"),
            Line(Speaker.USER, "학교에 공부했어요"),
        )
        val p = ConversationPrompts.review("", "3A", 3, targets, lines)
        assertTrue("1. YO: 친구가 만났어요" in p)
        assertTrue("2. YO: 학교에 공부했어요" in p)
        assertTrue("   IA: 어디에서요?" in p)
        assertTrue("w12 | 등산하다 | verbo" in p)
    }

    @Test
    fun vocabularyComesFromWordTargetsAndDiary() {
        val v = ConversationPrompts.vocabulary(targets, "오늘 회사에서 일했어요. ok")
        assertTrue("등산하다" in v)
        assertFalse("-고 싶다" in v)
        assertTrue("회사에서" in v)
        assertFalse("ok" in v)
    }

    @Test
    fun parsesCoachResponses() {
        val hint = ConversationParser.hint("""```json
            {"clave": "-고 싶다: querer", "medio": "저는 등산을", "frase": "저는 등산을 하고 싶어요.", "es": "Quiero hacer senderismo."}
            ```""")
        assertEquals("저는 등산을 하고 싶어요.", hint.full)
        val word = ConversationParser.word("""{"f": "등산", "s": "senderismo", "nota": null}""", "등산을")
        assertEquals("등산", word.form)
        assertNull(word.note)
        val same = ConversationParser.sentence("""{"es": "Hola", "simple": "안녕하세요"}""", "안녕하세요")
        assertNull(same.simple)
    }

    @Test(expected = ParseException::class)
    fun hintWithoutSentenceFails() {
        ConversationParser.hint("""{"clave": "x"}""")
    }

    @Test
    fun reviewKeepsCommentAndDiaryContract() {
        val r = ConversationParser.review(
            """{"oraciones": [{"n": 1, "texto": "친구가 만났어요", "tiempo": "pasado"}],
                "correcciones": [{"oracion": 1, "original": "친구가", "sugerencia": "친구를", "tipo": "particula", "explicacion": "만나다 lleva 을/를."}],
                "usados": [{"f": "만나다", "t": "v", "ok": true, "id": null}], "mejoras": [], "comentario": "Bien."}"""
        )
        assertEquals("Bien.", r.comment)
        assertEquals("친구를", r.correction.corrections.single().suggestion)
    }

    @Test
    fun recordRoundTrip() {
        val record = ConversationRecord(
            id = 5, startedAt = 5, durationSec = 300, model = "gemini-3.8-live", turnMode = "ptt", topic = "Libre",
            lines = listOf(Line(Speaker.AI, "안녕하세요"), Line(Speaker.USER, "안녕하세요!")),
            targets = targets, lookups = listOf("등산: senderismo"), hintsUsed = 2, tokensTotal = 12_345,
            lastPromptTokens = 900, reconnections = 1, endReason = "Se cumplió el tiempo.", reviewRaw = null,
        )
        val back = ConversationStore.decode(ConversationStore.encode(record))
        assertEquals(record, back)
        assertEquals(1, back.userLines)
    }

    @Test
    fun statsPerDay() {
        val day = LocalDate.of(2026, 11, 7)
        val ms = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val records = listOf(
            ConversationRecord(id = 1, startedAt = ms + 1000, durationSec = 600),
            ConversationRecord(id = 2, startedAt = ms + 5000, durationSec = 120),
            ConversationRecord(id = 3, startedAt = ms - 86_400_000, durationSec = 60),
        )
        val s = ConversationMath.stats(records, day, ZoneOffset.UTC)
        assertEquals(720, s.secondsToday)
        assertEquals(780, s.secondsTotal)
        assertEquals(2, s.days)
        assertEquals("12 min", ConversationMath.format(720))
        assertEquals("45 s", ConversationMath.format(45))
    }

    @Test
    fun wordSeenUsesStemAndSkipsShortOnes() {
        assertTrue(ConversationMath.wordSeen("등산하다", "주말에 등산했어요"))
        assertFalse(ConversationMath.wordSeen("가다", "가방을 샀어요"))
        assertTrue(ConversationMath.wordSeen("회사", "회사에 갔어요"))
    }
}
