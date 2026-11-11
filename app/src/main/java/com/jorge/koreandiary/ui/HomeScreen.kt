package com.jorge.koreandiary.ui

import androidx.compose.foundation.border
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
import com.jorge.koreandiary.data.db.PageKind
import com.jorge.koreandiary.data.db.PageStatus
import com.jorge.koreandiary.data.db.PageSummary
import kotlinx.coroutines.launch
import com.jorge.koreandiary.mascot.MascotBrain
import com.jorge.koreandiary.settings.ProviderId
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormatter = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM, HH:mm", Locale.forLanguageTag("es"))

fun formatDate(millis: Long): String =
    dateFormatter.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
        .replaceFirstChar { it.uppercase() }

private val dayFormatter = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale.forLanguageTag("es"))
private val dayYearFormatter = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es"))

/** "2026-11-05" → "Hoy", "Ayer" o "Martes 3 de noviembre" (con año si no es el actual). */
fun relativeDay(isoDate: String, today: LocalDate): String {
    val date = runCatching { LocalDate.parse(isoDate) }.getOrNull() ?: return isoDate
    return when (date) {
        today -> "Hoy"
        today.minusDays(1) -> "Ayer"
        else -> (if (date.year == today.year) dayFormatter else dayYearFormatter).format(date).replaceFirstChar { it.uppercase() }
    }
}

fun pageStatusLabel(status: String) = when (status) {
    PageStatus.DRAFT -> "Borrador"
    PageStatus.PENDING -> "Esperando corrección"
    PageStatus.REVIEWED -> "Revisada ✓"
    else -> status
}

@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenPage: (Long) -> Unit,
    onStudy: () -> Unit,
    onSettings: () -> Unit,
    onWorkbook: () -> Unit,
    onProgress: () -> Unit,
    onConversation: () -> Unit,
    onScenes: () -> Unit,
) {
    val pages by container.diary.pageSummaries.collectAsStateWithLifecycle(emptyList())
    // Días que cuentan para la racha: el diario y las escenas ya verificadas.
    val counted by container.diary.pagesPerDay.collectAsStateWithLifecycle(emptyList())
    val tenses by container.diary.tenseCounts.collectAsStateWithLifecycle(emptyList())
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("일기", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            AppOutlinedButton(onClick = onStudy) { Text("Estudio") }
            AppOutlinedButton(onClick = onSettings, modifier = Modifier.padding(start = 6.dp)) { Text("Ajustes") }
        }
        UpdatePanel(container.updater, alwaysVisible = false, modifier = Modifier.padding(top = 10.dp))
        BackupBanner(container, onSettings = onSettings, modifier = Modifier.padding(top = 10.dp))
        val words by container.study.words.collectAsStateWithLifecycle(emptyList())
        val grammar by container.study.grammar.collectAsStateWithLifecycle(emptyList())
        val needsStudy = words.isEmpty() && grammar.isEmpty()
        val needsProfile = settings.profile.isBlank()
        if (!settings.hideOnboarding && (needsStudy || needsProfile)) {
            OnboardingCard(
                needsStudy = needsStudy,
                needsProfile = needsProfile,
                hasApi = container.isConfigured(ProviderId.GEMINI) || container.isConfigured(ProviderId.AZURE),
                onStudy = onStudy,
                onSettings = onSettings,
                onHide = { container.settings.update { it.copy(hideOnboarding = true) } },
            )
        }
        if (settings.showMascot) {
            MascotGreeting(container, counted.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() })
        }
        if (pages.isNotEmpty()) {
            // Resumen corto; el detalle (calendario, memoria, lo que más cuesta) está en Progreso.
            val byTense = tenses.associate { it.tense to it.total }
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    .clickable(onClick = onProgress)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${pages.size} ${if (pages.size == 1) "página" else "páginas"} · pasado ${byTense["pasado"] ?: 0} · " +
                        "presente ${byTense["presente"] ?: 0} · futuro ${byTense["futuro"] ?: 0}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text("Progreso ›", style = MaterialTheme.typography.labelLarge)
            }
        }
        AppButton(
            onClick = {
                creating = true
                scope.launch {
                    val id = newPageWithSuggestions(container, PageKind.DIARY)
                    creating = false
                    onOpenPage(id)
                }
            },
            enabled = !creating,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            contentPadding = PaddingValues(vertical = 14.dp),
        ) { Text("Nueva página") }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val padding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)
            // Describir un lugar o un momento, vivido o imaginado.
            AppOutlinedButton(onClick = onScenes, modifier = Modifier.weight(1f), contentPadding = padding) { Text("Escenas") }
            // Pistas y revisión para ejercicios del workbook, con foto.
            AppOutlinedButton(onClick = onWorkbook, modifier = Modifier.weight(1f), contentPadding = padding) { Text("Workbook") }
            // Conversación por voz con Gemini (speaking).
            AppOutlinedButton(onClick = onConversation, modifier = Modifier.weight(1f), contentPadding = padding) { Text("Hablar") }
        }

        if (pages.isEmpty()) {
            Text(
                "Aún no hay páginas. Empieza una nueva y escribe sobre tu día con el S Pen.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        val today = LocalDate.now()
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(pages, key = { it.pageId }) { p -> PageSummaryCard(p, today, onClick = { onOpenPage(p.pageId) }) }
        }
    }
}

