package com.jorge.koreandiary

import com.jorge.koreandiary.ai.CorrectionParser
import com.jorge.koreandiary.ai.HowToSayParser
import com.jorge.koreandiary.ai.ParseException
import com.jorge.koreandiary.data.ItemRef
import com.jorge.koreandiary.data.db.ItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionParserTest {

    @Test
    fun parsesCompactFormatInsideCodeFence() {
        val raw = """
            Claro, aquí está:
            ```json
            {"oraciones": [{"n": 1, "texto": "어제 잠을 못 자서 피곤해요", "tiempo": "pasado"}],
             "correcciones": [],
             "usados": [{"f": "자다", "t": "v", "ok": true, "id": "w42"},
                        {"f": "-어서", "t": "g", "ok": true, "id": null},
                        {"f": "피곤하다", "t": "a", "ok": false, "id": "sv12"}],
             "mejoras": [{"oracion": 1, "estructura": "-는 바람에", "ejemplo": "늦게 자는 바람에", "explicacion": "causa"}]}
            ```
        """.trimIndent()
        val r = CorrectionParser.parse(raw)
        assertEquals(3, r.used.size)
        assertEquals("w42", r.used[0].itemRef)
        assertEquals("verbo", r.used[0].kind)
        assertTrue(r.used[1].isGrammar)
        assertNull(r.used[1].itemRef)
        assertFalse(r.used[2].correct)
        assertEquals("sv12", r.used[2].itemRef)
        assertEquals("-는 바람에", r.improvements.single().structure)
    }

    @Test
    fun acceptsPreviousFormatWithNuevos() {
        val raw = """{"oraciones": [{"n": 1, "texto": "허리가 아파요", "tiempo": "presente"}],
            "usados": [{"id": "w3", "forma_base": "아프다", "resultado": "correcto"}],
            "nuevos": [{"forma_base": "허리", "tipo": "sustantivo"}]}"""
        val r = CorrectionParser.parse(raw)
        assertEquals(listOf("아프다", "허리"), r.used.map { it.form })
        assertEquals("sustantivo", r.used[1].kind)
    }

    @Test
    fun hyphenWithoutTypeIsGrammar() {
        val r = CorrectionParser.parse("""{"usados": [{"f": "-기 때문에"}]}""")
        assertTrue(r.used.single().isGrammar)
    }

    @Test(expected = ParseException::class)
    fun rejectsTextWithoutJson() {
        CorrectionParser.parse("No puedo ayudar con eso.")
    }

    @Test
    fun howToSayWithNaturalVersion() {
        val r = HowToSayParser.parse(
            """{"tu": {"k": "저랑 같은 학교에 다닌 사람이 내일 와요", "g": ["-은"]},
               "nat": {"k": "같이 학교 다녔던 친구가 내일 와요", "g": ["-었던"]},
               "nota": "다녔던 suena más natural.", "w": [{"f": "다니다", "s": "asistir"}]}"""
        )
        assertEquals(listOf("-은"), r.mine.grammar)
        assertEquals("같이 학교 다녔던 친구가 내일 와요", r.natural?.text)
        assertEquals("다니다" to "asistir", r.words.single())
    }

    @Test
    fun howToSayCollapsesIdenticalNatural() {
        val r = HowToSayParser.parse("""{"tu": {"k": "내일 가요.", "g": []}, "nat": {"k": "내일 가요", "g": []}, "nota": ""}""")
        assertNull(r.natural)
        val r2 = HowToSayParser.parse("""{"tu": {"k": "내일 가요", "g": []}, "nat": null}""")
        assertNull(r2.natural)
    }

    @Test
    fun itemRefsRoundTrip() {
        for (code in listOf("w12", "g3", "sv465", "sg7")) assertEquals(code, ItemRef.parse(code)!!.code)
        assertEquals(ItemType.STD_WORD, ItemRef.parse("sv465")!!.type)
        assertNull(ItemRef.parse("x9"))
    }
}
