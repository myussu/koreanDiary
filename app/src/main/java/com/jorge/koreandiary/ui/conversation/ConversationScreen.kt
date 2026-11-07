package com.jorge.koreandiary.ui.conversation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jorge.koreandiary.AppContainer
import com.jorge.koreandiary.conversation.ConversationMath
import com.jorge.koreandiary.conversation.ConversationRecord
import com.jorge.koreandiary.conversation.DrillResult
import com.jorge.koreandiary.conversation.Speaker
import com.jorge.koreandiary.ui.AppButton
import com.jorge.koreandiary.ui.AppOutlinedButton
import com.jorge.koreandiary.ui.AppTag
import com.jorge.koreandiary.ui.AppTextButton
import com.jorge.koreandiary.ui.AppToggle
import com.jorge.koreandiary.voice.TurnMode
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormat = DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale.forLanguageTag("es"))

private fun formatDate(ms: Long) = dateFormat.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private fun clock(sec: Int) = "%d:%02d".format(sec.coerceAtLeast(0) / 60, sec.coerceAtLeast(0) % 60)

/** Conversación por voz con Gemini: preparación, sesión en vivo y revisión. */
@Composable
fun ConversationScreen(container: AppContainer, onBack: () -> Unit) {
    val vm: ConversationViewModel = viewModel(key = "conversation") { ConversationViewModel(container) }
    val st by vm.state.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.start() else vm.showMessage("Sin permiso de micrófono no se puede conversar. Puedes darlo en los ajustes del celular.")
    }

    // Al entrar (no al rotar): objetivos y minutos de hoy al día.
    var entered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!entered) {
            entered = true
            vm.onEnter()
        }
    }
    fun startWithPermission() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.start() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Sin servicio en primer plano: al salir de la app se deja de enviar audio.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onBackground() }

    val live = st.stage == ConvStage.Live
    val view = LocalView.current
    DisposableEffect(live) {
        view.keepScreenOn = live
        onDispose { view.keepScreenOn = false }
    }

    // En la sesión los avisos (p. ej. "vuelve a ir atrás") desaparecen solos.
    LaunchedEffect(st.message, live) {
        if (live && st.message != null) {
            delay(3000)
            vm.dismissMessage()
        }
    }

    // Un gesto de "atrás" sin querer no debe terminar la sesión: hay que hacerlo dos veces seguidas.
    var backArmedAt by remember { mutableStateOf(0L) }
    BackHandler(enabled = live) {
        val now = System.currentTimeMillis()
        if (now - backArmedAt < 2500) vm.end() else {
            backArmedAt = now
            vm.showMessage("Vuelve a ir atrás para terminar la sesión.")
        }
    }
    BackHandler(enabled = st.stage == ConvStage.Review) { vm.backToSetup() }

    when (st.stage) {
        ConvStage.Setup -> SetupContent(
            container, st, vm,
            onBack = onBack,
            onStart = ::startWithPermission,
            turnModeLabel = if (settings.voiceTurnMode == TurnMode.AUTO) "Automático (${settings.voiceSilenceMs / 1000.0} s de silencio)" else "Pulsar para hablar",
            dailyMinutes = settings.voiceDailyMinutes,
        )
        ConvStage.Live -> LiveContent(st, vm, blur = settings.voiceBlurText)
        ConvStage.Review -> ReviewContent(st, vm)
    }
}

// ---------------------------------------------------------------- Preparación

