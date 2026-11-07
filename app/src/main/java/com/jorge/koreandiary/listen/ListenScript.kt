package com.jorge.koreandiary.listen

import com.jorge.koreandiary.ai.ParseException
import com.jorge.koreandiary.ai.PromptItem
import com.jorge.koreandiary.ai.extractJsonObject
import com.jorge.koreandiary.conversation.CorrectionStyle
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Pregunta que la IA hace en una pausa: abierta, para que respondas usando un objetivo de repaso. */
data class PausePrompt(val ko: String, val es: String, val target: String?)

/** Un trozo de la narración (2–4 oraciones). [targets]: objetivos que aparecen, repetidos si salen dos veces. */
data class ScriptSegment(
    val ko: String,
    val es: String,
    val targets: List<String> = emptyList(),
    /** Pregunta al terminar este segmento (punto de pregunta) o null si sigue solo. */
    val question: PausePrompt? = null,
)

/** Pregunta de comprensión del final, con la respuesta esperada en español (para la IA y la revisión). */
data class ComprehensionQuestion(val ko: String, val es: String, val answer: String)

data class ListenScript(
    val title: String,
    val titleEs: String,
    val segments: List<ScriptSegment>,
    val comprehension: List<ComprehensionQuestion>,
    /** Páginas que usó la búsqueda de Google (si estaba activada). */
    val sources: List<String> = emptyList(),
) {
    /** Veces que aparece cada objetivo en los segmentos [0, upTo). */
    fun targetCounts(upTo: Int = segments.size): Map<String, Int> =
        segments.take(upTo.coerceIn(0, segments.size)).flatMap { it.targets }.groupingBy { it }.eachCount()
}

/** Resultado de una pregunta de comprensión, según la IA de voz. */
data class ComprehensionResult(val index: Int, val correct: Boolean, val note: String)

/** Lee el guion que escribe el modelo y lo guarda/recupera con la sesión. */
object ListenScriptParser {

    fun parse(raw: String, validTargets: Set<String> = emptySet()): ListenScript {
        val o = try {
            JSONObject(extractJsonObject(raw))
        } catch (e: JSONException) {
            throw ParseException("El guion no es un JSON válido: ${e.message}")
        }
        val script = decode(o, validTargets)
        if (script.segments.isEmpty()) throw ParseException("El guion no trae segmentos.")
        return script
    }

    fun encode(s: ListenScript): JSONObject = JSONObject()
        .put("titulo", s.title)
        .put("titulo_es", s.titleEs)
        .put("segmentos", JSONArray().apply {
            s.segments.forEach { seg ->
                put(
                    JSONObject().put("texto", seg.ko).put("es", seg.es).put("objetivos", JSONArray(seg.targets)).apply {
                        seg.question?.let { q ->
                            put("pregunta", JSONObject().put("texto", q.ko).put("es", q.es).put("objetivo", q.target ?: ""))
                        }
                    }
                )
            }
        })
        .put("comprension", JSONArray().apply {
            s.comprehension.forEach { put(JSONObject().put("pregunta", it.ko).put("es", it.es).put("respuesta", it.answer)) }
        })
        .put("fuentes", JSONArray(s.sources))

    /** [validTargets] vacío = no filtrar (al leer una sesión guardada). */
    fun decode(o: JSONObject, validTargets: Set<String> = emptySet()): ListenScript {
        fun ok(ref: String) = ref.isNotBlank() && (validTargets.isEmpty() || ref in validTargets)
        val segs = o.optJSONArray("segmentos") ?: JSONArray()
        val segments = (0 until segs.length()).mapNotNull { i ->
            val s = segs.optJSONObject(i) ?: return@mapNotNull null
            val ko = s.optString("texto").trim()
            if (ko.isEmpty()) return@mapNotNull null
            val refs = s.optJSONArray("objetivos")
            val q = s.optJSONObject("pregunta")?.let { p ->
                val text = p.optString("texto").trim()
                if (text.isEmpty()) null else PausePrompt(text, p.optString("es").trim(), p.optString("objetivo").trim().takeIf(::ok))
            }
            ScriptSegment(
                ko = ko,
                es = s.optString("es").trim(),
                targets = (0 until (refs?.length() ?: 0)).map { refs!!.optString(it).trim() }.filter(::ok),
                question = q,
            )
        }
        // Nunca una pregunta después del último segmento: ahí vienen las de comprensión.
        val fixed = segments.mapIndexed { i, s -> if (i == segments.lastIndex && s.question != null) s.copy(question = null) else s }
        val comp = o.optJSONArray("comprension") ?: JSONArray()
        val sources = o.optJSONArray("fuentes")
        return ListenScript(
            title = o.optString("titulo").trim(),
            titleEs = o.optString("titulo_es").trim(),
            segments = fixed,
            comprehension = (0 until comp.length()).mapNotNull { i ->
                val c = comp.optJSONObject(i) ?: return@mapNotNull null
                val q = c.optString("pregunta").trim()
                if (q.isEmpty()) null else ComprehensionQuestion(q, c.optString("es").trim(), c.optString("respuesta").trim())
            },
            sources = (0 until (sources?.length() ?: 0)).map { sources!!.optString(it) }.filter { it.isNotBlank() },
        )
    }
}

