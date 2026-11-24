package com.jorge.koreandiary.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.jorge.koreandiary.dictionary.DictionaryExtras
import com.jorge.koreandiary.voice.KoreanSpeech

/**
 * La voz coreana del celular para «Escuchar» una palabra o una oración, sin internet ni IA. Se
 * prende la primera vez que se pide y se suelta al salir de la pantalla.
 */
@Composable
fun rememberKoreanVoice(): KoreanSpeech {
    val context = LocalContext.current.applicationContext
    val voice = remember { KoreanSpeech(context) }
    DisposableEffect(voice) { onDispose { voice.release() } }
    return voice
}

/** «Escuchar»: lee [text] con la voz del celular (sin los signos del diccionario: -는 동안, 먹다^보다). */
@Composable
fun SpeakButton(text: String, voice: KoreanSpeech, modifier: Modifier = Modifier) {
    AppTextButton(
        onClick = { voice.say(text.replace(Regex("[-^]"), "").trim()) },
        modifier = modifier.testTag("escuchar"),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) { Text("Escuchar", style = MaterialTheme.typography.labelMedium) }
}

/**
 * Lo que el diccionario dice además de los sentidos (pedido por Jorge el 2026-11-24): cómo se
 * pronuncia si no suena como se escribe, sinónimos y contrarios, otras palabras con el mismo hanja y
 * modismos. Lo usan tocar una palabra, «Más sobre esto» y las tarjetas de sugerencias.
 */
@Composable
fun DictionaryExtrasView(extras: DictionaryExtras, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.testTag("diccionario_extras"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        extras.pronunciation?.let {
            Text("Se pronuncia [$it]", style = MaterialTheme.typography.bodySmall, color = scheme.secondary)
        }
        extras.related.forEach { group ->
            Text(group.text, style = MaterialTheme.typography.bodySmall)
        }
        extras.hanja.forEach { family ->
            Text("Mismo hanja: ${family.text}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        if (extras.idioms.isNotEmpty()) {
            extras.idioms.forEach { idiom ->
                Text(
                    (if (idiom.proverb) "Refrán: " else "Modismo: ") + idiom.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** El encabezado de una palabra con «Escuchar» al lado. */
@Composable
fun HeaderWithVoice(voice: KoreanSpeech?, speak: String?, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        content()
        if (voice != null && !speak.isNullOrBlank()) SpeakButton(speak, voice)
    }
}
