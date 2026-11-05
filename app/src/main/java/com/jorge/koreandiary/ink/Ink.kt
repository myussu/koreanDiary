package com.jorge.koreandiary.ink

import org.json.JSONArray
import kotlin.math.floor

data class InkPoint(val x: Float, val y: Float, val t: Long)

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
 * Serializa trazos de forma compacta: [[x,y,t, x,y,t, ...], ...].
 * Las coordenadas se guardan normalizadas al ancho del lienzo (0..1 en x),
 * para que la página se vea igual si cambia el tamaño de pantalla.
 */
object InkSerializer {
    fun toJson(strokes: List<InkStroke>, width: Float): String {
        val w = if (width > 0f) width else 1f
        val root = JSONArray()
        for (s in strokes) {
            val arr = JSONArray()
            for (p in s.points) {
                arr.put(round4(p.x / w))
                arr.put(round4(p.y / w))
                arr.put(p.t)
            }
            root.put(arr)
        }
        return root.toString()
    }

    fun fromJson(json: String?, width: Float): List<InkStroke> {
        if (json.isNullOrBlank() || width <= 0f) return emptyList()
        return try {
            val root = JSONArray(json)
            (0 until root.length()).mapNotNull { i ->
                val arr = root.optJSONArray(i) ?: return@mapNotNull null
                val points = ArrayList<InkPoint>(arr.length() / 3)
                var j = 0
                while (j + 2 < arr.length()) {
                    points += InkPoint(
                        x = (arr.getDouble(j) * width).toFloat(),
                        y = (arr.getDouble(j + 1) * width).toFloat(),
                        t = arr.getLong(j + 2),
                    )
                    j += 3
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
