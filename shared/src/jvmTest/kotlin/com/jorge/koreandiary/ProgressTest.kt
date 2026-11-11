package com.jorge.koreandiary

import com.jorge.koreandiary.data.db.ItemType
import com.jorge.koreandiary.data.db.MemoryState
import com.jorge.koreandiary.progress.Progress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class ProgressTest {
    // Jueves.
    private val today = LocalDate.of(2026, 11, 5)

    @Test
    fun heatmapEndsInTheCurrentWeekStartingOnMonday() {
        val grid = Progress.heatmap(mapOf(today to 2, today.minusDays(8) to 1), today, weeks = 10)
        assertEquals(10, grid.size)
        assertTrue(grid.all { it.size == 7 })
        assertEquals(DayOfWeek.MONDAY, grid.first().first().date.dayOfWeek)
        val current = grid.last()
        assertEquals(2, current.first { it.date == today }.pages)
        assertFalse(current.first { it.date == today }.future)
        assertTrue(current.last().future) // domingo 8
        assertEquals(1, grid.flatten().first { it.date == today.minusDays(8) }.pages)
    }

    private fun state(type: String, id: Long, reps: Int, stability: Double = 1.0, due: Long = 0, lapses: Int = 0, difficulty: Double = 5.0) =
        MemoryState(type, id, stability, difficulty, lastReviewAt = 0, dueAt = due, reps = reps, lapses = lapses)

    @Test
    fun summaryCountsOnlyPracticedItemsOfTheType() {
        val states = listOf(
            state(ItemType.WORD, 1, reps = 3, stability = 30.0, due = 500),
            state(ItemType.WORD, 2, reps = 1, stability = 2.0, due = 50),
            state(ItemType.WORD, 3, reps = 0, due = 10),
            state(ItemType.GRAMMAR, 1, reps = 2, due = 10),
        )
        val words = Progress.summarize(states, ItemType.WORD, endOfToday = 100)
        assertEquals(2, words.practiced)
        assertEquals(1, words.solid)
        assertEquals(1, words.dueToday)
    }

    @Test
    fun hardestOrdersByLapsesThenDifficulty() {
        val states = listOf(
            state(ItemType.WORD, 1, reps = 3, lapses = 1, difficulty = 9.0),
            state(ItemType.GRAMMAR, 2, reps = 4, lapses = 3),
            state(ItemType.WORD, 3, reps = 2, lapses = 1, difficulty = 4.0),
            state(ItemType.WORD, 4, reps = 2, lapses = 0),
        )
        assertEquals(listOf(2L, 1L, 3L), Progress.hardest(states, 5).map { it.itemId })
        assertEquals(1, Progress.hardest(states, 1).size)
    }
}
