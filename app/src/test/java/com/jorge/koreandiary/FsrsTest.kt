package com.jorge.koreandiary

import com.jorge.koreandiary.srs.Fsrs
import com.jorge.koreandiary.srs.Fsrs.Rating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FsrsTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun firstGoodReviewUsesInitialStability() {
        val result = Fsrs.review(null, Rating.GOOD, t0)
        assertEquals(Fsrs.DEFAULT_WEIGHTS[2], result.memory.stability, 1e-9)
        // Con retención 0.9 el intervalo es igual a la estabilidad.
        val days = (result.dueAt - t0).toDouble() / Fsrs.DAY_MS
        assertEquals(result.memory.stability, days, 0.01)
    }

    @Test
    fun retrievabilityIsNinetyPercentAtStability() {
        assertEquals(0.9, Fsrs.retrievability(10.0, 10.0), 1e-6)
        assertEquals(1.0, Fsrs.retrievability(0.0, 10.0), 1e-9)
    }

    @Test
    fun successfulReviewOnDueDateIncreasesStability() {
        val first = Fsrs.review(null, Rating.GOOD, t0)
        val second = Fsrs.review(first.memory, Rating.GOOD, first.dueAt)
        assertTrue(second.memory.stability > first.memory.stability)
        assertTrue(second.dueAt - first.dueAt > first.dueAt - t0)
    }

    @Test
    fun lapseReducesStabilityAndCountsLapse() {
        val first = Fsrs.review(null, Rating.GOOD, t0)
        val second = Fsrs.review(first.memory, Rating.GOOD, first.dueAt)
        val failed = Fsrs.review(second.memory, Rating.AGAIN, second.dueAt)
        assertTrue(failed.memory.stability < second.memory.stability)
        assertEquals(1, failed.memory.lapses)
    }

    @Test
    fun hardIsBelowGoodAndDifficultyStaysInRange() {
        val first = Fsrs.review(null, Rating.GOOD, t0)
        val hard = Fsrs.review(first.memory, Rating.HARD, first.dueAt)
        val good = Fsrs.review(first.memory, Rating.GOOD, first.dueAt)
        assertTrue(hard.memory.stability < good.memory.stability)
        assertTrue(hard.memory.difficulty in 1.0..10.0)
        assertTrue(hard.memory.difficulty > good.memory.difficulty)
    }
}
