package com.jorge.koreandiary.listen

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Audio guardado de una escucha, listo para volver a oírlo. */
data class SavedNarration(val file: File, val starts: List<SegmentStart>, val starred: Boolean)

/**
 * Audio de las escuchas para volver a oírlas sin gastar cuota: `filesDir/listen_audio/<id>.m4a` y
 * `<id>.json` (dónde empieza cada segmento y si está marcado). Se guarda en AAC (unos 0,4 MB por
 * minuto, contra 2,8 MB del PCM): quedan las [NarrationAudio.KEEP_RECENT] últimas escuchas y todas
 * las marcadas con ★. Entra en la copia exportada y al pasar de celular, no en la nube de Google
 * (su límite es de 25 MB por app).
 */
class ListenAudioStore(context: Context) {
    private val dir = File(context.filesDir, DIR)

    /** Cambia cada vez que se guarda, se marca o se borra algo, para que la pantalla se entere. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun get(id: Long): SavedNarration? {
        val audio = File(dir, "$id.m4a")
        val meta = File(dir, "$id.json")
        if (!audio.exists() || !meta.exists()) return null
        return runCatching {
            val o = JSONObject(meta.readText())
            val arr = o.optJSONArray("starts") ?: JSONArray()
            val starts = (0 until arr.length()).mapNotNull { k ->
                val pair = arr.optJSONArray(k) ?: return@mapNotNull null
                SegmentStart(pair.optInt(0), pair.optLong(1))
            }
            SavedNarration(audio, starts, o.optBoolean("star", false))
        }.getOrNull()
    }

    /** Une los segmentos, los guarda en AAC y aplica el límite. Sin audio no guarda nada. */
    suspend fun save(id: Long, segments: List<PcmAudio?>) = withContext(Dispatchers.Default) {
        val joined = NarrationAudio.join(segments) ?: return@withContext
        dir.mkdirs()
        val tmp = File(dir, "$id.m4a.tmp")
        runCatching { encodeAac(joined.audio, tmp) }.onFailure { tmp.delete(); return@withContext }
        tmp.renameTo(File(dir, "$id.m4a"))
        writeMeta(id, joined.starts, starred = false)
        prune()
        _version.value++
    }

    suspend fun setStarred(id: Long, starred: Boolean) = withContext(Dispatchers.IO) {
        val saved = get(id) ?: return@withContext
        writeMeta(id, saved.starts, starred)
        prune()
        _version.value++
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        File(dir, "$id.m4a").delete()
        File(dir, "$id.json").delete()
        _version.value++
    }

    private fun writeMeta(id: Long, starts: List<SegmentStart>, starred: Boolean) {
        val o = JSONObject()
            .put("v", 1)
            .put("star", starred)
            .put("starts", JSONArray().apply { starts.forEach { put(JSONArray().put(it.index).put(it.ms)) } })
        val tmp = File(dir, "$id.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(dir, "$id.json"))
    }

    private fun prune() {
        val entries = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
            val id = f.name.removeSuffix(".json").toLongOrNull() ?: return@mapNotNull null
            id to (runCatching { JSONObject(f.readText()).optBoolean("star", false) }.getOrDefault(false))
        }
        NarrationAudio.toDelete(entries).forEach { id ->
            File(dir, "$id.m4a").delete()
            File(dir, "$id.json").delete()
        }
    }

    companion object {
        /** Carpeta en filesDir; está en `BackupFormat.MANAGED_DIRS` y en las reglas de copia. */
        const val DIR = "listen_audio"
        /** Voz: 48 kbps en AAC-LC mono se oye igual que el original. */
        private const val BIT_RATE = 48_000
        private const val TIMEOUT_US = 10_000L

        /** PCM de 16 bits mono → AAC-LC en un .m4a, con el codificador del propio Android. */
        internal fun encodeAac(pcm: PcmAudio, out: File) {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, pcm.rate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1
            var muxing = false
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                val info = MediaCodec.BufferInfo()
                val bytesPerSecond = pcm.rate * 2L
                var offset = 0
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val buffer = codec.getInputBuffer(inIndex) ?: error("Sin búfer de entrada")
                            buffer.clear()
                            // Siempre un número par de bytes: cada muestra son 2.
                            val n = minOf(buffer.remaining(), pcm.data.size - offset) and 1.inv()
                            val pts = offset * 1_000_000L / bytesPerSecond
                            if (n <= 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                buffer.put(pcm.data, offset, n)
                                codec.queueInputBuffer(inIndex, 0, n, pts, 0)
                                offset += n
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxing = true
                        }
                        outIndex >= 0 -> {
                            val data = codec.getOutputBuffer(outIndex) ?: error("Sin búfer de salida")
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0 && muxing) {
                                data.position(info.offset)
                                data.limit(info.offset + info.size)
                                muxer.writeSampleData(track, data, info)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
                if (muxing) runCatching { muxer.stop() }
                muxer.release()
            }
        }
    }
}
