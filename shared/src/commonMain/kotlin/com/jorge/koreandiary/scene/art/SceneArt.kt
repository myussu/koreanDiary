package com.jorge.koreandiary.scene.art

import com.jorge.koreandiary.scene.SceneElement
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * La escena viva pintada a pincel en acuarela, toda en una franja abajo de la hoja: el fondo de color
 * (cielo, atardecer, noche), lo lejano (sol, luna, nubes, montañas, ciudad), el agua y los árboles,
 * el suelo adelante y lo que cae o vuela encima. Nada sube más que [stageHeight]: arriba se escribe.
 *
 * Coordenadas de la franja: x de 0 a `width`, y de 0 (arriba de la franja) a [stageHeight] (el borde
 * de abajo de la hoja). Todo se calcula de nuevo en cada cuadro con el tiempo `t` (segundos): los
 * dibujos se mecen, las olas avanzan, la lluvia cae. Las posiciones salen de semillas fijas, así que
 * el mismo `t` da siempre el mismo dibujo.
 */
object SceneArt {

    /** La unidad de tamaño: el ancho de la hoja, sin pasar de lo que permite su alto (tabletas acostadas). */
    fun unit(width: Float, height: Float): Float = min(width, height * 0.62f)

    /** Alto de la franja donde vive el dibujo. */
    fun stageHeight(width: Float, height: Float): Float = unit(width, height) * 0.72f

    /** En qué capa va cada elemento: fondo, lejos, medio, suelo y aire. */
    fun layer(id: String): Int = when (id) {
        "sky", "sunset", "night", "warm", "cool" -> 0
        "sun", "moon", "clouds", "stars", "rainbow", "mountain", "city" -> 1
        "sea", "lake", "forest" -> 2
        "sand", "street", "leaves", "grass", "wheat", "flowers" -> 3
        else -> 4
    }

    /**
     * Las manchas de toda la escena para una hoja de [width] × [height] en el segundo [t]. [fade] da la
     * entrada de cada elemento (0..1).
     */
    fun paint(elements: List<SceneElement>, width: Float, height: Float, t: Float, fade: (String) -> Float = { 1f }): List<Mark> {
        if (width <= 0f || height <= 0f) return emptyList()
        val s = Stage(width, stageHeight(width, height), unit(width, height))
        val ids = elements.map { it.id }.distinct()
        val out = ArrayList<Mark>()
        for (id in ids.sortedBy { layer(it) }) {
            val p = Painter(fade(id).coerceIn(0f, 1f))
            s.draw(id, p, t, ids)
            out += p.marks
        }
        return out
    }
}

/** La franja y sus medidas: [w] de ancho, [h] de alto (el borde de abajo es y = h), [u] la unidad. */
internal class Stage(val w: Float, val h: Float, val u: Float) {
    /** Alto del agua. */
    val hb = u * 0.24f

    /** Alto de la tira de suelo de adelante. */
    val gs = hb * 0.5f

