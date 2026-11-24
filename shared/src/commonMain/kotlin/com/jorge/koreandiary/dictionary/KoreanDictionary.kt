package com.jorge.koreandiary.dictionary

import org.json.JSONArray
import org.json.JSONObject

/** Una palabra o gramática del 한국어기초사전: lo que va en memoria para buscar y verificar. */
data class DictEntry(
    val id: Int,
    val form: String,
    val homonym: Int,
    /** Categoría tal como la da el diccionario: 명사, 동사, 형용사, 부사, 어미, 조사... */
    val pos: String,
    /** 1 inicial, 2 intermedio, 3 avanzado; 0 sin nivel (palabras menos frecuentes). */
    val level: Int,
    /** Es gramática (문법‧표현, o una terminación o partícula), no una palabra para el vocabulario. */
    val grammar: Boolean,
    val variants: List<String> = emptyList(),
    /** Conjugaciones del diccionario: las irregulares dicen cómo cambia el tema (덥다 → 더워, 더우니). */
    val conjugations: List<String> = emptyList(),
) {
    val isVerb: Boolean get() = pos in VERB_POS && form.endsWith("다")

    /** El tipo como lo guarda la lista de vocabulario de la app. */
    val wordType: String
        get() = when (pos) {
            "명사", "의존 명사", "대명사", "수사" -> "sustantivo"
            "동사", "보조 동사" -> "verbo"
            "형용사", "보조 형용사" -> "adjetivo"
            "부사" -> "adverbio"
            else -> "otro"
        }

    companion object {
        private val VERB_POS = setOf("동사", "형용사", "보조 동사", "보조 형용사")
    }
}

/** Un sentido de una entrada, con su traducción al español tal como viene del diccionario. */
data class DictSense(
    val lemma: String?,
    val definition: String?,
    /** Oraciones de ejemplo, primero las corteses (-요). */
    val examples: List<String>,
    /** Frases cortas de uso (가를 꾸미다). */
    val phrases: List<String>,
    val patterns: List<String>,
    /** Sinónimos, contrarios, forma corta… de este sentido. */
    val relations: List<DictRelation> = emptyList(),
)

/**
 * Otra palabra relacionada con un sentido: [type] tal como lo da el diccionario (유의어 sinónimo,
 * 반대말 contrario, 준말 forma corta, 본말 forma completa, 높임말, 낮춤말, 참고어 ver también) y
 * [gloss], su traducción corta.
 */
data class DictRelation(val type: String, val form: String, val gloss: String?)

/** El detalle de una entrada: se lee de su archivo cuando hace falta. */
data class DictDetail(
    val id: Int,
    /** De dónde viene: hanja (不便) o la lengua de un préstamo (銀medal). */
    val origin: String?,
    val senses: List<DictSense>,
    /** Palabras relacionadas: [tipo, forma], p. ej. ["파생어", "불편하다"]. */
    val related: List<Pair<String, String>>,
    /** Cómo se pronuncia, solo si no suena como se escribe: 같이 → «가치»; dos formas, «교외/교웨». */
    val pronunciation: String? = null,
)

/** Un modismo (관용구, 발이 넓다) o un refrán (속담), con su traducción corta y su palabra principal. */
data class DictIdiom(val id: Int, val form: String, val proverb: Boolean, val gloss: String, val headId: Int)

/**
 * Un hanja de los orígenes: [readings], su 훈음 de libhangul con el 훈 en español (學: «배울 학»,
 * «aprender»), y [words], las palabras del diccionario que lo llevan, las comunes primero.
 */
data class HanjaInfo(val hanja: String, val readings: List<HanjaReading>, val words: List<HanjaWord>)

data class HanjaReading(val meaning: String, val sound: String, val gloss: String?)

data class HanjaWord(val id: Int, val form: String, val level: Int, val gloss: String)

/**
 * El 한국어기초사전 dentro de la app (assets/dictionary, armado con tools/build_dictionary.py): verifica
 * que una palabra existe, da su forma de diccionario y sus sentidos con traducción al español, sin
 * internet ni IA. Pedido por Jorge el 2026-11-21, para que los datos fijos no dependan de la IA.
 *
 * [open] lee las líneas de un archivo de esa carpeta ("index.tsv", "senses/07.jsonl"). La lista se
 * carga entera la primera vez que se usa (unas 51.000 entradas: conviene [warmUp] fuera del hilo
 * principal); el detalle se lee por partes y se guardan las últimas partes leídas.
 */
