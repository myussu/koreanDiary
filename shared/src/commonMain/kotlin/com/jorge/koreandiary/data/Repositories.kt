package com.jorge.koreandiary.data

import androidx.room3.withWriteTransaction
import com.jorge.koreandiary.ai.CorrectionResponse
import com.jorge.koreandiary.ai.ImportPayload
import com.jorge.koreandiary.ai.PromptItem
import com.jorge.koreandiary.ai.UsedItemDto
import com.jorge.koreandiary.ai.WorkbookGrammar
import com.jorge.koreandiary.workbook.LessonContext
import com.jorge.koreandiary.data.db.AppDatabase
import com.jorge.koreandiary.data.db.Correction
import com.jorge.koreandiary.data.db.CorrectionStatus
import com.jorge.koreandiary.data.db.DiaryEntry
import com.jorge.koreandiary.data.db.DiaryPage
import com.jorge.koreandiary.data.db.EventKind
import com.jorge.koreandiary.data.db.GrammarPoint
import com.jorge.koreandiary.data.db.ItemType
import com.jorge.koreandiary.data.db.PageInk
import com.jorge.koreandiary.data.db.PageKind
import com.jorge.koreandiary.data.db.MemoryState
import com.jorge.koreandiary.data.db.PageStatus
import com.jorge.koreandiary.data.db.PendingWord
import com.jorge.koreandiary.data.db.ReviewEvent
import com.jorge.koreandiary.data.db.Sentence
import com.jorge.koreandiary.data.db.Word
import com.jorge.koreandiary.data.standard.GrammarKeys
import com.jorge.koreandiary.data.standard.StandardCatalog
import com.jorge.koreandiary.data.standard.StdGrammar
import com.jorge.koreandiary.data.standard.StdWord
import com.jorge.koreandiary.progress.LevelCoverage
import com.jorge.koreandiary.progress.LevelEstimate
import com.jorge.koreandiary.review.Cloze
import com.jorge.koreandiary.review.ClozeBlank
import com.jorge.koreandiary.review.ClozeKind
import com.jorge.koreandiary.review.ClozeTarget
import com.jorge.koreandiary.review.QuickReviewPick
import com.jorge.koreandiary.srs.Fsrs
import com.jorge.koreandiary.conversation.TargetHelp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.random.Random

data class ImportSummary(val wordsAdded: Int, val grammarAdded: Int, val duplicates: Int)

data class ApplySummary(
    val corrections: Int,
    val reviewed: Int,
    val newWords: Int,
    val newGrammar: Int,
    val pendingWords: Int,
    val improvements: Int,
    /** Escenas: notas de sentido (lo escrito dice otra cosa que la idea). */
    val notes: Int = 0,
    /** Oraciones correctas con una versión más natural. */
    val naturals: Int = 0,
    /** Correcciones de errores que ya cometió en las últimas dos semanas. */
    val repeated: Int = 0,
)

/** Fuentes de un ítem en la base. */
object ItemSource {
    const val IMPORT = "import"
    /** Detectado en un diario (lo usaste sin tenerlo en tus listas). */
    const val DIARY = "diario"
    /** Sugerido como posible laguna del estándar y usado después. */
    const val GAP = "laguna"
    /** Del workbook: vocabulario impreso o gramática que elegiste agregar (aún no practicado). */
    const val WORKBOOK = "workbook"
    /** Del estándar, desde «Para agregar» («Agregar» o «Ya lo sé»). */
    const val STANDARD = "estandar"
    /** Una palabra que tocaste en una oración del repaso rápido y agregaste. */
    const val REVIEW = "repaso"
}

/** Cómo terminó «Corregir» una palabra en Estudio. */
enum class WordFix {
    RENAMED,
    /** Igual que antes: no había nada que cambiar. */
    UNCHANGED,
    /** La forma nueva ya está en tu lista: se quita esta en vez de juntarlas. */
    EXISTS,
    /** No es una palabra en coreano (vacía, con espacios o letras sueltas). */
    INVALID,
}

/** «Tu nivel»: cuánto cubres de cada nivel del estándar y la gramática que te falta. */
data class LevelReport(
    val grammar: List<LevelCoverage>,
    val words: List<LevelCoverage>,
    val missingGrammar: List<StdGrammar>,
)

/** Tus oraciones con un ítem (de las páginas donde lo usaste) y las correcciones que tuvo. */
data class ItemUsage(val sentences: List<String>, val corrections: List<String>)

data class UsageSummary(val reviewed: Int = 0, val newWords: Int = 0, val newGrammar: Int = 0, val pending: Int = 0)

/** Escuchar: cuántas preguntas volvieron al repaso y cuántas palabras quedaron en «Por revisar». */
data class ListenSummary(val again: Int = 0, val pending: Int = 0)

/**
 * Referencia a un ítem: "w12" (palabra), "g3" (gramática) en tu base;
 * "sv465" / "sg12" en el catálogo estándar (aún no están en tu base).
 */
data class ItemRef(val type: String, val id: Long) {
    val code: String
        get() = when (type) {
            ItemType.WORD -> "w$id"
            ItemType.GRAMMAR -> "g$id"
            ItemType.STD_WORD -> "sv$id"
            else -> "sg$id"
        }

    val isStandard get() = type == ItemType.STD_WORD || type == ItemType.STD_GRAMMAR

    companion object {
        fun parse(code: String): ItemRef? {
            val (type, digits) = when {
                code.startsWith("sv") -> ItemType.STD_WORD to code.drop(2)
                code.startsWith("sg") -> ItemType.STD_GRAMMAR to code.drop(2)
                code.startsWith("w") -> ItemType.WORD to code.drop(1)
                code.startsWith("g") -> ItemType.GRAMMAR to code.drop(1)
                else -> return null
            }
            return digits.toLongOrNull()?.let { ItemRef(type, it) }
        }

        /** "word:12,grammar:3,stdword:465" → lista de referencias. */
        fun decodeList(encoded: String): List<ItemRef> = encoded.split(',').mapNotNull { part ->
            val (type, id) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            id.toLongOrNull()?.let { ItemRef(type, it) }
        }
    }
}

class StudyRepository(private val db: AppDatabase, private val catalog: StandardCatalog) {
    private val study = db.studyDao()
    private val memory = db.memoryDao()

    val words = study.observeWords()
    val grammar = study.observeGrammar()
    /** ¿Hay vocabulario o gramática? El inicio solo necesita saber eso, no cargar las listas. */
    val hasItems = study.observeHasItems()
    val pendingWords = study.observePendingWords()
    val memoryStates = memory.observeAll()

