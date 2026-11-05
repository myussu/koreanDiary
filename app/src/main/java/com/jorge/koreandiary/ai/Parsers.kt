package com.jorge.koreandiary.ai

import org.json.JSONArray
import org.json.JSONObject

class ParseException(message: String) : Exception(message)

/** Saca el primer objeto JSON balanceado de un texto (tolera ```json, texto antes o después). */
internal fun extractJsonObject(raw: String): String {
    val start = raw.indexOf('{')
    if (start < 0) throw ParseException("No se encontró ningún objeto JSON en la respuesta.")
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until raw.length) {
        val c = raw[i]
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            continue
        }
        when (c) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return raw.substring(start, i + 1)
            }
        }
    }
    throw ParseException("El JSON está incompleto: falta cerrar llaves.")
}

private fun JSONObject.str(key: String): String? =
    if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }

private inline fun <T> JSONArray?.mapObjects(block: (JSONObject) -> T?): List<T> {
    if (this == null) return emptyList()
    val out = ArrayList<T>(length())
    for (i in 0 until length()) {
        val obj = optJSONObject(i) ?: continue
        block(obj)?.let { out += it }
    }
    return out
}

object CorrectionParser {
    fun parse(raw: String): CorrectionResponse {
        val json = try {
            JSONObject(extractJsonObject(raw))
        } catch (e: org.json.JSONException) {
            throw ParseException("JSON inválido: ${e.message}")
        }
        val sentences = json.optJSONArray("oraciones").mapObjects { o ->
            val text = o.str("texto") ?: return@mapObjects null
            SentenceDto(o.optInt("n", 0), text, normalizeTense(o.str("tiempo")))
        }
        val corrections = json.optJSONArray("correcciones").mapObjects { o ->
            val original = o.str("original") ?: return@mapObjects null
            val suggestion = o.str("sugerencia") ?: return@mapObjects null
            CorrectionDto(
                sentence = o.optInt("oracion", 0),
                original = original,
                suggestion = suggestion,
                type = o.str("tipo") ?: "gramatica",
                explanation = o.str("explicacion") ?: "",
                itemRef = o.str("item_id")?.takeIf { ITEM_REF.matches(it) },
            )
        }
        val used = json.optJSONArray("usados").mapObjects { o ->
            val ref = o.str("id")?.takeIf { ITEM_REF.matches(it) } ?: return@mapObjects null
            UsedItemDto(ref, o.str("forma_base") ?: "", o.str("resultado")?.lowercase() != "incorrecto")
        }
        val newWords = json.optJSONArray("nuevos").mapObjects { o ->
            val base = o.str("forma_base") ?: return@mapObjects null
            NewWordDto(base, o.str("tipo") ?: "otro", o.str("significado"))
        }
        if (sentences.isEmpty() && corrections.isEmpty() && used.isEmpty() && newWords.isEmpty()) {
            throw ParseException("La respuesta no tiene oraciones, correcciones ni ítems.")
        }
        return CorrectionResponse(sentences, corrections, used, newWords)
    }

    private val ITEM_REF = Regex("^[wg]\\d+$")

    private fun normalizeTense(t: String?): String = when (t?.lowercase()) {
        "pasado", "past" -> "pasado"
        "futuro", "future" -> "futuro"
        else -> "presente"
    }
}

