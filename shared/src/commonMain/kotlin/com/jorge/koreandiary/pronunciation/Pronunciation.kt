package com.jorge.koreandiary.pronunciation

import com.jorge.koreandiary.dictionary.DictEntry
import com.jorge.koreandiary.dictionary.Hangul
import com.jorge.koreandiary.dictionary.KoreanDictionary
import com.jorge.koreandiary.dictionary.Lemmatizer
import com.jorge.koreandiary.dictionary.Morph

/**
 * Cómo suena el coreano escrito (pedido por Jorge el 2026-11-24): 먹었어요 → [머거써요], 같이 →
 * [가치], 국물 → [궁물], 좋아요 → [조아요]. Son las reglas del 표준 발음법 (las normas de pronunciación
 * estándar, artículos 5 a 30) en el orden de g2pK (github.com/Kyubyong/g2pK, Apache-2.0), escritas
 * aquí sílaba por sílaba y sin su tabla, que tenía celdas corridas.
 *
 * Lo que depende de la gramática lo dice el analizador de oraciones (Kiwi), si está: 신고 suena [신꼬]
 * si es «se calza» y [신고] si es «denuncia»; 할 수 suena [할 쑤] porque 할 modifica. Lo que es de cada
 * palabra (갈등 [갈뜽], 휘발유 [휘발류], 맛없다 [마덥따]) lo dice el 한국어기초사전. Sin ellos van reglas
 * más simples que cubren lo común.
 *
 * Decisiones (2026-11-24): entre dos formas permitidas va la que se oye al hablar (주의 [주이], la
 * partícula 의 [에]); ㅖ queda como se escribe (시계, no 시게). Entre palabras, la consonante pasa a la
 * siguiente (밭 아래 [바 다래]) salvo antes de 이, 야, 여, 요, 유, donde puede sumarse una ㄴ (무슨 일
 * [무슨 닐]) según cómo se diga: ahí se lee como si hubiera una pausa.
 */
object Pronunciation {

    /**
     * Cómo suena [text], con los mismos espacios y signos (los números se leen: 3시 → 세시). [morphs]:
     * las piezas del analizador para [text], tal cual; [dictionary]: para las palabras que no siguen las
     * reglas.
     */
    fun of(text: String, morphs: List<Morph>? = null, dictionary: KoreanDictionary? = null): String {
        val digits = text.any { it in '0'..'9' }
        // Con números las posiciones del analizador ya no coinciden: van las reglas sin él.
        val source = if (digits) KoreanNumbers.spell(text) else text
        val flags = if (morphs != null && !digits) Flags.from(source, morphs) else null
        val items = build(source, flags)
        val syls = items.filterIsInstance<Syl>()
        if (syls.isEmpty()) return source
        if (flags == null) guess(syls, dictionary, source)
        if (dictionary != null) lexical(syls, morphs?.takeIf { !digits }, dictionary, source)
        // Art. 15 dentro de la palabra: 없다 tras un sustantivo (버릇없다 [버르덥따], 아랑곳없이).
        for ((i, s) in syls.withIndex()) {
            if (s.initial == 'ㅇ' && s.vowel == 'ㅓ' && s.final == "ㅄ" && !s.firstOfWord && syls[i - 1].final.isNotEmpty()) s.contentStart = true
        }
        val pairs = pairsOf(items)
        prepare(pairs, flags != null)
        for ((a, b, join) in pairs) if (join != Join.NONE && a.final.isNotEmpty() && !a.lexical) resolve(a, b, join == Join.WORD)
        for (s in syls) s.final = sound(s.final)
        vowels(syls)
        return render(items)
    }

    /** Lo que se compara para decir «suena igual»: sin espacios ni signos, con ㅐ/ㅔ y ㅚ/ㅙ/ㅞ juntas. */
    fun key(text: String, dictionary: KoreanDictionary? = null): String =
        of(text, null, dictionary).filter(Hangul::isSyllable).map { c ->
            val p = Hangul.parts(c)!!
            val v = when (p.vowelJamo) {
                'ㅐ' -> 'ㅔ'
                'ㅒ' -> 'ㅖ'
                'ㅙ', 'ㅞ' -> 'ㅚ'
                else -> p.vowelJamo
            }
            Hangul.syllable(p.initial, Hangul.VOWELS.indexOf(v), p.final)
        }.joinToString("")

