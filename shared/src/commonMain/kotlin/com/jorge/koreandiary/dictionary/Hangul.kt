package com.jorge.koreandiary.dictionary

/**
 * Sílabas hangul por partes (consonante inicial, vocal, consonante final), para conjugar y
 * desconjugar: 만나 + ㄴ = 만난, 더워 + ㅆ = 더웠, 꿈꾸어 → 꿈꿔.
 */
object Hangul {
    const val INITIALS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    const val VOWELS = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
    private val FINALS = listOf("", "ㄱ", "ㄲ", "ㄳ", "ㄴ", "ㄵ", "ㄶ", "ㄷ", "ㄹ", "ㄺ", "ㄻ", "ㄼ", "ㄽ", "ㄾ", "ㄿ", "ㅀ", "ㅁ", "ㅂ", "ㅄ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ")

    /** Una sílaba por partes: índices de inicial, vocal y final (0 = sin final). */
    data class Parts(val initial: Int, val vowel: Int, val final: Int) {
        val initialJamo: Char get() = INITIALS[initial]
        val vowelJamo: Char get() = VOWELS[vowel]
        val finalJamo: String get() = FINALS[final]
    }

    fun isSyllable(c: Char): Boolean = c in '가'..'힣'

    fun parts(c: Char): Parts? {
        if (!isSyllable(c)) return null
        val n = c - '가'
        return Parts(n / 588, (n % 588) / 28, n % 28)
    }

    fun syllable(initial: Int, vowel: Int, final: Int = 0): Char = '가' + (initial * 588 + vowel * 28 + final)

    /** La consonante final de la última sílaba ("" si no tiene; null si no es hangul). */
    fun finalOf(s: String): String? = s.lastOrNull()?.let(::parts)?.finalJamo

    fun hasFinal(s: String): Boolean = (s.lastOrNull()?.let(::parts)?.final ?: 0) != 0

    /** Pone una consonante final a la última sílaba (만나 + ㄴ = 만난). null si ya tiene una. */
    fun addFinal(s: String, jamo: Char): String? {
        val p = s.lastOrNull()?.let(::parts) ?: return null
        if (p.final != 0) return null
        val f = FINALS.indexOf(jamo.toString()).takeIf { it > 0 } ?: return null
        return s.dropLast(1) + syllable(p.initial, p.vowel, f)
    }

    /** Quita la consonante final de la última sílaba (만들 → 만드). */
    fun dropFinal(s: String): String {
        val p = s.lastOrNull()?.let(::parts) ?: return s
        if (p.final == 0) return s
        return s.dropLast(1) + syllable(p.initial, p.vowel, 0)
    }

    /** ¿Es una letra suelta (ㄴ, ㄹ, ㅂ...), como las que empiezan algunas terminaciones? */
    fun isJamo(c: Char): Boolean = c in 'ㄱ'..'ㅣ'

    /**
     * Las letras sueltas como las escribe el diccionario (ㄹ, U+3139): Kiwi da las de «juntar» (ᆯ,
     * U+11AF) en sus piezas (-ᆯ게요, -ᆫ 다음에), que no coinciden al comparar. Para unir piezas con Kiwi
     * hay que dejarle las suyas.
     */
    fun compat(s: String): String = buildString(s.length) {
        for (c in s) append(
            when (c) {
                in '\u1100'..'\u1112' -> INITIALS[c - '\u1100']
                in '\u1161'..'\u1175' -> VOWELS[c - '\u1161']
                in '\u11A8'..'\u11C2' -> FINALS[c - '\u11A8' + 1][0]
                else -> c
            },
        )
    }

    /** El índice de una letra suelta como consonante final (ㄹ → 8), o 0 si no puede ir al final. */
    fun finalIndex(jamo: Char): Int = FINALS.indexOf(jamo.toString()).coerceAtLeast(0)

    private val CONTRACTIONS = mapOf(
        "ㅜㅓ" to 'ㅝ', "ㅗㅏ" to 'ㅘ', "ㅣㅓ" to 'ㅕ', "ㅐㅓ" to 'ㅐ', "ㅔㅓ" to 'ㅔ', "ㅚㅓ" to 'ㅙ',
        "ㅏㅏ" to 'ㅏ', "ㅓㅓ" to 'ㅓ', "ㅕㅓ" to 'ㅕ', "ㅡㅓ" to 'ㅓ', "ㅡㅏ" to 'ㅏ', "ㅐㅏ" to 'ㅐ',
    )

    /**
     * La forma en -아/-어 y sus contracciones: 꿈꾸어 → 꿈꿔, 오아 → 와, 하여 → 해, 쓰어 → 써.
     * Siempre incluye la forma tal cual.
     */
    fun contractions(ae: String): Set<String> {
        val out = linkedSetOf(ae)
        if (ae.length < 2) return out
        if (ae.endsWith("하여")) out += ae.dropLast(2) + "해"
        val a = parts(ae[ae.length - 2]) ?: return out
        val b = parts(ae.last()) ?: return out
        if (a.final != 0 || b.initialJamo != 'ㅇ' || b.final != 0) return out
        val merged = CONTRACTIONS["${a.vowelJamo}${b.vowelJamo}"] ?: return out
        out += ae.dropLast(2) + syllable(a.initial, VOWELS.indexOf(merged))
        return out
    }
}
