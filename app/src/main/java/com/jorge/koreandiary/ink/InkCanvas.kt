package com.jorge.koreandiary.ink

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.hypot

enum class InkTool { PEN, ERASER }


/**
 * Lienzo de escritura. Con [stylusOnly] solo el S Pen dibuja (el dedo y la palma se ignoran).
 * Mantener presionado el botón lateral del S Pen borra temporalmente.
 */
@Composable
fun InkCanvas(
    strokes: List<InkStroke>,
    tool: InkTool,
    stylusOnly: Boolean,
    lineHeightPx: Float,
    reviewedLines: Int,
    inkColor: Color,
    lineColor: Color,
    reviewedTint: Color,
    onStrokeFinished: (InkStroke) -> Unit,
    onErase: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val live = remember { mutableStateListOf<InkPoint>() }
    val currentOnStroke by rememberUpdatedState(onStrokeFinished)
    val currentOnErase by rememberUpdatedState(onErase)
    val strokePaths = remember(strokes) { strokes.map { it.toPath() } }

    Canvas(
        modifier = modifier.pointerInput(tool, stylusOnly) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val isPen = down.type == PointerType.Stylus || down.type == PointerType.Eraser
                if (stylusOnly && !isPen) return@awaitEachGesture

                var erasing = tool == InkTool.ERASER || down.type == PointerType.Eraser
                live.clear()
                if (erasing) {
                    currentOnErase(down.position)
                } else {
                    live.add(InkPoint(down.position.x, down.position.y, down.uptimeMillis))
                }
                down.consume()

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (event.buttons.isSecondaryPressed) erasing = true
                    if (erasing) {
                        currentOnErase(change.position)
                    } else {
                        for (h in change.historical) live += InkPoint(h.position.x, h.position.y, h.uptimeMillis)
                        live += InkPoint(change.position.x, change.position.y, change.uptimeMillis)
                    }
                    if (change.positionChange() != Offset.Zero) change.consume()
                    if (!change.pressed) break
                }
                if (!erasing && live.size >= 2) currentOnStroke(InkStroke(live.toList()))
                live.clear()
            }
        },
    ) {
        if (reviewedLines > 0) {
            drawRect(reviewedTint, size = size.copy(height = (reviewedLines * lineHeightPx).coerceAtMost(size.height)))
        }
        var y = lineHeightPx
        while (y < size.height) {
            drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f)
            y += lineHeightPx
        }
        val style = Stroke(width = 4.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        strokePaths.forEach { drawPath(it, inkColor, style = style) }
        if (live.size >= 2) drawPath(InkStroke(live.toList()).toPath(), inkColor, style = style)
    }
}

private fun InkStroke.toPath(): Path = Path().apply {
    val first = points.firstOrNull() ?: return@apply
    moveTo(first.x, first.y)
    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val p = points[i]
        // Curva cuadrática entre puntos medios: trazo más suave sin costo extra.
        quadraticTo(prev.x, prev.y, (prev.x + p.x) / 2f, (prev.y + p.y) / 2f)
    }
    val last = points.last()
    lineTo(last.x, last.y)
}

/** Índices de trazos que pasan cerca de [point]. */
fun strokesNear(strokes: List<InkStroke>, point: Offset, radius: Float): Set<Int> =
    strokes.indices.filter { i ->
        strokes[i].points.any { hypot(it.x - point.x, it.y - point.y) <= radius }
    }.toSet()

/** Dibuja la página en un bitmap blanco con tinta oscura (aunque uses modo oscuro): así la IA la lee mejor. */
fun renderPage(strokes: List<InkStroke>, width: Int, height: Int, lineHeight: Float): Bitmap {
    val bmp = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(android.graphics.Color.WHITE)
    val linePaint = Paint().apply { color = 0xFFD5DCE6.toInt(); strokeWidth = 1.5f }
    var y = lineHeight
    while (y < height) {
        canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
        y += lineHeight
    }
    val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A1A2E.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    for (s in strokes) {
        val path = android.graphics.Path()
        s.points.firstOrNull()?.let { path.moveTo(it.x, it.y) }
        s.points.drop(1).forEach { path.lineTo(it.x, it.y) }
        canvas.drawPath(path, inkPaint)
    }
    return bmp
}
