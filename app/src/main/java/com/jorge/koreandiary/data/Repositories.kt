package com.jorge.koreandiary.data

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
import com.jorge.koreandiary.srs.Fsrs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class ImportSummary(val wordsAdded: Int, val grammarAdded: Int, val duplicates: Int)

data class ApplySummary(
    val corrections: Int,
    val reviewed: Int,
    val newWords: Int,
    val newGrammar: Int,
    val pendingWords: Int,
    val improvements: Int,
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
}

data class UsageSummary(val reviewed: Int = 0, val newWords: Int = 0, val newGrammar: Int = 0, val pending: Int = 0)

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

    suspend fun import(payload: ImportPayload, source: String = ItemSource.IMPORT): ImportSummary {
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
        return ImportSummary(words, grammar, dupes)
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

    suspend fun approvePendingWord(p: PendingWord, now: Long) {
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
     */
    suspend fun applyCorrection(page: DiaryPage, response: CorrectionResponse, now: Long): ApplySummary {
        val diary = db.diaryDao()
        val firstReview = page.status != PageStatus.REVIEWED

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
                    type = c.type, explanation = c.explanation, tense = tenseBySentence[c.sentence],
                    itemType = ref?.type, itemId = ref?.id,
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

        val usage = if (firstReview) {
            applyUsage(response.used, ItemRef.decodeList(page.suggestedItems).toSet(), now, page.id)
        } else UsageSummary()
        return ApplySummary(response.corrections.size, usage.reviewed, usage.newWords, usage.newGrammar, usage.pending, response.improvements.size)
    }

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
    suspend fun applyUsage(used: List<UsedItemDto>, suggested: Set<ItemRef>, now: Long, pageId: Long?): UsageSummary {
        var reviewed = 0
        var newWords = 0
        var newGrammar = 0
        var pending = 0
        val myGrammar = study.allGrammar().toMutableList()
        val counted = mutableSetOf<ItemRef>()
        for (u in used) {
            val resolved = resolve(u, suggested, myGrammar, pageId, now, onPending = { pending++ }) ?: continue
            if (!counted.add(resolved.ref)) continue
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
        (suggested.filterNot { it.isStandard }.toSet() - counted).forEach { ref ->
            memory.insertEvent(ReviewEvent(itemType = ref.type, itemId = ref.id, at = now, kind = EventKind.SUGGESTED_UNUSED, rating = null, pageId = pageId))
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
        if (std == null && !looksLikeKoreanWord(base)) {
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
}

class DiaryRepository(private val db: AppDatabase) {
    private val diary = db.diaryDao()

    val pageSummaries = diary.observePageSummaries()
    val tenseCounts = diary.observeTenseCounts()
    val pagesPerDay = diary.observePagesPerDay()

    suspend fun newPage(now: Long, suggestedItems: String): Long {
        val today = LocalDate.now().toString()
        val entryId = diary.entryForDate(today)?.id ?: diary.insertEntry(DiaryEntry(date = today, createdAt = now))
        return diary.insertPage(DiaryPage(entryId = entryId, suggestedItems = suggestedItems, createdAt = now, updatedAt = now))
    }

    suspend fun page(id: Long) = diary.page(id)
    suspend fun save(page: DiaryPage) = diary.updatePage(page)
    fun corrections(pageId: Long) = diary.observeCorrections(pageId)
    suspend fun correctionsNow(pageId: Long) = diary.corrections(pageId)
    suspend fun markRewritten(c: Correction) = diary.updateCorrection(c.copy(status = CorrectionStatus.REWRITTEN))
    suspend fun pendingPages() = diary.pagesWithStatus(PageStatus.PENDING)

    /** Borra la página con sus correcciones y oraciones. El historial de FSRS se conserva. */
    suspend fun deletePage(id: Long) {
        diary.deleteCorrections(id)
        diary.deleteSentences(id)
        diary.deletePage(id)
    }
}
