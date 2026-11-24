package com.jorge.koreandiary.settings

/**
 * Un modelo registrado con el que se puede explicar: Azure (su despliegue) o Gemini con un modelo.
 * Si el elegido falla (saturado, sin cuota, sin acceso), «Probar con …» pasa al siguiente (pedido
 * por Jorge el 2026-11-24, cuando gemini-4-hydrogen respondió 503).
 */
data class ExplainOption(val providerId: String, val model: String) {
    /** Lo que dice el botón: «Azure» o el nombre del modelo de Gemini. */
    val label: String get() = if (providerId == ProviderId.AZURE) "Azure" else model
}

object ExplainChoice {
    /**
     * Los registrados, sin repetir: el elegido primero, luego Azure (si está configurado) y los
     * modelos de Gemini que ya están en Ajustes (explicaciones, el de corregir, el de Escuchar).
     */
    fun options(preferred: ExplainOption?, azureDeployment: String?, geminiModels: List<String>): List<ExplainOption> =
        (listOfNotNull(preferred, azureDeployment?.let { ExplainOption(ProviderId.AZURE, it.trim()) }) +
            geminiModels.map { ExplainOption(ProviderId.GEMINI, it.trim()) })
            .filter { it.model.isNotBlank() }
            .distinct()

    /** El que sigue a [current], dando la vuelta, para «Probar con …». Null si no hay otro. */
    fun next(options: List<ExplainOption>, current: ExplainOption?): ExplainOption? {
        current ?: return null
        val i = options.indexOf(current)
        return (options.drop(i + 1) + options.take(maxOf(i, 0))).firstOrNull { it != current }
    }
}
