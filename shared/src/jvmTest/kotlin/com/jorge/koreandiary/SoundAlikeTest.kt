package com.jorge.koreandiary

import com.jorge.koreandiary.pronunciation.SoundAlike
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Por revisar»: la palabra del diccionario que suena como lo mal escrito. */
class SoundAlikeTest {

    private val alike = SoundAlike(KiwiFixtures.dictionary)

    @Test
    fun lasQueSeEscribieronComoSuenan() {
        for ((written, expected) in listOf(
            "설겆이" to "설거지", "금새" to "금세", "몇일" to "며칠", "오랫만" to "오랜만", "맛잇다" to "맛있다", "어의없다" to "어이없다",
        )) {
            val first = alike.suggestions(written).firstOrNull()
            assertEquals(written, expected, first?.entry?.form)
            assertTrue(written, first!!.same)
        }
        // Sin la ㅎ: 괜찬다 [괜찬다] y 괜찮다 [괜찬타] suenan casi igual.
        val near = alike.suggestions("괜찬다").first()
        assertEquals("괜찮다", near.entry.form)
        assertTrue(!near.same)
    }

    @Test
    fun loQueYaExisteNoTieneSugerencia() {
        assertTrue(alike.suggestions("괜찮다").isEmpty())
        assertTrue(alike.suggestions("가치").isEmpty())
        assertEquals(emptyList<String>(), alike.suggestions("쀍뛟").map { it.entry.form })
    }
}
