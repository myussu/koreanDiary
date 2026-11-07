package com.jorge.koreandiary.conversation

import android.content.Context
import com.jorge.koreandiary.ai.PromptItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Una sesión de conversación guardada. Todo queda registrado (lo que usaste, lo que tocaste para
 * traducir, las pistas, la revisión) para decidir más adelante, con datos, cómo debería afectar la
 * lista de estudio. Por ahora la conversación solo LEE la lista: no cambia FSRS ni agrega palabras.
 */
data class ConversationRecord(
    val id: Long,
    val startedAt: Long,
    val durationSec: Int = 0,
    val model: String = "",
    val turnMode: String = "",
    val topic: String = "",
    val lines: List<Line> = emptyList(),
    /** Objetivos de repaso que se le pasaron al modelo de voz. */
    val targets: List<PromptItem> = emptyList(),
    /** Objetivos que la IA resaltó (flotaron) durante la sesión, en orden. */
    val highlighted: List<String> = emptyList(),
    /** Lo que preguntaste con "¿Cómo digo?": "idea → frase". */
    val askedHowTo: List<String> = emptyList(),
    /** Palabras que tocaste para traducir: "forma: significado". */
    val lookups: List<String> = emptyList(),
    val hintsUsed: Int = 0,
    /** Cuántas de esas pistas aparecieron solas (pista automática). */
    val autoHints: Int = 0,
    /** Suma de totalTokenCount de cada mensaje de uso (aprox. lo que cuenta para la cuota). */
    val tokensTotal: Long = 0,
    /** Tamaño del contexto en el último turno. */
    val lastPromptTokens: Int = 0,
    val reconnections: Int = 0,
    /** Avisos técnicos de la sesión (p. ej. planes B de configuración), para diagnosticar. */
    val notes: List<String> = emptyList(),
    /** Por qué terminó: tú, el tiempo o un error (con el código de cierre). */
    val endReason: String = "",
    /** Respuesta cruda de la revisión final; se vuelve a leer con ConversationParser. */
    val reviewRaw: String? = null,
    /** Práctica en voz alta de las correcciones: cuántas dijiste bien de cuántas. */
    val drillPassed: Int = 0,
    val drillTotal: Int = 0,
) {
    val userLines get() = lines.count { it.speaker == Speaker.USER }
}

data class ConversationStats(val secondsToday: Int, val secondsTotal: Int, val sessions: Int, val days: Int)

object ConversationMath {
    /** Segundos hablados por día local. */
    fun secondsPerDay(records: List<ConversationRecord>, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, Int> =
        records.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
            .mapValues { (_, list) -> list.sumOf { it.durationSec } }

    fun stats(records: List<ConversationRecord>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): ConversationStats {
        val perDay = secondsPerDay(records, zone)
        return ConversationStats(
            secondsToday = perDay[today] ?: 0,
            secondsTotal = perDay.values.sum(),
            sessions = records.size,
            days = perDay.size,
        )
    }

    /**
     * Marca aproximada en vivo: ¿apareció la raíz de la palabra en lo que dijiste? (먹다 → 먹).
     * Raíces de una sola sílaba (가다 → 가) darían falsos positivos, así que no se marcan.
     * La revisión final es la que dice de verdad qué usaste.
     */
    fun wordSeen(label: String, userText: String): Boolean {
        val form = label.trim()
        val stem = when {
            // 등산하다 → 등산: "하" se contrae al conjugar (했어요, 해요).
            form.length > 2 && form.endsWith("하다") -> form.dropLast(2)
            form.length > 1 && form.endsWith("다") -> form.dropLast(1)
            else -> form
        }
        return stem.length >= 2 && userText.contains(stem)
    }

    /** "12 min" o "45 s". */
    fun format(seconds: Int): String = if (seconds >= 60) "${(seconds + 30) / 60} min" else "$seconds s"
}

/** Sesiones en archivos (filesDir/conversation/<id>.json), como el workbook: sin migrar la base. */
class ConversationStore(context: Context) {
    private val root = File(context.filesDir, "conversation")

