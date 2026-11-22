package com.jorge.koreandiary.ai

import com.jorge.koreandiary.conversation.TargetHelp
import com.jorge.koreandiary.review.Cloze
import com.jorge.koreandiary.review.ClozeKind
import com.jorge.koreandiary.scene.SceneDictionary
import org.json.JSONArray
import org.json.JSONObject

/**
 * IA de respuestas fijas, para la versión de prueba (la que se instala al lado de la tuya). No usa
 * la red ni gasta cuota: reconoce cada pedido por su texto y contesta con un JSON válido armado con
 * lo que el pedido trae (tus oraciones, los candidatos, los ítems). Así las pruebas con el emulador
 * recorren Revisar, «¿Cómo digo?», «Actualizar», las explicaciones, el repaso rápido (con tocar una
 * palabra) y el workbook siempre igual. Lo que no reconoce devuelve `{}`.
 *
 * Errores fijos para probar Revisar y Verificar (ver docs/PRUEBAS.md):
 * - «만나요» en una oración con «어제» → «만났어요» (conjugación).
 * - «친구을» → «친구를» (partícula).
 * - «학교에서 가요» → «학교에 가요» (partícula).
 * La última oración, si hay dos o más y no tiene error, vuelve como «más natural».
 * La primera corrección se engancha a un ítem de tu lista sugerido en la página (una gramática si
 * hay, si no una palabra): así se ve «Te corrigieron» al abrir ese ítem en Estudio.
 */
class FixedProvider : CorrectionProvider {
    override val id: String = "prueba"
    override suspend fun complete(request: CorrectionRequest): String = FixedResponses.answer(request.prompt)
}

object FixedResponses {

    fun answer(prompt: String): String = when {
        "corriges el diario manuscrito" in prompt -> correction(prompt)
        "\"tu\": la frase en coreano" in prompt -> howToSay(prompt)
        "CANDIDATOS (código: forma, tipo):" in prompt -> suggestions(prompt)
        "Explica cada una y muestra cómo la usaría" in prompt -> explain(prompt)
        "repasa con oraciones en las que falta una parte" in prompt -> cloze(prompt)
        "oración coreana del repaso de un estudiante" in prompt -> JSONObject().put("traduccion", "Traducción de prueba con [lo que falta].").toString()
        "es una página de un libro de coreano" in prompt -> workbookHints(prompt)
        "escribió a mano en una página de su libro" in prompt -> workbookReview(prompt)
        "\"f\": la forma de diccionario de la palabra de contenido" in prompt -> word(prompt)
        else -> "{}"
    }

    /** Tocar una palabra: le quita la partícula del final, si tiene una de las comunes, y la da como sustantivo. */
    private fun word(prompt: String): String {
        val word = Regex("""aparece «(.+?)»""").find(prompt)?.groupValues?.get(1)?.trim().orEmpty()
        val form = WORD_PARTICLES.firstOrNull { word.length > it.length && word.endsWith(it) }?.let { word.dropLast(it.length) } ?: word
        return JSONObject().put("f", form).put("t", "sustantivo").put("s", "significado de prueba")
            .put("p", fixedParts(form) ?: JSONObject.NULL).put("nota", JSONObject.NULL).toString()
    }

    /** Un desglose que cuadra (la primera sílaba + el resto), para ver «Partes» en la versión de prueba. */
    private fun fixedParts(form: String): String? {
        val bare = form.replace("-", "").trim()
        if (bare.length < 2 || bare.any { it !in '가'..'힣' }) return null
        return "${bare.take(1)} «parte de prueba» + ${bare.drop(1)} «parte de prueba»"
    }

    private val WORD_PARTICLES = listOf("에서", "을", "를", "이", "가", "은", "는", "에", "도", "와", "과")

    /** Las líneas de una sección: desde la que empieza con [header] hasta la primera vacía. */
    private fun section(prompt: String, header: String): List<String> {
        val lines = prompt.lines()
        val start = lines.indexOfFirst { it.startsWith(header) }
        if (start < 0) return emptyList()
        return lines.drop(start + 1).takeWhile { it.isNotBlank() }
    }

    private val FIXES = listOf(
        Triple("친구을", "친구를", "particula") to "Después de una vocal va 를.",
        Triple("학교에서 가요", "학교에 가요", "particula") to "Con 가다 el destino lleva 에, no 에서.",
    )

    private fun tense(s: String) = when {
        Regex("[었았였했갔왔봤셨]").containsMatchIn(s) -> "pasado"
        "겠" in s || "거예요" in s || "게요" in s -> "futuro"
        else -> "presente"
    }

