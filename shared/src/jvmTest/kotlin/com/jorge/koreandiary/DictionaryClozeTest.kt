package com.jorge.koreandiary

import com.jorge.koreandiary.ai.ParseException
import com.jorge.koreandiary.dictionary.KoreanDictionary
import com.jorge.koreandiary.review.Cloze
import com.jorge.koreandiary.review.ClozeKind
import com.jorge.koreandiary.review.ClozeTarget
import com.jorge.koreandiary.review.DictionaryCloze
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Repaso rápido con oraciones del 한국어기초사전 (sin IA) y el hueco con el diccionario en tus
 * oraciones. Usa los archivos reales del repositorio.
 */
class DictionaryClozeTest {

    private val dictionary by lazy {
        val dir = listOf("../app/src/main/assets/dictionary", "app/src/main/assets/dictionary").map(::File).first { it.isDirectory }
        KoreanDictionary { name -> File(dir, name).readLines() }
    }

    @Test
    fun unaPalabraConOracionDelDiccionario() {
        val card = DictionaryCloze.card(dictionary, ClozeTarget("w1", "은메달", ClozeKind.WORD), Random(1))
        assertNotNull(card)
        card!!
        assertTrue(card.fromDictionary && !card.own)
        assertEquals("은메달", card.answer)
        assertEquals("은메달", card.sentence.substring(card.start, card.start + card.answer.length))
        // Sin significado en tu lista, la pista «Falta: …» usa el del diccionario.
        assertEquals("medalla de plata", card.meaning)
        assertNull(card.translation)
    }

    @Test
    fun unVerboConjugadoYTuSignificado() {
        val card = DictionaryCloze.card(dictionary, ClozeTarget("w2", "덥다", ClozeKind.VERB, "hacer calor"), Random(2))!!
        assertEquals("hacer calor", card.meaning)
        assertEquals("덥다", card.base)
        assertTrue(card.answer, dictionary.baseForms(card.answer).any { it.form == "덥다" })
        // Cortés primero: el repaso va en -아요/어요.
        assertTrue(card.sentence, Regex("(요|니다|니까)[.?!~]*$").containsMatchIn(card.sentence))
    }

    @Test
    fun unaGramaticaConLaNotacionDeLaApp() {
        val card = DictionaryCloze.card(dictionary, ClozeTarget("g1", "-(으)ㄹ 수 있다", ClozeKind.GRAMMAR), Random(3))!!
        assertTrue(card.answer, card.answer.contains("수 있"))
        assertNotNull(card.base)
        val particle = DictionaryCloze.card(dictionary, ClozeTarget("g2", "N이랑", ClozeKind.GRAMMAR), Random(4))!!
        // Una partícula: el hueco es solo ella.
        assertEquals("이랑", particle.answer)
    }

    @Test
    fun sinDiccionarioNoHayTarjeta() {
        assertNull(DictionaryCloze.card(dictionary, ClozeTarget("w3", "이난", ClozeKind.WORD)))
        assertNull(DictionaryCloze.card(dictionary, ClozeTarget("g3", "-고 말고요 inventado", ClozeKind.GRAMMAR)))
    }

    @Test
    fun elHuecoEnTusOracionesConIrregulares() {
        // Antes no se encontraban y la oración tuya no entraba al repaso.
        assertEquals("고팠다", DictionaryCloze.blank(dictionary, "배가 너무 고팠다.", "고프다", ClozeKind.VERB)?.text)
        assertEquals("덥군요", DictionaryCloze.blank(dictionary, "날씨가 덥군요.", "덥다", ClozeKind.VERB)?.text)
        assertEquals("늦어", DictionaryCloze.blank(dictionary, "오늘 늦어.", "늦다", ClozeKind.VERB)?.text)
        assertEquals("읽을 수 있어요", DictionaryCloze.blank(dictionary, "한국어를 읽을 수 있어요.", "-(으)ㄹ 수 있다", ClozeKind.GRAMMAR)?.text)
        // 장 es la palabra 장을, no el 장 de 시장.
        assertEquals(5, DictionaryCloze.blank(dictionary, "시장에서 장을 봤어요.", "장", ClozeKind.WORD)?.start)
    }

    @Test
    fun traducirSoloSiLaPides() {
        val prompt = Cloze.translatePrompt("날씨가 무척 덥군요.", "덥군요")
        assertTrue(prompt.contains("날씨가 무척 덥군요.") && prompt.contains("«덥군요»"))
        assertEquals("Hace [mucho calor].", Cloze.parseTranslation("{\"traduccion\": \"Hace [mucho calor].\"}"))
        try {
            Cloze.parseTranslation("{}")
            throw AssertionError("tenía que fallar")
        } catch (e: ParseException) {
            // sin traducción
        }
    }
}
