"""
Pinta un dibujo de la escena (el JSON de `scene/art/DrawingFormat.kt`) con la misma geometría de pincel
que la app (`scene/art/Brush.kt` y `CustomDrawing.boxMarks`), para mirarlo antes de agregarlo.

Solo necesita Pillow (`pip install pillow`). La app además pasa todo por un filtro de acuarela que
corre y suaviza los bordes unos píxeles; aquí la vista «en la app» lo imita con un desenfoque.
"""
import json
import math
import random

from PIL import Image, ImageDraw, ImageFilter

PAPEL_CLARO = (250, 247, 238)
PAPEL_OSCURO = (32, 32, 30)


# ---------- Pincel (copia de Brush.kt) ----------

def catmull(points, steps=6):
    if len(points) < 3:
        return list(points)
    pts = [points[0]] + list(points) + [points[-1]]
    out = []
    for i in range(1, len(pts) - 2):
        p0, p1, p2, p3 = pts[i - 1], pts[i], pts[i + 1], pts[i + 2]
        for s in range(steps):
            t = s / steps
            t2, t3 = t * t, t * t * t

            def c(a, b, cc, d):
                return 0.5 * ((2 * b) + (-a + cc) * t + (2 * a - 5 * b + 4 * cc - d) * t2 + (-a + 3 * b - 3 * cc + d) * t3)

            out.append((c(p0[0], p1[0], p2[0], p3[0]), c(p0[1], p1[1], p2[1], p3[1])))
    out.append(points[-1])
    return out


def stroke_outline(points, width, taper_in=0.12, taper_out=0.3, wobble=0.1, seed=0):
    rnd = random.Random(seed)
    pts = catmull(points)
    n = len(pts)
    left, right = [], []
    for i in range(n):
        a = pts[max(i - 1, 0)]
        b = pts[min(i + 1, n - 1)]
        dx, dy = b[0] - a[0], b[1] - a[1]
        ln = math.hypot(dx, dy) or 1.0
        nx, ny = -dy / ln, dx / ln
        s = i / (n - 1)
        if taper_in > 0 and s < taper_in:
            pressure = math.sin(s / taper_in * math.pi / 2)
        elif taper_out > 0 and s > 1 - taper_out:
            pressure = max(math.cos((s - (1 - taper_out)) / taper_out * math.pi / 2), 0.0) ** 0.8
        else:
            pressure = 1.0
        w = width * max(pressure, 0.04) * (1 + wobble * (rnd.random() - 0.5))
        left.append((pts[i][0] + nx * w / 2, pts[i][1] + ny * w / 2))
        right.append((pts[i][0] - nx * w / 2, pts[i][1] - ny * w / 2))
    return left + right[::-1]


def blob_outline(cx, cy, r, irregular=0.18, seed=0, squash=1.0, angle=0.0):
    rnd = random.Random(seed)
    ph = [rnd.random() * 6.2832 for _ in range(3)]
    k = 18
    ca, sa = math.cos(angle), math.sin(angle)
    pts = []
    for i in range(k):
        a = i / k * 2 * math.pi
        rr = r * (1 + irregular * (0.5 * math.sin(3 * a + ph[0]) + 0.3 * math.sin(5 * a + ph[1]) + 0.2 * math.sin(7 * a + ph[2])))
        x = math.cos(a) * rr
        y = math.sin(a) * rr * squash
        pts.append((cx + x * ca - y * sa, cy + x * sa + y * ca))
    return catmull(pts + pts[:3], steps=4)


def wash_outline(points):
    return catmull(points, steps=6)


# ---------- El dibujo en su caja (copia de CustomDrawing.boxMarks) ----------

def marks(d, side, left=0.0, top=0.0, t=0.0, dark=False):
    """Lista de (contorno, (r, g, b), alfa) en píxeles, en el orden en que se pintan."""
    colors = {c["key"]: c["dark" if dark else "light"] for c in d.get("colors", [])}
    seed = abs(hash(d.get("id", ""))) % (2 ** 31)
    out = []

    def place(x, y):
        return (left + x / 100 * side, top + y / 100 * side)

    for i, s in enumerate(d.get("shapes", [])):
        hexc = colors.get(s.get("c"))
        if not hexc:
            continue
        rgb = tuple(int(hexc[j:j + 2], 16) for j in (1, 3, 5))
        alpha = float(s.get("a", 0.5))
        move = s.get("move", "NONE")
        phase = i * 1.3
        if move == "TWINKLE":
            alpha *= 0.65 + 0.35 * math.sin(t * 2.2 + phase)

        def moved(points):
            if not points:
                return points
            px, py = points[0]
            if move in ("SWAY", "FLAP"):
                flap = move == "FLAP"
                direction = -1 if (flap and points[-1][0] < px) else 1
                a = direction * 0.35 * math.sin(t * 5) if flap else 0.12 * math.sin(t * 1.4 + phase)
                c, sn = math.cos(a), math.sin(a)
                return [(px + (x - px) * c - (y - py) * sn, py + (x - px) * sn + (y - py) * c) for x, y in points]
            if move == "BOB":
                return [(x, y + 2 * math.sin(t * 1.6 + phase)) for x, y in points]
            return points

        k = s.get("k")
        if k == "wash":
            pts = [place(x, y) for x, y in moved([tuple(p) for p in s.get("pts", [])])]
            if len(pts) >= 3:
                out.append((wash_outline(pts), rgb, alpha))
        elif k == "stroke":
            pts = [place(x, y) for x, y in moved([tuple(p) for p in s.get("pts", [])])]
            if len(pts) >= 3:
                out.append((stroke_outline(pts, s.get("w", 3) / 100 * side, seed=seed + i), rgb, alpha))
        elif k == "blob":
            cx, cy = place(s.get("x", 50), s.get("y", 50))
            out.append((blob_outline(cx, cy, s.get("r", 5) / 100 * side, seed=seed + i, squash=s.get("sq", 1), angle=s.get("ang", 0)), rgb, alpha))
    return out


def paint(d, size=512, dark=False, t=0.0, ground_line=True, opacity=0.85):
    """La caja de 100 × 100 en un cuadrado de [size] px con margen, sobre el papel claro u oscuro."""
    img = Image.new("RGBA", (size, size), PAPEL_OSCURO + (255,) if dark else PAPEL_CLARO + (255,))
    side = size * 0.84
    left = (size - side) / 2
    top = size * 0.06
    if ground_line:
        ImageDraw.Draw(img).line([(0, top + side), (size, top + side)], fill=(90, 90, 84, 255) if dark else (214, 207, 191, 255), width=2)
    for outline, rgb, alpha in marks(d, side, left, top, t, dark):
        layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
        ImageDraw.Draw(layer).polygon(outline, fill=rgb + (int(max(0, min(1, alpha * opacity)) * 255),))
        img = Image.alpha_composite(img, layer)
    return img


def sheet(d, path):
    """Tres vistas juntas: papel claro, papel oscuro y «en la app» (chico y suavizado, como lo verás)."""
    light = paint(d, 512, dark=False)
    dark = paint(d, 512, dark=True)
    small = paint(d, 512, dark=True, ground_line=False).resize((150, 150), Image.LANCZOS).filter(ImageFilter.GaussianBlur(0.6))
    out = Image.new("RGBA", (512 * 2 + 150 + 24, 512), (255, 255, 255, 255))
    out.paste(light, (0, 0))
    out.paste(dark, (512 + 12, 0))
    out.paste(small, (512 * 2 + 24, 512 - 150))
    out.convert("RGB").save(path)


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)
