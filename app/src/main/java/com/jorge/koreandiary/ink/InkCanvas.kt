package com.jorge.koreandiary.ink

import java.util.IdentityHashMap
import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
 *
 * Son dos capas. Abajo, lo ya escrito (renglones, zona revisada y trazos terminados) en una capa que
 * el celular guarda como imagen y solo vuelve a pintar cuando cambia. Arriba, el trazo que estás
 * haciendo. Compose redibuja la ventana entera con cada cuadro de cualquier cosa (el lápiz a 120 por
 * segundo, la mascota, la escena viva): sin la capa, cada uno de esos cuadros volvía a pintar todos
 * los trazos de la página.
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
    strokeWidthPx: Float,
    /** Grosor variable según la presión del S Pen; si es false, trazo uniforme. */
    pressureEnabled: Boolean,
    onStrokeFinished: (InkStroke) -> Unit,
    onErase: (Offset) -> Unit,
    modifier: Modifier = Modifier,
    onInsertLine: (Int) -> Unit = {},
    scroll: InkScrollState = rememberInkScrollState(),
) {
    // El trazo en curso: una lista simple y un contador que avisa al dibujo. El lápiz manda cientos
    // de puntos por segundo; una lista de estado haría una copia por cada uno.
    val live = remember { ArrayList<InkPoint>() }
    var liveVersion by remember { mutableIntStateOf(0) }
    val currentOnStroke by rememberUpdatedState(onStrokeFinished)
    val currentOnErase by rememberUpdatedState(onErase)
    val currentOnInsertLine by rememberUpdatedState(onInsertLine)
    // La forma de cada trazo se calcula una sola vez: al agregar o borrar uno, solo se arma el nuevo.
    val shaper = remember { StrokeShaper() }
    val shapeCache = remember { ShapeCache(shaper) }
    val shapes = remember(strokes, strokeWidthPx, pressureEnabled) {
        shapeCache.shapesFor(strokes, strokeWidthPx, pressureEnabled)
    }
    val liveShape = remember { StrokeShape(Path()) }
    val uniformStyle = remember(strokeWidthPx) { Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round) }
    var viewport by remember { mutableFloatStateOf(0f) }
    val contentHeight = inkContentHeight(strokes, lineHeightPx, reviewedLines, viewport)

    SideEffect {
        scroll.lineHeight = lineHeightPx
        scroll.maxOffset = (contentHeight - viewport).coerceAtLeast(0f)
        if (scroll.offset > scroll.maxOffset) scroll.offset = scroll.maxOffset
    }

    Box(
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

                    // Dentro de la hoja: el gesto sigue llegando aunque el lápiz salga del lienzo, y un
                    // trazo que terminaba fuera del borde quedaba guardado con puntos fuera de la página.
                    fun page(p: Offset) = Offset(p.x.coerceIn(0f, size.width.toFloat()), (p.y + scroll.offset).coerceAtLeast(0f))
                    var erasing = tool == InkTool.ERASER || down.type == PointerType.Eraser
                    var scrolling = false
                    live.clear()
                    if (erasing) {
                        currentOnErase(page(down.position))
                    } else {
                        val p = page(down.position)
                        live.add(InkPoint(p.x, p.y, down.uptimeMillis, pressureOf(down.pressure, isPen)))
                    }
                    liveVersion++
                    down.consume()

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        // Dos dedos (cuando el dedo escribe): se descarta el trazo y se desplaza.
                        if (!isPen && pressed.size >= 2) {
                            scrolling = true
                            live.clear()
                            liveVersion++
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
                            // Los puntos intermedios no traen presión: se interpola entre el último punto y el actual.
                            val endPressure = pressureOf(change.pressure, isPen)
                            val startPressure = live.lastOrNull()?.p ?: endPressure
                            val history = change.historical
                            history.forEachIndexed { k, h ->
                                val p = page(h.position)
                                val f = (k + 1f) / (history.size + 1f)
                                live += InkPoint(p.x, p.y, h.uptimeMillis, startPressure + (endPressure - startPressure) * f)
                            }
                            val p = page(change.position)
                            live += InkPoint(p.x, p.y, change.uptimeMillis, endPressure)
                            liveVersion++
                        }
                        if (change.positionChange() != Offset.Zero) change.consume()
                        if (!change.pressed) break
                    }
                    if (!erasing && !scrolling && live.size >= 2) currentOnStroke(InkStroke(live.toList()))
                    live.clear()
                    liveVersion++
                }
            },
    ) {
        // Lo ya escrito. Capa guardada como imagen: se vuelve a pintar solo si cambian los trazos,
        // el desplazamiento o los colores, no con cada punto del lápiz.
        Canvas(Modifier.matchParentSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
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
                // Solo los trazos que tocan la parte visible.
                val top = offset
                val bottom = offset + size.height
                for (i in shapes.indices) {
                    val shape = shapes[i]
                    if (shape.bottom >= top && shape.top <= bottom) drawShape(shape, inkColor, uniformStyle)
                }
            }
            // Barra de desplazamiento fina a la derecha, solo si la página no cabe.
            if (contentHeight > size.height + 1f) {
                val barHeight = (size.height * size.height / contentHeight).coerceAtLeast(24f)
                val barTop = (size.height - barHeight) * (offset / (contentHeight - size.height))
                drawRect(lineColor, topLeft = Offset(size.width - 4f, barTop), size = Size(4f, barHeight))
            }
        }
        // El trazo en curso, encima. Con la misma forma que tendrá al terminar, armada en un Path que
        // se reutiliza (leer `liveVersion` es lo que lo redibuja cuando llegan puntos).
        Canvas(Modifier.matchParentSize()) {
            if (liveVersion >= 0 && live.size >= 2) {
                val offset = scroll.offset.coerceIn(0f, (contentHeight - size.height).coerceAtLeast(0f))
                shaper.build(live, strokeWidthPx, pressureEnabled, liveShape)
                translate(top = -offset) { drawShape(liveShape, inkColor, uniformStyle) }
            }
        }
    }
}