@Composable
private fun SetupContent(
    container: AppContainer,
    st: ConvUiState,
    vm: ConversationViewModel,
    onBack: () -> Unit,
    onStart: () -> Unit,
    turnModeLabel: String,
    dailyMinutes: Int,
) {
    val sessions by container.conversations.sessions.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppTextButton(onClick = onBack) { Text("‹ Diario") }
            Text("Conversación", style = MaterialTheme.typography.titleLarge)
        }
        if (!st.hasVoiceKey) {
            Note("Necesitas tu clave de Gemini en Ajustes: la conversación usa la misma clave gratuita que el diario.")
        }
        if (!st.hasCoach) {
            Note("Sin Gemini o Azure configurado no habrá pistas, traducciones ni revisión.")
        }

        Label("Tema")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            AppToggle("Libre", st.topic == Topic.FREE) { vm.setTopic(Topic.FREE) }
            if (st.diaryToday != null) AppToggle("Mi diario de hoy", st.topic == Topic.DIARY) { vm.setTopic(Topic.DIARY) }
            AppToggle("Otro", st.topic == Topic.OTHER) { vm.setTopic(Topic.OTHER) }
        }
        if (st.topic == Topic.OTHER) {
            OutlinedTextField(
                value = st.customTopic, onValueChange = vm::setCustomTopic, singleLine = true,
                label = { Text("¿De qué quieres hablar?") }, modifier = Modifier.fillMaxWidth(),
            )
        }

        Label("Duración")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(5, 10, 15, 20).forEach { m -> AppToggle("$m min", st.minutes == m) { vm.setMinutes(m) } }
        }
        Text(
            "Hoy: ${ConversationMath.format(st.secondsToday)} de $dailyMinutes min · Turnos: $turnModeLabel (cámbialo en Ajustes)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Label("Objetivos de repaso")
        when {
            st.loadingTargets -> Text("Cargando…", style = MaterialTheme.typography.bodySmall)
            st.targets.isEmpty() -> Text("Tu lista de estudio está vacía: la conversación será libre.", style = MaterialTheme.typography.bodySmall)
            else -> {
                val open = (st.panel as? CoachPanel.TargetShown)?.item?.ref
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    st.targets.forEach { t ->
                        val excluded = t.ref in st.excluded
                        AppTag(
                            if (excluded) "✕ ${t.label}" else t.label,
                            selected = t.ref == open,
                            accent = !excluded,
                            onClick = { vm.showTarget(t.ref) },
                        )
                    }
                }
                if (st.panel is CoachPanel.TargetShown || st.panel is CoachPanel.Loading || st.panel is CoachPanel.Error) {
                    val ref = open
                    CoachPanelView(
                        st.panel, onMore = vm::hint, onClose = vm::closePanel, onExplain = vm::explainTarget, hasCoach = st.hasCoach,
                        includeLabel = ref?.let { if (it in st.excluded) "Volver a incluir" else "Quitar de esta sesión" },
                        onToggleInclude = { ref?.let(vm::toggleTarget) },
                    )
                }
                Text(
                    "La IA te hará preguntas donde los necesites. Toca uno para ver qué significa o quitarlo. Tu lista de estudio no cambia con la conversación.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        AppButton(
            onClick = onStart,
            enabled = st.hasVoiceKey && !st.loadingTargets,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 14.dp),
        ) { Text("Empezar") }
        Text(
            "Usa audífonos si eliges el modo automático: así la IA no se escucha a sí misma.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        st.message?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        if (sessions.isNotEmpty()) {
            Label("Sesiones anteriores")
            sessions.forEach { r -> SessionRow(r) { vm.openReview(r.id) } }
        }
        Spacer(Modifier.padding(bottom = 16.dp))
    }
}

@Composable
private fun SessionRow(r: ConversationRecord, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(formatDate(r.startedAt), style = MaterialTheme.typography.bodyMedium)
            Text(
                "${ConversationMath.format(r.durationSec)} · ${r.userLines} intervenciones · ${r.topic}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(if (r.reviewRaw != null) "Revisada ✓" else "Sin revisar", style = MaterialTheme.typography.labelMedium)
    }
}

// ---------------------------------------------------------------- En vivo

