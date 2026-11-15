package com.jorge.koreandiary

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.jorge.koreandiary.ai.PromptItem
import com.jorge.koreandiary.ai.Prompts
import com.jorge.koreandiary.ai.UsedItemDto
import com.jorge.koreandiary.conversation.ConversationParser
import com.jorge.koreandiary.conversation.ConversationPrompts
import com.jorge.koreandiary.conversation.Line
import com.jorge.koreandiary.conversation.Speaker
import com.jorge.koreandiary.conversation.TargetHelp
import com.jorge.koreandiary.data.ItemRef
import com.jorge.koreandiary.data.StudyRepository
import com.jorge.koreandiary.data.db.AppDatabase
import com.jorge.koreandiary.data.db.EventKind
import com.jorge.koreandiary.data.db.GrammarPoint
import com.jorge.koreandiary.data.db.ItemType
import com.jorge.koreandiary.data.db.ReviewEvent
import com.jorge.koreandiary.data.db.Word
import com.jorge.koreandiary.data.standard.StandardCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conversar y Escuchar cuentan para el repaso (decidido con Jorge el 2026-11-15): en la conversación
 * solo los objetivos (sin ayuda = bien, con ayuda = difícil, corregido = otra vez); en la escucha,
 * lo que preguntaste. Y la prioridad sin forzar: la lección reciente y lo que fallaste.
 */
class ConversationReviewCountsTest {

    private val targets = listOf(
        PromptItem("w1", "도서관", "sustantivo"),
        PromptItem("w2", "공부하다", "verbo"),
        PromptItem("g1", "-고 싶다", "gramática"),
        PromptItem("g2", "-아서/어서", "gramática"),
    )

    @Test
    fun helpMarksTheTargetsItShowed() {
        // Una pista con su clave: la gramática que muestra.
        assertEquals(setOf("g1"), TargetHelp.shown(targets, listOf("주말에 … 싶어요"), grammar = listOf("-고 싶다")))
        // «¿Cómo digo?»: la frase, sus estructuras y sus palabras.
        assertEquals(
            setOf("w1", "w2", "g2"),
            TargetHelp.shown(targets, listOf("도서관에 가서 공부했어요"), grammar = listOf("-어서"), words = listOf("도서관", "가다")),
        )
        // La raíz basta para un verbo (공부했어요 → 공부), pero no una sílaba suelta.
        assertTrue(TargetHelp.stemIn("공부하다", "어제 공부했어요"))
        assertFalse(TargetHelp.stemIn("가다", "가방을 샀어요"))
        assertEquals(emptySet<String>(), TargetHelp.shown(targets, listOf("커피를 마셨어요"), grammar = listOf("-었-")))
    }

    @Test
    fun theListenReviewAsksWhatHeAskedAbout() {
        val lines = listOf(Line(Speaker.USER, "소풍이 뭐예요?"), Line(Speaker.AI, "소풍은 picnic이에요."))
        val listen = ConversationPrompts.review("", "3A", 3, targets, lines, listen = true)
        assertTrue(listen.contains("7. \"preguntas\""))
        assertTrue(listen.contains("\"preguntas\": [{\"f\": \"소풍\""))
        // El formato queda sin sangría de más (el bloque se recorta parejo).
        assertTrue(listen.lines().any { it.startsWith("TAREAS") })
        assertFalse(ConversationPrompts.review("", "3A", 3, targets, lines).contains("preguntas"))
        // El modelo de voz salta los objetivos que no encajan con el tema.
        val parsed = ConversationParser.review(
            """{"oraciones": [{"n": 1, "texto": "소풍이 뭐예요?", "tiempo": "presente"}], "correcciones": [], "usados": [{"f": "소풍", "t": "n", "ok": true}],
               "comentario": "Bien.", "preguntas": [{"f": "소풍", "t": "n", "id": null, "s": "picnic"}, {"f": "-는 바람에", "t": "g", "id": "g9"}]}"""
        )
        assertEquals(listOf("소풍", "-는 바람에"), parsed.asked.map { it.form })
        assertEquals("picnic", parsed.asked[0].meaning)
        assertTrue(parsed.asked[1].isGrammar)
        assertEquals("g9", parsed.asked[1].itemRef)
    }

    @Test
    fun priorityCandidatesAreMarkedButNotForced() {
        val candidates = listOf(PromptItem("g1", "-고 싶다", "gramática"), PromptItem("w5", "모래", "sustantivo"))
        val p = Prompts.contextSuggestions("3A", 3, "어제 친구를 만났어요", candidates, scene = false, priority = setOf("g1"))
        assertTrue(p.contains("- g1: -고 싶다 (gramática) ★"))
        assertTrue(p.contains("- w5: 모래 (sustantivo)\n"))
        assertTrue(p.contains("si no encajan, no los fuerces"))
        assertFalse(Prompts.contextSuggestions("3A", 3, "x", candidates, scene = false).contains("★"))
    }

