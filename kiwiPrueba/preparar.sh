#!/usr/bin/env bash
# Baja lo que la app de prueba de Kiwi necesita y no va en el repo (pesa ~100 MB):
# - la librería para Android (AAR) y el modelo, del release oficial de github.com/bab2min/Kiwi,
#   en la versión de gradle/libs.versions.toml;
# - las formas del diccionario de la app, para el método «Kiwi + diccionario».
# Lo corre el workflow kiwi-prueba.yml; en local: kiwiPrueba/preparar.sh y luego
# ./gradlew :kiwiPrueba:assembleRelease (el módulo solo existe con la librería ya bajada).
set -euo pipefail
cd "$(dirname "$0")"

version=$(sed -n 's/^kiwi = "\(.*\)"$/\1/p' ../gradle/libs.versions.toml)
[ -n "$version" ] || { echo "No encuentro la versión de Kiwi en gradle/libs.versions.toml" >&2; exit 1; }
tag="v$version"
base="https://github.com/bab2min/Kiwi/releases/download/$tag"

mkdir -p libs
curl -fsSL --retry 3 -o libs/kiwi-android.aar "$base/kiwi-android-$tag.aar"

# El modelo se descomprime en una carpeta temporal y solo se copian sus archivos.
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
curl -fsSL --retry 3 -o "$tmp/modelo.tgz" "$base/kiwi_model_${tag}_base.tgz"
mkdir "$tmp/modelo"
tar -xzf "$tmp/modelo.tgz" -C "$tmp/modelo"
rm -rf src/main/assets/kiwi
mkdir -p src/main/assets/kiwi
find "$tmp/modelo" -type f -exec cp {} src/main/assets/kiwi/ \;
# multi.dict (expresiones de varias palabras) no cambia ningún resultado de los casos y suma memoria.
rm -f src/main/assets/kiwi/multi.dict

# Segunda columna del índice del diccionario: las formas que existen como entrada.
awk -F'\t' 'NF > 1 && $2 != "" { print $2 }' ../app/src/main/assets/dictionary/index.tsv | sort -u > src/main/assets/formas.txt

echo "Kiwi $tag listo:"
ls -la libs src/main/assets/kiwi
wc -l src/main/assets/formas.txt
