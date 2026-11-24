package com.jorge.koreandiary.pronunciation

import com.jorge.koreandiary.dictionary.DictEntry
import com.jorge.koreandiary.dictionary.Hangul
import com.jorge.koreandiary.dictionary.KoreanDictionary

/**
 * Las palabras del diccionario que suenan como algo mal escrito (pedido por Jorge el 2026-11-24, para
 * «Por revisar»): 설겆이 → 설거지, 금새 → 금세, 몇일 → 며칠 suenan igual; 괜찬다 → 괜찮다 casi (sin la ㅎ
 * que vuelve 다 en [타]). Quien aprende de oído escribe como suena; si eso no está en el diccionario,
 * la palabra que suena igual o casi casi siempre es la que quiso escribir.
 *
 * Se probó también el corrector de faltas de Kiwi (2026-11-24): con palabras sueltas encontró pocas
 * (맛잇다 sí; 괜찬다, 몰르다, 설겆이, 몇일 no), así que va esto, que encuentra esas y más.
 */
class SoundAlike(private val dictionary: KoreanDictionary) {

    /** [same]: suena igual; si no, casi (solo cambia una aspirada: ㅌ/ㄷ, ㅊ/ㅈ, ㅋ/ㄱ, ㅍ/ㅂ). */
    data class Suggestion(val entry: DictEntry, val same: Boolean)

    private class Bucket(val exact: Map<String, List<DictEntry>>, val near: Map<String, List<DictEntry>>)

    /** Por cantidad de sílabas: se arma la primera vez que se pide (unos segundos con todo el diccionario). */
    private val buckets = HashMap<Int, Bucket>()

    /** Lo que suena como [written] (como mucho [max], primero lo que suena igual y lo común); vacío si [written] ya existe. */
    fun suggestions(written: String, max: Int = 3): List<Suggestion> {
        val w = written.trim()
        if (w.length < 2 || !w.all(Hangul::isSyllable) || dictionary.isWord(w)) return emptyList()
        val key = Pronunciation.key(w)
        if (key.isEmpty()) return emptyList()
        val bucket = synchronized(buckets) { buckets.getOrPut(w.length) { build(w.length) } }
        val same = bucket.exact[key].orEmpty().map { Suggestion(it, true) }
        // Casi igual solo con palabras comunes (niveles 1 a 3): con todas saldría cualquier cosa.
        val near = bucket.near[loose(key)].orEmpty().filter { it.level in 1..3 }.map { Suggestion(it, false) }
        return (same + near)
            .filter { it.entry.form != w }
            .sortedWith(compareBy({ !it.same }, { if (it.entry.level == 0) 9 else it.entry.level }))
            .distinctBy { it.entry.form }
            .take(max)
    }

    private fun build(length: Int): Bucket {
        val words = dictionary.allEntries().filter { !it.grammar && it.form.length == length && it.form.all(Hangul::isSyllable) }
        val keys = words.associateWith { Pronunciation.key(it.form) }
        return Bucket(words.groupBy { keys.getValue(it) }, words.groupBy { loose(keys.getValue(it)) })
    }

    /** La clave sin la diferencia entre aspiradas y simples al comienzo de sílaba. */
    private fun loose(key: String): String = key.map { c ->
        val p = Hangul.parts(c) ?: return@map c
        val plain = when (p.initialJamo) {
            'ㅋ' -> 'ㄱ'
            'ㅌ' -> 'ㄷ'
            'ㅍ' -> 'ㅂ'
            'ㅊ' -> 'ㅈ'
            else -> p.initialJamo
        }
        Hangul.syllable(Hangul.INITIALS.indexOf(plain), p.vowel, p.final)
    }.joinToString("")
}
