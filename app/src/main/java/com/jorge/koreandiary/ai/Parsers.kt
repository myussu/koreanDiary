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
        val used = parseUsedItems(json.optJSONArray("usados"))
        // Formato anterior: palabras nuevas aparte.
        val legacyNew = json.optJSONArray("nuevos").mapObjects { o ->
            val base = o.str("forma_base") ?: return@mapObjects null
            UsedItemDto(null, base, normalizeKind(o.str("tipo"), base), true, o.str("significado"))
        }
        val improvements = json.optJSONArray("mejoras").mapObjects { o ->
            val structure = o.str("estructura") ?: return@mapObjects null
            ImprovementDto(o.optInt("oracion", 0), structure, o.str("ejemplo") ?: "", o.str("explicacion") ?: "")
        }.take(2)
        val allUsed = (used + legacyNew).distinctBy { it.itemRef ?: "${it.kind}:${it.form}" }
        if (sentences.isEmpty() && corrections.isEmpty() && allUsed.isEmpty()) {
            throw ParseException("La respuesta no tiene oraciones, correcciones ni ítems usados.")
        }
        return CorrectionResponse(sentences, corrections, allUsed, improvements)
    }

    /** Lista "usados" (formato compacto f/t/ok o el anterior forma_base/resultado). La comparte el workbook. */
    internal fun parseUsedItems(arr: JSONArray?): List<UsedItemDto> = arr.mapObjects { o ->
        val ref = o.str("id")?.takeIf { ITEM_REF.matches(it) }
        val form = o.str("f") ?: o.str("forma_base") ?: return@mapObjects null
        val ok = when {
            o.has("ok") -> o.optBoolean("ok", true)
            else -> o.str("resultado")?.lowercase() != "incorrecto"
        }
        UsedItemDto(ref, form, normalizeKind(o.str("t") ?: o.str("tipo"), form), ok, o.str("significado") ?: o.str("s"))
    }

    private val ITEM_REF = Regex("^(w|g|sv|sg)\\d+$")

    /** Acepta las claves cortas (n, v, a, adv, g, o) y los nombres largos. */
    internal fun normalizeKind(t: String?, form: String): String = when (t?.lowercase()?.trim()) {
        "n", "sustantivo", "noun" -> "sustantivo"
        "v", "verbo", "verb" -> "verbo"
        "a", "adj", "adjetivo", "adjective" -> "adjetivo"
        "adv", "adverbio", "adverb" -> "adverbio"
        "g", "gramatica", "gramática", "grammar" -> "gramatica"
        "o", "otro", "other" -> "otro"
        // Sin tipo: si empieza con guion es una terminación.
        else -> if (form.trimStart().startsWith("-")) "gramatica" else "otro"
    }

    private fun normalizeTense(t: String?): String = when (t?.lowercase()) {
        "pasado", "past" -> "pasado"
        "futuro", "future" -> "futuro"
        else -> "presente"
    }
}

object HowToSayParser {
    fun parse(raw: String): HowToSayResponse {
        val json = try {
            JSONObject(extractJsonObject(raw))
        } catch (e: org.json.JSONException) {
            throw ParseException("JSON inválido: ${e.message}")
        }
        val mine = phrase(json.optJSONObject("tu")) ?: throw ParseException("La respuesta no trae la frase.")
        // Si "nat" es igual a "tu" (aunque la IA no haya puesto null), se muestra una sola.
        val natural = phrase(json.optJSONObject("nat"))?.takeUnless { same(it.text, mine.text) }
        val words = json.optJSONArray("w").mapObjects { o ->
            val f = o.str("f") ?: return@mapObjects null
            f to (o.str("s") ?: "")
        }
        return HowToSayResponse(mine, natural, json.str("nota") ?: "", words)
    }

