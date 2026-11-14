package com.jorge.koreandiary.scene

/**
 * Sombreadores de la escena viva, en SkSL: el celular los compila con AGSL (`RuntimeShader`, Android
 * 13+) y la PC con Skia (`RuntimeEffect`). Cada material es un programa que pinta píxel por píxel lo
 * suyo desde un borde ([SceneLayout] le da la caja y el lado), con ruido y movimiento, en vez de
 * figuras dibujadas a mano.
 *
 * Todos comparten [UNIFORMS] (los declara el preludio; los que un material no usa igual existen):
 * - `uSize`: tamaño de la hoja en px. `uBox`: la caja del elemento (izquierda, arriba, derecha, abajo).
 * - `uSide`: el borde (orden de [SceneSide]). `uEdge`: la unidad de [SceneLayout.edge].
 * - `uTime`: segundos. `uAlpha`: entrada suave (0..1). `uSeed`: [SceneSlot.seed]. `uKind`: la variante.
 * - `uDark`: 1 sobre papel oscuro. `uParam`: extra del material (el tono: intensidad y velocidad).
 * - `uColor`, `uColor2`: los colores de la paleta de cada app (`layout(color)`).
 *
 * Devuelven color premultiplicado. Solo ASCII y sin comentarios dentro del código del sombreador:
 * se comentan aquí. Se prueban compilando en la PC (`SceneShadersTest` de `:desktopApp`).
 */
object SceneShaders {

    val UNIFORMS: List<String> = listOf(
        "uSize", "uBox", "uSide", "uEdge", "uTime", "uAlpha", "uSeed", "uKind", "uDark", "uParam", "uColor", "uColor2",
    )

    /** El código completo de [material]: preludio + su `main`. */
    fun source(material: SceneMaterial): String = PRELUDE + "\n" + when (material) {
        SceneMaterial.WATER -> WATER
        SceneMaterial.FOLIAGE -> FOLIAGE
        SceneMaterial.GROUND -> GROUND
        SceneMaterial.SKYLINE -> SKYLINE
        SceneMaterial.SKY -> SKY
        SceneMaterial.LIGHT -> LIGHT
        SceneMaterial.CLOUDS -> CLOUDS
        SceneMaterial.STARS -> STARS
        SceneMaterial.RAINBOW -> RAINBOW
        SceneMaterial.WIND -> WIND
        SceneMaterial.FALL -> FALL
        SceneMaterial.MIST -> MIST
        SceneMaterial.MOOD -> MOOD
    }

    /** Uniformes comunes, azar sin seno (estable en la GPU del celular), ruido, coordenadas desde el borde y ayudas. */
    private const val PRELUDE = """
uniform float2 uSize;
uniform float4 uBox;
uniform float uSide;
uniform float uEdge;
uniform float uTime;
uniform float uAlpha;
uniform float uSeed;
uniform float uKind;
uniform float uDark;
uniform float4 uParam;
layout(color) uniform half4 uColor;
layout(color) uniform half4 uColor2;

float hash21(float2 p) {
    float3 p3 = fract(float3(p.xyx + uSeed * 17.0) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float hash11(float n) {
    return hash21(float2(n, n * 0.618));
}

float vnoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash21(i), hash21(i + float2(1.0, 0.0)), u.x),
               mix(hash21(i + float2(0.0, 1.0)), hash21(i + float2(1.0, 1.0)), u.x), u.y);
}

float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += a * vnoise(p);
        p = p * 2.03 + float2(17.0, 9.0);
        a *= 0.5;
    }
    return v;
}

float4 edgeSpace(float2 p) {
    if (uSide < 0.5) return float4(p.x - uBox.x, uBox.w - p.y, uBox.z - uBox.x, uBox.w - uBox.y);
    if (uSide < 1.5) return float4(p.x - uBox.x, p.y - uBox.y, uBox.z - uBox.x, uBox.w - uBox.y);
    if (uSide < 2.5) return float4(uBox.w - p.y, p.x - uBox.x, uBox.w - uBox.y, uBox.z - uBox.x);
    if (uSide < 3.5) return float4(uBox.w - p.y, uBox.z - p.x, uBox.w - uBox.y, uBox.z - uBox.x);
    float d = min(min(p.x, p.y), min(uSize.x - p.x, uSize.y - p.y));
    return float4(p.x + p.y, d, uSize.x + uSize.y, uEdge * 1.4);
}

float slotFade(float4 e) {
    if (uSide > 3.5) return 1.0;
    float f = e.z * 0.22;
    return smoothstep(0.0, f, e.x) * (1.0 - smoothstep(e.z - f, e.z, e.x));
}

float frameGlow(float2 p, float r) {
    float g = exp(-p.x / r) + exp(-(uSize.x - p.x) / r) + exp(-p.y / r) + exp(-(uSize.y - p.y) / r);
    return clamp(g, 0.0, 1.2);
}

half4 premul(float3 c, float a) {
    a = clamp(a, 0.0, 1.0) * uAlpha;
    return half4(half3(c * a), half(a));
}

float segDist(float2 p, float2 a, float2 b) {
    float2 pa = p - a;
    float2 ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h);
}
"""

