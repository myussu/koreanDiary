package com.jorge.koreandiary.scene

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * La imagen de una escena: la pinta tu propio servidor de ComfyUI (por Tailscale) o la traes tú
 * desde el chat de Gemini. Aquí está lo que no depende de Android: los prompts, el flujo de ComfyUI
 * en su formato de API y la lectura de sus respuestas.
 */
object SceneImage {

    /** Vertical, como la hoja del celular. Medidas que SDXL conoce bien (múltiplos de 64, ~1 MP). */
    const val WIDTH = 768
    const val HEIGHT = 1344

    /** El estilo va siempre igual, para que todas las escenas se vean de la misma mano. */
    private const val STYLE =
        "soft watercolor painting, hand-painted with a brush, gentle washes, visible paper texture, " +
            "muted pastel colors, loose edges, calm composition"

    private const val NEGATIVE =
        "text, letters, words, writing, watermark, signature, logo, frame, border, photo, photorealistic, " +
            "3d render, harsh outlines, oversaturated, blurry, lowres, deformed hands, extra fingers"

    /** Prompt para el servidor (SDXL entiende inglés): la descripción que dio la IA al revisar + el estilo. */
    fun localPrompt(description: String): String = "$STYLE, ${description.trim().trimEnd('.')}"

    fun negativePrompt(): String = NEGATIVE

    /**
     * Para pegar en el chat de Gemini (o cualquiera que pinte). Si la revisión ya dio una descripción
     * en inglés, va esa; si no, el texto coreano de la página (Gemini lo entiende).
     */
    fun chatPrompt(description: String?, koreanText: String): String = buildString {
        append("Pinta una imagen vertical (9:16) en acuarela suave, como pintada a mano con pincel, ")
        append("con textura de papel y colores pastel apagados. Sin texto, letras ni marcos. La escena: ")
        if (!description.isNullOrBlank()) {
            append(description.trim())
        } else {
            append("lo que describe este texto en coreano: «")
            append(koreanText.trim().replace('\n', ' '))
            append("»")
        }
    }

    /**
     * La dirección del servidor tal como la escribes («truenas:8188», «http://100.101.1.2:8188/»):
     * con esquema y sin barra final. Null si está vacía o no parece una dirección.
     */
    fun normalizeServer(input: String): String? {
        var s = input.trim().trimEnd('/')
        if (s.isEmpty() || ' ' in s) return null
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        val host = s.substringAfter("://").substringBefore('/').substringBefore(':')
        return if (host.isEmpty()) null else s
    }

    /**
     * Flujo de texto a imagen en el formato de la API de ComfyUI (el mismo que exporta «Save (API)»):
     * cargar el modelo, los dos prompts, el lienzo vacío, el muestreo, decodificar y guardar.
     */
    fun workflow(checkpoint: String, positive: String, negative: String, seed: Long, steps: Int = 26): JSONObject {
        fun node(type: String, inputs: JSONObject) = JSONObject().put("class_type", type).put("inputs", inputs)
        fun link(id: String, out: Int) = JSONArray().put(id).put(out)
        return JSONObject()
            .put("1", node("CheckpointLoaderSimple", JSONObject().put("ckpt_name", checkpoint)))
            .put("2", node("CLIPTextEncode", JSONObject().put("text", positive).put("clip", link("1", 1))))
            .put("3", node("CLIPTextEncode", JSONObject().put("text", negative).put("clip", link("1", 1))))
            .put("4", node("EmptyLatentImage", JSONObject().put("width", WIDTH).put("height", HEIGHT).put("batch_size", 1)))
            .put(
                "5",
                node(
                    "KSampler",
                    JSONObject()
                        .put("seed", seed)
                        .put("steps", steps)
                        .put("cfg", 6.0)
                        .put("sampler_name", "dpmpp_2m")
                        .put("scheduler", "karras")
                        .put("denoise", 1.0)
                        .put("model", link("1", 0))
                        .put("positive", link("2", 0))
                        .put("negative", link("3", 0))
                        .put("latent_image", link("4", 0)),
                ),
            )
            .put("6", node("VAEDecode", JSONObject().put("samples", link("5", 0)).put("vae", link("1", 2))))
            .put("7", node("SaveImage", JSONObject().put("filename_prefix", "koreandiary-escena").put("images", link("6", 0))))
    }

    /** El cuerpo de `POST /prompt`. */
    fun promptBody(workflow: JSONObject, clientId: String): String =
        JSONObject().put("prompt", workflow).put("client_id", clientId).toString()

    /** `prompt_id` de la respuesta de `POST /prompt`, o el error que dio ComfyUI. */
    fun parsePromptResponse(body: String): Result<String> = try {
        val o = JSONObject(body)
        val id = o.optString("prompt_id")
        if (id.isNotEmpty()) {
            Result.success(id)
        } else {
            val err = o.optJSONObject("error")?.optString("message").orEmpty()
            Result.failure(IllegalStateException(err.ifEmpty { "ComfyUI no aceptó el pedido." }))
        }
    } catch (e: JSONException) {
        Result.failure(IllegalStateException("Respuesta de ComfyUI que no se entiende."))
    }

    /** Una imagen guardada por ComfyUI: lo que pide `GET /view`. */
    data class Output(val filename: String, val subfolder: String, val type: String) {
        fun viewQuery(): String = "filename=${enc(filename)}&subfolder=${enc(subfolder)}&type=${enc(type)}"

        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    }

    /**
     * La primera imagen de `GET /history/{id}`. Null mientras no termina (la historia viene vacía).
     * Si ComfyUI dice que falló, lanza con su mensaje.
     */
    fun parseHistory(body: String, promptId: String): Output? {
        val entry = try {
            JSONObject(body).optJSONObject(promptId)
        } catch (e: JSONException) {
            null
        } ?: return null
        val status = entry.optJSONObject("status")
        if (status?.optString("status_str") == "error") throw IllegalStateException("ComfyUI no pudo pintar la imagen.")
        val outputs = entry.optJSONObject("outputs") ?: return null
        for (key in outputs.keys()) {
            val images = outputs.optJSONObject(key)?.optJSONArray("images") ?: continue
            for (i in 0 until images.length()) {
                val img = images.optJSONObject(i) ?: continue
                val name = img.optString("filename")
                if (name.isNotEmpty()) return Output(name, img.optString("subfolder"), img.optString("type", "output"))
            }
        }
        return null
    }

    /**
     * Los modelos instalados, de `GET /object_info/CheckpointLoaderSimple`. ComfyUI los da como lista
     * (`[[...], {}]`) o, en versiones nuevas, como `["COMBO", {"options": [...]}]`.
     */
    fun parseCheckpoints(body: String): List<String> = try {
        val spec = JSONObject(body).optJSONObject("CheckpointLoaderSimple")
            ?.optJSONObject("input")?.optJSONObject("required")?.optJSONArray("ckpt_name")
        val first = spec?.opt(0)
        val list = when {
            first is JSONArray -> first
            first == "COMBO" -> spec.optJSONObject(1)?.optJSONArray("options")
            else -> null
        }
        if (list == null) emptyList() else (0 until list.length()).map { list.optString(it) }.filter { it.isNotBlank() }
    } catch (e: JSONException) {
        emptyList()
    }
}
