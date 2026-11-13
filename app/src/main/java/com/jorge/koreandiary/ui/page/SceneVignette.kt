package com.jorge.koreandiary.ui.page

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import com.jorge.koreandiary.scene.SceneElement
import com.jorge.koreandiary.scene.SceneMotion
import com.jorge.koreandiary.scene.SceneZone
import com.jorge.koreandiary.ui.ScenePalette
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Escena viva: los bordes de la página se llenan con lo que escribes, elemento por elemento.
 * Va DETRÁS del lienzo (la tinta queda siempre encima, con su contraste) y vive junto a los bordes:
 * el suelo abajo, el horizonte a los lados, el cielo arriba y el aire por todo el marco. El centro
 * solo cambia de tono muy apenas. Cada elemento entra de a poco y se mueve solo si el sistema tiene
 * las animaciones encendidas.
 */
@Composable
fun SceneVignette(elements: List<SceneElement>, dark: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val motionOn = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    // Entrada suave de cada elemento nuevo (los que ya estaban no vuelven a aparecer).
    val appear = remember { mutableStateMapOf<String, Animatable<Float, AnimationVector1D>>() }
    LaunchedEffect(elements) {
        val ids = elements.map { it.id }.toSet()
        (appear.keys - ids).forEach { appear.remove(it) }
        elements.forEach { e ->
            if (e.id !in appear) {
                val a = Animatable(if (motionOn) 0f else 1f)
                appear[e.id] = a
                launch { a.animateTo(1f, tween(1_400)) }
            }
        }
    }
    val time: State<Float> = if (motionOn) {
        rememberInfiniteTransition(label = "escena viva").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(CYCLE_MS, easing = LinearEasing)),
            label = "tiempo",
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    Canvas(modifier.fillMaxSize().clipToBounds()) {
        if (elements.isEmpty()) return@Canvas
        val t = time.value
        val edge = min(size.width, size.height) * 0.12f
        val glow = if (dark) 0.40f else 0.48f
        fun alphaOf(e: SceneElement) = appear[e.id]?.value ?: 1f

        // Un tono apenas perceptible del primer elemento en toda la hoja.
        val first = elements.first()
        drawRect(ScenePalette.color(first.palette, dark).copy(alpha = (if (dark) 0.05f else 0.035f) * alphaOf(first)))

        for (zone in SceneZone.entries) {
            val inZone = elements.filter { it.zone == zone }
            inZone.forEachIndexed { i, e ->
                val a = alphaOf(e)
                val color = ScenePalette.color(e.palette, dark)
                val anchor = anchor(zone, i, inZone.size, size)
                val pulse = if (e.motion == SceneMotion.PULSE) 1f + 0.08f * sin(TAU * t * 4f) else 1f
                val radius = edge * 2.2f * pulse
                drawCircle(
                    brush = Brush.radialGradient(listOf(color.copy(alpha = glow * a), Color.Transparent), center = anchor, radius = radius),
                    radius = radius,
                    center = anchor,
                )
                when {
                    e.id == "rainbow" -> drawRainbow(a, edge, dark)
                    e.id == "fog" -> drawFog(color, a, edge, t)
                    else -> when (e.motion) {
                        SceneMotion.WAVE -> drawWaves(color, a, anchor, edge, t)
                        SceneMotion.SWAY -> drawBlades(e, color, a, zone, anchor, edge, inZone.size, t)
                        SceneMotion.DRIFT -> if (zone == SceneZone.SKY) drawClouds(color, a, edge, t) else drawWind(color, a, edge, t)
                        SceneMotion.FALL -> drawFalling(e, color, a, edge, t)
                        SceneMotion.TWINKLE -> drawTwinkles(color, a, anchor, edge, t, zone)
                        SceneMotion.PULSE, SceneMotion.NONE -> Unit
                    }
                }
            }
        }
    }
}

/** Un ciclo de animación; los movimientos son múltiplos enteros para que el ciclo cierre sin saltos. */
private const val CYCLE_MS = 12_000
private const val TAU = (2 * PI).toFloat()

private fun frac(x: Float) = x - floor(x)

