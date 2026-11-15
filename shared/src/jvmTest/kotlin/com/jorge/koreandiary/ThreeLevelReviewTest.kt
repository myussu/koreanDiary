package com.jorge.koreandiary

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.jorge.koreandiary.ai.CorrectionDto
import com.jorge.koreandiary.ai.CorrectionParser
import com.jorge.koreandiary.ai.CorrectionResponse
import com.jorge.koreandiary.ai.NaturalDto
import com.jorge.koreandiary.ai.Prompts
import com.jorge.koreandiary.ai.ReviewLevels
import com.jorge.koreandiary.ai.SentenceDto
import com.jorge.koreandiary.conversation.ConversationParser
import com.jorge.koreandiary.conversation.ConversationPrompts
import com.jorge.koreandiary.conversation.Drill
import com.jorge.koreandiary.conversation.DrillResult
import com.jorge.koreandiary.conversation.Line
import com.jorge.koreandiary.conversation.Speaker
import com.jorge.koreandiary.data.StudyRepository
import com.jorge.koreandiary.data.db.AppDatabase
import com.jorge.koreandiary.data.db.CorrectionStatus
import com.jorge.koreandiary.data.db.DiaryEntry
import com.jorge.koreandiary.data.db.DiaryPage
import com.jorge.koreandiary.data.standard.StandardCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La revisión en tres niveles (bien, corregir y correcto pero más natural) en el diario, las escenas
 * y la conversación, y el aviso de errores que se repiten.
 */
class ThreeLevelReviewTest {

