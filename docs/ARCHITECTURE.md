# Arquitectura

Documento vivo con todas las decisiones: https://claude.ai/artifact/HF2YEd3vBEkcJP7zH7sXrg

## Resumen

- **Objetivo:** diario manuscrito en coreano (escritura y lectura), en ratos cortos y en silencio. Sin listening ni speaking.
- **Lienzo:** página que crece hacia abajo (siempre 6 líneas libres bajo lo escrito o revisado) y se desplaza: con “solo S Pen” el dedo desplaza; si el dedo escribe, se desplaza con dos dedos. Los trazos se guardan en coordenadas de página. “+ Línea” inserta un renglón vacío donde tocas (lo de abajo baja, como insertar fila en Excel); si cae en la zona revisada, la zona crece. “Verificar” revisa toda la página, así que puedes reescribir debajo o en el mismo lugar.
- **Flujo:** página nueva → sugerencias FSRS (sin IA) → escribir con S Pen → Revisar → ML Kit reconoce línea por línea → confirmar texto → proveedor de IA → correcciones arriba → reescribir debajo → Verificar (comparación local, sin IA).
- **Proveedores:** interfaz `CorrectionProvider`. Gemini (Interactions API, capa gratuita), Azure OpenAI (API v1 + Responses API, despliegue recomendado `gpt-5.6-luna` en Global Standard, razonamiento bajo) y “Compartir con un chat” (Claude/ChatGPT, copiar y pegar). Futuro: servidor propio.
- **Contrato JSON:** `oraciones`, `correcciones`, `usados` (con ids `w12`/`g3`), `nuevos`. Ver `ai/Prompts.kt` y `ai/Parsers.kt`.
- **Todo lo usado cuenta:** la IA devuelve en `usados` cada palabra (forma de diccionario) y cada gramática (notación estándar) de la página. La app las busca en tu base, luego en el catálogo estándar, y crea las que falten con su nivel (`N급`) y la fuente `diario`. Solo se omiten partículas y terminaciones básicas (이/가, 은/는, -어요...).
- **Catálogo estándar (assets/standard):** lista oficial del 국립국어원 de vocabulario (10.635) y gramática (336) por niveles 1–6, 공공누리 제1유형. Se consulta en memoria: el nivel no cuesta tokens. Las gramáticas se comparan por claves normalizadas (`GrammarKeys`): sin A/V/N, con y sin (으), separando terminaciones de partículas.
- **Lagunas:** en cada página nueva se sugiere 1 palabra y 1 gramática del estándar de tu nivel o del anterior que no están en tu base (marcadas con “+”). Además la IA puede proponer hasta 2 `mejoras`: estructuras de tu nivel donde escribiste algo demasiado básico.
- **¿Cómo digo esto?:** botón junto a Sugerencias; escribes la idea en español y la IA devuelve la frase con estructuras hasta tu nivel (`tu`) y, si difiere, la natural (`nat`). No se manda tu lista (ahorra tokens): la app marca después, en local, qué no está en tus listas. Lo mostrado pasa a “sugerido” de la página, así que usarlo cuenta como Hard en FSRS. Sin API, se comparte el prompt con un chat y se pega la respuesta.
- **Workbook:** foto de una página → la IA (proveedor elegible: Gemini, Azure o compartir con un chat) devuelve por ejercicio pistas con gramáticas, conectores y sustantivos (uso, ejemplo en coreano + inglés) y una respuesta modelo escondida. El vocabulario impreso entra a la base como “visto en el workbook”, sin contar como repaso. Luego, foto de lo escrito → estado por respuesta (bien / corregir / correcto pero más natural así); solo lo que escribiste cuenta para FSRS (lo visto en pistas o impreso cuenta como sugerido = Hard). Sesiones en archivos (`filesDir/workbook`), no en Room.
- **Mascota:** gotita de tinta vectorial (`mascot/Mascot.kt`), sin assets, con los colores del papel. Seis expresiones (idle, feliz, pensando, sorprendida, ánimo, dormida). En el inicio saluda en coreano básico según racha y hora (`MascotBrain`, sin IA); en la página piensa mientras la IA trabaja y reacciona al resultado. Se puede reemplazar por imágenes propias (PNG/ZIP) que quedan en el almacenamiento privado: nunca en el repo ni en el APK.
- **FSRS-6:** el uso en el diario es el repaso. Correcto sin sugerencia = Good; correcto tras verlo sugerido = Hard; incorrecto = Again; sugerido y no usado = sin calificación.
- **Progreso:** pantalla sin IA (`progress/Progress.kt`, con tests): racha actual y mejor racha, calendario de 14 semanas (páginas por día), memoria FSRS por tipo (practicadas, firmes con estabilidad ≥ 21 días, las que tocan hoy), tiempos verbales con un consejo si uno queda por debajo del 20 % y los 5 ítems con más fallos.
- **Recordatorio:** opcional, a la hora elegida (`reminder/Reminder.kt`). Alarma con ventana de 10 minutos que se reprograma cada día (sin permiso de alarma exacta); al sonar, solo notifica si hoy no hay página, con una frase de la mascota y la racha. Se reprograma al reiniciar el celular, al actualizar la app y al abrirla.
- **Copia de seguridad:** Auto Backup de Android (`res/xml/data_extraction_rules.xml` y `backup_rules.xml`) guarda la base, los workbooks, la mascota importada y los ajustes. Las claves de API no viajan: su clave de cifrado vive en el Keystore del celular, así que en un celular nuevo hay que volver a pegarlas.
- **Palabras fuera del estándar:** se agregan solas si son hangul válido; si no, quedan en “Por revisar”. Pendiente: validar con la API del 한국어기초사전.

## Distribución y actualizaciones

- GitHub Actions compila en cada push a `main`; el número de compilación es el `versionCode` (`0.1.N`).
- El release `latest` contiene `koreandiary.apk` y `version.json` (`versionCode`, `versionName`, `commit`, `notes`, `sha256`, `size`).
- `update/Updater.kt`: compara versiones, descarga, verifica SHA-256 e instala con `PackageInstaller`. Con `UPDATE_PACKAGES_WITHOUT_USER_ACTION` (Android 12+) la autoactualización no pide confirmación.

## Diferencias con el documento (MVP)

- Perfil y proveedores viven en SharedPreferences (claves cifradas con Android Keystore), no en tablas.
- Las reglas de conjugación se guardan como campo `irregular` de cada palabra; la tabla aparte queda para después.
- La integración con AnkiDroid queda para la siguiente iteración.
