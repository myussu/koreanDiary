package com.jorge.koreandiary

import com.jorge.koreandiary.kiwi.KiwiAnalyzer
import com.jorge.koreandiary.kiwi.KiwiModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La versión de Kiwi de gradle/libs.versions.toml (la del AAR) es la del modelo que se baja: si se
 * cambia una sin la otra, el modelo no coincide con la librería o su suma de control falla.
 */
class KiwiVersionTest {

    @Test
    fun laLibreriaYElModeloSonDeLaMismaVersion() {
        assertEquals(BuildConfig.KIWI_VERSION, KiwiModel.VERSION)
    }

    @Test
    fun lasEtiquetasVanYVuelven() {
        // VV-I (irregular) se guarda como VV + irregular y vuelve al mismo byte para unir piezas.
        for (tag in listOf("NNG", "VV", "VA", "EP", "EF", "EC", "ETM", "XSV", "JKO")) {
            val byte = KiwiAnalyzer.tagByte(tag, irregular = false)
            assertEquals(tag, KiwiAnalyzer.tagName(byte))
            assertEquals(tag, KiwiAnalyzer.tagName(KiwiAnalyzer.tagByte(tag, irregular = true)))
        }
    }
}
