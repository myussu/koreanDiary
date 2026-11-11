package com.jorge.koreandiary.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.jorge.koreandiary.backup.BackupException
import com.jorge.koreandiary.backup.BackupFormat
import com.jorge.koreandiary.backup.BackupManifest
import com.jorge.koreandiary.data.db.AppDatabase
import com.jorge.koreandiary.data.db.DiaryEntry
import com.jorge.koreandiary.data.db.DiaryPage
import com.jorge.koreandiary.data.db.PageStatus
import com.jorge.koreandiary.desktop.ui.DiaryApp
import com.jorge.koreandiary.desktop.ui.KoreanDiaryTheme
import com.jorge.koreandiary.ink.InkPoint
import com.jorge.koreandiary.ink.InkSerializer
import com.jorge.koreandiary.ink.InkStroke
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.sin

/**
 * Prueba de humo en la JVM de escritorio (en Actions corre en Windows): la base compartida abre
 * con el SQLite nativo, la copia del celular se importa y la ventana muestra la página. Deja
 * capturas en build/screens para revisarlas a ojo.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopSmokeTest {

    private val text = "오늘은 친구를 만났어요"

    @Before
    fun clean() {
        DesktopPaths.dataDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    @Test
    fun importaLaCopiaDelCelular() {
        val phoneDb = File(DesktopPaths.dataDir, "celular/${AppDatabase.FILE_NAME}").apply { parentFile.mkdirs() }
        seed(phoneDb)
        val zip = File(DesktopPaths.dataDir, "copia.zip")
        writeBackup(zip, phoneDb, mapOf("files/workbook/s1/session.json" to "{}"))

        val result = BackupImporter.prepare(zip)
        assertEquals(1, result.pages)
        BackupImporter.apply()

        val db = openDatabase()
        try {
            runBlocking {
                assertEquals(1, db.diaryDao().pageCount())
                assertEquals(text, db.diaryDao().page(1)?.confirmedText)
            }
        } finally {
            db.close()
        }
        assertTrue(File(DesktopPaths.files, "workbook/s1/session.json").isFile)
        assertTrue("La carpeta de preparación queda limpia", !DesktopPaths.staging.exists())
    }

    @Test
    fun rechazaUnZipQueNoEsCopia() {
        val zip = File(DesktopPaths.dataDir, "otro.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("hola.txt"))
            z.write("hola".toByteArray())
            z.closeEntry()
        }
        try {
            BackupImporter.prepare(zip)
            fail("Debió rechazar el archivo")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("no es una copia"))
        }
    }

    @Test
    fun ventanaConUnaPagina() {
        seed(DesktopPaths.database)
        for (dark in listOf(false, true)) {
            runComposeUiTest {
                setContent { KoreanDiaryTheme(dark = dark) { DiaryApp(chooseZip = { null }) } }
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithText(text)[0].performClick()
                // Al abrirla, el texto aparece también en el detalle.
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithText(text).fetchSemanticsNodes().size >= 2 }
                waitForIdle()
                screenshot(if (dark) "diario-oscuro" else "diario-claro")
            }
        }
    }

    // ---------- Ayudas ----------

    private fun androidx.compose.ui.test.ComposeUiTest.screenshot(name: String) {
        val dir = File(System.getProperty("koreandiary.screens") ?: "build/screens").apply { mkdirs() }
        val image = onRoot().captureToImage().toAwtImage()
        ImageIO.write(image, "png", File(dir, "$name.png"))
    }

    /** Una página con tinta parecida a la del celular: círculos (ㅇ) y trazos rectos. */
    private fun seed(file: File) {
        val width = 1000f
        val strokes = buildList {
            var x = 60f
            repeat(6) { i ->
                add(circle(x, 80f, 26f, t0 = i * 1000L))
                add(line(x - 34f, 130f, x + 34f, 130f, t0 = i * 1000L + 300))
                add(line(x, 130f, x, 175f, t0 = i * 1000L + 500))
                x += 110f
            }
            add(line(40f, 250f, 700f, 262f, t0 = 9000))
        }
        val db = openDatabase(file)
        try {
            runBlocking {
                val now = System.currentTimeMillis()
                val entryId = db.diaryDao().insertEntry(DiaryEntry(date = "2026-11-11", createdAt = now))
                db.diaryDao().insertPage(
                    DiaryPage(
                        entryId = entryId,
                        inkJson = InkSerializer.toJson(strokes, width),
                        canvasWidth = width.toInt(),
                        recognizedText = text,
                        confirmedText = text,
                        status = PageStatus.REVIEWED,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
        } finally {
            db.close()
        }
    }

    private fun circle(cx: Float, cy: Float, r: Float, t0: Long) = InkStroke(
        (0..36).map { k ->
            val a = k * Math.PI * 2 / 36
            InkPoint((cx + r * cos(a)).toFloat(), (cy + r * sin(a)).toFloat(), t0 + k * 10, 0.4f + 0.3f * (k % 6) / 6f)
        },
    )

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, t0: Long) = InkStroke(
        (0..12).map { k ->
            val f = k / 12f
            InkPoint(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, t0 + k * 10, 0.3f + 0.5f * f)
        },
    )

    private fun writeBackup(zip: File, db: File, extra: Map<String, String>) {
        val manifest = BackupManifest(
            exportedAt = System.currentTimeMillis(),
            appVersionCode = 1,
            appVersionName = "0.1.1",
            dbVersion = AppDatabase.VERSION,
            identityHash = "",
            pages = 1,
        )
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry(BackupFormat.MANIFEST))
            z.write(BackupFormat.manifestToJson(manifest).toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry(BackupFormat.DATABASE))
            db.inputStream().use { it.copyTo(z) }
            z.closeEntry()
            for ((name, content) in extra) {
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray())
                z.closeEntry()
            }
        }
    }
}
