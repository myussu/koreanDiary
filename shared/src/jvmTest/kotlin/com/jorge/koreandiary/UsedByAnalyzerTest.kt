package com.jorge.koreandiary

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.jorge.koreandiary.ai.CorrectionDto
import com.jorge.koreandiary.ai.CorrectionResponse
import com.jorge.koreandiary.ai.SentenceDto
import com.jorge.koreandiary.ai.UsedItemDto
import com.jorge.koreandiary.data.StudyRepository
import com.jorge.koreandiary.data.db.AppDatabase
import com.jorge.koreandiary.data.db.DiaryEntry
import com.jorge.koreandiary.data.db.DiaryPage
import com.jorge.koreandiary.data.db.ItemType
import com.jorge.koreandiary.data.db.Word
import com.jorge.koreandiary.data.standard.StandardCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * «Revisar» con Kiwi: lo de tu lista que usaste bien y la IA no puso en «usados» también cuenta para
 * el repaso. Lo de una oración con corrección no, y lo que la IA ya nombró no se cuenta dos veces.
 */
class UsedByAnalyzerTest {

    private fun response(friend: Long) = CorrectionResponse(
        sentences = listOf(SentenceDto(1, "친구와 같이 영화를 봤어요.", "pasado"), SentenceDto(2, "음악을 들어요.", "presente")),
        corrections = listOf(CorrectionDto(2, "들어요", "들었어요", "tiempo", "Fue ayer: va en pasado.", itemRef = null)),
        // La IA solo nombró 친구.
        used = listOf(UsedItemDto("w$friend", "친구", "sustantivo", correct = true)),
    )

    private fun rated(withAnalyzer: Boolean): Pair<Int, Set<String>> = runBlocking {
        val db = Room.inMemoryDatabaseBuilder<AppDatabase>().setDriver(BundledSQLiteDriver()).build()
        try {
            val study = StudyRepository(db, StandardCatalog { emptyList() }, KiwiFixtures.dictionary)
            val words = db.studyDao()
            val ids = listOf("친구" to "sustantivo", "영화" to "sustantivo", "보다" to "verbo", "듣다" to "verbo")
                .associate { (form, type) -> words.insertWord(Word(baseForm = form, type = type, source = "import")) to form }
            val friend = ids.entries.first { it.value == "친구" }.key
            val dao = db.diaryDao()
            val entry = dao.insertEntry(DiaryEntry(date = "2026-11-24", createdAt = 0))
            val pageId = dao.insertPage(DiaryPage(entryId = entry, createdAt = 0, updatedAt = 0))
            val summary = study.applyCorrection(
                dao.page(pageId)!!, response(friend), now = 1,
                analyzer = if (withAnalyzer) KiwiFixtures.analyzer else null,
            )
            val forms = db.memoryDao().ratedOnPage(pageId).filter { it.itemType == ItemType.WORD }.mapNotNull { ids[it.itemId] }.toSet()
            summary.reviewed to forms
        } finally {
            db.close()
        }
    }

    @Test
    fun loQueUsasteBienCuentaAunqueLaIaLoOlvide() {
        val (reviewed, forms) = rated(withAnalyzer = true)
        // 영화 y 보다 (de 봤어요) los suma Kiwi; 듣다 está en una oración corregida y no cuenta.
        assertEquals(setOf("친구", "영화", "보다"), forms)
        assertEquals(3, reviewed)
    }

    @Test
    fun sinAnalizadorSoloLoQueNombroLaIa() {
        val (reviewed, forms) = rated(withAnalyzer = false)
        assertEquals(setOf("친구"), forms)
        assertEquals(1, reviewed)
    }
}
