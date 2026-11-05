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
