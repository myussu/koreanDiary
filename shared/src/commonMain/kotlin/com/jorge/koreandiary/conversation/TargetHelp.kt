package com.jorge.koreandiary.conversation

import com.jorge.koreandiary.ai.PromptItem
import com.jorge.koreandiary.data.standard.GrammarKeys

/**
 * ¿Qué objetivos de la sesión te mostró una ayuda? Si un objetivo salió en una pista, en «¿Cómo
 * digo?» o lo abriste para verlo, usarlo después cuenta como «difícil» (lo reconociste) y no como
 * «bien» (lo recordaste solo). Funciones puras para poder probarlas.
 */
object TargetHelp {
    /**
     * Los refs de [targets] que aparecen en lo que mostró una ayuda: [texts] (frases en coreano o
     * la clave de una pista), [grammar] (estructuras en notación estándar) y [words] (palabras en
     * forma de diccionario).
     */
    fun shown(targets: List<PromptItem>, texts: List<String>, grammar: List<String> = emptyList(), words: List<String> = emptyList()): Set<String> {
        val wordSet = words.map { it.trim() }.toSet()
        val grammarKeys = GrammarKeys.keys(grammar)
        return targets.filter { t ->
            if (t.kind == "gramática") {
                GrammarKeys.keys(t.label).any { it in grammarKeys }
            } else {
                t.label.trim() in wordSet || texts.any { stemIn(t.label, it) }
            }
        }.map { it.ref }.toSet()
    }

    /**
     * ¿Aparece la raíz de la palabra en el texto? (먹다 → 먹, 등산하다 → 등산). Raíces de una sílaba
     * (가다 → 가) darían falsos positivos, así que solo cuentan si la palabra está entera.
     */
    fun stemIn(label: String, text: String): Boolean {
        val form = label.trim()
        val stem = when {
            form.length > 2 && form.endsWith("하다") -> form.dropLast(2)
            form.length > 1 && form.endsWith("다") -> form.dropLast(1)
            else -> form
        }
        return if (stem.length >= 2) text.contains(stem) else text.contains(form)
    }
}
