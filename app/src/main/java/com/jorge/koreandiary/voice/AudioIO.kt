package com.jorge.koreandiary.voice

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Micrófono a 16 kHz, mono, PCM de 16 bits (lo que pide la Live API), en trozos de 40 ms.
 * Usa la fuente de "comunicación por voz", que activa la cancelación de eco del celular.
 */
class Microphone(private val onChunk: (ByteArray, Int) -> Unit) {
    private var record: AudioRecord? = null
    private var job: Job? = null
    private val effects = mutableListOf<android.media.audiofx.AudioEffect>()

    /** Llamar solo con el permiso RECORD_AUDIO concedido. */
    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        if (job != null) return
        val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, CHUNK_BYTES * 4),
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw IllegalStateException("No se pudo abrir el micrófono.")
        }
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(r.audioSessionId)?.let { it.setEnabled(true); effects += it }
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(r.audioSessionId)?.let { it.setEnabled(true); effects += it }
        record = r
        r.startRecording()
        job = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(CHUNK_BYTES)
            while (isActive) {
                val n = r.read(buffer, 0, buffer.size)
                if (n > 0) onChunk(buffer, n)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
        effects.forEach { runCatching { it.release() } }
        effects.clear()
    }

    companion object {
        const val RATE = 16_000
        /** 40 ms de audio: 16 000 muestras/s × 2 bytes × 0,04 s. */
        const val CHUNK_BYTES = 1_280
    }
}

/** Parlante a 24 kHz (lo que devuelve la Live API). Se puede vaciar al instante si interrumpes a la IA. */
class Speaker(scope: CoroutineScope) {
    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), RATE))
        .build()

    private val queue = Channel<ByteArray>(Channel.UNLIMITED)
    @Volatile private var framesWritten = 0L
    @Volatile private var generation = 0

    private val job = scope.launch(Dispatchers.IO) {
        track.play()
        for (chunk in queue) {
            val gen = generation
            var offset = 0
            while (offset < chunk.size && gen == generation) {
                val n = track.write(chunk, offset, chunk.size - offset)
                if (n <= 0) break
                offset += n
            }
            if (gen == generation) framesWritten += offset / 2
        }
    }

    fun play(pcm: ByteArray) {
        queue.trySend(pcm)
    }

    /** Corta lo que la IA estaba diciendo (la interrumpiste o empezaste a hablar). */
    fun flush() {
        generation++
        while (queue.tryReceive().isSuccess) Unit
        runCatching {
            track.pause()
            track.flush()
            framesWritten = 0
            track.play()
        }
    }

    /** Milisegundos de audio que faltan por sonar: > 0 mientras la IA habla. */
    fun pendingMs(): Long {
        val played = runCatching { track.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L)
        val queued = (framesWritten - played).coerceAtLeast(0)
        return queued * 1000 / RATE
    }

    fun release() {
        job.cancel()
        queue.close()
        runCatching { track.stop() }
        track.release()
    }

    companion object {
        const val RATE = 24_000
    }
}
