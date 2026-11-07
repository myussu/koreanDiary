package com.jorge.koreandiary

import com.jorge.koreandiary.ai.ParseException
import com.jorge.koreandiary.ai.PromptItem
import com.jorge.koreandiary.conversation.ConversationRecord
import com.jorge.koreandiary.conversation.ConversationStore
import com.jorge.koreandiary.conversation.CorrectionStyle
import com.jorge.koreandiary.conversation.RecordMode
import com.jorge.koreandiary.listen.ComprehensionResult
import com.jorge.koreandiary.listen.Earcon
import com.jorge.koreandiary.listen.Earcons
import com.jorge.koreandiary.listen.ListenJson
import com.jorge.koreandiary.listen.ListenPrompts
import com.jorge.koreandiary.listen.ListenScriptParser
import com.jorge.koreandiary.listen.PcmAudio
import com.jorge.koreandiary.listen.ScriptPlan
import com.jorge.koreandiary.listen.SilenceSplitter
import com.jorge.koreandiary.listen.VoiceCommand
import com.jorge.koreandiary.listen.VoiceCommands
import com.jorge.koreandiary.listen.Wav
import com.jorge.koreandiary.voice.LiveConfig
import com.jorge.koreandiary.voice.LiveProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class ListenTest {

    private val raw = """
        Aquí está el guion:
        ```json
        {"titulo":"제주도 여행","titulo_es":"Viaje a Jeju",
         "segmentos":[
           {"texto":"지난 주말에 제주도에 갔어요.","es":"El fin de semana pasado fui a Jeju.","objetivos":["w1","w1","x9"],
            "pregunta":{"texto":"여행 좋아해요?","es":"¿Te gusta viajar?","objetivo":"w1"}},
           {"texto":"바다가 정말 예뻤어요.","es":"El mar era precioso.","objetivos":["g2"]},
           {"texto":"","es":"vacío"},
           {"texto":"다음에 또 가고 싶어요.","es":"Quiero volver.","objetivos":[],"pregunta":{"texto":"어디 가고 싶어요?","es":"","objetivo":""}}
         ],
         "comprension":[{"pregunta":"어디에 갔어요?","es":"¿A dónde fue?","respuesta":"A Jeju"},{"pregunta":"","es":"","respuesta":""}]}
        ```
    """.trimIndent()

    @Test
    fun parsesScriptAndFiltersUnknownTargets() {
        val s = ListenScriptParser.parse(raw, setOf("w1", "g2"))
        assertEquals("제주도 여행", s.title)
        assertEquals(3, s.segments.size)
        assertEquals(listOf("w1", "w1"), s.segments[0].targets)
        assertEquals("w1", s.segments[0].question?.target)
        // Nunca hay pregunta después del último segmento.
        assertNull(s.segments.last().question)
        assertEquals(1, s.comprehension.size)
        assertEquals("A Jeju", s.comprehension[0].answer)
        assertEquals(mapOf("w1" to 2), s.targetCounts(1))
        assertEquals(mapOf("w1" to 2, "g2" to 1), s.targetCounts())
    }

    @Test
    fun emptyTargetInQuestionIsNull() {
        val s = ListenScriptParser.parse(
            """{"segmentos":[{"texto":"가","pregunta":{"texto":"뭐예요?","objetivo":""}},{"texto":"나"}]}""",
        )
        assertNotNull(s.segments[0].question)
        assertNull(s.segments[0].question?.target)
    }

    @Test(expected = ParseException::class)
    fun scriptWithoutSegmentsFails() {
        ListenScriptParser.parse("""{"titulo":"x","segmentos":[]}""")
    }

    @Test
    fun scriptRoundTrip() {
        val s = ListenScriptParser.parse(raw, setOf("w1", "g2")).copy(sources = listOf("Visit Jeju — https://example.com"))
        val back = ListenScriptParser.decode(JSONObject(ListenScriptParser.encode(s).toString()))
        assertEquals(s, back)
    }

    @Test
    fun scriptPromptMentionsPlanTargetsAndSearch() {
        val targets = listOf(PromptItem("w1", "여행", "palabra"))
        val p = ListenPrompts.script("", "3A", 3, "Jeju", targets, 10, search = true)
        val plan = ScriptPlan.forMinutes(10)
        assertTrue(p.contains("${plan.syllables} sílabas"))
        assertTrue(p.contains("${plan.segments} segmentos"))
        assertTrue(p.contains("w1: 여행"))
        assertTrue(p.contains("Busca en Google"))
        assertFalse(ListenPrompts.script("", "3A", 3, "Jeju", emptyList(), 5, search = false).contains("Busca en Google"))
    }

    @Test
    fun liveSystemHasScriptRulesAndCorrection() {
        val s = ListenScriptParser.parse(raw, setOf("w1", "g2"))
        val p = ListenPrompts.liveSystem("", "3A", 3, s, emptyList(), CorrectionStyle.REPEAT)
        assertTrue(p.contains("[1] 지난 주말에 제주도에 갔어요."))
        assertTrue(p.contains("Question after segment 1: 여행 좋아해요?"))
        assertTrue(p.contains("resume_narration"))
        assertTrue(p.contains("answer IN SPANISH"))
        assertTrue(p.contains("Important problems"))
        assertFalse(p.contains("CORRECTION_RULES"))
    }

    @Test
    fun voiceCommandsOnlyWhenTheWholeUtteranceIsACommand() {
        assertEquals(VoiceCommand.RESUME, VoiceCommands.match("Sigue, por favor."))
        assertEquals(VoiceCommand.RESUME, VoiceCommands.match("계속 해 주세요"))
        assertEquals(VoiceCommand.REPEAT, VoiceCommands.match("¿Otra vez?"))
        assertEquals(VoiceCommand.SLOWER, VoiceCommands.match("repite más lento"))
        assertEquals(VoiceCommand.SLOWER, VoiceCommands.match("다시 천천히"))
        assertEquals(VoiceCommand.PAUSE, VoiceCommands.match("잠깐만요"))
        assertEquals(VoiceCommand.FINISH, VoiceCommands.match("termina"))
        // Preguntas que contienen la palabra no son órdenes.
        assertNull(VoiceCommands.match("다시 한 번 설명해 주세요 그 단어"))
        assertNull(VoiceCommands.match("¿qué significa 계속?"))
        assertNull(VoiceCommands.match(""))
    }

    @Test
    fun liveSetupDeclaresListenTools() {
        val base = LiveConfig("gemini-3.8-live", "Kore", "x", "ptt", 2000, listenTools = true)
        val decl = JSONObject(LiveProtocol.setup(base)).getJSONObject("setup").getJSONArray("tools")
            .getJSONObject(0).getJSONArray("functionDeclarations")
        val names = (0 until decl.length()).map { decl.getJSONObject(it).getString("name") }
        assertTrue(names.containsAll(listOf("resume_narration", "repeat_segment", "pause_narration", "comprehension_result", "finish_listening")))
        assertFalse("highlight_target" in names)
        // Plan B nivel 2: sin herramientas.
        assertFalse(JSONObject(LiveProtocol.setup(base.copy(fallback = 2))).getJSONObject("setup").has("tools"))
    }

    // ---------------------------------------------------------------- Audio

    private fun wav(pcm: ByteArray, rate: Int, dataSize: Int = pcm.size): ByteArray {
        fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        return "RIFF".toByteArray() + le32(36 + pcm.size) + "WAVE".toByteArray() +
            "fmt ".toByteArray() + le32(16) + le16(1) + le16(1) + le32(rate) + le32(rate * 2) + le16(2) + le16(16) +
            "LIST".toByteArray() + le32(3) + byteArrayOf(1, 2, 3, 0) +
            "data".toByteArray() + le32(dataSize) + pcm
    }

    @Test
    fun parsesWavHeaderAndSkipsExtraChunks() {
        val pcm = ByteArray(480) { it.toByte() }
        val a = Wav.parse(wav(pcm, 22_050))
        assertEquals(22_050, a.rate)
        assertTrue(pcm.contentEquals(a.data))
        // Tamaño desconocido (streaming): hasta el final.
        assertEquals(480, Wav.parse(wav(pcm, 24_000, dataSize = -1)).data.size)
        // Sin cabecera: PCM crudo a 24 kHz.
        assertEquals(24_000, Wav.parse(pcm).rate)
    }

    /** Tono de [ms] seguido de silencio de [gaps] ms, para simular la voz con pausas. */
    private fun speech(rate: Int, parts: List<Pair<Int, Int>>): PcmAudio {
        val samples = mutableListOf<Short>()
        for ((voiceMs, silenceMs) in parts) {
            repeat(voiceMs * rate / 1000) { i -> samples += (sin(2 * PI * 220 * i / rate) * 8000).toInt().toShort() }
            repeat(silenceMs * rate / 1000) { samples += 0 }
        }
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            out[i * 2] = s.toByte()
            out[i * 2 + 1] = (s.toInt() shr 8).toByte()
        }
        return PcmAudio(out, rate)
    }

    @Test
    fun splitsAtTheLongPausesNotBetweenSentences() {
        val rate = 8_000
        // Tres segmentos de dos "oraciones" cada uno: pausas cortas (300 ms) dentro, largas (1500 ms) entre segmentos.
        val audio = speech(rate, listOf(2000 to 300, 2000 to 1500, 1500 to 300, 1500 to 1500, 3000 to 300, 1000 to 0))
        val ranges = SilenceSplitter.split(audio, listOf(40, 30, 40))
        assertEquals(3, ranges.size)
        val cutsMs = ranges.drop(1).map { it.first / 2 * 1000 / rate }
        assertTrue("primer corte en $cutsMs", abs(cutsMs[0] - (2000 + 300 + 2000 + 750)) < 200)
        assertTrue("segundo corte en $cutsMs", abs(cutsMs[1] - (2000 + 300 + 2000 + 1500 + 1500 + 300 + 1500 + 750)) < 200)
        // Los rangos cubren todo el audio, sin huecos.
        assertEquals(0, ranges.first().first)
        assertEquals(audio.data.size - 1, ranges.last().last)
        ranges.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
    }

    @Test
    fun splitFallsBackToProportionalWithoutPauses() {
        val audio = speech(8_000, listOf(6000 to 0))
        val ranges = SilenceSplitter.split(audio, listOf(1, 1, 2))
        assertEquals(3, ranges.size)
        assertNear(audio.data.size / 4, ranges[1].first, 2)
        assertEquals(1, SilenceSplitter.split(audio, listOf(5)).size)
    }

    private fun assertNear(expected: Int, actual: Int, tolerance: Int) = assertTrue("$actual ≈ $expected", abs(expected - actual) <= tolerance)

    @Test
    fun earconsAreShortAndAudible() {
        Earcon.entries.forEach { e ->
            val pcm = Earcons.render(e, 24_000)
            val ms = pcm.size / 2 * 1000 / 24_000
            assertTrue("$e dura $ms ms", ms in 50..800)
            var peak = 0
            for (i in pcm.indices step 2) peak = maxOf(peak, abs(((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()))
            assertTrue("$e se oye", peak > 1500)
        }
    }

    // ---------------------------------------------------------------- HTTP (sin red)

    @Test
    fun scriptBodyAddsSearchAndThinking() {
        val b = ListenJson.scriptBody("gemini-3.8-flash", "hola", search = true, thinking = true)
        assertEquals("google_search", b.getJSONArray("tools").getJSONObject(0).getString("type"))
        assertEquals("low", b.getJSONObject("generation_config").getString("thinking_level"))
        assertFalse(b.getBoolean("store"))
        val plain = ListenJson.scriptBody("m", "hola", search = false, thinking = false)
        assertFalse(plain.has("tools"))
        assertFalse(plain.has("generation_config"))
    }

    @Test
    fun speechBodyPutsStyleInAnnotation() {
        val b = ListenJson.speechBody("gemini-3.8-flash-tts", "안녕하세요", "Kore", "calm")
        val content = b.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(0)
        assertEquals("안녕하세요", content.getString("text"))
        assertEquals("calm", content.getJSONArray("annotations").getJSONObject(0).getString("style"))
        assertEquals("audio", b.getJSONObject("response_format").getString("type"))
        assertEquals("Kore", b.getJSONObject("generation_config").getJSONArray("speech_config").getJSONObject(0).getString("voice"))
    }

    @Test
    fun readsAudioAndSourcesFromInteraction() {
        val data = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val json = JSONObject(
            """{"steps":[
                 {"type":"user_input","content":[{"type":"text","text":"x"}]},
                 {"type":"model_output","content":[{"type":"audio","mime_type":"audio/wav","data":"$data"}]}
               ]}"""
        )
        assertTrue(byteArrayOf(1, 2, 3, 4).contentEquals(ListenJson.audio(json)))
        assertNull(ListenJson.audio(JSONObject("""{"steps":[]}""")))
        val withSources = JSONObject(
            """{"steps":[{"type":"model_output","content":[{"type":"text","text":"y",
                "annotations":[{"type":"url_citation","url":"https://a.com/x","title":"A"},{"url":"https://a.com/x"},{"url":"https://b.com"}]}]}]}"""
        )
        assertEquals(listOf("A — https://a.com/x", "https://b.com"), ListenJson.sources(withSources))
    }

    // ---------------------------------------------------------------- Guardado

    @Test
    fun listenRecordRoundTrip() {
        val r = ConversationRecord(
            id = 5, startedAt = 5, mode = RecordMode.LISTEN, scriptRaw = "{\"segmentos\":[]}",
            comprehension = listOf(ComprehensionResult(1, true, "A Jeju"), ComprehensionResult(2, false, "no sé")),
            heard = mapOf("w1" to 3), segmentsHeard = 4, repeats = 2, pauses = 1, narrator = "gemini+sistema",
        )
        val back = ConversationStore.decode(JSONObject(ConversationStore.encode(r).toString()))
        assertEquals(r, back)
        assertTrue(back.isListen)
        // Las sesiones viejas (sin "mode") son conversaciones.
        val old = JSONObject(ConversationStore.encode(r).toString()).apply { remove("mode") }
        assertEquals(RecordMode.CONVERSATION, ConversationStore.decode(old).mode)
    }
}