class KoreanDictionary(private val open: (String) -> List<String>) {

    private class Index(val byForm: Map<String, List<DictEntry>>, val byId: Map<Int, DictEntry>, val lemmatizer: Lemmatizer)

    private val index: Index by lazy { load() }

    /** Modismos y refranes por su palabra principal (발 → 발이 넓다, 발이 닳다…). */
    private val idiomsByHead: Map<Int, List<DictIdiom>> by lazy { loadIdioms().groupBy { it.headId } }

    private val hanjaIndex: Map<String, HanjaInfo> by lazy { loadHanja() }
    /** Las últimas partes leídas del detalle (como mucho [MAX_SHARDS]). */
    private val shards: MutableMap<Int, Map<Int, DictDetail>> = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<Int, Map<Int, DictDetail>>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Map<Int, DictDetail>>?): Boolean = size > MAX_SHARDS
        }
    )

    /** Carga la lista; para llamarlo al abrir la app, fuera del hilo principal. */
    fun warmUp() {
        index
    }

    private fun load(): Index {
        val byForm = HashMap<String, MutableList<DictEntry>>(80_000)
        val byId = HashMap<Int, DictEntry>(80_000)
        val all = ArrayList<DictEntry>(55_000)
        for (line in open("index.tsv")) {
            if (line.isBlank()) continue
            val p = line.split('\t')
            if (p.size < 6) continue
            val pos = p[3]
            val entry = DictEntry(
                id = p[0].toIntOrNull() ?: continue,
                form = p[1],
                homonym = p[2].toIntOrNull() ?: 0,
                pos = pos,
                level = p[4].toIntOrNull() ?: 0,
                grammar = p[5] == "문법‧표현" || pos == "어미" || pos == "조사",
                variants = p.getOrNull(6).orEmpty().split('|').filter { it.isNotBlank() },
                conjugations = p.getOrNull(7).orEmpty().split('|').filter { it.isNotBlank() },
            )
            all += entry
            byId[entry.id] = entry
            (listOf(entry.form) + entry.variants).forEach { byForm.getOrPut(it) { mutableListOf() } += entry }
        }
        return Index(byForm, byId, Lemmatizer(all))
    }

    /** Un archivo opcional de la carpeta: vacío si no está (un diccionario armado antes de tenerlo). */
    private fun optional(name: String): List<String> = try {
        open(name)
    } catch (e: Exception) {
        emptyList()
    }

    private fun loadIdioms(): List<DictIdiom> = optional("idioms.tsv").mapNotNull { line ->
        val p = line.split('\t')
        if (p.size < 5) return@mapNotNull null
        DictIdiom(
            id = p[0].toIntOrNull() ?: return@mapNotNull null,
            form = p[1],
            proverb = p[2] == "속담",
            gloss = p[3],
            headId = p[4].toIntOrNull() ?: return@mapNotNull null,
        )
    }

    private fun loadHanja(): Map<String, HanjaInfo> {
        val out = HashMap<String, HanjaInfo>(4_000)
        for (line in optional("hanja.jsonl")) {
            if (line.isBlank()) continue
            val o = JSONObject(line)
            val hanja = o.optString("h").ifBlank { null } ?: continue
            val readings = o.optJSONArray("r")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONArray(i)?.let { r -> HanjaReading(r.optString(0), r.optString(1), r.optString(2).ifBlank { null }) }
                }
            }.orEmpty()
            val words = o.optJSONArray("w")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONArray(i)?.let { w -> HanjaWord(w.optInt(0), w.optString(1), w.optInt(2), w.optString(3)) }
                }
            }.orEmpty()
            out[hanja] = HanjaInfo(hanja, readings, words)
        }
        return out
    }

    /** Las entradas con esta forma exacta (homónimos incluidos), primero las más comunes. */
    fun entries(form: String): List<DictEntry> = index.byForm[form.trim()].orEmpty().sortedWith(COMMON_FIRST)

    /** ¿Existe como palabra o gramática en el diccionario? */
    fun exists(form: String): Boolean = index.byForm.containsKey(form.trim())

    /** ¿Existe como palabra (no gramática)? Para decidir si algo entra a la lista de vocabulario. */
    fun isWord(form: String): Boolean = index.byForm[form.trim()].orEmpty().any { !it.grammar }

    /**
     * Las palabras de diccionario que puede ser [written], una palabra tal como aparece en una
     * oración (만났어요, 은메달을, «사는» → 살다 y 사다), primero las más comunes. Vacío si no se
     * reconoce.
     */
    fun baseForms(written: String): List<DictEntry> {
        val word = written.trim().replace(EDGES, "")
        if (word.isEmpty() || !word.any(Hangul::isSyllable)) return emptyList()
        val idx = index
        val out = LinkedHashMap<Int, DictEntry>()
        idx.byForm[word]?.filter { !it.grammar }?.forEach { out.putIfAbsent(it.id, it) }
        for (p in Lemmatizer.PARTICLES) {
            if (word.length > p.length && word.endsWith(p)) {
                idx.byForm[word.dropLast(p.length)]?.filter { !it.grammar && !it.isVerb }?.forEach { out.putIfAbsent(it.id, it) }
            }
        }
        idx.lemmatizer.verbs(word).forEach { out.putIfAbsent(it.id, it) }
        val exact = out.values.filter { it.form == word }.sortedWith(COMMON_FIRST)
        return exact + out.values.filter { it.form != word }.sortedWith(COMMON_FIRST)
    }

    /** Todas las entradas (palabras y gramática), para recorrerlas: «suena como» en [com.jorge.koreandiary.pronunciation.SoundAlike]. */
    fun allEntries(): Collection<DictEntry> = index.byId.values

    /** La entrada con este id, o null (un modismo no está en la lista: ver [idiomsOf]). */
    fun entry(id: Int): DictEntry? = index.byId[id]

    /** Los modismos y refranes de una palabra (발 → 발이 넓다), primero los modismos y los cortos. */
    fun idiomsOf(entry: DictEntry): List<DictIdiom> =
        idiomsByHead[entry.id].orEmpty().sortedWith(compareBy<DictIdiom> { it.proverb }.thenBy { it.form.length })

    /** Lo que se sabe de un hanja (學): su 훈음 y las palabras que lo llevan. null si no está. */
    fun hanja(hanja: String): HanjaInfo? = hanjaIndex[hanja]

    /** Sentidos, origen y relacionadas de una entrada. null si su archivo no está. */
    fun detail(entry: DictEntry): DictDetail? = detail(entry.id)

    fun detail(id: Int): DictDetail? {
        val n = id % SHARDS
        val shard = shards[n] ?: readShard(n).also { shards[n] = it }
        return shard[id]
    }

    private fun readShard(n: Int): Map<Int, DictDetail> {
        val lines = try {
            open("senses/${n.toString().padStart(2, '0')}.jsonl")
        } catch (e: Exception) {
            return emptyMap()
        }
        val out = HashMap<Int, DictDetail>(lines.size * 2)
        for (line in lines) {
            if (line.isBlank()) continue
            val o = JSONObject(line)
            val id = o.getInt("i")
            val senses = o.optJSONArray("s")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val s = arr.getJSONObject(i)
                    DictSense(
                        lemma = s.optString("l").ifBlank { null },
                        definition = s.optString("d").trim().ifBlank { null },
                        examples = s.optJSONArray("x").strings(),
                        phrases = s.optJSONArray("c").strings(),
                        patterns = s.optJSONArray("p").strings(),
                        relations = s.optJSONArray("rel")?.let { rel ->
                            (0 until rel.length()).mapNotNull { j ->
                                rel.optJSONArray(j)?.let { r -> DictRelation(r.optString(0), r.optString(1), r.optString(2).ifBlank { null }) }
                            }.filter { it.form.isNotBlank() }
                        }.orEmpty(),
                    )
                }
            }.orEmpty()
            val related = o.optJSONArray("r")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optJSONArray(i)?.let { r -> r.optString(0) to r.optString(1) } }
            }.orEmpty()
            out[id] = DictDetail(id, o.optString("o").ifBlank { null }, senses, related, o.optString("pr").ifBlank { null })
        }
        return out
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifBlank { null } }

    companion object {
        private const val SHARDS = 64
        private const val MAX_SHARDS = 6
        private val EDGES = Regex("^[^가-힣ㄱ-ㅣ]+|[^가-힣ㄱ-ㅣ]+$")

        /** Las comunes primero: inicial, intermedio, avanzado y al final las sin nivel. */
        val COMMON_FIRST: Comparator<DictEntry> = compareBy<DictEntry> { if (it.level == 0) 9 else it.level }.thenBy { it.homonym }
    }
}
