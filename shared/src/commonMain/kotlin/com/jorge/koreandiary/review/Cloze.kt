package com.jorge.koreandiary.review

import com.jorge.koreandiary.ai.ParseException
import com.jorge.koreandiary.ai.Prompts
import com.jorge.koreandiary.ai.extractJsonObject
import com.jorge.koreandiary.data.standard.GrammarKeys
import com.jorge.koreandiary.srs.Fsrs
import org.json.JSONObject

/** Cómo se pide un ítem en el repaso rápido: cada tipo tiene sus pistas. */
enum class ClozeKind {
    /** Sustantivo, adverbio u otro: se escribe tal cual. */
    WORD,
    /** Verbo o adjetivo: va conjugado. */
    VERB,
    GRAMMAR,
}

/** Dónde está el hueco en la oración. */
data class ClozeBlank(val start: Int, val text: String)

/**
 * Un ítem que entra al repaso: de tu lista y, si la hay, una oración tuya ya corregida con el hueco
 * encontrado en el celular. Sin oración, la IA escribe una.
 */
data class ClozeTarget(
    /** "w12" o "g3". */
    val code: String,
    /** Forma de diccionario o patrón, como está en tu lista. */
    val label: String,
    val kind: ClozeKind,
    val meaning: String? = null,
    val sentence: String? = null,
    val blank: ClozeBlank? = null,
) {
    val own: Boolean get() = sentence != null
}

/** Una tarjeta del repaso: la oración con el hueco, la traducción y lo que hace falta para las pistas. */
data class ClozeCard(
    val code: String,
    val label: String,
    val kind: ClozeKind,
    val meaning: String?,
    val sentence: String,
    val start: Int,
    /** Lo que va en el hueco, tal como está en la oración. */
    val answer: String,
    /** Traducción con lo que corresponde al hueco entre corchetes; null sin IA. */
    val translation: String? = null,
    /** Verbo o adjetivo: su forma de diccionario. Gramática: el verbo o adjetivo al que va unida. */
    val base: String? = null,
    /** Verbo o adjetivo: la terminación que lleva («-았/었어요»). */
    val ending: String? = null,
    /** La oración es tuya (de una página), no de la IA. */
    val own: Boolean = false,
    /** La oración es del 한국어기초사전 ([DictionaryCloze]): sin traducción, con «Falta: …» y «Traducir». */
    val fromDictionary: Boolean = false,
) {
    val before: String get() = sentence.substring(0, start)
    val after: String get() = sentence.substring(start + answer.length)
}

/** Un trozo de una palabra de la oración; [blank]: es (parte de) lo que va en el hueco. */
data class ClozePiece(val text: String, val blank: Boolean)

/** Una palabra de la oración (o los espacios entre palabras) y lo que se busca al tocarla (null: no se toca). */
data class ClozeWord(val pieces: List<ClozePiece>, val tap: String?)

/**
 * Repaso rápido (decidido con Jorge el 2026-11-15): en vez de tarjetas como Anki, oraciones con un
 * espacio en blanco. Primero tus oraciones (ya corregidas); para lo que nunca usaste, una del
 * 한국어기초사전 ([DictionaryCloze], desde el 2026-11-22) y solo si no hay, de la IA (una sola
 * llamada por sesión). Las de la IA traen su traducción con lo que falta resaltado; las tuyas y las
 * del diccionario, «Falta: …» con el significado y «Traducir» si la quieres. Respondes a mano. Tres
 * pistas según el tipo; la calificación sale de cuántas usaste.
 */
object Cloze {

    // ---------------------------------------------------------------- hueco en tus oraciones

    private val TRAILING = Regex("""[.,!?~…·"'“”‘’)\]」』:;]+$""")
    private val LEADING = Regex("""^["'“”‘’(\[「『]+""")

