package com.jorge.koreandiary

import com.jorge.koreandiary.ink.DEFAULT_PRESSURE
import com.jorge.koreandiary.ink.InkPoint
import com.jorge.koreandiary.ink.InkSerializer
import com.jorge.koreandiary.ink.InkStroke
import com.jorge.koreandiary.ink.pressureWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkSerializerTest {

    @Test
    fun roundTripKeepsPressure() {
        val stroke = InkStroke(listOf(InkPoint(10f, 20f, 1, 0.2f), InkPoint(30f, 40f, 9, 0.9f)))
        val back = InkSerializer.fromJson(InkSerializer.toJson(listOf(stroke), 100f), 100f).single()
        assertEquals(listOf(0.2f, 0.9f), back.points.map { it.p })
        assertEquals(30f, back.points[1].x, 0.01f)
        assertEquals(9L, back.points[1].t)
    }

    @Test
    fun readsOldFormatWithoutPressure() {
        // Formato v1: [[x, y, t, ...]] sin presión, como estaban guardadas las páginas antes.
        val old = "[[0.1,0.2,5,0.3,0.4,15]]"
        val pts = InkSerializer.fromJson(old, 100f).single().points
        assertEquals(2, pts.size)
        assertEquals(10f, pts[0].x, 0.01f)
        assertTrue(pts.all { it.p == DEFAULT_PRESSURE })
    }

    @Test
    fun mediumPressureKeepsBaseWidth() {
        assertEquals(3f, pressureWidth(3f, DEFAULT_PRESSURE), 0.001f)
        assertTrue(pressureWidth(3f, 0f) < 3f)
        assertTrue(pressureWidth(3f, 1f) > 3f)
        assertEquals(pressureWidth(3f, 1f), pressureWidth(3f, 5f), 0.001f)
    }
}
