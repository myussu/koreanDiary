package com.jorge.koreandiary.listen

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Audio PCM de 16 bits, mono, little-endian. */
class PcmAudio(val data: ByteArray, val rate: Int) {
    val durationMs: Long get() = data.size / 2 * 1000L / rate.coerceAtLeast(1)

    fun slice(range: IntRange): PcmAudio = PcmAudio(data.copyOfRange(range.first, range.last + 1), rate)
}

/** Lee un WAV (lo que devuelve el TTS de Gemini y el de Android). Si no tiene cabecera, es PCM crudo. */
object Wav {
    fun parse(bytes: ByteArray, defaultRate: Int = 24_000): PcmAudio {
        if (bytes.size < 12 || ascii(bytes, 0) != "RIFF" || ascii(bytes, 8) != "WAVE") return PcmAudio(evenLength(bytes), defaultRate)
        var rate = defaultRate
        var channels = 1
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = ascii(bytes, pos)
            val size = int32(bytes, pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> if (body + 8 <= bytes.size) {
                    channels = int16(bytes, body + 2).coerceAtLeast(1)
                    rate = int32(bytes, body + 4).takeIf { it in 4_000..192_000 } ?: defaultRate
                }
                "data" -> {
                    // En streaming el tamaño puede venir como 0 o 0xFFFFFFFF: se toma hasta el final.
                    val end = if (size <= 0 || body.toLong() + size > bytes.size) bytes.size else body + size
                    val pcm = evenLength(bytes.copyOfRange(body, end))
                    return PcmAudio(if (channels == 2) downmix(pcm) else pcm, rate)
                }
            }
            if (size < 0) break
            pos = body + size + (size and 1)
        }
        throw IllegalArgumentException("WAV sin datos de audio.")
    }

    private fun ascii(b: ByteArray, at: Int) = if (at + 4 <= b.size) String(b, at, 4, Charsets.US_ASCII) else ""
    private fun int16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun int32(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or (b[at + 3].toInt() shl 24)

    private fun evenLength(b: ByteArray) = if (b.size % 2 == 0) b else b.copyOf(b.size - 1)

    private fun downmix(stereo: ByteArray): ByteArray {
        val out = ByteArray(stereo.size / 2 / 2 * 2)
        var o = 0
        var i = 0
        while (i + 3 < stereo.size && o + 1 < out.size) {
            val l = sample(stereo, i)
            val r = sample(stereo, i + 2)
            val m = (l + r) / 2
            out[o] = m.toByte()
            out[o + 1] = (m shr 8).toByte()
            o += 2
            i += 4
        }
        return out
    }
}

internal fun sample(b: ByteArray, at: Int): Int = ((b[at + 1].toInt() shl 8) or (b[at].toInt() and 0xFF)).toShort().toInt()

/**
 * Corta un audio con varios segmentos (un solo pedido al TTS, para cuidar la cuota diaria) en sus partes,
 * usando los silencios largos que deja el TTS entre segmentos (`<long pause>`). Se eligen los silencios
 * más largos que además caen cerca de donde se espera cada corte según el largo del texto.
 */
object SilenceSplitter {
    private const val FRAME_MS = 20
    /** Un silencio más corto no es candidato (entre oraciones suele haber ~200–400 ms). */
    private const val MIN_SILENCE_MS = 240

    /** Rangos en bytes de cada parte. [weights]: largo esperado de cada una (p. ej. sílabas). */
    fun split(audio: PcmAudio, weights: List<Int>): List<IntRange> {
        val parts = weights.size
        val total = audio.data.size and 1.inv()
        if (parts <= 1 || total < 4) return listOf(0 until total)
        val frameBytes = (audio.rate * FRAME_MS / 1000) * 2
        val frames = total / frameBytes
        if (frames < parts * 2) return proportional(total, weights)

        val rms = DoubleArray(frames) { f -> rms(audio.data, f * frameBytes, frameBytes) }
        val sorted = rms.sorted()
        val floor = sorted[(frames * 0.10).toInt().coerceIn(0, frames - 1)]
        val loud = sorted[(frames * 0.90).toInt().coerceIn(0, frames - 1)]
        // Sin contraste entre voz y silencio no hay pausas que buscar.
        if (loud < 200 || loud <= floor * 1.5) return proportional(total, weights)
        val threshold = floor + (loud - floor) * 0.12

        // Silencios internos (sin contar el inicio ni el final).
        data class Gap(val mid: Int, val lengthMs: Int)
        val gaps = mutableListOf<Gap>()
        var start = -1
        for (f in 0 until frames) {
            val silent = rms[f] <= threshold
            if (silent && start < 0) start = f
            if ((!silent || f == frames - 1) && start >= 0) {
                val end = if (silent) f + 1 else f
                val len = (end - start) * FRAME_MS
                if (start > 0 && end < frames && len >= MIN_SILENCE_MS) gaps += Gap((start + end) / 2, len)
                start = -1
            }
        }
        if (gaps.size < parts - 1) return proportional(total, weights)

        // Dónde debería caer cada corte, en frames, según el peso de cada parte.
        val sum = weights.sumOf { it.coerceAtLeast(1) }.toDouble()
        val expected = DoubleArray(parts - 1)
        var acc = 0.0
        for (k in 0 until parts - 1) {
            acc += weights[k].coerceAtLeast(1)
            expected[k] = acc / sum * frames
        }
        val avgPart = frames.toDouble() / parts

        // Programación dinámica: cortes en orden que maximizan (largo del silencio) − (distancia a lo esperado).
        fun score(g: Gap, k: Int) = g.lengthMs / 100.0 - abs(g.mid - expected[k]) / (avgPart * 0.15)
        val n = gaps.size
        val cuts = parts - 1
        val best = Array(cuts) { DoubleArray(n) { Double.NEGATIVE_INFINITY } }
        val from = Array(cuts) { IntArray(n) { -1 } }
        for (j in 0 until n) best[0][j] = score(gaps[j], 0)
        for (k in 1 until cuts) {
            var runMax = Double.NEGATIVE_INFINITY
            var runArg = -1
            for (j in 0 until n) {
                if (j > 0 && best[k - 1][j - 1] > runMax) {
                    runMax = best[k - 1][j - 1]
                    runArg = j - 1
                }
                if (runArg >= 0) {
                    best[k][j] = runMax + score(gaps[j], k)
                    from[k][j] = runArg
                }
            }
        }
        var j = (0 until n).maxByOrNull { best[cuts - 1][it] } ?: return proportional(total, weights)
        if (best[cuts - 1][j] == Double.NEGATIVE_INFINITY) return proportional(total, weights)
        val chosen = IntArray(cuts)
        for (k in cuts - 1 downTo 0) {
            chosen[k] = gaps[j].mid
            if (k > 0) j = from[k][j]
        }
        val bounds = listOf(0) + chosen.map { it * frameBytes } + listOf(total)
        return bounds.zipWithNext { a, b -> a until b }
    }

