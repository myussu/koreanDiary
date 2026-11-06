package com.jorge.koreandiary

import com.jorge.koreandiary.mascot.MascotBrain
import com.jorge.koreandiary.mascot.MascotMood
import com.jorge.koreandiary.reminder.ReminderScheduler
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
    fun bestStreakFindsLongestRun() {
        assertEquals(0, MascotBrain.bestStreak(emptyList()))
        assertEquals(3, MascotBrain.bestStreak(daysAgo(0, 4, 5, 6, 9)))
        // Fechas repetidas o desordenadas no cambian el resultado.
        assertEquals(2, MascotBrain.bestStreak(daysAgo(3, 2, 2, 7)))
    }

    @Test
    fun reminderMentionsTheNextStreakDay() {
        val line = MascotBrain.reminder(daysAgo(1, 2), today)
        assertEquals(MascotMood.ENCOURAGE, line.mood)
        assertTrue(line.korean.contains("3일"))
        assertEquals(MascotMood.IDLE, MascotBrain.reminder(daysAgo(3), today).mood)
    }

    @Test
    fun reminderTriggerIsTodayOrTomorrow() {
        val now = java.time.LocalDateTime.of(2026, 11, 5, 18, 30)
        assertEquals(now.withHour(20).withMinute(0), ReminderScheduler.nextTrigger(20, 0, now))
        assertEquals(now.plusDays(1).withHour(8).withMinute(15), ReminderScheduler.nextTrigger(8, 15, now))
        // Justo a la hora: ya pasó, toca mañana.
        assertEquals(now.plusDays(1), ReminderScheduler.nextTrigger(18, 30, now))
    }

    @Test
    fun importedFileNamesMapToMoods() {
        assertEquals(MascotMood.ENCOURAGE, MascotMood.fromFileName("Ánimo.PNG"))
        assertEquals(MascotMood.IDLE, MascotMood.fromFileName("pack/idle.webp"))
        assertEquals(MascotMood.HAPPY, MascotMood.fromFileName("happy.png"))
        assertNull(MascotMood.fromFileName("fondo.png"))
    }
}
