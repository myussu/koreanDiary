package com.jorge.koreandiary.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jorge.koreandiary.AppContainer
import com.jorge.koreandiary.data.db.ItemType
import com.jorge.koreandiary.data.db.MemoryState
import com.jorge.koreandiary.srs.Fsrs
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun StudyScreen(container: AppContainer, onBack: () -> Unit, onImport: () -> Unit) {
    val words by container.study.words.collectAsStateWithLifecycle(emptyList())
    val grammar by container.study.grammar.collectAsStateWithLifecycle(emptyList())
    val pending by container.study.pendingWords.collectAsStateWithLifecycle(emptyList())
    val states by container.study.memoryStates.collectAsStateWithLifecycle(emptyList())
    val stateMap = remember(states) { states.associateBy { it.itemType to it.itemId } }
    var tab by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val now = remember { System.currentTimeMillis() }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppTextButton(onClick = onBack) { Text("‹ Diario") }
            Text("Estudio", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            AppButton(onClick = onImport) { Text("Importar") }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(vertical = 8.dp).horizontalScroll(rememberScrollState()),
        ) {
            listOf("Vocabulario (${words.size})", "Gramática (${grammar.size})", "Por revisar (${pending.size})")
                .forEachIndexed { i, label -> AppToggle(label, tab == i) { tab = i } }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            when (tab) {
                0 -> items(words, key = { "w${it.id}" }) { w ->
                    ItemRow(
                        title = w.baseForm,
                        subtitle = listOfNotNull(w.type, w.meaning, w.irregular?.let { "irregular $it" }).joinToString(" · "),
                        memory = stateMap[ItemType.WORD to w.id], now = now,
                    )
                }
                1 -> items(grammar, key = { "g${it.id}" }) { g ->
                    ItemRow(
                        title = g.pattern,
                        subtitle = listOfNotNull(g.level + (g.unit?.let { " · U$it" } ?: ""), g.meaning).joinToString(" · "),
                        memory = stateMap[ItemType.GRAMMAR to g.id], now = now,
                    )
                }
                else -> items(pending, key = { "p${it.id}" }) { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.baseForm, style = MaterialTheme.typography.titleMedium)
                            Text(listOfNotNull(p.type, p.meaning).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        }
                        AppTextButton(onClick = { scope.launch { container.study.rejectPendingWord(p) } }) { Text("Descartar") }
                        AppButton(onClick = { scope.launch { container.study.approvePendingWord(p, System.currentTimeMillis()) } }) { Text("Agregar") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ItemRow(title: String, subtitle: String, memory: MemoryState?, now: Long) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(memoryLabel(memory, now), style = MaterialTheme.typography.labelMedium)
        }
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider()
}

private fun memoryLabel(m: MemoryState?, now: Long): String {
    if (m == null) return "nuevo"
    val r = Fsrs.retrievabilityAt(Fsrs.Memory(m.stability, m.difficulty, m.lastReviewAt, m.reps, m.lapses), now)
    val days = ((m.dueAt - now).toDouble() / Fsrs.DAY_MS)
    val due = if (days <= 0) "toca repasar" else "en ${days.roundToInt().coerceAtLeast(1)} d"
    return "${(r * 100).roundToInt()} % · $due"
}