    /** Agua al costado: mar (uKind 0) o lago y río (1), visto en perspectiva: el horizonte arriba de su espacio, las olas más juntas lejos y más grandes cerca, brillos y espuma en la orilla. */
    private const val WATER = """
half4 main(float2 p) {
    float4 e = edgeSpace(p);
    float t = uTime;
    float calm = uKind;
    float len = e.z;
    float z = clamp(1.0 - e.x / len, 0.0, 1.0);
    float v = e.y / uEdge;
    float depth = e.w / uEdge;
    float shore = depth * (0.3 + 0.7 * pow(z, 0.7)) * (0.85 + 0.25 * fbm(float2(z * 3.0 + 3.0, t * 0.04)));
    if (v > shore + 0.5) return half4(0.0);
    float body = 1.0 - smoothstep(shore * 0.6, shore, v);
    body *= smoothstep(0.0, 0.08, z);
    float persp = 1.0 / (z + 0.12);
    float warp = fbm(float2(v * 1.2 * persp * 0.25, z * 4.0 - t * 0.05));
    float speed = mix(0.25, 0.1, calm);
    float phase = persp * mix(16.0, 11.0, calm) - t * speed * 6.2831 + warp * 2.2;
    float crest = pow(0.5 + 0.5 * sin(phase), mix(8.0, 14.0, calm));
    float lineW = mix(0.25, 1.0, z);
    float breakup = smoothstep(0.35, 0.65, vnoise(float2(v * 1.6 + t * 0.1, phase * 0.15)));
    crest *= lineW * (0.35 + 0.65 * breakup);
    float2 gq = float2(v * 5.0, e.x / uEdge * 5.0);
    float2 cell = floor(gq);
    float h = hash21(cell);
    float2 spot = cell + 0.2 + 0.6 * float2(h, hash21(cell + 3.7));
    float spotMask = 1.0 - smoothstep(0.02, 0.1 * mix(0.5, 1.2, z), length((gq - spot) * float2(1.0, 2.0)));
    float glint = step(0.7, h) * spotMask * pow(0.5 + 0.5 * sin(t * (1.2 + h * 2.5) + h * 40.0), 4.0);
    float wash = 0.06 * sin(t * 0.9 + z * 4.0);
    float foamBand = 1.0 - smoothstep(0.0, 0.25 * (0.5 + z), abs(v - shore * (0.86 + wash)));
    float foam = foamBand * smoothstep(0.4, 0.7, fbm(float2(z * 9.0 - t * 0.2, v * 3.0))) * (1.0 - calm * 0.7);

    float3 deep = uColor.rgb * mix(0.6, 0.8, uDark);
    float3 shallow = mix(uColor.rgb, uColor2.rgb, 0.45);
    float3 col = mix(deep, shallow, clamp(v / max(shore, 0.01), 0.0, 1.0) * 0.7 + z * 0.2);
    col = mix(col, uColor2.rgb, (1.0 - smoothstep(0.0, 0.3, z)) * 0.5);
    float3 light = mix(uColor2.rgb, float3(1.0), 0.5);
    col = mix(col, light, crest * 0.8);
    col = mix(col, float3(1.0), clamp(glint + foam * 0.8, 0.0, 1.0));
    float a = body * (0.48 + crest * 0.25) + (glint * 0.6 + foam * 0.45) * body;
    a *= slotFade(e);
    return premul(col, a);
}
"""

