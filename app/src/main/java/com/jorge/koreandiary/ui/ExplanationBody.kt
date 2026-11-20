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
import com.jorge.koreandiary.ai.WordParts

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
        e.structure?.let { PartsLine(e.form, it) }
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

/**
 * «Partes: …», el desglose de la IA. Si las partes no forman [form] letra por letra
 * ([WordParts.fits]), sale como dudoso con el porqué, sin esconderlo: puede ser una palabra
 * irregular o un desglose inventado (pedido por Jorge el 2026-11-20).
 */
@Composable
fun PartsLine(form: String, parts: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val doubtful = WordParts.fits(form, parts) == false
    Column(modifier) {
        Text(
            (if (doubtful) "Partes (dudoso): " else "Partes: ") + parts,
            style = MaterialTheme.typography.bodySmall,
            color = if (doubtful) scheme.onSurfaceVariant else scheme.secondary,
        )
        if (doubtful) {
            Text(
                "No forman «${form.trim()}» letra por letra: puede ser una palabra irregular o un error de la IA.",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.error,
            )
        }
    }
}
