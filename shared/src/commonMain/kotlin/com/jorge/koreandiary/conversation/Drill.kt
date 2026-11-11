package com.jorge.koreandiary.conversation

import com.jorge.koreandiary.ai.CorrectionDto
import com.jorge.koreandiary.ink.TextMatch

/** Una oración corregida para decir en voz alta. [fix] es la parte que cambió. */
data class DrillItem(val target: String, val fix: String, val original: String, val explanation: String)

enum class DrillResult {
    /** Dijiste la oración corregida. */
    OK,
    /** Se parece, pero no se escuchó la parte corregida: probablemente repetiste el error. */
    MISSING_FIX,
    /** No se parece a la oración. */
    DIFFERENT,
}

/**
 * Práctica después de la revisión: como en el diario reescribes las correcciones a mano,
 * aquí las dices en voz alta. Funciones puras para poder probarlas.
 */
object Drill {
    /**
     * Cada corrección se aplica sobre tu línea original para practicar la oración entera
     * (si el fragmento no aparece tal cual, se practica solo la sugerencia).
     */
    fun build(lines: List<Line>, corrections: List<CorrectionDto>): List<DrillItem> {
        val userLines = lines.filter { it.speaker == Speaker.USER }
        return corrections.mapNotNull { c ->
            val suggestion = c.suggestion.trim()
            if (suggestion.isEmpty()) return@mapNotNull null
            val line = userLines.getOrNull(c.sentence - 1)?.text?.trim()
            val target = if (line != null && c.original.isNotBlank() && line.contains(c.original.trim())) {
                line.replace(c.original.trim(), suggestion)
            } else {
                suggestion
            }
            DrillItem(target = target, fix = suggestion, original = c.original.trim(), explanation = c.explanation)
        }.distinctBy { normalize(it.target) }
    }

    /** Sin espacios ni puntuación: el reconocimiento de voz no siempre los pone igual. */
    fun normalize(s: String): String = s.filter { it.isLetterOrDigit() }

    /** Compara lo que escuchó el reconocimiento con la oración; toma la mejor de las alternativas. */
    fun evaluate(item: DrillItem, heard: List<String>): DrillResult =
        heard.map { evaluate(item, it) }.minByOrNull { it.ordinal } ?: DrillResult.DIFFERENT

    fun evaluate(item: DrillItem, heard: String): DrillResult {
        val h = normalize(heard)
        val target = normalize(item.target)
        if (h.isEmpty() || target.isEmpty()) return DrillResult.DIFFERENT
        // 1 error cada 6 caracteres: el reconocimiento se equivoca a veces aunque lo digas bien.
        val allowed = maxOf(1, target.length / 6)
        val close = TextMatch.substringDistance(h, target) <= allowed
        // La parte corregida suele ser corta (una partícula, una terminación): ahí no se tolera
        // ningún error, porque justo ese carácter es lo que se está practicando.
        val fix = normalize(item.fix)
        val fixHeard = h.contains(fix) || (fix.length > 8 && TextMatch.approxContains(h, fix))
        return when {
            close && fixHeard -> DrillResult.OK
            close -> DrillResult.MISSING_FIX
            else -> DrillResult.DIFFERENT
        }
    }
}
