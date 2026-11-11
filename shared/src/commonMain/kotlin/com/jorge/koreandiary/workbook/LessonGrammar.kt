package com.jorge.koreandiary.workbook

import com.jorge.koreandiary.data.db.GrammarPoint
import com.jorge.koreandiary.data.db.MemoryState
import com.jorge.koreandiary.data.standard.GrammarKeys
import java.text.Normalizer

/**
 * Búsqueda en tu lista de gramática que ignora la notación del libro: guiones, A/V/N, (으) y espacios.
 * "텐데" encuentra "A/V-(으)ㄹ 텐데", "을 텐데" también; "causa" busca en el significado.
 */
object GrammarSearch {
    fun matches(query: String, g: GrammarPoint): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        val queryKeys = bareKeys(listOf(q))
        if (queryKeys.isNotEmpty()) {
            val hay = bareKeys(forms(g))
            if (queryKeys.any { k -> hay.any { it.contains(k) } }) return true
        }
        val plain = fold(q)
        return plain.isNotEmpty() && (forms(g).any { fold(it).contains(plain) } || g.meaning?.let { fold(it).contains(plain) } == true)
    }

    /** Coincidencias exactas primero, luego las agregadas más recientemente. */
    fun search(query: String, all: List<GrammarPoint>, limit: Int = 20): List<GrammarPoint> {
        val queryKeys = bareKeys(listOf(query.trim()))
        return all.filter { matches(query, it) }
            .sortedWith(compareByDescending<GrammarPoint> { g -> bareKeys(forms(g)).any { it in queryKeys } }.thenByDescending { it.id })
            .take(limit)
    }

    private fun forms(g: GrammarPoint) = listOf(g.pattern) + g.variants.split(" | ").filter { it.isNotBlank() }

    private fun bareKeys(forms: List<String>) = GrammarKeys.keys(forms).map { it.substringAfter(':') }.toSet()

    private fun fold(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "")
        .filterNot { it.isWhitespace() || it == '-' || it == '~' }
}

/** Contexto corto para la IA: la gramática de la lección y las anteriores que estudiaste. */
object LessonContext {
    const val MAX_PREVIOUS = 30

    /**
     * Gramáticas ya estudiadas para combinar con la de la lección: primero las que FSRS dice que toca
     * repasar, luego las más recientes. Sin la de la lección. Solo el patrón, para gastar pocos tokens.
     */
    fun previousGrammar(
        pool: List<GrammarPoint>,
        states: Map<Long, MemoryState>,
        focus: String?,
        now: Long,
        limit: Int = MAX_PREVIOUS,
    ): List<String> {
        val others = if (focus.isNullOrBlank()) pool
        else pool.filterNot { GrammarKeys.matches(listOf(it.pattern) + it.variants.split(" | "), listOf(focus)) }
        val (due, rest) = others.partition { g -> states[g.id]?.let { it.reps > 0 && it.dueAt <= now } == true }
        return (due.sortedBy { states.getValue(it.id).dueAt } + rest.sortedByDescending { it.id })
            .map { it.pattern }
            .distinct()
            .take(limit)
    }
}
