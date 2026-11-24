package com.jorge.koreandiary.review

import com.jorge.koreandiary.dictionary.DictEntry
import com.jorge.koreandiary.dictionary.DictionaryExplain
import com.jorge.koreandiary.dictionary.GrammarMatch
import com.jorge.koreandiary.dictionary.KoreanDictionary
import com.jorge.koreandiary.dictionary.SentenceAnalyzer
import com.jorge.koreandiary.dictionary.SentenceLemmas
import kotlin.random.Random

/**
 * Oraciones del 한국어기초사전 para el repaso rápido (decidido con Jorge el 2026-11-22): lo que no
 * tiene oración tuya sale con una del diccionario antes que pedírsela a la IA. La traducción la pone
 * la llamada de la ronda; sin IA queda la línea «Usa:» con lo que dice el diccionario.
 */
object DictionaryCloze {

    /**
     * Una tarjeta para [target] con una oración del diccionario: corta, cortés (-요) si hay, y donde
     * se encuentra el ítem. [random] cambia la oración entre sesiones. Null si no hay ninguna.
     */
    fun card(dictionary: KoreanDictionary, target: ClozeTarget, random: Random = Random.Default, analyzer: SentenceAnalyzer? = null): ClozeCard? {
        val grammar = target.kind == ClozeKind.GRAMMAR
        val family = if (grammar) DictionaryExplain.grammarFamily(dictionary, target.label) else emptyList()
        val sentences: List<String>
        val meaning: String?
        if (grammar) {
            if (family.isEmpty()) return null
            sentences = family.flatMap { e -> dictionary.detail(e)?.senses.orEmpty().flatMap { it.examples } }
            meaning = dictionary.detail(family.first())?.senses?.firstOrNull()?.definition?.let(DictionaryExplain::grammarGloss)
        } else {
            val choice = DictionaryExplain.wordChoice(dictionary, target.label, target.meaning) ?: return null
            sentences = choice.ordered.flatMap { it.examples }
            meaning = DictionaryExplain.senseMeaning(choice.senses[choice.main])
        }
        val options = sentences.map { it.trim().replace(SPACES, " ") }.distinct()
            .filter { it.length in MIN_LENGTH..MAX_LENGTH }
            .mapNotNull { s ->
                val b = if (grammar) grammarBlank(s, family) else wordBlank(dictionary, s, target.label, target.kind, analyzer)
                b?.let { s to it }
            }
        if (options.isEmpty()) return null
        // Corteses primero (el repaso va en -아요/어요) y de esas, las más cortas.
        val pool = options.filter { POLITE.containsMatchIn(it.first) }.ifEmpty { options }.sortedBy { it.first.length }.take(POOL)
        val (sentence, blank) = pool[random.nextInt(pool.size)]
        val card = ClozeCard(
            code = target.code, label = target.label, kind = target.kind,
            meaning = target.meaning?.takeIf { it.isNotBlank() },
            sentence = sentence, start = blank.start, answer = blank.text,
            fromDictionary = true,
            dictionaryMeaning = meaning?.takeIf { it.isNotBlank() },
        )
        return card.copy(base = base(dictionary, card))
    }

    /**
     * Lo que dice el diccionario del ítem, para «Usa:» cuando la IA no contesta (también en tus
     * oraciones): el significado de una palabra (el sentido de tu lista si lo tiene) o qué expresa una
     * gramática, corto. Null si no está.
     */
    fun dictionaryMeaning(dictionary: KoreanDictionary, label: String, kind: ClozeKind, meaning: String? = null): String? =
        if (kind == ClozeKind.GRAMMAR) {
            DictionaryExplain.grammarFamily(dictionary, label).firstOrNull()
                ?.let { dictionary.detail(it) }?.senses?.firstOrNull()?.definition
                ?.let(DictionaryExplain::grammarGloss)
        } else {
            DictionaryExplain.wordChoice(dictionary, label, meaning)?.let { DictionaryExplain.senseMeaning(it.senses[it.main]) }
        }?.takeIf { it.isNotBlank() }

