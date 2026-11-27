# Revisión de las animaciones de abrir y cerrar apps

Fecha: 26/11/2026. Versión revisada: la de `main` (último commit `c0eef53`, 3.3.0).
Solo se ha leído código: no se ha compilado ni se ha probado en el móvil. No se ha tocado ningún archivo de código.

**Leyenda** (aparece en cada punto):

- **[V]** Verificado en el código: lo dice el código tal cual, con su archivo y su línea.
- **[O]** Opinión o criterio de diseño, mía. Es razonable, pero se puede discutir.
- **[P]** Por probar en el móvil: es muy probable, pero solo una grabación o un registro lo confirma.

Los números de línea son los de `main` a día de hoy.

---

## Resumen: las 10 mejoras más rentables

Ordenadas por lo que ganas frente a lo que cuesta hacerlas.

| # | Mejora | Ganancia | Esfuerzo |
|---|--------|----------|----------|
| 1 | **Abanico, Pergamino y Carta saltan de lado al arrastrar.** Hay que fijar el lado (`nearSide`) al empezar la sesión, no en cada fotograma (`Genie.java:1884`, `1901`, `1930`, `1956`). | Alta | 10 min |
| 2 | **La estela (desenfoque de movimiento) usa la velocidad del rectángulo de MIUI y no la de la forma que se dibuja.** En el Pergamino emborrona el papel que ya está quieto (`Genie.java:1566`, `1991`). Hay que medir la velocidad de la forma dibujada, quitar la estela en Pergamino y Carta, y bajar el valor de serie de 1 a 0,3. | Alta | 1 h |
| 3 | **La Carta da dos vueltas completas.** A 60 Hz gira más de 100° por fotograma y se ve como una rueda de carro en una película (efecto estroboscópico). Además, al salir enseña el icono estirado a toda la carta. La propuesta: media vuelta y el icono cuadrado (`Genie.java:212`, `GlRenderer.java:296`). | Alta | 30 min |
| 4 | **Constantes que dependen del refresco:** `JANK_NS` (25 ms), `READY_NS` (10 ms) y `OVERLAP_NS` (20 ms). Deben ser múltiplos del periodo real de la pantalla. A 120 Hz los tirones no se cuentan, así que la resolución automática nunca se activa; a 60 Hz puede verse un fotograma vacío al empezar (`Genie.java:110`, `127`, `138`). | Alta | 1 h |
| 5 | **Al final de la apertura el contenido "salta":** la captura es de media resolución y puede tener 200-300 ms. Hay que devolver la ventana real antes, en cuanto la forma ya es un rectángulo (`Genie.java:145`, `152`). | Alta | 2-3 h |
| 6 | **El tiempo de cada muestra de MIUI es el momento en que llega y no el del fotograma.** Eso da micro-tirones en la predicción. Hay que usar la hora del fotograma de MIUI y predecir para el momento en que se verá, no para el refresco que ya pasó (`Genie.java:1311`, `1629`). | Media-alta | 2 h |
| 7 | **El icono se estira a toda la forma** en genio, copa y carta. Debe quedarse cuadrado y centrado, y la textura debe subir de 128 a 256 px (`GlRenderer.java:323`, `354`; `Genie.java:75`). | Media | 1 h |
| 8 | **La escala de animación del sistema se ignora:** los tiempos propios (fundidos, mezclas, el genio suelto) no la tienen en cuenta. Tu móvil está a 1,5, así que lo que ajustas "a ojo" no es lo que ve quien lo tiene a 1 (`Genie.java:58`-`123`, `157`). | Media | 1 h |
| 9 | **Rehacer el Pergamino** como un rollo de verdad (ver sección 5): con su propio reloj de muelles, el dorso del papel, sombras y la mano en directo al cerrar. | Muy alta | 2-4 días |
| 10 | **Un solo modelo de muelle** y un solo sitio para los valores (`MotionSpec`). Ahora hay 6 muelles distintos. El Rebote debe tocar solo la posición y el tamaño, no el radio ni la transparencia (`Springs.java:63`). El muelle lento del Pergamino tampoco debe frenar el cierre (`Springs.java:56`). | Media | 1 día |

**Lo más urgente** son el 1, el 2 y el 3: son defectos que se ven, y el arreglo es de pocas líneas. **Lo que más acerca a iOS** son el 5, el 6 y el 10.

---

## 0. La tubería que comparten todos los estilos

Todos los estilos salvo HyperOS (el 8) pasan por la misma tubería. Así se entienden mejor las secciones de cada estilo.

### 0.1 Quién hace qué y en qué hilo [V]

1. **Hilo de animación de MIUI.** El launcher calcula su muelle (`RectFSpringAnim`) y llama a `LocalWindowAnimImplementor.updateElementValue` en cada fotograma. HyperSpring se engancha ahí (`WindowAnim.java:106`) y `onFrame` (`WindowAnim.java:630`) hace esto:
   - Decide si toca genio: solo `OPEN_FROM_HOME`, `CLOSE_TO_HOME` y `CLOSE_TO_DRAG`, en vertical, con una sola ventana.
   - Rellena un `Genie.Frame` con el rectángulo actual `cur`, el del icono, la pantalla `full` y el radio de las esquinas que da MIUI (`WindowAnim.java:730`).
   - Llama a `Genie.frame` (`Genie.java:458`), que pasa el fotograma a la sesión (`Session.post`, `Genie.java:1237`).
2. **`Session.post`** guarda la muestra bajo `lock`. También calcula la velocidad de cada borde (`edgeVel`, suavizada al 50 %), la física de lámpara, el paso de icono a dedo y el sentido (abrir o cerrar). Después pide un dibujo (`requestRender`) y una captura si le toca (`captureDue`).
3. **Hilo `IceHome-genie`** (prioridad −16). Tiene un bucle con `Choreographer` (`pacer`, `Genie.java:1123`):
   - Dibuja como mucho a `maxFps`, con el periodo calculado por `periodNs()` (`Genie.java:305`).
   - Si MIUI tarda en mandar fotogramas, rellena los huecos: predice el rectángulo con la velocidad de cada borde, como mucho 2 refrescos por delante (`predict`, `Genie.java:1629`).
   - Calcula la forma (`shape`, `Genie.java:1986`) y dibuja con OpenGL en una capa propia (`GlRenderer.drawGenie`).
4. **Hilo `IceHome-capture`.** Hace `captureLayers` de la ventana de la app (`captureOnce`, `Genie.java:1408`) a media resolución como mucho (`CAPTURE_MAX_SCALE = 0.5`, `Genie.java:152`). La captura entra sin copias en una `SurfaceTexture`.
5. **El aplicador de MIUI** (`SurfaceParams.applyTo`, `WindowAnim.java:123`) llama a `Genie.onApply` (`Genie.java:859`). En la misma transacción que mueve la ventana, la oculta (alfa 0) y pone nuestra capa encima (`setRelativeLayer`, Z = 2). Así el cambio es atómico, sin parpadeos.

### 0.2 Cronología común de la apertura [V]

| Momento | Qué pasa | Dónde |
|---|---|---|
| Dedo abajo en el icono | El icono sube a la GPU (128×128 px) y se precarga la última imagen de la app si sigue en recientes | `WindowAnim.java:579`, `Genie.java:759` |
| Primer fotograma de MIUI | Se crea la `Session`. Hasta que hay dibujo propio, solo se ve el icono flotante de MIUI | `Genie.java:514`, `onApply` `887` |
| Primer dibujo + 10 ms | `ready()` deja mostrar la capa. La ventana real pasa a alfa 0 | `Genie.java:1655`, `READY_NS` `138` |
| +20 ms | El icono flotante de MIUI desaparece (`OVERLAP_NS`) | `Genie.java:689` |
| Anchura del 2 % al 12 % | Fundido del icono a la app (`OPEN_MIX_END`/`START`) | `Genie.java:72`, `iconMix` `2086` |
| Primera captura | Fundido de 140 ms de la pantalla provisional a la captura (`CONTENT_FADE_S`) | `Genie.java:121`, `1568` |
| Color de la app | Fundido de 100 ms del color provisional (negro o blanco) al de MIUI | `Genie.java:123`, `1189` |
| Con "en vivo" | Una captura cada 100 ms hasta tener 3, y luego una cada 200 ms | `Genie.java:145`, `246`, `1461` |
| Fin del muelle de MIUI | `animEnded` y, 40 ms después (`GRACE_MS`), `stop` y luego `end()`: se oculta la capa y la ventana vuelve con su alfa en la misma transacción | `Genie.java:539`, `1710` |

**Los tiempos del muelle principal no son de HyperSpring:** el tamaño y la posición los decide el muelle de MIUI. HyperSpring solo cambia su periodo (`Velocidad`) y su amortiguación (`Rebote`) en `Springs.java:54`. Todos los estilos se calculan a partir de `pw`, la "anchura relativa" del rectángulo de MIUI (0 = icono, 1 = pantalla; `progress`, `Genie.java:2436`).

### 0.3 Cronología común del cierre [V]