    private fun correction(prompt: String): String {
        val text = section(prompt, "TEXTO:").filterNot { it.startsWith("(Hay una imagen") }.joinToString(" ")
        val sentences = Regex("""[^.!?\n]+[.!?]?""").findAll(text).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
        val oraciones = JSONArray()
        val correcciones = JSONArray()
        val corrected = mutableSetOf<Int>()
        sentences.forEachIndexed { i, s ->
            val n = i + 1
            oraciones.put(JSONObject().put("n", n).put("texto", s).put("tiempo", tense(s)))
            if ("어제" in s && "만나요" in s) {
                correcciones.put(fix(n, "만나요", "만났어요", "conjugacion", "Habla de ayer: va en pasado."))
                corrected += n
            }
            for ((t, why) in FIXES) if (t.first in s) {
                correcciones.put(fix(n, t.first, t.second, t.third, why))
                corrected += n
            }
        }
        // La primera corrección queda enganchada a un ítem de tu lista (no del estándar) sugerido aquí.
        val suggested = section(prompt, "SUGERIDOS EN ESTA PÁGINA").mapNotNull { it.split(" | ").firstOrNull()?.trim() }
        val owner = suggested.firstOrNull { Regex("""g\d+""").matches(it) } ?: suggested.firstOrNull { Regex("""w\d+""").matches(it) }
        if (owner != null && correcciones.length() > 0) correcciones.getJSONObject(0).put("item_id", owner)
        val naturals = JSONArray()
        val last = sentences.size
        if (last >= 2 && last !in corrected) {
            val s = sentences.last()
            naturals.put(JSONObject().put("oracion", last).put("original", s).put("sugerencia", "정말 $s").put("explicacion", "Respuesta fija: así suena más natural."))
        }
        // Lo sugerido que aparece en el texto cuenta como usado bien.
        val usados = JSONArray()
        section(prompt, "SUGERIDOS EN ESTA PÁGINA").forEach { line ->
            val parts = line.split(" | ")
            if (parts.size < 3) return@forEach
            val (ref, label, kind) = parts
            val grammar = kind.startsWith("gram")
            val found = if (grammar) Cloze.blankIn(text, label, ClozeKind.GRAMMAR) != null else TargetHelp.stemIn(label, text)
            val t = when {
                grammar -> "g"
                kind.startsWith("verbo") -> "v"
                kind.startsWith("adjetivo") -> "a"
                kind.startsWith("adverbio") -> "adv"
                else -> "n"
            }
            if (found) usados.put(JSONObject().put("f", label).put("t", t).put("ok", true).put("id", ref))
        }
        val o = JSONObject()
            .put("oraciones", oraciones)
            .put("correcciones", correcciones)
            .put("natural", naturals)
            .put("usados", usados)
            .put("mejoras", JSONArray())
        if ("LO QUE QUISO DECIR" in prompt) o.put("notas", JSONArray())
        if ("\"escena\":" in prompt) {
            o.put("escena", JSONArray(SceneDictionary.detect(text).map { it.id }))
            o.put("tono", JSONObject().put("id", "calm").put("n", 1))
            o.put("nuevos", JSONArray())
        }
        return o.toString()
    }

    private fun fix(n: Int, original: String, suggestion: String, type: String, why: String) = JSONObject()
        .put("oracion", n).put("original", original).put("sugerencia", suggestion).put("tipo", type)
        .put("explicacion", why).put("item_id", JSONObject.NULL).put("repite", 0)

    private fun howToSay(prompt: String): String {
        val o = JSONObject()
            .put("tu", JSONObject().put("k", "오늘 친구하고 공원에 갔어요.").put("g", JSONArray(listOf("-하고"))))
            .put("nat", JSONObject().put("k", "오늘 친구랑 공원에 놀러 갔어요.").put("g", JSONArray(listOf("-러 가다"))))
            .put("nota", "Respuesta fija de la versión de prueba: «놀러 가다» es ir a pasear.")
            .put("w", JSONArray().put(JSONObject().put("f", "공원").put("s", "parque")).put(JSONObject().put("f", "가다").put("s", "ir")))
            .put("pista", "공원 lleva 에 (destino) y 가다 va en pasado: -았어요.")
        if ("\"esc\":" in prompt) {
            o.put("esc", JSONArray(listOf("forest")))
            o.put("tono", JSONObject().put("id", "calm").put("n", 1))
        }
        return o.toString()
    }

    private fun suggestions(prompt: String): String {
        val codes = section(prompt, "CANDIDATOS").mapNotNull { Regex("""^- (\w+):""").find(it)?.groupValues?.get(1) }
        return JSONObject().put("items", JSONArray(codes.take(2))).toString()
    }

    private fun explain(prompt: String): String {
        val items = JSONArray()
        section(prompt, "ÍTEMS:").forEach { line ->
            val m = Regex("""^- (.+) \((gramática|palabra)\)$""").find(line) ?: return@forEach
            val form = m.groupValues[1]
            items.put(
                JSONObject().put("f", form).put("s", "significado de prueba").put("u", "Explicación fija de la versión de prueba.")
                    .put("c", if (m.groupValues[2] == "gramática") "가다 → 가요" else JSONObject.NULL)
                    .put("p", fixedParts(form) ?: JSONObject.NULL)
                    .put("ej", JSONArray().put(JSONObject().put("ko", "오늘 공원에 갔어요.").put("en", "I went to the park today.")))
            )
        }
        return JSONObject().put("items", items).toString()
    }