    /**
     * Lo que acompaña al hueco en «Usa:» y en las pistas: un verbo, su forma de diccionario; una
     * gramática, el verbo o adjetivo al que va unida (읽을 수 있어요 → 읽다). Null si no se sabe.
     */
    fun base(dictionary: KoreanDictionary, card: ClozeCard): String? = when (card.kind) {
        ClozeKind.VERB -> card.label.trim()
        ClozeKind.GRAMMAR -> tokens(card.sentence).firstOrNull { card.start >= it.start && card.start < it.start + it.text.length }
            ?.let { first -> dictionary.baseForms(first.text).firstOrNull { it.isVerb }?.form }
        ClozeKind.WORD -> null
    }

    /**
     * Si lo que escribiste usa la misma palabra que la respuesta pero otra terminación (먹으면서 en vez
     * de 먹는 동안), lo dice: la palabra está bien y lo que cambia es la gramática o la conjugación.
     * Null si no es el caso.
     */
    fun nearMiss(dictionary: KoreanDictionary, card: ClozeCard, written: String, analyzer: SentenceAnalyzer? = null): String? {
        if (card.kind != ClozeKind.GRAMMAR) relatedMiss(dictionary, card, written)?.let { return it }
        if (card.kind == ClozeKind.WORD) return null
        // Con el analizador, lo que escribiste se mira dentro de la oración (들어요 es 듣다 o 들다 según ella).
        val start = card.start
        val end = card.start + written.length
        val morphs = analyzer?.let { a -> runCatching { a.analyze(card.before + written + card.after) }.getOrNull() }
        val mine = if (analyzer != null && morphs != null) {
            SentenceLemmas.lemma(analyzer, dictionary, morphs, start, end)?.let { lemma -> dictionary.entries(lemma).firstOrNull { it.isVerb } }
        } else null
        val verb = mine ?: verbOf(dictionary, written) ?: return null
        val expected = card.base?.let { b -> dictionary.entries(b).firstOrNull { it.isVerb } } ?: verbOf(dictionary, card.answer) ?: return null
        if (verb.form != expected.form) return null
        val what = if (expected.pos.contains("형용사")) "El adjetivo" else "El verbo"
        if (card.kind != ClozeKind.GRAMMAR) return "$what está bien (${expected.form}), pero va conjugado de otra forma: mira la traducción."
        // Qué gramática usaste en vez de la del hueco (먹으면서 → «-으면서»), si el analizador la ve.
        val used = morphs?.let { SentenceLemmas.endingAfterVerb(dictionary, it, start, end) }
            ?.takeIf { u -> DictionaryExplain.grammarFamily(dictionary, card.label).none { it.form == u } }
        if (used != null) {
            val gloss = dictionaryMeaning(dictionary, used, ClozeKind.GRAMMAR)?.let { " ($it)" }.orEmpty()
            return "$what está bien (${expected.form}), pero usaste «$used»$gloss: aquí va otra gramática, la de «Usa:»."
        }
        return "$what está bien (${expected.form}), pero aquí va otra gramática: la de «Usa:»."
    }

    private fun verbOf(dictionary: KoreanDictionary, text: String): DictEntry? =
        tokens(text).firstOrNull()?.let { t -> dictionary.baseForms(t.text).firstOrNull { it.isVerb } }