/** El dedo no mide presión real (suele dar siempre 1): se usa la media para que no salga grueso. */
private fun pressureOf(raw: Float, isPen: Boolean): Float = if (isPen) raw.coerceIn(0f, 1f) else DEFAULT_PRESSURE

/**
 * Forma lista para dibujar. Uniforme: un Path con trazo de ancho fijo. Con presión: el contorno
 * relleno del trazo (bordes desplazados según el grosor de cada punto) más dos círculos en los
 * extremos. La de un trazo terminado se calcula una vez, no en cada cuadro; la del trazo en curso
 * se rehace con cada punto sobre el mismo [path].
 */
private class StrokeShape(val path: Path) {
    var filled = false

    /** Los círculos de los extremos de un trazo con presión: centro y radio. */
    var startX = 0f
    var startY = 0f
    var startRadius = 0f
    var endX = 0f
    var endY = 0f
    var endRadius = 0f

    /** Alto que ocupa en la página (con margen para el grosor), para no dibujarlo si no se ve. */
    var top = Float.NEGATIVE_INFINITY
    var bottom = Float.POSITIVE_INFINITY
}

/**
 * Arma la forma de un trazo. Trabaja con arreglos de números que reutiliza (el borde derecho se
 * guarda mientras se recorre el izquierdo), sin crear una lista de puntos por trazo: el trazo en
 * curso pasa por aquí en cada cuadro. Se usa solo desde el hilo de la interfaz.
 */
private class StrokeShaper {
    private var right = FloatArray(256)