    private fun proportional(total: Int, weights: List<Int>): List<IntRange> {
        val sum = weights.sumOf { it.coerceAtLeast(1) }.toDouble()
        var acc = 0.0
        val bounds = mutableListOf(0)
        for (k in 0 until weights.size - 1) {
            acc += weights[k].coerceAtLeast(1)
            bounds += ((acc / sum * total).toInt() and 1.inv())
        }
        bounds += total
        return bounds.zipWithNext { a, b -> a until b }
    }

    private fun rms(b: ByteArray, offset: Int, length: Int): Double {
        var sum = 0.0
        var count = 0
        var i = offset
        val end = minOf(offset + length, b.size - 1)
        while (i < end) {
            val v = sample(b, i).toDouble()
            sum += v * v
            count++
            i += 2
        }
        return if (count == 0) 0.0 else sqrt(sum / count)
    }
}

/**
 * Tonos cortos para usar la app sin mirar la pantalla. Se generan (no hay archivos de audio):
 * cada uno tiene un dibujo distinto para reconocerlo sin pensar.
 */
enum class Earcon { START, TURN, RESUME, WORKING, ERROR, END }

object Earcons {
    fun render(e: Earcon, rate: Int = 24_000): ByteArray = when (e) {
        // Subida de dos notas: "empezamos".
        Earcon.START -> tones(rate, listOf(523.25 to 120, 0.0 to 30, 783.99 to 200))
        // Dos notas agudas, rápidas: "te toca hablar".
        Earcon.TURN -> tones(rate, listOf(880.0 to 90, 0.0 to 40, 1318.5 to 140))
        // Una nota breve: "sigue la narración".
        Earcon.RESUME -> tones(rate, listOf(659.25 to 110), volume = 0.18)
        // Toque grave y suave, se repite mientras algo carga o reconecta.
        Earcon.WORKING -> tones(rate, listOf(392.0 to 70), volume = 0.12)
        // Bajada grave: "algo falló".
        Earcon.ERROR -> tones(rate, listOf(311.13 to 160, 0.0 to 40, 233.08 to 260), volume = 0.28)
        // Tres notas que bajan: "terminamos".
        Earcon.END -> tones(rate, listOf(783.99 to 120, 0.0 to 30, 659.25 to 120, 0.0 to 30, 523.25 to 260))
    }

    /** Notas (frecuencia en Hz, duración en ms); 0 Hz = silencio. Envolvente suave para que no chasquee. */
    private fun tones(rate: Int, notes: List<Pair<Double, Int>>, volume: Double = 0.22): ByteArray {
        val total = notes.sumOf { it.second } * rate / 1000
        val out = ByteArray(total * 2)
        var n = 0
        for ((freq, ms) in notes) {
            val len = ms * rate / 1000
            for (i in 0 until len) {
                val t = i.toDouble() / rate
                val v = if (freq <= 0.0) 0.0 else {
                    val attack = (i / (rate * 0.008)).coerceAtMost(1.0)
                    val decay = exp(-3.0 * i / len)
                    // Fundamental más un armónico suave: suena a "ding", no a pitido.
                    (sin(2 * PI * freq * t) + 0.3 * sin(4 * PI * freq * t)) / 1.3 * attack * decay * volume
                }
                val s = (v * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out[n * 2] = s.toByte()
                out[n * 2 + 1] = (s shr 8).toByte()
                n++
            }
        }
        return out
    }
}
