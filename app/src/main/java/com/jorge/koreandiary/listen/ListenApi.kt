package com.jorge.koreandiary.listen

import com.jorge.koreandiary.ai.GeminiProvider
import com.jorge.koreandiary.ai.HttpProviderException
import com.jorge.koreandiary.ai.ProviderException
import com.jorge.koreandiary.ai.postJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** El guion ya escrito, con avisos si hubo que cambiar algo (otro modelo, sin búsqueda). */
data class ScriptResult(val raw: String, val model: String, val searched: Boolean, val sources: List<String>, val notes: List<String>)

/**
 * Llamadas HTTP del modo Escuchar, por la Interactions API de Gemini (misma clave que el diario):
 * el guion (con búsqueda de Google opcional) y la voz del narrador (TTS).
 */
class ListenApi(private val apiKey: String) {
    private val headers get() = mapOf("x-goog-api-key" to apiKey, "Api-Revision" to GeminiProvider.API_REVISION)

    /**
     * Escribe el guion. Si algo no está disponible con tu clave se reintenta sin eso, en este orden:
     * sin el nivel de razonamiento, sin la búsqueda, y con [fallbackModel] si el modelo no existe o se
     * acabó su cuota (la capa gratuita de gemini-3.8-flash da pocos pedidos al día).
     */
    suspend fun script(prompt: String, model: String, fallbackModel: String, search: Boolean): ScriptResult = withContext(Dispatchers.IO) {
        var currentModel = model
        var useSearch = search
        var thinking = true
        val notes = mutableListOf<String>()
        var lastError: Exception? = null
        repeat(5) {
            try {
                val json = postJson(GeminiProvider.ENDPOINT, headers, ListenJson.scriptBody(currentModel, prompt, useSearch, thinking), "Gemini", 150_000)
                val text = GeminiProvider.extractText(json) ?: throw ProviderException("Gemini no devolvió el guion.")
                return@withContext ScriptResult(text, currentModel, useSearch, if (useSearch) ListenJson.sources(json) else emptyList(), notes)
            } catch (e: HttpProviderException) {
                lastError = e
                val msg = e.message.orEmpty()
                when {
                    e.code == 400 && thinking && msg.contains("thinking", ignoreCase = true) -> thinking = false
                    e.code == 400 && useSearch -> {
                        useSearch = false
                        notes += "La búsqueda de Google no está disponible con tu clave: el guion se escribió sin buscar."
                    }
                    (e.code == 429 || e.code == 404 || e.code == 403) && currentModel != fallbackModel -> {
                        notes += "El guion lo escribió $fallbackModel porque $currentModel respondió ${e.code}."
                        currentModel = fallbackModel
                        thinking = true
                    }
                    else -> throw e
                }
            }
        }
        throw lastError ?: ProviderException("No se pudo escribir el guion.")
    }

    /** Voz del narrador. Devuelve PCM (el TTS responde un WAV de 24 kHz). */
    suspend fun speech(text: String, model: String, voice: String, style: String): PcmAudio = withContext(Dispatchers.IO) {
        val json = postJson(GeminiProvider.ENDPOINT, headers, ListenJson.speechBody(model, text, voice, style), "Gemini TTS", 240_000)
        val bytes = ListenJson.audio(json) ?: throw ProviderException("El TTS de Gemini no devolvió audio.")
        Wav.parse(bytes)
    }
}

/** Armado y lectura del JSON, sin red, para poder probarlo. */
object ListenJson {
    fun scriptBody(model: String, prompt: String, search: Boolean, thinking: Boolean): JSONObject = JSONObject()
        .put("model", model)
        .put("input", JSONArray().put(JSONObject().put("type", "text").put("text", prompt)))
        .put("store", false)
        .apply {
            // Razonamiento bajo: el guion no lo necesita y sale más rápido.
            if (thinking) put("generation_config", JSONObject().put("thinking_level", "low"))
            if (search) put("tools", JSONArray().put(JSONObject().put("type", "google_search")))
        }

    /** El texto se lee tal cual; el estilo va aparte, como anotación. */
    fun speechBody(model: String, text: String, voice: String, style: String): JSONObject = JSONObject()
        .put("model", model)
        .put(
            "input",
            JSONArray().put(
                JSONObject().put("type", "user_input").put(
                    "content",
                    JSONArray().put(
                        JSONObject().put("type", "text").put("text", text)
                            .put("annotations", JSONArray().put(JSONObject().put("type", "speech_metadata").put("style", style)))
                    ),
                )
            ),
        )
        .put("response_format", JSONObject().put("type", "audio"))
        .put("generation_config", JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", voice))))

    /** El último bloque de audio de la respuesta (steps[].content[].data), ya decodificado. */
    fun audio(json: JSONObject): ByteArray? {
        for (key in listOf("steps", "outputs")) {
            val steps = json.optJSONArray(key) ?: continue
            for (i in steps.length() - 1 downTo 0) {
                val step = steps.optJSONObject(i) ?: continue
                val blocks = step.optJSONArray("content") ?: JSONArray().put(step)
                for (j in blocks.length() - 1 downTo 0) {
                    val b = blocks.optJSONObject(j) ?: continue
                    val data = b.optString("data")
                    val isAudio = b.optString("type") == "audio" || b.optString("mime_type").startsWith("audio") || b.optString("mimeType").startsWith("audio")
                    if (isAudio && data.isNotEmpty()) return runCatching { Base64.getDecoder().decode(data) }.getOrNull()
                }
            }
        }
        return null
    }

    /** Páginas citadas por la búsqueda: "título — url", sin repetir. */
    fun sources(json: JSONObject): List<String> {
        val found = LinkedHashMap<String, String>()
        fun walk(v: Any?) {
            when (v) {
                is JSONObject -> {
                    val url = v.optString("url")
                    if (url.startsWith("http")) found.putIfAbsent(url, v.optString("title").ifBlank { url })
                    v.keys().forEach { walk(v.opt(it)) }
                }
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
            }
        }
        walk(json.optJSONArray("steps"))
        return found.entries.take(8).map { (url, title) -> if (title == url) url else "$title — $url" }
    }
}
