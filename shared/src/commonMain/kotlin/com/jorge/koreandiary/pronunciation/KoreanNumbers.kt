package com.jorge.koreandiary.pronunciation

/**
 * Los números escritos con cifras, como se leen (para «Cómo suena»): 3시 → 세시, 30분 → 삼십분,
 * 2026년 → 이천이십육년, 6월 → 유월. Con los contadores que van con los números coreanos nativos (개,
 * 명, 살, 시…) y hasta 99, los nativos (한, 두, 세, 스무); con el resto, los sino-coreanos. Basado en
 * los números de g2pK (Apache-2.0), con más contadores (명, 사람, 시간, 잔…).
 */
object KoreanNumbers {

    /**
     * Los contadores que se cuentan con 하나, 둘, 셋 (en su forma corta: 한 개, 두 명). 번 y 대 no:
     * con cifras casi siempre son un número (3번 버스 [삼 번], 20대 [이십 대]).
     */
    private val NATIVE_COUNTERS = listOf(
        "시간", "사람", "마리", "켤레", "송이", "그루", "군데", "가지", "번째", "개", "명", "살", "시", "권", "병", "잔", "장",
        "벌", "달", "곳", "통", "채", "척", "모금", "봉지", "곡", "판",
    ).sortedByDescending { it.length }

    private val DIGITS = listOf("영", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구")
    private val NATIVE_ONES = listOf("", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉")
    private val NATIVE_TENS = listOf("", "열", "스물", "서른", "마흔", "쉰", "예순", "일흔", "여든", "아흔")
    private val GROUPS = listOf("", "만", "억", "조", "경")

    private val NUMBER = Regex("""\d[\d,]*(\.\d+)?""")

    /** [text] con cada número escrito en letras. */
    fun spell(text: String): String = NUMBER.replace(text) { m ->
        val raw = m.value.trimEnd(',')
        val after = text.substring(m.range.last + 1).trimStart()
        val decimal = raw.substringAfter('.', "")
        val integer = raw.substringBefore('.').replace(",", "")
        val n = integer.toLongOrNull() ?: return@replace m.value
        val counter = NATIVE_COUNTERS.firstOrNull { after.startsWith(it) }
        val words = when {
            decimal.isNotEmpty() -> sino(n) + "점" + decimal.map { DIGITS[it - '0'] }.joinToString("")
            counter == "번째" && n == 1L -> "첫"
            counter != null && n in 1..99 -> native(n.toInt())
            after.startsWith("월") && n == 6L -> "유"
            after.startsWith("월") && n == 10L -> "시"
            integer.length > 1 && integer.startsWith("0") -> integer.map { DIGITS[it - '0'] }.joinToString("")
            else -> sino(n)
        }
        words + m.value.removePrefix(raw)
    }

    /** 1 a 99 con los nativos, en la forma que va antes de un contador: 한, 스무, 스물한. */
    fun native(n: Int): String {
        if (n == 20) return "스무"
        return NATIVE_TENS[n / 10] + NATIVE_ONES[n % 10]
    }

    /** Los sino-coreanos: 16 → 십뉵 (suena [심뉵]: se suma una ㄴ ante 육), 10000 → 만, 2026 → 이천이십육. */
    fun sino(n: Long): String {
        if (n == 0L) return "영"
        val out = StringBuilder()
        var rest = n
        val parts = mutableListOf<Int>()
        while (rest > 0) {
            parts += (rest % 10000).toInt()
            rest /= 10000
        }
        for (g in parts.indices.reversed()) {
            val v = parts[g]
            if (v == 0) continue
            // 10000 es 만, no 일만; 일억 y 일조 sí llevan 일.
            out.append(if (v == 1 && g == 1) "" else group(v))
            out.append(GROUPS.getOrElse(g) { "" })
        }
        // Art. 29: 육 tras una sílaba con consonante final suena [뉵] (십육 [심뉵], 백육 [뱅뉵]); art. 26:
        // 십 tras 일, 칠, 팔 suena [씹] (칠십 [칠씹]).
        return out.toString().replace(Regex("(?<=[십백천만억])육"), "뉵").replace(Regex("(?<=[일칠팔])십"), "씹")
    }

    private fun group(v: Int): String {
        val sb = StringBuilder()
        val units = listOf("천" to 1000, "백" to 100, "십" to 10)
        var rest = v
        for ((name, size) in units) {
            val d = rest / size
            if (d > 0) {
                if (d > 1) sb.append(DIGITS[d])
                sb.append(name)
            }
            rest %= size
        }
        if (rest > 0) sb.append(DIGITS[rest])
        return sb.toString()
    }
}