    /**
     * Si escribiste un sinónimo, el contrario o la forma corta de la palabra del hueco (pedido por
     * Jorge el 2026-11-24: antes salía solo «no es»), lo dice sin dar la respuesta. Lo saca de las
     * relaciones de cada sentido del 한국어기초사전, en los dos sentidos (기쁘다 → 슬프다 y al revés).
     */
    fun relatedMiss(dictionary: KoreanDictionary, card: ClozeCard, written: String): String? {
        val first = tokens(written).firstOrNull()?.text ?: return null
        val mine = dictionary.baseForms(first).take(MAX_RELATED_CANDIDATES)
        val expected = dictionary.entries(card.base ?: card.label).filter { !it.grammar }.take(MAX_RELATED_CANDIDATES)
        if (mine.isEmpty() || expected.isEmpty()) return null
        // La misma palabra con otra forma no es esto: de eso se ocupa el resto de [nearMiss].
        if (mine.any { m -> expected.any { it.form == m.form } }) return null
        fun relationsOf(e: DictEntry) = dictionary.detail(e)?.senses?.flatMap { it.relations }.orEmpty()
        for (e in expected) {
            for (r in relationsOf(e)) {
                val m = mine.firstOrNull { it.form == r.form } ?: continue
                RELATED_MISS[r.type]?.let { return it(m.form) }
            }
        }
        for (m in mine) {
            for (r in relationsOf(m)) {
                if (expected.none { it.form == r.form }) continue
                // Al revés: si 학원 es sinónimo de 학교, 학교 lo es de 학원; si es su forma corta, la otra es la completa.
                RELATED_MISS[REVERSE[r.type] ?: r.type]?.let { return it(m.form) }
            }
        }
        return null
    }

    private const val MAX_RELATED_CANDIDATES = 4

    private val REVERSE = mapOf("준말" to "본말", "본말" to "준말", "높임말" to "낮춤말", "낮춤말" to "높임말")

    /**
     * Qué decir según la relación que la palabra del hueco tiene con lo que escribiste ([form]): en el
     * diccionario, «준말 X» en una entrada quiere decir que X es su forma corta.
     */
    private val RELATED_MISS: Map<String, (String) -> String> = mapOf(
        "유의어" to { f -> "«$f» significa casi lo mismo que la del hueco, pero aquí va otra palabra: mira la traducción." },
        "반대말" to { f -> "«$f» es lo contrario de la del hueco." },
        "준말" to { f -> "«$f» es la forma corta de la del hueco: aquí va la completa. Si también cabe, «La mía también vale»." },
        "본말" to { f -> "«$f» es la forma completa de la del hueco: aquí va la corta. Si también cabe, «La mía también vale»." },
        "높임말" to { f -> "«$f» es la forma más respetuosa de la del hueco: aquí va la común." },
        "낮춤말" to { f -> "«$f» es menos respetuosa que la del hueco: aquí va la más respetuosa." },
    )

    /**
     * El ítem en una oración (también tuya, cuando [Cloze.blankIn] no lo encuentra): un verbo
     * irregular (고팠다, 덥군요, 늦어, 더워요) o una gramática escrita de otra forma (-ㄹ 수 있다).
     */
    fun blank(dictionary: KoreanDictionary, sentence: String, label: String, kind: ClozeKind, analyzer: SentenceAnalyzer? = null): ClozeBlank? =
        if (kind == ClozeKind.GRAMMAR) grammarBlank(sentence, DictionaryExplain.grammarFamily(dictionary, label))
        else wordBlank(dictionary, sentence, label, kind, analyzer)

