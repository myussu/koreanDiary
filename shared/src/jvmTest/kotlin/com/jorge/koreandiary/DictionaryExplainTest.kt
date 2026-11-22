package com.jorge.koreandiary

import com.jorge.koreandiary.dictionary.DictionaryExplain
import com.jorge.koreandiary.dictionary.GrammarMatch
import com.jorge.koreandiary.dictionary.KoreanDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * «Más sobre esto» y las tarjetas de sugerencias desde el 한국어기초사전 (sin IA), y dónde va una
 * gramática en una oración. Usa los archivos reales del repositorio.
 */
class DictionaryExplainTest {

    private val dictionary by lazy {
        val dir = listOf("../app/src/main/assets/dictionary", "app/src/main/assets/dictionary").map(::File).first { it.isDirectory }
        KoreanDictionary { name -> File(dir, name).readLines() }
    }

    private fun found(sentence: String, form: String, expression: Boolean = false): String? =
        GrammarMatch.find(sentence, form, expression)?.let { sentence.substring(it.start, it.end) }

    @Test
    fun encuentraLaGramaticaEnLaOracion() {
        assertEquals("읽을 수 있어요", found("저는 한자도 읽을 수 있어요.", "-을 수 있다", true))
        assertEquals("뛸 수 있다", found("나는 백 미터를 십오 초에 뛸 수 있다.", "-ㄹ 수 있다", true))
        assertEquals("추워서", found("날씨가 추워서 집에 있었다.", "-어서"))
        assertEquals("가서", found("집에 가서 쉬었다.", "-아서"))
        assertEquals("먹기로 해요", found("좋아요. 그럼 저녁에 먹기로 해요.", "-기로 하다", true))
        assertEquals("가야 해", found("시간이 늦어서 이만 집에 가야 해.", "-아야 하다", true))
        assertEquals("가게 됐다", found("이사를 가게 됐다.", "-게 되다", true))
        assertEquals("속았다", found("나는 동생의 거짓말에 또 속았다.", "-았-"))
        assertEquals("끝났겠다", found("수업이 벌써 끝났겠다.", "-겠-"))
        assertEquals("볼까요", found("오늘 오후에 영화 볼까요?", "-ㄹ까요", true))
        assertEquals("먹습니다", found("저는 밥을 먹습니다.", "-ㅂ니다"))
        assertEquals("먹고", found("불고기를 먹고 싶어요.", "-고"))
        assertEquals("밝혀졌다", found("비밀이 드디어 밝혀졌다.", "-어지다", true))
        assertEquals("가르쳐 주세요", found("좀 가르쳐 주세요.", "-어 주다", true))
        assertEquals("마시지 마세요", found("술은 마시지 마세요.", "-지 말다", true))
        assertEquals("걷다 보니", found("길을 따라 걷다 보니 연못이 나왔다.", "-다가 보다", true))
        // Una partícula: el hueco es solo ella.
        assertEquals("이랑", found("유민이랑 나랑은 동급생이다.", "이랑"))
        assertEquals("이라고", found("나는 “안녕”이라고 인사했다.", "이라고"))
    }

    @Test
    fun noLaVeDondeNoEsta() {
        // Las otras líneas del diálogo que trae el diccionario no la usan: no se muestran.
        assertNull(found("이사하면 방에 책상하고 옷장, 침대를 놓을 거예요.", "-을 수 있다", true))
        assertNull(found("겉옷을 어디에 둘까요?", "-어서"))
        assertNull(found("오늘 불고기를 먹으면 어때요?", "-기로 하다", true))
        // 에서 no es -어서, 없다 no es 있다, 있다 no es pasado, 책을 no termina en -어.
        assertNull(found("학교에서 공부했어요.", "-어서"))
        assertNull(found("읽을 수 없어요.", "-을 수 있다", true))
        assertNull(found("책이 있다.", "-았-"))
        assertNull(found("책을 주세요.", "-어 주다", true))
        assertNull(found("불고기가 맛있다", "-고"))
    }

    @Test
    fun casiTodosLosEjemplosDelDiccionarioLaUsan() {
        // En las pruebas del 2026-11-22: 98 % de los ejemplos de las 1.656 gramáticas; lo que no, eran
        // líneas de diálogo que no la usan.
        val samples = listOf("-느라고", "-을 수 있다", "-고 싶다", "-지만", "-는데", "-으면", "-습니다", "-어요", "-았-", "이랑", "-기로 하다", "-게 되다")
        var total = 0
        var hits = 0
        for (form in samples) {
            val entry = dictionary.entries(form).first { it.grammar }
            for (ex in dictionary.detail(entry)!!.senses.flatMap { it.examples }) {
                total++
                if (GrammarMatch.find(ex, form, entry.pos == "품사 없음") != null) hits++
            }
        }
        assertTrue("$hits de $total", hits * 100 >= total * 85)
    }

