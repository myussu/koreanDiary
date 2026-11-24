package com.jorge.koreandiary.dictionary

/**
 * Lo que el 한국어기초사전 dice de una palabra además de sus sentidos (pedido por Jorge el
 * 2026-11-24): cómo se pronuncia si no suena como se escribe, sus sinónimos y contrarios, las
 * otras palabras con su mismo hanja y sus modismos. Todo sin IA, del diccionario que va en la app.
 */
data class DictionaryExtras(
    /** «가치» para 같이; null si suena como se escribe. */
    val pronunciation: String?,
    val related: List<RelatedGroup>,
    val hanja: List<HanjaFamily>,
    val idioms: List<IdiomGloss>,
) {
    val isEmpty: Boolean get() = pronunciation == null && related.isEmpty() && hanja.isEmpty() && idioms.isEmpty()
}

/** Las palabras relacionadas de un tipo: «Parecidas: 기쁘다 (alegre) · 즐겁다 (divertido)». */
data class RelatedGroup(val label: String, val words: List<Glossed>) {
    val text: String get() = "$label: " + words.joinToString(" · ") { it.text }
}

/** Una forma coreana con su traducción corta, si la hay: «학교 (escuela)». */
data class Glossed(val form: String, val gloss: String?) {
    val text: String get() = if (gloss.isNullOrBlank()) form else "$form ($gloss)"
}

/** Un hanja de la palabra con su significado y otras palabras que lo llevan. */
data class HanjaFamily(val hanja: String, val meaning: String?, val words: List<Glossed>) {
    /** «學 (배울 학, aprender): 학교 (escuela) · 학생 (estudiante)». */
    val text: String
        get() = buildString {
            append(hanja)
            meaning?.let { append(" ($it)") }
            if (words.isNotEmpty()) append(": ").append(words.joinToString(" · ") { it.text })
        }
}

/** Un modismo o un refrán con su traducción: «발이 넓다: tener muchos contactos». */
data class IdiomGloss(val form: String, val meaning: String, val proverb: Boolean) {
    val text: String get() = "$form: $meaning"
}

object DictionaryExtrasOf {
    private val HAN = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]")

    /** Cómo se llama cada relación para quien estudia, en el orden en que se muestran. */
    private val LABELS = linkedMapOf(
        "유의어" to "Parecidas",
        "반대말" to "Contrarias",
        "준말" to "Forma corta",
        "본말" to "Forma completa",
        "높임말" to "Más respetuosa",
        "낮춤말" to "Más humilde",
        "참고어" to "Ver también",
    )

    private const val MAX_PER_GROUP = 4
    private const val MAX_HANJA = 2
    private const val MAX_HANJA_WORDS = 4
    private const val MAX_IDIOMS = 3

    /**
     * Lo de [entry] con su detalle ya leído. [senses] en el orden en que se muestran (el sentido que
     * estudias primero): sus relaciones van en ese orden. Llamar fuera del hilo principal la primera
     * vez: lee el índice de hanja y los modismos.
     */
    fun of(dictionary: KoreanDictionary, entry: DictEntry, detail: DictDetail, senses: List<DictSense> = detail.senses): DictionaryExtras =
        DictionaryExtras(
            pronunciation = detail.pronunciation?.takeIf { p -> p.split('/').any { it != plain(entry.form) } },
            related = related(senses, entry.form),
            hanja = detail.origin?.let { hanja(dictionary, it, entry) }.orEmpty(),
            idioms = dictionary.idiomsOf(entry).take(MAX_IDIOMS).map { IdiomGloss(it.form, shortGloss(it.gloss), it.proverb) },
        )

    /** Las relaciones de los sentidos, agrupadas por tipo y sin repetir formas. */
    fun related(senses: List<DictSense>, form: String): List<RelatedGroup> {
        val seen = mutableSetOf(form)
        val byType = LinkedHashMap<String, MutableList<Glossed>>()
        for (r in senses.flatMap { it.relations }) {
            val label = LABELS[r.type] ?: continue
            if (!seen.add(r.form)) continue
            val list = byType.getOrPut(r.type) { mutableListOf() }
            if (list.size < MAX_PER_GROUP) list += Glossed(r.form, r.gloss?.let(::shortGloss))
        }
        return LABELS.keys.mapNotNull { type -> byType[type]?.let { RelatedGroup(LABELS.getValue(type), it) } }
    }

    /** Cada hanja del origen (不便 → 不, 便) con su 훈음 y hasta [MAX_HANJA_WORDS] palabras comunes que lo llevan. */
    fun hanja(dictionary: KoreanDictionary, origin: String, entry: DictEntry): List<HanjaFamily> =
        HAN.findAll(origin).map { it.value }.distinct().mapNotNull { h ->
            val info = dictionary.hanja(h) ?: return@mapNotNull null
            val words = info.words
                .filter { it.id != entry.id && it.form != entry.form }
                .sortedBy { if (it.level == 0) 9 else it.level }
                .take(MAX_HANJA_WORDS)
                .map { Glossed(it.form, shortGloss(it.gloss)) }
            val meaning = info.readings.firstOrNull()?.let { r ->
                listOfNotNull("${r.meaning} ${r.sound}".trim().ifBlank { null }, r.gloss?.let(::shortGloss)).joinToString(", ")
            }?.ifBlank { null }
            if (words.isEmpty() && meaning == null) null else HanjaFamily(h, meaning, words)
        }.take(MAX_HANJA).toList()

    /** La primera traducción, en español de Perú y en minúscula: «escuela, colegio» → «escuela». */
    fun shortGloss(gloss: String): String {
        val firstItem = gloss.split(',', ';').first().trim()
        val adapted = PeruSpanish.adaptLemma(firstItem)
        return if (adapted.length > 1 && adapted[0].isUpperCase() && adapted[1].isLowerCase()) adapted.replaceFirstChar { it.lowercase() } else adapted
    }

    /** La forma sin los signos del diccionario (-는 동안, 먹다^보다): para comparar con la pronunciación. */
    private fun plain(form: String): String = form.replace(Regex("[-^ ]"), "")
}