    /** Las palabras de la oración (separadas por espacios), sin la puntuación de los bordes, con su posición. */
    private fun tokens(sentence: String): List<ClozeBlank> =
        Regex("""\S+""").findAll(sentence).mapNotNull { m ->
            val lead = LEADING.find(m.value)?.value?.length ?: 0
            val core = m.value.drop(lead).replace(TRAILING, "")
            if (core.isEmpty()) null else ClozeBlank(m.range.first + lead, core)
        }.toList()

    /** La raíz de un verbo o adjetivo: sin el 다 final (공부하다 → 공부하, 먹다 → 먹). */
    fun stem(label: String): String {
        val form = label.trim()
        return if (form.length > 1 && form.endsWith("다")) form.dropLast(1) else form
    }

    /**
     * Busca el ítem en una oración tuya: un sustantivo tal cual; un verbo, la palabra conjugada entera
     * (también contraída: 가다 → 갔어요, 공부하다 → 공부했어요); una terminación, la palabra que la
     * lleva (o las dos, si va en dos: 가고 싶어요); una partícula, solo la partícula.
     */
    fun blankIn(sentence: String, label: String, kind: ClozeKind): ClozeBlank? {
        val words = tokens(sentence)
        return when (kind) {
            ClozeKind.WORD -> {
                val form = label.trim()
                if (form.isEmpty()) null
                else words.firstOrNull { it.text.startsWith(form) }?.let { ClozeBlank(it.start, form) }
                    ?: sentence.indexOf(form).takeIf { it >= 0 }?.let { ClozeBlank(it, form) }
            }
            ClozeKind.VERB -> {
                val stem = stem(label)
                if (stem.isEmpty()) null else words.lastOrNull { conjugated(stem, it.text) }
            }
            ClozeKind.GRAMMAR -> grammarBlank(sentence, words, label)
        }
    }

    private fun grammarBlank(sentence: String, words: List<ClozeBlank>, label: String): ClozeBlank? {
        // Las claves más largas primero: "-고 싶다" antes que "-고". Las que tienen letras sueltas
        // ("ㄹ 수 있다") no se pueden buscar así: esas las marca la IA.
        for (key in GrammarKeys.keys(label).sortedByDescending { it.length }) {
            // Una expresión que termina en 다 se conjuga: "고싶다" se busca como "고싶" (가고 싶어요).
            val bare = key.substringAfter(':').let { if (it.length > 2 && it.endsWith("다")) it.dropLast(1) else it }
            if (bare.isEmpty() || bare.any { it in 'ㄱ'..'ㅣ' }) continue
            if (key.startsWith("P:")) {
                // Una partícula va pegada al sustantivo: el hueco es solo ella.
                val w = words.lastOrNull { it.text.length > bare.length && it.text.endsWith(bare) } ?: continue
                return ClozeBlank(w.start + w.text.length - bare.length, bare)
            }
            // Una terminación de una sílaba que también es partícula o final de sustantivo («-는» en
            // 저는, «-고» en 그리고, «-기» en 고기) no se puede distinguir aquí: el hueco caía en una
            // palabra que no la usa y el repaso calificaba mal. Esas las marca la IA en su oración.
            if (bare.length == 1 && bare[0] in AMBIGUOUS_ENDINGS) continue
            for (i in words.indices.reversed()) {
                for (span in 1..minOf(3, words.size - i)) {
                    val joined = (i until i + span).joinToString("") { words[it].text }
                    // Una terminación de una sílaba va al final de una palabra, sin ser toda la palabra.
                    val found = if (bare.length == 1) span == 1 && joined.length >= 2 && joined.endsWith(bare) else joined.contains(bare)
                    if (!found) continue
                    val first = words[i]
                    val last = words[i + span - 1]
                    return ClozeBlank(first.start, sentence.substring(first.start, last.start + last.text.length))
                }
            }
        }
        return null
    }

    /** Las sílabas del hangul: inicial, vocal y final (índices de Unicode). */
    private data class Jamo(val initial: Int, val vowel: Int, val final: Int)

    private fun jamo(c: Char): Jamo? {
        if (c !in '가'..'힣') return null
        val n = c - '가'
        return Jamo(n / (21 * 28), (n / 28) % 21, n % 28)
    }

