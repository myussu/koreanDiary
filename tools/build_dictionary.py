"""Genera el diccionario que la app lleva en assets/dictionary, a partir del 한국어기초사전.

Fuente: 국립국어원 «한국어기초사전» (https://krdict.korean.go.kr), licencia CC BY-SA 2.0 KR.
Entrada: los XML (formato LMF) que da «사전 내려받기» en el sitio, o el respaldo de
https://github.com/spellcheck-ko/korean-dict-nikl-krdict (2019-08-02), con el mismo formato.

Salida:
  index.tsv     una línea por palabra o gramática, para buscar, verificar y sacar la forma de
                diccionario sin internet:
                id \t forma \t homónimo \t categoría \t nivel (1 inicial, 2 intermedio, 3 avanzado, vacío)
                \t tipo (단어 o 문법‧표현) \t variantes (|) \t conjugaciones del diccionario (|)
  senses/NN.jsonl  el detalle, repartido en 64 archivos por id % 64 (se lee solo el que hace falta):
                {"i": id, "o": origen (hanja o lengua), "pr": pronunciación si no suena como se escribe
                (가치, o 교외/교웨 si hay dos), "s": [{"l": traducción corta, "d": definición en español,
                "x": [oraciones de ejemplo], "c": [frases de ejemplo], "p": [patrones], "rel": [[relación
                (유의어, 반대말…), forma, traducción corta de esa forma]]}], "r": [[relación, forma]]}
                También el de los modismos y refranes.
  idioms.tsv    modismos (관용구) y refranes (속담): id propio (desde 1.000.000) \t forma \t tipo \t
                traducción corta \t id de su palabra principal (발이 넓다 → 발).
  hanja.jsonl   por hanja de los orígenes: {"h": hanja, "r": [[훈, 음, traducción del 훈]], "w": [[id,
                forma, nivel, traducción corta]]}, con las palabras comunes primero (máx. 12). El 훈음 sale
                de libhangul (data/hanja/hanja.txt, licencia BSD de 3 cláusulas), si se pasa.
  FUENTE.txt    atribución y licencias.

Las traducciones van tal cual vienen: la app adapta algunas palabras de España al hablar de Perú
(PeruSpanish en :shared).

Uso: python3 tools/build_dictionary.py <carpeta con los .xml> app/src/main/assets/dictionary [hanja.txt]
"""
import io
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

SHARDS = 64
LEVELS = {"초급": "1", "중급": "2", "고급": "3"}
KEEP_UNITS = {"단어", "문법‧표현"}
IDIOM_UNITS = {"관용구", "속담"}
IDIOM_BASE = 1_000_000
# Las relaciones que se muestran, en este orden (las demás no aportan a quien estudia).
RELATION_ORDER = ["유의어", "반대말", "준말", "본말", "높임말", "낮춤말", "참고어"]
MAX_RELATIONS = 6
MAX_HANJA_WORDS = 12
HAN = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")
ATTR = re.compile(r'val="([^"]*)"')


def clean_xml(text):
    # Algún valor trae «<» sin escapar (una traducción al francés).
    return ATTR.sub(lambda m: 'val="' + m.group(1).replace("<", "&lt;").replace(">", "&gt;") + '"', text)


def feats(el):
    out = {}
    for f in el.findall("feat"):
        out.setdefault(f.get("att"), []).append(f.get("val"))
    return out


def first(d, k):
    v = d.get(k)
    return v[0] if v else None


def tsv(s):
    return (s or "").replace("\t", " ").replace("\n", " ").replace("|", "/").strip()


def polite(sentence):
    return sentence.rstrip(" .?!").endswith("요")


def plain_form(form):
    return re.sub(r"[-^ ]", "", form or "")


def spanish(sense_el):
    for eq in sense_el.findall("Equivalent"):
        e = feats(eq)
        if first(e, "language") == "스페인어":
            return e
    return None


def hanja_readings(path):
    """libhangul: «학:學:배울 학» → {"學": [("배울", "학")]}; solo los de un carácter."""
    out = {}
    if not path:
        return out
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        parts = line.split(":")
        if len(parts) < 3 or len(parts[1]) != 1:
            continue
        for item in parts[2].split(","):
            words = item.strip().split()
            if len(words) >= 2:
                pair = (" ".join(words[:-1]), words[-1])
                if pair not in out.setdefault(parts[1], []):
                    out[parts[1]].append(pair)
    return out


def final_index(syllable):
    code = ord(syllable) - 0xAC00
    return code % 28 if 0 <= code < 11172 else -1


def without_final(syllable):
    code = ord(syllable) - 0xAC00
    return chr(ord(syllable) - code % 28)