@Composable
private fun LiveContent(st: ConvUiState, vm: ConversationViewModel, blur: Boolean) {
    val listState = rememberLazyListState()
    val lastLength = st.lines.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(st.lines.size, lastLength) {
        if (st.lines.isNotEmpty()) listState.animateScrollToItem(st.lines.lastIndex)
    }
    val userText = remember(st.lines) { st.lines.filter { it.speaker == Speaker.USER }.joinToString(" ") { it.text } }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${st.status}  ${clock(st.elapsedSec)} / ${clock(st.totalSec)}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Tokens: ${st.tokensTotal} · contexto ${st.contextTokens}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AppOutlinedButton(onClick = { vm.end() }) { Text("Terminar") }
        }
        if (st.activeTargets.isNotEmpty()) {
            // El objetivo que la IA acaba de pedir va primero y flota (sube y baja) hasta que respondas.
            val ordered = st.activeTargets.sortedByDescending { it.ref == st.floating }
            val bob = rememberInfiniteTransition(label = "flotar")
            val lift by bob.animateFloat(
                initialValue = 0f, targetValue = -6f,
                animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse), label = "altura",
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                // El espacio va dentro del scroll: si no, el recorte cortaría el objetivo al subir.
                modifier = Modifier.padding(top = 2.dp).horizontalScroll(rememberScrollState()).padding(top = 8.dp),
            ) {
                ordered.forEach { t ->
                    val seen = t.kind != "gramática" && ConversationMath.wordSeen(t.label, userText)
                    val open = (st.panel as? CoachPanel.TargetShown)?.item?.ref == t.ref
                    val floating = t.ref == st.floating
                    Box(if (floating) Modifier.offset { IntOffset(0, lift.dp.roundToPx()) } else Modifier) {
                        AppTag(
                            when {
                                floating -> "↑ ${t.label}"
                                seen -> "✓ ${t.label}"
                                else -> t.label
                            },
                            selected = open || floating,
                            accent = seen,
                            onClick = { vm.showTarget(t.ref) },
                        )
                    }
                }
            }
            if (st.floating != null) {
                Text(
                    "Buena oportunidad para usar «${st.targets.firstOrNull { it.ref == st.floating }?.label.orEmpty()}». Tócalo si no recuerdas qué significa.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(st.lines, key = { it.id }) { line ->
                if (line.speaker == Speaker.USER) UserBubble(line.text)
                else AiBubble(line.text, blur = blur, onWord = { w -> vm.lookup(w, line.text) })
            }
        }

        CoachPanelView(
            st.panel, onMore = vm::hint, onClose = vm::closePanel, onExplain = vm::explainTarget, hasCoach = st.hasCoach,
            howTo = HowToActions(onText = vm::setHowToText, onDictate = vm::dictateHowTo, onAsk = vm::askHowTo, onHear = vm::hear),
        )

        val compact = PaddingValues(horizontal = 6.dp, vertical = 10.dp)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AppOutlinedButton(onClick = vm::hint, enabled = st.lines.any { it.speaker == Speaker.AI }, modifier = Modifier.weight(1f), contentPadding = compact) { Text("Pista") }
            AppOutlinedButton(onClick = vm::openHowTo, modifier = Modifier.weight(1.3f), contentPadding = compact) { Text("¿Cómo digo?") }
            AppOutlinedButton(onClick = vm::translateLast, enabled = st.lines.any { it.speaker == Speaker.AI }, modifier = Modifier.weight(1.1f), contentPadding = compact) { Text("Traducir") }
            AppOutlinedButton(onClick = vm::slower, enabled = st.connected && !st.talking, modifier = Modifier.weight(0.9f), contentPadding = compact) { Text("Lento") }
        }
        val status = when {
            !st.connected -> st.status
            st.turnMode == TurnMode.PUSH_TO_TALK && st.talking -> "Te escucho… pulsa «Listo» cuando termines (puedes pensar lo que necesites)"
            st.aiSpeaking -> "La IA está hablando"
            st.turnMode == TurnMode.AUTO && st.muted -> "Micrófono silenciado"
            st.turnMode == TurnMode.AUTO -> "Te escucho"
            else -> "Pulsa «Hablar» para responder"
        }
        Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        st.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (st.turnMode == TurnMode.PUSH_TO_TALK) {
            AppButton(
                onClick = { if (st.talking) vm.stopTalking() else vm.startTalking() },
                enabled = st.connected,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                contentPadding = PaddingValues(vertical = 20.dp),
            ) { Text(if (st.talking) "Listo" else "Hablar", style = MaterialTheme.typography.titleMedium) }
        } else {
            AppButton(
                onClick = vm::toggleMute,
                enabled = st.connected,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                contentPadding = PaddingValues(vertical = 20.dp),
            ) { Text(if (st.muted) "Activar micrófono" else "Silenciar", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.width(48.dp))
        Spacer(Modifier.weight(1f))
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) { Text(text.trim().ifEmpty { "…" }, modifier = Modifier.padding(10.dp)) }
    }
}

