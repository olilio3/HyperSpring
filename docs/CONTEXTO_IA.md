# Contexto para la IA: lo hecho del 16 al 17-11-2026 (2.9.2 → 2.12.1)

Resumen de una sesión larga de Claude Code en la nube, para que otra sesión (local o en la nube)
pueda seguir sin la conversación. Léelo junto con `CLAUDE.md`. Todo está en la rama
`claude/roadmap-implementation-vslhi1`, ya subida a GitHub. **No hay ninguna release publicada
después de la 2.9.1**: todo lo de aquí está sin publicar.

## Cómo trabaja el usuario

- Habla en español y quiere las explicaciones en español, sencillas.
- Prueba en su Redmi 14C (HyperOS 3, Android 16, Vector API 102) y manda capturas o vídeos. Lo
  visual solo lo puede validar él: **nada de lo de esta sesión se ha visto funcionar salvo el
  cristal de las notificaciones** (ver abajo).
- Pide los APK directamente en el chat para instalarlos. Ha dado permiso para compilarlos y
  firmarlos con la clave de depuración de uber-apk-signer (la de las releases).
- Las ideas nuevas que propone van a la hoja de ruta (`docs/ROADMAP*.md`) además de hacerse.

## Compilar sin SDK ni GitHub

En la nube, GitHub releases (jadx, uber-apk-signer) y Google Maven están bloqueados. Se usa
**`local-build/maven/build.sh`**, que solo tira de Maven Central y de raw.githubusercontent.com
(las URL de cada herramienta están en su cabecera):

```
TOOLS=~/tools bash local-build/maven/build.sh     # deja apk/HyperSpring.apk y apk/HyperSpring-legacy.apk
```

- Usa `dx` en vez de D8 (DEX ~6 % más grande), firma solo v2 (apksig 2.3.0 no firma v1 con JDK
  modernos; con minSdk 30 no hace falta) y alinea con `local-build/maven/zipalign.py`.
- La clave es la de uber-apk-signer: el APK se instala encima de las releases. Se comprobó que la
  huella SHA-256 coincide con la de la 2.9.1 publicada (`1E:08:A9:03:…:18:7B:59:53`).
- Maven Central limita peticiones seguidas (error 429): si una descarga da un archivo de 96 bytes,
  espera unos segundos y repite.
- Para releases oficiales, mejor `local-build/build.sh` o el CI.
- `apk/` y `local-build/out-maven/` están en `.gitignore`.

## CI (2.9.2)

`.github/workflows/build.yml`: en cada push y pull request compila con `local-build/build.sh`
(descarga jadx, apktool, uber-apk-signer y android.jar; instala libegl1 y skia-python para
comprobar los shaders AGSL) y sube los dos APK como artefacto. **Aún no se ha visto correr**: la
rama se subió al final de la sesión. Revisa la primera ejecución.

## Versiones de esta sesión

| Versión | Código | Qué |
|---|---|---|
| 2.9.2 | 59 | Revisión de todo el código (fallos y optimizaciones) + CI |
| — | — | Filtro rápido en los hooks muy frecuentes (`XC_MethodHook.skip`) |
| 2.10.0 | 60 | Cristal en las notificaciones (solo el borde) — **descartado**, quedaba raro |
| 2.10.1 | 61 | Notificaciones enteras de cristal, oscurecidas |
| 2.11.0 | 62 | Rueda de funciones en Inicio + pausa automática |
| 2.12.0 | 63 | Diez cosas: enfoque al cambiar de página, estilos y modos, dock, etc. |
| 2.12.1 | 64 | Arreglo de los tirones al deslizar las notificaciones de cristal |
| 2.12.2 | 65 | Notificaciones de cristal ligeras (seguían a pocos fps): esmerilado oscuro sin shader |

Detalle de cada una en `CHANGELOG.md` (en inglés).

## Revisión del código (2.9.2)

Se leyó todo buscando fallos. Lo que se arregló, por si vuelve a aparecer algo parecido:

- **`PrefsProvider`** estaba exportado sin control: cualquier app leía los ajustes, el registro y
  la lista de apps sin genio. Ahora `trusted()` mira el UID de quien llama: la propia app, UID de
  sistema (< 10000) y los paquetes de `READERS` (launcher, SystemUI, su plugin, capturas). Solo la
  app puede borrar el registro. **Si algo deja de leer los ajustes, mira aquí primero.**
- **`GlRenderer` / `TelonRenderer`**: si fallaba la creación del contexto OpenGL a medias, se
  quedaba roto hasta reiniciar. Ahora `destroyContext()` lo deshace y se reintenta.
