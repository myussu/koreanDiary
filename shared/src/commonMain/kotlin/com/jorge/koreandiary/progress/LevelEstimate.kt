package com.jorge.koreandiary.progress

import com.jorge.koreandiary.data.db.GrammarPoint
import com.jorge.koreandiary.data.db.Word
import com.jorge.koreandiary.data.standard.GrammarKeys
import com.jorge.koreandiary.data.standard.StdGrammar
import com.jorge.koreandiary.data.standard.StdWord
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Cuánto cubres de un nivel del estándar (1–6): cuántos ítems tiene, cuántos están en tu lista y
 * cuántos de esos ya usaste bien al menos una vez.
 */
data class LevelCoverage(val level: Int, val total: Int, val inList: Int, val usedWell: Int) {
    val listShare: Float get() = if (total == 0) 0f else inList.toFloat() / total
    val wellShare: Float get() = if (total == 0) 0f else usedWell.toFloat() / total
}

/**
 * La prueba corta de vocabulario: por nivel, qué parte de una muestra al azar dijiste conocer, y las
 * palabras que no (para «Para agregar»). Se guarda en los ajustes como JSON.
 */
data class VocabTest(val takenAt: Long, val known: Map<Int, Float>, val unknown: List<String>) {
    fun encode(): String = JSONObject()
        .put("at", takenAt)
        .put("known", JSONObject().apply { known.forEach { (level, share) -> put(level.toString(), share.toDouble()) } })
        .put("unknown", JSONArray(unknown))
        .toString()

    companion object {
        fun decode(raw: String?): VocabTest? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(raw)
                val k = o.optJSONObject("known")
                val known = k?.keys()?.asSequence()?.mapNotNull { key -> key.toIntOrNull()?.let { it to k.optDouble(key).toFloat() } }?.toMap().orEmpty()
                val u = o.optJSONArray("unknown")
                VocabTest(o.optLong("at"), known, (0 until (u?.length() ?: 0)).map { u!!.optString(it) }.filter { it.isNotBlank() })
            }.getOrNull()
        }
    }
}

/**
 * «Tu nivel» y «Para agregar», en el celular y sin IA. El nivel sale sobre todo de la gramática (45 a
 * 67 puntos por nivel: se puede cubrir); el vocabulario, de la prueba corta, porque el estándar tiene
 * entre 735 y 2 580 palabras por nivel y tu lista siempre se vería chica al lado. «En tu lista» no
 * quiere decir que lo sepas: por eso va aparte lo que ya usaste bien. Lo estimado solo se propone.
 */
object LevelEstimate {
    /** Un nivel cuenta como cubierto desde el 70 %. */
    const val THRESHOLD = 0.7f

    /** Palabras por nivel en la prueba corta. */
    const val SAMPLE_PER_LEVEL = 15

    /**
     * Gramática por nivel. Una del estándar está en tu lista si alguna tuya comparte una clave
     * (`GrammarKeys`); está usada bien si esa tuya está en [usedWell] (ids con al menos un acierto).
     */
    fun grammarCoverage(catalog: List<StdGrammar>, mine: List<GrammarPoint>, usedWell: Set<Long>): List<LevelCoverage> {
        val keyed = mine.map { g -> g.id to GrammarKeys.keys(listOf(g.pattern) + g.variants.split(" | ")) }
        return (1..6).map { level ->
            val atLevel = catalog.filter { it.level == level }
            var inList = 0
            var well = 0
            for (std in atLevel) {
                val keys = std.primaryKeys + std.relatedKeys
                val matched = keyed.filter { (_, k) -> k.any { it in keys } }.map { it.first }
                if (matched.isNotEmpty()) {
                    inList++
                    if (matched.any { it in usedWell }) well++
                }
            }
            LevelCoverage(level, atLevel.size, inList, well)
        }
    }

    /** Vocabulario por nivel: está en tu lista si coincide la forma de diccionario. */
    fun wordCoverage(catalog: List<StdWord>, mine: List<Word>, usedWell: Set<Long>): List<LevelCoverage> {
        val byForm = mine.associateBy { it.baseForm }
        return (1..6).map { level ->
            val forms = catalog.filter { it.level == level }.map { it.word }.distinct()
            val matched = forms.mapNotNull { byForm[it] }
            LevelCoverage(level, forms.size, matched.size, matched.count { it.id in usedWell })
        }
    }

    /**
     * El nivel más alto que cubres desde el 1 sin saltos (si te falta el 2, no llegas al 3 aunque
     * tengas mucho del 3). 0 si ni el 1 llega al umbral.
     */
    fun estimate(coverage: List<LevelCoverage>, threshold: Float = THRESHOLD): Int {
        var level = 0
        for (c in coverage.sortedBy { it.level }) {
            if (c.total == 0 || c.listShare < threshold) break
            level = c.level
        }
        return level
    }

    /** Lo mismo con la prueba de vocabulario: el nivel más alto seguido en que conoces el 70 %. */
    fun estimateFromTest(known: Map<Int, Float>, threshold: Float = THRESHOLD): Int {
        var level = 0
        for (l in 1..6) {
            val share = known[l] ?: break
            if (share < threshold) break
            level = l
        }
        return level
    }

    /** La muestra de la prueba: [perLevel] palabras al azar por nivel que no están en tu lista (sin repetir forma). */
    fun sample(catalog: List<StdWord>, mine: Set<String>, perLevel: Int = SAMPLE_PER_LEVEL, random: Random = Random.Default): Map<Int, List<StdWord>> =
        (1..6).associateWith { level ->
            catalog.asSequence()
                .filter { it.level == level && it.type != "otro" && it.word !in mine }
                .distinctBy { it.word }
                .toList()
                .shuffled(random)
                .take(perLevel)
        }

    /**
     * La gramática del estándar que no está en tu lista, de los niveles hasta [maxLevel], de lo más
     * básico a lo más alto (dentro de un nivel, el orden del estándar).
     */
    fun missingGrammar(catalog: List<StdGrammar>, mine: List<GrammarPoint>, maxLevel: Int): List<StdGrammar> {
        val mineKeys = mine.flatMap { g -> GrammarKeys.keys(listOf(g.pattern) + g.variants.split(" | ")) }.toSet()
        return catalog.filter { it.level <= maxLevel && (it.primaryKeys + it.relatedKeys).none { k -> k in mineKeys } }
            .sortedWith(compareBy({ it.level }, { it.id }))
    }
}