    /** Texto para mostrar de cada ítem (palabra o patrón), por tipo e id. */
    suspend fun labels(states: List<MemoryState>): Map<Pair<String, Long>, String> {
        val result = mutableMapOf<Pair<String, Long>, String>()
        val wordIds = states.filter { it.itemType == ItemType.WORD }.map { it.itemId }
        val grammarIds = states.filter { it.itemType == ItemType.GRAMMAR }.map { it.itemId }
        if (wordIds.isNotEmpty()) study.wordsByIds(wordIds).forEach { w ->
            result[ItemType.WORD to w.id] = listOfNotNull(w.baseForm, w.meaning).joinToString(" · ")
        }
        if (grammarIds.isNotEmpty()) study.grammarByIds(grammarIds).forEach { g ->
            result[ItemType.GRAMMAR to g.id] = listOfNotNull(g.pattern, g.meaning).joinToString(" · ")
        }
        return result
    }

    /** En una sola transacción: una lista grande entra completa (y de una vez al disco) o no entra. */
    suspend fun import(payload: ImportPayload, source: String = ItemSource.IMPORT): ImportSummary = db.withWriteTransaction {
        var words = 0
        var grammar = 0
        var dupes = 0
        for (w in payload.words) {
            val base = w.baseForm.trim()
            if (base.isEmpty() || study.findWord(base) != null) { dupes++; continue }
            // Si no trae nivel, se toma del estándar.
            val level = w.level.takeUnless { it.isBlank() || it == "desconocido" }
                ?: standardWord(base)?.let { "${it.level}급" } ?: "desconocido"
            study.insertWord(Word(baseForm = base, type = w.type, meaning = w.meaning, level = level, irregular = w.irregular, source = source))
            words++
        }
        for (g in payload.grammar) {
            val pattern = g.pattern.trim()
            if (pattern.isEmpty() || study.findGrammar(pattern) != null) { dupes++; continue }
            study.insertGrammar(
                GrammarPoint(
                    unit = g.unit, level = g.level, pattern = pattern,
                    variants = g.variants.joinToString(" | "), appliesTo = g.appliesTo,
                    meaning = g.meaning, example = g.example, tags = g.tags.joinToString(","), source = source,
                )
            )
            grammar++
        }
        ImportSummary(words, grammar, dupes)
    }

    /**
     * Qué significa y para qué sirve cada ítem, con lo que ya está en tu base o en el catálogo
     * (mismo texto que las sugerencias del diario). Clave: el código del ítem ("w12", "sg3").
     */
    suspend fun details(refs: List<ItemRef>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        study.wordsByIds(refs.filter { it.type == ItemType.WORD }.map { it.id }).forEach {
            result["w${it.id}"] = listOfNotNull(it.type, it.meaning).joinToString(" · ")
        }
        study.grammarByIds(refs.filter { it.type == ItemType.GRAMMAR }.map { it.id }).forEach { g ->
            result["g${g.id}"] = listOfNotNull(g.meaning, g.example).joinToString(" — ").ifEmpty { "Gramática ${g.level}" }
        }
        withContext(Dispatchers.Default) {
            refs.forEach { ref ->
                when (ref.type) {
                    ItemType.STD_WORD -> catalog.wordById(ref.id.toInt())?.let {
                        result[ref.code] = "Posible laguna · nivel ${it.level} del estándar · ${it.type} · ej.: ${it.guide}"
                    }
                    ItemType.STD_GRAMMAR -> catalog.grammarById(ref.id.toInt())?.let {
                        val extra = listOf(it.category, it.meaning).filter { s -> s.isNotBlank() }.joinToString(" · ")
                        result[ref.code] = "Posible laguna · nivel ${it.level} del estándar · $extra"
                    }
                }
            }
        }
        return result
    }

    /** Ítems para el prompt de corrección, con su id para que la IA los nombre sin ambigüedad. */
    suspend fun promptItems(refs: List<ItemRef>): List<PromptItem> {
        val words = study.wordsByIds(refs.filter { it.type == ItemType.WORD }.map { it.id })
        val grammar = study.grammarByIds(refs.filter { it.type == ItemType.GRAMMAR }.map { it.id })
        val std = withContext(Dispatchers.Default) {
            refs.mapNotNull { ref ->
                when (ref.type) {
                    ItemType.STD_WORD -> catalog.wordById(ref.id.toInt())?.let { PromptItem(ref.code, it.word, it.type) }
                    ItemType.STD_GRAMMAR -> catalog.grammarById(ref.id.toInt())?.let { PromptItem(ref.code, it.form, "gramática") }
                    else -> null
                }
            }
        }
        return words.map { PromptItem("w${it.id}", it.baseForm, it.type) } +
            grammar.map { PromptItem("g${it.id}", it.pattern, "gramática") } + std
    }

    suspend fun approvePendingWord(p: PendingWord, now: Long): Unit = db.withWriteTransaction {
        if (study.findWord(p.baseForm) == null) {
            val id = study.insertWord(Word(baseForm = p.baseForm, type = p.type, meaning = p.meaning, source = ItemSource.DIARY))
            review(ItemRef(ItemType.WORD, id), Fsrs.Rating.GOOD, EventKind.DISCOVERED, now, p.pageId)
        }
        study.deletePendingWord(p.id)
    }

    suspend fun rejectPendingWord(p: PendingWord) = study.deletePendingWord(p.id)

