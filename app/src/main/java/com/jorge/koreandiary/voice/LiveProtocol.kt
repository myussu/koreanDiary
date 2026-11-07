package com.jorge.koreandiary.voice

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** Cómo se decide que terminaste de hablar. */
object TurnMode {
    /** Pulsas "Hablar" y luego "Listo": la IA nunca te corta, aunque pienses un minuto. */
    const val PUSH_TO_TALK = "ptt"
    /** La IA detecta el silencio. Más natural, pero puede responder si dudas mucho. */
    const val AUTO = "auto"
}

/** Todo lo que necesita el mensaje "setup" de la Live API. */
data class LiveConfig(
    val model: String,
    val voice: String,
    val systemInstruction: String,
    val turnMode: String,
    val silenceMs: Int,
    /** Palabras que el reconocimiento debe esperar (mejora la transcripción de tu coreano). */
    val vocabulary: List<String> = emptyList(),
    /** Idiomas esperados en lo que dices: coreano y español. */
    val languageCodes: List<String> = listOf("ko-KR", "es-US"),
    /** Token de reanudación: retoma la misma conversación en una conexión nueva. */
    val resumeHandle: String? = null,
    /** Sin opciones de transcripción: plan B si el servidor rechaza la configuración completa. */
    val minimal: Boolean = false,
)

/** Lo que llega del servidor, ya interpretado. */
sealed interface LiveEvent {
    data object SetupComplete : LiveEvent
    /** PCM de 16 bits, mono, 24 kHz. */
    class Audio(val pcm: ByteArray) : LiveEvent
    /** Transcripción (parcial) de lo que dijiste. */
    data class InputText(val text: String) : LiveEvent
    /** Transcripción (parcial) de lo que dijo la IA. */
    data class OutputText(val text: String) : LiveEvent
    /** Empezaste a hablar mientras la IA hablaba: hay que cortar su audio. */
    data object Interrupted : LiveEvent
    data object TurnComplete : LiveEvent
    data class Usage(val prompt: Int, val response: Int, val total: Int) : LiveEvent
    data class ResumeHandle(val handle: String) : LiveEvent
    /** El servidor va a cerrar la conexión pronto (unos 10 minutos por conexión). */
    data class GoAway(val timeLeftMs: Long?) : LiveEvent
}

/**
 * Mensajes JSON de la Live API (WebSocket). Funciones puras para poder probarlas sin red.
 * Referencia: ai.google.dev/api/live
 */
object LiveProtocol {
    const val ENDPOINT =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    /**
     * Compresión del contexto: al llegar a [TRIGGER_TOKENS] se descarta lo más antiguo hasta [TARGET_TOKENS].
     * El audio cuenta unos 32 tokens por segundo, así que 48K ≈ 25 minutos de conversación. Más bajo que el
     * valor por defecto (80 % de 128K) porque cada turno vuelve a procesar el contexto: menos tokens por turno.
     */
    const val TRIGGER_TOKENS = 48_000
    const val TARGET_TOKENS = 24_000

    /** Máximo de términos para la transcripción: la documentación dice que rinde mejor hasta ~100. */
    const val MAX_VOCABULARY = 100

