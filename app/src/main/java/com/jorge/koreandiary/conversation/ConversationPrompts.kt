package com.jorge.koreandiary.conversation

import com.jorge.koreandiary.ai.PromptItem

/** Quién dijo cada línea de la conversación. */
enum class Speaker { USER, AI }

data class Line(val speaker: Speaker, val text: String)

/** Cómo corrige la IA durante la conversación. */
object CorrectionStyle {
    /** Repite tu idea bien dicha dentro de su respuesta (recast). */
    const val SOFT = "suave"
    /** Igual que suave, pero en errores importantes te pide repetir la versión natural. */
    const val REPEAT = "repetir"
}

/**
 * Prompts de la conversación.
 * - El del modelo de voz va en inglés: los modelos de voz siguen mejor las reglas así, y el
 *   ejemplo de traducción va en español porque es justo lo que tiene que imitar.
 * - Los del "coach" (pistas, traducciones y revisión final) usan el proveedor de corrección,
 *   así el modelo de voz nunca tiene que cortar la conversación para explicar.
 */
object ConversationPrompts {

    fun voiceSystem(
        profile: String,
        level: String,
        standardLevel: Int,
        topic: String,
        diaryText: String?,
        targets: List<PromptItem>,
        minutes: Int,
        correction: String = CorrectionStyle.REPEAT,
    ): String = buildString {
        appendLine("You are a friendly Korean conversation partner for a Spanish-speaking learner at about TOPIK level ${standardLevel.coerceIn(1, 6)} (his textbook level: $level).")
        if (profile.isNotBlank()) appendLine("About him: ${profile.trim()}")
        appendLine("The session lasts about $minutes minutes.")
        appendLine()
        appendLine(
            """
            TOP PRIORITY — SPANISH HELP (this overrides every other rule):
            When he asks for a meaning or translation, or says he didn't understand
            (in Spanish OR Korean: "¿qué significa…?", "traduce", "no entendí",
            "¿qué dijiste?", "뭐예요?", "다시요"), you MUST answer IN SPANISH, briefly.
            - One word → its Spanish meaning in a few words, then say the Korean word once, slowly.
            - "No entendí" / "traduce" → translate YOUR last sentence into Spanish, then say it
              again in simpler, slower Korean.
            - Then go back to Korean and continue the topic.
            NEVER explain a meaning in Korean.

            Example:
            You: 주말에 뭐 할 거예요? 등산 갈 거예요?
            Him: ¿Qué significa 등산?
            You: "등산" significa hacer senderismo, subir montañas. 등산. 주말에 등산 좋아해요?

            Example:
            You: 요즘 회사 일이 바빠서 피곤하겠네요.
            Him: No entendí.
            You: Dije: "Últimamente debes estar cansado porque el trabajo está ocupado." 회사 일이 많아요? 피곤해요?

            OTHER RULES:
            - Otherwise speak only Korean (해요체), short simple sentences, a bit slower than normal.
              1–2 sentences per turn, and end with a question.
            - Sounds like 음, 어, 그, "eh", "este", "uh" are him thinking, not another language.
              Wait silently. Never finish his sentence for him.
            CORRECTION_RULES
            - If he asks for translations often, use simpler words from then on.
            - Messages that start with [APP] come from the learning app, not from him: follow them,
              never read them aloud and never mention them.
            """.trimIndent().replace("CORRECTION_RULES", correctionRules(correction))
        )
        appendLine()
        appendLine("TOPIC: ${topic.ifBlank { "daily life (his day, food, work, plans)" }}")
        if (!diaryText.isNullOrBlank()) {
            appendLine("Today he wrote this in his Korean diary. Talk with him about it, ask follow-up questions, but do not correct the diary itself:")
            appendLine(diaryText.trim().take(1500))
        }
        if (targets.isNotEmpty()) {
            appendLine()
            appendLine("PRACTICE TARGETS — vocabulary and grammar he is reviewing. Ask questions whose natural answer needs them, one at a time. Do not name them, do not quiz him, do not translate them unless he asks:")
            targets.forEach { appendLine("- ${it.label} (${it.kind})") }
        }
        appendLine()
        append("Start: greet him in Korean and ask your first question about the topic.")
    }

