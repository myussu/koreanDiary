package com.jorge.koreandiary.ui.page

import android.graphics.RuntimeShader
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.jorge.koreandiary.scene.MoodReading
import com.jorge.koreandiary.scene.SceneElement
import com.jorge.koreandiary.scene.SceneMaterial
import com.jorge.koreandiary.scene.SceneMoods
import com.jorge.koreandiary.scene.SceneShaders
import com.jorge.koreandiary.scene.art.Mark
import com.jorge.koreandiary.scene.art.SceneArt
import com.jorge.koreandiary.ui.ScenePalette
import com.jorge.koreandiary.ui.rememberSlowClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Escena viva: lo que escribes aparece pintado a pincel en acuarela en una franja abajo de la hoja
 * ([SceneArt]): el fondo de color, lo lejano, el agua y los árboles, el suelo y lo que cae o vuela.
 * Arriba de la franja se escribe tranquilo. Encima, la luz del tono de la escena ([mood], lo lee la
 * IA al revisar o en «¿Cómo digo?») sube desde abajo y respira.
 *
 * Va DETRÁS del lienzo: la tinta queda siempre encima. Desde Android 13 la capa de los dibujos pasa
 * por el filtro de acuarela ([SceneMaterial.WATERCOLOR]) y el tono es un sombreador; antes, o si un
 * sombreador no compila en este celular, los dibujos van sin filtro y el tono es un degradado.
 * Cada elemento entra de a poco y todo se mueve solo si el sistema tiene las animaciones encendidas
 * y [active] (la página la detiene cuando no la tocas un rato: así no calienta el celular mientras
 * piensas o lees; al tocarla sigue).
 */
@Composable
fun SceneVignette(elements: List<SceneElement>, mood: MoodReading?, dark: Boolean, modifier: Modifier = Modifier, active: Boolean = true) {
    val context = LocalContext.current
    val motionOn = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    // Entrada suave de cada elemento nuevo (los que ya estaban no vuelven a aparecer).
    val appear = remember { mutableStateMapOf<String, Animatable<Float, AnimationVector1D>>() }
    LaunchedEffect(elements) {
        val ids = elements.map { it.id }.toSet()
        (appear.keys - ids).forEach { appear.remove(it) }
        elements.forEach { e ->
            if (e.id !in appear) {
                val a = Animatable(if (motionOn) 0f else 1f)
                appear[e.id] = a
                launch { a.animateTo(1f, tween(1_400)) }
            }
        }
    }
    // El tono cambia despacio: el nuevo aparece mientras el anterior se apaga.
    val moods = remember { mutableStateMapOf<String, MoodLayer>() }
    LaunchedEffect(mood) {
        if (mood != null) {
            val layer = moods.getOrPut(mood.id) { MoodLayer(mood, Animatable(if (motionOn) 0f else 1f)) }
            layer.reading = mood
            launch { layer.alpha.animateTo(1f, tween(MOOD_FADE_MS)) }
        }
        moods.values.filter { it.reading.id != mood?.id }.forEach { old ->
            launch {
                old.alpha.animateTo(0f, tween(MOOD_FADE_MS))
                if (moods[old.reading.id] === old && old.alpha.value == 0f) moods.remove(old.reading.id)
            }
        }
    }
    // Si llega un elemento o un tono nuevo con la página quieta, se mueve unos segundos para que se note.
    var justChanged by remember { mutableStateOf(false) }
    LaunchedEffect(elements.size, mood?.id) {
        justChanged = true
        delay(WAKE_ON_CHANGE_MS)
        justChanged = false
    }
    // ~30 cuadros por segundo y solo mientras se mueve (ver rememberSlowClock).
    val time = rememberSlowClock(fps = 30, running = motionOn && (active || justChanged), start = 3f)

    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        if (width <= 0f || height <= 0f) return@BoxWithConstraints
        val stage = SceneArt.stageHeight(width, height)
        val unit = SceneArt.unit(width, height)
        val density = LocalDensity.current
        val layers = moods.values.toList()
        if (layers.isNotEmpty()) {
            MoodLight(
                layers, time, dark, unit,
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(with(density) { (stage * 1.25f).coerceAtMost(height).toDp() }),
            )
        }
        if (elements.isNotEmpty()) {
            ArtLayer(
                elements, appear, time, dark, width, height,
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(with(density) { stage.toDp() }),
            )
        }
    }
}

/** Un tono en pantalla y cuánto se ve (sube al llegar, baja cuando lo reemplaza otro). */
private class MoodLayer(var reading: MoodReading, val alpha: Animatable<Float, AnimationVector1D>)

private const val MOOD_FADE_MS = 2_500

/** Cuánto se mueve la escena quieta cuando le llega algo nuevo. */
private const val WAKE_ON_CHANGE_MS = 8_000L

/** Cuánto se ve la capa de los dibujos: deja ver la tinta y el papel. */
private const val ART_OPACITY = 0.72f

// ---------- Los dibujos a pincel ----------

/**
 * La franja de los dibujos. Las manchas se calculan fuera del hilo principal (para no trabar el
 * lápiz) cada vez que avanza el tiempo; aquí solo se rellenan. Con el filtro de acuarela si se puede.
 */
