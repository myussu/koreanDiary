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
 * Lo que vio la IA en «¿Cómo digo?» (elementos y tono) y todavía no escribiste. Aparece en la escena
 * cuando la tinta tiene alguna de sus palabras ([words], las de la frase en coreano): la escena viva
 * reacciona a lo escrito, no a lo que pensabas escribir.
 */
data class PendingScene(val elements: List<String>, val mood: MoodReading?, val words: List<String>) {
    /** Cada palabra a buscar en lo escrito: los verbos y adjetivos sin el -다. Solo hangul. */
    val keys: List<String>
        get() = words.map { w ->
            val f = w.trim()
            if (f.length >= 2 && f.endsWith("다")) f.dropLast(1) else f
        }.filter { k -> k.isNotEmpty() && k.all { it in '\uAC00'..'\uD7A3' } }.distinct()

    /** Ya está escrita: alguna de sus palabras aparece en [text] (lo que leyó ML Kit). */
    fun writtenIn(text: String): Boolean {
        val compact = text.replace(" ", "")
        return keys.any { it in compact }
    }

    companion object {
        /**
         * Las palabras de una respuesta de «¿Cómo digo?»: las de diccionario que dio la IA y, si no
         * dio ninguna, el comienzo de cada palabra de la frase (sin la partícula).
         */
        fun wordsFrom(dictionaryForms: List<String>, sentence: String): List<String> {
            val forms = dictionaryForms.map { it.trim() }.filter { it.isNotEmpty() }
            if (forms.isNotEmpty()) return forms
            return sentence.split(' ', '.', ',', '?', '!').map { it.trim() }.filter { it.isNotEmpty() }
                .map { if (it.length > 2) it.take(2) else it }
        }
    }
}

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
    /** Lo de «¿Cómo digo?» que espera a que lo escribas. */
    val pending: List<PendingScene> = emptyList(),
) {
    /** Guarda lo que vio la IA en una idea para cuando la escribas (nada si no vio nada). */
    fun withPending(p: PendingScene): SceneData =
        if (p.elements.isEmpty() && p.mood == null) this else copy(pending = (pending + p).takeLast(MAX_PENDING))

    /** Lo pendiente que ya está escrito en [text] pasa a la escena: sus elementos y su tono. */
    fun revealWritten(text: String): SceneData {
        if (pending.isEmpty() || text.isBlank()) return this
        val (written, waiting) = pending.partition { it.writtenIn(text) }
        if (written.isEmpty()) return this
        var d = copy(pending = waiting)
        written.forEach { d = d.withElements(it.elements).withMood(it.mood) }
        return d
    }

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
        .apply {
            if (pending.isNotEmpty()) {
                put("pend", JSONArray().apply {
                    pending.forEach { p ->
                        put(JSONObject().put("els", JSONArray(p.elements)).put("w", JSONArray(p.words)).apply {
                            p.mood?.let { put("tono", JSONObject().put("id", it.id).put("n", it.level)) }
                        })
                    }
                })
            }
        }
        .toString()

    companion object {
        /** Ideas que se guardan por escena; las más viejas se descartan. */
        const val MAX_IDEAS = 30
        /** Elementos que puede tener una escena viva: más ya no se distinguen en los bordes. */
        const val MAX_ELEMENTS = 12
        /** Ideas de «¿Cómo digo?» que esperan a ser escritas; las más viejas se olvidan. */
        const val MAX_PENDING = 8

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
                    pending = o.optJSONArray("pend").let { arr ->
                        if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
                            val p = arr.optJSONObject(i) ?: return@mapNotNull null
                            val e = p.optJSONArray("els")
                            val w = p.optJSONArray("w")
                            val t = p.optJSONObject("tono")
                            PendingScene(
                                elements = if (e == null) emptyList() else (0 until e.length()).map { e.optString(it) }.filter { SceneDictionary.element(it) != null },
                                mood = t?.let { SceneMoods.reading(it.optString("id"), it.optInt("n", 2)) },
                                words = if (w == null) emptyList() else (0 until w.length()).map { w.optString(it) }.filter { it.isNotBlank() },
                            )
                        }
                    },
                )
            } catch (e: org.json.JSONException) {
                SceneData()
            }
        }
    }
}
