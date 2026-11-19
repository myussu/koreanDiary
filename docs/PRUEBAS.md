# Pruebas con el emulador (para Claude Code)

Esta guía es para probar la app en un emulador de Android desde la PC, con Claude Code manejando `adb`.
También sirve en el celular, pero **siempre con la versión de prueba**: nunca instales la de depuración
encima de tu diario de verdad.

## La versión de prueba

La compilación de depuración es la versión de prueba:

| | Tu app | Versión de prueba |
|---|---|---|
| Nombre | 일기 Diario | **Diario prueba** |
| applicationId | `com.jorge.koreandiary` | `com.jorge.koreandiary.prueba` |
| Datos | tus diarios | aparte, empieza vacía |
| IA | Gemini o Azure reales | **respuestas fijas** (se puede apagar) |
| Actualización | desde GitHub | no se actualiza sola: se compila de nuevo |

Las dos se instalan una al lado de la otra. La versión de prueba usa la misma llave de firma, pero como
es otra app, no puede tocar los datos de la tuya.

```bash
# Compilar e instalar (emulador encendido o celular conectado)
./gradlew :app:installDebug

# Abrirla
adb shell am start -n com.jorge.koreandiary.prueba/com.jorge.koreandiary.MainActivity

# Empezar de cero (borra solo los datos de la versión de prueba)
adb shell pm clear com.jorge.koreandiary.prueba
```

No uses `installRelease` ni un APK de GitHub para probar: esas versiones son tu app de verdad.

### Respuestas fijas

En la versión de prueba, Gemini y Azure no usan la red ni piden clave: contestan siempre lo mismo
(`shared/.../ai/FixedResponses.kt`, con tests en `FixedResponsesTest`). El proveedor por defecto arranca
en Gemini. El interruptor está en **Ajustes › IA › Versión de prueba › Respuestas fijas**.

Con respuestas fijas funcionan:

- Revisar: corrección, más natural, escena viva y tono en escenas.
- «¿Cómo digo?».
- «Actualizar».
- Las explicaciones de las sugerencias.
- El repaso rápido.
- Las fotos del workbook (pistas y revisión).

Necesitan la IA real (Gemini con clave): **Hablar y escuchar** (voz en vivo y pódcast) y los dibujos de
Claude.

Errores fijos para probar Revisar y Verificar:

- «만나요» en una oración con «어제» → «만났어요» (conjugación).
- «친구을» → «친구를» (partícula).
- «학교에서 가요» → «학교에 가요» (partícula).
- Si hay dos oraciones o más, la última sin error vuelve como «más natural» («정말 …»).

### Ayudas que solo existen en la versión de prueba

`adb shell input text` no escribe coreano. Para no depender de un teclado coreano:

- **«Usar texto de prueba»** en «¿Esto es lo que escribiste?» de Revisar: pone `어제 친구을 만나요. 공원에 갔어요.`, que trae dos errores fijos y una oración «más natural».
- **«Prueba: bien»** y **«Prueba: mal»** en el repaso rápido: responden la tarjeta sin escribir.
- **«Forzar un cierre»** en Ajustes › IA: cierra la app para probar el aviso del inicio.

