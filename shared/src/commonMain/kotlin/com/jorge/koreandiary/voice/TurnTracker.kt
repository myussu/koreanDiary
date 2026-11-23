package com.jorge.koreandiary.voice

/**
 * Decide cuándo terminó de verdad el turno de la IA.
 *
 * En los modelos con razonamiento de fondo ([LiveModels.EXTENDED_THINKING]) `turnComplete` solo cierra una
 * frase (p. ej. «a ver…») y el modelo puede seguir pensando, llamando funciones y hablando mientras
 * `interactionStatus` sea IN_PROGRESS: el turno termina recién con IDLE. Los modelos estándar no mandan
 * estado, así que su `turnComplete` pasa tal cual.
 *
 * La pantalla y la reconexión (que espera el fin del turno para no cortar a la IA) reciben un solo
 * [LiveEvent.TurnComplete] en el momento correcto. Los [LiveEvent.Status] se consumen aquí.
 * No es seguro entre hilos: lo usa solo el listener de la conexión actual.
 */
class TurnTracker {
    private var busy = false
    private var pending = false

    /** Los eventos que deben seguir hacia la pantalla en lugar de [event] (ninguno, el mismo o un TurnComplete). */
    fun filter(event: LiveEvent): List<LiveEvent> = when (event) {
        is LiveEvent.Status -> if (event.idle) {
            busy = false
            if (pending) {
                pending = false
                listOf(LiveEvent.TurnComplete)
            } else {
                emptyList()
            }
        } else {
            busy = true
            emptyList()
        }
        LiveEvent.TurnComplete -> if (busy) {
            pending = true
            emptyList()
        } else {
            listOf(event)
        }
        else -> listOf(event)
    }

    /**
     * Conexión nueva: el razonamiento de la anterior no sigue. Si quedaba un turno abierto se cierra,
     * para que la pantalla no espere un IDLE que ya no llegará.
     */
    fun reset(): List<LiveEvent> {
        val closeOpenTurn = pending
        busy = false
        pending = false
        return if (closeOpenTurn) listOf(LiveEvent.TurnComplete) else emptyList()
    }
}
