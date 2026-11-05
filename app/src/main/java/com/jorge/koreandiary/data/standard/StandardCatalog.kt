package com.jorge.koreandiary.data.standard

import android.content.res.AssetManager

/**
 * Catálogo estándar del 국립국어원 (어휘·문법 등급 목록, 2017/2020), niveles 1 a 6.
 * Va en assets y se consulta en memoria: el nivel de cada palabra o gramática se resuelve
 * aquí, sin gastar tokens de IA. Licencia 공공누리 제1유형; ver assets/standard/FUENTE.txt.
 */
data class StdWord(
    val id: Int,
    val level: Int,
    val word: String,
    val homonym: String,
    /** 명사, 동사, 형용사, 부사... */
    val pos: String,
    /** Ejemplo de uso corto, p. ej. "문을 열다". */
    val guide: String,
) {
    /** Tipo en el vocabulario de la app. */
    val type: String
        get() = when {
            pos.startsWith("동사") -> "verbo"
            pos.startsWith("형용사") -> "adjetivo"
            pos.startsWith("부사") -> "adverbio"
            pos.startsWith("명사") || pos.startsWith("의존명사") || pos.startsWith("대명사") -> "sustantivo"
            else -> "otro"
        }
}

data class StdGrammar(
    val id: Int,
    val level: Int,
    /** 조사, 연결어미, 종결어미, 전성어미, 선어말어미, 표현. */
    val category: String,
    /** Forma estándar, p. ej. "-으니까". */
    val form: String,
    /** Variantes, p. ej. ["-니까"]. */
    val related: List<String>,
    val meaning: String,
) {
    val primaryKeys: Set<String> by lazy { GrammarKeys.keys(form) }
    val relatedKeys: Set<String> by lazy { GrammarKeys.keys(related) }
}

class StandardCatalog(private val loadLines: (String) -> List<String>) {

    constructor(assets: AssetManager) : this({ name ->
        assets.open("standard/$name").bufferedReader(Charsets.UTF_8).use { it.readLines() }
    })

    /** Se carga la primera vez que se usa. Llamar desde un hilo de fondo. */
    val words: List<StdWord> by lazy { parseVocab(loadLines("vocab.tsv")) }
    val grammar: List<StdGrammar> by lazy { parseGrammar(loadLines("grammar.tsv")) }

    private val wordsByForm by lazy { words.groupBy { it.word } }
    private val wordsById by lazy { words.associateBy { it.id } }
    private val grammarById by lazy { grammar.associateBy { it.id } }

    /** Clave → gramática de menor nivel. La forma principal tiene prioridad sobre las variantes. */
    private val byPrimaryKey by lazy { indexBy { it.primaryKeys } }
    private val byRelatedKey by lazy { indexBy { it.relatedKeys } }

    private fun indexBy(keysOf: (StdGrammar) -> Set<String>): Map<String, StdGrammar> {
        val map = HashMap<String, StdGrammar>()
        for (g in grammar.sortedBy { it.level }) for (k in keysOf(g)) map.putIfAbsent(k, g)
        return map
    }

    /** Si hay homónimos, se toma el de menor nivel (el más básico). */
    fun word(form: String): StdWord? = wordsByForm[form.trim()]?.minByOrNull { it.level }

    fun wordById(id: Int): StdWord? = wordsById[id]

    fun grammarById(id: Int): StdGrammar? = grammarById[id]

    /** Busca una gramática por su patrón y variantes, tolerando A/V/N, (으), [..] y espacios. */
    fun grammar(patterns: List<String>): StdGrammar? {
        val keys = GrammarKeys.keys(patterns)
        return keys.mapNotNull { byPrimaryKey[it] }.minByOrNull { it.level }
            ?: keys.mapNotNull { byRelatedKey[it] }.minByOrNull { it.level }
    }

