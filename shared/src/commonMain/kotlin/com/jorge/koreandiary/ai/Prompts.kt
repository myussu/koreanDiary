package com.jorge.koreandiary.ai

import com.jorge.koreandiary.scene.SceneDictionary

/**
 * Prompts del documento de arquitectura. Están escritos para que cualquier LLM común
 * (Gemini, Claude, ChatGPT...) los siga y responda solo con JSON.
 */
object Prompts {

    /**
     * Prompt de corrección. Pensado para gastar pocos tokens: instrucciones compactas y
     * claves cortas en "usados", que es la lista más larga de la respuesta.
     * [standardLevel] es el nivel 1–6 del estándar del 국립국어원 para las mejoras.
     */
    fun correction(
        profile: String,
        level: String,
        standardLevel: Int,
        items: List<PromptItem>,
        text: String,
        hasImage: Boolean,
        /** Página de la sección Escenas: describe un lugar o un momento, no necesariamente su día. */
        scene: Boolean = false,
        /** Escenas: lo que dijo que quería decir en «¿Cómo digo?», para las notas de sentido. */
        ideas: List<String> = emptyList(),
    ): String = buildString {
        appendLine("Eres profesor de coreano y corriges el diario manuscrito de un estudiante hispanohablante. Responde SOLO con un objeto JSON válido, sin texto extra ni bloques de código.")
        appendLine()
        appendLine("ESTUDIANTE: ${profile.ifBlank { "(sin perfil)" }}")
        appendLine("Nivel: $level (≈ nivel $standardLevel de 6 del 국제 통용 한국어 표준 교육과정)")
        if (scene) appendLine("Esta página describe una escena, vivida o imaginada: lo que ve, oye y siente. No tiene por qué hablar de su día.")
        appendLine()
        appendLine("SUGERIDOS EN ESTA PÁGINA (id | forma | tipo):")
        if (items.isEmpty()) appendLine("(ninguno)")
        items.forEach { appendLine("${it.ref} | ${it.label} | ${it.kind}") }
        appendLine()
        if (ideas.isNotEmpty()) {
            appendLine("LO QUE QUISO DECIR (lo dijo en español mientras escribía; puede haber cambiado de idea después):")
            ideas.forEach { appendLine("- ${it.trim().replace('\n', ' ')}") }
            appendLine()
        }
        appendLine("TEXTO:")
        appendLine(text)
        if (hasImage) appendLine("(Hay una imagen de la página manuscrita: es la fuente principal; el texto es referencia.)")
        appendLine()
        appendLine(
            """
            TAREAS:
            1. "oraciones": divide en oraciones y da el tiempo principal: "pasado", "presente" o "futuro".
            2. "correcciones": ortografía, partículas, conjugación, gramática y naturalidad. No cambies lo correcto ni reescribas por estilo. Explica en español en 1-2 frases.
            3. "usados": TODO lo que usó, una vez cada cosa:
               - Palabras de contenido en forma de diccionario (먹었어요 → 먹다).
               - Gramática (terminaciones, partículas y expresiones) en notación estándar: terminaciones con guion y forma con 으/어 (-어서, -으니까, -을 것 같다, -는 바람에); partículas sin guion (이라도, 처럼).
               - Omite solo: 이/가, 은/는, 을/를, 에, 의, 도, 와/과, -어요/아요, -습니다/ㅂ니다, -었-/았-.
               - "id": el de la lista de sugeridos si coincide; si no, null.
               - "t": n (sustantivo), v (verbo), a (adjetivo), adv (adverbio), g (gramática) u o (otro). "ok": false si lo usó mal.
            4. "mejoras": como máximo 2 estructuras de nivel $standardLevel o menor que el estudiante debería saber y que encajarían mejor donde escribió algo demasiado básico. Solo si de verdad aplica; si no, [].${if (scene) " Prefiere estructuras para describir lo que se ve, se oye y se siente (-아/어 보이다, -는 것 같다, 의성어 y 의태어)." else ""}${if (ideas.isNotEmpty()) "\n            5. \"notas\": si una oración correcta dice algo distinto de lo que quiso decir según la lista de arriba (p. ej., \"golpea\" cuando quería \"acariciar\"), anótalo aquí y NO en correcciones: no es un error. \"explicacion\": 1 frase en español con lo que dice y lo que quería decir. Solo si es claro; si cambió de idea o no tiene que ver, []." else ""}${if (scene) "\n            ${if (ideas.isNotEmpty()) 6 else 5}. \"escena\": qué elementos de esta lista aparecen en la página, de lo más importante a lo menos (solo ids; [] si ninguno): ${SceneDictionary.promptList()}." else ""}

            FORMATO:
            {
              "oraciones": [{"n": 1, "texto": "...", "tiempo": "pasado"}],
              "correcciones": [{"oracion": 1, "original": "...", "sugerencia": "...", "tipo": "ortografia|particula|conjugacion|gramatica|naturalidad", "explicacion": "...", "item_id": null}],
              "usados": [{"f": "자다", "t": "v", "ok": true, "id": "w42"}, {"f": "-어서", "t": "g", "ok": true, "id": null}],
              "mejoras": [{"oracion": 2, "estructura": "-는 바람에", "ejemplo": "...", "explicacion": "..."}]${if (ideas.isNotEmpty()) ",\n              \"notas\": [{\"oracion\": 1, \"original\": \"...\", \"sugerencia\": \"...\", \"explicacion\": \"...\"}]" else ""}${if (scene) ",\n              \"escena\": [\"sea\", \"sand\"]" else ""}
            }
            """.trimIndent()
        )
    }

