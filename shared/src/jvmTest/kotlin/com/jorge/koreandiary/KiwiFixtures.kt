package com.jorge.koreandiary

import com.jorge.koreandiary.dictionary.KoreanDictionary
import com.jorge.koreandiary.dictionary.Morph
import com.jorge.koreandiary.dictionary.SentenceAnalyzer
import org.json.JSONObject
import java.io.File

/**
 * Kiwi grabado para los tests: las piezas que de verdad devolvió para cada oración y lo que devuelve
 * al unirlas (`resources/kiwi-fixtures.json`, armado con tools/kiwi_fixtures.py y kiwipiepy 0.25).
 * Una oración que no está ahí falla, para no probar con datos inventados.
 */
object KiwiFixtures {

    private class Recorded(private val analyses: Map<String, List<Morph>>, private val joins: Map<String, String>) : SentenceAnalyzer {
        override fun analyze(sentence: String): List<Morph> = analyses[sentence] ?: error("Falta en los datos de prueba: $sentence")
        override fun join(pieces: List<Morph>): String? = joins[pieces.joinToString("+") { "${it.form}/${it.tag}${if (it.irregular) "-I" else ""}" }]
    }

    val analyzer: SentenceAnalyzer by lazy {
        val file = listOf("src/jvmTest/resources/kiwi-fixtures.json", "shared/src/jvmTest/resources/kiwi-fixtures.json").map(::File).first { it.isFile }
        val o = JSONObject(file.readText())
        val a = o.getJSONObject("analyses")
        val analyses = a.keys().asSequence().associateWith { s ->
            val arr = a.getJSONArray(s)
            (0 until arr.length()).map { i ->
                val m = arr.getJSONArray(i)
                Morph(m.getString(0), m.getString(1), m.getInt(2), m.getInt(3), m.getBoolean(4))
            }
        }
        val j = o.getJSONObject("joins")
        Recorded(analyses, j.keys().asSequence().associateWith { j.getString(it) })
    }

    val dictionary: KoreanDictionary by lazy {
        val dir = listOf("../app/src/main/assets/dictionary", "app/src/main/assets/dictionary").map(::File).first { it.isDirectory }
        KoreanDictionary { name -> File(dir, name).readLines() }
    }
}
