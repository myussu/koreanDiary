package com.jorge.koreandiary

import com.jorge.koreandiary.ink.DEFAULT_PRESSURE
import com.jorge.koreandiary.ink.InkJsonWriter
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
    fun theWriterThatRemembersStrokesGivesTheSameText() {
        val a = stroke(10f, 20f, 30f, 40f)
        val b = InkStroke(listOf(InkPoint(500f, 60f, 1_234_567, 0.31f), InkPoint(512.5f, 61f, 1_234_575, 1f)))
        val c = stroke(700f, 900f, 705f, 910f, 720f, 915f)
        val writer = InkJsonWriter()
        // Como al escribir: la página crece trazo a trazo, se borra uno y se deshace.
        val pages = listOf(emptyList(), listOf(a), listOf(a, b), listOf(a, b, c), listOf(a, c), listOf(a, b, c), listOf(c))
        for (page in pages) {
            assertEquals(InkSerializer.toJson(page, 1000f), writer.toJson(page, 1000f))
        }
        // Con otro ancho de hoja las coordenadas guardadas cambian: lo recordado no se reutiliza.
        assertEquals(InkSerializer.toJson(listOf(a, b), 500f), writer.toJson(listOf(a, b), 500f))
        assertEquals(InkSerializer.toJson(listOf(a, b), 1000f), writer.toJson(listOf(a, b), 1000f))
    }

    @Test
    fun savedInkIsReadBackWithEveryStrokeInOrder() {
        val page = listOf(stroke(10f, 20f, 30f, 40f), stroke(100f, 50f, 300f, 80f, 310f, 90f))
        val json = InkJsonWriter().toJson(page, 1000f)
        // La racha busca `[[` en la tinta vieja al migrar: una página con trazos siempre lo trae.
        assertTrue("[[" in json)
        assertTrue("[[" !in InkSerializer.toJson(emptyList(), 1000f))
        val back = InkSerializer.fromJson(json, 1000f)
        assertEquals(listOf(2, 3), back.map { it.points.size })
        assertEquals(300f, back[1].points[1].x, 0.06f)
        assertEquals(1L, back[1].points[1].t)
    }

    @Test
    fun aLineKeepsItsKeyUntilItsStrokesChange() {
        val a = InkStroke(listOf(InkPoint(10f, 20f, 100), InkPoint(30f, 40f, 140), InkPoint(35f, 42f, 150)))
        val b = InkStroke(listOf(InkPoint(60f, 20f, 900), InkPoint(80f, 25f, 930)))
        val line = listOf(a, b)
        val key = InkOps.lineKey(line)
        // Guardar y volver a abrir, girar el celular (escala) o bajar el renglón no cambian la huella.
        assertEquals(key, InkOps.lineKey(InkSerializer.fromJson(InkSerializer.toJson(line, 1000f), 1000f)))
        assertEquals(key, InkOps.lineKey(InkOps.scale(line, 0.5f)))
        assertEquals(key, InkOps.lineKey(line.map { s -> InkStroke(s.points.map { it.copy(y = it.y + 64f) }) }))
        // Agregar, borrar o reescribir un trazo, sí.
        val c = InkStroke(listOf(InkPoint(60f, 20f, 2000), InkPoint(80f, 25f, 2030)))
        assertTrue(key != InkOps.lineKey(listOf(a)))
        assertTrue(key != InkOps.lineKey(listOf(a, b, c)))
        assertTrue(key != InkOps.lineKey(listOf(a, c)))
    }

    @Test
    fun aStrokeKnowsTheBoxItOccupies() {
        val s = stroke(100f, 50f, 300f, 80f, 20f, 65f)
        assertEquals(20f, s.minX, 0f)
        assertEquals(300f, s.maxX, 0f)
        assertEquals(50f, s.minY, 0f)
        assertEquals(80f, s.maxY, 0f)
        assertEquals(65f, s.centerY, 0f)
        assertEquals(1, s.lineIndex(64f))
        // Sin puntos no hay caja: queda en el origen, como antes.
        assertEquals(0f, InkStroke(emptyList()).centerY, 0f)
    }

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

    /** Un trazo que terminó un poco fuera del borde se recorta él solo: la página no se achica. */
    @Test
    fun aStrokeThatSlippedOffTheEdgeDoesNotShrinkThePage() {
        val inside = stroke(10f, 10f, 900f, 20f)
        val slipped = stroke(800f, 200f, 1080f, 210f)
        val fitted = InkOps.fitToWidth(listOf(inside, slipped), 1000f)!!
        // El que estaba dentro queda igual (el mismo objeto: no se vuelve a guardar distinto).
        assertTrue(fitted[0] === inside)
        assertEquals(1000f, fitted[1].points.maxOf { it.x }, 0.001f)
        assertEquals(200f, fitted[1].points.first().y, 0.001f)
        // Ya recortado, no se toca más.
        assertNull(InkOps.fitToWidth(fitted, 1000f))
    }
}
