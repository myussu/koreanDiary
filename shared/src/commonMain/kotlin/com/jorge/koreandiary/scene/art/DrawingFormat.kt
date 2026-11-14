package com.jorge.koreandiary.scene.art

import com.jorge.koreandiary.scene.SceneZone
import org.json.JSONArray
import org.json.JSONObject

/** Algo que apareció al revisar una escena y que todavía no tiene dibujo: el sustantivo y su nombre. */
data class NewSceneThing(val ko: String, val es: String)

/**
 * El formato de los dibujos nuevos: lo que Claude devuelve, cómo se guarda (el mismo JSON) y cómo se
 * lee de vuelta sin confiar en él (todo se acota: un dibujo raro se ve raro, pero no rompe la escena).
 */
object DrawingFormat {

    const val VERSION = 1
    private const val MAX_SHAPES = 40
    private const val MAX_COLORS = 6
    private const val MAX_KEYS = 5
    private val hex = Regex("^#[0-9a-fA-F]{6}$")
    private val hangul = Regex("^[\\p{IsHangul}]{1,8}$")

    /** Lee un dibujo; [ko] y [es] completan lo que falte (lo que se pidió dibujar). Lanza si no hay nada que pintar. */
    fun parse(json: JSONObject, ko: String = "", es: String = ""): CustomDrawing {
        val colors = json.optJSONArray("colors").objects().mapNotNull { o ->
            val key = o.optString("key").trim()
            val light = o.optString("light").trim()
            val dark = o.optString("dark").trim()
            if (key.isEmpty() || !hex.matches(light) || !hex.matches(dark)) null else DrawingColor(key, light.lowercase(), dark.lowercase())
        }.distinctBy { it.key }.take(MAX_COLORS)
        val known = colors.map { it.key }.toSet()
        val shapes = json.optJSONArray("shapes").objects().mapNotNull { o -> shape(o, known) }.take(MAX_SHAPES)
        if (shapes.isEmpty()) throw IllegalArgumentException("El dibujo vino vacío.")

        val keys = (json.optJSONArray("keys").strings() + listOf(ko))
            .map { it.trim() }
            .filter { hangul.matches(it) }
            .distinct()
            .take(MAX_KEYS)
        val slug = json.optString("id").lowercase().replace(Regex("[^a-z0-9_]"), "_").trim('_').take(24)
        val id = CustomDrawing.PREFIX + slug.removePrefix(CustomDrawing.PREFIX).ifEmpty { "d" + (ko.ifEmpty { es }).hashCode().toUInt().toString(16) }
        return CustomDrawing(
            id = id,
            name = json.optString("name").trim().ifEmpty { es.trim() }.ifEmpty { ko }.take(40),
            keys = keys,
            zone = enumOr(json.optString("zone"), SceneZone.GROUND),
            size = enumOr(json.optString("size"), DrawingSize.MEDIUM),
            motion = enumOr(json.optString("motion"), DrawingMotion.NONE),
            count = json.optInt("count", 1).coerceIn(1, 5),
            colors = colors,
            shapes = shapes,
        )
    }

    private fun shape(o: JSONObject, colors: Set<String>): DrawingShape? {
        val c = o.optString("c").trim()
        if (c !in colors) return null
        val a = o.optDouble("a", 0.5).toFloat().takeIf { !it.isNaN() }?.coerceIn(0.04f, 0.9f) ?: 0.5f
        val move = enumOr(o.optString("move"), PartMotion.NONE)
        fun num(name: String, def: Float, lo: Float, hi: Float): Float =
            o.optDouble(name, def.toDouble()).toFloat().takeIf { !it.isNaN() }?.coerceIn(lo, hi) ?: def
        return when (o.optString("k").lowercase()) {
            "wash" -> points(o.optJSONArray("pts"))?.let { DrawingShape.Wash(it, c, a, move) }
            "stroke" -> points(o.optJSONArray("pts"))?.let { DrawingShape.Stroke(it, num("w", 3f, 0.4f, 20f), c, a, move) }
            "blob" -> DrawingShape.Blob(
                num("x", 50f, -10f, 110f),
                num("y", 50f, -10f, 110f),
                num("r", 5f, 0.5f, 50f),
                num("sq", 1f, 0.2f, 3f),
                num("ang", 0f, -6.3f, 6.3f),
                c,
                a,
                move,
            )
            else -> null
        }
    }

    /**
     * Puntos de una aguada o pincelada, dentro de la caja (con un poco de margen). Con dos puntos se
     * agrega el del medio: una pincelada de dos puntos entra y sale tan fina que no se ve.
     */
    private fun points(arr: JSONArray?): List<Pt>? {
        if (arr == null) return null
        val pts = (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONArray(i) ?: return@mapNotNull null
            val x = p.optDouble(0, Double.NaN).toFloat()
            val y = p.optDouble(1, Double.NaN).toFloat()
            if (x.isNaN() || y.isNaN()) null else Pt(x.coerceIn(-10f, 110f), y.coerceIn(-10f, 110f))
        }.take(60)
        return when {
            pts.size >= 3 -> pts
            pts.size == 2 -> listOf(pts[0], Pt((pts[0].x + pts[1].x) / 2, (pts[0].y + pts[1].y) / 2), pts[1])
            else -> null
        }
    }

