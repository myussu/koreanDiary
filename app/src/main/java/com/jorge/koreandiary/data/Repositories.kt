package com.jorge.koreandiary.data

import com.jorge.koreandiary.ai.CorrectionResponse
import com.jorge.koreandiary.ai.ImportPayload
import com.jorge.koreandiary.ai.PromptItem
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
import com.jorge.koreandiary.srs.Fsrs
import java.time.LocalDate

data class ImportSummary(val wordsAdded: Int, val grammarAdded: Int, val duplicates: Int)

data class ApplySummary(val corrections: Int, val reviewed: Int, val newWords: Int, val pendingWords: Int)

/** Referencia a un ítem: "w12" ↔ (word, 12). */
data class ItemRef(val type: String, val id: Long) {
    val code get() = (if (type == ItemType.WORD) "w" else "g") + id

    companion object {
        fun parse(code: String): ItemRef? {
            val id = code.drop(1).toLongOrNull() ?: return null
            return when (code.firstOrNull()) {
                'w' -> ItemRef(ItemType.WORD, id)
                'g' -> ItemRef(ItemType.GRAMMAR, id)
                else -> null
            }
        }

        /** "word:12,grammar:3" → lista de referencias. */
        fun decodeList(encoded: String): List<ItemRef> = encoded.split(',').mapNotNull { part ->
            val (type, id) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            id.toLongOrNull()?.let { ItemRef(type, it) }
        }
    }
}

class StudyRepository(private val db: AppDatabase) {
    private val study = db.studyDao()
    private val memory = db.memoryDao()

    val words = study.observeWords()
    val grammar = study.observeGrammar()
    val pendingWords = study.observePendingWords()
    val memoryStates = memory.observeAll()

