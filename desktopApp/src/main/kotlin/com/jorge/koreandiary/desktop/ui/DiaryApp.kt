package com.jorge.koreandiary.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jorge.koreandiary.backup.BackupException
import com.jorge.koreandiary.backup.BackupFormat
import com.jorge.koreandiary.data.db.AppDatabase
import com.jorge.koreandiary.data.db.DiaryPage
import com.jorge.koreandiary.data.db.PageStatus
import com.jorge.koreandiary.data.db.PageSummary
import com.jorge.koreandiary.desktop.BackupImporter
import com.jorge.koreandiary.desktop.DesktopPaths
import com.jorge.koreandiary.desktop.ImportResult
import com.jorge.koreandiary.desktop.openDatabase
import com.jorge.koreandiary.ink.InkOps
import com.jorge.koreandiary.ink.InkSerializer
import com.jorge.koreandiary.ink.pressureWidth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Primera pantalla de escritorio: el diario de esta computadora, de solo lectura por ahora.
 * Sirve para comprobar que la base y la tinta compartidas funcionan aquí, con datos reales del
 * celular importados desde su copia. Escribir con la tableta llega en el siguiente paso.
 */
@Composable
fun DiaryApp(chooseZip: () -> File?) {
    // null mientras se reemplaza la base: así nada la está observando cuando se cierra.
    var database by remember { mutableStateOf<AppDatabase?>(openDatabase()) }
    val current by rememberUpdatedState(database)
    DisposableEffect(Unit) { onDispose { current?.close() } }

    val summaries by remember(database) {
        database?.diaryDao()?.observePageSummaries() ?: flowOf(emptyList())
    }.collectAsState(emptyList())
    var selected by remember(database) { mutableStateOf<PageSummary?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<ImportResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun applyImport(result: ImportResult) {
        busy = true
        scope.launch {
            try {
                val old = database
                database = null
                // Dos cuadros: la interfaz se redibuja sin la base y deja de observarla.
                withFrameNanos { }
                withFrameNanos { }
                withContext(Dispatchers.IO) {
                    old?.close()
                    BackupImporter.apply()
                }
                database = openDatabase()
                message = "Listo: ${BackupFormat.pagesLabel(result.pages)} de la copia del " +
                    "${BackupFormat.dateLabel(result.manifest.exportedAt)}."
            } catch (e: Exception) {
                database = openDatabase()
                message = "No se pudo importar: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun startImport() {
        val zip = chooseZip() ?: return
        busy = true
        message = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { BackupImporter.prepare(zip) }
                val existing = withContext(Dispatchers.IO) { database?.diaryDao()?.pageCount() ?: 0 }
                busy = false
                // Si esta computadora ya tiene páginas, se pide confirmación antes de reemplazarlas.
                if (existing == 0) applyImport(result) else pending = result
            } catch (e: BackupException) {
                message = e.message
                busy = false
            } catch (e: Exception) {
                message = "No se pudo leer la copia: ${e.message}"
                busy = false
            }
        }
    }

    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.background, contentColor = scheme.onBackground, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("일기", style = MaterialTheme.typography.headlineMedium)
                Text("Diario coreano · escritorio", style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                AppOutlinedButton(onClick = { startImport() }, enabled = !busy) {
                    Text(if (busy) "Importando…" else "Importar copia del celular…")
                }
            }
            message?.let {
                Text(it, color = scheme.secondary, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 10.dp))
            }
            HorizontalDivider(color = scheme.outlineVariant)

            val db = database
            when {
                db == null -> Text(
                    "Importando la copia…",
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(48.dp),
                )
                summaries.isEmpty() -> EmptyState(Modifier.weight(1f).fillMaxWidth())
                else -> Row(Modifier.weight(1f).fillMaxWidth()) {
                    PageList(summaries, selected?.pageId, { selected = it }, Modifier.width(360.dp).fillMaxHeight())
                    VerticalDivider(color = scheme.outlineVariant)
                    PageDetail(db, selected, Modifier.weight(1f).fillMaxHeight())
                }
            }

            HorizontalDivider(color = scheme.outlineVariant)
            Text(
                "Datos de esta computadora: ${DesktopPaths.dataDir}",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
    }

    pending?.let { result ->
        AlertDialog(
            onDismissRequest = { BackupImporter.discard(); pending = null },
            shape = Square,
            title = { Text("¿Reemplazar el diario de esta computadora?") },
            text = {
                Text(
                    "La copia del ${BackupFormat.dateLabel(result.manifest.exportedAt)} trae " +
                        "${BackupFormat.pagesLabel(result.pages)}. Lo que hay ahora queda guardado en " +
                        "${DesktopPaths.beforeImport}.",
                )
            },
            confirmButton = { AppButton(onClick = { pending = null; applyImport(result) }) { Text("Reemplazar") } },
            dismissButton = { AppTextButton(onClick = { BackupImporter.discard(); pending = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Column(modifier.padding(48.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("El diario de esta computadora está vacío.", style = MaterialTheme.typography.titleLarge)
        Text(
            "Para traer el del celular: en la app, Ajustes → Copia de seguridad → Exportar copia. " +
                "Pasa el .zip a esta computadora e impórtalo con el botón de arriba.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val DayFormat = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM yyyy", Locale.forLanguageTag("es"))

private fun dayLabel(date: String): String =
    runCatching { LocalDate.parse(date).format(DayFormat).replaceFirstChar { it.uppercase() } }.getOrDefault(date)

/** Igual que en la pantalla de inicio del celular. */
private fun statusLabel(status: String): String = when (status) {
    PageStatus.DRAFT -> "Borrador"
    PageStatus.PENDING -> "Esperando corrección"
    PageStatus.REVIEWED -> "Revisada ✓"
    else -> status
}

@Composable
private fun PageList(
    summaries: List<PageSummary>,
    selectedId: Long?,
    onSelect: (PageSummary) -> Unit,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val byDay = remember(summaries) { summaries.groupBy { it.date }.toList() }
    LazyColumn(modifier) {
        byDay.forEach { (date, pages) ->
            item(key = "d-$date") {
                Text(
                    dayLabel(date),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp),
                )
            }
            items(pages, key = { "p-${it.pageId}" }) { p ->
                val isSelected = p.pageId == selectedId
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(if (isSelected) scheme.surfaceContainerHigh else scheme.background)
                        .clickable { onSelect(p) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Text(
                        statusLabel(p.status),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (p.status == PageStatus.PENDING) scheme.secondary else scheme.onSurfaceVariant,
                    )
                    Text(
                        p.preview?.takeIf { it.isNotBlank() } ?: "Sin texto todavía",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (p.preview.isNullOrBlank()) scheme.onSurfaceVariant else scheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun PageDetail(database: AppDatabase, summary: PageSummary?, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    if (summary == null) {
        Column(modifier.padding(32.dp)) {
            Text("Elige una página a la izquierda.", color = scheme.onSurfaceVariant)
        }
        return
    }
    val page by produceState<DiaryPage?>(null, database, summary.pageId) {
        value = withContext(Dispatchers.IO) { database.diaryDao().page(summary.pageId) }
    }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(dayLabel(summary.date), style = MaterialTheme.typography.titleLarge)
        val p = page ?: return@Column
        InkView(
            p.inkJson,
            Modifier
                .widthIn(max = 900.dp)
                .fillMaxWidth()
                .border(1.dp, scheme.outlineVariant, Square)
                .background(scheme.surfaceContainerLowest),
        )
        val text = p.confirmedText?.takeIf { it.isNotBlank() } ?: p.recognizedText
        if (!text.isNullOrBlank()) {
            Text(if (p.confirmedText.isNullOrBlank()) "Texto reconocido" else "Texto", style = MaterialTheme.typography.labelLarge)
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(max = 900.dp))
        }
    }
}

/** Dibuja la tinta guardada (relativa al ancho de la hoja) al ancho disponible, de solo lectura. */
@Composable
private fun InkView(inkJson: String, modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        val strokes = remember(inkJson, widthPx) {
            val loaded = InkSerializer.fromJson(inkJson, widthPx)
            InkOps.fitToWidth(loaded, widthPx) ?: loaded
        }
        // Grosor proporcional al ancho, parecido al del lápiz en el celular.
        val base = widthPx * 0.008f
        val maxY = strokes.maxOfOrNull { s -> s.points.maxOf { it.y } } ?: 0f
        val heightPx = (maxY + base * 10).coerceAtLeast(widthPx * 0.25f)
        val density = LocalDensity.current
        Canvas(Modifier.fillMaxWidth().height(with(density) { heightPx.toDp() })) {
            for (s in strokes) {
                val pts = s.points
                if (pts.size == 1) {
                    drawCircle(ink, pressureWidth(base, pts[0].p) / 2f, Offset(pts[0].x, pts[0].y))
                    continue
                }
                for (i in 1 until pts.size) {
                    val a = pts[i - 1]
                    val b = pts[i]
                    drawLine(
                        ink,
                        Offset(a.x, a.y),
                        Offset(b.x, b.y),
                        strokeWidth = pressureWidth(base, (a.p + b.p) / 2f),
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}