object ImportParser {
    fun parse(raw: String): ImportPayload {
        val json = try {
            JSONObject(extractJsonObject(raw))
        } catch (e: org.json.JSONException) {
            throw ParseException("JSON inválido: ${e.message}")
        }
        val words = json.optJSONArray("palabras").mapObjects { o ->
            val base = o.str("forma_base") ?: return@mapObjects null
            ImportWord(
                baseForm = base,
                type = o.str("tipo") ?: "otro",
                irregular = o.str("irregular"),
                level = o.str("nivel") ?: "desconocido",
                meaning = o.str("significado"),
            )
        }
        val grammar = json.optJSONArray("gramatica").mapObjects { o ->
            val pattern = o.str("patron") ?: return@mapObjects null
            val variants = o.optJSONArray("variantes").mapObjects { v -> v.str("forma") }
            val appliesTo = o.optJSONArray("variantes").mapObjects { v -> v.str("aplica_a") }
                .flatMap { it.split('|', '/', ',') }.map { it.trim().uppercase() }
                .filter { it in setOf("A", "V", "N") }.distinct().joinToString("")
            ImportGrammar(
                unit = if (o.isNull("unidad")) null else o.optInt("unidad").takeIf { it > 0 },
                level = o.str("nivel") ?: "base",
                pattern = pattern,
                variants = variants.ifEmpty { listOf(pattern) },
                appliesTo = appliesTo.ifEmpty { GrammarTxtParser.appliesTo(pattern) },
                meaning = o.str("significado"),
                example = o.str("ejemplo"),
                tags = o.optJSONArray("etiquetas")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                    ?.filter { it.isNotBlank() } ?: emptyList(),
            )
        }
        if (words.isEmpty() && grammar.isEmpty()) throw ParseException("No se encontraron palabras ni gramática.")
        return ImportPayload(words, grammar)
    }
}

/**
 * Lee la lista de gramática en TXT sin IA, con el formato del libro:
 *   - **Unit 1:** A-다고 하다, V-ㄴ/는다고 하다 | V-아/어야겠다 | ...
 * Las viñetas del apéndice ("- N 대신") se importan con nivel "base".
 * La sección de irregulares se omite: son reglas para el corrector, no patrones para sugerir.
 */
object GrammarTxtParser {
    private val UNIT_LINE = Regex("""^\s*-\s*\**\s*Unit\s+(\d+)\s*:?\s*\**\s*:?\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val BULLET = Regex("""^\s*-\s+(.+)$""")

    fun parse(text: String, level: String): ImportPayload {
        val grammar = mutableListOf<ImportGrammar>()
        var inAppendix = false
        var inRules = false
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val lower = trimmed.lowercase()
            if (trimmed.startsWith("#") || trimmed.startsWith("**")) {
                if ("appendix" in lower || "extension" in lower || "apéndice" in lower) inAppendix = true
                inRules = "irregular" in lower
                continue
            }
            val unitMatch = UNIT_LINE.find(trimmed)
            if (unitMatch != null) {
                val unit = unitMatch.groupValues[1].toInt()
                unitMatch.groupValues[2].split(" | ", "|").map { it.trim() }.filter { it.isNotEmpty() }
                    .forEach { grammar += point(it, unit, level) }
                continue
            }
            if (inAppendix && !inRules) {
                BULLET.find(trimmed)?.let { grammar += point(it.groupValues[1].trim(), null, "base") }
            }
        }
        if (grammar.isEmpty()) throw ParseException("No se reconoció ninguna línea \"Unit N:\" ni viñetas del apéndice.")
        return ImportPayload(emptyList(), grammar.distinctBy { it.pattern })
    }

    private fun point(raw: String, unit: Int?, level: String): ImportGrammar {
        val variants = splitOutsideBrackets(raw)
        return ImportGrammar(
            unit = unit,
            level = level,
            pattern = raw,
            variants = variants,
            appliesTo = appliesTo(raw),
            meaning = null,
            example = null,
            tags = emptyList(),
        )
    }

    /** Divide por comas que no estén dentro de [] o (). "생각[계획, 예정]이다" queda entero. */
    fun splitOutsideBrackets(s: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        val current = StringBuilder()
        for (c in s) {
            when (c) {
                '[', '(' -> depth++
                ']', ')' -> depth = maxOf(0, depth - 1)
            }
            if (c == ',' && depth == 0) {
                out += current.toString().trim()
                current.clear()
            } else {
                current.append(c)
            }
        }
        if (current.isNotBlank()) out += current.toString().trim()
        return out.filter { it.isNotEmpty() }
    }

    fun appliesTo(pattern: String): String {
        val tags = linkedSetOf<Char>()
        Regex("""(?<![A-Za-z])(A/V|A|V|N)(?![A-Za-z/])""").findAll(pattern).forEach { m ->
            m.value.split('/').forEach { tags += it.first() }
        }
        return tags.joinToString("")
    }
}
