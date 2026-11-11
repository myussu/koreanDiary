package com.jorge.koreandiary.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Copia provisional de ui/Theme.kt y ui/Components.kt de Android (misma paleta y botones
 * cuadrados). Cuando las pantallas pasen a :shared con Compose Multiplatform, queda una sola.
 */

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1C1C1C),
    onPrimary = Color(0xFFF7F5F0),
    primaryContainer = Color(0xFFE1DCD3),
    onPrimaryContainer = Color(0xFF1C1C1C),
    secondary = Color(0xFFA4462F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3E3DC),
    onSecondaryContainer = Color(0xFF4A1D12),
    tertiary = Color(0xFF2F6B4A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDDEEE3),
    onTertiaryContainer = Color(0xFF0F3320),
    background = Color(0xFFF7F5F0),
    onBackground = Color(0xFF1C1C1C),
    surface = Color(0xFFF7F5F0),
    onSurface = Color(0xFF1C1C1C),
    surfaceVariant = Color(0xFFECE8E0),
    onSurfaceVariant = Color(0xFF5A5650),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2EFE9),
    surfaceContainer = Color(0xFFEDE9E2),
    surfaceContainerHigh = Color(0xFFE7E3DB),
    surfaceContainerHighest = Color(0xFFE1DCD3),
    outline = Color(0xFF1C1C1C),
    outlineVariant = Color(0xFFD6D1C7),
    error = Color(0xFF9B2C2C),
    onError = Color.White,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFE8E6E1),
    onPrimary = Color(0xFF0F0F0F),
    primaryContainer = Color(0xFF262626),
    onPrimaryContainer = Color(0xFFE8E6E1),
    secondary = Color(0xFFE29A82),
    onSecondary = Color(0xFF2A0E06),
    secondaryContainer = Color(0xFF3A231C),
    onSecondaryContainer = Color(0xFFF5D6CB),
    tertiary = Color(0xFF8FCFA8),
    onTertiary = Color(0xFF0A2416),
    tertiaryContainer = Color(0xFF183526),
    onTertiaryContainer = Color(0xFFC6EBD3),
    background = Color(0xFF0F0F0F),
    onBackground = Color(0xFFE8E6E1),
    surface = Color(0xFF0F0F0F),
    onSurface = Color(0xFFE8E6E1),
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFA8A49C),
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceContainerLow = Color(0xFF141414),
    surfaceContainer = Color(0xFF181818),
    surfaceContainerHigh = Color(0xFF1E1E1E),
    surfaceContainerHighest = Color(0xFF242424),
    outline = Color(0xFFE8E6E1),
    outlineVariant = Color(0xFF333333),
    error = Color(0xFFF2A0A0),
    onError = Color(0xFF3A0B0B),
)

val Square = RoundedCornerShape(0.dp)

private val SquareShapes = Shapes(
    extraSmall = Square,
    small = Square,
    medium = Square,
    large = Square,
    extraLarge = Square,
)

private val AppTypography = Typography().let { base ->
    base.copy(
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Light, letterSpacing = 0.5.sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Light),
    )
}

@Composable
fun KoreanDiaryTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        shapes = SquareShapes,
        typography = AppTypography,
        content = content,
    )
}

private val DefaultPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)

@Composable
fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = Button(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = Square,
    elevation = null,
    contentPadding = DefaultPadding,
    content = content,
)

@Composable
fun AppOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
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
        contentPadding = DefaultPadding,
        content = content,
    )
}

@Composable
fun AppTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = TextButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = Square,
    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    content = content,
)
