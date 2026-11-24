package com.jorge.koreandiary.dictionary

import com.jorge.koreandiary.pronunciation.Pronunciation

/**
 * «Desglosar» una oración sin IA (pedido por Jorge el 2026-11-24): cómo suena, cada palabra con su
 * forma de diccionario y su significado, las partículas y terminaciones con qué hacen (만났어요 = 만나다
 * «encontrarse» + -었- pasado + -어요 cortés) y los modismos que aparecen (발이 넓어요 → 발이 넓다).
 * Las piezas las da el analizador de oraciones (Kiwi); sin él, solo la forma de diccionario de cada
 * palabra y cómo suena.
 */
object SentenceBreakdown {

    /** Una partícula o terminación: [form] como se escribe en el diccionario (-었-, -어요, 를) y qué hace. */
    data class Piece(val form: String, val label: String?)

    data class Word(val text: String, val lemma: String?, val gloss: String?, val grammar: List<Piece>)

    data class Idiom(val form: String, val gloss: String, val proverb: Boolean)

    data class Result(
        val sentence: String,
        /** Cómo suena ([Pronunciation]). */
        val sound: String,
        val words: List<Word>,
        val idioms: List<Idiom>,
        /** Con el analizador (piezas y gramática); sin él, solo formas de diccionario. */
        val analyzed: Boolean,
    )

    private val WORD = Regex("\\S+")
    private val EDGE = Regex("^[^가-힣ㄱ-ㅣ0-9A-Za-z]+|[^가-힣ㄱ-ㅣ0-9A-Za-z]+$")

    fun of(dictionary: KoreanDictionary, sentence: String, analyzer: SentenceAnalyzer?): Result {
        val morphs = analyzer?.let { runCatching { it.analyze(sentence) }.getOrNull() }
        val words = if (analyzer != null && morphs != null) {
            SentenceLemmas.words(analyzer, dictionary, sentence).map { w ->
                val head = w.pieces.firstOrNull { !it.tag.startsWith("S") }?.tag
                Word(w.text, w.lemma, w.lemma?.let { gloss(dictionary, it, head) }, grammar(w.pieces))
            }
        } else {
            WORD.findAll(sentence).mapNotNull { m ->
                val text = m.value.replace(EDGE, "")
                if (text.isEmpty()) return@mapNotNull null
                val lemma = dictionary.baseForms(text).firstOrNull()?.form
                Word(text, lemma, lemma?.let { gloss(dictionary, it) }, emptyList())
            }.toList()
        }
        return Result(
            sentence = sentence,
            sound = Pronunciation.of(sentence, morphs, dictionary),
            words = words,
            idioms = idioms(dictionary, words.mapNotNull { it.lemma }),
            analyzed = morphs != null,
        )
    }

    /**
     * El primer sentido del diccionario, corto: «encontrarse». Con [tag] (la etiqueta del analizador), la
     * entrada de esa categoría: 이 de «이 옷» es «este», no «diente».
     */
    fun gloss(dictionary: KoreanDictionary, form: String, tag: String? = null): String? {
        val words = dictionary.entries(form).filter { !it.grammar }
        val pos = tag?.let { POS[it] }
        val e = words.firstOrNull { pos != null && it.pos in pos } ?: words.firstOrNull() ?: return null
        val s = dictionary.detail(e)?.senses?.firstOrNull() ?: return null
        s.lemma?.trim()?.takeIf { it.isNotEmpty() }?.let { return DictionaryExtrasOf.shortGloss(it) }
        return s.definition?.substringBefore('.')?.trim()?.take(60)?.takeIf { it.isNotEmpty() }
    }

