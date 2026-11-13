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
) {
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
        .toString()

    companion object {
        /** Ideas que se guardan por escena; las más viejas se descartan. */
        const val MAX_IDEAS = 30

        /** Tolerante: JSON vacío, nulo o roto da una escena vacía en vez de fallar. */
        fun parse(json: String?): SceneData {
            if (json.isNullOrBlank()) return SceneData()
            return try {
                val o = JSONObject(json)
                val ng = o.optJSONArray("ng")
                val ideas = o.optJSONArray("ideas")
                SceneData(
                    newGrammar = if (ng == null) emptyList() else (0 until ng.length()).map { ng.optInt(it, -1) }.filter { it >= 0 },
                    ideas = if (ideas == null) emptyList() else (0 until ideas.length()).mapNotNull { i ->
                        val idea = ideas.optJSONObject(i) ?: return@mapNotNull null
                        val text = idea.optString("t").trim()
                        if (text.isEmpty()) null else SceneIdea(text, idea.optInt("p", 1).coerceIn(1, 3))
                    },
                )
            } catch (e: org.json.JSONException) {
                SceneData()
            }
        }
    }
}
