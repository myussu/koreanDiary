package com.jorge.koreandiary.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jorge.koreandiary.AppContainer
import com.jorge.koreandiary.data.ItemRef
import com.jorge.koreandiary.data.ItemSource
import com.jorge.koreandiary.data.ItemUsage
import com.jorge.koreandiary.data.LevelReport
import com.jorge.koreandiary.data.db.ItemType
import com.jorge.koreandiary.data.db.MemoryState
import com.jorge.koreandiary.data.standard.StdWord
import com.jorge.koreandiary.progress.LevelCoverage
import com.jorge.koreandiary.progress.LevelEstimate
import com.jorge.koreandiary.progress.VocabTest
import com.jorge.koreandiary.srs.Fsrs
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Las pestañas de Estudio. */
enum class StudyTab(val label: String) {
    SUMMARY("Resumen"),
    WORDS("Vocabulario"),
    GRAMMAR("Gramática"),
    PENDING("Por revisar"),
    TO_ADD("Para agregar"),
}

/**
 * Estudio: tu lista y tu progreso en un solo lugar (antes eran Estudio y Progreso).
 * - «Resumen»: «Tu nivel» (cuánto cubres de cada nivel del estándar, la prueba corta de vocabulario)
 *   y lo de Progreso (racha, calendario, memoria, tiempos, lo que más cuesta).
 * - «Vocabulario» y «Gramática»: la lista con buscador; al tocar un ítem, su memoria y tus oraciones.
 * - «Por revisar»: palabras que la app no reconoció o que preguntaste al escuchar.
 * - «Para agregar»: lo que te falta del estándar, de lo más básico arriba, con «Agregar» o «Ya lo sé».
 */
