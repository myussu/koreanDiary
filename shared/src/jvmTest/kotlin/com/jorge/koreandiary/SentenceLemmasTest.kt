package com.jorge.koreandiary

import com.jorge.koreandiary.dictionary.DictionaryExplain
import com.jorge.koreandiary.dictionary.DictionaryLookup
import com.jorge.koreandiary.dictionary.SentenceLemmas
import com.jorge.koreandiary.review.ClozeCard
import com.jorge.koreandiary.review.ClozeKind
import com.jorge.koreandiary.review.DictionaryCloze
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La forma de diccionario mirando la oración entera, con lo que Kiwi de verdad devolvió para estas
 * oraciones (`resources/kiwi-fixtures.json`, armado con tools/kiwi_fixtures.py) y el diccionario real.
 */
class SentenceLemmasTest {

    private val dictionary get() = KiwiFixtures.dictionary
    private val analyzer get() = KiwiFixtures.analyzer

    private fun lemma(sentence: String, word: String): String? {
        val start = sentence.indexOf(word)
        return SentenceLemmas.lemmaIn(analyzer, dictionary, sentence, start, start + word.length)
    }

    @Test
    fun laOracionDecideCualEs() {
        assertEquals("듣다", lemma("음악을 들어요.", "들어요"))
        assertEquals("들다", lemma("가방을 들어요.", "들어요"))
        assertEquals("걷다", lemma("길을 걸어요.", "걸어요"))
        assertEquals("걸다", lemma("벽에 그림을 걸어요.", "걸어요"))
        assertEquals("사다", lemma("시장에서 사과를 사는 사람이 많아요.", "사는"))
        assertEquals("춥다", lemma("날씨가 추워서 집에 있었어요.", "추워서"))
    }

    @Test
    fun laPalabraMasLargaQueElDiccionarioTiene() {
        assertEquals("일반적", lemma("일반적으로 늦어지면 연락해요.", "일반적으로"))
        assertEquals("늦어지다", lemma("일반적으로 늦어지면 연락해요.", "늦어지면"))
        assertEquals("연락하다", lemma("일반적으로 늦어지면 연락해요.", "연락해요"))
        assertEquals("매운맛", lemma("저는 매운맛을 좋아해요.", "매운맛을"))
        assertEquals("듣다", SentenceLemmas.lemmaOfWord(analyzer, dictionary, "음악을 들어요.", "들어요."))
        val words = SentenceLemmas.words(analyzer, dictionary, "저는 매운맛을 좋아해요.")
        assertEquals(listOf("저는", "매운맛을", "좋아해요"), words.map { it.text })
        assertEquals("좋아하다", words.last().lemma)
    }

    @Test
    fun tocarUnaPalabraPoneLaDeLaOracionPrimero() {
        val heard = DictionaryLookup.lookup(dictionary, "들어요", preferred = "듣다")!!
        assertEquals("듣다", heard.entries.first().entry.form)
        // La otra sigue a mano por si el analizador se equivocó.
        assertTrue(heard.entries.any { it.entry.form == "들다" })
        val lifted = DictionaryLookup.lookup(dictionary, "들어요", preferred = "들다")!!
        assertEquals("들다", lifted.entries.first().entry.form)
        assertTrue(heard.byContext || lifted.byContext)
        assertFalse(DictionaryLookup.lookup(dictionary, "들어요")!!.byContext)
    }

    @Test
    fun elHuecoVaSoloDondeDeVerdadEstaLaPalabra() {
        val sentence = "시장에서 사과를 사는 사람이 많아요."
        // Sin el analizador, 사는 podía ser 살다; con él, en esta oración es 사다.
        assertNotNull(DictionaryCloze.wordBlank(dictionary, sentence, "살다", ClozeKind.VERB))
        assertNull(DictionaryCloze.wordBlank(dictionary, sentence, "살다", ClozeKind.VERB, analyzer))
        assertEquals("사는", DictionaryCloze.wordBlank(dictionary, sentence, "사다", ClozeKind.VERB, analyzer)?.text)
        assertEquals("들어요", DictionaryCloze.wordBlank(dictionary, "음악을 들어요.", "듣다", ClozeKind.VERB, analyzer)?.text)
        assertNull(DictionaryCloze.wordBlank(dictionary, "음악을 들어요.", "들다", ClozeKind.VERB, analyzer))
        assertEquals("사과", DictionaryCloze.wordBlank(dictionary, sentence, "사과", ClozeKind.WORD, analyzer)?.text)
    }