    companion object {
        /** id \t nivel \t palabra \t homónimo \t categoría \t ejemplo */
        fun parseVocab(lines: List<String>): List<StdWord> = lines.mapNotNull { line ->
            val c = line.split('\t')
            if (c.size < 6) return@mapNotNull null
            StdWord(c[0].toIntOrNull() ?: return@mapNotNull null, c[1].toIntOrNull() ?: 0, c[2], c[3], c[4], c[5])
        }

        /** id \t nivel \t categoría \t forma \t variantes(|) \t significado */
        fun parseGrammar(lines: List<String>): List<StdGrammar> = lines.mapNotNull { line ->
            val c = line.split('\t')
            if (c.size < 6) return@mapNotNull null
            StdGrammar(
                id = c[0].toIntOrNull() ?: return@mapNotNull null,
                level = c[1].toIntOrNull() ?: 0,
                category = c[2],
                form = c[3],
                related = c[4].split('|').map { it.trim() }.filter { it.isNotEmpty() },
                meaning = c[5],
            )
        }
    }
}

/**
 * Claves para comparar gramáticas escritas de formas distintas:
 * "A/V-(으)니까" (libro), "-으니까" / "-니까" (estándar) y "-니까" (IA) comparten la clave "E:니까".
 * El prefijo separa terminaciones (E, llevan guion o A/V-) de partículas y expresiones (P),
 * para no confundir la terminación "-다가" con la partícula "다가" ni "-라도" con "이라도".
 */
object GrammarKeys {
    private val BRACKETS = Regex("""\[[^\]]*]""")
    /** Notas entre paréntesis con letras latinas o barras: "(피동 / Passive)". */
    private val NOTES = Regex("""\([^)]*[A-Za-z/][^)]*\)""")
    private val DIGITS = Regex("""[0-9]""")
    private val MARKERS = Regex("""(?<![A-Za-z])(A/V|A|V|N)(?![A-Za-z])""")
    private val ENDING = Regex("""(^\s*-)|((?<![A-Za-z])(A/V|A|V)\s*-)""")
    private val OPTIONAL = Regex("""\(([^)]*)\)""")
    private val HANGUL_SYLLABLE = Regex("""[가-힣]""")

    fun keys(patterns: List<String>): Set<String> = patterns.flatMap { keys(it) }.toSet()

    fun keys(raw: String): Set<String> {
        val s = raw.replace(BRACKETS, "").replace(NOTES, "").replace(DIGITS, "")
        val out = linkedSetOf<String>()
        // Comas y "|" separan variantes; "/" separa alternativas ("아서/어서", "ㄴ/는다고").
        for (variant in s.split(',', '|')) {
            val kind = if (ENDING.containsMatchIn(variant)) "E" else "P"
            val clean = variant.replace(MARKERS, " ")
            val pieces = clean.split('/')
            // Una sílaba sola solo vale si no viene de partir alternativas ("-고" sí; el "아" de "아/어" no).
            val minLength = if (pieces.size == 1) 1 else 2
            for (piece in pieces) {
                // (으), (이), (요): una versión sin lo opcional y otra con ello.
                for (candidate in listOf(piece.replace(OPTIONAL, ""), piece.replace(OPTIONAL, "$1"))) {
                    val key = joinBatchim(candidate.filterNot { it.isWhitespace() || it == '-' || it == '~' || it == '?' || it == '.' })
                    if (key.length >= minLength && HANGUL_SYLLABLE.containsMatchIn(key)) out += "$kind:$key"
                }
            }
        }
        return out
    }

    /** "(으)ㄹ" del libro queda como "으ㄹ": se une en la sílaba "을" como en el estándar (-을, -은, -음, -읍). */
    private fun joinBatchim(s: String): String =
        s.replace("으ㄹ", "을").replace("으ㄴ", "은").replace("으ㅁ", "음").replace("으ㅂ", "읍")

    fun matches(a: List<String>, b: List<String>): Boolean {
        val ka = keys(a)
        return keys(b).any { it in ka }
    }
}