/** Texto de la IA: borroso hasta tocarlo; luego cada palabra se toca para traducirla. */
@Composable
private fun AiBubble(text: String, blur: Boolean, onWord: (String) -> Unit) {
    var revealed by rememberSaveable { mutableStateOf(!blur) }
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth()) {
        Surface(
            color = scheme.surfaceContainerHigh,
            contentColor = scheme.onSurface,
            modifier = Modifier.border(1.dp, scheme.outlineVariant).weight(1f, fill = false),
        ) {
            if (!revealed) {
                // blur() solo funciona desde Android 12; antes se oculta el texto.
                val hidden = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                Text(
                    if (hidden) text.trim() else "··· toca para ver el texto",
                    modifier = Modifier.clickable { revealed = true }.padding(10.dp).let { if (hidden) it.blur(6.dp) else it },
                )
            } else {
                val linkStyle = TextLinkStyles(SpanStyle(color = scheme.onSurface))
                val annotated = remember(text) {
                    buildAnnotatedString {
                        text.trim().split(Regex("(?<=\\s)|(?=\\s)")).forEach { token ->
                            if (token.any { it in '가'..'힣' }) {
                                val link = LinkAnnotation.Clickable(tag = token, styles = linkStyle, linkInteractionListener = LinkInteractionListener { onWord(token) })
                                withLink(link) { append(token) }
                            } else append(token)
                        }
                    }
                }
                Text(annotated, modifier = Modifier.padding(10.dp))
            }
        }
        Spacer(Modifier.width(48.dp))
    }
}

/** Acciones del panel "¿Cómo digo?" (solo en la sesión en vivo). */
private class HowToActions(
    val onText: (String) -> Unit,
    val onDictate: () -> Unit,
    val onAsk: () -> Unit,
    val onHear: (String) -> Unit,
)

