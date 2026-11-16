package com.jorge.koreandiary.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

// Botones cuadrados, planos y sin sombra. Misma firma que los de Material para reemplazarlos 1:1.

private val Square = RoundedCornerShape(0.dp)
private val DefaultPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)

@Composable
fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = DefaultPadding,
    content: @Composable RowScope.() -> Unit,
) = Button(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = Square,
    elevation = null,
    contentPadding = contentPadding,
    content = content,
)

@Composable
fun AppOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = DefaultPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outline
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = Square,
        border = BorderStroke(1.dp, if (enabled) outline else outline.copy(alpha = 0.25f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
fun AppTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit,
) = TextButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = Square,
    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    contentPadding = contentPadding,
    content = content,
)

/** Opción seleccionable: rellena si está activa, con borde si no. */
@Composable
fun AppToggle(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    if (selected) AppButton(onClick = onClick, modifier = modifier, contentPadding = padding) { Text(label) }
    else AppOutlinedButton(onClick = onClick, modifier = modifier, contentPadding = padding) { Text(label) }
}

/** Etiqueta cuadrada (sugerencias, estados). */
@Composable
fun AppTag(
    text: String,
    selected: Boolean = false,
    accent: Boolean = false,
    fontSize: TextUnit = TextUnit.Unspecified,
    onClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val container = when {
        selected -> scheme.primary
        accent -> scheme.secondaryContainer
        else -> scheme.surfaceContainerHigh
    }
    val content = when {
        selected -> scheme.onPrimary
        accent -> scheme.onSecondaryContainer
        else -> scheme.onSurface
    }
    var modifier: Modifier = Modifier.border(1.dp, if (selected) scheme.primary else scheme.outlineVariant, Square)
    if (onClick != null) modifier = modifier.clickable(onClick = onClick)
    Surface(color = container, contentColor = content, shape = Square, modifier = modifier) {
        Text(text, fontSize = fontSize, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

/**
 * Una fila que se desplaza de lado y, si no cabe, se desvanece en el borde por donde sigue: así se
 * nota que hay más sin ocupar otra fila de alto (pruebas del 2026-11-15: «Rehacer», «Por revisar» y
 * «Hablar y escuchar» quedaban fuera sin pista). Para las pestañas, los grupos y la barra de la hoja.
 */
@Composable
fun ScrollRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(6.dp),
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    Row(
        modifier.fadingEdges(scroll).horizontalScroll(scroll),
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = verticalAlignment,
        content = content,
    )
}

/**
 * El degradado de [ScrollRow] y de los paneles que cortan el texto: borra el contenido hacia el
 * borde por donde sigue, con una máscara (no pinta ningún color, así sirve sobre cualquier fondo y en
 * modo oscuro). [vertical] para paneles que se desplazan hacia abajo (la frase de «¿Cómo digo?», las
 * correcciones). Va antes del modificador que desplaza.
 */
fun Modifier.fadingEdges(scroll: ScrollableState, vertical: Boolean = false, width: androidx.compose.ui.unit.Dp = 36.dp): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val length = if (vertical) size.height else size.width
        val edge = width.toPx().coerceAtMost(length / 3)
        // Negro y transparente son la máscara (BlendMode.DstOut), no colores que se vean.
        fun gradient(from: Color, to: Color, start: Float, end: Float) =
            if (vertical) Brush.verticalGradient(listOf(from, to), startY = start, endY = end)
            else Brush.horizontalGradient(listOf(from, to), startX = start, endX = end)
        if (scroll.canScrollForward) drawRect(gradient(Color.Transparent, Color.Black, length - edge, length), blendMode = BlendMode.DstOut)
        if (scroll.canScrollBackward) drawRect(gradient(Color.Black, Color.Transparent, 0f, edge), blendMode = BlendMode.DstOut)
    }

/**
 * Los `testTag` como `resource-id` dentro de un diálogo: es otra ventana y no hereda lo de la raíz
 * (F9 de las pruebas). Va en el contenido de cada diálogo que tenga etiquetas.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.testIds(): Modifier = semantics { testTagsAsResourceId = true }
