package com.jorge.koreandiary.ai

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
    ): String = buildString {
        appendLine("Eres profesor de coreano y corriges el diario manuscrito de un estudiante hispanohablante. Responde SOLO con un objeto JSON válido, sin texto extra ni bloques de código.")
        appendLine()
        appendLine("ESTUDIANTE: ${profile.ifBlank { "(sin perfil)" }}")
        appendLine("Nivel: $level (≈ nivel $standardLevel de 6 del 국제 통용 한국어 표준 교육과정)")
        appendLine()
        appendLine("SUGERIDOS EN ESTA PÁGINA (id | forma | tipo):")
        if (items.isEmpty()) appendLine("(ninguno)")
        items.forEach { appendLine("${it.ref} | ${it.label} | ${it.kind}") }
        appendLine()
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
            4. "mejoras": como máximo 2 estructuras de nivel $standardLevel o menor que el estudiante debería saber y que encajarían mejor donde escribió algo demasiado básico. Solo si de verdad aplica; si no, [].

            FORMATO:
            {
              "oraciones": [{"n": 1, "texto": "...", "tiempo": "pasado"}],
              "correcciones": [{"oracion": 1, "original": "...", "sugerencia": "...", "tipo": "ortografia|particula|conjugacion|gramatica|naturalidad", "explicacion": "...", "item_id": null}],
              "usados": [{"f": "자다", "t": "v", "ok": true, "id": "w42"}, {"f": "-어서", "t": "g", "ok": true, "id": null}],
              "mejoras": [{"oracion": 2, "estructura": "-는 바람에", "ejemplo": "...", "explicacion": "..."}]
            }
            """.trimIndent()
        )
    }

    /**
     * "¿Cómo digo esto?": la frase con lo que el estudiante sabe y, si difiere, la versión natural.
     * No se manda su lista (ahorra tokens): basta el nivel; la app marca después lo que no está en sus listas.
     */
    fun howToSay(idea: String, level: String, standardLevel: Int): String = """
        Eres profesor de coreano. Un estudiante hispanohablante de nivel $level (≈ nivel $standardLevel de 6 del 국제 통용 한국어 표준 교육과정) escribe su diario y quiere decir:
        «${idea.trim().replace('\n', ' ')}»
        Responde SOLO con un objeto JSON válido, sin texto extra ni bloques de código.

        1. "tu": la frase en coreano usando solo vocabulario y gramática de nivel $standardLevel o menor; la forma más simple que siga siendo correcta. Registro -어요 salvo que la idea pida otro.
        2. "nat": cómo lo diría normalmente un coreano. Si sería igual a "tu", pon null.
        3. En "g" de cada frase, las estructuras gramaticales usadas en notación estándar: terminaciones con guion y forma con 으/어 (-어서, -을 것 같다); modificadores de sustantivo como -은, -는, -던; partículas sin guion (처럼). Omite 이/가, 은/는, 을/를, 에, 도, -어요.
        4. "nota": 1 o 2 frases en español sobre la diferencia, o por qué la frase ya es natural.
        5. "w": las palabras de contenido en forma de diccionario con su significado en español.

        FORMATO:
        {"tu": {"k": "...", "g": ["-은"]}, "nat": {"k": "...", "g": ["-었던"]}, "nota": "...", "w": [{"f": "다니다", "s": "asistir"}]}
    """.trimIndent()

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
