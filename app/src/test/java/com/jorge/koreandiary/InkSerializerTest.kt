package com.jorge.koreandiary

import com.jorge.koreandiary.ink.DEFAULT_PRESSURE
import com.jorge.koreandiary.ink.InkOps
import com.jorge.koreandiary.ink.InkPoint
import com.jorge.koreandiary.ink.InkSerializer
import com.jorge.koreandiary.ink.InkStroke
import com.jorge.koreandiary.ink.pressureWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun stroke(vararg xy: Float) = InkStroke(xy.toList().chunked(2).mapIndexed { i, (x, y) -> InkPoint(x, y, i.toLong()) })

    @Test
    fun rotatingKeepsTheSamePageWhenTheWidthChanges() {
        // Guardar con un ancho y cargar con otro (lo que pasa al girar) escala igual x e y.
        val page = listOf(stroke(100f, 50f, 300f, 80f))
        val loaded = InkSerializer.fromJson(InkSerializer.toJson(page, 1000f), 500f)
        val expected = InkOps.scale(page, 0.5f).first().points
        expected.zip(loaded.first().points).forEach { (e, l) ->
            assertEquals(e.x, l.x, 0.01f)
            assertEquals(e.y, l.y, 0.01f)
        }
    }

    @Test
    fun inkOutsideThePageIsShrunkToFit() {
        assertNull(InkOps.fitToWidth(listOf(stroke(10f, 10f, 1000f, 20f)), 1000f))
        val fitted = InkOps.fitToWidth(listOf(stroke(10f, 10f, 2000f, 40f)), 1000f)!!
        val maxX = fitted.flatMap { it.points }.maxOf { it.x }
        assertTrue(maxX <= 1000f)
        // Se escala todo por igual: la forma se conserva.
        assertEquals(0.49f, fitted.first().points.last().x / 2000f, 0.001f)
        assertEquals(40f * 0.49f, fitted.first().points.last().y, 0.01f)
    }
}
