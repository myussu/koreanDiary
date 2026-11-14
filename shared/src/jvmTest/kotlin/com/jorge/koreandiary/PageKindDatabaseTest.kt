package com.jorge.koreandiary

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.jorge.koreandiary.data.DiaryRepository
import com.jorge.koreandiary.data.db.AppDatabase
import com.jorge.koreandiary.data.db.Correction
import com.jorge.koreandiary.data.db.CorrectionStatus
import com.jorge.koreandiary.data.db.DiaryEntry
import com.jorge.koreandiary.data.db.DiaryPage
import com.jorge.koreandiary.data.db.PageInk
import com.jorge.koreandiary.data.db.PageKind
import com.jorge.koreandiary.data.db.PageStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * La base real (Room con el mismo SQLite que la app): qué páginas cuentan para la racha, dónde vive
 * la tinta, y que una base de una versión anterior, como la del celular, abre con la versión actual
 * sin perder páginas ni tinta.
 */
class PageKindDatabaseTest {

    private fun inMemory(): AppDatabase =
        Room.inMemoryDatabaseBuilder<AppDatabase>().setDriver(BundledSQLiteDriver()).build()

    private val oneStroke = "{\"v\":2,\"s\":[[0.1,0.1,0,0.5,0.2,0.2,8,0.5]]}"

    @Test
    fun scenesCountForTheStreakOnlyWhenReviewedWithNothingPending() = runBlocking {
        val db = inMemory()
        try {
            val dao = db.diaryDao()
            val day = dao.insertEntry(DiaryEntry(date = "2026-11-11", createdAt = 0))
            val otherDay = dao.insertEntry(DiaryEntry(date = "2026-11-10", createdAt = 0))

            fun page(entry: Long, kind: String, status: String) =
                DiaryPage(entryId = entry, status = status, createdAt = 0, updatedAt = 0, kind = kind)

            // Día 11: un borrador del diario con tinta (cuenta), dos abiertos por error (no cuentan) y cuatro escenas.
            val written = dao.insertPage(page(day, PageKind.DIARY, PageStatus.DRAFT))
            dao.upsertInk(PageInk(written, oneStroke))
            dao.insertPage(page(day, PageKind.DIARY, PageStatus.DRAFT))
            dao.insertPage(page(day, PageKind.DIARY, PageStatus.DRAFT))
            dao.insertPage(page(day, PageKind.SCENE, PageStatus.DRAFT))
            dao.insertPage(page(day, PageKind.SCENE, PageStatus.PENDING))
            val withPending = dao.insertPage(page(day, PageKind.SCENE, PageStatus.REVIEWED))
            val allRewritten = dao.insertPage(page(day, PageKind.SCENE, PageStatus.REVIEWED))
            // Día 10: una escena revisada sin correcciones y una página del diario con solo texto confirmado.
            dao.insertPage(page(otherDay, PageKind.SCENE, PageStatus.REVIEWED))
            dao.insertPage(page(otherDay, PageKind.DIARY, PageStatus.PENDING).copy(confirmedText = "오늘은 바다에 갔어요."))

            fun correction(pageId: Long, status: String) = Correction(
                pageId = pageId, sentence = 1, original = "학교를", suggestion = "학교에", type = "particula",
                explanation = "", tense = null, itemType = null, itemId = null, status = status,
            )
            dao.insertCorrection(correction(withPending, CorrectionStatus.PENDING))
            dao.insertCorrection(correction(withPending, CorrectionStatus.REWRITTEN))
            dao.insertCorrection(correction(allRewritten, CorrectionStatus.REWRITTEN))
            // Una mejora o una nota de sentido no exigen reescribir: no frenan la escena.
            dao.insertCorrection(correction(allRewritten, CorrectionStatus.SUGGESTION))
            dao.insertCorrection(correction(allRewritten, CorrectionStatus.NOTE))

            val perDay = dao.pagesPerDay().associate { it.date to it.total }
            assertEquals(mapOf("2026-11-11" to 2, "2026-11-10" to 2), perDay)
            assertEquals(2, dao.pageCountForDate("2026-11-11"))

            // Al reescribir la última corrección pendiente, la escena pasa a contar.
            val pending = dao.corrections(withPending).first { it.status == CorrectionStatus.PENDING }
            dao.updateCorrection(pending.copy(status = CorrectionStatus.REWRITTEN))
            assertEquals(3, dao.pageCountForDate("2026-11-11"))
        } finally {
            db.close()
        }
    }