    /** Plantas. Abajo: pasto (uKind 0), trigo (1) o flores (2), en tres capas que el viento mece. Al costado: árboles (3). */
    private const val FOLIAGE = """
half4 main(float2 p) {
    float4 e = edgeSpace(p);
    float t = uTime;
    float x = e.x;
    float y = e.y;
    float depth = e.w;
    if (y > depth * 1.05) return half4(0.0);
    float3 col = float3(0.0);
    float a = 0.0;

    if (uKind < 2.5) {
        float base = 1.0 - smoothstep(0.0, depth * 0.38, y);
        col = uColor.rgb * 0.55;
        a = base * 0.3;
        for (int l = 0; l < 3; l++) {
            float fl = float(l);
            float cellW = uEdge * (0.15 - 0.025 * fl);
            float hMax = depth * (0.6 + 0.18 * fl);
            float shade = 0.6 + 0.2 * fl;
            float cx = floor(x / cellW);
            for (int c = -1; c <= 1; c++) {
                float i = cx + float(c) + fl * 1000.0;
                float hb = hash11(i);
                float xb = (cx + float(c) + 0.15 + 0.7 * hb) * cellW;
                float h = hMax * (0.5 + 0.5 * hash11(i + 7.3));
                if (y > h + cellW) continue;
                float s = clamp(y / h, 0.0, 1.0);
                float wind = sin(t * 0.8 + xb / uEdge * 0.5) * 0.5 + (vnoise(float2(xb / uEdge * 0.2 - t * 0.12, fl * 7.0)) - 0.5) * 1.6;
                float lean = (hash11(i + 3.1) - 0.5) * 0.6;
                float bend = (wind * 0.32 + lean) * h * s * s;
                float bx = xb + bend;
                float w = cellW * 0.2 * (1.0 - s * 0.85) + 0.5;
                float cov = clamp(w - abs(x - bx) + 0.5, 0.0, 1.0) * step(y, h);
                float3 bc = mix(uColor.rgb * 0.6, mix(uColor.rgb, uColor2.rgb, 0.35), s) * shade;
                if (uKind > 1.5) bc = mix(uColor2.rgb * 0.55, uColor2.rgb, s) * shade;
                float tipBend = (wind * 0.32 + lean) * h;
                float2 tip = float2(xb + tipBend, h);
                if (uKind > 0.5 && uKind < 1.5) {
                    float2 q = float2(x, y) - tip;
                    float ear = length(float2(q.x * 2.6, (q.y + cellW * 0.4) * 0.9));
                    float ec = clamp(cellW * 0.55 - ear + 0.5, 0.0, 1.0);
                    if (ec > 0.0) {
                        float grain = 0.75 + 0.25 * step(0.5, fract((q.y) / (cellW * 0.25)));
                        bc = mix(bc, mix(uColor.rgb, uColor2.rgb, 0.5) * grain * shade * 1.15, ec);
                        cov = max(cov, ec);
                    }
                }
                if (uKind > 1.5 && hb > 0.45) {
                    float2 q = float2(x, y) - tip;
                    float ang = atan(q.y, q.x);
                    float petals = 0.75 + 0.25 * cos(ang * 5.0 + hb * 6.0);
                    float r = cellW * (0.45 + 0.25 * hash11(i + 1.7)) * petals;
                    float fc = clamp(r - length(q) + 0.5, 0.0, 1.0);
                    if (fc > 0.0) {
                        float3 pc = uColor.rgb * (0.85 + 0.3 * hash11(i + 5.5));
                        float core = clamp(r * 0.35 - length(q) + 0.5, 0.0, 1.0);
                        pc = mix(pc, float3(1.0, 0.86, 0.45), core);
                        bc = mix(bc, pc * (0.8 + 0.2 * fl), fc);
                        cov = max(cov, fc);
                    }
                }
                col = mix(col, bc, cov);
                a = max(a, cov * (0.55 + 0.15 * fl));
            }
        }
        a *= 1.0 - smoothstep(depth * 0.8, depth * 1.05, y);
    } else {
        float sway = sin(t * 0.5 + x / uEdge * 0.3) * 0.07 * uEdge + sin(t * 1.3 + x / uEdge) * 0.015 * uEdge;
        float yy = y - sway * clamp(y / depth, 0.0, 1.0);
        float2 sp = float2(yy, x);
        float reach = depth * (0.4 + 0.45 * fbm(float2(x / uEdge * 0.4, 1.0))) * smoothstep(0.0, 0.2, x / e.z);
        float mass = reach - yy + (fbm(sp / uEdge * 1.3) - 0.5) * uEdge * 0.6;
        float cellS = uEdge * 0.11;
        float2 q = sp / cellS;
        float2 ci = floor(q);
        float leaf = 0.0;
        float leafTone = 0.0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                float2 c = ci + float2(float(dx), float(dy));
                float h = hash21(c);
                float2 center = c + 0.5 + 0.5 * float2(hash21(c + 1.9) - 0.5, hash21(c + 3.3) - 0.5);
                float ang = h * 6.2831 + sin(t * 0.9 + h * 12.0) * 0.15;
                float2 dd = q - center;
                dd = float2(dd.x * cos(ang) - dd.y * sin(ang), dd.x * sin(ang) + dd.y * cos(ang));
                float l = 1.0 - smoothstep(0.55, 0.62, length(dd * float2(1.0, 1.9)));
                if (l > leaf) {
                    leaf = l;
                    leafTone = hash21(c + 5.5);
                }
            }
        }
        float edgeZone = uEdge * 0.25;
        float inside = smoothstep(-edgeZone, 0.0, mass);
        float cov = max(smoothstep(0.0, edgeZone, mass), leaf * inside);
        float deep = clamp(mass / (uEdge * 0.9), 0.0, 1.0);
        float lit = 0.35 + 0.45 * leafTone * leaf + 0.35 * (1.0 - deep) + 0.1 * clamp((yy - reach * 0.5) / uEdge, -1.0, 1.0);
        float3 lc = mix(uColor.rgb * 0.55, uColor2.rgb, clamp(lit, 0.0, 1.0));
        col = lc;
        a = cov * 0.62;
        for (int k = 0; k < 2; k++) {
            float fk = float(k);
            float pos = depth * (0.1 + 0.2 * fk) + sin(x / uEdge * 0.7 + fk * 2.0) * uEdge * 0.04;
            float s01 = x / e.z;
            float tw = uEdge * (0.09 - 0.03 * fk) * (1.0 - 0.6 * s01);
            float tm = clamp(tw - abs(y - pos) + 0.5, 0.0, 1.0) * (1.0 - smoothstep(0.35, 0.6, s01)) * (1.0 - cov * 0.85);
            col = mix(col, mix(uColor.rgb, float3(0.3, 0.22, 0.15), 0.75) * (0.8 + 0.2 * vnoise(float2(y, x) / 3.0)), tm);
            a = max(a, tm * 0.6);
        }
        a *= 1.0 - smoothstep(depth * 0.85, depth, y);
    }
    a *= slotFade(e);
    return premul(col, a);
}
"""