    fun draw(id: String, p: Painter, t: Float, all: List<String>) {
        when (id) {
            "sky" -> bands(p, t, listOf("sky" to 0.12f, "sky_light" to 0.14f, "sky_light" to 0.16f), listOf(2.7f, 2.0f, 1.3f))
            "sunset" -> bands(p, t, listOf("sunset_c" to 0.18f, "sunset_b" to 0.18f, "sunset_a" to 0.2f), listOf(2.7f, 2.0f, 1.3f))
            "night" -> bands(p, t, listOf("night" to 0.26f, "night_light" to 0.14f), listOf(2.7f, 1.6f))
            "warm" -> bands(p, t, listOf("warm" to 0.13f, "warm" to 0.12f), listOf(2.5f + 0.15f * sin(t * 0.6f), 1.5f))
            "cool" -> bands(p, t, listOf("cool" to 0.12f, "cool" to 0.1f), listOf(2.6f, 1.6f + 0.1f * sin(t * 0.4f)))
            "sun" -> sun(p, t, if ("moon" in all) w * 0.2f else w * 0.22f)
            "moon" -> moon(p, t, if ("sun" in all) w * 0.8f else w * 0.78f)
            "clouds" -> clouds(p, t)
            "stars" -> stars(p, t, "star", 14, 0.45f, 11)
            "rainbow" -> rainbow(p, t)
            "mountain" -> mountains(p, t)
            "city" -> city(p, t, onLeft = "mountain" !in all)
            "sea" -> waves(p, t, calm = false)
            "lake" -> waves(p, t, calm = true)
            "forest" -> {
                tree(p, t, w - u * 0.38f, h + 4f, u * 0.4f, 0.75f, 41)
                tree(p, t, w - u * 0.08f, h + 6f, u * 0.6f, 1f, 3)
            }
            "grass" -> blades(p, t, "grass", 0f, w, hb * 0.8f, 7)
            "wheat" -> blades(p, t, "wheat", 0f, w, hb * 0.95f, 13)
            "flowers" -> blades(p, t, "flowers", 0f, w, hb * 0.7f, 19)
            "sand" -> sand(p, t)
            "leaves" -> leaves(p, t)
            "street" -> street(p, t)
            "wind" -> wind(p, t)
            "rain" -> rain(p, t)
            "snow" -> snow(p, t)
            "petals" -> petals(p, t)
            "fog" -> fog(p, t)
            "sparkle" -> stars(p, t, "sparkle", 10, 0.75f, 23)
        }
    }

    // ---------- Fondos ----------

    /** Aguadas horizontales desde abajo, con el borde de arriba ondulado que se mueve muy despacio. */
    private fun bands(p: Painter, t: Float, layers: List<Pair<String, Float>>, tops: List<Float>) {
        layers.forEachIndexed { k, (color, alpha) ->
            val top = h - hb * tops[k]
            val pts = ArrayList<Pt>()
            pts += Pt(-20f, h + 10f)
            var x = -20f
            while (x <= w + 20f) {
                pts += Pt(x, top + hb * (0.1f * sin(x / w * 6.28f * 1.2f + t * 0.08f + k) + 0.05f * sin(x / w * 6.28f * 2.7f - t * 0.05f)))
                x += w / 16
            }
            pts += Pt(w + 20f, h + 10f)
            p.wash(pts, color, alpha)
        }
    }

    // ---------- Lo lejano ----------

    private fun sun(p: Painter, t: Float, cx: Float) {
        val cy = h - hb * 2.0f
        val r = u * 0.075f
        val breath = 1 + 0.04f * sin(t * 0.8f)
        // Un halo de agua muy diluida: que se sienta luz y no solo un disco.
        p.blob(cx, cy, r * 2.3f * breath, "sun_core", 0.12f, irregular = 0.12f, seed = 8)
        p.blob(cx, cy, r * breath, "sun", 0.6f, irregular = 0.08f, seed = 9)
        p.blob(cx - r * 0.15f, cy - r * 0.15f, r * 0.55f, "sun_core", 0.4f, irregular = 0.15f, seed = 10)
        for (i in 0 until 11) {
            val a = i / 11f * 2 * PI.toFloat() + t * 0.05f
            val a0 = r * (1.3f + 0.08f * sin(i * 2.3f))
            val a1 = r * (1.75f + 0.15f * sin(i * 1.7f + t * 0.7f))
            val pts = listOf(
                Pt(cx + cos(a) * a0, cy + sin(a) * a0),
                Pt(cx + cos(a + 0.05f) * (a0 + a1) / 2, cy + sin(a + 0.05f) * (a0 + a1) / 2),
                Pt(cx + cos(a + 0.08f) * a1, cy + sin(a + 0.08f) * a1),
            )
            p.stroke(pts, r * 0.16f, "sun_ray", 0.45f, 0.15f, 0.7f, seed = 20 + i)
        }
    }

    private fun moon(p: Painter, t: Float, cx: Float) {
        val cy = h - hb * 2.1f
        val r = u * 0.065f
        val glow = 0.1f + 0.04f * sin(t * 0.7f)
        p.blob(cx, cy, r * 1.7f, "moon", glow, irregular = 0.1f, seed = 30)
        p.blob(cx, cy, r, "moon", 0.65f, irregular = 0.06f, seed = 31)
        p.blob(cx - r * 0.3f, cy - r * 0.2f, r * 0.22f, "moon_dark", 0.3f, seed = 32)
        p.blob(cx + r * 0.25f, cy + r * 0.3f, r * 0.16f, "moon_dark", 0.28f, seed = 33)
        p.blob(cx + r * 0.35f, cy - r * 0.35f, r * 0.1f, "moon_dark", 0.25f, seed = 34)
    }

