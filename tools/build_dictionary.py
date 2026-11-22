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
                {"i": id, "o": origen (hanja o lengua), "s": [{"l": traducción corta, "d": definición en
                español, "x": [oraciones de ejemplo], "c": [frases de ejemplo], "p": [patrones]}],
                "r": [[relación, forma]]}
  FUENTE.txt    atribución y licencia.

Las traducciones van tal cual vienen: la app adapta algunas palabras de España al hablar de Perú
(PeruSpanish en :shared).

Uso: python3 tools/build_dictionary.py <carpeta con los .xml> app/src/main/assets/dictionary
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


def entries(src):
    files = sorted(Path(src).glob("*.xml"), key=lambda p: int(re.sub(r"\D", "", p.stem) or 0))
    for path in files:
        data = io.BytesIO(clean_xml(path.read_text(encoding="utf-8")).encode("utf-8"))
        for _, el in ET.iterparse(data, events=("end",)):
            if el.tag == "LexicalEntry":
                yield el
                el.clear()


def main(src, out_dir):
    out = Path(out_dir)
    (out / "senses").mkdir(parents=True, exist_ok=True)
    shards = [[] for _ in range(SHARDS)]
    index = []
    for el in entries(src):
        top = feats(el)
        unit = first(top, "lexicalUnit")
        lemma = el.find("Lemma")
        lf = feats(lemma) if lemma is not None else {}
        form = first(lf, "writtenForm")
        if unit not in KEEP_UNITS or not form:
            continue
        eid = int(el.get("val"))
        conj = []
        for wf in el.findall("WordForm"):
            w = feats(wf)
            if first(w, "type") != "활용":
                continue
            conj.append(first(w, "writtenForm"))
            for fr in wf.findall("FormRepresentation"):
                conj.append(first(feats(fr), "writtenForm"))
        conj = [tsv(c) for c in dict.fromkeys(c for c in conj if c)]
        index.append("\t".join([
            str(eid), tsv(form), tsv(first(top, "homonym_number")), tsv(first(top, "partOfSpeech")),
            LEVELS.get(first(top, "vocabularyLevel"), ""), unit,
            "|".join(tsv(v) for v in lf.get("variant", [])), "|".join(conj),
        ]))
        senses = []
        for s in el.findall("Sense"):
            sf = feats(s)
            es = None
            for eq in s.findall("Equivalent"):
                e = feats(eq)
                if first(e, "language") == "스페인어":
                    es = e
                    break
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
            senses.append({k: v for k, v in sense.items() if v})
        related = []
        for rf in el.findall("RelatedForm"):
            r = feats(rf)
            kind, other = first(r, "type"), first(r, "writtenForm") or first(r, "lemma")
            if kind and other:
                related.append([kind, other])
        detail = {"i": eid, "s": senses}
        if first(top, "origin"):
            detail["o"] = first(top, "origin")
        if related:
            detail["r"] = related[:6]
        shards[eid % SHARDS].append(json.dumps(detail, ensure_ascii=False, separators=(",", ":")))

    (out / "index.tsv").write_text("\n".join(index) + "\n", encoding="utf-8")
    for n, lines in enumerate(shards):
        (out / "senses" / f"{n:02d}.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")
    (out / "FUENTE.txt").write_text(
        "Diccionario de palabras y gramática con traducción al español\n\n"
        "Fuente: 국립국어원 «한국어기초사전» (Diccionario Básico de Coreano del Instituto Nacional de la\n"
        "Lengua Coreana), https://krdict.korean.go.kr\n"
        "Datos: respaldo XML del 2019-08-02, https://github.com/spellcheck-ko/korean-dict-nikl-krdict\n\n"
        "Licencia: Creative Commons Atribución-CompartirIgual 2.0 Corea (CC BY-SA 2.0 KR),\n"
        "https://creativecommons.org/licenses/by-sa/2.0/kr/\n"
        "Cambios: se guardan solo palabras y gramática, con la traducción al español y algunos ejemplos;\n"
        "la app cambia algunas palabras de España por las de Perú al mostrarlas. Estos archivos se\n"
        "comparten con la misma licencia.\n"
        "Formato: tools/build_dictionary.py\n",
        encoding="utf-8",
    )
    print(f"{len(index)} entradas -> {out}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
