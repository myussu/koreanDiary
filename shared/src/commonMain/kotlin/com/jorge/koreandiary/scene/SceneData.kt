package com.jorge.koreandiary.scene

import org.json.JSONArray
import org.json.JSONObject

/** Una idea dicha (o escrita) en «¿Cómo digo?» y hasta qué pista llegaste. */
data class SceneIdea(
    val text: String,
    /** 1 = palabras, 2 = cómo unirlas, 3 = la frase completa. */
    val hints: Int,
)

/**
 * Lo propio de una escena, guardado en `pages.sceneJson`. Es JSON (no columnas) para sumar cosas
 * sin migrar la base cada vez. Las páginas del diario lo dejan en null.
 */
data class SceneData(
    /** Ids del catálogo estándar de las gramáticas nuevas de esta escena (van aparte de las sugerencias). */
    val newGrammar: List<Int> = emptyList(),
    /** Lo que quisiste decir: lo usa la revisión para avisar si lo escrito dice otra cosa. */
    val ideas: List<SceneIdea> = emptyList(),
    /**
     * Elementos de la escena viva que vio la IA (ids de [SceneDictionary]), en orden: completan lo
     * que el diccionario no reconoce. Los del diccionario no se guardan: salen de la tinta.
     */
    val elements: List<String> = emptyList(),
    /**
     * El tono de la escena según la IA (al revisar o en «¿Cómo digo?»): la luz de los bordes. Se queda
     * el último leído; una lectura sin tono no lo borra.
     */
    val mood: MoodReading? = null,
    /** Cómo se ve la escena, en inglés, para pintarla (lo da la IA al revisar). */
    val imagePrompt: String = "",
) {
    fun withImagePrompt(prompt: String?): SceneData =
        if (prompt.isNullOrBlank() || prompt.trim() == imagePrompt) this else copy(imagePrompt = prompt.trim())

    fun withMood(reading: MoodReading?): SceneData =
        if (reading == null || reading == mood) this else copy(mood = reading)

    /** Suma elementos sin repetir, manteniendo el orden en que aparecieron. */
    fun withElements(ids: List<String>): SceneData {
        val merged = (elements + ids.filter { SceneDictionary.element(it) != null }).distinct().takeLast(MAX_ELEMENTS)
        return if (merged == elements) this else copy(elements = merged)
    }

    fun withIdea(idea: SceneIdea): SceneData {
        // La misma idea preguntada otra vez: se queda la pista más alta que viste.
        val existing = ideas.indexOfFirst { it.text.equals(idea.text, ignoreCase = true) }
        if (existing < 0) return copy(ideas = (ideas + idea).takeLast(MAX_IDEAS))
        val merged = ideas[existing].copy(hints = maxOf(ideas[existing].hints, idea.hints))
        return copy(ideas = ideas.toMutableList().also { it[existing] = merged })
    }

    fun toJson(): String = JSONObject()
        .put("v", 1)
        .put("ng", JSONArray(newGrammar))
        .put("ideas", JSONArray().apply { ideas.forEach { put(JSONObject().put("t", it.text).put("p", it.hints)) } })
        .put("els", JSONArray(elements))
        .apply { mood?.let { put("tono", JSONObject().put("id", it.id).put("n", it.level)) } }
        .apply { if (imagePrompt.isNotEmpty()) put("img", imagePrompt) }
        .toString()

    companion object {
        /** Ideas que se guardan por escena; las más viejas se descartan. */
        const val MAX_IDEAS = 30
        /** Elementos que puede tener una escena viva: más ya no se distinguen en los bordes. */
        const val MAX_ELEMENTS = 12

        /** Tolerante: JSON vacío, nulo o roto da una escena vacía en vez de fallar. */
        fun parse(json: String?): SceneData {
            if (json.isNullOrBlank()) return SceneData()
            return try {
                val o = JSONObject(json)
                val ng = o.optJSONArray("ng")
                val ideas = o.optJSONArray("ideas")
                val els = o.optJSONArray("els")
                val tono = o.optJSONObject("tono")
                SceneData(
                    newGrammar = if (ng == null) emptyList() else (0 until ng.length()).map { ng.optInt(it, -1) }.filter { it >= 0 },
                    ideas = if (ideas == null) emptyList() else (0 until ideas.length()).mapNotNull { i ->
                        val idea = ideas.optJSONObject(i) ?: return@mapNotNull null
                        val text = idea.optString("t").trim()
                        if (text.isEmpty()) null else SceneIdea(text, idea.optInt("p", 1).coerceIn(1, 3))
                    },
                    elements = if (els == null) emptyList() else (0 until els.length()).map { els.optString(it) }
                        .filter { SceneDictionary.element(it) != null }.distinct(),
                    mood = tono?.let { SceneMoods.reading(it.optString("id"), it.optInt("n", 2)) },
                    imagePrompt = o.optString("img").trim(),
                )
            } catch (e: org.json.JSONException) {
                SceneData()
            }
        }
    }
}
