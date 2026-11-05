"""Genera los TSV del catálogo estándar que la app lleva en assets.

Fuente: 국립국어원, «2017년 국제 통용 한국어 표준 교육과정 적용 연구(4단계) 어휘, 문법 등급 목록»
(revisión 2020-11-17), 공공누리 제1유형 (출처표시).
Entrada: los JSON convertidos del Excel oficial que publica
https://github.com/dev-somi/skala-korean-tutor-supporter (ai-server/data/standard/).

Uso: python3 tools/build_standard_catalog.py grammar.json vocab.json app/src/main/assets/standard
"""
import json
import sys
from pathlib import Path


def clean(s):
    return (s or "").replace("\t", " ").replace("\n", " ").strip()


def main(grammar_path, vocab_path, out_dir):
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    grammar = json.load(open(grammar_path, encoding="utf-8"))["items"]
    vocab = json.load(open(vocab_path, encoding="utf-8"))["items"]

    # id \t nivel \t categoría \t forma \t variantes(|) \t significado
    with open(out / "grammar.tsv", "w", encoding="utf-8") as f:
        for g in grammar:
            gid = int(g["id"].rsplit("-", 1)[1])
            f.write("\t".join([str(gid), str(g["level"]), clean(g["category"]), clean(g["form"]),
                               "|".join(clean(r) for r in g.get("related") or []), clean(g.get("meaning"))]) + "\n")

    # id \t nivel \t palabra \t homónimo \t categoría gramatical \t ejemplo de uso
    with open(out / "vocab.tsv", "w", encoding="utf-8") as f:
        for v in vocab:
            vid = int(v["id"].rsplit("-", 1)[1])
            f.write("\t".join([str(vid), str(v["level"]), clean(v["word"]), clean(v.get("homonym")),
                               clean(v["pos"]), clean(v.get("guide"))]) + "\n")
    print(f"{len(grammar)} gramáticas, {len(vocab)} palabras -> {out}")


if __name__ == "__main__":
    main(*sys.argv[1:4])