- **Con el dedo (`CLOSE_TO_DRAG`):** el extremo cercano es un "icono virtual" bajo el dedo, del 20 % del ancho (`FINGER_ANCHOR`, `Genie.java:81`). La lámpara añade balanceo con un muelle K = 170 y ζ = 0,5 (`Genie.java:99`).
- **Al soltar:** el extremo cercano pasa del dedo al icono en 120 ms (`ICON_BLEND_S`, smoothstep, `Genie.java:79`), y la lámpara se apaga en 350 ms.
- **Capturas:** al cerrar se usa la captura tomada al empezar el gesto (`precapture`, `Genie.java:721`). Si no la hay, se toma una al 35 % y luego una buena (`FIRST_CLOSE_SCALE`, `Genie.java:136`).
- **Icono:** el paso de la app al icono va del 30 % al 13 % de anchura (`CLOSE_MIX`, `Genie.java:73`). El icono flotante de MIUI se oculta en cuanto se ve el genio (`Genie.java:686`).
- **Llegada:** vibración al bajar del 3 % (`LAND_PW`, `Genie.java:125`). Al acabar el muelle, `end()` oculta la capa y MIUI enseña el icono real.
- **Si se abre otra app mientras una se cierra:** la que se cierra sigue sola hasta su icono (`Ghost`, muelle K = 300 y ζ = 0,92, `Genie.java:157`), o como un rectángulo de color en el modo ligero (`RectGhost`, `Genie.java:2279`).

### 0.4 Interrupciones [V]

- **Tocar el icono mientras se cierra:** la app se reabre desde donde esté (`Genie.java:479` y `hookReopenTap` en `WindowAnim.java:358`). Está bien resuelto.
- **Cambio de sentido:** la forma de abrir y la de cerrar se funden en 150 ms (`DIR_BLEND_S`, `Genie.java:77`). Esa mezcla va por tiempo, no por velocidad: la posición la sigue llevando el muelle de MIUI, que sí conserva su velocidad.
- **Otra ventana o paso a recientes:** la sesión se para. Al pasar a recientes, la capa se funde en 160 ms (`fadeOut`, `Genie.java:559`).

### 0.5 Defectos comunes a todos los estilos

**C1. Las constantes de "un refresco" están fijas en milisegundos.** [V]
- Dónde: `READY_NS = 10 ms` (`Genie.java:138`), `OVERLAP_NS = 20 ms` (`110`), `JANK_NS = 25 ms` (`127`), y el `READY_NS` del telón.
- Escenario a 60 Hz [P]: un refresco dura 16,7 ms, pero `ready()` da por buena la capa a los 10 ms. Si el compositor aún no ha cogido nuestro primer búfer, la ventana ya está a alfa 0 y en ese fotograma no se ve ni la app ni el genio: un destello del escritorio al empezar.
- Escenario a 120 Hz [V]: un fotograma perdido son 8,3 ms y dos son 16,7 ms. Ninguno pasa de 25 ms, así que no cuenta como tirón. `autoQuality` (`Genie.java:274`) y la "animación paralela ligera" automática (`slow`) no se enteran nunca de los tirones en pantallas rápidas. Justo en las pantallas del aviso de Reddit.
- Arreglo mínimo:
  ```java
  long vsync = (long) (1e9 / pantallaHz);           // de Display.getRefreshRate()
  READY_NS   = vsync + vsync / 4;                   // 1,25 refrescos
  OVERLAP_NS = 2 * vsync;
  JANK_NS    = (long) (1.5 * periodNs());           // tirón = más de 1,5 periodos de dibujo
  ```
  Lo ideal para `ready()` es saber cuándo se ha presentado el búfer de verdad, con `SurfaceControl.Transaction.addTransactionCompletedListener` (API 31+, oculta en algunas versiones). Con eso no hace falta adivinar.

**C2. "Auto hasta 90" da 60 fps en una pantalla de 120 Hz.** [V]
- Dónde: `periodNs() = 1e9/90 − 2,5 ms = 8,6 ms` (`Genie.java:305`), y los refrescos llegan cada 8,33 ms.
- Qué pasa: un refresco se salta y el siguiente se dibuja, así que salen 60 fps exactos y regulares. No es malo (es lo que necesita una GPU modesta), pero el texto de la app dice "hasta 90". A 144 Hz salen 72 fps.
- Arreglo: no hace falta cambiar el comportamiento, solo el texto: "Auto: 60 en pantallas de 120 Hz, 90 en las de 90 Hz". Cuando la pantalla es múltiplo, elige un divisor exacto (120 → 60, 90 → 90, 144 → 72) a propósito, no por casualidad.

**C3. Hora de las muestras de MIUI y latencia de presentación.** [V] + [P]
- Dónde: `sampleNs = System.nanoTime()` en el momento en que llega el hook (`Genie.java:1311`). MIUI calcula su muelle para la hora de su fotograma, pero el hook llega entre 5 y 20 ms después, según lo cargado que vaya el hilo.
- Qué pasa: la velocidad (`edgeVel`) y la predicción (`predict`) mezclan posiciones de una hora con horas de llegada distintas. El resultado es un temblor de 1-3 px en la posición dibujada, que se nota como "no va fino" aunque no haya tirones.
- Segundo problema: `render(frameNs)` predice para `frameNs`, el refresco que acaba de empezar. Pero la imagen se verá en el siguiente refresco o en el otro, porque no se espera al refresco (`eglSwapInterval(0)`, `GlRenderer.java:533`). Todo va con un fotograma de retraso respecto al dedo.
- Arreglo mínimo:
  1. En el hook, lee la hora del fotograma de MIUI. Si su hilo tiene `Choreographer`, es `Choreographer.getInstance().getFrameTimeNanos()`. Si no, `AnimationUtils.currentAnimationTimeMillis()`. Guárdala como `sampleNs`.
  2. En `render`, predice para `frameNs + periodo`.
  3. Si puedes, marca el búfer con `EGLExt.eglPresentationTimeANDROID(display, window, frameNs + periodo)`. Así SurfaceFlinger lo enseña en el refresco justo.

**C4. La escala de animación del sistema se ignora.** [V] (que MIUI la respete es [P])
- Dónde: en `Genie.java` nunca se llama a `ValueAnimator.getDurationScale()`. Los tiempos propios van con `System.nanoTime()` tal cual: `OPEN_FADE_S` 60 ms, `DIR_BLEND_S` 150 ms, `ICON_BLEND_S` 120 ms, `CONTENT_FADE_S` 140 ms, `BG_BLEND_S` 100 ms, `LAMP_FADE_S` 350 ms, `FADE_MS` 160 ms y el muelle del genio suelto. Solo `GlassBar` e `Icons` la usan.
- Qué pasa: si el muelle de MIUI sí respeta la escala, en tu móvil (a 1,5) la ventana va 1,5 veces más lenta, pero los fundidos no. Las proporciones que ves no son las que ve alguien a 1,0, ni las de alguien a 0,5 (muy común entre "tuneadores"). A 0,5 los fundidos se comen más de la mitad del viaje.
- Cómo comprobarlo: compara la línea `curva 50/90/99% a X/Y/Z ms` del registro con la escala a 1,0 y a 1,5. Si cambia ×1,5, MIUI la respeta.
- Arreglo: un único `MotionClock.dt()` que multiplique por `1 / getDurationScale()` (y que, si la escala es 0, salte al final), y usarlo en todos los tiempos propios.

**C5. El final de la apertura enseña una captura vieja y borrosa.** [V] + [O]
- Dónde: las capturas van a media resolución como mucho (`CAPTURE_MAX_SCALE = 0.5`, `Genie.java:152`). En vivo hay una cada 200 ms; sin "en vivo", solo las 3 primeras. La capa se dibuja al 75 % y el compositor la escala.
- Qué pasa: en el último fotograma del genio se ve una imagen de hace hasta 200-300 ms y a media resolución. Luego entra la ventana real, nítida y al día. Se ve un "enfoque" de golpe y, a veces, saltan textos o imágenes que la app pintó mientras tanto. En iOS la app está en vivo en todo momento.
- Arreglo [O]: el genio y la copa terminan siendo el rectángulo de MIUI (con `pw → 1` la deformación tiende a 0). En cuanto la forma dibujada está a menos de 0,5 px del rectángulo y la estela está apagada, devuelve la ventana real (en vivo, con el radio de MIUI) aunque el muelle siga moviéndose. Para la copa y el genio suele pasar con `pw ≈ 0,85-0,9`, unos 100-150 ms antes del final. Los estilos 3D deben llegar planos antes (ver cada uno).
  ```java
  // en render(), después de shape():
  if (startedOpen && deformacionPx(geoNow, cur) < 0.5f && geoNow.vel == 0 && fromWindow)
      handBackEarly();   // misma lógica que end(), pero sin esperar a animEnded
  ```

**C6. El icono se estira a toda la forma.** [V]
- Dónde: en los estilos con `cup < 2.5` (genio, copa, profundidad) y en la Carta, `iuv = (u, s)` (`GlRenderer.java:323` y `296`). Con `iconMix > 0` el icono se pinta sobre toda la forma (`354`).
- Qué pasa: al abrir, con una anchura del 12 % la forma mide unos 216 × 306 px en 720p y el icono se ve estirado en vertical un 40 % justo cuando aún está medio visible. Al cerrar pasa lo mismo, del 30 % al 13 %. Además, la textura es de 128 px (`ICON_TEXTURE`, `Genie.java:75`) para un icono de unos 154 px en pantalla, dibujada al 75 % y escalada por el compositor. Sale más borrosa que el icono real, y al final del cierre, cuando aparece el real, se nota que se "enfoca".
- Arreglo: hacer lo mismo que ya hace `splashAt` (icono cuadrado y centrado). Su lado debe ser el ancho de la forma, como mucho el alto. Y la textura de 256 px (sube 256 KB de memoria de GPU; da igual).
  ```glsl
  float side = min(wE, h);
  vec2 iuvSq = (p - centroForma) / side + 0.5;   // cuadrado, centrado
  float inSq = step(0.,iuvSq.x)*step(iuvSq.x,1.)*step(0.,iuvSq.y)*step(iuvSq.y,1.);
  ```

