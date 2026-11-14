package com.jorge.koreandiary.scene

import android.app.Application
import android.util.Log
import com.jorge.koreandiary.ai.ClaudeClient
import com.jorge.koreandiary.ai.ClaudeCutOffException
import com.jorge.koreandiary.ai.ClaudeProtocol
import com.jorge.koreandiary.ai.HttpProviderException
import com.jorge.koreandiary.scene.art.CustomDrawing
import com.jorge.koreandiary.scene.art.DrawingFormat
import com.jorge.koreandiary.scene.art.NewSceneThing
import com.jorge.koreandiary.settings.SecretStore
import com.jorge.koreandiary.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Un dibujo nuevo guardado: el dibujo y de dónde salió (qué modelo, cuánto gastó, en qué frase apareció). */
data class SavedDrawing(
    val drawing: CustomDrawing,
    val createdAt: Long,
    val model: String,
    val tokensIn: Int,
    val tokensOut: Int,
    val context: String,
)

/** Qué pasa con algo nuevo mientras Claude lo dibuja. */
sealed interface DrawJob {
    data object Drawing : DrawJob
    data class Failed(val message: String) : DrawJob
}

/** Algo que espera su dibujo: falta la clave, se acabó el límite del día o falló. */
data class PendingThing(val thing: NewSceneThing, val context: String)

/**
 * Los dibujos nuevos de la escena: lo que apareció al revisar y la app no sabía pintar, dibujado por
 * Claude en dos vueltas (dibuja; mira la imagen de su dibujo y lo corrige). Un archivo por dibujo en `filesDir/scene_drawings/<id>.json` (el mismo JSON que devolvió
 * Claude, más `meta`) y lo que espera en `pending.json`. Vive en el `AppContainer`: dibujar tarda unos
 * segundos y sigue aunque salgas de la página. La carpeta entra en la copia de seguridad.
 */
