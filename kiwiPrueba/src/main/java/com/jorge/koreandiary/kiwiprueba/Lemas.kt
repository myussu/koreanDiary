package com.jorge.koreandiary.kiwiprueba

import kr.pe.bab2min.Kiwi
import kr.pe.bab2min.Kiwi.JoinableToken
import kr.pe.bab2min.Kiwi.Token

/**
 * Las dos maneras de sacar la forma de diccionario de una palabra que se compararon en el servidor
 * (kiwi_eval.py e hybrid.py de la prueba, ver claude/kiwi-prueba.md), pasadas tal cual a Kotlin para
 * ver si en el celular dan lo mismo.
 */
object Lemas {
    private val VERBOS = setOf("VV", "VA", "VX", "VCN", "VCP")
    private val SUSTANTIVOS = setOf("NNG", "NNP", "NNB", "NR", "NP")
    private val OTROS = setOf("MAG", "MAJ", "MM", "XR", "IC")
    private val VERBALES = setOf("VV", "VA", "VX", "XSV", "XSA", "VCN")
    private val NOMINALES = SUSTANTIVOS + setOf("XSN", "XR", "MAG", "MAJ", "MM", "IC")
    // Partículas, terminaciones finales y signos: la palabra de diccionario termina antes.
    private val CORTE = listOf("J", "EF", "SF", "SP", "SS", "SE", "SO", "SW", "SL", "SH", "SN")

    /** Etiqueta sin la marca de irregular (VV-I → VV). */
    fun etiqueta(t: Token): String = Kiwi.POSTag.toString((t.tag.toInt() and 0x7F).toByte()) ?: "?"

    /** Etiqueta como la muestra Kiwi, con la marca de irregular. */
    fun etiquetaCompleta(t: Token): String = Kiwi.POSTag.toString(t.tag) ?: etiqueta(t)

    /** Las piezas de Kiwi que caen dentro de la palabra [inicio, fin) de la oración. */
    fun piezas(tokens: Array<Token>, inicio: Int, fin: Int): List<Token> =
        tokens.filter { it.position < fin && it.position + it.length > inicio }

    /** Solo Kiwi: la primera pieza verbal + 다, o el sustantivo (los compuestos que Kiwi parte, juntos). */
    fun soloKiwi(tokens: Array<Token>, inicio: Int, fin: Int): String {
        val ms = piezas(tokens, inicio, fin)
        if (ms.isEmpty()) return ""
        for ((i, m) in ms.withIndex()) {
            val tag = etiqueta(m)
            // 공부 + 하 (XSV) → 공부하다; 깨끗 + 하 (XSA) → 깨끗하다
            if ((tag == "XSV" || tag == "XSA") && i > 0 && etiqueta(ms[i - 1]) in SUSTANTIVOS + "XR") {
                return ms[i - 1].form + m.form + "다"
            }
            // 먹 + 어 + 보 (VX): la palabra es el primer verbo
            if (tag in VERBOS) return m.form + "다"
        }
        for (m in ms) {
            val tag = etiqueta(m)
            if (tag in SUSTANTIVOS || tag in OTROS) {
                // Sustantivos compuestos que Kiwi parte: 은 + 메달 → 은메달
                val nouns = ms.filter { etiqueta(it) in SUSTANTIVOS }
                if (tag in SUSTANTIVOS && nouns.size > 1) return nouns.joinToString("") { it.form }
                return m.form
            }
        }
        return ms[0].form
    }

    /**
     * Kiwi + diccionario: de las piezas de Kiwi, la palabra más larga que el diccionario tiene
     * (일반 + 적 → 일반적, 늦 + 어 + 지 → 늦어지다, 맵 + 은 + 맛 → 매운맛), rearmada con [Kiwi.join].
     */
    fun conDiccionario(kiwi: Kiwi, tokens: Array<Token>, inicio: Int, fin: Int, formas: Set<String>): String {
        val ms = piezas(tokens, inicio, fin)
        for (j in ms.size downTo 1) {
            val head = ms.subList(0, j)
            if (head.any { t -> CORTE.any { etiqueta(t).startsWith(it) } }) continue
            val ultima = etiqueta(head.last())
            val partes = head.map { JoinableToken(it) }
            val candidata = try {
                when (ultima) {
                    in VERBALES -> kiwi.join((partes + JoinableToken("다", Kiwi.POSTag.ef)).toTypedArray())
                    in NOMINALES -> kiwi.join(partes.toTypedArray())
                    else -> continue
                }
            } catch (e: Exception) {
                continue
            }.replace(" ", "")
            if (candidata in formas) return candidata
        }
        return ""
    }
}