    /** Nubes de varios toques que pasan despacio y vuelven a entrar por el otro lado. */
    private fun clouds(p: Painter, t: Float) {
        for (c in 0 until 3) {
            val cw = u * (0.32f - 0.05f * c)
            val span = w + cw * 2
            val cx = ((c * span / 3 + t * (5f + 2f * c)) % span) - cw
            val cy = h - hb * (2.3f - 0.3f * c)
            val rnd = Random(60 + c)
            for (i in 0 until 6) {
                val fx = (i - 2.5f) / 2.5f
                val r = cw * (0.2f + 0.14f * (1 - abs(fx))) * (0.85f + 0.3f * rnd.nextFloat())
                val x = cx + fx * cw * 0.42f
                val y = cy - r * 0.35f * (1 - abs(fx)) + rnd.nextFloat() * r * 0.2f
                p.blob(x, y, r, "cloud", 0.42f, irregular = 0.22f, seed = 100 + c * 10 + i, squash = 0.75f)
                p.blob(x, y + r * 0.35f, r * 0.8f, "cloud_shade", 0.2f, irregular = 0.2f, seed = 140 + c * 10 + i, squash = 0.5f)
            }
        }
    }

    /** Estrellas o destellos: cruces de pincel que titilan, cada una a su ritmo. */
    private fun stars(p: Painter, t: Float, color: String, count: Int, strength: Float, seed: Int) {
        val rnd = Random(seed)
        repeat(count) { i ->
            val x = w * (0.04f + 0.92f * rnd.nextFloat())
            val y = h - hb * (1.2f + 1.5f * rnd.nextFloat())
            val size = u * (0.012f + 0.01f * rnd.nextFloat())
            val speed = 0.6f + 1.4f * rnd.nextFloat()
            val phase = rnd.nextFloat() * 6.28f
            val tw = (0.5f + 0.5f * sin(t * speed + phase)).pow(2.5f)
            val a = strength * (0.25f + 0.75f * tw)
            p.stroke(listOf(Pt(x - size, y), Pt(x, y + size * 0.05f), Pt(x + size, y)), size * 0.35f, color, a, 0.5f, 0.5f, seed = seed * 10 + i)
            p.stroke(listOf(Pt(x, y - size), Pt(x + size * 0.05f, y), Pt(x, y + size)), size * 0.35f, color, a, 0.5f, 0.5f, seed = seed * 10 + i + 500)
        }
    }

    private fun rainbow(p: Painter, t: Float) {
        val cx = w * 0.5f
        val cy = h + hb * 0.6f
        val colors = listOf("rb_r", "rb_o", "rb_y", "rb_g", "rb_b", "rb_v")
        colors.forEachIndexed { k, c ->
            val r = min(w * 0.42f, hb * 3.4f) - k * u * 0.02f
            val shimmer = 0.85f + 0.15f * sin(t * 0.4f + k)
            val pts = (0..10).map { i ->
                val a = PI.toFloat() + 0.35f + (PI.toFloat() - 0.7f) * i / 10f
                Pt(cx + cos(a) * r, cy + sin(a) * r)
            }
            p.stroke(pts, u * 0.022f, c, 0.32f * shimmer, 0.2f, 0.2f, wobble = 0.08f, seed = 200 + k)
        }
    }

