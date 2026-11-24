package com.jorge.koreandiary.dictionary

import com.jorge.koreandiary.ai.ExampleSentence
import com.jorge.koreandiary.ai.ItemExplanation
import java.text.Normalizer

/**
 * «Más sobre esto» y las tarjetas de sugerencias desde el 한국어기초사전 (decidido con Jorge el
 * 2026-11-22): significado, otros sentidos, origen, formas y ejemplos, sin IA. Si el diccionario
 * no tiene la palabra o la gramática, [explain] da null y la app le pregunta a la IA.
 */
object DictionaryExplain {

    /**
     * [meaning]: lo que dice tu lista, para elegir el homónimo y el sentido que estudias (장 «hoja»
     * y no 장 «mercado»). Llamar fuera del hilo principal: lee el diccionario.
     */
    fun explain(dictionary: KoreanDictionary, label: String, isGrammar: Boolean, meaning: String? = null, analyzer: SentenceAnalyzer? = null): ItemExplanation? =
        if (isGrammar) explainGrammar(dictionary, label) else explainWord(dictionary, label, meaning, analyzer)

    /** ¿Lo tiene el diccionario? Barato: no lee el detalle. */
    fun covers(dictionary: KoreanDictionary, label: String, isGrammar: Boolean): Boolean =
        if (isGrammar) grammarEntries(dictionary, label).isNotEmpty() else dictionary.isWord(label)

    // ---------------------------------------------------------------- palabras

    /**
     * La palabra que estudias en el diccionario: el homónimo y el sentido que más se parecen a lo que
     * dice tu lista ([meaning]); si empatan (o no hay lista), el homónimo más común y su primer
     * sentido. [others]: los demás homónimos, para mencionarlos.
     */
    class WordChoice(val entry: DictEntry, val detail: DictDetail, val senses: List<DictSense>, val main: Int, val others: List<Pair<DictEntry, List<DictSense>>>) {
        /** Los sentidos con el elegido primero. */
        val ordered: List<DictSense> get() = listOf(senses[main]) + senses.filterIndexed { i, _ -> i != main }
    }

    fun wordChoice(dictionary: KoreanDictionary, label: String, meaning: String?): WordChoice? {
        val entries = dictionary.entries(label).filter { !it.grammar }
        if (entries.isEmpty()) return null
        val details = entries.take(MAX_HOMONYMS).mapNotNull { e -> dictionary.detail(e)?.let { e to it } }
            .map { (e, d) -> Triple(e, d, d.senses.filter { it.lemma != null || it.definition != null }) }
            .filter { it.third.isNotEmpty() }
        if (details.isEmpty()) return null
        val pick = details.flatMap { (e, d, ss) -> ss.indices.map { Pick(e, d, ss, it) } }
            .maxWithOrNull(compareBy { score(meaning, it.senses[it.main]) })
            ?: return null
        return WordChoice(pick.entry, pick.detail, pick.senses, pick.main, details.filter { it.first != pick.entry }.map { it.first to it.third })
    }

    /** Lo que se muestra como significado de un sentido: su traducción o, si no tiene, su definición. */
    fun senseMeaning(s: DictSense): String =
        s.lemma?.let(::lemmaText) ?: s.definition?.let(::cleanDefinition)?.let { shorten(it, 90) }.orEmpty()

