package com.jorge.koreandiary

import com.jorge.koreandiary.data.db.GrammarPoint
import com.jorge.koreandiary.data.db.MemoryState
import com.jorge.koreandiary.workbook.GrammarSearch
import com.jorge.koreandiary.workbook.LessonContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonGrammarTest {
    private fun g(id: Long, pattern: String, meaning: String? = null, variants: String = pattern) =
        GrammarPoint(id = id, unit = null, level = "3A", pattern = pattern, variants = variants, appliesTo = "", meaning = meaning, source = "import")

    private val tende = g(1, "A/V-(으)ㄹ 텐데", "supongo que… (suposición)")
    private val neurago = g(2, "V-느라고", "por estar haciendo (causa)")
    private val neunde = g(3, "A/V-는데", "contraste o contexto")

    @Test
    fun searchIgnoresBookNotation() {
        assertTrue(GrammarSearch.matches("텐데", tende))
        assertTrue(GrammarSearch.matches("을 텐데", tende))
        assertTrue(GrammarSearch.matches("-느라", neurago))
        assertFalse(GrammarSearch.matches("느라", tende))
    }

    @Test
    fun searchAlsoLooksAtTheMeaningWithoutAccents() {
        assertTrue(GrammarSearch.matches("suposicion", tende))
        assertTrue(GrammarSearch.matches("CAUSA", neurago))
    }

    @Test
    fun exactMatchesComeFirst() {
        val all = listOf(tende, neurago, neunde, g(9, "A/V-는데도", "aunque"))
        assertEquals(listOf(3L, 9L), GrammarSearch.search("는데", all).map { it.id })
        assertEquals(4, GrammarSearch.search("", all).size)
    }

    @Test
    fun previousGrammarSkipsTheLessonAndPutsDueFirst() {
        val now = 1_000L
        val states = mapOf(
            1L to MemoryState("grammar", 1, 3.0, 5.0, 0, dueAt = 500, reps = 2, lapses = 0),
            3L to MemoryState("grammar", 3, 30.0, 5.0, 0, dueAt = 5_000, reps = 4, lapses = 0),
        )
        val pool = listOf(tende, neurago, neunde, g(4, "N(이)라도"))
        val previous = LessonContext.previousGrammar(pool, states, focus = "-느라고", now = now)
        assertEquals(listOf("A/V-(으)ㄹ 텐데", "N(이)라도", "A/V-는데"), previous)
        assertEquals(2, LessonContext.previousGrammar(pool, states, null, now, limit = 2).size)
    }
}
