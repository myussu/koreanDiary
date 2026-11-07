package com.jorge.koreandiary.conversation

import com.jorge.koreandiary.ai.CorrectionParser
import com.jorge.koreandiary.ai.CorrectionResponse
import com.jorge.koreandiary.ai.ParseException
import com.jorge.koreandiary.ai.extractJsonObject
import org.json.JSONObject

data class Hint(val key: String, val half: String, val full: String, val spanish: String)

data class WordGloss(val word: String, val form: String, val meaning: String, val note: String?)

data class SentenceGloss(val sentence: String, val spanish: String, val simple: String?)

/** Revisión final: el contrato del diario más un comentario general. */
data class ConversationReview(val correction: CorrectionResponse, val comment: String?)

object ConversationParser {
    fun hint(raw: String): Hint {
        val o = obj(raw)
        val full = o.text("frase") ?: throw ParseException("La pista no trae la oración.")
        return Hint(key = o.text("clave").orEmpty(), half = o.text("medio").orEmpty(), full = full, spanish = o.text("es").orEmpty())
    }

    fun word(raw: String, word: String): WordGloss {
        val o = obj(raw)
        val meaning = o.text("s") ?: throw ParseException("La traducción no trae el significado.")
        return WordGloss(word = word, form = o.text("f") ?: word, meaning = meaning, note = o.text("nota"))
    }

    fun sentence(raw: String, sentence: String): SentenceGloss {
        val o = obj(raw)
        val es = o.text("es") ?: throw ParseException("La respuesta no trae la traducción.")
        return SentenceGloss(sentence = sentence, spanish = es, simple = o.text("simple")?.takeIf { it != sentence.trim() })
    }

    fun review(raw: String): ConversationReview =
        ConversationReview(CorrectionParser.parse(raw), obj(raw).text("comentario"))

    private fun obj(raw: String): JSONObject = try {
        JSONObject(extractJsonObject(raw))
    } catch (e: org.json.JSONException) {
        throw ParseException("JSON inválido: ${e.message}")
    }

    private fun JSONObject.text(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
}
