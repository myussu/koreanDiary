package com.jorge.koreandiary

import com.jorge.koreandiary.ai.ExplainParser
import com.jorge.koreandiary.ai.ParseException
import com.jorge.koreandiary.data.ExplanationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExplainParserTest {
    private val raw = """
        ```json
        {"items": [
          {"f": "-느라고", "s": "por estar haciendo algo (causa)", "u": "Para excusas: lo primero impidió lo segundo.",
           "c": "가다 → 가느라고, 먹다 → 먹느라고",
           "ej": [{"ko": "과제하느라고 못 잤어요.", "en": "I couldn't sleep because I was doing homework."}, {"ko": "늦게 일어나느라고 늦었어요."}]},
          {"f": "미용실", "s": "peluquería", "u": null, "c": null, "ej": []},
          {"s": "sin forma"}
        ]}
        ```
    """.trimIndent()

    @Test
    fun parsesItemsWithExamplesAndOptionalFields() {
        val items = ExplainParser.parse(raw)
        assertEquals(listOf("-느라고", "미용실"), items.map { it.form })
        val g = items[0]
        assertEquals(2, g.examples.size)
        assertEquals("I couldn't sleep because I was doing homework.", g.examples[0].english)
        assertNull(g.examples[1].english)
        assertNull(items[1].usage)
        assertNull(items[1].conjugation)
    }

    @Test
    fun cacheFormatRoundTrips() {
        val item = ExplainParser.parse(raw).first()
        assertEquals(item, ExplainParser.fromJson(ExplainParser.toJson(item)))
        assertEquals(ExplainParser.parse(raw)[1], ExplainParser.fromJson(ExplainParser.toJson(ExplainParser.parse(raw)[1])))
    }

    @Test(expected = ParseException::class)
    fun emptyAnswerFails() {
        ExplainParser.parse("""{"items": []}""")
    }

    @Test
    fun matchesByOrderOrByFormIgnoringNotation() {
        val answered = ExplainParser.parse(raw)
        // Pedido en otro orden y con la notación del libro: se empareja por forma.
        val requested = listOf("미용실" to false, "V-느라고" to true, "학교" to false)
        val matched = ExplanationStore.match(requested, answered)
        assertEquals("peluquería", matched[ExplanationStore.key("미용실", false)]?.meaning)
        assertEquals("-느라고", matched[ExplanationStore.key("V-느라고", true)]?.form)
        assertNull(matched[ExplanationStore.key("학교", false)])
    }
}
