package com.jorge.koreandiary.srs

import com.jorge.koreandiary.data.db.GrammarPoint
import com.jorge.koreandiary.data.db.ItemType
import com.jorge.koreandiary.data.db.MemoryDao
import com.jorge.koreandiary.data.db.MemoryState
import com.jorge.koreandiary.data.db.StudyDao
import com.jorge.koreandiary.data.db.Word
import com.jorge.koreandiary.data.standard.GrammarKeys
import com.jorge.koreandiary.data.standard.StandardCatalog
import com.jorge.koreandiary.data.standard.StdGrammar
import com.jorge.koreandiary.data.standard.StdWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Suggestions(
    val words: List<Word>,
    val grammar: List<GrammarPoint>,
    /** Posibles lagunas: del estándar, de tu nivel o del anterior, que no están en tu base. */
    val gapWords: List<StdWord> = emptyList(),
    val gapGrammar: List<StdGrammar> = emptyList(),
) {
    val isEmpty get() = words.isEmpty() && grammar.isEmpty() && gapWords.isEmpty() && gapGrammar.isEmpty()

    /** Formato guardado en DiaryPage.suggestedItems: "word:12,grammar:3,stdword:465,stdgrammar:12". */
    fun encode(): String =
        (words.map { "${ItemType.WORD}:${it.id}" } + grammar.map { "${ItemType.GRAMMAR}:${it.id}" } +
            gapWords.map { "${ItemType.STD_WORD}:${it.id}" } + gapGrammar.map { "${ItemType.STD_GRAMMAR}:${it.id}" })
            .joinToString(",")
}

/**
 * Elige qué sugerir al abrir una página, sin IA: solo con el estado FSRS.
 *
 * Prioridad:
 *  1. Ítems vencidos (ya pasó su fecha ideal), primero los de menor probabilidad de recordar.
 *  2. Algunos ítems nunca vistos, para ir sumando de a poco.
 *  3. Si falta, los que están más cerca de vencer.
 * Se excluye lo repasado en las últimas 12 horas.
 */