    /**
     * "¿Cómo digo esto?": la frase con lo que el estudiante sabe y, si difiere, la versión natural.
     * No se manda su lista (ahorra tokens): basta el nivel; la app marca después lo que no está en sus listas.
     */
    /**
     * «¿Cómo digo esto?». Con [tiered] (escenas) pide además "pista": cómo unir las palabras sin dar
     * la frase, para mostrar la ayuda por partes (palabras → pista → frase). [targets]: gramáticas
     * nuevas que el estudiante está practicando en esta página; se usan solo si encajan.
     */
    fun howToSay(
        idea: String,
        level: String,
        standardLevel: Int,
        tiered: Boolean = false,
        targets: List<String> = emptyList(),
    ): String = buildString {
        appendLine("Eres profesor de coreano. Un estudiante hispanohablante de nivel $level (≈ nivel $standardLevel de 6 del 국제 통용 한국어 표준 교육과정) escribe ${if (tiered) "una escena (un lugar o un momento, con lo que ve, oye y siente)" else "su diario"} y quiere decir:")
        appendLine("«${idea.trim().replace('\n', ' ')}»")
        appendLine("Responde SOLO con un objeto JSON válido, sin texto extra ni bloques de código.")
        appendLine()
        appendLine("1. \"tu\": la frase en coreano usando solo vocabulario y gramática de nivel $standardLevel o menor; la forma más simple que siga siendo correcta. Registro -어요 salvo que la idea pida otro.")
        if (targets.isNotEmpty()) {
            appendLine("   Está practicando estas gramáticas nuevas: ${targets.joinToString(", ")}. Si alguna encaja de forma natural en esta idea, úsala en \"tu\" aunque esté sobre su nivel; si ninguna encaja, no la fuerces.")
        }
        appendLine("2. \"nat\": cómo lo diría normalmente un coreano. Si sería igual a \"tu\", pon null.")
        appendLine("3. En \"g\" de cada frase, las estructuras gramaticales usadas en notación estándar: terminaciones con guion y forma con 으/어 (-어서, -을 것 같다); modificadores de sustantivo como -은, -는, -던; partículas sin guion (처럼). Omite 이/가, 은/는, 을/를, 에, 도, -어요.")
        appendLine("4. \"nota\": 1 o 2 frases en español sobre la diferencia, o por qué la frase ya es natural.")
        appendLine("5. \"w\": las palabras de contenido en forma de diccionario con su significado en español.")
        if (tiered) {
            appendLine("6. \"pista\": cómo unir esas palabras SIN escribir la frase ni partes de ella en coreano: qué partícula lleva cada palabra (바람 + 이) y qué terminación lleva el verbo y por qué (쓰다듬다 en presente -어요). 1 o 2 frases en español. Es la ayuda intermedia antes de ver la frase.")
            appendLine("7. \"esc\": qué elementos de esta lista aparecen en la idea, de lo más importante a lo menos (solo ids de la lista; [] si ninguno): ${SceneDictionary.promptList()}.")
        }
        appendLine()
        appendLine("FORMATO:")
        append("{\"tu\": {\"k\": \"...\", \"g\": [\"-은\"]}, \"nat\": {\"k\": \"...\", \"g\": [\"-었던\"]}, \"nota\": \"...\", \"w\": [{\"f\": \"다니다\", \"s\": \"asistir\"}]")
        appendLine(if (tiered) ", \"pista\": \"...\", \"esc\": [\"wind\", \"grass\"]}" else "}")
    }

