package com.jorge.koreandiary.features

import java.text.Normalizer

/** Dónde funciona una función. */
enum class Platform(val label: String) {
    PHONE("celular"),
    DESKTOP("PC"),
}

/** Una línea de la lista: qué hace, con los nombres de los botones tal como aparecen, y dónde funciona. */
class Feature(val text: String, val platforms: Set<Platform>) {
    /** "solo en celular" si no funciona en [platform]; null si sí. */
    fun missingLabel(platform: Platform): String? =
        if (platform in platforms) null
        else "solo en " + Platform.entries.filter { it in platforms }.joinToString(" y ") { it.label }
}

/** Un grupo de la lista: el nombre de la pantalla y una línea por función. */
class FeatureGroup(val title: String, val features: List<Feature>)

/**
 * Un recuadro junto a un paso: lo que tienes a mano en cualquier momento de ese paso (las ayudas
 * mientras hablas, los botones de los audífonos).
 */
class FlowAside(val title: String, val buttons: List<String> = emptyList(), val text: String? = null)

/** Un paso de un recorrido: qué haces, los botones que usas (tal como aparecen) y, si hay, un recuadro al lado. */
class FlowStep(val title: String, val text: String, val buttons: List<String> = emptyList(), val aside: FlowAside? = null)

/**
 * Una pantalla de principio a fin, paso a paso, para «Cómo se usa» del botón «?». [group] es el
 * grupo de la lista al que pertenece (desde ahí se salta al recorrido); [where] cómo se llega.
 */
class FeatureFlow(
    val title: String,
    val group: String,
    val where: String,
    val platforms: Set<Platform>,
    val steps: List<FlowStep>,
    val note: String? = null,
) {
    /** "solo en celular" si no funciona en [platform]; null si sí. */
    fun missingLabel(platform: Platform): String? = Feature("", platforms).missingLabel(platform)
}

private fun phone(text: String) = Feature(text, setOf(Platform.PHONE))
private fun desktop(text: String) = Feature(text, setOf(Platform.DESKTOP))
private fun both(text: String) = Feature(text, setOf(Platform.PHONE, Platform.DESKTOP))

/**
 * Todo lo que la app ya sabe hacer, una línea por función, para no olvidarlo ni pedirlo dos veces.
 * La leen el celular y la PC (botón «?» del inicio). Al agregar, quitar o renombrar una función
 * visible, o cuando una función llega a la otra versión, actualiza esta lista (lo pide CLAUDE.md).
 */
object Features {

