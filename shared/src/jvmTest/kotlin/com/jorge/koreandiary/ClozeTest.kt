package com.jorge.koreandiary

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.jorge.koreandiary.data.StudyRepository
import com.jorge.koreandiary.data.db.AppDatabase
import com.jorge.koreandiary.data.db.Correction
import com.jorge.koreandiary.data.db.CorrectionStatus
import com.jorge.koreandiary.data.db.DiaryEntry
import com.jorge.koreandiary.data.db.DiaryPage
import com.jorge.koreandiary.data.db.EventKind
import com.jorge.koreandiary.data.db.GrammarPoint
import com.jorge.koreandiary.data.db.ItemType
import com.jorge.koreandiary.data.db.MemoryState
import com.jorge.koreandiary.data.db.PageKind
import com.jorge.koreandiary.data.db.PageStatus
import com.jorge.koreandiary.data.db.ReviewEvent
import com.jorge.koreandiary.data.db.Sentence
import com.jorge.koreandiary.data.db.Word
import com.jorge.koreandiary.data.standard.StandardCatalog
import com.jorge.koreandiary.review.Cloze
import com.jorge.koreandiary.review.ClozeBlank
import com.jorge.koreandiary.review.ClozeCard
import com.jorge.koreandiary.review.ClozeKind
import com.jorge.koreandiary.review.ClozeTarget
import com.jorge.koreandiary.review.QuickReviewPick
import com.jorge.koreandiary.srs.Fsrs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Repaso rápido con espacios en blanco (decidido con Jorge el 2026-11-15): tus oraciones primero, el
 * hueco encontrado en el celular, tres pistas por tipo y la calificación según cuántas usaste.
 */
class ClozeTest {

    private fun blank(sentence: String, label: String, kind: ClozeKind) = Cloze.blankIn(sentence, label, kind)?.text

    @Test
    fun theBlankIsFoundInYourSentence() {
        // Un sustantivo tal cual, sin su partícula.
        assertEquals("도서관", blank("시험이 있으니까 도서관에서 공부했어요.", "도서관", ClozeKind.WORD))
        assertEquals(ClozeBlank(9, "도서관"), Cloze.blankIn("시험이 있으니까 도서관에서 공부했어요.", "도서관", ClozeKind.WORD))
        // Un verbo, la palabra conjugada entera (también contraída).
        assertEquals("공부했어요", blank("시험이 있으니까 도서관에서 공부했어요.", "공부하다", ClozeKind.VERB))
        assertEquals("갔어요", blank("어제 친구랑 바다에 갔어요!", "가다", ClozeKind.VERB))
        assertEquals("왔어요", blank("동생이 집에 왔어요.", "오다", ClozeKind.VERB))
        assertEquals("마셨어요", blank("커피를 마셨어요.", "마시다", ClozeKind.VERB))
        assertEquals("들었어요", blank("음악을 들었어요.", "듣다", ClozeKind.VERB))
        assertEquals("먹고", blank("밥을 먹고 잤어요.", "먹다", ClozeKind.VERB))
        // Una raíz de una sílaba no se confunde con un sustantivo que empieza igual.
        assertNull(blank("가방을 샀어요.", "가다", ClozeKind.VERB))
        assertNull(blank("한국어를 배워요.", "하다", ClozeKind.VERB))
        // Una terminación, con su palabra; si ocupa dos palabras, las dos.
        assertEquals("있으니까", blank("시험이 있으니까 도서관에서 공부했어요.", "A/V-(으)니까", ClozeKind.GRAMMAR))
        assertEquals("가고 싶어요", blank("주말에 바다에 가고 싶어요.", "V-고 싶다", ClozeKind.GRAMMAR))
        assertEquals("먹는데", blank("밥을 먹는데 전화가 왔어요.", "V-는데", ClozeKind.GRAMMAR))
        // Una partícula, sola.
        val particle = Cloze.blankIn("서울까지 기차로 갔어요.", "N까지", ClozeKind.GRAMMAR)!!
        assertEquals(ClozeBlank(2, "까지"), particle)
        // Lo que no está, no se inventa.
        assertNull(blank("커피를 마셨어요.", "V-고 싶다", ClozeKind.GRAMMAR))
    }

