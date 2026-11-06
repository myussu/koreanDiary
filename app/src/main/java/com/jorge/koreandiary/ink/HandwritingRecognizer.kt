package com.jorge.koreandiary.ink

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import kotlinx.coroutines.suspendCancellableCoroutine
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

    /** [wifiOnly] para la precarga en segundo plano: ~20 MB que no deben gastar datos móviles. */
    suspend fun downloadModel(wifiOnly: Boolean = false) {
        val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        manager.download(model, conditions).await()
    }

    /**
     * Devuelve el texto de cada línea, en orden, para las líneas desde [fromLine] (inclusive).
     * Las líneas vacías se omiten.
     */
    suspend fun recognizeLines(
        strokes: List<InkStroke>,
        lineHeight: Float,
        width: Float,
        fromLine: Int = 0,
    ): List<RecognizedLine> {
        if (!isModelDownloaded()) downloadModel()
        val byLine = strokes.filter { it.points.isNotEmpty() }
            .groupBy { it.lineIndex(lineHeight) }
            .filterKeys { it >= fromLine }
            .toSortedMap()
        val result = mutableListOf<RecognizedLine>()
        var preContext = ""
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
                .setPreContext(preContext.takeLast(20))
                .setWritingArea(WritingArea(width, lineHeight))
                .build()
            val candidates = recognizer.recognize(inkBuilder.build(), context).await().candidates
            val text = candidates.firstOrNull()?.text?.trim().orEmpty()
            if (text.isNotEmpty()) {
                result += RecognizedLine(line, text, candidates.drop(1).take(3).map { it.text })
                preContext = "$preContext $text"
            }
        }
        return result
    }

    fun close() = recognizer.close()
}

data class RecognizedLine(val line: Int, val text: String, val alternatives: List<String>)

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
