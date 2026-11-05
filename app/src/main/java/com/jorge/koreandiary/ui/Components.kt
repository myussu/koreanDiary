package com.jorge.koreandiary.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.Modifier
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