    /** El dibujo en el mismo formato en que vino (así se guarda). */
    fun toJson(d: CustomDrawing): JSONObject {
        val shapes = JSONArray()
        d.shapes.forEach { s ->
            val o = JSONObject().put("c", s.color).put("a", r2(s.alpha))
            if (s.move != PartMotion.NONE) o.put("move", s.move.name)
            when (s) {
                is DrawingShape.Wash -> o.put("k", "wash").put("pts", pointsJson(s.points))
                is DrawingShape.Stroke -> o.put("k", "stroke").put("pts", pointsJson(s.points)).put("w", r2(s.width))
                is DrawingShape.Blob -> o.put("k", "blob").put("x", r2(s.x)).put("y", r2(s.y)).put("r", r2(s.r))
                    .put("sq", r2(s.squash)).put("ang", r2(s.angle))
            }
            shapes.put(o)
        }
        return JSONObject()
            .put("v", VERSION)
            .put("id", d.id)
            .put("name", d.name)
            .put("keys", JSONArray(d.keys))
            .put("zone", d.zone.name)
            .put("size", d.size.name)
            .put("motion", d.motion.name)
            .put("count", d.count)
            .put("colors", JSONArray().apply { d.colors.forEach { put(JSONObject().put("key", it.key).put("light", it.light).put("dark", it.dark)) } })
            .put("shapes", shapes)
    }

    private fun pointsJson(pts: List<Pt>) = JSONArray().apply { pts.forEach { put(JSONArray().put(r2(it.x)).put(r2(it.y))) } }

    /** Dos decimales: el archivo queda legible y no cambia el dibujo. */
    private fun r2(v: Float): Double = Math.round(v * 100.0) / 100.0

    // ---------- Lo que se le pide a Claude ----------