    private fun explainWord(dictionary: KoreanDictionary, label: String, meaning: String?, analyzer: SentenceAnalyzer? = null): ItemExplanation? {
        val choice = wordChoice(dictionary, label, meaning) ?: return null
        val entry = choice.entry
        val detail = choice.detail
        val senses = choice.senses
        val main = choice.main
        val first = senses[main]
        val lemma = first.lemma?.let(::lemmaText)
        val definition = first.definition?.let(::cleanDefinition)
        val others = senses.indices.filter { it != main }
        val usage = buildList {
            if (lemma != null && definition != null) add(definition)
            if (others.isNotEmpty()) {
                add("Otros sentidos: " + others.take(4).joinToString(" · ") { i -> senseShort(senses[i], avoid = lemma) })
            }
            val phrases = (listOf(first) + others.map { senses[it] }).flatMap { it.phrases }.distinct().take(3)
            if (phrases.isNotEmpty()) add("Va con: " + phrases.joinToString(" · ") { it.trim().trimEnd('.') })
            val homonyms = choice.others.take(3)
            if (homonyms.isNotEmpty()) add("También «${entry.form}»: " + homonyms.joinToString(" · ") { senseShort(it.second.first(), avoid = lemma) })
        }.joinToString("\n").ifBlank { null }
        val examples = (listOf(first) + others.map { senses[it] }).flatMap { it.examples }
            .map { it.trim() }.distinct()
            .filter { usesWord(dictionary, entry, it, analyzer) }
            .take(MAX_EXAMPLES)
            .map { ExampleSentence(it, null) }
        return ItemExplanation(
            form = entry.form,
            meaning = lemma ?: definition.orEmpty(),
            usage = usage,
            conjugation = entry.conjugations.takeIf { entry.isVerb && it.isNotEmpty() }?.let { "Formas: " + it.joinToString(" · ") },
            examples = examples,
            structure = detail.origin?.let { DictionaryLookup.originParts(entry.form, it) },
            fromDictionary = true,
            extras = DictionaryExtrasOf.of(dictionary, entry, detail, choice.ordered).takeIf { !it.isEmpty },
        )
    }

    private data class Pick(val entry: DictEntry, val detail: DictDetail, val senses: List<DictSense>, val main: Int)

    /**
     * «hoja» o el comienzo de su definición, para la lista de otros sentidos. Si su traducción es la
     * misma que la principal ([avoid]: 가다 «ir», «ir», «ir»), va la definición, que sí los distingue.
     */
    private fun senseShort(s: DictSense, avoid: String? = null, max: Int = 60): String {
        val avoided = avoid?.split(',')?.map { it.trim() }?.toSet().orEmpty()
        // También si solo repite parte de la principal: «mercado» junto a «mercado, feria».
        val lemma = s.lemma?.let(::lemmaText)?.takeIf { l -> !avoided.containsAll(l.split(',').map { it.trim() }) }
        return lemma ?: s.definition?.let(::cleanDefinition)?.let { shorten(it, max) } ?: s.lemma?.let(::lemmaText).orEmpty()
    }

    /** Corta en un espacio, no en medio de una palabra. */
    private fun shorten(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.take(max).substringBeforeLast(' ').trimEnd(',', ';', ' ')
        return (if (cut.length >= max / 2) cut else text.take(max)) + "…"
    }

    /** La traducción en español de Perú y en minúscula (el diccionario a veces pone «Ir»). */
    private fun lemmaText(lemma: String): String {
        val l = PeruSpanish.adaptLemma(lemma)
        return if (l.length > 1 && l[0].isUpperCase() && l[1].isLowerCase()) l.replaceFirstChar { it.lowercase() } else l
    }

    /** Cuántas palabras de [meaning] (tu lista) aparecen en el sentido: 0 si no hay nada que comparar. */
    private fun score(meaning: String?, s: DictSense): Int {
        if (meaning.isNullOrBlank()) return 0
        val words = plain(meaning).split(Regex("[^a-zñ]+")).filter { it.length >= 3 && it !in STOPWORDS }.toSet()
        if (words.isEmpty()) return 0
        val text = plain(listOfNotNull(s.lemma?.let(PeruSpanish::adaptLemma), s.lemma, s.definition).joinToString(" "))
        return words.count { w -> Regex("\\b${Regex.escape(w)}").containsMatchIn(text) }
    }

