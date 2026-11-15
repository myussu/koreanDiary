package com.jorge.koreandiary

import com.jorge.koreandiary.features.Features
import com.jorge.koreandiary.features.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun flowsAreWellFormed() {
        val titles = Features.flows.map { it.title }
        assertEquals("Recorridos repetidos", titles.size, titles.toSet().size)
        assertTrue(Features.flows.isNotEmpty())
        for (f in Features.flows) {
            assertTrue("«${f.title}» tiene menos de dos pasos", f.steps.size >= 2)
            assertTrue("«${f.title}» sin plataforma", f.platforms.isNotEmpty())
            // Desde la lista se salta al recorrido por el nombre del grupo: tiene que existir.
            assertNotNull("«${f.title}» apunta a «${f.group}», que no está en la lista", Features.groups.firstOrNull { it.title == f.group })
            assertEquals(f.group, Features.flowFor(f.group)?.group)
            for (step in f.steps) {
                assertTrue("Paso sin título en «${f.title}»", step.title.isNotBlank())
                assertTrue("«${step.title}» sin texto", step.text.isNotBlank())
                val buttons = step.buttons + step.aside?.buttons.orEmpty()
                assertTrue("Botón vacío en «${step.title}»", buttons.all { it.isNotBlank() })
                step.aside?.let { a -> assertTrue("Recuadro vacío en «${step.title}»", a.buttons.isNotEmpty() || !a.text.isNullOrBlank()) }
            }
        }
        // El diario tiene el recorrido del celular y el de la PC: cada versión salta al suyo.
        assertEquals("Diario", Features.flowFor("Diario", Platform.PHONE)?.title)
        assertEquals("En la PC", Features.flowFor("Diario", Platform.DESKTOP)?.title)
        // Cada grupo de la lista tiene su recorrido (Progreso ya no es un grupo: está en Estudio › «Resumen»).
        val withFlow = Features.flows.map { it.group }.toSet()
        assertEquals(Features.groups.map { it.title }.toSet(), withFlow)
        assertTrue(Features.groups.none { it.title == "Progreso" })
        // Un recorrido solo del celular sale en gris en la PC, igual que la lista.
        val phoneOnly = Features.flows.first { it.platforms == setOf(Platform.PHONE) }
        assertNull(phoneOnly.missingLabel(Platform.PHONE))
        assertEquals("solo en celular", phoneOnly.missingLabel(Platform.DESKTOP))
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
