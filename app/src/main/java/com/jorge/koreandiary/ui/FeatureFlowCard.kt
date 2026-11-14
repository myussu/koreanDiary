package com.jorge.koreandiary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jorge.koreandiary.features.FeatureFlow
import com.jorge.koreandiary.features.FlowAside
import com.jorge.koreandiary.features.FlowStep
import com.jorge.koreandiary.features.Platform

/**
 * Un recorrido de «Cómo se usa» dibujado: los pasos numerados unidos por una línea, cada uno con los
 * botones que usas dibujados como en la app y, si hay, un recuadro con lo que tienes a mano. La PC
 * tiene el mismo dibujo (`desktop/ui/FeatureFlowCard.kt`); si no funciona en esta versión, sale en gris.
 */
@Composable
fun FeatureFlowCard(flow: FeatureFlow, platform: Platform, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val missing = flow.missingLabel(platform)
    Column(modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text(flow.title.uppercase(), style = MaterialTheme.typography.labelMedium, color = scheme.secondary)
        HorizontalDivider(color = scheme.outlineVariant, modifier = Modifier.padding(top = 4.dp))
        Text(flow.where, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        if (missing != null) Text(missing, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        Column(Modifier.padding(top = 12.dp)) {
            flow.steps.forEachIndexed { i, step -> FlowStepRow(i + 1, step, last = i == flow.steps.lastIndex, dim = missing != null) }
        }
        flow.note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = RAIL, top = 2.dp))
        }
    }
}

/** Ancho de la columna de los números y la línea que los une. */
private val RAIL = 40.dp

@Composable
private fun FlowStepRow(number: Int, step: FlowStep, last: Boolean, dim: Boolean) {
    val scheme = MaterialTheme.colorScheme
    // La fila mide lo que su texto: así la línea llega justo hasta el número siguiente.
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.width(RAIL).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(28.dp).background(if (dim) scheme.outline else scheme.primary), contentAlignment = Alignment.Center) {
                Text(
                    "$number",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (dim) scheme.surface else scheme.onPrimary,
                )
            }
            if (!last) Box(Modifier.width(2.dp).weight(1f).background(scheme.outlineVariant))
        }
        Column(Modifier.weight(1f).padding(bottom = if (last) 10.dp else 22.dp)) {
            Text(
                step.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (dim) scheme.onSurfaceVariant else scheme.onSurface,
                modifier = Modifier.padding(top = 3.dp),
            )
            Text(step.text, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            if (step.buttons.isNotEmpty()) ButtonDrawings(step.buttons, Modifier.padding(top = 8.dp))
            step.aside?.let { Aside(it) }
        }
    }
}

@Composable
private fun Aside(aside: FlowAside) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.padding(top = 10.dp).fillMaxWidth()
            .border(1.dp, scheme.outlineVariant)
            .background(scheme.surfaceContainerLow)
            .padding(10.dp),
    ) {
        Text(aside.title.uppercase(), style = MaterialTheme.typography.labelSmall, color = scheme.secondary)
        if (aside.buttons.isNotEmpty()) ButtonDrawings(aside.buttons, Modifier.padding(top = 6.dp))
        aside.text?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)) }
    }
}

/** Los botones dibujados (no se tocan): cuadrados y con borde, como los de la app, para reconocerlos. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ButtonDrawings(labels: List<String>, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEach { label ->
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurface,
                modifier = Modifier.border(1.dp, scheme.outline).padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}
