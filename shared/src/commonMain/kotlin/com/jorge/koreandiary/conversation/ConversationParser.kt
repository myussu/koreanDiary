package com.jorge.koreandiary.conversation

import com.jorge.koreandiary.ai.CorrectionParser
import com.jorge.koreandiary.ai.CorrectionResponse
import com.jorge.koreandiary.ai.UsedItemDto
import com.jorge.koreandiary.ai.ParseException
import com.jorge.koreandiary.ai.extractJsonObject
import org.json.JSONObject

data class Hint(val key: String, val half: String, val full: String, val spanish: String)

/** Una palabra tocada: [form] de diccionario, [type] (sustantivo, verbo…) y [parts], de qué partes se forma si es compuesta. */
data class WordGloss(
    val word: String,
    val form: String,
    val meaning: String,
    val note: String?,
    val type: String? = null,
    val parts: String? = null,
) {
    /** Es una partícula o una terminación, no una palabra para la lista de vocabulario. */
    val isGrammar: Boolean get() = form.startsWith("-") ||
        type?.lowercase()?.let { it.startsWith("partícula") || it.startsWith("particula") || it.startsWith("terminaci") } == true
}

data class ItemExplanation(val meaning: String, val usage: String, val example: String, val exampleSpanish: String)

data class SentenceGloss(val sentence: String, val spanish: String, val simple: String?)

/** Revisión final: el contrato del diario más un comentario general. */
/** La revisión de una sesión. [asked]: en una escucha, lo que preguntó en las pausas (vuelve a su repaso). */
data class ConversationReview(val correction: CorrectionResponse, val comment: String?, val asked: List<UsedItemDto> = emptyList())

object ConversationParser {
    fun hint(raw: String): Hint {
        val o = obj(raw)
        val full = o.text("frase") ?: throw ParseException("La pista no trae la oración.")
        return Hint(key = o.text("clave").orEmpty(), half = o.text("medio").orEmpty(), full = full, spanish = o.text("es").orEmpty())
    }

    fun word(raw: String, word: String): WordGloss {
        val o = obj(raw)
        val meaning = o.text("s") ?: throw ParseException("La traducción no trae el significado.")
        return WordGloss(word = word, form = o.text("f") ?: word, meaning = meaning, note = o.text("nota"), type = o.text("t"), parts = o.text("p"))
    }

    fun sentence(raw: String, sentence: String): SentenceGloss {
        val o = obj(raw)
        val es = o.text("es") ?: throw ParseException("La respuesta no trae la traducción.")
        return SentenceGloss(sentence = sentence, spanish = es, simple = o.text("simple")?.takeIf { it != sentence.trim() })
    }

    fun item(raw: String): ItemExplanation {
        val o = obj(raw)
        val meaning = o.text("s") ?: throw ParseException("La explicación no trae el significado.")
        return ItemExplanation(meaning, o.text("uso").orEmpty(), o.text("k").orEmpty(), o.text("es").orEmpty())
    }

    fun review(raw: String): ConversationReview {
        val json = obj(raw)
        return ConversationReview(
            CorrectionParser.parse(raw),
            json.text("comentario"),
            CorrectionParser.parseUsedItems(json.optJSONArray("preguntas")).take(10),
        )
    }

    private fun obj(raw: String): JSONObject = try {
        JSONObject(extractJsonObject(raw))
    } catch (e: org.json.JSONException) {
        throw ParseException("JSON inválido: ${e.message}")
    }

    private fun JSONObject.text(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
}