    /**
     * Las posiciones de [sound] (lo que dio [of] para [written]) cuya sílaba no es la escrita, para
     * resaltar lo que cambia al decirlo. Vacío si no se pueden alinear (con números: 3시 → 세시).
     */
    fun changed(written: String, sound: String): Set<Int> {
        if (written.any { it in '0'..'9' }) return emptySet()
        val w = written.filter { !it.isWhitespace() }
        val s = sound.withIndex().filter { !it.value.isWhitespace() }
        if (w.length != s.size) return emptySet()
        return s.withIndex().filter { (k, iv) -> iv.value != w[k] }.map { it.value.index }.toSet()
    }

    /** ¿[a] y [b] suenan igual aunque se escriban distinto? (됬어요 y 됐어요, 머거요 y 먹어요). */
    fun sameSound(a: String, b: String): Boolean {
        val x = key(a)
        return x.isNotEmpty() && x == key(b)
    }

    // ------------------------------------------------------------------ sílabas

    private class Syl(var initial: Char, var vowel: Char, var final: String, val word: Int, val firstOfWord: Boolean, val at: Int) {
        /** Última sílaba de una raíz verbal seguida de su terminación (앉|고, 신|고 de «calzarse»). */
        var stemEnd = false
        /** Su ㄹ es la de -(으)ㄹ que modifica a lo que sigue (할 수, 먹을 거). */
        var adnominal = false
        /** Su ㄹ empieza una terminación de varias sílabas (할|게요, 할|수록): la siguiente va tensa. */
        var endingL = false
        /** Es la partícula 의 (우리의): suena [에]. */
        var particleUi = false
        /** Se sabe si su 의 es la partícula o parte de la palabra (주의): con el analizador o el diccionario. */
        var uiKnown = false
        var lastOfWord = false
        /** Empieza un morfema con contenido dentro de la palabra (겉|옷): la consonante pasa por su sonido. */
        var contentStart = false
        /** Con la siguiente ya la dejó el diccionario: entre las dos no se aplican reglas. */
        var lexical = false
        /** Su consonante pasó a la palabra siguiente (한국 음식 [한구금식]): se muestran juntas. */
        var joinNext = false
    }

    private class Sep(val text: String)

    private class Flags(n: Int) {
        val stemEnd = BooleanArray(n)
        val adnominal = BooleanArray(n)
        val endingL = BooleanArray(n)
        val particleUi = BooleanArray(n)
        val contentStart = BooleanArray(n)

        companion object {
            private val STEMS = setOf("VV", "VA", "VX")
            private val CONTENT = setOf("NNG", "NNP", "NR", "NP", "VV", "VA", "MAG", "XR")
            private val BEFORE_CONTENT = CONTENT + setOf("XPN", "MM")

            fun from(text: String, morphs: List<Morph>): Flags {
                val f = Flags(text.length)
                val sorted = morphs.filter { it.start >= 0 && it.end <= text.length && it.length > 0 }.sortedWith(compareBy({ it.start }, { it.end }))
                for ((k, m) in sorted.withIndex()) {
                    val next = sorted.getOrNull(k + 1)
                    val prev = sorted.getOrNull(k - 1)
                    // Kiwi escribe la ㄹ de -ᆯ, -ᆯ게요 con la letra de juntar.
                    val form = Hangul.compat(m.form)
                    if (m.tag in STEMS && next != null && next.tag.startsWith("E") && next.start >= m.end - 1 && joined(text, m.end - 1, next.start)) {
                        f.stemEnd[m.end - 1] = true
                    }
                    if (m.tag == "ETM" && (form == "ㄹ" || form == "을")) f.adnominal[m.end - 1] = true
                    if (m.tag.startsWith("E") && m.tag != "ETM" && (form.startsWith("ㄹ") || form.startsWith("을")) && m.length >= 2) f.endingL[m.start] = true
                    if (m.tag == "JKG" && form == "의") f.particleUi[m.start] = true
                    // 맛있다, 멋있다: se dice [마싣따] más que [마딛따].
                    if (m.tag in CONTENT && prev != null && prev.end == m.start && prev.tag in BEFORE_CONTENT && m.form != "있") {
                        f.contentStart[m.start] = true
                    }
                }
                return f
            }

            /** ¿De [a] a [b] sin espacios? (la raíz y su terminación están en la misma palabra). */
            private fun joined(text: String, a: Int, b: Int): Boolean = (a..b.coerceAtMost(text.length - 1)).none { text[it].isWhitespace() }
        }
    }

