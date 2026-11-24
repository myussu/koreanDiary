# Kiwi prueba

App aparte para medir [Kiwi](https://github.com/bab2min/Kiwi) (analizador morfológico coreano) en el
celular antes de decidir si entra en la app. En el servidor ya se midió que acierta más en oraciones
(ver `claude/kiwi-prueba.md` en el proyecto). Lo que falta es saber cuánto tarda en cargar y cuánta
memoria usa en el Galaxy.

- **Instalar:** el APK del release
  [`kiwi-prueba`](https://github.com/myussu/koreanDiary/releases/tag/kiwi-prueba). Se llama «Kiwi
  prueba» y tiene otro applicationId (`com.jorge.koreandiary.kiwiprueba`), así que se instala al lado
  de la app sin tocar el diario. Pide permiso para instalar apps de origen desconocido, como el APK de
  siempre.
- **Usar:**
  1. «Cargar Kiwi»: la primera vez copia el modelo (~97 MB) a la memoria del celular. Mide cuánto
     tarda en cargar y cuánta memoria suma.
  2. «Correr casos»: los 2.857 casos de la prueba del servidor. Muestra los aciertos del celular al
     lado de los del servidor (deberían ser iguales) y cuánto tarda cada oración.
  3. «Copiar resultados» y pegarlos en el chat.
  4. «Probar una oración» sirve para ver qué piezas y qué forma de diccionario saca de una oración
     cualquiera.
- **Qué lleva:** la librería de Kiwi para Android (del AAR solo la de ARM64, la del celular) y su modelo, los dos del
  release oficial, en la versión de `gradle/libs.versions.toml`, sin `multi.dict` (no cambia ningún
  resultado). También lleva las formas del diccionario de la app, para «Kiwi + diccionario». Nada de
  esto va en el repo: lo baja `preparar.sh`.
- **Compilar:** el workflow `kiwi-prueba.yml` (al cambiar algo en `kiwiPrueba/`). En local:
  `kiwiPrueba/preparar.sh` y luego `./gradlew :kiwiPrueba:assembleRelease`. El módulo solo existe en
  Gradle si la librería ya está bajada, así que la compilación de la app no lo necesita.
- **Se borra** (esta carpeta, el workflow, el release y la línea de `settings.gradle.kts`) cuando se
  decida.
