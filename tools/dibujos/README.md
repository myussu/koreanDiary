# Dibujos de la escena para pregenerar

Kit para que un agente (Codex, Claude Code u otro) dibuje por adelantado cosas para la escena viva, en el mismo formato que usa la app. Después se agregan a la app como dibujos de fábrica: sus palabras los traen mientras escribes, igual que los 27 de siempre.

**Ya hay 108 en la app** (`app/src/main/assets/dibujos/`, la primera tanda, de Codex; su revisión está en `revision-codex.md`). Una tanda nueva se dibuja en `salida/` y, cuando está lista, se mueve a esa carpeta.

**No son imágenes:** cada dibujo es un JSON de pinceladas (aguadas, toques y pinceladas en una caja de 100 × 100) que la app pinta con su pincel y su filtro de acuarela. Por eso se mueven, cambian de color en papel oscuro y pesan ~1,5 KB cada uno: 100 dibujos son ~150 KB. Con imágenes, en cambio, 100 PNG serían 10–30 MB y quedarían quietos y con otro estilo.

## Qué hay aquí

| Archivo | Para qué |
|---|---|
| `palabras.tsv` | Lo que hay que dibujar: palabras coreanas, nombre en español, zona, tamaño e idea (las 108 de la primera tanda ya están en la app) |
| `ejemplos/` | Tres dibujos bien hechos: `gato.json`, `perro.json`, `faro.json` |
| `claves_de_la_app.txt` | Palabras que ya traen uno de los 27 dibujos de código: no se pueden usar en `keys` (las de los dibujos de fábrica las revisa `validar.py` leyendo la carpeta de la app) |
| `dibujo.py` | Pinta un dibujo con la misma geometría de pincel que la app |
| `ver.py` | `python ver.py salida/x.json` deja `salida/x.png` con tres vistas; `python ver.py --hoja` junta las de `salida/` y `python ver.py --hoja app` las que ya trae la app |
| `validar.py` | `python validar.py` revisa formato y reglas de `salida/` y de la carpeta de la app, y que ninguna palabra ni id se repita entre ellas |
| `salida/` | Aquí van los dibujos terminados, uno por archivo: `salida/<id>.json` |

Solo hace falta Python 3 con Pillow: `pip install pillow`.

## Cómo trabajar (para el agente)

Por cada fila de `palabras.tsv`, en orden, salvo que ya exista `salida/<id>.json` o `app/src/main/assets/dibujos/<id>.json`:

1. **Mira los ejemplos** (`ejemplos/`) antes del primero: muestran cómo se arma una silueta por partes.
2. **Si tienes una herramienta para generar imágenes**, puedes pedir una referencia simple (ilustración plana, de perfil, fondo liso) y guardarla en `referencias/` (no se sube al repo). Úsala solo para proporciones; el dibujo lo haces tú en JSON.
3. **Escribe** `salida/<id>.json` con el formato de abajo.
4. **Míralo:** `python ver.py salida/<id>.json` y abre `salida/<id>.png`. Las tres vistas son papel claro, papel oscuro y chico como en el celular. Pregúntate si alguien que no ve el JSON diría el nombre de la cosa con una mirada a la vista chica. Corrige y vuelve a mirar; como mucho 3 vueltas.
5. **Valida:** `python validar.py salida/<id>.json` hasta que no haya errores. Los avisos conviene arreglarlos.
6. Si trabajas en una copia del repo: commit cada 10 dibujos en la rama `dibujos/pregenerados`, con el mensaje en español y sin líneas de atribución (nada de `Co-Authored-By`). No toques nada fuera de `tools/dibujos/`.

Al terminar: `python validar.py` (todo) y `python ver.py --hoja`, y deja en `salida/_resumen.md` qué se dibujó, qué se saltó y por qué.

## Formato

```json
{"id":"cat","name":"gato","keys":["고양이","냥이"],"zone":"GROUND","size":"SMALL","motion":"NONE","count":1,
 "colors":[{"key":"fur","light":"#8a7461","dark":"#d8c2a8"}],
 "shapes":[{"k":"wash","pts":[[38,100],[34,90],[36,76]],"c":"fur","a":0.67}]}
```