/** Dónde nace el resplandor de cada elemento según su zona y cuántos hay en ella. */
private fun anchor(zone: SceneZone, i: Int, n: Int, size: Size): Offset = when (zone) {
    SceneZone.GROUND -> Offset(size.width * (i + 0.5f) / n, size.height)
    SceneZone.SKY -> Offset(size.width * (i + 0.5f) / n, 0f)
    SceneZone.HORIZON -> {
        val rows = ceil(n / 2f)
        val row = i / 2
        Offset(if (i % 2 == 0) 0f else size.width, size.height * (0.45f + 0.35f * (row + 0.5f) / rows))
    }
    SceneZone.AIR -> when (i % 4) {
        0 -> Offset(0f, 0f)
        1 -> Offset(size.width, size.height)
        2 -> Offset(size.width, 0f)
        else -> Offset(0f, size.height)
    }
}

/** Olas: tres líneas onduladas junto al borde del agua. */
private fun DrawScope.drawWaves(color: Color, a: Float, anchor: Offset, edge: Float, t: Float) {
    val inward = if (anchor.x < size.width / 2) 1f else -1f
    val length = edge * 1.4f
    for (k in 0 until 3) {
        val y = anchor.y + (k - 1) * edge * 0.35f
        val path = Path()
        var step = 0f
        while (step <= length) {
            val x = anchor.x + inward * step
            val yy = y + sin(step / (edge * 0.6f) * TAU + TAU * t * 6f + k) * edge * 0.06f
            if (step == 0f) path.moveTo(x, yy) else path.lineTo(x, yy)
            step += 6f
        }
        drawPath(path, color.copy(alpha = 0.55f * a * (1f - k * 0.2f)), style = Stroke(width = edge * 0.035f, cap = StrokeCap.Round))
    }
}

/** Pasto, trigo, flores y árboles: hojas que se mecen desde el borde. */
private fun DrawScope.drawBlades(e: SceneElement, color: Color, a: Float, zone: SceneZone, anchor: Offset, edge: Float, n: Int, t: Float) {
    val stroke = edge * 0.03f
    if (zone == SceneZone.GROUND) {
        val span = size.width / n
        val count = (span / (edge * 0.22f)).toInt().coerceIn(4, 40)
        for (j in 0 until count) {
            val x = anchor.x - span / 2 + span * (j + 0.5f) / count
            val h = edge * (0.45f + 0.45f * frac(j * 0.618f))
            val sway = sin(TAU * t * 3f + j * 0.7f) * edge * 0.08f
            val tip = Offset(x + sway, size.height - h)
            drawLine(color.copy(alpha = 0.6f * a), Offset(x, size.height), tip, strokeWidth = stroke, cap = StrokeCap.Round)
            when (e.id) {
                "wheat" -> drawCircle(color.copy(alpha = 0.75f * a), radius = stroke * 1.8f, center = tip)
                "flowers" -> if (j % 3 == 0) drawCircle(color.copy(alpha = 0.85f * a), radius = stroke * 2.2f, center = tip)
            }
        }
    } else {
        // Árboles al costado: troncos que suben desde abajo del resplandor.
        val inward = if (anchor.x < size.width / 2) 1f else -1f
        for (j in 0 until 6) {
            val x = anchor.x + inward * edge * (0.15f + 0.13f * j)
            val base = anchor.y + edge * 0.9f
            val h = edge * (0.7f + 0.5f * frac(j * 0.618f))
            val sway = sin(TAU * t * 2f + j) * edge * 0.05f
            drawLine(color.copy(alpha = 0.55f * a), Offset(x, base), Offset(x + sway, base - h), strokeWidth = stroke * 1.4f, cap = StrokeCap.Round)
        }
    }
}

/** Viento: trazos curvos que cruzan arriba y abajo. */
private fun DrawScope.drawWind(color: Color, a: Float, edge: Float, t: Float) {
    val length = size.width * 0.25f
    for (k in 0 until 4) {
        val y = if (k % 2 == 0) edge * (0.3f + 0.2f * k) else size.height - edge * (0.3f + 0.2f * k)
        val x0 = frac(t * 2f + k * 0.27f) * (size.width + 2 * length) - length
        val path = Path().apply {
            moveTo(x0, y)
            quadraticTo(x0 + length / 2, y - edge * 0.18f, x0 + length, y)
        }
        drawPath(path, color.copy(alpha = 0.5f * a), style = Stroke(width = edge * 0.025f, cap = StrokeCap.Round))
    }
}

