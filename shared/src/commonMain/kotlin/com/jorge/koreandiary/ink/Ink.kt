package com.jorge.koreandiary.ink

import org.json.JSONArray
import org.json.JSONObject
import java.util.IdentityHashMap
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
    /**
     * La caja que ocupa el trazo (todo en 0 si no tiene puntos). Se calcula una sola vez: un trazo
     * no cambia, se reemplaza por otro. La usan el renglón del trazo, el borrador y el lienzo, que
     * antes recorrían todos los puntos cada vez.
     */
    val minX: Float
    val maxX: Float
    val minY: Float
    val maxY: Float

    init {
        var x0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (p in points) {
            if (p.x < x0) x0 = p.x
            if (p.x > x1) x1 = p.x
            if (p.y < y0) y0 = p.y
            if (p.y > y1) y1 = p.y
        }
        val empty = points.isEmpty()
        minX = if (empty) 0f else x0
        maxX = if (empty) 0f else x1
        minY = if (empty) 0f else y0
        maxY = if (empty) 0f else y1
    }

    val centerY: Float get() = (minY + maxY) / 2f

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
        val maxX = strokes.maxOfOrNull { it.maxX } ?: return null
        if (maxX <= width * 1.02f) return null
        return scale(strokes, width * 0.98f / maxX)
    }

    /**
     * Huella de los trazos de un renglón: cuántos son y, de cada uno, cuántos puntos tiene y cuándo
     * empezó y terminó. No cambia al guardar la página y volver a abrirla, ni al escalarla o bajarla
     * de renglón; sí cambia si se agrega, se borra o se reescribe un trazo. Sirve para no volver a
     * leer con el reconocedor un renglón que ya se leyó.
     */
    fun lineKey(strokes: List<InkStroke>): Long {
        var key = strokes.size.toLong()
        for (s in strokes) {
            val points = s.points
            key = key * 31 + points.size
            if (points.isNotEmpty()) {
                key = key * 31 + points.first().t
                key = key * 31 + points.last().t
            }
        }
        return key
    }
}

object InkSerializer {
    fun toJson(strokes: List<InkStroke>, width: Float): String {
        val w = if (width > 0f) width else 1f
        return page(strokes) { strokeJson(it, w) }
    }

    /** La página: `{"v":2,"s":[trazo, trazo, ...]}`, con el texto de cada trazo que da [strokeText]. */
    internal inline fun page(strokes: List<InkStroke>, strokeText: (InkStroke) -> String): String {
        val out = StringBuilder(32 + strokes.size * 256)
        out.append("{\"v\":2,\"s\":[")
        var first = true
        for (s in strokes) {
            if (!first) out.append(',')
            first = false
            out.append(strokeText(s))
        }
        return out.append("]}").toString()
    }

