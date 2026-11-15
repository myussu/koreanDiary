package com.jorge.koreandiary.scene.art

import com.jorge.koreandiary.scene.SceneElement
import com.jorge.koreandiary.scene.SceneMotion
import com.jorge.koreandiary.scene.SceneZone
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Qué tan grande es un dibujo nuevo, en unidades de la franja (lado de su caja). */
enum class DrawingSize(val scale: Float) { SMALL(0.26f), MEDIUM(0.36f), LARGE(0.5f) }

/** Cómo se mueve el dibujo entero. */
enum class DrawingMotion { NONE, SWAY, BOB, DRIFT }

/**
 * Cómo se mueve una parte: SWAY se mece alrededor de su primer punto (una cola, una rama), FLAP
 * aletea alrededor de su primer punto, en espejo según hacia qué lado apunta (las dos alas a la
 * vez), BOB sube y baja, TWINKLE titila (una luz).
 */
enum class PartMotion { NONE, SWAY, FLAP, BOB, TWINKLE }

/** Un color del dibujo, para papel claro y oscuro. */
class DrawingColor(val key: String, val light: String, val dark: String) {
    /** La clave que entiende la paleta de cada app: los dos colores juntos. */
    val markKey: String get() = "$light|$dark"
}

/**
 * Una forma del dibujo, en una caja de 0 a 100 (y hacia abajo; y = 100 es donde se apoya).
 * Aguada (zona de color), toque redondo o pincelada.
 */
sealed class DrawingShape(val color: String, val alpha: Float, val move: PartMotion) {
    class Wash(val points: List<Pt>, color: String, alpha: Float, move: PartMotion = PartMotion.NONE) : DrawingShape(color, alpha, move)

    class Blob(
        val x: Float,
        val y: Float,
        val r: Float,
        val squash: Float,
        val angle: Float,
        color: String,
        alpha: Float,
        move: PartMotion = PartMotion.NONE,
    ) : DrawingShape(color, alpha, move)

    class Stroke(val points: List<Pt>, val width: Float, color: String, alpha: Float, move: PartMotion = PartMotion.NONE) : DrawingShape(color, alpha, move)
}

/**
 * Un dibujo de pinceladas en una caja (un gato, un faro), con sus palabras coreanas: aparece al
 * escribirlas, igual que los 27 dibujos de código de la app. Puede ser de fábrica (viene en los assets
 * de la app, id `f_…`) o hecho por Claude para algo que la escena no sabía pintar (id `x_…`).
 */
