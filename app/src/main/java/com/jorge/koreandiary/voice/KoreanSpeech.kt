package com.jorge.koreandiary.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Reconocimiento de voz y lectura en coreano del propio Android: gratis y sin gastar cuota de
 * Gemini. Se usa para practicar las correcciones en voz alta. Llamar siempre desde el hilo principal.
 */
class KoreanSpeech(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSay: String? = null

    fun recognitionAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** Escucha una oración. [onResult] recibe las alternativas del reconocedor, la mejor primero. */
    fun listen(onPartial: (String) -> Unit, onResult: (List<String>) -> Unit, onError: (String) -> Unit) {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onError(error: Int) = onError(message(error))
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                if (list.isEmpty()) onError(message(SpeechRecognizer.ERROR_NO_MATCH)) else onResult(list)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        r.startListening(intent)
    }

    fun stopListening() {
        recognizer?.stopListening()
    }

    /** Lee la oración con la voz coreana del celular, para escucharla antes de repetirla. */
    fun say(text: String) {
        val engine = tts
        if (engine == null) {
            pendingSay = text
            tts = TextToSpeech(context) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.setLanguage(Locale.KOREAN)
                    tts?.setSpeechRate(0.85f)
                    pendingSay?.let { speak(it) }
                }
                pendingSay = null
            }
            return
        }
        if (ttsReady) speak(text) else pendingSay = text
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "drill")
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    private fun message(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "No te entendí. Intenta otra vez."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No escuché nada. Pulsa «Decir» y habla."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Falta el permiso de micrófono."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Sin conexión para reconocer la voz."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "El reconocimiento de voz está ocupado. Intenta en un momento."
        // ERROR_LANGUAGE_NOT_SUPPORTED (12) y ERROR_LANGUAGE_UNAVAILABLE (13), Android 12+.
        12, 13 -> "Tu reconocimiento de voz no tiene coreano: agrégalo en los ajustes de idioma del celular."
        else -> "Error del reconocimiento de voz ($code)."
    }
}