    /** Un trazo: `[x, y, t, p, x, y, t, p, ...]` con x e y relativos al ancho [w]. */
    internal fun strokeJson(stroke: InkStroke, w: Float): String {
        val arr = JSONArray()
        for (p in stroke.points) {
            arr.put(round4(p.x / w))
            arr.put(round4(p.y / w))
            arr.put(p.t)
            arr.put(Math.round(p.p * 100.0) / 100.0)
        }
        return arr.toString()
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
 * Serializa la tinta de una página recordando el texto de cada trazo. La página se guarda después de
 * cada pausa al escribir: sin esto se convertían a texto todos sus trazos cada vez (una página llena
 * son decenas de miles de puntos); así solo se convierte lo nuevo y el resto se copia.
 *
 * Los trazos se recuerdan por identidad (no cambian, se reemplazan), como las formas del lienzo, y se
 * olvidan los que ya no están en la página. Da el mismo texto que [InkSerializer.toJson].
 */
class InkJsonWriter {
    private var width = -1f
    private var cache = IdentityHashMap<InkStroke, String>()

    /** Puede llamarse desde varios hilos (el guardado de la página y el guardado final al salir). */
    @Synchronized
    fun toJson(strokes: List<InkStroke>, width: Float): String {
        val w = if (width > 0f) width else 1f
        // Las coordenadas se guardan relativas al ancho: con otro ancho, lo recordado ya no sirve.
        if (w != this.width) {
            cache = IdentityHashMap()
            this.width = w
        }
        val next = IdentityHashMap<InkStroke, String>(strokes.size * 2)
        val json = InkSerializer.page(strokes) { s ->
            (cache[s] ?: InkSerializer.strokeJson(s, w)).also { next[s] = it }
        }
        cache = next
        return json
    }
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
    /** Solo letras y dígitos: sin espacios, saltos de línea ni puntuación. */
    fun normalize(s: String): String = s.filter { it.isLetterOrDigit() }

    /**
     * Mínimo de ediciones (insertar, borrar, cambiar un carácter) para que [needle]
     * aparezca como subcadena de [haystack] (algoritmo de Sellers).
     */
    fun substringDistance(haystack: String, needle: String): Int =
        if (needle.isEmpty()) 0 else endDistances(haystack, needle).min()

    /**
     * Última fila de Sellers: para cada posición de [haystack], el mínimo de ediciones para que
     * [needle] aparezca terminando justo ahí.
     */
    private fun endDistances(haystack: String, needle: String): IntArray {
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
        return prev
    }

    /** Ediciones para convertir [a] entera en [b] entera (Levenshtein). */
    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j - 1] + cost, prev[j] + 1, cur[j - 1] + 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** 1 error tolerado cada 8 caracteres (al menos 1 si la frase tiene 4 o más). */
    private fun allowedErrors(length: Int): Int = if (length < 4) 0 else maxOf(1, length / 8)

    fun approxContains(haystack: String, needle: String): Boolean {
        if (needle.isEmpty()) return false
        return substringDistance(haystack, needle) <= allowedErrors(needle.length)
    }

    /**
     * ¿[written] (todo lo que se lee ahora en la página) trae la [suggestion] reescrita?
     *
     * No basta con que la sugerencia aparezca con la tolerancia de [approxContains]: casi todas las
     * correcciones cambian un solo carácter, así que el original, todavía sin corregir, ya cae dentro
     * de esa tolerancia. Se pide evidencia nueva: un fragmento que se parezca más a la sugerencia que
     * a cualquier cosa que ya estaba en la página cuando se revisó, según lo que leyó la app
     * ([recognizedBefore]) y lo que confirmó el estudiante ([confirmedBefore]).
     *
     * Así no cuentan ni el original ni una reescritura que repite el mismo error, y sí cuenta una
     * reescritura correcta con una sílaba mal leída en otra parte. Si la sílaba mal leída es justo la
     * corregida no se puede saber, y no cuenta (para eso está "Hecho").
     *
     * Si la sugerencia ya estaba tal cual en la página (p. ej. una partícula suelta), cuenta cuando
     * aparece más veces que antes.
     */
    fun isRewritten(written: String, suggestion: String, recognizedBefore: String, confirmedBefore: String): Boolean {
        val text = normalize(written)
        val target = normalize(suggestion)
        if (text.isEmpty() || target.isEmpty()) return false
        val before = listOf(normalize(recognizedBefore), normalize(confirmedBefore)).filter { it.isNotEmpty() }
        val allowed = allowedErrors(target.length)
        val ends = endDistances(text, target)
        for (end in 1..text.length) {
            if (ends[end] > allowed) continue
            // Un fragmento a [allowed] ediciones de la sugerencia mide lo mismo que ella, ± [allowed].
            for (length in (target.length - allowed)..(target.length + allowed)) {
                val start = end - length
                if (length <= 0 || start < 0) continue
                val window = text.substring(start, end)
                val toSuggestion = distance(window, target)
                if (toSuggestion > allowed) continue
                // Sin texto guardado de la revisión no hay con qué comparar: vale la tolerancia de siempre.
                val toBefore = before.minOfOrNull { substringDistance(it, window) } ?: Int.MAX_VALUE
                if (toSuggestion < toBefore) return true
            }
        }
        if (before.isEmpty()) return false
        return occurrences(text, target) > before.maxOf { occurrences(it, target) }
    }

    private fun occurrences(text: String, target: String): Int {
        var count = 0
        var at = text.indexOf(target)
        while (at >= 0) {
            count++
            at = text.indexOf(target, at + target.length)
        }
        return count
    }
}
