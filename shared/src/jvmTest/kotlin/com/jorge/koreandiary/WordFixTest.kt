package com.jorge.koreandiary

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.jorge.koreandiary.ai.UsedItemDto
import com.jorge.koreandiary.data.StudyRepository
import com.jorge.koreandiary.data.WordFix
import com.jorge.koreandiary.data.db.AppDatabase
import com.jorge.koreandiary.data.db.Correction
import com.jorge.koreandiary.data.db.EventKind
import com.jorge.koreandiary.data.db.ItemType
import com.jorge.koreandiary.data.db.MemoryState
import com.jorge.koreandiary.data.db.ReviewEvent
import com.jorge.koreandiary.data.db.Word
import com.jorge.koreandiary.data.standard.StandardCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Palabras mal escritas en la lista (entró «이난» del diario, 2026-11-21): no entran si las usaste mal
 * y no están en el estándar, y las que ya entraron se corrigen o se quitan desde Estudio.
 */
class WordFixTest {

    private val catalog = StandardCatalog { name ->
        when (name) {
            "vocab.tsv" -> listOf("21\t1\t올해\t\t명사\t올해 여름")
            else -> emptyList()
        }
    }

    private fun withDb(block: suspend (AppDatabase, StudyRepository) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder<AppDatabase>().setDriver(BundledSQLiteDriver()).build()
        try {
            block(db, StudyRepository(db, catalog))
        } finally {
            db.close()
        }
    }

    @Test
    fun loQueUsasteMalYNoEstaEnElEstandarVaAPorRevisar() = withDb { db, study ->
        val summary = study.applyUsage(
            listOf(
                UsedItemDto(null, "이난", "otro", correct = false),
                UsedItemDto(null, "은메달", "sustantivo", correct = true),
                UsedItemDto(null, "올해", "sustantivo", correct = false),
            ),
            suggested = emptySet(), now = 1_000L, pageId = null,
        )
        val dao = db.studyDao()
        assertNull(dao.findWord("이난"))
        assertNotNull(dao.findPendingWord("이난"))
        assertEquals(1, summary.pending)
        // Bien usada aunque no esté en el estándar: entra como siempre.
        assertNotNull(dao.findWord("은메달"))
        // Del estándar entra aunque la hayas usado mal: es una palabra real.
        assertNotNull(dao.findWord("올해"))
    }

    @Test
    fun corregirLaRenombraConSuHistorial() = withDb { db, study ->
        val dao = db.studyDao()
        val id = dao.insertWord(Word(baseForm = "이난", type = "otro", source = "diario"))
        db.memoryDao().upsert(MemoryState(ItemType.WORD, id, stability = 3.0, difficulty = 5.0, lastReviewAt = 1L, dueAt = 2L, reps = 2, lapses = 1))
        db.memoryDao().insertEvent(ReviewEvent(itemType = ItemType.WORD, itemId = id, at = 1L, kind = EventKind.DISCOVERED, rating = 3, pageId = null))

        assertEquals(WordFix.RENAMED, study.correctWord(id, " 올해 "))
        val fixed = dao.wordsByIds(listOf(id)).single()
        assertEquals("올해", fixed.baseForm)
        // Tipo y nivel del estándar.
        assertEquals("sustantivo", fixed.type)
        assertEquals("1급", fixed.level)
        assertEquals(2, db.memoryDao().get(ItemType.WORD, id)?.reps)
        assertNull(dao.findWord("이난"))
    }

    @Test
    fun corregirNoJuntaNiAceptaCualquierCosa() = withDb { db, study ->
        val dao = db.studyDao()
        val wrong = dao.insertWord(Word(baseForm = "이난", type = "otro", source = "diario"))
        dao.insertWord(Word(baseForm = "올해", type = "sustantivo", source = "import"))
        assertEquals(WordFix.EXISTS, study.correctWord(wrong, "올해"))
        assertEquals(WordFix.UNCHANGED, study.correctWord(wrong, "이난"))
        assertEquals(WordFix.INVALID, study.correctWord(wrong, "올 해"))
        assertEquals(WordFix.INVALID, study.correctWord(wrong, ""))
        assertEquals("이난", dao.wordsByIds(listOf(wrong)).single().baseForm)
    }

    @Test
    fun quitarBorraLaPalabraYSuHistorial() = withDb { db, study ->
        val dao = db.studyDao()
        val id = dao.insertWord(Word(baseForm = "이난", type = "otro", source = "diario"))
        val other = dao.insertWord(Word(baseForm = "올해", type = "sustantivo", source = "import"))
        db.memoryDao().upsert(MemoryState(ItemType.WORD, id, stability = 3.0, difficulty = 5.0, lastReviewAt = 1L, dueAt = 2L, reps = 1, lapses = 0))
        db.memoryDao().upsert(MemoryState(ItemType.WORD, other, stability = 3.0, difficulty = 5.0, lastReviewAt = 1L, dueAt = 2L, reps = 1, lapses = 0))
        db.memoryDao().insertEvent(ReviewEvent(itemType = ItemType.WORD, itemId = id, at = 1L, kind = EventKind.INCORRECT, rating = 1, pageId = 7L))
        val correction = db.diaryDao().insertCorrection(
            Correction(pageId = 7L, sentence = 0, original = "이난", suggestion = "올해", type = "ortografia", explanation = "…", tense = null, itemType = ItemType.WORD, itemId = id)
        )

        study.removeWord(id)

        assertTrue(dao.wordsByIds(listOf(id)).isEmpty())
        assertNull(db.memoryDao().get(ItemType.WORD, id))
        assertTrue(db.memoryDao().pagesUsing(ItemType.WORD, id, 5).isEmpty())
        assertTrue(db.memoryDao().failedSince(0, 10).isEmpty())
        assertTrue(db.diaryDao().correctionsForItem(ItemType.WORD, id, 5).isEmpty())
        // La corrección sigue, sin enlace; lo demás no se toca.
        assertNotNull(db.diaryDao().corrections(7L).singleOrNull { it.id == correction && it.itemId == null })
        assertNotNull(db.memoryDao().get(ItemType.WORD, other))
    }
}