    private fun cloze(prompt: String): String {
        val items = JSONArray()
        section(prompt, "ÍTEMS (código").forEach { line ->
            val m = Regex("""^- (\w+): (.+?) \((palabra|verbo o adjetivo|gramática)\)""").find(line) ?: return@forEach
            val (code, label, kind) = m.destructured
            val own = Regex("""oración suya: «(.+?)»""").find(line)?.groupValues?.get(1)
            val ownBlank = Regex("""hueco: «(.+?)»""").find(line)?.groupValues?.get(1)
            val o = JSONObject().put("id", code)
            when {
                own != null -> {
                    o.put("oracion", own).put("hueco", ownBlank ?: own.split(' ').first())
                    o.put("traduccion", "Traducción fija de tu oración con [la parte que falta].")
                }
                kind == "verbo o adjetivo" -> {
                    val stem = Cloze.stem(label)
                    o.put("oracion", "주말에 친구하고 같이 ${stem}고 싶어요.").put("hueco", "${stem}고")
                    o.put("traduccion", "El fin de semana quiero [hacerlo] con un amigo.")
                    o.put("base", label).put("terminacion", "-고 싶다")
                }
                kind == "gramática" -> {
                    o.put("oracion", "오늘은 $label 연습을 해요.").put("hueco", label)
                    o.put("traduccion", "Esta oración practica [esta gramática].").put("base", "하다")
                }
                else -> {
                    o.put("oracion", "어제 $label 이야기를 했어요.").put("hueco", label)
                    o.put("traduccion", "Ayer hablamos de [eso].")
                }
            }
            items.put(o)
        }
        return JSONObject().put("items", items).toString()
    }

    private fun workbookHints(prompt: String): String {
        val focus = Regex("""GRAMÁTICA DE LA LECCIÓN \(la eligió el estudiante\): (.+)""").find(prompt)?.groupValues?.get(1)?.trim() ?: "-느라고"
        fun hint(f: String, t: String, u: String, k: String, en: String) = JSONObject().put("f", f).put("t", t).put("u", u).put("k", k).put("en", en)
        val exercises = JSONArray()
            .put(
                JSONObject().put("n", "1").put("e", "Completa la oración con la gramática de la lección.")
                    .put("p", JSONArray().put(hint("-는데", "c", "Para dar contexto antes.", "숙제를 하는데 친구가 왔어요.", "While I was doing homework, a friend came.")))
                    .put("r", "숙제를 하느라고 못 갔어요.")
            )
            .put(
                JSONObject().put("n", "2").put("e", "Responde la pregunta.")
                    .put("p", JSONArray().put(hint("주말", "n", "Para decir cuándo.", "주말에 청소하느라고 바빴어요.", "I was busy cleaning on the weekend.")))
                    .put("r", "주말에 이사하느라고 바빴어요.")
            )
        return JSONObject()
            .put("ejercicios", exercises)
            .put("vocab", JSONArray().put(JSONObject().put("f", "미용실").put("t", "n").put("s", "peluquería")).put(JSONObject().put("f", "이사").put("t", "n").put("s", "mudanza")))
            .put(
                "gram", JSONArray()
                    .put(JSONObject().put("f", focus).put("s", "gramática de la lección").put("foco", true).put("d", "titulo"))
                    .put(JSONObject().put("f", "-는데").put("s", "contexto, contraste").put("foco", false).put("d", "ejercicios"))
            )
            .put("unidad", 3)
            .toString()
    }

    private fun workbookReview(prompt: String): String {
        val numbers = section(prompt, "EJERCICIOS DE LA PÁGINA").mapNotNull { it.split(" | ").firstOrNull()?.trim()?.takeIf { n -> n.isNotEmpty() } }
        val rev = JSONArray()
        numbers.forEachIndexed { i, n ->
            val o = JSONObject().put("n", n).put("w", "숙제를 하느라고 못 갔어요.")
            when (i) {
                0 -> o.put("s", "fix").put("c", "숙제를 하느라고 못 갔어요.").put("nat", JSONObject.NULL).put("x", "Respuesta fija: corrección de prueba.")
                1 -> o.put("s", "nat").put("c", JSONObject.NULL).put("nat", "숙제하느라 못 갔어요.").put("x", "Respuesta fija: así suena más natural.")
                else -> o.put("s", "ok").put("c", JSONObject.NULL).put("nat", JSONObject.NULL).put("x", "Bien.")
            }
            rev.put(o)
        }
        return JSONObject()
            .put("rev", rev)
            .put("usados", JSONArray().put(JSONObject().put("f", "-느라고").put("t", "g").put("ok", true)))
            .toString()
    }
}
