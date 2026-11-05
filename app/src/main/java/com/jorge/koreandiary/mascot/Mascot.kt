package com.jorge.koreandiary.mascot

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Mascota: una gotita de tinta (먹) dibujada con código. Pesa 0 KB, se ve nítida en cualquier
 * tamaño y toma los colores del papel: tinta oscura en claro, marfil sobre el papel de caligrafía.
 * Si hay una mascota importada ([custom]), se muestra esa imagen con la misma animación.
 */
@Composable
fun Mascot(
    mood: MascotMood,
    size: Dp,
    body: Color,
    features: Color,
    accent: Color,
    modifier: Modifier = Modifier,
    custom: ImageBitmap? = null,
) {
    val transition = rememberInfiniteTransition(label = "mascota")
    // Rebote suave; más lento si está dormida.
    val bob by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (mood == MascotMood.SLEEPY) 3200 else 1800), RepeatMode.Reverse),
        label = "rebote",
    )
    // Fase continua para los puntos de "pensando" y las Z de "dormida".
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "fase",
    )
    val blink = remember { Animatable(0f) }
    LaunchedEffect(mood) {
        if (mood == MascotMood.SLEEPY || mood == MascotMood.HAPPY) return@LaunchedEffect
        while (true) {
            delay(Random.nextLong(2_200, 5_000))
            blink.animateTo(1f, tween(70))
            blink.animateTo(0f, tween(110))
        }
    }

    if (custom != null) {
        Image(
            bitmap = custom,
            contentDescription = "Mascota",
            modifier = modifier.size(size).graphicsLayer { translationY = -bob * this.size.height * 0.04f },
        )
        return
    }

    Canvas(modifier.size(size)) {
        val w = this.size.width
        translate(top = -bob * w * 0.04f) {
            drawDrop(body, features)
            drawFace(mood, features, accent, blink.value, phase)
        }
        drawExtras(mood, body, phase, bob)
    }
}

private fun DrawScope.drawDrop(body: Color, features: Color) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(0.5f * w, 0.04f * h)
        cubicTo(0.55f * w, 0.22f * h, 0.80f * w, 0.38f * h, 0.80f * w, 0.64f * h)
        // Base redonda: medio círculo de la derecha a la izquierda pasando por abajo.
        arcTo(Rect(0.20f * w, 0.34f * h, 0.80f * w, 0.94f * h), 0f, 180f, false)
        cubicTo(0.20f * w, 0.38f * h, 0.45f * w, 0.22f * h, 0.5f * w, 0.04f * h)
        close()
    }
    drawPath(path, body)
    // Brillo de tinta fresca.
    drawArc(
        color = features.copy(alpha = 0.28f),
        startAngle = 200f,
        sweepAngle = 50f,
        useCenter = false,
        topLeft = Offset(0.27f * w, 0.42f * h),
        size = Size(0.30f * w, 0.30f * h),
        style = Stroke(width = 0.035f * w, cap = StrokeCap.Round),
    )
}