    fun setup(c: LiveConfig): String {
        val vad = if (c.turnMode == TurnMode.PUSH_TO_TALK) {
            JSONObject().put("disabled", true)
        } else {
            JSONObject()
                .put("disabled", false)
                // Sensibilidad baja: tus "음…", "eh…" y pausas no cuentan como fin de turno tan rápido.
                .put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW")
                .put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                .put("prefixPaddingMs", 300)
                .put("silenceDurationMs", c.silenceMs.coerceIn(500, 10_000))
        }
        val inputTranscription = JSONObject()
        if (!c.minimal) {
            if (c.languageCodes.isNotEmpty()) inputTranscription.put("languageCodes", JSONArray(c.languageCodes))
            val vocab = c.vocabulary.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_VOCABULARY)
            if (vocab.isNotEmpty()) inputTranscription.put("customVocabulary", JSONArray(vocab))
        }
        val setup = JSONObject()
            .put("model", if (c.model.startsWith("models/")) c.model else "models/${c.model}")
            .put(
                "generationConfig",
                JSONObject()
                    // Los modelos de audio nativo solo devuelven audio; el texto llega por la transcripción.
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig",
                        JSONObject().put(
                            "voiceConfig",
                            JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", c.voice)),
                        ),
                    ),
            )
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", c.systemInstruction))))
            .put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", vad))
            .put("inputAudioTranscription", inputTranscription)
            .put("outputAudioTranscription", JSONObject())
            .put(
                "contextWindowCompression",
                JSONObject()
                    .put("triggerTokens", TRIGGER_TOKENS)
                    .put("slidingWindow", JSONObject().put("targetTokens", TARGET_TOKENS)),
            )
            .put("sessionResumption", JSONObject().apply { c.resumeHandle?.let { put("handle", it) } })
        return JSONObject().put("setup", setup).toString()
    }

    /** Un trozo de micrófono: PCM de 16 bits, mono, 16 kHz. */
    fun audio(pcm: ByteArray, length: Int = pcm.size): String {
        val data = Base64.getEncoder().encodeToString(if (length == pcm.size) pcm else pcm.copyOf(length))
        return realtime(JSONObject().put("audio", JSONObject().put("data", data).put("mimeType", "audio/pcm;rate=16000")))
    }

    /** Pulsas para hablar: empieza tu turno (solo con la detección automática apagada). */
    fun activityStart(): String = realtime(JSONObject().put("activityStart", JSONObject()))

    fun activityEnd(): String = realtime(JSONObject().put("activityEnd", JSONObject()))

    /** Modo automático: el micrófono se pausa (silenciado o en segundo plano). */
    fun audioStreamEnd(): String = realtime(JSONObject().put("audioStreamEnd", true))

    /**
     * Texto de la app para el modelo de voz. Con [respond] = false se agrega en silencio al contexto
     * (lo tendrá en cuenta en su próxima respuesta); con true pide una respuesta ya.
     */
    fun context(text: String, respond: Boolean): String {
        val turn = JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))
        return JSONObject()
            .put("clientContent", JSONObject().put("turns", JSONArray().put(turn)).put("turnComplete", respond))
            .toString()
    }

    private fun realtime(body: JSONObject) = JSONObject().put("realtimeInput", body).toString()

    /** Un mensaje del servidor puede traer varias cosas a la vez (p. ej. audio y uso de tokens). */
    fun parse(raw: String): List<LiveEvent> {
        val o = try {
            JSONObject(raw)
        } catch (e: org.json.JSONException) {
            return emptyList()
        }
        val events = mutableListOf<LiveEvent>()
        if (o.has("setupComplete")) events += LiveEvent.SetupComplete
        o.optJSONObject("serverContent")?.let { sc ->
            sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
                events += LiveEvent.InputText(it)
            }
            val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                    val mime = inline.optString("mimeType")
                    if (mime.isNotEmpty() && !mime.startsWith("audio/")) continue
                    val data = inline.optString("data")
                    if (data.isEmpty()) continue
                    runCatching { Base64.getDecoder().decode(data) }.getOrNull()?.let { events += LiveEvent.Audio(it) }
                }
            }
            sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
                events += LiveEvent.OutputText(it)
            }
            if (sc.optBoolean("interrupted")) events += LiveEvent.Interrupted
            if (sc.optBoolean("turnComplete")) events += LiveEvent.TurnComplete
        }
        o.optJSONObject("sessionResumptionUpdate")?.let { u ->
            val handle = u.optString("newHandle")
            if (u.optBoolean("resumable") && handle.isNotEmpty()) events += LiveEvent.ResumeHandle(handle)
        }
        if (o.has("goAway")) events += LiveEvent.GoAway(durationMs(o.optJSONObject("goAway")?.opt("timeLeft")))
        o.optJSONObject("usageMetadata")?.let { u ->
            events += LiveEvent.Usage(u.optInt("promptTokenCount"), u.optInt("responseTokenCount"), u.optInt("totalTokenCount"))
        }
        return events
    }

    /** Duration de protobuf en JSON: "12s", "1.5s", o {"seconds": 12, "nanos": 0}. */
    internal fun durationMs(v: Any?): Long? = when (v) {
        is String -> v.removeSuffix("s").toDoubleOrNull()?.let { (it * 1000).toLong() }
        is JSONObject -> v.optLong("seconds") * 1000 + v.optLong("nanos") / 1_000_000
        is Number -> v.toLong() * 1000
        else -> null
    }
}
