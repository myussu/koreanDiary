package com.jorge.koreandiary.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Update
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

// Room 3: toda función de DAO es suspend o devuelve Flow.

@Dao
interface StudyDao {
    @Insert
    suspend fun insertWord(word: Word): Long

    @Insert
    suspend fun insertGrammar(point: GrammarPoint): Long

    @Query("SELECT * FROM words WHERE baseForm = :baseForm LIMIT 1")
    suspend fun findWord(baseForm: String): Word?

    @Query("SELECT * FROM grammar_points WHERE pattern = :pattern LIMIT 1")
    suspend fun findGrammar(pattern: String): GrammarPoint?

    @Query("SELECT * FROM words WHERE id IN (:ids)")
    suspend fun wordsByIds(ids: List<Long>): List<Word>

    @Query("SELECT * FROM grammar_points WHERE id IN (:ids)")
    suspend fun grammarByIds(ids: List<Long>): List<GrammarPoint>

    @Query("SELECT * FROM words ORDER BY baseForm")
    fun observeWords(): Flow<List<Word>>

    @Query("SELECT * FROM grammar_points ORDER BY level, unit, id")
    fun observeGrammar(): Flow<List<GrammarPoint>>

    @Query("SELECT id FROM words")
    suspend fun allWordIds(): List<Long>

    @Query("SELECT baseForm FROM words")
    suspend fun allWordForms(): List<String>

    @Query("SELECT * FROM grammar_points")
    suspend fun allGrammar(): List<GrammarPoint>

    /**
     * Gramática a sugerir: la de los niveles elegidos (p. ej. base y 3A) más toda la que
     * detectaste en tus diarios, que ya sabes que usas, y la que elegiste agregar desde el workbook.
     */
    @Query("SELECT id FROM grammar_points WHERE level IN (:levels) OR source IN ('diario', 'laguna', 'workbook')")
    suspend fun grammarIdsForLevels(levels: List<String>): List<Long>

    @Insert
    suspend fun insertPendingWord(word: PendingWord): Long

    @Query("SELECT * FROM pending_words WHERE baseForm = :baseForm LIMIT 1")
    suspend fun findPendingWord(baseForm: String): PendingWord?

    @Query("SELECT * FROM pending_words ORDER BY createdAt DESC")
    fun observePendingWords(): Flow<List<PendingWord>>

    @Query("DELETE FROM pending_words WHERE id = :id")
    suspend fun deletePendingWord(id: Long)
}

@Dao
interface MemoryDao {
    @Upsert
    suspend fun upsert(state: MemoryState)

    @Query("SELECT * FROM memory_states WHERE itemType = :itemType AND itemId = :itemId LIMIT 1")
    suspend fun get(itemType: String, itemId: Long): MemoryState?

    @Query("SELECT * FROM memory_states WHERE itemType = :itemType")
    suspend fun allOfType(itemType: String): List<MemoryState>

    @Query("SELECT * FROM memory_states")
    fun observeAll(): Flow<List<MemoryState>>

    @Insert
    suspend fun insertEvent(event: ReviewEvent): Long

    /** Repasos que ya dejó una página: si hay alguno, su uso ya se contó en FSRS. */
    @Query("SELECT COUNT(*) FROM review_events WHERE pageId = :pageId")
    suspend fun eventCountForPage(pageId: Long): Int
}

@Dao
interface DiaryDao {
    @Insert
    suspend fun insertEntry(entry: DiaryEntry): Long

    @Query("SELECT * FROM entries WHERE date = :date LIMIT 1")
    suspend fun entryForDate(date: String): DiaryEntry?

    @Insert
    suspend fun insertPage(page: DiaryPage): Long

    @Update
    suspend fun updatePage(page: DiaryPage)

    @Query("UPDATE pages SET status = :status WHERE id = :id")
    suspend fun setPageStatus(id: Long, status: String)

    @Query("SELECT * FROM pages WHERE id = :id LIMIT 1")
    suspend fun page(id: Long): DiaryPage?

    @Query(
        """
        SELECT pages.id AS pageId, entries.date AS date, pages.status AS status,
               pages.confirmedText AS preview, pages.updatedAt AS updatedAt
        FROM pages JOIN entries ON pages.entryId = entries.id
        ORDER BY entries.date DESC, pages.createdAt DESC
        """
    )
    fun observePageSummaries(): Flow<List<PageSummary>>

    @Query("SELECT COUNT(pages.id) FROM pages JOIN entries ON pages.entryId = entries.id WHERE entries.date = :date")
    suspend fun pageCountForDate(date: String): Int

    /** Total de páginas. También sirve para comprobar que la base abre (tras importar una copia). */
    @Query("SELECT COUNT(*) FROM pages")
    suspend fun pageCount(): Int

    /** Páginas por día (solo días con al menos una página): racha, calendario y recordatorio. */
    @Query("SELECT entries.date AS date, COUNT(pages.id) AS total FROM pages JOIN entries ON pages.entryId = entries.id GROUP BY entries.date")
    fun observePagesPerDay(): Flow<List<DayCount>>

    @Query("SELECT entries.date AS date, COUNT(pages.id) AS total FROM pages JOIN entries ON pages.entryId = entries.id GROUP BY entries.date")
    suspend fun pagesPerDay(): List<DayCount>

    @Query("DELETE FROM pages WHERE id = :id")
    suspend fun deletePage(id: Long)

    @Query("SELECT * FROM pages WHERE status = :status")
    suspend fun pagesWithStatus(status: String): List<DiaryPage>

    @Insert
    suspend fun insertCorrection(correction: Correction): Long

    @Update
    suspend fun updateCorrection(correction: Correction)

    @Query("SELECT * FROM corrections WHERE pageId = :pageId ORDER BY sentence, id")
    fun observeCorrections(pageId: Long): Flow<List<Correction>>

    @Query("SELECT * FROM corrections WHERE pageId = :pageId ORDER BY sentence, id")
    suspend fun corrections(pageId: Long): List<Correction>

    @Query("DELETE FROM corrections WHERE pageId = :pageId")
    suspend fun deleteCorrections(pageId: Long)

    @Insert
    suspend fun insertSentence(sentence: Sentence): Long

    @Query("DELETE FROM sentences WHERE pageId = :pageId")
    suspend fun deleteSentences(pageId: Long)

    /** Cuántas oraciones hay de cada tiempo verbal, para ver qué se practica poco. */
    @Query("SELECT tense, COUNT(*) AS total FROM sentences GROUP BY tense")
    fun observeTenseCounts(): Flow<List<TenseCount>>
}

data class PageSummary(
    val pageId: Long,
    val date: String,
    val status: String,
    val preview: String?,
    val updatedAt: Long,
)

data class TenseCount(val tense: String, val total: Int)

data class DayCount(val date: String, val total: Int)