    @Test
    fun theConversationCountsOnlyItsTargets() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder<AppDatabase>().setDriver(BundledSQLiteDriver()).build()
        try {
            val catalog = StandardCatalog { name -> if (name == "grammar.tsv") listOf("7\t3\t표현\t-는 바람에\t\tpor culpa de") else emptyList() }
            val study = StudyRepository(db, catalog)
            val dao = db.studyDao()
            val w1 = dao.insertWord(Word(baseForm = "도서관", type = "sustantivo", source = "import"))
            val w2 = dao.insertWord(Word(baseForm = "공부하다", type = "verbo", source = "import"))
            val w3 = dao.insertWord(Word(baseForm = "운동하다", type = "verbo", source = "import"))
            val g1 = dao.insertGrammar(GrammarPoint(unit = null, level = "2급", pattern = "-고 싶다", variants = "-고 싶다", appliesTo = "V", source = "import"))
            val session = listOf(
                PromptItem("w$w1", "도서관", "sustantivo"),
                PromptItem("w$w2", "공부하다", "verbo"),
                PromptItem("w$w3", "운동하다", "verbo"),
                PromptItem("g$g1", "-고 싶다", "gramática"),
                PromptItem("sg7", "-는 바람에", "gramática"),
            )
            val now = 1_000_000L
            val summary = study.applyConversation(
                targets = session,
                used = listOf(
                    UsedItemDto("w$w1", "도서관", "sustantivo", correct = true),
                    UsedItemDto("g$g1", "-고 싶다", "gramatica", correct = true),
                    UsedItemDto("sg7", "-는 바람에", "gramatica", correct = true),
                    // Algo que no era objetivo no cuenta aunque lo haya usado.
                    UsedItemDto(null, "커피", "sustantivo", correct = true),
                ),
                corrected = setOf("w$w2"),
                helped = setOf("w$w1"),
                now = now,
            )
            // 도서관 (con ayuda), -고 싶다 (solo), 공부하다 (corregido) y la del catálogo, que entra a la lista.
            assertEquals(4, summary.reviewed)
            assertEquals(1, summary.newGrammar)
            val memory = db.memoryDao()
            val helped = memory.get(ItemType.WORD, w1)!!
            val alone = memory.get(ItemType.GRAMMAR, g1)!!
            val failed = memory.get(ItemType.WORD, w2)!!
            // Otra vez < difícil < bien: lo corregido vuelve antes que lo que usó con ayuda, y eso antes que lo que usó solo.
            assertTrue(failed.dueAt < helped.dueAt)
            assertTrue(helped.dueAt < alone.dueAt)
            // Lo que no usó no se califica, y lo que no era objetivo no entra.
            assertNull(memory.get(ItemType.WORD, w3))
            assertNull(dao.findWord("커피"))
            // Lo corregido cuenta como fallado (y por eso tiene prioridad después).
            assertEquals(listOf(ItemRef(ItemType.WORD, w2)), memory.failedSince(now - 1, 10).map { ItemRef(it.itemType, it.itemId) })
        } finally {
            db.close()
        }
    }

    @Test
    fun whatYouAskWhileListeningComesBack() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder<AppDatabase>().setDriver(BundledSQLiteDriver()).build()
        try {
            val study = StudyRepository(db, StandardCatalog { emptyList() })
            val dao = db.studyDao()
            val w1 = dao.insertWord(Word(baseForm = "소풍", type = "sustantivo", source = "import"))
            val now = 5_000_000L
            val summary = study.applyListenQuestions(
                listOf(
                    UsedItemDto(null, "소풍", "sustantivo", correct = true),
                    UsedItemDto(null, "돗자리", "sustantivo", correct = true, meaning = "esterilla"),
                    // Una gramática que no está en la lista no tiene «Por revisar»: se deja.
                    UsedItemDto(null, "-는 바람에", "gramatica", correct = true),
                ),
                now,
            )
            assertEquals(1, summary.again)
            assertEquals(1, summary.pending)
            val state = db.memoryDao().get(ItemType.WORD, w1)
            assertNotNull(state)
            // Vuelve pronto: en menos de dos días.
            assertTrue(state!!.dueAt - now < 2L * 24 * 60 * 60 * 1000)
            assertEquals("esterilla", dao.findPendingWord("돗자리")?.meaning)
            // Preguntar no es un error: no cuenta como fallado.
            assertTrue(db.memoryDao().failedSince(0, 10).isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun priorityItemsAreTheRecentLessonAndRecentMistakes() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder<AppDatabase>().setDriver(BundledSQLiteDriver()).build()
        try {
            val catalog = StandardCatalog { name -> if (name == "grammar.tsv") listOf("7\t3\t표현\t-는 바람에\t\tpor culpa de") else emptyList() }
            val study = StudyRepository(db, catalog)
            val dao = db.studyDao()
            val g1 = dao.insertGrammar(GrammarPoint(unit = 3, level = "3A", pattern = "-느라고", variants = "-느라고", appliesTo = "V", source = "workbook"))
            val w1 = dao.insertWord(Word(baseForm = "학교", type = "sustantivo", source = "import"))
            val w2 = dao.insertWord(Word(baseForm = "집", type = "sustantivo", source = "import"))
            val day = 24L * 60 * 60 * 1000
            val now = 30 * day
            db.memoryDao().insertEvent(ReviewEvent(itemType = ItemType.WORD, itemId = w1, at = now - 3 * day, kind = EventKind.INCORRECT, rating = 1, pageId = null))
            // Hace un mes: ya no tiene prioridad.
            db.memoryDao().insertEvent(ReviewEvent(itemType = ItemType.WORD, itemId = w2, at = now - 20 * day, kind = EventKind.INCORRECT, rating = 1, pageId = null))
            val items = study.priorityItems(now, listOf("V-느라고", "-는 바람에", "-는 바람에"))
            assertEquals(
                listOf(ItemRef(ItemType.GRAMMAR, g1), ItemRef(ItemType.STD_GRAMMAR, 7), ItemRef(ItemType.WORD, w1)),
                items,
            )
        } finally {
            db.close()
        }
    }
}
