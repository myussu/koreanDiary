package com.jorge.koreandiary

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.jorge.koreandiary.ai.CorrectionResponse
import com.jorge.koreandiary.ai.UsedItemDto
import com.jorge.koreandiary.data.StudyRepository
import com.jorge.koreandiary.data.db.AppDatabase
import com.jorge.koreandiary.data.db.DiaryEntry
import com.jorge.koreandiary.data.db.DiaryPage
import com.jorge.koreandiary.data.db.ItemType
import com.jorge.koreandiary.data.db.PageKind
import com.jorge.koreandiary.data.standard.StandardCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Revisar otra vez una página (sigues escribiendo después de revisar): solo cuenta lo nuevo. Así una
 * gramática nueva de la escena que usas en las líneas de después también entra a tu lista.
 */
class ReviewAgainTest {

    @Test
    fun aSecondReviewCountsOnlyWhatIsNew() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder<AppDatabase>().setDriver(BundledSQLiteDriver()).build()
        try {
            val catalog = StandardCatalog { name ->
                if (name == "grammar.tsv") listOf("1\t3\t표현\t-아서/어서\t\tporque", "2\t4\t표현\t-느라고\t\tpor estar") else emptyList()
            }
            val study = StudyRepository(db, catalog)
            val dao = db.diaryDao()
            val day = dao.insertEntry(DiaryEntry(date = "2026-11-14", createdAt = 0))
            // Las dos gramáticas nuevas de la escena van en las sugeridas.
            val suggested = "${ItemType.STD_GRAMMAR}:1,${ItemType.STD_GRAMMAR}:2"
            val id = dao.insertPage(DiaryPage(entryId = day, createdAt = 0, updatedAt = 0, kind = PageKind.SCENE, suggestedItems = suggested))
            fun response(vararg refs: String) = CorrectionResponse(
                sentences = emptyList(),
                corrections = emptyList(),
                used = refs.map { UsedItemDto(itemRef = it, form = "", kind = "gramatica", correct = true) },
            )

            val first = study.applyCorrection(dao.page(id)!!, response("sg1"), now = 1_000)
            assertEquals(1, first.newGrammar)
            assertEquals(1, first.reviewed)

            // La primera ya contó; la segunda gramática nueva, escrita después, sí cuenta.
            val second = study.applyCorrection(dao.page(id)!!, response("sg1", "sg2"), now = 2_000)
            assertEquals(1, second.newGrammar)
            assertEquals(1, second.reviewed)

            // Otra revisión sin nada nuevo no califica nada.
            val third = study.applyCorrection(dao.page(id)!!, response("sg1", "sg2"), now = 3_000)
            assertEquals(0, third.reviewed)
            assertEquals(2, db.studyDao().allGrammar().size)
        } finally {
            db.close()
        }
    }
}
