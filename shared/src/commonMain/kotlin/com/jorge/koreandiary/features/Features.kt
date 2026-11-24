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
                phone("Si la app se cerró por un error, el inicio lo avisa: «Copiar detalle» para pasarlo y «Descartar»."),
                phone("Versión de prueba («Diario prueba»): se instala al lado de la tuya, con sus propios datos; la IA contesta con respuestas fijas (Ajustes › IA › «Respuestas fijas»). Trae «Usar texto de prueba», «Prueba: bien» y «Forzar un cierre» para probar sin escribir coreano."),
            ),
        ),
        FeatureGroup(
            "Diario",
            listOf(
                phone("«Nueva página» trae sugerencias de palabras y gramática según tu repaso; las marcadas con «+» son nuevas de tu nivel."),
                phone("Toca una sugerencia para ver lo que dice el diccionario 한국어기초사전, sin IA: significado (el sentido de tu lista primero) con «Escuchar», cómo se pronuncia si no suena como se escribe (같이 [가치]), otros sentidos, sinónimos y contrarios, origen (은메달 = 은 銀 + medal) y otras palabras con el mismo hanja (學: 학교, 학생…), modismos y refranes, cómo se une una gramática (-을 tras consonante, -ㄹ tras vocal), sus formas y ejemplos. En palabras nativas, «¿De qué partes se forma?» (IA). Si el diccionario no la tiene, la explica la IA y sale «Según la IA»."),
                phone("«¿Cómo digo?»: «Dictar» o escribes la idea en español; la ayuda llega por partes: palabras, «Más ayuda» (cómo unirlas) y «Ver la frase», a tu nivel y, si cambia, la natural. Igual en el diario, las escenas y la conversación."),
                phone("«Actualizar» en las sugerencias: lee lo que llevas escrito y suma palabras y gramática que encajan con lo que cuentas (también después de «Revisar»). Lo que viste hace poco en el workbook y lo que fallaste hace poco va primero, si encaja."),
                phone("Notas de sentido (≈): si lo escrito dice otra cosa que lo que dijiste en «¿Cómo digo?», «Revisar» avisa sin pedir reescribir; «Descartar» si cambiaste de idea."),
                phone("Herramientas: Lápiz, Borrar, «+ Línea» (inserta un renglón donde tocas), Deshacer y Rehacer."),
                phone("«Revisar»: la app lee tu letra, confirmas el texto y la IA corrige."),
                phone("Con el analizador de oraciones (Kiwi), «Revisar» también cuenta para el repaso las palabras de tu lista que usaste bien (en una oración sin correcciones), aunque la IA se haya olvidado de nombrarlas."),
                phone("La revisión va en tres niveles: bien, corregir (lo reescribes) y correcto pero más natural (cómo lo escribiría un coreano; solo para leer, «Descartar» si no te sirve). Arriba sale cuántas oraciones hay de cada uno."),
                phone("Errores que se repiten: si ya cometiste el mismo error en las últimas dos semanas, la corrección lo dice («Tercera vez en dos semanas»)."),
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
                phone("Las mismas ayudas que el diario: «¿Cómo digo?» por partes con «Dictar», «Actualizar» las sugerencias según tu escena y las notas de sentido (≈)."),
                phone("Escena viva: mientras escribes, lo que nombras aparece pintado a pincel en acuarela en una franja abajo de la hoja (el mar, un árbol que se mece, el sol, la lluvia, un gato, un faro, un templo… unas 135 cosas), sin tapar donde escribes. Lo que pides en «¿Cómo digo?» aparece recién cuando lo escribes. Si no tocas la hoja por 30 segundos se queda quieta para no gastar batería; al tocarla sigue."),
                phone("La escena viva entiende también 봄, 여름, 해돋이, 아침, 새벽, 반달, 별자리, 정원, 계곡… Una sílaba en otro sentido no trae dibujo: 눈을 감았어요 (ojos), 해 주세요 (hacer), 한 달 (mes). Con el analizador de oraciones (Kiwi) mira cada palabra en su oración: 산 en «어제 산 옷» (comprar) no trae una montaña; en «산에 갔어요», sí."),
                phone("Tono de la escena: al «Revisar» o con «¿Cómo digo?», la IA lee lo que se siente (amor, calma, nostalgia, tristeza…) y una luz de ese color sube desde abajo. No gasta pedidos extra."),
                phone("Dibujos nuevos con Claude: al «Revisar» una escena, lo que se ve y la escena viva no sabía pintar (un camello, un robot) lo dibuja Claude en acuarela, hasta 2 por página: dibuja, mira cómo quedó y lo corrige; desde entonces aparece al escribir su palabra. Sin clave de Claude queda en espera."),
                phone("«Dibujos» (en Escenas): todo lo que la escena sabe pintar, por zona, y las palabras que lo traen, «Prueba una palabra», lo que espera («Dibujar», «Quitar») y en los nuevos «Dibujar otra vez» y «Borrar». Si una palabra es de un dibujo de la app y de uno tuyo, sale el tuyo."),
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
            "Conversar",
            listOf(
                phone("Conversación por voz con Gemini sobre el tema que elijas."),
                phone("«Pulsar para hablar» (no te corta aunque pienses) o «Automático»."),
                phone("«Pista» en tres niveles con «Más ayuda», «Traducir» la última frase y «Lento»."),
                phone("«¿Cómo digo?» con «Dictar», por partes como en el diario: palabras, «Más ayuda» (cómo unirlas) y «Ver la frase»; después, «Que la IA lo diga»."),
                phone("Toca una palabra de la IA para ver lo que dice el diccionario: forma de diccionario (con el analizador, primero la de la oración), sentidos, origen, «Escuchar», cómo se pronuncia, sinónimos y contrarios, otras palabras con el mismo hanja y modismos. Si no está en el diccionario, la explica la IA y lo marca."),
                phone("El texto de la IA sale borroso hasta que lo tocas, para practicar escuchar (se apaga en Ajustes › Hablar y escuchar)."),
                phone("Pista automática si no respondes (se activa en Ajustes › Hablar y escuchar)."),
                phone("Al terminar: confirmas lo que dijiste y recibes la revisión en tres niveles (bien, corregir y correcto pero más natural, cómo lo diría un coreano hablando)."),
                phone("«Practicar correcciones»: dices en voz alta cada corrección y cada versión más natural."),
                phone("La revisión cuenta para tu repaso, solo con los objetivos de la sesión: usado sin ayuda = bien; con «Pista», «¿Cómo digo?» o si lo abriste = difícil; corregido = otra vez. Una sola vez por sesión."),
                phone("Entre los objetivos pueden venir hasta 2 con prioridad (la lección reciente del workbook o lo que fallaste hace poco); la IA los usa solo si encajan con el tema."),
                phone("Si sales de la app o apagas la pantalla, la conversación espera un minuto; si no vuelves, termina y se guarda."),
            ),
        ),
        FeatureGroup(
            "Escuchar",
            listOf(
                phone("Pódcast corto a tu nivel sobre lo que elijas; «Tema actual» busca en Google."),
                phone("En las pausas preguntas por voz, en coreano o en español."),
                phone("«− Lento», «+ Rápido», «Repetir», «Siguiente» y ver el texto con traducción."),
                phone("Sirve con la pantalla apagada: botones en la notificación y en los audífonos (1 toque pausa, 2 sigue, 3 repite)."),
                phone("Al final, preguntas de comprensión."),
                phone("Al revisar la escucha, lo que preguntaste en las pausas vuelve pronto a tu repaso; si no está en tu lista, queda en «Por revisar». Lo que solo oíste no cuenta."),
                phone("Una pausa en la que nadie habla por 3 minutos cierra el micrófono («Hablar» lo abre de nuevo); una llamada pausa la narración sin abrirlo."),
                phone("Con audífonos Bluetooth, en las pausas preguntas desde su micrófono (se apaga en Ajustes › Hablar y escuchar → «Micrófono de los audífonos»)."),
                phone("En la revisión de una escucha: «Escuchar» otra vez la narración guardada, sin gastar cuota, con «‹ Anterior», «Siguiente ›» y velocidad."),
                phone("Quedan las últimas 5 escuchas; «☆ Guardar» conserva una para siempre."),
            ),
        ),
        FeatureGroup(
            "Estudio",
            listOf(
                phone("Tu lista y tu progreso en una pantalla, por pestañas: «Resumen», «Vocabulario», «Gramática», «Por revisar» y «Para agregar». Se abre con «Estudio» o tocando el resumen del inicio."),
                phone("«Resumen»: «Repaso rápido», «Tu nivel» y abajo racha, calendario, memoria del repaso, tiempos verbales, lo que más te cuesta y minutos hablados."),
                phone("«Repaso rápido»: unas diez oraciones con un espacio en blanco, con lo que te toca repasar y algo que nunca usaste. Primero tus oraciones (ya corregidas); para lo demás, una del diccionario 한국어기초사전; solo si no hay, una de la IA. Una sola llamada a la IA por ronda las traduce todas y escribe las que faltan."),
                phone("Arriba de la oración, su traducción con lo que falta resaltado. En una gramática, «Usa:» dice qué expresa y con qué verbo va («la gramática de «durante el tiempo que», con 먹다»), para no confundirla con una parecida; lo que expresa sale de tu lista o, si no tiene, de la IA (marcado). Sin IA, «Usa:» lleva el significado de tu lista o del diccionario. «Traducir» la pide si la ronda quedó sin traducción."),
                phone("Si fallas con la misma palabra y otra terminación (먹으면서 en vez de 먹는 동안), el repaso te dice que el verbo está bien y que lo que cambia es la gramática o la conjugación. Si escribiste un sinónimo, el contrario o la forma corta de la palabra del hueco, también lo dice, sin darte la respuesta."),
                phone("Si casi acertaste, «LEÍ» subraya la sílaba que está mal y dice qué revisar (la vocal, la consonante de abajo, falta algo al final…), sin darte la respuesta; avisa si lo escribiste como suena (머거요 por 먹어요) o si suena igual (됬/됐). Con «Ver respuesta», qué letra cambiaba: «En «었» va ㅆ abajo, no ㅅ». Al cerrar la tarjeta, «Escuchar» lee la oración completa."),
                phone("Con el analizador de oraciones (Kiwi, Ajustes › Escritura › «Descargar analizador»): tocar una palabra pone primero la que corresponde a la oración («Elegida por la oración»: 들어요 en «음악을 들어요» es 듣다), el hueco va solo donde de verdad está la palabra (en «사과를 사는 사람», 사는 no es 살다), los ejemplos de «Más sobre esto» son solo los que la usan y, si usaste otra gramática con el mismo verbo, te dice cuál («usaste «-으면서»»)."),
                phone("En el repaso rápido escribes la respuesta a mano en el recuadro («Teclado» si prefieres) y «Comprobar». Lo que la app leyó sale en grande («LEÍ»), para ver si leyó bien tu letra; si leyó mal, «La mía también vale». «Pista» abre hasta tres: en una palabra, cuántas sílabas, la primera y las fichas desordenadas; en un verbo, la forma de diccionario, la terminación y las fichas; en una gramática, cuántas sílabas, el patrón y las fichas."),
                phone("Al cerrar una tarjeta del repaso rápido, «Más sobre esto»: lo que dice el diccionario 한국어기초사전, sin IA (significado con «Escuchar», cómo se pronuncia, otros sentidos, sinónimos y contrarios, origen y otras palabras con el mismo hanja, modismos, cómo se une, formas y ejemplos que de verdad la usan). En palabras nativas, «¿De qué partes se forma?» (IA, 꿈꾸다 = 꿈 + 꾸다). Si el diccionario no la tiene, la explica la IA, marcada «Según la IA», y queda guardada. Si las partes de la IA no forman la palabra letra por letra, salen como «Partes (dudoso)»."),
                phone("En el repaso rápido, toca cualquier palabra de la oración: el diccionario 한국어기초사전 te da su forma de diccionario (만났어요 → 만나다), todos sus sentidos con los comunes primero y su origen (은메달 = 은 銀 + medal), sin IA; además «Escuchar», cómo se pronuncia, sinónimos y contrarios, otras palabras con el mismo hanja y sus modismos. «Ver todo» abre el resto; después de un número salen primero los contadores (세 장). En palabras nativas, «¿De qué partes se forma? (IA)». Si no está en tu lista, «Agregar «…» a mi lista». Si el diccionario no la tiene, la explica la IA y lo marca. La palabra del hueco se puede tocar al cerrar la tarjeta."),
                phone("Con «Ver respuesta» el recuadro se queda: si tienes tiempo, cópiala a mano y «Comprobar» dice si quedó bien escrita (es opcional, y «Siguiente» está siempre). No cambia lo que cuenta para tu repaso."),
                phone("Cuenta para tu repaso al momento: sin pistas o con una = bien; con más, o si fallaste antes = difícil; «Ver respuesta» = vuelve pronto. Si tu forma también es correcta, «La mía también vale». Sin IA van solo tus oraciones, sin traducción."),
                phone("«Tu nivel»: cuánto tienes de la gramática de cada nivel del estándar (y cuánto ya usaste bien) y el nivel que eso indica. Si no coincide con el de Ajustes, lo propone; no lo cambia solo."),
                phone("«Prueba de vocabulario»: unas 15 palabras al azar por nivel, tocas las que conoces; suma su resultado a «Tu nivel». «Repetir la prueba» cuando quieras."),
                phone("«Para agregar»: la gramática del estándar que te falta hasta tu nivel y uno más, y las palabras que no conociste en la prueba, de lo más básico arriba. «Agregar» la suma como nueva (te la va a sugerir); «Ya lo sé», como conocida (vuelve mucho después)."),
                phone("En «Vocabulario» y «Gramática», buscador; al tocar un ítem, su memoria, tus oraciones con él y lo que te corrigieron."),
                phone("Si una palabra quedó mal escrita en tu lista, tócala: «Corregir» la cambia sin perder su historial y «Quitar de mi lista» la borra con su historial."),
                phone("«Importar» tu lista de gramática (TXT) o vocabulario."),
                phone("Lo que usas en el diario entra solo a tu lista, con su nivel."),
                phone("Las palabras nuevas (del diario, el libro, la conversación o una lista importada) se verifican con el diccionario 한국어기초사전, que va dentro de la app: si no está ahí, puede estar mal escrita o mal leída y queda en «Por revisar»."),
                phone("«Por revisar»: palabras que no están en el diccionario o que preguntaste al escuchar; las agregas o las descartas."),
            ),
        ),
        FeatureGroup(
            "Ajustes",
            listOf(
                phone("Por grupos, uno a la vez: «General», «Escritura», «IA», «Escenas», «Hablar y escuchar» y «Copia y actualizaciones»."),
                phone("General: perfil, nivel y niveles de gramática a sugerir; tema claro u oscuro y papel en modo oscuro; mascota (mostrarla u ocultarla, o importar la tuya; en el inicio, toca su globo para traducir); recordatorio diario, que solo avisa si ese día no escribiste."),
                phone("Escritura: grosor del lápiz, presión del S Pen, «Solo el S Pen dibuja» (el dedo desplaza) y «Descargar modelo de escritura coreana» (una sola vez). «Descargar analizador» (Kiwi, 84 MB, una sola vez; mejor con Wi-Fi): hace que tocar una palabra, el repaso rápido, la escena viva y «Revisar» miren la oración entera; «Quitar» lo borra."),
                phone("IA: proveedor por defecto, Gemini o Azure, con «Probar conexión»."),
                phone("IA › Explicaciones: quién explica lo que el diccionario no tiene (palabras o gramática que no están, las partes de una palabra nativa) y traduce el repaso rápido. Azure o un modelo de Gemini más fuerte que el de corregir, para que los desgloses sean confiables."),
                phone("Si ese modelo falla (saturado, sin cuota, sin acceso), sale «Reintentar» o «Probar con …» otro modelo que ya tengas en Ajustes (Azure, el de corregir de Gemini, el de Escuchar). El cambio dura hasta cerrar la app; en IA › Explicaciones se ve y «Volver al elegido» lo deshace."),
                phone("Escenas: «Escena viva» (encender o apagar los dibujos en acuarela de abajo) y «Dibujos nuevos (Claude)»: tu clave de la API de Claude, el modelo, «Probar conexión», «Dibujar al revisar una escena» y «Ver dibujos». Como mucho 12 dibujos por día."),
                phone("Hablar y escuchar: turnos, corrección durante la conversación, pista automática, texto borroso, voz y modelos; en Escuchar, narrador, velocidad, pausas y micrófono de los audífonos."),
                phone("Copia y actualizaciones: «Exportar copia» e «Importar copia» (.zip; las claves de API no viajan). La app avisa cuando hay una versión nueva («Buscar actualización») y se instala con «Actualizar ahora»."),
                phone("«Deshacer la última importación»: vuelve a lo que había antes de importar. Lo que tenías al deshacer queda guardado dentro de la app, como «deshacer», con «Restaurar»."),
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
            title = "Primeros pasos",
            group = "Inicio",
            where = "Inicio",
            platforms = setOf(Platform.PHONE),
            steps = listOf(
                FlowStep(
                    "Importas tu lista",
                    "En «Estudio» › «Importar»: tu lista de gramática en TXT o cualquier lista con un chat. Con ella llegan las sugerencias de cada página.",
                    listOf("Estudio", "Importar"),
                ),
                FlowStep(
                    "Escribes tu perfil y tu nivel",
                    "En «Ajustes» › General › Perfil: quién eres, tu nivel y qué niveles de gramática sugerir. La IA corrige con ese contexto.",
                    listOf("Ajustes"),
                ),
                FlowStep(
                    "Descargas el modelo de escritura",
                    "Una sola vez, en Ajustes › Escritura. Con él la app lee tu letra, sin internet.",
                    listOf("Descargar modelo de escritura coreana"),
                ),
                FlowStep(
                    "Eliges con qué corregir",
                    "Gemini o Azure con tu clave y «Probar conexión». Sin clave, cada página se corrige igual con «Compartir con un chat».",
                    listOf("Probar conexión", "Compartir con un chat"),
                ),
                FlowStep(
                    "Guardas una copia",
                    "En Ajustes › Copia y actualizaciones: «Exportar copia» y guárdala fuera del celular; «Elegir carpeta» para la copia automática antes de cada actualización.",
                    listOf("Exportar copia", "Elegir carpeta"),
                ),
            ),
            note = "En el inicio, «Primeros pasos» marca lo que ya hiciste; «Ocultar» lo quita.",
        ),
        FeatureFlow(
            title = "Diario",
            group = "Diario",
            where = "Inicio › Nueva página",
            platforms = setOf(Platform.PHONE),
            steps = listOf(
                FlowStep(
                    "Abres una página",
                    "Llega con sugerencias de palabras y gramática según tu repaso; las marcadas con «+» son nuevas de tu nivel. Toca una para ver lo que dice el diccionario: significado, origen y ejemplos.",
                    listOf("Nueva página"),
                ),
                FlowStep(
                    "Escribes con el S Pen",
                    "«+ Línea» abre un renglón donde tocas. La pantalla no se apaga mientras escribes.",
                    listOf("Lápiz", "Borrar", "+ Línea", "Deshacer", "Rehacer"),
                    FlowAside(
                        "Si no sabes cómo decirlo",
                        listOf("¿Cómo digo?", "Dictar", "Más ayuda", "Ver la frase"),
                        "La dices o la escribes en español; la ayuda llega por partes: palabras, cómo unirlas y la frase. La escribes tú, a mano.",
                    ),
                ),
                FlowStep(
                    "Sumas sugerencias",
                    "Lee lo que llevas escrito y suma palabras y gramática que encajan con lo que cuentas.",
                    listOf("Actualizar"),
                ),
                FlowStep(
                    "Pides la revisión",
                    "La app lee tu letra; en «¿Esto es lo que escribiste?» corriges lo que leyó mal y eliges con qué revisar.",
                    listOf("Revisar"),
                    FlowAside(
                        "Sin API",
                        listOf("Compartir con un chat", "Pegar", "Pegar del portapapeles", "Procesar"),
                        "Manda la página a Claude o ChatGPT; copias su respuesta y la pegas.",
                    ),
                ),
                FlowStep(
                    "Lees la revisión",
                    "En tres niveles: bien, corregir y correcto pero más natural. Cada error con su corrección y el porqué, y si se repite de las últimas dos semanas, lo dice. Lo más natural, las «mejoras» y las notas de sentido (≈, si escribiste otra cosa que lo que dijiste en «¿Cómo digo?») son para leer: no hace falta reescribirlas.",
                ),
                FlowStep(
                    "Reescribes y verificas",
                    "Escribes cada corrección debajo y tocas «Verificar». Si una no la detecta, «Hecho» la marca a mano.",
                    listOf("Verificar", "Hecho"),
                ),
                FlowStep(
                    "Queda en tu repaso",
                    "Lo que usaste entra a tu lista y al repaso; la página cuenta para la racha.",
                ),
            ),
        ),
        FeatureFlow(
            title = "Escenas",
            group = "Escenas",
            where = "Inicio › Escenas › Nueva escena",
            platforms = setOf(Platform.PHONE),
            steps = listOf(
                FlowStep(
                    "Abres una escena",
                    "Llega con dos gramáticas nuevas (★), un paso por encima de tu nivel, además de las sugerencias.",
                    listOf("Escenas", "Nueva escena"),
                ),
                FlowStep(
                    "Describes lo que ves",
                    "Lo que nombras aparece pintado en acuarela abajo de la hoja: el mar, un árbol que se mece, la lluvia, un gato, un faro.",
                    aside = FlowAside(
                        "Si te trabas",
                        listOf("¿Cómo digo?", "Dictar", "Más ayuda", "Ver la frase"),
                        "La ayuda llega por partes: palabras, cómo unirlas y la frase. Lo que dibuja aparece cuando lo escribes.",
                    ),
                ),
                FlowStep(
                    "Sumas sugerencias",
                    "Lee lo que llevas escrito y suma palabras y gramática que encajan con tu escena.",
                    listOf("Actualizar"),
                ),
                FlowStep(
                    "Revisas",
                    "Como en el diario, con las notas de sentido (≈). Además la IA lee el tono (sube una luz de ese color) y lo que se ve sin dibujo lo dibuja Claude.",
                    listOf("Revisar", "Descartar"),
                ),
                FlowStep(
                    "Verificas",
                    "Reescribes las correcciones. La escena cuenta para la racha cuando no le quedan pendientes.",
                    listOf("Verificar", "Hecho"),
                ),
            ),
            note = "En Escenas, «Dibujos» muestra lo que la escena sabe pintar (unas 135 cosas) y las palabras que lo traen; «Prueba una palabra» te dice si una trae dibujo.",
        ),
        FeatureFlow(
            title = "Workbook",
            group = "Workbook",
            where = "Inicio › Workbook › Nueva página",
            platforms = setOf(Platform.PHONE),
            steps = listOf(
                FlowStep(
                    "Abres una página del libro",
                    "Arriba eliges con qué revisar (Gemini, Azure o un chat).",
                    listOf("Workbook", "Nueva página"),
                ),
                FlowStep(
                    "Le tomas una foto",
                    "La gramática de la lección la eliges tú o la detecta la IA.",
                    listOf("Tomar foto", "Galería", "Cambiar"),
                ),
                FlowStep(
                    "Pides pistas",
                    "Para cada ejercicio, pistas para combinar esa gramática con otras y una respuesta modelo escondida.",
                    listOf("Pedir pistas", "Ver respuesta"),
                    FlowAside("Con un chat", listOf("Pegar respuesta del chat")),
                ),
                FlowStep(
                    "Sumas la gramática impresa",
                    "Marcas las gramáticas de la página que quieres en tu lista; ★ es la de la lección.",
                    listOf("Agregar N a mi lista"),
                ),
                FlowStep(
                    "Revisas tus respuestas",
                    "Resuelves en el libro y les tomas foto: cada una sale bien, a corregir o correcta pero más natural. Cuenta para tu repaso.",
                    listOf("Foto de mis respuestas", "Revisar otra vez"),
                ),
            ),
        ),
        FeatureFlow(
            title = "Conversar",
            group = "Conversar",
            where = "Inicio › Hablar y escuchar › Conversar",
            platforms = setOf(Platform.PHONE),
            steps = listOf(
                FlowStep(
                    "Eliges de qué hablar",
                    "Un tema libre, tu diario de hoy u otro que escribas, y cuántos minutos (5 a 20). La app trae de tu lista de estudio unas palabras y gramáticas para practicar; toca una para quitarla de esta sesión.",
                    listOf("Libre", "Mi diario de hoy", "Otro", "Empezar"),
                ),
                FlowStep(
                    "Hablas con la IA",
                    "Con «Pulsar para hablar» tocas «Hablar», dices tu frase y tocas «Listo»: no te corta aunque te quedes pensando. En «Automático» solo hablas (se elige en Ajustes › Hablar y escuchar).",
                    listOf("Hablar", "Listo"),
                    FlowAside(
                        "Si te trabas",
                        listOf("Pista", "Más ayuda", "¿Cómo digo?", "Dictar", "Ver la frase", "Traducir", "Lento"),
                        "«¿Cómo digo?» va por partes, como en el diario: palabras, cómo unirlas y la frase. Toca una palabra de la IA para traducirla; su texto sale borroso hasta que lo tocas.",
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
                    "Primero corriges lo que el reconocimiento de voz entendió mal; después la IA te muestra la revisión en tres niveles (bien, corregir y más natural), estructuras para mejorar y qué objetivos usaste.",
                    listOf("Revisar lo que dije"),
                ),
                FlowStep(
                    "Practicas en voz alta",
                    "Escuchas cada oración corregida o más natural y la dices: en una corrección, la parte corregida tiene que oírse igual.",
                    listOf("Practicar correcciones", "Escuchar", "Decir"),
                ),
            ),
            note = "Al revisar, los objetivos que usaste cuentan para tu repaso (sin ayuda = bien, con ayuda = difícil, corregido = otra vez). Los minutos hablados salen en Estudio › «Resumen».",
        ),
        FeatureFlow(
            title = "Escuchar",
            group = "Escuchar",
            where = "Inicio › Hablar y escuchar › Escuchar",
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
                    "Igual que en Conversar: corriges la transcripción, la IA revisa y practicas en voz alta. Lo que preguntaste en las pausas vuelve a tu repaso (o a «Por revisar»). Además puedes escuchar otra vez la narración guardada sin gastar cuota.",
                    listOf("Revisar lo que dije", "Escuchar", "‹ Anterior", "Siguiente ›", "☆ Guardar"),
                ),
            ),
            note = "Quedan las últimas 5 escuchas; «☆ Guardar» conserva una para siempre.",
        ),
        FeatureFlow(
            title = "Estudio",
            group = "Estudio",
            where = "Inicio › Estudio",
            platforms = setOf(Platform.PHONE),
            steps = listOf(
                FlowStep(
                    "Importas una lista",
                    "Gramática en TXT («Unit N: patrón | patrón», sin IA): pones el nivel de las unidades y eliges el archivo, o pegas el texto.",
                    listOf("Importar", "Elegir archivo TXT", "Leer texto pegado"),
                ),
                FlowStep(
                    "O cualquier lista, con un chat",
                    "Copias el prompt, lo pegas en Claude o ChatGPT con tu lista (texto o fotos) y pegas su respuesta.",
                    listOf("Copiar prompt de importación", "Leer respuesta"),
                ),
                FlowStep(
                    "Lo que usas entra solo",
                    "Lo que escribes en el diario, las escenas y el workbook entra a tu lista con su nivel.",
                ),
                FlowStep(
                    "Revisas lo que no reconoció",
                    "En «Por revisar» quedan las palabras que la app no conocía: las agregas o las descartas.",
                    listOf("Por revisar", "Agregar", "Descartar"),
                ),
                FlowStep(
                    "Repasas en unos minutos",
                    "«Repaso rápido» trae unas diez oraciones con un espacio en blanco: tuyas, del diccionario o de la IA. Arriba, la traducción con lo que falta resaltado y, en gramática, «Usa:» con qué expresa y con qué verbo va. Escribes a mano lo que falta; si no sale, pides pistas o ves la respuesta, y el recuadro se queda para copiarla a mano. Toca cualquier palabra de la oración para ver qué es y agregarla a tu lista. Cuenta para tu repaso al momento. Al cerrar la tarjeta, «Más sobre esto» la explica con el diccionario: significado, origen y ejemplos.",
                    listOf("Repaso rápido", "Comprobar", "Pista", "Ver respuesta", "La mía también vale", "Más sobre esto", "Escuchar", "Agregar a mi lista"),
                    aside = FlowAside("Si prefieres", listOf("Teclado", "Borrar", "Traducir")),
                ),
                FlowStep(
                    "Ves tu nivel",
                    "«Resumen» compara tu lista con el estándar, nivel por nivel. La prueba corta de vocabulario afina el cálculo. Si no coincide con tu nivel de Ajustes, te lo propone.",
                    listOf("Resumen", "Prueba de vocabulario", "Ajustes"),
                ),
                FlowStep(
                    "Agregas lo que falta",
                    "«Para agregar» trae la gramática que te falta y las palabras que no conociste en la prueba. Lo nuevo te lo va a sugerir; lo que ya sabes vuelve mucho después.",
                    listOf("Para agregar", "Agregar", "Ya lo sé"),
                ),
            ),
            note = "«Vocabulario» y «Gramática» tienen buscador; toca un ítem para ver tus oraciones con él, y en una palabra, «Corregir» o «Quitar de mi lista». Debajo de «Tu nivel» están la racha, el calendario y la memoria.",
        ),
        FeatureFlow(
            title = "Copia de seguridad",
            group = "Ajustes",
            where = "Inicio › Ajustes › Copia y actualizaciones",
            platforms = setOf(Platform.PHONE),
            steps = listOf(
                FlowStep(
                    "Exportas",
                    "Un .zip con todo menos las claves de API. Guárdalo fuera del celular (Drive, la PC).",
                    listOf("Exportar copia"),
                ),
                FlowStep(
                    "Antes de cada actualización, sola",
                    "La app guarda una copia antes de instalar cada versión (quedan las 3 últimas). Con una carpeta elegida, sobrevive aunque desinstales.",
                    listOf("Elegir carpeta"),
                ),
                FlowStep(
                    "Restauras",
                    "Eliges el .zip y confirmas: la app se reinicia con esos datos. Las copias guardadas dentro de la app se recuperan con «Restaurar».",
                    listOf("Importar copia", "Reemplazar", "Restaurar"),
                ),
                FlowStep(
                    "Te arrepientes",
                    "Vuelve a los datos que había antes de importar. Lo que tenías en ese momento queda dentro de la app, como «deshacer», con «Restaurar».",
                    listOf("Deshacer la última importación"),
                ),
            ),
            note = "Si la copia antes de actualizar falla, no instala: «Reintentar» o «Actualizar sin copia».",
        ),
        FeatureFlow(
            title = "En la PC",
            group = "Diario",
            where = "Celular › Ajustes, y la app de escritorio",
            platforms = setOf(Platform.DESKTOP),
            steps = listOf(
                FlowStep(
                    "Exportas en el celular",
                    "Ajustes › Copia y actualizaciones.",
                    listOf("Exportar copia"),
                ),
                FlowStep(
                    "Pasas el archivo a la PC",
                    "Por cable, por Drive o como prefieras.",
                ),
                FlowStep(
                    "Lo importas en la PC",
                    "Eliges el .zip. Si la PC ya tenía páginas, pide confirmación y guarda lo anterior.",
                    listOf("Importar copia del celular…"),
                ),
                FlowStep(
                    "Lees tus páginas",
                    "Por día, con su tinta y su texto. Escribir con la tableta llega después.",
                ),
            ),
        ),
    )

    /**
     * El recorrido de un grupo de la lista, si tiene. Si hay más de uno (el diario tiene el del celular
     * y el de la PC), primero el que funciona en [platform].
     */
    fun flowFor(group: String, platform: Platform? = null): FeatureFlow? =
        flows.filter { it.group == group }.sortedBy { platform != null && platform !in it.platforms }.firstOrNull()

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
