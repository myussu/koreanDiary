# Uso: python3 tools/kiwi_fixtures.py > shared/src/jvmTest/resources/kiwi-fixtures.json (con kiwipiepy 0.25).
# Genera los datos de prueba de SentenceLemmasTest con Kiwi de verdad (kiwipiepy 0.25.1, sin multi.dict).
import json
from kiwipiepy import Kiwi
kiwi = Kiwi(load_multi_dict=False)
SENTENCES = [
    "서울에 사는 친구를 만났어요.",
    "시장에서 사과를 사는 사람이 많아요.",
    "먹는 동안 다른 음식도 먹어 봤어요.",
    "먹으면서 다른 음식도 먹어 봤어요.",
    "음악을 들어요.",
    "가방을 들어요.",
    "길을 걸어요.",
    "벽에 그림을 걸어요.",
    "날씨가 추워서 집에 있었어요.",
    "일반적으로 늦어지면 연락해요.",
    "저는 매운맛을 좋아해요.",
    # Escena viva: 산 de comprar o la montaña, 해 del sol o de hacer, 맑 de 맑다.
    "어제 산 옷이 예뻐요.",
    "엄마가 산 케이크가 맛있었어요.",
    "주말에 산에 갔어요.",
    "하늘이 맑아요.",
    "해가 떴어요.",
    "숙제를 해 봤어요.",
    # «Usados» del diario.
    "친구와 같이 영화를 봤어요.",
]
VERBAL = {"VV", "VA", "VX", "XSV", "XSA", "VCN"}
NOMINAL = {"NNG", "NNP", "NNB", "NR", "NP", "XSN", "XR", "MAG", "MAJ", "MM", "IC"}
STOP = ("J", "EF", "SF", "SP", "SS", "SE", "SO", "SW", "SL", "SH", "SN")
def base(t): return t.split("-")[0]
out = {"analyses": {}, "joins": {}}
for s in SENTENCES:
    toks = kiwi.tokenize(s)
    out["analyses"][s] = [[t.form, base(t.tag), t.start, t.len, t.tag.endswith("-I")] for t in toks]
    # Todas las uniones que puede pedir el método con diccionario, palabra por palabra.
    import re
    for m in re.finditer(r"\S+", s):
        ms = [t for t in toks if t.start < m.end() and t.start + t.len > m.start()]
        for j in range(len(ms), 0, -1):
            head = ms[:j]
            if any(base(t.tag).startswith(STOP) for t in head):
                continue
            last = base(head[-1].tag)
            pieces = [(t.form, t.tag) for t in head]
            if last in VERBAL:
                pieces = pieces + [("다", "EF")]
            elif last not in NOMINAL:
                continue
            key = "+".join(f"{f}/{base(tg)}{'-I' if tg.endswith('-I') else ''}" for f, tg in pieces)
            try:
                out["joins"][key] = kiwi.join(pieces).replace(" ", "")
            except Exception as e:
                pass
print(json.dumps(out, ensure_ascii=False, indent=1))
