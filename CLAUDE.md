# Guía para Claude en este repositorio

- App Android nativa en Kotlin + Jetpack Compose, con versión de escritorio en camino. Arquitectura y decisiones: `docs/ARCHITECTURE.md`. Respétalas.
- Módulos: `:app` (Android), `:shared` (Kotlin Multiplatform: lógica, IA, tinta, FSRS y la base de datos) y `:desktopApp` (Compose Multiplatform para Windows). Lo que no dependa de Android va en `shared/src/commonMain`, con el mismo paquete; nada de `android.*` ahí.
- Idioma: comentarios, textos de UI y commits en español.
- AGP 9 con Kotlin integrado: no aplicar `org.jetbrains.kotlin.android` ni `kapt`. Anotaciones con KSP. En `:shared`, el plugin Android es `com.android.kotlin.multiplatform.library`.
- Room 3 (`androidx.room3`): DAOs `suspend` o `Flow`; driver `BundledSQLiteDriver`; sin SupportSQLite.
- Cambiar una entidad de Room exige subir `VERSION` en `AppDatabase.kt`, agregar una `AutoMigration` y agregar al commit el JSON nuevo de `shared/schemas` (ver «Esquema de la base» en `docs/ARCHITECTURE.md`). Nunca `fallbackToDestructiveMigration`.
- Datos nuevos fuera de Room (una carpeta en `filesDir`, otro SharedPreferences) deben entrar en la copia: `BackupFormat.MANAGED_DIRS` y los XML de `res/xml` (ver «Copia de seguridad» en `docs/ARCHITECTURE.md`). La base solo se copia o reemplaza por `backup/`.
- JSON con `org.json` (incluido en Android; en escritorio es dependencia de `:shared`) para no sumar dependencias. HTTP con `HttpURLConnection`. Única excepción: OkHttp para el WebSocket de la conversación por voz (`voice/`).
- Las versiones viven en `gradle/libs.versions.toml`. Verifícalas en las páginas oficiales antes de cambiarlas.
- La compilación se verifica en GitHub Actions: `android.yml` publica el APK en el release `latest`; `escritorio.yml` publica la app de Windows en el release `escritorio`.
- Lógica pura (FSRS, parsers) debe tener tests en `shared/src/jvmTest` (corren en la JVM con org.json real). `app/src/test` queda para lo que depende de clases de `:app`.
- Commits sin líneas de atribución: nada de `Co-Authored-By` ni `Claude-Session`.
- Diseño: botones y superficies cuadrados y minimalistas (usar `AppButton`, `AppOutlinedButton`, `AppTextButton` de `ui/Components.kt`). Colores siempre desde `MaterialTheme.colorScheme` o `PaperStyle`, nunca fijos en las pantallas, para que funcione el modo oscuro. La raíz es un `Surface` que fija `contentColor`; no la reemplaces por un `Box`.