    /** Suelo sin plantas: arena con ondas (uKind 0), hojas caídas (1) o calle empedrada (2). */
    private const val GROUND = """
half4 main(float2 p) {
    float4 e = edgeSpace(p);
    float t = uTime;
    float x = e.x;
    float y = e.y;
    float depth = e.w;
    if (y > depth) return half4(0.0);
    float fade = 1.0 - smoothstep(depth * 0.35, depth, y);
    float3 col = uColor.rgb;
    float a = 0.0;
    float2 q = float2(x, y) / uEdge;

    if (uKind < 0.5) {
        float dune = fbm(float2(q.x * 0.35, 0.0) + float2(t * 0.01, 0.0));
        float ripple = 0.5 + 0.5 * sin(q.x * 7.0 + fbm(q * 1.3) * 5.0 + q.y * 2.0);
        float grain = hash21(floor(p / 2.0));
        float sparkle = step(0.997, grain) * pow(0.5 + 0.5 * sin(t * 1.5 + grain * 60.0), 4.0);
        col = mix(uColor.rgb * 0.8, uColor2.rgb, ripple * 0.35 + dune * 0.3);
        col = mix(col, float3(1.0), sparkle);
        a = fade * (0.42 + 0.12 * ripple) + sparkle * fade * 0.4;
    } else if (uKind < 1.5) {
        a = fade * 0.18;
        col = uColor.rgb * 0.6;
        float cellS = 0.42;
        float2 cq = q / cellS;
        float2 ci = floor(cq);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                float2 c = ci + float2(float(dx), float(dy));
                float h = hash21(c);
                if (h < 0.35) continue;
                float drift = sin(t * 0.5 + h * 30.0) * 0.08 * step(0.85, h);
                float2 center = c + 0.5 + 0.35 * float2(hash21(c + 1.3) - 0.5, hash21(c + 2.9) - 0.5) + float2(drift, 0.0);
                float ang = h * 6.2831 + drift * 3.0;
                float2 d = cq - center;
                d = float2(d.x * cos(ang) - d.y * sin(ang), d.x * sin(ang) + d.y * cos(ang));
                float leaf = length(d * float2(1.0, 2.1));
                float m = 1.0 - smoothstep(0.30, 0.34, leaf);
                float vein = 1.0 - smoothstep(0.0, 0.025, abs(d.y));
                float3 lc = mix(uColor.rgb, uColor2.rgb, hash21(c + 5.1));
                lc *= 0.85 + 0.25 * hash21(c + 7.7) - vein * 0.25 * m;
                col = mix(col, lc, m);
                a = max(a, m * fade * 0.7);
            }
        }
    } else {
        float rowH = 0.32;
        float row = floor(q.y / rowH);
        float off = mod(row, 2.0) * 0.5;
        float2 cell = float2((q.x / 0.5) + off, q.y / rowH);
        float2 f = fract(cell);
        float2 id = floor(cell);
        float joint = smoothstep(0.0, 0.08, f.x) * (1.0 - smoothstep(0.92, 1.0, f.x)) * smoothstep(0.0, 0.12, f.y) * (1.0 - smoothstep(0.88, 1.0, f.y));
        float tone = 0.8 + 0.3 * hash21(id);
        float sheen = pow(0.5 + 0.5 * sin(q.x * 0.6 - t * 0.4), 12.0) * (1.0 - f.y);
        col = mix(uColor.rgb * 0.55, uColor.rgb * tone, joint);
        col = mix(col, uColor2.rgb, sheen * 0.5 * joint);
        a = fade * (0.25 + 0.3 * joint) * (1.0 - smoothstep(depth * 0.3, depth * 0.85, y));
    }
    a *= slotFade(e);
    return premul(col, a);
}
"""

    /** Siluetas al costado: montañas en capas con neblina (uKind 0) o edificios con ventanas encendidas (1). */
    private const val SKYLINE = """
half4 main(float2 p) {
    float4 e = edgeSpace(p);
    float t = uTime;
    float up = e.x;
    float inw = e.y;
    float depth = e.w;
    if (inw > depth) return half4(0.0);
    float3 col = float3(0.0);
    float a = 0.0;

    if (uKind < 0.5) {
        for (int l = 0; l < 3; l++) {
            float fl = float(l);
            float s = inw / uEdge * (1.2 + 0.5 * fl) + fl * 7.0;
            float ridge = 1.0 - abs(vnoise(float2(s * 0.9, fl)) * 2.0 - 1.0);
            float hgt = e.z * (0.42 - 0.08 * fl) * (0.5 + 0.5 * ridge) * (1.0 - inw / depth * 0.5);
            hgt += uEdge * 0.25 * fbm(float2(s * 3.0, fl * 3.0));
            float m = clamp((hgt - up) / 1.5, 0.0, 1.0);
            if (m <= 0.0) continue;
            float haze = 1.0 - fl / 3.0;
            float3 mc = mix(uColor2.rgb, uColor.rgb * 0.7, fl / 2.0);
            float snow = smoothstep(hgt - uEdge * 0.22, hgt - uEdge * 0.05, up) * step(fl, 0.5) * step(uEdge * 2.0, hgt);
            mc = mix(mc, float3(0.95), snow * 0.7);
            mc *= 0.9 + 0.12 * sin(up / uEdge * 1.5 - t * 0.15 + fl);
            col = mix(col, mc, m);
            a = max(a, m * (0.32 + 0.14 * fl) * mix(1.0, haze, 0.3));
        }
        a *= 1.0 - smoothstep(depth * 0.75, depth, inw);
    } else {
        float bw = uEdge * 0.38;
        float bi = floor(inw / bw);
        float bh = hash21(float2(bi, 3.0));
        float hgt = e.z * (0.22 + 0.35 * bh) * (1.0 - inw / depth * 0.45);
        float inB = step(up, hgt);
        float gap = smoothstep(0.0, 1.5, fract(inw / bw) * bw) * (1.0 - smoothstep(bw - 1.5, bw, fract(inw / bw) * bw));
        float m = inB * gap;
        float3 bc = uColor.rgb * (0.55 + 0.25 * bh);
        float2 wq = float2(fract(inw / bw) * 3.0, up / (uEdge * 0.2));
        float2 wid = float2(bi * 3.0 + floor(wq.x), floor(wq.y));
        float lit = step(0.55, hash21(wid + floor(t * 0.05 + hash21(wid) * 5.0)));
        float2 wf = fract(wq);
        float win = step(0.3, wf.x) * step(wf.x, 0.75) * step(0.3, wf.y) * step(wf.y, 0.75) * step(up, hgt - uEdge * 0.12);
        float glow = win * lit;
        bc = mix(bc, mix(uColor2.rgb, float3(1.0, 0.85, 0.5), 0.7), glow * mix(0.6, 0.95, uDark));
        col = bc;
        a = m * (0.45 + 0.4 * glow);
        a *= 1.0 - smoothstep(depth * 0.7, depth, inw);
    }
    a *= slotFade(e);
    return premul(col, a);
}
"""

