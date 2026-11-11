package com.jorge.koreandiary.data.db

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** Tipos de ítem que FSRS sigue. Se guardan como texto para que la base sea legible. */
object ItemType {
    const val WORD = "word"
    const val GRAMMAR = "grammar"
    /** Ítems del catálogo estándar que aún no están en tu base (sugerencias de laguna). */
    const val STD_WORD = "stdword"
    const val STD_GRAMMAR = "stdgrammar"
}

/** Punto de gramática. Sube por niveles (a diferencia del vocabulario). */
@Entity(
    tableName = "grammar_points",
    indices = [Index(value = ["pattern"], unique = true)],
)
data class GrammarPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Unidad del libro, o null si viene del apéndice. */
    val unit: Int?,
    /** "3A", "4A", "base"... */
    val level: String,
    /** Patrón principal, p. ej. "V-자마자". */
    val pattern: String,
    /** Variantes separadas por " | ", p. ej. "A-다고 하다 | V-ㄴ/는다고 하다 | N(이)라고 하다". */
    val variants: String,
    /** Letras a las que se aplica: combinación de A, V, N. */
    val appliesTo: String,
    val meaning: String? = null,
    val example: String? = null,
    /** Etiquetas separadas por coma: pasado, futuro, discurso indirecto... */
    val tags: String = "",
    val source: String,
)

/** Palabra de vocabulario. No se ata a un nivel: el nivel es solo informativo. */
@Entity(
    tableName = "words",
    indices = [Index(value = ["baseForm"], unique = true)],
)
data class Word(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Forma de diccionario (verbos y adjetivos terminados en 다). */
    val baseForm: String,
    /** sustantivo, verbo, adjetivo, adverbio, otro. */
    val type: String,
    val meaning: String? = null,
    /** inicial, intermedio, avanzado, desconocido. */
    val level: String = "desconocido",
    /** ㅂ, ㄷ, ㅅ, ㅎ, 르, ㄹ, ㅡ o null. */
    val irregular: String? = null,
    /** "import", "diario"... */
    val source: String,
)

/** Estado de memoria FSRS. Una fila por ítem (palabra o gramática). */
@Entity(
    tableName = "memory_states",
    primaryKeys = ["itemType", "itemId"],
)
data class MemoryState(
    val itemType: String,
    val itemId: Long,
    /** Días que aguanta en memoria con 90 % de probabilidad de recordarlo. */
    val stability: Double,
    /** 1 (fácil) a 10 (difícil). */
    val difficulty: Double,
    val lastReviewAt: Long,
    val dueAt: Long,
    val reps: Int,
    val lapses: Int,
    val sentToAnki: Boolean = false,
)

/** Qué pasó con un ítem en un diario. Con este historial se puede recalcular FSRS. */
object EventKind {
    /** Usado bien, sin que la app lo sugiriera. */
    const val CORRECT_SPONTANEOUS = "correcto_espontaneo"
    /** Usado bien, pero estaba en las sugerencias. */
    const val CORRECT_SUGGESTED = "correcto_sugerido"
    const val INCORRECT = "incorrecto"
    /** Sugerido y no usado: se registra sin calificación ni penalización. */
    const val SUGGESTED_UNUSED = "sugerido_no_usado"
    /** Palabra nueva descubierta en el diario. */
    const val DISCOVERED = "descubierta"
}

@Entity(
    tableName = "review_events",
    indices = [Index(value = ["itemType", "itemId"]), Index(value = ["pageId"])],
)
data class ReviewEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemType: String,
    val itemId: Long,
    val at: Long,
    val kind: String,
    /** Calificación FSRS 1–4, o null si el evento no cuenta como repaso. */
    val rating: Int?,
    val pageId: Long?,
)

@Entity(tableName = "entries", indices = [Index(value = ["date"], unique = true)])
data class DiaryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** yyyy-MM-dd */
    val date: String,
    val createdAt: Long,
)

object PageStatus {
    const val DRAFT = "borrador"
    const val PENDING = "pendiente"
    const val REVIEWED = "revisada"
}

@Entity(tableName = "pages", indices = [Index(value = ["entryId"])])
data class DiaryPage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: Long,
    /** Trazos originales serializados (ver InkSerializer). Se guardan para reconocer de nuevo. */
    val inkJson: String = "[]",
    val canvasWidth: Int = 0,
    val recognizedText: String? = null,
    val confirmedText: String? = null,
    val status: String = PageStatus.DRAFT,
    /** Cuántas líneas entraron en la revisión. Lo que está debajo es la reescritura. */
    val reviewedLineCount: Int = 0,
    /** Sugerencias mostradas al abrir la página: "word:12,grammar:3". */
    val suggestedItems: String = "",
    val createdAt: Long,
    val updatedAt: Long,
)

object CorrectionStatus {
    const val PENDING = "pendiente"
    const val REWRITTEN = "reescrita"
    /** Mejora sugerida: informativa, no hay que reescribirla. */
    const val SUGGESTION = "sugerencia"
}

@Entity(tableName = "corrections", indices = [Index(value = ["pageId"])])
data class Correction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pageId: Long,
    val sentence: Int,
    val original: String,
    val suggestion: String,
    /** ortografia, particula, conjugacion, gramatica, naturalidad. */
    val type: String,
    val explanation: String,
    /** pasado, presente, futuro (tiempo de la oración). */
    val tense: String?,
    val itemType: String?,
    val itemId: Long?,
    val status: String = CorrectionStatus.PENDING,
)

/** Oraciones de una página con su tiempo verbal, para estadísticas. */
@Entity(tableName = "sentences", indices = [Index(value = ["pageId"])])
data class Sentence(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pageId: Long,
    val number: Int,
    val text: String,
    val tense: String,
)

/** Palabra nueva que no pasó la validación automática y espera revisión manual. */
@Entity(tableName = "pending_words", indices = [Index(value = ["baseForm"], unique = true)])
data class PendingWord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val baseForm: String,
    val type: String,
    val meaning: String?,
    val pageId: Long?,
    val createdAt: Long,
)