/** Nubes: óvalos suaves que pasan por arriba. */
private fun DrawScope.drawClouds(color: Color, a: Float, edge: Float, t: Float) {
    val w = size.width * 0.28f
    val h = edge * 0.55f
    for (k in 0 until 2) {
        val x = frac(t + k * 0.5f) * (size.width + w) - w
        val y = edge * (0.15f + 0.25f * k)
        drawOval(
            brush = Brush.radialGradient(listOf(color.copy(alpha = 0.6f * a), Color.Transparent), center = Offset(x + w / 2, y + h / 2), radius = w / 2),
            topLeft = Offset(x, y),
            size = Size(w, h),
        )
    }
}

/** Niebla: bandas suaves arriba y abajo que respiran. */
private fun DrawScope.drawFog(color: Color, a: Float, edge: Float, t: Float) {
    val breath = 0.75f + 0.25f * sin(TAU * t * 2f)
    val band = edge * 1.6f
    drawRect(Brush.verticalGradient(listOf(color.copy(alpha = 0.4f * a * breath), Color.Transparent), startY = 0f, endY = band))
    drawRect(
        Brush.verticalGradient(listOf(Color.Transparent, color.copy(alpha = 0.4f * a * breath)), startY = size.height - band, endY = size.height),
        topLeft = Offset(0f, size.height - band),
        size = Size(size.width, band),
    )
}

/** Lluvia, nieve y pétalos: caen por los costados, nunca por el medio de la hoja. */
private fun DrawScope.drawFalling(e: SceneElement, color: Color, a: Float, edge: Float, t: Float) {
    for (p in 0 until 14) {
        val left = p % 2 == 0
        val offset = edge * (0.15f + 0.7f * frac(p * 0.37f))
        val x = if (left) offset else size.width - offset
        val speed = (1 + p % 3).toFloat()
        val y = frac(t * speed * 2f + p * 0.13f) * (size.height + edge) - edge / 2
        when (e.id) {
            "rain" -> drawLine(
                color.copy(alpha = 0.55f * a), Offset(x, y), Offset(x - edge * 0.05f, y + edge * 0.22f),
                strokeWidth = edge * 0.02f, cap = StrokeCap.Round,
            )
            "petals" -> drawOval(
                color.copy(alpha = 0.7f * a),
                topLeft = Offset(x + sin(TAU * t * 4f + p) * edge * 0.1f, y),
                size = Size(edge * 0.09f, edge * 0.055f),
            )
            else -> drawCircle(color.copy(alpha = 0.8f * a), radius = edge * 0.035f, center = Offset(x + sin(TAU * t * 3f + p) * edge * 0.06f, y))
        }
    }
}

/** Estrellas y brillos: puntos que titilan cerca de su resplandor. */
private fun DrawScope.drawTwinkles(color: Color, a: Float, anchor: Offset, edge: Float, t: Float, zone: SceneZone) {
    for (j in 0 until 8) {
        val dx = (frac(j * 0.618f) - 0.5f) * edge * 3f
        val dy = frac(j * 0.382f) * edge * 1.3f
        val x = (anchor.x + dx).coerceIn(edge * 0.1f, size.width - edge * 0.1f)
        // Siempre hacia adentro desde el borde donde nace.
        val y = if (anchor.y <= 0f || zone == SceneZone.SKY) dy + edge * 0.1f else if (anchor.y >= size.height) size.height - dy - edge * 0.1f else anchor.y + dy - edge * 0.65f
        val tw = 0.3f + 0.7f * abs(sin(TAU * (t * 6f + j * 0.31f)))
        drawCircle(color.copy(alpha = a * tw), radius = edge * 0.03f, center = Offset(x, y))
    }
}

/** Arcoíris: tres arcos tenues arriba, de borde a borde. */
private fun DrawScope.drawRainbow(a: Float, edge: Float, dark: Boolean) {
    val bands = listOf("sunset", "sun", "sky", "rainbow")
    bands.forEachIndexed { k, key ->
        val inset = k * edge * 0.12f
        drawArc(
            color = ScenePalette.color(key, dark).copy(alpha = 0.35f * a),
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(-edge + inset, -edge * 0.2f + inset),
            size = Size(size.width + 2 * edge - 2 * inset, edge * 2.4f - 2 * inset),
            style = Stroke(width = edge * 0.08f),
        )
    }
}
