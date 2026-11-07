package com.jorge.koreandiary.listen

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Reproduce un segmento de la narración. Va en su propio AudioTrack (las respuestas de la IA y los
 * tonos usan otro), así se puede pausar a mitad de frase, hablar con la IA y seguir donde quedó.
 * La velocidad se cambia sin alterar el tono de la voz (PlaybackParams).
 */
class NarrationPlayer(private val scope: CoroutineScope) {
    @Volatile private var track: AudioTrack? = null
    @Volatile private var trackRate = 0
    private var job: Job? = null
    @Volatile private var generation = 0
    @Volatile private var paused = false
    var speed: Float = 1f
        private set

    val isPaused get() = paused

    /**
     * Reproduce desde el inicio, tras [delayMs] (para que no se pise con un tono). [onDone] se llama al
     * terminar de sonar (no si se detiene antes).
     */
    fun play(audio: PcmAudio, delayMs: Long = 0, onDone: () -> Unit) {
        val old = job
        stop()
        val gen = ++generation
        paused = false
        job = scope.launch(Dispatchers.IO) {
            // El write anterior pudo quedar bloqueado: se espera a que salga y se vacía otra vez.
            old?.join()
            if (delayMs > 0) delay(delayMs)
            if (gen != generation) return@launch
            val t = trackFor(audio.rate)
            runCatching {
                t.pause()
                t.flush()
            }
            applySpeed(t)
            if (!paused) runCatching { t.play() }
            val data = audio.data
            val totalFrames = data.size / 2
            var offset = 0
            while (offset < data.size && gen == generation && isActive) {
                val n = t.write(data, offset, minOf(CHUNK, data.size - offset))
                if (n <= 0) break
                offset += n
            }
            // Un poco de silencio al final empuja las últimas muestras fuera del búfer.
            if (gen == generation) t.write(ByteArray(audio.rate / 5 * 2), 0, audio.rate / 5 * 2)
            // Espera a que suene todo. Si la posición no avanza sin estar en pausa, se da por terminado.
            var last = -1L
            var stalledSince = 0L
            while (gen == generation && isActive) {
                val head = runCatching { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L)
                if (head >= totalFrames) break
                val now = System.currentTimeMillis()
                if (!paused && head == last) {
                    if (stalledSince == 0L) stalledSince = now
                    if (now - stalledSince > 3_000) break
                } else {
                    stalledSince = 0L
                }
                last = head
                delay(40)
            }
            if (gen == generation && isActive) onDone()
        }
    }

    fun pause() {
        if (job?.isActive != true) return
        paused = true
        runCatching { track?.pause() }
    }

    fun resume() {
        if (!paused) return
        paused = false
        runCatching { track?.play() }
    }

    /** Corta lo que suena y descarta el resto del segmento. */
    fun stop() {
        generation++
        job?.cancel()
        job = null
        paused = false
        track?.let {
            runCatching {
                it.pause()
                it.flush()
            }
        }
    }

    val isPlaying get() = job?.isActive == true

    fun setSpeed(value: Float) {
        speed = value.coerceIn(0.5f, 1.5f)
        track?.let { applySpeed(it) }
    }

    private fun applySpeed(t: AudioTrack) {
        runCatching { t.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f) }
    }

    private fun trackFor(rate: Int): AudioTrack {
        track?.let { if (trackRate == rate) return it }
        track?.release()
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), rate))
            .build()
        track = t
        trackRate = rate
        return t
    }

    fun release() {
        stop()
        track?.release()
        track = null
    }

    private companion object {
        const val CHUNK = 9_600
    }
}