class CustomDrawing(
    val id: String,
    val name: String,
    val keys: List<String>,
    val zone: SceneZone,
    val size: DrawingSize,
    val motion: DrawingMotion,
    val count: Int,
    val colors: List<DrawingColor>,
    val shapes: List<DrawingShape>,
) {
    private val colorKeys = colors.associate { it.key to it.markKey }

    /** El elemento de la escena que lo representa (mismo id), para la escena viva y la revisión. */
    val element: SceneElement by lazy {
        val motion = when (motion) {
            DrawingMotion.NONE -> SceneMotion.NONE
            DrawingMotion.SWAY -> SceneMotion.SWAY
            DrawingMotion.BOB -> SceneMotion.PULSE
            DrawingMotion.DRIFT -> SceneMotion.DRIFT
        }
        SceneElement(id, name, zone, PALETTE, motion)
    }

    /** ¿Viene con la app (no lo hizo Claude para ti)? */
    val isFactory: Boolean get() = id.startsWith(FACTORY_PREFIX)

    companion object {
        /** Los ids de los dibujos que hace Claude empiezan así: nunca chocan con los de la app. */
        const val PREFIX = "x_"

        /** Y los de los dibujos de fábrica, así: tampoco chocan con los que hizo Claude. */
        const val FACTORY_PREFIX = "f_"

        /** Clave de paleta de estos elementos: sus colores vienen en cada mancha. */
        const val PALETTE = "custom"

        fun isCustomId(id: String): Boolean =
            (id.startsWith(PREFIX) && id.length > PREFIX.length) || (id.startsWith(FACTORY_PREFIX) && id.length > FACTORY_PREFIX.length)
    }

    /** En qué capa va: con lo lejano, con el agua y los árboles, en el suelo o en el aire. */
    val layer: Int
        get() = when (zone) {
            SceneZone.SKY -> 1
            SceneZone.HORIZON -> 2
            SceneZone.GROUND -> 3
            SceneZone.AIR -> 4
        }

    /**
     * Pinta el dibujo en la franja [s]. [slot] es su lugar entre los dibujos nuevos de la misma zona
     * (para que dos no caigan en el mismo sitio).
     */
    internal fun paint(s: Stage, p: Painter, t: Float, slot: Int) {
        val seed = id.hashCode()
        val copies = count.coerceIn(1, 5)
        for (k in 0 until copies) {
            val rnd = Random(seed + k * 101)
            val scale = if (copies == 1) 1f else 0.7f + 0.3f * rnd.nextFloat()
            var side = size.scale * s.u * scale
            // Base (centro de abajo de la caja) según dónde vive.
            var baseY = when (zone) {
                SceneZone.GROUND -> s.h - s.gs * 0.45f
                SceneZone.HORIZON -> s.h - s.hb * 0.45f
                SceneZone.SKY -> s.h - s.hb * (1.7f + 0.5f * rnd.nextFloat())
                SceneZone.AIR -> s.h - s.hb * (0.6f + 1.4f * rnd.nextFloat())
            }
            // Nada sube más que el techo de la franja: si no entra, se achica.
            val ceiling = s.h - s.hb * 2.9f
            if (baseY - side < ceiling) side = (baseY - ceiling).coerceAtLeast(s.u * 0.05f)
            // Lugares de izquierda a centro primero: a la derecha suele estar el bosque.
            val slots = floatArrayOf(0.4f, 0.2f, 0.6f, 0.8f, 0.5f)
            var baseX = if (copies == 1) s.w * slots[slot % slots.size] else s.w * (0.12f + 0.76f * ((k + 0.5f) / copies)) + (rnd.nextFloat() - 0.5f) * s.w * 0.08f
            var angle = 0f
            when (motion) {
                DrawingMotion.NONE -> Unit
                DrawingMotion.SWAY -> angle = 0.035f * sin(t * 0.6f + k)
                DrawingMotion.BOB -> baseY += side * 0.03f * sin(t * 1.1f + k * 1.7f)
                DrawingMotion.DRIFT -> {
                    val span = s.w + side * 2
                    val speed = s.u * (0.018f + 0.01f * rnd.nextFloat())
                    baseX = -side + ((baseX + side + t * speed) % span + span) % span
                    baseY += side * 0.05f * sin(t * 0.9f + k * 2.1f)
                }
            }
            val ca = cos(angle)
            val sa = sin(angle)
            // Caja → franja: centro de abajo en la base, girado si se mece.
            fun place(x: Float, y: Float): Pt {
                val dx = (x - 50f) / 100f * side
                val dy = (y - 100f) / 100f * side
                return Pt(baseX + dx * ca - dy * sa, baseY + dx * sa + dy * ca)
            }
            paintShapes(p, t, k, side, angle) { x, y -> place(x, y) }
        }
    }

    /**
     * El dibujo en una caja de [side] píxeles con la esquina de arriba a la izquierda en ([left], [top]),
     * sin moverse por la franja (solo sus partes, en el segundo [t]). Para la ventana de dibujos y para
     * la imagen que Claude mira al revisar su dibujo.
     */
    fun boxMarks(side: Float, left: Float = 0f, top: Float = 0f, t: Float = 0f): List<Mark> {
        val p = Painter()
        paintShapes(p, t, 0, side, 0f) { x, y -> Pt(left + x / 100f * side, top + y / 100f * side) }
        return p.marks
    }

    /** Las formas, de la caja a donde diga [place]; [k] es la copia (desfasa los movimientos). */
    private fun paintShapes(p: Painter, t: Float, k: Int, side: Float, angle: Float, place: (Float, Float) -> Pt) {
        val seed = id.hashCode()
        shapes.forEachIndexed { i, shape ->
            val color = colorKeys[shape.color] ?: return@forEachIndexed
            val phase = i * 1.3f + k * 0.9f
            var alpha = shape.alpha
            if (shape.move == PartMotion.TWINKLE) alpha *= 0.65f + 0.35f * sin(t * 2.2f + phase)
            fun moved(points: List<Pt>): List<Pt> {
                if (points.isEmpty()) return points
                val pivot = points.first()
                return when (shape.move) {
                    PartMotion.SWAY, PartMotion.FLAP -> {
                        val flap = shape.move == PartMotion.FLAP
                        val dir = if (flap && points.last().x < pivot.x) -1f else 1f
                        val a = if (flap) dir * 0.35f * sin(t * 5f + k * 0.7f) else 0.12f * sin(t * 1.4f + phase)
                        val c = cos(a)
                        val sn = sin(a)
                        points.map { q ->
                            val dx = q.x - pivot.x
                            val dy = q.y - pivot.y
                            Pt(pivot.x + dx * c - dy * sn, pivot.y + dx * sn + dy * c)
                        }
                    }
                    PartMotion.BOB -> points.map { Pt(it.x, it.y + 2f * sin(t * 1.6f + phase)) }
                    else -> points
                }
            }
            when (shape) {
                is DrawingShape.Wash -> p.wash(moved(shape.points).map { place(it.x, it.y) }, color, alpha)
                is DrawingShape.Stroke -> p.stroke(
                    moved(shape.points).map { place(it.x, it.y) },
                    shape.width / 100f * side,
                    color,
                    alpha,
                    taperIn = 0.12f,
                    taperOut = 0.3f,
                    wobble = 0.1f,
                    seed = seed + i,
                )
                is DrawingShape.Blob -> {
                    val c = moved(listOf(Pt(shape.x, shape.y))).first()
                    val at = place(c.x, c.y)
                    p.blob(at.x, at.y, shape.r / 100f * side, color, alpha, irregular = 0.18f, seed = seed + i, squash = shape.squash, angle = shape.angle + angle)
                }
            }
        }
    }
}