    /** Dos capas de montañas a la izquierda, la de atrás con nieve; una luz pasa despacio. */
    private fun mountains(p: Painter, t: Float) {
        val far = listOf(
            Pt(-30f, h + 10f), Pt(-30f, h - hb * 1.2f), Pt(w * 0.1f, h - hb * 2.2f), Pt(w * 0.2f, h - hb * 2.7f),
            Pt(w * 0.3f, h - hb * 2.0f), Pt(w * 0.4f, h - hb * 2.3f), Pt(w * 0.55f, h - hb * 1.0f), Pt(w * 0.62f, h + 10f),
        )
        p.wash(far, "mountain_far", 0.3f + 0.04f * sin(t * 0.15f))
        p.blob(w * 0.2f, h - hb * 2.55f, u * 0.04f, "snow", 0.5f, seed = 210, squash = 0.5f)
        p.blob(w * 0.4f, h - hb * 2.2f, u * 0.03f, "snow", 0.45f, seed = 211, squash = 0.5f)
        val near = listOf(
            Pt(-30f, h + 10f), Pt(-30f, h - hb * 0.9f), Pt(w * 0.08f, h - hb * 1.5f), Pt(w * 0.18f, h - hb * 1.1f),
            Pt(w * 0.3f, h - hb * 1.6f), Pt(w * 0.44f, h - hb * 0.6f), Pt(w * 0.5f, h + 10f),
        )
        p.wash(near, "mountain", 0.34f)
        p.stroke(
            listOf(Pt(w * 0.02f, h - hb * 1.3f), Pt(w * 0.08f, h - hb * 1.48f), Pt(w * 0.18f, h - hb * 1.1f), Pt(w * 0.3f, h - hb * 1.58f), Pt(w * 0.42f, h - hb * 0.7f)),
            u * 0.008f, "mountain", 0.3f, 0.1f, 0.3f, seed = 212,
        )
    }

    /** Edificios de aguada con ventanas que se encienden y se apagan muy de vez en cuando. */
    private fun city(p: Painter, t: Float, onLeft: Boolean) {
        val rnd = Random(71)
        var x = if (onLeft) -u * 0.02f else w * 0.52f
        val end = if (onLeft) w * 0.42f else w * 0.94f
        var i = 0
        while (x < end) {
            val bw = u * (0.06f + 0.05f * rnd.nextFloat())
            val bh = hb * (1.0f + 1.5f * rnd.nextFloat())
            val base = h - gs * 0.3f
            val color = if (i % 3 == 1) "city_dark" else "city"
            p.wash(listOf(Pt(x, base), Pt(x, base - bh), Pt(x + bw * 0.5f, base - bh - u * 0.004f), Pt(x + bw, base - bh), Pt(x + bw, base)), color, 0.34f)
            val cols = max(1, (bw / (u * 0.022f)).toInt())
            val rows = max(1, (bh / (u * 0.03f)).toInt() - 1)
            for (r in 0 until rows) for (c in 0 until cols) {
                val seed = i * 100 + r * 10 + c
                val on = Random(seed + (t * 0.05f + seed * 0.37f).toInt()).nextFloat() > 0.55f
                if (!on) continue
                p.blob(x + bw * (c + 0.5f) / cols, base - bh + u * 0.02f + r * u * 0.03f, u * 0.006f, "window", 0.55f, irregular = 0.15f, seed = seed, squash = 1.3f)
            }
            x += bw + u * 0.008f
            i++
        }
    }

    // ---------- Agua y árboles ----------