@Composable
private fun ArtLayer(
    elements: List<SceneElement>,
    appear: Map<String, Animatable<Float, AnimationVector1D>>,
    time: FloatState,
    dark: Boolean,
    width: Float,
    height: Float,
    modifier: Modifier,
) {
    val marks = remember { mutableStateOf<List<Mark>>(emptyList()) }
    LaunchedEffect(elements, width, height) {
        // El tiempo y la entrada de cada elemento: así uno nuevo aparece aunque la escena esté quieta.
        snapshotFlow { time.floatValue to elements.associate { it.id to (appear[it.id]?.value ?: 1f) } }.collectLatest { (t, fades) ->
            marks.value = withContext(Dispatchers.Default) { SceneArt.paint(elements, width, height, t) { fades[it] ?: 1f } }
        }
    }
    val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) remember(dark) { watercolorEffect(dark) } else null
    // Sin filtro, el pigmento se ajusta a mano: un poco más de cuerpo en papel oscuro, como hace el filtro.
    val alphaScale = if (effect != null) 1f else ART_OPACITY * (if (dark) 1.3f else 1f)
    val paths = remember { ArrayList<Path>() }
    Canvas(if (effect != null) modifier.graphicsLayer { renderEffect = effect } else modifier) {
        val list = marks.value
        list.forEachIndexed { i, m ->
            val path = if (i < paths.size) paths[i].also { it.reset() } else Path().also { paths += it }
            val o = m.outline
            path.moveTo(o[0], o[1])
            var k = 2
            while (k + 1 < o.size) {
                path.lineTo(o[k], o[k + 1])
                k += 2
            }
            path.close()
            drawPath(path, ScenePalette.watercolor(m.color, dark), alpha = (m.alpha * alphaScale).coerceIn(0f, 1f))
        }
    }
}

/** El filtro de acuarela como efecto de capa; null si no compila en este celular. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun watercolorEffect(dark: Boolean): RenderEffect? = try {
    val shader = RuntimeShader(SceneShaders.source(SceneMaterial.WATERCOLOR))
    runCatching { shader.setFloatUniform("uAlpha", ART_OPACITY) }
    runCatching { shader.setFloatUniform("uDark", if (dark) 1f else 0f) }
    android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
} catch (e: RuntimeException) {
    Log.w("SceneVignette", "No compiló el filtro de acuarela; los dibujos van sin filtro.", e)
    null
}

// ---------- La luz del tono ----------

@Composable
private fun MoodLight(layers: List<MoodLayer>, time: FloatState, dark: Boolean, unit: Float, modifier: Modifier) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shaders = remember { HashMap<String, MoodShader?>() }
        // Se compila una vez por tono; si falla queda null y se usa el degradado.
        layers.forEach { l -> if (!shaders.containsKey(l.reading.id)) shaders[l.reading.id] = MoodShader.create() }
        val ready = layers.all { shaders[it.reading.id] != null }
        if (ready) {
            Canvas(modifier) {
                val t = time.floatValue
                for (layer in layers) {
                    val a = layer.alpha.value
                    val m = SceneMoods.byId(layer.reading.id) ?: continue
                    val s = shaders[m.id] ?: continue
                    if (a <= 0.001f) continue
                    val (c1, c2) = ScenePalette.mood(m.id)
                    s.set(size.width, size.height, unit * 0.12f, t, a, m.particles.ordinal, dark, layer.reading.intensity, m.speed, c1, c2)
                    drawRect(s.brush)
                }
            }
            return
        }
    }
    // Sin sombreador: luz del color del tono que sube desde abajo.
    Canvas(modifier) {
        for (layer in layers) {
            val a = layer.alpha.value * layer.reading.intensity * (if (dark) 0.22f else 0.16f)
            if (a <= 0.001f) continue
            val c = ScenePalette.mood(layer.reading.id).first.copy(alpha = a)
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, c), startY = size.height * 0.2f, endY = size.height))
        }
    }
}

/** El sombreador del tono, su pincel y los uniformes que este celular no reconoce (se saltan). */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class MoodShader private constructor(val shader: RuntimeShader) {
    val brush = ShaderBrush(shader)
    private val missing = HashSet<String>()

    private inline fun safe(name: String, set: () -> Unit) {
        if (name in missing) return
        try {
            set()
        } catch (e: IllegalArgumentException) {
            missing += name
        }
    }

    fun set(
        w: Float, h: Float, edge: Float, time: Float, alpha: Float, kind: Int,
        dark: Boolean, intensity: Float, speed: Float, color: Color, color2: Color,
    ) {
        safe("uSize") { shader.setFloatUniform("uSize", w, h) }
        safe("uEdge") { shader.setFloatUniform("uEdge", edge) }
        safe("uTime") { shader.setFloatUniform("uTime", time) }
        safe("uAlpha") { shader.setFloatUniform("uAlpha", alpha) }
        safe("uKind") { shader.setFloatUniform("uKind", kind.toFloat()) }
        safe("uDark") { shader.setFloatUniform("uDark", if (dark) 1f else 0f) }
        safe("uParam") { shader.setFloatUniform("uParam", intensity, speed, 0f, 0f) }
        safe("uColor") { shader.setColorUniform("uColor", color.toArgb()) }
        safe("uColor2") { shader.setColorUniform("uColor2", color2.toArgb()) }
    }

    companion object {
        fun create(): MoodShader? = try {
            MoodShader(RuntimeShader(SceneShaders.source(SceneMaterial.MOOD)))
        } catch (e: RuntimeException) {
            Log.w("SceneVignette", "No compiló el sombreador del tono; uso un degradado.", e)
            null
        }
    }
}