    // Vocales (índice Unicode): ㅏ0 ㅐ1 ㅓ4 ㅕ6 ㅗ8 ㅘ9 ㅙ10 ㅚ11 ㅜ13 ㅝ14 ㅡ18 ㅣ20.
    private val VOWEL_CONTRACTIONS = mapOf(8 to 9, 13 to 14, 18 to 4, 20 to 6, 11 to 10)
    // Finales posibles al conjugar: ninguna, ㄴ, ㄹ, ㅂ (ㅂ니다) y ㅆ (pasado).
    private val CONJUGATED_FINALS = setOf(0, 4, 8, 17, 20)
    private const val HIEUT = 18
    /** Lo que suele seguir a una raíz de una sílaba al conjugarla (가요, 가고, 갔어요…), no a un sustantivo (가방, 가족). */
    private val VERBAL_NEXT = "아어았었요고서면는니지세야도기려자다네며시셨으을은겠".toSet()
    /**
     * Palabras comunes que empiezan como una raíz de una sílaba conjugada y no lo son: 하지만 no es
     * 하다, 가을 no es 가다, 나는 y 나도 son «yo», 사자 es el león y 할아버지 no es «할».
     */
    private val NOT_A_VERB = listOf("하지만", "할아버지", "가을", "나는", "나도", "사자")
    /**
     * Terminaciones de una sílaba que no se buscan en el celular: son también partículas (저는,
     * 밥을, 집에) o el final de muchos sustantivos y conectores (그리고, 고기, 아버지, 의자, 어머니, 가게).
     */
    private val AMBIGUOUS_ENDINGS = "은는을를이가도만에의로와과나고기지자니게".toSet()

    /**
     * ¿Esta palabra es el verbo conjugado? Empieza por la raíz o la contrae (가 → 갔, 오 → 왔,
     * 하 → 했/해, 듣 → 들, 덥 → 더). Con una raíz de una sílaba lo que sigue tiene que sonar a
     * conjugación, para no confundir 가다 con 가방 o 하다 con 한국.
     */
    fun conjugated(stem: String, word: String): Boolean {
        if (stem.isEmpty()) return false
        val single = stem.length == 1
        if (single && NOT_A_VERB.any { word.startsWith(it) }) return false
        if (word.startsWith(stem)) return !single || (word.length > 1 && word[1] in VERBAL_NEXT)
        if (word.length < stem.length || (single && word.length < 2)) return false
        if (!word.startsWith(stem.dropLast(1))) return false
        val a = jamo(stem.last()) ?: return false
        val b = jamo(word[stem.length - 1]) ?: return false
        if (a.initial != b.initial) return false
        val vowelOk = a.vowel == b.vowel || VOWEL_CONTRACTIONS[a.vowel] == b.vowel || (a.initial == HIEUT && a.vowel == 0 && b.vowel == 1)
        val finalOk = b.final == a.final || b.final in CONJUGATED_FINALS
        val nextOk = !single || word[1] in VERBAL_NEXT
        return vowelOk && finalOk && nextOk
    }

    /** Tu oración con sus correcciones aplicadas (original → corrección), para repasar la versión buena. */
    fun applyCorrections(text: String, fixes: List<Pair<String, String>>): String =
        fixes.fold(text) { acc, (original, fixed) ->
            val o = original.trim()
            if (o.isEmpty() || !acc.contains(o)) acc else acc.replaceFirst(o, fixed.trim())
        }

    // ---------------------------------------------------------------- pistas

    private fun syllables(s: String): List<Char> = s.filterNot { it.isWhitespace() }.toList()

    private val INITIALS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private val VOWELS = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"

    /** Las sílabas desordenadas, para armar la respuesta. Siempre el mismo orden para la misma tarjeta. */
    fun tiles(answer: String): List<Char> {
        val s = syllables(answer)
        if (s.size < 2) return s
        val shuffled = s.shuffled(kotlin.random.Random(answer.hashCode()))
        return if (shuffled == s) s.drop(1) + s.first() else shuffled
    }