/** Tamaño del guion según los minutos de la sesión (la narración ocupa ~55 %; el resto son pausas). */
data class ScriptPlan(val segments: Int, val questions: Int, val comprehension: Int, val syllables: Int) {
    companion object {
        fun forMinutes(minutes: Int): ScriptPlan = when {
            minutes <= 5 -> ScriptPlan(segments = 4, questions = 1, comprehension = 2, syllables = 550)
            minutes <= 10 -> ScriptPlan(segments = 6, questions = 2, comprehension = 3, syllables = 1100)
            else -> ScriptPlan(segments = 8, questions = 3, comprehension = 3, syllables = 1600)
        }
    }
}

object ListenPrompts {

    /** Guion del pódcast (lo escribe el modelo de texto, con búsqueda de Google si está activada). */
    fun script(
        profile: String,
        level: String,
        standardLevel: Int,
        topic: String,
        targets: List<PromptItem>,
        minutes: Int,
        search: Boolean,
    ): String = buildString {
        val plan = ScriptPlan.forMinutes(minutes)
        val n = standardLevel.coerceIn(1, 6)
        appendLine("Eres guionista de un pódcast corto en coreano para un estudiante hispanohablante de nivel $level (≈ nivel $n de 6 del estándar coreano). Lo va a ESCUCHAR, muchas veces sin mirar la pantalla, para entrenar el oído. Responde SOLO con un objeto JSON válido, sin texto extra ni bloques de código.")
        if (profile.isNotBlank()) appendLine("Sobre él: ${profile.trim()}")
        appendLine()
        appendLine("TEMA: ${topic.ifBlank { "algo cotidiano e interesante de la vida en Corea" }}")
        if (search) {
            appendLine("Busca en Google información actual y real sobre el tema y básate en ella. Menciona solo hechos que encontraste; sin URLs ni citas dentro del texto.")
        }
        appendLine()
        appendLine(
            """
            NARRACIÓN
            - Un solo narrador, tono de pódcast cercano, en 해요체. Cuenta algo con un hilo (una historia, una noticia explicada, una experiencia), no una lista de datos.
            - Unas ${plan.syllables} sílabas en total, divididas en ${plan.segments} segmentos de 2 a 4 oraciones.
            - Vocabulario y gramática de nivel $n o menor; alguna palabra de nivel ${n + 1} solo si el contexto la explica.
            - Es para leer en voz alta: sin listas, emojis, paréntesis, romanización ni símbolos. Escribe los números y las horas en hangul (세 시, 이십 분, 삼월) para que se lean bien.
            - Repite de forma natural las ideas clave: ayuda a seguir el hilo solo escuchando.
            """.trimIndent()
        )
        if (targets.isNotEmpty()) {
            appendLine()
            appendLine("OBJETIVOS DE REPASO (vocabulario y gramática que está repasando). Úsalos con naturalidad en la narración, cada uno 1 a 3 veces, sin destacarlos:")
            targets.forEach { appendLine("- ${it.ref}: ${it.label} (${it.kind})") }
            appendLine("En \"objetivos\" de cada segmento pon los códigos de los que aparecen en ese segmento (repetido si aparece dos veces).")
        }
        appendLine()
        appendLine(
            """
            PREGUNTAS EN LAS PAUSAS
            - Exactamente ${plan.questions} segmentos (nunca el último) llevan "pregunta": una pregunta corta y abierta en coreano para él, relacionada con lo que acaba de escuchar (su opinión o su experiencia, no un examen), cuya respuesta natural use un objetivo de repaso. En "objetivo" va su código ("" si ninguno encaja).
            - Repártelas a lo largo de la narración.

            COMPRENSIÓN AL FINAL
            - ${plan.comprehension} preguntas sencillas en coreano sobre el contenido, cada una con su traducción y la respuesta esperada (corta, en español).

            FORMATO EXACTO:
            {"titulo":"título en coreano","titulo_es":"título en español",
             "segmentos":[{"texto":"oraciones en coreano","es":"traducción natural al español","objetivos":["w12"],"pregunta":{"texto":"pregunta en coreano","es":"traducción","objetivo":"w12"}}],
             "comprension":[{"pregunta":"pregunta en coreano","es":"traducción","respuesta":"respuesta esperada en español"}]}
            Los segmentos sin pregunta no llevan la clave "pregunta".
            """.trimIndent()
        )
    }

