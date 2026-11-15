package com.jorge.koreandiary.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jorge.koreandiary.ai.ReviewLevels

/** «Bien 3 · Corregir 1 · Más natural 1»: el resumen de una revisión en sus tres niveles. */
@Composable
fun ReviewLevelsLine(levels: ReviewLevels, modifier: Modifier = Modifier) {
    if (levels.total == 0) return
    val scheme = MaterialTheme.colorScheme
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Level("Bien", levels.ok, scheme.tertiary)
        Level("Corregir", levels.fix, scheme.error)
        Level("Más natural", levels.natural, scheme.secondary)
    }
}

@Composable
private fun Level(label: String, count: Int, color: androidx.compose.ui.graphics.Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$count", color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
    }
}