    private fun build(text: String, flags: Flags?): MutableList<Any> {
        val items = mutableListOf<Any>()
        var word = 0
        var first = true
        for ((i, c) in text.withIndex()) {
            val p = Hangul.parts(c)
            if (p != null) {
                val s = Syl(p.initialJamo, p.vowelJamo, p.finalJamo, word, first, i)
                if (flags != null) {
                    s.stemEnd = flags.stemEnd[i]
                    s.adnominal = flags.adnominal[i]
                    s.endingL = flags.endingL[i]
                    s.particleUi = flags.particleUi[i]
                    s.contentStart = flags.contentStart[i]
                }
                items += s
                first = false
                if (i + 1 >= text.length || !Hangul.isSyllable(text[i + 1])) s.lastOfWord = true
                if (flags != null) s.uiKnown = true
            } else {
                if (c.isWhitespace()) {
                    if (!first) word++
                    first = true
                } else if (!first) {
                    // Un signo corta la palabra: lo que sigue es otra.
                    word++
                    first = true
                }
                items += Sep(c.toString())
            }
        }
        return items
    }

    private fun render(items: List<Any>): String = buildString {
        var last: Syl? = null
        for (it in items) {
            when (it) {
                is Syl -> {
                    append(Hangul.syllable(Hangul.INITIALS.indexOf(it.initial), Hangul.VOWELS.indexOf(it.vowel), finalIndex(it.final)))
                    last = it
                }
                is Sep -> if (!(last?.joinNext == true && it.text.isBlank())) {
                    append(it.text)
                    last = null
                }
            }
        }
    }

    private fun finalIndex(f: String): Int = if (f.isEmpty()) 0 else Hangul.finalIndex(f[0])

    /** Cómo se unen dos sílabas seguidas: en la misma palabra, separadas por espacios o no se unen. */
    private enum class Join { WORD, SPACE, NONE }

    /** Los pares de sílabas seguidas con cómo se unen. */
    private fun pairsOf(items: List<Any>): List<Triple<Syl, Syl, Join>> {
        val out = mutableListOf<Triple<Syl, Syl, Join>>()
        var last: Syl? = null
        var gap = StringBuilder()
        for (it in items) {
            when (it) {
                is Syl -> {
                    last?.let { a ->
                        val join = when {
                            gap.isEmpty() -> Join.WORD
                            gap.all { ch -> ch == ' ' || ch == ' ' } && gap.length <= 2 -> Join.SPACE
                            else -> Join.NONE
                        }
                        out += Triple(a, it, join)
                    }
                    last = it
                    gap = StringBuilder()
                }
                is Sep -> gap.append(it.text)
            }
        }
        return out
    }

    // ------------------------------------------------------------------ palabras del diccionario

    private val NOMINAL = setOf("NNG", "NNP", "NNB", "NR", "NP", "MAG", "MAJ", "MM", "XR", "XPN", "IC")
    private val VERBAL = setOf("VV", "VA")

    /** Lo que sigue al sustantivo y no cambia cómo suena él: partículas, 하다, 이다. */
    private val AFTER_NOUN = Lemmatizer.PARTICLES.toSet()
    private val AFTER_NOUN_START = listOf("하", "했", "해", "한", "할", "합", "되", "돼", "됐", "된", "될", "이", "입", "였", "예", "시")
    private val HADA = listOf("하", "했", "해", "한", "할", "합", "히")

