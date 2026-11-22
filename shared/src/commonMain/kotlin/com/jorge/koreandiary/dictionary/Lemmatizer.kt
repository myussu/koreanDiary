package com.jorge.koreandiary.dictionary

/**
 * La forma de diccionario de una palabra escrita (만났어요 → 만나다, 은메달을 → 은메달) sin IA, con
 * los datos del 한국어기초사전 (decidido con Jorge el 2026-11-22). Los verbos irregulares salen de las
 * conjugaciones que trae el diccionario (덥다 → 더워, 더우니); los regulares, de las reglas.
 *
 * Cada verbo deja cuatro bases a las que se pegan las terminaciones: el tema (먹-고), la base de
 * las terminaciones con 으 (먹으-면, 더우-면), la forma en -아/-어 con sus contracciones (먹어-요,
 * 꿈꿔-요) y el pasado (먹었-어요). Desconjugar es quitar una terminación conocida y buscar la base.
 * En la prueba con datos de 2019: 97,7 % en una lista a mano y 95,9 % de los verbos de las
 * oraciones del diccionario (claude/diccionario-prueba.md en el proyecto).
 */
internal class Lemmatizer(entries: Collection<DictEntry>) {
    private val stems = HashMap<String, MutableList<DictEntry>>()
    private val nBases = HashMap<String, MutableList<DictEntry>>()
    private val aeForms = HashMap<String, MutableList<DictEntry>>()
    private val pasts = HashMap<String, MutableList<DictEntry>>()
    private val direct = HashMap<String, MutableList<DictEntry>>()

    init {
        for (e in entries) if (e.isVerb) addVerb(e)
    }

    private fun MutableMap<String, MutableList<DictEntry>>.link(key: String?, e: DictEntry) {
        if (key.isNullOrEmpty()) return
        val list = getOrPut(key) { mutableListOf() }
        if (list.none { it.id == e.id }) list += e
    }

    private fun addVerb(e: DictEntry) {
        val stem = e.form.removeSuffix("다")
        if (stem.isEmpty() || !stem.all(Hangul::isSyllable)) return
        stems.link(stem, e)
        e.conjugations.forEach { direct.link(it, e) }
        // Base de las terminaciones con 으: la del diccionario (더우니 → 더우) o la regular.
        val fromDict = e.conjugations.filter { it.endsWith("니") && !it.endsWith("니다") }.map { it.dropLast(1) }
        fromDict.forEach { nBases.link(it, e) }
        when {
            Hangul.finalOf(stem) == "ㄹ" -> { nBases.link(stem, e); nBases.link(Hangul.dropFinal(stem), e) }
            fromDict.isEmpty() -> nBases.link(if (Hangul.hasFinal(stem)) stem + "으" else stem, e)
        }
        // Forma en -아/-어: las del diccionario (더워, 꿈꾸어, 가감해) o la regular.
        val aes = e.conjugations.filter(::looksLikeAe).toMutableSet()
        if (aes.isEmpty()) regularAe(stem)?.let { aes += it }
        for (a in aes.flatMap(Hangul::contractions)) {
            aeForms.link(a, e)
            pasts.link(Hangul.addFinal(a, 'ㅆ'), e)
        }
    }

    private fun looksLikeAe(c: String): Boolean {
        val p = c.lastOrNull()?.let(Hangul::parts) ?: return false
        return p.final == 0 && p.vowelJamo in AE_VOWELS && !c.endsWith("거라") && !c.endsWith("너라") && !c.endsWith("니")
    }

    private fun regularAe(stem: String): String? {
        if (stem.endsWith("하")) return stem + "여"
        val p = stem.lastOrNull()?.let(Hangul::parts) ?: return null
        return stem + if (p.vowelJamo == 'ㅏ' || p.vowelJamo == 'ㅗ') "아" else "어"
    }

