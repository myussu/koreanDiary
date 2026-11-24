package com.jorge.koreandiary

import com.jorge.koreandiary.ai.ParseException
import com.jorge.koreandiary.dictionary.DictionaryExplain
import com.jorge.koreandiary.dictionary.KoreanDictionary
import com.jorge.koreandiary.review.Cloze
import com.jorge.koreandiary.review.ClozeCard
import com.jorge.koreandiary.review.ClozeKind
import com.jorge.koreandiary.review.ClozeTarget
import com.jorge.koreandiary.review.DictionaryCloze
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // Sin significado en tu lista, «Usa:» lleva el del diccionario (sin marca de IA).
        assertNull(card.meaning)
        assertEquals("medalla de plata", card.sense)
        assertFalse(card.senseByAi)
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
    fun laGramaticaDiceQueExpresaYConQueVerbo() {
        // La tarjeta de Jorge del 2026-11-22: su oración sin el 에 de «-는 동안에».
        val sentence = "먹는 동안 다른 음식도 먹어 봤어요."
        val blank = DictionaryCloze.blank(dictionary, sentence, "-는 동안에", ClozeKind.GRAMMAR)!!
        assertEquals("먹는 동안", blank.text)
        val card = ClozeCard("g1", "-는 동안에", ClozeKind.GRAMMAR, null, sentence, blank.start, blank.text, own = true)
        assertEquals("먹다", DictionaryCloze.base(dictionary, card))
        // Del diccionario, corto y sin «Expresión que indica…».
        val fromDictionary = DictionaryCloze.card(dictionary, ClozeTarget("g2", "-는 동안에", ClozeKind.GRAMMAR), Random(5))!!
        assertEquals("el tiempo que dura un acto o estado", fromDictionary.dictionaryMeaning)
        assertEquals("es posible realizar", DictionaryExplain.grammarGloss("Expresión que indica que es posible realizar lo que dice el comentario anterior."))
        assertEquals("el deseo de hacer un acto", DictionaryExplain.grammarGloss("Expresión que se usa para mostrar el deseo de hacer un acto que representa el comentario anterior de la cláusula. "))
        // Tu lista manda; si no tiene, la IA (marcada); si no, el diccionario.
        assertEquals("durante", card.copy(meaning = "durante", aiSense = "x").sense)
        assertTrue(card.copy(aiSense = "durante el tiempo que", dictionaryMeaning = "y").senseByAi)
        assertEquals("y", card.copy(dictionaryMeaning = "y").sense)
    }

    @Test
    fun loDelDiccionarioTambienEnTusOraciones() {
        // Sin IA y sin significado en tu lista, «Usa:» no queda en «una palabra».
        assertEquals("ladrón", DictionaryCloze.dictionaryMeaning(dictionary, "도둑", ClozeKind.WORD, "ladrón"))
        assertEquals("el tiempo que dura un acto o estado", DictionaryCloze.dictionaryMeaning(dictionary, "-는 동안에", ClozeKind.GRAMMAR))
        assertNull(DictionaryCloze.dictionaryMeaning(dictionary, "이난", ClozeKind.WORD))
    }

    @Test
    fun laMismaPalabraConOtraTerminacion() {
        val card = ClozeCard("g1", "-는 동안에", ClozeKind.GRAMMAR, null, "먹는 동안 다른 음식도 먹어 봤어요.", 0, "먹는 동안", base = "먹다")
        assertEquals("El verbo está bien (먹다), pero aquí va otra gramática: la de «Usa:».", DictionaryCloze.nearMiss(dictionary, card, "먹으면서"))
        assertNull(DictionaryCloze.nearMiss(dictionary, card, "마시면서"))
        val verb = ClozeCard("w1", "먹다", ClozeKind.VERB, null, "어제 불고기를 먹었어요.", 8, "먹었어요", base = "먹다")
        assertTrue(DictionaryCloze.nearMiss(dictionary, verb, "먹어요")!!.contains("conjugado de otra forma"))
        val word = ClozeCard("w2", "사과", ClozeKind.WORD, null, "사과를 먹었어요.", 0, "사과")
        assertNull(DictionaryCloze.nearMiss(dictionary, word, "사람"))
    }

    @Test
    fun traducirMarcaElHueco() {
        // El hueco va marcado en la oración: si solo se nombraba, la IA resaltó «probé» en vez de «Mientras comía».
        val prompt = Cloze.translatePrompt(ClozeCard("w1", "덥다", ClozeKind.VERB, null, "날씨가 무척 덥군요.", 7, "덥군요"))
        assertTrue(prompt.contains("«날씨가 무척 [덥군요].»"))
        assertEquals("Hace [mucho calor].", Cloze.parseTranslation("{\"traduccion\": \"Hace [mucho calor].\"}"))
        try {
            Cloze.parseTranslation("{}")
            throw AssertionError("tenía que fallar")
        } catch (e: ParseException) {
            // sin traducción
        }
    }
}
