package com.jorge.koreandiary.ui.conversation

import android.os.Build
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.jorge.koreandiary.conversation.ConversationRecord
import com.jorge.koreandiary.conversation.Speaker
import com.jorge.koreandiary.listen.ListenEngine
import com.jorge.koreandiary.listen.ListenPhase
import com.jorge.koreandiary.listen.ListenScript
import com.jorge.koreandiary.listen.ListenScriptParser
import com.jorge.koreandiary.listen.ListenState
import com.jorge.koreandiary.ui.AppButton
import com.jorge.koreandiary.ui.AppOutlinedButton
import com.jorge.koreandiary.ui.AppTag
import com.jorge.koreandiary.ui.AppTextButton
import com.jorge.koreandiary.ui.AppToggle
import com.jorge.koreandiary.voice.TurnMode
import org.json.JSONObject

// ---------------------------------------------------------------- Preparación

/** Campos del modo Escuchar en la preparación (el resto —objetivos, botón— es común). */
@Composable
internal fun ListenSetupFields(st: ConvUiState, vm: ConversationViewModel, recentTopics: List<String>) {
    Label("Tema")
    OutlinedTextField(
        value = st.listenTopic,
        onValueChange = vm::setListenTopic,
        label = { Text("¿Sobre qué quieres escuchar?") },
        placeholder = { Text("Cómo celebran Chuseok los jóvenes") },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        AppOutlinedButton(onClick = vm::dictateTopic) { Text(if (st.dictatingTopic) "Escuchando… (parar)" else "Dictar") }
        Text("Vacío = un tema cotidiano.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (recentTopics.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            recentTopics.forEach { t -> AppTag(t, selected = t == st.listenTopic.trim(), onClick = { vm.setListenTopic(t) }) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Tema actual (buscar en Google)")
            Text(
                "Para noticias o algo reciente. Puede no estar disponible en la capa gratuita: si falla, se escribe sin buscar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = st.listenSearch, onCheckedChange = vm::setListenSearch)
    }
    Label("Duración")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(5, 10, 15).forEach { m -> AppToggle("$m min", st.listenMinutes == m) { vm.setListenMinutes(m) } }
    }
}

// ---------------------------------------------------------------- En vivo