**C7. La estela usa la velocidad equivocada.** [V]
- Dónde: `drawVelocity(cur)` mide la velocidad del rectángulo de MIUI (`Genie.java:1566`), y `motion()` la aplica a la forma dibujada (`1991`). En el genio, la punta lejana va mucho más deprisa que el borde de MIUI (por eso existe el "lead"), y en Pergamino y Carta la forma no se parece nada al rectángulo.
- Qué pasa: la estela no coincide con lo que se mueve. Sobra donde la forma está quieta y falta donde corre. En el Pergamino el papel ya extendido no se mueve nada, y aun así se emborrona.
- Arreglo: medir la velocidad de los bordes de `out.cur` después de `shape()` (o, mejor, de los 4 puntos extremos de la forma). Para Pergamino y Carta, `vel = 0`.
- Opinión [O]: iOS no tiene estela. A 60 Hz y con una ventana que ocupa media pantalla, la estela se lee como "borroso", no como "rápido". Yo la dejaría a 0,3 de serie (ahora es 1, `Tune.java:29`) y solo en Profundidad.

**C8. Trabajo por fotograma en el hilo de MIUI.** [V], pequeño
- Dónde: `SEEN.add(type + impl.getClass().getSimpleName())` crea un `String` en cada fotograma (`WindowAnim.java:636`). Los `Method.invoke` sin argumentos crean un `Object[0]`, y `outerRadius.invoke` crea un `Float` en cada llamada (`730`). Son unas 10 asignaciones por fotograma en el hilo de animación de MIUI.
- Coste: unos microsegundos y algo de basura para el recolector. Lo mide ya la línea `hilo de MIUI: … ms de media` del registro.
- Arreglo: guarda el último `type` y compara con `==`, y usa arrays vacíos constantes. Ganancia pequeña; hazlo cuando toques ese archivo.

**C9. La capa ocupa toda la pantalla y tiene transparencia.** [O] + [P]
- Dónde: `GlRenderer.ensure` crea una capa del tamaño de la pantalla, translúcida y escalada con matriz (`GlRenderer.java:519`, `layerMatrix` `557`), y cada fotograma se borra y se pinta entera.
- Riesgo: con escala y transparencia, el compositor de hardware (HWC) del Helio G81 puede no aceptarla y que SurfaceFlinger la componga con la GPU ("CLIENT"). En ese caso la misma Mali-G52 hace nuestro dibujo y además la composición de toda la pantalla. Esto encaja con los tirones "por búfer" (`jankWhy[0]`).
- Cómo verlo: durante una animación, `adb shell dumpsys SurfaceFlinger` y busca la capa `IceHome genie`. Si pone `Composition: CLIENT`, es esto.
- Arreglo si es así: recortar la capa a la caja de la forma con `setCrop`, en la misma transacción. Hasta el 50 % de la animación la forma ocupa menos de un cuarto de la pantalla.

**C10. Si no hay icono, al cerrar la ventana se desvanece y el icono aparece de golpe.** [V], caso raro
- Dónde: sin `iconReady` (la copia del icono falló), `layerAlpha()` devuelve `1 − iconFade` (`Genie.java:1221`), así que la capa se funde entre el 22 % y el 6 %. Pero `floatingIconAlpha()` devuelve 0 al cerrar si se ve el genio (`686`). Ni capa ni icono: hay un hueco hasta que MIUI enseña el icono real.
- Arreglo: con `!s.iconReady` y cerrando, devolver `s.iconFade` en `floatingIconAlpha()`.

---

## 1. Genio clásico (estilo 0)

### 1.1 Cómo funciona [V]

- **Forma:** en `shapeOnly` (`Genie.java:2053`-`2079`) y en el vertex shader `row()` (`GlRenderer.java:127`-`133`). Cada fila de la malla (64 filas, `ROWS`) tiene una anchura que va de la del icono a la de la pantalla con `k = q^e(n)`. El exponente va de `lead = 1/(1 + 0,35·g)` en el extremo lejano a `lag = 1 + 0,6·g` en el cercano, con `g = strength` (2,2 de serie).
- **Envolvente:** cerca del icono la deformación se apaga con `smooth(ph/0,12)` (`CUP_FADE`), sin pendiente infinita al salir ni al llegar.
- **Vertical:** se desplaza arriba y abajo según `far` y `near`. Al cerrar, el extremo lejano se adelanta menos (`CLOSE_RISE = 0.35`, `2060`).
- **Copa invertida:** si el icono está arriba, la forma se da la vuelta (`flipFor`, con `FLIP_LO`/`HI` = 0,40/0,60 y constante de 120 ms).
- **Tiempo:** el del muelle de MIUI.
- **Cierre:** lo mismo al revés, con la lámpara al arrastrar.
- **Fin:** como en 0.2 y 0.3.

### 1.2 Defectos

- **C3, C5, C6 y C7 en toda su fuerza.** Es el estilo con la forma más alejada del rectángulo de MIUI. [V]
- **El cambio de sentido de la copa (`flip`) es una exponencial de 120 ms** (`Genie.java:1283`). Si sueltas el dedo hacia un icono de arriba, la forma se da la vuelta mientras vuela: en un 15-20 % del viaje la forma "se retuerce". [V] + [O] Mejor: elegir el `flip` final al soltar y pasar a él con un muelle de la misma respuesta que el de la ventana.
- **La lámpara** (ω = √170 = 13 rad/s, ζ = 0,5) tarda unos 0,48 s en cada oscilación y se pasa un 16 %: al arrastrar despacio "baila". [V] Opinión [O]: en iOS la tarjeta sigue al dedo 1:1, sin balanceo. Bájala a ζ = 0,75 y K = 260, con un tope de inclinación de 0,12 en vez de 0,22 (`LAMP_MAX_BEND`).

### 1.3 Barato o de calidad [O]

- **Calidad:** la envolvente cerca del icono y la malla por filas (barata y suave) están muy bien.
- **Barato:**
  - El icono estirado y el "enfoque" del icono al llegar (C6).
  - La captura vieja al final (C5).
  - La estela que no coincide (C7).
  - Con intensidad 2,2 la cola es larga y "de Mac": se ve como un efecto, no como una ventana que crece. Para que se vea de calidad, la deformación debe notarse en el primer 40 % y desaparecer del todo antes del 85 %.

### 1.4 Valores de partida y cómo afinarlos

- Muelle de apertura: ζ = 1,0 y respuesta 0,44 s, a escala 1. Equivale a `Velocidad` ≈ 0,95 si la respuesta base de MIUI es ≈ 0,42 s (`SpringGraph.BASE_RESPONSE`, que es una aproximación: mídela, ver la sección 10.4).
- Intensidad: 1,6-1,8.
- Envolvente final: añade `* (1 − smooth((pw − 0.75)/0.15))` a `far` y `near`, para que con `pw ≥ 0,9` ya sea un rectángulo y se pueda devolver la ventana real (C5).
- Fundido icono → app: 0,02-0,14 está bien. Fundido app → icono al cerrar: 0,28-0,10.
- Cómo afinarlo: graba (sección 10.4), mide `pw(t)` y comprueba que la deformación máxima (la diferencia entre la anchura de la fila de arriba y la de abajo) se da entre el 20 % y el 35 % del tiempo.

### 1.5 Calidad

Es correcto. Con los arreglos C3-C7 y la envolvente final queda de calidad. No necesita rehacerse.

---

## 2. Copa (estilo 1, también los antiguos 2 y 4)

### 2.1 Cómo funciona [V]

- **Forma:** `shapeOnly`, en `Genie.java:2021`-`2051`, y el vertex shader con `cup > 0.5` (`GlRenderer.java:114`-`124`).
- **Extremos:** el lejano sigue `1 − (1 − q)^(1 + 0,9·g)` y el cercano `q^(1 + 0,5·g)`, los dos con la envolvente `CUP_FADE = 0,12`. Los lados son casi rectos, con hombros suaves (`pr`).
- **Sin inversión:** la copa nunca se da la vuelta (`flip = 0`).
- **Arrastre:** lo que el dedo desplaza el rectángulo (`off`) se suma, menos cerca del icono.
- **Uso:** es el estilo de serie de los perfiles "Suave", "HyperOS 4" e "iOS 26" (`Presets.java:30`, `124`, `129`).

### 2.2 Defectos

- Los comunes: **C3, C5, C6 y C7**. [V]
- **"iOS 26" usa la Copa con intensidad 0,6 y rebote 0,12.** El rebote 0,12 baja la amortiguación de todos los muelles de MIUI a 0,88, incluidos el radio y la transparencia (`Springs.java:63`): el radio de las esquinas también se pasa y vuelve. [V] En iOS solo rebota la geometría (y muy poco al abrir). Ver la sección 7.

### 2.3 Barato o de calidad [O]

Con intensidad baja (0,6-1,0) es lo más parecido a iOS que tiene el proyecto. Lo que lo abarata es lo común: el icono estirado, el final con la captura y el radio que rebota.

### 2.4 Valores

- Para el modo iOS: intensidad 0,5. Apertura con ζ = 1 y respuesta 0,45 s. Cierre con ζ = 0,86 y respuesta 0,40 s, con la velocidad del dedo.
- El Rebote debe ir solo a `centerX/centerY/width/height`.
- Prueba de calidad: con la grabación, la esquina de arriba no debe ir más de un 8 % de la pantalla por delante de la de MIUI en ningún fotograma.

### 2.5

No es de baja calidad: basta con arreglar lo común.

---

## 3. Profundidad (estilo 3)

### 3.1 Cómo funciona [V]

