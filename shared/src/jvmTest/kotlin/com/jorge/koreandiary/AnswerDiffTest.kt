package com.jorge.koreandiary

import com.jorge.koreandiary.review.AnswerDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dónde está el error de una respuesta casi correcta del repaso rápido. */
class AnswerDiffTest {

    private fun diff(written: String, expected: String) = AnswerDiff.compare(written, expected)

    @Test
    fun unaConsonanteDeAbajo() {
        val r = diff("먹엇어요", "먹었어요")!!
        assertEquals(setOf(1), r.wrong)
        // Con la tarjeta abierta no da la letra, solo dónde mirar.
        assertEquals("Revisa «엇»: la consonante de abajo.", AnswerDiff.where(r))
        assertEquals("En «었» va ㅆ abajo, no ㅅ.", AnswerDiff.explain(r))
    }

    @Test
    fun vocalesQueSuenanIgual() {
        val r = diff("됬어요", "됐어요")!!
        // Con «Cómo suena»: 됬어요 y 됐어요 suenan igual del todo.
        assertEquals("Revisa «됬»: la vocal. Suena igual: es de ortografía.", AnswerDiff.where(r))
        assertEquals("En «됐» la vocal es ㅙ, no ㅚ (suenan igual). Las dos se pronuncian [돼써요].", AnswerDiff.explain(r))
        assertTrue(AnswerDiff.explain(diff("가게에서", "가개에서")!!).contains("(suenan igual)"))
        // 갔어요 y 갓어요: la sílaba suena casi igual, pero antes de una vocal no ([가써요] y [가서요]).
        assertEquals("Revisa «갓»: la consonante de abajo.", AnswerDiff.where(diff("갓어요", "갔어요")!!))
    }

    @Test
    fun escritoComoSuena() {
        assertEquals("Lo escribiste como suena: «머거» se escribe «먹어». Las dos se pronuncian [머거요].", AnswerDiff.explain(diff("머거요", "먹어요")!!))
        assertEquals("Lo escribiste como suena: «업서» se escribe «없어». Las dos se pronuncian [업써서].", AnswerDiff.explain(diff("업서서", "없어서")!!))
        // ㅌ antes de 이 suena ㅊ: 같이 [가치].
        assertEquals("Lo escribiste como suena: «가치» se escribe «같이». Las dos se pronuncian [가치].", AnswerDiff.explain(diff("가치", "같이")!!))
        assertEquals("«머거» está escrito como suena.", AnswerDiff.where(diff("머거요", "먹어요")!!))
        assertEquals("Lo escribiste como suena: «일거» se escribe «읽어». Las dos se pronuncian [일거요].", AnswerDiff.explain(diff("일거요", "읽어요")!!))
        // La ㅎ de abajo no suena antes de una vocal.
        assertEquals("En «좋» lleva ㅎ abajo (suenan igual). Las dos se pronuncian [조아요].", AnswerDiff.explain(diff("조아요", "좋아요")!!))
    }

    @Test
    fun consonantesQueSuenanIgualAlFinal() {
        // 낫/낮 al final suenan [낟]; 학꾜/학교: la ㄱ tras consonante suena tensa.
        assertTrue(AnswerDiff.explain(diff("낫", "낮")!!).contains("(suenan igual)."))
        assertTrue(AnswerDiff.explain(diff("학꾜", "학교")!!).endsWith("(suenan igual). Las dos se pronuncian [학꾜]."))
        // 멍는 por 먹는: antes de ㄴ, la ㄱ suena ㅇ.
        assertTrue(AnswerDiff.explain(diff("멍는", "먹는")!!).contains("(suenan igual)."))
        // Antes de una vocal no suenan igual: 엇어 [어서] y 었어 [어써].
        assertTrue(!AnswerDiff.explain(diff("먹엇어요", "먹었어요")!!).contains("suenan"))
    }

    @Test
    fun faltaOSobra() {
        val missing = diff("먹었요", "먹었어요")!!
        assertEquals("Falta algo después de «었».", AnswerDiff.where(missing))
        assertEquals("Falta «어».", AnswerDiff.explain(missing))
        assertEquals("Falta «요» al final.", AnswerDiff.explain(diff("먹었어", "먹었어요")!!))
        val extra = diff("먹었어서요", "먹었어요")!!
        assertEquals(setOf(3), extra.wrong)
        assertEquals("Sobra «서».", AnswerDiff.explain(extra))
    }

    @Test
    fun sinEspaciosNiSignos() {
        assertNull(diff("할 수 있어요", "할수있어요."))
        val r = diff("할 수 잇어요", "할 수 있어요")!!
        // Lo que se resalta en lo leído, con sus espacios: «잇» está en la posición 4.
        assertEquals(setOf(4), AnswerDiff.wrongIn("할 수 잇어요", r))
    }

    @Test
    fun siSeParecenPocoNoHayLetraQueCorregir() {
        assertNull(diff("갔어요", "먹었어요"))
        assertNull(diff("물", "책"))
        assertNull(diff("먹었어요", "먹었어요"))
    }
}