    /** Cielo arriba: azul (uKind 0), atardecer en franjas (1) o noche con un velo que se mueve (2). */
    private const val SKY = """
half4 main(float2 p) {
    float4 e = edgeSpace(p);
    float t = uTime;
    float y = e.y;
    float depth = e.w;
    if (y > depth) return half4(0.0);
    float s = y / depth;
    float2 q = float2(e.x, y) / uEdge;
    float3 col;
    float a;
    if (uKind < 0.5) {
        float light = fbm(q * 0.35 + float2(t * 0.02, 0.0));
        col = mix(uColor.rgb, uColor2.rgb, s * 0.8 + light * 0.25);
        a = (1.0 - smoothstep(0.0, 1.0, s)) * (0.42 + 0.1 * light);
    } else if (uKind < 1.5) {
        float wave = fbm(float2(q.x * 0.25 - t * 0.015, q.y * 0.6));
        float b = s + (wave - 0.5) * 0.25;
        col = mix(uColor2.rgb, uColor.rgb, smoothstep(0.0, 0.55, b));
        col = mix(col, float3(1.0, 0.82, 0.5), smoothstep(0.5, 0.95, b) * 0.6);
        a = (1.0 - smoothstep(0.15, 1.0, s)) * 0.5;
    } else {
        float veil = fbm(float2(q.x * 0.4 + t * 0.03, q.y * 1.2 - t * 0.01));
        float aurora = smoothstep(0.55, 0.8, veil) * (1.0 - s) * 0.5;
        col = mix(uColor.rgb * 0.8, uColor2.rgb, aurora);
        a = (1.0 - smoothstep(0.0, 1.0, s)) * 0.5 + aurora * 0.3;
    }
    a *= slotFade(e);
    return premul(col, a);
}
"""

    /** Sol (uKind 0) con rayos que giran muy despacio, o luna (1) con su halo; los dos respiran. */
    private const val LIGHT = """
half4 main(float2 p) {
    float4 e = edgeSpace(p);
    float t = uTime;
    float2 c = float2(e.z * 0.5, uEdge * 0.62);
    float2 d = float2(e.x, e.y) - c;
    float r = length(d);
    float R = uEdge * 0.42;
    float breath = 1.0 + 0.05 * sin(t * 0.9);
    float3 col;
    float a;
    if (uKind < 0.5) {
        float ang = atan(d.y, d.x);
        float rays = 0.5 + 0.5 * sin(ang * 11.0 + t * 0.12) * sin(ang * 7.0 - t * 0.09 + 1.3);
        float rayFall = exp(-max(r - R, 0.0) / (uEdge * 1.1));
        float halo = exp(-max(r - R, 0.0) / (uEdge * 0.55 * breath));
        float disk = 1.0 - smoothstep(R * breath - 1.5, R * breath + 1.5, r);
        col = mix(uColor.rgb, uColor2.rgb, disk * 0.8 + halo * 0.2);
        a = disk * 0.85 + halo * 0.45 + rays * rayFall * 0.22 * (1.0 - disk);
    } else {
        float disk = 1.0 - smoothstep(R * 0.9 - 1.5, R * 0.9 + 1.5, r);
        float crater = fbm(d / uEdge * 4.0 + 3.0);
        float halo = exp(-max(r - R, 0.0) / (uEdge * 0.7 * breath));
        float3 moonC = mix(uColor2.rgb, uColor.rgb * 0.8, smoothstep(0.45, 0.7, crater));
        col = mix(uColor.rgb, moonC, disk);
        a = disk * 0.85 + halo * 0.35 * (1.0 - disk);
    }
    a *= 1.0 - smoothstep(e.w * 0.45, e.w * 0.98, e.y);
    a *= slotFade(e);
    return premul(col, a);
}
"""

