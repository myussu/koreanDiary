package com.jorge.koreandiary.ai

/**
 * Comprueba el desglose («Partes») que da la IA: las partes en coreano, juntas, tienen que formar la
 * palabra. Pedido por Jorge el 2026-11-20, cuando la IA desarmó 일으키다 como 일다 + -이- + -기다
 * (lo correcto es 일다 + -으키-): un desglose inventado desvía el estudio. Si no cuadra, la app lo
 * muestra como dudoso, sin esconderlo: las palabras irregulares (고마움 = 고맙다 + -음) tampoco
 * cuadran letra por letra.
 */
object WordParts {
    /** Lo que va entre comillas o paréntesis son significados o hanja, no partes. */
    private val GLOSS = Regex("""«[^»]*»|"[^"]*"|“[^”]*”|\([^)]*\)|\[[^\]]*]""")
    private val HANGUL = Regex("""[가-힣]+""")
    private val JAMO = Regex("""[ㄱ-ㅣ]""")

    /**
     * ¿Las partes forman [form]? A cada parte se le puede quitar el -다 del diccionario (일다 + -으키-
     * + -다 = 일으키다). null si no se puede comprobar: menos de dos partes en coreano, jamo sueltos
     * (꾸다 + -ㅁ), o una forma con letras que no son sílabas coreanas (V-느라고, -(으)ㄹ 수 있다).
     */
    fun fits(form: String, parts: String): Boolean? {
        val target = form.trim().replace("-", "").replace(" ", "")
        if (target.isEmpty() || target.any { it !in '가'..'힣' }) return null
        // Solo la primera frase, y lo que va después de «=», «→» o «:» (은메달 = 은 + 메달).
        val body = parts.substringBefore(';').let { s -> s.substring(maxOf(s.lastIndexOf('='), s.lastIndexOf('→'), s.lastIndexOf(':')) + 1) }
        val bare = GLOSS.replace(body, " ")
        if (JAMO.containsMatchIn(bare)) return null
        val pieces = bare.split('+').map { seg -> HANGUL.findAll(seg).joinToString("") { it.value } }.filter { it.isNotEmpty() }
        if (pieces.size < 2) return null
        val targets = listOfNotNull(target, target.removeSuffix("다").takeIf { target.endsWith("다") && target.length > 1 })
        return targets.any { joins(pieces, 0, it, 0) }
    }

    private fun joins(pieces: List<String>, i: Int, target: String, pos: Int): Boolean {
        if (i == pieces.size) return pos == target.length
        val piece = pieces[i]
        val options = if (piece.endsWith("다")) listOf(piece, piece.dropLast(1)) else listOf(piece)
        return options.any { target.startsWith(it, pos) && joins(pieces, i + 1, target, pos + it.length) }
    }
}