    @Test
    fun inkOnlySaveKeepsTheStatus() = runBlocking {
        val db = inMemory()
        try {
            val dao = db.diaryDao()
            val diary = DiaryRepository(db)
            val day = dao.insertEntry(DiaryEntry(date = "2026-11-13", createdAt = 0))
            val id = dao.insertPage(DiaryPage(entryId = day, status = PageStatus.REVIEWED, createdAt = 0, updatedAt = 0))
            diary.saveInk(id, oneStroke, 1080, 5)
            val page = dao.page(id)!!
            assertEquals(PageStatus.REVIEWED, page.status)
            assertEquals(1080, page.canvasWidth)
            assertEquals(5L, page.updatedAt)
            assertEquals(oneStroke, diary.ink(id))
            assertTrue(diary.hasInk(id))
        } finally {
            db.close()
        }
    }

    @Test
    fun aPageWithoutStrokesHasNoInkRowAndDoesNotCount() = runBlocking {
        val db = inMemory()
        try {
            val dao = db.diaryDao()
            val diary = DiaryRepository(db)
            val day = dao.insertEntry(DiaryEntry(date = "2026-11-14", createdAt = 0))
            val id = dao.insertPage(DiaryPage(entryId = day, createdAt = 0, updatedAt = 0))
            assertNull(diary.ink(id))
            assertEquals(0, dao.pageCountForDate("2026-11-14"))

            // Escribes: la página y su tinta se guardan juntas y el día pasa a contar.
            val page = dao.page(id)!!
            diary.save(page.copy(suggestedItems = "word:1"), oneStroke)
            assertEquals(oneStroke, diary.ink(id))
            assertEquals("word:1", dao.page(id)!!.suggestedItems)
            assertEquals(1, dao.pageCountForDate("2026-11-14"))

            // Un guardado de solo datos (p. ej. el estado) no toca la tinta.
            diary.save(dao.page(id)!!.copy(status = PageStatus.PENDING))
            assertEquals(oneStroke, diary.ink(id))
            // La columna vieja de la tinta en `pages` queda siempre vacía.
            assertEquals("[]", dao.page(id)!!.inkJson)

            // Borras todo lo escrito: se va la fila de tinta y el día deja de contar.
            diary.save(dao.page(id)!!.copy(status = PageStatus.DRAFT), null)
            assertNull(diary.ink(id))
            assertFalse(diary.hasInk(id))
            assertEquals(0, dao.pageCountForDate("2026-11-14"))
        } finally {
            db.close()
        }
    }

    @Test
    fun deletingAPageDeletesItsInk() = runBlocking {
        val db = inMemory()
        try {
            val dao = db.diaryDao()
            val diary = DiaryRepository(db)
            val day = dao.insertEntry(DiaryEntry(date = "2026-11-14", createdAt = 0))
            val id = dao.insertPage(DiaryPage(entryId = day, createdAt = 0, updatedAt = 0))
            diary.saveInk(id, oneStroke, 1000, 1)
            diary.deletePage(id)
            assertNull(dao.page(id))
            assertFalse(diary.hasInk(id))
        } finally {
            db.close()
        }
    }

    @Test
    fun summariesCarryTheKind() = runBlocking {
        val db = inMemory()
        try {
            val dao = db.diaryDao()
            val day = dao.insertEntry(DiaryEntry(date = "2026-11-11", createdAt = 1))
            dao.insertPage(DiaryPage(entryId = day, createdAt = 1, updatedAt = 1))
            dao.insertPage(DiaryPage(entryId = day, createdAt = 2, updatedAt = 2, kind = PageKind.SCENE))
            val kinds = dao.observePageSummaries().first().map { it.kind }
            assertEquals(listOf(PageKind.SCENE, PageKind.DIARY), kinds)
        } finally {
            db.close()
        }
    }

    /** Arma una base con el esquema 1.json (el primero que tuvo el celular) y la abre con la versión actual. */
    @Test
    fun versionOneDatabaseOpensWithItsPagesAsDiary() = runBlocking {
        withDatabaseFromSchema(
            version = 1,
            seed = {
                execSQL("INSERT INTO entries (id, date, createdAt) VALUES (1, '2026-11-09', 0)")
                execSQL(
                    "INSERT INTO pages (id, entryId, inkJson, canvasWidth, status, reviewedLineCount, suggestedItems, createdAt, updatedAt) " +
                        "VALUES (7, 1, '[]', 1080, '${PageStatus.REVIEWED}', 3, '', 0, 0)"
                )
            },
        ) { db ->
            val page = db.diaryDao().page(7)!!
            assertEquals(PageKind.DIARY, page.kind)
            assertEquals(3, page.reviewedLineCount)
            assertEquals(1, db.diaryDao().pageCountForDate("2026-11-09"))
        }
    }