    /**
     * Las palabras con pronunciación propia en el diccionario (갈등 [갈뜽], 같이 [가치], 휘발유
     * [휘발류]): se toman sus sílabas menos la consonante final de la última, que decide cómo se une con
     * lo que sigue (같이 + 도, 갈등 + 이). Entre esas sílabas ya no se aplican reglas: el diccionario
     * dice cómo quedan (급속도 [급쏙도], no [급쏙또]).
     */
    private fun lexical(syls: List<Syl>, morphs: List<Morph>?, dictionary: KoreanDictionary, text: String) {
        for ((_, word) in syls.groupBy { it.word }) {
            val surface = word.joinToString("") { text[it.at].toString() }
            val span = if (morphs != null) spanFromMorphs(word, morphs, dictionary, text) else spanFromLemmatizer(surface, dictionary)
            val (length, sound) = span ?: continue
            if (sound.length != length || length > word.size || !sound.all(Hangul::isSyllable)) continue
            for (k in 0 until length) {
                val p = Hangul.parts(sound[k])!!
                val s = word[k]
                s.initial = p.initialJamo
                s.vowel = p.vowelJamo
                if (k < length - 1) {
                    s.final = p.finalJamo
                    s.lexical = true
                }
            }
        }
    }

    /** Con el analizador: el sustantivo del comienzo de la palabra, o la raíz de un verbo como 맛없다. */
    private fun spanFromMorphs(word: List<Syl>, morphs: List<Morph>, dictionary: KoreanDictionary, text: String): Pair<Int, String>? {
        val start = word.first().at
        val end = word.last().at + 1
        val pieces = morphs.filter { it.start >= start && it.end <= end }.sortedBy { it.start }
        if (pieces.isEmpty() || pieces.first().start != start) return null
        // Sustantivos seguidos (문 + 고리 → 문고리): el más largo que el diccionario tiene.
        var n = 0
        while (n < pieces.size && pieces[n].tag in NOMINAL && text.substring(pieces[n].start, pieces[n].end) == pieces[n].form) n++
        for (j in n downTo 1) {
            val form = text.substring(start, pieces[j - 1].end)
            val hada = pieces.getOrNull(j)?.let { it.tag == "XSV" || it.tag == "XSA" } == true
            if (dictionary.entries(form).none { !it.grammar } && !(hada && dictionary.entries(form + "하다").any { it.isVerb })) continue
            return prefixSound(dictionary, form, hada)?.let { form.length to it }
        }
        // Un verbo de varias sílabas con su pronunciación (맛없다 [마덥따]): las sílabas de la raíz.
        val stem = pieces.first()
        if (stem.tag in VERBAL && stem.form.length >= 2 && text.substring(stem.start, stem.end) == stem.form) {
            return verbStemSound(dictionary, stem.form + "다")?.let { stem.form.length to it }
        }
        return null
    }

    /** Sin el analizador: la palabra entera, o el sustantivo más largo del comienzo si lo que sigue es una partícula o 하다. */
    private fun spanFromLemmatizer(surface: String, dictionary: KoreanDictionary): Pair<Int, String>? {
        dictionary.entries(surface).firstOrNull { !it.grammar }?.let { e ->
            return if (e.isVerb) verbStemSound(dictionary, e.form)?.let { e.form.length - 1 to it }
            else nounSound(dictionary, surface, e)?.let { surface.length to it }
        }
        for (len in surface.length - 1 downTo 1) {
            val form = surface.substring(0, len)
            val rest = surface.substring(len)
            if (rest !in AFTER_NOUN && AFTER_NOUN_START.none { rest.startsWith(it) }) continue
            val hada = HADA.any { rest.startsWith(it) } && dictionary.entries(form + "하다").any { it.isVerb }
            if (!hada && dictionary.entries(form).none { !it.grammar && !it.isVerb }) continue
            prefixSound(dictionary, form, hada)?.let { return len to it }
            break
        }
        // Un verbo: 맛없어요 → 맛없다.
        val verb = dictionary.baseForms(surface).firstOrNull { it.isVerb } ?: return null
        val stem = verb.form.dropLast(1)
        if (stem.length < 2 || !surface.startsWith(stem)) return null
        return verbStemSound(dictionary, verb.form)?.let { stem.length to it }
    }

    /**
     * Cómo suena [form] al comienzo de una palabra; antes de 하다, como en su verbo (돈독해요 es de
     * 돈독하다 [돈도카다], no del sustantivo 돈독 [돈똑]).
     */
    private fun prefixSound(dictionary: KoreanDictionary, form: String, hada: Boolean): String? {
        if (hada) {
            val verb = dictionary.entries(form + "하다").firstOrNull { it.isVerb }
            if (verb != null) {
                val pr = dictionary.detail(verb)?.pronunciation?.substringBefore("/")?.replace(" ", "") ?: return null
                if (pr.length != form.length + 2) return null
                return pr.take(form.length).takeIf { it != form }
            }
        }
        return nounSound(dictionary, form)
    }

