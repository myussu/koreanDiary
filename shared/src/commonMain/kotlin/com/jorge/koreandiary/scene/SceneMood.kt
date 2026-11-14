package com.jorge.koreandiary.scene

/**
 * Qué partículas acompañan un tono. El orden es el número que recibe el sombreador del tono
 * (`uKind` en [SceneShaders]): no reordenar.
 */
enum class MoodParticles { MOTES, DUST, TWINKLES, DROPS, FLICKER, WAVES }

/**
 * El sentimiento de una escena, no lo que pasa en ella: «tomo su mano» es una acción simple, pero
 * el tono es amor. Lo lee la IA al revisar y en «¿Cómo digo?»; cambia la luz de los bordes.
 * [name] es en español, para la IA. [speed] multiplica el tiempo del sombreador.
 */
data class SceneMood(
    val id: String,
    val name: String,
    val particles: MoodParticles,
    val speed: Float,
)

/** Un tono leído por la IA y su intensidad: 1 apenas, 2 claro, 3 muy fuerte. */
data class MoodReading(val id: String, val level: Int) {
    /** Cuánto se nota en pantalla (0..1). */
    val intensity: Float get() = when (level) {
        1 -> 0.55f
        2 -> 0.8f
        else -> 1f
    }
}

object SceneMoods {

    val all: List<SceneMood> = listOf(
        SceneMood("love", "amor, ternura", MoodParticles.MOTES, 0.6f),
        SceneMood("calm", "calma, paz", MoodParticles.WAVES, 0.4f),
        SceneMood("joy", "alegría", MoodParticles.TWINKLES, 1.0f),
        SceneMood("nostalgia", "nostalgia, recuerdo", MoodParticles.DUST, 0.5f),
        SceneMood("sad", "tristeza, melancolía", MoodParticles.DROPS, 0.5f),
        SceneMood("awe", "asombro, maravilla", MoodParticles.TWINKLES, 0.6f),
        SceneMood("excited", "emoción, entusiasmo", MoodParticles.MOTES, 1.4f),
        SceneMood("tense", "miedo, inquietud", MoodParticles.FLICKER, 1.0f),
    )

    private val byId = all.associateBy { it.id }

    fun byId(id: String?): SceneMood? = id?.let { byId[it.trim().lowercase()] }

    /** Una lectura válida o null: id conocido e intensidad entre 1 y 3. */
    fun reading(id: String?, level: Int): MoodReading? = byId(id)?.let { MoodReading(it.id, level.coerceIn(1, 3)) }

    /** Ids para el prompt: «id (nombre)». */
    fun promptList(): String = all.joinToString(", ") { "${it.id} (${it.name})" }
}