    /** Los nombres de las tareas de un prompt, en orden: `1. "oraciones": …` → "oraciones". */
    private fun tasks(prompt: String): List<String> =
        prompt.lines().mapNotNull { Regex("""^(\d+)\. "(\w+)"""").find(it.trim())?.groupValues?.get(2) }

    @Test
    fun theReviewAsksForTheThirdLevel() {
        val diary = Prompts.correction("", "3A", 3, emptyList(), "학교에 공부했어요.", hasImage = false)
        assertEquals(listOf("oraciones", "correcciones", "natural", "usados", "mejoras"), tasks(diary))
        // «naturalidad» queda para errores de uso; lo correcto pero poco natural va aparte.
        assertTrue(diary.contains(Prompts.NATURALNESS_ERRORS))
        assertTrue(diary.contains("\"natural\": [{\"oracion\": 3"))
        assertTrue(diary.contains("escribiría un coreano"))
        // Sin errores recientes no se pide "repite".
        assertFalse(diary.contains("repite"))

        val scene = Prompts.correction("", "3A", 3, emptyList(), "바다가 보여요.", hasImage = false, scene = true, ideas = listOf("veo el mar"))
        assertEquals(listOf("oraciones", "correcciones", "natural", "usados", "mejoras", "notas", "escena", "tono", "nuevos"), tasks(scene))

        val talk = ConversationPrompts.review("", "3A", 3, emptyList(), listOf(Line(Speaker.USER, "학교에 갔어요")))
        assertEquals(listOf("oraciones", "correcciones", "natural", "usados", "mejoras", "comentario"), tasks(talk))
        assertTrue(talk.contains("diría un coreano hablando"))
    }

    @Test
    fun recentErrorsAskIfTheMistakeRepeats() {
        val prompt = Prompts.correction(
            "", "3A", 3, emptyList(), "도서관에 공부했어요.", hasImage = false,
            recentErrors = listOf("학교에 → 학교에서 (particula)", "먹엇어요 → 먹었어요 (ortografia)"),
        )
        assertTrue(prompt.contains("ERRORES RECIENTES"))
        assertTrue(prompt.contains("- 학교에 → 학교에서 (particula)"))
        assertTrue(prompt.contains("\"repite\": si es el mismo error"))
        assertTrue(prompt.contains("\"repite\": 0"))
        assertEquals("Tercera vez en dos semanas. ", StudyRepository.repeatNote(3))
        assertEquals("Segunda vez en dos semanas. ", StudyRepository.repeatNote(2))
        assertEquals("", StudyRepository.repeatNote(1))
        assertEquals("", StudyRepository.repeatNote(0))
    }

    @Test
    fun theParserReadsNaturalVersionsAndRepeats() {
        val r = CorrectionParser.parse(
            """
            {"oraciones": [{"n": 1, "texto": "도서관에 공부했어요.", "tiempo": "pasado"}, {"n": 2, "texto": "저는 커피를 마시는 것을 좋아해요.", "tiempo": "presente"}, {"n": 3, "texto": "좋았어요.", "tiempo": "pasado"}],
             "correcciones": [{"oracion": 1, "original": "도서관에", "sugerencia": "도서관에서", "tipo": "particula", "explicacion": "Lugar donde pasa algo.", "repite": 3},
                              {"oracion": 3, "original": "좋았어요", "sugerencia": "좋았어요!", "tipo": "ortografia", "explicacion": "x", "repite": 1}],
             "natural": [{"oracion": 2, "original": "저는 커피를 마시는 것을 좋아해요.", "sugerencia": "커피 마시는 걸 좋아해요.", "explicacion": "Sin 저는 y con 걸 suena hablado."},
                         {"oracion": 1, "original": "도서관에 공부했어요.", "sugerencia": "도서관에서 공부했어요.", "explicacion": "ya corregida"},
                         {"oracion": 3, "original": "좋았어요.", "sugerencia": "좋았어요", "explicacion": "solo un punto"}],
             "usados": [{"f": "공부하다", "t": "v", "ok": true, "id": null}]}
            """.trimIndent()
        )
        assertEquals(3, r.corrections[0].repeats)
        // «1» es la primera vez: no se repite.
        assertEquals(0, r.corrections[1].repeats)
        // Solo la natural de verdad: ni la de una oración con corrección ni la que solo cambia un punto.
        assertEquals(1, r.naturals.size)
        assertEquals(2, r.naturals[0].sentence)
        assertEquals("커피 마시는 걸 좋아해요.", r.naturals[0].suggestion)
        // La revisión de la conversación usa el mismo contrato.
        val talk = ConversationParser.review("""{"oraciones": [], "correcciones": [], "natural": [{"oracion": 1, "original": "네", "sugerencia": "네, 맞아요.", "explicacion": "Más completo."}], "usados": [{"f": "맞다", "t": "a", "ok": true}], "comentario": "Bien."}""")
        assertEquals("네, 맞아요.", talk.correction.naturals.single().suggestion)
    }

    @Test
    fun levelsCountEachSentenceOnce() {
        assertEquals(ReviewLevels(ok = 2, fix = 1, natural = 1), ReviewLevels.of(setOf(1, 2, 3, 4), setOf(2), setOf(4)))
        // Una oración con corrección y versión natural cuenta como «corregir».
        assertEquals(ReviewLevels(ok = 1, fix = 1, natural = 0), ReviewLevels.of(setOf(1, 2), setOf(2), setOf(2)))
        // Números fuera de las oraciones conocidas igual cuentan; el 0 (sin oración) no.
        assertEquals(ReviewLevels(ok = 1, fix = 1, natural = 0), ReviewLevels.of(setOf(1), setOf(0, 5), emptySet()))
        assertEquals(0, ReviewLevels.of(emptySet(), emptySet(), emptySet()).total)
    }

    @Test
    fun naturalVersionsArePracticedAloud() {
        val lines = listOf(
            Line(Speaker.AI, "주말에 뭐 했어요?"),
            Line(Speaker.USER, "도서관에 공부했어요"),
            Line(Speaker.AI, "커피 좋아해요?"),
            Line(Speaker.USER, "저는 커피를 마시는 것을 좋아해요"),
        )
        val items = Drill.build(
            lines,
            listOf(CorrectionDto(1, "도서관에", "도서관에서", "particula", "Lugar.", null)),
            listOf(NaturalDto(2, "저는 커피를 마시는 것을 좋아해요", "커피 마시는 걸 좋아해요", "Más hablado.")),
        )
        assertEquals(2, items.size)
        assertFalse(items[0].natural)
        assertEquals("도서관에서 공부했어요", items[0].target)
        val natural = items[1]
        assertTrue(natural.natural)
        assertEquals("커피 마시는 걸 좋아해요", natural.target)
        assertEquals("저는 커피를 마시는 것을 좋아해요", natural.original)
        assertEquals(DrillResult.OK, Drill.evaluate(natural, "커피 마시는 걸 좋아해요"))
        assertEquals(DrillResult.DIFFERENT, Drill.evaluate(natural, "저는 커피를 마시는 것을 좋아해요"))
    }

    @Test
    fun aReviewStoresNaturalsAndMarksRepeatedErrors() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder<AppDatabase>().setDriver(BundledSQLiteDriver()).build()
        try {
            val study = StudyRepository(db, StandardCatalog { emptyList() })
            val dao = db.diaryDao()
            val day = 24L * 60 * 60 * 1000
            val entry = dao.insertEntry(DiaryEntry(date = "2026-11-01", createdAt = 0))
            val old = dao.insertPage(DiaryPage(entryId = entry, createdAt = 0, updatedAt = 0))
            val lastWeek = dao.insertPage(DiaryPage(entryId = entry, createdAt = 10 * day, updatedAt = 10 * day))
            val today = dao.insertPage(DiaryPage(entryId = entry, createdAt = 16 * day, updatedAt = 16 * day))
            fun fix(n: Int, original: String, suggestion: String, repeats: Int = 0) =
                CorrectionDto(n, original, suggestion, "particula", "Lugar donde pasa algo.", null, repeats)

            study.applyCorrection(dao.page(old)!!, CorrectionResponse(listOf(SentenceDto(1, "x", "pasado")), listOf(fix(1, "집에", "집에서")), emptyList()), now = 1)
            study.applyCorrection(
                dao.page(lastWeek)!!,
                CorrectionResponse(
                    listOf(SentenceDto(1, "학교에 공부했어요.", "pasado"), SentenceDto(2, "저는 커피를 좋아해요.", "presente")),
                    listOf(fix(1, "학교에", "학교에서")),
                    emptyList(),
                    naturals = listOf(NaturalDto(2, "저는 커피를 좋아해요.", "커피 좋아해요.", "Sin 저는.")),
                ),
                now = 2,
            )

            // Solo lo de las últimas dos semanas, de otras páginas, y solo errores (no lo «más natural»).
            assertEquals(listOf("학교에 → 학교에서 (particula)"), study.recentErrors(now = 16 * day, exceptPage = today))

            val summary = study.applyCorrection(
                dao.page(today)!!,
                CorrectionResponse(listOf(SentenceDto(1, "도서관에 공부했어요.", "pasado")), listOf(fix(1, "도서관에", "도서관에서", repeats = 2)), emptyList()),
                now = 3,
            )
            assertEquals(1, summary.repeated)
            val stored = dao.corrections(today).single()
            assertTrue(stored.explanation.startsWith("Segunda vez en dos semanas. "))

            val natural = dao.corrections(lastWeek).single { it.status == CorrectionStatus.NATURAL }
            assertEquals("커피 좋아해요.", natural.suggestion)
            // Lo más natural no frena nada: la página no queda con correcciones pendientes por eso.
            assertEquals(1, dao.corrections(lastWeek).count { it.status == CorrectionStatus.PENDING })
        } finally {
            db.close()
        }
    }
}