- **La hoja:** el rectángulo de MIUI tal cual, como una lámina inclinada vista con una cámara (`depth`, `Genie.java:1835`; `PERSP` y el fragment shader con `cup` 2, `GlRenderer.java:82`, `164`, `297`). Gira sobre su borde de abajo, así que abajo mide siempre lo que el rectángulo de MIUI y arriba se aleja.
- **La inclinación** depende solo de `pw`: `smooth(pw/0,06) · (1 − smooth((pw − 0,8)/0,2))`, por `k = e/(1 − e)` con `e = depthTaper` (0,3). Cerrar es abrir al revés.
- **La estela:** obturador de 12,5 ms (`DEPTH_SHUTTER_S`), con un tope del 9 % de la altura. Ver C7.
- **Fondo:** el escritorio se oscurece un 6 % (`DEPTH_DIM`), pintado como borrado de la capa (`beginFrame(dim)`, `GlRenderer.java:747`).

### 3.2 Defectos

- **La inclinación entra muy de golpe.** Llega a tope cuando la anchura apenas es un 6 % mayor que la del icono (`DEPTH_RISE = 0.06`). Con el muelle de MIUI, ese 6 % pasa en 1-2 fotogramas: la hoja "salta" a trapecio nada más empezar. Al cerrar pasa lo mismo al final: de trapecio a icono en 1-2 fotogramas. [V] los valores, [P] la cuenta de fotogramas.
  - Arreglo: `DEPTH_RISE = 0.15`, o, mejor, que la inclinación siga a un muelle propio (ζ = 0,9, respuesta 0,2 s) que persiga a `shape(pw)`. Así nunca puede ir más deprisa que un muelle.
- **El oscurecido es parte de nuestra capa.** [V] Al pasar a recientes (`fadeOut`) se funde con ella, lo cual está bien. Pero si la sesión acaba con `pw` entre 0 y 1 (`stop` por "otra ventana" o "sin fotogramas"), el oscurecido desaparece de golpe. [O] Es raro, pero se ve.
- **Estela:** este es el único estilo en el que la estela casi coincide con lo dibujado, porque la hoja sigue al rectángulo. La inclinación cambia el ancho de arriba y la estela no lo incluye (C7, menor aquí).

### 3.3 Barato o de calidad [O]

- **Calidad:** la idea es la que más "profundidad" da con menos coste.
- **Barato:**
  - El salto de inclinación al empezar y al acabar.
  - Un oscurecido plano sin escala del escritorio. En iOS lo de detrás se aleja (se hace más pequeño y se desenfoca un poco).
- **Mejora barata:** sube `icehome_wallpaper_zoom`. HyperOS ya escala el escritorio (`homeLeash`, que se compensa en `onMatrix`, `Genie.java:908`). Para que se lea como profundidad hace falta que lo de detrás vaya con el mismo muelle que la ventana.

### 3.4 Valores

| Ajuste | Valor de partida |
|---|---|
| `depthTaper` | 0,18-0,22 |
| `DEPTH_RISE` | 0,15 |
| `DEPTH_SETTLE` | 0,7 (recta antes, para poder devolver la ventana pronto, C5) |
| `depthBlur` | 0,4 |
| `DEPTH_DIM` | 0,10, pero con escala del escritorio 0,94 a la vez |

Prueba: en la grabación, el ancho de arriba no debe cambiar más de un 3 % de la pantalla de un fotograma al siguiente.

---

## 4. Abanico (estilo 5)

### 4.1 Cómo funciona [V]

- **Forma:** `fan` (`Genie.java:1900`) y el shader con `cup` 3 (`GlRenderer.java:222`-`237`).
- **El lado cercano al borde** va a la pantalla con un `easeOut` cúbico y llega con `pw = 0,45` (`FAN_NEAR_END`).
- **El lado lejano** sale con `pw = 0,12` (`FAN_FAR_START`) con un smoothstep, y crece en alto un poco antes que en ancho (`farV = max(far, near·0,6)`).
- **Arriba y abajo** son rectas de un lado al otro. Se ignora el rectángulo de MIUI; solo se usa `pw`.
- **El icono** queda tapado (`cover`) hasta `pw ≈ 0,6` (`Genie.java:1572`).

### 4.2 Defectos

- **Salto de lado al arrastrar.** [V] Al cerrar con el dedo, `icon` es el icono virtual bajo el dedo (`updateIcon`, `Genie.java:1346`), y `nearSide(icon, full)` cambia en cuanto el dedo cruza el centro de la pantalla. Todo el abanico se refleja de golpe en un fotograma.
  - Arreglo: calcular el lado una vez por sesión, con el icono real (al abrir) o con el icono al que vuelve (al cerrar), y guardarlo en `Session`.
  ```java
  // Session: float side = 0;  (0 = sin decidir)
  if (side == 0 && !following) side = nearSide(icon, full);
  // y pasar side a fan/scroll/card en vez de recalcularlo
  ```
- **Mezcla de unidades:** `cover` usa `DIR_BLEND_S` (0,15 s, un tiempo) como ancho de tramo de `pw` (`Genie.java:1573`). Funciona por casualidad. [V] Usa una constante propia, `COVER_BLEND = 0.15f`.
- **El lado cercano se para en seco** con `pw = 0,45` (el `easeOut` acaba con velocidad 0) mientras el otro sigue. Se ve como dos animaciones pegadas. [O]

### 4.3 Barato o de calidad [O]

El concepto es bueno, porque recuerda a abrir una puerta. Lo abaratan los lados rectos (parece un trapecio de PowerPoint), que el lado cercano "frene y espere" y que la imagen se vea sesgada (arriba y abajo inclinados, con el contenido también inclinado). Una puerta de verdad tiene perspectiva: el lado lejano se ve más pequeño en vertical, pero el contenido sigue en ángulo recto dentro de la hoja.

### 4.4 Valores

- `FAN_NEAR_END = 0,55`, con una curva que termine con algo de velocidad, como `1 − (1 − t)^2` en vez de `^3`.
- `FAN_FAR_START = 0,05`.
- Mejor aún: convertir el abanico en una "puerta" con la misma perspectiva que Profundidad, pero girando sobre el eje vertical del borde cercano, 35° al empezar y 0° al llegar. El código de la Carta (`cup` 5) ya hace esa proyección.

---

## 5. Pergamino (estilo 6): análisis a fondo y rediseño

### 5.1 Cómo funciona ahora [V]

**Datos** (`scroll`, `Genie.java:1929`-`1948`):

| Cosa | Cálculo |
|---|---|
| Lado | `nearSide(icon, full)`: el borde más cercano al icono |
| Viaje | `travel = smooth(pw / 0.36)` (`SCROLL_TRAVEL`) |
| Desenrollado | `unroll = smooth((pw − 0.3)/0.7)` (`SCROLL_UNROLL`) |
| Radio al salir | `r0 = icono/2` |
| Radio al final | `r1 = 1,1 %` del ancho (≈ 8 px en 720p) |
| Radio en medio | `r = √((1 − unroll)(r0² − r1²) + r1²)`: el área del papel se conserva, una buena idea |
| Centro del rollo | va del icono al borde (`c`) y luego al otro lado (`xc → W + r1 + 2`) |
| Alto | `top` y `bot` van del icono a la pantalla con `travel` (ya está a toda altura con `pw = 0,36`) |

Se ignora el rectángulo `cur` de MIUI; solo se usa `pw`.

**Velocidad:** el muelle de MIUI va al 60 % (`SCROLL_SPEED = 0.6`, `Genie.java:210`). Se aplica en `Springs.java:56` **al abrir y al cerrar**.

**Shader** (`cup` 4, `GlRenderer.java:241`-`273`), con `x` medida desde el borde cercano:

- **Hasta `xc`:** papel plano. El contenido va a escala 1:1 en horizontal: `ul = (xl − anchor)/W`.
- **De `xc` a `xc + r`:** el papel "cae" por un cuarto de cilindro, con `ph = asin(t)`. En vertical se encoge con `kz = 1/(1 + r(1 − cos)·2,4/W)` y se oscurece con `lit = 0,25 + 0,75·cos`.
- **Brillo:** una franja de 3 px de pantalla alrededor de `xc` (`spec = 0,3`).
- **Malla:** un rectángulo un 6 % más alto, que el fragment shader recorta (`GlRenderer.java:151`).

**Icono:** queda tapado hasta `pw ≈ 0,6`, centrado en el rollo y a su tamaño real (`Genie.java:1572`; `GlRenderer.java:269`-`273`).

**Cierre:** la misma función de `pw` al revés: el papel se enrolla hacia el borde cercano y luego el rollo viaja al icono.

**Fin:** a pantalla completa, con el rollo ya fuera de la pantalla, la ventana real entra como en 0.2.

### 5.2 Por qué se ve de baja calidad (de más a menos grave)

