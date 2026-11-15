# Instrucciones para agentes en esta carpeta

Todo está en `README.md` (sección «Cómo trabajar» y «Formato»). Resumen:

- Dibuja en `salida/<id>.json` lo de `palabras.tsv` que todavía no exista (ni en `salida/` ni en `app/src/main/assets/dibujos/`, donde están los que ya trae la app), uno por archivo.
- Después de cada dibujo: `python ver.py salida/<id>.json`, mira `salida/<id>.png` y corrige (hasta 3 vueltas); luego `python validar.py salida/<id>.json` sin errores.
- No cambies nada fuera de `tools/dibujos/`. Commits en español, sin líneas de atribución.