    /**
     * «Actualizar sugerencias» en una escena: de [candidates] (lo que te toca repasar según FSRS y un
     * poco del catálogo que aún no está en tu lista), la IA elige lo que encaja con lo que llevas
     * escrito. [text] es lo que leyó ML Kit de tu letra y puede traer errores de lectura.
     */
    fun sceneSuggestions(
        level: String,
        standardLevel: Int,
        text: String,
        candidates: List<PromptItem>,
        known: Int = 4,
        extra: Int = 2,
    ): String = buildString {
        appendLine("Eres profesor de coreano. Un estudiante hispanohablante de nivel $level (≈ nivel $standardLevel de 6 del 국제 통용 한국어 표준 교육과정) está describiendo a mano una escena (un lugar o un momento, con lo que ve, oye y siente). Esto es lo que lleva escrito, leído de su letra (puede tener errores de lectura):")
        appendLine("«${text.trim().replace('\n', ' ')}»")
        appendLine()
        appendLine("Elige de esta lista lo que le ayudaría a seguir describiendo ESA escena de forma coherente. Responde SOLO con un objeto JSON válido, sin texto extra ni bloques de código.")
        appendLine()
        appendLine("CANDIDATOS (código: forma, tipo):")
        candidates.forEach { appendLine("- ${it.ref}: ${it.label} (${it.kind})") }
        appendLine()
        appendLine("- Hasta $known de su lista (códigos que empiezan con w o g) y hasta $extra que aún no conoce (códigos sv o sg).")
        appendLine("- Prefiere sustantivos y adjetivos de lo que se ve, se oye o se siente en esa escena, y una gramática que sirva para describirla.")
        appendLine("- No elijas lo que ya usó en el texto ni lo que no tenga relación con la escena. Si nada encaja, devuelve la lista vacía.")
        appendLine("- Solo códigos de la lista, tal cual.")
        appendLine()
        appendLine("FORMATO:")
        appendLine("{\"items\": [\"w12\", \"sv465\"]}")
    }

    /**
     * Significado y ejemplos naturales de las sugerencias del diario, todas en una llamada:
     * al tocar la primera se piden las de la página y quedan guardadas para siempre.
     * [items]: forma y si es gramática.
     */
    fun explainItems(profile: String, level: String, standardLevel: Int, items: List<Pair<String, Boolean>>): String = buildString {
        appendLine("Eres profesor de coreano. Un estudiante hispanohablante de nivel $level (≈ nivel $standardLevel de 6 del 국제 통용 한국어 표준 교육과정) escribe su diario a mano y la app le sugirió estas palabras y gramáticas. Explica cada una y muestra cómo la usaría un coreano en la vida diaria. Responde SOLO con un objeto JSON válido, sin texto extra ni bloques de código.")
        if (profile.isNotBlank()) {
            appendLine()
            appendLine("SOBRE EL ESTUDIANTE: ${profile.trim().replace('\n', ' ')}")
        }
        appendLine()
        appendLine("ÍTEMS:")
        items.forEach { (form, isGrammar) -> appendLine("- $form (${if (isGrammar) "gramática" else "palabra"})") }
        appendLine()
        appendLine(
            """
            Para cada ítem, en el mismo orden, en "items":
            - "f": la forma tal como aparece arriba.
            - "s": significado en español, máximo 10 palabras.
            - "u": cuándo y cómo se usa de forma natural, en español, 1 o 2 frases: matiz, registro, con qué partícula o palabras suele ir, o con qué no confundirla.
            - "c": en gramática, cómo se une a verbos y adjetivos con 2 o 3 ejemplos cortos (가다 → 가느라고, 먹다 → 먹느라고). En palabras, la conjugación en -어요 si es irregular (듣다 → 들어요); si no, null.
            - "ej": 2 oraciones naturales y cotidianas, como las diría un coreano, que el estudiante podría escribir en su diario (su día, la universidad, amigos, planes). Fuera del ítem, vocabulario y gramática de nivel $standardLevel o menor. Registro -어요. Cada una: "ko" y "en" (traducción al inglés).

            FORMATO:
            {"items": [{"f": "-느라고", "s": "...", "u": "...", "c": "...", "ej": [{"ko": "...", "en": "..."}]}]}
            """.trimIndent()
        )
    }