class SuggestionEngine(
    private val studyDao: StudyDao,
    private val memoryDao: MemoryDao,
    private val catalog: StandardCatalog,
) {
    suspend fun suggest(
        now: Long,
        grammarLevels: List<String>,
        /** Nivel 1–6 del estándar para buscar lagunas. */
        standardLevel: Int,
        wordCount: Int = 6,
        grammarCount: Int = 2,
        newWordsPerPage: Int = 2,
        gapWordCount: Int = 1,
        gapGrammarCount: Int = 1,
    ): Suggestions {
        val wordStates = memoryDao.allOfType(ItemType.WORD).associateBy { it.itemId }
        val wordIds = pick(studyDao.allWordIds(), wordStates, now, wordCount, newWordsPerPage)
        val words = balanceByType(studyDao.wordsByIds(wordIds), wordIds)

        val grammarStates = memoryDao.allOfType(ItemType.GRAMMAR).associateBy { it.itemId }
        val grammarPool = studyDao.grammarIdsForLevels(grammarLevels)
        val grammarIds = pick(grammarPool, grammarStates, now, grammarCount, newPerPage = 1)
        val grammar = studyDao.grammarByIds(grammarIds).sortedBy { grammarIds.indexOf(it.id) }

        val myWordForms = studyDao.allWordForms().toSet()
        val myGrammar = studyDao.allGrammar()
        val (gapWords, gapGrammar) = withContext(Dispatchers.Default) {
            findGaps(myWordForms, myGrammar, standardLevel, gapWordCount, gapGrammarCount)
        }
        return Suggestions(words, grammar, gapWords, gapGrammar)
    }

    /**
     * Gramáticas nuevas para una escena: un paso por encima de lo que ya sabes (ver [pickNewGrammar]).
     * [exclude]: ids del catálogo que ya salen como sugerencia en la misma página.
     */
    suspend fun newGrammarForScene(standardLevel: Int, count: Int = SCENE_NEW_GRAMMAR, exclude: Set<Int> = emptySet()): List<StdGrammar> {
        val myGrammar = studyDao.allGrammar()
        return withContext(Dispatchers.Default) {
            val myKeys = myGrammar.flatMap { GrammarKeys.keys(listOf(it.pattern) + it.variants.split(" | ")) }.toSet()
            pickNewGrammar(catalog.grammar, myKeys, standardLevel, count, exclude)
        }
    }

    /**
     * Lagunas: ítems del estándar de tu nivel o del anterior que no están en tu base ni has usado.
     * Es el "punto medio": ni tan básicos como para sabértelos de memoria, ni por encima de tu nivel.
     */
    private fun findGaps(
        myWordForms: Set<String>,
        myGrammar: List<GrammarPoint>,
        level: Int,
        wordCount: Int,
        grammarCount: Int,
    ): Pair<List<StdWord>, List<StdGrammar>> {
        val levels = (level - 1).coerceAtLeast(1)..level.coerceIn(1, 6)
        val words = catalog.words.asSequence()
            .filter { it.level in levels && it.type != "otro" && it.word !in myWordForms }
            .distinctBy { it.word }
            .toList()
            .shuffled()
            .take(wordCount)
        val myKeys = myGrammar.flatMap { GrammarKeys.keys(listOf(it.pattern) + it.variants.split(" | ")) }.toSet()
        val grammar = catalog.grammar
            .filter { g -> g.level in levels && (g.primaryKeys + g.relatedKeys).none { it in myKeys } }
            .shuffled()
            .take(grammarCount)
        return words to grammar
    }

    companion object {
        /** Gramáticas nuevas por escena, aparte de las sugerencias normales (decidido con Jorge). */
        const val SCENE_NEW_GRAMMAR = 2

        /**
         * Lo siguiente para aprender: gramáticas del estándar de tu nivel o del siguiente que no están en
         * tu base (ni la forma ni sus variantes). Primero las de tu nivel; al azar dentro de cada nivel.
         * Es "i+1": las lagunas ([findGaps]) repasan tu nivel y el anterior; esto empuja hacia adelante.
         */
        fun pickNewGrammar(
            catalog: List<StdGrammar>,
            myKeys: Set<String>,
            level: Int,
            count: Int,
            exclude: Set<Int> = emptySet(),
            random: kotlin.random.Random = kotlin.random.Random.Default,
        ): List<StdGrammar> {
            val from = level.coerceIn(1, 6)
            val levels = from..(from + 1).coerceAtMost(6)
            return catalog
                .filter { g -> g.level in levels && g.id !in exclude && (g.primaryKeys + g.relatedKeys).none { it in myKeys } }
                .distinctBy { it.form }
                .shuffled(random)
                .sortedBy { it.level }
                .take(count)
        }
    }

    private fun pick(
        pool: List<Long>,
        states: Map<Long, MemoryState>,
        now: Long,
        count: Int,
        newPerPage: Int,
    ): List<Long> {
        if (pool.isEmpty() || count <= 0) return emptyList()
        val recentCutoff = now - 12 * 60 * 60 * 1000L
        val seen = pool.mapNotNull { states[it] }.filter { it.lastReviewAt < recentCutoff }
        val unseen = pool.filter { it !in states }.shuffled()

        fun recall(s: MemoryState) = Fsrs.retrievabilityAt(
            Fsrs.Memory(s.stability, s.difficulty, s.lastReviewAt, s.reps, s.lapses), now,
        )

        val due = seen.filter { it.dueAt <= now }.sortedBy { recall(it) }.map { it.itemId }
        val notDue = seen.filter { it.dueAt > now }.sortedBy { it.dueAt }.map { it.itemId }

        val result = mutableListOf<Long>()
        val newQuota = minOf(newPerPage, unseen.size, count)
        result += due.take(count - newQuota)
        result += unseen.take(minOf(newQuota, count - result.size))
        if (result.size < count) result += unseen.drop(newQuota).take(count - result.size)
        if (result.size < count) result += notDue.take(count - result.size)
        return result.distinct()
    }

    /** Mezcla sustantivos, verbos y adjetivos manteniendo la prioridad dentro de cada tipo. */
    private fun balanceByType(words: List<Word>, priority: List<Long>): List<Word> {
        val byType = words.sortedBy { priority.indexOf(it.id) }.groupBy { it.type }.mapValues { ArrayDeque(it.value) }
        val order = listOf("verbo", "sustantivo", "adjetivo") + byType.keys.filterNot { it in setOf("verbo", "sustantivo", "adjetivo") }
        val result = mutableListOf<Word>()
        while (result.size < words.size) {
            for (type in order) byType[type]?.removeFirstOrNull()?.let { result += it }
        }
        return result
    }
}
