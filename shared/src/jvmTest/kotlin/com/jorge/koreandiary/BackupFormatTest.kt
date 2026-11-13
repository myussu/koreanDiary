package com.jorge.koreandiary

import com.jorge.koreandiary.backup.BackupException
import com.jorge.koreandiary.backup.BackupFormat
import com.jorge.koreandiary.backup.BackupManifest
import com.jorge.koreandiary.backup.BackupStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class BackupFormatTest {
    private val lima = ZoneId.of("America/Lima")

    // 10 de noviembre de 2026, 20:15 en Lima.
    private val exportedAt = 1_794_359_700_000L

    private val manifest = BackupManifest(
        exportedAt = exportedAt,
        appVersionCode = 43,
        appVersionName = "0.1.43",
        dbVersion = 1,
        identityHash = "1667b8afb9a07f91e238aba217e1a4c2",
        pages = 37,
    )

    // ---------- Entradas del .zip ----------

    @Test
    fun knownEntriesAreExtractedWhereTheyAre() {
        listOf(
            "manifest.json",
            "settings.json",
            "db/korean_diary.db",
            "files/workbook/1731200000000/session.json",
            "files/workbook_photos/1731200000000/pagina.jpg",
            "files/conversation/1731200000000.json",
            "files/explanations/cache.json",
            "files/mascot/idle.png",
        ).forEach { assertEquals(it, BackupFormat.entryTarget(it)) }
    }

    @Test
    fun entriesThatLeaveTheStagingFolderAreIgnored() {
        listOf(
            "/data/data/com.jorge.koreandiary/databases/korean_diary.db",
            "../shared_prefs/secrets.xml",
            "files/workbook/../../shared_prefs/secrets.xml",
            "files/workbook/./session.json",
            "db/../settings.json",
            "files\\workbook\\1\\session.json",
            "files/workbook//session.json",
        ).forEach { assertNull(it, BackupFormat.entryTarget(it)) }
    }

    @Test
    fun entriesTheAppDoesNotKnowAreIgnored() {
        listOf(
            "",
            "files/",
            "files/workbook/",
            "files/workbook",
            "files/secrets/gemini.txt",
            "shared_prefs/secrets.xml",
            "db/korean_diary.db-wal",
            "db/otra.db",
            "files/workbook/a/b/c/d.json",
            "leeme.txt",
        ).forEach { assertNull(it, BackupFormat.entryTarget(it)) }
    }

    // ---------- Manifiesto ----------

    @Test
    fun manifestSurvivesTheRoundTrip() {
        assertEquals(manifest, BackupFormat.parseManifest(BackupFormat.manifestToJson(manifest)))
    }

    @Test
    fun filesThatAreNotACopyAreRejected() {
        listOf(
            "esto no es json",
            "{}",
            """{"app":"otra.app","format":1}""",
            """{"app":"com.jorge.koreandiary","format":0}""",
        ).forEach { text ->
            assertThrows(text, BackupException::class.java) { BackupFormat.parseManifest(text) }
        }
    }

    // ---------- ¿Se puede abrir con la app instalada? ----------

    @Test
    fun copyFromTheSameSchemaCanBeImported() {
        assertNull(BackupFormat.importProblem(manifest, liveVersion = 1, liveHash = manifest.identityHash))
    }

    @Test
    fun copyFromAnOlderSchemaCanBeImported() {
        // Room la migra al abrirla; su hash es otro porque el esquema era otro.
        assertNull(BackupFormat.importProblem(manifest, liveVersion = 3, liveHash = "otro"))
    }

    @Test
    fun copyFromANewerAppIsRejected() {
        val newerSchema = BackupFormat.importProblem(manifest.copy(dbVersion = 2), liveVersion = 1, liveHash = "x")
        val newerFormat = BackupFormat.importProblem(manifest.copy(format = BackupFormat.VERSION + 1), liveVersion = 1, liveHash = manifest.identityHash)
        assertTrue(newerSchema.orEmpty().contains("más nueva"))
        assertTrue(newerFormat.orEmpty().contains("más nueva"))
    }

    @Test
    fun sameVersionWithAnotherSchemaIsRejected() {
        assertNotNull(BackupFormat.importProblem(manifest, liveVersion = 1, liveHash = "otro"))
    }

    @Test
    fun copyWithoutASchemaVersionIsRejected() {
        assertNotNull(BackupFormat.importProblem(manifest.copy(dbVersion = 0), liveVersion = 1, liveHash = manifest.identityHash))
    }

    // ---------- Ajustes ----------

    @Test
    fun settingsKeepTheirTypes() {
        val settings = mapOf(
            "profile" to "Estudio en Lima. 한국어를 공부해요.",
            "stylusOnly" to true,
            "hideOnboarding" to false,
            "geminiDailyLimit" to 30,
            "ultimaVez" to 5_000_000_000L,
            "listenSpeed" to 0.9f,
            "etiquetas" to setOf("a", "b"),
        )
        // Pasa por texto, como al escribirse en el .zip y volver a leerse.
        val restored = BackupFormat.decodeSettings(JSONObject(BackupFormat.encodeSettings(settings).toString()))
        assertEquals(settings, restored)
        assertTrue(restored["geminiDailyLimit"] is Int)
        assertTrue(restored["ultimaVez"] is Long)
        assertTrue(restored["listenSpeed"] is Float)
    }

    @Test
    fun settingsThatCannotBeStoredAreSkipped() {
        val encoded = BackupFormat.encodeSettings(mapOf("ok" to 1, "lista" to listOf(1, 2), "nan" to Float.NaN, "nulo" to null))
        assertEquals(setOf("ok"), encoded.keys().asSequence().toSet())
    }

    @Test
    fun brokenSettingsAreSkippedOneByOne() {
        val json = JSONObject("""{"a":{"t":"i","v":"hola"},"b":{"t":"s","v":"bien"},"c":"suelto","d":{"t":"?","v":1},"e":{"t":"b"}}""")
        assertEquals(mapOf("b" to "bien"), BackupFormat.decodeSettings(json))
    }

    // ---------- Estado de la copia de Google ----------

    @Test
    fun quotaWarningFollowsTheLastThingThatHappened() {
        assertFalse(BackupStatus().overQuota)
        // Pasada (mide) y rechazo: sigue sin subir.
        assertTrue(BackupStatus(lastFullBackupAt = 100, quotaExceededAt = 200).overQuota)
        assertTrue(BackupStatus(lastFullBackupAt = 200, quotaExceededAt = 200).overQuota)
        // Una pasada posterior sin rechazo: volvió a caber.
        assertFalse(BackupStatus(lastFullBackupAt = 300, quotaExceededAt = 200).overQuota)
    }

    @Test
    fun homeWarnsOnlyWithoutARecentFile() {
        val day = 24L * 60 * 60 * 1000
        val over = BackupStatus(lastFullBackupAt = 100, quotaExceededAt = 200)
        assertTrue(over.needsAttention(exportedAt))
        assertTrue(over.copy(lastExportAt = exportedAt - 8 * day).needsAttention(exportedAt))
        assertFalse(over.copy(lastExportAt = exportedAt - day).needsAttention(exportedAt))
        assertFalse(BackupStatus(lastExportAt = 0).needsAttention(exportedAt))
    }

    // ---------- Textos ----------

    @Test
    fun labels() {
        assertEquals("korean-diary-2026-11-10-2015.zip", BackupFormat.fileName(exportedAt, lima))
        assertEquals("10 de noviembre de 2026, 20:15", BackupFormat.dateLabel(exportedAt, lima))
        assertEquals("3 de enero de 2026, 07:05", BackupFormat.dateLabel(1_767_441_900_000L, lima))
        assertEquals("30,0 MB", BackupFormat.sizeLabel(31_500_000))
        assertEquals("25,0 MB", BackupFormat.sizeLabel(26_214_400))
        assertEquals("489 KB", BackupFormat.sizeLabel(500_000))
        assertEquals("0 KB", BackupFormat.sizeLabel(0))
        assertEquals("1 página", BackupFormat.pagesLabel(1))
        assertEquals("37 páginas", BackupFormat.pagesLabel(37))
    }

    // ---------- Copias de antes de actualizar ----------

    @Test
    fun autoBackupNameSaysTheVersionAndWhen() {
        val at = java.time.ZonedDateTime.of(2026, 11, 13, 18, 33, 0, 0, ZoneId.of("America/Lima")).toInstant().toEpochMilli()
        val name = BackupFormat.autoBackupName("0.1.65", at, ZoneId.of("America/Lima"))
        assertEquals("koreandiary-antes-de-0.1.65-20261113-1833.zip", name)
        assertTrue(BackupFormat.isAutoBackup(name))
        // Caracteres raros de la versión no llegan al nombre del archivo.
        assertEquals("koreandiary-antes-de-0.1.65beta-20261113-1833.zip", BackupFormat.autoBackupName("0.1.65 beta/", at, ZoneId.of("America/Lima")))
        assertTrue(BackupFormat.isAutoBackup("koreandiary-antes-de-0.1.65-20261113-1833 (1).zip"))
        assertFalse(BackupFormat.isAutoBackup("koreandiary-2026-11-13.zip"))
        assertFalse(BackupFormat.isAutoBackup("koreandiary-antes-de-0.1.65-20261113-1833.zip.tmp"))
    }

    @Test
    fun keepsTheThreeNewestAutoBackupsAndIgnoresOtherFiles() {
        val names = listOf(
            "koreandiary-antes-de-0.1.60-20261110-0900.zip",
            "koreandiary-antes-de-0.1.62-20261111-1200.zip",
            "mi-copia-manual.zip",
            "koreandiary-antes-de-0.1.64-20261113-1800.zip",
            "koreandiary-antes-de-0.1.61-20261110-2300.zip",
            "koreandiary-antes-de-0.1.65-20261113-1833.zip",
        )
        assertEquals(
            listOf("koreandiary-antes-de-0.1.61-20261110-2300.zip", "koreandiary-antes-de-0.1.60-20261110-0900.zip"),
            BackupFormat.autoBackupsToDelete(names),
        )
        assertTrue(BackupFormat.autoBackupsToDelete(names.take(3)).isEmpty())
    }

    @Test
    fun statusKeepsItsDefaultsForTheAutoFolder() {
        val s = BackupStatus()
        assertEquals("", s.autoFolder)
        assertEquals(0L, s.lastAutoAt)
    }
}
