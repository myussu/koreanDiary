package com.jorge.koreandiary.dictionary

/**
 * Las traducciones del 한국어기초사전 mezclan español de España y de América (휴대폰 → «teléfono
 * móvil», 자동차 → «coche»). Al mostrarlas se cambian algunas palabras de España por las de Perú
 * (decidido con Jorge el 2026-11-22). Solo palabras de un único sentido: «piso» (suelo o
 * departamento), «conducir» (manejar o llevar) o «billete» (boleto o dinero) se quedan como están.
 */
object PeruSpanish {
    private val WORDS = listOf(
        "teléfonos móviles" to "celulares", "teléfono móvil" to "celular",
        "coches" to "carros", "coche" to "carro",
        "ordenadores" to "computadoras", "ordenador" to "computadora",
        "zumos" to "jugos", "zumo" to "jugo",
        "patatas" to "papas", "patata" to "papa",
        "melocotones" to "duraznos", "melocotón" to "durazno",
        "cacahuetes" to "maníes", "cacahuete" to "maní",
        "gafas" to "lentes",
        "aparcamientos" to "estacionamientos", "aparcamiento" to "estacionamiento", "aparcar" to "estacionar",
        "neveras" to "refrigeradoras", "nevera" to "refrigeradora",
        "bolígrafos" to "lapiceros", "bolígrafo" to "lapicero",
        "coger" to "agarrar",
        "vosotros" to "ustedes", "vosotras" to "ustedes",
    )

    private val PATTERNS = WORDS.map { (spain, peru) ->
        Regex("(?<![\\p{L}])" + Regex.escape(spain) + "(?![\\p{L}])", RegexOption.IGNORE_CASE) to peru
    }

    /** El texto con las palabras de España cambiadas, respetando la mayúscula inicial. */
    fun adapt(text: String): String {
        var out = text
        for ((pattern, peru) in PATTERNS) {
            out = pattern.replace(out) { m -> if (m.value.first().isUpperCase()) peru.replaceFirstChar { it.uppercase() } else peru }
        }
        return out
    }

    /**
     * Una traducción corta («papa, patata»): adaptada y sin repetir lo que queda igual
     * («papa, papa» → «papa»).
     */
    fun adaptLemma(lemma: String): String =
        adapt(lemma).split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }.joinToString(", ")
}