    /**
     * Las tres pistas, de menos a más:
     * - sustantivo o adverbio: cuántas sílabas → la primera → las sílabas desordenadas;
     * - verbo o adjetivo: forma de diccionario → terminación → las sílabas desordenadas;
     * - gramática: el verbo → el patrón → las sílabas desordenadas.
     * Si la respuesta es una sola sílaba, la última pista es su consonante inicial y su vocal.
     */
    fun hints(card: ClozeCard): List<String> {
        val s = syllables(card.answer)
        val boxes = "□".repeat(s.size)
        val last = if (s.size >= 2) "Fichas: " + tiles(card.answer).joinToString(" · ")
        else jamo(s.firstOrNull() ?: ' ')?.let { "Empieza con ${INITIALS[it.initial]} y su vocal es ${VOWELS[it.vowel]}" } ?: boxes
        return when (card.kind) {
            ClozeKind.WORD -> listOf(
                "${s.size} ${if (s.size == 1) "sílaba" else "sílabas"}: $boxes",
                if (s.size >= 2) "Empieza con ${s.first()}: ${s.first()}${"□".repeat(s.size - 1)}"
                else jamo(s.first())?.let { "Empieza con ${INITIALS[it.initial]}" } ?: boxes,
                last,
            )
            ClozeKind.VERB -> listOf(
                "Forma de diccionario: ${card.base ?: card.label}",
                "Terminación: ${card.ending ?: localEnding(card.base ?: card.label, card.answer)}",
                last,
            )
            ClozeKind.GRAMMAR -> listOf(
                card.base?.let { "Con: $it" } ?: card.meaning?.let { "Significa: $it" } ?: "${s.size} sílabas: $boxes",
                "Gramática: ${card.label}",
                last,
            )
        }
    }

    /** Sin la IA: lo que viene después de la raíz (먹었어요 → -었어요; 공부했어요 → …어요). */
    fun localEnding(label: String, answer: String): String {
        val stem = stem(label)
        val word = answer.trim()
        val common = stem.zip(word).takeWhile { (a, b) -> a == b }.size
        val rest = word.drop(common)
        return when {
            rest.isEmpty() -> "la forma de diccionario"
            common == stem.length -> "-$rest"
            rest.length >= 2 -> "…" + rest.drop(1)
            else -> "…$rest"
        }
    }

    // ---------------------------------------------------------------- respuesta y calificación

    private val NOISE = Regex("""[\s.,!?~…·"'“”‘’()\[\]-]""")

    private fun normalize(s: String) = s.replace(NOISE, "")

    /** ¿Lo que escribiste es lo del hueco? Vale cualquiera de las lecturas del reconocedor. */
    fun matches(card: ClozeCard, candidates: List<String>): Boolean {
        val expected = normalize(card.answer)
        return expected.isNotEmpty() && candidates.any { normalize(it) == expected }
    }

    /**
     * Sin pistas o con una = bien; con dos o tres, o si fallaste antes de acertar = difícil; si viste
     * la respuesta = otra vez.
     */
    fun rating(hints: Int, wrongTries: Int, revealed: Boolean): Fsrs.Rating = when {
        revealed -> Fsrs.Rating.AGAIN
        hints <= 1 && wrongTries == 0 -> Fsrs.Rating.GOOD
        else -> Fsrs.Rating.HARD
    }

    /** La traducción en trozos: (texto, va resaltado). Los corchetes marcan lo que corresponde al hueco. */
    fun translationParts(translation: String): List<Pair<String, Boolean>> {
        val out = mutableListOf<Pair<String, Boolean>>()
        var rest = translation
        while (rest.isNotEmpty()) {
            val open = rest.indexOf('[')
            val close = if (open >= 0) rest.indexOf(']', open + 1) else -1
            if (open < 0 || close < 0) {
                out += rest to false
                break
            }
            if (open > 0) out += rest.substring(0, open) to false
            out += rest.substring(open + 1, close) to true
            rest = rest.substring(close + 1)
        }
        return out.filter { it.first.isNotEmpty() }
    }

