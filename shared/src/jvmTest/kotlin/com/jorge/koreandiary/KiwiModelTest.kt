package com.jorge.koreandiary

import com.jorge.koreandiary.kiwi.KiwiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

/** Sacar el modelo de Kiwi del .tgz oficial: solo los archivos que se usan, sin carpetas. */
class KiwiModelTest {

    /** Un .tgz como el oficial: models/cong/base/<archivo>, en formato ustar. */
    private fun tgz(files: Map<String, ByteArray>, withFolder: Boolean = true): ByteArray {
        val tar = ByteArrayOutputStream()
        fun header(name: String, size: Long, type: Char) {
            val h = ByteArray(512)
            name.toByteArray().copyInto(h, 0)
            "0000644\u0000".toByteArray().copyInto(h, 100)
            String.format("%011o\u0000", size).toByteArray().copyInto(h, 124)
            String.format("%011o\u0000", 0L).toByteArray().copyInto(h, 136)
            h[156] = type.code.toByte()
            "ustar\u000000".toByteArray().copyInto(h, 257)
            // La suma de control se calcula con su campo en espacios.
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            String.format("%06o\u0000 ", h.sumOf { it.toInt() and 0xFF }).toByteArray().copyInto(h, 148)
            tar.write(h)
        }
        if (withFolder) header("models/cong/base/", 0, '5')
        for ((name, data) in files) {
            header("models/cong/base/$name", data.size.toLong(), '0')
            tar.write(data)
            tar.write(ByteArray((512 - data.size % 512) % 512))
        }
        tar.write(ByteArray(1024))
        return ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(tar.toByteArray()) } }.toByteArray()
    }

    @Test
    fun sacaLosArchivosQueSeUsanYDejaMultiDict() {
        val files = KiwiModel.FILES.associateWith { name -> "contenido de $name".toByteArray() } + ("multi.dict" to ByteArray(700) { 7 })
        val dir = Files.createTempDirectory("kiwi").toFile()
        try {
            val written = KiwiModel.extract(ByteArrayInputStream(tgz(files)), dir)
            assertEquals(KiwiModel.FILES, dir.list()!!.toSet())
            assertFalse(File(dir, "multi.dict").exists())
            assertEquals("contenido de cong.mdl", File(dir, "cong.mdl").readText())
            assertEquals(KiwiModel.FILES.sumOf { "contenido de $it".length.toLong() }, written)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun siFaltaUnArchivoNoQuedaComoInstalado() {
        val files = (KiwiModel.FILES - "cong.mdl").associateWith { ByteArray(10) }
        val dir = Files.createTempDirectory("kiwi").toFile()
        try {
            KiwiModel.extract(ByteArrayInputStream(tgz(files)), dir)
            throw AssertionError("tenía que fallar")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("cong.mdl"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun laDireccionEsLaDelReleaseOficial() {
        assertEquals(
            "https://github.com/bab2min/Kiwi/releases/download/v0.25.0/kiwi_model_v0.25.0_base.tgz",
            KiwiModel.url(),
        )
    }
}