    @Test
    fun yourSentenceIsReviewedCorrected() {
        val fixed = Cloze.applyCorrections("시험이 있니까 도서관에서 공부했어요.", listOf("있니까" to "있으니까", "없는것" to "x"))
        assertEquals("시험이 있으니까 도서관에서 공부했어요.", fixed)
    }

    private fun card(answer: String, kind: ClozeKind, label: String, base: String? = null, ending: String? = null) =
        ClozeCard("w1", label, kind, "significado", "어제 $answer.", 3, answer, base = base, ending = ending)

    @Test
    fun threeHintsByType() {
        val noun = Cloze.hints(card("도서관", ClozeKind.WORD, "도서관"))
        assertEquals("3 sílabas: □□□", noun[0])
        assertEquals("Empieza con 도: 도□□", noun[1])
        assertTrue(noun[2].startsWith("Fichas: "))
        assertEquals(setOf('도', '서', '관'), noun[2].removePrefix("Fichas: ").split(" · ").map { it.single() }.toSet())
        // Las fichas nunca salen en el orden de la respuesta, y siempre igual para la misma tarjeta.
        assertFalse(Cloze.tiles("도서관") == "도서관".toList())
        assertEquals(Cloze.tiles("공부했어요"), Cloze.tiles("공부했어요"))

        val verb = Cloze.hints(card("공부했어요", ClozeKind.VERB, "공부하다", ending = "-았/었어요"))
        assertEquals("Forma de diccionario: 공부하다", verb[0])
        assertEquals("Terminación: -았/었어요", verb[1])
        // Sin la IA, la terminación sale de la oración.
        assertEquals("-었어요", Cloze.localEnding("먹다", "먹었어요"))
        assertEquals("…어요", Cloze.localEnding("공부하다", "공부했어요"))

        val grammar = Cloze.hints(card("있으니까", ClozeKind.GRAMMAR, "A/V-(으)니까", base = "있다"))
        assertEquals("Con: 있다", grammar[0])
        assertEquals("Gramática: A/V-(으)니까", grammar[1])

        // Una sílaba: la última pista es su consonante y su vocal.
        val one = Cloze.hints(card("집", ClozeKind.WORD, "집"))
        assertEquals("Empieza con ㅈ", one[1])
        assertEquals("Empieza con ㅈ y su vocal es ㅣ", one[2])
    }

    @Test
    fun theAnswerAndTheRating() {
        val c = card("공부했어요", ClozeKind.VERB, "공부하다")
        assertTrue(Cloze.matches(c, listOf("공부 했어요.")))
        // Vale cualquier lectura del reconocedor.
        assertTrue(Cloze.matches(c, listOf("공부했어 요", "공부했어요")))
        assertFalse(Cloze.matches(c, listOf("공부해요")))
        assertEquals(Fsrs.Rating.GOOD, Cloze.rating(hints = 0, wrongTries = 0, revealed = false))
        assertEquals(Fsrs.Rating.GOOD, Cloze.rating(hints = 1, wrongTries = 0, revealed = false))
        assertEquals(Fsrs.Rating.HARD, Cloze.rating(hints = 2, wrongTries = 0, revealed = false))
        assertEquals(Fsrs.Rating.HARD, Cloze.rating(hints = 3, wrongTries = 0, revealed = false))
        assertEquals(Fsrs.Rating.HARD, Cloze.rating(hints = 0, wrongTries = 1, revealed = false))
        assertEquals(Fsrs.Rating.AGAIN, Cloze.rating(hints = 0, wrongTries = 0, revealed = true))
    }