    /**
     * Instrucciones del modelo de voz en el modo Escuchar. En inglés, como el de la conversación.
     * La narración la pone la app con otra voz: el modelo solo habla en las pausas.
     */
    fun liveSystem(
        profile: String,
        level: String,
        standardLevel: Int,
        script: ListenScript,
        targets: List<PromptItem>,
        correction: String,
    ): String = buildString {
        appendLine("You host a Korean listening session for a Spanish-speaking learner at about TOPIK level ${standardLevel.coerceIn(1, 6)} (his textbook level: $level). He is often not looking at the screen.")
        if (profile.isNotBlank()) appendLine("About him: ${profile.trim()}")
        appendLine("The app plays the narration below with a separate voice. You stay silent during the narration and only speak during PAUSES.")
        appendLine()
        appendLine("NARRATION — \"${script.title}\" (${script.titleEs}):")
        script.segments.forEachIndexed { i, s ->
            appendLine("[${i + 1}] ${s.ko}")
            if (s.es.isNotBlank()) appendLine("    (es: ${s.es})")
            s.question?.let { q -> appendLine("    → Question after segment ${i + 1}: ${q.ko}${q.target?.let { t -> " (target $t)" } ?: ""}") }
        }
        if (script.comprehension.isNotEmpty()) {
            appendLine()
            appendLine("COMPREHENSION QUESTIONS (only at the end, when [APP] says so):")
            script.comprehension.forEachIndexed { i, c -> appendLine("${i + 1}. ${c.ko} — expected answer: ${c.answer}") }
        }
        appendLine()
        appendLine(
            """
            IN A PAUSE, WHEN HE SPEAKS:
            - He asks in Korean → answer in simple, slow Korean (short sentences).
            - He asks in Spanish (including Spanish in the middle of a Korean sentence) → answer IN SPANISH, briefly,
              then say the Korean word or phrase once more, slowly. He may ask the same thing again in Spanish if
              your Korean was too hard: then explain it in Spanish.
            - A word from the narration he didn't understand → its Spanish meaning in a few words + the word slowly.
            - "pista" / "힌트" / "ayuda" → a hint for the current question: first a key word, if he asks again half a
              sentence, then a full sentence he can repeat.
            - Sounds like 음, 어, "eh", "este" are him thinking: wait silently, never finish his sentence.
            - Keep every answer short: he wants to get back to the narration.

            VOICE COMMANDS (call the function; at most a 1–2 word acknowledgement):
            - "sigue", "continúa", "계속", "계속해요" → resume_narration.
            - "repite", "otra vez", "다시" → repeat_segment. "más lento", "천천히" → repeat_segment with slower=true.
            - "pausa", "espera", "잠깐만" → pause_narration (he needs more time).
            - "termina", "terminar", "그만" → finish_listening.

            QUESTION POINTS: when [APP] tells you, ask that question naturally in Korean (call highlight_target with its
            target id if it has one) and wait. When he answers, give short feedback, then call resume_narration.
            If instead he asked you something, answer it, ask "계속 들을까요?" and wait for him.
            When [APP] says he paused by himself: say nothing and wait for his question.

            COMPREHENSION: when [APP] says the narration ended, ask the comprehension questions one at a time in simple
            Korean. After each answer: say if it is right; if not, give the right answer in simple Korean and in
            Spanish. Then call comprehension_result. After the last one, say goodbye in one sentence and call
            finish_listening.
            CORRECTION_RULES
            - Messages that start with [APP] come from the learning app, not from him: follow them, never read them
              aloud and never mention them. Never say function names or target ids.
            """.trimIndent().replace("CORRECTION_RULES", correctionRules(correction))
        )
        if (targets.isNotEmpty()) {
            appendLine()
            appendLine("PRACTICE TARGETS (he is reviewing them; the narration uses them):")
            targets.forEach { appendLine("- ${it.ref}: ${it.label} (${it.kind})") }
        }
    }

