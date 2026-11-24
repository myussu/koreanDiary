package com.jorge.koreandiary

import com.jorge.koreandiary.dictionary.SentenceBreakdown
import com.jorge.koreandiary.dictionary.SentenceLemmas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Desglosar» una oración sin IA, con lo que Kiwi de verdad devolvió. */
class SentenceBreakdownTest {

    private val dictionary = KiwiFixtures.dictionary
    private val analyzer = KiwiFixtures.analyzer

    @Test
    fun cadaPalabraConSuFormaYSuGramatica() {
        val r = SentenceBreakdown.of(dictionary, "어제 친구를 만났어요.", analyzer)
        assertTrue(r.analyzed)
        assertEquals("어제 친구를 만나써요.", r.sound)
        assertEquals(listOf("어제", "친구", "만나다"), r.words.map { it.lemma })
        val met = r.words.last()
        assertEquals(listOf("-었-" to "pasado", "-어요" to "cortés"), met.grammar.map { it.form to it.label })
        assertEquals(listOf("를" to "objeto"), r.words[1].grammar.map { it.form to it.label })
        assertTrue(met.gloss.orEmpty().isNotBlank())
    }

    @Test
    fun losModismosDeLaOracion() {
        val r = SentenceBreakdown.of(dictionary, "이 옷이 정말 마음에 들어요.", analyzer)
        assertEquals(listOf("마음에 들다"), r.idioms.map { it.form })
        assertEquals(listOf("귀가 얇다"), SentenceBreakdown.of(dictionary, "제 친구는 귀가 얇아요.", analyzer).idioms.map { it.form })
        // Las palabras sueltas no bastan: tienen que estar todas y en orden.
        assertTrue(SentenceBreakdown.idioms(dictionary, listOf("들다", "마음")).isEmpty())
    }

    @Test
    fun sinElAnalizadorSoloLaFormaYComoSuena() {
        val r = SentenceBreakdown.of(dictionary, "친구를 만났어요.", null)
        assertFalse(r.analyzed)
        assertEquals("친구를 만나써요.", r.sound)
        assertEquals(listOf("친구", "만나다"), r.words.map { it.lemma })
        assertTrue(r.words.all { it.grammar.isEmpty() })
    }

    @Test
    fun kiwiNoConvierteAyerEnUnaInterjeccion() {
        // Kiwi lee «어제 친구를…» como 어 + 저 + 의; la palabra entera está en el diccionario.
        assertEquals("어제", SentenceLemmas.lemmaOfWord(analyzer, dictionary, "어제 친구를 만났어요.", "어제"))
    }
}