    /**
     * Aplica la respuesta de la IA: guarda correcciones, mejoras y oraciones, y actualiza FSRS con
     * TODO lo usado. Lo que no está en tu base se busca en el catálogo estándar (para su nivel) y se agrega.
     * FSRS solo se actualiza en la primera revisión de la página, para no contar dos veces.
     *
     * Todo va en una transacción, junto con marcar la página como revisada: si la app se cierra a la
     * mitad no queda nada aplicado, y al reintentar no se cuenta dos veces.
     */
    suspend fun applyCorrection(page: DiaryPage, response: CorrectionResponse, now: Long): ApplySummary = db.withWriteTransaction {
        val diary = db.diaryDao()
        // La página se pudo borrar mientras la IA respondía (la revisión sigue aunque salgas): de una
        // página que ya no existe no se guarda nada. Antes quedaban correcciones y oraciones sueltas.
        if (diary.page(page.id) == null) return@withWriteTransaction ApplySummary(0, 0, 0, 0, 0, 0)
        // Además del estado se mira si la página ya dejó repasos: el estado viaja con cada guardado de
        // la tinta, y uno atrasado podría devolverla a "pendiente" con su uso ya contado.
        val firstReview = page.status != PageStatus.REVIEWED && memory.eventCountForPage(page.id) == 0

        diary.deleteCorrections(page.id)
        diary.deleteSentences(page.id)
        val tenseBySentence = response.sentences.associate { it.number to it.tense }
        val textBySentence = response.sentences.associate { it.number to it.text }
        response.sentences.forEach { diary.insertSentence(Sentence(pageId = page.id, number = it.number, text = it.text, tense = it.tense)) }
        response.corrections.forEach { c ->
            val ref = c.itemRef?.let { ItemRef.parse(it) }?.takeUnless { it.isStandard }
            diary.insertCorrection(
                Correction(
                    pageId = page.id, sentence = c.sentence, original = c.original, suggestion = c.suggestion,
                    type = c.type, explanation = repeatNote(c.repeats) + c.explanation, tense = tenseBySentence[c.sentence],
                    itemType = ref?.type, itemId = ref?.id,
                )
            )
        }
        response.naturals.forEach { n ->
            diary.insertCorrection(
                Correction(
                    pageId = page.id, sentence = n.sentence, original = n.original, suggestion = n.suggestion,
                    type = CorrectionType.NATURAL, explanation = n.explanation, tense = tenseBySentence[n.sentence],
                    itemType = null, itemId = null, status = CorrectionStatus.NATURAL,
                )
            )
        }
        response.improvements.forEach { m ->
            diary.insertCorrection(
                Correction(
                    pageId = page.id, sentence = m.sentence, original = textBySentence[m.sentence].orEmpty(),
                    suggestion = if (m.example.isBlank()) m.structure else "${m.structure} — ${m.example}",
                    type = CorrectionType.IMPROVEMENT, explanation = m.explanation, tense = tenseBySentence[m.sentence],
                    itemType = null, itemId = null, status = CorrectionStatus.SUGGESTION,
                )
            )
        }

        response.notes.forEach { n ->
            diary.insertCorrection(
                Correction(
                    pageId = page.id, sentence = n.sentence, original = n.original, suggestion = n.suggestion,
                    type = CorrectionType.MEANING, explanation = n.explanation, tense = tenseBySentence[n.sentence],
                    itemType = null, itemId = null, status = CorrectionStatus.NOTE,
                )
            )
        }

        val suggested = ItemRef.decodeList(page.suggestedItems).toSet()
        // Al revisar otra vez (sigues escribiendo después de revisar) solo cuenta lo que esta página
        // no calificó todavía: lo nuevo que escribiste, p. ej. una de las gramáticas nuevas de la escena.
        val usage = if (firstReview) {
            registerUsage(response.used, suggested, now, page.id)
        } else {
            val already = memory.ratedOnPage(page.id).map { ItemRef(it.itemType, it.itemId) }.toSet()
            registerUsage(response.used, suggested, now, page.id, alreadyCounted = already, recordUnused = false)
        }
        diary.setPageStatus(page.id, PageStatus.REVIEWED)
        ApplySummary(
            response.corrections.size, usage.reviewed, usage.newWords, usage.newGrammar, usage.pending,
            response.improvements.size, response.notes.size,
            naturals = response.naturals.size,
            repeated = response.corrections.count { it.repeats >= 2 },
        )
    }

    /**
     * Las correcciones de las últimas dos semanas, sin las de [exceptPage], para que la IA avise si un
     * error se repite: «학교에 → 학교에서 (particula)». Solo errores (sin mejoras, notas ni versiones
     * más naturales), las más recientes primero.
     */
    suspend fun recentErrors(now: Long, exceptPage: Long, limit: Int = 20): List<String> =
        db.diaryDao().recentErrors(now - RECENT_ERRORS_MS, exceptPage, limit)
            .map { "${it.original.trim()} → ${it.suggestion.trim()} (${it.type})" }
            .distinct()


    /**
     * Marca, sin IA, qué estructuras y palabras de una respuesta ya están en tus listas.
     * Las que no, se buscan en el estándar para mostrar su nivel.
     */
    suspend fun annotate(grammar: List<String>, words: List<Pair<String, String>>): List<AnnotatedItem> {
        val myGrammar = if (grammar.isEmpty()) emptyList() else study.allGrammar()
        val out = mutableListOf<AnnotatedItem>()
        for (g in grammar.distinct()) {
            val mine = withContext(Dispatchers.Default) {
                myGrammar.firstOrNull { GrammarKeys.matches(listOf(it.pattern) + it.variants.split(" | "), listOf(g)) }
            }
            out += if (mine != null) {
                AnnotatedItem(g, known = true, level = null, ref = ItemRef(ItemType.GRAMMAR, mine.id), isGrammar = true, meaning = mine.meaning)
            } else {
                val std = standardGrammar(g)
                AnnotatedItem(g, known = false, level = std?.level, ref = std?.let { ItemRef(ItemType.STD_GRAMMAR, it.id.toLong()) }, isGrammar = true, meaning = std?.meaning)
            }
        }
        for ((form, meaning) in words.distinctBy { it.first }) {
            val mine = study.findWord(form)
            out += if (mine != null) {
                AnnotatedItem(form, known = true, level = null, ref = ItemRef(ItemType.WORD, mine.id), isGrammar = false, meaning = meaning)
            } else {
                val std = standardWord(form)
                AnnotatedItem(form, known = false, level = std?.level, ref = std?.let { ItemRef(ItemType.STD_WORD, it.id.toLong()) }, isGrammar = false, meaning = meaning)
            }
        }
        return out
    }

    /**
     * Actualiza FSRS con lo que el estudiante usó (diario o workbook). Lo que no está en su base se crea.
     * [suggested]: lo que vio sugerido (cuenta como "Hard" si lo usa); lo sugerido y no usado se registra sin calificación.
     */
    suspend fun applyUsage(used: List<UsedItemDto>, suggested: Set<ItemRef>, now: Long, pageId: Long?): UsageSummary =
        db.withWriteTransaction { registerUsage(used, suggested, now, pageId) }

    /**
     * La conversación cuenta para el repaso, pero solo con los objetivos de la sesión ([targets]):
     * usado sin ayuda = bien; usado con ayuda ([helped]: salió en una pista, en «¿Cómo digo?» o lo
     * miraste) = difícil; usado mal o corregido ([corrected]) = otra vez. Lo que no usaste no se
     * califica. Un objetivo del catálogo que usaste entra a tu lista, como en el diario.
     * [used] y [corrected] vienen de la revisión de la conversación (ids de los objetivos).
     */
    suspend fun applyConversation(
        targets: List<PromptItem>,
        used: List<UsedItemDto>,
        corrected: Set<String>,
        helped: Set<String>,
        now: Long,
    ): UsageSummary = db.withWriteTransaction {
        val byRef = targets.associateBy { it.ref }
        val fromUsed = used.filter { it.itemRef in byRef }
            .map { u -> if (u.itemRef in corrected) u.copy(correct = false) else u }
        // Corregido aunque la IA no lo haya puesto en "usados": igual lo intentó y falló.
        val onlyCorrected = (corrected - fromUsed.mapNotNull { it.itemRef }.toSet()).mapNotNull { ref ->
            byRef[ref]?.let { t -> UsedItemDto(itemRef = t.ref, form = t.label, kind = if (t.kind == "gramática") "gramatica" else t.kind, correct = false) }
        }
        val helpedRefs = helped.mapNotNull { ItemRef.parse(it) }.toSet()
        registerUsage((fromUsed + onlyCorrected).distinctBy { it.itemRef }, helpedRefs, now, pageId = null, recordUnused = false)
    }