    /** Nubes de ruido que pasan arriba, con el borde de arriba más claro. */
    private const val CLOUDS = """
half4 main(float2 p) {
    float4 e = edgeSpace(p);
    float t = uTime;
    float y = e.y;
    float depth = e.w;
    if (y > depth) return half4(0.0);
    float2 q = float2(e.x / (uEdge * 1.5) - t * 0.035, y / (uEdge * 0.75));
    float n = fbm(q + float2(0.0, t * 0.01));
    float shape = n - (y / depth) * 0.55 - 0.18;
    float dens = smoothstep(0.0, 0.25, shape);
    float above = fbm(q + float2(0.0, -0.15) + float2(0.0, t * 0.01));
    float lit = clamp(0.6 + (above - n) * 4.0, 0.0, 1.0);
    float3 col = mix(uColor.rgb * 0.85, uColor2.rgb, lit);
    float a = dens * 0.6;
    a *= slotFade(e);
    return premul(col, a);
}
"""

    /** Estrellas arriba (uKind 0) o destellos dorados por todo el marco (1): titilan con cruces de luz. */
    private const val STARS = """
half4 main(float2 p) {
    float4 e = edgeSpace(p);
    float t = uTime;
    float depth = e.w;
    if (e.y > depth * 1.2) return half4(0.0);
    float cellS = uEdge * mix(0.42, 0.55, uKind);
    float2 q = p / cellS;
    float2 ci = floor(q);
    float3 col = float3(0.0);
    float a = 0.0;
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            float2 c = ci + float2(float(dx), float(dy));
            float h = hash21(c);
            if (h < mix(0.45, 0.7, uKind)) continue;
            float2 center = c + 0.2 + 0.6 * float2(hash21(c + 1.1), hash21(c + 2.3));
            float2 d = (q - center) * cellS / uEdge;
            float speed = 0.6 + 1.6 * hash21(c + 4.4);
            float tw = pow(0.5 + 0.5 * sin(t * speed + h * 50.0), 3.0);
            if (uKind > 0.5) tw = pow(max(sin(t * speed * 0.5 + h * 50.0), 0.0), 6.0);
            float size = 0.025 + 0.035 * hash21(c + 6.6);
            float core = exp(-dot(d, d) / (size * size));
            float flare = (exp(-abs(d.x) / (size * 0.35)) * exp(-abs(d.y) / (size * 3.5)) + exp(-abs(d.y) / (size * 0.35)) * exp(-abs(d.x) / (size * 3.5)));
            float m = (core + flare * 0.6 * step(0.8, h)) * tw;
            col = mix(col, mix(uColor.rgb, uColor2.rgb, core), clamp(m, 0.0, 1.0));
            a = max(a, m);
        }
    }
    a *= 1.0 - smoothstep(depth * 0.8, depth * 1.2, e.y);
    a *= slotFade(e);
    return premul(col, a);
}
"""

    /** Arcoíris tenue arriba, de un extremo a otro de su espacio. */
    private const val RAINBOW = """
half4 main(float2 p) {
    float4 e = edgeSpace(p);
    float t = uTime;
    float2 c = float2(e.z * 0.5, e.z * 0.62);
    float r = length(float2(e.x, e.y) - c);
    float R = e.z * 0.62 - uEdge * 0.35;
    float band = uEdge * 0.42;
    float s = (R - r) / band;
    if (s < 0.0 || s > 1.0) return half4(0.0);
    float3 hue = clamp(abs(fract(s * 0.85 + float3(0.0, 0.667, 0.333)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
    float edgeSoft = smoothstep(0.0, 0.15, s) * (1.0 - smoothstep(0.85, 1.0, s));
    float shimmer = 0.85 + 0.15 * sin(e.x / uEdge * 0.8 - t * 0.4);
    float a = edgeSoft * 0.38 * shimmer;
    a *= slotFade(e);
    return premul(mix(hue, float3(1.0), 0.25), a);
}
"""

    /** Viento: rachas finas que cruzan cerca de los bordes, con la cabeza clara y la cola que se apaga, y motas de polvo que se lleva. */
    private const val WIND = """
half4 main(float2 p) {
    float4 e = edgeSpace(p);
    float t = uTime;
    float d = e.y;
    float depth = e.w;
    if (d > depth * 1.5) return half4(0.0);
    float mask = 1.0 - smoothstep(depth * 0.5, depth * 1.4, d);
    float2 q = p / uEdge;
    float laneH = 0.55;
    float lane = q.y / laneH;
    float li = floor(lane);
    float hl = hash11(li);
    float wav = sin(q.x * 0.8 + li * 1.7 + t * 0.7) * 0.16 + sin(q.x * 1.9 - t * 0.45 + li) * 0.05;
    float distPx = abs(fract(lane) - 0.5 - wav) * laneH * uEdge;
    float line = clamp(1.4 - distPx, 0.0, 1.0);
    float s = q.x * 0.28 - t * (0.18 + 0.14 * hl) + hl * 10.0;
    float f = fract(s);
    float streak = smoothstep(0.0, 0.32, f) * (1.0 - smoothstep(0.32, 0.36, f));
    float gust = smoothstep(0.35, 0.65, vnoise(float2(floor(s) * 1.7, li * 3.1)));
    float m = line * streak * gust;

    float2 mq = (p + float2(-t * uEdge * 0.9, sin(t * 0.8 + p.x / uEdge) * uEdge * 0.08)) / (uEdge * 0.45);
    float2 mc = floor(mq);
    float mh = hash21(mc);
    float2 mcen = mc + 0.25 + 0.5 * float2(hash21(mc + 1.7), hash21(mc + 4.1));
    float mote = step(0.86, mh) * (1.0 - smoothstep(0.03, 0.07, length(mq - mcen)));

    float a = (m * 0.55 + mote * 0.45) * mask;
    float3 col = mix(uColor.rgb, uColor2.rgb, streak);
    return premul(col, a);
}
"""

