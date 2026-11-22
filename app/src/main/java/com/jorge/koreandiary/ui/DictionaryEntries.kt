package com.jorge.koreandiary.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jorge.koreandiary.dictionary.WordLookupResult

/**
 * Lo que dice el 한국어기초사전 de una palabra tocada: cada entrada con su categoría, sus sentidos en
 * español y su origen, primero las comunes (decidido con Jorge el 2026-11-22). Plegado muestra lo
 * principal; «Ver todo» abre todas las entradas y sentidos. La usan el repaso rápido y la conversación.
 */
@Composable
fun DictionaryEntries(result: WordLookupResult, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    var all by remember(result) { mutableStateOf(false) }
    val entries = if (all) result.entries else result.entries.take(COLLAPSED_ENTRIES)
    val hidden = result.entries.size > entries.size ||
        entries.withIndex().any { (i, e) -> e.senses.size > if (i == 0) FIRST_SENSES else OTHER_SENSES }
    Column(modifier.testTag("diccionario"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.forEachIndexed { i, e ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    listOfNotNull(e.entry.form, e.kind, levelLabel(e.entry.level)).joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (i == 0) scheme.onSurface else scheme.onSurfaceVariant,
                )
                val senses = if (all) e.senses else e.senses.take(if (i == 0) FIRST_SENSES else OTHER_SENSES)
                senses.forEachIndexed { n, s ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (e.senses.size > 1) Text("${n + 1}.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                        Column {
                            s.lemma?.let {
                                Text(it, style = if (i == 0 && n == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium)
                            }
                            s.definition?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                        }
                    }
                }
                e.origin?.let { Text("Origen: $it", style = MaterialTheme.typography.bodySmall, color = scheme.secondary) }
            }
        }
        if (hidden) {
            AppTextButton(onClick = { all = true }, modifier = Modifier.testTag("diccionario_todo")) {
                Text(if (result.entries.size > entries.size) "Ver todo (${result.entries.size} entradas)" else "Ver todos los sentidos")
            }
        }
        Text("Del diccionario 한국어기초사전", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
    }
}

/** El nivel del diccionario, como lo leería alguien que estudia: qué tan común es la palabra. */
private fun levelLabel(level: Int): String? = when (level) {
    1 -> "básica"
    2 -> "intermedia"
    3 -> "avanzada"
    else -> null
}

private const val COLLAPSED_ENTRIES = 3
private const val FIRST_SENSES = 3
private const val OTHER_SENSES = 1
