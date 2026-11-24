package com.jorge.koreandiary

import com.jorge.koreandiary.scene.SceneDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** La escena viva con Kiwi: la palabra tiene que ser el sustantivo, no otra cosa que empieza igual. */
class SceneKiwiTest {

    private fun ids(text: String) = SceneDictionary.detect(text, KiwiFixtures.analyzer).map { it.id }

    @Test
    fun soloElSustantivoTraeDibujo() {
        // 산 de comprar (사 + ㄴ) no es la montaña; 산에, sí. «엄마가 산» no está en la lista de
        // frases a mano (notScene): antes traía una montaña.
        assertTrue(ids("어제 산 옷이 예뻐요.").none { it == "mountain" })
        assertTrue(SceneDictionary.detect("엄마가 산 케이크가 맛있었어요.").any { it.id == "mountain" })
        assertTrue(ids("엄마가 산 케이크가 맛있었어요.").none { it == "mountain" })
        assertTrue(ids("주말에 산에 갔어요.").contains("mountain"))
        // 해 del sol sí; 해 de hacer no.
        assertTrue(ids("해가 떴어요.").contains("sun"))
        assertTrue(ids("숙제를 해 봤어요.").none { it == "sun" })
    }

    @Test
    fun lasRaicesSiguenSiendoRaices() {
        // 맑 es raíz (맑다): con Kiwi tiene que ser el adjetivo.
        val withKiwi = SceneDictionary.detect("하늘이 맑아요.", KiwiFixtures.analyzer).map { it.id }
        val without = SceneDictionary.detect("하늘이 맑아요.").map { it.id }
        assertEquals(without, withKiwi)
    }
}