    fun build(points: List<InkPoint>, base: Float, pressure: Boolean, into: StrokeShape) {
        val path = into.path
        path.rewind()
        val n = points.size
        if (!pressure || n < 3) {
            into.filled = false
            if (n == 0) return
            val first = points[0]
            path.moveTo(first.x, first.y)
            for (i in 1 until n) {
                val prev = points[i - 1]
                val p = points[i]
                // Curva cuadrática entre puntos medios: trazo más suave sin costo extra.
                path.quadraticTo(prev.x, prev.y, (prev.x + p.x) / 2f, (prev.y + p.y) / 2f)
            }
            val last = points[n - 1]
            path.lineTo(last.x, last.y)
            return
        }
        into.filled = true
        if (right.size < n * 2) right = FloatArray(maxOf(n * 2, right.size * 2))
        val right = right
        var nx = 0f
        var ny = -1f
        var half = 0f
        for (i in 0 until n) {
            val a = points[maxOf(0, i - 1)]
            val b = points[minOf(n - 1, i + 1)]
            val p = points[i]
            // Grosor suavizado (media de 3) para que el borde no tiemble con el ruido del sensor.
            half = (pressureWidth(base, a.p) + pressureWidth(base, p.p) + pressureWidth(base, b.p)) / 6f
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = kotlin.math.sqrt(dx * dx + dy * dy)
            // Si el lápiz no se movió, se conserva la normal anterior.
            if (len > 0.01f) {
                nx = -dy / len
                ny = dx / len
            }
            val lx = p.x + nx * half
            val ly = p.y + ny * half
            if (i == 0) {
                path.moveTo(lx, ly)
                into.startX = p.x
                into.startY = p.y
                into.startRadius = half
            } else {
                path.lineTo(lx, ly)
            }
            right[i * 2] = p.x - nx * half
            right[i * 2 + 1] = p.y - ny * half
        }
        val last = points[n - 1]
        into.endX = last.x
        into.endY = last.y
        into.endRadius = half
        for (i in n - 1 downTo 0) path.lineTo(right[i * 2], right[i * 2 + 1])
        path.close()
    }
}

/**
 * Formas ya calculadas por trazo (por identidad, no por igualdad: comparar puntos costaría lo mismo
 * que rehacerlas). Se descartan las de trazos que ya no están y todas si cambia el grosor o la presión.
 */
private class ShapeCache(private val shaper: StrokeShaper) {
    private var base = -1f
    private var pressure = false
    private var cache = IdentityHashMap<InkStroke, StrokeShape>()

    fun shapesFor(strokes: List<InkStroke>, base: Float, pressure: Boolean): List<StrokeShape> {
        if (base != this.base || pressure != this.pressure) {
            cache = IdentityHashMap()
            this.base = base
            this.pressure = pressure
        }
        val next = IdentityHashMap<InkStroke, StrokeShape>(strokes.size)
        val result = strokes.map { s -> (cache[s] ?: shapeOf(s, base, pressure)).also { next[s] = it } }
        cache = next
        return result
    }

    private fun shapeOf(stroke: InkStroke, base: Float, pressure: Boolean): StrokeShape {
        val shape = StrokeShape(Path())
        shaper.build(stroke.points, base, pressure, shape)
        // Margen vertical: medio grosor máximo, con holgura para la presión.
        val margin = base * 2f
        shape.top = stroke.minY - margin
        shape.bottom = stroke.maxY + margin
        return shape
    }
}

private fun DrawScope.drawShape(shape: StrokeShape, color: Color, uniform: Stroke) {
    if (shape.filled) {
        drawPath(shape.path, color)
        drawCircle(color, radius = shape.startRadius, center = Offset(shape.startX, shape.startY))
        drawCircle(color, radius = shape.endRadius, center = Offset(shape.endX, shape.endY))
    } else {
        drawPath(shape.path, color, style = uniform)
    }
}

/**
 * Índices de trazos que pasan cerca de [point]. Primero se descartan por su caja (ya calculada) los
 * que están lejos: el borrador pregunta con cada movimiento y la página puede tener miles de puntos.
 */
fun strokesNear(strokes: List<InkStroke>, point: Offset, radius: Float): Set<Int> =
    strokes.indices.filter { i ->
        val s = strokes[i]
        point.x >= s.minX - radius && point.x <= s.maxX + radius &&
            point.y >= s.minY - radius && point.y <= s.maxY + radius &&
            s.points.any { hypot(it.x - point.x, it.y - point.y) <= radius }
    }.toSet()

/**
 * Dibuja la página entera (no solo lo visible) en un bitmap blanco con tinta oscura,
 * aunque uses modo oscuro: así la IA la lee mejor.
 */
fun renderPage(strokes: List<InkStroke>, width: Int, minHeight: Int, lineHeight: Float): Bitmap {
    val lastY = strokes.maxOfOrNull { it.maxY } ?: 0f
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