Si hace falta escribir coreano de verdad, instala [ADB Keyboard](https://github.com/senzhk/ADBKeyBoard),
actívalo como teclado y usa:

```bash
adb shell am broadcast -a ADB_INPUT_TEXT --es msg '어제 친구를 만났어요.'
```

## Leer la pantalla y tocar

Los `testTag` salen como `resource-id` (la raíz tiene `testTagsAsResourceId`, y cada diálogo con etiquetas también, porque un diálogo es otra ventana). Los botones también se
encuentran por su texto, que es el mismo que dice «Qué puede hacer la app».

```bash
adb shell uiautomator dump /sdcard/pantalla.xml && adb pull /sdcard/pantalla.xml
adb exec-out screencap -p > pantalla.png
adb shell input tap X Y
```

| resource-id | Qué es |
|---|---|
| `inicio_nueva_pagina`, `inicio_estudio`, `inicio_ajustes`, `inicio_escenas`, `inicio_workbook`, `inicio_hablar` | botones del inicio |
| `version_prueba` | aviso «Versión de prueba» del inicio |
| `aviso_cierre` | aviso de cierre por error |
| `hoja` | el lienzo de la página |
| `confirmar_texto`, `texto_de_prueba` | el texto a confirmar en Revisar y su botón de prueba |
| `repaso_recuadro`, `repaso_prueba_bien`, `repaso_prueba_mal`, `repaso_copia_comprobar`, `repaso_copia_bien`, `repaso_copia_mal` | el repaso rápido |

### Escribir en la hoja

Por defecto **«Solo el S Pen dibuja»** está encendido: el dedo (o `input tap/swipe` normal) no escribe.

- Usa la fuente stylus: `adb shell input stylus swipe X1 Y1 X2 Y2 300`.
- Para trazos con curvas: `adb shell input stylus motionevent DOWN X Y`, varios `MOVE X Y` y `UP X Y`.
- O apaga «Solo el S Pen dibuja» en Ajustes › Escritura.

Unos garabatos bastan para probar el flujo: el texto se confirma (o se cambia con «Usar texto de prueba»)
antes de enviarlo.

El reconocedor de escritura (ML Kit) descarga su modelo la primera vez (unos 20 MB), así que el emulador
necesita red. Conviene una imagen de sistema con Google Play o Google APIs.

### Cierres

```bash
adb logcat -d -b crash
adb logcat -d -s AndroidRuntime:E
```

Si la app se cierra, al abrirla otra vez el inicio muestra «La app se cerró por un error» con
«Copiar detalle». El detalle queda en `noBackupFilesDir/cierre.txt`, fuera de las copias.

## Qué probar

Marca cada punto y anota lo que falle: pantalla, pasos, qué esperabas, qué pasó y captura. En un cierre,
agrega el detalle del aviso o el logcat.

### Inicio y navegación

- [ ] El inicio muestra «Versión de prueba…» con «La IA contesta con respuestas fijas».
- [ ] «?» abre la lista con buscador; «Cómo se usa» abre los recorridos; «Ver cómo se usa ›» salta al de cada grupo.
- [ ] Atrás (gesto o botón) vuelve de cada pantalla sin cerrar la app.
- [ ] Girar el celular en el inicio, en una página y en Estudio no pierde lo que estaba en pantalla. En horizontal, el inicio se desplaza entero hasta «Nueva página», los tres botones y la lista.
- [ ] Detalles que se veían mal: «SUGERENCIAS» en un renglón; los tipos de corrección con tilde («conjugación», «partícula»); plurales («1 palabra nueva», «1 usada bien»); «-다가» sin número en las sugerencias; la pista del estándar entre comillas («달»), sin «ej.:»; la frase de «¿Cómo digo?» y las correcciones se desvanecen abajo si hay más; «Ver cómo se usa ›» cae justo en el recorrido; la versión de prueba no muestra «Reintentar» al buscar actualización.
- [ ] Modo oscuro (Ajustes › General): nada queda ilegible (texto negro sobre negro, botones invisibles).

### Diario

- [ ] Modelo de escritura sin Wi-Fi: `pm clear`, Wi-Fi apagado y solo datos móviles (`adb shell svc wifi disable`). «Revisar» baja el modelo con datos; si no hay red, en 90 s como mucho dice en español «Sin conexión…» o «tarda demasiado». Lo mismo con «Actualizar» y con «Comprobar» del repaso rápido. Ningún aviso en inglés, y el aviso no tapa la hoja (máximo tres renglones, con ✕).
- [ ] «Nueva página» trae sugerencias; tocar una abre su significado (con respuestas fijas, «significado de prueba»).
- [ ] Escribir en la hoja (stylus), Borrar, «+ Línea», Deshacer y Rehacer.
- [ ] «¿Cómo digo?»: escribir una idea, ver la ayuda por partes (palabras → «Más ayuda» → «Ver la frase»).
- [ ] «Actualizar» suma sugerencias según lo escrito.
- [ ] «Revisar» → «Usar texto de prueba» → revisar con Gemini:
  - salen dos correcciones en la oración 1 y «~» más natural en la 2;
  - arriba, la línea de niveles (bien / corregir / más natural).
- [ ] «Descartar» en la más natural la quita.
- [ ] Reescribir debajo y «Verificar»: marca como reescrita la que coincide; «Hecho» marca a mano.
- [ ] La página del diario cuenta para la racha en cuanto tiene algo escrito, aunque tenga correcciones pendientes.
- [ ] «Descartar» una más natural y «Hecho» en una corrección cambian el aviso de abajo por lo que queda («Queda 1 corrección por reescribir», «Todas las correcciones reescritas ✓»).
- [ ] Con «+ Línea» activo, el aviso sale encima de la barra y no tapa el primer renglón.
- [ ] La barra de la hoja: si no caben, las herramientas se desvanecen en el borde y se deslizan; «Rehacer» se alcanza.

### Escenas

- [ ] «Nueva escena» trae dos gramáticas ★ aparte de las sugerencias.
- [ ] Escribir 바다, 봄, 반달 o 새벽 (con texto confirmado o ADB Keyboard) hace aparecer su dibujo en la franja de abajo.
- [ ] 눈을 감았어요, 해 주세요 y 한 달 no traen nieve, sol ni luna.
- [ ] Una escena con correcciones pendientes no cuenta para la racha; al reescribirlas (o «Hecho» en todas), sí.
- [ ] Revisar una escena trae la escena viva y el tono (luz de color desde abajo).
- [ ] «Dibujos»: la lista por zona, «Prueba una palabra» con 봄바람 (viento) y 반달곰 (oso).
- [ ] Sin tocar la hoja por 30 s, la escena se queda quieta; al tocar, sigue.

### Estudio

- [ ] «Importar» con un TXT de gramática (`Unit 1: V-고 싶다 | A/V-(으)니까`): entra a «Gramática».
- [ ] Pestañas Resumen, Vocabulario, Gramática, Por revisar y Para agregar.
- [ ] Tocar un ítem de la lista abre su memoria, tus oraciones y lo que te corrigieron.
- [ ] «Te corrigieron»: con gramática importada, una página nueva cuyas sugerencias traigan una gramática tuya, «Revisar» con «Usar texto de prueba». En Estudio › Gramática, esa gramática muestra «만나요 → 만났어요» en «Te corrigieron».
- [ ] «Tu nivel»: filas por nivel. Si no coincide con Ajustes, aparece el aviso y el botón a Ajustes. No cambia el nivel solo.
- [ ] «Prueba de vocabulario»: tocar algunas palabras, «Siguiente nivel» hasta «Listo» (o «Terminar aquí»). «Tu nivel» muestra el resultado.
- [ ] «Para agregar»:
  - En Gramática, «Agregar» la saca de la lista y aparece en «Gramática» como «del estándar».
  - En Vocabulario, salen las palabras que no conociste en la prueba.
  - «Ya lo sé» la agrega con repaso lejano.
- [ ] «Repaso rápido»:
  - Hay tarjetas con traducción y lo que falta resaltado.
  - «Pista» abre hasta tres.
  - «Prueba: bien» cierra la tarjeta.
  - «Prueba: mal», luego «La mía también vale» y «Otra vez».
  - «Ver respuesta», «Siguiente» y al final el resumen con «Otra ronda».
- [ ] Tras «Ver respuesta» el recuadro (`repaso_recuadro`) sigue en el mismo lugar, vacío, con «Si tienes tiempo, cópiala aquí». «Prueba: bien» (`repaso_copia_bien`) dice «Bien escrita.» con «LEÍ»; «Prueba: mal» (`repaso_copia_mal`), «No coincide…». El resumen la sigue contando en «Vuelven pronto». «Siguiente» funciona sin copiarla.
- [ ] Con stylus: tras «Ver respuesta», escribir la respuesta en el recuadro y «Comprobar» (`repaso_copia_comprobar`); «Borrar» limpia el recuadro y el resultado.
- [ ] Al cerrar una tarjeta (acierto o «Ver respuesta»), «Más sobre esto» abre la explicación: significado, «Partes:», uso y ejemplos (con respuestas fijas, «Partes fijas de la versión de prueba…»). En la tarjeta siguiente ya no está abierta.
- [ ] Repaso rápido escribiendo a mano en el recuadro (stylus) y «Comprobar». Lo leído sale en grande bajo «LEÍ» (`repaso_leido`), acierte o no.
- [ ] «Prueba: mal» → «La mía también vale»: dice «Bien.» si no abriste más de una pista, y el resumen la cuenta igual.
- [ ] Repasar todo y «Otra ronda» hasta que no quede nada: dice «Ya repasaste todo lo que tocaba hoy», no «importa tu lista».
- [ ] En Gramática, abrir un ítem y girar el celular: sigue abierto, y lo escrito en el buscador sigue.

### Workbook

- [ ] Foto desde la galería: sube una imagen al emulador y avisa al sistema que existe; luego elígela.
  ```bash
  adb push foto.jpg /sdcard/Pictures/
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/foto.jpg
  ```
- [ ] Con respuestas fijas: dos ejercicios con pistas, vocabulario y gramática (★ la de la lección); elegir cuáles entran a tu lista.
- [ ] Foto de las respuestas: el primero «corregir», el segundo «más natural», el resto «bien».

### Ajustes

- [ ] Cada grupo abre solo lo suyo: General, Escritura, IA, Escenas, Hablar y escuchar, y Copia y actualizaciones.
- [ ] «Buscar actualización» en la versión de prueba dice que no se actualiza sola, sin descargar nada.
- [ ] «Forzar un cierre» → al abrir, el aviso con «Copiar detalle» (pegar en algún campo para ver el texto) y «Descartar».

### Copia de seguridad (nunca probado de punta a punta)

- [ ] «Exportar copia» a un archivo, borrar con `pm clear`, «Importar copia» de ese archivo: vuelven páginas, tinta, listas, dibujos y ajustes.
- [ ] Después de importar, «Deshacer la última importación» vuelve a los datos de antes, y lo que había al deshacer queda en «Copia antes de actualizar» como «Dentro de la app: deshacer-…», con «Restaurar».
- [ ] Las fotos del workbook siguen después de importar.
- [ ] «Copia antes de actualizar» › «Elegir carpeta» (Ajustes › Copia y actualizaciones): la carpeta queda elegida, y «Cambiar carpeta» y «Quitar carpeta» funcionan.
- [ ] La copia en esa carpeta se crea al instalar una actualización: solo se puede probar en tu app de verdad, con una actualización real. Revisa que el archivo aparezca.

### Solo en el celular de verdad

Esto no se puede probar en el emulador:

- [ ] «Solo el S Pen dibuja» con la palma apoyada: la palma no deja rayas.
- [ ] Batería: una hora de escena viva y de escritura; mirar el consumo en Ajustes de Android.
- [ ] Hablar y escuchar con Gemini real, con la pantalla apagada y con audífonos Bluetooth.
- [ ] Dibujos de Claude al revisar una escena (con clave de Claude).

## Notas para Claude Code

- Lee `CLAUDE.md` y `docs/ARCHITECTURE.md` antes de tocar código.
- Para probar, instala siempre `:app:installDebug` (la versión de prueba). Para cambiar el código, sigue las reglas de `CLAUDE.md`: rama, PR y las dos compilaciones de GitHub Actions en verde.
- Si algo falla, anota los pasos exactos, la captura y el logcat, y propón el arreglo en una rama aparte.
