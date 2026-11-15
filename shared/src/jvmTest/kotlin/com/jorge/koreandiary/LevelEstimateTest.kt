package com.jorge.koreandiary

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.jorge.koreandiary.data.ItemRef
import com.jorge.koreandiary.data.ItemSource
import com.jorge.koreandiary.data.StudyRepository
import com.jorge.koreandiary.data.db.AppDatabase
import com.jorge.koreandiary.data.db.Correction
import com.jorge.koreandiary.data.db.DiaryEntry
import com.jorge.koreandiary.data.db.DiaryPage
import com.jorge.koreandiary.data.db.EventKind
import com.jorge.koreandiary.data.db.GrammarPoint
import com.jorge.koreandiary.data.db.ItemType
import com.jorge.koreandiary.data.db.PageKind
import com.jorge.koreandiary.data.db.PageStatus
import com.jorge.koreandiary.data.db.ReviewEvent
import com.jorge.koreandiary.data.db.Sentence
import com.jorge.koreandiary.data.db.Word
import com.jorge.koreandiary.data.standard.StandardCatalog
import com.jorge.koreandiary.data.standard.StdGrammar
import com.jorge.koreandiary.data.standard.StdWord
import com.jorge.koreandiary.progress.LevelCoverage
import com.jorge.koreandiary.progress.LevelEstimate
import com.jorge.koreandiary.progress.VocabTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * «Tu nivel» y «Para agregar» (decidido con Jorge el 2026-11-15): el nivel sale de la gramática de tu
 * lista y de la prueba corta de vocabulario, solo se propone, y lo que falta se agrega como nuevo o
 * como «Ya lo sé».
 */
class LevelEstimateTest {

    private fun std(id: Int, level: Int, form: String, related: List<String> = emptyList()) =
        StdGrammar(id, level, "표현", form, related, "")

    private fun mine(id: Long, pattern: String, variants: String = pattern) =
        GrammarPoint(id = id, unit = null, level = "", pattern = pattern, variants = variants, appliesTo = "", source = "import")

    private fun word(id: Int, level: Int, form: String, pos: String = "명사") = StdWord(id, level, form, "", pos, "")

    @Test
    fun grammarCoverageMatchesTheBookNotationAndCountsWhatWasUsedWell() {
        val catalog = listOf(std(1, 1, "-고"), std(2, 1, "-으니까", listOf("-니까")), std(3, 2, "-는데"))
        // El libro escribe "A/V-(으)니까": comparte clave con la del estándar.
        val list = listOf(mine(10, "A/V-(으)니까"), mine(11, "V-고"))
        val coverage = LevelEstimate.grammarCoverage(catalog, list, usedWell = setOf(10L))
        assertEquals(LevelCoverage(1, 2, 2, 1), coverage[0])
        assertEquals(LevelCoverage(2, 1, 0, 0), coverage[1])
        assertEquals(6, coverage.size)
        assertEquals(1f, coverage[0].listShare)
        assertEquals(0.5f, coverage[0].wellShare)
    }

    @Test
    fun theEstimateIsTheHighestLevelCoveredWithoutGaps() {
        fun c(level: Int, total: Int, inList: Int) = LevelCoverage(level, total, inList, 0)
        // 1 y 2 al 70 % o más; el 3 no llega. Aunque el 4 esté lleno, no salta.
        assertEquals(2, LevelEstimate.estimate(listOf(c(1, 10, 9), c(2, 10, 7), c(3, 10, 6), c(4, 10, 10))))
        assertEquals(0, LevelEstimate.estimate(listOf(c(1, 10, 6), c(2, 10, 10))))
        // Un nivel vacío (catálogo sin datos) corta.
        assertEquals(1, LevelEstimate.estimate(listOf(c(1, 10, 10), c(2, 0, 0))))
        assertEquals(3, LevelEstimate.estimateFromTest(mapOf(1 to 1f, 2 to 0.8f, 3 to 0.7f, 4 to 0.4f, 5 to 0.9f)))
        // Si dejó la prueba en el nivel 2, no hay nivel 3.
        assertEquals(2, LevelEstimate.estimateFromTest(mapOf(1 to 0.9f, 2 to 0.9f)))
        assertEquals(0, LevelEstimate.estimateFromTest(emptyMap()))
    }