    /**
     * Escuchar: lo que oyes no se califica (oír no prueba que lo recuerdes), pero lo que preguntaste en
     * una pausa ([asked]) sí dice que no lo sabías. Si está en tu lista vuelve pronto al repaso (otra
     * vez); si es una palabra que no está, queda en «Por revisar» para que decidas.
     */
    suspend fun applyListenQuestions(asked: List<UsedItemDto>, now: Long): ListenSummary = db.withWriteTransaction {
        var again = 0
        var pending = 0
        val myGrammar = study.allGrammar()
        for (a in asked.distinctBy { it.itemRef ?: it.form.trim() }) {
            val form = a.form.trim()
            if (form.isEmpty()) continue
            // Un id que la IA devuelve y no está en tu lista no se califica: se busca por su forma.
            val explicit = a.itemRef?.let { ItemRef.parse(it) }?.takeUnless { it.isStandard }?.takeIf { inMyList(it) }
            val ref = explicit ?: if (a.isGrammar) {
                findMine(form, myGrammar)?.let { ItemRef(ItemType.GRAMMAR, it.id) }
            } else {
                study.findWord(form)?.let { ItemRef(ItemType.WORD, it.id) }
            }
            if (ref != null) {
                review(ref, Fsrs.Rating.AGAIN, EventKind.ASKED, now, pageId = null)
                again++
            } else if (!a.isGrammar && study.findPendingWord(form) == null && form.all { it in '가'..'힣' }) {
                study.insertPendingWord(PendingWord(baseForm = form, type = a.kind, meaning = a.meaning, pageId = null, createdAt = now))
                pending++
            }
        }
        ListenSummary(again, pending)
    }

    /** ¿El ítem (una palabra o una gramática tuya) sigue en tu lista? */
    private suspend fun inMyList(ref: ItemRef): Boolean = when (ref.type) {
        ItemType.WORD -> study.wordsByIds(listOf(ref.id)).isNotEmpty()
        else -> study.grammarByIds(listOf(ref.id)).isNotEmpty()
    }

    /**
     * Lo que tiene prioridad entre los candidatos (de «Actualizar» y de los objetivos de la
     * conversación), sin forzarlo: la gramática de las lecciones recientes del workbook
     * ([lessonForms]) y lo que fallaste en las últimas dos semanas. Lo de la lección que no está en
     * tu lista va como ítem del catálogo.
     */
    suspend fun priorityItems(now: Long, lessonForms: List<String>, limit: Int = 6): List<ItemRef> {
        val myGrammar = study.allGrammar()
        val lesson = lessonForms.map { it.trim() }.filter { it.isNotEmpty() }.distinct().mapNotNull { form ->
            findMine(form, myGrammar)?.let { ItemRef(ItemType.GRAMMAR, it.id) }
                ?: standardGrammar(form)?.let { ItemRef(ItemType.STD_GRAMMAR, it.id.toLong()) }
        }
        val failed = memory.failedSince(now - RECENT_ERRORS_MS, limit).map { ItemRef(it.itemType, it.itemId) }
        return (lesson + failed).distinct().take(limit)
    }

    /**
     * «Tu nivel»: cobertura de cada nivel del estándar en gramática y vocabulario, y la gramática que
     * te falta hasta [maxLevel]. «Usado bien» = al menos un acierto en el repaso.
     */
    suspend fun levelReport(maxLevel: Int): LevelReport {
        val grammar = study.allGrammar()
        val words = study.allWords()
        val wellGrammar = memory.allOfType(ItemType.GRAMMAR).filter { it.reps > it.lapses }.map { it.itemId }.toSet()
        val wellWords = memory.allOfType(ItemType.WORD).filter { it.reps > it.lapses }.map { it.itemId }.toSet()
        return withContext(Dispatchers.Default) {
            LevelReport(
                LevelEstimate.grammarCoverage(catalog.grammar, grammar, wellGrammar),
                LevelEstimate.wordCoverage(catalog.words, words, wellWords),
                LevelEstimate.missingGrammar(catalog.grammar, grammar, maxLevel.coerceIn(1, 6)),
            )
        }
    }

    /** La muestra de la prueba corta de vocabulario (palabras que no están en tu lista). */
    suspend fun vocabSample(): Map<Int, List<StdWord>> {
        val mine = study.allWordForms().toSet()
        return withContext(Dispatchers.Default) { LevelEstimate.sample(catalog.words, mine) }
    }

    /** Las palabras del estándar con esas formas, sin las que ya están en tu lista (para «Para agregar»). */
    suspend fun missingWords(forms: List<String>): List<StdWord> {
        val mine = study.allWordForms().toSet()
        return withContext(Dispatchers.Default) {
            forms.distinct().filter { it !in mine }.mapNotNull { catalog.word(it) }.sortedWith(compareBy({ it.level }, { it.id }))
        }
    }

    /**
     * «Para agregar»: suma un ítem del estándar a tu lista. Con [known] («Ya lo sé») cuenta como
     * «fácil» y vuelve mucho después; sin él es nuevo y la app te lo va a sugerir. Si ya estaba (o
     * una tuya comparte la clave), usa esa.
     */
    suspend fun addStandard(ref: ItemRef, known: Boolean, now: Long): ItemRef? = db.withWriteTransaction {
        val added: ItemRef? = when (ref.type) {
            ItemType.STD_GRAMMAR -> standardGrammarById(ref.id)?.let { std ->
                findMine(std.form, study.allGrammar())?.let { ItemRef(ItemType.GRAMMAR, it.id) }
                    ?: ItemRef(
                        ItemType.GRAMMAR,
                        study.insertGrammar(
                            GrammarPoint(
                                unit = null, level = "${std.level}급", pattern = std.form,
                                variants = (listOf(std.form) + std.related).distinct().joinToString(" | "),
                                appliesTo = "", meaning = std.meaning.takeIf { it.isNotBlank() }, source = ItemSource.STANDARD,
                            )
                        ),
                    )
            }
            ItemType.STD_WORD -> standardWordById(ref.id)?.let { std ->
                study.findWord(std.word)?.let { ItemRef(ItemType.WORD, it.id) }
                    ?: ItemRef(
                        ItemType.WORD,
                        study.insertWord(Word(baseForm = std.word, type = std.type, level = "${std.level}급", source = ItemSource.STANDARD)),
                    )
            }
            else -> ref
        }
        if (added != null && known) review(added, Fsrs.Rating.EASY, EventKind.DECLARED, now, pageId = null)
        added
    }