1. **No es un rollo: es una página que se dobla hacia abajo.** [V] El shader solo pinta el cuarto de cilindro entre `xc` y `xc + r`, que va de la cresta al lado de fuera, y debajo deja el papel plano hasta `xc`. Un rollo apoyado en el papel tapa de `xc − r` a `xc + r`: se ve su mitad de arriba entera, y el papel plano termina bajo el rollo. Con lo que hay se ve medio cilindro pegado a una hoja: el ojo lo lee como "una hoja que cae por el borde de una mesa", no como "un pergamino".
2. **El rollo encoge en vertical (`kz < 1`).** [V] Un rollo está encima del papel, más cerca de ti, así que debería verse un poco más grande o igual, nunca más pequeño. Encoger hace que parezca que se hunde en la pantalla. Unido al oscurecido, refuerza la idea de "hoja que cae".
3. **El rollo enseña la cara impresa.** [V] La app se sigue pintando sobre el cilindro (`ul` continúa por el arco). En un papel que se enrolla con la cara impresa hacia arriba (como una alfombra), la cara impresa queda dentro y lo que se ve del rollo es el dorso. Pintar la app sobre la curva da una mancha comprimida de colores que no se entiende.
4. **La luz es de "degradado", no de volumen.** [V]
   - `lit` llega a 0,25 en el borde del cilindro y deja una franja casi negra.
   - El brillo es una línea fija de 3 px de pantalla, el mismo para un rollo de 65 px que para uno de 8 px. En el rollo grande parece una costura, no un brillo.
   - El brillo usa `abs(xl − xc)`, así que también se pinta 3 px sobre el papel plano.
   - No hay sombra de ningún tipo: ni del rollo sobre el papel, ni de la hoja sobre el escritorio.
5. **Mientras viaja, la imagen se deforma sin sentido.** [V] El contenido va a escala 1:1 en horizontal, pero estirado en vertical de la altura del icono a la de la pantalla. Y el icono se pega a su tamaño real encima (no escala con la forma). Resultado: una tira alta con un trozo estirado del borde de la app y un icono pegado en medio.
6. **Se para en seco al cambiar de sentido.** [V] El rollo va hacia el borde y luego se desenrolla hacia el otro lado: el sentido cambia, y las dos curvas son smoothstep (velocidad 0 en sus dos extremos). Con `pw` entre 0,3 y 0,36 todo se queda casi quieto. Un objeto de verdad que cambia de sentido rebota. Este se detiene y vuelve a arrancar.
7. **El desenrollado cae en la cola del muelle.** [V] + [O] `pw` viene del muelle de MIUI, que va muy rápido al principio y se arrastra al final. El 70 % del desenrollado (`pw` de 0,3 a 1) pasa en el tramo lento y exponencial del muelle: los últimos centímetros se arrastran. Por eso se puso `SCROLL_SPEED = 0.6`, que alarga todo y no arregla la forma de la curva.
8. **El cierre también va al 60 %** (`Springs.java:56` se aplica a `CLOSE_TO_HOME`). [V] El cierre es justo donde la gente quiere velocidad. Tras soltar el dedo, el muelle es más lento de lo que el gesto pedía: la ventana "se frena" al soltarla.
9. **La estela emborrona el papel quieto** (C7). [V] El papel extendido no se mueve, pero los bordes de MIUI sí, así que se le aplica la estela de 8 muestras. Es la causa del aspecto "sucio" en mitad del desenrollado. Y además cuesta 8 lecturas de textura por píxel en casi toda la pantalla.
10. **Salto de lado al arrastrar** (como en 4.2). [V] Al cerrar con el dedo, si cruzas el centro, el rollo salta al otro borde.
11. **Esquinas:** solo se redondean las del papel plano, con el radio de la ventana de MIUI. El rollo es rectangular por arriba y por abajo, y no tiene grosor. [V]

### 5.3 Rediseño propuesto: "Pergamino 2"

**Objetivo:** que parezca un objeto físico (papel grueso con su peso) y no un efecto de shader. Se respeta la idea del usuario (su dibujo): sale del icono, va al borde más cercano, se desenrolla hacia el otro y acaba a pantalla completa.

#### Concepto

1. **El icono se enrolla (0-90 ms).** El icono se convierte en un rollo vertical del mismo ancho: el cuadrado pasa a cilindro (empieza a tener luz de cilindro) y crece en alto. El icono queda pintado como "etiqueta" en el rollo y se funde con el dorso del papel mientras el rollo crece.
2. **Viaje y elevación (0-280 ms).** El rollo se desliza hacia el borde más cercano mientras crece en alto hasta la pantalla. El alto va un poco por delante del viaje (alto con 0 ms de retraso, viaje con 20 ms).
3. **Choque y rebote.** Al llegar al borde, el rollo se aplasta un 5-6 % durante unos 60 ms y su velocidad se convierte en el arranque del desenrollado (coeficiente de rebote 0,3). Así el cambio de sentido se ve físico, como un rebote, y no se para en seco.
4. **Desenrollado (200-560 ms).** El rollo rueda hacia el otro lado y deja detrás el papel plano con la app en vivo:
   - El rollo enseña el **dorso** del papel: el color de fondo de la app, un poco más apagado, con un 6 % de la cara impresa que se transparenta (en papel fino pasa).
   - El radio baja con lo que queda de papel. Es una espiral de Arquímedes con un grosor de papel "teatral".
   - Luz de verdad: difusa más brillo, con la luz arriba a la izquierda.
   - Oclusión en el pliegue y **sombra de contacto** sobre el papel plano junto al rollo.
   - **Sombra suave** del rollo y de la hoja sobre el escritorio, del lado todavía sin desenrollar.
   - El muelle del desenrollado se pasa un 2 %: el rollo sale por el borde lejano y se va, no frena en el borde.
5. **Relevo:** en cuanto el rollo sale de la pantalla y la velocidad baja de 50 px/s, entra la ventana real en la misma transacción. Para que no haya captura vieja, a partir del 60 % del desenrollado se puede pintar el papel plano con la ventana real (alfa 1 sobre la zona plana) y el rollo con nuestra capa encima (ver la nota de relevo más abajo).

**Cierre: el dedo manda.**
- **Con el dedo (`CLOSE_TO_DRAG`):** lo que sube el dedo se convierte en papel enrollado, 1:1. El papel se enrolla desde el borde lejano hacia el cercano bajo el dedo: `L = W · (1 − subidaDelDedo / (0,45·H))`. La velocidad vertical del dedo pasa a ser la velocidad de `L` al soltar.
- **Al soltar:**
  1. Muelle de `L → 0`, con ζ = 0,9 y respuesta 0,30 s, empezando con la velocidad del dedo.
  2. Cuando queda un 20 % de papel, el rollo vuela al icono con ζ = 0,85 y respuesta 0,32 s (se pasa un 1,5 % y se asienta) mientras baja su alto.
  3. En los últimos 120 ms la "etiqueta" del icono aparece en el rollo y el cilindro se aplana hasta ser el icono (la luz de cilindro va de 1 a 0).
- **El lado** se decide al empezar el gesto (con el icono al que vuelve, si MIUI lo sabe; si no, con la posición del dedo al empezar) y no cambia nunca.

#### Geometría (vista desde arriba, con un poco de perspectiva)

`x` es la distancia desde el borde cercano, y `L` la longitud de papel ya extendida (el eje del rollo está en `xa = L`). El radio actual es `R`.

- **Zona A** (`x < xa − R`): papel plano a la vista. `u = x/W` (reflejado según el lado), y `s` lineal en alto.
- **Zona B** (`xa − R ≤ x ≤ xa + R`): la mitad de arriba del rollo. Con `q = (x − xa)/R`, la normal es `n = (q, 0, √(1 − q²))` y el ángulo desde la cresta es `θ = asin(q)`.
  - La coordenada del papel a lo largo de su recorrido es `up = L + R·(π − θ)`. Es la vuelta de fuera: del punto de contacto, por el lado lejano, la cresta y el lado cercano.
  - Esa cara es el **dorso**.
- **Zona C** (`x > xa + R`): escritorio. Solo se pinta la sombra que proyecta el rollo, que se apaga con la distancia.
- **Radio:** `R(L) = √(Rc² + T·(W − L)/π)`, con `Rc` = 6 dp de alma. El grosor es `T = π·(R0² − Rc²)/W`, para que con todo enrollado `R = R0 =` medio icono (unos 28 dp). Es un grosor exagerado a propósito (unos 6 dp); se ve "papel grueso" y no "film".
- **Perspectiva suave:** cámara a `D = 3·W`. En la zona B la altura `z = R·(1 + n.z)` amplía en vertical por `D/(D − z)`: el rollo se ve un poco más alto que el papel (la sensación de que está encima), lo contrario de lo que hace ahora.

#### Esquema del shader (GLSL ES 2.0)

Encaja como un `cup` nuevo (o sustituye al 4). Se reutilizan `CONTENT(u, s)`, `bg` y `ex`.

