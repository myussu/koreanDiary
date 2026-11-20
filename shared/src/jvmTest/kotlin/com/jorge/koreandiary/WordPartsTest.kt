package com.jorge.koreandiary

import com.jorge.koreandiary.ai.WordParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** El desglose de la IA: las partes juntas tienen que formar la palabra. */
class WordPartsTest {

    @Test
    fun elDesgloseInventadoNoCuadra() {
        // Lo que dijo la IA el 2026-11-20.
        assertEquals(false, WordParts.fits("일으키다", "일다 «levantarse» + -이- (causativo) + -기다 (sufijo causativo)"))
    }

    @Test
    fun losDesglosesCorrectosCuadran() {
        assertEquals(true, WordParts.fits("일으키다", "일다 «surgir» + -으키- (sufijo causativo)"))
        assertEquals(true, WordParts.fits("일으키다", "일- «surgir» + -으키- «hacer que» + -다"))
        assertEquals(true, WordParts.fits("은메달", "은 «plata» + 메달 «medalla»"))
        assertEquals(true, WordParts.fits("꿈꾸다", "꿈꾸다 = 꿈 «sueño» + 꾸다 «soñar»; 꿈 viene de 꾸다 + -ㅁ"))
        assertEquals(true, WordParts.fits("불편", "불(不) «no» + 편(便) «comodidad»"))
        assertEquals(true, WordParts.fits("-느라고", "-느라 + -고"))
        assertEquals(true, WordParts.fits("들어가다", "들다 «entrar» + -어 + 가다 «ir»"))
    }

    @Test
    fun loQueNoSePuedeComprobar() {
        assertNull(WordParts.fits("꿈", "de 꾸다 + -ㅁ (sustantivador)")) // jamo suelto
        assertNull(WordParts.fits("V-느라고", "-느라 + -고"))
        assertNull(WordParts.fits("-(으)ㄹ 수 있다", "-(으)ㄹ + 수 + 있다"))
        assertNull(WordParts.fits("사과", "palabra simple"))
        assertNull(WordParts.fits("은메달", "Partes fijas: A + B."))
    }

    @Test
    fun lasIrregularesSalenDudosas() {
        // Correcto, pero no cuadra letra por letra: por eso se marca y no se esconde.
        assertEquals(false, WordParts.fits("고마움", "고맙다 «agradecer» + -음"))
    }
}