    @Test
    fun theTranslationMarksWhatIsMissing() {
        assertEquals(
            listOf("Como hay examen, " to false, "estudié" to true, " en la biblioteca." to false),
            Cloze.translationParts("Como hay examen, [estudié] en la biblioteca."),
        )
        assertEquals(listOf("Sin corchetes" to false), Cloze.translationParts("Sin corchetes"))
    }

    @Test
    fun oneCallTranslatesYoursAndWritesTheRest() {
        val targets = listOf(
            ClozeTarget("w1", "공부하다", ClozeKind.VERB, "estudiar", "시험이 있으니까 도서관에서 공부했어요.", ClozeBlank(15, "공부했어요")),
            ClozeTarget("g2", "V-고 싶다", ClozeKind.GRAMMAR, "querer"),
            ClozeTarget("w3", "바다", ClozeKind.WORD, "mar"),
            ClozeTarget("w4", "사과", ClozeKind.WORD, "manzana", "사과를 먹었어요.", ClozeBlank(0, "사과")),
        )
        val prompt = Cloze.prompt("Vivo en Lima.", "3A", 3, targets)
        assertTrue(prompt.contains("- w1: 공부하다 (verbo o adjetivo) | oración suya: «시험이 있으니까 도서관에서 공부했어요.» | hueco: «공부했어요»"))
        assertTrue(prompt.contains("- g2: V-고 싶다 (gramática)\n"))
        assertTrue(prompt.contains("SOBRE EL ESTUDIANTE: Vivo en Lima."))
        val raw = """```json
            {"items": [
              {"id": "g2", "oracion": "주말에 바다에 가고 싶어요.", "hueco": "가고 싶어요", "traduccion": "El fin de semana [quiero ir] al mar.", "base": "가다", "terminacion": null},
              {"id": "w3", "oracion": "바다가 정말 예뻤어요.", "hueco": "바닷가", "traduccion": "El [mar] era precioso."},
              {"id": "w1", "oracion": "otra cosa", "hueco": "x", "traduccion": "Como hay examen, [estudié] en la biblioteca.", "base": "공부하다", "terminacion": "-았/었어요"}
            ]}
            ```"""
        val cards = Cloze.parse(raw, targets)
        // Las tuyas primero; la de la IA con un hueco que no está en su oración se deja.
        assertEquals(listOf("w1", "w4", "g2"), cards.map { it.code })
        val mine = cards[0]
        // Tu oración no la cambia la IA; solo trae la traducción y las pistas.
        assertEquals("시험이 있으니까 도서관에서 공부했어요.", mine.sentence)
        assertEquals("공부했어요", mine.answer)
        assertEquals("시험이 있으니까 도서관에서 ", mine.before)
        assertEquals(".", mine.after)
        assertEquals("-았/었어요", mine.ending)
        assertTrue(mine.own)
        // La tuya que la IA no devolvió queda sin traducción.
        assertNull(cards[1].translation)
        val ai = cards[2]
        assertFalse(ai.own)
        assertEquals("가고 싶어요", ai.answer)
        assertEquals("주말에 바다에 ", ai.before)
        assertNull(ai.ending)
        // Sin IA, solo las tuyas.
        assertEquals(listOf("w1", "w4"), Cloze.localCards(targets).map { it.code })
    }

    @Test
    fun theSessionIsDueFirstThenAFewNew() {
        val due = (1..12).map { "d$it" }
        val unseen = (1..5).map { "n$it" }
        assertEquals(due.take(7) + unseen.take(3), QuickReviewPick.pick(due, unseen))
        // Si hay poco vencido, se completa con lo nuevo; y al revés.
        assertEquals(listOf("d1", "n1", "n2", "n3", "n4", "n5"), QuickReviewPick.pick(listOf("d1"), unseen))
        assertEquals(due.take(10), QuickReviewPick.pick(due, emptyList()))
    }