    /**
     * Un sustantivo, al comienzo de una palabra y solo él (장으로 → 장, no el 장 de 시장); un verbo o
     * adjetivo, la palabra conjugada entera.
     *
     * Con el analizador ([analyzer], Kiwi) primero la palabra que en esta oración de verdad es esa: en
     * «사과를 사는 사람» 사는 es 사다, así que para 살다 esa oración no sirve, aunque el lematizador diga
     * que 사는 puede ser 살다. Si el analizador no la encuentra, el lematizador, salvo que el analizador
     * diga que esa palabra es otra (también una más larga que empieza igual: 나라도 es 나라 en «우리
     * 나라도 추워요», no 나).
     *
     * Sin analizador decide el lematizador, y con cuidado, porque un hueco en otra palabra califica
     * mal el repaso (una oración que no se usa no cuesta nada: sale otra tuya o una del diccionario):
     * - un verbo que el lematizador no reconoce se compara a ojo ([Cloze.conjugated]) solo si tampoco
     *   reconoce la palabra como otra cosa: 갔어요 es 가다, así que no es 같다, y 바다에 no es 받다;
     * - un sustantivo no se saca de una palabra que también puede ser otra más larga que empieza igual:
     *   별 de 별로, 나 de 나이, 회 de 회의, ni 나 de 나라도 (que también es 나라 + 도: sin la oración no
     *   se sabe cuál).
     */
    fun wordBlank(dictionary: KoreanDictionary, sentence: String, label: String, kind: ClozeKind, analyzer: SentenceAnalyzer? = null): ClozeBlank? {
        val form = label.trim()
        if (form.isEmpty()) return null
        val analyzed = analyzer?.let { a -> runCatching { SentenceLemmas.words(a, dictionary, sentence) }.getOrNull() }
        if (analyzed != null) {
            val found = if (kind == ClozeKind.VERB) analyzed.lastOrNull { it.lemma == form }?.let { ClozeBlank(it.start, it.text) }
            else analyzed.firstOrNull { it.lemma == form && it.text.startsWith(form) }?.let { ClozeBlank(it.start, form) }
            if (found != null) return found
        }
        val words = tokens(sentence)
        val old = if (kind == ClozeKind.VERB) {
            words.lastOrNull { w -> dictionary.baseForms(w.text).any { it.form == form } }
                ?: words.lastOrNull { w -> dictionary.baseForms(w.text).isEmpty() && Cloze.conjugated(Cloze.stem(form), w.text) }
        } else {
            words.firstOrNull { w ->
                if (!w.text.startsWith(form)) return@firstOrNull false
                if (w.text == form) return@firstOrNull true
                val readings = dictionary.baseForms(w.text)
                readings.any { it.form == form } && readings.none { !it.isVerb && it.form != form && it.form.startsWith(form) }
            }?.let { ClozeBlank(it.start, form) }
        } ?: return null
        // El analizador dice que esa palabra es otra (사는 = 사다, 나라도 = 나라): esta oración no sirve.
        val other = analyzed?.firstOrNull { it.start <= old.start && old.start < it.end }?.lemma
        return if (other != null && other != form) null else old
    }

    /** La gramática con cualquiera de sus formas: las palabras que la llevan; una partícula, solo ella. */
    fun grammarBlank(sentence: String, family: List<DictEntry>): ClozeBlank? {
        for (e in family) {
            if (!e.grammar) {
                // «N 때문에»: el sustantivo dependiente va suelto, con lo que lleva pegado (때문에).
                tokens(sentence).firstOrNull { it.text.startsWith(e.form) }?.let { return it }
                continue
            }
            val span = GrammarMatch.find(sentence, e.form, e.pos == "품사 없음") ?: continue
            return ClozeBlank(span.start, sentence.substring(span.start, span.end))
        }
        return null
    }

    /** Las palabras de la oración, sin la puntuación de los bordes, con su posición. */
    private fun tokens(sentence: String): List<ClozeBlank> =
        Regex("""\S+""").findAll(sentence).mapNotNull { m ->
            val lead = m.value.takeWhile { it in LEADING }.length
            val core = m.value.drop(lead).trimEnd { it in TRAILING }
            if (core.isEmpty()) null else ClozeBlank(m.range.first + lead, core)
        }.toList()

    private const val MIN_LENGTH = 6
    private const val MAX_LENGTH = 40
    private const val POOL = 4
    private val POLITE = Regex("""(요|니다|니까)[.?!~]*$""")
    private val SPACES = Regex("""\s+""")
    private const val LEADING = "\"'“”‘’([「『"
    private const val TRAILING = ".,!?~…·\"'“”‘’)]」』:;"
}