    /**
     * La oración en palabras para dibujarla y poder tocar cada una (pedido por Jorge el 2026-11-19:
     * saber qué es 은메달 sin salir del repaso). Con la tarjeta abierta ([shown] = false) la palabra
     * del hueco no se toca, porque daría la respuesta, y sus trozos marcan el hueco; cerrada, se
     * tocan todas las que tienen coreano. Juntando los trozos sale la oración tal cual.
     */
    fun words(card: ClozeCard, shown: Boolean): List<ClozeWord> {
        val text = card.sentence
        val bs = card.start
        val be = card.start + card.answer.length
        val tokens = Regex("""\S+""").findAll(text).map { it.range.first until it.range.last + 1 }.toList()
        val out = mutableListOf<ClozeWord>()
        var pos = 0
        fun space(until: Int) {
            if (until > pos) out += ClozeWord(listOf(ClozePiece(text.substring(pos, until), blank = false)), tap = null)
            pos = maxOf(pos, until)
        }
        fun pieces(from: Int, to: Int): List<ClozePiece> = listOf(
            ClozePiece(text.substring(from, maxOf(from, minOf(to, bs))), blank = false),
            ClozePiece(text.substring(maxOf(from, bs), maxOf(maxOf(from, bs), minOf(to, be))), blank = true),
            ClozePiece(text.substring(minOf(to, maxOf(from, be)), to), blank = false),
        ).filter { it.text.isNotEmpty() }
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.first >= be || t.last + 1 <= bs) {
                space(t.first)
                out += ClozeWord(listOf(ClozePiece(text.substring(t.first, t.last + 1), blank = false)), tap = tapTarget(text.substring(t.first, t.last + 1)))
                pos = t.last + 1
                i++
                continue
            }
            // Las palabras que tocan el hueco: más de una si lo que falta lleva espacios.
            var j = i
            while (j + 1 < tokens.size && tokens[j + 1].first < be) j++
            if (shown) {
                for (k in i..j) {
                    val tk = tokens[k]
                    space(tk.first)
                    out += ClozeWord(pieces(tk.first, tk.last + 1), tap = tapTarget(text.substring(tk.first, tk.last + 1)))
                    pos = tk.last + 1
                }
            } else {
                space(t.first)
                out += ClozeWord(pieces(t.first, tokens[j].last + 1), tap = null)
                pos = tokens[j].last + 1
            }
            i = j + 1
        }
        space(text.length)
        return out
    }

    /** Lo que se busca al tocar una palabra: sin la puntuación de los bordes, y solo si tiene coreano. */
    fun tapTarget(word: String): String? =
        word.trim().replace(LEADING, "").replace(TRAILING, "").takeIf { w -> w.any { it in '가'..'힣' } }

    private val WORD_EDGE =Regex("""[\s.,!?~…·"'“”‘’()\[\]「」『』:;]""")

    /**
     * Dónde va el hueco que trajo la IA. Si aparece una vez, ahí. Si aparece más (이 en 이것 y en
     * 책이), primero donde [blankIn] encuentra el ítem en la oración; si no, según el tipo: una
     * partícula o terminación corta pegada al final de una palabra, lo demás al principio de una
     * palabra. Antes se tomaba siempre la primera (F6 de las pruebas).
     */
    fun placeBlank(sentence: String, answer: String, label: String, kind: ClozeKind): ClozeBlank? {
        if (answer.isEmpty()) return null
        val starts = generateSequence(sentence.indexOf(answer).takeIf { it >= 0 }) { prev ->
            sentence.indexOf(answer, prev + 1).takeIf { it >= 0 }
        }.toList()
        if (starts.size <= 1) return starts.firstOrNull()?.let { ClozeBlank(it, answer) }
        blankIn(sentence, label, kind)?.let { b ->
            starts.firstOrNull { s -> s < b.start + b.text.length && b.start < s + answer.length }?.let { return ClozeBlank(it, answer) }
        }
        fun startsWord(i: Int) = i == 0 || WORD_EDGE.matches(sentence[i - 1].toString())
        fun endsWord(i: Int) = i + answer.length >= sentence.length || WORD_EDGE.matches(sentence[i + answer.length].toString())
        val attached = kind == ClozeKind.GRAMMAR && answer.trim().length <= 2
        val best = if (attached) starts.firstOrNull { endsWord(it) && !startsWord(it) } ?: starts.firstOrNull { endsWord(it) }
        else starts.firstOrNull { startsWord(it) }
        return ClozeBlank(best ?: starts.first(), answer)
    }

    /** Las tarjetas que salen sin IA: las de tus oraciones con el hueco encontrado. */
    fun localCards(targets: List<ClozeTarget>): List<ClozeCard> = targets.mapNotNull { t ->
        val sentence = t.sentence ?: return@mapNotNull null
        val blank = t.blank ?: return@mapNotNull null
        ClozeCard(t.code, t.label, t.kind, t.meaning, sentence, blank.start, blank.text, own = true)
    }

    // ---------------------------------------------------------------- IA (una llamada por sesión)

    private fun kindLabel(kind: ClozeKind) = when (kind) {
        ClozeKind.WORD -> "palabra"
        ClozeKind.VERB -> "verbo o adjetivo"
        ClozeKind.GRAMMAR -> "gramática"
    }

    /**
     * Una sola llamada para toda la sesión: traduce tus oraciones (y marca el hueco si el celular no
     * lo encontró) y escribe una oración para lo que no tiene.
     */
    fun prompt(profile: String, level: String, standardLevel: Int, targets: List<ClozeTarget>): String = buildString {
        appendLine("Eres profesor de coreano. Un estudiante hispanohablante de nivel $level (≈ nivel $standardLevel de 6 del 국제 통용 한국어 표준 교육과정) repasa con oraciones en las que falta una parte (el hueco) y la escribe a mano. Responde SOLO con un objeto JSON válido, sin texto extra ni bloques de código.")
        appendLine(Prompts.SPANISH)
        if (profile.isNotBlank()) {
            appendLine()
            appendLine("SOBRE EL ESTUDIANTE: ${profile.trim().replace('\n', ' ')}")
        }
        appendLine()
        appendLine("ÍTEMS (código: forma, tipo):")
        targets.forEach { t ->
            append("- ${t.code}: ${t.label} (${kindLabel(t.kind)})")
            if (t.sentence != null) {
                append(" | oración suya: «${t.sentence}»")
                t.blank?.let { append(" | hueco: «${it.text}»") }
            }
            appendLine()
        }
        appendLine()
        appendLine("TAREAS:")
        appendLine("1. Si el ítem trae «oración suya», úsala tal cual (ya está corregida; no la cambies). Si trae «hueco», devuélvelo igual.")
        appendLine("2. Si no trae oración, escribe una corta (una sola oración, 5 a 10 palabras), natural y cotidiana, sobre su vida, que use el ítem como lo usaría un coreano. Solo vocabulario y gramática de su nivel o menor, en estilo -아요/어요.")
        appendLine("3. \"hueco\": el trozo de la oración que corresponde al ítem, copiado letra por letra: un sustantivo o adverbio tal cual (sin la partícula); un verbo o adjetivo, la palabra conjugada entera; una terminación, la palabra que la lleva entera (si la expresión ocupa dos palabras, las dos); una partícula, solo la partícula.")
        appendLine("4. \"traduccion\": la traducción natural al español, fiel al sentido de la oración, con lo que corresponde al hueco entre corchetes, por ejemplo: «Como hay examen, [estudié] en la biblioteca».")
        appendLine("5. \"base\": en un verbo o adjetivo, su forma de diccionario; en una gramática, la forma de diccionario del verbo, adjetivo o sustantivo al que va unida. \"terminacion\": en un verbo o adjetivo, la terminación que lleva en el hueco escrita como en un libro (\"-았/었어요\", \"-고\", \"-(으)니까\"); si no, null.")
        appendLine()
        appendLine("FORMATO:")
        appendLine("{\"items\": [{\"id\": \"w12\", \"oracion\": \"시험이 있으니까 도서관에서 공부했어요.\", \"hueco\": \"공부했어요\", \"traduccion\": \"Como hay examen, [estudié] en la biblioteca.\", \"base\": \"공부하다\", \"terminacion\": \"-았/었어요\"}]}")
    }

    /**
     * «Traducir» en una oración tuya o del diccionario: la traducción con lo que corresponde al hueco
     * entre corchetes, como las de las oraciones de la IA.
     */
    fun translatePrompt(sentence: String, answer: String): String = buildString {
        appendLine("Traduce al español esta oración coreana del repaso de un estudiante: «${sentence.trim()}».")
        appendLine(Prompts.SPANISH)
        appendLine("Traducción natural y fiel al sentido de la oración, con lo que corresponde a «${answer.trim()}» entre corchetes, por ejemplo: «Como hay examen, [estudié] en la biblioteca».")
        appendLine("Responde SOLO con un objeto JSON válido, sin texto extra ni bloques de código:")
        appendLine("{\"traduccion\": \"...\"}")
    }

    fun parseTranslation(raw: String): String {
        val json = try {
            JSONObject(extractJsonObject(raw))
        } catch (e: org.json.JSONException) {
            throw ParseException("JSON inválido: ${e.message}")
        }
        return json.optString("traduccion").trim().takeIf { it.isNotEmpty() && it != "null" }
            ?: throw ParseException("La respuesta no trae la traducción.")
    }

    /**
     * Las tarjetas de la sesión, tus oraciones primero. De la IA solo se toma un hueco que esté en la
     * oración; si no, se usa el que encontró el celular o se deja el ítem. Tus oraciones que la IA no
     * devolvió quedan sin traducción.
     */
    fun parse(raw: String, targets: List<ClozeTarget>): List<ClozeCard> {
        val json = try {
            JSONObject(extractJsonObject(raw))
        } catch (e: org.json.JSONException) {
            throw ParseException("JSON inválido: ${e.message}")
        }
        val arr = json.optJSONArray("items")
        val byCode = HashMap<String, JSONObject>()
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            if (id.isNotEmpty() && id !in byCode) byCode[id] = o
        }
        fun JSONObject.text(key: String): String? =
            if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }

        val cards = targets.mapNotNull { t ->
            val o = byCode[t.code]
            val sentence = t.sentence ?: o?.text("oracion") ?: return@mapNotNull null
            val blank = t.blank ?: o?.text("hueco")?.let { hueco -> placeBlank(sentence, hueco, t.label, t.kind) } ?: return@mapNotNull null
            ClozeCard(
                code = t.code, label = t.label, kind = t.kind, meaning = t.meaning,
                sentence = sentence, start = blank.start, answer = blank.text,
                translation = o?.text("traduccion"),
                base = o?.text("base"),
                ending = o?.text("terminacion"),
                own = t.own,
            )
        }
        return cards.sortedBy { if (it.own) 0 else 1 }
    }
}

/**
 * Qué entra a una sesión: primero lo que te toca repasar (de menor a mayor recuerdo) y unos pocos
 * que nunca usaste ([newQuota]), hasta [limit]. Si falta de un lado, se completa con el otro.
 */
object QuickReviewPick {
    fun <T> pick(due: List<T>, unseen: List<T>, limit: Int = 10, newQuota: Int = 3): List<T> {
        val fresh = minOf(newQuota, unseen.size, limit)
        val result = due.take(limit - fresh).toMutableList()
        result += unseen.take(minOf(fresh, limit - result.size))
        if (result.size < limit) result += unseen.drop(fresh).take(limit - result.size)
        return result.distinct()
    }
}