```glsl
// uniforms nuevos: vec4 roll = (xa, R, side, D); vec4 sheet = (top, bot, W, radioEsquina)
// uniform vec3 lightDir;   // normalize(vec3(-0.35*side, -0.45, 0.82)) en coordenadas de pantalla
float W = sheet.z, xa = roll.x, R = max(roll.y, 0.5);
float x = roll.z < 0.0 ? p.x : W - p.x;          // desde el borde cercano
float top = sheet.x, bot = sheet.y, H = bot - top;
vec4 col = vec4(0.0);
float shade = 1.0;

if (x < xa - R) {                                // A: papel plano (la app)
  float u = x / W, s = (p.y - top) / H;
  col = CONTENT(roll.z < 0.0 ? u : 1.0 - u, s);
  col = col + bg * (1.0 - col.a);
  // sombra de contacto junto al rollo (el rollo "se apoya" aquí)
  float dC = (xa - R) - x;
  shade = 1.0 - 0.30 * exp(-dC / (0.55 * R + 4.0));
  d = distanciaAlBordeRedondeado(...);           // como ahora (esquinas del lado cercano)
} else if (x <= xa + R) {                        // B: rollo, mitad de arriba
  float q = (x - xa) / R;
  vec3 n = vec3(q * (roll.z < 0.0 ? 1.0 : -1.0), 0.0, sqrt(max(1.0 - q*q, 0.0)));
  float z = R * (1.0 + n.z);
  float k = roll.w / (roll.w - z);               // perspectiva: se ve algo más alto
  float cy = (top + bot) * 0.5;
  float y0 = (p.y - cy) / k;                     // de pantalla a papel
  float s = clamp(y0 / H + 0.5, 0.0, 1.0);
  float up = xa + R * (3.14159 - asin(q));       // recorrido del papel
  vec4 face = CONTENT(clamp(roll.z < 0.0 ? up/W : 1.0 - up/W, 0.0, 1.0), s);
  vec3 back = mix(bg.rgb * 0.86, vec3(0.93, 0.91, 0.87) * bg.a, 0.25);   // dorso del papel
  back = mix(back, face.rgb, 0.06);              // se transparenta la cara impresa
  float diff = 0.55 + 0.45 * max(dot(n, lightDir), 0.0);
  float spec = pow(max(dot(n, normalize(lightDir + vec3(0,0,1))), 0.0), 28.0) * 0.22;
  float ao   = mix(0.72, 1.0, n.z);              // los lados del rollo, algo más oscuros
  col = vec4(back * diff * ao + spec, 1.0);
  d = min(R - abs(x - xa), (H * 0.5 * k - abs(p.y - cy)));   // antialias del contorno
} else {                                         // C: sombra del rollo sobre el escritorio
  float dS = x - (xa + R);
  float a = 0.22 * exp(-dS / (0.6 * R + 6.0));
  col = vec4(0.0, 0.0, 0.0, a);                  // premultiplicado
  d = 1e4;
}
// la sombra de toda la hoja sobre el escritorio (bajo la zona A), opcional:
// alfa 0,15·exp(−distanciaAlBorde/12dp) fuera del contorno de la hoja
col.rgb *= shade;
gl_FragColor = col * clamp(d / (1.0 / scale) + 0.5, 0.0, 1.0);
```

**Sin estela** en este estilo. Si se quiere, solo en la zona B y con 4 muestras en x.

#### Cronología y muelles (con el reloj propio de la sección 10.1)

```java
// Estado del Pergamino 2 (hilo de dibujo). Todo en px y px/s.
Spring rise   = new Spring(1.00f, 0.30f);   // alto: del icono a la pantalla
Spring travel = new Spring(0.92f, 0.26f);   // eje del rollo: del icono al borde
Spring squash = new Spring(0.60f, 0.12f);   // aplastado al chocar (escala de ancho)
Spring unroll = new Spring(0.85f, 0.42f);   // L: de 0 a W (se pasa un ~2 %)
boolean unrolling;

void step(float dt) {                        // dt = MotionClock.dt()
  rise.step(dt); travel.step(dt); squash.step(dt);
  if (!unrolling && travel.x <= edge + R0 * 1.02f) {      // choca con el borde
    unrolling = true;
    unroll.v = 0.30f * Math.abs(travel.v);                // rebote: la velocidad no se pierde
    squash.v = -2.5f;                                     // se aplasta y vuelve
  }
  if (unrolling) unroll.step(dt);
}
// Relevo: unroll.x >= W − 0.5 && |unroll.v| < 50 → ventana real, misma transacción.
```

**Duración total** a escala 1: unos 560 ms; la sensación es de 450 ms, porque lo último es casi quieto y ya en vivo.

**Relación con el muelle de MIUI (lo importante para que funcione):**
- El Pergamino 2 tiene su propio reloj, así que hay que evitar que `animEnded` (`Genie.java:539`) corte la sesión antes de tiempo. Para este estilo, `stop("fin")` debe esperar a que el propio cronograma termine, con un tope (p. ej. 250 ms más que MIUI).
- **Riesgo:** mientras tanto la app ya recibe toques y está invisible bajo nuestra capa. Hay que mantener ese tiempo extra por debajo de 150 ms. Si hace falta más, alarga el muelle de MIUI con `Springs` solo en `OPEN_FROM_HOME`, nunca en el cierre.

**Nota de relevo:** para que el papel plano esté en vivo al final, entre el 60 % y el 100 % del desenrollado se puede dejar la ventana real a la vista con un recorte (`setCrop` en el aplicador de MIUI a la zona A) y dibujar solo el rollo y las sombras en nuestra capa. Así no hay "enfoque" al final. Pide más trabajo; hazlo en una segunda fase.

#### Presupuesto en la Mali-G52 (Helio G81, MC2) [O], estimación

| Pieza | Coste |
|---|---|
| Píxeles | capa al 75 %: 540 × 1200 ≈ 650 mil |
| Zona A | 1 lectura de textura + unas 15 operaciones |
| Zona B (ocupa menos del 15 % de la pantalla) | 1 lectura + unas 45 operaciones (`asin`, `pow`, `sqrt`) |
| Zona C | unas 8 operaciones |
| Total GPU | unos 30 millones de operaciones y unos 0,8 millones de lecturas por fotograma, ≈ 0,5-0,8 ms en la G52. Ahora, con la estela de 8 lecturas, son unos 5 millones de lecturas |
| Ancho de banda | borrar + escribir + que SurfaceFlinger lea la capa: unos 8 MB por fotograma, ≈ 0,5 GB/s a 60 fps. Asumible |
| CPU (hilo de dibujo) | menos de 0,3 ms: 4 muelles y uniformes |
| Capturas | como mucho 2 en la apertura (la primera y una a los 150 ms); 0 en el cierre (la precargada) |

**Objetivo:** menos de 3 ms de GPU por fotograma a 60 Hz y menos de 2 ms a 90 Hz. Hay margen de sobra **si se quita la estela**.

Importante: con un shader para todo, el peor estilo marca los registros de todos (sección 10.2). Si el Pergamino 2 sube de 60 operaciones, mejor que sea un programa aparte.

---

## 6. Carta (estilo 7)

### 6.1 Cómo funciona [V]

- **Giro:** `card` (`Genie.java:1955`) calcula `a = 2π · 2 · (1 − (1 − t)^3,2)`, con `t = (pw − 0,14)/0,86` (`CARD_TURNS = 2`, `CARD_STILL = 0.14`, `CARD_EASE = 3.2`).
- **Cámara:** a 1,93 veces el ancho de la pantalla.
- **Shader** (`cup` 5, `GlRenderer.java:277`-`296`): proyecta la carta girando sobre su eje vertical. De espaldas enseña el color del icono con el icono en el centro (`GlRenderer.java:347`-`353`). La luz es `1 − (1 − |cos|)·0,45`.
- **Tamaño:** el del rectángulo de MIUI.

### 6.2 Defectos

- **Efecto estroboscópico.** [V] la fórmula, [O] la cuenta: dos vueltas son 12,6 rad. La pendiente inicial de `1 − (1 − t)^3,2` es 3,2. Si `t` pasa de 0 a 1 en unos 300 ms, la velocidad media es de 42 rad/s y al principio llega a unos 130 rad/s: **unos 125° por fotograma a 60 Hz**. A partir de unos 90° por fotograma el ojo no sabe hacia dónde gira (como las ruedas de los coches en las películas): se ve una carta que "tiembla" o cambia de sentido.
- **El icono se estira a toda la carta al salir.** [V] `cover` fuerza `iconMix = 1` hasta `pw ≈ 0,6` (`Genie.java:1572`), y en la Carta `iuv = (u, s)` con `iconIn = 1`. El icono de 128 px se estira a media pantalla: borroso y deformado.
- **Perspectiva casi nula al principio.** [V] La cámara se mide en anchos de la pantalla (`ex.z = 1,93·W`), pero al principio la carta es del tamaño del icono: sin perspectiva, el giro parece un estrechamiento plano. Y cuando por fin hay perspectiva, ya casi no gira.
- **Salto de lado al arrastrar** (4.2): `-side * sin(a)` cambia el sentido de giro de golpe. [V]
- **La estela no tiene en cuenta el giro** (C7). [V]

### 6.3 Barato o de calidad [O]

Dos vueltas es un efecto de "presentación de diapositivas". En un móvil se lee como un truco. Media vuelta, que enseña el dorso (el icono) y luego la app, sí cuenta algo: "le das la vuelta a la carta del icono y detrás está la app".

### 6.4 Valores

| Ajuste | Ahora | Propuesta |
|---|---|---|
| `CARD_TURNS` | 2 | **0,5** (de espaldas al salir, mostrando el icono, y de frente al llegar) |
| `CARD_EASE` | 3,2 | 2,2 |
| `CARD_STILL` | 0,14 | 0,04 |
| Cámara | 1,93 × ancho de la pantalla | **2,4 × ancho de la carta actual** (`max(cur.width(), icon.width()*3)`), para que haya perspectiva desde el principio |

- **Límite de seguridad:** como mucho 25-30° por fotograma al periodo de dibujo real. Compruébalo en el código: `Δa/fotograma ≤ 0,5 rad`.
- **Icono del dorso:** cuadrado, del 42 % del ancho (ya está así), y sin el `cover` estirado: con media vuelta, el dorso ya es el icono.

---

## 7. HyperOS (estilo 8)

### 7.1 Cómo funciona [V]

- **No dibuja nada propio** (`Genie.draws()` devuelve false, `Genie.java:202`).
- **Muelles:** solo se ajustan los de MIUI: `response / velocidad` y, con Rebote, `damping = min(d, 1 − rebote)` en **todas** las claves del array: centro, ancho, alto, radio y transparencia (`Springs.java:59`-`64`).
- **Sin efecto:** sin genio ni estela. El empuje de iconos y el zoom del fondo siguen funcionando.

### 7.2 Defectos