    private fun plain(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace('ñ', 'n')

    /**
     * ¿El ejemplo usa la palabra? Las oraciones del diccionario sí; algunas son la otra línea de un
     * diálogo y no la tienen. Un verbo puede ir conjugado (먹다 → 먹었어요, 듣다 → 들어요).
     */
    fun usesWord(dictionary: KoreanDictionary, entry: DictEntry, sentence: String, analyzer: SentenceAnalyzer? = null): Boolean {
        // De dos sílabas o más basta con encontrarla; una sola (장, 가다) también está dentro de otras
        // palabras (시장, 가방): ahí se mira palabra por palabra.
        val key = if (entry.isVerb) entry.form.dropLast(1) else entry.form
        if (key.length >= 2 && sentence.contains(key)) return true
        // Con el analizador decide la oración: en «음악을 들어요» está 듣다 y no 들다.
        analyzer?.let { a -> runCatching { SentenceLemmas.words(a, dictionary, sentence) }.getOrNull() }
            ?.let { words -> return words.any { it.lemma == entry.form } }
        return sentence.split(Regex("\\s+")).any { token -> dictionary.baseForms(token).any { it.id == entry.id } }
    }

    // ---------------------------------------------------------------- gramática

    /**
     * La gramática y sus otras formas, aunque tu lista diga una sola: -습니다 y -ㅂ니다, -어요 y -아요,
     * 이랑 y 랑. La principal primero; vacío si el diccionario no la tiene.
     */
    fun grammarFamily(dictionary: KoreanDictionary, label: String): List<DictEntry> {
        val found = grammarEntries(dictionary, label)
        return (found + found.flatMap { e ->
            counterparts(e.form).flatMap { c -> dictionary.entries(c).filter { it.grammar == e.grammar && it.pos == e.pos } }
        }).distinctBy { it.id }
    }

    private fun explainGrammar(dictionary: KoreanDictionary, label: String): ItemExplanation? {
        val entries = grammarFamily(dictionary, label)
        val main = entries.firstOrNull() ?: return null
        val details = entries.mapNotNull { e -> dictionary.detail(e)?.let { e to it } }
        val mainSenses = details.firstOrNull { it.first == main }?.second?.senses.orEmpty().filter { it.definition != null || it.lemma != null }
        val first = mainSenses.firstOrNull() ?: return null
        val definition = first.definition?.let(::cleanDefinition) ?: first.lemma.orEmpty()
        val usage = buildList {
            if (mainSenses.size > 1) add("Otros usos: " + mainSenses.drop(1).take(3).mapIndexed { i, s -> "${i + 2}) " + senseShort(s, max = 90) }.joinToString(" · "))
            val patterns = mainSenses.flatMap { it.patterns }.distinct().take(2)
            if (patterns.isNotEmpty()) add("Se usa con: " + patterns.joinToString(" · "))
        }.joinToString("\n").ifBlank { null }
        // Ejemplos de todas sus formas (-을 수 있다 y -ㄹ 수 있다), solo los que de verdad la usan.
        val examples = details.flatMap { (e, d) ->
            val expression = e.pos == "품사 없음"
            d.senses.flatMap { it.examples }.map { it.trim() }.filter { ex ->
                when {
                    !e.grammar -> ex.contains(e.form) // 때문: va suelto, después de un espacio
                    !GrammarMatch.locatable(e.form) -> true
                    else -> GrammarMatch.find(ex, e.form, expression) != null
                }
            }
        }.distinct().sortedBy { if (POLITE.containsMatchIn(it)) 0 else 1 }.take(MAX_EXAMPLES).map { ExampleSentence(it, null) }
        return ItemExplanation(
            form = main.form,
            meaning = definition,
            usage = usage,
            conjugation = attachNote(entries.map { it.form }.distinct()),
            examples = examples,
            structure = null,
            fromDictionary = true,
        )
    }

    /**
     * Las entradas de gramática del diccionario para una notación de la app, la principal primero.
     * Todas del mismo tipo que la primera que exista: «은/는» son las partículas, no las terminaciones
     * «-은» y «-는». Si no hay gramática, un sustantivo dependiente («N 때문에» → 때문).
     */
    fun grammarEntries(dictionary: KoreanDictionary, label: String): List<DictEntry> {
        val candidates = grammarCandidates(label)
        val found = candidates.mapNotNull { c -> dictionary.entries(c).filter { it.grammar }.takeIf { it.isNotEmpty() }?.let { c to it } }
        val dash = found.firstOrNull()?.first?.startsWith("-")
            ?: return candidates.filter { !it.startsWith("-") }.flatMap { c -> dictionary.entries(c).filter { it.pos == "의존 명사" } }.distinctBy { it.id }.take(1)
        return found.filter { it.first.startsWith("-") == dash }.flatMap { it.second }.distinctBy { it.id }
    }

    /**
     * Las otras formas posibles de una gramática según lo que va antes, para buscarlas en el
     * diccionario: -습니다 ↔ -ㅂ니다, -으면 ↔ -면, -을까요 ↔ -ㄹ까요, -아요 ↔ -어요, 이랑 ↔ 랑, 은 ↔ 는.
     * Solo cuentan las que el diccionario tiene.
     */
    fun counterparts(form: String): List<String> {
        val dash = form.startsWith("-")
        val body = form.removePrefix("-")
        val first = body.firstOrNull() ?: return emptyList()
        val p = Hangul.parts(first)
        val out = mutableListOf<String>()
        withoutConnector(body)?.let { out += it }
        // -는다고 ↔ -ㄴ다고 (먹는다고, 간다고).
        if (dash && body.startsWith("는다")) out += "ㄴ" + body.drop(1)
        if (dash && body.startsWith("ㄴ다")) out += "는" + body.drop(1)
        if (Hangul.isJamo(first)) {
            if (body.startsWith("ㅂ니")) out += "습" + body.drop(1)
            val f = Hangul.finalIndex(first)
            if (f > 0) out += Hangul.syllable(Hangul.INITIALS.indexOf('ㅇ'), Hangul.VOWELS.indexOf('ㅡ'), f) + body.drop(1)
        } else if (dash && p != null && !(p.initialJamo == 'ㅇ' && p.vowelJamo == 'ㅡ') && !body.startsWith("습")) {
            out += "으$body"
        }
        if (dash && p != null && p.initialJamo == 'ㅇ' && (p.vowelJamo == 'ㅏ' || p.vowelJamo == 'ㅓ')) {
            val other = Hangul.VOWELS.indexOf(if (p.vowelJamo == 'ㅏ') 'ㅓ' else 'ㅏ')
            out += Hangul.syllable(p.initial, other, p.final) + body.drop(1)
        }
        if (!dash) {
            if (body.startsWith("이") && body.length > 1) out += body.drop(1) else out += "이$body"
            PARTICLE_PAIRS.forEach { (a, b) -> if (body == a) out += b; if (body == b) out += a }
        }
        return out.filter { it.isNotEmpty() && it != body }.map { if (dash) "-$it" else it }.distinct()
    }

    /**
     * Las formas en que el diccionario puede escribir una gramática de la app: «V-느라고» → «-느라고»;
     * «-(으)ㄹ 수 있다» → «-을 수 있다», «-ㄹ 수 있다»; «-아/어서» → «-어서», «-아서»; «N이랑» → «이랑».
     */
    fun grammarCandidates(label: String): List<String> {
        val clean = label.replace(BRACKETS, "").replace(NOTES, "").replace(DIGITS, "").replace(PUNCTUATION, "").replace('~', '-').trim()
        var forms = clean.split(',', '|').map { it.trim() }.filter { it.isNotEmpty() }.map { v ->
            v.replace(LEADING_TAG, "-").replace(LEADING_NOUN, "").replace(INNER_TAG, " ").replace(Regex("\\s+"), " ").trim()
        }
        // (으) pegado a una letra suelta se une con ella: (으)ㄹ → 을 o ㄹ.
        for ((pattern, options) in COMBOS) forms = forms.flatMap { f -> if (pattern in f) options.map { f.replace(pattern, it) } else listOf(f) }
        for ((pattern, options) in OPTIONAL) forms = forms.flatMap { f -> if (pattern in f) options.map { f.replace(pattern, it) } else listOf(f) }
        // A/B: una sílaba u otra, con lo que sigue en común (-아/어서 → -어서, -아서). Primero la de la
        // derecha, que suele ser la general.
        forms = forms.flatMap { f ->
            val m = SLASH.find(f) ?: return@flatMap listOf(f)
            val (a, b) = m.destructured
            listOf(f.replaceRange(m.range, b), f.replaceRange(m.range, a))
        }
        // «-기 때문에» está como «-기 때문» (y «N 때문에», como el sustantivo 때문); «-은 대신에», con el 에.
        forms = forms.flatMap { f -> if (f.removePrefix("-").length >= 3 && f.endsWith("에")) listOf(f, f.dropLast(1)) else listOf(f) }
        // Con y sin guion: las terminaciones lo llevan (-느라고), las partículas no (이랑).
        forms = forms.flatMap { f -> listOf(f, if (f.startsWith("-")) f.removePrefix("-") else "-$f") }
        return forms.map { it.trim() }.filter { f -> f.any { Hangul.isSyllable(it) } }.distinct().take(16)
    }

    /**
     * Cómo se une, cuando el diccionario trae sus formas: «-을 수 de consonante, -ㄹ 수 tras vocal»,
     * «-아서 si la última vocal es ㅏ u ㅗ», «이랑 tras consonante». Null con una sola forma.
     */
    fun attachNote(forms: List<String>): String? {
        if (forms.size < 2) return null
        for (a in forms) for (b in forms) {
            if (a == b) continue
            val ba = a.removePrefix("-")
            val bb = b.removePrefix("-")
            val particle = !a.startsWith("-") && !b.startsWith("-")
            if (withoutConnector(ba) == bb || (ba.startsWith("는") && bb == "ㄴ" + ba.drop(1))) {
                // 으 o 습 delante: tras consonante; sin ellos, tras vocal (y tras ㄹ, que se cae: 갈 수, 만들면).
                val rest = if (particle && !ba.startsWith("으")) "tras vocal" else "tras vocal o ㄹ"
                return "Tras consonante: $a · $rest: $b"
            }
            if (particle && (ba == "이$bb" || (ba to bb) in PARTICLE_PAIRS)) return "Tras consonante: $a · tras vocal: $b"
            val pa = ba.firstOrNull()?.let(Hangul::parts)
            val pb = bb.firstOrNull()?.let(Hangul::parts)
            if (!particle && pa != null && pb != null && ba.drop(1) == bb.drop(1) && pa.initialJamo == 'ㅇ' && pb.initialJamo == 'ㅇ' &&
                pa.vowelJamo == 'ㅏ' && pb.vowelJamo == 'ㅓ' && pa.final == pb.final
            ) {
                val ha = Hangul.syllable(Hangul.INITIALS.indexOf('ㅎ'), Hangul.VOWELS.indexOf('ㅐ'), pa.final) + ba.drop(1)
                return "Si la última vocal es ㅏ u ㅗ: $a · con las demás: $b · con 하다: $ha"
            }
        }
        return null
    }

    /** Sin el 으 (o 습) del comienzo, como queda tras vocal: 을 수 있다 → ㄹ 수 있다, 으면 → 면, 습니다 → ㅂ니다. */
    private fun withoutConnector(body: String): String? {
        val p = body.firstOrNull()?.let(Hangul::parts) ?: return null
        return when {
            body.startsWith("습") -> "ㅂ" + body.drop(1)
            p.initialJamo == 'ㅇ' && p.vowelJamo == 'ㅡ' && p.final == 0 && body.length > 1 -> body.drop(1)
            p.initialJamo == 'ㅇ' && p.vowelJamo == 'ㅡ' && p.final != 0 -> p.finalJamo + body.drop(1)
            else -> null
        }
    }

    /**
     * Qué expresa una gramática, corto, desde su definición del diccionario, para la línea «Usa:» del
     * repaso cuando no hay IA: «Expresión que indica el tiempo que dura un acto o estado que representa
     * el comentario anterior.» → «el tiempo que dura un acto o estado».
     */
    fun grammarGloss(definition: String): String {
        var g = cleanDefinition(definition).replace(Regex("""^\([^)]*\)\s*"""), "")
        g = g.replace(GLOSS_LEAD, "").replace(GLOSS_TRAIL, "").trim().trimEnd('.', ' ', ',')
        return shorten(g.ifBlank { cleanDefinition(definition) }, 70)
    }

    /**
     * La definición sin la etiqueta en mayúsculas del diccionario, en palabras de la app:
     * «(TRATAMIENTO HONORÍFICO GENERAL) Desinencia…» → «(cortés, 해요체) Desinencia…».
     */
    fun cleanDefinition(definition: String): String {
        val d = PeruSpanish.adapt(definition.trim())
        val m = Regex("""^\(([A-ZÁÉÍÓÚÑ ]+)\)\s*""").find(d) ?: return d
        val label = m.groupValues[1].trim()
        return "(" + (REGISTERS[label] ?: label.lowercase()) + ") " + d.substring(m.range.last + 1)
    }

    /** «Expresión que (se usa para) indica(r)…», «Desinencia conectora que se usa cuando…», «Posposición que…». */
    private val GLOSS_LEAD = Regex(
        """^(Expresión|Desinencia( conectora| de terminación| final)?|Posposición|Sufijo)( que)?( usa el hablante)?( se)?( usa| utiliza| emplea)?( para| cuando| al)?( indicar| mostrar| expresar| señalar| manifestar| indica| muestra| expresa)?( que)?\s+""",
    )
    /** «…lo que dice el comentario anterior», «…que representa la palabra anterior de la cláusula». */
    private val GLOSS_TRAIL = Regex(
        """\s*(\b(de )?lo que |\bque |\bel acto que )?(representa|dice|indica|expresa|se dice en) (el comentario|la palabra|el contenido|la parte)( anterior| precedente)?( de la cláusula)?\.?$""",
    )

    private const val MAX_EXAMPLES = 3
    private const val MAX_HOMONYMS = 8
    private val POLITE = Regex("""요[.?!]?\s*$|니다[.?!]?\s*$|니까[.?!]?\s*$""")
    private val STOPWORDS = setOf("para", "que", "con", "una", "uno", "los", "las", "del", "por", "como", "algo", "alguien", "cosa", "hacer", "estar", "ser", "muy", "mas")
    private val REGISTERS = mapOf(
        "TRATAMIENTO HONORÍFICO GENERAL" to "cortés, 해요체",
        "TRATAMIENTO DE MODESTIA GENERAL" to "informal, 반말",
        "TRATAMIENTO DE MODESTIA MÁXIMA" to "informal o escrito, 해라체",
        "TRATAMIENTO HONORÍFICO MÁXIMO" to "muy formal, 합쇼체",
        "TRATAMIENTO DE MODESTIA ORDINARIA" to "a un inferior, poco usado, 하게체",
        "TRATAMIENTO HONORÍFICO ORDINARIO" to "anticuado, 하오체",
        "TRATAMIENTO HONORÍFICO" to "honorífico",
    )
    private val PARTICLE_PAIRS = setOf("은" to "는", "을" to "를", "과" to "와", "이" to "가")
    private val BRACKETS = Regex("""\[[^\]]*]""")
    /** Notas entre paréntesis con letras latinas o barras: «(피동 / Passive)». */
    private val NOTES = Regex("""\([^)]*[A-Za-z/][^)]*\)""")
    private val DIGITS = Regex("""[0-9]""")
    private val LEADING_TAG = Regex("""^\s*(A/V|V/A|A|V)\s*-""")
    private val LEADING_NOUN = Regex("""^\s*N\s*(?=[가-힣(])""")
    /** «-고 V-» o «A/V-고 싶다» con marcas en medio: se quitan. */
    private val INNER_TAG = Regex("""\s(A/V|V/A|A|V|N)\s*-?\s""")
    private val COMBOS = listOf(
        "(으)ㄹ" to listOf("을", "ㄹ"), "(으)ㄴ" to listOf("은", "ㄴ"), "(으)ㅁ" to listOf("음", "ㅁ"), "(으)ㅂ" to listOf("읍", "ㅂ"),
        "(스)ㅂ" to listOf("습", "ㅂ"),
    )
    private val OPTIONAL = listOf("(으)" to listOf("으", ""), "(이)" to listOf("이", ""), "(느)" to listOf("느", ""), "(스)" to listOf("스", ""), "(아)" to listOf("아", ""))
    private val SLASH = Regex("""([가-힣ㄱ-ㅎ])/([가-힣ㄱ-ㅎ])""")
    private val PUNCTUATION = Regex("""[?!.…]""")
}
