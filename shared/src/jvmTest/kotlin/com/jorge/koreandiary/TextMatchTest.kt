package com.jorge.koreandiary

import com.jorge.koreandiary.ink.TextMatch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Verificar": una corrección solo cuenta como reescrita si hay algo nuevo que lo demuestre. */
class TextMatchTest {

    /** La página tal como se revisó: dos errores de un solo carácter (학교를 → 학교에, 먹엇어요 → 먹었어요). */
    private val reviewed = "오늘 학교를 갔어요.\n친구하고 밥을 먹엇어요.\n재미있었어요."

    private fun rewritten(written: String, suggestion: String, recognized: String = reviewed, confirmed: String = reviewed) =
        TextMatch.isRewritten(written, suggestion, recognized, confirmed)

    @Test
    fun untouchedPageDoesNotCount() {
        // Sin escribir nada nuevo: el original está a un carácter de cada sugerencia, y no debe bastar.
        assertFalse(rewritten(reviewed, "학교에 갔어요"))
        assertFalse(rewritten(reviewed, "먹었어요"))
        assertFalse(rewritten(reviewed, "밥을 먹었어요"))
    }

    @Test
    fun correctRewriteCounts() {
        val written = "$reviewed\n학교에 갔어요"
        assertTrue(rewritten(written, "학교에 갔어요"))
        // Solo la que se reescribió.
        assertFalse(rewritten(written, "먹었어요"))
    }

    @Test
    fun rewriteSplitInTwoLinesCounts() {
        assertTrue(rewritten("$reviewed\n학교에\n갔어요", "학교에 갔어요"))
    }

    @Test
    fun rewriteRepeatingTheMistakeDoesNotCount() {
        assertFalse(rewritten("$reviewed\n학교를 갔어요", "학교에 갔어요"))
    }

    @Test
    fun misreadSyllableElsewhereStillCounts() {
        // 요 leída como 오: la corrección (에) está bien escrita.
        assertTrue(rewritten("$reviewed\n학교에 갔어오", "학교에 갔어요"))
    }

    @Test
    fun misreadOnTheCorrectedSyllableDoesNotCount() {
        // 에 leída como 애: no se puede saber si se corrigió.
        assertFalse(rewritten("$reviewed\n학교애 갔어요", "학교에 갔어요"))
    }

    @Test
    fun fixedInPlaceCounts() {
        // Borrar la partícula equivocada y reescribirla en el mismo renglón.
        assertTrue(rewritten(reviewed.replace("학교를", "학교에"), "학교에 갔어요"))
    }

    @Test
    fun shortSuggestionNeedsAnExactRead() {
        assertFalse(rewritten(reviewed, "학교에"))
        assertTrue(rewritten("$reviewed\n학교에", "학교에"))
        assertFalse(rewritten("$reviewed\n학교애", "학교에"))
    }

    @Test
    fun suggestionAlreadyOnThePageNeedsOneMore() {
        // La partícula sola ya aparece en otra oración: cuenta cuando aparece una vez más.
        val page = "저는 집에 있어요.\n학교 가요."
        assertFalse(rewritten(page, "에", recognized = page, confirmed = page))
        assertTrue(rewritten("$page\n학교에 가요", "에", recognized = page, confirmed = page))
    }

    @Test
    fun confirmedTextAlsoCountsAsAlreadyThere() {
        // Al revisar, la app leyó mal dos sílabas y el estudiante las arregló al confirmar.
        val recognized = "오늘 학고를 갓어요."
        val confirmed = "오늘 학교를 갔어요."
        // Repetir el error, esta vez bien leído, no es una reescritura.
        assertFalse(rewritten("$recognized\n학교를 갔어요", "학교에 갔어요", recognized, confirmed))
        assertTrue(rewritten("$recognized\n학교에 갔어요", "학교에 갔어요", recognized, confirmed))
    }

    @Test
    fun recognizerReadingTheMistakeAsCorrectDoesNotCount() {
        // Escribió 먹엇어요, la app leyó 먹었어요 y el estudiante puso al confirmar lo que escribió de verdad.
        val recognized = "밥을 먹었어요."
        val confirmed = "밥을 먹엇어요."
        assertFalse(rewritten(recognized, "먹었어요", recognized, confirmed))
        assertTrue(rewritten("$recognized\n먹었어요", "먹었어요", recognized, confirmed))
    }

    @Test
    fun longSentenceToleratesAMisreadButNotTheSameMistake() {
        val right = "어제는 친구를 만나서 같이 영화를 봤어요."
        val wrong = right.replace("친구를", "친구가")
        assertFalse(rewritten(wrong, right, wrong, wrong))
        assertTrue(rewritten("$wrong\n${right.replace("영화", "영하")}", right, wrong, wrong))
        assertFalse(rewritten("$wrong\n${wrong.replace("영화", "영하")}", right, wrong, wrong))
    }

    @Test
    fun unrelatedNewTextDoesNotCount() {
        assertFalse(rewritten("$reviewed\n내일은 도서관에 갈 거예요", "학교에 갔어요"))
        assertFalse(rewritten(reviewed, "  "))
    }

    @Test
    fun withoutSavedReviewTextTheUsualToleranceApplies() {
        // Páginas sin texto guardado de la revisión: no hay con qué comparar.
        assertTrue(rewritten("학교를 갔어요", "학교에 갔어요", recognized = "", confirmed = ""))
    }
}