    /** Lo que cae por el marco: lluvia (uKind 0), nieve (1) o pétalos (2), en dos capas de profundidad. */
    private const val FALL = """
half4 main(float2 p) {
    float4 e = edgeSpace(p);
    float t = uTime;
    float d = e.y;
    float depth = e.w;
    if (d > depth * 1.5) return half4(0.0);
    float mask = 1.0 - smoothstep(depth * 0.4, depth * 1.1, d);
    float3 col = float3(0.0);
    float a = 0.0;
    for (int l = 0; l < 2; l++) {
        float fl = float(l);
        float cellS = uEdge * (0.55 - 0.17 * fl);
        float speed = uEdge * mix(mix(3.2, 0.32, step(0.5, uKind)), 0.45, step(1.5, uKind)) * (1.0 + 0.5 * fl);
        float slant = mix(0.18, 0.0, step(0.5, uKind));
        float2 q = float2(p.x + slant * speed * t, p.y - speed * t) / cellS;
        float2 ci = floor(q);
        for (int dy = -1; dy <= 1; dy++) {
            float2 c = ci + float2(0.0, float(dy));
            float h = hash21(c + fl * 37.0);
            if (h < mix(0.55, 0.62, step(0.5, uKind))) continue;
            float2 center = c + float2(0.15 + 0.7 * hash21(c + 2.2 + fl), 0.5);
            float sway = sin(t * (1.0 + h) + h * 20.0);
            if (uKind > 0.5) center.x += sway * 0.22;
            float2 dpx = (q - center) * cellS;
            float m;
            float3 pc = mix(uColor.rgb, uColor2.rgb, h);
            if (uKind < 0.5) {
                float len = cellS * 0.7;
                float2 dir = normalize(float2(-slant, 1.0));
                m = clamp(1.6 - segDist(dpx, -dir * len * 0.5, dir * len * 0.5), 0.0, 1.0) * (0.45 + 0.4 * clamp(dot(dpx, dir) / len + 0.5, 0.0, 1.0));
            } else if (uKind < 1.5) {
                float r = uEdge * (0.025 + 0.02 * hash21(c + 5.0)) * (0.8 + 0.3 * fl);
                m = 1.0 - smoothstep(r * 0.4, r, length(dpx));
                pc = mix(uColor.rgb, uColor2.rgb, 0.7);
            } else {
                float ang = t * (0.8 + h) + h * 6.2831;
                float2 rd = float2(dpx.x * cos(ang) - dpx.y * sin(ang), dpx.x * sin(ang) + dpx.y * cos(ang));
                float flip = 0.45 + 0.55 * abs(sin(t * 1.3 + h * 9.0));
                float pr = uEdge * 0.06 * (0.8 + 0.3 * fl);
                float petal = length(rd / float2(pr, pr * 0.55 * flip));
                m = 1.0 - smoothstep(0.85, 1.0, petal);
                pc = mix(uColor.rgb, float3(1.0, 0.92, 0.95), 0.25 * hash21(c + 9.0)) * (0.85 + 0.25 * flip);
            }
            m *= 0.75 + 0.25 * fl;
            col = mix(col, pc, clamp(m, 0.0, 1.0));
            a = max(a, m);
        }
    }
    return premul(col, a * mask);
}
"""

    /** Aire del marco: niebla que se arrastra (uKind 0), calidez que respira y ondula (1) o frescura en hilos (2). */
    private const val MIST = """
half4 main(float2 p) {
    float4 e = edgeSpace(p);
    float t = uTime;
    float d = e.y;
    float depth = e.w * 1.6;
    if (uKind < 0.5 && d > depth * 1.3) return half4(0.0);
    float2 q = p / uEdge;
    float3 col;
    float a;
    if (uKind < 0.5) {
        float n = fbm(q / 1.8 + float2(t * 0.04, t * 0.012));
        float n2 = fbm(q / 0.9 - float2(t * 0.06, 0.0) + 5.0);
        float dens = smoothstep(0.35, 0.8, n * 0.6 + n2 * 0.5);
        col = mix(uColor.rgb, uColor2.rgb, n2);
        a = dens * 0.5 * (1.0 - smoothstep(0.0, depth * 0.75, d)) + 0.1 * (1.0 - smoothstep(0.0, depth * 0.4, d));
    } else if (uKind < 1.5) {
        float shimmer = fbm(q * 1.4 + float2(0.0, -t * 0.25));
        float glow = frameGlow(p, uEdge * 1.1) * (0.8 + 0.2 * sin(t * 0.7));
        col = mix(uColor.rgb, uColor2.rgb, shimmer);
        a = glow * (0.24 + 0.14 * shimmer);
    } else {
        float strands = fbm(float2(q.x / 3.0 - t * 0.08, q.y / 0.35));
        float glow = frameGlow(p, uEdge * 1.0);
        col = mix(uColor.rgb, uColor2.rgb, strands);
        a = glow * (0.12 + 0.22 * smoothstep(0.45, 0.75, strands));
    }
    return premul(col, a);
}
"""

