package com.jorge.koreandiary

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.jorge.koreandiary.data.db.AppDatabase
import com.jorge.koreandiary.data.db.Correction
import com.jorge.koreandiary.data.db.CorrectionStatus
import com.jorge.koreandiary.data.db.DiaryEntry
import com.jorge.koreandiary.data.db.DiaryPage
import com.jorge.koreandiary.data.db.PageKind
import com.jorge.koreandiary.data.db.PageStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * La base real (Room con el mismo SQLite que la app): qué páginas cuentan para la racha y que una
 * base de la versión 1, como la del celular, abre con la versión actual sin perder páginas.
 */
class PageKindDatabaseTest {

    private fun inMemory(): AppDatabase =
        Room.inMemoryDatabaseBuilder<AppDatabase>().setDriver(BundledSQLiteDriver()).build()

    @Test
    fun scenesCountForTheStreakOnlyWhenReviewedWithNothingPending() = runBlocking {
        val db = inMemory()
        try {
            val dao = db.diaryDao()
            val day = dao.insertEntry(DiaryEntry(date = "2026-11-11", createdAt = 0))
            val otherDay = dao.insertEntry(DiaryEntry(date = "2026-11-10", createdAt = 0))

            fun page(entry: Long, kind: String, status: String) =
                DiaryPage(entryId = entry, status = status, createdAt = 0, updatedAt = 0, kind = kind)

            // Día 11: un borrador del diario (cuenta, como siempre) y cuatro escenas.
            dao.insertPage(page(day, PageKind.DIARY, PageStatus.DRAFT))
            dao.insertPage(page(day, PageKind.SCENE, PageStatus.DRAFT))
            dao.insertPage(page(day, PageKind.SCENE, PageStatus.PENDING))
            val withPending = dao.insertPage(page(day, PageKind.SCENE, PageStatus.REVIEWED))
            val allRewritten = dao.insertPage(page(day, PageKind.SCENE, PageStatus.REVIEWED))
            // Día 10: solo una escena revisada sin correcciones.
            dao.insertPage(page(otherDay, PageKind.SCENE, PageStatus.REVIEWED))

            fun correction(pageId: Long, status: String) = Correction(
                pageId = pageId, sentence = 1, original = "학교를", suggestion = "학교에", type = "particula",
                explanation = "", tense = null, itemType = null, itemId = null, status = status,
            )
            dao.insertCorrection(correction(withPending, CorrectionStatus.PENDING))
            dao.insertCorrection(correction(withPending, CorrectionStatus.REWRITTEN))
            dao.insertCorrection(correction(allRewritten, CorrectionStatus.REWRITTEN))
            // Una mejora no exige reescribir: no frena la escena.
            dao.insertCorrection(correction(allRewritten, CorrectionStatus.SUGGESTION))

            val perDay = dao.pagesPerDay().associate { it.date to it.total }
            assertEquals(mapOf("2026-11-11" to 2, "2026-11-10" to 1), perDay)
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
    fun summariesCarryTheKind() = runBlocking {
        val db = inMemory()
        try {
            val dao = db.diaryDao()
            val day = dao.insertEntry(DiaryEntry(date = "2026-11-11", createdAt = 0))
            dao.insertPage(DiaryPage(entryId = day, createdAt = 1, updatedAt = 1))
            dao.insertPage(DiaryPage(entryId = day, createdAt = 2, updatedAt = 2, kind = PageKind.SCENE))
            val kinds = dao.observePageSummaries().first().map { it.kind }
            assertEquals(listOf(PageKind.SCENE, PageKind.DIARY), kinds)
        } finally {
            db.close()
        }
    }

    /** Arma una base con el esquema 1.json (el que tiene hoy el celular) y la abre con la versión actual. */
    @Test
    fun versionOneDatabaseOpensWithItsPagesAsDiary() = runBlocking {
        val schema = JSONObject(File("schemas/com.jorge.koreandiary.data.db.AppDatabase/1.json").readText())
            .getJSONObject("database")
        assertEquals(1, schema.getInt("version"))
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
                    execSQL("PRAGMA user_version = 1")
                    execSQL("INSERT INTO entries (id, date, createdAt) VALUES (1, '2026-11-09', 0)")
                    execSQL(
                        "INSERT INTO pages (id, entryId, inkJson, canvasWidth, status, reviewedLineCount, suggestedItems, createdAt, updatedAt) " +
                            "VALUES (7, 1, '[]', 1080, '${PageStatus.REVIEWED}', 3, '', 0, 0)"
                    )
                } finally {
                    close()
                }
            }

            val db = Room.databaseBuilder<AppDatabase>(file.absolutePath).setDriver(BundledSQLiteDriver()).build()
            try {
                val page = db.diaryDao().page(7)!!
                assertEquals(PageKind.DIARY, page.kind)
                assertEquals(3, page.reviewedLineCount)
                assertEquals(1, db.diaryDao().pageCountForDate("2026-11-09"))
            } finally {
                db.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