    @Test
    fun aSessionFromYourPages() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder<AppDatabase>().setDriver(BundledSQLiteDriver()).build()
        try {
            val study = StudyRepository(db, StandardCatalog { emptyList() })
            val dao = db.studyDao()
            val diary = db.diaryDao()
            val memory = db.memoryDao()
            val day = 24L * 60 * 60 * 1000
            val now = 100 * day
            val w = dao.insertWord(Word(baseForm = "공부하다", type = "verbo", meaning = "estudiar", source = "import"))
            val fresh = dao.insertWord(Word(baseForm = "바다", type = "sustantivo", source = "import"))
            val g = dao.insertGrammar(GrammarPoint(unit = 1, level = "3A", pattern = "A/V-(으)니까", variants = "A/V-(으)니까", appliesTo = "AV", source = "import"))
            // Otra gramática de un nivel que no eliges: no entra como nueva.
            dao.insertGrammar(GrammarPoint(unit = 9, level = "6A", pattern = "V-느라고", variants = "V-느라고", appliesTo = "V", source = "import"))
            val entry = diary.insertEntry(DiaryEntry(date = "2026-08-01", createdAt = 0))
            val page = diary.insertPage(DiaryPage(entryId = entry, status = PageStatus.REVIEWED, createdAt = 0, updatedAt = 0, kind = PageKind.DIARY))
            diary.insertSentence(Sentence(pageId = page, number = 1, text = "시험이 있니까 도서관에서 공부했어요.", tense = "pasado"))
            diary.insertCorrection(
                Correction(
                    pageId = page, sentence = 1, original = "있니까", suggestion = "있으니까", type = "conjugacion",
                    explanation = "", tense = null, itemType = ItemType.GRAMMAR, itemId = g, status = CorrectionStatus.REWRITTEN,
                )
            )
            for ((type, id) in listOf(ItemType.WORD to w, ItemType.GRAMMAR to g)) {
                memory.insertEvent(ReviewEvent(itemType = type, itemId = id, at = 10 * day, kind = EventKind.CORRECT_SPONTANEOUS, rating = 3, pageId = page))
                // Vencido hace mucho.
                memory.upsert(MemoryState(itemType = type, itemId = id, stability = 3.0, difficulty = 5.0, lastReviewAt = 10 * day, dueAt = 13 * day, reps = 1, lapses = 0))
            }
            val targets = study.quickReviewTargets(now, listOf("3A"), random = Random(1))
            assertEquals(setOf("w$w", "g$g", "w$fresh"), targets.map { it.code }.toSet())
            // Lo vencido primero, con tu oración corregida y el hueco.
            val verb = targets.first { it.code == "w$w" }
            assertEquals("시험이 있으니까 도서관에서 공부했어요.", verb.sentence)
            assertEquals("공부했어요", verb.blank?.text)
            assertEquals(ClozeKind.VERB, verb.kind)
            assertEquals("있으니까", targets.first { it.code == "g$g" }.blank?.text)
            // Lo nuevo no tiene oración tuya: la escribe la IA.
            assertNull(targets.first { it.code == "w$fresh" }.sentence)
            assertEquals("w$fresh", targets.last().code)

            // Cada tarjeta cuenta al cerrarse; lo repasado no vuelve a la misma ronda del día.
            study.applyQuickReview("w$w", Fsrs.Rating.GOOD, now)
            study.applyQuickReview("w$fresh", Fsrs.Rating.AGAIN, now)
            study.applyQuickReview("sg5", Fsrs.Rating.GOOD, now)
            val state = memory.get(ItemType.WORD, w)
            assertNotNull(state)
            assertTrue(state!!.dueAt > now)
            assertEquals(2, state.reps)
            assertNotNull(memory.get(ItemType.WORD, fresh))
            // No es una página ni un error de escritura.
            assertTrue(memory.failedSince(0, 10).isEmpty())
            assertEquals(listOf(page), memory.pagesUsing(ItemType.WORD, w, 5))
            assertEquals(listOf("g$g"), study.quickReviewTargets(now + 1000, listOf("3A")).map { it.code })
        } finally {
            db.close()
        }
    }
}
