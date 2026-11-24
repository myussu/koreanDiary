package com.jorge.koreandiary

import com.jorge.koreandiary.pronunciation.KoreanNumbers
import com.jorge.koreandiary.pronunciation.Pronunciation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Cómo suena»: los ejemplos de las normas de pronunciación estándar (표준 발음법, los mismos que trae
 * g2pK en rules.txt), con la forma que elegimos donde hay dos permitidas.
 */
class PronunciationTest {

    private fun check(cases: Map<String, String>, sound: (String) -> String = { Pronunciation.of(it) }) {
        val wrong = cases.mapNotNull { (written, expected) ->
            val got = sound(written)
            if (got != expected) "$written → $got (esperado $expected)" else null
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun lasReglasSinDiccionario() = check(
        mapOf(
            // Art. 5: 져 쪄 쳐; ㅢ tras consonante; 의 fuera del comienzo y la partícula.
            "가져" to "가저", "다쳐" to "다처", "무늬" to "무니", "띄어쓰기" to "띠어쓰기", "희망" to "히망", "유희" to "유히",
            // Sin el diccionario no se sabe si 의 es la partícula: queda [의], que siempre vale.
            "의사" to "의사", "주의" to "주의", "우리의" to "우리의", "회의실" to "회이실",
            // Art. 9 a 11: la consonante final antes de otra o al final.
            "닦다" to "닥따", "키읔" to "키윽", "옷" to "옫", "웃다" to "욷따", "있다" to "읻따", "젖" to "젇", "빚다" to "빋따",
            "꽃" to "꼳", "쫓다" to "쫃따", "솥" to "솓", "뱉다" to "밷따", "앞" to "압", "덮다" to "덥따",
            "넋" to "넉", "넋과" to "넉꽈", "앉다" to "안따", "여덟" to "여덜", "넓다" to "널따", "외곬" to "외골", "핥다" to "할따",
            "값" to "갑", "없다" to "업따", "밟다" to "밥따", "밟는" to "밤는", "밟게" to "밥께", "넓죽하다" to "넙쭈카다",
            "닭" to "닥", "흙과" to "흑꽈", "맑다" to "막따", "늙지" to "늑찌", "삶" to "삼", "젊다" to "점따", "읊고" to "읍꼬",
            "맑게" to "말께", "묽고" to "물꼬", "읽거나" to "일꺼나",
            // Art. 12: la ㅎ.
            "놓고" to "노코", "좋던" to "조턴", "쌓지" to "싸치", "많고" to "만코", "않던" to "안턴", "닳지" to "달치",
            "각하" to "가카", "먹히다" to "머키다", "밟히다" to "발피다", "맏형" to "마텽", "좁히다" to "조피다", "넓히다" to "널피다",
            "꽂히다" to "꼬치다", "앉히다" to "안치다", "옷 한 벌" to "오 탄 벌", "꽃 한 송이" to "꼬 탄 송이", "숱하다" to "수타다",
            "닿소" to "다쏘", "많소" to "만쏘", "싫소" to "실쏘", "놓는" to "논는", "쌓네" to "싼네", "않네" to "안네", "뚫는" to "뚤른",
            "낳은" to "나은", "놓아" to "노아", "쌓이다" to "싸이다", "많아" to "마나", "않은" to "아는", "닳아" to "다라", "싫어도" to "시러도",
            "좋아요" to "조아요", "좋습니다" to "조씀니다", "괜찮아요" to "괜차나요", "축하해요" to "추카해요", "깨끗해요" to "깨끄태요",
            // Art. 13 y 14: antes de vocal.
            "깎아" to "까까", "옷이" to "오시", "있어" to "이써", "낮이" to "나지", "꽃을" to "꼬츨", "밭에" to "바테", "앞으로" to "아프로",
            "넋이" to "넉씨", "앉아" to "안자", "닭을" to "달글", "젊어" to "절머", "곬이" to "골씨", "핥아" to "할타", "읊어" to "을퍼",
            "값을" to "갑쓸", "없어" to "업써", "먹었어요" to "머거써요", "읽어요" to "일거요", "강아지" to "강아지",
            // Art. 15: entre palabras, por su sonido.
            // Entre palabras se muestran juntas, como se oyen.
            "밭 아래" to "바다래", "늪 앞" to "느밥", "꽃 위" to "꼬뒤", "넋 없다" to "너겁따", "닭 앞에" to "다가페", "못 와요" to "모돠요",
            "한국 음식은" to "한구금시근", "버릇없다" to "버르덥따",
            // Art. 17.
            "굳이" to "구지", "미닫이" to "미다지", "땀받이" to "땀바지", "밭이" to "바치", "벼훑이" to "벼훌치", "굳히다" to "구치다",
            "닫히다" to "다치다", "같이" to "가치", "붙여요" to "부처요", "끝이에요" to "끄치에요",
            // Art. 18: ante ㄴ y ㅁ, también entre palabras.
            "먹는" to "멍는", "국물" to "궁물", "깎는" to "깡는", "몫몫이" to "몽목씨", "긁는" to "긍는", "흙만" to "흥만", "닫는" to "단는",
            "짓는" to "진는", "있는" to "인는", "맞는" to "만는", "꽃망울" to "꼰망울", "붙는" to "분는", "잡는" to "잠는", "앞마당" to "암마당",
            "읊는" to "음는", "없는" to "엄는", "책 넣는다" to "챙 넌는다", "밥 먹는다" to "밤 멍는다", "옷 맞추다" to "온 맏추다" /* así el diccionario: 맞추다 [맏추다] */, "합니다" to "함니다",
            // Art. 19 y 20.
            "담력" to "담녁", "침략" to "침냑", "강릉" to "강능", "대통령" to "대통녕", "막론" to "망논", "백리" to "뱅니", "협력" to "혐녁", "십리" to "심니",
            "난로" to "날로", "신라" to "실라", "천리" to "철리", "칼날" to "칼랄", "물난리" to "물랄리", "할는지" to "할른지", "닳는" to "달른", "핥네" to "할레",
            // Art. 21 y 23.
            "감기" to "감기", "옷감" to "옫깜", "있고" to "읻꼬", "꽃길" to "꼳낄", "젖먹이" to "전머기", "꽃밭" to "꼳빧",
            "국밥" to "국빱", "깎다" to "깍따", "삯돈" to "삭똔", "닭장" to "닥짱", "칡범" to "칙뻠", "뻗대다" to "뻗때다", "옷고름" to "옫꼬름",
            "있던" to "읻떤", "꽂고" to "꼳꼬", "꽃다발" to "꼳따발", "낯설다" to "낟썰다", "밭갈이" to "받까리", "솥전" to "솓쩐",
            "곱돌" to "곱똘", "덮개" to "덥깨", "옆집" to "엽찝", "읊조리다" to "읍쪼리다", "값지다" to "갑찌다", "학교" to "학꾜",
            // Art. 24 y 25 sin el analizador: ㄵ, ㄻ, ㄼ, ㄾ son de raíz.
            "앉고" to "안꼬", "얹다" to "언따", "닮고" to "담꼬", "젊지" to "점찌", "굶기다" to "굼기다", "옮기다" to "옴기다",
            "넓게" to "널께", "훑소" to "훌쏘", "떫지" to "떨찌",
            // Art. 27: tras -(으)ㄹ que modifica, sin el analizador solo ante 것, 수, 데…
            "할 것을" to "할 꺼슬", "갈 데가" to "갈 떼가", "할 수는" to "할 쑤는", "할 적에" to "할 쩌게", "갈 곳" to "갈 꼳",
            "할게요" to "할께요", "할수록" to "할쑤록", "할걸" to "할껄", "알게 됐어요" to "알게 돼써요",
            // Art. 19 y 20 solo dentro de la palabra.
            "버스를 놓쳤어요" to "버스를 녿처써요", "십육" to "심뉵",
            // Lo de siempre en las oraciones del repaso.
            "어제 친구를 만났어요." to "어제 친구를 만나써요.", "한국어를 공부해요" to "한구거를 공부해요", "몇 시예요?" to "멷 씨예요?",
            "못 해요" to "모 태요", "못 먹어요" to "몬 머거요", "잘 있어요" to "잘 이써요", "무슨 일이에요?" to "무슨 이리에요?",
        ),
    )

    @Test
    fun lasPalabrasConPronunciacionPropia() = check(
        mapOf(
            "갈등이" to "갈뜽이", "휘발유를" to "휘발류를", "문법을" to "문뻐블", "인기가" to "인끼가", "학교에서" to "학꾜에서",
            "닭고기를" to "닥꼬기를", "맛없어요" to "마덥써요", "맛있어요" to "마시써요", "겉옷" to "거돋", "서울역에" to "서울려게",
            "식용유" to "시굥뉴", "깻잎" to "깬닙", "확인했어요" to "화긴해써요", "혹시" to "혹씨", "발전했어요" to "발쩐해써요",
            "회의" to "회이", "강의" to "강이", "주의" to "주이", "우리의" to "우리에", "친구의 집" to "친구에 집",
            "선생님의 말씀" to "선생니메 말씀", "돈독해요" to "돈도캐요", "급속도로" to "급쏙도로", "짓밟았어요" to "짇빨바써요",
        ),
    ) { Pronunciation.of(it, dictionary = KiwiFixtures.dictionary) }

    @Test
    fun conElAnalizadorLaGramaticaDecide() {
        val analyzer = KiwiFixtures.analyzer
        fun sound(s: String) = Pronunciation.of(s, analyzer.analyze(s), KiwiFixtures.dictionary)
        // 신고: «se calza» lleva la ㄲ (art. 24); la denuncia, no.
        assertEquals("신바를 신꼬 나가요.", sound("신발을 신고 나가요."))
        assertEquals("경차레 신고를 해써요.", sound("경찰에 신고를 했어요."))
        // Art. 27: 할 + cualquier palabra; -ㄹ게요.
        assertEquals("할 쑤 이써요.", sound("할 수 있어요."))
        assertEquals("내일 할께요.", sound("내일 할게요."))
        assertEquals("만날 싸라미 마나요.", sound("만날 사람이 많아요."))
        // Art. 11.1 con la raíz: 읽고 [일꼬]; 닭고기, no.
        assertEquals("채글 일꼬 이써요.", sound("책을 읽고 있어요."))
        assertEquals("닥꼬기를 머거요.", sound("닭고기를 먹어요."))
        // La partícula 의.
        assertEquals("우리에 집", sound("우리의 집"))
    }

    @Test
    fun losNumerosSeLeen() {
        assertEquals("세시 삼십분", KoreanNumbers.spell("3시 30분"))
        assertEquals("세 시 삼십 분", KoreanNumbers.spell("3 시 30 분"))
        assertEquals("스무살", KoreanNumbers.spell("20살"))
        assertEquals("스물한명", KoreanNumbers.spell("21명"))
        assertEquals("이천이십뉵년 유월 시월", KoreanNumbers.spell("2026년 6월 10월"))
        assertEquals("만오천원", KoreanNumbers.spell("15,000원"))
        assertEquals("첫번째", KoreanNumbers.spell("1번째"))
        assertEquals("삼번 출구", KoreanNumbers.spell("3번 출구"))
        assertEquals("일억", KoreanNumbers.sino(100_000_000))
        assertEquals("삼점오", KoreanNumbers.spell("3.5"))
        assertEquals("세시 삼십뿌네 만나요.", Pronunciation.of("3시 30분에 만나요."))
        assertEquals("심뉵", Pronunciation.of("16"))
    }

    @Test
    fun loQueCambiaAlDecirlo() {
        val sound = Pronunciation.of("한국 음식은 맛있어요")
        assertEquals("한구금시근 마시써요", sound)
        // 국→구, 음→금, 식→시, 은→근, 맛→마, 있→시, 어→써: las posiciones en lo que suena.
        assertEquals(setOf(1, 2, 3, 4, 6, 7, 8), Pronunciation.changed("한국 음식은 맛있어요", sound))
        assertTrue(Pronunciation.changed("3시", Pronunciation.of("3시")).isEmpty())
    }

    @Test
    fun suenaIgualAunqueSeEscribaDistinto() {
        for ((a, b) in listOf("됬어요" to "됐어요", "머거요" to "먹어요", "금새" to "금세", "할께요" to "할게요", "몇일" to "며칠",
            "설겆이" to "설거지", "괜찬아요" to "괜찮아요", "조아요" to "좋아요", "학꾜" to "학교", "왠지" to "웬지", "어의없다" to "어이없다")) {
            assertTrue("$a y $b", Pronunciation.sameSound(a, b))
        }
        for ((a, b) in listOf("않 가요" to "안 가요", "몰랐든" to "몰랐던", "깨끗히" to "깨끗이", "먹었어요" to "갔어요")) {
            assertFalse("$a y $b", Pronunciation.sameSound(a, b))
        }
    }
}
