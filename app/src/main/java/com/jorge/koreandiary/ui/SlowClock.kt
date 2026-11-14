package com.jorge.koreandiary.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay

/**
 * Segundos para animaciones que no necesitan 60–120 cuadros por segundo (la escena viva, la
 * mascota). Avanza a [fps] y pide un cuadro de pantalla solo cuando avanza: una animación de Compose
 * normal (`rememberInfiniteTransition`) pide uno en cada refresco y deja la pantalla a 120 Hz todo el
 * tiempo, aunque se vea igual.
 *
 * Se detiene sola con la app en segundo plano o la pantalla apagada (Compose pausa su reloj de
 * cuadros y `withFrameNanos` espera), y mientras [running] es false; al volver sigue desde donde quedó.
 * Vuelve a [start] cada [wrapSeconds] para no perder precisión en la GPU.
 */
@Composable
fun rememberSlowClock(fps: Int, running: Boolean = true, start: Float = 0f, wrapSeconds: Float = 600f): FloatState {
    val time = remember { mutableFloatStateOf(start) }
    LaunchedEffect(fps, running) {
        if (!running) return@LaunchedEffect
        val frameMs = 1000L / fps.coerceAtLeast(1)
        val base = time.floatValue
        var origin = -1L
        while (true) {
            withFrameNanos { now ->
                if (origin < 0) origin = now
                var t = base + (now - origin) / 1_000_000_000f
                if (t > start + wrapSeconds) t = start + (t - start) % wrapSeconds
                time.floatValue = t
            }
            delay(frameMs)
        }
    }
    return time
}