@Composable
internal fun ListenLiveContent(ls: ListenState, engine: ListenEngine, conv: ConvUiState, vm: ConversationViewModel, blur: Boolean) {
    val script = ls.script
    val listState = rememberLazyListState()
    val lastLength = ls.lines.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(ls.lines.size, lastLength) {
        if (ls.lines.isNotEmpty()) listState.animateScrollToItem(ls.lines.lastIndex)
    }
    LaunchedEffect(ls.message) {
        if (ls.message != null) {
            kotlinx.coroutines.delay(4000)
            engine.dismissMessage()
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(script?.title?.ifBlank { null } ?: ls.topic, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${clock(ls.elapsedSec)} / ${clock(ls.totalSec)} · ${if (ls.connected) "IA lista" else "IA conectando…"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AppOutlinedButton(onClick = { engine.finish() }, enabled = ls.phase != ListenPhase.CLOSING) { Text("Terminar") }
        }

        if (script != null) {
            SegmentBar(script.segments.size, ls.segment, ls.phase)
        }
        Text(
            when {
                ls.phase == ListenPhase.GAP && ls.gapLeft > 0 -> "Sigue en ${ls.gapLeft} s…"
                else -> ls.status
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 6.dp),
        )

        if (ls.targets.isNotEmpty()) TargetRow(ls, conv, vm)

        // El texto del segmento: borroso hasta tocarlo, con traducción a pedido y palabras para tocar.
        val segment = script?.segments?.getOrNull(ls.segment)
        if (segment != null && ls.phase != ListenPhase.PREPARING) {
            SegmentCard(segment.ko, segment.es, blur, key = ls.segment) { w -> vm.lookup(w, segment.ko) }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ls.lines, key = { it.id }) { line ->
                if (line.speaker == Speaker.USER) UserBubble(line.text)
                else AiBubble(line.text, blur = blur, onWord = { w -> vm.lookup(w, line.text) })
            }
        }

        CoachPanelView(conv.panel, onMore = {}, onClose = vm::closePanel, onExplain = vm::explainTarget, hasCoach = conv.hasCoach)

        if (ls.phase == ListenPhase.COMPREHENSION && script != null && script.comprehension.isNotEmpty()) {
            Text(
                "Comprensión: ${ls.comprehension.count { it.correct }} de ${script.comprehension.size} bien" +
                    " (${ls.comprehension.size} respondidas)",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        ls.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        val compact = PaddingValues(horizontal = 6.dp, vertical = 10.dp)
        val canControl = script != null && ls.phase != ListenPhase.PREPARING && ls.phase != ListenPhase.CLOSING
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AppOutlinedButton(onClick = { engine.repeat() }, enabled = canControl, modifier = Modifier.weight(1f), contentPadding = compact) { Text("Repetir") }
            AppOutlinedButton(onClick = { engine.setSpeed(ls.speed - 0.05f) }, enabled = ls.speed > 0.61f, modifier = Modifier.weight(0.8f), contentPadding = compact) { Text("− Lento") }
            Box(Modifier.weight(0.7f).align(Alignment.CenterVertically), contentAlignment = Alignment.Center) {
                Text("%.2f×".format(ls.speed), style = MaterialTheme.typography.labelLarge)
            }
            AppOutlinedButton(onClick = { engine.setSpeed(ls.speed + 0.05f) }, enabled = ls.speed < 1.19f, modifier = Modifier.weight(0.8f), contentPadding = compact) { Text("+ Rápido") }
            AppOutlinedButton(onClick = { engine.next() }, enabled = canControl, modifier = Modifier.weight(1f), contentPadding = compact) { Text("Siguiente") }
        }

        val narrating = ls.phase == ListenPhase.NARRATING || ls.phase == ListenPhase.GAP
        val ptt = ls.turnMode == TurnMode.PUSH_TO_TALK
        val hint = when {
            ls.phase == ListenPhase.PREPARING -> "Preparando… sonará un tono cuando empiece."
            narrating -> "Pausa para preguntar lo que no entendiste (en coreano o en español)."
            ls.phase.interactive && !ls.connected -> "La IA se está conectando…"
            ls.phase.interactive && ptt && ls.talking -> "Te escucho… pulsa «Listo» cuando termines."
            ls.phase.interactive && ptt -> "Pulsa «Hablar» para preguntar o responder. Di «sigue» o pulsa «Seguir» para continuar."
            ls.phase.interactive -> "Te escucho: pregunta o responde. Di «sigue» para continuar."
            else -> ""
        }
        if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))

        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (ls.phase.interactive && ptt) {
                AppButton(
                    onClick = { engine.toggleTalk() },
                    enabled = ls.connected,
                    modifier = Modifier.weight(1.4f),
                    contentPadding = PaddingValues(vertical = 20.dp),
                ) { Text(if (ls.talking) "Listo" else "Hablar", style = MaterialTheme.typography.titleMedium) }
                if (ls.phase != ListenPhase.COMPREHENSION) {
                    AppOutlinedButton(onClick = { engine.resume() }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 20.dp)) {
                        Text("Seguir", style = MaterialTheme.typography.titleMedium)
                    }
                }
            } else {
                AppButton(
                    onClick = { engine.togglePause() },
                    enabled = narrating || (ls.phase.interactive && ls.phase != ListenPhase.COMPREHENSION),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 20.dp),
                ) { Text(if (narrating) "Pausa" else "Seguir", style = MaterialTheme.typography.titleMedium) }
            }
        }
        Text(
            "Audífonos: 1 toque pausa${if (ptt) " (en pausa: hablar/listo)" else " o sigue"} · 2 toques sigue · 3 toques repite. Funciona con la pantalla apagada.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

/** Un cuadrado por segmento: lleno los escuchados, con borde grueso el actual. */
@Composable
private fun SegmentBar(count: Int, current: Int, phase: ListenPhase) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(count) { i ->
            val done = i < current || (i == current && phase != ListenPhase.NARRATING && phase != ListenPhase.PREPARING)
            Box(
                Modifier.weight(1f).height(8.dp)
                    .background(if (done) scheme.primary else scheme.surfaceContainerHigh)
                    .border(if (i == current) 2.dp else 1.dp, if (i == current) scheme.primary else scheme.outlineVariant)
            )
        }
    }
}

