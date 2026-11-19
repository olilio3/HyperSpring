# HyperSpring: hoja de ruta

Lo previsto para las próximas versiones. Los planes pueden cambiar. Las sugerencias son bienvenidas en [Issues](https://github.com/olilio3/HyperSpring/issues).

Criterio para entrar aquí: que lo vaya a usar bastante gente, que se pueda mantener cuando cambie HyperOS y que no cueste batería ni fluidez a quien no lo usa. Un efecto llamativo que se desactiva a los dos días no entra. Antes de empezar algo del escritorio, mirar si ya lo traen el Launcher Mod de Kashi o HyperCeiler.

¿Has encontrado un error? Usa **Acerca de → Informar de un error**: copia el registro y abre el issue con tus datos ya puestos.

## Siguiente
- [ ] **Publicar lo acumulado.** Desde la 2.9.1 no hay release: la 2.9.2 a la 2.12.x solo están en la rama. Antes, quitar del CHANGELOG lo que ya no existe (notificaciones de cristal, enfoque al cambiar de página).
- [ ] **Abrir y cerrar apps mejor que nunca:** que parezca hecha por la propia Xiaomi.
  - **Sin saltos bruscos:** la captura tiene que estar lista antes de que la ventana se mueva. *Desde la 2.12.x la primera captura se prepara al arrancar el launcher (de 354 ms a 9 ms el primer cierre).*
  - **Como de serie:** mismos muelles, tiempos, esquinas y sombras que HyperOS. Necesita que el usuario lo compare a ojo.
  - **Un espectáculo:** más física y más luz en Profundidad, sin perder fluidez en gama media.
- [ ] **Dock de cristal fuera de pruebas:** está en beta desde la 2.12.0.
- [ ] **Recarga en caliente (libxposed API 102):** que una actualización del módulo y los interruptores se apliquen sin reiniciar el launcher ni la interfaz del sistema.
  - Falta que el módulo sepa "desmontarse": parar sus hilos, quitar sus listeners y volver a instalar los hooks con el código nuevo.
  - Es lo más delicado de la lista: si se deja algo a medias, se cae la interfaz. Saldrá primero en una beta.
- [x] **Soporte de POCO Launcher**, integrando el trabajo del fork de la comunidad ([ToolDroidYT/hyperspring](https://github.com/ToolDroidYT/hyperspring)). *Publicado en la 3.0.0.*

## Planeado
Sin empezar y sin orden fijo.

- [ ] **Brillo que sigue al dedo en el centro de control:** el reflejo del cristal se mueve hacia donde tocas, como ya hacen las carpetas (**Brillo al tocar**). Hubo una versión de prueba el 16-11-2026 que no llegó a guardarse en ninguna rama: hay que rehacerla.
- [ ] **Genio desde las notificaciones:** al abrir una notificación, la app sale de la tarjeta. Primero, mirar qué hace ya el telón en esa transición.
- [ ] **Genio hacia la carpeta:** al cerrar una app que está dentro de una carpeta, la ventana vuelve a la carpeta.
- [ ] **Genio en los widgets:** al abrir una app desde un widget, la ventana sale del widget.
- [ ] **Gesto de atrás predictivo con físicas:** mientras se arrastra, la pantalla se encoge y se inclina siguiendo el dedo (con la física del telón); al soltar, vuelve con muelle o se cierra. En el Redmi 14C HyperOS no trae animaciones de atrás predictivas, así que hay que crearlas.
- [ ] **Iconos que se apartan con muelle** al arrastrar uno por el escritorio, en vez de deslizarse en línea recta.
- [ ] **Tic al cambiar la pila de widgets**, como el que ya tienen el genio al aterrizar y las carpetas al cambiar de tamaño.

## Descartado
Ideas que estaban en la lista y se han quitado, con el motivo:
- **Notificaciones de cristal** (quitadas el 17-11-2026). La gente prefiere el desenfoque propio de HyperOS, y pintar cada tarjeta daba tirones al deslizar en gama media.
- **Notificaciones con muelle** (18-11-2026). Mismo motivo: las notificaciones se dejan como las hace HyperOS.
- **Enfoque o rebote al cambiar entre notificaciones y centro de control** (17-11-2026). El avance de apertura salta de golpe (de 0 a 0,52) al cambiar de página: no hay una señal fiable para sincronizar nada encima.
- **Lupa en los sliders del centro de control** (18-11-2026). Depende de clases del plugin de SystemUI que cambian en cada versión, igual que el efecto goma.
- **Efecto goma en los sliders de volumen y brillo.** Mismo motivo: mucho mantenimiento para un detalle.
- **Modo oscuro con onda circular** y **páginas del escritorio que estiran los iconos** (18-11-2026). Efectos llamativos que se apagan a los pocos días; el segundo además choca con las transiciones del launcher de Kashi.
- **Interfaz de la app renovada** (18-11-2026). El diseño actual se queda; las entradas, el atrás predictivo de la app y los fondos animados ya están.
- **Vista previa dentro de la app.** Obliga a duplicar las animaciones fuera del launcher; con los perfiles y los cambios en vivo, aporta poco.
- **Pilas de widgets y carpetas en el launcher de serie.** Siguen solo en el launcher de Kashi, que es donde existen las pilas y los tamaños de carpeta.
- **Rebote elástico en las listas de todas las apps.** Habría que engancharse al framework de Android en cada app: mucho riesgo para un efecto pequeño.
- **Paralaje del fondo con el giroscopio.** Mantiene un sensor encendido en el escritorio: gasta batería a cambio de un adorno.
- **Onda en el fondo al tocar** y **entrada en cascada al desbloquear.** Vistosos, pero cansan pronto y cargan la GPU justo cuando el móvil ya va cargado. En su lugar está el zoom suave al desbloquear.
- **Más estilos de recientes (abanico, pila 3D).** Lo que pedía la gente era poder apagar el doblado, y eso ya está.

## Hecho
- [x] 2.9.2 a 2.12.x (sin publicar todavía): revisión de todo el código, compilación automática en GitHub, rueda de funciones, pausa automática (calor, juegos, ahorro), estilos de cristal, modos de sistema (HyperOS 4, iOS 26, iOS 27), dock de cristal (beta), zoom al desbloquear, resolución automática del genio, «¿Cómo va mi móvil?», refracción en las carpetas y la intro de la app. Detalles en el CHANGELOG.
- [x] 2.7.0: menú de atajos con muelle y borde de cristal líquido, tile para pausar HyperSpring, velocidad de las animaciones de Android y códigos `HS1:` para compartir los ajustes.
- [x] 2.2.0: la bienvenida ya no mete el texto bajo el icono y el icono de Acerca de no se corta.
- [x] 2.1.0: estilo Profundidad, editor visual del muelle, apps sin genio, actualizaciones y novedades desde la app, y app rediseñada.
- [x] 2.0.0: libxposed API 102 con dos motores (el antiguo, API 93, como APK aparte), perfiles de un toque, exportar e importar ajustes.
- [x] 1.0.3: genio más fluido y más parecido al de HyperOS.
- [x] 1.0.2: el cristal del centro de control dejaba de funcionar al cabo de un rato; las tarjetas de recientes solo se doblan al moverlas con el dedo.
- [x] 1.0.0: el APK se compilaba con targetSdk 30 y Android 16 no dejaba instalarlo.