    suspend fun import(payload: ImportPayload, source: String = "import"): ImportSummary {
        var words = 0
        var grammar = 0
        var dupes = 0
        for (w in payload.words) {
            val base = w.baseForm.trim()
            if (base.isEmpty() || study.findWord(base) != null) { dupes++; continue }
            study.insertWord(Word(baseForm = base, type = w.type, meaning = w.meaning, level = w.level, irregular = w.irregular, source = source))
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

    /** Ítems para el prompt de corrección, con su id para que la IA los nombre sin ambigüedad. */
    suspend fun promptItems(refs: List<ItemRef>): List<PromptItem> {
        val words = study.wordsByIds(refs.filter { it.type == ItemType.WORD }.map { it.id })
        val grammar = study.grammarByIds(refs.filter { it.type == ItemType.GRAMMAR }.map { it.id })
        return words.map { PromptItem("w${it.id}", it.baseForm, it.type) } +
            grammar.map { PromptItem("g${it.id}", it.pattern, "gramática") }
    }

    suspend fun approvePendingWord(p: PendingWord, now: Long) {
        if (study.findWord(p.baseForm) == null) {
            val id = study.insertWord(Word(baseForm = p.baseForm, type = p.type, meaning = p.meaning, source = "diario"))
            review(ItemRef(ItemType.WORD, id), Fsrs.Rating.GOOD, EventKind.DISCOVERED, now, p.pageId)
        }
        study.deletePendingWord(p.id)
    }

    suspend fun rejectPendingWord(p: PendingWord) = study.deletePendingWord(p.id)

    /**
     * Aplica la respuesta de la IA: guarda correcciones y oraciones y actualiza FSRS.
     * FSRS solo se actualiza en la primera revisión de la página, para no contar dos veces.
     */
    suspend fun applyCorrection(page: DiaryPage, response: CorrectionResponse, now: Long): ApplySummary {
        val diary = db.diaryDao()
        val firstReview = page.status != PageStatus.REVIEWED

        diary.deleteCorrections(page.id)
        diary.deleteSentences(page.id)
        val tenseBySentence = response.sentences.associate { it.number to it.tense }
        response.sentences.forEach { diary.insertSentence(Sentence(pageId = page.id, number = it.number, text = it.text, tense = it.tense)) }
        response.corrections.forEach { c ->
            val ref = c.itemRef?.let { ItemRef.parse(it) }
            diary.insertCorrection(
                Correction(
                    pageId = page.id, sentence = c.sentence, original = c.original, suggestion = c.suggestion,
                    type = c.type, explanation = c.explanation, tense = tenseBySentence[c.sentence],
                    itemType = ref?.type, itemId = ref?.id,
                )
            )
        }

        var reviewed = 0
        var added = 0
        var pending = 0
        if (firstReview) {
            val suggested = ItemRef.decodeList(page.suggestedItems).toSet()
            val usedRefs = mutableSetOf<ItemRef>()
            for (u in response.used) {
                val ref = ItemRef.parse(u.itemRef) ?: continue
                if (!usedRefs.add(ref)) continue
                val (rating, kind) = when {
                    !u.correct -> Fsrs.Rating.AGAIN to EventKind.INCORRECT
                    // Usarlo tras verlo sugerido es reconocimiento, no recuerdo libre: cuenta como "Hard".
                    ref in suggested -> Fsrs.Rating.HARD to EventKind.CORRECT_SUGGESTED
                    else -> Fsrs.Rating.GOOD to EventKind.CORRECT_SPONTANEOUS
                }
                review(ref, rating, kind, now, page.id)
                reviewed++
            }
            // Sugerido y no usado: se registra sin calificación. No es un fallo.
            (suggested - usedRefs).forEach { ref ->
                memory.insertEvent(ReviewEvent(itemType = ref.type, itemId = ref.id, at = now, kind = EventKind.SUGGESTED_UNUSED, rating = null, pageId = page.id))
            }
            for (n in response.newWords) {
                val base = n.baseForm.trim()
                val existing = study.findWord(base)
                if (existing != null) {
                    val ref = ItemRef(ItemType.WORD, existing.id)
                    if (usedRefs.add(ref)) {
                        review(ref, Fsrs.Rating.GOOD, EventKind.CORRECT_SPONTANEOUS, now, page.id)
                        reviewed++
                    }
                } else if (looksLikeKoreanWord(base)) {
                    val id = study.insertWord(Word(baseForm = base, type = n.type, meaning = n.meaning, source = "diario"))
                    review(ItemRef(ItemType.WORD, id), Fsrs.Rating.GOOD, EventKind.DISCOVERED, now, page.id)
                    added++
                } else if (study.findPendingWord(base) == null) {
                    study.insertPendingWord(PendingWord(baseForm = base, type = n.type, meaning = n.meaning, pageId = page.id, createdAt = now))
                    pending++
                }
            }
        }
        return ApplySummary(response.corrections.size, reviewed, added, pending)
    }

    private suspend fun review(ref: ItemRef, rating: Fsrs.Rating, kind: String, now: Long, pageId: Long?) {
        val prev = memory.get(ref.type, ref.id)?.let { Fsrs.Memory(it.stability, it.difficulty, it.lastReviewAt, it.reps, it.lapses) }
        val result = Fsrs.review(prev, rating, now)
        val sentToAnki = memory.get(ref.type, ref.id)?.sentToAnki ?: false
        memory.upsert(
            MemoryState(
                itemType = ref.type, itemId = ref.id,
                stability = result.memory.stability, difficulty = result.memory.difficulty,
                lastReviewAt = now, dueAt = result.dueAt,
                reps = result.memory.reps, lapses = result.memory.lapses, sentToAnki = sentToAnki,
            )
        )
        memory.insertEvent(ReviewEvent(itemType = ref.type, itemId = ref.id, at = now, kind = kind, rating = rating.value, pageId = pageId))
    }

    /**
     * Validación provisional de palabras nuevas: solo sílabas hangul y largo razonable.
     * TODO: validar contra la API abierta del 한국어기초사전 (requiere clave gratuita).
     */
    private fun looksLikeKoreanWord(s: String): Boolean =
        s.length in 1..12 && s.all { it in '가'..'힣' }
}

class DiaryRepository(private val db: AppDatabase) {
    private val diary = db.diaryDao()

    val pageSummaries = diary.observePageSummaries()
    val tenseCounts = diary.observeTenseCounts()

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
}
