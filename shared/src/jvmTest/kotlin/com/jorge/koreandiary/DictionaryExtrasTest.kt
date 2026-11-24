package com.jorge.koreandiary

import com.jorge.koreandiary.dictionary.DictionaryExplain
import com.jorge.koreandiary.dictionary.DictionaryExtrasOf
import com.jorge.koreandiary.dictionary.DictionaryLookup
import com.jorge.koreandiary.dictionary.KoreanDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Lo que el 한국어기초사전 dice además de los sentidos: pronunciación, sinónimos y contrarios, mismo
 * hanja y modismos. Usa los archivos reales del repositorio.
 */
class DictionaryExtrasTest {

    private val dictionary by lazy {
        val dir = listOf("../app/src/main/assets/dictionary", "app/src/main/assets/dictionary").map(::File).first { it.isDirectory }
        KoreanDictionary { name -> File(dir, name).readLines() }
    }

    private fun extras(form: String, pos: String? = null) =
        dictionary.entries(form).first { pos == null || it.pos == pos }.let { e -> DictionaryExtrasOf.of(dictionary, e, dictionary.detail(e)!!) }

    @Test
    fun laPronunciacionSaleSoloSiNoSuenaComoSeEscribe() {
        assertEquals("가치", extras("같이", "부사").pronunciation)
        assertEquals("학꾜", extras("학교").pronunciation)
        assertEquals("조타", extras("좋다", "형용사").pronunciation)
        assertNull(extras("발").pronunciation)
    }

    @Test
    fun sinonimosYContrariosSinRepetir() {
        // 크다 trae «작다» como contrario en varios sentidos: sale una vez, con su primera traducción.
        assertEquals(listOf("Contrarias: 작다 (pequeño)"), extras("크다", "형용사").related.map { it.text })
        val give = extras("주다", "동사").related.map { it.text }
        assertTrue(give.toString(), give.any { it.startsWith("Contrarias: 받다") })
        assertTrue(give.toString(), give.any { it.startsWith("Más respetuosa: 드리다") })
    }

    @Test
    fun otrasPalabrasConElMismoHanja() {
        val hak = extras("학교").hanja.first { it.hanja == "學" }
        assertEquals("배울 학, aprender", hak.meaning)
        assertEquals(4, hak.words.size)
        assertTrue(hak.words.none { it.form == "학교" })
        // Las comunes primero: 대학, 방학… antes que las avanzadas.
        assertTrue(hak.text, hak.text.startsWith("學 (배울 학, aprender): 대학 (universidad)"))
        val silver = extras("은행").hanja.first { it.hanja == "銀" }
        assertTrue(silver.text, silver.words.any { it.form == "은" && it.gloss == "plata" })
    }

    @Test
    fun losModismosDeUnaPalabra() {
        val idioms = extras("발").idioms
        assertEquals(3, idioms.size)
        assertTrue(idioms.toString(), idioms.all { it.form.startsWith("발") && it.meaning.isNotBlank() && !it.proverb })
        assertTrue(dictionary.idiomsOf(dictionary.entries("발").first()).size > 10)
    }

    @Test
    fun tocarUnaPalabraYMasSobreEstoLosTraen() {
        val lookup = DictionaryLookup.lookup(dictionary, "같이")
        assertNotNull(lookup)
        assertEquals("가치", lookup!!.entries.first().extras?.pronunciation)
        val explained = DictionaryExplain.explain(dictionary, "학교", isGrammar = false)
        assertTrue(explained?.extras?.hanja.orEmpty().any { it.hanja == "學" })
    }

    @Test
    fun laTraduccionCortaEsLaPrimera() {
        assertEquals("escuela", DictionaryExtrasOf.shortGloss("escuela, colegio"))
        assertEquals("ir", DictionaryExtrasOf.shortGloss("Ir"))
    }

    @Test
    fun losModismosTienenSuPropioIdYNoPisanASuPalabra() {
        val foot = dictionary.entries("발").first()
        // El detalle de 발 sigue siendo el de la palabra, no el de un modismo con el mismo id original.
        assertTrue(dictionary.detail(foot)!!.senses.first().lemma.orEmpty().contains("pie"))
        val idiom = dictionary.idiomsOf(foot).first()
        assertTrue(idiom.id >= 1_000_000)
        assertNotNull(dictionary.detail(idiom.id))
    }
}