    private fun correctionRules(style: String): String = if (style == CorrectionStyle.SOFT) {
        "- Corrections: when he answers in Korean with a mistake, repeat his idea correctly as part of your feedback."
    } else {
        """
        - Corrections: small mistakes → repeat his idea correctly as part of your feedback. Important problems
          (the meaning changes or it sounds very unnatural) → say the natural version, ask him to repeat it once
          ("따라 해 보세요: …"), praise briefly and move on.
        """.trimIndent()
    }

    fun heard(index: Int) = "[APP] Ya escuchó el segmento ${index + 1}."

    fun askQuestion(index: Int, q: PausePrompt) =
        "[APP] Pausa después del segmento ${index + 1}. Hazle ahora la pregunta: «${q.ko}»" +
            (q.target?.let { " (objetivo $it)" } ?: "") + ". Luego espera su respuesta."

    fun manualPause(index: Int) = "[APP] Él pausó la narración durante el segmento ${index + 1}. No digas nada: espera su pregunta."

    const val COMPREHENSION = "[APP] La narración terminó. Empieza las preguntas de comprensión."

    const val NO_COMPREHENSION = "[APP] La narración terminó. Despídete en una frase y llama finish_listening."

    const val TIME_UP = "[APP] Se acabó el tiempo. Despídete en una frase y llama finish_listening."

    /** Estilo de lectura para el TTS (no se lee: va como anotación). */
    const val TTS_STYLE = "warm Korean podcast narrator, clear and natural, at a calm pace"
}

/** Órdenes cortas que se reconocen en lo que dices, por si el modelo no llama a la función. */
enum class VoiceCommand { RESUME, REPEAT, SLOWER, PAUSE, FINISH }

object VoiceCommands {
    private val phrases: Map<VoiceCommand, List<String>> = mapOf(
        VoiceCommand.SLOWER to listOf(
            "más lento", "mas lento", "más despacio", "mas despacio", "repite más lento", "repite mas lento",
            "otra vez más lento", "천천히", "다시 천천히", "천천히 해 주세요", "천천히 다시", "더 천천히",
        ),
        VoiceCommand.REPEAT to listOf(
            "repite", "repítelo", "repitelo", "repetir", "otra vez", "de nuevo", "repite eso",
            "다시", "다시요", "다시 해 주세요", "다시 들려주세요", "한 번 더", "한번 더",
        ),
        VoiceCommand.RESUME to listOf(
            "sigue", "seguir", "sigamos", "continúa", "continua", "continuar", "dale", "adelante",
            "계속", "계속해", "계속해요", "계속해 주세요", "계속하세요", "계속 들을게요", "계속 들을래요",
        ),
        VoiceCommand.PAUSE to listOf("pausa", "espera", "un momento", "잠깐", "잠깐만", "잠깐만요"),
        VoiceCommand.FINISH to listOf("termina", "terminar", "terminemos", "ya terminé", "그만", "그만할게요", "끝"),
    )

    /** Muletillas y cortesía que no cambian la orden ("sigue, por favor" = "sigue"). */
    private val fillers = setOf("por", "favor", "porfa", "eh", "em", "este", "bueno", "ya", "ok", "okay", "vale", "음", "어", "그", "네", "좋아요", "좀")

    private fun squash(s: String) = s.replace(" ", "")

    private val index: Map<String, VoiceCommand> = phrases.flatMap { (cmd, list) -> list.map { squash(it) to cmd } }.toMap()

    /** La orden si TODO lo que dijiste es una orden; null si es una pregunta u otra cosa. */
    fun match(text: String): VoiceCommand? {
        val words = text.lowercase()
            .replace(Regex("[¿?¡!.,;:…\"«»]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it !in fillers }
        if (words.isEmpty() || words.size > 4) return null
        return index[squash(words.joinToString(" "))]
    }
}
