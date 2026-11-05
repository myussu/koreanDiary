package com.jorge.koreandiary.ai

/** Respuesta de corrección: el contrato entre la app y cualquier proveedor. */
data class CorrectionResponse(
    val sentences: List<SentenceDto>,
    val corrections: List<CorrectionDto>,
    val used: List<UsedItemDto>,
    val newWords: List<NewWordDto>,
)

data class SentenceDto(val number: Int, val text: String, val tense: String)

data class CorrectionDto(
    val sentence: Int,
    val original: String,
    val suggestion: String,
    val type: String,
    val explanation: String,
    /** "w42", "g3" o null. */
    val itemRef: String?,
)

data class UsedItemDto(val itemRef: String, val baseForm: String, val correct: Boolean)

data class NewWordDto(val baseForm: String, val type: String, val meaning: String?)

/** Resultado del prompt de importación. */
data class ImportPayload(val words: List<ImportWord>, val grammar: List<ImportGrammar>)

data class ImportWord(
    val baseForm: String,
    val type: String,
    val irregular: String?,
    val level: String,
    val meaning: String?,
)

data class ImportGrammar(
    val unit: Int?,
    val level: String,
    val pattern: String,
    val variants: List<String>,
    val appliesTo: String,
    val meaning: String?,
    val example: String?,
    val tags: List<String>,
)

/** Ítem que se manda en el prompt: "w12 | 먹다 | verbo". */
data class PromptItem(val ref: String, val label: String, val kind: String)
