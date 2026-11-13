package com.jorge.koreandiary

import com.jorge.koreandiary.listen.NarrationAudio
import com.jorge.koreandiary.listen.PcmAudio
import com.jorge.koreandiary.listen.SegmentStart
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NarrationAudioTest {

    /** [ms] de un tono constante (valor [v]) a [rate] Hz, en PCM de 16 bits. */
    private fun tone(ms: Long, rate: Int, v: Int = 1000): PcmAudio {
        val n = (rate * ms / 1000).toInt()
        val bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            bytes[2 * i] = (v and 0xFF).toByte()
            bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return PcmAudio(bytes, rate)
    }

    @Test
    fun joinSkipsMissingSegmentsAndMarksWhereEachStarts() {
        val joined = NarrationAudio.join(listOf(tone(1000, 24_000), null, tone(500, 24_000)))!!
        assertEquals(24_000, joined.audio.rate)
        assertEquals(listOf(SegmentStart(0, 0), SegmentStart(2, 1000 + NarrationAudio.GAP_MS)), joined.starts)
        assertEquals(1000 + NarrationAudio.GAP_MS + 500, joined.audio.durationMs)
        assertNull(NarrationAudio.join(listOf(null, null)))
    }

    @Test
    fun joinConvertsOtherRatesToTheFirst() {
        val joined = NarrationAudio.join(listOf(tone(1000, 24_000), tone(1000, 16_000)))!!
        assertEquals(24_000, joined.audio.rate)
        // Un segundo a 16 kHz sigue durando un segundo a 24 kHz.
        assertEquals(2000 + NarrationAudio.GAP_MS, joined.audio.durationMs)
    }

    @Test
    fun resampleKeepsAConstantSignalAndNegativeValues() {
        val up = NarrationAudio.resample(tone(100, 16_000, v = -1234), 24_000)
        assertEquals(24_000 * 100 / 1000, up.data.size / 2)
        for (i in 0 until up.data.size / 2) {
            val s = ((up.data[2 * i + 1].toInt()) shl 8) or (up.data[2 * i].toInt() and 0xFF)
            assertEquals(-1234, s)
        }
        val same = tone(10, 24_000)
        assertArrayEquals(same.data, NarrationAudio.resample(same, 24_000).data)
    }

    @Test
    fun keepsTheFiveMostRecentAndEveryStarred() {
        val entries = (1L..8L).map { it to (it == 2L) }
        assertEquals(listOf(3L, 1L), NarrationAudio.toDelete(entries))
        assertEquals(emptyList<Long>(), NarrationAudio.toDelete(entries.take(4)))
    }

    @Test
    fun segmentAtFollowsThePosition() {
        val starts = listOf(SegmentStart(0, 0), SegmentStart(2, 1600), SegmentStart(3, 4000))
        assertEquals(0, NarrationAudio.segmentAt(starts, 0))
        assertEquals(0, NarrationAudio.segmentAt(starts, 1599))
        assertEquals(2, NarrationAudio.segmentAt(starts, 1600))
        assertEquals(3, NarrationAudio.segmentAt(starts, 99_999))
    }
}
