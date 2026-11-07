# Guía para Claude en este repositorio

- App Android nativa en Kotlin + Jetpack Compose. Arquitectura y decisiones: `docs/ARCHITECTURE.md`. Respétalas.
- Idioma: comentarios, textos de UI y commits en español.
- AGP 9 con Kotlin integrado: no aplicar `org.jetbrains.kotlin.android` ni `kapt`. Anotaciones con KSP.
- Room 3 (`androidx.room3`): DAOs `suspend` o `Flow`; driver `BundledSQLiteDriver`; sin SupportSQLite.
- JSON con `org.json` (incluido en Android) para no sumar dependencias. HTTP con `HttpURLConnection`. Única excepción: OkHttp para el WebSocket de la conversación por voz (`voice/`).
- Las versiones viven en `gradle/libs.versions.toml`. Verifícalas en las páginas oficiales antes de cambiarlas.
- La compilación se verifica en GitHub Actions (`.github/workflows/android.yml`), que publica el APK en el release `latest`.
- Lógica pura (FSRS, parsers) debe tener tests en `app/src/test`.
- Commits sin líneas de atribución: nada de `Co-Authored-By` ni `Claude-Session`.
- Diseño: botones y superficies cuadrados y minimalistas (usar `AppButton`, `AppOutlinedButton`, `AppTextButton` de `ui/Components.kt`). Colores siempre desde `MaterialTheme.colorScheme` o `PaperStyle`, nunca fijos en las pantallas, para que funcione el modo oscuro. La raíz es un `Surface` que fija `contentColor`; no la reemplaces por un `Box`.
