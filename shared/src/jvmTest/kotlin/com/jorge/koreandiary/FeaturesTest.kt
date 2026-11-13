package com.jorge.koreandiary

import com.jorge.koreandiary.features.Features
import com.jorge.koreandiary.features.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturesTest {

    private fun texts(query: String) = Features.search(query).flatMap { g -> g.features.map { it.text } }

    @Test
    fun theListIsWellFormed() {
        val titles = Features.groups.map { it.title }
        assertEquals("Grupos repetidos", titles.size, titles.toSet().size)
        for (g in Features.groups) {
            assertTrue("«${g.title}» está vacío", g.features.isNotEmpty())
            for (f in g.features) {
                assertTrue("Línea sin plataforma: ${f.text}", f.platforms.isNotEmpty())
                assertTrue("Línea vacía en «${g.title}»", f.text.isNotBlank())
            }
        }
        val all = Features.groups.flatMap { g -> g.features.map { it.text } }
        assertEquals("Líneas repetidas", all.size, all.toSet().size)
        // La lista misma tiene que estar en las dos versiones.
        assertTrue(Features.groups.first().features.any { it.text.startsWith("«?»") && it.platforms == Platform.entries.toSet() })
    }

    @Test
    fun searchIgnoresCaseAccentsAndSigns() {
        assertTrue(texts("como digo").any { it.contains("«¿Cómo digo?»") })
        assertEquals(texts("podcast"), texts("PÓDCAST"))
        assertTrue(texts("podcast").isNotEmpty())
        assertTrue(texts("revision").any { it.contains("revisión") })
        assertTrue(texts("s pen").any { it.contains("S Pen") })
    }

    @Test
    fun allWordsMustMatchAndTheGroupTitleCounts() {
        // «escenas» está en el título del grupo: trae todas sus líneas.
        val all = Features.groups.first { it.title == "Escenas" }.features.size
        assertEquals(all, Features.search("escenas").first { it.title == "Escenas" }.features.size)
        // Dos palabras: título + línea.
        val streak = texts("escenas racha")
        assertEquals(1, streak.size)
        assertTrue(streak[0].contains("racha"))
        assertTrue(texts("racha xyzzy").isEmpty())
    }

    @Test
    fun blankQueryReturnsEverything() {
        assertEquals(Features.groups.map { it.title }, Features.search("  ¿? ").map { it.title })
        assertEquals(Features.total, Features.search("").sumOf { it.features.size })
    }

    @Test
    fun missingLabelSaysWhereItWorks() {
        val phoneOnly = Features.groups.flatMap { it.features }.first { it.platforms == setOf(Platform.PHONE) }
        assertNull(phoneOnly.missingLabel(Platform.PHONE))
        assertEquals("solo en celular", phoneOnly.missingLabel(Platform.DESKTOP))
        val desktopOnly = Features.groups.flatMap { it.features }.first { it.platforms == setOf(Platform.DESKTOP) }
        assertEquals("solo en PC", desktopOnly.missingLabel(Platform.PHONE))
    }
}
