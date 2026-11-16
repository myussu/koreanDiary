package com.jorge.koreandiary.ink

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reconocimiento de escritura coreana con ML Kit Digital Ink (offline tras descargar el modelo, ~20 MB).
 * Se reconoce línea por línea: ML Kit funciona mejor con una línea de texto a la vez.
 */
class HandwritingRecognizer {
    private val model: DigitalInkRecognitionModel by lazy {
        val identifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag("ko")
            ?: error("ML Kit no tiene modelo para coreano")
        DigitalInkRecognitionModel.builder(identifier).build()
    }
    private val recognizer by lazy {
        DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
    }
    private val manager get() = RemoteModelManager.getInstance()

    suspend fun isModelDownloaded(): Boolean = manager.isModelDownloaded(model).await()

    /**
     * Baja el modelo (~20 MB) con la red que haya, con un tiempo límite. Nunca con condiciones de ML
     * Kit («solo Wi-Fi»): ML Kit junta las descargas del mismo modelo, y una que espera el Wi-Fi hacía
     * esperar para siempre a las que pedías después con datos móviles. La precarga decide antes, con
     * [onUnmeteredNetwork], si conviene bajarlo.
     *
     * @throws ModelDownloadException con el mensaje en español, listo para mostrar.
     */
    suspend fun downloadModel() {
        try {
            withTimeout(DOWNLOAD_TIMEOUT_MS) { manager.download(model, DownloadConditions.Builder().build()).await() }
        } catch (e: TimeoutCancellationException) {
            throw ModelDownloadException(SLOW_DOWNLOAD, e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ModelDownloadException(NO_DOWNLOAD, e)
        }
    }

    /**
     * Devuelve el texto de cada línea, en orden, para las líneas desde [fromLine] (inclusive).
     * Las líneas vacías se omiten.
     *
     * [preContext] es el texto que va justo antes de lo escrito (ML Kit usa los últimos 20 caracteres,
     * espacios incluidos): con él, su modelo de idioma prefiere palabras que existen. El repaso
     * rápido pasa la oración antes del hueco; la escena viva, el renglón anterior. Entre renglones
     * se suma solo lo leído.
     */
    suspend fun recognizeLines(
        strokes: List<InkStroke>,
        lineHeight: Float,
        width: Float,
        fromLine: Int = 0,
        preContext: String = "",
    ): List<RecognizedLine> {
        if (!isModelDownloaded()) downloadModel()
        val byLine = strokes.filter { it.points.isNotEmpty() }
            .groupBy { it.lineIndex(lineHeight) }
            .filterKeys { it >= fromLine }
            .toSortedMap()
        val result = mutableListOf<RecognizedLine>()
        var before = preContext
        for ((line, lineStrokes) in byLine) {
            val offsetY = line * lineHeight
            val inkBuilder = Ink.builder()
            // Orden espacial y tiempos coherentes: la reescritura en el lugar no confunde al reconocedor.
            for (s in InkOrdering.spatialOrder(lineStrokes)) {
                val sb = Ink.Stroke.builder()
                for (p in s.points) sb.addPoint(Ink.Point.create(p.x, p.y - offsetY, p.t))
                inkBuilder.addStroke(sb.build())
            }
            val context = RecognitionContext.builder()
                .setPreContext(before.takeLast(20))
                .setWritingArea(WritingArea(width, lineHeight))
                .build()
            val candidates = recognizer.recognize(inkBuilder.build(), context).await().candidates
            val text = candidates.firstOrNull()?.text?.trim().orEmpty()
            if (text.isNotEmpty()) {
                // Hasta 4 alternativas: más abajo las lecturas suelen ser casi la respuesta y dejarían
                // pasar errores de verdad en el repaso rápido.
                result += RecognizedLine(line, text, candidates.drop(1).take(4).map { it.text })
                before = if (before.isEmpty()) text else "$before $text"
            }
        }
        return result
    }

    fun close() = recognizer.close()

    companion object {
        /** Cuánto se espera la descarga del modelo antes de decir que tarda demasiado. */
        const val DOWNLOAD_TIMEOUT_MS = 90_000L

        private const val NO_DOWNLOAD =
            "Sin conexión: la primera vez hay que bajar el modelo de escritura coreana (unos 20 MB). Conéctate y prueba otra vez."
        private const val SLOW_DOWNLOAD =
            "El modelo de escritura coreana (unos 20 MB) tarda demasiado en bajar. Revisa la conexión y prueba otra vez."

        /**
         * El mensaje para la pantalla si [e] vino del reconocedor (la descarga del modelo o ML Kit), sin
         * la excepción en inglés. Null si es otro error: quien llama muestra el suyo.
         */
        fun inkMessage(e: Throwable): String? = when {
            e is ModelDownloadException -> e.message
            generateSequence(e) { it.cause }.any { it is MlKitException } -> "No se pudo leer tu letra. Prueba otra vez."
            else -> null
        }

        /** ¿Hay una red sin medición (Wi-Fi)? Para la precarga: no gastar datos móviles sin que lo pidas. */
        fun onUnmeteredNetwork(context: Context): Boolean {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
    }
}

data class RecognizedLine(val line: Int, val text: String, val alternatives: List<String>)

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

/** El modelo de escritura no se pudo bajar (sin conexión o tardó demasiado). El mensaje va en español. */
class ModelDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)