- **`id`:** slug corto en inglés, minúsculas y guion bajo (`cat`, `hot_air_balloon`). También es el nombre del archivo.
- **`name`:** nombre en español, en minúsculas (`gato`).
- **`keys`:** los sustantivos coreanos que lo traen al escribir: primero el de diccionario (`고양이`), luego hasta 3 variantes o sinónimos comunes (`냥이`). Solo hangul, sin partículas ni verbos, y ninguno de `claves_de_la_app.txt`. Toma las de la primera columna de `palabras.tsv` y suma variantes si las hay. Evita palabras con otro sentido muy común (배 es barco, pero también barriga y pera). Una clave de una sílaba solo cuenta sola o con partícula (곰이, 곰을); 게, 새 y 절 solo con partícula, porque solas casi siempre son otra cosa (먹는 게, 새 옷, 절 도와주세요).
- **`zone`:** `GROUND` (adelante, se apoya en el suelo: animales, objetos, plantas chicas), `HORIZON` (atrás, en tierra o agua: edificios, barcos, árboles grandes, rocas), `SKY` (arriba: pájaros, cometas, globos, aviones), `AIR` (cosas chicas que flotan por toda la franja: mariposas, luciérnagas).
- **`size`:** `SMALL` (gato, pájaro, flor), `MEDIUM` (bicicleta, banca, perro), `LARGE` (faro, casa, barco).
- **`motion`** (todo el dibujo): `NONE`, `SWAY` (se mece; plantas), `BOB` (sube y baja; barcos, globos), `DRIFT` (cruza la escena despacio; pájaros, aviones).
- **`count`:** copias repartidas por la escena; 1 casi siempre, 2–5 solo para cosas chicas que vuelan o flotan.
- **`colors`:** 2 a 5, cada uno con `light` (para el papel crema `#faf7ee`) y `dark` (para el papel casi negro `#20201e`). Tonos de acuarela apagados, nunca neón. `light` de tono medio para que se vea en el crema (no pálido); `dark` una versión más clara y suave para que se vea en el negro. Un tono claramente más oscuro para los detalles. Colores de la escena, por armonía (claro/oscuro): mar `#5fa8d8`/`#78b8e0`, pasto `#8dbf5a`/`#a6d27a`, corteza `#7a5a3c`/`#b08a66`, sol `#f6c453`/`#fbe3a0`, piedra `#9aa3ad`/`#8e979f`.
- **`shapes`:** se pintan en orden. Cada una tiene un color `c` (una `key` de `colors`) y pigmento `a` de 0.05 a 0.9:
  - `wash`: una zona de color; `pts` es el contorno (al menos 3 puntos; la app suaviza las esquinas, así que pon dos puntos juntos donde quieras una esquina).
  - `blob`: un toque redondo en `x`,`y` con radio `r`; `sq` lo aplasta (1 redondo, 0.5 chato) y `ang` lo gira (radianes).
  - `stroke`: una pincelada por `pts` (al menos 3 puntos) de ancho `w`; entra fina y sale más fina, como un pincel de verdad.
  - `move` (opcional) anima esa parte: `SWAY` la mece alrededor de su **primer** punto (cola, bandera, rama: los puntos desde donde se une hacia afuera), `FLAP` aletea alrededor de su primer punto, en espejo (dos alas que empiezan en el cuerpo), `BOB` la sube y baja, `TWINKLE` la hace titilar (una luz).

**La caja:** 100 × 100. `x` de 0 (izquierda) a 100 (derecha); `y` de 0 (arriba) a 100 (abajo). `y = 100` es donde se apoya: el suelo, o la línea del agua para lo que flota. Centrado en `x = 50`, usando casi toda la caja.

## Qué hace bueno a un dibujo

En el celular se ve chico (~250 px de ancho) y con un filtro que suaviza y mueve los bordes unos píxeles. Por eso:

- **Una silueta clara**, desde donde mejor se reconoce (casi siempre de perfil), con los rasgos que la definen grandes y un poco exagerados: un cachorro tiene cabeza grande y redonda, orejas caídas, hocico, patas cortas y gruesas, y una colita.
- **Formas sólidas:** nada más fino que `w` 3 (salvo bigotes), ojos de `r` 2.5 o más. Muchos toques chicos encimados se funden en una mancha.
- **Por partes:** (1) las masas grandes como `wash` que siguen el contorno real (8 a 20 puntos); (2) patas, postes y tallos como `stroke` gruesos (patas `w` 5 a 9) que llegan a `y = 100` si se apoya; (3) los rasgos (orejas, hocico, cola, techo, ruedas, pétalos); (4) uno o dos `blob` de sombra en un tono más oscuro (`a` 0.12 a 0.2) y al final los detalles oscuros (ojo, nariz).
- 8 a 30 formas. Cuerpos `a` 0.55 a 0.75; sombras y halos 0.12 a 0.25; pinceladas 0.55 a 0.8.
- Sin texto, sin contorno alrededor de todo y sin caras detalladas.
- Movimiento solo donde tiene sentido: muchas cosas están quietas.

## Prompt para pegarle a Codex

> Lee `tools/dibujos/README.md` y sigue la sección «Cómo trabajar»: dibuja en `tools/dibujos/salida/` todo lo de `palabras.tsv` que todavía no esté, mirando cada dibujo con `ver.py` y validándolo con `validar.py`. Trabaja en la rama `dibujos/pregenerados` y haz commit cada 10 dibujos. No cambies nada fuera de `tools/dibujos/`.

## Después

Cuando la tanda esté lista y `python validar.py` no dé errores, se mueven los JSON de `salida/` a `app/src/main/assets/dibujos/` (con `git mv`). La app los lee al abrir y suma sus palabras al diccionario de la escena; si una palabra también es de un dibujo que hizo Claude para ti, gana el tuyo. El test `FactoryDrawingsTest` (en `shared/src/jvmTest`) revisa en cada compilación que todos se lean y que cada palabra traiga su dibujo. Si alguno no convence, se borra su JSON y listo.
