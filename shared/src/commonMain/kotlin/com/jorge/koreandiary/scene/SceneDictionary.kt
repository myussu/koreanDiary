package com.jorge.koreandiary.scene

/**
 * Dónde aparece un elemento en los bordes de la página, como si miraras la escena desde adentro:
 * lo que pisas abajo, lo que está a tu altura a los lados, el cielo arriba y lo que está en el aire
 * por todo el borde.
 */
enum class SceneZone { GROUND, HORIZON, SKY, AIR }

/** Cómo se mueve (si el sistema no tiene las animaciones apagadas). */
enum class SceneMotion { NONE, WAVE, SWAY, DRIFT, FALL, TWINKLE, PULSE }

/**
 * Un elemento de la escena viva. [palette] es una clave de la paleta de escenas de cada app (clara y
 * oscura), no un color fijo. [name] es en español, para la IA y para mostrarlo.
 */
data class SceneElement(
    val id: String,
    val name: String,
    val zone: SceneZone,
    val palette: String,
    val motion: SceneMotion,
)

/**
 * Palabra coreana → elemento de la escena, sin IA. Reacciona al instante a lo que reconoce ML Kit.
 *
 * Coincidencia:
 * - Claves de dos sílabas o más: al inicio de la palabra (바다를, 바닷가… → 바다 por su clave 바닷).
 * - Claves de una sílaba (해, 비, 눈, 별…): solo la palabra sola o con una partícula (해가, 비를,
 *   별처럼), porque al inicio de otra palabra significan otra cosa (해요, 비싸요, 별로).
 * Una lectura errónea suma o quita un elemento: es inofensivo; Revisar corrige el texto.
 */
object SceneDictionary {

    val elements: List<SceneElement> = listOf(
        // Suelo
        SceneElement("grass", "pasto", SceneZone.GROUND, "grass", SceneMotion.SWAY),
        SceneElement("wheat", "campo de trigo", SceneZone.GROUND, "wheat", SceneMotion.SWAY),
        SceneElement("sand", "arena, playa", SceneZone.GROUND, "sand", SceneMotion.NONE),
        SceneElement("flowers", "flores", SceneZone.GROUND, "flowers", SceneMotion.SWAY),
        SceneElement("leaves", "hojas caídas", SceneZone.GROUND, "autumn", SceneMotion.NONE),
        SceneElement("street", "calle, ciudad", SceneZone.GROUND, "stone", SceneMotion.NONE),
        // Horizonte
        SceneElement("sea", "mar", SceneZone.HORIZON, "sea", SceneMotion.WAVE),
        SceneElement("lake", "lago, río", SceneZone.HORIZON, "water", SceneMotion.WAVE),
        SceneElement("mountain", "montaña", SceneZone.HORIZON, "mountain", SceneMotion.NONE),
        SceneElement("forest", "bosque, árboles", SceneZone.HORIZON, "forest", SceneMotion.SWAY),
        SceneElement("city", "edificios", SceneZone.HORIZON, "stone", SceneMotion.NONE),
        // Cielo
        SceneElement("sky", "cielo", SceneZone.SKY, "sky", SceneMotion.NONE),
        SceneElement("sun", "sol", SceneZone.SKY, "sun", SceneMotion.PULSE),
        SceneElement("sunset", "atardecer", SceneZone.SKY, "sunset", SceneMotion.PULSE),
        SceneElement("clouds", "nubes", SceneZone.SKY, "clouds", SceneMotion.DRIFT),
        SceneElement("night", "noche", SceneZone.SKY, "night", SceneMotion.NONE),
        SceneElement("moon", "luna", SceneZone.SKY, "moon", SceneMotion.PULSE),
        SceneElement("stars", "estrellas", SceneZone.SKY, "stars", SceneMotion.TWINKLE),
        SceneElement("rainbow", "arcoíris", SceneZone.SKY, "rainbow", SceneMotion.NONE),
        // Aire
        SceneElement("wind", "viento", SceneZone.AIR, "wind", SceneMotion.DRIFT),
        SceneElement("rain", "lluvia", SceneZone.AIR, "rain", SceneMotion.FALL),
        SceneElement("snow", "nieve", SceneZone.AIR, "snow", SceneMotion.FALL),
        SceneElement("petals", "pétalos", SceneZone.AIR, "flowers", SceneMotion.FALL),
        SceneElement("fog", "niebla", SceneZone.AIR, "fog", SceneMotion.DRIFT),
        SceneElement("sparkle", "brillo", SceneZone.AIR, "sparkle", SceneMotion.TWINKLE),
        SceneElement("warm", "calidez", SceneZone.AIR, "warm", SceneMotion.PULSE),
        SceneElement("cool", "frescura", SceneZone.AIR, "cool", SceneMotion.DRIFT),
    )

    private val byId = elements.associateBy { it.id }

    fun element(id: String): SceneElement? = byId[id]

