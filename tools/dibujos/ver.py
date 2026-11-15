"""
Pinta uno o varios dibujos para mirarlos: por cada `salida/x.json` deja `salida/x.png` con tres vistas
(papel claro, papel oscuro y chico como en la app).

    python ver.py salida/gato.json
    python ver.py salida/*.json
    python ver.py --hoja      (las de salida/ juntas en salida/_hoja.png)
    python ver.py --hoja app  (las que ya trae la app, en salida/_hoja_app.png)
"""
import glob
import os
import sys

from PIL import Image, ImageDraw

import dibujo

AQUI = os.path.dirname(os.path.abspath(__file__))


def hoja(app=False):
    folder = os.path.join(AQUI, "..", "..", "app", "src", "main", "assets", "dibujos") if app else os.path.join(AQUI, "salida")
    files = sorted(glob.glob(os.path.join(folder, "*.json")))
    if not files:
        print("No hay dibujos en " + os.path.normpath(folder))
        return
    cell, cols = 200, 8
    rows = (len(files) + cols - 1) // cols
    out = Image.new("RGB", (cols * cell, rows * (cell + 22)), (255, 255, 255))
    draw = ImageDraw.Draw(out)
    for i, f in enumerate(files):
        d = dibujo.load(f)
        img = dibujo.paint(d, cell, dark=False).convert("RGB")
        x, y = (i % cols) * cell, (i // cols) * (cell + 22)
        out.paste(img, (x, y + 22))
        draw.text((x + 4, y + 4), f"{d.get('name', '?')} ({os.path.basename(f)[:-5]})", fill=(0, 0, 0))
    path = os.path.join(AQUI, "salida", "_hoja_app.png" if app else "_hoja.png")
    out.save(path)
    print(path)


def main(args):
    if args[:1] == ["--hoja"]:
        hoja(app=args[1:] == ["app"])
        return
    if not args:
        print(__doc__)
        return
    for pattern in args:
        for f in sorted(glob.glob(pattern)):
            out = f[:-5] + ".png"
            dibujo.sheet(dibujo.load(f), out)
            print(out)


if __name__ == "__main__":
    main(sys.argv[1:])