@Composable
private fun TargetRow(ls: ListenState, conv: ConvUiState, vm: ConversationViewModel) {
    val ordered = ls.targets.sortedByDescending { it.ref == ls.floating }
    val bob = rememberInfiniteTransition(label = "flotar")
    val lift by bob.animateFloat(
        initialValue = 0f, targetValue = -6f,
        animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse), label = "altura",
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 2.dp).horizontalScroll(rememberScrollState()).padding(top = 8.dp),
    ) {
        ordered.forEach { t ->
            val count = ls.heard[t.ref] ?: 0
            val floating = t.ref == ls.floating
            val open = (conv.panel as? CoachPanel.TargetShown)?.item?.ref == t.ref
            Box(if (floating) Modifier.offset { IntOffset(0, lift.dp.roundToPx()) } else Modifier) {
                AppTag(
                    when {
                        floating -> "↑ ${t.label}"
                        count > 0 -> "${t.label} ×$count"
                        else -> t.label
                    },
                    selected = open || floating,
                    accent = count > 0,
                    onClick = { vm.showTarget(t.ref) },
                )
            }
        }
    }
    if (ls.floating != null) {
        Text(
            "Buena oportunidad para usar «${ls.targets.firstOrNull { it.ref == ls.floating }?.label.orEmpty()}» en tu respuesta.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** El segmento actual: borroso hasta tocarlo (practicas el oído), palabras tocables y traducción. */
@Composable
private fun SegmentCard(ko: String, es: String, blur: Boolean, key: Int, onWord: (String) -> Unit) {
    var revealed by rememberSaveable(key) { mutableStateOf(!blur) }
    var spanish by rememberSaveable(key) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).border(1.dp, scheme.outlineVariant).heightIn(max = 180.dp),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!revealed) {
                val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                Text(
                    if (canBlur) ko else "··· toca para ver el texto",
                    modifier = Modifier.clickable { revealed = true }.let { if (canBlur) it.blur(6.dp) else it },
                )
            } else {
                val linkStyle = TextLinkStyles(SpanStyle(color = scheme.onSurface))
                val annotated = remember(ko) {
                    buildAnnotatedString {
                        ko.split(Regex("(?<=\\s)|(?=\\s)")).forEach { token ->
                            if (token.any { it in '가'..'힣' }) {
                                withLink(LinkAnnotation.Clickable(tag = token, styles = linkStyle, linkInteractionListener = LinkInteractionListener { onWord(token) })) {
                                    append(token)
                                }
                            } else append(token)
                        }
                    }
                }
                Text(annotated)
                if (spanish && es.isNotBlank()) Text(es, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                if (es.isNotBlank()) {
                    AppTextButton(onClick = { spanish = !spanish }) { Text(if (spanish) "Ocultar traducción" else "Ver traducción") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Revisión

/** Parte de la revisión propia del modo Escuchar: comprensión, objetivos escuchados y el guion. */
@Composable
internal fun ListenReviewSection(r: ConversationRecord) {
    val script: ListenScript? = remember(r.scriptRaw) {
        r.scriptRaw?.let { runCatching { ListenScriptParser.decode(JSONObject(it)) }.getOrNull() }
    }
    val scheme = MaterialTheme.colorScheme
    if (script != null) {
        Text(script.title, style = MaterialTheme.typography.titleMedium)
        if (script.titleEs.isNotBlank()) Text(script.titleEs, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
    Text(
        "Escuchaste ${r.segmentsHeard} de ${script?.segments?.size ?: "?"} segmentos · ${r.repeats} repeticiones · ${r.pauses} pausas · " +
            "voz: ${r.narrator.ifBlank { "—" }}",
        style = MaterialTheme.typography.bodySmall,
    )
    if (script != null && script.comprehension.isNotEmpty()) {
        Label("Comprensión")
        script.comprehension.forEachIndexed { i, q ->
            val result = r.comprehension.firstOrNull { it.index == i + 1 }
            Column(Modifier.fillMaxWidth().border(1.dp, scheme.outlineVariant).padding(10.dp)) {
                Text(
                    "${when (result?.correct) { true -> "✓"; false -> "✗"; null -> "·" }} ${q.ko}",
                    fontWeight = FontWeight.SemiBold,
                )
                if (q.es.isNotBlank()) Text(q.es, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                result?.note?.takeIf { it.isNotBlank() }?.let { Text("Respondiste: $it", style = MaterialTheme.typography.bodySmall) }
                Text("Respuesta: ${q.answer}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (r.heard.isNotEmpty() || r.targets.isNotEmpty()) {
        Label("Objetivos escuchados")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            r.targets.forEach { t ->
                val n = r.heard[t.ref] ?: 0
                AppTag(if (n > 0) "${t.label} ×$n" else t.label, accent = n > 0)
            }
        }
    }
    if (script != null) {
        var open by rememberSaveable(r.id) { mutableStateOf(false) }
        Label("Guion")
        if (!open) {
            AppOutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("Ver el texto con traducción") }
        } else {
            script.segments.forEachIndexed { i, seg ->
                Column(Modifier.fillMaxWidth().border(1.dp, scheme.outlineVariant).padding(10.dp)) {
                    Text("${i + 1}. ${seg.ko}")
                    if (seg.es.isNotBlank()) Text(seg.es, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    seg.question?.let { q -> Text("Pregunta: ${q.ko}${if (q.es.isNotBlank()) " (${q.es})" else ""}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Barra en otras pantallas

/** Escucha en curso vista desde otra pantalla: estado, pausa y volver a la sesión. */
@Composable
fun ListenMiniBar(ls: ListenState, engine: ListenEngine, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val narrating = ls.phase == ListenPhase.NARRATING || ls.phase == ListenPhase.GAP
    Surface(
        color = scheme.primaryContainer,
        contentColor = scheme.onPrimaryContainer,
        modifier = modifier.fillMaxWidth().border(1.dp, scheme.outline).clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Escuchando", style = MaterialTheme.typography.labelMedium)
                Text(ls.status, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            if (narrating || (ls.phase.interactive && ls.phase != ListenPhase.COMPREHENSION)) {
                AppTextButton(onClick = { engine.togglePause() }) { Text(if (narrating) "Pausa" else "Seguir") }
            }
            AppTextButton(onClick = onOpen) { Text("Abrir") }
        }
    }
}
