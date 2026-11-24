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
    fun sinAnalizadorElHuecoNoCaeEnOtraPalabra() {
        fun verb(sentence: String, label: String) = DictionaryCloze.wordBlank(dictionary, sentence, label, ClozeKind.VERB)?.text
        fun noun(sentence: String, label: String) = DictionaryCloze.wordBlank(dictionary, sentence, label, ClozeKind.WORD)?.start
        // Lo que el lematizador reconoce como otra palabra no es el verbo del ítem (antes se comparaba a ojo).
        assertEquals("읽고", verb("책을 읽고 있어요.", "읽다"))
        assertNull(verb("학교에 갔어요.", "같다"))
        assertNull(verb("바다에 갔어요.", "받다"))
        assertNull(verb("친구하고 말을 했어요.", "많다"))
        assertNull(verb("저는 서울에 살아요.", "사다"))
        assertNull(verb("언니가 집에 왔어요.", "없다"))
        assertEquals("갔어요", verb("학교에 갔어요.", "가다"))
        assertEquals("살아요", verb("저는 서울에 살아요.", "살다"))
        // Un sustantivo no sale de otra palabra más larga que empieza igual…
        assertNull(noun("일요일에 친구를 만났어요.", "일"))
        assertNull(noun("별로 안 좋아요.", "별"))
        assertNull(noun("나이 때문에 힘들어요.", "나"))
        assertNull(noun("회의 시간에 늦었어요.", "회"))
        // …ni de una que puede ser las dos cosas (나라 + 도 o 나 + 라도): sin la oración no se sabe.
        assertNull(noun("우리 나라도 겨울에는 추워요.", "나"))
        // El sustantivo con su partícula sigue saliendo.
        assertEquals(0, noun("나도 갈게요.", "나"))
        assertEquals(4, noun("오늘은 일이 많아요.", "일"))
        assertEquals(0, noun("별이 정말 예뻐요.", "별"))
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
    fun unSinonimoOUnContrarioLoDiceSinDarLaRespuesta() {
        // 학교 trae a 학원 como sinónimo; 크다, a 작다 como contrario (relaciones del 한국어기초사전).
        val school = ClozeCard("w3", "학교", ClozeKind.WORD, null, "학교에 가요.", 0, "학교")
        val synonym = DictionaryCloze.nearMiss(dictionary, school, "학원")
        assertEquals("«학원» significa casi lo mismo que la del hueco, pero aquí va otra palabra: mira la traducción.", synonym)
        assertTrue(!synonym!!.contains("학교"))
        val big = ClozeCard("w4", "크다", ClozeKind.VERB, null, "집이 정말 커요.", 6, "커요", base = "크다")
        assertEquals("«작다» es lo contrario de la del hueco.", DictionaryCloze.nearMiss(dictionary, big, "작아요"))
        // Al revés también: 작다 no trae a 크다, pero 크다 sí trae a 작다.
        val small = ClozeCard("w5", "작다", ClozeKind.VERB, null, "방이 작아요.", 3, "작아요", base = "작다")
        assertEquals("«크다» es lo contrario de la del hueco.", DictionaryCloze.nearMiss(dictionary, small, "커요"))
        // Lo que no tiene relación sigue sin mensaje.
        assertNull(DictionaryCloze.nearMiss(dictionary, school, "사과"))
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