    private fun nounSound(dictionary: KoreanDictionary, form: String, entry: DictEntry? = null): String? {
        val e = entry ?: dictionary.entries(form).firstOrNull { !it.grammar && !it.isVerb } ?: return null
        val pr = dictionary.detail(e)?.pronunciation ?: return null
        val first = pr.substringBefore("/").replace(" ", "")
        return first.takeIf { it.length == form.length && it.all(Hangul::isSyllable) && it != form }
    }

    /** Las sílabas de la raíz según el diccionario: 맛없다 [마덥따] → 마덥 (la final de 덥 se toma de la escrita). */
    private fun verbStemSound(dictionary: KoreanDictionary, lemma: String): String? {
        if (lemma == "맛있다" || lemma == "멋있다") return null
        val e = dictionary.entries(lemma).firstOrNull { it.isVerb } ?: return null
        val pr = dictionary.detail(e)?.pronunciation?.substringBefore("/")?.replace(" ", "") ?: return null
        if (pr.length != lemma.length || lemma.length < 3) return null
        val stem = pr.dropLast(1)
        return stem.takeIf { it.all(Hangul::isSyllable) && it != lemma.dropLast(1) }
    }

    // ------------------------------------------------------------------ sin el analizador

    /** Los sustantivos dependientes tras -(으)ㄹ: 할 수, 갈 거예요, 먹을 때. */
    private val BOUND = listOf("것", "거", "걸", "게", "건", "수", "데", "때", "적", "줄", "바", "곳", "뻔", "듯", "리")
    private val BOUND_REST = setOf("", "이", "가", "을", "를", "은", "는", "도", "에", "에는", "밖에", "예요", "이에요", "야", "에요", "이다", "지", "죠", "요", "해요", "했어요", "하다", "이야")

    /**
     * Terminaciones en -(으)ㄹ de varias sílabas que no se confunden con una raíz en ㄹ: 알게 (알다 + -게)
     * no es -ㄹ게, pero 할게요 y 알걸 sí son -ㄹ게요 y -ㄹ걸.
     */
    private val L_ENDINGS = listOf("게요", "걸", "걸요", "수록", "세라", "밖에", "지라도", "지언정", "진대", "거예요", "거야", "거에요")

    /** Lo que el analizador diría, con reglas: 할 수 modifica, 할게요 lleva -ㄹ게요, 우리의 lleva la partícula. */
    private fun guess(syls: List<Syl>, dictionary: KoreanDictionary?, source: String) {
        val words = syls.groupBy { it.word }.toSortedMap()
        val text = words.mapValues { (_, w) -> w.joinToString("") { source[it.at].toString() } }
        for ((n, w) in words) {
            val last = w.last()
            val next = text[n + 1]
            if (last.final == "ㄹ" && next != null && BOUND.any { b -> next.startsWith(b) && next.removePrefix(b) in BOUND_REST }) last.adnominal = true
            val word = text.getValue(n)
            for (i in 0 until w.size - 1) {
                if (w[i].final != "ㄹ") continue
                val rest = word.substring(i + 1)
                if (L_ENDINGS.any { rest == it || rest == it + "요" }) w[i].endingL = true
            }
            // Art. 24 con el diccionario: si la palabra es un verbo, su raíz (담다 [담따], 감고 [감꼬]); si es
            // otra cosa (신고 «denuncia», 참고), no.
            if (dictionary != null) {
                val verb = dictionary.baseForms(word).firstOrNull()?.takeIf { it.isVerb }
                val stem = verb?.form?.dropLast(1)
                if (stem != null && stem.isNotEmpty() && word.startsWith(stem) && word.length > stem.length) w[stem.length - 1].stemEnd = true
            }
            // 우리의 lleva la partícula; 주의, 회의 son palabras. Sin el diccionario no se sabe y queda [의].
            if (dictionary != null && w.size >= 2 && last.initial == 'ㅇ' && last.vowel == 'ㅢ' && last.final.isEmpty()) {
                last.particleUi = !dictionary.isWord(word)
                last.uiKnown = true
            }
        }
    }

