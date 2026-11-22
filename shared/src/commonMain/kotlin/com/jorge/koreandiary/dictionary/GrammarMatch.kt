package com.jorge.koreandiary.dictionary

/**
 * Busca una gramática del diccionario en una oración: «-어서» en 넣어서, «-을 수 있다» en
 * 읽을 수 있어요, «-ㄹ까요» en 볼까요, «-았-» en 갔다, «이랑» en 유민이랑. Sirve para mostrar solo
 * los ejemplos del diccionario que de verdad la usan (algunos son la otra línea de un diálogo) y
 * para poner el hueco del repaso rápido en una oración del diccionario.
 */
object GrammarMatch {

    /** Dónde está en la oración, [start, end): la palabra o palabras que la llevan; una partícula, solo ella. */
    data class Span(val start: Int, val end: Int)

    private class Pattern(
        /** Consonante final de la sílaba de antes (-을 → ㄹ: 먹을, 갈; -ㅂ니다 → ㅂ), o 0. */
        val finalReq: Int,
        /** Lo que va tal cual («서» de -어서, «까요» de -ㄹ까요, «이랑»). */
        val tail: String,
        /** Se quitó un 아/어 que se funde con la raíz (가서, 와서, 해서): la sílaba de antes termina en esa vocal. */
        val aeVowel: Boolean,
        /** Palabras sueltas que siguen, tal cual (수 en -을 수 있다, 때문에 en -기 때문에). */
        val middle: List<String>,
        /** La última palabra si es un verbo (있다, 하다, 되다): su raíz, que va conjugada. */
        val lastStem: String?,
        val particle: Boolean,
        /** Va en medio de la palabra (-았-, -겠-, -시-): después vienen más terminaciones. */
        val infix: Boolean,
        /**
         * La última sílaba de [tail] se conjuga: una expresión de una palabra que termina en 다
         * (-어지다 → 밝혀졌다, 예뻐지는) o una terminación intermedia (-시- → 가셨다, 입으세요).
         */
        val conjugableTail: Boolean,
    )

    /**
     * [expression]: la entrada es una expresión (문법‧표현, como «-어지다» o «-기 일쑤이다»), no una
     * terminación: su 다 final es el de un verbo y va conjugado.
     */
    fun find(sentence: String, form: String, expression: Boolean = false): Span? {
        for (p in patterns(form, expression)) {
            // Desde el final: la terminación del verbo principal suele ir al final (불고기를 먹고 → 먹고).
            for (i in sentence.indices.reversed()) matchAt(sentence, i, p)?.let { return it }
        }
        return null
    }

    /** La forma y cómo se suele acortar al hablar: «-다가 보다» también es «-다 보다» (걷다 보니). */
    private fun patterns(form: String, expression: Boolean): List<Pattern> {
        val f = form.trim()
        val short = if (f.contains("다가 ")) f.replaceFirst(DAGA, "다") else null
        return listOfNotNull(pattern(f, expression), short?.let { pattern(it, expression) })
    }

    /** ¿Se puede buscar? «-어» o «-아» solos no dejan nada que buscar. */
    fun locatable(form: String): Boolean = pattern(form, false) != null

