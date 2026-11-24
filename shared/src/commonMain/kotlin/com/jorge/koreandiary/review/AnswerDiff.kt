package com.jorge.koreandiary.review

import com.jorge.koreandiary.dictionary.Hangul

/**
 * Dónde está el error de una respuesta casi correcta, sílaba por sílaba y letra por letra (pedido por
 * Jorge el 2026-11-24). Antes el repaso solo decía «no es»; ahora marca la sílaba que está mal y qué
 * revisar (la vocal, la consonante de abajo…) sin dar la respuesta, y después de «Ver respuesta» dice
 * qué letra cambia: «En «었» va ㅆ abajo, no ㅅ». También reconoce los errores de escribir como suena
 * (머거요 por 먹어요, 돼/되, 애/에), que son los más comunes en quien aprende de oído.
 *
 * Compara sin espacios ni signos, como [Cloze.matches]. Si las dos se parecen poco (más de dos
 * cambios), [compare] da null: ahí no hay una letra que corregir sino otra respuesta.
 */
object AnswerDiff {

    enum class Part(val label: String) { INITIAL("la consonante del comienzo"), VOWEL("la vocal"), FINAL("la consonante de abajo") }

    sealed class Change {
        /** La sílaba [written] (en la posición [at] de lo escrito, sin espacios) debía ser [expected]. */
        data class Swap(val at: Int, val written: Char, val expected: Char, val parts: List<Part>) : Change()

        /** Falta [expected] antes de la posición [at] de lo escrito ([at] = largo: al final). */
        data class Missing(val at: Int, val expected: String) : Change()

        /** Sobra [written], que está en la posición [at] de lo escrito. */
        data class Extra(val at: Int, val written: String) : Change()

        /**
         * Escrito como suena: la consonante de abajo de [expected][0] pasó al comienzo de la sílaba
         * siguiente (머거 por 먹어). [at] es la posición de la primera de las dos sílabas.
         */
        data class AsItSounds(val at: Int, val written: String, val expected: String) : Change()
    }

    data class Result(val written: String, val expected: String, val changes: List<Change>) {
        /** Las posiciones de lo escrito (sin espacios ni signos) que están mal. */
        val wrong: Set<Int>
            get() = changes.flatMap { c ->
                when (c) {
                    is Change.Swap -> listOf(c.at)
                    is Change.Extra -> c.written.indices.map { c.at + it }
                    is Change.AsItSounds -> listOf(c.at, c.at + 1)
                    is Change.Missing -> emptyList()
                }
            }.toSet()
    }

    private val NOISE = Regex("""[\s.,!?~…·"'“”‘’()\[\]-]""")
    private const val MAX_CHANGES = 2

    fun plain(s: String): String = s.replace(NOISE, "")

    /** Las posiciones de [original] (con espacios y signos) que [Result.wrong] marca, para resaltarlas. */
    fun wrongIn(original: String, r: Result): Set<Int> {
        val wrong = r.wrong
        val out = mutableSetOf<Int>()
        var plainIndex = 0
        original.forEachIndexed { i, ch ->
            if (NOISE.matches(ch.toString())) return@forEachIndexed
            if (plainIndex in wrong) out += i
            plainIndex++
        }
        return out
    }

    fun compare(written: String, expected: String): Result? {
        val w = plain(written)
        val e = plain(expected)
        if (w.isEmpty() || e.isEmpty() || w == e) return null
        if (!w.all(Hangul::isSyllable) || !e.all(Hangul::isSyllable)) return null
        val changes = merge(align(w, e))
        if (changes.isEmpty() || changes.size > MAX_CHANGES) return null
        // Cuántas letras cambian, frente a las de la respuesta: 갔어요 por 먹었어요 cambia la mitad (es
        // otra palabra); 먹엇어요, una de diez.
        if (changes.sumOf(::weight).toDouble() / jamoCount(e) > MAX_SHARE) return null
        return Result(w, e, changes)
    }