    /**
     * Página de cualquier libro (student book, workbook u otro), paso 1: gramática de la página y,
     * si hay ejercicios, pistas para combinar la gramática de la lección con lo que ya estudiaste.
     * [focus]: la gramática que el estudiante dice que practica, o null para que la IA la deduzca.
     * [previous]: patrones que ya estudió (máx. ~30), para que las pistas salgan de su lista.
     */
    fun workbookHints(level: String, standardLevel: Int, focus: String?, previous: List<String>): String = buildString {
        appendLine("Eres profesor de coreano. La imagen es una página de un libro de coreano (student book, workbook u otro) de un estudiante hispanohablante de nivel $level (≈ nivel $standardLevel de 6 del 국제 통용 한국어 표준 교육과정). Responde SOLO con un objeto JSON válido, sin texto extra ni bloques de código.")
        appendLine()
        if (focus.isNullOrBlank()) {
            appendLine("GRAMÁTICA DE LA LECCIÓN: no se indicó. Dedúcela de la página (título, recuadros o lo que piden los ejercicios) y márcala con \"foco\" en \"gram\".")
        } else {
            appendLine("GRAMÁTICA DE LA LECCIÓN (la eligió el estudiante): $focus")
        }
        if (previous.isNotEmpty()) {
            appendLine("GRAMÁTICAS QUE YA ESTUDIÓ: ${previous.joinToString(" · ")}")
        }
        appendLine()
        appendLine(
            """
            1. "ejercicios": cada ejercicio o pregunta por completar, en orden. "n": el número como aparece en la página ("1", "2-3", "(4)"). "e": qué pide, en español, máximo 15 palabras. Si la página no tiene ejercicios (p. ej. una explicación), devuelve [] y llena igual "vocab" y "gram".
            2. "p": de 1 a 3 pistas por ejercicio. El estudiante ya entiende la gramática de la lección y la va a usar: NO la des como pista. Propón cómo enriquecer la MISMA oración combinándola con otra cosa: una gramática que ya estudió (prioriza las de su lista), un conector, o un sustantivo o verbo útil. Son sugerencias opcionales. NO des la respuesta. Cada pista:
               - "f": la forma. Gramática en notación estándar (-느라고, -더라고요, -을 텐데); palabras en forma de diccionario.
               - "t": g (gramática), c (conector), x (expresión), n (sustantivo), v (verbo), a (adjetivo), adv (adverbio).
               - "u": para qué sirve y cómo combinarla con la gramática de la lección en este ejercicio, en español, 1 o 2 frases.
               - "k": un ejemplo corto en coreano que use la pista y la gramática de la lección juntas, distinto al ejercicio.
               - "en": la traducción del ejemplo al inglés.
            3. "r": una respuesta modelo que use la gramática de la lección y, si encaja, una de las pistas (se mostrará escondida).
            4. "vocab": las palabras de vocabulario impresas en la página (listas, recuadros, alrededor y dentro de las preguntas), sobre todo sustantivos, en forma de diccionario. "t" como arriba y "s": significado en español. Sin gramática ni partículas.
            5. "gram": TODAS las gramáticas, conectores y expresiones gramaticales impresas en la página (título, recuadros, ejemplos, instrucciones, preguntas y textos), sin repetir, en notación estándar (-느라고, -(으)ㄹ 텐데, N만 하다). Omite las básicas: 이/가, 은/는, 을/를, 에, 의, 도, 와/과, -어요/아요, -습니다/ㅂ니다, -았/었-. Cada una:
               - "f": la forma.
               - "s": significado en español, máximo 8 palabras.
               - "foco": true si es la gramática que enseña esta lección: resaltada arriba o en el título, en un recuadro de explicación, o la que practican la mayoría de los ejercicios. Si no, false. Puede haber más de una o ninguna.
               - "d": dónde aparece: titulo, recuadro, ejercicios, instrucciones o texto.
            6. "unidad": el número de la unidad o lección si se ve en la página (5 para "5과" o "Unit 5"); si no, null.

            FORMATO:
            {"ejercicios": [{"n": "1", "e": "...", "p": [{"f": "-는데", "t": "c", "u": "...", "k": "...", "en": "..."}], "r": "..."}], "vocab": [{"f": "미용실", "t": "n", "s": "peluquería"}], "gram": [{"f": "-느라고", "s": "por estar haciendo (causa negativa)", "foco": true, "d": "titulo"}], "unidad": 5}
            """.trimIndent()
        )
    }