    /**
     * Mar (o lago, más calmo) a lo ancho de la franja: dos aguadas, crestas que corren y, solo en el
     * mar, se enroscan; espuma y reflejos que titilan.
     */
    private fun waves(p: Painter, t: Float, calm: Boolean) {
        val by = h + 4f
        val deep = if (calm) "lake" else "sea_deep"
        val light = if (calm) "lake_light" else "sea"
        listOf(light to 0.32f, deep to 0.3f).forEachIndexed { k, (color, alpha) ->
            val pts = ArrayList<Pt>()
            pts += Pt(-20f, by + 10f)
            var x = -20f
            while (x <= w + 20f) {
                val y = by - hb * (0.72f - 0.26f * k + (if (calm) 0.03f else 0.07f) * sin(x / w * 6.28f * 1.3f + t * 0.4f + k) +
                    0.04f * sin(x / w * 6.28f * 3.1f - t * 0.6f))
                pts += Pt(x, y)
                x += w / 24
            }
            pts += Pt(w + 20f, by + 10f)
            p.wash(pts, color, alpha)
        }
        for (row in 0 until 3) {
            val len = hb * (0.7f + 0.45f * row)
            val amp = hb * (0.06f + 0.04f * row) * (if (calm) 0.4f else 1f)
            val spacing = len * (if (calm) 2.2f else 1.6f)
            val speed = (5f + 4f * row) * (if (calm) 0.5f else 1f)
            val yRow = by - hb * (0.6f - 0.2f * row)
            val n = (w / spacing).toInt() + 3
            for (i in 0 until n) {
                val xs = -spacing + ((i * spacing + t * speed + row * 53) % (n * spacing))
                val bob = sin(t * 0.9f + i * 1.3f + row) * hb * 0.025f
                val pts = ArrayList<Pt>()
                for (k in 0..6) pts += Pt(xs + len * k / 6f, yRow + bob - amp * sin(k / 6f * PI.toFloat()) * 0.7f)
                if (!calm && (row == 2 || i % 2 == 0)) {
                    pts += Pt(xs + len * 1.03f, yRow + bob - amp * 1.1f)
                    pts += Pt(xs + len * 0.97f, yRow + bob - amp * 1.7f)
                    pts += Pt(xs + len * 0.9f, yRow + bob - amp * 1.35f)
                }
                val color = if (row == 2) "foam" else light
                p.stroke(pts, hb * (0.035f + 0.02f * row), color, 0.35f + 0.18f * row, 0.3f, 0.25f, seed = 5 + row * 50 + i)
                if (!calm && row >= 1) {
                    val tip = pts[pts.size - 2]
                    p.blob(tip.x + amp * 0.6f, tip.y - amp * 0.2f, hb * 0.022f, "foam", 0.45f, seed = 305 + i + row)
                }
            }
        }
        val glints = (w / (hb * 0.9f)).toInt()
        for (i in 0 until glints) {
            val xr = (i * hb * 0.9f + (i * 37) % 23) % w
            val yr = by - hb * (0.15f + 0.35f * ((i * 0.618f) % 1f))
            val a = 0.35f * max(sin(t * (0.7f + (i % 3) * 0.3f) + i * 2.1f), 0f)
            p.stroke(listOf(Pt(xr, yr), Pt(xr + hb * 0.12f, yr - 1f), Pt(xr + hb * 0.24f, yr)), hb * 0.02f, "foam", a, 0.4f, 0.4f, seed = 700 + i)
        }
    }