    // ------------------------------------------------------------------ reglas

    private fun tense(c: Char): Char = when (c) {
        'ㄱ' -> 'ㄲ'
        'ㄷ' -> 'ㄸ'
        'ㅂ' -> 'ㅃ'
        'ㅅ' -> 'ㅆ'
        'ㅈ' -> 'ㅉ'
        else -> c
    }

    private fun aspirate(c: Char): Char = when (c) {
        'ㄱ' -> 'ㅋ'
        'ㄷ' -> 'ㅌ'
        'ㅂ' -> 'ㅍ'
        'ㅈ' -> 'ㅊ'
        else -> c
    }

    private val K = setOf("ㄱ", "ㄲ", "ㅋ", "ㄳ", "ㄺ")
    private val T = setOf("ㄷ", "ㅅ", "ㅆ", "ㅈ", "ㅊ", "ㅌ")
    private val P = setOf("ㅂ", "ㅍ", "ㄿ", "ㅄ")
    private val N = setOf("ㄴ", "ㄵ")
    private val L = setOf("ㄹ", "ㄼ", "ㄽ", "ㄾ")
    private val M = setOf("ㅁ", "ㄻ")
    private val H = setOf("ㅎ", "ㄶ", "ㅀ")

    /** Después de estas, ㄱ ㄷ ㅂ ㅅ ㅈ suenan tensas (art. 23 y 25: 국밥 [국빱], 넓게 [널께], 핥다 [할따]). */
    private val TENSING = K + T + P + setOf("ㄼ", "ㄾ")

    /** El sonido de la consonante final antes de otra consonante o al final (art. 9 a 11). */
    private fun sound(f: String): String = when (f) {
        "" -> ""
        in K -> "ㄱ"
        in T, "ㅎ" -> "ㄷ"
        in P -> "ㅂ"
        in N, "ㄶ" -> "ㄴ"
        in L, "ㅀ" -> "ㄹ"
        in M -> "ㅁ"
        else -> f
    }

    /** Las dobles: cuál queda y cuál pasa a la sílaba siguiente antes de una vocal (art. 14). */
    private val CLUSTERS = mapOf(
        "ㄳ" to ("ㄱ" to 'ㅆ'), "ㄵ" to ("ㄴ" to 'ㅈ'), "ㄺ" to ("ㄹ" to 'ㄱ'), "ㄻ" to ("ㄹ" to 'ㅁ'), "ㄼ" to ("ㄹ" to 'ㅂ'),
        "ㄽ" to ("ㄹ" to 'ㅆ'), "ㄾ" to ("ㄹ" to 'ㅌ'), "ㄿ" to ("ㄹ" to 'ㅍ'), "ㅄ" to ("ㅂ" to 'ㅆ'),
    )

    private val YI = setOf('ㅣ', 'ㅑ', 'ㅕ', 'ㅛ', 'ㅠ')
    private val LAX = setOf('ㄱ', 'ㄷ', 'ㅂ', 'ㅅ', 'ㅈ')

    private fun original(s: Syl): Char = Hangul.syllable(Hangul.INITIALS.indexOf(s.initial), Hangul.VOWELS.indexOf(s.vowel), finalIndex(s.final))

