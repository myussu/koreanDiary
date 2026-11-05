package com.jorge.koreandiary.ai

/**
 * Prompts del documento de arquitectura. Están escritos para que cualquier LLM común
 * (Gemini, Claude, ChatGPT...) los siga y responda solo con JSON.
 */
object Prompts {

    fun correction(
        profile: String,
        level: String,
        items: List<PromptItem>,
        text: String,
        hasImage: Boolean,
    ): String = buildString {
        appendLine("Eres un profesor de coreano que corrige el diario escrito a mano de un estudiante hispanohablante de nivel intermedio. Responde SOLO con un objeto JSON válido, sin texto antes ni después y sin bloques de código.")
        appendLine()
        appendLine("PERFIL DEL ESTUDIANTE:")
        appendLine(profile.ifBlank { "(sin perfil)" })
        appendLine("Nivel de gramática actual: ${level.ifBlank { "intermedio" }}")
        appendLine()
        appendLine("ÍTEMS QUE EL ESTUDIANTE ESTÁ REPASANDO (id | forma base | tipo):")
        if (items.isEmpty()) appendLine("(ninguno)")
        items.forEach { appendLine("${it.ref} | ${it.label} | ${it.kind}") }
        appendLine()
        appendLine("TEXTO DEL DIARIO:")
        appendLine(text)
        if (hasImage) appendLine("(Hay una imagen adjunta de la página manuscrita: úsala como fuente principal y el texto como referencia.)")
        appendLine()
        appendLine(
            """
            TAREAS:
            1. Divide el texto en oraciones. Para cada una indica su tiempo: "pasado", "presente" o "futuro". Si mezcla tiempos, usa el principal.
            2. Corrige ortografía, partículas, conjugación, gramática y naturalidad. No cambies lo que ya es correcto ni reescribas por estilo. Si una oración está bien, no generes corrección.
            3. Explica cada corrección en español, en una o dos frases, citando la regla si aplica.
            4. Detecta qué ítems de la lista se usaron, aunque estén conjugados (먹었어요 = 먹다). Indica "correcto" o "incorrecto".
            5. Lista en "nuevos" las palabras de contenido (sustantivos, verbos, adjetivos, adverbios) que se usaron y NO están en la lista, en forma de diccionario. No incluyas partículas ni terminaciones.

            FORMATO DE SALIDA:
            {
              "oraciones": [{"n": 1, "texto": "...", "tiempo": "pasado"}],
              "correcciones": [{"oracion": 1, "original": "...", "sugerencia": "...", "tipo": "ortografia|particula|conjugacion|gramatica|naturalidad", "explicacion": "...", "item_id": "w42 o null"}],
              "usados": [{"id": "w42", "forma_base": "...", "resultado": "correcto|incorrecto"}],
              "nuevos": [{"forma_base": "...", "tipo": "sustantivo|verbo|adjetivo|adverbio", "significado": "..."}]
            }
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
