# 일기 · Diario coreano con S Pen

App Android para practicar escritura coreana con un diario manuscrito. Escribes con el S Pen, ML Kit lee tu letra, una IA corrige y FSRS (el algoritmo de Anki) decide qué vocabulario y gramática sugerirte.

## Descargar el APK

Cada push a `main` compila un APK en GitHub Actions y lo publica en el release **latest**:

https://github.com/myussu/koreanDiary/releases/tag/latest

Ábrelo desde el celular, descarga `koreandiary.apk` e instálalo (Android pedirá permitir instalar desde el navegador). Todas las compilaciones se firman con el mismo keystore de depuración, así que cada versión se instala encima de la anterior sin borrar tus datos.

## Primeros pasos en la app

1. **Ajustes:** escribe tu perfil y nivel, y pulsa “Descargar modelo de escritura coreana” (unos 20 MB, solo una vez).
2. **Estudio → Importar:** carga tu TXT de gramática (formato `Unit N: patrón | patrón`) o usa el prompt de importación con un chat para vocabulario.
3. **Nueva página:** escribe con el S Pen. Arriba verás las sugerencias. Pulsa **Revisar**, confirma el texto y elige:
   - **Compartir:** manda la imagen y el prompt a Claude o ChatGPT; copia la respuesta y pégala en la app. Gratis.
   - **Gemini:** corrección automática con tu clave gratuita de Google AI Studio.
4. Reescribe las correcciones debajo de la zona sombreada y pulsa **Verificar**.

## Stack

Kotlin · Jetpack Compose · Room 3 · ML Kit Digital Ink · FSRS-6 · AGP 9.5 (Kotlin integrado) · Gradle 9.6.

La arquitectura completa está en [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
