package com.jorge.koreandiary

import com.jorge.koreandiary.dictionary.Hangul
import com.jorge.koreandiary.dictionary.KoreanDictionary
import com.jorge.koreandiary.dictionary.PeruSpanish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * El 한국어기초사전 que va en assets/dictionary: verificar, formas de diccionario sin IA, sentidos y
 * el español de Perú. Usa los archivos reales del repositorio.
 */
class DictionaryTest {

    private val dictionary by lazy {
        val dir = listOf("../app/src/main/assets/dictionary", "app/src/main/assets/dictionary").map(::File).first { it.isDirectory }
        KoreanDictionary { name -> File(dir, name).readLines() }
    }

    @Test
    fun verificaQueLaPalabraExiste() {
        listOf("은메달", "올해", "이날", "일으키다", "꿈꾸다", "선선하다", "날씨").forEach { assertTrue(it, dictionary.isWord(it)) }
        assertFalse(dictionary.exists("이난"))
        assertFalse(dictionary.isWord("국귀요"))
        // Las terminaciones están, pero como gramática, no como palabras para el vocabulario.
        assertTrue(dictionary.exists("-느라고"))
        assertFalse(dictionary.isWord("-느라고"))
    }

    /** Palabras comunes y de las conversaciones con Jorge, con su forma de diccionario. */
    private val written = listOf(
        "만났어요" to "만나다", "꿈꿔요" to "꿈꾸다", "좋았어요" to "좋다", "일으키고" to "일으키다", "받았어요" to "받다",
        "시원해요" to "시원하다", "더워요" to "덥다", "갔어요" to "가다", "먹었어요" to "먹다", "했어요" to "하다", "봐요" to "보다",
        "와요" to "오다", "써요" to "쓰다", "몰라요" to "모르다", "커요" to "크다", "지어요" to "짓다", "하얘요" to "하얗다",
        "들었어요" to "듣다", "만들어요" to "만들다", "만드는" to "만들다", "만든" to "만들다", "살아요" to "살다", "사는" to "살다",
        "추워서" to "춥다", "더운" to "덥다", "가까워요" to "가깝다", "걸어요" to "걷다", "나아요" to "낫다", "빨라요" to "빠르다",
        "불러요" to "부르다", "그래요" to "그렇다", "예쁜" to "예쁘다", "바빠요" to "바쁘다", "기다려요" to "기다리다",
        "마셔요" to "마시다", "배워요" to "배우다", "가르쳐요" to "가르치다", "돼요" to "되다", "됐어요" to "되다", "알아요" to "알다",
        "아는" to "알다", "압니다" to "알다", "놀았어요" to "놀다", "공부했어요" to "공부하다", "먹고" to "먹다", "먹으면" to "먹다",
        "먹을" to "먹다", "먹은" to "먹다", "갈" to "가다", "간" to "가다", "가면" to "가다", "가세요" to "가다", "오셨어요" to "오다",
        "읽었는데" to "읽다", "싶어요" to "싶다", "있어요" to "있다", "없었어요" to "없다", "좋아해요" to "좋아하다",
        "맛있어요" to "맛있다", "재미있었어요" to "재미있다", "힘들어요" to "힘들다", "입었어요" to "입다", "누워요" to "눕다",
        "구워요" to "굽다", "고팠다" to "고프다", "덥군요" to "덥다", "늦어" to "늦다", "마시며" to "마시다", "지났다" to "지나다",
        "날씨가" to "날씨", "은메달을" to "은메달", "올해" to "올해", "친구를" to "친구", "학교에서" to "학교", "대회에서" to "대회",
        "물을" to "물", "사진을" to "사진", "한국어를" to "한국어", "어제" to "어제", "아주" to "아주",
    )

    @Test
    fun sacaLaFormaDeDiccionarioSinIa() {
        val misses = written.filter { (w, base) -> dictionary.baseForms(w).none { it.form == base } }
        // En la prueba con estos datos falló solo algún caso raro; se pide al menos 95 %.
        assertTrue("no encontró: $misses", misses.size <= written.size / 20)
        // Casos de la app: 꿈꿔요 (la lectura que salió como 국귀요) y 은메달.
        assertEquals("꿈꾸다", dictionary.baseForms("꿈꿔요.").first().form)
        assertEquals("은메달", dictionary.baseForms("은메달을").first().form)
        // Ambigua: 사는 puede ser 살다 o 사다; las dos salen.
        assertTrue(dictionary.baseForms("사는").map { it.form }.containsAll(listOf("살다", "사다")))
        assertTrue(dictionary.baseForms("이난").isEmpty())
    }

    @Test
    fun sentidosOrigenYEjemplos() {
        val medal = dictionary.entries("은메달").single()
        val detail = dictionary.detail(medal)
        assertNotNull(detail)
        assertTrue(detail!!.origin!!.contains("銀"))
        assertEquals("medalla de plata", detail.senses.first().lemma)
        // Los ejemplos traen la palabra.
        val weather = dictionary.detail(dictionary.entries("날씨").first())!!
        assertTrue(weather.senses.first().examples.isNotEmpty())
        assertTrue(weather.senses.flatMap { it.examples }.all { it.contains("날") })
        // 장 tiene varios homónimos; primero el más común (el contador de cosas planas, nivel inicial).
        val jang = dictionary.entries("장")
        assertTrue(jang.size >= 5)
        assertEquals(1, jang.first().level)
    }

    @Test
    fun espanolDePeru() {
        assertEquals("carro", PeruSpanish.adapt("coche"))
        assertEquals("Celular nuevo", PeruSpanish.adapt("Teléfono móvil nuevo"))
        assertEquals("computadora", PeruSpanish.adapt("ordenador"))
        assertEquals("papa", PeruSpanish.adaptLemma("papa, patata"))
        assertEquals("jugo", PeruSpanish.adaptLemma("jugo, zumo"))
        // Palabras con otro sentido se quedan: «conducir» también es llevar, «piso» también es suelo.
        assertEquals("conducir", PeruSpanish.adapt("conducir"))
        assertEquals("piso", PeruSpanish.adapt("piso"))
        // No toca partes de otras palabras.
        assertEquals("cochera", PeruSpanish.adapt("cochera"))
    }

    @Test
    fun silabas() {
        assertEquals("만난", Hangul.addFinal("만나", 'ㄴ'))
        assertEquals("만드", Hangul.dropFinal("만들"))
        assertTrue("꿈꿔" in Hangul.contractions("꿈꾸어"))
        assertTrue("와" in Hangul.contractions("오아"))
        assertTrue("공부해" in Hangul.contractions("공부하여"))
        assertTrue("써" in Hangul.contractions("쓰어"))
    }
}
