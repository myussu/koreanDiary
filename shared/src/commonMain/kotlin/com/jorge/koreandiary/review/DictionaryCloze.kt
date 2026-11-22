package com.jorge.koreandiary.review

import com.jorge.koreandiary.dictionary.DictEntry
import com.jorge.koreandiary.dictionary.DictionaryExplain
import com.jorge.koreandiary.dictionary.GrammarMatch
import com.jorge.koreandiary.dictionary.KoreanDictionary
import kotlin.random.Random

/**
 * Oraciones del 한국어기초사전 para el repaso rápido (decidido con Jorge el 2026-11-22): lo que no
 * tiene oración tuya sale con una del diccionario antes que pedírsela a la IA. La pista es
 * «Falta: …» con el significado; la traducción, solo si la pides («Traducir»).
 */
object DictionaryCloze {

    /**
     * Una tarjeta para [target] con una oración del diccionario: corta, cortés (-요) si hay, y donde
     * se encuentra el ítem. [random] cambia la oración entre sesiones. Null si no hay ninguna.
     */
    fun card(dictionary: KoreanDictionary, target: ClozeTarget, random: Random = Random.Default): ClozeCard? {
        val grammar = target.kind == ClozeKind.GRAMMAR
        val family = if (grammar) DictionaryExplain.grammarFamily(dictionary, target.label) else emptyList()
        val sentences: List<String>
        val meaning: String?
        if (grammar) {
            if (family.isEmpty()) return null
            sentences = family.flatMap { e -> dictionary.detail(e)?.senses.orEmpty().flatMap { it.examples } }
            meaning = dictionary.detail(family.first())?.senses?.firstOrNull()?.let(DictionaryExplain::senseMeaning)
        } else {
            val choice = DictionaryExplain.wordChoice(dictionary, target.label, target.meaning) ?: return null
            sentences = choice.ordered.flatMap { it.examples }
            meaning = DictionaryExplain.senseMeaning(choice.senses[choice.main])
        }
        val options = sentences.map { it.trim().replace(SPACES, " ") }.distinct()
            .filter { it.length in MIN_LENGTH..MAX_LENGTH }
            .mapNotNull { s ->
                val b = if (grammar) grammarBlank(s, family) else wordBlank(dictionary, s, target.label, target.kind)
                b?.let { s to it }
            }
        if (options.isEmpty()) return null
        // Corteses primero (el repaso va en -아요/어요) y de esas, las más cortas.
        val pool = options.filter { POLITE.containsMatchIn(it.first) }.ifEmpty { options }.sortedBy { it.first.length }.take(POOL)
        val (sentence, blank) = pool[random.nextInt(pool.size)]
        return ClozeCard(
            code = target.code, label = target.label, kind = target.kind,
            meaning = target.meaning?.takeIf { it.isNotBlank() } ?: meaning?.takeIf { it.isNotBlank() },
            sentence = sentence, start = blank.start, answer = blank.text,
            base = when (target.kind) {
                ClozeKind.VERB -> target.label.trim()
                ClozeKind.GRAMMAR -> grammarBase(dictionary, sentence, blank)
                ClozeKind.WORD -> null
            },
            fromDictionary = true,
        )
    }

    /**
     * El ítem en una oración (también tuya, cuando [Cloze.blankIn] no lo encuentra): un verbo
     * irregular (고팠다, 덥군요, 늦어, 더워요) o una gramática escrita de otra forma (-ㄹ 수 있다).
     */
    fun blank(dictionary: KoreanDictionary, sentence: String, label: String, kind: ClozeKind): ClozeBlank? =
        if (kind == ClozeKind.GRAMMAR) grammarBlank(sentence, DictionaryExplain.grammarFamily(dictionary, label))
        else wordBlank(dictionary, sentence, label, kind)

    /**
     * Un sustantivo, al comienzo de una palabra y solo él (장으로 → 장, no el 장 de 시장); un verbo o
     * adjetivo, la palabra conjugada entera.
     */
    fun wordBlank(dictionary: KoreanDictionary, sentence: String, label: String, kind: ClozeKind): ClozeBlank? {
        val form = label.trim()
        if (form.isEmpty()) return null
        val words = tokens(sentence)
        return if (kind == ClozeKind.VERB) {
            words.lastOrNull { w -> dictionary.baseForms(w.text).any { it.form == form } }
                ?: words.lastOrNull { w -> Cloze.conjugated(Cloze.stem(form), w.text) }
        } else {
            words.firstOrNull { w -> w.text.startsWith(form) && (w.text == form || dictionary.baseForms(w.text).any { it.form == form }) }
                ?.let { ClozeBlank(it.start, form) }
        }
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

    /** Para la pista «Con: …»: el verbo o adjetivo al que va unida la gramática (읽을 수 있어요 → 읽다). */
    private fun grammarBase(dictionary: KoreanDictionary, sentence: String, blank: ClozeBlank): String? {
        val first = tokens(sentence).firstOrNull { blank.start >= it.start && blank.start < it.start + it.text.length } ?: return null
        return dictionary.baseForms(first.text).firstOrNull { it.isVerb }?.form
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
