package com.jorge.koreandiary.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Claude por la API de Anthropic (Messages API). Por ahora solo dibuja lo nuevo de las escenas; la
 * corrección sigue con Gemini o Azure. Pide la respuesta como JSON con un esquema (salida
 * estructurada), así que no hay que adivinar dónde empieza el objeto.
 */
class ClaudeClient(private val apiKey: String, private val model: String) {

    /**
     * Un pedido con instrucciones fijas [system] y respuesta JSON según [schema]. [user] es texto o
     * una lista de bloques (`ClaudeProtocol.imageBlock`, `textBlock`). El límite deja lugar para que
     * piense antes de responder (el pensamiento cuenta como salida).
     */
    suspend fun json(system: String, user: Any, schema: JSONObject, maxTokens: Int = 16_000): ClaudeReply = withContext(Dispatchers.IO) {
        val res = postJson(ENDPOINT, ClaudeProtocol.headers(apiKey), ClaudeProtocol.request(model, system, user, schema, maxTokens), "Claude", 180_000)
        ClaudeProtocol.reply(res)
    }

    /** «Probar conexión»: un pedido mínimo. Devuelve el modelo que respondió. */
    suspend fun ping(): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 16)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Responde solo: OK")))
        val res = postJson(ENDPOINT, ClaudeProtocol.headers(apiKey), body, "Claude", 30_000)
        res.optString("model").ifEmpty { model }
    }

    companion object {
        const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
    }
}

/** Lo que devolvió Claude: el objeto pedido y los tokens que gastó. */
class ClaudeReply(val json: JSONObject, val inputTokens: Int, val outputTokens: Int)

/** El protocolo en funciones puras (con tests). */
object ClaudeProtocol {

    /** La versión de la API. La 2026-10-21 no acepta `temperature`, `top_p` ni `top_k`: no se mandan. */
    const val API_VERSION = "2026-10-21"

    fun headers(apiKey: String): Map<String, String> = mapOf("x-api-key" to apiKey, "anthropic-version" to API_VERSION)

    /** [user]: texto o un `JSONArray` de bloques. */
    fun request(model: String, system: String, user: Any, schema: JSONObject, maxTokens: Int): JSONObject = JSONObject()
        .put("model", model)
        .put("max_tokens", maxTokens)
        .put("system", system)
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        .put("output_config", JSONObject().put("format", JSONObject().put("type", "json_schema").put("schema", schema)))

    fun textBlock(text: String): JSONObject = JSONObject().put("type", "text").put("text", text)

    /** Una imagen PNG para que Claude la mire (va antes del texto que habla de ella). */
    fun imageBlock(png: ByteArray): JSONObject = JSONObject()
        .put("type", "image")
        .put(
            "source",
            JSONObject().put("type", "base64").put("media_type", "image/png")
                .put("data", java.util.Base64.getEncoder().encodeToString(png)),
        )

    /** El JSON del bloque de texto. Lanza con un mensaje claro si se cortó, se negó o no es JSON. */
    fun reply(res: JSONObject): ClaudeReply {
        when (res.optString("stop_reason")) {
            "max_tokens" -> throw ProviderException("Claude se quedó sin espacio antes de terminar el dibujo.")
            "refusal" -> throw ProviderException("Claude no quiso dibujarlo.")
        }
        val content = res.optJSONArray("content") ?: JSONArray()
        val text = buildString {
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                if (block.optString("type") == "text") append(block.optString("text"))
            }
        }
        if (text.isBlank()) throw ProviderException("Claude no devolvió texto.")
        val json = try {
            JSONObject(extractJsonObject(text))
        } catch (e: JSONException) {
            throw ProviderException("Claude devolvió algo que no es JSON.")
        }
        val usage = res.optJSONObject("usage")
        return ClaudeReply(json, usage?.optInt("input_tokens") ?: 0, usage?.optInt("output_tokens") ?: 0)
    }
}
