package com.jorge.koreandiary.dictionary

/** Un sentido como se muestra: traducción corta y definición, en el español de Perú. */
data class LookupSense(val lemma: String?, val definition: String?)

/** Una entrada del diccionario lista para mostrar al tocar una palabra. */
data class LookupEntry(
    val entry: DictEntry,
    /** La categoría en español: sustantivo, verbo, contador o unidad… */
    val kind: String,
    val senses: List<LookupSense>,
    /** De qué viene, por partes: «은 銀 + medal», «공 工 · 부 夫 + 하다». null si es nativa. */
    val origin: String?,
    /** Pronunciación, sinónimos y contrarios, palabras con el mismo hanja y modismos. */
    val extras: DictionaryExtras? = null,
)

/** Lo que dice el diccionario de una palabra tocada, con las entradas posibles, primero las comunes. */
data class WordLookupResult(val written: String, val entries: List<LookupEntry>) {
    /** Las formas de diccionario distintas (사는 → 사다 y 살다). */
    val forms: List<String> get() = entries.map { it.entry.form }.distinct()
}

/**
 * Tocar una palabra sin IA (decidido con Jorge el 2026-11-22): forma de diccionario, todos los
 * sentidos con los comunes primero y, después de un número, primero los contadores (세 장 → 장 de
 * cosas planas). Si el diccionario no la tiene, [lookup] da null y la app pregunta a la IA.
 */
object DictionaryLookup {

    fun lookup(dictionary: KoreanDictionary, written: String, previous: String? = null, maxEntries: Int = 8): WordLookupResult? {
        val entries = rank(dictionary.baseForms(written), previous)
        if (entries.isEmpty()) return null
        return WordLookupResult(
            written.trim(),
            entries.take(maxEntries).map { e ->
                val detail = dictionary.detail(e)
                LookupEntry(
                    entry = e,
                    kind = kindOf(e),
                    senses = detail?.senses.orEmpty().map { s ->
                        LookupSense(s.lemma?.let(PeruSpanish::adaptLemma), s.definition?.let(PeruSpanish::adapt))
                    },
                    origin = detail?.origin?.let { originParts(e.form, it) },
                    extras = detail?.let { DictionaryExtrasOf.of(dictionary, e, it) }?.takeIf { !it.isEmpty },
                )
            },
        )
    }

    /** Después de un número (세 장, 두 개), primero los contadores y unidades (의존 명사). */
    fun rank(entries: List<DictEntry>, previous: String?): List<DictEntry> {
        if (previous == null || !isNumber(previous)) return entries
        val (counters, rest) = entries.partition { it.pos == "의존 명사" }
        return counters + rest
    }

    /** ¿Es un número o algo que cuenta (세, 두, 몇, 3)? */
    fun isNumber(word: String): Boolean {
        val w = word.trim().trim('.', ',', '!', '?')
        return w.isNotEmpty() && (w.all(Char::isDigit) || w in NUMBERS)
    }

    private val NUMBERS = setOf(
        "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열", "스무", "스물", "서른", "마흔", "쉰",
        "몇", "여러", "한두", "두세", "서너", "너덧", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구", "십", "백", "천", "만", "첫",
    )

    /**
     * El origen por partes, cada hanja con su sílaba: «銀 medal» → «은 銀 + medal»; «工夫 하다» →
     * «공 工 · 부 夫 + 하다»; «大會» → «대 大 · 회 會». Si las sílabas no cuadran, las partes tal cual.
     */
    fun originParts(form: String, origin: String): String {
        val parts = origin.replace(MARKS, "").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val syllables = form.removePrefix("-").filter(Hangul::isSyllable)
        var pos = 0
        val out = mutableListOf<String>()
        var loan = false
        for (p in parts) {
            val isLoan = !p.all(::isHan) && !p.all(Hangul::isSyllable)
            // Un préstamo de varias palabras va junto: «hand phone».
            if (isLoan && loan) {
                out[out.lastIndex] = out.last() + " " + p
                continue
            }
            loan = isLoan
            when {
                p.all(::isHan) -> {
                    if (pos + p.length > syllables.length) return parts.joinToString(" + ")
                    out += p.indices.joinToString(" · ") { "${syllables[pos + it]} ${p[it]}" }
                    pos += p.length
                }
                p.all(Hangul::isSyllable) -> {
                    if (!syllables.startsWith(p, pos)) return parts.joinToString(" + ")
                    out += p
                    pos += p.length
                }
                // Un préstamo (medal, computer): ocupa lo que queda de la palabra.
                else -> {
                    out += p
                    pos = syllables.length
                }
            }
        }
        return out.joinToString(" + ")
    }

    private val MARKS = Regex("[←▼▽△▲→・]")

    private fun isHan(c: Char): Boolean = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

    /** La categoría del diccionario, en español. */
    fun kindOf(e: DictEntry): String = when (e.pos) {
        "명사" -> "sustantivo"
        "의존 명사" -> "contador o unidad"
        "대명사" -> "pronombre"
        "수사" -> "número"
        "동사" -> "verbo"
        "형용사" -> "adjetivo"
        "보조 동사" -> "verbo auxiliar"
        "보조 형용사" -> "adjetivo auxiliar"
        "부사" -> "adverbio"
        "관형사" -> "determinante"
        "감탄사" -> "interjección"
        "조사" -> "partícula"
        "어미" -> "terminación"
        "접사" -> "afijo"
        else -> "expresión"
    }
}
