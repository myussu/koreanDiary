"""
Revisa que los dibujos cumplan el formato y las reglas: los nuevos de salida/ y los que ya están en la
app (app/src/main/assets/dibujos/). Las palabras y los ids no pueden repetirse entre unos y otros.

    python validar.py            (todos: salida/ y la app)
    python validar.py salida/gato.json

Sale con código 1 si algún dibujo tiene errores. Los avisos no frenan, pero conviene mirarlos.
"""
import glob
import json
import os
import re
import sys

AQUI = os.path.dirname(os.path.abspath(__file__))
# Los dibujos de fábrica que ya trae la app.
APP = os.path.normpath(os.path.join(AQUI, "..", "..", "app", "src", "main", "assets", "dibujos"))
HEX = re.compile(r"^#[0-9a-fA-F]{6}$")
HANGUL = re.compile(r"^[가-힣ᄀ-ᇿ㄰-㆏]{1,8}$")
SLUG = re.compile(r"^[a-z][a-z0-9_]{1,23}$")
ZONES = {"GROUND", "HORIZON", "SKY", "AIR"}
SIZES = {"SMALL", "MEDIUM", "LARGE"}
MOTIONS = {"NONE", "SWAY", "BOB", "DRIFT"}
MOVES = {"NONE", "SWAY", "FLAP", "BOB", "TWINKLE"}