class SceneDrawings(
    app: Application,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val secrets: SecretStore,
) {
    private val dir = File(app.filesDir, DIR)
    private val pendingFile = File(dir, PENDING)
    private val lock = Mutex()

    private val _saved = MutableStateFlow<List<SavedDrawing>>(emptyList())
    val saved: StateFlow<List<SavedDrawing>> = _saved.asStateFlow()

    private val _pending = MutableStateFlow<List<PendingThing>>(emptyList())
    val pending: StateFlow<List<PendingThing>> = _pending.asStateFlow()

    /** Por sustantivo coreano (o id, al redibujar). */
    private val _jobs = MutableStateFlow<Map<String, DrawJob>>(emptyMap())
    val jobs: StateFlow<Map<String, DrawJob>> = _jobs.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { load() }
    }

    /** Los dibujos, sin lo demás (para la escena y el diccionario). */
    fun drawings(): List<CustomDrawing> = _saved.value.map { it.drawing }

    /** Hay clave de Claude (no la descifra). */
    fun ready(): Boolean = secrets.has(SecretStore.CLAUDE_KEY)

    fun usedToday(): Int = settings.usageToday(USAGE)

    private fun load() {
        val list = dir.listFiles { f -> f.name.endsWith(".json") && f.name != PENDING }.orEmpty().mapNotNull { f ->
            runCatching {
                val json = JSONObject(f.readText())
                val meta = json.optJSONObject("meta") ?: JSONObject()
                SavedDrawing(
                    DrawingFormat.parse(json),
                    meta.optLong("at"),
                    meta.optString("model"),
                    meta.optInt("in"),
                    meta.optInt("out"),
                    meta.optString("ctx"),
                )
            }.getOrNull()
        }.sortedBy { it.createdAt }
        _saved.value = list
        _pending.value = runCatching {
            val arr = JSONArray(pendingFile.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                PendingThing(NewSceneThing(o.optString("ko"), o.optString("es")), o.optString("ctx"))
            }.filter { it.thing.ko.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    /** ¿Ya hay dibujo (de la app o nuevo) para esta palabra? */
    private fun known(ko: String): Boolean = SceneDictionary.withCustom(drawings()).detect(ko).isNotEmpty()

    /**
     * Tras revisar una escena: lo que se ve y no tiene dibujo. Con la opción encendida y la clave
     * puesta, Claude lo dibuja; si no, queda en espera en la ventana de dibujos.
     */
    fun offer(things: List<NewSceneThing>, context: String) {
        val fresh = things.filter { !known(it.ko) && it.ko !in _jobs.value }
        if (fresh.isEmpty()) return
        val s = settings.state.value
        if (s.sceneDrawAuto && ready()) fresh.forEach { draw(it, context) } else addPending(fresh.map { PendingThing(it, context) })
    }

    /** Dibuja [thing] (desde la revisión o con «Dibujar» en la ventana). */
    fun draw(thing: NewSceneThing, context: String = "") {
        if (_jobs.value[thing.ko] == DrawJob.Drawing) return
        setJob(thing.ko, DrawJob.Drawing)
        scope.launch {
            val result = runCatching { create(thing, context, replace = null) }
            result.onSuccess {
                setJob(thing.ko, null)
                removePending(thing.ko)
            }.onFailure { e ->
                if (e is CancellationException) throw e
                setJob(thing.ko, DrawJob.Failed(message(e)))
                addPending(listOf(PendingThing(thing, context)))
            }
        }
    }

    /** «Dibujar otra vez»: mismo id y mismas palabras, dibujo nuevo. */
    fun redraw(id: String) {
        val old = _saved.value.firstOrNull { it.drawing.id == id } ?: return
        if (_jobs.value[id] == DrawJob.Drawing) return
        setJob(id, DrawJob.Drawing)
        scope.launch {
            runCatching {
                create(NewSceneThing(old.drawing.keys.firstOrNull() ?: old.drawing.name, old.drawing.name), old.context, replace = old)
            }.onSuccess { setJob(id, null) }.onFailure { e ->
                if (e is CancellationException) throw e
                setJob(id, DrawJob.Failed(message(e)))
            }
        }
    }

    fun delete(id: String) {
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                File(dir, "$id.json").delete()
                _saved.update { list -> list.filterNot { it.drawing.id == id } }
            }
        }
        setJob(id, null)
    }

    /** Quitar de la espera sin dibujar. */
    fun dismiss(ko: String) {
        removePending(ko)
        setJob(ko, null)
    }

    /** «Probar conexión». */
    suspend fun test(): String {
        val client = client() ?: return "Falta la clave de Claude."
        return "Conexión correcta (${client.ping()})."
    }

    private fun model(): String = settings.state.value.claudeModel.ifBlank { ClaudeClient.DEFAULT_MODEL }

    private fun client(): ClaudeClient? {
        val key = secrets.get(SecretStore.CLAUDE_KEY) ?: return null
        return ClaudeClient(key, model())
    }

    private suspend fun create(thing: NewSceneThing, context: String, replace: SavedDrawing?) {
        val client = client() ?: throw IllegalStateException("Falta la clave de Claude: ponla en Ajustes → «Dibujos nuevos (Claude)».")
        if (usedToday() >= DAILY_LIMIT) throw IllegalStateException("Ya se hicieron $DAILY_LIMIT dibujos hoy (el límite cuida tus créditos). Mañana sigue.")
        val existing = (SceneDictionary.elements.map { it.name } + _saved.value.map { it.drawing.name }).distinct()
        // Primera vuelta, un borrador: con poco esfuerzo piensa lo justo (con el de fábrica, high, llegó a
        // gastar los 16 000 tokens pensando y no terminó). La revisión, que mira la imagen, piensa más.
        val first = try {
            client.json(DrawingFormat.system, DrawingFormat.request(thing, context, existing), DrawingFormat.schema(), effort = DRAFT_EFFORT)
        } catch (e: ClaudeCutOffException) {
            // Se cobró igual: cuenta para el límite del día.
            settings.registerUse(USAGE)
            throw e
        }
        settings.registerUse(USAGE)
        var drawing = DrawingFormat.parse(first.json, thing.ko, thing.es)
        var tokensIn = first.inputTokens
        var tokensOut = first.outputTokens
        // Segunda vuelta: Claude mira cómo quedó y lo corrige. Si falla, se queda el primero.
        try {
            val png = withContext(Dispatchers.Default) { DrawingSnapshot.png(drawing) }
            val content = JSONArray()
                .put(ClaudeProtocol.imageBlock(png))
                .put(ClaudeProtocol.textBlock(DrawingFormat.review(thing, DrawingFormat.toJson(drawing))))
            val second = client.json(DrawingFormat.system, content, DrawingFormat.schema(), effort = REVIEW_EFFORT)
            tokensIn += second.inputTokens
            tokensOut += second.outputTokens
            drawing = DrawingFormat.parse(second.json, thing.ko, thing.es)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("SceneDrawings", "No se pudo revisar el dibujo; queda el primero.", e)
        }
        lock.withLock {
            drawing = if (replace != null) {
                // Mismo id y las palabras de antes más las nuevas.
                copyOf(drawing, id = replace.drawing.id, keys = (replace.drawing.keys + drawing.keys).distinct().take(5))
            } else {
                val taken = _saved.value.map { it.drawing.id }.toSet()
                var id = drawing.id
                var n = 2
                while (id in taken || SceneDictionary.element(id) != null) id = "${drawing.id}_${n++}"
                if (id == drawing.id) drawing else copyOf(drawing, id = id, keys = drawing.keys)
            }
            val entry = SavedDrawing(drawing, System.currentTimeMillis(), model(), tokensIn, tokensOut, context.take(400))
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                val json = DrawingFormat.toJson(drawing).put(
                    "meta",
                    JSONObject().put("at", entry.createdAt).put("model", entry.model)
                        .put("in", entry.tokensIn).put("out", entry.tokensOut).put("ctx", entry.context).put("ko", thing.ko),
                )
                File(dir, "${drawing.id}.json").writeText(json.toString())
            }
            _saved.update { list -> list.filterNot { it.drawing.id == entry.drawing.id } + entry }
        }
    }

    private fun copyOf(d: CustomDrawing, id: String, keys: List<String>) =
        CustomDrawing(id, d.name, keys, d.zone, d.size, d.motion, d.count, d.colors, d.shapes)

    private fun setJob(key: String, job: DrawJob?) {
        _jobs.update { if (job == null) it - key else it + (key to job) }
    }

    private fun addPending(items: List<PendingThing>) {
        _pending.update { list -> (list + items).distinctBy { it.thing.ko }.takeLast(MAX_PENDING) }
        savePending()
    }

    private fun removePending(ko: String) {
        _pending.update { list -> list.filterNot { it.thing.ko == ko } }
        savePending()
    }

    private fun savePending() {
        val snapshot = _pending.value
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                dir.mkdirs()
                val arr = JSONArray()
                snapshot.forEach { arr.put(JSONObject().put("ko", it.thing.ko).put("es", it.thing.es).put("ctx", it.context)) }
                pendingFile.writeText(arr.toString())
            }
        }
    }

    private fun message(e: Throwable): String = when {
        e is HttpProviderException && e.code == 401 -> "La clave de Claude no sirve (401). Revísala en Ajustes."
        e is HttpProviderException && e.code == 429 -> "Claude pide esperar un poco (429). Prueba «Dibujar» en un rato."
        e is HttpProviderException && e.code == 529 -> "Claude está saturado (529). Prueba en un rato."
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        /** Carpeta en `filesDir`; está en `BackupFormat.MANAGED_DIRS` y en las reglas de `res/xml`. */
        const val DIR = "scene_drawings"
        private const val PENDING = "pending.json"
        private const val USAGE = "claude"

        /** Cuánto piensa Claude en el borrador y en la revisión mirando la imagen. */
        private const val DRAFT_EFFORT = "low"
        private const val REVIEW_EFFORT = "medium"

        /** Dibujos por día como máximo: cuida los créditos si algo se repite sin querer. */
        const val DAILY_LIMIT = 12
        private const val MAX_PENDING = 20
    }
}
