package com.jorge.koreandiary.ai

/**
 * Los tres niveles de una revisión, por oración: bien, por corregir y correcta pero más natural. Lo
 * muestran el diario, las escenas y la revisión de la conversación.
 */
data class ReviewLevels(val ok: Int, val fix: Int, val natural: Int) {
    val total: Int get() = ok + fix + natural

    companion object {
        /**
         * [sentences], [corrected] y [natural] son números de oración (desde 1). Una oración con una
         * corrección cuenta como «corregir» aunque también tenga una versión más natural.
         */
        fun of(sentences: Set<Int>, corrected: Set<Int>, natural: Set<Int>): ReviewLevels {
            val all = (sentences + corrected + natural).filter { it > 0 }.toSet()
            val fix = corrected.filter { it > 0 }.toSet()
            val nat = natural.filter { it > 0 && it !in fix }.toSet()
            return ReviewLevels(ok = (all - fix - nat).size, fix = fix.size, natural = nat.size)
        }
    }
}
