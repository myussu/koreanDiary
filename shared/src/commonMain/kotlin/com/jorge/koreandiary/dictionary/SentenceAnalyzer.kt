package com.jorge.koreandiary.dictionary

/**
 * Una pieza de una oración según el analizador: 먹 (VV), 었 (EP), 어요 (EF). [tag] sin la marca de
 * irregular (VV y no VV-I); [irregular] la guarda para volver a unir las piezas (듣 + 어요 → 들어요).
 */
data class Morph(val form: String, val tag: String, val start: Int, val length: Int, val irregular: Boolean = false) {
    val end: Int get() = start + length
}

/**
 * El analizador de oraciones (Kiwi, decidido con Jorge el 2026-11-24, prueba en `claude/kiwi-prueba.md`):
 * mira la oración entera y por eso sabe que en «음악을 들어요» es 듣다 y en «가방을 들어요» es 들다, lo
 * que el lematizador no puede (da las dos). Vive en :app (librería nativa y un modelo que se baja una
 * vez); aquí solo la interfaz, para que la lógica tenga tests en la JVM. Sin el modelo, todo sigue con
 * el lematizador.
 */
interface SentenceAnalyzer {
    /** Las piezas de [sentence], con su posición en ella. */
    fun analyze(sentence: String): List<Morph>

    /** Une piezas en una palabra (맵 + 은 + 맛 → 매운맛, 늦 + 어 + 지 + 다 → 늦어지다). null si no se puede. */
    fun join(pieces: List<Morph>): String?
}

/** Una palabra de la oración (entre espacios, sin los signos de los bordes) con su forma de diccionario. */
data class SentenceWord(val text: String, val start: Int, val end: Int, val lemma: String?, val pieces: List<Morph>)

/**
 * La forma de diccionario de cada palabra con el analizador y el 한국어기초사전: de las piezas, la palabra
 * más larga que el diccionario tiene (일반 + 적 → 일반적, 늦 + 어 + 지 → 늦어지다, 맵 + 은 + 맛 →
 * 매운맛). Es el método «Kiwi + diccionario» de la prueba: 90 % en las ambiguas y 97 % en verbos, contra
 * 70 % y 92 % del lematizador solo. Con la palabra sola le va peor: ahí sigue el lematizador.
 */
object SentenceLemmas {
    private val VERBALES = setOf("VV", "VA", "VX", "XSV", "XSA", "VCN")
    private val SUSTANTIVOS = setOf("NNG", "NNP", "NNB", "NR", "NP")
    private val NOMINALES = SUSTANTIVOS + setOf("XSN", "XR", "MAG", "MAJ", "MM", "IC")
    private val VERBOS = setOf("VV", "VA", "VX", "VCN", "VCP")

    // Partículas, terminaciones finales y signos: la palabra de diccionario termina antes.
    private val CORTE = listOf("J", "EF", "SF", "SP", "SS", "SE", "SO", "SW", "SL", "SH", "SN")
    private val EDGE = Regex("^[^가-힣ㄱ-ㅣ0-9A-Za-z]+|[^가-힣ㄱ-ㅣ0-9A-Za-z]+$")
    private val WORD = Regex("\\S+")

    /** Las piezas que caen dentro de [start, end) de la oración. */
    fun pieces(morphs: List<Morph>, start: Int, end: Int): List<Morph> = morphs.filter { it.start < end && it.end > start }

    /**
     * La forma de diccionario de [start, end) de la oración ya analizada ([surface] es ese pedazo); null
     * si no se sabe.
     */
    fun lemma(analyzer: SentenceAnalyzer, dictionary: KoreanDictionary, morphs: List<Morph>, start: Int, end: Int, surface: String? = null): String? {
        val ms = pieces(morphs, start, end)
        if (ms.isEmpty()) return null
        // Kiwi a veces parte una palabra común en una interjección y otra cosa («어제 친구를 만났어요» →
        // 어 + 저 + 의): si la palabra entera está en el diccionario, va ella.
        if (ms.first().tag == "IC" && ms.size > 1 && surface != null && dictionary.isWord(surface)) return surface
        withDictionary(analyzer, dictionary, ms)?.let { return it }
        // Lo que el diccionario no tiene armado: la primera pieza verbal + 다, o el sustantivo.
        return kiwiOnly(ms)?.takeIf { dictionary.isWord(it) }
    }

    /** La forma de diccionario de [start, end) analizando [sentence]. */
    fun lemmaIn(analyzer: SentenceAnalyzer, dictionary: KoreanDictionary, sentence: String, start: Int, end: Int): String? =
        lemma(analyzer, dictionary, analyzer.analyze(sentence), start, end, sentence.substring(start, end))