    private val _sessions = MutableStateFlow<List<ConversationRecord>>(emptyList())
    val sessions: StateFlow<List<ConversationRecord>> = _sessions.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _sessions.value = root.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { decode(JSONObject(it.readText())) }.getOrNull() }
            ?.sortedByDescending { it.startedAt } ?: emptyList()
    }

    suspend fun load(id: Long): ConversationRecord? = withContext(Dispatchers.IO) {
        file(id).takeIf { it.exists() }?.let { runCatching { decode(JSONObject(it.readText())) }.getOrNull() }
    }

    suspend fun save(record: ConversationRecord) = withContext(Dispatchers.IO) {
        root.mkdirs()
        file(record.id).writeText(encode(record).toString())
        refresh()
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        file(id).delete()
        refresh()
    }

    private fun file(id: Long) = File(root, "$id.json")

    companion object {
        fun encode(r: ConversationRecord): JSONObject = JSONObject()
            .put("id", r.id)
            .put("startedAt", r.startedAt)
            .put("durationSec", r.durationSec)
            .put("model", r.model)
            .put("turnMode", r.turnMode)
            .put("topic", r.topic)
            .put("lines", JSONArray().apply {
                r.lines.forEach { put(JSONObject().put("s", if (it.speaker == Speaker.USER) "yo" else "ia").put("t", it.text)) }
            })
            .put("targets", JSONArray().apply {
                r.targets.forEach { put(JSONObject().put("ref", it.ref).put("label", it.label).put("kind", it.kind)) }
            })
            .put("lookups", JSONArray(r.lookups))
            .put("askedHowTo", JSONArray(r.askedHowTo))
            .put("highlighted", JSONArray(r.highlighted))
            .put("notes", JSONArray(r.notes))
            .put("hintsUsed", r.hintsUsed)
            .put("autoHints", r.autoHints)
            .put("drillPassed", r.drillPassed)
            .put("drillTotal", r.drillTotal)
            .put("tokensTotal", r.tokensTotal)
            .put("lastPromptTokens", r.lastPromptTokens)
            .put("reconnections", r.reconnections)
            .put("endReason", r.endReason)
            .put("reviewRaw", r.reviewRaw ?: "")

        fun decode(o: JSONObject): ConversationRecord {
            val lines = o.optJSONArray("lines")
            val targets = o.optJSONArray("targets")
            val lookups = o.optJSONArray("lookups")
            val asked = o.optJSONArray("askedHowTo")
            val highlighted = o.optJSONArray("highlighted")
            val notes = o.optJSONArray("notes")
            return ConversationRecord(
                id = o.getLong("id"),
                startedAt = o.optLong("startedAt", o.getLong("id")),
                durationSec = o.optInt("durationSec"),
                model = o.optString("model"),
                turnMode = o.optString("turnMode"),
                topic = o.optString("topic"),
                lines = (0 until (lines?.length() ?: 0)).mapNotNull { i ->
                    lines!!.optJSONObject(i)?.let { Line(if (it.optString("s") == "yo") Speaker.USER else Speaker.AI, it.optString("t")) }
                },
                targets = (0 until (targets?.length() ?: 0)).mapNotNull { i ->
                    targets!!.optJSONObject(i)?.let { PromptItem(it.optString("ref"), it.optString("label"), it.optString("kind")) }
                },
                lookups = (0 until (lookups?.length() ?: 0)).map { lookups!!.optString(it) },
                askedHowTo = (0 until (asked?.length() ?: 0)).map { asked!!.optString(it) },
                highlighted = (0 until (highlighted?.length() ?: 0)).map { highlighted!!.optString(it) },
                notes = (0 until (notes?.length() ?: 0)).map { notes!!.optString(it) },
                hintsUsed = o.optInt("hintsUsed"),
                autoHints = o.optInt("autoHints"),
                drillPassed = o.optInt("drillPassed"),
                drillTotal = o.optInt("drillTotal"),
                tokensTotal = o.optLong("tokensTotal"),
                lastPromptTokens = o.optInt("lastPromptTokens"),
                reconnections = o.optInt("reconnections"),
                endReason = o.optString("endReason"),
                reviewRaw = o.optString("reviewRaw").ifBlank { null },
            )
        }
    }
}