    private const val MAX_SHARE = 0.34

    private fun weight(c: Change): Int = when (c) {
        is Change.Swap -> c.parts.size
        is Change.Missing -> jamoCount(c.expected)
        is Change.Extra -> jamoCount(c.written)
        is Change.AsItSounds -> 1
    }

    private fun jamoCount(s: String): Int = s.sumOf { ch -> Hangul.parts(ch)?.let { if (it.final == 0) 2 else 3 } ?: 1 }

    /** Pasos de edición sílaba por sílaba: el camino más corto, prefiriendo cambiar a quitar y poner. */
    private fun align(w: String, e: String): List<Change> {
        val n = w.length
        val m = e.length
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) for (j in 1..m) {
            val swap = d[i - 1][j - 1] + if (w[i - 1] == e[j - 1]) 0 else 1
            d[i][j] = minOf(swap, d[i - 1][j] + 1, d[i][j - 1] + 1)
        }
        val out = ArrayDeque<Change>()
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + if (w[i - 1] == e[j - 1]) 0 else 1) {
                if (w[i - 1] != e[j - 1]) out.addFirst(Change.Swap(i - 1, w[i - 1], e[j - 1], parts(w[i - 1], e[j - 1])))
                i--
                j--
            } else if (i > 0 && d[i][j] == d[i - 1][j] + 1) {
                out.addFirst(Change.Extra(i - 1, w[i - 1].toString()))
                i--
            } else {
                out.addFirst(Change.Missing(i, e[j - 1].toString()))
                j--
            }
        }
        return out.toList()
    }

    /** Junta lo que va seguido: dos faltas o sobras contiguas son una; 머거 por 먹어 es «como suena». */
    private fun merge(changes: List<Change>): List<Change> {
        val out = mutableListOf<Change>()
        for (c in changes) {
            val last = out.lastOrNull()
            when {
                last is Change.Missing && c is Change.Missing && last.at == c.at -> out[out.lastIndex] = Change.Missing(c.at, last.expected + c.expected)
                last is Change.Extra && c is Change.Extra && last.at + last.written.length == c.at -> out[out.lastIndex] = Change.Extra(last.at, last.written + c.written)
                last is Change.Swap && c is Change.Swap && c.at == last.at + 1 && asItSounds(last, c) ->
                    out[out.lastIndex] = Change.AsItSounds(last.at, "${last.written}${c.written}", "${last.expected}${c.expected}")
                else -> out += c
            }
        }
        return out
    }

    private fun parts(a: Char, b: Char): List<Part> {
        val x = Hangul.parts(a) ?: return Part.entries
        val y = Hangul.parts(b) ?: return Part.entries
        return buildList {
            if (x.initial != y.initial) add(Part.INITIAL)
            if (x.vowel != y.vowel) add(Part.VOWEL)
            if (x.final != y.final) add(Part.FINAL)
        }
    }

    /**
     * 머거 por 먹어: la primera sílaba perdió su consonante de abajo y la siguiente, que empezaba con
     * ㅇ, la tomó al comienzo (así suena: [머거]).
     */
    private fun asItSounds(first: Change.Swap, second: Change.Swap): Boolean {
        val w1 = Hangul.parts(first.written) ?: return false
        val e1 = Hangul.parts(first.expected) ?: return false
        val w2 = Hangul.parts(second.written) ?: return false
        val e2 = Hangul.parts(second.expected) ?: return false
        if (w1.initial != e1.initial || w1.vowel != e1.vowel || e1.final == 0) return false
        if (e2.initialJamo != 'ㅇ' || w2.vowel != e2.vowel || w2.final != e2.final) return false
        // Lo que pasa: toda la consonante de abajo (먹어 → 머거), o la segunda de una doble y la
        // primera se queda (없어 → 업서, 읽어 → 일거); ㄷ y ㅌ antes de 이 suenan ㅈ y ㅊ (같이 [가치]).
        val (stays, moves) = CLUSTERS[e1.finalJamo] ?: ("" to e1.finalJamo)
        if (w1.finalJamo != stays) return false
        val palatal = if (e2.vowelJamo == 'ㅣ') PALATAL[e1.finalJamo] else null
        return w2.initialJamo.toString() == moves || w2.initialJamo == palatal
    }

    private val PALATAL = mapOf("ㄷ" to 'ㅈ', "ㅌ" to 'ㅊ', "ㄾ" to 'ㅊ')

    /** Las dobles de abajo: cuál se queda y cuál pasa a la sílaba siguiente al pronunciar. */
    private val CLUSTERS = mapOf(
        "ㄳ" to ("ㄱ" to "ㅅ"), "ㄵ" to ("ㄴ" to "ㅈ"), "ㄺ" to ("ㄹ" to "ㄱ"), "ㄻ" to ("ㄹ" to "ㅁ"), "ㄼ" to ("ㄹ" to "ㅂ"),
        "ㄽ" to ("ㄹ" to "ㅅ"), "ㄾ" to ("ㄹ" to "ㅌ"), "ㄿ" to ("ㄹ" to "ㅍ"), "ㅄ" to ("ㅂ" to "ㅅ"),
    )

    /** Al final de una sílaba suenan igual: 엇/었/엊/엋 suenan [얻]; 박/밖/밬 suenan [박]. */
    private val FINAL_SOUND = mapOf(
        "ㄱ" to "ㄱ", "ㄲ" to "ㄱ", "ㅋ" to "ㄱ", "ㄳ" to "ㄱ", "ㄺ" to "ㄱ",
        "ㄴ" to "ㄴ", "ㄵ" to "ㄴ", "ㄶ" to "ㄴ",
        "ㄷ" to "ㄷ", "ㅅ" to "ㄷ", "ㅆ" to "ㄷ", "ㅈ" to "ㄷ", "ㅊ" to "ㄷ", "ㅌ" to "ㄷ", "ㅎ" to "ㄷ",
        "ㄹ" to "ㄹ", "ㄼ" to "ㄹ", "ㄽ" to "ㄹ", "ㄾ" to "ㄹ", "ㅀ" to "ㄹ",
        "ㅁ" to "ㅁ", "ㄻ" to "ㅁ",
        "ㅂ" to "ㅂ", "ㅍ" to "ㅂ", "ㅄ" to "ㅂ", "ㄿ" to "ㅂ",
        "ㅇ" to "ㅇ",
    )

    /** Vocales que hoy casi no se distinguen al hablar. */
    private val SAME_VOWEL = listOf(setOf('ㅐ', 'ㅔ'), setOf('ㅒ', 'ㅖ'), setOf('ㅚ', 'ㅙ', 'ㅞ'))

    private val TENSE = mapOf('ㄱ' to 'ㄲ', 'ㄷ' to 'ㄸ', 'ㅂ' to 'ㅃ', 'ㅅ' to 'ㅆ', 'ㅈ' to 'ㅉ')

    /** ¿Lo escrito suena (casi) igual que lo esperado en esa sílaba? Para avisar que es de ortografía. */
    private fun soundsAlike(c: Change.Swap, written: String): Boolean {
        val x = Hangul.parts(c.written) ?: return false
        val y = Hangul.parts(c.expected) ?: return false
        return when (c.parts) {
            listOf(Part.VOWEL) -> SAME_VOWEL.any { x.vowelJamo in it && y.vowelJamo in it }
            listOf(Part.FINAL) -> finalsSoundAlike(x, y, written.getOrNull(c.at + 1))
            // 학꾜 por 학교: tras una consonante de abajo, la ㄱ suena tensa.
            listOf(Part.INITIAL) -> c.at > 0 && (Hangul.parts(written[c.at - 1])?.final ?: 0) != 0 &&
                (TENSE[y.initialJamo] == x.initialJamo || TENSE[x.initialJamo] == y.initialJamo)
            else -> false
        }
    }

    /**
     * La consonante de abajo, al final de la palabra o antes de otra consonante, suena por su grupo
     * (엇/었/엊 suenan [얻]); antes de ㄴ o ㅁ, la de ㄱ, ㄷ y ㅂ suena nasal (먹는 [멍는], 닫는 [단는]).
     */
    private fun finalsSoundAlike(x: Hangul.Parts, y: Hangul.Parts, next: Char?): Boolean {
        val nextInitial = next?.let(Hangul::parts)?.initialJamo
        // La ㅎ de abajo no suena antes de una vocal: 좋아요 [조아요], 많아요 [마나요].
        if (nextInitial == 'ㅇ') return SILENT_H[y.finalJamo] == x.finalJamo || SILENT_H[x.finalJamo] == y.finalJamo
        if (x.final == 0 || y.final == 0) return false
        val a = FINAL_SOUND[x.finalJamo]
        val b = FINAL_SOUND[y.finalJamo]
        if (a == b) return true
        if (nextInitial == 'ㄴ' || nextInitial == 'ㅁ') return NASAL[a] == b || NASAL[b] == a || NASAL[a] == NASAL[b] && NASAL[a] != null
        return false
    }

    private val NASAL = mapOf("ㄱ" to "ㅇ", "ㄷ" to "ㄴ", "ㅂ" to "ㅁ")

    private val SILENT_H = mapOf("ㅎ" to "", "ㄶ" to "ㄴ", "ㅀ" to "ㄹ")

    /**
     * Dónde está el error, sin dar la respuesta (con la tarjeta todavía abierta): «Revisa «엇»: la
     * consonante de abajo».
     */
    fun where(r: Result): String {
        val parts = r.changes.map { c ->
            when (c) {
                is Change.Swap -> "revisa «${c.written}»: " + c.parts.joinToString(" y ") { it.label }
                is Change.Missing -> if (c.at >= r.written.length) "falta algo al final" else if (c.at == 0) "falta algo al comienzo" else "falta algo después de «${r.written[c.at - 1]}»"
                is Change.Extra -> "sobra «${c.written}»"
                is Change.AsItSounds -> "«${c.written}» está escrito como suena"
            }
        }
        val alike = r.changes.any { it is Change.Swap && soundsAlike(it, r.written) }
        return parts.joinToString("; ").replaceFirstChar { it.uppercase() } + "." + if (alike) " Suena casi igual: es de ortografía." else ""
    }

    /** Qué cambia, letra por letra (después de «Ver respuesta»): «En «었» va ㅆ abajo, no ㅅ.». */
    fun explain(r: Result): String = r.changes.joinToString(" ") { c ->
        when (c) {
            is Change.Swap -> {
                val x = Hangul.parts(c.written)!!
                val y = Hangul.parts(c.expected)!!
                val what = c.parts.joinToString(", ") { p ->
                    when (p) {
                        Part.INITIAL -> "empieza con ${y.initialJamo}, no ${x.initialJamo}"
                        Part.VOWEL -> "la vocal es ${y.vowelJamo}, no ${x.vowelJamo}"
                        Part.FINAL -> when {
                            y.final == 0 -> "no lleva consonante abajo (pusiste ${x.finalJamo})"
                            x.final == 0 -> "lleva ${y.finalJamo} abajo"
                            else -> "va ${y.finalJamo} abajo, no ${x.finalJamo}"
                        }
                    }
                }
                "En «${c.expected}» $what" + (if (soundsAlike(c, r.written)) " (suenan casi igual)." else ".")
            }
            is Change.Missing -> "Falta «${c.expected}»" + if (c.at >= r.written.length) " al final." else "."
            is Change.Extra -> "Sobra «${c.written}»."
            is Change.AsItSounds -> "Lo escribiste como suena: «${c.written}» se escribe «${c.expected}»."
        }
    }
}
