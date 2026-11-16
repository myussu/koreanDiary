package com.jorge.koreandiary.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jorge.koreandiary.ai.ItemExplanation

/**
 * Lo que la IA explicó de una palabra o una gramática: significado, de qué partes se forma, cómo se
 * usa, cómo se conjuga y ejemplos. La usan la tarjeta de una sugerencia y «Más sobre esto» del
 * repaso rápido, así se ve igual en los dos lados.
 */
@Composable
fun ExplanationBody(e: ItemExplanation, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(e.meaning, style = MaterialTheme.typography.bodyLarge)
        e.structure?.let { Text("Partes: $it", style = MaterialTheme.typography.bodySmall, color = scheme.secondary) }
        e.usage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        e.conjugation?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.secondary) }
        e.examples.forEach { ex ->
            Column(Modifier.padding(top = 4.dp)) {
                Text(ex.korean, fontSize = 17.sp)
                ex.english?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
            }
        }
    }
}
