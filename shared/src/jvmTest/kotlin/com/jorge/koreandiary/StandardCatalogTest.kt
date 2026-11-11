package com.jorge.koreandiary

import com.jorge.koreandiary.data.standard.GrammarKeys
import com.jorge.koreandiary.data.standard.StandardCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StandardCatalogTest {

    // Los tests de JVM corren con shared/ como directorio de trabajo; el catálogo vive en los assets de la app.
    private val catalog = StandardCatalog { name -> File("../app/src/main/assets/standard/$name").readLines() }

    @Test
    fun loadsFullCatalog() {
        assertEquals(336, catalog.grammar.size)
        assertEquals(10635, catalog.words.size)
    }

    @Test
    fun bookNotationMatchesStandardForm() {
        assertEquals("-으니까", catalog.grammar(listOf("A/V-(으)니까, N(이)니까"))?.form)
        assertEquals("-어서", catalog.grammar(listOf("A/V-아서/어서"))?.form)
        assertEquals("-자마자", catalog.grammar(listOf("V-자마자"))?.form)
    }

    @Test
    fun aiNotationMatches() {
        assertEquals("-어서", catalog.grammar(listOf("-아서"))?.form)
        assertEquals("-는 바람에", catalog.grammar(listOf("-는 바람에"))?.form)
        assertNotNull(catalog.grammar(listOf("-고")))
    }

    @Test
    fun endingsAndParticlesDoNotMix() {
        // "N(이)라도" es la partícula 이라도, no la terminación -어도/-라도.
        assertEquals("이라도", catalog.grammar(listOf("N(이)라도"))?.form)
        // La terminación -다가 no es la partícula 에다가.
        assertTrue(catalog.grammar(listOf("V-다가"))!!.form.startsWith("-다가"))
    }

    @Test
    fun wordLookupPrefersLowestLevel() {
        val w = catalog.word("열다")
        assertNotNull(w)
        assertEquals("verbo", w!!.type)
        assertNull(catalog.word("없는말입니다"))
    }

    @Test
    fun keysIgnoreMarkersBracketsAndNotes() {
        assertTrue(GrammarKeys.matches(listOf("V-(으)ㄹ 생각[계획, 예정]이다"), listOf("-을 생각이다")))
        assertTrue(GrammarKeys.matches(listOf("V-고 있다 (착용 동사 / Wearing verbs)"), listOf("-고 있다")))
        assertFalse(GrammarKeys.matches(listOf("N(이)라도"), listOf("-라도")))
    }
}
