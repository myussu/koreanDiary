package com.jorge.koreandiary.kiwi

import com.jorge.koreandiary.dictionary.Morph
import com.jorge.koreandiary.dictionary.SentenceAnalyzer
import java.io.File
import kr.pe.bab2min.Kiwi
import kr.pe.bab2min.KiwiBuilder

/**
 * Kiwi detrás de [SentenceAnalyzer], con las mismas opciones que la prueba (sin multi.dict, el
 * análisis por defecto de kiwipiepy). Una llamada a la vez: Kiwi es nativo y se suelta con [close].
 */
class KiwiAnalyzer private constructor(private val kiwi: Kiwi) : SentenceAnalyzer, AutoCloseable {
    private val lock = Any()
    private var closed = false
    private val option = Kiwi.AnalyzeOption(Kiwi.Match.all)

    override fun analyze(sentence: String): List<Morph> = synchronized(lock) {
        check(!closed) { "Kiwi ya se soltó" }
        kiwi.tokenize(sentence, option).map { t ->
            Morph(t.form, tagName(t.tag), t.position, t.length.toInt(), irregular = (t.tag.toInt() and IRREGULAR) != 0)
        }
    }

    override fun join(pieces: List<Morph>): String? = synchronized(lock) {
        if (closed || pieces.isEmpty()) return null
        val tokens = pieces.map { m -> Kiwi.JoinableToken(m.form, tagByte(m.tag, m.irregular), false) }.toTypedArray()
        runCatching { kiwi.join(tokens) }.getOrNull()
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            runCatching { kiwi.close() }
        }
        Unit
    }

    companion object {
        private const val IRREGULAR = 0x80
        private val OPTIONS = KiwiBuilder.BuildOption.integrateAllomorph or
            KiwiBuilder.BuildOption.loadDefaultDict or KiwiBuilder.BuildOption.loadTypoDict

        /** Carga el modelo de [dir] (~1 s en el Galaxy, ~+230 MB de memoria mientras está cargado). */
        fun load(dir: File): KiwiAnalyzer {
            val builder = KiwiBuilder(dir.absolutePath, 1, OPTIONS, KiwiBuilder.ModelType.cong)
            try {
                return KiwiAnalyzer(builder.build())
            } finally {
                // El constructor guarda otra copia del diccionario; Kiwi ya no lo necesita (~60 MB menos).
                runCatching { builder.close() }
            }
        }

        /** La etiqueta sin la marca de irregular: VV-I → VV. */
        fun tagName(tag: Byte): String = runCatching { Kiwi.POSTag.toString((tag.toInt() and 0x7F).toByte()) }.getOrNull() ?: "UNK"

        private val BYTES: Map<String, Byte> by lazy {
            (0 until 0x7F).mapNotNull { i -> runCatching { Kiwi.POSTag.toString(i.toByte()) }.getOrNull()?.let { it to i.toByte() } }.toMap()
        }

        fun tagByte(tag: String, irregular: Boolean): Byte = ((BYTES[tag]?.toInt() ?: 0) or (if (irregular) IRREGULAR else 0)).toByte()
    }
}
