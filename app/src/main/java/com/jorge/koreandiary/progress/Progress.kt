package com.jorge.koreandiary.progress

import com.jorge.koreandiary.data.db.ItemType
import com.jorge.koreandiary.data.db.MemoryState
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Un día del calendario de constancia. */
data class HeatDay(val date: LocalDate, val pages: Int, val future: Boolean)

/** Resumen de memoria FSRS de un tipo de ítem. */
data class MemorySummary(
    /** Ítems con al menos un repaso calificado. */
    val practiced: Int,
    /** Estabilidad de 21 días o más: los recuerdas aunque pasen semanas. */
    val solid: Int,
    /** Ya les toca repaso (vencen hoy o antes). */
    val dueToday: Int,
)

/** Cálculos de la pantalla de progreso, sin Android para poder probarlos. */
object Progress {
    const val SOLID_STABILITY_DAYS = 21.0

    /**
     * Semanas de lunes a domingo, de la más antigua a la actual (que contiene [today]).
     * Cada semana es una columna de 7 días; los días posteriores a hoy van marcados como futuros.
     */
    fun heatmap(pagesPerDay: Map<LocalDate, Int>, today: LocalDate, weeks: Int): List<List<HeatDay>> {
        val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks((weeks - 1).toLong())
        return List(weeks) { w ->
            List(7) { d ->
                val date = start.plusDays((w * 7 + d).toLong())
                HeatDay(date, pagesPerDay[date] ?: 0, date.isAfter(today))
            }
        }
    }

    fun summarize(states: List<MemoryState>, itemType: String, endOfToday: Long): MemorySummary {
        val practiced = states.filter { it.itemType == itemType && it.reps > 0 }
        return MemorySummary(
            practiced = practiced.size,
            solid = practiced.count { it.stability >= SOLID_STABILITY_DAYS },
            dueToday = practiced.count { it.dueAt <= endOfToday },
        )
    }

    /** Lo que más te cuesta: más olvidos primero; a igualdad, más difícil según FSRS. */
    fun hardest(states: List<MemoryState>, limit: Int): List<MemoryState> =
        states.filter { it.lapses > 0 && (it.itemType == ItemType.WORD || it.itemType == ItemType.GRAMMAR) }
            .sortedWith(compareByDescending<MemoryState> { it.lapses }.thenByDescending { it.difficulty })
            .take(limit)
}