    /**
     * Un árbol con base en ([bx], [by]) y alto [s]: tronco que se afina, ramas y una copa de toques en
     * tres capas (sombra, medio y luz). Se mece desde la punta; algunas hojas caen.
     */
    private fun tree(p: Painter, t: Float, bx: Float, by: Float, s: Float, strength: Float, seed: Int) {
        val sway = sin(t * 0.55f + seed) * 0.6f + sin(t * 1.3f + 1f + seed) * 0.25f
        fun at(uu: Float, v: Float, flutter: Float = 0f) = Pt(bx + (uu + sway * 0.05f * max(v, 0f).pow(1.6f) + flutter) * s, by - v * s)
        val trunk = listOf(0f to 0f, -0.02f to 0.18f, 0.012f to 0.36f, -0.01f to 0.55f)
        p.stroke(trunk.map { at(it.first, it.second) }, 0.075f * s, "bark", 0.75f * strength, 0.02f, 0.55f, seed = seed)
        p.stroke(trunk.take(3).map { at(it.first + 0.018f, it.second) }, 0.03f * s, "bark_dark", 0.55f * strength, 0.05f, 0.6f, seed = seed + 1)
        listOf(
            listOf(0f to 0.34f, -0.1f to 0.46f, -0.2f to 0.58f),
            listOf(0.005f to 0.42f, 0.1f to 0.52f, 0.19f to 0.63f),
            listOf(-0.01f to 0.5f, -0.03f to 0.62f, -0.06f to 0.72f),
        ).forEachIndexed { k, br ->
            p.stroke(br.map { at(it.first, it.second) }, 0.028f * s, "bark", 0.7f * strength, 0.05f, 0.75f, seed = seed + 10 + k)
        }
        val rnd = Random(seed)
        val cu = -0.01f
        val cv = 0.72f
        val layers = listOf(
            Triple("leaf_dark", 14, floatArrayOf(0.07f, 0.1f, -1f, 0.2f, 0.5f)),
            Triple("leaf", 20, floatArrayOf(0.06f, 0.095f, -0.8f, 0.9f, 0.45f)),
            Triple("leaf_light", 12, floatArrayOf(0.045f, 0.07f, 0f, 1f, 0.42f)),
        )
        layers.forEachIndexed { li, (color, count, cfg) ->
            for (i in 0 until count) {
                val ang = rnd.nextFloat() * 2 * PI.toFloat()
                val rad = sqrt(rnd.nextFloat()) * 0.92f
                val dv = cfg[2] + (cfg[3] - cfg[2]) * (sin(ang) * rad * 0.5f + 0.5f)
                val du = cos(ang) * rad - (if (color == "leaf_light") 0.25f * rnd.nextFloat() else 0f)
                val r = cfg[0] + (cfg[1] - cfg[0]) * rnd.nextFloat()
                val flutter = sin(t * 2.1f + i * 1.7f + li) * 0.006f
                val c = at(cu + du * 0.27f, cv + dv * 0.19f, flutter)
                p.blob(c.x, c.y, r * s, color, cfg[4] * strength, irregular = 0.3f, seed = seed * 100 + li * 20 + i, squash = 0.85f, angle = rnd.nextFloat())
            }
        }
        if (strength >= 1f) {
            for (i in 0 until 3) {
                val ph = (t * 0.08f + i * 0.37f) % 1f
                val c = Pt(bx - (0.25f + 0.12f * i + sin(t * 0.9f + i) * 0.04f + ph * 0.1f) * s, by - (0.7f - ph * 0.65f) * s)
                p.blob(c.x, c.y, 0.018f * s, "leaf", 0.5f * sin(ph * PI.toFloat()), irregular = 0.2f, seed = 900 + i, squash = 0.5f, angle = t * 1.5f + i)
            }
        }
    }

    // ---------- El suelo de adelante ----------

    /** Pasto, trigo o flores: matas de hojas que salen del borde y se mecen con el viento. */
    private fun blades(p: Painter, t: Float, kind: String, x0: Float, x1: Float, hh: Float, seed: Int) {
        val rnd = Random(seed)
        val colors = when (kind) {
            "wheat" -> listOf("wheat_stem", "wheat", "wheat")
            else -> listOf("grass_dark", "grass", "grass_light")
        }
        var x = x0
        var clumpIndex = 0
        while (x < x1) {
            val clump = x + rnd.nextFloat() * 10f
            val nb = (if (kind == "flowers") 3 else 5) + rnd.nextInt(4)
            for (b in 0 until nb) {
                val bx = clump + (b - nb / 2f) * hh * 0.05f
                val bh = hh * (0.45f + 0.55f * rnd.nextFloat())
                val lean = (rnd.nextFloat() - 0.5f) * 0.6f + (b - nb / 2f) * 0.12f
                val sway = sin(t * 0.9f + clump * 0.02f + b * 0.3f) * 0.18f
                val tip = Pt(bx + (lean + sway) * bh * 0.5f, h - bh)
                val pts = listOf(Pt(bx, h), Pt(bx + (lean + sway) * bh * 0.12f, h - bh * 0.45f), tip)
                val color = colors[rnd.nextInt(3)]
                p.stroke(pts, hh * 0.035f, color, 0.6f, 0f, 0.85f, seed = seed + clump.toInt() + b)
                if (kind == "wheat") {
                    val ang = kotlin.math.atan2(tip.y - pts[1].y, tip.x - pts[1].x)
                    p.blob(tip.x, tip.y, hh * 0.055f, "wheat_ear", 0.6f, irregular = 0.18f, seed = seed + 300 + b, squash = 0.38f, angle = ang)
                } else if (kind == "flowers" && b % 2 == 0) {
                    val petal = listOf("flower_pink", "flower_yellow", "flower_white")[(clumpIndex + b) % 3]
                    val r = hh * 0.05f
                    for (k in 0 until 5) {
                        val a = k / 5f * 2 * PI.toFloat() + clumpIndex
                        p.blob(tip.x + cos(a) * r, tip.y + sin(a) * r, r * 0.75f, petal, 0.55f, irregular = 0.2f, seed = seed + clumpIndex * 10 + k, squash = 0.7f, angle = a)
                    }
                    p.blob(tip.x, tip.y, r * 0.45f, "sun_core", 0.6f, irregular = 0.15f, seed = seed + 900 + clumpIndex)
                }
            }
            x += hh * (0.35f + 0.3f * rnd.nextFloat()) * (if (kind == "flowers") 1.6f else 1f)
            clumpIndex++
        }
    }

