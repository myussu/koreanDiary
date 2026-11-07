package com.jorge.koreandiary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jorge.koreandiary.AppContainer
import com.jorge.koreandiary.conversation.ConversationMath
import com.jorge.koreandiary.data.db.ItemType
import com.jorge.koreandiary.mascot.MascotBrain
import com.jorge.koreandiary.progress.HeatDay
import com.jorge.koreandiary.progress.MemorySummary
import com.jorge.koreandiary.progress.Progress
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val WEEKS = 14
private val shortDate = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("es"))

/** Constancia, memoria y lo que más cuesta, todo calculado en el celular (sin IA). */
@Composable
fun ProgressScreen(container: AppContainer, onBack: () -> Unit) {
    val days by container.diary.pagesPerDay.collectAsStateWithLifecycle(emptyList())
    val states by container.study.memoryStates.collectAsStateWithLifecycle(emptyList())
    val tenses by container.diary.tenseCounts.collectAsStateWithLifecycle(emptyList())
    val conversations by container.conversations.sessions.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { container.conversations.refresh() }
    val today = LocalDate.now()

    val perDay = remember(days) {
        days.mapNotNull { d -> runCatching { LocalDate.parse(d.date) to d.total }.getOrNull() }.toMap()
    }
    val streak = remember(perDay, today) { MascotBrain.streak(perDay.keys, today) }
    val best = remember(perDay) { MascotBrain.bestStreak(perDay.keys) }
    val endOfToday = remember(today) {
        today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
    }
    val words = remember(states, endOfToday) { Progress.summarize(states, ItemType.WORD, endOfToday) }
    val grammar = remember(states, endOfToday) { Progress.summarize(states, ItemType.GRAMMAR, endOfToday) }
    val hardest = remember(states) { Progress.hardest(states, 5) }
    val labels by produceState(emptyMap<Pair<String, Long>, String>(), hardest) {
        value = container.study.labels(hardest)
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppTextButton(onClick = onBack) { Text("‹ Diario") }
            Text("Progreso", style = MaterialTheme.typography.titleLarge)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatBox("Racha", streak, if (streak == 1) "día" else "días", Modifier.weight(1f))
            StatBox("Mejor racha", best, if (best == 1) "día" else "días", Modifier.weight(1f))
            StatBox("Páginas", perDay.values.sum(), "en ${perDay.size} días", Modifier.weight(1f))
        }

        if (conversations.isNotEmpty()) {
            // La conversación no suma a la racha (por ahora): solo se muestra el tiempo hablado.
            val talk = remember(conversations, today) { ConversationMath.stats(conversations, today) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("Conversación")
                Text(
                    "Hoy ${ConversationMath.format(talk.secondsToday)} · en total ${ConversationMath.format(talk.secondsTotal)} " +
                        "en ${talk.sessions} ${if (talk.sessions == 1) "sesión" else "sesiones"} (${talk.days} ${if (talk.days == 1) "día" else "días"})",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("Últimas $WEEKS semanas")
            val grid = remember(perDay, today) { Progress.heatmap(perDay, today, WEEKS) }
            Heatmap(grid, today)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Desde el ${shortDate.format(grid.first().first().date)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text("menos ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf(0, 1, 2, 3).forEach { n -> Box(Modifier.padding(1.dp).size(10.dp).background(heatColor(n))) }
                Text(" más", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("Memoria (FSRS)")
            MemoryRow("Palabras", words)
            MemoryRow("Gramática", grammar)
            Text(
                "Firme: la recuerdas aunque pasen 3 semanas. Te toca hoy: buen momento para usarla en el diario.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val byTense = tenses.associate { it.tense to it.total }
        if (byTense.values.sum() > 0) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("Tiempos verbales")
                val order = listOf("pasado", "presente", "futuro")
                val rows = order.map { it to (byTense[it] ?: 0) } + byTense.filterKeys { it !in order }.toList()
                val max = rows.maxOf { it.second }.coerceAtLeast(1)
                rows.forEach { (tense, count) -> TenseBar(tense, count, count.toFloat() / max) }
                val total = rows.sumOf { it.second }
                val weakest = rows.filter { it.first in order }.minByOrNull { it.second }
                if (total >= 10 && weakest != null && weakest.second * 5 < total) {
                    Text(
                        "Escribes poco en ${weakest.first}. Prueba a contar ${tenseIdea(weakest.first)}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }

        if (hardest.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel("Lo que más te cuesta")
                hardest.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            labels[m.itemType to m.itemId] ?: "…",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${m.lapses} ${if (m.lapses == 1) "fallo" else "fallos"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Text(
                    "La app te las sugiere más seguido hasta que se afirmen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(Modifier.height(16.dp))
    }
}

private fun tenseIdea(tense: String) = when (tense) {
    "futuro" -> "tus planes para el fin de semana (-(으)ㄹ 거예요, -(으)려고 해요)"
    "pasado" -> "qué hiciste ayer (-았/었어요)"
    else -> "tu rutina o cómo te sientes ahora (-아/어요, -고 있어요)"
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StatBox(title: String, value: Int, unit: String, modifier: Modifier = Modifier) {
    Column(modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant).padding(10.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$value", style = MaterialTheme.typography.headlineMedium)
        Text(unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Intensidad según páginas del día: 0, 1, 2, 3 o más. */
@Composable
private fun heatColor(pages: Int): Color {
    val scheme = MaterialTheme.colorScheme
    return when {
        pages <= 0 -> scheme.surfaceVariant
        pages == 1 -> scheme.primary.copy(alpha = 0.4f)
        pages == 2 -> scheme.primary.copy(alpha = 0.7f)
        else -> scheme.primary
    }
}

/** Calendario estilo GitHub: columnas = semanas, filas = lunes a domingo. */
@Composable
private fun Heatmap(grid: List<List<HeatDay>>, today: LocalDate) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth()) {
        // Peso 0.6 y proporción 0.6: cada etiqueta mide lo mismo de alto que una celda, en cualquier ancho.
        Column(Modifier.weight(0.6f)) {
            listOf("L", "", "X", "", "V", "", "D").forEach { label ->
                Box(Modifier.fillMaxWidth().aspectRatio(0.6f), contentAlignment = Alignment.CenterStart) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
        }
        grid.forEach { week ->
            Column(Modifier.weight(1f)) {
                week.forEach { day ->
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(1.5.dp)) {
                        if (!day.future) {
                            Box(
                                Modifier.fillMaxSize().background(heatColor(day.pages))
                                    .then(if (day.date == today) Modifier.border(1.dp, scheme.onSurface) else Modifier)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryRow(label: String, m: MemorySummary) {
    Row(
        Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        MiniStat(m.practiced, "practicadas")
        MiniStat(m.solid, "firmes")
        MiniStat(m.dueToday, "te tocan hoy", highlight = m.dueToday > 0)
    }
}

@Composable
private fun MiniStat(value: Int, label: String, highlight: Boolean = false) {
    Column(Modifier.padding(start = 12.dp), horizontalAlignment = Alignment.End) {
        Text(
            "$value",
            style = MaterialTheme.typography.titleMedium,
            color = if (highlight) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TenseBar(tense: String, count: Int, fraction: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tense.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(72.dp))
        Box(Modifier.weight(1f).height(14.dp)) {
            if (fraction > 0f) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(MaterialTheme.colorScheme.secondary))
            }
        }
        Text("$count", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(36.dp).padding(start = 8.dp))
    }
}