    /** Las partículas y terminaciones de una palabra, en orden. */
    private fun grammar(pieces: List<Morph>): List<Piece> = pieces.mapNotNull { m ->
        val form = Hangul.compat(m.form)
        when {
            m.tag.startsWith("J") -> Piece(form, PARTICLES[form])
            m.tag == "EP" -> Piece("-$form-", TENSE[form])
            m.tag == "EF" -> Piece("-$form", FINAL[form] ?: "terminación")
            m.tag == "EC" -> Piece("-$form", LINKING[form] ?: "une con lo que sigue")
            m.tag == "ETM" -> Piece("-$form", MODIFIER[form] ?: "modifica al sustantivo")
            m.tag == "ETN" -> Piece("-$form", "vuelve sustantivo al verbo")
            m.tag == "VCP" -> Piece("이다", "ser")
            m.tag == "XSN" && form in NOUN_SUFFIXES -> Piece("-$form", NOUN_SUFFIXES[form])
            else -> null
        }
    }

    /**
     * Los modismos y refranes del diccionario cuyas palabras están todas en la oración y en orden (발이
     * 넓다 en «그 사람은 발이 넓어요»). Pueden ser también el sentido literal: la pantalla lo dice.
     */
    fun idioms(dictionary: KoreanDictionary, lemmas: List<String>): List<Idiom> {
        if (lemmas.size < 2) return emptyList()
        val heads = lemmas.distinct().flatMap { l -> dictionary.entries(l).filter { !it.grammar } }
        return heads.flatMap { dictionary.idiomsOf(it) }.distinctBy { it.id }.filter { idiom ->
            val parts = idiomWords(dictionary, idiom.form)
            parts.size >= 2 && inOrder(parts, lemmas)
        }.map { Idiom(it.form, DictionaryExtrasOf.shortGloss(it.gloss), it.proverb) }.take(3)
    }

    /** Las palabras de un modismo sin partículas ni lo opcional: «가늠(을) 보다» → 가늠, 보다. */
    private fun idiomWords(dictionary: KoreanDictionary, form: String): List<String> =
        form.replace(Regex("\\([^)]*\\)|\\[[^]]*]"), "").split(' ').filter { it.isNotBlank() }.map { token ->
            if (token.endsWith("다") && dictionary.entries(token).any { it.isVerb }) token
            else Lemmatizer.PARTICLES.firstOrNull { token.length > it.length && token.endsWith(it) && dictionary.isWord(token.dropLast(it.length)) }
                ?.let { token.dropLast(it.length) } ?: (dictionary.baseForms(token).firstOrNull()?.form ?: token)
        }

    private fun inOrder(parts: List<String>, lemmas: List<String>): Boolean {
        var i = 0
        for (l in lemmas) if (i < parts.size && l == parts[i]) i++
        return i == parts.size
    }

    /** Las categorías del diccionario que corresponden a cada etiqueta del analizador. */
    private val POS = mapOf(
        "NNG" to setOf("명사"), "NNP" to setOf("명사"), "NNB" to setOf("의존 명사", "명사"), "NR" to setOf("수사", "명사"),
        "NP" to setOf("대명사"), "VV" to setOf("동사"), "VA" to setOf("형용사"), "VX" to setOf("보조 동사", "보조 형용사"),
        "MAG" to setOf("부사"), "MAJ" to setOf("부사"), "MM" to setOf("관형사"), "IC" to setOf("감탄사"), "XR" to setOf("명사", "형용사"),
    )

    // ------------------------------------------------------------------ qué hace cada pieza

    private val PARTICLES = mapOf(
        "이" to "sujeto", "가" to "sujeto", "께서" to "sujeto (respeto)",
        "을" to "objeto", "를" to "objeto", "ㄹ" to "objeto",
        "은" to "tema: en cuanto a…", "는" to "tema: en cuanto a…", "ㄴ" to "tema: en cuanto a…",
        "도" to "también", "만" to "solo", "까지" to "hasta", "부터" to "desde", "마다" to "cada", "밖에" to "solo (con negación)",
        "요" to "cortesía", "나" to "o, como mucho", "이나" to "o, como mucho", "라도" to "aunque sea", "이라도" to "aunque sea",
        "처럼" to "como", "같이" to "como", "보다" to "más que", "만큼" to "tanto como", "쯤" to "más o menos",
        "에" to "en, a (lugar o tiempo)", "에서" to "en (donde pasa), desde", "에게" to "a (alguien)", "한테" to "a (alguien)",
        "께" to "a (alguien, respeto)", "에게서" to "de (alguien)", "한테서" to "de (alguien)",
        "로" to "a, hacia; con (medio)", "으로" to "a, hacia; con (medio)",
        "와" to "y, con", "과" to "y, con", "하고" to "y, con", "랑" to "y, con", "이랑" to "y, con",
        "의" to "de (posesión)", "이요" to "cortesía", "라고" to "cita: que…", "이라고" to "cita: que…",
    )