    val groups: List<FeatureGroup> = listOf(
        FeatureGroup(
            "Inicio",
            listOf(
                both("«?»: esta lista, con buscador. Lo que no funciona en esta versión sale en gris."),
                both("«Cómo se usa» (en «?»): cada pantalla paso a paso, dibujada, con sus botones. Desde la lista, «Ver cómo se usa ›» salta a la de ese grupo."),
            ),
        ),
        FeatureGroup(
            "Diario",
            listOf(
                phone("«Nueva página» trae sugerencias de palabras y gramática según tu repaso; las marcadas con «+» son nuevas de tu nivel."),
                phone("Toca una sugerencia para ver su significado, cómo se usa y ejemplos."),
                phone("«¿Cómo digo?»: escribes la idea en español y te da la frase a tu nivel y, si cambia, la natural."),
                phone("Herramientas: Lápiz, Borrar, «+ Línea» (inserta un renglón donde tocas), Deshacer y Rehacer."),
                phone("«Revisar»: la app lee tu letra, confirmas el texto y la IA corrige."),
                phone("Sin API: «Compartir con un chat» manda la página a Claude o ChatGPT y pegas la respuesta."),
                phone("Reescribe las correcciones debajo y pulsa «Verificar»; «Hecho» marca a mano una que no detectó."),
                phone("Las «mejoras» proponen estructuras de tu nivel; no hace falta reescribirlas."),
                phone("Al girar el celular la hoja se ve igual; en horizontal los paneles van al costado."),
                phone("La pantalla se queda encendida mientras escribes; si pasan 5 minutos sin tocar la hoja, vuelve a apagarse sola."),
                desktop("Ver las páginas del celular por día, con su tinta y su texto. Por ahora solo se leen: escribir con la tableta llega después."),
            ),
        ),
        FeatureGroup(
            "Escenas",
            listOf(
                phone("Describe un lugar o un momento, vivido o imaginado: mismo lienzo, Revisar y Verificar que el diario."),
                phone("«Nueva escena» trae dos gramáticas nuevas (★), un paso por encima de tu nivel, aparte de las sugerencias."),
                phone("«¿Cómo digo?»: «Dictar» la idea en español; la ayuda llega por partes: palabras, «Más ayuda» (cómo unirlas) y «Ver la frase»."),
                phone("«Actualizar» en las sugerencias: lee lo que llevas escrito y suma palabras y gramática que encajan con tu escena (también después de «Revisar»)."),
                phone("Notas de sentido (≈): si lo escrito dice otra cosa que lo que dijiste, «Revisar» avisa sin pedir reescribir; «Descartar» si cambiaste de idea."),
                phone("Escena viva: mientras escribes, lo que nombras aparece pintado a pincel en acuarela en una franja abajo de la hoja (el mar, un árbol que se mece, el pasto, el sol, la lluvia…), sin tapar donde escribes. Lo que pides en «¿Cómo digo?» aparece recién cuando lo escribes. Si no tocas la hoja por 30 segundos se queda quieta para no gastar batería; al tocarla sigue."),
                phone("Tono de la escena: al «Revisar» o con «¿Cómo digo?», la IA lee lo que se siente (amor, calma, nostalgia, tristeza…) y una luz de ese color sube desde abajo. No gasta pedidos extra."),
                phone("Dibujos nuevos con Claude: al «Revisar» una escena, lo que se ve y la escena viva no sabía pintar (un gato, un faro) lo dibuja Claude en acuarela, hasta 2 por página: dibuja, mira cómo quedó y lo corrige; desde entonces aparece al escribir su palabra. Sin clave de Claude queda en espera."),
                phone("«Dibujos» (en Escenas): todo lo que la escena sabe pintar y las palabras que lo traen, «Prueba una palabra», lo que espera («Dibujar», «Quitar») y en los nuevos «Dibujar otra vez» y «Borrar»."),
                phone("«Imagen»: la escena pintada en acuarela. Con tu servidor ComfyUI (por Tailscale) se pinta sola al terminarla; sin servidor, «Copiar prompt para Gemini» y «Elegir imagen». Sale muy suave de fondo y como miniatura en la lista de escenas."),
                phone("Cuenta para la racha cuando está revisada y sin correcciones pendientes."),
                desktop("En la lista de páginas de la PC, las escenas salen marcadas «Escena»."),
            ),
        ),
        FeatureGroup(
            "Workbook",
            listOf(
                phone("Foto de una página del libro: eliges la gramática que practicas o dejas que la IA la detecte."),
                phone("Pistas para combinar esa gramática con otras, con una respuesta modelo escondida."),
                phone("Eliges qué gramáticas impresas entran a tu lista (★ es la de la lección)."),
                phone("Foto de tus respuestas: bien, corregir o correcto pero más natural."),
            ),
        ),
        FeatureGroup(
            "Hablar",
            listOf(
                phone("Conversación por voz con Gemini sobre el tema que elijas."),
                phone("«Pulsar para hablar» (no te corta aunque pienses) o «Automático»."),
                phone("«Pista» en tres niveles con «Más ayuda», «¿Cómo digo?» con «Dictar», «Traducir» la última frase y «Lento»."),
                phone("Toca una palabra de la IA para traducirla."),
                phone("El texto de la IA sale borroso hasta que lo tocas, para practicar escuchar (se apaga en Ajustes)."),
                phone("Pista automática si no respondes (se activa en Ajustes)."),
                phone("Al terminar: confirmas lo que dijiste, recibes la revisión y «Practicar correcciones» en voz alta."),
                phone("Si sales de la app o apagas la pantalla, la conversación espera un minuto; si no vuelves, termina y se guarda."),
            ),
        ),
        FeatureGroup(
            "Escuchar (en Hablar)",
            listOf(
                phone("Pódcast corto a tu nivel sobre lo que elijas; «Tema actual» busca en Google."),
                phone("En las pausas preguntas por voz, en coreano o en español."),
                phone("«− Lento», «+ Rápido», «Repetir», «Siguiente» y ver el texto con traducción."),
                phone("Sirve con la pantalla apagada: botones en la notificación y en los audífonos (1 toque pausa, 2 sigue, 3 repite)."),
                phone("Al final, preguntas de comprensión."),
                phone("Una pausa en la que nadie habla por 3 minutos cierra el micrófono («Hablar» lo abre de nuevo); una llamada pausa la narración sin abrirlo."),
                phone("Con audífonos Bluetooth, en las pausas preguntas desde su micrófono (se apaga en Ajustes → «Micrófono de los audífonos»)."),
                phone("En la revisión de una escucha: «Escuchar» otra vez la narración guardada, sin gastar cuota, con «‹ Anterior», «Siguiente ›» y velocidad."),
                phone("Quedan las últimas 5 escuchas; «☆ Guardar» conserva una para siempre."),
            ),
        ),
        FeatureGroup(
            "Estudio",
            listOf(
                phone("«Importar» tu lista de gramática (TXT) o vocabulario."),
                phone("Lo que usas en el diario entra solo a tu lista, con su nivel."),
                phone("«Por revisar»: palabras que la app no reconoció; las agregas o las descartas."),
            ),
        ),
        FeatureGroup(
            "Progreso",
            listOf(
                phone("Racha, calendario, memoria del repaso, tiempos verbales, lo que más te cuesta y minutos hablados."),
                phone("Se abre tocando el resumen del inicio."),
            ),
        ),
        FeatureGroup(
            "Ajustes",
            listOf(
                phone("Perfil, nivel y niveles de gramática a sugerir."),
                phone("IA: Gemini o Azure, con «Probar conexión»."),
                phone("«Descargar modelo de escritura coreana» (una sola vez)."),
                phone("«Solo el S Pen dibuja» (el dedo desplaza) y presión del S Pen."),
                phone("«Escena viva en los bordes»: encender o apagar la escena viva (los dibujos en acuarela de abajo) de las escenas."),
                phone("«Imagen de la escena»: la dirección de tu servidor ComfyUI, «Probar conexión» y el modelo; «Pintar al terminar una escena» e «Imagen de fondo en la escena»."),
                phone("«Dibujos nuevos (Claude)»: tu clave de la API de Claude, el modelo, «Probar conexión», «Dibujar al revisar una escena» y «Ver dibujos». Como mucho 12 dibujos por día."),
                phone("Recordatorio diario: solo avisa si ese día no escribiste."),
                phone("Copia de seguridad: «Exportar copia» e «Importar copia» (.zip). Las claves de API no viajan."),
                phone("Tema claro u oscuro y papel en modo oscuro."),
                phone("Mascota: mostrarla u ocultarla, o importar la tuya. En el inicio, toca su globo para traducir."),
                phone("La app se actualiza sola cuando hay una versión nueva."),
                phone("Copia antes de actualizar: guarda todo antes de instalar cada versión (quedan las 3 últimas). «Elegir carpeta» para que sobreviva aunque desinstales; sin carpeta queda dentro de la app, con «Restaurar»."),
                phone("Si esa copia falla, no instala: «Reintentar» o «Actualizar sin copia»."),
                desktop("«Importar copia del celular…»: trae el .zip exportado en el celular. Si la PC ya tenía páginas, pide confirmación y guarda lo anterior."),
                desktop("Tema claro u oscuro según el de Windows."),
            ),
        ),
    )

