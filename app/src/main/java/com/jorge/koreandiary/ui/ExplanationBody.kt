package com.jorge.koreandiary.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jorge.koreandiary.ai.ItemExplanation
import com.jorge.koreandiary.ai.WordParts

/**
 * Lo que se sabe de una palabra o una gramática: significado con «Escuchar», origen o partes, cómo
 * se pronuncia, sinónimos, mismo hanja y modismos, cómo se usa, sus formas y ejemplos. La usan la tarjeta de una sugerencia y «Más sobre esto» del repaso rápido, así
 * se ve igual en los dos lados. Viene del 한국어기초사전 y, si no lo tiene, de la IA, marcada
 * «Según la IA» (decidido con Jorge el 2026-11-22).
 *
 * Para una palabra nativa el diccionario no dice de qué partes se forma: con [onAskParts] sale
 * «¿De qué partes se forma?», que se le pregunta a la IA; su respuesta llega en [parts].
 */
@Composable
fun ExplanationBody(
    e: ItemExplanation,
    modifier: Modifier = Modifier,
    parts: String? = null,
    partsLoading: Boolean = false,
    onAskParts: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val voice = rememberKoreanVoice()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!e.fromDictionary) {
            Text("Según la IA", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.meaning, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
            SpeakButton(e.form, voice)
        }
        e.structure?.let {
            if (e.fromDictionary) Text("Origen: $it", style = MaterialTheme.typography.bodySmall, color = scheme.secondary)
            else PartsLine(e.form, it)
        }
        e.extras?.let { DictionaryExtrasView(it) }
        if (e.fromDictionary && e.structure == null && onAskParts != null) {
            when {
                parts != null -> Column {
                    Text("Según la IA", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    PartsLine(e.form, parts)
                }
                partsLoading -> Text("Preguntando a la IA…", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                else -> AppTextButton(onClick = onAskParts, modifier = Modifier.testTag("explicacion_partes")) {
                    Text("¿De qué partes se forma?")
                }
            }
        }
        e.usage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        e.conjugation?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.secondary) }
        e.examples.forEach { ex ->
            Column(Modifier.padding(top = 4.dp)) {
                Text(ex.korean, fontSize = 17.sp)
                ex.english?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
            }
        }
        if (e.fromDictionary) {
            Text("Del diccionario 한국어기초사전", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
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