    /** Lo que depende de la gramática o de una palabra en particular, antes de las reglas de cada par. */
    private fun prepare(pairs: List<Triple<Syl, Syl, Join>>, analyzed: Boolean) {
        for ((a, b, join) in pairs) {
            if (join == Join.NONE || a.lexical) continue
            // Art. 27: tras -(으)ㄹ que modifica, tensa (할 수 [할 쑤], 먹을 거 [머글 꺼]).
            if (a.adnominal && a.final == "ㄹ" && b.initial in LAX) b.initial = tense(b.initial)
            if (join != Join.WORD) continue
            // 십육 [심뉵], 백육 [뱅뉵]: ante 육 de un número se suma una ㄴ (art. 29).
            if (b.initial == 'ㅇ' && b.vowel == 'ㅠ' && b.final == "ㄱ" && original(a) in "십백천만억") b.initial = 'ㄴ'
            // -ㄹ게요, -ㄹ수록, -ㄹ지: la sílaba que sigue a la ㄹ (할게요 [할께요]).
            if (a.endingL && a.final == "ㄹ" && b.initial in LAX) b.initial = tense(b.initial)
            // Art. 24: raíz en ㄴ o ㅁ + terminación (신고 [신꼬], 감다 [감따]); ㄵ y ㄻ son siempre de raíz,
            // menos 굶기다, 옮기다 (-기- de pasiva) y el sustantivo 삶 (삶과).
            if (b.initial in LAX && b.initial != 'ㅂ') {
                val stemTense = when (a.final) {
                    "ㄴ", "ㅁ" -> a.stemEnd
                    "ㄵ" -> true
                    "ㄻ" -> if (analyzed) a.stemEnd else !(b.initial == 'ㄱ' && (b.vowel == 'ㅣ' || b.vowel == 'ㅘ'))
                    else -> false
                }
                if (stemTense) b.initial = tense(b.initial)
            }
            // Art. 11.1: ㄺ de raíz antes de ㄱ suena ㄹ (맑게 [말께], 읽고 [일꼬]); 흙과 y 닭고기, no.
            if (a.final == "ㄺ" && b.initial == 'ㄱ' && (if (analyzed) a.stemEnd else b.vowel != 'ㅘ')) {
                a.final = "ㄹ"
                b.initial = 'ㄲ'
            }
            // Art. 10.1: 밟- antes de consonante suena [밥] (밟다 [밥따], 밟는 [밤는]); 넓죽, 넓둥 [넙].
            if (a.final == "ㄼ" && b.initial != 'ㅇ' && b.initial != 'ㅎ') {
                val balb = (a.initial == 'ㅂ' || a.initial == 'ㅃ') && a.vowel == 'ㅏ'
                val neolb = a.initial == 'ㄴ' && a.vowel == 'ㅓ' && (b.initial == 'ㅈ' || b.initial == 'ㄷ' || b.initial == 'ㅉ' || b.initial == 'ㄸ') && b.vowel == 'ㅜ'
                if (balb || neolb) a.final = "ㅂ"
            }
        }
    }

