package com.jorge.koreandiary

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.jorge.koreandiary.ai.ImportPayload
import com.jorge.koreandiary.ai.ImportWord
import com.jorge.koreandiary.ai.UsedItemDto
import com.jorge.koreandiary.data.StudyRepository
import com.jorge.koreandiary.data.db.AppDatabase
import com.jorge.koreandiary.data.standard.StandardCatalog
import com.jorge.koreandiary.dictionary.KoreanDictionary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Con el diccionario, una palabra nueva entra a la lista solo si existe (pedido por Jorge el 2026-11-21). */
class DictionaryVerifyTest {

    private val catalog = StandardCatalog { name ->
        when (name) {
            "vocab.tsv" -> listOf("21\t1\t올해\t\t명사\t올해 여름")
            else -> emptyList()
        }
    }

    // id, forma, homónimo, categoría, nivel, tipo, variantes, conjugaciones
    private val dictionary = KoreanDictionary { name ->
        when (name) {
            "index.tsv" -> listOf(
                "1\t은메달\t0\t명사\t3\t단어\t\t",
                "2\t올해\t0\t명사\t1\t단어\t\t",
                "3\t선선하다\t0\t형용사\t1\t단어\t\t선선한|선선하여|선선하니|선선해",
                "4\t-느라고\t0\t어미\t\t단어\t\t",
            )
            "senses/01.jsonl" -> listOf("""{"i":1,"s":[{"l":"medalla de plata","d":"Medalla de plata."}],"o":"銀medal"}""")
            else -> emptyList()
        }
    }

    private fun withDb(block: suspend (AppDatabase, StudyRepository) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder<AppDatabase>().setDriver(BundledSQLiteDriver()).build()
        try {
            block(db, StudyRepository(db, catalog, dictionary))
        } finally {
            db.close()
        }
    }

    @Test
    fun soloEntraLoQueEstaEnElDiccionario() = withDb { db, study ->
        val summary = study.applyUsage(
            listOf(
                UsedItemDto(null, "이난", "otro", correct = true),
                UsedItemDto(null, "은메달", "otro", correct = true),
                UsedItemDto(null, "선선하다", "otro", correct = false),
            ),
            suggested = emptySet(), now = 1_000L, pageId = null,
        )
        val dao = db.studyDao()
        // Aunque la IA diga que la usaste bien, si no existe va a «Por revisar».
        assertNull(dao.findWord("이난"))
        assertNotNull(dao.findPendingWord("이난"))
        assertEquals(1, summary.pending)
        // Existe: entra con el tipo y el significado del diccionario.
        val medal = dao.findWord("은메달")!!
        assertEquals("sustantivo", medal.type)
        assertEquals("medalla de plata", medal.meaning)
        // Existe aunque la hayas usado mal: entra (el error cuenta en su repaso).
        assertEquals("adjetivo", dao.findWord("선선하다")!!.type)
    }

    @Test
    fun alImportarLoQueNoExisteVaAPorRevisar() = withDb { db, study ->
        val summary = study.import(
            ImportPayload(
                words = listOf(
                    ImportWord(baseForm = "은메달", type = "sustantivo", meaning = "", level = "", irregular = null),
                    ImportWord(baseForm = "이난", type = "sustantivo", meaning = "ese día", level = "", irregular = null),
                ),
                grammar = emptyList(),
            )
        )
        assertEquals(1, summary.wordsAdded)
        assertEquals(1, summary.pending)
        assertEquals("medalla de plata", db.studyDao().findWord("은메달")!!.meaning)
        assertNull(db.studyDao().findWord("이난"))
        assertNotNull(db.studyDao().findPendingWord("이난"))
    }
}
