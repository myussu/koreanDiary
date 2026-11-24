package com.jorge.koreandiary

import com.jorge.koreandiary.settings.ExplainChoice
import com.jorge.koreandiary.settings.ExplainOption
import com.jorge.koreandiary.settings.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** «Probar con …»: a qué modelo registrado se pasa cuando el de las explicaciones falla. */
class ExplainChoiceTest {

    private val hydrogen = ExplainOption(ProviderId.GEMINI, "gemini-4-hydrogen")
    private val azure = ExplainOption(ProviderId.AZURE, "gpt-mini")
    private val lite = ExplainOption(ProviderId.GEMINI, "gemini-3.5-flash-lite")
    private val flash = ExplainOption(ProviderId.GEMINI, "gemini-3.8-flash")

    @Test
    fun registradosSinRepetirElElegidoPrimero() {
        val options = ExplainChoice.options(hydrogen, "gpt-mini", listOf("gemini-4-hydrogen", "gemini-3.5-flash-lite", "gemini-3.8-flash"))
        assertEquals(listOf(hydrogen, azure, lite, flash), options)
        // Sin Azure configurado ni modelos vacíos.
        assertEquals(listOf(hydrogen, lite), ExplainChoice.options(hydrogen, null, listOf("gemini-4-hydrogen", " ", "gemini-3.5-flash-lite")))
    }

    @Test
    fun elSiguienteDaLaVuelta() {
        val options = listOf(hydrogen, azure, lite)
        // Hydrogen saturado (503): primero Azure, como pidió Jorge.
        assertEquals(azure, ExplainChoice.next(options, hydrogen))
        assertEquals(lite, ExplainChoice.next(options, azure))
        assertEquals(hydrogen, ExplainChoice.next(options, lite))
        assertEquals(hydrogen, ExplainChoice.next(options, flash))
        // Sin otro registrado, no hay botón.
        assertNull(ExplainChoice.next(listOf(hydrogen), hydrogen))
        assertNull(ExplainChoice.next(options, null))
        assertEquals("Azure", azure.label)
        assertEquals("gemini-4-hydrogen", hydrogen.label)
    }
}