def app_keys():
    keys = {}
    with open(os.path.join(AQUI, "claves_de_la_app.txt"), encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            k, owner = line.rstrip("\n").split("\t")
            keys[k] = owner
    return keys


def check(d, name):
    errors, warnings = [], []
    for field in ("id", "name", "keys", "zone", "size", "motion", "count", "colors", "shapes"):
        if field not in d:
            errors.append(f"falta «{field}»")
    if errors:
        return errors, warnings
    if not SLUG.match(d["id"]):
        errors.append(f"id «{d['id']}»: en inglés, minúsculas y guion bajo (cat, hot_air_balloon)")
    if d["zone"] not in ZONES:
        errors.append(f"zone «{d['zone']}» no es {sorted(ZONES)}")
    if d["size"] not in SIZES:
        errors.append(f"size «{d['size']}» no es {sorted(SIZES)}")
    if d["motion"] not in MOTIONS:
        errors.append(f"motion «{d['motion']}» no es {sorted(MOTIONS)}")
    if not isinstance(d["count"], int) or not 1 <= d["count"] <= 5:
        errors.append("count va de 1 a 5")
    if not d["name"] or d["name"] != d["name"].lower():
        errors.append("name: en español y en minúsculas")
    if not d["keys"] or not all(isinstance(k, str) and HANGUL.match(k) for k in d["keys"]):
        errors.append("keys: sustantivos en hangul, sin partículas ni espacios")
    if len(d["keys"]) > 5:
        errors.append("keys: como mucho 5")

    colors = {}
    for c in d["colors"]:
        if not HEX.match(c.get("light", "")) or not HEX.match(c.get("dark", "")):
            errors.append(f"color «{c.get('key')}»: light y dark en #rrggbb")
        colors[c.get("key")] = c
    if not 2 <= len(colors) <= 6:
        warnings.append(f"{len(colors)} colores (lo normal es 2 a 5)")
    for c in colors.values():
        if HEX.match(c.get("light", "")):
            r, g, b = (int(c["light"][j:j + 2], 16) for j in (1, 3, 5))
            if min(r, g, b) > 215:
                warnings.append(f"color «{c['key']}» claro casi no se ve en el papel crema ({c['light']})")
        if HEX.match(c.get("dark", "")):
            r, g, b = (int(c["dark"][j:j + 2], 16) for j in (1, 3, 5))
            if max(r, g, b) < 70:
                warnings.append(f"color «{c['key']}» oscuro casi no se ve en el papel negro ({c['dark']}); si es un detalle sobre otra forma, está bien")

    shapes = d["shapes"]
    if not 6 <= len(shapes) <= 40:
        errors.append(f"{len(shapes)} formas (van de 6 a 40; lo normal es 8 a 30)")
    xs, ys = [], []
    for i, s in enumerate(shapes):
        where = f"forma {i + 1} ({s.get('k')})"
        if s.get("k") not in ("wash", "blob", "stroke"):
            errors.append(f"{where}: k es wash, blob o stroke")
            continue
        if s.get("c") not in colors:
            errors.append(f"{where}: el color «{s.get('c')}» no está en colors")
        a = s.get("a")
        if not isinstance(a, (int, float)) or not 0.04 <= a <= 0.9:
            errors.append(f"{where}: a va de 0.05 a 0.9")
        if s.get("move", "NONE") not in MOVES:
            errors.append(f"{where}: move no es {sorted(MOVES)}")
        if s["k"] in ("wash", "stroke"):
            pts = s.get("pts", [])
            if len(pts) < 3:
                errors.append(f"{where}: necesita al menos 3 puntos")
            for p in pts:
                if not (isinstance(p, list) and len(p) == 2):
                    errors.append(f"{where}: cada punto es [x, y]")
                    break
                xs.append(p[0])
                ys.append(p[1])
            if s["k"] == "stroke":
                w = s.get("w", 0)
                if not isinstance(w, (int, float)) or not 0.4 <= w <= 20:
                    errors.append(f"{where}: w va de 0.4 a 20")
                elif w < 2.5:
                    warnings.append(f"{where}: w {w} es muy fino; en el celular se pierde (salvo bigotes)")
        else:
            for f in ("x", "y", "r"):
                if not isinstance(s.get(f), (int, float)):
                    errors.append(f"{where}: falta {f}")
            if isinstance(s.get("r"), (int, float)) and s["r"] < 2 and s.get("a", 0) < 0.7:
                warnings.append(f"{where}: un toque de r {s['r']} y poco pigmento casi no se ve")
            if all(isinstance(s.get(f), (int, float)) for f in ("x", "y", "r")):
                xs += [s["x"] - s["r"], s["x"] + s["r"]]
                ys += [s["y"] - s["r"], s["y"] + s["r"]]
    if xs and ys:
        if min(xs) < -10 or max(xs) > 110 or min(ys) < -10 or max(ys) > 110:
            errors.append("hay formas fuera de la caja (-10 a 110)")
        if max(xs) - min(xs) < 45 and max(ys) - min(ys) < 45:
            warnings.append("el dibujo ocupa poco de la caja; agrándalo")
        if d["zone"] in ("GROUND", "HORIZON") and max(ys) < 92:
            warnings.append("no llega al suelo (y = 100); lo que se apoya tiene que tocarlo")
    if d["count"] > 1 and d["zone"] in ("GROUND", "HORIZON"):
        warnings.append("count > 1 es para cosas chicas que vuelan o flotan")
    return errors, warnings


def load(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def main(args):
    everything = sorted(glob.glob(os.path.join(AQUI, "salida", "*.json"))) + sorted(glob.glob(os.path.join(APP, "*.json")))
    files = args or everything
    taken_keys = app_keys()
    seen_ids, seen_keys = {}, {}
    # Con archivos sueltos, los demás (los de la app y el resto de salida/) cuentan como ocupados.
    chosen = {os.path.abspath(f) for f in files}
    for f in everything:
        if os.path.abspath(f) in chosen:
            continue
        try:
            d = load(f)
        except Exception:  # noqa: BLE001
            continue
        where = os.path.relpath(f, AQUI)
        seen_ids.setdefault(d.get("id"), where)
        for k in d.get("keys", []):
            seen_keys.setdefault(k, where)
    bad = 0
    for f in files:
        name = os.path.relpath(f, AQUI)
        try:
            d = load(f)
        except Exception as e:  # noqa: BLE001
            print(f"✗ {name}: no es JSON ({e})")
            bad += 1
            continue
        errors, warnings = check(d, name)
        if os.path.basename(f) != f"{d.get('id')}.json":
            errors.append(f"el archivo se llama distinto que su id («{d.get('id')}.json»)")
        for k in d.get("keys", []):
            if k in taken_keys:
                errors.append(f"«{k}» ya trae un dibujo de la app: {taken_keys[k]}")
            if k in seen_keys:
                errors.append(f"«{k}» ya está en {seen_keys[k]}")
            seen_keys.setdefault(k, name)
        if d.get("id") in seen_ids:
            errors.append(f"id «{d['id']}» repetido con {seen_ids[d['id']]}")
        seen_ids.setdefault(d.get("id"), name)
        mark = "✗" if errors else ("!" if warnings else "✓")
        print(f"{mark} {name}: {d.get('name', '?')} ({len(d.get('shapes', []))} formas)")
        for e in errors:
            print(f"    error: {e}")
        for w in warnings:
            print(f"    aviso: {w}")
        if errors:
            bad += 1
    print(f"\n{len(files)} dibujos, {bad} con errores.")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main(sys.argv[1:])
