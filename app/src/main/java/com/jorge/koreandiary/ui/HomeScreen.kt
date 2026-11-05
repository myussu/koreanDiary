package com.jorge.koreandiary.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jorge.koreandiary.AppContainer
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormatter = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM, HH:mm", Locale.forLanguageTag("es"))

fun formatDate(millis: Long): String =
    dateFormatter.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
        .replaceFirstChar { it.uppercase() }

@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenPage: (Long) -> Unit,
    onStudy: () -> Unit,
    onSettings: () -> Unit,
) {
    val pages by container.diary.pageSummaries.collectAsStateWithLifecycle(emptyList())
    val tenses by container.diary.tenseCounts.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("일기", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            AppOutlinedButton(onClick = onStudy) { Text("Estudio") }
            AppOutlinedButton(onClick = onSettings, modifier = Modifier.padding(start = 6.dp)) { Text("Ajustes") }
        }
        if (tenses.isNotEmpty()) {
            val byTense = tenses.associate { it.tense to it.total }
            Text(
                "Oraciones · pasado ${byTense["pasado"] ?: 0} · presente ${byTense["presente"] ?: 0} · futuro ${byTense["futuro"] ?: 0}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        AppButton(
            onClick = {
                creating = true
                scope.launch {
                    val s = container.settings.state.value
                    val suggestions = container.suggestions.suggest(System.currentTimeMillis(), s.grammarLevelList)
                    val id = container.diary.newPage(System.currentTimeMillis(), suggestions.encode())
                    creating = false
                    onOpenPage(id)
                }
            },
            enabled = !creating,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            contentPadding = PaddingValues(vertical = 14.dp),
        ) { Text("Nueva página") }

        if (pages.isEmpty()) {
            Text(
                "Aún no hay páginas. Empieza una nueva y escribe sobre tu día con el S Pen.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(pages, key = { it.pageId }) { p ->
                Card(Modifier.fillMaxWidth().clickable { onOpenPage(p.pageId) }) {
                    Column(Modifier.padding(14.dp)) {
                        Row {
                            Text(p.date, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text(p.status, style = MaterialTheme.typography.labelMedium)
                        }
                        Text(
                            p.preview?.takeIf { it.isNotBlank() } ?: "(sin texto reconocido)",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