    private fun phrase(o: JSONObject?): HowToSayResponse.Phrase? {
        val text = o?.str("k") ?: return null
        val grammar = o.optJSONArray("g")?.let { arr -> (0 until arr.length()).map { arr.optString(it).trim() } }
            ?.filter { it.isNotEmpty() && it != "null" } ?: emptyList()
        return HowToSayResponse.Phrase(text, grammar)
    }

    private fun same(a: String, b: String) =
        a.filter { it.isLetterOrDigit() } == b.filter { it.isLetterOrDigit() }
}

object WorkbookParser {
    fun parseHints(raw: String): WorkbookHints {
        val json = parseObject(raw)
        val exercises = json.optJSONArray("ejercicios").mapObjects { o ->
            val n = o.str("n") ?: return@mapObjects null
            WorkbookExercise(
                number = n,
                ask = o.str("e") ?: "",
                hints = o.optJSONArray("p").mapObjects { h ->
                    val form = h.str("f") ?: return@mapObjects null
                    WorkbookHint(
                        form = form,
                        kind = hintKind(h.str("t"), form),
                        use = h.str("u") ?: "",
                        example = h.str("k") ?: "",
                        english = h.str("en") ?: "",
                    )
                },
                answer = o.str("r") ?: "",
            )
        }
        val vocab = json.optJSONArray("vocab").mapObjects { o ->
            val f = o.str("f") ?: return@mapObjects null
            UsedItemDto(null, f, CorrectionParser.normalizeKind(o.str("t"), f), true, o.str("s"))
        }.filterNot { it.isGrammar }
        val grammar = json.optJSONArray("gram").mapObjects { o ->
            val f = o.str("f") ?: return@mapObjects null
            WorkbookGrammar(form = f, meaning = o.str("s"), focus = isTrue(o.opt("foco")), where = o.str("d"))
        }.distinctBy { it.form }
        // Una página de explicación (student book) puede no tener ejercicios: basta con su gramática o vocabulario.
        if (exercises.isEmpty() && grammar.isEmpty() && vocab.isEmpty()) throw ParseException("No se encontraron ejercicios ni gramática en la respuesta.")
        return WorkbookHints(exercises, vocab, grammar, unit(json.opt("unidad")))
    }

    fun parseReview(raw: String): WorkbookReview {
        val json = parseObject(raw)
        val items = json.optJSONArray("rev").mapObjects { o ->
            val n = o.str("n") ?: return@mapObjects null
            WorkbookReviewItem(
                number = n,
                written = o.str("w") ?: "",
                status = when (o.str("s")?.lowercase()) {
                    "ok" -> ReviewStatus.OK
                    "nat" -> ReviewStatus.NATURAL
                    else -> ReviewStatus.FIX
                },
                corrected = o.str("c"),
                natural = o.str("nat"),
                explanation = o.str("x") ?: "",
            )
        }
        val used = CorrectionParser.parseUsedItems(json.optJSONArray("usados"))
        if (items.isEmpty()) throw ParseException("No se encontraron respuestas para revisar en la foto.")
        return WorkbookReview(items, used)
    }

    private fun parseObject(raw: String): JSONObject = try {
        JSONObject(extractJsonObject(raw))
    } catch (e: org.json.JSONException) {
        throw ParseException("JSON inválido: ${e.message}")
    }

    /** true, "true", "sí" o 1. */
    private fun isTrue(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v.trim().lowercase() in setOf("true", "si", "sí", "1")
        else -> false
    }

    /** 5, "5", "5과" o "Unit 5" → 5. */
    private fun unit(v: Any?): Int? = when (v) {
        is Number -> v.toInt()
        is String -> Regex("\\d+").find(v)?.value?.toIntOrNull()
        else -> null
    }?.takeIf { it in 1..99 }

    private fun hintKind(t: String?, form: String): String = when (t?.lowercase()?.trim()) {
        "c", "conector" -> "conector"
        "x", "expresion", "expresión" -> "expresión"
        else -> CorrectionParser.normalizeKind(t, form)
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