    private fun correctionRules(style: String): String = if (style == CorrectionStyle.SOFT) {
        """
        - Corrections: never interrupt. After he finishes, if he made a mistake, repeat his idea
          correctly in a natural way as part of your answer. At most one correction per turn.
        """.trimIndent()
    } else {
        """
        - Corrections: never interrupt him while he speaks.
          · Small mistakes: after he finishes, repeat his idea correctly in a natural way as part of your answer.
          · Important problems (the meaning changes, a Korean would not understand, or it sounds very unnatural):
            after he finishes, say the natural version and ask him to repeat it ("따라 해 보세요: …"), then wait.
            When he repeats it, praise briefly (좋아요!) and continue the topic. If he still gets it wrong,
            say it once more slowly and move on. Never more than twice for the same sentence.
          · Ask him to repeat at most once every 3 of his turns, so the conversation keeps flowing.
        """.trimIndent()
    }

    /** Aviso para cerrar la sesión con naturalidad. */
    const val WRAP_UP = "[APP] Queda un minuto. Cierra la conversación de forma natural en tus próximas respuestas y despídete."

    /** El estudiante tocó una palabra: hay que bajar el nivel sin decírselo. */
    fun unknownWord(word: String) = "[APP] No conocía la palabra «$word». Desde ahora usa palabras más simples."

    const val SLOWER = "[APP] Repite tu última frase más despacio y con palabras más simples, y sigue hablando así de lento."

    /**
     * Pista en tres niveles para responder a lo último que dijo la IA. La pantalla muestra
     * primero la palabra o el patrón, luego media oración y al final la oración completa.
     */
    fun hint(level: String, standardLevel: Int, recent: List<Line>, targets: List<PromptItem>): String = buildString {
        appendLine("Eres profesor de coreano. Un estudiante hispanohablante de nivel $level (≈ nivel $standardLevel de 6) está conversando por voz y se trabó: no sabe cómo responder. Responde SOLO con un objeto JSON válido, sin texto extra ni bloques de código.")
        appendLine()
        appendLine("ÚLTIMAS LÍNEAS (IA = su compañero de conversación, YO = el estudiante):")
        recent.takeLast(8).forEach { appendLine("${if (it.speaker == Speaker.AI) "IA" else "YO"}: ${it.text}") }
        if (targets.isNotEmpty()) appendLine("Si encaja, usa uno de estos objetivos de repaso: ${targets.joinToString(", ") { it.label }}")
        appendLine()
        appendLine(
            """
            Propón UNA respuesta corta y natural a la última pregunta de la IA, con vocabulario y gramática de nivel $standardLevel o menor, registro -어요.
            1. "clave": la palabra o el patrón gramatical clave, con su significado en español (p. ej. "-고 싶다: querer hacer").
            2. "medio": la primera mitad de la oración en coreano, para que el estudiante la complete.
            3. "frase": la oración completa en coreano.
            4. "es": su traducción al español.

            FORMATO:
            {"clave": "...", "medio": "...", "frase": "...", "es": "..."}
            """.trimIndent()
        )
    }

    /** Traducción de una palabra que dijo la IA, en el contexto de su oración. */
    fun word(word: String, sentence: String): String = """
        Eres profesor de coreano para hispanohablantes. En la oración «${sentence.trim()}» aparece «${word.trim()}».
        Responde SOLO con un objeto JSON válido, sin texto extra ni bloques de código.
        1. "f": la forma de diccionario de la palabra de contenido (먹었어요 → 먹다); si es una partícula o terminación, su notación estándar (-어서, 처럼).
        2. "s": su significado en español en este contexto, máximo 8 palabras.
        3. "nota": si la forma conjugada o una partícula pegada cambia el sentido, explícalo en 1 frase en español; si no, null.

        FORMATO:
        {"f": "...", "s": "...", "nota": null}
    """.trimIndent()

    /** Qué significa y cómo se usa un objetivo de repaso (palabra o gramática). */
    fun item(label: String, kind: String, standardLevel: Int): String = """
        Eres profesor de coreano para hispanohablantes de nivel ≈ $standardLevel de 6. Explica «${label.trim()}» (${kind.trim()}).
        Responde SOLO con un objeto JSON válido, sin texto extra ni bloques de código.
        1. "s": su significado en español, máximo 10 palabras.
        2. "uso": para qué sirve y cómo se usa en una conversación, en español, 1 o 2 frases. Si es gramática, con qué se combina (A/V/N) y cómo se conjuga.
        3. "k": un ejemplo corto y natural en coreano (registro -어요) que podría decir en una conversación.
        4. "es": la traducción del ejemplo.

        FORMATO:
        {"s": "...", "uso": "...", "k": "...", "es": "..."}
    """.trimIndent()