@Composable
fun StudyScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit = {},
    onQuickReview: () -> Unit = {},
    initialTab: StudyTab = StudyTab.SUMMARY,
) {
    val words by container.study.words.collectAsStateWithLifecycle(emptyList())
    val grammar by container.study.grammar.collectAsStateWithLifecycle(emptyList())
    val pending by container.study.pendingWords.collectAsStateWithLifecycle(emptyList())
    val states by container.study.memoryStates.collectAsStateWithLifecycle(emptyList())
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val stateMap = remember(states) { states.associateBy { it.itemType to it.itemId } }
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    // Se guardan al girar el celular (F5 de las pruebas): la búsqueda y el ítem abierto, por su código.
    var query by rememberSaveable { mutableStateOf("") }
    var openCode by rememberSaveable { mutableStateOf<String?>(null) }
    val open = openCode?.let { ItemRef.parse(it) }
    val q = query.trim().lowercase()
    val shownWords = remember(words, q) {
        if (q.isEmpty()) words else words.filter { w -> w.baseForm.contains(q) || w.meaning?.lowercase()?.contains(q) == true }
    }
    val shownGrammar = remember(grammar, q) {
        if (q.isEmpty()) grammar else grammar.filter { g ->
            g.pattern.lowercase().contains(q) || g.variants.lowercase().contains(q) || g.meaning?.lowercase()?.contains(q) == true
        }
    }
    val scope = rememberCoroutineScope()
    val now = remember { System.currentTimeMillis() }
    // «Tu nivel» y «Para agregar» se recalculan cuando cambia tu lista o tu repaso.
    val report by produceState<LevelReport?>(null, words.size, grammar.size, states.size, settings.standardLevel) {
        value = container.study.levelReport(maxLevel = settings.standardLevel + 1)
    }
    val vocabTest = remember(settings.vocabTest) { VocabTest.decode(settings.vocabTest) }
    var testing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppTextButton(onClick = onBack) { Text("‹ Diario") }
            Text("Estudio", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            AppButton(onClick = onImport) { Text("Importar") }
        }
        // Si no caben, se desvanecen en el borde y se deslizan (sin otra fila: más lugar para la lista).
        ScrollRow(Modifier.padding(vertical = 8.dp)) {
            StudyTab.entries.forEach { t ->
                val label = when (t) {
                    StudyTab.WORDS -> "${t.label} (${words.size})"
                    StudyTab.GRAMMAR -> "${t.label} (${grammar.size})"
                    StudyTab.PENDING -> "${t.label} (${pending.size})"
                    else -> t.label
                }
                AppToggle(label, tab == t) { tab = t; openCode = null }
            }
        }
        when (tab) {
            StudyTab.SUMMARY -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                QuickReviewCard(due = states.count { it.dueAt <= now }, hasItems = words.isNotEmpty() || grammar.isNotEmpty(), onStart = onQuickReview)
                LevelCard(report, vocabTest, settings.level, settings.standardLevel, onTest = { testing = true }, onSettings = onSettings)
                ProgressSummary(container)
                Box(Modifier.height(16.dp))
            }
            StudyTab.WORDS, StudyTab.GRAMMAR -> {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(if (tab == StudyTab.WORDS) "Buscar palabra o significado" else "Buscar gramática o significado") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                )
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (tab == StudyTab.WORDS) {
                        items(shownWords, key = { "w${it.id}" }) { w ->
                            val ref = ItemRef(ItemType.WORD, w.id)
                            ItemRow(
                                title = w.baseForm,
                                subtitle = listOfNotNull(
                                    w.type,
                                    w.level.takeUnless { it == "desconocido" },
                                    w.meaning,
                                    w.irregular?.let { "irregular $it" },
                                    sourceLabel(w.source),
                                ).joinToString(" · "),
                                memory = stateMap[ItemType.WORD to w.id], now = now,
                                expanded = open == ref,
                                onClick = { openCode = if (open == ref) null else ref.code },
                            ) { ItemDetail(container, ref, w.baseForm, stateMap[ItemType.WORD to w.id], now) }
                        }
                    } else {
                        items(shownGrammar, key = { "g${it.id}" }) { g ->
                            val ref = ItemRef(ItemType.GRAMMAR, g.id)
                            ItemRow(
                                title = g.pattern,
                                subtitle = listOfNotNull(g.level + (g.unit?.let { " · U$it" } ?: ""), g.meaning, sourceLabel(g.source)).joinToString(" · "),
                                memory = stateMap[ItemType.GRAMMAR to g.id], now = now,
                                expanded = open == ref,
                                onClick = { openCode = if (open == ref) null else ref.code },
                            ) { ItemDetail(container, ref, g.pattern, stateMap[ItemType.GRAMMAR to g.id], now) }
                        }
                    }
                }
            }
            StudyTab.PENDING -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                if (pending.isEmpty()) {
                    item { EmptyNote("Nada por revisar: aquí caen las palabras que la app no reconoció y las que preguntaste al escuchar.") }
                }
                items(pending, key = { "p${it.id}" }) { p ->
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
            StudyTab.TO_ADD -> ToAddTab(container, report, vocabTest, onTest = { testing = true })
        }
    }

    if (testing) {
        VocabTestDialog(
            container,
            onDone = { result ->
                testing = false
                if (result != null) container.settings.update { it.copy(vocabTest = result.encode()) }
            },
        )
    }
}

// ---------------------------------------------------------------- Repaso rápido