/** Crea una página de hoy con sus sugerencias (FSRS y lagunas, sin IA) y devuelve su id. */
suspend fun newPageWithSuggestions(container: AppContainer, kind: String): Long {
    val s = container.settings.state.value
    val now = System.currentTimeMillis()
    val suggestions = container.suggestions.suggest(now, s.grammarLevelList, s.standardLevel)
    return container.diary.newPage(now, suggestions.encode(), kind)
}

/** Tarjeta de una página en las listas del diario y de escenas. */
@Composable
fun PageSummaryCard(p: PageSummary, today: LocalDate, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Row {
                Text(relativeDay(p.date, today), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    pageStatusLabel(p.status),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (p.status == PageStatus.PENDING) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

/** La mascota saluda según tu racha y la hora. Toca el globo para ver la traducción. */
@Composable
private fun MascotGreeting(container: AppContainer, dates: List<LocalDate>) {
    val today = LocalDate.now()
    val line = remember(dates, today) { MascotBrain.greeting(dates, today, LocalTime.now().hour) }
    val streak = remember(dates, today) { MascotBrain.streak(dates, today) }
    var translated by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        AppMascot(container.mascotPack, line.mood, 88.dp)
        Column(
            Modifier.weight(1f).padding(start = 12.dp)
                .border(1.dp, scheme.outlineVariant)
                .clickable { translated = !translated }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(line.korean, style = MaterialTheme.typography.titleMedium)
            Text(
                if (translated) line.spanish else "Toca para traducir",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            if (streak > 0) {
                Text(
                    "Racha: $streak ${if (streak == 1) "día" else "días"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.secondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** Lo mínimo para que las sugerencias y correcciones funcionen bien. Desaparece al completarlo. */
@Composable
private fun OnboardingCard(
    needsStudy: Boolean,
    needsProfile: Boolean,
    hasApi: Boolean,
    onStudy: () -> Unit,
    onSettings: () -> Unit,
    onHide: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).border(1.dp, scheme.outlineVariant).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("PRIMEROS PASOS", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            AppTextButton(onClick = onHide) { Text("Ocultar") }
        }
        OnboardingItem(
            done = !needsStudy,
            text = "Importa tu lista de gramática o vocabulario para recibir sugerencias.",
            action = "Estudio",
            onClick = onStudy,
        )
        OnboardingItem(
            done = !needsProfile,
            text = "Escribe tu perfil y tu nivel: la IA corrige con ese contexto.",
            action = "Ajustes",
            onClick = onSettings,
        )
        OnboardingItem(
            done = hasApi,
            text = "Opcional: configura Gemini o Azure. Sin API también puedes usar “Compartir con un chat”.",
            action = "Ajustes",
            onClick = onSettings,
        )
    }
}

@Composable
private fun OnboardingItem(done: Boolean, text: String, action: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (done) "✓" else "·", color = if (done) scheme.tertiary else scheme.secondary, modifier = Modifier.padding(end = 8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (done) scheme.onSurfaceVariant else scheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (!done) AppTextButton(onClick = onClick) { Text(action) }
    }
}