- **Fuga de memoria en `GlassFolder.Glass`** (y en el menú de atajos): el valor de un
  `WeakHashMap` retenía su propia clave. Regla: los valores de esos mapas guardan la vista con
  `WeakReference`, y los listeners usan la vista que reciben, no la capturan.
- `GlassFolder.loadWallpaper` preguntaba el fondo al sistema en cada `onLayout`: ahora cada 2 s.
- `FolderMorph.Follow` se paraba solo si se perdía el "levantar el dedo".
- `Updates`: descargas cortadas se detectan. `Migrate`: como mucho 3 intentos de root.
- `ModernEngine` reutiliza los invokers de métodos originales.

### Filtro rápido en los hooks

`XC_MethodHook.skip(thisObject)`: si devuelve true, los dos motores (`ModernEngine`,
`LegacyEngine`) llaman al método original sin crear objetos. Úsalo en todo hook de un método muy
frecuente. Ya lo llevan: `View.drawBackground` (SystemUI, `CcGlass`), `applyTransformation` del
telón, `SurfaceParams.applyTo`, `TransactionCompat.setMatrix`, el icono flotante,
`setIconVisibility`, y los hooks de `NotifGlass`.

## Funciones nuevas

### Cristal en las notificaciones — `cc/NotifGlass.java` (en pruebas, apagado de serie)

- Interruptor `kashiplus_notif_on` (Inicio → Notificaciones de cristal; se instala al arrancar
  SystemUI) y `kashiplus_notif_dark` (Oscuridad, 0,5). Ambos en `cc/Tune.java`.
- **Dónde pinta**: después del `onDraw` de `NotificationBackgroundView` (el fondo de cada
  tarjeta, por debajo del texto), un shader `GlassShader` opaco y oscurecido. La geometría sale de
  los mismos campos que usa Android: `mActualHeight`, `mClipTopAmount`, `mClipBottomAmount`,
  `mBackgroundTop`, `mCornerRadii` ([0] arriba, [4] abajo), `mExpandAnimationRunning`,
  `mActualWidth`. Si los radios de arriba y abajo difieren (grupos), pinta cada mitad con el suyo.
- **Una sola captura** del fondo, compartida con el centro de control (`CcGlass.backdropFor`,
  `markStale`, `backdropScaleNow`, `scaleOf`). Se pide otra si no se ha dibujado en 2,5 s
  (`REOPEN_MS`). Mientras llega, se ve el fondo de HyperOS y el cristal entra en 160 ms.
- **Sigue a la tarjeta** (2.12.0): un `OnPreDrawListener` por tarjeta (puesto y quitado con
  `OnAttachStateChangeListener`) repinta si la tarjeta se ha movido. Sin esto el cristal se
  quedaba quieto como un fondo al deslizar.
- **Rendimiento** (2.12.1, tras un vídeo con tirones de 280-530 ms al deslizar): un shader por
  tarjeta configurado solo cuando cambia algo (`configure`); por fotograma solo `origin`/`scale`.
  Sin arcoíris (`chroma` 0: 3 lecturas del fondo por píxel en vez de 7). Al verse del todo, se
  quita el desenfoque de HyperOS de esa tarjeta con `CcGlass.miClear`.
- **Ligero** (2.12.2, el usuario seguía viéndolo a pocos fps): ya no usa `GlassShader`. `darkFor`
  oscurece y satura la captura UNA vez (ColorMatrix) y cada tarjeta la pinta con un
  `BitmapShader` y una matriz local (1 lectura de textura por píxel), más un canto de luz con un
  degradado. Sin refracción en las notificaciones. **Pendiente de que el usuario confirme que ya
  va fluido.** Si no: dejar de seguir a la tarjeta mientras se mueve y ponerse al día al soltar.
- Historial: la 2.10.0 lo ponía solo en el borde, por encima del contenido (overlay de la fila
  `ExpandableNotificationRow`). Al usuario le pareció raro: pidió toda la tarjeta y más oscura.
- Se apaga solo tras 5 fallos seguidos. Líneas útiles del registro: «cristal en notificaciones
  activo» y «cristal en la primera notificación (…tamaño, radios…)».

### Rueda de funciones — `FeatureWheel.java` (2.11.0)

Idea del usuario: cuando haya muchas funciones, Inicio deja la rejilla por una rueda. Se probó
primero en una rama aparte; le gustó y se integró.

- Tambor vertical que da la vuelta. Cada ficha va dentro de un `FrameLayout` soporte que es lo que
  gira (`rotationX`, escala, alfa, `translationZ`, desenfoque `RenderEffect` en pasos de 1 px), así
  las animaciones propias de la ficha (`sink`, `ripple`, el círculo al encender) no se pisan.