    /**
     * La base que tiene hoy el celular (esquema 3.json, la tinta en `pages.inkJson`) abre con la
     * versión actual: cada página con trazos conserva su tinta, ahora en `page_ink`; las vacías no
     * dejan fila; y lo demás de cada página queda como estaba.
     */
    @Test
    fun versionThreeInkMovesToItsOwnTable() = runBlocking {
        // Una página larga de verdad: el texto de la tinta no cabe en una sola página de SQLite.
        val long = "{\"v\":2,\"s\":[" + (0 until 4000).joinToString(",") { "[0.1234,0.5678,${it * 10},0.55,0.2345,0.6789,${it * 10 + 5},0.6]" } + "]}"
        val oldFormat = "[[0.1,0.2,5,0.3,0.4,15]]"
        withDatabaseFromSchema(
            version = 3,
            seed = {
                execSQL("INSERT INTO entries (id, date, createdAt) VALUES (1, '2026-11-12', 0)")
                execSQL("INSERT INTO entries (id, date, createdAt) VALUES (2, '2026-11-13', 0)")
                fun page(id: Int, entry: Int, ink: String, status: String, kind: String, text: String?) = execSQL(
                    "INSERT INTO pages (id, entryId, inkJson, canvasWidth, confirmedText, status, reviewedLineCount, suggestedItems, createdAt, updatedAt, kind, sceneJson) " +
                        "VALUES ($id, $entry, '$ink', 1080, ${text?.let { "'$it'" } ?: "NULL"}, '$status', 2, 'word:3', $id, $id, '$kind', NULL)"
                )
                page(1, 1, long, PageStatus.REVIEWED, PageKind.DIARY, "오늘은 바다에 갔어요.")
                page(2, 1, oldFormat, PageStatus.DRAFT, PageKind.DIARY, null)
                // Abiertas por error: sin trazos, en los dos formatos.
                page(3, 2, "[]", PageStatus.DRAFT, PageKind.DIARY, null)
                page(4, 2, "{\"v\":2,\"s\":[]}", PageStatus.DRAFT, PageKind.DIARY, null)
                page(5, 2, oneStroke, PageStatus.PENDING, PageKind.SCENE, null)
            },
        ) { db ->
            val dao = db.diaryDao()
            assertEquals(5, dao.pageCount())
            // La tinta llega entera, carácter por carácter.
            assertEquals(long, dao.ink(1))
            assertEquals(oldFormat, dao.ink(2))
            assertEquals(oneStroke, dao.ink(5))
            assertNull(dao.ink(3))
            assertNull(dao.ink(4))
            // La columna vieja queda vacía en todas: es lo que achica las filas de `pages`.
            (1L..5L).forEach { assertEquals("[]", dao.page(it)!!.inkJson) }
            // Lo demás de la página no se toca.
            val first = dao.page(1)!!
            assertEquals(PageStatus.REVIEWED, first.status)
            assertEquals("오늘은 바다에 갔어요.", first.confirmedText)
            assertEquals(1080, first.canvasWidth)
            assertEquals(2, first.reviewedLineCount)
            assertEquals("word:3", first.suggestedItems)
            assertEquals(PageKind.SCENE, dao.page(5)!!.kind)
            // La racha sale igual que antes: el 12 cuentan las dos del diario con tinta; el 13, ninguna
            // (dos vacías y una escena sin revisar).
            assertEquals(mapOf("2026-11-12" to 2), dao.pagesPerDay().associate { it.date to it.total })
        }
    }

    /**
     * Crea un archivo de base con el esquema exportado de [version] (como lo dejó esa versión de la
     * app), lo llena con [seed] y lo abre con Room en la versión actual.
     */
    private suspend fun withDatabaseFromSchema(version: Int, seed: SQLiteConnection.() -> Unit, check: suspend (AppDatabase) -> Unit) {
        val schema = JSONObject(File("schemas/com.jorge.koreandiary.data.db.AppDatabase/$version.json").readText())
            .getJSONObject("database")
        assertEquals(version, schema.getInt("version"))
        val dir = Files.createTempDirectory("koreandiary-migracion").toFile()
        val file = File(dir, AppDatabase.FILE_NAME)
        try {
            BundledSQLiteDriver().open(file.path).apply {
                try {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val e = entities.getJSONObject(i)
                        val table = e.getString("tableName")
                        execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = e.optJSONArray("indices")
                        if (indices != null) for (j in 0 until indices.length()) {
                            execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                        }
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) execSQL(setup.getString(i))
                    execSQL("PRAGMA user_version = $version")
                    seed()
                } finally {
                    close()
                }
            }

            val db = Room.databaseBuilder<AppDatabase>(file.absolutePath).setDriver(BundledSQLiteDriver()).build()
            try {
                check(db)
            } finally {
                db.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
