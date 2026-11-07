package com.jorge.koreandiary.ink

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor

/** [p]: presión del lápiz (0–1). El dedo y las páginas antiguas usan un valor medio. */
data class InkPoint(val x: Float, val y: Float, val t: Long, val p: Float = DEFAULT_PRESSURE)

const val DEFAULT_PRESSURE = 0.5f

/**
 * Grosor según la presión, como un pincel: con la presión media (0.5) da exactamente [base],
 * así las páginas antiguas y el dedo se ven igual que antes. Va de 0.4× a 1.6× del grosor base.
 */
fun pressureWidth(base: Float, pressure: Float): Float =
    base * (0.4f + 1.2f * pressure.coerceIn(0f, 1f)).coerceIn(0.4f, 1.6f)

data class InkStroke(val points: List<InkPoint>) {
    val centerY: Float
        get() {
            if (points.isEmpty()) return 0f
            var min = Float.MAX_VALUE
            var max = -Float.MAX_VALUE
            for (p in points) {
                if (p.y < min) min = p.y
                if (p.y > max) max = p.y
            }
            return (min + max) / 2f
        }

    fun lineIndex(lineHeight: Float): Int = floor(centerY / lineHeight).toInt().coerceAtLeast(0)
}

/**
 * Serializa trazos de forma compacta. Formato actual (v2):
 *   {"v": 2, "s": [[x, y, t, p,  x, y, t, p, ...], ...]}  (p = presión)
 * Formato anterior (v1), que se sigue leyendo: [[x, y, t, ...], ...] sin presión.
 * Las coordenadas se guardan normalizadas al ancho del lienzo (0..1 en x),
 * para que la página se vea igual si cambia el tamaño de pantalla.
 */
/** Transformaciones de toda la tinta de una página. */
object InkOps {
    /** Escala x e y por igual (la tinta se guarda relativa al ancho de la hoja). */
    fun scale(strokes: List<InkStroke>, factor: Float): List<InkStroke> =
        if (factor == 1f) strokes
        else strokes.map { s -> InkStroke(s.points.map { it.copy(x = it.x * factor, y = it.y * factor) }) }

    /**
     * Si algo se sale de la hoja por la derecha (páginas guardadas al girar el celular antes de
     * que la hoja tuviera ancho fijo), lo achica todo para que vuelva a verse. null si ya entra.
     */
    fun fitToWidth(strokes: List<InkStroke>, width: Float): List<InkStroke>? {
        if (width <= 0f) return null
        val maxX = strokes.maxOfOrNull { s -> s.points.maxOfOrNull { it.x } ?: 0f } ?: return null
        if (maxX <= width * 1.02f) return null
        return scale(strokes, width * 0.98f / maxX)
    }
}

object InkSerializer {
    fun toJson(strokes: List<InkStroke>, width: Float): String {
        val w = if (width > 0f) width else 1f
        val list = JSONArray()
        for (s in strokes) {
            val arr = JSONArray()
            for (p in s.points) {
                arr.put(round4(p.x / w))
                arr.put(round4(p.y / w))
                arr.put(p.t)
                arr.put(Math.round(p.p * 100.0) / 100.0)
            }
            list.put(arr)
        }
        return JSONObject().put("v", 2).put("s", list).toString()
    }

    fun fromJson(json: String?, width: Float): List<InkStroke> {
        if (json.isNullOrBlank() || width <= 0f) return emptyList()
        return try {
            val trimmed = json.trimStart()
            val (list, stride) = if (trimmed.startsWith("{")) {
                val o = JSONObject(trimmed)
                (o.optJSONArray("s") ?: JSONArray()) to (if (o.optInt("v", 2) >= 2) 4 else 3)
            } else {
                JSONArray(trimmed) to 3
            }
            (0 until list.length()).mapNotNull { i ->
                val arr = list.optJSONArray(i) ?: return@mapNotNull null
                val points = ArrayList<InkPoint>(arr.length() / stride)
                var j = 0
                while (j + stride - 1 < arr.length()) {
                    points += InkPoint(
                        x = (arr.getDouble(j) * width).toFloat(),
                        y = (arr.getDouble(j + 1) * width).toFloat(),
                        t = arr.getLong(j + 2),
                        p = if (stride == 4) arr.getDouble(j + 3).toFloat() else DEFAULT_PRESSURE,
                    )
                    j += stride
                }
                if (points.isEmpty()) null else InkStroke(points)
            }
        } catch (e: org.json.JSONException) {
            emptyList()
        }
    }

