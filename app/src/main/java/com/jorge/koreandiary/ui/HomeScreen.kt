package com.jorge.koreandiary.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.jorge.koreandiary.AppContainer
import com.jorge.koreandiary.BuildConfig
import com.jorge.koreandiary.CrashLog
import com.jorge.koreandiary.data.db.ItemType
import com.jorge.koreandiary.data.db.PageKind
import com.jorge.koreandiary.data.db.PageStatus
import com.jorge.koreandiary.data.db.PageSummary
import com.jorge.koreandiary.scene.SceneData
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
    onSettings: (SettingsGroup) -> Unit,
    onWorkbook: () -> Unit,
    onProgress: () -> Unit,
    onConversation: () -> Unit,
    onScenes: () -> Unit,
    onFeatures: () -> Unit,
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
            AppOutlinedButton(onClick = onStudy, modifier = Modifier.testTag("inicio_estudio")) { Text("Estudio") }
            AppOutlinedButton(onClick = { onSettings(SettingsGroup.GENERAL) }, modifier = Modifier.padding(start = 6.dp).testTag("inicio_ajustes")) { Text("Ajustes") }
            // Botón chico en la esquina: la lista de todo lo que la app ya sabe hacer.
            AppOutlinedButton(
                onClick = onFeatures,
                modifier = Modifier.padding(start = 6.dp).semantics { contentDescription = "Qué puede hacer la app" },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) { Text("?") }
        }
        CrashCard(modifier = Modifier.padding(top = 10.dp))
        if (BuildConfig.PRUEBA) {
            Text(
                "Versión de prueba: va aparte de tu diario. " + if (settings.fixedAi) "La IA contesta con respuestas fijas." else "La IA es la real.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 6.dp).testTag("version_prueba"),
            )
        }
        UpdatePanel(container.updater, alwaysVisible = false, modifier = Modifier.padding(top = 10.dp))
        BackupBanner(container, onSettings = { onSettings(SettingsGroup.BACKUP) }, modifier = Modifier.padding(top = 10.dp))
        // Solo hace falta saber si hay algo en las listas, no cargarlas enteras cada vez que abres el inicio.
        val hasStudy by container.study.hasItems.collectAsStateWithLifecycle(true)
        val needsStudy = !hasStudy
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
            // Resumen corto; el detalle (tu nivel, calendario, memoria, lo que más cuesta) está en Estudio › Resumen.
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
                Text("Resumen ›", style = MaterialTheme.typography.labelLarge)
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
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("inicio_nueva_pagina"),
            contentPadding = PaddingValues(vertical = 14.dp),
        ) { Text("Nueva página") }
        // Misma altura para los tres aunque «Hablar y escuchar» ocupe dos renglones.
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val padding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)
            val cell = Modifier.weight(1f).fillMaxHeight()
            // Describir un lugar o un momento, vivido o imaginado.
            AppOutlinedButton(onClick = onScenes, modifier = cell.testTag("inicio_escenas"), contentPadding = padding) { Text("Escenas") }
            // Pistas y revisión para ejercicios del workbook, con foto.
            AppOutlinedButton(onClick = onWorkbook, modifier = cell.testTag("inicio_workbook"), contentPadding = padding) { Text("Workbook") }
            // Conversación por voz con Gemini y el pódcast de Escuchar.
            AppOutlinedButton(onClick = onConversation, modifier = cell.testTag("inicio_hablar"), contentPadding = padding) {
                Text("Hablar y escuchar", textAlign = TextAlign.Center)
            }
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

/**
 * Crea una página de hoy con sus sugerencias (FSRS y lagunas, sin IA) y devuelve su id. Una escena
 * suma además 2 gramáticas nuevas un paso por encima de tu nivel, en su propio grupo: van también
 * en las sugeridas (si las usas cuentan como reconocidas) y en `sceneJson` para mostrarlas aparte.
 */
suspend fun newPageWithSuggestions(container: AppContainer, kind: String): Long {
    val s = container.settings.state.value
    val now = System.currentTimeMillis()
    val suggestions = container.suggestions.suggest(now, s.grammarLevelList, s.standardLevel)
    if (kind != PageKind.SCENE) return container.diary.newPage(now, suggestions.encode(), kind)
    val fresh = container.suggestions.newGrammarForScene(s.standardLevel, exclude = suggestions.gapGrammar.map { it.id }.toSet())
    val items = (listOf(suggestions.encode()) + fresh.map { "${ItemType.STD_GRAMMAR}:${it.id}" })
        .filter { it.isNotEmpty() }
        .joinToString(",")
    return container.diary.newPage(now, items, kind, SceneData(newGrammar = fresh.map { it.id }).toJson())
}

/** Tarjeta de una página en las listas del diario y de escenas. */
@Composable
fun PageSummaryCard(p: PageSummary, today: LocalDate, onClick: () -> Unit, leading: (@Composable () -> Unit)? = null) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(start = if (leading != null) 10.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
            leading?.invoke()
            Column(Modifier.weight(1f).padding(14.dp)) {
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

/**
 * Si la app se cerró por un error la vez anterior: un aviso con «Copiar detalle» (para pegarlo en un
 * chat o en un issue) y «Descartar».
 */
@Composable
private fun CrashCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var detail by remember { mutableStateOf(CrashLog.read(context)) }
    var copied by remember { mutableStateOf(false) }
    val text = detail ?: return
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().border(1.dp, scheme.error).padding(12.dp).testTag("aviso_cierre"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("LA APP SE CERRÓ POR UN ERROR", style = MaterialTheme.typography.labelMedium, color = scheme.error)
        Text(
            text.lineSequence().firstOrNull { it.startsWith("Fecha:") }?.removePrefix("Fecha: ")?.let { "La última vez, el $it. " }.orEmpty() +
                "Copia el detalle y pásalo para arreglarlo.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Cierre de la app", text))
                copied = true
            }) { Text(if (copied) "Copiado" else "Copiar detalle") }
            AppTextButton(onClick = { CrashLog.clear(context); detail = null }) { Text("Descartar") }
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
    onSettings: (SettingsGroup) -> Unit,
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
            onClick = { onSettings(SettingsGroup.GENERAL) },
        )
        OnboardingItem(
            done = hasApi,
            text = "Opcional: configura Gemini o Azure. Sin API también puedes usar “Compartir con un chat”.",
            action = "Ajustes",
            onClick = { onSettings(SettingsGroup.AI) },
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
