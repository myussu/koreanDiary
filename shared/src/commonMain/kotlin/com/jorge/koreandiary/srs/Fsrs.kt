package com.jorge.koreandiary.srs

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Implementación de FSRS-6 (Free Spaced Repetition Scheduler), el algoritmo que usa Anki.
 *
 * Modelo DSR: cada ítem tiene
 *  - Estabilidad (S): días hasta que la probabilidad de recordarlo cae al 90 %.
 *  - Dificultad (D): 1 a 10, cuánto cuesta subir la estabilidad.
 *  - Recuperabilidad (R): probabilidad de recordarlo hoy, calculada con S y los días transcurridos.
 *
 * En esta app no hay tarjetas: cada uso en el diario es un repaso.
 * Parámetros por defecto de FSRS-6 (py-fsrs / open-spaced-repetition).
 */
object Fsrs {

    enum class Rating(val value: Int) { AGAIN(1), HARD(2), GOOD(3), EASY(4) }

    data class Memory(
        val stability: Double,
        val difficulty: Double,
        val lastReviewAt: Long,
        val reps: Int,
        val lapses: Int,
    )

    data class Result(val memory: Memory, val dueAt: Long)

    val DEFAULT_WEIGHTS = doubleArrayOf(
        0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666,
        0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658,
        0.1542,
    )

    const val DAY_MS = 86_400_000L
    private const val MIN_STABILITY = 0.001
    private const val MAX_INTERVAL_DAYS = 36_500.0

    private val w = DEFAULT_WEIGHTS
    private val decay = -w[20]
    private val factor = 0.9.pow(1.0 / decay) - 1.0

    /** Probabilidad de recordar el ítem después de [elapsedDays] días. */
    fun retrievability(elapsedDays: Double, stability: Double): Double =
        (1.0 + factor * max(0.0, elapsedDays) / stability).pow(decay)

    fun retrievabilityAt(memory: Memory, now: Long): Double =
        retrievability((now - memory.lastReviewAt).toDouble() / DAY_MS, memory.stability)

    /** Días hasta el próximo repaso para mantener [desiredRetention]. */
    fun intervalDays(stability: Double, desiredRetention: Double = 0.9): Double {
        val days = stability / factor * (desiredRetention.pow(1.0 / decay) - 1.0)
        return days.coerceIn(1.0, MAX_INTERVAL_DAYS)
    }

    /** Aplica un repaso. [previous] es null si es la primera vez que se ve el ítem. */
    fun review(previous: Memory?, rating: Rating, now: Long, desiredRetention: Double = 0.9): Result {
        val g = rating.value
        val memory = if (previous == null) {
            Memory(
                stability = initialStability(g),
                difficulty = clampDifficulty(initialDifficulty(g)),
                lastReviewAt = now,
                reps = 1,
                lapses = if (rating == Rating.AGAIN) 1 else 0,
            )
        } else {
            val elapsedDays = (now - previous.lastReviewAt).toDouble() / DAY_MS
            val s = previous.stability
            val d = previous.difficulty
            val newStability = when {
                elapsedDays < 1.0 -> shortTermStability(s, g)
                rating == Rating.AGAIN -> forgetStability(d, s, retrievability(elapsedDays, s))
                else -> recallStability(d, s, retrievability(elapsedDays, s), g)
            }
            Memory(
                stability = max(MIN_STABILITY, newStability),
                difficulty = nextDifficulty(d, g),
                lastReviewAt = now,
                reps = previous.reps + 1,
                lapses = previous.lapses + if (rating == Rating.AGAIN) 1 else 0,
            )
        }
        val interval = intervalDays(memory.stability, desiredRetention)
        return Result(memory, now + (interval * DAY_MS).toLong())
    }

    private fun initialStability(g: Int): Double = max(w[g - 1], 0.1)

    private fun initialDifficulty(g: Int): Double = w[4] - exp(w[5] * (g - 1)) + 1.0

    private fun clampDifficulty(d: Double): Double = d.coerceIn(1.0, 10.0)

    private fun nextDifficulty(d: Double, g: Int): Double {
        val delta = -w[6] * (g - 3)
        val damped = d + delta * (10.0 - d) / 9.0
        // Reversión a la media hacia la dificultad inicial de "Easy" (sin acotar), como en FSRS-6.
        val reverted = w[7] * initialDifficulty(4) + (1.0 - w[7]) * damped
        return clampDifficulty(reverted)
    }

    private fun recallStability(d: Double, s: Double, r: Double, g: Int): Double {
        val hardPenalty = if (g == 2) w[15] else 1.0
        val easyBonus = if (g == 4) w[16] else 1.0
        return s * (1.0 + exp(w[8]) * (11.0 - d) * s.pow(-w[9]) *
            (exp((1.0 - r) * w[10]) - 1.0) * hardPenalty * easyBonus)
    }

    private fun forgetStability(d: Double, s: Double, r: Double): Double {
        val longTerm = w[11] * d.pow(-w[12]) * ((s + 1.0).pow(w[13]) - 1.0) * exp((1.0 - r) * w[14])
        val shortTermCap = s / exp(w[17] * w[18])
        return min(longTerm, shortTermCap)
    }

    /** Repaso el mismo día: la estabilidad cambia poco. */
    private fun shortTermStability(s: Double, g: Int): Double {
        var increase = exp(w[17] * (g - 3 + w[18])) * s.pow(-w[19])
        if (g >= 3) increase = max(increase, 1.0)
        return s * increase
    }
}
