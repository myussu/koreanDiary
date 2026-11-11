package com.jorge.koreandiary

import com.jorge.koreandiary.ink.InkOrdering
import com.jorge.koreandiary.ink.InkPoint
import com.jorge.koreandiary.ink.InkStroke
import com.jorge.koreandiary.ink.TextMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkOrderingTest {

    /** Trazo horizontal de [x0] a [x1], escrito en el instante [t]. */
    private fun stroke(x0: Float, x1: Float, t: Long) =
        InkStroke(listOf(InkPoint(x0, 10f, t), InkPoint((x0 + x1) / 2, 12f, t + 20), InkPoint(x1, 10f, t + 40)))

    private fun firstX(s: InkStroke) = s.points.first().x

    @Test
    fun rewrittenWordGoesBackToItsPlace() {
        // "A ... B" escritos primero; luego se borró la palabra del medio y se reescribió (queda al final).
        val a = stroke(0f, 90f, 1_000)
        val b = stroke(200f, 290f, 2_000)
        val rewritten = stroke(110f, 180f, 9_000)
        val ordered = InkOrdering.spatialOrder(listOf(a, b, rewritten))
        assertEquals(listOf(0f, 110f, 200f), ordered.map(::firstX))
    }

    @Test
    fun timesBecomeIncreasingEvenIfSavedBeforeReboot() {
        // Tiempos de antes de reiniciar el celular (mayores) mezclados con nuevos (menores).
        val old = stroke(0f, 90f, 900_000_000)
        val new = stroke(120f, 200f, 5_000)
        val times = InkOrdering.spatialOrder(listOf(new, old)).flatMap { s -> s.points.map { it.t } }
        assertTrue(times.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun overlappingStrokesKeepWritingOrder() {
        // Una sílaba: el segundo trazo se solapa con el primero y debe seguir después.
        val first = stroke(0f, 60f, 100)
        val second = stroke(20f, 40f, 200)
        val ordered = InkOrdering.spatialOrder(listOf(first, second))
        assertEquals(listOf(0f, 20f), ordered.map(::firstX))
    }

    @Test
    fun approximateMatchToleratesOneMisreadSyllable() {
        val written = "교실로돌아가서선생님에게인사했어요삼십분늦어서미안하다고햇어요"
        assertTrue(TextMatch.approxContains(written, "삼십분늦어서미안하다고했어요"))
        assertFalse(TextMatch.approxContains(written, "삼십분늦었어미안하다고했어요".replace("늦었어", "일찍와서")))
        assertFalse(TextMatch.approxContains("가나", "다라"))
    }
}