    private fun pattern(form: String, expression: Boolean): Pattern? {
        val f = form.trim()
        val ending = f.startsWith("-")
        val words = f.trim('-').split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty() || words.any { w -> w.any { !Hangul.isSyllable(it) && !Hangul.isJamo(it) } }) return null
        val w0 = words[0]
        var finalReq = 0
        var tail = w0
        var ae = false
        val c0 = w0[0]
        if (Hangul.isJamo(c0)) {
            // -ㄹ까요, -ㅂ니다: la letra se pega a la sílaba de antes (볼까요, 갑니다).
            finalReq = Hangul.finalIndex(c0).takeIf { it > 0 } ?: return null
            tail = w0.drop(1)
        } else if (ending) {
            val p = Hangul.parts(c0)
            // 아/어/여/으 del inicio cambian según la raíz: 가서 (가+아서), 먹을 (먹+을), 갈 (가+ㄹ).
            if (p != null && p.initialJamo == 'ㅇ' && p.vowelJamo in "ㅏㅓㅕㅡ") {
                finalReq = p.final
                tail = w0.drop(1)
                ae = p.final == 0 && p.vowelJamo != 'ㅡ'
            }
        }
        if (tail.any { Hangul.isJamo(it) }) return null
        val rest = words.drop(1)
        if (rest.any { w -> w.any { Hangul.isJamo(it) } }) return null
        val last = rest.lastOrNull()
        val lastStem = last?.takeIf { it.length >= 2 && it.endsWith("다") }?.dropLast(1)
        val middle = if (lastStem != null) rest.dropLast(1) else rest
        if (tail.isEmpty() && finalReq == 0 && rest.isEmpty()) return null
        val infix = ending && f.length > 2 && f.endsWith("-")
        var conjugableTail = infix && tail.isNotEmpty()
        // «-어지다», «-고 싶어 하다» o la partícula «이다»: el 다 es el de un verbo y va conjugado
        // (밝혀졌다, 학생입니다). No en «-란다» o «-단다», donde 다 es parte de la terminación.
        val beforeDa = tail.dropLast(1).lastOrNull()?.let(Hangul::parts)
        if ((expression || !ending) && rest.isEmpty() && tail.length >= 2 && tail.endsWith("다") && beforeDa != null && beforeDa.final == 0) {
            tail = tail.dropLast(1)
            conjugableTail = true
        }
        return Pattern(finalReq, tail, ae, middle, lastStem, particle = !ending && rest.isEmpty(), infix = infix, conjugableTail = conjugableTail)
    }

    private fun matchAt(s: String, i: Int, p: Pattern): Span? {
        val start: Int
        var pos: Int
        if (p.tail.isNotEmpty()) {
            if (i < 1 || !tailAt(s, i, p)) return null
            // Una partícula también va pegada a unas comillas: “안녕”이라고.
            if (p.particle && s[i - 1] in CLOSING_QUOTES) return Span(i, i + p.tail.length)
            val prev = Hangul.parts(s[i - 1]) ?: return null
            start = when {
                p.finalReq != 0 -> if (prev.final == p.finalReq) i - 1 else return null
                p.aeVowel -> if (prev.final == 0 && prev.vowelJamo in AE_VOWELS) i - 1 else return null
                else -> i
            }
            pos = i + p.tail.length
            // Una terminación de una sílaba va al final de la palabra o antes de 요, 는, 도... (먹고, 먹고요),
            // no en medio de un sustantivo (불고기).
            if (!p.particle && !p.infix && !p.conjugableTail && p.tail.length == 1 && p.middle.isEmpty() && p.lastStem == null && p.finalReq == 0 && !p.aeVowel) {
                if (pos < s.length && Hangul.isSyllable(s[pos]) && s[pos] !in AFTER_SHORT_ENDING) return null
            }
        } else {
            val syl = Hangul.parts(s[i]) ?: return null
            if (p.finalReq != 0) {
                if (syl.final != p.finalReq) return null
                // -았-/-었-: 있다 y -겠- también terminan en ㅆ.
                if (p.finalReq == FINAL_SS && (s[i] == '있' || s[i] == '겠')) return null
            } else {
                // -어 주다: la palabra de antes termina en -아/-어 (가르쳐 주세요), no en otra cosa (책을 주세요).
                if (i + 1 < s.length && !s[i + 1].isWhitespace()) return null
                if (p.aeVowel && (syl.final != 0 || syl.vowelJamo !in AE_VOWELS)) return null
            }
            start = i
            pos = i + 1
        }
        val rest = p.middle.isNotEmpty() || p.lastStem != null
        if (rest && (pos >= s.length || !s[pos].isWhitespace())) return null
        for (w in p.middle) {
            pos = skipSpaces(s, pos) ?: return null
            if (!s.startsWith(w, pos)) return null
            pos += w.length
        }
        p.lastStem?.let { stem ->
            pos = skipSpaces(s, pos) ?: return null
            if (!s.startsWith(stem.dropLast(1), pos)) return null
            pos += stem.length - 1
            if (pos >= s.length || !conjugates(stem.last(), s[pos])) return null
            pos += 1
        }
        return if (p.particle) Span(start, pos) else Span(wordStart(s, start), wordEnd(s, pos - 1))
    }

    /** [Pattern.tail] en [i]: tal cual, o con su última sílaba conjugada. */
    private fun tailAt(s: String, i: Int, p: Pattern): Boolean {
        if (!p.conjugableTail) return s.startsWith(p.tail, i)
        val head = p.tail.dropLast(1)
        val at = i + head.length
        return s.startsWith(head, i) && at < s.length && conjugates(p.tail.last(), s[at])
    }

    private fun skipSpaces(s: String, from: Int): Int? {
        var j = from
        while (j < s.length && s[j].isWhitespace()) j++
        return if (j > from && j < s.length) j else null
    }

    /**
     * ¿[t] es la sílaba [stem] conjugada? 하 → 해/했/한/할/합, 되 → 돼/됐, 지 → 져/졌, 시 → 세/셨,
     * 이 → 예 (일쑤예요), 쉽 → 쉬 (쉬우니), 놓 → 놔; 있 solo es 있.
     */
    private fun conjugates(stem: Char, t: Char): Boolean {
        if (stem == t) return true
        val a = Hangul.parts(stem) ?: return false
        val b = Hangul.parts(t) ?: return false
        if (a.initial != b.initial) return false
        when (a.finalJamo) {
            "" -> {}
            "ㅂ" -> return b.vowel == a.vowel && b.final == 0
            "ㅎ" -> return CONTRACTED[a.vowelJamo] == b.vowelJamo
            // 말다 → 마세요, 말고; 살다 → 사는.
            "ㄹ" -> return b.vowel == a.vowel && b.final in CONJUGATED_FINALS
            else -> return false
        }
        val special = (a.initialJamo == 'ㅎ' && a.vowelJamo == 'ㅏ' && b.vowelJamo == 'ㅐ') ||
            (a.vowelJamo == 'ㅣ' && b.vowelJamo == 'ㅖ' && a.initialJamo == 'ㅇ') ||
            (a.initialJamo == 'ㅅ' && a.vowelJamo == 'ㅣ' && b.vowelJamo == 'ㅔ')
        val vowelOk = a.vowel == b.vowel || CONTRACTED[a.vowelJamo] == b.vowelJamo || special
        return vowelOk && b.final in CONJUGATED_FINALS
    }

    private fun wordStart(s: String, i: Int): Int {
        var j = i
        while (j > 0 && !s[j - 1].isWhitespace() && s[j - 1] !in EDGE) j--
        return j
    }

    /** El final de la palabra que contiene [i], sin la puntuación del borde. */
    private fun wordEnd(s: String, i: Int): Int {
        var j = i + 1
        while (j < s.length && !s[j].isWhitespace() && s[j] !in EDGE) j++
        return j
    }

    private val DAGA = Regex("""다가(?= )""")
    private const val FINAL_SS = 20
    /** Vocales en que termina una forma en -아/-어: 가, 해, 먹어, 와, 줘, 돼, 써, 가르쳐. */
    private const val AE_VOWELS = "ㅏㅐㅓㅕㅘㅙㅝ"
    private val CONTRACTED = mapOf('ㅗ' to 'ㅘ', 'ㅜ' to 'ㅝ', 'ㅡ' to 'ㅓ', 'ㅣ' to 'ㅕ', 'ㅚ' to 'ㅙ')
    /** Finales que pone la conjugación: ninguna, ㄴ, ㄹ, ㅂ (합니다) y ㅆ (했다). */
    private val CONJUGATED_FINALS = setOf(0, 4, 8, 17, 20)
    private const val AFTER_SHORT_ENDING = "요는도서야만가를에로은을"
    private const val CLOSING_QUOTES = "”’\"'」』"
    private const val EDGE = ".,!?~…·\"'“”‘’()[]「」『』:;"
}