    /** Traducción de la última oración de la IA, con una versión más simple. */
    fun sentence(sentence: String, standardLevel: Int): String = """
        Eres profesor de coreano para hispanohablantes. Traduce al español: «${sentence.trim()}»
        Responde SOLO con un objeto JSON válido, sin texto extra ni bloques de código.
        1. "es": la traducción natural al español.
        2. "simple": la misma idea en coreano más simple (nivel $standardLevel o menor, registro -어요); si ya es simple, repítela.

        FORMATO:
        {"es": "...", "simple": "..."}
    """.trimIndent()

    /**
     * Revisión final. Usa el mismo contrato que la corrección del diario ("oraciones", "correcciones",
     * "usados", "mejoras") para reutilizar CorrectionParser; cada línea del estudiante es una "oración".
     * Además pide "comentario". La lista de estudio NO se modifica con esto (por ahora solo se guarda).
     */
    fun review(
        profile: String,
        level: String,
        standardLevel: Int,
        targets: List<PromptItem>,
        lines: List<Line>,
    ): String = buildString {
        appendLine("Eres profesor de coreano y revisas una conversación hablada entre un estudiante hispanohablante y una IA. Lo del estudiante es una transcripción automática que él ya revisó: es lenguaje hablado, no corrijas puntuación ni espacios. Responde SOLO con un objeto JSON válido, sin texto extra ni bloques de código.")
        appendLine()
        appendLine("ESTUDIANTE: ${profile.ifBlank { "(sin perfil)" }}")
        appendLine("Nivel: $level (≈ nivel $standardLevel de 6 del 국제 통용 한국어 표준 교육과정)")
        appendLine()
        appendLine("OBJETIVOS DE REPASO (id | forma | tipo):")
        if (targets.isEmpty()) appendLine("(ninguno)")
        targets.forEach { appendLine("${it.ref} | ${it.label} | ${it.kind}") }
        appendLine()
        appendLine("CONVERSACIÓN (las líneas del estudiante están numeradas):")
        var n = 0
        lines.forEach { line ->
            if (line.speaker == Speaker.USER) appendLine("${++n}. YO: ${line.text}") else appendLine("   IA: ${line.text}")
        }
        appendLine()
        appendLine(
            """
            TAREAS (solo sobre las líneas numeradas del estudiante; las de la IA son contexto):
            1. "oraciones": cada línea numerada con su tiempo principal: "pasado", "presente" o "futuro". Omite las que estén en español.
            2. "correcciones": partículas, conjugación, gramática y naturalidad. "oracion" es el número de la línea. No cambies lo correcto ni reescribas por estilo. Explica en español en 1-2 frases.
            3. "usados": TODO lo que usó en coreano, una vez cada cosa:
               - Palabras de contenido en forma de diccionario (먹었어요 → 먹다).
               - Gramática en notación estándar: terminaciones con guion y forma con 으/어 (-어서, -으니까, -을 것 같다); partículas sin guion (이라도, 처럼).
               - Omite solo: 이/가, 은/는, 을/를, 에, 의, 도, 와/과, -어요/아요, -습니다/ㅂ니다, -었-/았-.
               - "id": el de los objetivos si coincide; si no, null.
               - "t": n, v, a, adv, g u o. "ok": false si lo usó mal.
            4. "mejoras": como máximo 2 estructuras de nivel $standardLevel o menor que encajarían donde dijo algo demasiado básico. Si no aplica, [].
            5. "comentario": 2 o 3 frases en español sobre cómo le fue (fluidez, lo que hizo bien, qué practicar).

            FORMATO:
            {
              "oraciones": [{"n": 1, "texto": "...", "tiempo": "pasado"}],
              "correcciones": [{"oracion": 1, "original": "...", "sugerencia": "...", "tipo": "particula|conjugacion|gramatica|naturalidad", "explicacion": "...", "item_id": null}],
              "usados": [{"f": "자다", "t": "v", "ok": true, "id": "w42"}],
              "mejoras": [{"oracion": 2, "estructura": "-는 바람에", "ejemplo": "...", "explicacion": "..."}],
              "comentario": "..."
            }
            """.trimIndent()
        )
    }

    /** Vocabulario para mejorar la transcripción: objetivos y palabras del diario de hoy. */
    fun vocabulary(targets: List<PromptItem>, diaryText: String?): List<String> {
        val fromTargets = targets.filter { it.kind != "gramática" }.map { it.label }
        val fromDiary = diaryText.orEmpty()
            .split(Regex("[\\s.,!?·…\"'()]+"))
            .map { it.trim() }
            .filter { w -> w.length >= 2 && w.any { it in '가'..'힣' } }
        return (fromTargets + fromDiary).distinct()
    }
}