    private fun resolve(a: Syl, b: Syl, sameWord: Boolean) {
        var f = a.final
        // Art. 17: ㄷ, ㅌ antes de 이 (o 여, 였) suenan ㅈ, ㅊ (같이 [가치], 붙여 [부처]); ㄷ + 히 → ㅊ (닫히다 [다치다]).
        val iLike = b.vowel == 'ㅣ' || b.vowel == 'ㅕ' && (b.final.isEmpty() || b.final == "ㅆ")
        if (sameWord && !b.contentStart && iLike) {
            if (b.initial == 'ㅇ') when (f) {
                "ㄷ" -> { a.final = ""; b.initial = 'ㅈ'; return }
                "ㅌ" -> { a.final = ""; b.initial = 'ㅊ'; return }
                "ㄾ" -> { a.final = "ㄹ"; b.initial = 'ㅊ'; return }
            }
            if (b.initial == 'ㅎ' && f == "ㄷ") { a.final = ""; b.initial = 'ㅊ'; return }
        }
        // Art. 12: la ㅎ de abajo.
        if (f in H) {
            val rest = when (f) {
                "ㄶ" -> "ㄴ"
                "ㅀ" -> "ㄹ"
                else -> ""
            }
            when (b.initial) {
                'ㄱ', 'ㄷ', 'ㅈ' -> { b.initial = aspirate(b.initial); a.final = rest; return }
                'ㅅ' -> { b.initial = 'ㅆ'; a.final = rest; return }
                'ㄴ' -> {
                    a.final = if (f == "ㅎ") "ㄴ" else rest
                    if (rest == "ㄹ") b.initial = 'ㄹ'
                    return
                }
                'ㅇ' -> {
                    // 12.4: 좋아 [조아], 많아 [마나], 싫어 [시러].
                    a.final = ""
                    if (rest.isNotEmpty()) b.initial = rest[0]
                    return
                }
                'ㅎ' -> { a.final = rest; return }
                else -> {
                    f = if (f == "ㅎ") "ㄷ" else rest
                    a.final = f
                }
            }
        }
        // Art. 13 a 15: antes de vocal, la consonante pasa a la sílaba siguiente.
        if (b.initial == 'ㅇ') {
            if (f == "ㅇ") return
            val bySound = !sameWord || b.contentStart
            // Entre palabras (o palabras juntas, 솜이불) antes de 이, 야, 여, 요, 유 puede sumarse una ㄴ:
            // se lee como si hubiera pausa.
            if (bySound && b.vowel in YI) return
            if (bySound) {
                // Art. 15: por su sonido (밭 아래 [바다래], 넋 없다 [너겁따]); entre palabras se muestran juntas.
                val s = sound(f)
                if (s.isEmpty() || s == "ㅇ") return
                b.initial = s[0]
                a.final = ""
                if (!sameWord) a.joinNext = true
                return
            }
            val cluster = CLUSTERS[f]
            if (cluster != null) {
                a.final = cluster.first
                b.initial = cluster.second
            } else {
                b.initial = f[0]
                a.final = ""
            }
            return
        }
        // Art. 12, 붙임 1 y 2: ㄱ ㄷ ㅂ ㅈ + ㅎ suenan ㅋ ㅌ ㅍ ㅊ (축하 [추카], 옷 한 벌 [오 탄 벌]).
        if (b.initial == 'ㅎ') {
            when (f) {
                "ㄱ", "ㄲ", "ㅋ", "ㄳ" -> { a.final = ""; b.initial = 'ㅋ' }
                "ㄺ" -> { a.final = "ㄹ"; b.initial = 'ㅋ' }
                "ㄷ", "ㅅ", "ㅆ", "ㅌ" -> { a.final = ""; b.initial = 'ㅌ' }
                "ㅈ", "ㅊ" -> { a.final = ""; b.initial = if (sameWord) 'ㅊ' else 'ㅌ' }
                "ㅂ", "ㅍ", "ㅄ", "ㄿ" -> { a.final = ""; b.initial = 'ㅍ' }
                "ㄼ" -> { a.final = "ㄹ"; b.initial = 'ㅍ' }
                "ㄵ" -> { a.final = "ㄴ"; b.initial = 'ㅊ' }
                else -> a.final = sound(f)
            }
            return
        }
        // Art. 18 a 20 y 23: el resto de las consonantes.
        when (b.initial) {
            'ㄱ', 'ㄷ', 'ㅂ', 'ㅅ', 'ㅈ' -> {
                if (f in TENSING) b.initial = tense(b.initial)
                a.final = sound(f)
            }
            'ㄴ', 'ㅁ' -> a.final = when {
                f in K -> "ㅇ"
                f in T -> "ㄴ"
                f in P -> "ㅁ"
                // Art. 20 solo dentro de la palabra: 칼날 [칼랄], pero 버스를 놓쳤어요 [버스를 녿처써요].
                f in L && b.initial == 'ㄴ' && sameWord -> { b.initial = 'ㄹ'; "ㄹ" }
                else -> sound(f)
            }
            'ㄹ' -> a.final = when {
                !sameWord -> sound(f)
                f in N -> "ㄹ"
                f in M || f == "ㅇ" -> { b.initial = 'ㄴ'; sound(f) }
                f in K -> { b.initial = 'ㄴ'; "ㅇ" }
                f in T -> { b.initial = 'ㄴ'; "ㄴ" }
                f in P -> { b.initial = 'ㄴ'; "ㅁ" }
                else -> "ㄹ"
            }
            else -> a.final = sound(f)
        }
    }

    /**
     * Art. 5: 져 쪄 쳐 [저 쩌 처]; ㅢ tras consonante [ㅣ] (희망 [히망]); 의 fuera del comienzo [이]
     * (주의 [주이]) y la partícula [에] (우리의 [우리에]). Si no se sabe cuál es, [의], que siempre vale.
     */
    private fun vowels(syls: List<Syl>) {
        for (s in syls) {
            if (s.vowel == 'ㅕ' && (s.initial == 'ㅈ' || s.initial == 'ㅉ' || s.initial == 'ㅊ')) s.vowel = 'ㅓ'
            if (s.vowel != 'ㅢ') continue
            s.vowel = when {
                s.particleUi -> 'ㅔ'
                s.initial != 'ㅇ' -> 'ㅣ'
                s.firstOfWord -> 'ㅢ'
                s.lastOfWord && !s.uiKnown -> 'ㅢ'
                else -> 'ㅣ'
            }
        }
    }
}