private fun DrawScope.drawFace(mood: MascotMood, features: Color, accent: Color, blink: Float, phase: Float) {
    val w = size.width
    val h = size.height
    val line = Stroke(width = 0.04f * w, cap = StrokeCap.Round)
    val left = Offset(0.39f * w, 0.62f * h)
    val right = Offset(0.61f * w, 0.62f * h)

    fun ovalEyes(center: Offset, rx: Float, ry: Float) {
        val open = (1f - blink).coerceAtLeast(0.08f)
        scale(scaleX = 1f, scaleY = open, pivot = center) {
            drawOval(features, topLeft = Offset(center.x - rx, center.y - ry), size = Size(rx * 2, ry * 2))
        }
    }

    fun smile(width: Float, depth: Float, y: Float) {
        val p = Path().apply {
            moveTo(0.5f * w - width / 2, y)
            quadraticTo(0.5f * w, y + depth, 0.5f * w + width / 2, y)
        }
        drawPath(p, features, style = line)
    }

    when (mood) {
        MascotMood.IDLE -> {
            listOf(left, right).forEach { ovalEyes(it, 0.032f * w, 0.048f * h) }
            smile(0.12f * w, 0.04f * h, 0.74f * h)
        }
        MascotMood.HAPPY -> {
            // Ojos cerrados de felicidad (^ ^), mejillas y sonrisa amplia.
            listOf(left, right).forEach { c ->
                val p = Path().apply {
                    moveTo(c.x - 0.045f * w, c.y + 0.012f * h)
                    quadraticTo(c.x, c.y - 0.05f * h, c.x + 0.045f * w, c.y + 0.012f * h)
                }
                drawPath(p, features, style = line)
            }
            drawCircle(accent.copy(alpha = 0.55f), radius = 0.04f * w, center = Offset(0.30f * w, 0.71f * h))
            drawCircle(accent.copy(alpha = 0.55f), radius = 0.04f * w, center = Offset(0.70f * w, 0.71f * h))
            smile(0.20f * w, 0.09f * h, 0.72f * h)
        }
        MascotMood.THINKING -> {
            // Mira hacia arriba a la derecha, boca de lado.
            listOf(left, right).forEach { ovalEyes(Offset(it.x + 0.02f * w, it.y - 0.035f * h), 0.03f * w, 0.045f * h) }
            drawLine(features, Offset(0.45f * w, 0.77f * h), Offset(0.57f * w, 0.755f * h), strokeWidth = 0.04f * w, cap = StrokeCap.Round)
        }
        MascotMood.SURPRISED -> {
            listOf(left, right).forEach { ovalEyes(it, 0.042f * w, 0.058f * h) }
            drawOval(features, topLeft = Offset(0.465f * w, 0.73f * h), size = Size(0.07f * w, 0.085f * h))
        }
        MascotMood.ENCOURAGE -> {
            // Guiño y sonrisa: "¡tú puedes!" (sin cejas: inclinadas se leían como enojo).
            val wink = Path().apply {
                moveTo(left.x - 0.045f * w, left.y + 0.012f * h)
                quadraticTo(left.x, left.y - 0.05f * h, left.x + 0.045f * w, left.y + 0.012f * h)
            }
            drawPath(wink, features, style = line)
            ovalEyes(right, 0.032f * w, 0.048f * h)
            smile(0.18f * w, 0.07f * h, 0.73f * h)
        }
        MascotMood.SLEEPY -> {
            listOf(left, right).forEach { c ->
                val p = Path().apply {
                    moveTo(c.x - 0.04f * w, c.y)
                    quadraticTo(c.x, c.y + 0.03f * h, c.x + 0.04f * w, c.y)
                }
                drawPath(p, features, style = line)
            }
            drawOval(features, topLeft = Offset(0.475f * w, 0.75f * h), size = Size(0.05f * w, 0.035f * h))
        }
    }
}

/** Detalles fuera de la gota: puntos al pensar, Z al dormir, destellos al sorprenderse. */
private fun DrawScope.drawExtras(mood: MascotMood, color: Color, phase: Float, bob: Float) {
    val w = size.width
    val h = size.height
    when (mood) {
        MascotMood.THINKING -> repeat(3) { i ->
            // Se encienden uno tras otro.
            val alpha = ((phase * 3f - i).coerceIn(0f, 1f))
            drawCircle(color.copy(alpha = 0.25f + 0.6f * alpha), radius = 0.035f * w, center = Offset((0.80f + i * 0.08f) * w, (0.30f - i * 0.07f) * h))
        }
        MascotMood.SLEEPY -> repeat(2) { i ->
            val t = (phase + i * 0.5f) % 1f
            val s = (0.06f + 0.03f * i) * w
            val x = (0.78f + i * 0.1f) * w
            val y = (0.32f - t * 0.2f - i * 0.08f) * h
            val z = Path().apply {
                moveTo(x, y)
                lineTo(x + s, y)
                lineTo(x, y + s)
                lineTo(x + s, y + s)
            }
            drawPath(z, color.copy(alpha = 1f - t), style = Stroke(width = 0.025f * w, cap = StrokeCap.Round))
        }
        MascotMood.SURPRISED, MascotMood.HAPPY -> {
            val a = 0.4f + 0.5f * bob
            listOf(Offset(0.83f * w, 0.18f * h), Offset(0.15f * w, 0.26f * h)).forEach { c ->
                val r = 0.05f * w
                drawLine(color.copy(alpha = a), Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = 0.025f * w, cap = StrokeCap.Round)
                drawLine(color.copy(alpha = a), Offset(c.x, c.y - r), Offset(c.x, c.y + r), strokeWidth = 0.025f * w, cap = StrokeCap.Round)
            }
        }
        else -> Unit
    }
}
