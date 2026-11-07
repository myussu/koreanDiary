package com.jorge.koreandiary.data

import android.content.Context
import com.jorge.koreandiary.ai.ExplainParser
import com.jorge.koreandiary.ai.ItemExplanation
import com.jorge.koreandiary.data.standard.GrammarKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Explicaciones de sugerencias ya pedidas a la IA, guardadas para no volver a gastar tokens.
 * Un archivo JSON en `filesDir/explanations/` (entra en la copia de seguridad), no en Room:
 * así no hace falta una migración de la base.
 */
class ExplanationStore(context: Context) {
    private val file = File(context.filesDir, "explanations/cache.json")
    private val mutex = Mutex()
    private var cache: MutableMap<String, ItemExplanation>? = null

    suspend fun get(key: String): ItemExplanation? = mutex.withLock { load()[key] }

    suspend fun putAll(items: Map<String, ItemExplanation>) {
        if (items.isEmpty()) return
        mutex.withLock {
            val all = load()
            all.putAll(items)
            withContext(Dispatchers.IO) {
                val json = JSONObject()
                all.forEach { (k, v) -> json.put(k, ExplainParser.toJson(v)) }
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "cache.json.tmp")
                tmp.writeText(json.toString())
                tmp.renameTo(file)
            }
        }
    }

    private suspend fun load(): MutableMap<String, ItemExplanation> {
        cache?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            val map = mutableMapOf<String, ItemExplanation>()
            runCatching {
                if (file.exists()) {
                    val json = JSONObject(file.readText())
                    json.keys().forEach { k -> json.optJSONObject(k)?.let(ExplainParser::fromJson)?.let { map[k] = it } }
                }
            }
            map
        }
        cache = loaded
        return loaded
    }

    companion object {
        /** "g:-느라고" o "w:미용실". */
        fun key(form: String, isGrammar: Boolean) = (if (isGrammar) "g:" else "w:") + form.trim()

        /**
         * Empareja lo que devolvió la IA con lo pedido: por orden si coincide el número,
         * si no por forma (en gramática, ignorando la notación: "V-느라고" = "-느라고").
         */
        fun match(requested: List<Pair<String, Boolean>>, answered: List<ItemExplanation>): Map<String, ItemExplanation> {
            val out = mutableMapOf<String, ItemExplanation>()
            requested.forEachIndexed { i, (form, isGrammar) ->
                val byOrder = answered.getOrNull(i)?.takeIf { answered.size == requested.size && same(it.form, form, isGrammar) }
                val found = byOrder ?: answered.firstOrNull { same(it.form, form, isGrammar) }
                if (found != null) out[key(form, isGrammar)] = found
            }
            return out
        }

        private fun same(a: String, b: String, isGrammar: Boolean): Boolean =
            a.trim() == b.trim() || (isGrammar && GrammarKeys.matches(listOf(a), listOf(b)))
    }
}
