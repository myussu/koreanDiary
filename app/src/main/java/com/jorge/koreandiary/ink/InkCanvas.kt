package com.jorge.koreandiary.ink

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.floor
import kotlin.math.hypot

enum class InkTool {
    PEN,
    ERASER,
    /** Tocar una línea inserta una vacía ahí y baja todo lo de abajo (como "insertar fila"). */
    INSERT_LINE,
}

/** Líneas vacías que siempre quedan debajo de lo último escrito o revisado. */
private const val EXTRA_LINES = 6

/**
 * Desplazamiento vertical del lienzo. La página crece hacia abajo: siempre hay [EXTRA_LINES]
 * libres debajo de lo escrito y de la zona revisada, para reescribir las correcciones.
 */
@Stable
class InkScrollState {
    var offset by mutableFloatStateOf(0f)
        internal set
    var maxOffset by mutableFloatStateOf(0f)
        internal set
    internal var lineHeight = 1f

    fun scrollBy(dy: Float) {
        offset = (offset + dy).coerceIn(0f, maxOffset)
    }

    /** Lleva la línea [line] cerca del borde superior (deja una línea de contexto encima). */
    fun scrollToLine(line: Int) {
        offset = ((line - 1).coerceAtLeast(0) * lineHeight).coerceIn(0f, maxOffset)
    }
}

@Composable
fun rememberInkScrollState() = remember { InkScrollState() }

/** Alto de la página según lo escrito, la zona revisada y el alto visible. */
fun inkContentHeight(strokes: List<InkStroke>, lineHeight: Float, reviewedLines: Int, viewport: Float): Float {
    val lastInkLine = strokes.maxOfOrNull { it.lineIndex(lineHeight) } ?: -1
    val lines = maxOf(lastInkLine + 1, reviewedLines) + EXTRA_LINES
    return maxOf(viewport, lines * lineHeight)
}

/**
 * Lienzo de escritura con desplazamiento vertical.
 * - Con [stylusOnly], el S Pen escribe y el dedo desplaza (la palma no dibuja).
 * - Sin [stylusOnly], el dedo escribe y se desplaza con dos dedos.
 * - Mantener presionado el botón lateral del S Pen borra temporalmente.
 * Los trazos se guardan en coordenadas de la página (no de la pantalla), así no cambian al desplazar.
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
    onInsertLine: (Int) -> Unit = {},
    scroll: InkScrollState = rememberInkScrollState(),
) {
    val live = remember { mutableStateListOf<InkPoint>() }
    val currentOnStroke by rememberUpdatedState(onStrokeFinished)
    val currentOnErase by rememberUpdatedState(onErase)
    val currentOnInsertLine by rememberUpdatedState(onInsertLine)
    val strokePaths = remember(strokes) { strokes.map { it.toPath() } }
    var viewport by remember { mutableFloatStateOf(0f) }
    val contentHeight = inkContentHeight(strokes, lineHeightPx, reviewedLines, viewport)

    SideEffect {
        scroll.lineHeight = lineHeightPx
        scroll.maxOffset = (contentHeight - viewport).coerceAtLeast(0f)
        if (scroll.offset > scroll.maxOffset) scroll.offset = scroll.maxOffset
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { viewport = it.height.toFloat() }
            .pointerInput(tool, stylusOnly, lineHeightPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val isPen = down.type == PointerType.Stylus || down.type == PointerType.Eraser

                    // Insertar línea: un toque (con lápiz o dedo) sobre la línea elegida.
                    if (tool == InkTool.INSERT_LINE) {
                        val line = floor((down.position.y + scroll.offset) / lineHeightPx).toInt()
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                            if (event.changes.none { it.pressed }) break
                        }
                        currentOnInsertLine(line)
                        return@awaitEachGesture
                    }

                    // Dedo con "solo S Pen": desplaza la página.
                    if (stylusOnly && !isPen) {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            scroll.scrollBy(-change.positionChange().y)
                            change.consume()
                        }
                        return@awaitEachGesture
                    }

                    fun page(p: Offset) = Offset(p.x, p.y + scroll.offset)
                    var erasing = tool == InkTool.ERASER || down.type == PointerType.Eraser
                    var scrolling = false
                    live.clear()
                    if (erasing) {
                        currentOnErase(page(down.position))
                    } else {
                        val p = page(down.position)
                        live.add(InkPoint(p.x, p.y, down.uptimeMillis))
                    }
                    down.consume()

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        // Dos dedos (cuando el dedo escribe): se descarta el trazo y se desplaza.
                        if (!isPen && pressed.size >= 2) {
                            scrolling = true
                            live.clear()
                        }
                        if (scrolling) {
                            if (pressed.isEmpty()) break
                            scroll.scrollBy(-pressed.map { it.positionChange().y }.average().toFloat())
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (event.buttons.isSecondaryPressed) erasing = true
                        if (erasing) {
                            currentOnErase(page(change.position))
                        } else {
                            for (h in change.historical) {
                                val p = page(h.position)
                                live += InkPoint(p.x, p.y, h.uptimeMillis)
                            }
                            val p = page(change.position)
                            live += InkPoint(p.x, p.y, change.uptimeMillis)
                        }
                        if (change.positionChange() != Offset.Zero) change.consume()
                        if (!change.pressed) break
                    }
                    if (!erasing && !scrolling && live.size >= 2) currentOnStroke(InkStroke(live.toList()))
                    live.clear()
                }
            },
    ) {
        val offset = scroll.offset.coerceIn(0f, (contentHeight - size.height).coerceAtLeast(0f))
        translate(top = -offset) {
            if (reviewedLines > 0) {
                drawRect(reviewedTint, size = size.copy(height = reviewedLines * lineHeightPx))
            }
            // Solo las líneas visibles.
            var y = (floor(offset / lineHeightPx) + 1) * lineHeightPx
            while (y < offset + size.height) {
                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f)
                y += lineHeightPx
            }
            val style = Stroke(width = 4.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            strokePaths.forEach { drawPath(it, inkColor, style = style) }
            if (live.size >= 2) drawPath(InkStroke(live.toList()).toPath(), inkColor, style = style)
        }
        // Barra de desplazamiento fina a la derecha, solo si la página no cabe.
        if (contentHeight > size.height + 1f) {
            val barHeight = (size.height * size.height / contentHeight).coerceAtLeast(24f)
            val barTop = (size.height - barHeight) * (offset / (contentHeight - size.height))
            drawRect(lineColor, topLeft = Offset(size.width - 4f, barTop), size = Size(4f, barHeight))
        }
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

/**
 * Dibuja la página entera (no solo lo visible) en un bitmap blanco con tinta oscura,
 * aunque uses modo oscuro: así la IA la lee mejor.
 */
fun renderPage(strokes: List<InkStroke>, width: Int, minHeight: Int, lineHeight: Float): Bitmap {
    val lastY = strokes.maxOfOrNull { s -> s.points.maxOfOrNull { it.y } ?: 0f } ?: 0f
    val height = maxOf(minHeight.toFloat(), lastY + lineHeight).toInt()
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
