package com.jorge.koreandiary.scene

/** Un sombreador de la escena viva. */
enum class SceneMaterial { MOOD, WATERCOLOR }

/**
 * Sombreadores de la escena viva, en SkSL: el celular los compila con AGSL (`RuntimeShader`, Android
 * 13+) y la PC con Skia (`RuntimeEffect`). Los dibujos son vectores a pincel (`scene.art.SceneArt`);
 * los sombreadores ponen lo que un vector no tiene:
 * - [SceneMaterial.MOOD]: la luz del tono que sube desde abajo y respira, con partículas.
 * - [SceneMaterial.WATERCOLOR]: el filtro de acuarela de la capa de los dibujos (`RenderEffect`, con
 *   `uniform shader content`): bordes que se corren, pigmento en el borde, granulado y papel.
 *
 * Todos comparten [UNIFORMS] (los declara el preludio; los que un sombreador no usa igual existen):
 * `uSize` (px), `uBox`, `uSide`, `uEdge` (unidad de medida), `uTime` (s), `uAlpha` (cuánto se ve),
 * `uSeed`, `uKind` (variante: las partículas del tono), `uDark` (1 sobre papel oscuro), `uParam`
 * (tono: intensidad y velocidad) y `uColor`, `uColor2` (`layout(color)`).
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
        SceneMaterial.MOOD -> MOOD
        SceneMaterial.WATERCOLOR -> WATERCOLOR
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

    /** El tono de la escena: una luz que sube desde abajo (y un poco por las esquinas de abajo) y respira, con partículas según el sentimiento, solo en la parte de abajo de la hoja. uKind: 0 motas que suben (amor, entusiasmo), 1 polvo en el aire (nostalgia), 2 destellos (alegría, asombro), 3 gotas lentas (tristeza), 4 parpadeo (inquietud), 5 olas de luz (calma). uParam.x = intensidad (0..1), uParam.y = velocidad. */
    private const val MOOD = """
half4 main(float2 p) {
    float t = uTime * uParam.y;
    float intensity = uParam.x;
    float d = uSize.y - p.y;
    float side = min(p.x, uSize.x - p.x);
    float reach = uEdge * (1.5 + 1.1 * intensity);
    float2 q = p / uEdge;
    float breath = 0.85 + 0.15 * sin(t * 0.6);
    if (uKind > 3.5 && uKind < 4.5) {
        breath = 0.7 + 0.3 * vnoise(float2(t * 2.2, 1.0)) + 0.1 * step(0.92, vnoise(float2(t * 7.0, 3.0)));
    }
    float texture = 0.75 + 0.5 * fbm(q * 0.35 + float2(t * 0.03, -t * 0.02));
    float glow = (exp(-d / reach) + 0.6 * exp(-side / (uEdge * 0.9)) * exp(-d / (reach * 1.8))) * breath * texture;
    float hueMix = fbm(q * 0.2 + float2(-t * 0.02, t * 0.015));
    float3 col = mix(uColor.rgb, uColor2.rgb, smoothstep(0.3, 0.7, hueMix));
    float a = glow * (0.2 + 0.14 * intensity);
    if (uKind > 4.5) {
        float wave = 0.5 + 0.5 * sin(q.y * 0.9 - t * 0.5 + fbm(q * 0.5) * 2.0);
        a += exp(-d / reach) * wave * 0.1 * intensity;
    }
    float band = 1.0 - smoothstep(reach * 0.9, reach * 2.0, d);
    if (band > 0.0) {
        float cellS = mix(0.7, 0.5, step(1.5, uKind));
        float2 drift;
        if (uKind < 0.5) drift = float2(sin(t * 0.3) * 0.2, t * 0.35);
        else if (uKind < 1.5) drift = float2(-t * 0.08, -t * 0.03);
        else if (uKind < 2.5) drift = float2(0.0, 0.0);
        else if (uKind < 3.5) drift = float2(0.0, -t * 0.25);
        else drift = float2(-t * 0.05, 0.0);
        float2 pq = (q + drift) / cellS;
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

    /** Filtro de acuarela para la capa de los dibujos a pincel (RenderEffect): bordes que se corren con ruido, pigmento acumulado en el borde, granulado y textura del papel. uAlpha = cuánto se ve todo; en papel oscuro el pigmento cubre más para no verse turbio. */
    private const val WATERCOLOR = """
uniform shader content;

half4 main(float2 p) {
    float2 warp = float2(fbm(p / 18.0), fbm(p / 18.0 + 9.0)) - 0.5;
    float2 q = p + warp * 7.0;
    half4 c = content.eval(q);
    float a = c.a;
    if (a < 0.002) {
        return half4(0.0);
    }
    float around = 0.0;
    for (int i = 0; i < 8; i++) {
        float ang = float(i) * 0.7854;
        around += content.eval(q + float2(cos(ang), sin(ang)) * 4.0).a;
    }
    around /= 8.0;
    float edge = clamp((a - around) * 3.0, 0.0, 1.0);
    float grain = fbm(p / 2.2);
    float paper = fbm(p / 40.0);
    float3 col = c.rgb / max(a, 0.001);
    float pigment = a * (0.78 + 0.35 * edge) * (0.82 + 0.3 * grain) * (0.9 + 0.2 * paper);
    col = mix(col, col * (0.8 + 0.2 * grain), 1.0 - uDark);
    float outA = clamp(pigment * mix(1.0, 1.45, uDark), 0.0, 1.0) * uAlpha;
    return half4(half3(col * outA), half(outA));
}
"""
}