    /**
     * «Corregir» en Estudio: una palabra que quedó mal escrita (como «이난», que entró así del diario)
     * pasa a [newForm] con su historial, porque lo que usaste era la palabra que querías. El tipo y el
     * nivel salen del estándar si la tiene. Si [newForm] ya está en tu lista no se juntan: [WordFix.EXISTS],
     * para que quites esta.
     */
    suspend fun correctWord(id: Long, newForm: String): WordFix = db.withWriteTransaction {
        val form = newForm.trim()
        val word = study.wordsByIds(listOf(id)).firstOrNull()
        when {
            word == null || !looksLikeKoreanWord(form) -> WordFix.INVALID
            form == word.baseForm -> WordFix.UNCHANGED
            study.findWord(form) != null -> WordFix.EXISTS
            else -> {
                val std = standardWord(form)
                study.updateWord(
                    word.copy(
                        baseForm = form,
                        type = std?.type?.takeIf { word.type !in WORD_KINDS } ?: word.type,
                        level = std?.let { "${it.level}급" } ?: "desconocido",
                        irregular = null,
                    )
                )
                WordFix.RENAMED
            }
        }
    }

    /**
     * «Quitar de mi lista»: la palabra, su memoria y su historial de repaso. Las correcciones que la
     * nombraban se quedan, sin enlace a ella.
     */
    suspend fun removeWord(id: Long): Unit = db.withWriteTransaction {
        memory.deleteState(ItemType.WORD, id)
        memory.deleteEvents(ItemType.WORD, id)
        db.diaryDao().unlinkCorrections(ItemType.WORD, id)
        study.deleteWord(id)
    }

    /** ¿Esta palabra (forma de diccionario) ya está en tu lista? */
    suspend fun hasWord(form: String): Boolean = study.findWord(form.trim()) != null

    /**
     * Una palabra que tocaste en el repaso rápido y no conocías: entra a tu lista como nueva, sin
     * contar como repaso, así la app te la sugiere y la trae al repaso como algo que nunca usaste. El
     * tipo y el nivel salen del estándar si la tiene; si no, del tipo que dijo la IA.
     */
    suspend fun addLookedUpWord(form: String, type: String?, meaning: String?): ItemRef = db.withWriteTransaction {
        val base = form.trim()
        val id = study.findWord(base)?.id ?: run {
            val std = standardWord(base)
            val kind = type?.trim()?.lowercase()?.takeIf { it in WORD_KINDS } ?: std?.type ?: "otro"
            study.insertWord(
                Word(baseForm = base, type = kind, meaning = meaning?.trim(), level = std?.let { "${it.level}급" } ?: "desconocido", source = ItemSource.REVIEW)
            )
        }
        ItemRef(ItemType.WORD, id)
    }

    /**
     * Tus oraciones con un ítem, de las páginas (diario y escenas) donde lo usaste: las que lo traen
     * (la raíz de la palabra o la gramática) y, si ninguna lo muestra tal cual, las primeras de esas
     * páginas. Y las correcciones que tuvo («원래 → 수정»).
     */
    suspend fun itemUsage(ref: ItemRef, label: String, limit: Int = 3): ItemUsage {
        val diary = db.diaryDao()
        val pages = memory.pagesUsing(ref.type, ref.id, 6)
        val sentences = if (pages.isEmpty()) emptyList() else diary.sentencesOf(pages).map { it.text.trim() }.filter { it.isNotEmpty() }
        val keys = if (ref.type == ItemType.GRAMMAR) GrammarKeys.keys(label).map { it.substringAfter(':') } else emptyList()
        val matching = sentences.filter { s ->
            if (ref.type == ItemType.GRAMMAR) keys.any { k -> k.length >= 2 && s.replace(" ", "").contains(k) } else TargetHelp.stemIn(label, s)
        }
        val corrections = diary.correctionsForItem(ref.type, ref.id, limit).map { "${it.original.trim()} → ${it.suggestion.trim()}" }
        return ItemUsage((matching.ifEmpty { sentences }).distinct().take(limit), corrections)
    }

    /**
     * Repaso rápido: hasta [limit] ítems de tu lista para completar en una oración. Primero lo que te
     * toca repasar (lo que menos recuerdas) y unos pocos que nunca usaste (gramática de tus niveles y
     * palabras). Cada uno trae, si la hay, una oración tuya ya corregida donde aparece; si no, la IA
     * escribe una. No entra lo que repasaste en las últimas 12 horas.
     */
    suspend fun quickReviewTargets(now: Long, grammarLevels: List<String>, limit: Int = 10, random: Random = Random.Default): List<ClozeTarget> {
        val wordStates = memory.allOfType(ItemType.WORD)
        val grammarStates = memory.allOfType(ItemType.GRAMMAR)
        val recent = now - 12 * 60 * 60 * 1000L
        fun recall(s: MemoryState) = Fsrs.retrievabilityAt(Fsrs.Memory(s.stability, s.difficulty, s.lastReviewAt, s.reps, s.lapses), now)
        val due = (wordStates + grammarStates)
            .filter { it.dueAt <= now && it.lastReviewAt < recent }
            .sortedBy { recall(it) }
            .map { ItemRef(it.itemType, it.itemId) }
        val seenWords = wordStates.map { it.itemId }.toSet()
        val seenGrammar = grammarStates.map { it.itemId }.toSet()
        val unseenGrammar = study.grammarIdsForLevels(grammarLevels).filter { it !in seenGrammar }.shuffled(random).map { ItemRef(ItemType.GRAMMAR, it) }
        val unseenWords = study.allWordIds().filter { it !in seenWords }.shuffled(random).map { ItemRef(ItemType.WORD, it) }
        // Lo nuevo, mezclado: una gramática y dos palabras.
        val unseen = buildList {
            val g = ArrayDeque(unseenGrammar)
            val w = ArrayDeque(unseenWords)
            while (g.isNotEmpty() || w.isNotEmpty()) {
                g.removeFirstOrNull()?.let { add(it) }
                repeat(2) { w.removeFirstOrNull()?.let { add(it) } }
            }
        }
        val picked = QuickReviewPick.pick(due, unseen, limit)
        val words = study.wordsByIds(picked.filter { it.type == ItemType.WORD }.map { it.id }).associateBy { it.id }
        val grammar = study.grammarByIds(picked.filter { it.type == ItemType.GRAMMAR }.map { it.id }).associateBy { it.id }
        return picked.mapNotNull { ref ->
            val target = when (ref.type) {
                ItemType.WORD -> words[ref.id]?.let { w ->
                    val kind = if (w.type == "verbo" || w.type == "adjetivo") ClozeKind.VERB else ClozeKind.WORD
                    ClozeTarget(ref.code, w.baseForm, kind, w.meaning)
                }
                else -> grammar[ref.id]?.let { g -> ClozeTarget(ref.code, g.pattern, ClozeKind.GRAMMAR, g.meaning) }
            } ?: return@mapNotNull null
            val own = ownSentence(ref, target.label, target.kind)
            if (own == null) target else target.copy(sentence = own.first, blank = own.second)
        }
    }

