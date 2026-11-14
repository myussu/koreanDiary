package com.jorge.koreandiary

import com.jorge.koreandiary.ai.CorrectionParser
import com.jorge.koreandiary.ai.Prompts
import com.jorge.koreandiary.scene.SceneData
import com.jorge.koreandiary.scene.SceneImage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** La imagen de la escena: prompts, el flujo de ComfyUI y la lectura de sus respuestas. */
class SceneImageTest {

    @Test
    fun serverAddressIsNormalized() {
        assertEquals("http://truenas:8188", SceneImage.normalizeServer(" truenas:8188/ "))
        assertEquals("http://100.101.1.2:8188", SceneImage.normalizeServer("http://100.101.1.2:8188/"))
        assertEquals("https://nas.tail1234.ts.net", SceneImage.normalizeServer("https://nas.tail1234.ts.net"))
        assertNull(SceneImage.normalizeServer(""))
        assertNull(SceneImage.normalizeServer("http://"))
        assertNull(SceneImage.normalizeServer("mi servidor"))
    }

    @Test
    fun workflowIsComfyApiFormat() {
        val wf = SceneImage.workflow("juggernautXL.safetensors", "pos", "neg", seed = 42)
        assertEquals("CheckpointLoaderSimple", wf.getJSONObject("1").getString("class_type"))
        assertEquals("juggernautXL.safetensors", wf.getJSONObject("1").getJSONObject("inputs").getString("ckpt_name"))
        val sampler = wf.getJSONObject("5").getJSONObject("inputs")
        assertEquals(42L, sampler.getLong("seed"))
        // Los enlaces son [id del nodo, salida].
        assertEquals("1", sampler.getJSONArray("model").getString(0))
        assertEquals(0, sampler.getJSONArray("model").getInt(1))
        assertEquals("2", sampler.getJSONArray("positive").getString(0))
        val latent = wf.getJSONObject("4").getJSONObject("inputs")
        assertEquals(SceneImage.WIDTH, latent.getInt("width"))
        assertTrue(SceneImage.HEIGHT > SceneImage.WIDTH)
        assertEquals("SaveImage", wf.getJSONObject("7").getString("class_type"))
        val body = JSONObject(SceneImage.promptBody(wf, "app"))
        assertEquals("app", body.getString("client_id"))
        assertEquals("KSampler", body.getJSONObject("prompt").getJSONObject("5").getString("class_type"))
    }

    @Test
    fun readsComfyResponses() {
        assertEquals("abc", SceneImage.parsePromptResponse("""{"prompt_id": "abc", "number": 3, "node_errors": {}}""").getOrNull())
        val bad = SceneImage.parsePromptResponse("""{"error": {"type": "prompt_outputs_failed_validation", "message": "Prompt outputs failed validation"}, "node_errors": {}}""")
        assertEquals("Prompt outputs failed validation", bad.exceptionOrNull()?.message)
        assertTrue(SceneImage.parsePromptResponse("<html>").isFailure)

        // Mientras pinta, la historia viene vacía.
        assertNull(SceneImage.parseHistory("{}", "abc"))
        val done = """{"abc": {"status": {"status_str": "success", "completed": true},
            "outputs": {"7": {"images": [{"filename": "koreandiary-escena_00001_.png", "subfolder": "", "type": "output"}]}}}}"""
        val out = SceneImage.parseHistory(done, "abc")!!
        assertEquals("koreandiary-escena_00001_.png", out.filename)
        assertEquals("filename=koreandiary-escena_00001_.png&subfolder=&type=output", out.viewQuery())
        val failed = """{"abc": {"status": {"status_str": "error", "completed": false}, "outputs": {}}}"""
        assertTrue(runCatching { SceneImage.parseHistory(failed, "abc") }.isFailure)
    }

    @Test
    fun readsInstalledCheckpointsInBothFormats() {
        val old = """{"CheckpointLoaderSimple": {"input": {"required": {"ckpt_name": [["a.safetensors", "b.ckpt"], {}]}}}}"""
        assertEquals(listOf("a.safetensors", "b.ckpt"), SceneImage.parseCheckpoints(old))
        val combo = """{"CheckpointLoaderSimple": {"input": {"required": {"ckpt_name": ["COMBO", {"options": ["c.safetensors"]}]}}}}"""
        assertEquals(listOf("c.safetensors"), SceneImage.parseCheckpoints(combo))
        assertTrue(SceneImage.parseCheckpoints("{}").isEmpty())
        assertTrue(SceneImage.parseCheckpoints("no es json").isEmpty())
    }

    @Test
    fun promptsCarryTheStyleAndTheScene() {
        val local = SceneImage.localPrompt("A beach at sunset, two people holding hands under a tree.")
        assertTrue(local.startsWith("soft watercolor"))
        assertTrue(local.endsWith("holding hands under a tree"))
        assertTrue(SceneImage.negativePrompt().contains("text"))
        val chat = SceneImage.chatPrompt(null, "바다를 보고 있어요\n여보의 손을 잡아요")
        assertTrue(chat.contains("9:16") && chat.contains("acuarela"))
        assertTrue(chat.contains("«바다를 보고 있어요 여보의 손을 잡아요»"))
        assertTrue(SceneImage.chatPrompt("A quiet sea.", "").endsWith("A quiet sea."))
    }

    @Test
    fun reviewBringsTheImageDescription() {
        val r = CorrectionParser.parse(
            """{"oraciones": [{"n": 1, "texto": "바다를 봐요.", "tiempo": "presente"}], "imagen": "  A calm sea at dusk.  "}"""
        )
        assertEquals("A calm sea at dusk.", r.sceneImagePrompt)
        val d = SceneData().withImagePrompt(r.sceneImagePrompt)
        assertEquals(d, SceneData.parse(d.toJson()))
        // Una revisión sin descripción no borra la que había.
        assertEquals(d, d.withImagePrompt(""))
        val withIdeas = Prompts.correction("", "3A", 3, emptyList(), "가요.", hasImage = false, scene = true, ideas = listOf("x"))
        assertTrue(withIdeas.lines().any { it.startsWith("8. \"imagen\"") })
        assertTrue(Prompts.correction("", "3A", 3, emptyList(), "가요.", hasImage = false, scene = true).lines().any { it.startsWith("7. \"imagen\"") })
        assertFalse(Prompts.correction("", "3A", 3, emptyList(), "가요.", hasImage = false).contains("\"imagen\""))
    }
}
