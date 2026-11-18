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

    /**
     * Lee un dibujo; [ko] y [es] completan lo que falte (lo que se pidió dibujar). [prefix] va delante
     * del id: el de Claude o el de fábrica. Lanza si no hay nada que pintar.
     */
    fun parse(json: JSONObject, ko: String = "", es: String = "", prefix: String = CustomDrawing.PREFIX): CustomDrawing {
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
        // El prefijo se quita antes de recortar: si no, un id largo ya guardado («x_» + 23 letras o más)
        // perdía sus últimas letras al releerlo y dejaba de coincidir con su archivo.
        val slug = json.optString("id").lowercase().replace(Regex("[^a-z0-9_]"), "_").trim('_')
        val bare = slug.removePrefix(CustomDrawing.PREFIX).removePrefix(CustomDrawing.FACTORY_PREFIX).take(24).trim('_')
        val id = prefix + bare.ifEmpty { "d" + (ko.ifEmpty { es }).hashCode().toUInt().toString(16) }
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

    /** Un dibujo de fábrica (un JSON de `assets/dibujos/` de la app). */
    fun parseFactory(text: String): CustomDrawing = parse(JSONObject(text), prefix = CustomDrawing.FACTORY_PREFIX)

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

        GOAL: anyone must recognize the thing at a glance, small (about 250 px wide on a phone) and through a watercolor filter that softens edges and wobbles them a few pixels. So: one clear iconic silhouette in the view where it is most recognizable (usually side view); its defining features big and a little exaggerated (a puppy: big round head, floppy ears, snout, short thick legs, little tail; a lighthouse: tall tapered tower, stripes, lamp on top); solid shapes; nothing thinner than w 3 except whiskers; eyes at least r 2.5. Avoid piles of small overlapping blobs: they melt into one stain. No text, no outline around everything, no detailed faces.

        HOW TO BUILD IT:
        1. Plan the silhouette in the box first: where the main body sits, how tall it is, where every part attaches. Use most of the box.
        2. Main masses as washes whose points follow the real outline (8 to 20 points; the app smooths corners, so put two close points where you want a corner).
        3. Legs, poles, stems and handles as thick strokes (legs w 5 to 9) that reach y = 100 when the thing stands.
        4. The defining features: ears, snout, tail, roof, wheels, petals...
        5. One or two shading blobs in a darker tone (a 0.12 to 0.2), then the small dark details last (eye, nose).

        THE BOX: 100 x 100. x goes from 0 (left) to 100 (right); y from 0 (top) to 100 (bottom). y = 100 is where the thing rests: the ground for things that stand, the waterline for things on the water. Center it around x = 50.

        SHAPES (each has a color key "c" and pigment "a" from 0.05 to 0.9):
        - "wash": an area of color. "pts" is the outline, at least 3 points, smoothed by the app.
        - "blob": a round touch at "x","y" with radius "r" (box units); "sq" squashes it (1 = round, 0.5 = flat), "ang" rotates it in radians.
        - "stroke": a brush stroke along "pts" (at least 3 points) with width "w" (box units); it starts thin and ends thinner, like a real brush.
        Shapes are painted in order. Use 8 to 30 shapes. Main bodies: a 0.55 to 0.75. Shading and halos: 0.12 to 0.25. Strokes: 0.55 to 0.8.

        COLORS: 2 to 5 colors, each with "light" (for cream paper #faf7ee) and "dark" (for near-black paper #20201e). Muted watercolor tones, never neon. "light" must be mid-tone so it shows on cream (not pale); "dark" a lighter, softer version so it shows on black. Use a clearly darker tone for details than for the body. Existing scene colors (light/dark) for harmony: sea #5fa8d8/#78b8e0, grass #8dbf5a/#a6d27a, bark #7a5a3c/#b08a66, sun #f6c453/#fbe3a0, stone #9aa3ad/#8e979f.

        PLACE ("zone"): GROUND (stands in front: animals, people, objects, small plants), HORIZON (behind, on land or water: buildings, boats, big trees, rocks), SKY (high up: birds, kites, balloons, planes), AIR (small things floating all over: butterflies, fireflies, bubbles). "size": SMALL (cat, bird, flower), MEDIUM (bicycle, bench, dog), LARGE (lighthouse, house, boat).

        MOTION: "motion" moves the whole drawing: NONE, SWAY (gently rocks, for plants), BOB (floats up and down, for boats and balloons), DRIFT (crosses the scene slowly, for birds and clouds). "move" on a shape animates that part: SWAY rocks it around its FIRST point (a tail, a flag, a branch: list the points from the attached end outward), FLAP flaps it around its first point mirrored left/right (two wings: both start at the body), BOB lifts it up and down, TWINKLE makes it glow on and off (a light, a lantern). Use motion only where it makes sense; many things are still. "count": copies scattered in the scene, 1 for almost everything, 2-5 only for small flying or floating things (birds, butterflies).

        WORDS: "keys" are the Korean nouns that make it appear when the student writes them: the dictionary noun first (고양이), then up to 3 common variants or synonyms (냥이), hangul only, no particles, no verbs. "name" is the name in Spanish, lowercase (gato). "id" is a short English slug in lowercase with underscores (cat).

        EXAMPLE 1 (a sitting cat whose tail sways):
        {"id":"cat","name":"gato","keys":["고양이","냥이"],"zone":"GROUND","size":"SMALL","motion":"NONE","count":1,"colors":[{"key":"fur","light":"#8a7461","dark":"#d8c2a8"},{"key":"fur_dark","light":"#5e4c3f","dark":"#a08a74"}],"shapes":[{"k":"wash","pts":[[38,100],[34,90],[36,76],[42,64],[50,58],[60,60],[68,70],[72,84],[72,100]],"c":"fur","a":0.67},{"k":"blob","x":62,"y":80,"r":11,"sq":1.25,"ang":0.3,"c":"fur_dark","a":0.16},{"k":"blob","x":40,"y":50,"r":13,"sq":0.92,"ang":0,"c":"fur","a":0.72},{"k":"wash","pts":[[29,46],[30,30],[39,40]],"c":"fur","a":0.72},{"k":"wash","pts":[[42,38],[48,27],[51,41]],"c":"fur","a":0.72},{"k":"stroke","pts":[[44,72],[43,86],[42,99]],"w":6,"c":"fur_dark","a":0.32},{"k":"stroke","pts":[[70,97],[83,95],[89,85],[86,72]],"w":5.5,"c":"fur","a":0.72,"move":"SWAY"},{"k":"blob","x":35,"y":49,"r":2.5,"sq":1,"ang":0,"c":"fur_dark","a":0.8}]}

        EXAMPLE 2 (a standing dog, side view, whose tail sways: body wash, four thick legs to the ground, big head, ear, light snout, dark eye and nose):
        {"id":"dog","name":"perro","keys":["개","강아지"],"zone":"GROUND","size":"SMALL","motion":"NONE","count":1,"colors":[{"key":"fur","light":"#c08a52","dark":"#dcae7c"},{"key":"ear","light":"#7a5232","dark":"#b48660"},{"key":"belly","light":"#ead2b0","dark":"#f0dcc0"},{"key":"ink","light":"#3a2a20","dark":"#2a1e16"}],"shapes":[{"k":"wash","pts":[[30,62],[34,55],[44,52],[58,52],[70,55],[76,62],[77,72],[72,80],[60,82],[44,82],[33,79],[28,71]],"c":"fur","a":0.72},{"k":"stroke","pts":[[36,76],[36,88],[36,99]],"w":8,"c":"fur","a":0.8},{"k":"stroke","pts":[[46,79],[46,90],[46,99]],"w":8,"c":"fur","a":0.8},{"k":"stroke","pts":[[62,79],[62,90],[62,99]],"w":8,"c":"fur","a":0.8},{"k":"stroke","pts":[[71,76],[71,88],[71,99]],"w":8,"c":"fur","a":0.8},{"k":"stroke","pts":[[76,62],[83,55],[87,46]],"w":5,"c":"fur","a":0.8,"move":"SWAY"},{"k":"blob","x":28,"y":44,"r":17,"sq":0.95,"ang":0,"c":"fur","a":0.78},{"k":"wash","pts":[[14,46],[8,47],[5,52],[8,57],[15,58],[22,55],[22,48]],"c":"belly","a":0.8},{"k":"wash","pts":[[33,32],[41,30],[45,38],[44,52],[39,57],[34,50],[32,40]],"c":"ear","a":0.78},{"k":"blob","x":54,"y":74,"r":9,"sq":0.6,"ang":0,"c":"ear","a":0.18},{"k":"blob","x":22,"y":40,"r":2.8,"sq":1,"ang":0,"c":"ink","a":0.85},{"k":"blob","x":6,"y":50,"r":3,"sq":0.8,"ang":0,"c":"ink","a":0.85},{"k":"stroke","pts":[[8,56],[12,59],[17,58]],"w":1.6,"c":"ink","a":0.6}]}

        EXAMPLE 3 (a lighthouse on rocks whose lamp glows):
        {"id":"lighthouse","name":"faro","keys":["등대"],"zone":"HORIZON","size":"LARGE","motion":"NONE","count":1,"colors":[{"key":"wall","light":"#d6cdbb","dark":"#d8d0bf"},{"key":"stripe","light":"#b5544a","dark":"#d07a6e"},{"key":"roof","light":"#4f5a63","dark":"#9aa7b1"},{"key":"glow","light":"#f0c24a","dark":"#f5d77a"},{"key":"rock","light":"#8d877c","dark":"#a8a196"}],"shapes":[{"k":"blob","x":50,"y":25,"r":14,"sq":1,"ang":0,"c":"glow","a":0.14,"move":"TWINKLE"},{"k":"wash","pts":[[38,96],[38.5,94],[43,31],[43.5,30],[56.5,30],[57,31],[61.5,94],[62,96]],"c":"wall","a":0.6},{"k":"wash","pts":[[39.6,80],[40.6,68],[59.4,68],[60.4,80]],"c":"stripe","a":0.6},{"k":"wash","pts":[[41.4,56],[42.3,45],[57.7,45],[58.6,56]],"c":"stripe","a":0.6},{"k":"wash","pts":[[45,30],[45,20],[55,20],[55,30]],"c":"glow","a":0.7,"move":"TWINKLE"},{"k":"stroke","pts":[[39,30.5],[50,30],[61,30.5]],"w":3,"c":"roof","a":0.65},{"k":"wash","pts":[[42,20.5],[50,10],[58,20.5]],"c":"roof","a":0.65},{"k":"wash","pts":[[47,96],[47,88],[50,86],[53,88],[53,96]],"c":"roof","a":0.4},{"k":"wash","pts":[[20,100],[25,92],[36,88],[50,91],[64,87],[75,93],[80,100]],"c":"rock","a":0.55}]}
    """.trimIndent()

    /**
     * La segunda vuelta: Claude mira cómo se ve su dibujo (la imagen va antes de este texto) y lo
     * corrige. Dibujar a ciegas con coordenadas es difícil; con la imagen ve lo que falta o se empasta.
     */
    fun review(thing: NewSceneThing, drawing: JSONObject): String = buildString {
        append("The image above is how your drawing of «").append(thing.ko).append("» (").append(thing.es).append(") renders: ")
        append("plain fills on cream paper, the 100 x 100 box at 512 px; the thin grey line is y = 100. ")
        append("In the app it is shown much smaller (about 250 px) through a watercolor filter that softens and wobbles edges, so thin or tiny details disappear.\n\n")
        append("Look at the image as someone who has not seen the JSON. Would they say «").append(thing.es).append("» at a glance? ")
        append("Check the silhouette and proportions; parts that are missing, detached or in the wrong place; parts that melt together into a stain; ")
        append("legs that do not reach the ground; colors too pale for the paper; a drawing too small for the box.\n\n")
        append("Then return the complete improved drawing in the same format, with every field. Keep what works; if it already reads clearly, make only small fixes.\n\n")
        append("Your drawing:\n").append(drawing.toString())
    }

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
