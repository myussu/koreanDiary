package com.jorge.koreandiary.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** [image]: PNG o JPEG; [imageMime] dice cuál. */
data class CorrectionRequest(val prompt: String, val image: ByteArray? = null, val imageMime: String = "image/png")

/**
 * Capa intermedia: cada proveedor (Gemini, Azure, Claude, un servidor propio...) implementa esto
 * y devuelve el texto crudo de la respuesta. El parseo es común (CorrectionParser).
 */
interface CorrectionProvider {
    val id: String
    suspend fun complete(request: CorrectionRequest): String
}

open class ProviderException(message: String, val offline: Boolean = false) : Exception(message)

/** POST JSON con manejo uniforme de errores. Devuelve el cuerpo de la respuesta ya parseado. */
internal fun postJson(
    url: String,
    headers: Map<String, String>,
    body: JSONObject,
    providerName: String,
    readTimeoutMs: Int = 120_000,
): JSONObject {
    val conn = try {
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
    } catch (e: IOException) {
        throw ProviderException("No se pudo conectar: ${e.message}", offline = true)
    }
    try {
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val message = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
            throw HttpProviderException(code, "$providerName respondió $code: ${message ?: text.take(300)}")
        }
        return JSONObject(text)
    } catch (e: org.json.JSONException) {
        throw ProviderException("$providerName devolvió una respuesta que no es JSON.")
    } catch (e: java.net.UnknownHostException) {
        throw ProviderException("Sin conexión o endpoint incorrecto (${e.message}).", offline = true)
    } catch (e: IOException) {
        throw ProviderException("Sin conexión: ${e.message}", offline = true)
    } finally {
        conn.disconnect()
    }
}

class HttpProviderException(val code: Int, message: String) : ProviderException(message)

/** Gemini por la Interactions API (v1beta). La capa gratuita de Google AI Studio basta para un diario. */
class GeminiProvider(
    private val apiKey: String,
    private val model: String,
) : CorrectionProvider {
    override val id = "gemini"

    override suspend fun complete(request: CorrectionRequest): String = withContext(Dispatchers.IO) {
        val input = JSONArray().put(JSONObject().put("type", "text").put("text", request.prompt))
        request.image?.let {
            input.put(
                JSONObject().put("type", "image").put("mime_type", request.imageMime)
                    .put("data", java.util.Base64.getEncoder().encodeToString(it))
            )
        }
        val body = JSONObject()
            .put("model", model)
            .put("input", input)
            // No guardar la conversación en los servidores de Google.
            .put("store", false)
        val json = postJson(ENDPOINT, mapOf("x-goog-api-key" to apiKey, "Api-Revision" to API_REVISION), body, "Gemini")
        extractText(json) ?: throw ProviderException("Gemini no devolvió texto.")
    }

    companion object {
        internal const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
        internal const val API_REVISION = "2026-05-20"

        /** Interactions API: steps[].content[] con type "text". Acepta también el formato de generateContent. */
        internal fun extractText(json: JSONObject): String? {
            json.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
            val steps = json.optJSONArray("steps")
            if (steps != null) {
                for (i in steps.length() - 1 downTo 0) {
                    val content = steps.optJSONObject(i)?.optJSONArray("content") ?: continue
                    val text = buildString {
                        for (j in 0 until content.length()) {
                            val block = content.optJSONObject(j) ?: continue
                            if (block.optString("type") == "text") append(block.optString("text"))
                        }
                    }
                    if (text.isNotBlank()) return text
                }
            }
            val parts = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            if (parts != null) {
                val text = buildString { for (j in 0 until parts.length()) append(parts.optJSONObject(j)?.optString("text").orEmpty()) }
                if (text.isNotBlank()) return text
            }
            return null
        }
    }
}

/**
 * Azure OpenAI con la API v1 (sin api-version) y la Responses API, que Microsoft recomienda
 * para sus modelos. "model" es el nombre del despliegue. Azure no usa estos datos para entrenar.
 */
class AzureOpenAIProvider(
    endpoint: String,
    private val deployment: String,
    private val apiKey: String,
    /** low, medium o high. Bajo = más rápido y barato; la corrección no necesita razonar mucho. */
    private val effort: String,
) : CorrectionProvider {
    override val id = "azure"
    private val url = "${AzureEndpoint.baseUrl(endpoint)}/responses"

    override suspend fun complete(request: CorrectionRequest): String = withContext(Dispatchers.IO) {
        val content = JSONArray().put(JSONObject().put("type", "input_text").put("text", request.prompt))
        request.image?.let {
            val b64 = java.util.Base64.getEncoder().encodeToString(it)
            content.put(JSONObject().put("type", "input_image").put("image_url", "data:${request.imageMime};base64,$b64"))
        }
        fun body(withReasoning: Boolean) = JSONObject()
            .put("model", deployment)
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            // Incluye razonamiento y salida: margen amplio para que el JSON no quede cortado.
            .put("max_output_tokens", 16_000)
            .put("store", false)
            .apply { if (withReasoning) put("reasoning", JSONObject().put("effort", effort)) }

        val headers = mapOf("api-key" to apiKey)
        val json = try {
            postJson(url, headers, body(withReasoning = true), "Azure")
        } catch (e: HttpProviderException) {
            // Los valores de esfuerzo varían por modelo: si el despliegue no acepta "reasoning", se reintenta sin él.
            if (e.code == 400 && e.message?.contains("reasoning", ignoreCase = true) == true) {
                postJson(url, headers, body(withReasoning = false), "Azure")
            } else {
                throw e
            }
        }
        if (json.optString("status") == "incomplete") {
            val reason = json.optJSONObject("incomplete_details")?.optString("reason") ?: "desconocido"
            throw ProviderException("Azure cortó la respuesta antes de terminar ($reason).")
        }
        extractText(json) ?: throw ProviderException("Azure no devolvió texto.")
    }

    companion object {
        /** Responses API: output[] con items "message" cuyo content[] trae "output_text". */
        internal fun extractText(json: JSONObject): String? {
            json.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
            val output = json.optJSONArray("output") ?: return null
            val text = buildString {
                for (i in 0 until output.length()) {
                    val item = output.optJSONObject(i) ?: continue
                    if (item.optString("type") != "message") continue
                    val parts = item.optJSONArray("content") ?: continue
                    for (j in 0 until parts.length()) {
                        val part = parts.optJSONObject(j) ?: continue
                        if (part.optString("type") == "output_text") append(part.optString("text"))
                    }
                }
            }
            return text.takeIf { it.isNotBlank() }
        }
    }
}

/** Lógica pura (con tests): normaliza lo que el usuario pega como endpoint. */
object AzureEndpoint {
    /**
     * Acepta "mi-recurso.openai.azure.com", "https://mi-recurso.openai.azure.com/",
     * la URL completa de Foundry o la de un proyecto, y devuelve "https://host/openai/v1".
     */
    fun baseUrl(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "Falta el endpoint de Azure" }
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val host = java.net.URI(withScheme).host ?: throw IllegalArgumentException("Endpoint de Azure inválido: $raw")
        return "https://$host/openai/v1"
    }
}