- `STEP_DEG` 26°, tarjetas de 60 dp. `rotationX` positivo = el borde de arriba se aleja (calculado
  a partir de HWUI/Skia; el usuario no ha dicho que se vea al revés).
- Arrastre 1:1, inercia con rozamiento, muelle de encaje, tic al pasar cada ficha. Toque en el
  centro = encender/apagar; en otra = traerla al centro; girando = parar.
- Fundido arriba/abajo con máscara `DST_OUT` en un `saveLayer`, para que se vea el fondo animado
  de la app (no una franja de color).
- En `SettingsActivity.featuresWheel()`: reutiliza `featuresGrid()` para crear las fichas y el
  cuadro de explicación; debajo se lee siempre qué hace la ficha del centro.

### Pausa automática — `AutoPause.java` (2.11.0)

Se pausa como con el tile cuando: hace mucho calor (`THERMAL_STATUS_SEVERE`), Game Turbo está en
marcha (`Settings.Secure` `gb_boosting` = 1; **no confirmado en HyperOS 3**) o, si se activa, con
ahorro de batería. Ajustes `icehome_auto_heat`, `icehome_auto_game`, `icehome_auto_saver` en
`Tune.ITEMS` (sección «Pausa automática», que se muestra en la pestaña Sistema). Se instala desde
`Remote.registerLauncher/registerSystemUi` y relee los ajustes al cambiar.

### Lo de la 2.12.0

- **`cc/PanelFocus.java`** — enfoque al cambiar entre notificaciones y centro de control (petición:
  «la animación de ColorOS 17»; no había referencia exacta, es una interpretación). Va atado al
  `blurRatio` de `ControlCenterWindowViewController.setBlurRatio` (en `CcGlass.hookOpen`): el CC
  entra un 7 % más grande y desenfocado; `NotificationStackScrollLayout` se encoge un 5 % y se
  desenfoca. Ajuste `kashiplus_cc_focus` (1). Si al usuario no le convence, pedirle un vídeo de
  ColorOS.
- **`Presets.GLASS`** (Líquido, Transparente, Ahumado, Esmerilado, Prisma; en Sistema) y
  **`Presets.MODES`** (HyperOS 4, iOS 26, iOS 27; en Inicio). Cada perfil puede llevar un sexto
  elemento con las claves que gestiona (`Presets.keys`). Falta «Tinte Monet» (necesita tocar el
  shader).
- **`GlassDock.java`** (en pruebas, `icehome_dock_glass_on`): cristal de carpetas como fondo de
  `com.miui.home.launcher.hotseats.HotSeats` — **nombre de clase supuesto, sin confirmar**.
- **`UnlockZoom.java`** (`icehome_unlock_zoom`, 0 de serie): escala el contenido del launcher al
  recibir `ACTION_USER_PRESENT`.
- **Resolución automática** (`icehome_genie_auto`): `Genie.autoQuality` baja `wantedScale` un
  10 % tras 3 animaciones seguidas con más del 20 % de tirones.
- **`Health.java`** — «¿Cómo va mi móvil?» (Inicio → Acciones): lee las líneas «genio … N
  fotogramas en M ms (J tirones» del registro y da una nota.
- La lista de **apps sin genio** vale también para el telón (`Telon.exclude`, por la tarea de cada
  cambio de la transición).
- Tic al cambiar de tamaño una carpeta (`FolderMorph`, launcher de Kashi).

## Pendiente

1. **Confirmar con el usuario** que la 2.12.2 ya va fluida en el panel de notificaciones.
   Decisiones del 17/11: **no** se harán carpetas con muelle ni pila de widgets para el launcher de
   serie (la idea es que la gente use el de Kashi); lo siguiente es mejorar la animación del
   launcher (abrir y cerrar apps).
2. **Vídeos del README**: el usuario pidió cambiarlos y mandó un solo vídeo (10 s del panel de
   notificaciones). No se hizo porque: no estaba claro qué vídeo sustituye; salían datos
   personales (sus notificaciones de WhatsApp y noticias, y la operadora); y se veían los tirones.
   Se le propuso regrabar con la 2.12.1, notificaciones neutras y en modo avión. Además,
   `README.md` usa `docs/media/demo-sin-wifi.mp4` dentro de un `<img>`, que GitHub no muestra.
3. Ver correr el CI por primera vez.
4. Recarga en caliente (libxposed 102), según `CLAUDE.md`.
5. Probar en el móvil lo que no se ha visto: dock de cristal, enfoque al cambiar de página, zoom
   al desbloquear, pausa en juegos, modos y estilos de cristal, rueda.
6. Publicar una release cuando el usuario lo decida (la última publicada es la 2.9.1).
