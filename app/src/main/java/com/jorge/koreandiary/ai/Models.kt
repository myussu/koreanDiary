package com.jorge.koreandiary.ai

/** Respuesta de corrección: el contrato entre la app y cualquier proveedor. */
data class CorrectionResponse(
    val sentences: List<SentenceDto>,
    val corrections: List<CorrectionDto>,
    /** Todo lo usado: palabras en forma de diccionario y gramática en notación estándar. */
    val used: List<UsedItemDto>,
    /** Estructuras de su nivel que encajarían mejor donde escribió algo demasiado básico. */
    val improvements: List<ImprovementDto> = emptyList(),
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

data class UsedItemDto(
    /** "w12", "g3", "sv465", "sg12" si coincide con un ítem de la lista; si no, null. */
    val itemRef: String?,
    /** Forma de diccionario ("자다") o gramática estándar ("-어서", "이라도"). */
    val form: String,
    /** sustantivo, verbo, adjetivo, adverbio, gramatica u otro. */
    val kind: String,
    val correct: Boolean,
    val meaning: String? = null,
) {
    val isGrammar get() = kind == "gramatica"
}

data class ImprovementDto(val sentence: Int, val structure: String, val example: String, val explanation: String)

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

/** Respuesta de "¿Cómo digo esto?". */
data class HowToSayResponse(
    /** La frase con vocabulario y gramática hasta el nivel del estudiante. */
    val mine: Phrase,
    /** Cómo lo diría normalmente un coreano; null si coincide con [mine]. */
    val natural: Phrase?,
    val note: String,
    /** Palabras de contenido usadas (forma de diccionario → significado). */
    val words: List<Pair<String, String>>,
) {
    data class Phrase(val text: String, val grammar: List<String>)
}

/** Pistas para una página de workbook. */
data class WorkbookHints(
    val exercises: List<WorkbookExercise>,
    val vocab: List<UsedItemDto>,
    /** Todas las gramáticas impresas en la página; el estudiante elige cuáles van a su lista. */
    val grammar: List<WorkbookGrammar> = emptyList(),
    /** Número de unidad o lección si se ve en la página. */
    val unit: Int? = null,
)

/** Gramática impresa en una página del workbook. */
data class WorkbookGrammar(
    val form: String,
    val meaning: String?,
    /** La IA cree que es la gramática que enseña la lección (resaltada arriba, en un recuadro…). */
    val focus: Boolean,
    /** Dónde aparece: título, recuadro, ejercicios, instrucciones o texto. */
    val where: String?,
)

data class WorkbookExercise(
    /** Como aparece en la página: "1", "2-3", "(4)". */
    val number: String,
    /** Qué pide, en una línea. */
    val ask: String,
    val hints: List<WorkbookHint>,
    /** Respuesta modelo: se muestra solo al pulsar "Ver respuesta". */
    val answer: String,
)

data class WorkbookHint(
    val form: String,
    /** gramatica, conector, expresión, sustantivo, verbo, adjetivo, adverbio u otro. */
    val kind: String,
    /** Para qué sirve y en qué situación se usa. */
    val use: String,
    val example: String,
    val english: String,
) {
    val isGrammar get() = kind == "gramatica" || kind == "conector" || kind == "expresión"
}

enum class ReviewStatus { OK, FIX, NATURAL }

data class WorkbookReviewItem(
    val number: String,
    val written: String,
    val status: ReviewStatus,
    val corrected: String?,
    val natural: String?,
    val explanation: String,
)

data class WorkbookReview(val items: List<WorkbookReviewItem>, val used: List<UsedItemDto>)