    /**
     * Una oración tuya donde está el ítem, con sus correcciones aplicadas (la versión buena), de la
     * página más reciente donde lo usaste. Null si no hay o si el celular no encuentra dónde está.
     */
    private suspend fun ownSentence(ref: ItemRef, label: String, kind: ClozeKind): Pair<String, ClozeBlank>? {
        val pages = memory.pagesUsing(ref.type, ref.id, 6)
        if (pages.isEmpty()) return null
        val diary = db.diaryDao()
        val sentences = diary.sentencesOf(pages).groupBy { it.pageId }
        for (page in pages) {
            val fixes = diary.corrections(page).filter { it.status == CorrectionStatus.PENDING || it.status == CorrectionStatus.REWRITTEN }
            for (s in sentences[page].orEmpty()) {
                val text = Cloze.applyCorrections(s.text.trim(), fixes.filter { it.sentence == s.number }.map { it.original to it.suggestion })
                val blank = Cloze.blankIn(text, label, kind) ?: continue
                return text to blank
            }
        }
        return null
    }

    /** El resultado de una tarjeta del repaso rápido, al momento: si sales a la mitad, lo hecho ya cuenta. */
    suspend fun applyQuickReview(code: String, rating: Fsrs.Rating, now: Long): Unit = db.withWriteTransaction {
        val ref = ItemRef.parse(code)
        if (ref != null && !ref.isStandard) review(ref, rating, EventKind.QUICK_REVIEW, now, pageId = null)
    }

    /**
     * El trabajo de [applyUsage]. Quien llama ya abrió la transacción. [alreadyCounted]: lo que esta
     * página ya calificó (no se califica dos veces). Con [recordUnused] se anota lo sugerido que no usaste.
     */
    private suspend fun registerUsage(
        used: List<UsedItemDto>,
        suggested: Set<ItemRef>,
        now: Long,
        pageId: Long?,
        alreadyCounted: Set<ItemRef> = emptySet(),
        recordUnused: Boolean = true,
    ): UsageSummary {
        var reviewed = 0
        var newWords = 0
        var newGrammar = 0
        var pending = 0
        val myGrammar = study.allGrammar().toMutableList()
        val counted = mutableSetOf<ItemRef>()
        for (u in used) {
            val resolved = resolve(u, suggested, myGrammar, pageId, now, onPending = { pending++ }) ?: continue
            if (resolved.ref in alreadyCounted || !counted.add(resolved.ref)) continue
            // Sin id de la IA (p. ej. en el workbook) se compara el ítem encontrado con lo que viste.
            val wasSuggested = resolved.wasSuggested || resolved.ref in suggested
            val rating = when {
                !u.correct -> Fsrs.Rating.AGAIN
                // Usarlo tras verlo sugerido es reconocimiento, no recuerdo libre: cuenta como "Hard".
                wasSuggested -> Fsrs.Rating.HARD
                else -> Fsrs.Rating.GOOD
            }
            val kind = when {
                resolved.isNew -> EventKind.DISCOVERED
                !u.correct -> EventKind.INCORRECT
                wasSuggested -> EventKind.CORRECT_SUGGESTED
                else -> EventKind.CORRECT_SPONTANEOUS
            }
            review(resolved.ref, rating, kind, now, pageId)
            reviewed++
            if (resolved.isNew) {
                if (resolved.ref.type == ItemType.WORD) newWords++ else newGrammar++
            }
        }
        // Sugerido y no usado: se registra sin calificación. No es un fallo.
        if (recordUnused) {
            (suggested.filterNot { it.isStandard }.toSet() - counted).forEach { ref ->
                memory.insertEvent(ReviewEvent(itemType = ref.type, itemId = ref.id, at = now, kind = EventKind.SUGGESTED_UNUSED, rating = null, pageId = pageId))
            }
        }
        return UsageSummary(reviewed, newWords, newGrammar, pending)
    }

    /**
     * Palabras impresas en una página del workbook: se agregan a la base como "vistas" (con su nivel del
     * estándar) pero SIN contar como repaso. Cuentan para FSRS solo cuando el estudiante las usa.
     */
    suspend fun registerSeenWords(vocab: List<UsedItemDto>): Int {
        var added = 0
        for (v in vocab.distinctBy { it.form.trim() }) {
            val base = v.form.trim()
            if (base.isEmpty() || v.isGrammar || study.findWord(base) != null) continue
            val std = standardWord(base)
            if (std == null && !looksLikeKoreanWord(base)) continue
            val type = if (v.kind in WORD_KINDS) v.kind else std?.type ?: "otro"
            study.insertWord(
                Word(
                    baseForm = base, type = type, meaning = v.meaning,
                    level = std?.let { "${it.level}급" } ?: "desconocido",
                    source = ItemSource.WORKBOOK,
                )
            )
            added++
        }
        return added
    }

    /** ¿Ya está en tu lista? Mismo criterio que al registrar uso: tu forma, luego la del estándar y sus relacionadas. */
    private suspend fun findMine(form: String, myGrammar: List<GrammarPoint>): GrammarPoint? = withContext(Dispatchers.Default) {
        fun match(forms: List<String>) = myGrammar.firstOrNull { g -> GrammarKeys.matches(listOf(g.pattern) + g.variants.split(" | "), forms) }
        match(listOf(form)) ?: catalog.grammar(listOf(form))?.let { std -> match(listOf(std.form) + std.related) }
    }

    /** De estas formas, cuáles ya están en tu lista de gramática. */
    suspend fun knownGrammar(forms: List<String>): Set<String> {
        if (forms.isEmpty()) return emptySet()
        val mine = study.allGrammar()
        return forms.filter { findMine(it, mine) != null }.toSet()
    }

    /**
     * Gramática que elegiste de una página del workbook (p. ej. la que enseña la lección).
     * Entra a tu lista sin contar como repaso, para que el diario te la sugiera. Devuelve lo agregado por forma de la página.
     */
    suspend fun registerWorkbookGrammar(items: List<WorkbookGrammar>, unit: Int?): Map<String, GrammarPoint> {
        val mine = study.allGrammar().toMutableList()
        val added = linkedMapOf<String, GrammarPoint>()
        for (g in items.distinctBy { it.form.trim() }) {
            val form = g.form.trim()
            if (form.isEmpty() || findMine(form, mine) != null) continue
            val std = standardGrammar(form)
            if (std == null && !looksLikeGrammar(form)) continue
            val pattern = std?.form ?: form
            if (study.findGrammar(pattern) != null) continue
            val point = GrammarPoint(
                unit = unit,
                level = std?.let { "${it.level}급" } ?: "?",
                pattern = pattern,
                // Se guarda también la forma del libro, para reconocerla igual que aparece ahí.
                variants = (listOf(pattern, form) + (std?.related ?: emptyList())).distinct().joinToString(" | "),
                appliesTo = "",
                meaning = g.meaning ?: std?.meaning?.takeIf { it.isNotBlank() },
                source = ItemSource.WORKBOOK,
            )
            val stored = point.copy(id = study.insertGrammar(point))
            mine += stored
            added[g.form] = stored
        }
        return added
    }

    suspend fun allGrammarPoints(): List<GrammarPoint> = study.allGrammar()