- **El Rebote toca el radio y la transparencia.** [V] Con rebote 0,12 el radio de las esquinas también oscila (las esquinas "laten" al llegar) y la transparencia del icono flotante se pasa y vuelve.
  - Arreglo: aplicar el Rebote solo a las claves de geometría.
  ```java
  boolean geo = k.contains("center") || k.contains("Width") || k.contains("Height");
  if (b > 0f && geo) d = Math.min(d, 1f - b);
  ```
  Antes, apunta en el registro las claves reales (`Log.i(k)` una vez) para saber cómo se llaman.
- **El muelle lento del Pergamino se cuela si el genio está activo.** [V] `Genie.style == STYLE_SCROLL` también frena el cierre (`Springs.java:56`). Aquí no aplica, pero es el mismo código.

### 7.3 y 7.4 [O]

Es la base más fiel: ventana en vivo, sin capturas ni GPU extra. Para el modo "iOS ligero" de los móviles a 120 Hz es la mejor opción: Velocidad 1,05 y Rebote 0,06, solo en geometría.

---

## 8. Genio suelto (`Ghost`) y rectángulo ligero (`RectGhost`)

### 8.1 Cómo funciona [V]

- **Cuándo:** si se abre otra app mientras una se cierra, la que se cierra sigue sola hasta su icono.
- **Muelle:** uno por borde, con K = 300 y ζ = 0,92 (respuesta ≈ 0,36 s), tope de 1,5 s y relevo de 40 ms (`Genie.java:157`-`160`, `2136`). Arranca con la velocidad que llevaba (`edgeVel`).
- **Modo ligero:** un rectángulo de color de una capa de color del sistema, sin OpenGL (`RectGhost`, `Genie.java:2279`). Radio `icono × 0,22` (`2302`).

### 8.2 Defectos

- **Radio inventado.** [V] `iconRadius = icon.width() * 0.22f` es una suposición: el icono de HyperOS es una "squircle" y su radio equivalente depende del pack de iconos. Al llegar, el rectángulo tiene un radio y el icono real otro: se nota un salto de forma al cambiar.
  - Arreglo: usar el radio que da MIUI en el último fotograma (`pendingRadius`, que al final de su muelle es el del icono), o leer la máscara adaptable de `Drawable`.
- **El rectángulo ligero no es el icono.** [V] Llega como un rectángulo de color plano y, tras 40 ms, aparece el icono real encima. [O] Aceptable como modo ligero. Mejor: que en los últimos 80 ms el rectángulo se funda a alfa 0 mientras el icono real ya está visible.
- **Su tiempo no tiene en cuenta la escala de animación** (C4). [V]

---

## 9. Piezas alrededor de la apertura

**Iconos que se hunden (`IconPress.java`).** [V] El icono se encoge a 0,9 en 110 ms y vuelve con `OvershootInterpolator(2.2)` en 300 ms al soltar o al abrir.
- [P] Si el rectángulo de salida de MIUI (`startRect`) es el del icono sin escalar, el genio sale un 10 % más grande que el icono hundido. Encima, el icono real está rebotando: se ve un pequeño salto en el primer fotograma.
- Cómo verlo: graba a cámara lenta el primer fotograma.
- Arreglo: escalar `f.icon` por `ShortcutIcon.getScaleX()` en la apertura, o dejar que el icono vuelva a 1 sin rebote cuando la pulsación termina en apertura.
- Opinión [O]: un muelle (ζ = 0,6, respuesta 0,3 s) en vez de `Overshoot` con duración fija se nota más natural si se interrumpe.

**Empujar iconos (`IconPush.java`).** [V] Sigue a `p`, la media del avance en ancho y en alto, con smoothstep. Con "ola", cada icono tiene un muelle (ω = 26 y ζ = 1 − 0,55·ola), con 2 subpasos.
- Es correcto y barato.
- Nota: `smoothstep(f)` sobre un `f` que ya viene de un muelle frena dos veces al final. [O] Usa `f` directo si la ola está activa.

**Zoom del fondo (`WallpaperZoom.java`).** [V] Usa el muelle de MIUI. Correcto. [O] Para que se lea como profundidad, como en iOS, hace falta que el escritorio, no solo el fondo, se aleje un poco (0,92-0,94), y eso lo hace HyperOS con `homeLeash`.

**Vibración al llegar (`Haptics.land`).** [V] Va por el hilo principal del launcher, con un margen de 150 ms. Correcto.

**Vista previa en la app.** [V] No hay vista previa de los estilos de apertura: solo `SpringGraph`, la curva de Velocidad y Rebote con un móvil en miniatura que crece como un rectángulo.
- Su muelle es una aproximación: `BASE_RESPONSE = 0.42 s` y amortiguación 0,98 (`SpringGraph.java:27`, `86`).
- No tiene en cuenta `SCROLL_SPEED` ni el estilo.
- Crea un `DashPathEffect` en cada `onDraw` (`SpringGraph.java:126`), una asignación por fotograma en la app. Es un detalle: muévelo a un campo.
- La página HTML con los estilos nuevos (Gelatina, Onda, Pergamino del dibujo del usuario) está fuera del repo, como artefacto.
- Propuesta [O]: una vista previa por estilo en la app, que use **las mismas funciones** `fan`, `scroll`, `card` y `shape` en Java con un `Canvas` o, mejor, el mismo shader en un `TextureView`. Así lo que ves en la app es lo que verás en el launcher. Ahora no hay forma de afinar sin abrir apps de verdad.

---

## 10. Transversal

### 10.1 Un solo sistema de movimiento

**Lo que hay ahora** [V]. Seis modelos de muelle distintos:

1. `Spring.java`: analítico, como interpolador de `ValueAnimator`, en la app.
2. `Motion.spring`: analítico, sin velocidad inicial.
3. La lámpara: K y ζ con Euler explícito en pasos de 4 ms (`Genie.java:1396`).
4. `Ghost` y `RectGhost`: otra vez K y ζ con Euler.
5. `IconPush`: ω y ζ con 2 subpasos.
6. El telón: K = 380 y ζ = 0,55.

Y el muelle principal es el de MIUI (Folme: `damping`, `response`). Cada uno se escribe de una forma y ninguno usa la escala de animación.

**Propuesta** [O]:

```java
/** Muelle único, con la misma forma de escribirlo que iOS y Folme: amortiguación y respuesta. */
final class Spring1D {
  float x, v, target;
  final float zeta, response;                        // ζ (1 = sin rebote) y periodo en s
  Spring1D(float zeta, float response) { ... }
  void step(float dt) {                              // semi-implícito y estable; sub-pasos de 4 ms
    float w = 2f * (float) Math.PI / response, k = w * w, c = 2f * zeta * w;
    for (float left = dt; left > 0f; left -= 0.004f) {
      float h = Math.min(0.004f, left);
      v += (k * (target - x) - c * v) * h;
      x += v * h;
    }
  }
  boolean settled(float epsPx) { return Math.abs(target - x) < epsPx && Math.abs(v) < 8f * epsPx; }
}

/** Reloj de animación: hora de vsync y escala del sistema. Todo tiempo propio pasa por aquí. */
final class MotionClock {
  static float dt(long frameNs, long prevNs) {
    float s = ValueAnimator.getDurationScale();
    if (s == 0f) return Float.POSITIVE_INFINITY;     // animaciones quitadas: saltar al final
    return Math.min(0.05f, (frameNs - prevNs) / 1e9f) / s;
  }
}

/** Todos los valores en un sitio (los que hoy están repartidos por Genie, GlRenderer, Springs). */
final class MotionSpec {
  static final float OPEN_ZETA = 1.00f, OPEN_RESPONSE = 0.44f;
  static final float CLOSE_ZETA = 0.86f, CLOSE_RESPONSE = 0.40f;
  static final float ICON_TO_APP_START = 0.02f, ICON_TO_APP_END = 0.14f;
  static final float APP_TO_ICON_START = 0.30f, APP_TO_ICON_END = 0.10f;
  ...
}
```

**Reglas:**

1. Cada animación propia es un `Spring1D` con su ζ y su respuesta. No hay duraciones fijas.
2. Un fundido que debe "seguir" a algo se hace en función del avance (`pw`), no del tiempo. Si tiene que ser por tiempo, va con `MotionClock`.
3. Al interrumpir, el muelle nuevo empieza con la `x` y la `v` del anterior. No se mezclan dos formas en 150 ms (el `DIR_BLEND_S` de ahora).
4. Todos los tiempos que dependen del refresco se escriben como múltiplos de `vsyncNs`.

### 10.2 Presupuesto de rendimiento (Mali-G52 MC2, 90 Hz) [O]

| Pieza | Presupuesto por fotograma | Ahora (estimado) |
|---|---|---|
| Hilo de MIUI (nuestros hooks) | < 0,3 ms | se mide: línea `hilo de MIUI: … ms de media` |
| Hilo de dibujo, CPU | < 1,0 ms | `dibujo X ms` del registro |
| GPU, nuestra capa al 75 % | < 3 ms a 60 Hz y < 2 ms a 90 Hz | sin estela, unos 0,5-1 ms; con estela de 8 lecturas en media pantalla, 2-4 ms |
| Composición (SurfaceFlinger) | por hardware (DEVICE) | [P] puede ir por GPU (C9) |
| Capturas | ≤ 2 al abrir y 0-1 al cerrar | al abrir en vivo, 3 + 1 cada 200 ms (cada una ocupa al compositor 25-80 ms: `Genie.java:140`) |

**Lo que más cuesta, por orden** [O]:
1. Las capturas (detienen a SurfaceFlinger).
2. La estela (8 lecturas de una textura externa).
3. La composición de una capa de pantalla completa con transparencia.
4. El shader único con todos los estilos (`FRAGMENT_BODY`).

