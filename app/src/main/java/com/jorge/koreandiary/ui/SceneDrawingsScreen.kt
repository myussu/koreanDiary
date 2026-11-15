package com.jorge.koreandiary.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jorge.koreandiary.AppContainer
import com.jorge.koreandiary.scene.DrawJob
import com.jorge.koreandiary.scene.SavedDrawing
import com.jorge.koreandiary.scene.SceneDictionary
import com.jorge.koreandiary.scene.SceneDrawings
import com.jorge.koreandiary.scene.SceneLexicon
import com.jorge.koreandiary.scene.SceneZone
import com.jorge.koreandiary.scene.art.CustomDrawing
import com.jorge.koreandiary.scene.art.Mark
import com.jorge.koreandiary.scene.art.NewSceneThing
import com.jorge.koreandiary.scene.art.SceneArt
import com.jorge.koreandiary.ui.page.MarksCanvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * «Dibujos de la escena»: todo lo que la escena viva sabe pintar y las palabras que lo traen. Los de
 * la app (los de código y los de fábrica, por zona) y los nuevos que dibujó Claude (con «Dibujar otra
 * vez» y «Borrar»), lo que espera su dibujo y un campo para probar una palabra con la misma regla que
 * usa la hoja.
 */
@Composable
fun SceneDrawingsScreen(container: AppContainer, onBack: () -> Unit, onSettings: () -> Unit) {
    val store = container.sceneDrawings
    val saved by store.saved.collectAsStateWithLifecycle()
    val factory by store.factory.collectAsStateWithLifecycle()
    val pending by store.pending.collectAsStateWithLifecycle()
    val jobs by store.jobs.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val paper = LocalPaperStyle.current
    val dark = paper.background.luminance() < 0.5f
    var probe by rememberSaveable { mutableStateOf("") }
    val lexicon = remember(saved, factory) { SceneDictionary.withCustom(saved.map { it.drawing } + factory) }
    val ready = store.ready()
    // Lo que se está dibujando y no está en espera (pedido desde la revisión o desde la prueba).
    val drawingNow = jobs.filter { (k, j) -> j == DrawJob.Drawing && pending.none { it.thing.ko == k } && saved.none { it.drawing.id == k } }.keys

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppTextButton(onClick = onBack) { Text("‹ Volver") }
            Text("Dibujos de la escena", style = MaterialTheme.typography.titleLarge)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            full("intro") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Lo que nombras en una escena aparece pintado abajo de la hoja. Estas son las ${SceneDictionary.elements.size + factory.size + saved.size} cosas que la escena sabe dibujar y las palabras que las traen mientras escribes.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (ready) {
                            "Al revisar una escena, lo que se ve y no tiene dibujo lo dibuja Claude (hasta 2 por página). Hoy: ${store.usedToday()} de ${SceneDrawings.DAILY_LIMIT}."
                        } else {
                            "Para que Claude dibuje lo que todavía no tiene dibujo, pon tu clave en Ajustes → «Dibujos nuevos (Claude)». Mientras tanto, lo nuevo queda en espera aquí."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    if (!ready) AppOutlinedButton(onClick = onSettings) { Text("Ajustes") }
                }
            }
            full("probar") {
                Probe(probe, { probe = it }, lexicon, ready, jobs) { ko -> store.draw(NewSceneThing(ko, ko)) }
            }
            if (pending.isNotEmpty() || drawingNow.isNotEmpty()) {
                header("espera", "En espera", "Lo que apareció al revisar y todavía no tiene dibujo.")
                drawingNow.forEach { ko ->
                    full("ahora:$ko") { Text("Dibujando «$ko»…", style = MaterialTheme.typography.bodyMedium) }
                }
                pending.forEach { p ->
                    full("espera:${p.thing.ko}") {
                        PendingRow(p.thing, p.context, jobs[p.thing.ko], ready, onDraw = { store.draw(p.thing, p.context) }, onDismiss = { store.dismiss(p.thing.ko) })
                    }
                }
            }
            if (saved.isNotEmpty()) {
                header("nuevos", "Nuevos (Claude)", "${saved.size} dibujo${if (saved.size == 1) "" else "s"}. Salen al escribir sus palabras, igual que los de la app.")
                items(saved.reversed(), key = { "n:" + it.drawing.id }) { s ->
                    NewDrawingCard(s, dark, paper, jobs[s.drawing.id], ready, onRedraw = { store.redraw(s.drawing.id) }, onDelete = { store.delete(s.drawing.id) })
                }
            }
            listOf(
                SceneZone.GROUND to "Suelo",
                SceneZone.HORIZON to "Horizonte",
                SceneZone.SKY to "Cielo",
                SceneZone.AIR to "Aire",
            ).forEach { (zone, title) ->
                val list = SceneDictionary.elements.filter { it.zone == zone }
                val boxed = factory.filter { it.zone == zone }
                header("z:$zone", title, "De la app: ${list.size + boxed.size} dibujos.")
                items(list, key = { "a:" + it.id }) { e ->
                    DrawingCard(
                        title = e.name.replaceFirstChar { it.uppercase() },
                        words = SceneDictionary.keysOf(e.id),
                        dark = dark,
                        paper = paper,
                        paint = { w, h -> SceneArt.paint(listOf(e), w, h, PREVIEW_TIME) },
                        key = e.id,
                    )
                }
                items(boxed, key = { "f:" + it.id }) { d -> FactoryCard(d, lexicon, dark, paper) }
            }
        }
    }
}