    private val TENSE = mapOf(
        "었" to "pasado", "았" to "pasado", "였" to "pasado", "었었" to "pasado lejano", "겠" to "futuro o suposición",
        "시" to "respeto", "으시" to "respeto",
    )

    private val FINAL = mapOf(
        "어요" to "cortés", "아요" to "cortés", "여요" to "cortés", "요" to "cortés", "에요" to "cortés (ser)", "예요" to "cortés (ser)",
        "습니다" to "formal", "ㅂ니다" to "formal", "습니까" to "pregunta formal", "ㅂ니까" to "pregunta formal",
        "다" to "forma de diccionario o escrita", "어" to "informal", "아" to "informal", "야" to "informal (ser)",
        "네요" to "¡qué…! (sorpresa)", "지요" to "¿verdad?", "죠" to "¿verdad?", "을까요" to "¿…? (sugerencia o duda)",
        "ㄹ까요" to "¿…? (sugerencia o duda)", "을게요" to "lo haré (promesa)", "ㄹ게요" to "lo haré (promesa)",
        "세요" to "por favor; respeto", "으세요" to "por favor; respeto", "자" to "vamos a…", "니" to "pregunta informal",
        "냐" to "pregunta informal", "군요" to "ya veo", "는군요" to "ya veo", "거든요" to "es que…", "잖아요" to "¿no ves que…?",
        "을래요" to "¿quieres…? / quiero", "ㄹ래요" to "¿quieres…? / quiero", "는데요" to "suaviza (y…)", "은데요" to "suaviza (y…)",
        "ㄴ데요" to "suaviza (y…)", "어라" to "orden", "아라" to "orden", "십시오" to "por favor (formal)", "으십시오" to "por favor (formal)",
    )

    private val LINKING = mapOf(
        "고" to "y (luego)", "어서" to "y entonces; porque", "아서" to "y entonces; porque", "여서" to "y entonces; porque",
        "지만" to "pero", "면" to "si", "으면" to "si", "면서" to "mientras", "으면서" to "mientras",
        "는데" to "y, pero (da contexto)", "은데" to "y, pero (da contexto)", "ㄴ데" to "y, pero (da contexto)",
        "니까" to "porque", "으니까" to "porque", "어도" to "aunque", "아도" to "aunque", "게" to "de forma…, para que",
        "지" to "va con 않다, 못하다", "러" to "para (ir a…)", "으러" to "para (ir a…)", "려고" to "para, con intención de",
        "으려고" to "para, con intención de", "어야" to "tener que (con 하다)", "아야" to "tener que (con 하다)",
        "다가" to "y de pronto; mientras", "거나" to "o", "든지" to "o, sea cual sea", "도록" to "para que; hasta que",
        "자마자" to "en cuanto", "어" to "une con el verbo que sigue", "아" to "une con el verbo que sigue",
    )

    private val MODIFIER = mapOf(
        "는" to "que… (presente)", "은" to "que… (pasado o cómo es)", "ㄴ" to "que… (pasado o cómo es)",
        "을" to "que… (futuro o posible)", "ㄹ" to "que… (futuro o posible)", "던" to "que… (solía, recuerdo)",
    )

    private val NOUN_SUFFIXES = mapOf("들" to "plural", "님" to "respeto", "씨" to "señor, señora", "적" to "-ico (como adjetivo)")
}