    /**
     * Los recorridos de «Cómo se usa»: lo mismo que la lista, pero en el orden en que lo vas usando.
     * Mismas reglas que la lista: los botones con el nombre tal como aparecen, y se actualizan en el
     * mismo commit que la función.
     */
    val flows: List<FeatureFlow> = listOf(
        FeatureFlow(
            title = "Conversar",
            group = "Hablar",
            where = "Inicio › Hablar › Conversar",
            platforms = setOf(Platform.PHONE),
            steps = listOf(
                FlowStep(
                    "Eliges de qué hablar",
                    "Un tema libre, tu diario de hoy u otro que escribas, y cuántos minutos (5 a 20). La app trae de tu lista de estudio unas palabras y gramáticas para practicar; toca una para quitarla de esta sesión.",
                    listOf("Libre", "Mi diario de hoy", "Otro", "Empezar"),
                ),
                FlowStep(
                    "Hablas con la IA",
                    "Con «Pulsar para hablar» tocas «Hablar», dices tu frase y tocas «Listo»: no te corta aunque te quedes pensando. En «Automático» solo hablas (se elige en Ajustes).",
                    listOf("Hablar", "Listo"),
                    FlowAside(
                        "Si te trabas",
                        listOf("Pista", "Más ayuda", "¿Cómo digo?", "Dictar", "Traducir", "Lento"),
                        "Toca una palabra de la IA para traducirla. Su texto sale borroso hasta que lo tocas.",
                    ),
                ),
                FlowStep(
                    "Usas tus objetivos",
                    "Cuando una palabra o gramática de tu lista sube y flota, la pregunta de la IA es para que la uses en tu respuesta.",
                ),
                FlowStep(
                    "Terminas",
                    "Se acaba el tiempo o tocas «Terminar». Si sales de la app o apagas la pantalla, espera un minuto; si no vuelves, termina y se guarda.",
                    listOf("Terminar"),
                ),
                FlowStep(
                    "Revisas lo que dijiste",
                    "Primero corriges lo que el reconocimiento de voz entendió mal; después la IA te muestra las correcciones, estructuras para mejorar y qué objetivos usaste.",
                    listOf("Revisar lo que dije"),
                ),
                FlowStep(
                    "Practicas en voz alta",
                    "Escuchas cada oración corregida y la dices: la parte corregida tiene que oírse igual.",
                    listOf("Practicar correcciones", "Escuchar", "Decir"),
                ),
            ),
            note = "Tu lista de estudio no cambia con la conversación; los minutos hablados salen en Progreso.",
        ),
        FeatureFlow(
            title = "Escuchar",
            group = "Escuchar (en Hablar)",
            where = "Inicio › Hablar › Escuchar",
            platforms = setOf(Platform.PHONE),
            steps = listOf(
                FlowStep(
                    "Eliges el tema",
                    "Escribe o dicta de qué quieres escuchar (vacío es algo cotidiano) y cuántos minutos. «Tema actual» busca en Google lo que está pasando.",
                    listOf("Dictar", "Tema actual (buscar en Google)", "Empezar a escuchar"),
                ),
                FlowStep(
                    "Escuchas la narración",
                    "Un pódcast corto a tu nivel, por partes. Sirve con la pantalla apagada.",
                    listOf("Pausa", "Seguir", "Repetir", "− Lento", "+ Rápido", "Siguiente"),
                    FlowAside("Con audífonos", text = "1 toque pausa · 2 toques sigue · 3 toques repite. También desde la notificación."),
                ),
                FlowStep(
                    "En las pausas, hablas",
                    "La IA te hace una pregunta, o tú le preguntas lo que no entendiste, en coreano o en español. «Seguir», o decir «sigue», continúa la narración.",
                    listOf("Hablar", "Listo", "Seguir"),
                ),
                FlowStep(
                    "Respondes las preguntas del final",
                    "La IA te hace unas preguntas por voz sobre lo que escuchaste.",
                ),
                FlowStep(
                    "Revisas y vuelves a escuchar",
                    "Igual que en Conversar: corriges la transcripción, la IA revisa y practicas en voz alta. Además puedes escuchar otra vez la narración guardada sin gastar cuota.",
                    listOf("Revisar lo que dije", "Escuchar", "‹ Anterior", "Siguiente ›", "☆ Guardar"),
                ),
            ),
            note = "Quedan las últimas 5 escuchas; «☆ Guardar» conserva una para siempre.",
        ),
    )