    /** Clave → id. Las claves de una sílaba piden la palabra sola o con partícula. */
    private val keys: Map<String, String> = mapOf(
        // Suelo
        "잔디" to "grass", "풀" to "grass", "풀밭" to "grass", "초원" to "grass", "들판" to "grass", "초록" to "grass",
        "밀밭" to "wheat", "밀" to "wheat", "보리" to "wheat", "황금" to "wheat", "금빛" to "wheat", "벼" to "wheat", "논" to "wheat",
        "모래" to "sand", "해변" to "sand", "해수욕" to "sand", "백사장" to "sand",
        "꽃" to "flowers", "꽃밭" to "flowers", "장미" to "flowers", "튤립" to "flowers",
        "낙엽" to "leaves", "단풍" to "leaves", "가을" to "leaves",
        "길" to "street", "거리" to "street", "골목" to "street", "도로" to "street",
        // Horizonte
        "바다" to "sea", "바닷" to "sea", "파도" to "sea", "해안" to "sea", "수평선" to "sea", "물결" to "sea",
        "호수" to "lake", "강" to "lake", "강물" to "lake", "시냇" to "lake", "개울" to "lake", "연못" to "lake",
        "산" to "mountain", "언덕" to "mountain", "봉우리" to "mountain",
        "숲" to "forest", "나무" to "forest", "나뭇" to "forest", "공원" to "forest",
        "도시" to "city", "건물" to "city", "빌딩" to "city",
        // Cielo
        "하늘" to "sky", "파랗" to "sky", "파란" to "sky", "파래" to "sky", "푸른" to "sky", "푸르" to "sky",
        "해" to "sun", "햇빛" to "sun", "햇살" to "sun", "태양" to "sun", "햇볕" to "sun", "맑" to "sun", "밝" to "sun",
        "노을" to "sunset", "석양" to "sunset", "저녁" to "sunset", "해질" to "sunset", "해가 지" to "sunset",
        "구름" to "clouds", "흐리" to "clouds", "흐려" to "clouds", "흐린" to "clouds",
        "밤" to "night", "밤하늘" to "night", "어둡" to "night", "어두" to "night", "깜깜" to "night",
        "달" to "moon", "달빛" to "moon", "보름달" to "moon",
        "별" to "stars", "별빛" to "stars", "은하수" to "stars",
        "무지개" to "rainbow",
        // Aire
        "바람" to "wind", "산들" to "wind",
        "비" to "rain", "빗" to "rain", "빗방울" to "rain", "소나기" to "rain", "장마" to "rain",
        "눈" to "snow", "눈송이" to "snow", "함박눈" to "snow", "겨울" to "snow",
        "꽃잎" to "petals", "벚꽃" to "petals",
        "안개" to "fog",
        "반짝" to "sparkle", "빛나" to "sparkle", "빛난" to "sparkle", "빛이" to "sparkle",
        "따뜻" to "warm", "따듯" to "warm", "포근" to "warm",
        "시원" to "cool", "선선" to "cool", "상쾌" to "cool",
    )

    /**
     * Raíces de una sílaba que sí van al inicio (맑다 → 맑아요, 맑은): esa sílaba no empieza otras
     * palabras comunes.
     */
    private val stems = setOf("맑", "밝")

    /**
     * Partículas que pueden seguir a un sustantivo de una sílaba. Sin 로: «별로» (no mucho) no son
     * estrellas.
     */
    private val particles = listOf(
        "", "이", "가", "은", "는", "을", "를", "에", "에서", "에는", "도", "의", "과", "와", "랑", "이랑",
        "으로", "처럼", "만", "까지", "부터", "보다", "같이", "같은", "들", "들이", "들은", "들을", "이에요", "예요", "이다",
    )

    /** Claves de varias palabras ("해가 지"): se buscan en el texto tal cual. */
    private val phraseKeys = keys.filterKeys { ' ' in it }
    private val singleSyllable = keys.filterKeys { ' ' !in it && it.length == 1 && it !in stems }
    private val prefixKeys = keys.filterKeys { ' ' !in it && (it.length >= 2 || it in stems) }.entries.sortedByDescending { it.key.length }

    private val wordSplit = Regex("[^\\p{IsHangul}]+")

    /**
     * Elementos que aparecen en [text], en el orden en que se escribieron, sin repetir: la escena
     * se arma elemento por elemento a medida que escribes.
     */
    fun detect(text: String): List<SceneElement> {
        if (text.isBlank()) return emptyList()
        val found = LinkedHashMap<String, Int>()
        fun add(id: String, at: Int) {
            if (id !in found || found.getValue(id) > at) found[id] = at
        }
        phraseKeys.forEach { (key, id) ->
            val i = text.indexOf(key)
            if (i >= 0) add(id, i)
        }
        var position = 0
        for (word in text.split(wordSplit)) {
            if (word.isEmpty()) continue
            val at = text.indexOf(word, position).coerceAtLeast(0)
            position = at + word.length
            val prefix = prefixKeys.firstOrNull { word.startsWith(it.key) }
            if (prefix != null) {
                add(prefix.value, at)
                continue
            }
            val head = word.substring(0, 1)
            val single = singleSyllable[head] ?: continue
            if (word.substring(1) in particles) add(single, at)
        }
        return found.entries.sortedBy { it.value }.mapNotNull { byId[it.key] }
    }

    /** Ids de la lista para el prompt: «id (nombre)». */
    fun promptList(): String = elements.joinToString(", ") { "${it.id} (${it.name})" }
}
