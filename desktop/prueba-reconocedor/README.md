# Prueba del reconocedor de escritura (Windows)

Primer paso de la versión de escritorio: comprobar si el reconocedor de escritura de Windows
puede reemplazar a ML Kit (que solo existe en Android/iOS) antes de construir la app con Kotlin
Multiplatform.

## Qué responde

1. **¿Funciona desde una app de escritorio normal?** Algunas API de Windows solo funcionan en apps
   empaquetadas (MSIX). Si la lista de reconocedores aparece, funciona.
2. **¿Qué tan bien lee tu letra en coreano?** Comparado con ML Kit en el celular.
3. **¿Cuánto tarda por renglón?**
4. **¿Llega la presión de la tableta?**

## Antes de abrirlo

- **Escritura a mano en coreano:** Configuración → Hora e idioma → Idioma y región → Coreano →
  Opciones de idioma → Escritura a mano. Si Coreano no está, agrégalo con la casilla
  «Escritura a mano» marcada.
- **Tableta Wacom:** en sus propiedades, activa «Usar Windows Ink» (sin eso el navegador no recibe
  la presión).

## Uso

Descarga `prueba-reconocedor.exe` del release
[prueba-reconocedor](https://github.com/myussu/koreanDiary/releases/tag/prueba-reconocedor) y ábrelo.
Windows SmartScreen puede advertir que es un programa desconocido (no está firmado): «Más
información» → «Ejecutar de todas formas».

Se abre una ventana negra (el programa, con la lista de reconocedores instalados) y una página en
el navegador. Escribe una oración por renglón y pulsa **Reconocer** (`Ctrl+Enter`).

- **Modo:** «línea por línea» es lo que hace el celular; «página entera» manda todo de una vez.
- **Lo que escribí:** si lo llenas, mide el acierto sin contar espacios.
- **Guardar / Cargar muestra:** la tinta se guarda en el mismo formato que la app (`{"v":2,"s":[...]}`).

Todo es local: la página habla con `127.0.0.1` y nada sale a internet.

## Cómo está hecho

- `src/tinta.rs`: lee el formato de tinta de la app y agrupa por renglones con el mismo orden
  espacial que `InkOrdering` (con tests).
- `src/reconocedor.rs`: llama a `Windows.UI.Input.Inking` (`InkRecognizerContainer`) con el crate
  `windows`. Fuera de Windows compila con un reemplazo que solo avisa.
- `src/main.rs` + `src/pagina.html`: servidor local mínimo y página de captura.

GitHub Actions lo compila en Windows (`.github/workflows/prueba-reconocedor.yml`).