    @Test
    fun theSampleSkipsYourListOtherWordsAndHomonyms() {
        val catalog = listOf(
            word(1, 1, "가게"), word(2, 1, "가격"), word(3, 1, "가격"), word(4, 1, "그리고", "부사"),
            word(5, 1, "아이고", "감탄사"), word(6, 2, "가구"),
        ) + (10..40).map { word(it, 3, "단어$it") }
        val sample = LevelEstimate.sample(catalog, mine = setOf("가게"), perLevel = 15, random = Random(7))
        assertEquals(setOf("가격", "그리고"), sample[1]!!.map { it.word }.toSet())
        assertEquals(listOf("가구"), sample[2]!!.map { it.word })
        assertEquals(15, sample[3]!!.size)
        assertEquals(15, sample[3]!!.map { it.word }.distinct().size)
        assertTrue(sample[6]!!.isEmpty())
    }

    @Test
    fun missingGrammarGoesFromTheBasicsUp() {
        val catalog = listOf(std(5, 2, "-는데"), std(1, 1, "-고"), std(9, 3, "-느라고"), std(2, 1, "-지만"), std(7, 2, "-으면서", listOf("-면서")))
        val missing = LevelEstimate.missingGrammar(catalog, listOf(mine(1, "V-(으)면서")), maxLevel = 2)
        assertEquals(listOf(1, 2, 5), missing.map { it.id })
    }

    @Test
    fun theVocabTestSurvivesTheSettings() {
        val test = VocabTest(123L, mapOf(1 to 0.9f, 2 to 0.5f), listOf("가구", "가방"))
        val back = VocabTest.decode(test.encode())
        assertNotNull(back)
        assertEquals(123L, back!!.takenAt)
        assertEquals(0.5f, back.known[2]!!, 0.0001f)
        assertEquals(listOf("가구", "가방"), back.unknown)
        assertNull(VocabTest.decode(""))
        assertNull(VocabTest.decode("{roto"))
    }

    private val catalog = StandardCatalog { name ->
        when (name) {
            "grammar.tsv" -> listOf(
                "1\t1\t연결어미\t-고\t\ty",
                "2\t1\t연결어미\t-으니까\t-니까\tporque",
                "3\t2\t연결어미\t-는데\t\tpero, y",
            )
            "vocab.tsv" -> listOf(
                "11\t1\t가방\t\t명사\t가방을 들다",
                "12\t1\t공부하다\t\t동사\t한국어를 공부하다",
                "13\t2\t가구\t02\t명사\t가구를 놓다",
            )
            else -> emptyList()
        }
    }