    /** La forma de diccionario de [word] (tocada en la oración), mirando [sentence]; null si no se sabe. */
    fun lemmaOfWord(analyzer: SentenceAnalyzer, dictionary: KoreanDictionary, sentence: String, word: String): String? {
        val clean = word.trim().replace(EDGE, "")
        if (clean.isEmpty()) return null
        return words(analyzer, dictionary, sentence).firstOrNull { it.text == clean }?.lemma
    }

    /** Las formas de diccionario de todas las palabras de [sentences], sin repetir. */
    fun lemmasIn(analyzer: SentenceAnalyzer, dictionary: KoreanDictionary, sentences: List<String>): Set<String> =
        sentences.flatMap { s -> words(analyzer, dictionary, s).mapNotNull { it.lemma } }.toSet()

    /** Las palabras de la oración con su forma de diccionario. */
    fun words(analyzer: SentenceAnalyzer, dictionary: KoreanDictionary, sentence: String): List<SentenceWord> {
        val morphs = analyzer.analyze(sentence)
        return WORD.findAll(sentence).mapNotNull { m ->
            val lead = EDGE.find(m.value)?.takeIf { it.range.first == 0 }?.value?.length ?: 0
            val text = m.value.replace(EDGE, "")
            if (text.isEmpty()) return@mapNotNull null
            val start = m.range.first + lead
            val end = start + text.length
            SentenceWord(text, start, end, lemma(analyzer, dictionary, morphs, start, end, text), pieces(morphs, start, end))
        }.toList()
    }

    /** La palabra más larga de las piezas que el diccionario tiene, rearmada con el analizador. */
    private fun withDictionary(analyzer: SentenceAnalyzer, dictionary: KoreanDictionary, ms: List<Morph>): String? {
        for (j in ms.size downTo 1) {
            val head = ms.subList(0, j)
            if (head.any { t -> CORTE.any { t.tag.startsWith(it) } }) continue
            val last = head.last().tag
            val candidate = when (last) {
                in VERBALES -> analyzer.join(head + Morph("다", "EF", head.last().end, 0))
                in NOMINALES -> analyzer.join(head)
                else -> null
            }?.replace(" ", "") ?: continue
            if (dictionary.isWord(candidate)) return candidate
        }
        return null
    }

    /** Solo las piezas: la primera verbal + 다 (공부 + 하 → 공부하다), o el sustantivo. */
    private fun kiwiOnly(ms: List<Morph>): String? {
        for ((i, m) in ms.withIndex()) {
            if ((m.tag == "XSV" || m.tag == "XSA") && i > 0 && ms[i - 1].tag in SUSTANTIVOS + "XR") return ms[i - 1].form + m.form + "다"
            if (m.tag in VERBOS) return m.form + "다"
        }
        return ms.firstOrNull { it.tag in NOMINALES }?.form
    }

    /**
     * La gramática que va después del verbo en [start, end) (먹으면서 → «-으면서», 먹는 동안 → «-는 동안»),
     * tal como la tiene el diccionario; null si no hay o no la tiene.
     */
    fun endingAfterVerb(dictionary: KoreanDictionary, morphs: List<Morph>, start: Int, end: Int): String? {
        val ms = pieces(morphs, start, end)
        val verb = ms.indexOfLast { it.tag in VERBALES }.takeIf { it >= 0 } ?: return null
        // Kiwi escribe -ᆯ게요 con la ㄹ de juntar; el diccionario, -ㄹ게요.
        val rest = ms.drop(verb + 1).filter { !it.tag.startsWith("S") }.map { it.copy(form = Hangul.compat(it.form)) }
        if (rest.isEmpty()) return null
        val candidates = mutableListOf<String>()
        // -는 동안, -은 다음에: la terminación que une y la palabra que sigue van separadas.
        val etm = rest.indexOfFirst { it.tag == "ETM" }
        if (etm >= 0 && etm + 1 < rest.size) candidates += "-" + rest.subList(0, etm + 1).joinToString("") { it.form } + " " + rest.drop(etm + 1).joinToString("") { it.form }
        candidates += "-" + rest.joinToString("") { it.form }
        // Solo la primera terminación que no es de tiempo (-었- + -어요 → -어요), y la de tiempo sola.
        rest.firstOrNull { it.tag.startsWith("E") && it.tag != "EP" }?.let { candidates += "-" + it.form }
        rest.firstOrNull { it.tag == "EP" }?.let { candidates += "-" + it.form + "-" }
        return candidates.distinct().firstOrNull { c -> dictionary.entries(c).any { it.grammar } }
    }
}
