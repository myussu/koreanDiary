package com.jorge.koreandiary.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jorge.koreandiary.mascot.Mascot
import com.jorge.koreandiary.mascot.MascotMood
import com.jorge.koreandiary.mascot.MascotPack

/** La mascota con los colores del papel actual, o la imagen importada si existe. */
@Composable
fun AppMascot(pack: MascotPack, mood: MascotMood, size: Dp, modifier: Modifier = Modifier) {
    val paper = LocalPaperStyle.current
    val version by pack.version.collectAsStateWithLifecycle()
    val custom by produceState<ImageBitmap?>(null, mood, version) { value = pack.image(mood) }
    Mascot(
        mood = mood,
        size = size,
        body = paper.ink,
        features = paper.background,
        accent = MaterialTheme.colorScheme.secondary,
        custom = custom,
        modifier = modifier,
    )
}