def native_gloss(hun, gloss_of):
    """El 훈 en español: 불 → fuego; 배울 (de 배우다) → aprender; 말할 → hablar."""
    if hun in gloss_of:
        return gloss_of[hun]
    last = hun[-1:]
    candidates = []
    # 맑을, 붉은: el adnominal en -을/-은 va tras la raíz (맑다, 붉다).
    if last in ("을", "은"):
        candidates.append(hun[:-1] + "다")
    # 배울, 말할, 될, 큰: el adnominal en -ㄹ (final 8) o -ㄴ (final 4) va pegado a la raíz.
    if last and final_index(last) in (4, 8):
        candidates.append(hun[:-1] + without_final(last) + "다")
    return next((gloss_of[c] for c in candidates if c in gloss_of), None)


def entries(src):
    files = sorted(Path(src).glob("*.xml"), key=lambda p: int(re.sub(r"\D", "", p.stem) or 0))
    for path in files:
        data = io.BytesIO(clean_xml(path.read_text(encoding="utf-8")).encode("utf-8"))
        for _, el in ET.iterparse(data, events=("end",)):
            if el.tag == "LexicalEntry":
                yield el
                el.clear()


def main(src, out_dir, hanja_txt=None):
    out = Path(out_dir)
    (out / "senses").mkdir(parents=True, exist_ok=True)
    details = []
    index = []
    idioms = []
    # Para la segunda pasada: la traducción corta de cada entrada y las palabras de cada hanja.
    gloss_by_id, gloss_of = {}, {}
    families = {}
    for el in entries(src):
        top = feats(el)
        unit = first(top, "lexicalUnit")
        lemma = el.find("Lemma")
        lf = feats(lemma) if lemma is not None else {}
        form = first(lf, "writtenForm")
        if unit not in KEEP_UNITS | IDIOM_UNITS or not form:
            continue
        eid = int(el.get("val"))
        first_sense = el.find("Sense")
        es_first = spanish(first_sense) if first_sense is not None else None
        short = tsv(first(es_first, "lemma")) if es_first else ""
        if unit in IDIOM_UNITS:
            # Un modismo trae el id de su palabra principal (발이 넓다 → 발): lleva uno propio y
            # guarda el de la palabra para encontrarlo desde ella.
            if not short:
                continue
            head, eid = eid, IDIOM_BASE + len(idioms)
            idioms.append("\t".join([str(eid), tsv(form), unit, short, str(head)]))
        elif short:
            gloss_by_id[eid] = short
            # Para traducir un 훈 (은 «plata»), una palabra y no la partícula ni la terminación.
            if unit == "단어" and first(top, "partOfSpeech") not in ("조사", "어미", "접사"):
                gloss_of.setdefault(form, short)
        conj, prons = [], []
        for wf in el.findall("WordForm"):
            w = feats(wf)
            kind = first(w, "type")
            if kind == "발음":
                prons.extend(p.replace("ː", "") for p in w.get("pronunciation", []) if p)
                continue
            if kind != "활용":
                continue
            conj.append(first(w, "writtenForm"))
            for fr in wf.findall("FormRepresentation"):
                conj.append(first(feats(fr), "writtenForm"))
        conj = [tsv(c) for c in dict.fromkeys(c for c in conj if c)]
        prons = list(dict.fromkeys(prons))
        level = LEVELS.get(first(top, "vocabularyLevel"), "")
        if unit in KEEP_UNITS:
            index.append("\t".join([
                str(eid), tsv(form), tsv(first(top, "homonym_number")), tsv(first(top, "partOfSpeech")),
                level, unit,
                "|".join(tsv(v) for v in lf.get("variant", [])), "|".join(conj),
            ]))
        origin = first(top, "origin")
        if unit == "단어" and origin and short:
            for ch in dict.fromkeys(HAN.findall(origin)):
                families.setdefault(ch, []).append((int(level or 9), len(form), form, eid, short))
        senses = []
        for s in el.findall("Sense"):
            sf = feats(s)
            es = spanish(s)
            sentences, phrases = [], []
            # En un diálogo no todas las líneas traen la palabra: se guardan las que tienen su
            # primera sílaba (덥다 → 더워 la tiene; 귤 초콜릿도 있네? no tiene 가공식품).
            mark = form.lstrip("-")[:1]
            for ex in s.findall("SenseExample"):
                x = feats(ex)
                kind = first(x, "type")
                texts = [t for t in x.get("example", []) if t]
                if kind == "구":
                    phrases.extend(texts)
                elif kind == "문장":
                    sentences.extend(texts)
                elif kind == "대화":
                    sentences.extend(t for t in texts if mark and mark in t)
            # Primero las corteses (-요): son las que mejor sirven para el repaso.
            sentences = sorted(dict.fromkeys(sentences), key=lambda t: not polite(t))[:3]
            sense = {"l": first(es, "lemma") if es else None, "d": first(es, "definition") if es else None,
                     "x": sentences, "c": list(dict.fromkeys(phrases))[:2]}
            if sf.get("syntacticPattern"):
                sense["p"] = sf["syntacticPattern"][:2]
            rels = []
            for sr in s.findall("SenseRelation"):
                r = feats(sr)
                kind, other = first(r, "type"), first(r, "lemma") or first(r, "writtenForm")
                if kind in RELATION_ORDER and other and other != form:
                    rels.append([kind, other, int(first(r, "id") or 0)])
            rels.sort(key=lambda r: RELATION_ORDER.index(r[0]))
            if rels:
                sense["rel"] = list({(r[0], r[1]): r for r in rels}.values())[:MAX_RELATIONS]
            senses.append({k: v for k, v in sense.items() if v})
        related = []
        for rf in el.findall("RelatedForm"):
            r = feats(rf)
            kind, other = first(r, "type"), first(r, "writtenForm") or first(r, "lemma")
            if kind and other:
                related.append([kind, other])
        detail = {"i": eid, "s": senses}
        if origin:
            detail["o"] = origin
        if prons and any(p != plain_form(form) for p in prons):
            detail["pr"] = "/".join(prons)
        if related:
            detail["r"] = related[:6]
        details.append(detail)

    # Segunda pasada: la traducción corta de cada relación (기쁘다 → «alegre»), ya con todas leídas.
    shards = [[] for _ in range(SHARDS)]
    for detail in details:
        for sense in detail["s"]:
            if "rel" in sense:
                sense["rel"] = [[k, f, gloss_by_id.get(i) or gloss_of.get(f, "")] for k, f, i in sense["rel"]]
        shards[detail["i"] % SHARDS].append(json.dumps(detail, ensure_ascii=False, separators=(",", ":")))

    readings = hanja_readings(hanja_txt)
    hanja_lines = []
    for ch, words in sorted(families.items()):
        words.sort()
        record = {"h": ch, "w": [[eid, f, lv if lv != 9 else 0, g] for lv, _, f, eid, g in words[:MAX_HANJA_WORDS]]}
        if ch in readings:
            record["r"] = [[hun, eum, native_gloss(hun, gloss_of) or ""] for hun, eum in readings[ch][:3]]
        hanja_lines.append(json.dumps(record, ensure_ascii=False, separators=(",", ":")))

    (out / "index.tsv").write_text("\n".join(index) + "\n", encoding="utf-8")
    (out / "idioms.tsv").write_text("\n".join(idioms) + "\n", encoding="utf-8")
    (out / "hanja.jsonl").write_text("\n".join(hanja_lines) + "\n", encoding="utf-8")
    for n, lines in enumerate(shards):
        (out / "senses" / f"{n:02d}.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")
    (out / "FUENTE.txt").write_text(
        "Diccionario de palabras y gramática con traducción al español\n\n"
        "Fuente: 국립국어원 «한국어기초사전» (Diccionario Básico de Coreano del Instituto Nacional de la\n"
        "Lengua Coreana), https://krdict.korean.go.kr\n"
        "Datos: respaldo XML del 2019-08-02, https://github.com/spellcheck-ko/korean-dict-nikl-krdict\n\n"
        "Licencia: Creative Commons Atribución-CompartirIgual 2.0 Corea (CC BY-SA 2.0 KR),\n"
        "https://creativecommons.org/licenses/by-sa/2.0/kr/\n"
        "Cambios: se guardan palabras, gramática, modismos y refranes, con la traducción al español,\n"
        "algunos ejemplos, la pronunciación y los sinónimos y contrarios; la app cambia algunas palabras\n"
        "de España por las de Perú al mostrarlas. Estos archivos se comparten con la misma licencia.\n"
        "Formato: tools/build_dictionary.py\n"
        + (HANJA_NOTICE if readings else ""),
        encoding="utf-8",
    )
    print(f"{len(index)} entradas, {len(idioms)} modismos y refranes, {len(hanja_lines)} hanja -> {out}")


# libhangul pide conservar su aviso de copyright junto con los datos.
HANJA_NOTICE = """
Significado de cada hanja (훈음, hanja.jsonl): data/hanja/hanja.txt de libhangul,
https://github.com/libhangul/libhangul

Copyright (c) 2005,2006 Choe Hwanjin
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.
3. Neither the name of the author nor the names of its contributors
   may be used to endorse or promote products derived from this software
   without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.
"""


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