    @Test
    fun notacionDeLaAppALaDelDiccionario() {
        assertEquals(listOf("-을 수 있다", "을 수 있다", "-ㄹ 수 있다", "ㄹ 수 있다"), DictionaryExplain.grammarCandidates("-(으)ㄹ 수 있다"))
        assertEquals(listOf("-어서", "어서", "-아서", "아서"), DictionaryExplain.grammarCandidates("A/V-아/어서"))
        assertEquals(listOf("-느라고", "느라고"), DictionaryExplain.grammarCandidates("V-느라고"))
        assertEquals(listOf("이랑", "-이랑"), DictionaryExplain.grammarCandidates("N이랑"))
        assertTrue("-을까요" in DictionaryExplain.grammarCandidates("-(으)ㄹ까요?"))
        assertTrue("-는다고" in DictionaryExplain.grammarCandidates("-ㄴ/는다고"))
        assertTrue("-기 때문" in DictionaryExplain.grammarCandidates("-기 때문에"))
    }

    @Test
    fun comoSeUne() {
        assertEquals("Tras consonante: -을 수 있다 · tras vocal o ㄹ: -ㄹ 수 있다", DictionaryExplain.attachNote(listOf("-을 수 있다", "-ㄹ 수 있다")))
        assertEquals("Si la última vocal es ㅏ u ㅗ: -아서 · con las demás: -어서 · con 하다: 해서", DictionaryExplain.attachNote(listOf("-어서", "-아서")))
        assertEquals("Tras consonante: 이랑 · tras vocal: 랑", DictionaryExplain.attachNote(listOf("이랑", "랑")))
        assertEquals("Tras consonante: 은 · tras vocal: 는", DictionaryExplain.attachNote(listOf("는", "은")))
        assertEquals("Tras consonante: -습니다 · tras vocal o ㄹ: -ㅂ니다", DictionaryExplain.attachNote(listOf("-습니다", "-ㅂ니다")))
        assertNull(DictionaryExplain.attachNote(listOf("-느라고")))
        assertTrue("-ㅂ니다" in DictionaryExplain.counterparts("-습니다"))
        assertTrue("-아요" in DictionaryExplain.counterparts("-어요"))
        assertTrue("랑" in DictionaryExplain.counterparts("이랑"))
    }

    @Test
    fun explicaUnaPalabraSinIa() {
        val e = DictionaryExplain.explain(dictionary, "은메달", false)
        assertNotNull(e)
        e!!
        assertTrue(e.fromDictionary)
        assertEquals("medalla de plata", e.meaning)
        assertEquals("은 銀 + medal", e.structure)
        assertTrue(e.examples.isNotEmpty() && e.examples.all { it.korean.contains("은메달") && it.english == null })
        // Español de Perú y verbos con sus formas.
        assertEquals("celular", DictionaryExplain.explain(dictionary, "휴대폰", false)!!.meaning)
        val hot = DictionaryExplain.explain(dictionary, "덥다", false)!!
        assertTrue(hot.conjugation!!.contains("더워"))
        // No existe: la app le pregunta a la IA.
        assertNull(DictionaryExplain.explain(dictionary, "이난", false))
    }

    @Test
    fun eligeElSentidoDeTuLista() {
        // 장 más común: el contador de hojas; si tu lista dice «mercado», el 장 de mercado.
        assertTrue(DictionaryExplain.explain(dictionary, "장", false)!!.meaning.contains("conteo"))
        assertTrue(DictionaryExplain.explain(dictionary, "장", false, "mercado")!!.meaning.contains("mercado"))
        assertEquals("disculpa, perdón, exculpación", DictionaryExplain.explain(dictionary, "사과", false, "disculpa")!!.meaning)
        assertEquals("manzana", DictionaryExplain.explain(dictionary, "사과", false)!!.meaning)
    }

    @Test
    fun explicaUnaGramaticaSinIa() {
        val e = DictionaryExplain.explain(dictionary, "-(으)ㄹ 수 있다", true)!!
        assertEquals("-을 수 있다", e.form)
        assertEquals("Tras consonante: -을 수 있다 · tras vocal o ㄹ: -ㄹ 수 있다", e.conjugation)
        assertTrue(e.examples.size >= 2 && e.examples.all { it.korean.contains("수 있") })
        // Tu lista dice una forma; el diccionario trae la otra.
        assertEquals("Tras consonante: -습니다 · tras vocal o ㄹ: -ㅂ니다", DictionaryExplain.explain(dictionary, "-습니다", true)!!.conjugation)
        // «은/는» son las partículas, no las terminaciones -은/-는.
        assertTrue(DictionaryExplain.explain(dictionary, "은/는", true)!!.meaning.startsWith("Posposición"))
        assertEquals("때문", DictionaryExplain.explain(dictionary, "N 때문에", true)!!.form)
        // La etiqueta del diccionario, en palabras de la app.
        assertTrue(DictionaryExplain.explain(dictionary, "-아/어요", true)!!.meaning.startsWith("(cortés, 해요체)"))
    }

    @Test
    fun etiquetasDeRegistro() {
        assertEquals("(muy formal, 합쇼체) Desinencia…", DictionaryExplain.cleanDefinition("(TRATAMIENTO HONORÍFICO MÁXIMO) Desinencia…"))
        assertEquals("(figurado) Algo.", DictionaryExplain.cleanDefinition("(FIGURADO) Algo."))
        assertEquals("Sin etiqueta.", DictionaryExplain.cleanDefinition("Sin etiqueta."))
    }
}
