# Arquitectura

Documento vivo con todas las decisiones: https://claude.ai/artifact/HF2YEd3vBEkcJP7zH7sXrg

## Resumen

- **Objetivo:** diario manuscrito en coreano (escritura y lectura), en ratos cortos y en silencio. Sin listening ni speaking.
- **Flujo:** página nueva → sugerencias FSRS (sin IA) → escribir con S Pen → Revisar → ML Kit reconoce línea por línea → confirmar texto → proveedor de IA → correcciones arriba → reescribir debajo → Verificar (comparación local, sin IA).
- **Proveedores:** interfaz `CorrectionProvider`. MVP: Gemini (Interactions API, capa gratuita) y “Compartir con un chat” (Claude/ChatGPT, copiar y pegar). Futuros: Azure, servidor propio.
- **Contrato JSON:** `oraciones`, `correcciones`, `usados` (con ids `w12`/`g3`), `nuevos`. Ver `ai/Prompts.kt` y `ai/Parsers.kt`.
- **FSRS-6:** el uso en el diario es el repaso. Correcto sin sugerencia = Good; correcto tras verlo sugerido = Hard; incorrecto = Again; sugerido y no usado = sin calificación.
- **Palabras nuevas:** se agregan solas si son hangul válido; si no, quedan en “Por revisar”. Pendiente: validar con la API del 한국어기초사전.

## Distribución y actualizaciones

- GitHub Actions compila en cada push a `main`; el número de compilación es el `versionCode` (`0.1.N`).
- El release `latest` contiene `koreandiary.apk` y `version.json` (`versionCode`, `versionName`, `commit`, `notes`, `sha256`, `size`).
- `update/Updater.kt`: compara versiones, descarga, verifica SHA-256 e instala con `PackageInstaller`. Con `UPDATE_PACKAGES_WITHOUT_USER_ACTION` (Android 12+) la autoactualización no pide confirmación.

## Diferencias con el documento (MVP)

- Perfil y proveedores viven en SharedPreferences (claves cifradas con Android Keystore), no en tablas.
- Las reglas de conjugación se guardan como campo `irregular` de cada palabra; la tabla aparte queda para después.
- La integración con AnkiDroid queda para la siguiente iteración.