    /**
     * Workbook, paso 2: revisa lo que el estudiante escribió. Se mandan los ejercicios y pistas
     * del paso 1 (texto corto) para que la IA no tenga que volver a interpretar la página entera.
     */
    fun workbookReview(level: String, standardLevel: Int, exercises: List<WorkbookExercise>, lesson: List<String> = emptyList()): String = buildString {
        appendLine("Eres profesor de coreano. La imagen muestra las respuestas que un estudiante hispanohablante de nivel $level (≈ nivel $standardLevel de 6) escribió a mano en una página de su libro. Responde SOLO con un objeto JSON válido, sin texto extra ni bloques de código.")
        appendLine()
        if (lesson.isNotEmpty()) {
            appendLine("GRAMÁTICA DE LA LECCIÓN (solo contexto): ${lesson.joinToString(" · ")}. Las pistas eran sugerencias opcionales: no exijas usarlas. Si eligió otra conexión válida, evalúala igual por si está bien y es natural.")
            appendLine()
        }
        appendLine("EJERCICIOS DE LA PÁGINA (n | qué pide | pistas que vio):")
        exercises.forEach { e -> appendLine("${e.number} | ${e.ask} | ${e.hints.joinToString(", ") { it.form }}") }
        appendLine()
        appendLine(
            """
            1. "rev": por cada ejercicio que respondió: "n"; "w": lo que escribió, transcrito tal cual; "s": "ok" (correcto y natural), "fix" (tiene errores) o "nat" (correcto, pero un coreano lo diría distinto); "c": la versión corregida si s es "fix"; "nat": la versión más natural si s es "nat" (o si es "fix" y hay una forma mejor), si no null; "x": explicación breve en español. Omite los ejercicios sin respuesta.
            2. "usados": TODO lo que el estudiante escribió, solo lo suyo y no lo impreso: palabras en forma de diccionario y gramática en notación estándar (-어서, -느라고). Omite 이/가, 은/는, 을/를, 에, 의, 도, 와/과, -어요/아요, -습니다/ㅂ니다, -었-/았-. Cada uno: "f", "t" (n, v, a, adv, g u o) y "ok" (false si lo usó mal).

            FORMATO:
            {"rev": [{"n": "1", "w": "...", "s": "fix", "c": "...", "nat": null, "x": "..."}], "usados": [{"f": "-느라고", "t": "g", "ok": true}]}
            """.trimIndent()
        )
    }

    fun importList(list: String = "{{lista}}"): String = IMPORT_TEMPLATE.replace("{{lista}}", list)

    private val IMPORT_TEMPLATE = """
        Convierte la siguiente lista de estudio de coreano en un objeto JSON válido. Responde SOLO con el JSON, sin texto adicional ni bloques de código.

        REGLAS:
        - Separa vocabulario y gramática.
        - Vocabulario: forma de diccionario (verbos y adjetivos terminados en 다). Tipo: sustantivo, verbo, adjetivo, adverbio u otro. Marca la irregularidad si existe: ㅂ, ㄷ, ㅅ, ㅎ, 르, ㄹ, ㅡ o null. Nivel según el 한국어기초사전 si lo sabes: inicial, intermedio, avanzado o desconocido. Significado en español.
        - Gramática: conserva la unidad y el nivel si aparecen en la lista; si no, usa null o "base". Si un punto tiene variantes para A, V y N, ponlas como variantes de un solo punto. Agrega significado en español, un ejemplo corto en coreano con traducción y etiquetas de uso (por ejemplo: pasado, futuro, discurso indirecto, causa, intención).
        - No inventes palabras que no estén en la lista. No dupliques.

        FORMATO:
        {
          "palabras": [{"forma_base": "", "tipo": "", "irregular": null, "nivel": "", "significado": ""}],
          "gramatica": [{"unidad": null, "nivel": "", "patron": "", "variantes": [{"aplica_a": "A|V|N", "forma": ""}], "significado": "", "ejemplo": "", "etiquetas": []}]
        }

        LISTA:
        {{lista}}
    """.trimIndent()
}