    @Test
    fun addingFromTheStandardIsNewOrKnown() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder<AppDatabase>().setDriver(BundledSQLiteDriver()).build()
        try {
            val study = StudyRepository(db, catalog)
            val dao = db.studyDao()
            val memory = db.memoryDao()
            val now = 10_000_000L
            // «Agregar»: entra con su nivel y la fuente del estándar, sin repaso (la app la va a sugerir).
            val added = study.addStandard(ItemRef(ItemType.STD_GRAMMAR, 3), known = false, now = now)!!
            assertEquals(ItemType.GRAMMAR, added.type)
            val point = dao.allGrammar().single()
            assertEquals("-는데", point.pattern)
            assertEquals("2급", point.level)
            assertEquals(ItemSource.STANDARD, point.source)
            assertNull(memory.get(ItemType.GRAMMAR, added.id))
            // Las del estándar cuentan para las sugerencias aunque su nivel no esté entre los elegidos.
            assertEquals(listOf(added.id), dao.grammarIdsForLevels(listOf("3A")))
            // Otra vez la misma: no se duplica.
            assertEquals(added, study.addStandard(ItemRef(ItemType.STD_GRAMMAR, 3), known = false, now = now))
            assertEquals(1, dao.allGrammar().size)

            // «Ya lo sé»: conocida, vuelve mucho después.
            val known = study.addStandard(ItemRef(ItemType.STD_WORD, 13), known = true, now = now)!!
            assertEquals("가구", dao.findWord("가구")?.baseForm)
            assertEquals("sustantivo", dao.findWord("가구")?.type)
            val state = memory.get(ItemType.WORD, known.id)!!
            assertTrue(state.dueAt - now > 5L * 24 * 60 * 60 * 1000)
            // «Declarada» no es un error ni una página: no sale entre lo fallado.
            assertTrue(memory.failedSince(0, 10).isEmpty())
            assertTrue(memory.pagesUsing(ItemType.WORD, known.id, 5).isEmpty())

            // Si ya tienes una que comparte la clave, usa la tuya.
            val book = dao.insertGrammar(GrammarPoint(unit = 2, level = "2A", pattern = "A/V-(으)니까", variants = "A/V-(으)니까", appliesTo = "AV", source = "import"))
            assertEquals(ItemRef(ItemType.GRAMMAR, book), study.addStandard(ItemRef(ItemType.STD_GRAMMAR, 2), known = false, now = now))
        } finally {
            db.close()
        }
    }

    @Test
    fun theLevelReportAndTheMissingWords() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder<AppDatabase>().setDriver(BundledSQLiteDriver()).build()
        try {
            val study = StudyRepository(db, catalog)
            val dao = db.studyDao()
            val g1 = dao.insertGrammar(GrammarPoint(unit = 1, level = "1A", pattern = "V-고", variants = "V-고", appliesTo = "V", source = "import"))
            dao.insertGrammar(GrammarPoint(unit = 2, level = "1A", pattern = "A/V-(으)니까", variants = "A/V-(으)니까", appliesTo = "AV", source = "import"))
            dao.insertWord(Word(baseForm = "가방", type = "sustantivo", source = "import"))
            // -고 ya la usó bien; -(으)니까 está en la lista pero nunca la usó.
            study.addStandard(ItemRef(ItemType.GRAMMAR, g1), known = true, now = 1000L)
            val report = study.levelReport(maxLevel = 2)
            assertEquals(LevelCoverage(1, 2, 2, 1), report.grammar[0])
            assertEquals(1, LevelEstimate.estimate(report.grammar))
            assertEquals(listOf("-는데"), report.missingGrammar.map { it.form })
            assertEquals(LevelCoverage(1, 2, 1, 0), report.words[0])
            // La prueba nunca pregunta lo que ya está en la lista; «Para agregar» tampoco lo repite.
            assertFalse(study.vocabSample()[1]!!.any { it.word == "가방" })
            assertEquals(listOf("공부하다", "가구"), study.missingWords(listOf("가구", "가방", "공부하다", "없는말")).map { it.word })
        } finally {
            db.close()
        }
    }

    @Test
    fun anItemShowsYourSentencesWithIt() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder<AppDatabase>().setDriver(BundledSQLiteDriver()).build()
        try {
            val study = StudyRepository(db, catalog)
            val dao = db.studyDao()
            val diary = db.diaryDao()
            val w = dao.insertWord(Word(baseForm = "공부하다", type = "verbo", source = "import"))
            val g = dao.insertGrammar(GrammarPoint(unit = 1, level = "1A", pattern = "A/V-(으)니까", variants = "A/V-(으)니까", appliesTo = "AV", source = "import"))
            val entry = diary.insertEntry(DiaryEntry(date = "2026-11-15", createdAt = 0))
            val page = diary.insertPage(DiaryPage(entryId = entry, status = PageStatus.REVIEWED, createdAt = 0, updatedAt = 0, kind = PageKind.DIARY))
            diary.insertSentence(Sentence(pageId = page, number = 1, text = "오늘은 비가 와서 집에 있었어요.", tense = "pasado"))
            diary.insertSentence(Sentence(pageId = page, number = 2, text = "시험이 있으니까 도서관에서 공부했어요.", tense = "pasado"))
            val memory = db.memoryDao()
            memory.insertEvent(ReviewEvent(itemType = ItemType.WORD, itemId = w, at = 5, kind = EventKind.CORRECT_SPONTANEOUS, rating = 3, pageId = page))
            memory.insertEvent(ReviewEvent(itemType = ItemType.GRAMMAR, itemId = g, at = 5, kind = EventKind.CORRECT_SPONTANEOUS, rating = 3, pageId = page))
            diary.insertCorrection(
                Correction(
                    pageId = page, sentence = 2, original = "있니까", suggestion = "있으니까", type = "conjugacion",
                    explanation = "", tense = null, itemType = ItemType.GRAMMAR, itemId = g,
                )
            )
            val word = study.itemUsage(ItemRef(ItemType.WORD, w), "공부하다")
            assertEquals(listOf("시험이 있으니까 도서관에서 공부했어요."), word.sentences)
            assertTrue(word.corrections.isEmpty())
            val grammar = study.itemUsage(ItemRef(ItemType.GRAMMAR, g), "A/V-(으)니까")
            assertEquals(listOf("시험이 있으니까 도서관에서 공부했어요."), grammar.sentences)
            assertEquals(listOf("있니까 → 있으니까"), grammar.corrections)
            // Sin páginas: nada que mostrar.
            assertTrue(study.itemUsage(ItemRef(ItemType.WORD, 999), "없다").sentences.isEmpty())
        } finally {
            db.close()
        }
    }
}
