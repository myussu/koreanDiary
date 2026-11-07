package com.jorge.koreandiary

import com.jorge.koreandiary.voice.LiveConfig
import com.jorge.koreandiary.voice.LiveEvent
import com.jorge.koreandiary.voice.LiveProtocol
import com.jorge.koreandiary.voice.TurnMode
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LiveProtocolTest {

    private fun config(mode: String, minimal: Boolean = false, handle: String? = null) = LiveConfig(
        model = "gemini-3.8-live", voice = "Kore", systemInstruction = "SYS", turnMode = mode, silenceMs = 2000,
        vocabulary = listOf("등산", "등산", " ", "회사"), resumeHandle = handle, minimal = minimal,
    )

    @Test
    fun pushToTalkDisablesServerVad() {
        val setup = JSONObject(LiveProtocol.setup(config(TurnMode.PUSH_TO_TALK))).getJSONObject("setup")
        assertEquals("models/gemini-3.8-live", setup.getString("model"))
        val vad = setup.getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection")
        assertTrue(vad.getBoolean("disabled"))
        assertFalse(vad.has("silenceDurationMs"))
        assertEquals("AUDIO", setup.getJSONObject("generationConfig").getJSONArray("responseModalities").getString(0))
        assertEquals("SYS", setup.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test
    fun autoModeUsesLowSensitivityAndSilence() {
        val setup = JSONObject(LiveProtocol.setup(config(TurnMode.AUTO))).getJSONObject("setup")
        val vad = setup.getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection")
        assertFalse(vad.getBoolean("disabled"))
        assertEquals("END_SENSITIVITY_LOW", vad.getString("endOfSpeechSensitivity"))
        assertEquals(2000, vad.getInt("silenceDurationMs"))
    }

    @Test
    fun transcriptionVocabularyIsDedupedAndMinimalDropsIt() {
        val full = JSONObject(LiveProtocol.setup(config(TurnMode.AUTO))).getJSONObject("setup")
        val vocab = full.getJSONObject("inputAudioTranscription").getJSONArray("customVocabulary")
        assertEquals(2, vocab.length())
        val minimal = JSONObject(LiveProtocol.setup(config(TurnMode.AUTO, minimal = true))).getJSONObject("setup")
        assertEquals(0, minimal.getJSONObject("inputAudioTranscription").length())
        assertTrue(minimal.has("contextWindowCompression"))
    }

    @Test
    fun resumeHandleGoesInSetup() {
        val setup = JSONObject(LiveProtocol.setup(config(TurnMode.AUTO, handle = "abc"))).getJSONObject("setup")
        assertEquals("abc", setup.getJSONObject("sessionResumption").getString("handle"))
        val fresh = JSONObject(LiveProtocol.setup(config(TurnMode.AUTO))).getJSONObject("setup")
        assertFalse(fresh.getJSONObject("sessionResumption").has("handle"))
    }

    @Test
    fun audioAndContextMessages() {
        val pcm = byteArrayOf(1, 2, 3, 4, 5)
        val audio = JSONObject(LiveProtocol.audio(pcm, 4)).getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), Base64.getDecoder().decode(audio.getString("data")))
        val ctx = JSONObject(LiveProtocol.context("hola", respond = false)).getJSONObject("clientContent")
        assertFalse(ctx.getBoolean("turnComplete"))
        assertEquals("user", ctx.getJSONArray("turns").getJSONObject(0).getString("role"))
    }

    @Test
    fun parsesServerContent() {
        val data = Base64.getEncoder().encodeToString(byteArrayOf(9, 8, 7))
        val raw = """
            {"serverContent": {"modelTurn": {"parts": [{"inlineData": {"mimeType": "audio/pcm;rate=24000", "data": "$data"}}, {"text": "pensando"}]},
              "outputTranscription": {"text": "안녕하세요"}, "turnComplete": true},
             "usageMetadata": {"promptTokenCount": 120, "responseTokenCount": 30, "totalTokenCount": 150}}
        """.trimIndent()
        val events = LiveProtocol.parse(raw)
        val audio = events.filterIsInstance<LiveEvent.Audio>().single()
        assertArrayEquals(byteArrayOf(9, 8, 7), audio.pcm)
        assertTrue(LiveEvent.OutputText("안녕하세요") in events)
        assertTrue(LiveEvent.TurnComplete in events)
        assertTrue(LiveEvent.Usage(120, 30, 150) in events)
    }

    @Test
    fun parsesControlMessages() {
        assertEquals(listOf(LiveEvent.SetupComplete), LiveProtocol.parse("""{"setupComplete": {}}"""))
        assertEquals(listOf(LiveEvent.ResumeHandle("h1")), LiveProtocol.parse("""{"sessionResumptionUpdate": {"newHandle": "h1", "resumable": true}}"""))
        assertTrue(LiveProtocol.parse("""{"sessionResumptionUpdate": {"newHandle": "", "resumable": false}}""").isEmpty())
        assertEquals(listOf(LiveEvent.GoAway(12_500)), LiveProtocol.parse("""{"goAway": {"timeLeft": "12.5s"}}"""))
        assertEquals(listOf(LiveEvent.InputText("저는"), LiveEvent.Interrupted), LiveProtocol.parse("""{"serverContent": {"inputTranscription": {"text": "저는"}, "interrupted": true}}"""))
        assertTrue(LiveProtocol.parse("no es json").isEmpty())
    }

    @Test
    fun highlightToolDeclaredOnlyWhenAskedAndNotMinimal() {
        val withTool = JSONObject(LiveProtocol.setup(config(TurnMode.AUTO).copy(highlightTool = true))).getJSONObject("setup")
        val decl = withTool.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0)
        assertEquals(LiveProtocol.TOOL_HIGHLIGHT, decl.getString("name"))
        assertEquals("NON_BLOCKING", decl.getString("behavior"))
        assertFalse(JSONObject(LiveProtocol.setup(config(TurnMode.AUTO))).getJSONObject("setup").has("tools"))
        assertFalse(JSONObject(LiveProtocol.setup(config(TurnMode.AUTO, minimal = true).copy(highlightTool = true))).getJSONObject("setup").has("tools"))
    }

    @Test
    fun parsesToolCallAndBuildsSilentResponse() {
        val events = LiveProtocol.parse("""{"toolCall": {"functionCalls": [{"id": "c1", "name": "highlight_target", "args": {"ref": "w12"}}]}}""")
        assertEquals(listOf(LiveEvent.ToolCall("c1", "highlight_target", mapOf("ref" to "w12"))), events)
        val r = JSONObject(LiveProtocol.toolResponse("c1", "highlight_target")).getJSONObject("toolResponse")
            .getJSONArray("functionResponses").getJSONObject(0)
        assertEquals("c1", r.getString("id"))
        assertEquals("SILENT", r.getJSONObject("response").getString("scheduling"))
    }
}