@Composable
private fun CoachPanelView(
    panel: CoachPanel,
    onMore: () -> Unit,
    onClose: () -> Unit,
    onExplain: () -> Unit,
    hasCoach: Boolean,
    howTo: HowToActions? = null,
    /** Solo en la preparación: quitar o volver a incluir el objetivo abierto. */
    includeLabel: String? = null,
    onToggleInclude: () -> Unit = {},
) {
    if (panel == CoachPanel.None) return
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainer,
        contentColor = scheme.onSurface,
        modifier = Modifier.fillMaxWidth().border(1.dp, scheme.outline),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (panel) {
                is CoachPanel.Loading -> Text(panel.what, style = MaterialTheme.typography.bodyMedium)
                is CoachPanel.Error -> Text(panel.message, color = scheme.error, style = MaterialTheme.typography.bodyMedium)
                is CoachPanel.HintShown -> {
                    val h = panel.hint
                    Text("${if (panel.auto) "Pista automática" else "Pista"} ${panel.level}/3", style = MaterialTheme.typography.labelMedium)
                    if (h.key.isNotBlank()) Text(h.key, fontWeight = FontWeight.SemiBold)
                    if (panel.level >= 2 && h.half.isNotBlank()) Text("${h.half} …")
                    if (panel.level >= 3) {
                        Text(h.full, style = MaterialTheme.typography.titleMedium)
                        if (h.spanish.isNotBlank()) Text(h.spanish, style = MaterialTheme.typography.bodySmall)
                    }
                }
                is CoachPanel.WordShown -> {
                    val g = panel.gloss
                    Text(if (g.form != g.word) "${g.word} → ${g.form}" else g.word, fontWeight = FontWeight.SemiBold)
                    Text(g.meaning)
                    g.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                is CoachPanel.SentenceShown -> {
                    val g = panel.gloss
                    Text(g.spanish)
                    g.simple?.let { Text("Más simple: $it", style = MaterialTheme.typography.bodySmall) }
                }
                is CoachPanel.TargetShown -> {
                    Text("${panel.item.label} · ${panel.item.kind}", fontWeight = FontWeight.SemiBold)
                    panel.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    panel.explanation?.let { e ->
                        Text(e.meaning)
                        if (e.usage.isNotBlank()) Text(e.usage, style = MaterialTheme.typography.bodySmall)
                        if (e.example.isNotBlank()) Text(e.example, style = MaterialTheme.typography.titleMedium)
                        if (e.exampleSpanish.isNotBlank()) Text(e.exampleSpanish, style = MaterialTheme.typography.bodySmall)
                    }
                    if (panel.detail == null && panel.explanation == null) {
                        Text("Tu lista no tiene el significado de este ítem.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                is CoachPanel.HowToInput -> {
                    Text("¿Qué quieres decir? (en español)", style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(
                        value = panel.text,
                        onValueChange = { howTo?.onText?.invoke(it) },
                        placeholder = { Text("Tengo tres horas de clase en ambas clases") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AppOutlinedButton(onClick = { howTo?.onDictate?.invoke() }) {
                            Text(if (panel.listening) "Escuchando… (parar)" else "Dictar")
                        }
                        AppButton(onClick = { howTo?.onAsk?.invoke() }, enabled = panel.text.isNotBlank() && !panel.listening, modifier = Modifier.weight(1f)) {
                            Text("Preguntar")
                        }
                    }
                    panel.error?.let { Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                is CoachPanel.HowToShown -> {
                    val a = panel.answer
                    Text("«${panel.idea}»", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    Text(a.mine.text, style = MaterialTheme.typography.titleLarge)
                    a.natural?.let { Text("Más natural: ${it.text}", style = MaterialTheme.typography.bodyMedium) }
                    if (a.note.isNotBlank()) Text(a.note, style = MaterialTheme.typography.bodySmall)
                    if (a.words.isNotEmpty()) {
                        Text(a.words.joinToString(" · ") { (f, m) -> if (m.isBlank()) f else "$f: $m" }, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Dilo cuando estés listo: la IA ya sabe qué preguntaste.", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
                CoachPanel.None -> Unit
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                if (panel is CoachPanel.HintShown && panel.level < 3) AppTextButton(onClick = onMore) { Text("Más ayuda") }
                if (panel is CoachPanel.TargetShown && panel.explanation == null && hasCoach) {
                    AppTextButton(onClick = onExplain) { Text("Explicar con IA") }
                }
                if (panel is CoachPanel.TargetShown && includeLabel != null) AppTextButton(onClick = onToggleInclude) { Text(includeLabel) }
                if (panel is CoachPanel.HowToShown && howTo != null) {
                    AppTextButton(onClick = { howTo.onHear(panel.answer.mine.text) }) { Text("Que la IA lo diga") }
                }
                AppTextButton(onClick = onClose) { Text("Cerrar") }
            }
        }
    }
}

// ---------------------------------------------------------------- Revisión

@Composable
private fun ReviewContent(st: ConvUiState, vm: ConversationViewModel) {
    val r = st.record ?: return
    var confirmDelete by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppTextButton(onClick = vm::backToSetup) { Text("‹ Conversación") }
            Text(formatDate(r.startedAt), style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "${ConversationMath.format(r.durationSec)} · ${r.userLines} intervenciones · ${r.hintsUsed} pistas · ${r.askedHowTo.size} «¿cómo digo?» · ${r.lookups.size} palabras consultadas",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Fin: ${r.endReason} · Tokens: ${r.tokensTotal} (contexto final ${r.lastPromptTokens}) · Reconexiones: ${r.reconnections}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        r.notes.forEach {
            Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }

        val review = st.review
        if (review == null) {
            Label("Revisa tu transcripción")
            Text(
                "Corrige lo que el reconocimiento de voz entendió mal antes de revisar: así no te marca errores que no cometiste.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            r.lines.forEachIndexed { i, line ->
                if (line.speaker == Speaker.AI) {
                    Text("IA: ${line.text}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    OutlinedTextField(
                        value = st.edits[i] ?: line.text,
                        onValueChange = { vm.editLine(i, it) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (r.lines.none { it.speaker == Speaker.USER }) Text("No se registró nada tuyo en esta sesión.")
            AppButton(
                onClick = vm::runReview,
                enabled = !st.reviewing && r.lines.any { it.speaker == Speaker.USER },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (st.reviewing) "Revisando…" else "Revisar lo que dije") }
            st.reviewError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } else {
            review.comment?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            val userLines = r.lines.filter { it.speaker == Speaker.USER }
            Label("Correcciones")
            if (review.correction.corrections.isEmpty()) Text("Nada que corregir. ✓")
            review.correction.corrections.forEach { c ->
                Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant).padding(10.dp)) {
                    userLines.getOrNull(c.sentence - 1)?.let {
                        Text(it.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${c.original} → ${c.suggestion}", fontWeight = FontWeight.SemiBold)
                    if (c.explanation.isNotBlank()) Text(c.explanation, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (review.correction.improvements.isNotEmpty()) {
                Label("Podrías probar")
                review.correction.improvements.forEach { m ->
                    Text("${m.structure}: ${m.example}", fontWeight = FontWeight.SemiBold)
                    if (m.explanation.isNotBlank()) Text(m.explanation, style = MaterialTheme.typography.bodySmall)
                }
            }
            val used = review.correction.used
            if (r.targets.isNotEmpty()) {
                val usedRefs = used.mapNotNull { it.itemRef }.toSet()
                Label("Objetivos")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    r.targets.forEach { t -> AppTag(if (t.ref in usedRefs) "✓ ${t.label}" else t.label, accent = t.ref in usedRefs) }
                }
            }
            if (used.isNotEmpty()) {
                Label("Lo que usaste")
                Text(
                    used.joinToString(" · ") { (if (it.correct) "" else "✗ ") + it.form },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val drill = st.drill
            if (drill != null) {
                DrillCard(drill, vm)
            } else if (review.correction.corrections.isNotEmpty()) {
                Label("Practicar en voz alta")
                Text(
                    "Di cada corrección en voz alta para fijarla, como reescribirla en el diario." +
                        if (r.drillTotal > 0) " Última vez: ${r.drillPassed} de ${r.drillTotal}." else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                AppButton(onClick = vm::startDrill, modifier = Modifier.fillMaxWidth()) { Text("Practicar correcciones") }
            }
            st.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary) }
            Text(
                "Por ahora la conversación no cambia tu lista de estudio: esto queda guardado para decidir después cómo debería contar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (r.askedHowTo.isNotEmpty()) {
            Label("Lo que preguntaste cómo decir")
            r.askedHowTo.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        if (r.lookups.isNotEmpty()) {
            Label("Palabras que consultaste")
            r.lookups.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }

        if (confirmDelete) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppButton(onClick = { vm.deleteSession(r.id) }) { Text("Sí, borrar") }
                AppOutlinedButton(onClick = { confirmDelete = false }) { Text("Cancelar") }
            }
        } else {
            AppTextButton(onClick = { confirmDelete = true }) { Text("Borrar esta sesión") }
        }
        Spacer(Modifier.padding(bottom = 16.dp))
    }
}

/** Una corrección a la vez: escucharla, decirla y ver si se entendió bien. */
@Composable
private fun DrillCard(d: DrillState, vm: ConversationViewModel) {
    val item = d.current ?: return
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainer,
        contentColor = scheme.onSurface,
        modifier = Modifier.fillMaxWidth().border(1.dp, scheme.outline),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Práctica ${d.index + 1}/${d.items.size}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                AppTextButton(onClick = vm::closeDrill) { Text("Terminar") }
            }
            Text(item.target, style = MaterialTheme.typography.headlineSmall)
            if (item.original.isNotBlank()) {
                Text("${item.original} → ${item.fix}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            if (item.explanation.isNotBlank()) Text(item.explanation, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppOutlinedButton(onClick = vm::drillHear) { Text("Escuchar") }
                AppButton(onClick = vm::drillListen, modifier = Modifier.weight(1f)) {
                    Text(if (d.listening) "Escuchando… (toca para parar)" else "Decir")
                }
            }
            if (d.heard.isNotBlank()) Text("Entendí: ${d.heard}", style = MaterialTheme.typography.bodyMedium)
            when (d.result) {
                DrillResult.OK -> Text("✓ ¡Bien dicho!", color = scheme.primary, fontWeight = FontWeight.SemiBold)
                DrillResult.MISSING_FIX -> Text("Casi: no escuché «${item.fix}». Dila otra vez fijándote en esa parte.", color = scheme.error)
                DrillResult.DIFFERENT -> Text("Sonó distinto. Escúchala y vuelve a intentarlo.", color = scheme.error)
                null -> Unit
            }
            d.error?.let { Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall) }
            Text(
                "Esto confirma que dijiste las palabras correctas, no evalúa tu pronunciación.",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            AppOutlinedButton(onClick = vm::drillNext, modifier = Modifier.fillMaxWidth()) {
                Text(if (d.index + 1 < d.items.size) (if (d.result == DrillResult.OK) "Siguiente" else "Saltar") else "Terminar práctica")
            }
        }
    }
}

@Composable
private fun Label(text: String) = Text(text.uppercase(), style = MaterialTheme.typography.labelMedium)

@Composable
private fun Note(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.secondary,
)
