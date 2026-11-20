package com.jorge.koreandiary

import com.jorge.koreandiary.review.Cloze
import com.jorge.koreandiary.review.ClozeCard
import com.jorge.koreandiary.review.ClozeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tocar las palabras de la oración del repaso rápido: qué se toca y qué no mientras falta la respuesta. */
class ClozeWordsTest {

    private fun card(sentence: String, answer: String, start: Int = sentence.indexOf(answer)) =
        ClozeCard("w1", answer, ClozeKind.WORD, null, sentence, start, answer)

    private fun text(words: List<com.jorge.koreandiary.review.ClozeWord>) = words.joinToString("") { w -> w.pieces.joinToString("") { it.text } }

    private val medals = card("우리 팀은 이번 대회에서 은메달을 세 장 받았어요.", "장")

    @Test
    fun laOracionQuedaIgual() {
        assertEquals(medals.sentence, text(Cloze.words(medals, shown = false)))
        assertEquals(medals.sentence, text(Cloze.words(medals, shown = true)))
    }

    @Test
    fun mientrasFaltaLaRespuestaSuPalabraNoSeToca() {
        val words = Cloze.words(medals, shown = false)
        val taps = words.mapNotNull { it.tap }
        assertTrue("은메달을" in taps)
        assertTrue("받았어요" in taps) // sin el punto
        assertTrue("장" !in taps)
        val blank = words.single { w -> w.pieces.any { it.blank } }
        assertNull(blank.tap)
        assertEquals("장", blank.pieces.single { it.blank }.text)
    }

    @Test
    fun alCerrarLaTarjetaTambienSeTocaLaRespuesta() {
        val taps = Cloze.words(medals, shown = true).mapNotNull { it.tap }
        assertEquals(listOf("우리", "팀은", "이번", "대회에서", "은메달을", "세", "장", "받았어요"), taps)
    }

    @Test
    fun huecoEnMedioDeUnaPalabra() {
        val c = card("어제 친구를 만났어요.", "났어요")
        val blank = Cloze.words(c, shown = false).single { w -> w.pieces.any { it.blank } }
        assertNull(blank.tap)
        assertEquals(listOf("만" to false, "났어요" to true, "." to false), blank.pieces.map { it.text to it.blank })
        assertTrue("만났어요" in Cloze.words(c, shown = true).mapNotNull { it.tap })
    }

    @Test
    fun huecoConEspacios() {
        val c = card("저는 한국어를 할 수 있어요.", "수 있어요")
        val closed = Cloze.words(c, shown = false)
        assertEquals(c.sentence, text(closed))
        val blank = closed.single { w -> w.pieces.any { it.blank } }
        assertEquals("수 있어요.", blank.pieces.joinToString("") { it.text })
        assertNull(blank.tap)
        val open = Cloze.words(c, shown = true)
        assertEquals(c.sentence, text(open))
        assertTrue(open.mapNotNull { it.tap }.containsAll(listOf("수", "있어요")))
    }

    @Test
    fun soloSeTocaLoQueTieneCoreano() {
        assertNull(Cloze.tapTarget("3."))
        assertNull(Cloze.tapTarget("—"))
        assertEquals("은메달", Cloze.tapTarget("“은메달”"))
    }
}
