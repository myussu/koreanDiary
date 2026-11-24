package com.jorge.koreandiary.kiwi

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * El modelo de Kiwi (analizador de oraciones, decidido con Jorge el 2026-11-24): no va dentro del
 * APK (cada actualización bajaría ~84 MB más), se baja una vez del release oficial y se guarda fuera
 * de la copia de seguridad (se puede volver a bajar). [VERSION] es la de `kiwi` en
 * gradle/libs.versions.toml; si cambia, cambian también [SHA256] y [SIZE] (lo comprueba un test de :app).
 */
object KiwiModel {
    const val VERSION = "0.25.0"

    /** El .tgz oficial de esa versión, tal como lo publica github.com/bab2min/Kiwi. */
    const val SHA256 = "2b32096ce9f48da6aaaf486db01ff46834e7e3dcb92f507879d35e3d2180a2dc"
    const val SIZE = 87_967_198L

    fun url(version: String = VERSION): String =
        "https://github.com/bab2min/Kiwi/releases/download/v$version/kiwi_model_v${version}_base.tgz"

    /**
     * Los archivos que se usan. multi.dict (expresiones de varias palabras, 12 MB) no: en la prueba
     * no cambió ningún resultado y suma memoria.
     */
    val FILES = setOf(
        "combiningRule.txt", "cong.mdl", "contraction.dict", "default.dict", "dialect.dict",
        "extract.mdl", "nounchr.mdl", "sj.morph", "typo.dict",
    )

    /**
     * Saca de [tgz] los [FILES] (sin carpetas) a [dir], que se crea. Lanza si el archivo está roto o
     * falta alguno. Devuelve cuántos bytes escribió.
     */
    fun extract(tgz: InputStream, dir: File): Long {
        dir.mkdirs()
        var written = 0L
        val found = mutableSetOf<String>()
        val input = DataInputStream(GZIPInputStream(tgz.buffered(), 1 shl 16))
        val header = ByteArray(BLOCK)
        while (true) {
            if (!readBlock(input, header)) break
            // Dos bloques vacíos marcan el final.
            if (header.all { it == 0.toByte() }) break
            val name = text(header, 0, 100).let { n ->
                val prefix = if (text(header, 257, 6).startsWith("ustar")) text(header, 345, 155) else ""
                if (prefix.isEmpty()) n else "$prefix/$n"
            }
            val size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()
            val base = name.substringAfterLast('/')
            val keep = (type == '0' || type == '\u0000') && base in FILES
            if (keep) {
                File(dir, base).outputStream().buffered().use { out -> copy(input, out, size) }
                found += base
                written += size
            } else {
                skip(input, size)
            }
            // Los datos van rellenos hasta completar bloques de 512.
            val pad = (BLOCK - size % BLOCK) % BLOCK
            skip(input, pad)
        }
        val missing = FILES - found
        if (missing.isNotEmpty()) throw IOException("Al modelo le faltan archivos: ${missing.sorted().joinToString()}")
        return written
    }

    private const val BLOCK = 512

    private fun readBlock(input: DataInputStream, block: ByteArray): Boolean = try {
        input.readFully(block)
        true
    } catch (e: EOFException) {
        false
    }

    private fun text(b: ByteArray, from: Int, length: Int): String {
        var end = from
        while (end < from + length && b[end] != 0.toByte()) end++
        return String(b, from, end - from, Charsets.UTF_8).trim()
    }

    private fun octal(b: ByteArray, from: Int, length: Int): Long {
        val s = text(b, from, length).trim()
        return if (s.isEmpty()) 0L else s.toLong(8)
    }

    private fun copy(input: InputStream, out: java.io.OutputStream, size: Long) {
        val buffer = ByteArray(1 shl 16)
        var left = size
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) throw IOException("El modelo se cortó")
            out.write(buffer, 0, n)
            left -= n
        }
    }

    private fun skip(input: InputStream, size: Long) {
        var left = size
        val buffer = ByteArray(1 shl 16)
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) throw IOException("El modelo se cortó")
            left -= n
        }
    }
}