    @Test
    fun elHuecoNoVaEnUnaPalabraMasLargaQueEmpiezaIgual() {
        // 나라도 es «el país también» o «hasta yo»: lo dice la oración. Antes, si el analizador veía
        // una palabra más larga que empezaba igual (나라), el hueco de 나 iba igual dentro de ella.
        val country = "우리 나라도 겨울에는 추워요."
        assertNull(DictionaryCloze.wordBlank(dictionary, country, "나", ClozeKind.WORD, analyzer))
        assertEquals(3, DictionaryCloze.wordBlank(dictionary, country, "나라", ClozeKind.WORD, analyzer)?.start)
        assertEquals(0, DictionaryCloze.wordBlank(dictionary, "나라도 갈게요.", "나", ClozeKind.WORD, analyzer)?.start)
        assertNull(DictionaryCloze.wordBlank(dictionary, "별로 안 좋아요.", "별", ClozeKind.WORD, analyzer))
        // 갔어요 es 가다: para 같다 esa oración no sirve.
        assertNull(DictionaryCloze.wordBlank(dictionary, "학교에 갔어요.", "같다", ClozeKind.VERB, analyzer))
        assertEquals("갔어요", DictionaryCloze.wordBlank(dictionary, "학교에 갔어요.", "가다", ClozeKind.VERB, analyzer)?.text)
    }

    @Test
    fun losEjemplosQueDeVerdadUsanLaPalabra() {
        val hear = dictionary.entries("듣다").first { it.isVerb }
        val lift = dictionary.entries("들다").first { it.isVerb }
        assertTrue(DictionaryExplain.usesWord(dictionary, hear, "음악을 들어요.", analyzer))
        assertFalse(DictionaryExplain.usesWord(dictionary, lift, "음악을 들어요.", analyzer))
        assertTrue(DictionaryExplain.usesWord(dictionary, lift, "가방을 들어요.", analyzer))
    }

    @Test
    fun dicienQueGramaticaUsaste() {
        val card = ClozeCard("g1", "-는 동안에", ClozeKind.GRAMMAR, null, "먹는 동안 다른 음식도 먹어 봤어요.", 0, "먹는 동안", base = "먹다")
        val miss = DictionaryCloze.nearMiss(dictionary, card, "먹으면서", analyzer)
        assertNotNull(miss)
        assertTrue(miss, miss!!.startsWith("El verbo está bien (먹다), pero usaste «-으면서»"))
        // Sin analizador sigue el mensaje de antes.
        assertEquals("El verbo está bien (먹다), pero aquí va otra gramática: la de «Usa:».", DictionaryCloze.nearMiss(dictionary, card, "먹으면서"))
        val morphs = analyzer.analyze("먹는 동안 다른 음식도 먹어 봤어요.")
        assertEquals("-는 동안", SentenceLemmas.endingAfterVerb(dictionary, morphs, 0, 5))
    }

    @Test
    fun lasPalabrasDeUnaPaginaEnFormaDeDiccionario() {
        // Lo que «Revisar» compara con tu lista: 친구와 → 친구, 봤어요 → 보다.
        val lemmas = SentenceLemmas.lemmasIn(analyzer, dictionary, listOf("친구와 같이 영화를 봤어요.", "음악을 들어요."))
        for (w in listOf("친구", "영화", "보다", "음악", "듣다")) assertTrue("$w en $lemmas", w in lemmas)
        assertFalse(lemmas.toString(), "들다" in lemmas)
    }
}