    private fun round4(v: Float): Double = Math.round(v * 10000.0) / 10000.0
}

/**
 * Prepara los trazos de una línea para el reconocedor, que depende del ORDEN de escritura.
 *
 * Al borrar y reescribir una palabra, sus trazos nuevos quedan al final aunque estén a la mitad
 * de la línea; y tras reiniciar el celular los tiempos guardados pueden quedar invertidos.
 * Aquí se agrupan los trazos que se solapan en horizontal (sílabas/palabras), los grupos se
 * ordenan de izquierda a derecha y dentro de cada grupo se respeta el orden original.
 * Luego se reescriben los tiempos para que sean crecientes, conservando la duración de cada trazo.
 */
object InkOrdering {
    private const val GAP_MS = 60L

    fun spatialOrder(strokes: List<InkStroke>): List<InkStroke> {
        val valid = strokes.filter { it.points.isNotEmpty() }
        if (valid.size <= 1) return retime(valid)
        val bounds = valid.mapIndexed { i, s -> Triple(i, s.points.minOf { it.x }, s.points.maxOf { it.x }) }
            .sortedBy { it.second }
        val clusters = mutableListOf<MutableList<Int>>()
        var clusterMaxX = Float.NEGATIVE_INFINITY
        for ((i, minX, maxX) in bounds) {
            if (clusters.isEmpty() || minX > clusterMaxX) {
                clusters += mutableListOf(i)
                clusterMaxX = maxX
            } else {
                clusters.last() += i
                clusterMaxX = maxOf(clusterMaxX, maxX)
            }
        }
        // Dentro de cada grupo: tramos escritos de corrido (índices consecutivos que avanzan o se solapan),
        // ordenados de izquierda a derecha. Así una sílaba reescrita en medio de una palabra queda en su lugar.
        val ordered = clusters.flatMap { c ->
            val segments = mutableListOf<MutableList<Int>>()
            var lastIdx = -2
            var segMaxX = Float.NEGATIVE_INFINITY
            for (idx in c.sorted()) {
                val minX = valid[idx].points.minOf { it.x }
                val maxX = valid[idx].points.maxOf { it.x }
                if (segments.isEmpty() || idx != lastIdx + 1 || minX > segMaxX) {
                    segments += mutableListOf(idx)
                    segMaxX = maxX
                } else {
                    segments.last() += idx
                    segMaxX = maxOf(segMaxX, maxX)
                }
                lastIdx = idx
            }
            segments.sortedBy { seg -> seg.minOf { valid[it].points.minOf { p -> p.x } } }
                .flatten().map { valid[it] }
        }
        return retime(ordered)
    }

    private fun retime(strokes: List<InkStroke>): List<InkStroke> {
        var clock = 0L
        return strokes.map { s ->
            val t0 = s.points.first().t
            var last = clock - 1
            val pts = s.points.mapIndexed { j, p ->
                val rel = (p.t - t0).takeIf { it in 0..10_000 } ?: (j * 8L)
                val t = maxOf(clock + rel, last + 1)
                last = t
                p.copy(t = t)
            }
            clock = last + GAP_MS
            InkStroke(pts)
        }
    }
}

/** Búsqueda aproximada: tolera errores puntuales del reconocimiento de escritura. */
object TextMatch {
    /**
     * Mínimo de ediciones (insertar, borrar, cambiar un carácter) para que [needle]
     * aparezca como subcadena de [haystack] (algoritmo de Sellers).
     */
    fun substringDistance(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var prev = IntArray(haystack.length + 1) // fila 0: cero, la coincidencia puede empezar en cualquier parte
        for (i in 1..needle.length) {
            val cur = IntArray(haystack.length + 1)
            cur[0] = i
            for (j in 1..haystack.length) {
                val cost = if (needle[i - 1] == haystack[j - 1]) 0 else 1
                cur[j] = minOf(prev[j - 1] + cost, prev[j] + 1, cur[j - 1] + 1)
            }
            prev = cur
        }
        return prev.min()
    }

    /** 1 error tolerado cada 8 caracteres (al menos 1 si la frase tiene 4 o más). */
    fun approxContains(haystack: String, needle: String): Boolean {
        if (needle.isEmpty()) return false
        val allowed = if (needle.length < 4) 0 else maxOf(1, needle.length / 8)
        return substringDistance(haystack, needle) <= allowed
    }
}