    /**
     * Gramáticas que ya estudiaste (las de tus niveles, las detectadas y las que practicaste),
     * para que la IA proponga combinaciones con ellas. Ver [LessonContext.previousGrammar].
     */
    suspend fun previousGrammar(levels: List<String>, focus: String?, now: Long): List<String> {
        val poolIds = study.grammarIdsForLevels(levels).toSet()
        val states = memory.allOfType(ItemType.GRAMMAR).associateBy { it.itemId }
        val pool = study.allGrammar().filter { it.id in poolIds || it.id in states }
        return withContext(Dispatchers.Default) { LessonContext.previousGrammar(pool, states, focus, now) }
    }

    private data class Resolved(val ref: ItemRef, val isNew: Boolean, val wasSuggested: Boolean)

    /** Encuentra o crea el ítem usado. Devuelve null si quedó en "Por revisar". */
    private suspend fun resolve(
        u: UsedItemDto,
        suggested: Set<ItemRef>,
        myGrammar: MutableList<GrammarPoint>,
        pageId: Long?,
        now: Long,
        onPending: () -> Unit,
    ): Resolved? {
        val explicit = u.itemRef?.let { ItemRef.parse(it) }
        // 1. La IA lo identificó con un id de la lista.
        if (explicit != null && !explicit.isStandard) {
            val exists = when (explicit.type) {
                ItemType.WORD -> study.wordsByIds(listOf(explicit.id)).isNotEmpty()
                else -> study.grammarByIds(listOf(explicit.id)).isNotEmpty()
            }
            if (exists) return Resolved(explicit, isNew = false, wasSuggested = explicit in suggested)
        }
        val stdWord = if (explicit?.type == ItemType.STD_WORD) standardWordById(explicit.id) else null
        val stdGrammar = if (explicit?.type == ItemType.STD_GRAMMAR) standardGrammarById(explicit.id) else null
        val wasSuggested = explicit != null && explicit in suggested

        // 2. Gramática: tu base primero (por claves), luego el estándar.
        if (stdGrammar != null || (stdWord == null && u.isGrammar)) {
            val forms = listOfNotNull(stdGrammar?.form, u.form.takeIf { stdGrammar == null }) + (stdGrammar?.related ?: emptyList())
            myGrammar.firstOrNull { g -> GrammarKeys.matches(listOf(g.pattern) + g.variants.split(" | "), forms) }?.let {
                return Resolved(ItemRef(ItemType.GRAMMAR, it.id), isNew = false, wasSuggested = wasSuggested)
            }
            val std = stdGrammar ?: standardGrammar(u.form)
            if (std != null) {
                myGrammar.firstOrNull { g -> GrammarKeys.matches(listOf(g.pattern) + g.variants.split(" | "), listOf(std.form) + std.related) }?.let {
                    return Resolved(ItemRef(ItemType.GRAMMAR, it.id), isNew = false, wasSuggested = wasSuggested)
                }
            }
            if (std == null && !looksLikeGrammar(u.form)) return null
            val point = GrammarPoint(
                unit = null,
                level = std?.let { "${it.level}급" } ?: "?",
                pattern = std?.form ?: u.form.trim(),
                variants = (listOfNotNull(std?.form ?: u.form.trim()) + (std?.related ?: emptyList())).joinToString(" | "),
                appliesTo = "",
                meaning = u.meaning ?: std?.meaning?.takeIf { it.isNotBlank() },
                source = if (wasSuggested) ItemSource.GAP else ItemSource.DIARY,
            )
            study.findGrammar(point.pattern)?.let { return Resolved(ItemRef(ItemType.GRAMMAR, it.id), false, wasSuggested) }
            val id = study.insertGrammar(point)
            myGrammar += point.copy(id = id)
            return Resolved(ItemRef(ItemType.GRAMMAR, id), isNew = true, wasSuggested = wasSuggested)
        }

        // 3. Palabra: tu base, luego el estándar (para el nivel), luego validación simple.
        val base = (stdWord?.word ?: u.form).trim()
        study.findWord(base)?.let { return Resolved(ItemRef(ItemType.WORD, it.id), isNew = false, wasSuggested = wasSuggested) }
        val std = stdWord ?: standardWord(base)
        // Fuera del estándar, lo que no parece palabra o lo que usaste mal va a «Por revisar»: puede
        // ser una palabra mal escrita o mal leída (así entró «이난», pedido por Jorge el 2026-11-21).
        if (std == null && (!looksLikeKoreanWord(base) || !u.correct)) {
            if (study.findPendingWord(base) == null) {
                study.insertPendingWord(PendingWord(baseForm = base, type = u.kind, meaning = u.meaning, pageId = pageId, createdAt = now))
                onPending()
            }
            return null
        }
        val type = if (u.kind in WORD_KINDS) u.kind else std?.type ?: "otro"
        val id = study.insertWord(
            Word(
                baseForm = base, type = type, meaning = u.meaning,
                level = std?.let { "${it.level}급" } ?: "desconocido",
                source = if (wasSuggested) ItemSource.GAP else ItemSource.DIARY,
            )
        )
        return Resolved(ItemRef(ItemType.WORD, id), isNew = true, wasSuggested = wasSuggested)
    }

    private suspend fun standardWord(form: String): StdWord? = withContext(Dispatchers.Default) { catalog.word(form) }
    private suspend fun standardWordById(id: Long): StdWord? = withContext(Dispatchers.Default) { catalog.wordById(id.toInt()) }
    private suspend fun standardGrammar(form: String): StdGrammar? = withContext(Dispatchers.Default) { catalog.grammar(listOf(form)) }
    private suspend fun standardGrammarById(id: Long): StdGrammar? = withContext(Dispatchers.Default) { catalog.grammarById(id.toInt()) }

    private suspend fun review(ref: ItemRef, rating: Fsrs.Rating, kind: String, now: Long, pageId: Long?) {
        val stored = memory.get(ref.type, ref.id)
        val prev = stored?.let { Fsrs.Memory(it.stability, it.difficulty, it.lastReviewAt, it.reps, it.lapses) }
        val result = Fsrs.review(prev, rating, now)
        memory.upsert(
            MemoryState(
                itemType = ref.type, itemId = ref.id,
                stability = result.memory.stability, difficulty = result.memory.difficulty,
                lastReviewAt = now, dueAt = result.dueAt,
                reps = result.memory.reps, lapses = result.memory.lapses, sentToAnki = stored?.sentToAnki ?: false,
            )
        )
        memory.insertEvent(ReviewEvent(itemType = ref.type, itemId = ref.id, at = now, kind = kind, rating = rating.value, pageId = pageId))
    }

    /**
     * Validación de palabras que no están en el estándar: solo sílabas hangul y largo razonable.
     * TODO: validar contra la API abierta del 한국어기초사전 (requiere clave gratuita).
     */
    private fun looksLikeKoreanWord(s: String): Boolean =
        s.length in 1..12 && s.all { it in '가'..'힣' }

