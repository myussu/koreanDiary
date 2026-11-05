package com.jorge.koreandiary

import com.jorge.koreandiary.mascot.MascotBrain
import com.jorge.koreandiary.mascot.MascotMood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MascotBrainTest {
    private val today = LocalDate.of(2026, 11, 5)
    private fun daysAgo(vararg n: Long) = n.map { today.minusDays(it) }

    @Test
    fun streakCountsConsecutiveDaysUpToToday() {
        assertEquals(3, MascotBrain.streak(daysAgo(0, 1, 2, 4), today))
    }

    @Test
    fun streakStillAliveIfLastPageWasYesterday() {
        assertEquals(2, MascotBrain.streak(daysAgo(1, 2), today))
        assertEquals(0, MascotBrain.streak(daysAgo(2, 3), today))
    }

    @Test
    fun greetingDependsOnActivity() {
        assertEquals(MascotMood.HAPPY, MascotBrain.greeting(emptyList(), today, 9).mood)
        assertEquals(MascotMood.SLEEPY, MascotBrain.greeting(daysAgo(5), today, 9).mood)
        val streak = MascotBrain.greeting(daysAgo(0, 1, 2), today, 20)
        assertEquals(MascotMood.HAPPY, streak.mood)
        assertTrue(streak.korean.contains("3일째"))
        assertEquals(MascotMood.ENCOURAGE, MascotBrain.greeting(daysAgo(1, 2), today, 15).mood)
    }

    @Test
    fun importedFileNamesMapToMoods() {
        assertEquals(MascotMood.ENCOURAGE, MascotMood.fromFileName("Ánimo.PNG"))
        assertEquals(MascotMood.IDLE, MascotMood.fromFileName("pack/idle.webp"))
        assertEquals(MascotMood.HAPPY, MascotMood.fromFileName("happy.png"))
        assertNull(MascotMood.fromFileName("fondo.png"))
    }
}
