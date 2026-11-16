package com.jorge.koreandiary

import com.jorge.koreandiary.ai.GrammarTxtParser
import com.jorge.koreandiary.ai.ParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La lista de gramática en TXT (Estudio › «Importar»), sin IA. En las pruebas del 2026-11-15 el
 * formato que describe la pantalla («Unit 1: patrón | patrón») no entraba porque se exigía la viñeta.
 */
class GrammarTxtParserTest {

    @Test
    fun theFormatTheScreenDescribes() {
        val payload = GrammarTxtParser.parse("Unit 1: V-고 싶다 | A/V-(으)니까", "3A")
        assertEquals(listOf("V-고 싶다", "A/V-(으)니까"), payload.grammar.map { it.pattern })
        assertTrue(payload.grammar.all { it.unit == 1 && it.level == "3A" })
        assertEquals(listOf("V", "AV"), payload.grammar.map { it.appliesTo })
        assertTrue(payload.words.isEmpty())
    }

    @Test
    fun theBookFormatWithBulletAndBold() {
        val payload = GrammarTxtParser.parse(
            """
            # Gramática 3A
            - **Unit 2:** A-다고 하다, V-ㄴ/는다고 하다 | V-아/어야겠다
            **Unit 3:** V-느라고
            * Unidad 4: N 때문에
            """.trimIndent(),
            "3A",
        )
        assertEquals(listOf("A-다고 하다, V-ㄴ/는다고 하다", "V-아/어야겠다", "V-느라고", "N 때문에"), payload.grammar.map { it.pattern })
        assertEquals(listOf(2, 2, 3, 4), payload.grammar.map { it.unit })
        // Las comas fuera de corchetes separan variantes del mismo punto.
        assertEquals(listOf("A-다고 하다", "V-ㄴ/는다고 하다"), payload.grammar[0].variants)
    }

    @Test
    fun theAppendixWithoutTheIrregulars() {
        val payload = GrammarTxtParser.parse(
            """
            - Unit 1: V-고 싶다
            ## Appendix
            - N 대신
            - 생각[계획, 예정]이다
            ## Irregular verbs
            - ㅂ 불규칙: 덥다 → 더워요
            """.trimIndent(),
            "3A",
        )
        assertEquals(listOf("V-고 싶다", "N 대신", "생각[계획, 예정]이다"), payload.grammar.map { it.pattern })
        val appendix = payload.grammar.drop(1)
        assertTrue(appendix.all { it.unit == null && it.level == "base" })
        // Los corchetes no se parten por la coma.
        assertEquals(listOf("생각[계획, 예정]이다"), appendix[1].variants)
        assertNull(appendix[1].meaning)
    }

    @Test
    fun repeatedPatternsEnterOnce() {
        val payload = GrammarTxtParser.parse("Unit 1: V-고 싶다\nUnit 5: V-고 싶다 | V-는데", "3A")
        assertEquals(listOf("V-고 싶다", "V-는데"), payload.grammar.map { it.pattern })
        assertEquals(1, payload.grammar[0].unit)
    }

    @Test
    fun nothingRecognizedIsAnError() {
        val error = try {
            GrammarTxtParser.parse("Hola, esto no es una lista.\n# Título", "3A")
            null
        } catch (e: ParseException) {
            e
        }
        assertTrue(error?.message.orEmpty().contains("Unit N:"))
    }
}