/** La entrada al repaso rápido: unas diez oraciones para completar con lo que te toca repasar. */
@Composable
private fun QuickReviewCard(due: Int, hasItems: Boolean, onStart: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().border(1.dp, scheme.outlineVariant).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("REPASO RÁPIDO", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            Text(
                when {
                    !hasItems -> "Cuando tengas una lista, aquí completas oraciones con lo que te toca repasar."
                    due == 0 -> "Nada vence hoy: repasa lo que nunca usaste."
                    else -> "$due ${if (due == 1) "te toca" else "te tocan"} hoy. Unas diez oraciones con un espacio en blanco, a mano."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        AppButton(onClick = onStart, enabled = hasItems) { Text("Repaso rápido") }
    }
}

// ---------------------------------------------------------------- Tu nivel

/**
 * «Tu nivel»: el estimado por la gramática (y por la prueba de vocabulario, si la hiciste), cuánto
 * cubres de cada nivel y la comparación con tu nivel de Ajustes. Solo propone: no cambia nada.
 */
@Composable
private fun LevelCard(
    report: LevelReport?,
    test: VocabTest?,
    levelSetting: String,
    standardLevel: Int,
    onTest: () -> Unit,
    onSettings: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().border(1.dp, scheme.outlineVariant).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("TU NIVEL", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        if (report == null) {
            Text("Calculando…", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        val byGrammar = LevelEstimate.estimate(report.grammar)
        val byWords = test?.let { LevelEstimate.estimateFromTest(it.known) }
        Text(
            if (byGrammar == 0) "Todavía no tienes en tu lista el 70 % de la gramática del nivel 1."
            else "Por la gramática de tu lista: nivel $byGrammar del estándar.",
            style = MaterialTheme.typography.titleMedium,
        )
        if (byWords != null) {
            Text(
                if (byWords == 0) "En la prueba de vocabulario no llegaste al 70 % del nivel 1." else "Por la prueba de vocabulario: nivel $byWords.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        // Una fila por nivel: gramática en tu lista / usada bien, y lo que conociste en la prueba.
        Row(Modifier.padding(top = 4.dp)) {
            Text("", Modifier.width(56.dp))
            Text("Gramática", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("Vocabulario", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        }
        report.grammar.forEach { g ->
            val w = report.words.firstOrNull { it.level == g.level }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Nivel ${g.level}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
                CoverageCell(g, Modifier.weight(1f))
                Column(Modifier.weight(1f)) {
                    val share = test?.known?.get(g.level)
                    Text(
                        if (share != null) "prueba ${(share * 100).roundToInt()} %" else "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (share != null && share >= LevelEstimate.THRESHOLD) scheme.tertiary else scheme.onSurface,
                    )
                    if (w != null) Text("${w.inList} de ${w.total} en tu lista", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
        }
        Text(
            "«En tu lista» no quiere decir que lo sepas: lo que importaste y nunca usaste también cuenta. Por eso va aparte lo que ya usaste bien.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        // Lo que se propone sale de la gramática (se puede cubrir entera); la prueba solo si aún no hay gramática.
        val estimate = if (byGrammar > 0) byGrammar else byWords ?: 0
        if (estimate > 0 && estimate != standardLevel) {
            Text(
                "En Ajustes tienes «$levelSetting» (nivel $standardLevel del estándar). Si te parece, cámbialo en Ajustes › General: las sugerencias y las correcciones usan ese nivel.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.secondary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppOutlinedButton(onClick = onTest) { Text(if (test == null) "Prueba de vocabulario" else "Repetir la prueba") }
            if (estimate > 0 && estimate != standardLevel) AppTextButton(onClick = onSettings) { Text("Ajustes") }
        }
        if (test == null) {
            Text(
                "Unas ${LevelEstimate.SAMPLE_PER_LEVEL} palabras al azar por nivel: tocas las que conoces. Dos o tres minutos.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CoverageCell(c: LevelCoverage, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier) {
        Text(
            "${c.inList} de ${c.total}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (c.listShare >= LevelEstimate.THRESHOLD) FontWeight.SemiBold else FontWeight.Normal,
            color = if (c.listShare >= LevelEstimate.THRESHOLD) scheme.tertiary else scheme.onSurface,
        )
        Text("${c.usedWell} ${if (c.usedWell == 1) "usada" else "usadas"} bien", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
    }
}

/**
 * La prueba corta: por nivel, palabras al azar que no están en tu lista; tocas las que conoces.
 * Al terminar (o con «Terminar» antes) devuelve lo que conociste por nivel y las que no.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VocabTestDialog(container: AppContainer, onDone: (VocabTest?) -> Unit) {
    val sample by produceState<Map<Int, List<StdWord>>?>(null) { value = container.study.vocabSample() }
    var level by remember { mutableIntStateOf(1) }
    val known = remember { mutableStateMapOf<String, Boolean>() }
    val shares = remember { mutableStateMapOf<Int, Float>() }
    val unknown = remember { mutableStateMapOf<String, Int>() }

    fun finishLevel() {
        val words = sample?.get(level) ?: return
        // Sin palabras para preguntar es que ya tienes en tu lista todas las de ese nivel.
        shares[level] = if (words.isEmpty()) 1f else words.count { known[it.word] == true }.toFloat() / words.size
        words.filter { known[it.word] != true }.forEach { unknown[it.word] = level }
    }
    fun result(): VocabTest? = if (shares.isEmpty()) null
    else VocabTest(System.currentTimeMillis(), shares.toMap(), unknown.entries.sortedBy { it.value }.map { it.key })

    AlertDialog(
        onDismissRequest = { onDone(null) },
        // Son seis niveles: un toque fuera del recuadro no descarta lo que ya marcaste.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("Nivel $level de 6: toca las que conoces") },
        text = {
            val words = sample?.get(level)
            if (words == null) {
                Text("Preparando…")
            } else if (words.isEmpty()) {
                Text("Ya tienes en tu lista todas las palabras que podría preguntarte de este nivel.")
            } else {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        words.forEach { w -> AppToggle(w.word, known[w.word] == true) { known[w.word] = known[w.word] != true } }
                    }
                    Text(
                        "Cuenta como conocida si sabes qué significa sin pensarlo mucho.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            AppButton(onClick = {
                finishLevel()
                if (level < 6) level++ else onDone(result())
            }, enabled = sample != null) { Text(if (level < 6) "Siguiente nivel" else "Listo") }
        },
        dismissButton = {
            AppTextButton(onClick = { onDone(result()) }) { Text(if (shares.isEmpty()) "Cancelar" else "Terminar aquí") }
        },
    )
}

// ---------------------------------------------------------------- Para agregar

/**
 * Lo que te falta del estándar, de lo más básico arriba: la gramática de los niveles hasta el tuyo y
 * uno más, y las palabras que no conociste en la prueba. «Agregar» la suma como nueva (la app te la
 * va a sugerir); «Ya lo sé», como conocida (vuelve mucho después, para confirmarla).
 */
@Composable
private fun ToAddTab(container: AppContainer, report: LevelReport?, test: VocabTest?, onTest: () -> Unit) {
    val scope = rememberCoroutineScope()
    var showWords by rememberSaveable { mutableStateOf(false) }
    // Lo que ya agregaste en esta pantalla desaparece enseguida, sin esperar el recálculo.
    val done = remember { mutableStateMapOf<String, Boolean>() }
    val missingWords by produceState(emptyList<StdWord>(), test) { value = container.study.missingWords(test?.unknown.orEmpty()) }

    fun add(ref: ItemRef, known: Boolean) {
        done[ref.code] = true
        scope.launch { container.study.addStandard(ref, known, System.currentTimeMillis()) }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
        AppToggle("Gramática", !showWords) { showWords = false }
        AppToggle("Vocabulario", showWords) { showWords = true }
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        if (!showWords) {
            val list = report?.missingGrammar.orEmpty().filter { "sg${it.id}" !in done }
            if (report == null) item { EmptyNote("Calculando…") }
            else if (list.isEmpty()) item { EmptyNote("Tienes en tu lista toda la gramática del estándar hasta tu nivel y uno más.") }
            items(list, key = { "sg${it.id}" }) { g ->
                AddRow(g.form, "Nivel ${g.level}" + (g.meaning.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")) { known ->
                    add(ItemRef(ItemType.STD_GRAMMAR, g.id.toLong()), known)
                }
            }
        } else {
            val list = missingWords.filter { "sv${it.id}" !in done }
            if (test == null) {
                item {
                    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        EmptyNote("Las palabras que te faltan salen de la prueba corta de vocabulario: las que no conociste.")
                        AppOutlinedButton(onClick = onTest) { Text("Prueba de vocabulario") }
                    }
                }
            } else if (list.isEmpty()) {
                item { EmptyNote("Agregaste o ya tenías todas las que no conociste en la prueba. Puedes repetirla en «Resumen».") }
            }
            items(list, key = { "sv${it.id}" }) { w ->
                AddRow(w.word, "Nivel ${w.level} · ${w.type}" + (w.guide.takeIf { it.isNotBlank() }?.let { " · «$it»" } ?: "")) { known ->
                    add(ItemRef(ItemType.STD_WORD, w.id.toLong()), known)
                }
            }
        }
    }
}

@Composable
private fun AddRow(title: String, subtitle: String, onAdd: (known: Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        AppTextButton(onClick = { onAdd(true) }) { Text("Ya lo sé") }
        AppButton(onClick = { onAdd(false) }) { Text("Agregar") }
    }
    HorizontalDivider()
}

@Composable
private fun EmptyNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
}

// ---------------------------------------------------------------- Lista

@Composable
private fun ItemRow(
    title: String,
    subtitle: String,
    memory: MemoryState?,
    now: Long,
    expanded: Boolean,
    onClick: () -> Unit,
    detail: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(memoryLabel(memory, now), style = MaterialTheme.typography.labelMedium)
        }
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        if (expanded) detail()
    }
    HorizontalDivider()
}

/** Al tocar un ítem: su memoria y tus oraciones con él (de las páginas donde lo usaste). */
@Composable
private fun ItemDetail(container: AppContainer, ref: ItemRef, label: String, memory: MemoryState?, now: Long) {
    val scheme = MaterialTheme.colorScheme
    val usage by produceState<ItemUsage?>(null, ref) { value = container.study.itemUsage(ref, label) }
    Column(Modifier.padding(top = 6.dp, start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (memory == null) "Nunca la usaste: la app te la va a sugerir."
            else "Usada ${memory.reps} ${if (memory.reps == 1) "vez" else "veces"}" +
                (if (memory.lapses > 0) " · ${memory.lapses} ${if (memory.lapses == 1) "fallo" else "fallos"}" else "") +
                " · ${memoryLabel(memory, now)}",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        val u = usage
        when {
            u == null -> Text("…", style = MaterialTheme.typography.bodySmall)
            u.sentences.isEmpty() && u.corrections.isEmpty() -> Text(
                "Todavía no hay oraciones tuyas con ella en el diario o las escenas.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            else -> {
                if (u.sentences.isNotEmpty()) Text("TUS ORACIONES", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                u.sentences.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (u.corrections.isNotEmpty()) Text("TE CORRIGIERON", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                u.corrections.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.error) }
            }
        }
    }
}

private fun sourceLabel(source: String): String? = when (source) {
    ItemSource.DIARY -> "detectada en el diario"
    ItemSource.GAP -> "laguna cubierta"
    ItemSource.WORKBOOK -> "vista en el workbook"
    ItemSource.STANDARD -> "del estándar"
    ItemSource.REVIEW -> "buscada en el repaso rápido"
    else -> null
}

private fun memoryLabel(m: MemoryState?, now: Long): String {
    if (m == null) return "nuevo"
    val r = Fsrs.retrievabilityAt(Fsrs.Memory(m.stability, m.difficulty, m.lastReviewAt, m.reps, m.lapses), now)
    val days = ((m.dueAt - now).toDouble() / Fsrs.DAY_MS)
    val due = if (days <= 0) "toca repasar" else "en ${days.roundToInt().coerceAtLeast(1)} d"
    return "${(r * 100).roundToInt()} % · $due"
}