    /** El esquema de la respuesta (salida estructurada de la API de Claude). */
    fun schema(): JSONObject {
        fun str() = JSONObject().put("type", "string")
        fun num() = JSONObject().put("type", "number")
        fun enumOf(vararg v: String) = JSONObject().put("type", "string").put("enum", JSONArray(v.toList()))
        fun obj(props: JSONObject, required: List<String>) =
            JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required)).put("additionalProperties", false)
        val point = JSONObject().put("type", "array").put("items", num())
        val color = obj(
            JSONObject().put("key", str())
                .put("light", JSONObject().put("type", "string").put("pattern", "^#[0-9a-fA-F]{6}$"))
                .put("dark", JSONObject().put("type", "string").put("pattern", "^#[0-9a-fA-F]{6}$")),
            listOf("key", "light", "dark"),
        )
        val shape = obj(
            JSONObject()
                .put("k", enumOf("wash", "blob", "stroke"))
                .put("c", str())
                .put("a", num())
                .put("move", enumOf(*PartMotion.entries.map { it.name }.toTypedArray()))
                .put("pts", JSONObject().put("type", "array").put("items", point))
                .put("w", num()).put("x", num()).put("y", num()).put("r", num()).put("sq", num()).put("ang", num()),
            listOf("k", "c", "a"),
        )
        return obj(
            JSONObject()
                .put("id", str())
                .put("name", str())
                .put("keys", JSONObject().put("type", "array").put("items", str()))
                .put("zone", enumOf(*SceneZone.entries.map { it.name }.toTypedArray()))
                .put("size", enumOf(*DrawingSize.entries.map { it.name }.toTypedArray()))
                .put("motion", enumOf(*DrawingMotion.entries.map { it.name }.toTypedArray()))
                .put("count", JSONObject().put("type", "integer"))
                .put("colors", JSONObject().put("type", "array").put("items", color))
                .put("shapes", JSONObject().put("type", "array").put("items", shape)),
            listOf("id", "name", "keys", "zone", "size", "motion", "count", "colors", "shapes"),
        )
    }

    /** Instrucciones fijas (iguales en cada pedido). */
    val system: String = """
        You draw small illustrations for a Korean handwriting diary app. At the bottom of each page there is a strip with a watercolor scene that reacts to what the student writes (sea, trees, sun, rain...). You add new things to that scene: one drawing per request, which the app paints with soft brush strokes and a watercolor filter, in the same hand as the existing drawings.

        THE BOX: draw inside a 100 x 100 box. x goes from 0 (left) to 100 (right); y goes from 0 (top) to 100 (bottom). y = 100 is where the thing rests: the ground for things that stand, the waterline for things on the water. Center the drawing horizontally around x = 50 and use most of the box. Side or three-quarter view, simple iconic silhouette that still reads at 100 pixels. No text, no letters, no outlines around everything, no detailed faces (a dot for an eye is enough).

        SHAPES (each has a color key "c" and pigment "a" from 0.05 to 0.9):
        - "wash": an area of color. "pts" is the outline, at least 3 points, smoothed by the app (corners come out round, so add points where you want a corner).
        - "blob": a round touch at "x","y" with radius "r" (box units); "sq" squashes it (1 = round, 0.5 = flat), "ang" rotates it in radians.
        - "stroke": a brush stroke along "pts" (at least 3 points) with width "w" (box units, 1-8 usually); it starts thin and ends thinner, like a real brush.
        Shapes are painted in order: big washes first, then shading, then details and strokes. Use 6 to 30 shapes. Main bodies: wash with a 0.45-0.7. Shading and halos: 0.12-0.25. Strokes: 0.5-0.75.

        COLORS: 2 to 5 colors, each with "light" (for cream paper #faf7ee) and "dark" (for near-black paper #20201e). Muted watercolor tones, never neon. "light" must be mid-tone so it shows on cream (not pale); "dark" must be a lighter, softer version so it shows on black. Existing scene colors (light/dark) for harmony: sea #5fa8d8/#78b8e0, grass #8dbf5a/#a6d27a, bark #7a5a3c/#b08a66, sun #f6c453/#fbe3a0, stone #9aa3ad/#8e979f.

        PLACE ("zone"): GROUND (stands in front: animals, people, objects, small plants), HORIZON (behind, on land or water: buildings, boats, big trees, rocks), SKY (high up: birds, kites, balloons, planes), AIR (small things floating all over: butterflies, fireflies, bubbles). "size": SMALL (cat, bird, flower), MEDIUM (bicycle, bench, dog), LARGE (lighthouse, house, boat).

        MOTION: "motion" moves the whole drawing: NONE, SWAY (gently rocks, for plants), BOB (floats up and down, for boats and balloons), DRIFT (crosses the scene slowly, for birds and clouds). "move" on a shape animates that part: SWAY rocks it around its FIRST point (a tail, a flag, a branch: list the points from the attached end outward), FLAP flaps it around its first point mirrored left/right (two wings: both start at the body), BOB lifts it up and down, TWINKLE makes it glow on and off (a light, a lantern). Use motion only where it makes sense; many things are still. "count": copies scattered in the scene, 1 for almost everything, 2-5 only for small flying or floating things (birds, butterflies).

        WORDS: "keys" are the Korean nouns that make it appear when the student writes them: the dictionary noun first (고양이), then up to 3 common variants or synonyms (냥이), hangul only, no particles, no verbs. "name" is the name in Spanish, lowercase (gato). "id" is a short English slug in lowercase with underscores (cat).

        EXAMPLE (a sitting cat whose tail sways):
        {"id":"cat","name":"gato","keys":["고양이","냥이"],"zone":"GROUND","size":"SMALL","motion":"NONE","count":1,"colors":[{"key":"fur","light":"#8a7461","dark":"#d8c2a8"},{"key":"fur_dark","light":"#5e4c3f","dark":"#a08a74"}],"shapes":[{"k":"wash","pts":[[38,100],[34,90],[36,76],[42,64],[50,58],[60,60],[68,70],[72,84],[72,100]],"c":"fur","a":0.67},{"k":"blob","x":62,"y":80,"r":11,"sq":1.25,"ang":0.3,"c":"fur_dark","a":0.16},{"k":"blob","x":40,"y":50,"r":13,"sq":0.92,"ang":0,"c":"fur","a":0.72},{"k":"wash","pts":[[29,46],[30,30],[39,40]],"c":"fur","a":0.72},{"k":"wash","pts":[[42,38],[48,27],[51,41]],"c":"fur","a":0.72},{"k":"stroke","pts":[[44,72],[43,86],[42,99]],"w":6,"c":"fur_dark","a":0.32},{"k":"stroke","pts":[[70,97],[83,95],[89,85],[86,72]],"w":5.5,"c":"fur","a":0.72,"move":"SWAY"},{"k":"blob","x":35,"y":49,"r":1.7,"sq":1,"ang":0,"c":"fur_dark","a":0.75}]}
    """.trimIndent()

    /** El pedido de un dibujo: qué es y dónde apareció (la frase ayuda: un faro en las rocas, un gato dormido). */
    fun request(thing: NewSceneThing, context: String, existing: List<String>): String = buildString {
        append("Draw: «").append(thing.ko).append("» (").append(thing.es).append(").")
        val sentence = context.trim().replace('\n', ' ').take(400)
        if (sentence.isNotEmpty()) append("\nIt appeared in this diary scene: «").append(sentence).append("»")
        if (existing.isNotEmpty()) append("\nThe scene can already draw (do not repeat these): ").append(existing.joinToString(", ")).append('.')
    }

    // ---------- Utilidades ----------

    private inline fun <reified E : Enum<E>> enumOr(value: String, def: E): E =
        enumValues<E>().firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: def

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
}