    private fun sand(p: Painter, t: Float) {
        val pts = ArrayList<Pt>()
        pts += Pt(-20f, h + 10f)
        var x = -20f
        while (x <= w + 20f) {
            pts += Pt(x, h - gs * (0.9f + 0.15f * sin(x / w * 6.28f * 1.6f + 0.6f)))
            x += w / 16
        }
        pts += Pt(w + 20f, h + 10f)
        p.wash(pts, "sand", 0.4f)
        val rnd = Random(81)
        for (i in 0 until 9) {
            val y = h - gs * (0.15f + 0.6f * rnd.nextFloat())
            val x0 = w * rnd.nextFloat()
            val len = u * (0.08f + 0.08f * rnd.nextFloat())
            val pts2 = (0..4).map { k -> Pt(x0 + len * k / 4f, y + sin(k * 1.4f + i) * u * 0.004f) }
            p.stroke(pts2, u * 0.006f, "sand_light", 0.35f, 0.3f, 0.4f, seed = 400 + i)
        }
        for (i in 0 until 3) {
            val a = 0.4f * max(sin(t * 0.8f + i * 2.3f), 0f).pow(3f)
            p.blob(w * (0.2f + 0.3f * i), h - gs * (0.3f + 0.15f * i), u * 0.005f, "sun_core", a, seed = 420 + i)
        }
    }

    private fun leaves(p: Painter, t: Float) {
        val rnd = Random(91)
        val colors = listOf("leaf_orange", "leaf_red", "leaf_yellow")
        for (i in 0 until 16) {
            val x = w * rnd.nextFloat()
            val y = h - gs * 0.85f * rnd.nextFloat()
            val r = u * (0.016f + 0.01f * rnd.nextFloat())
            p.blob(x, y, r, colors[i % 3], 0.6f, irregular = 0.18f, seed = 500 + i, squash = 0.5f, angle = rnd.nextFloat() * 6.28f)
        }
        for (i in 0 until 3) {
            val ph = (t * 0.06f + i * 0.33f) % 1f
            val x = w * ((0.15f + 0.3f * i + ph * 0.25f) % 1f) + sin(t * 1.1f + i) * u * 0.03f
            val y = h - hb * 2.4f * (1 - ph)
            p.blob(x, y, u * 0.018f, colors[i], 0.55f * sin(ph * PI.toFloat()), irregular = 0.18f, seed = 520 + i, squash = 0.5f, angle = t * 1.2f + i)
        }
    }

    private fun street(p: Painter, t: Float) {
        val rnd = Random(101)
        val rowH = gs * 0.38f
        for (row in 0 until 3) {
            val y = h - rowH * (row + 0.5f)
            val stone = u * (0.05f - row * 0.008f)
            var x = -stone * (row % 2) * 0.5f
            while (x < w + stone) {
                val r = stone * (0.42f + 0.08f * rnd.nextFloat())
                p.blob(x, y, r, if (rnd.nextFloat() > 0.7f) "stone_dark" else "stone", 0.34f - row * 0.05f, irregular = 0.12f, seed = 600 + row * 100 + x.toInt(), squash = 0.55f)
                x += stone * 1.02f
            }
        }
        val sheen = (t * 0.03f) % 1f
        p.blob(w * sheen, h - rowH * 1.2f, u * 0.06f, "foam", 0.08f, seed = 690, squash = 0.25f)
    }

    // ---------- Lo que vuela o cae ----------