    /** El recorrido de un grupo de la lista, si tiene. */
    fun flowFor(group: String): FeatureFlow? = flows.firstOrNull { it.group == group }

    /** Cuántas funciones de la lista funcionan en [platform]. */
    fun countOn(platform: Platform): Int = groups.sumOf { g -> g.features.count { platform in it.platforms } }

    val total: Int get() = groups.sumOf { it.features.size }

    /**
     * Las líneas que contienen todas las palabras buscadas, en el texto o en el nombre del grupo
     * («escena voz» encuentra las líneas de Escenas que hablan de voz). Sin distinguir mayúsculas,
     * tildes ni signos: «como digo» encuentra «¿Cómo digo?». Sin palabras, devuelve todo.
     */
    fun search(query: String): List<FeatureGroup> {
        val words = normalize(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return groups
        return groups.mapNotNull { g ->
            val title = normalize(g.title)
            val hits = g.features.filter { f ->
                val haystack = title + " " + normalize(f.text)
                words.all { it in haystack }
            }
            if (hits.isEmpty()) null else FeatureGroup(g.title, hits)
        }
    }

    private val marks = Regex("\\p{Mn}+")
    private val notWord = Regex("[^\\p{L}\\p{N}]+")

    /** Minúsculas, sin tildes (la ñ queda n) y con los signos como espacios. El hangul se compara por jamo. */
    internal fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(marks, "")
            .replace(notWord, " ")
            .trim()
}