    /** Gramática fuera del estándar: debe tener hangul y no ser una frase entera. */
    private fun looksLikeGrammar(s: String): Boolean {
        val t = s.trim()
        return t.length in 1..30 && t.any { it in '가'..'힣' || it in 'ㄱ'..'ㆎ' }
    }

    companion object {
        private val WORD_KINDS = setOf("sustantivo", "verbo", "adjetivo", "adverbio")

        /** Dos semanas: lo que la revisión mira para avisar que un error se repite. */
        const val RECENT_ERRORS_MS = 14L * 24 * 60 * 60 * 1000

        /** «Tercera vez en dos semanas. »: va delante de la explicación de un error que se repite. */
        fun repeatNote(repeats: Int): String = if (repeats >= 2) "${ordinal(repeats)} vez en dos semanas. " else ""

        private fun ordinal(n: Int): String = when (n) {
            2 -> "Segunda"
            3 -> "Tercera"
            4 -> "Cuarta"
            5 -> "Quinta"
            else -> "La $n.ª"
        }
    }
}

/** Una estructura o palabra de "¿Cómo digo esto?", marcada según tus listas. */
data class AnnotatedItem(
    val label: String,
    /** Ya está en tu vocabulario o gramática. */
    val known: Boolean,
    /** Nivel 1–6 del estándar, si no está en tus listas pero sí en el estándar. */
    val level: Int?,
    val ref: ItemRef?,
    val isGrammar: Boolean,
    val meaning: String?,
)

object CorrectionType {
    /** Sugerencia de estructura de su nivel; no exige reescritura. */
    const val IMPROVEMENT = "mejora"
    /** Escenas: lo escrito dice otra cosa que lo que dijiste que querías decir. */
    const val MEANING = "sentido"
    /** Correcto pero más natural (no es un error). */
    const val NATURAL = "natural"

    /**
     * El tipo para mostrar. La IA devuelve claves sin tildes («conjugacion») a propósito, para que el
     * JSON sea estable; en pantalla van con tilde (pruebas del 2026-11-15). Lo desconocido, tal cual.
     */
    fun label(type: String): String = when (type.trim().lowercase()) {
        "ortografia" -> "ortografía"
        "particula" -> "partícula"
        "conjugacion" -> "conjugación"
        "gramatica" -> "gramática"
        "naturalidad" -> "naturalidad"
        "vocabulario" -> "vocabulario"
        "tiempo" -> "tiempo verbal"
        IMPROVEMENT -> "mejora"
        MEANING -> "sentido"
        NATURAL -> "más natural"
        else -> type
    }
}

class DiaryRepository(private val db: AppDatabase) {
    private val diary = db.diaryDao()

    private val allSummaries = diary.observePageSummaries()
    /** Páginas del diario. Las escenas tienen su propia lista. */
    val pageSummaries = allSummaries.map { list -> list.filter { it.kind != PageKind.SCENE } }
    val sceneSummaries = allSummaries.map { list -> list.filter { it.kind == PageKind.SCENE } }
    val tenseCounts = diary.observeTenseCounts()
    /** Días con páginas que cuentan para la racha (ver `COUNTS_FOR_STREAK` en Daos.kt). */
    val pagesPerDay = diary.observePagesPerDay()

    suspend fun newPage(now: Long, suggestedItems: String, kind: String = PageKind.DIARY, sceneJson: String? = null): Long {
        val today = LocalDate.now().toString()
        val entryId = diary.entryForDate(today)?.id ?: diary.insertEntry(DiaryEntry(date = today, createdAt = now))
        return diary.insertPage(
            DiaryPage(entryId = entryId, suggestedItems = suggestedItems, createdAt = now, updatedAt = now, kind = kind, sceneJson = sceneJson)
        )
    }

    suspend fun page(id: Long) = diary.page(id)

    /** La tinta de una página (ver InkSerializer); null si no tiene trazos. */
    suspend fun ink(pageId: Long): String? = diary.ink(pageId)

    /** ¿La página tiene trazos guardados? No lee la tinta. */
    suspend fun hasInk(pageId: Long): Boolean = diary.hasInk(pageId)

    /** Guarda los datos de la página sin tocar su tinta (un cambio de estado, de sugerencias, de escena). */
    suspend fun save(page: DiaryPage) = diary.updatePage(page.withoutInk())

    /**
     * Guarda la página y su tinta juntas, o nada. [inkJson] null = la página quedó sin trazos: se
     * borra su fila de tinta, que es como la racha sabe que está vacía (ver `PageInk`).
     */
    suspend fun save(page: DiaryPage, inkJson: String?): Unit = db.withWriteTransaction {
        // Un guardado atrasado de una página ya borrada no deja su tinta suelta en la base.
        if (diary.page(page.id) == null) return@withWriteTransaction
        diary.updatePage(page.withoutInk())
        writeInk(page.id, inkJson)
    }

    /**
     * Solo la tinta (y el ancho de la hoja con que se guardó): no toca el estado, para que el
     * guardado al salir de la página no pise uno que cambió mientras tanto.
     */
    suspend fun saveInk(id: Long, inkJson: String?, canvasWidth: Int, updatedAt: Long): Unit = db.withWriteTransaction {
        if (diary.page(id) == null) return@withWriteTransaction
        writeInk(id, inkJson)
        diary.touchPage(id, canvasWidth, updatedAt)
    }

    private suspend fun writeInk(pageId: Long, inkJson: String?) {
        if (inkJson == null) diary.deleteInk(pageId) else diary.upsertInk(PageInk(pageId, inkJson))
    }

    /** La columna vieja de la tinta en `pages` se queda siempre vacía (la tinta vive en `page_ink`). */
    private fun DiaryPage.withoutInk() = if (inkJson == EMPTY_INK) this else copy(inkJson = EMPTY_INK)

    /** Descartar una nota de sentido (cambiaste de idea): se borra, no cuenta para nada. */
    suspend fun dismissNote(correction: Correction) = diary.deleteCorrection(correction.id)
    fun corrections(pageId: Long) = diary.observeCorrections(pageId)
    fun sentenceNumbers(pageId: Long) = diary.observeSentenceNumbers(pageId)
    suspend fun correctionsNow(pageId: Long) = diary.corrections(pageId)
    suspend fun markRewritten(c: Correction) = diary.updateCorrection(c.copy(status = CorrectionStatus.REWRITTEN))
    suspend fun pendingPages() = diary.pagesWithStatus(PageStatus.PENDING)

    /** Borra la página con su tinta, sus correcciones y oraciones. El historial de FSRS se conserva. */
    suspend fun deletePage(id: Long): Unit = db.withWriteTransaction {
        diary.deleteCorrections(id)
        diary.deleteSentences(id)
        diary.deleteInk(id)
        diary.deletePage(id)
    }

    private companion object {
        const val EMPTY_INK = "[]"
    }
}
