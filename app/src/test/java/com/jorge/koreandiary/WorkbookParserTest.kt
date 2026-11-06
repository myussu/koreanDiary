package com.jorge.koreandiary

import com.jorge.koreandiary.ai.ParseException
import com.jorge.koreandiary.ai.ReviewStatus
import com.jorge.koreandiary.ai.WorkbookParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkbookParserTest {

    @Test
    fun parsesHintsWithNounsAndHiddenAnswer() {
        val r = WorkbookParser.parseHints(
            """{"ejercicios": [{"n": "1", "e": "Completa con la causa",
                 "p": [{"f": "-느라고", "t": "g", "u": "Causa de algo negativo", "k": "숙제하느라고 못 잤어요.", "en": "I couldn't sleep because I was doing homework."},
                       {"f": "미용실", "t": "n", "u": "Lugar donde cortan el pelo", "k": "미용실에 가요.", "en": "I go to the hair salon."}],
                 "r": "숙제하느라고 늦게 잤어요."}],
                "vocab": [{"f": "미용실", "t": "n", "s": "peluquería"}, {"f": "-더라고요", "t": "g", "s": "x"}]}"""
        )
        val ex = r.exercises.single()
        assertEquals("1", ex.number)
        assertTrue(ex.hints[0].isGrammar)
        assertFalse(ex.hints[1].isGrammar)
        assertEquals("sustantivo", ex.hints[1].kind)
        assertEquals("숙제하느라고 늦게 잤어요.", ex.answer)
        // La gramática impresa no entra al vocabulario.
        assertEquals(listOf("미용실"), r.vocab.map { it.form })
        assertEquals("peluquería", r.vocab.single().meaning)
    }

    @Test
    fun parsesPageGrammarWithLessonFocusAndUnit() {
        val r = WorkbookParser.parseHints(
            """{"ejercicios": [{"n": "1", "e": "x", "p": [], "r": ""}],
                "gram": [{"f": "-느라고", "s": "causa negativa", "foco": true, "d": "titulo"},
                         {"f": "-(으)세요", "s": "pedido amable", "foco": false, "d": "instrucciones"},
                         {"f": "-느라고", "s": "repetida", "foco": "true", "d": "ejercicios"},
                         {"f": "-더라고요", "foco": "sí"}],
                "unidad": "5과"}"""
        )
        assertEquals(listOf("-느라고", "-(으)세요", "-더라고요"), r.grammar.map { it.form })
        assertEquals(listOf(true, false, true), r.grammar.map { it.focus })
        assertEquals("instrucciones", r.grammar[1].where)
        assertNull(r.grammar[2].meaning)
        assertEquals(5, r.unit)
    }

    @Test
    fun oldHintsWithoutPageGrammarStillParse() {
        val r = WorkbookParser.parseHints("""{"ejercicios": [{"n": "1", "e": "x", "p": [], "r": ""}], "unidad": null}""")
        assertTrue(r.grammar.isEmpty())
        assertNull(r.unit)
    }

    @Test
    fun parsesReviewStatusesAndUsedItems() {
        val r = WorkbookParser.parseReview(
            """{"rev": [{"n": "1", "w": "숙제하느라고 늦게 잤어요", "s": "ok", "c": null, "nat": null, "x": "Bien."},
                        {"n": "2", "w": "비가 와서 우산 가져가요", "s": "nat", "c": null, "nat": "비가 오니까 우산 가져가세요.", "x": "Con imperativo se usa -으니까."},
                        {"n": "3", "w": "...", "s": "fix", "c": "고쳤어요", "nat": null, "x": ""}],
                "usados": [{"f": "-느라고", "t": "g", "ok": true}, {"f": "우산", "t": "n", "ok": true}]}"""
        )
        assertEquals(listOf(ReviewStatus.OK, ReviewStatus.NATURAL, ReviewStatus.FIX), r.items.map { it.status })
        assertNull(r.items[0].corrected)
        assertEquals("비가 오니까 우산 가져가세요.", r.items[1].natural)
        assertEquals(2, r.used.size)
        assertTrue(r.used[0].isGrammar)
    }

    @Test(expected = ParseException::class)
    fun hintsWithoutExercisesFail() {
        WorkbookParser.parseHints("""{"ejercicios": [], "vocab": []}""")
    }
}
