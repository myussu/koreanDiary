package com.jorge.koreandiary.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.jorge.koreandiary.dictionary.SentenceBreakdown
import com.jorge.koreandiary.pronunciation.Pronunciation

/**
 * «Desglosar» una oración, sin IA (pedido por Jorge el 2026-11-24): cómo suena, con lo que cambia al
 * decirlo resaltado; cada palabra con su forma de diccionario, su significado y sus partículas y
 * terminaciones; y los modismos que aparecen. Lo usan el repaso rápido, la conversación y la escucha.
 */
@Composable
fun BreakdownView(result: SentenceBreakdown.Result, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val changed = remember(result) { Pronunciation.changed(result.sentence, result.sound) }
    Column(modifier.testTag("desglose"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("CÓMO SUENA", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        Text(
            buildAnnotatedString {
                append("[")
                result.sound.forEachIndexed { i, ch ->
                    if (i in changed) withStyle(SpanStyle(color = scheme.primary, fontWeight = FontWeight.Bold)) { append(ch) } else append(ch)
                }
                append("]")
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("desglose_suena"),
        )
        if (changed.isNotEmpty()) {
            Text("En color, lo que cambia al decirlo.", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        Text("PALABRA POR PALABRA", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        result.words.forEach { w ->
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(w.text) }
                        val lemma = w.lemma
                        if (lemma != null && lemma != w.text) append(" → $lemma")
                        w.gloss?.let { append(" «$it»") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (w.grammar.isNotEmpty()) {
                    Text(
                        w.grammar.joinToString("  ·  ") { p -> if (p.label != null) "${p.form} ${p.label}" else p.form },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (result.idioms.isNotEmpty()) {
            Text("MODISMOS", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            result.idioms.forEach { i ->
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(i.form) }
                        append(": ${i.gloss}")
                        if (i.proverb) append(" (refrán)")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("desglose_modismo"),
                )
            }
            Text("Puede ser también el sentido literal de las palabras.", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        if (!result.analyzed) {
            Text(
                "Con el analizador de oraciones (Ajustes › Escritura) también salen las partículas y las terminaciones.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Text("Sin IA: el analizador de oraciones y el diccionario 한국어기초사전.", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
    }
}