    /** El tono de la escena: una luz que entra por los bordes y respira, con partículas según el sentimiento. uKind: 0 motas que suben (amor, entusiasmo), 1 polvo en el aire (nostalgia), 2 destellos (alegría, asombro), 3 gotas lentas (tristeza), 4 parpadeo (inquietud), 5 olas de luz (calma). uParam.x = intensidad (0..1), uParam.y = velocidad. */
    private const val MOOD = """
half4 main(float2 p) {
    float t = uTime * uParam.y;
    float intensity = uParam.x;
    float d = min(min(p.x, p.y), min(uSize.x - p.x, uSize.y - p.y));
    float reach = uEdge * (0.9 + 0.7 * intensity);
    float2 q = p / uEdge;
    float bottom = smoothstep(uSize.y * 0.4, uSize.y, p.y);
    float breath = 0.85 + 0.15 * sin(t * 0.6);
    if (uKind > 3.5 && uKind < 4.5) {
        breath = 0.7 + 0.3 * vnoise(float2(t * 2.2, 1.0)) + 0.1 * step(0.92, vnoise(float2(t * 7.0, 3.0)));
    }
    float texture = 0.75 + 0.5 * fbm(q * 0.35 + float2(t * 0.03, -t * 0.02));
    float glow = frameGlow(p, reach * (0.8 + 0.4 * bottom)) * breath * texture;
    float hueMix = fbm(q * 0.2 + float2(-t * 0.02, t * 0.015));
    float3 col = mix(uColor.rgb, uColor2.rgb, smoothstep(0.3, 0.7, hueMix));
    float a = glow * (0.2 + 0.14 * intensity) + 0.012 * intensity;

    if (uKind > 4.5) {
        float wave = 0.5 + 0.5 * sin(q.y * 0.9 - t * 0.5 + fbm(q * 0.5) * 2.0);
        a += exp(-d / reach) * wave * 0.12 * intensity;
    }

    float band = 1.0 - smoothstep(uEdge * 0.8, uEdge * 1.7, d);
    if (band > 0.0) {
        float cellS = mix(0.7, 0.5, step(1.5, uKind));
        float2 drift;
        if (uKind < 0.5) drift = float2(sin(t * 0.3) * 0.2, t * 0.35);
        else if (uKind < 1.5) drift = float2(-t * 0.08, -t * 0.03);
        else if (uKind < 2.5) drift = float2(0.0, 0.0);
        else if (uKind < 3.5) drift = float2(0.0, -t * 0.25);
        else drift = float2(-t * 0.05, 0.0);
        float2 pq = (q + float2(drift.x, drift.y)) / cellS;
        float2 ci = floor(pq);
        float pa = 0.0;
        float3 pcol = col;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                float2 c = ci + float2(float(dx), float(dy));
                float h = hash21(c);
                if (h < 0.62) continue;
                float2 center = c + 0.2 + 0.6 * float2(hash21(c + 1.3), hash21(c + 2.7));
                center.x += sin(t * 0.7 + h * 20.0) * 0.15;
                float2 dd = (pq - center) * cellS;
                float life = 0.5 + 0.5 * sin(t * (0.4 + 0.5 * h) + h * 30.0);
                float m;
                if (uKind < 0.5) {
                    float r = 0.07 + 0.09 * hash21(c + 4.4);
                    float dist = length(dd);
                    m = (1.0 - smoothstep(r * 0.55, r, dist)) * (0.7 + 0.3 * smoothstep(r * 0.3, r, dist)) * life * 0.8;
                } else if (uKind < 1.5) {
                    float r = 0.018 + 0.02 * hash21(c + 4.4);
                    m = (1.0 - smoothstep(r * 0.3, r, length(dd))) * (0.4 + 0.6 * life);
                } else if (uKind < 2.5) {
                    float tw = pow(max(sin(t * (0.8 + h) + h * 40.0), 0.0), 5.0);
                    float s = 0.03 + 0.03 * hash21(c + 4.4);
                    float core = exp(-dot(dd, dd) / (s * s));
                    float flare = exp(-abs(dd.x) / (s * 0.3)) * exp(-abs(dd.y) / (s * 3.0)) + exp(-abs(dd.y) / (s * 0.3)) * exp(-abs(dd.x) / (s * 3.0));
                    m = (core + flare * 0.5) * tw;
                } else if (uKind < 3.5) {
                    float2 sd = dd * float2(1.0, 0.45);
                    float r = 0.035 + 0.02 * hash21(c + 4.4);
                    m = (1.0 - smoothstep(r * 0.4, r, length(sd))) * 0.7;
                } else {
                    float r = 0.02;
                    m = (1.0 - smoothstep(r * 0.3, r, length(dd))) * life * 0.6;
                }
                pcol = mix(pcol, mix(uColor.rgb, uColor2.rgb, h), clamp(m, 0.0, 1.0));
                pa = max(pa, m);
            }
        }
        col = mix(col, pcol, clamp(pa, 0.0, 1.0));
        a = max(a, pa * band * (0.45 + 0.35 * intensity));
    }
    return premul(col, a * mix(0.75, 1.0, uDark));
}
"""
}