    /** Los verbos y adjetivos que pueden ser [word] (sin puntuación), sin repetir. */
    fun verbs(word: String): List<DictEntry> {
        val out = LinkedHashMap<Int, DictEntry>()
        fun add(list: List<DictEntry>?) = list?.forEach { out.putIfAbsent(it.id, it) }
        add(direct[word])
        for (e in STEM_ENDINGS) {
            if (!word.endsWith(e) || word.length <= e.length) continue
            val base = word.dropLast(e.length)
            add(stems[base])
            // La ㄹ final cae ante ㄴ y ㅅ: 만드는 = 만들 + 는.
            if (Hangul.parts(e[0])?.initialJamo.let { it == 'ㄴ' || it == 'ㅅ' }) add(Hangul.addFinal(base, 'ㄹ')?.let(stems::get))
        }
        for (e in N_ENDINGS) {
            if (Hangul.isJamo(e[0])) {
                // La terminación empieza pegada a la última sílaba: 만난 = 만나 + ㄴ, 갈 = 가 + ㄹ.
                val rest = e.substring(1)
                if (!word.endsWith(rest) || word.length <= rest.length) continue
                val head = word.dropLast(rest.length)
                if (Hangul.finalOf(head) != e[0].toString()) continue
                add(nBases[Hangul.dropFinal(head)])
            } else if (word.endsWith(e) && word.length > e.length) {
                add(nBases[word.dropLast(e.length)])
            }
        }
        for (e in A_ENDINGS) if (word.endsWith(e) && word.length > e.length) add(aeForms[word.dropLast(e.length)])
        add(aeForms[word])
        for (e in P_ENDINGS) if (word.endsWith(e) && word.length > e.length) add(pasts[word.dropLast(e.length)])
        return out.values.toList()
    }

    companion object {
        private val AE_VOWELS = setOf('ㅏ', 'ㅓ', 'ㅐ', 'ㅕ', 'ㅘ', 'ㅝ', 'ㅙ', 'ㅔ', 'ㅒ')

        /** Se pegan al tema: 먹-고, 먹-습니다, 가-는데. */
        val STEM_ENDINGS = ("다 고 지 지만 게 기 기가 기는 기도 기로 기에 기를 는 는데 는지 네요 고요 겠다 겠어요 겠습니다 겠지만 던 " +
            "다가 자 죠 지요 잖아요 거나 도록 고서 느라고 느라 는구나 군요 는군요 냐 고는 고도 더니 다고 는다고 자면 냐고 느니 지도 " +
            "게도 기란 다면 던데 거든 거든요 길래 다니 기만 지는 고자 든지 든가 더라도 던가 더라 게요 고말고 습니다 습니까 는다 " +
            "네 는가 는가요 기에는 기보다 곤").split(" ").distinct().sortedByDescending { it.length }

        /** Se pegan a la base con 으 (먹으-면, 가-면); las que empiezan con letra suelta, a su última sílaba. */
        val N_ENDINGS = ("면 니까 니 세요 셔요 셨어요 셨다 셨습니다 시고 시는 시면 려고 려면 러 ㄴ ㄹ ㄹ까요 ㄹ게요 ㄹ래요 ㄹ수록 ㄴ데 ㄴ지 " +
            "ㅂ니다 ㅂ니까 ㄴ다 ㅁ 며 면서 라고 라 시며 시면서 신다 신다고 시더니 ㄹ까 ㄹ지 ㄹ지도 므로 ㄴ다고 니까요 ㄹ래 ㄹ게 ㄴ가요 " +
            "려는 려고요 시니 실 신 셔서 셔도 시지만 시는데 ㄴ데요 ㄹ거야 ㄹ걸 ㄴ다면 시겠어요 십시오 ㅂ시다 ㄹ까봐 ㄴ적").split(" ").distinct().sortedByDescending { it.length }

        /** Se pegan a la forma en -아/-어: 먹어-요, 꿈꿔-서. */
        val A_ENDINGS = listOf("요", "서", "도", "야", "야지", "라", "야겠다", "야겠어요", "야만", "서요", "요?", "주세요", "줘요", "보세요", "봐요")
            .sortedByDescending { it.length }

        /** Se pegan al pasado: 먹었-어요, 만났-는데. */
        val P_ENDINGS = ("어요 어 다 는데 고 지만 어서 으면 을 습니다 네요 죠 던 겠다 겠어요 으니까 으니 지 기 어도 거든요 잖아요 을까요 " +
            "는지 을지 더니 다고 으며 으면서 던데 다면 냐고 는데요 지요 을까 거나 다가 어야 었다").split(" ").distinct().sortedByDescending { it.length }

        /** Partículas que se quitan para encontrar el sustantivo: 은메달을 → 은메달. */
        val PARTICLES = ("이 가 은 는 을 를 와 과 으로 로 이랑 랑 이나 나 이에요 예요 이야 야 입니다 이다 에 에서 에게 한테 께 의 도 만 " +
            "까지 부터 보다 처럼 같이 마다 밖에 이라도 라도 에는 에서는 에도 에서도 으로는 로는 하고 이랑은 에게는 한테는 께서 께서는 " +
            "이지만 이고 인데 이라서 라서 만큼 뿐 쯤 들 들이 들은 들을 들도 들의 들에게 이에게 이었어요 였어요 이었다 였다 이라고 라고 " +
            "으로도 로도 까지는 부터는 처럼요 이요 요 이죠 죠 이네요 네요 이니까 니까").split(" ").distinct().sortedByDescending { it.length }
    }
}