    /** Rachas de pincel que cruzan la franja y se enroscan, y dos hojas que el viento se lleva. */
    private fun wind(p: Painter, t: Float) {
        for (i in 0 until 4) {
            val len = u * (0.35f + 0.1f * (i % 2))
            val span = w + len * 2
            val x0 = ((i * span / 4 + t * (u * 0.12f)) % span) - len
            val y = h - hb * (0.7f + 0.45f * i)
            val pts = ArrayList<Pt>()
            for (k in 0..6) pts += Pt(x0 + len * k / 6f, y - sin(k / 6f * PI.toFloat() + t * 0.6f + i) * u * 0.015f)
            pts += Pt(x0 + len * 1.04f, y - u * 0.025f)
            pts += Pt(x0 + len * 0.98f, y - u * 0.045f)
            pts += Pt(x0 + len * 0.93f, y - u * 0.03f)
            p.stroke(pts, u * 0.011f, "wind", 0.35f, 0.3f, 0.3f, seed = 800 + i)
        }
        for (i in 0 until 2) {
            val ph = (t * 0.1f + i * 0.5f) % 1f
            val x = -u * 0.05f + (w + u * 0.1f) * ph
            val y = h - hb * (1.2f + 0.6f * i) - sin(ph * 12f + i) * u * 0.03f
            p.blob(x, y, u * 0.016f, "leaf", 0.5f, irregular = 0.2f, seed = 850 + i, squash = 0.5f, angle = t * 3f + i)
        }
    }

    private fun rain(p: Painter, t: Float) {
        val rnd = Random(111)
        val len = u * 0.06f
        repeat(50) { i ->
            val x = w * rnd.nextFloat()
            val speed = h * (0.9f + 0.5f * rnd.nextFloat())
            val phase = rnd.nextFloat() * (h + len)
            val y = ((t * speed + phase) % (h + len)) - len
            val top = y.coerceAtLeast(0f)
            if (y + len <= top) return@repeat
            p.stroke(listOf(Pt(x + (y - top) * 0.2f, top), Pt(x - len * 0.2f, y + len)), u * 0.009f, "rain", 0.5f, 0.2f, 0.4f, seed = 900 + i)
        }
    }

    private fun snow(p: Painter, t: Float) {
        val rnd = Random(121)
        repeat(30) { i ->
            val x0 = w * rnd.nextFloat()
            val speed = h * (0.06f + 0.05f * rnd.nextFloat())
            val phase = rnd.nextFloat() * h
            val top = u * 0.03f
            val y = top + (t * speed + phase) % (h - top)
            val x = x0 + sin(t * (0.6f + rnd.nextFloat()) + i) * u * 0.02f
            val r = u * (0.006f + 0.006f * rnd.nextFloat())
            val edgeFade = min(1f, y / (h * 0.15f))
            p.blob(x, y, r, "snowflake", 0.7f * edgeFade, irregular = 0.15f, seed = 1000 + i)
        }
    }

    private fun petals(p: Painter, t: Float) {
        val rnd = Random(131)
        repeat(16) { i ->
            val x0 = w * rnd.nextFloat()
            val speed = h * (0.08f + 0.06f * rnd.nextFloat())
            val phase = rnd.nextFloat() * h
            val top = u * 0.03f
            val y = top + (t * speed + phase) % (h - top)
            val x = x0 + sin(t * (0.5f + rnd.nextFloat()) + i) * u * 0.04f
            val flip = 0.45f + 0.55f * abs(sin(t * 1.3f + i * 9f))
            val edgeFade = min(1f, y / (h * 0.15f))
            p.blob(x, y, u * 0.016f, "petal", 0.6f * edgeFade, irregular = 0.15f, seed = 1100 + i, squash = 0.55f * flip, angle = t * (0.8f + rnd.nextFloat()) + i)
        }
    }

    private fun fog(p: Painter, t: Float) {
        bands(p, t * 3f, listOf("fog" to 0.14f, "fog" to 0.16f), listOf(1.9f, 1.0f))
        for (i in 0 until 4) {
            val span = w + u * 0.6f
            val x = ((i * span / 4 + t * u * 0.03f) % span) - u * 0.3f
            p.blob(x, h - hb * (0.8f + 0.25f * (i % 2)), u * 0.16f, "fog", 0.12f, irregular = 0.25f, seed = 1200 + i, squash = 0.4f)
        }
    }
}
