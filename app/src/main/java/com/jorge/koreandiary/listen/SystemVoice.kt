package com.jorge.koreandiary.listen

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Plan B del narrador: la voz coreana del celular (sin conexión y sin cuota). Se sintetiza a un
 * archivo WAV para poder repetir, pausar y cambiar la velocidad igual que con la voz de Gemini.
 */
class SystemVoice(private val context: Context) {
    private var tts: TextToSpeech? = null
    private val ready = CompletableDeferred<String?>()
    private val pending = ConcurrentHashMap<String, (String?) -> Unit>()

    private suspend fun engine(): TextToSpeech {
        if (tts == null) {
            withContext(Dispatchers.Main) {
                if (tts != null) return@withContext
                tts = TextToSpeech(context.applicationContext) { status ->
                    val t = tts
                    val error = when {
                        status != TextToSpeech.SUCCESS || t == null -> "La voz del celular no está disponible."
                        t.setLanguage(Locale.KOREAN).let { it == TextToSpeech.LANG_MISSING_DATA || it == TextToSpeech.LANG_NOT_SUPPORTED } ->
                            "Falta la voz coreana del celular: instálala en Ajustes › Texto a voz."
                        else -> null
                    }
                    t?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) {
                            utteranceId?.let { pending.remove(it)?.invoke(null) }
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            utteranceId?.let { pending.remove(it)?.invoke("La voz del celular falló.") }
                        }
                        override fun onError(utteranceId: String?, errorCode: Int) {
                            utteranceId?.let { pending.remove(it)?.invoke("La voz del celular falló ($errorCode).") }
                        }
                    })
                    ready.complete(error)
                }
            }
        }
        val error = withTimeout(10_000) { ready.await() }
        if (error != null) throw IllegalStateException(error)
        return tts!!
    }

    suspend fun synth(text: String): PcmAudio {
        val engine = engine()
        val file = File(context.cacheDir, "voz_${System.nanoTime()}.wav")
        val id = file.name
        try {
            withTimeout(60_000) {
                suspendCancellableCoroutine { cont ->
                    pending[id] = { error ->
                        if (error == null) cont.resume(Unit) else cont.resumeWithException(IllegalStateException(error))
                    }
                    cont.invokeOnCancellation { pending.remove(id) }
                    val result = engine.synthesizeToFile(text, Bundle(), file, id)
                    if (result != TextToSpeech.SUCCESS) {
                        pending.remove(id)
                        cont.resumeWithException(IllegalStateException("La voz del celular no pudo leer el texto."))
                    }
                }
            }
            return withContext(Dispatchers.IO) { Wav.parse(file.readBytes(), defaultRate = 22_050) }
        } finally {
            file.delete()
        }
    }

    fun release() {
        runCatching { tts?.shutdown() }
        tts = null
    }
}
