package com.jorge.koreandiary.scene.art

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Una mancha de pigmento: el contorno cerrado en píxeles (x0, y0, x1, y1…), la clave de su color en
 * la paleta de acuarela de cada app y cuánto pigmento lleva (0..1). Cada app la rellena; el filtro de
 * acuarela le da el borde corrido, el granulado y el papel.
 */
class Mark(val outline: FloatArray, val color: String, val alpha: Float)

/** Un punto. */
data class Pt(val x: Float, val y: Float)

/**
 * Pincel: arma las manchas de un dibujo. [fade] multiplica todo el pigmento (la entrada suave de un
 * elemento nuevo).
 */
class Painter(private val fade: Float = 1f) {
    val marks = ArrayList<Mark>()

    /**
     * Pincelada que entra fina, carga y sale más fina todavía (la cola del pincel), con el borde un
     * poco irregular. [taper]: qué parte del trazo es entrada y qué parte es salida.
     */
    fun stroke(
        points: List<Pt>,
        width: Float,
        color: String,
        alpha: Float,
        taperIn: Float = 0.15f,
        taperOut: Float = 0.35f,
        wobble: Float = 0.12f,
        seed: Int = 0,
    ) {
        if (points.size < 2 || alpha * fade <= 0.003f) return
        marks += Mark(Brush.strokeOutline(points, width, taperIn, taperOut, wobble, seed), color, alpha * fade)
    }

    /** Toque de pincel: mancha redonda (o aplastada y girada) con el borde que se corre. */
    fun blob(
        cx: Float,
        cy: Float,
        r: Float,
        color: String,
        alpha: Float,
        irregular: Float = 0.28f,
        seed: Int = 0,
        squash: Float = 1f,
        angle: Float = 0f,
    ) {
        if (r <= 0.5f || alpha * fade <= 0.003f) return
        marks += Mark(Brush.blobOutline(cx, cy, r, irregular, seed, squash, angle), color, alpha * fade)
    }

    /** Aguada: una zona amplia de color diluido, con el contorno suavizado. */
    fun wash(points: List<Pt>, color: String, alpha: Float) {
        if (points.size < 3 || alpha * fade <= 0.003f) return
        marks += Mark(Brush.smoothClosed(points), color, alpha * fade)
    }
}

/** Geometría de pincel, sin nada de pantalla: cada app convierte los contornos en su `Path`. */
object Brush {

    /**
     * Suaviza una polilínea (Catmull-Rom), conservando la primera y la última punta. 6 pasos por
     * tramo bastan a este tamaño: la escena rehace todas las pinceladas en cada cuadro.
     */
    fun catmull(points: List<Pt>, steps: Int = 6): List<Pt> {
        if (points.size < 3) return points
        val pts = listOf(points.first()) + points + listOf(points.last())
        val out = ArrayList<Pt>((pts.size - 3) * steps + 1)
        for (i in 1 until pts.size - 2) {
            val p0 = pts[i - 1]
            val p1 = pts[i]
            val p2 = pts[i + 1]
            val p3 = pts[i + 2]
            for (s in 0 until steps) {
                val t = s.toFloat() / steps
                val t2 = t * t
                val t3 = t2 * t
                fun c(a: Float, b: Float, cc: Float, d: Float) =
                    0.5f * ((2 * b) + (-a + cc) * t + (2 * a - 5 * b + 4 * cc - d) * t2 + (-a + 3 * b - 3 * cc + d) * t3)
                out += Pt(c(p0.x, p1.x, p2.x, p3.x), c(p0.y, p1.y, p2.y, p3.y))
            }
        }
        out += points.last()
        return out
    }

    /** Contorno de una pincelada (ver [Painter.stroke]). */
    fun strokeOutline(points: List<Pt>, width: Float, taperIn: Float, taperOut: Float, wobble: Float, seed: Int): FloatArray {
        val rnd = Random(seed)
        val pts = catmull(points)
        val n = pts.size
        val left = FloatArray(n * 2)
        val right = FloatArray(n * 2)
        for (i in 0 until n) {
            val a = pts[max(i - 1, 0)]
            val b = pts[minOf(i + 1, n - 1)]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = hypot(dx, dy).takeIf { it > 0f } ?: 1f
            val nx = -dy / len
            val ny = dx / len
            val s = i.toFloat() / (n - 1)
            val pressure = when {
                taperIn > 0f && s < taperIn -> sin(s / taperIn * PI.toFloat() / 2)
                taperOut > 0f && s > 1 - taperOut -> cos((s - (1 - taperOut)) / taperOut * PI.toFloat() / 2).coerceAtLeast(0f).pow(0.8f)
                else -> 1f
            }
            val w = width * max(pressure, 0.04f) * (1 + wobble * (rnd.nextFloat() - 0.5f))
            left[i * 2] = pts[i].x + nx * w / 2
            left[i * 2 + 1] = pts[i].y + ny * w / 2
            right[i * 2] = pts[i].x - nx * w / 2
            right[i * 2 + 1] = pts[i].y - ny * w / 2
        }
        val out = FloatArray(n * 4)
        left.copyInto(out, 0)
        for (i in 0 until n) {
            val j = n - 1 - i
            out[n * 2 + i * 2] = right[j * 2]
            out[n * 2 + i * 2 + 1] = right[j * 2 + 1]
        }
        return out
    }

    /** Contorno de un toque redondo con el borde irregular (tres ondas de distinta frecuencia). */
    fun blobOutline(cx: Float, cy: Float, r: Float, irregular: Float, seed: Int, squash: Float, angle: Float): FloatArray {
        val rnd = Random(seed)
        val ph = FloatArray(3) { rnd.nextFloat() * 6.2832f }
        val k = 18
        val ca = cos(angle)
        val sa = sin(angle)
        val pts = ArrayList<Pt>(k + 3)
        for (i in 0 until k) {
            val a = i.toFloat() / k * 2 * PI.toFloat()
            val rr = r * (1 + irregular * (0.5f * sin(3 * a + ph[0]) + 0.3f * sin(5 * a + ph[1]) + 0.2f * sin(7 * a + ph[2])))
            val x = cos(a) * rr
            val y = sin(a) * rr * squash
            pts += Pt(cx + x * ca - y * sa, cy + x * sa + y * ca)
        }
        val smooth = catmull(pts + pts.subList(0, 3), steps = 4)
        return flatten(smooth)
    }

    /** Polígono cerrado suavizado (aguadas). */
    fun smoothClosed(points: List<Pt>): FloatArray = flatten(catmull(points, steps = 6))

    private fun flatten(pts: List<Pt>): FloatArray {
        val out = FloatArray(pts.size * 2)
        pts.forEachIndexed { i, p ->
            out[i * 2] = p.x
            out[i * 2 + 1] = p.y
        }
        return out
    }

    /** Raíz con signo cero para valores negativos (evita NaN en distribuciones). */
    internal fun sqrt0(x: Float): Float = if (x <= 0f) 0f else sqrt(x)
}