En una Mali, el programa reserva registros para la rama más cara aunque esa rama no se ejecute, y eso baja la ocupación de la GPU en todos los estilos. Con varios programas pequeños, uno por estilo y elegido en Java, cada estilo paga solo lo suyo.

**El aviso de los 120 Hz**, causas probables (de más a menos):
1. Las capturas.
2. La estela activada de serie.
3. Antes de la 2.13, `maxFps` seguía a la pantalla (resuelto con el tope; ver C2).
4. La detección de tirones a ciegas a 120 Hz (C1): la resolución automática nunca baja.

### 10.3 Cómo encontrar los valores buenos (método)

**Regla 1: mide siempre a escala 1,0.** Tu móvil está a 1,5. Antes de medir:

```
adb shell settings put global animator_duration_scale 1
# … medir …
adb shell settings put global animator_duration_scale 1.5   # dejarlo como estaba
```

Para ver a cámara lenta, prueba con 5. Si la línea `curva 50/90/99%` del registro sale 5 veces más larga, MIUI respeta la escala y la cámara lenta sirve para inspeccionar. Si no, usa una grabación (abajo).

**Regla 2: cambia un solo valor cada vez**, y anota el valor y la línea del registro.

**Ajustes que se pueden tocar sin compilar** [V]:
- `icehome_anim_speed`, `icehome_anim_bounce`, `icehome_genie_strength`, `icehome_depth_taper`, `icehome_depth_blur`, `icehome_genie_quality`, `icehome_genie_fps`, `icehome_genie_live` e `icehome_genie_style`, desde la app.
- También con `adb shell settings put global <clave> <valor>`, **pero solo si esa clave no está guardada en la app**: `Tune.value` (`Tune.java:214`) mira primero lo guardado en `PrefsProvider` y solo si no hay nada lee `Settings.Global`. Para probar con `settings put`, pulsa antes "restablecer" en ese ajuste.

**Registros que ya hay** [V] (`adb logcat -s HyperSpring`):
- `genio abrir|cerrar a 75%: empieza con anchura X%, N fotogramas en M ms (J tirones, máx. G ms); primer dibujo a …, ventana a …, visible a … ; captura C ms (f/n fallidas), dibujo D ms; curva 50/90/99% a a/b/c ms; fin: …; tirones por búfer/dibujo/hilo/captura/otro: …` (`Genie.java:1757`). Es la línea más útil: la curva 50/90/99 es el "perfil" del muelle.
- `hilo de MIUI: N fotogramas, HyperSpring X ms de media (+Y en transacciones), máx. Z ms` (`WindowAnim.java:150`).
- `genio suelto: llegó al icono en X ms` y `genio: rectángulo en el icono en X ms`.
- `genio: resolución automática, baja al …`.
- `animación TIPO por CLASE` y `sin genio en TIPO: motivo` (una vez por tipo).
- `Health.java` ya lee estas líneas para la nota de "¿Cómo va mi móvil?".

**Lo que falta** (sin tocarlo ahora; es una propuesta): una opción de depuración (`icehome_debug_curve`) que apunte por fotograma `t_ms, pw, cur.left, cur.top, cur.right, cur.bottom, forma.top, forma.bottom, iconMix, fade, retraso_ms`. Con eso se dibujan las curvas de posición y velocidad sin grabar vídeo.

### 10.4 Flujo de trabajo paso a paso

1. **Prepara el móvil:** escala 1,0, brillo fijo, sin ahorro de batería, el estilo que quieras afinar. Abre y cierra la misma app (p. ej. Ajustes) 5 veces seguidas para que se calienten las cachés.
2. **Graba la pantalla:**
   ```
   adb shell screenrecord --bit-rate 40000000 --time-limit 6 /sdcard/abrir.mp4
   adb pull /sdcard/abrir.mp4
   ```
   `screenrecord` graba los fotogramas que cambian, con su hora. Si a 90/120 Hz pierde fotogramas, graba la pantalla con otro móvil a 240 fps (cámara lenta).
3. **Saca los fotogramas con su hora:**
   ```
   ffmpeg -i abrir.mp4 -vsync 0 -frame_pts 1 f_%06d.png
   ffprobe -select_streams v -show_frames -show_entries frame=pts_time -of csv abrir.mp4 > horas.csv
   ```
   Fotogramas repetidos (tirones):
   ```
   ffmpeg -i abrir.mp4 -vf mpdecimate,showinfo -f null - 2>&1 | grep pts_time
   ```
4. **Sigue la ventana fotograma a fotograma** (script de Python en un directorio propio, `python -I`): resta el primer fotograma (el escritorio quieto), umbraliza y saca la caja de los píxeles que cambian. Da `t, x0, y0, x1, y1` por fotograma. Con eso, `pw = (ancho − anchoIcono)/(anchoPantalla − anchoIcono)` y la velocidad `Δpw/Δt`.
   ```python
   import cv2, numpy as np, csv, glob, sys
   frames = sorted(glob.glob(sys.argv[1] + "/f_*.png"))
   base = cv2.imread(frames[0]).astype(np.int16)
   with open("caja.csv", "w", newline="") as out:
       w = csv.writer(out)
       for f in frames:
           img = cv2.imread(f).astype(np.int16)
           m = (np.abs(img - base).max(axis=2) > 28).astype(np.uint8)
           ys, xs = np.nonzero(m)
           if len(xs): w.writerow([f, xs.min(), ys.min(), xs.max(), ys.max()])
   ```
5. **Compara con un iPhone (A/B):** graba un iPhone a 240 fps abriendo una app desde el dock, con el mismo script. Pon las dos curvas `pw(t)` en una gráfica normalizada. Ajusta la Velocidad y el Rebote hasta que el 50 %, el 90 % y el 99 % coincidan a ±20 ms. Para sacar la ζ y la respuesta del iPhone, ajusta el muelle a la curva con `scipy.optimize.curve_fit` y `Motion.spring` pasado a Python.
6. **Pruebas de percepción** (cada una con su número; en una grabación de 6 aperturas y 6 cierres):

   | Prueba | Umbral |
   |---|---|
   | Salto en el relevo (último fotograma del genio frente al primero de la ventana real) | ≤ 1 px de posición, ≤ 1 px de radio, ≤ 3 % de alfa |
   | Velocidad en el cambio de fase (choque del Pergamino, soltar el dedo, fin del icono) | la diferencia de `Δpw/Δt` entre el fotograma anterior y el siguiente, ≤ 10 % |
   | Sin "agujero" al empezar | ningún fotograma donde no se vea ni el icono ni el genio |
   | Ritmo | el 95 % de los intervalos entre fotogramas, ≤ 1,2 × el periodo de dibujo; ninguno > 2 × periodo |
   | Carta | ≤ 30° de giro por fotograma |
   | Icono al llegar | nitidez del último fotograma del genio frente al icono real (diferencia de bordes con Laplaciano) |
   | Contenido | en los últimos 150 ms de la apertura, la imagen del genio no tiene más de 1 fotograma de retraso (C5) |

7. **Mide la GPU y la composición:**
   ```
   adb shell dumpsys SurfaceFlinger --list | grep -i icehome
   adb shell dumpsys SurfaceFlinger --latency "<nombre exacto de la capa>"
   adb shell dumpsys SurfaceFlinger | grep -A3 -i "icehome genie"     # Composition: DEVICE o CLIENT
   ```
   Para ir a fondo, una traza de perfetto como dice `CLAUDE.md` (`sched freq gfx view -a com.miui.home`). En la traza, `FrameTimeline` marca cada búfer como "App Deadline Missed" (nuestra culpa) o "SurfaceFlinger …" (del compositor).
8. **Anota y fija:** cada valor bueno va a `MotionSpec` (o, mientras no exista, a la constante de su estilo) con un comentario: de qué grabación sale y cuánto medía.

---

## 11. Nota sobre el telón (`telon/`)

No es una animación de abrir apps desde el escritorio (son transiciones dentro de las apps, en SystemUI), pero comparte cosas:
- El mismo tope de fotogramas con la misma cuenta (`Telon.java:607`), así que C2 se aplica igual.
- Un `READY_NS` de 10 ms (C1).
- Un muelle propio (K = 380, ζ = 0,55).

Su avance sale de la animación de HyperOS (`fraction`), que sí respeta la escala de animación. Si se hace el `Spring1D` y el `MotionClock` comunes (10.1), conviene pasarlo también.

---

## Anexo: qué está verificado y qué no

**Verificado en el código** (leído entero):
`WindowAnim.java`, `Genie.java`, `GlRenderer.java`, `Springs.java`, `Spring.java`, `Motion.java`, `Tune.java`, `Presets.java` (perfiles y modos), `IconPush.java`, `WallpaperZoom.java`, `SpringGraph.java`, `IconPress.java` y `Haptics.java` (en lo que afecta), y la cabecera y el ritmo de `telon/Telon.java`.

**No verificado:**
- Que el muelle de MIUI respete la escala de animación (C4).
- Que la capa vaya por composición de GPU (C9).
- La cuenta exacta de fotogramas del salto de Profundidad y del giro de la Carta (depende del muelle real de MIUI en el móvil).
- El tamaño de salida con el icono hundido (sección 9).
- Las estimaciones de coste de la GPU (10.2, 5.3).

Todo eso se confirma con el flujo de la sección 10.4.

No hay en el código ningún estilo llamado Gelatina, Onda o Lupa: están solo en la página de vistas previas (fuera del repo). Esta revisión no los cubre.