/** Un instante de la escena para las miniaturas (quietas: no gastan batería). */
private const val PREVIEW_TIME = 3f

private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

private fun LazyGridScope.header(key: String, title: String, subtitle: String) {
    full(key) {
        val scheme = MaterialTheme.colorScheme
        Column(Modifier.padding(top = 10.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = scheme.secondary)
            HorizontalDivider(color = scheme.outlineVariant, modifier = Modifier.padding(top = 4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** «Prueba una palabra»: qué dibujo trae lo que escribes; si no trae ninguno, se puede pedir a Claude. */
@Composable
private fun Probe(
    text: String,
    onText: (String) -> Unit,
    lexicon: SceneLexicon,
    ready: Boolean,
    jobs: Map<String, DrawJob>,
    onDraw: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            label = { Text("Prueba una palabra o frase en coreano") },
            placeholder = { Text("바닷가에서 고양이를 봤다") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val hits = remember(text, lexicon) { lexicon.detectWithKeys(text) }
        if (text.isBlank()) {
            Unit
        } else if (hits.isNotEmpty()) {
            hits.forEach { (e, key) ->
                Text("→ ${e.name} (por «$key»)", style = MaterialTheme.typography.bodyMedium)
            }
        } else if (lexicon.owner(text.trim()) != null) {
            val word = text.trim()
            Text(
                "«$word» trae ${lexicon.owner(word)?.name} con una partícula (${word}가, ${word}를): sola casi siempre es otra cosa.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text("No trae ningún dibujo mientras escribes.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            val word = text.trim()
            val single = Regex("^[\\p{IsHangul}]{1,8}$").matches(word)
            when {
                !single -> Unit
                jobs[word] == DrawJob.Drawing -> Text("Dibujando «$word»…", style = MaterialTheme.typography.bodySmall)
                ready -> AppOutlinedButton(onClick = { onDraw(word) }) { Text("Dibujar «$word» con Claude") }
                else -> Text("Con una clave de Claude se puede dibujar.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            (jobs[word] as? DrawJob.Failed)?.let { Text(it.message, style = MaterialTheme.typography.bodySmall, color = scheme.error) }
        }
    }
}

@Composable
private fun PendingRow(thing: NewSceneThing, context: String, job: DrawJob?, ready: Boolean, onDraw: () -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().border(1.dp, scheme.outlineVariant).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${thing.ko} · ${thing.es}", style = MaterialTheme.typography.titleSmall)
        if (context.isNotBlank()) Text("«${context.take(120)}»", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        when (job) {
            DrawJob.Drawing -> Text("Dibujando…", style = MaterialTheme.typography.bodySmall)
            is DrawJob.Failed -> Text(job.message, style = MaterialTheme.typography.bodySmall, color = scheme.error)
            null -> Unit
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (ready && job != DrawJob.Drawing) AppOutlinedButton(onClick = onDraw) { Text("Dibujar") }
            AppTextButton(onClick = onDismiss) { Text("Quitar") }
        }
    }
}

@Composable
private fun NewDrawingCard(
    saved: SavedDrawing,
    dark: Boolean,
    paper: PaperStyle,
    job: DrawJob?,
    ready: Boolean,
    onRedraw: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val d = saved.drawing
    var confirm by remember(d.id) { mutableStateOf(false) }
    val tokens = saved.tokensIn + saved.tokensOut
    val date = remember(saved.createdAt) {
        if (saved.createdAt <= 0) "" else DateTimeFormatter.ofPattern("d/M/yyyy").format(Instant.ofEpochMilli(saved.createdAt).atZone(ZoneId.systemDefault()))
    }
    DrawingCard(
        title = d.name.replaceFirstChar { it.uppercase() },
        words = d.keys,
        dark = dark,
        paper = paper,
        paint = { w, _ -> boxPreview(d, w) },
        key = d.id + d.shapes.hashCode(),
    ) {
        Text(
            listOf(date, saved.model.removePrefix("claude-"), if (tokens > 0) "$tokens tokens" else "").filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
        when (job) {
            DrawJob.Drawing -> Text("Dibujando otra vez…", style = MaterialTheme.typography.bodySmall)
            is DrawJob.Failed -> Text(job.message, style = MaterialTheme.typography.bodySmall, color = scheme.error)
            null -> Unit
        }
        if (confirm) {
            Text("¿Borrar este dibujo? Sus palabras dejan de traerlo.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppButton(onClick = onDelete) { Text("Sí, borrar") }
                AppTextButton(onClick = { confirm = false }) { Text("No") }
            }
        } else {
            Column {
                if (ready && job != DrawJob.Drawing) AppTextButton(onClick = onRedraw) { Text("Dibujar otra vez") }
                AppTextButton(onClick = { confirm = true }) { Text("Borrar") }
            }
        }
    }
}

/**
 * Un dibujo de fábrica. Muestra las palabras que de verdad lo traen: si una también es de un dibujo
 * tuyo, gana el tuyo y se avisa.
 */
@Composable
private fun FactoryCard(d: CustomDrawing, lexicon: SceneLexicon, dark: Boolean, paper: PaperStyle) {
    val scheme = MaterialTheme.colorScheme
    val (mine, taken) = remember(d, lexicon) { d.keys.partition { lexicon.owner(it)?.id == d.id } }
    DrawingCard(
        title = d.name.replaceFirstChar { it.uppercase() },
        words = mine,
        dark = dark,
        paper = paper,
        paint = { w, _ -> boxPreview(d, w) },
        key = d.id,
    ) {
        if (taken.isNotEmpty()) {
            Text(
                "Con " + taken.joinToString(", ") { "«$it»" } + " sale tu dibujo de Claude.",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** El dibujo solo, en su caja y llenando la tarjeta: en la franja entera se vería chiquito. */
private fun boxPreview(d: CustomDrawing, w: Float): List<Mark> {
    val stage = w * STAGE
    val side = stage * 0.92f
    return d.boxMarks(side, (w - side) / 2, stage * 0.04f, PREVIEW_TIME)
}

/** Tarjeta de un dibujo: la miniatura en el papel de la hoja, el nombre y sus palabras. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DrawingCard(
    title: String,
    words: List<String>,
    dark: Boolean,
    paper: PaperStyle,
    paint: (Float, Float) -> List<Mark>,
    key: Any,
    extra: @Composable () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.border(1.dp, scheme.outlineVariant)) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f / STAGE).paperBackground(paper)) {
            val w = with(LocalDensity.current) { maxWidth.toPx() }
            // La franja de una hoja tan ancha como la tarjeta (su alto no limita la unidad).
            val marks = produceState(emptyList<Mark>(), key, w, dark) {
                value = withContext(Dispatchers.Default) { paint(w, w / 0.62f) }
            }
            MarksCanvas(marks, dark, Modifier.fillMaxSize())
        }
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                words.forEach { AppTag(it) }
            }
            extra()
        }
    }
}

/** Alto de la franja respecto de su ancho (`SceneArt.stageHeight` con la unidad igual al ancho). */
private const val STAGE = 0.72f
