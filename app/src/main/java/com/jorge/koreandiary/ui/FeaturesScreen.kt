package com.jorge.koreandiary.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Un grupo de la lista: el nombre de la pantalla y una línea por función. */
private class FeatureGroup(val title: String, val items: List<String>)

/**
 * Todo lo que la app ya sabe hacer, una línea por función, para no olvidarlo ni pedirlo dos veces.
 * Al agregar o cambiar una función visible, actualiza esta lista (lo pide CLAUDE.md).
 */
private val FEATURES = listOf(
    FeatureGroup(
        "Diario",
        listOf(
            "«Nueva página» trae sugerencias de palabras y gramática según tu repaso; las marcadas con «+» son nuevas de tu nivel.",
            "Toca una sugerencia para ver su significado, cómo se usa y ejemplos.",
            "«¿Cómo digo?»: escribes la idea en español y te da la frase a tu nivel y, si cambia, la natural.",
            "Herramientas: Lápiz, Borrar, «+ Línea» (inserta un renglón donde tocas), Deshacer y Rehacer.",
            "«Revisar»: la app lee tu letra, confirmas el texto y la IA corrige.",
            "Sin API: «Compartir con un chat» manda la página a Claude o ChatGPT y pegas la respuesta.",
            "Reescribe las correcciones debajo y pulsa «Verificar»; «Hecho» marca a mano una que no detectó.",
            "Las «mejoras» proponen estructuras de tu nivel; no hace falta reescribirlas.",
            "Al girar el celular la hoja se ve igual; en horizontal los paneles van al costado.",
        ),
    ),
    FeatureGroup(
        "Escenas",
        listOf(
            "Describe un lugar o un momento, vivido o imaginado: mismo lienzo, Revisar y Verificar que el diario.",
            "Cuenta para la racha cuando está revisada y sin correcciones pendientes.",
        ),
    ),
    FeatureGroup(
        "Workbook",
        listOf(
            "Foto de una página del libro: eliges la gramática que practicas o dejas que la IA la detecte.",
            "Pistas para combinar esa gramática con otras, con una respuesta modelo escondida.",
            "Eliges qué gramáticas impresas entran a tu lista (★ es la de la lección).",
            "Foto de tus respuestas: bien, corregir o correcto pero más natural.",
        ),
    ),
    FeatureGroup(
        "Hablar",
        listOf(
            "Conversación por voz con Gemini sobre el tema que elijas.",
            "«Pulsar para hablar» (no te corta aunque pienses) o «Automático».",
            "«Pista» en tres niveles con «Más ayuda», «¿Cómo digo?» con «Dictar», «Traducir» la última frase y «Lento».",
            "Toca una palabra de la IA para traducirla.",
            "El texto de la IA sale borroso hasta que lo tocas, para practicar escuchar (se apaga en Ajustes).",
            "Pista automática si no respondes (se activa en Ajustes).",
            "Al terminar: confirmas lo que dijiste, recibes la revisión y «Practicar correcciones» en voz alta.",
        ),
    ),
    FeatureGroup(
        "Escuchar (en Hablar)",
        listOf(
            "Pódcast corto a tu nivel sobre lo que elijas; «Tema actual» busca en Google.",
            "En las pausas preguntas por voz, en coreano o en español.",
            "«− Lento», «+ Rápido», «Repetir», «Siguiente» y ver el texto con traducción.",
            "Sirve con la pantalla apagada: botones en la notificación y en los audífonos (1 toque pausa, 2 sigue, 3 repite).",
            "Al final, preguntas de comprensión.",
        ),
    ),
    FeatureGroup(
        "Estudio",
        listOf(
            "«Importar» tu lista de gramática (TXT) o vocabulario.",
            "Lo que usas en el diario entra solo a tu lista, con su nivel.",
            "«Por revisar»: palabras que la app no reconoció; las agregas o las descartas.",
        ),
    ),
    FeatureGroup(
        "Progreso",
        listOf(
            "Racha, calendario, memoria del repaso, tiempos verbales, lo que más te cuesta y minutos hablados.",
            "Se abre tocando el resumen del inicio.",
        ),
    ),
    FeatureGroup(
        "Ajustes",
        listOf(
            "Perfil, nivel y niveles de gramática a sugerir.",
            "IA: Gemini o Azure, con «Probar conexión».",
            "«Descargar modelo de escritura coreana» (una sola vez).",
            "«Solo el S Pen dibuja» (el dedo desplaza) y presión del S Pen.",
            "Recordatorio diario: solo avisa si ese día no escribiste.",
            "Copia de seguridad: «Exportar copia» e «Importar copia» (.zip). Las claves de API no viajan.",
            "Tema claro u oscuro y papel en modo oscuro.",
            "Mascota: mostrarla u ocultarla, o importar la tuya. En el inicio, toca su globo para traducir.",
            "La app se actualiza sola cuando hay una versión nueva.",
        ),
    ),
)

/** Lista simple de lo que la app ya hace, agrupada por pantalla. Se abre con «?» en el inicio. */
@Composable
fun FeaturesScreen(onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppTextButton(onClick = onBack) { Text("‹ Diario") }
            Text("Qué puede hacer la app", style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        ) {
            FEATURES.forEach { group ->
                item(key = group.title) {
                    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                        Text(group.title.uppercase(), style = MaterialTheme.typography.labelMedium, color = scheme.secondary)
                        HorizontalDivider(color = scheme.outlineVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                items(group.items, key = { "${group.title}:$it" }) { line ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text("·", color = scheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
                        Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
