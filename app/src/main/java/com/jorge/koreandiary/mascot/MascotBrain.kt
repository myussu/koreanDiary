package com.jorge.koreandiary.mascot

import java.text.Normalizer
import java.time.LocalDate

/** Expresiones de la mascota. [fileNames]: nombres aceptados para las imágenes de una mascota importada. */
enum class MascotMood(val fileNames: List<String>) {
    IDLE(listOf("idle", "normal")),
    HAPPY(listOf("feliz", "happy")),
    THINKING(listOf("pensando", "thinking")),
    SURPRISED(listOf("sorprendida", "sorprendido", "surprised")),
    ENCOURAGE(listOf("animo", "encourage")),
    SLEEPY(listOf("dormida", "dormido", "sleepy"));

    /** Nombre con el que se guarda la imagen importada. */
    val storedName get() = fileNames.first()

    companion object {
        /** "Ánimo.PNG" → ENCOURAGE; null si el nombre no corresponde a ninguna expresión. */
        fun fromFileName(name: String): MascotMood? {
            val base = name.substringAfterLast('/').substringBeforeLast('.').trim().lowercase()
            val plain = Normalizer.normalize(base, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            return entries.firstOrNull { plain in it.fileNames }
        }
    }
}

/** Una frase de la mascota: en coreano sencillo (práctica) con su traducción. */
data class MascotLine(val korean: String, val spanish: String, val mood: MascotMood)

/**
 * Lógica de la mascota sin IA ni tokens: racha de días y qué decir al abrir la app.
 * Las frases son de nivel básico a propósito: hasta el saludo es lectura en coreano.
 */
object MascotBrain {

    /** Días seguidos con al menos una página, contando hasta hoy (o hasta ayer si hoy aún no escribiste). */
    fun streak(dates: Collection<LocalDate>, today: LocalDate): Int {
        val days = dates.toSet()
        var day = if (today in days) today else today.minusDays(1)
        var count = 0
        while (day in days) {
            count++
            day = day.minusDays(1)
        }
        return count
    }

    fun greeting(dates: Collection<LocalDate>, today: LocalDate, hour: Int): MascotLine {
        if (dates.isEmpty()) return MascotLine("같이 일기 써 봐요!", "¡Escribamos un diario juntos!", MascotMood.HAPPY)
        val last = dates.max()
        val daysAway = today.toEpochDay() - last.toEpochDay()
        val streak = streak(dates, today)
        return when {
            daysAway >= 3 -> MascotLine("오랜만이에요! 보고 싶었어요.", "¡Cuánto tiempo! Te extrañé.", MascotMood.SLEEPY)
            last == today && streak >= 3 -> MascotLine("벌써 ${streak}일째예요! 대단해요.", "¡Ya van $streak días seguidos! Increíble.", MascotMood.HAPPY)
            last == today -> MascotLine("오늘도 썼네요! 잘했어요.", "¡Hoy también escribiste! Bien hecho.", MascotMood.HAPPY)
            streak >= 2 -> MascotLine("${streak}일 연속이에요. 오늘도 써요!", "Llevas $streak días seguidos. ¡Escribe hoy también!", MascotMood.ENCOURAGE)
            hour < 12 -> MascotLine("좋은 아침이에요! 오늘 계획이 뭐예요?", "¡Buenos días! ¿Qué planes tienes hoy?", MascotMood.IDLE)
            hour < 19 -> MascotLine("오늘 어땠어요?", "¿Cómo te fue hoy?", MascotMood.IDLE)
            else -> MascotLine("오늘 하루 수고했어요. 일기 쓸까요?", "Buen trabajo hoy. ¿Escribimos el diario?", MascotMood.IDLE)
        }
    }
}
