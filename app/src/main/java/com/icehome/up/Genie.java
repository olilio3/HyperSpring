package com.icehome.up;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.SurfaceControl;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Efecto genio al abrir y cerrar apps; la animación de MIUI no llega a verse.
 *
 * El sistema solo deja mover, escalar y recortar la ventana de la app, no deformarla. Así que
 * durante todo el efecto se dibuja una capa propia (hija de la ventana del launcher) con el
 * contenido deformado por {@link GlRenderer}, y la ventana real se oculta.
 *
 * <ul>
 * <li><b>Abrir</b>: desde el primer fotograma. Hasta que llega la primera captura de la ventana (o
 *     si aún no existe, en el arranque rápido) el contenido es el color de la app con su icono.</li>
 * <li><b>Con ventana</b>: el contenido se captura en un hilo aparte, sin frenar el dibujo; cada
 *     fotograma usa la última captura. La capa se pone encima de la ventana y el cambio se hace en
 *     la transacción de MIUI que mueve la ventana ({@link #onApply}).</li>
 * <li><b>Arrastrar para cerrar</b>: el extremo cercano es el dedo; al soltar, se desliza al icono.</li>
 * </ul>
 *
 * La capa, su contexto de dibujo y el shader se crean una vez y se calientan al volver al
 * escritorio ({@link #prewarm}): en un móvil modesto crearlos cuesta más que la animación entera.
 *
 * Hilos: animación de MIUI → {@link #frame}; "IceHome-genie" → dibujo; "IceHome-capture" →
 * capturas; aplicador de MIUI → {@link #onApply}.
 */
final class Genie {

    /** Capa relativa a la ventana: por encima de ella (0) y por debajo del icono flotante (3). */
    private static final int Z = 2;
    /** Sin ventana aún: por encima de todo lo que dibuja el launcher. */
    private static final int Z_TOP = 1_000_000;
    /** Forma del genio por unidad de intensidad (ajustada contra el vídeo de referencia). */
    private static final float LEAD = 0.35f, LAG = 0.6f;
    /** Al cerrar, el extremo lejano se adelanta menos en vertical: la copa encoge proporcionada. */
    private static final float CLOSE_RISE = 0.35f;
    /** Al cerrar, tramo de anchura (0 = icono) en el que la copa se funde con el icono de MIUI. */
    private static final float ICON_FADE_START = 0.22f, ICON_FADE_END = 0.06f;
    /** Al arrastrar, el genio entra progresivamente para no saltar desde la ventana normal. */
    private static final float RAMP_IN_S = 0.08f;
    /** Al abrir, fundido de entrada del genio. */
    private static final float OPEN_FADE_S = 0.06f;
    /** Si el genio aparece con la ventana ya más grande que esto, no se cruza con el icono de MIUI. */
    private static final float EARLY_PW = 0.35f;
    /**
     * Tramo de anchura (0 = icono, 1 = pantalla) en el que el genio pasa de icono a app, o al
     * revés. Medido en la animación de serie de MIUI (HyperOS 3, a cámara lenta):
     * <ul>
     * <li>Al abrir, el icono desaparece enseguida: con la ventana a ~1,3 veces el icono ya solo
     *     se ve el color de la app.</li>
     * <li>Al cerrar, el icono toma el relevo pronto, con la ventana aún a ~2 veces el icono, y lo
     *     que queda del viaje es el icono encogiendo. Con el relevo al final (como antes, de 0,2 a
     *     0,03) la app oscura se mezclaba con el icono y se veía un cuadrado oscuro.</li>
     * </ul>
     */
    private static final float OPEN_MIX_START = 0.12f, OPEN_MIX_END = 0.02f;
    private static final float CLOSE_MIX_START = 0.3f, CLOSE_MIX_END = 0.13f;
    /** Lado del icono como textura (potencia de 2, con mipmaps). */
    private static final int ICON_TEXTURE = 256;          // el icono en pantalla mide ~150 px: a 128 se veía borroso
    /** Al interrumpir, la forma de abrir y la de cerrar se funden en este tiempo. */
    private static final float DIR_BLEND_S = 0.15f;
    /** Al soltar el dedo, el extremo cercano pasa del dedo al icono en este tiempo. */
    private static final float ICON_BLEND_S = 0.12f;
    /** Ancho del "icono" virtual bajo el dedo, respecto a la pantalla. */
    private static final float FINGER_ANCHOR = 0.2f;
    /** Tras el fin de una animación, margen por si MIUI encadena otra con el mismo animador. */
    private static final long GRACE_MS = 40;
    /** Un animador solo releva al del genio si este lleva este tiempo sin fotogramas. */
    private static final long HANDOVER_MS = 30;
    /** Sin fotogramas durante este tiempo, se da la animación por terminada. */
    private static final long WATCHDOG_MS = 300;
    /** Lo mismo con el dedo en la pantalla (puede estar quieto). */
    private static final long HOLD_MS = 5000;
    /** Tras tantos fallos seguidos al preparar o dibujar, el genio se aparta un rato. */
    private static final int MAX_FAILURES = 3;
    private static final long PAUSE_MS = 5000;
    /** Una ventana sustituida por otra animación no vuelve a llevar genio en este tiempo. */
    private static final long RETIRED_MS = 1000;
    /**
     * Físicas de lámpara: el extremo lejano cuelga del cercano con un muelle (rigidez en 1/s² y
     * amortiguación relativa; < 1 = se balancea un poco antes de quedarse quieto).
     */
    private static final float LAMP_K = 170f, LAMP_ZETA = 0.5f;
    /** Inclinación y estiramiento máximos de la lámpara, respecto al tamaño de la pantalla. */
    private static final float LAMP_MAX_BEND = 0.22f, LAMP_MAX_LIFT = 0.12f;
    /** Al soltar, las físicas se apagan en este tiempo mientras la copa vuela al icono. */
    private static final float LAMP_FADE_S = 0.35f;
    /**
     * Dos iconos cuyos centros están a menos de esta fracción del ancho del icono son el mismo
     * (reabrir la misma app): MIUI da rectángulos algo distintos al cerrar y al abrir.
     */
    private static final float SAME_ICON = 0.5f;
    // Icono de MIUI y genio a la vez al empezar: dos refrescos (Session.vsync).
    /** Al cerrar, con la ventana por encima de esta anchura (progreso) el icono flotante no se ve. */
    private static final float CLOSE_ICON_HIDE_PW = 0.3f;
    /**
     * Altura del icono (fracción de la pantalla) entre la que la copa pasa de estrecharse hacia
     * abajo a estrecharse hacia arriba; en medio, mezcla (sin saltos si el icono cambia).
     */
    private static final float FLIP_LO = 0.40f, FLIP_HI = 0.60f;
    /** Constante de tiempo del cambio de sentido de la copa (al soltar hacia un icono de arriba). */
    private static final float FLIP_S = 0.12f;
    /** Fundido de la pantalla provisional a la primera captura de la app. */
    private static final float CONTENT_FADE_S = 0.14f;
    /** Del color de fondo provisional al de la app, cuando MIUI lo da. */
    private static final float BG_BLEND_S = 0.1f;
    /** Por debajo de esta anchura (progreso) el genio ya ha llegado al icono: vibración. */
    private static final float LAND_PW = 0.03f;
    // Tirón: un fotograma que tarda más de 1,5 veces el ritmo de dibujo (Session.jankNs).
    /** Separación mínima entre capturas: cada una carga al compositor del sistema. */
    private static final long CAPTURE_GAP_MS = 30;
    /**
     * Además, entre captura y captura el compositor queda libre al menos este múltiplo de lo que
     * tardó la última: en un móvil modesto una captura cuesta 25-80 ms y, seguidas, dan tirones.
     */
    private static final float CAPTURE_IDLE = 1.5f;
    /** Primera captura al cerrar, más pequeña para que llegue antes (luego vienen las buenas). */
    private static final float FIRST_CLOSE_SCALE = 0.35f;
    // Listo: 1,25 refrescos tras el primer dibujo, para que el compositor ya lo tenga (Session.vsync).
    /**
     * Presupuesto de capturas (cada una ocupa al compositor 25-80 ms en un móvil modesto y es lo
     * que daba tirones): al abrir "en vivo", como mucho una cada {@link #OPEN_LIVE_MS}; al cerrar
     * el contenido ya no cambia y basta con una (ver {@link Session#captureDue}).
     * Sin "en vivo", al abrir solo las primeras (y otra si la ventana cambia de superficie).
     */
    private static final long OPEN_LIVE_MS = 200;
    /** Sin "en vivo", al abrir: la primera captura y otra a esta distancia (la app ya se ha pintado). */
    private static final long OPEN_SECOND_MS = 300;
    /**
     * Resolución máxima de las capturas: el coste para el compositor crece con los píxeles y el
     * contenido va en movimiento; los bordes del genio salen a la resolución del ajuste.
     */
    private static final float CAPTURE_MAX_SCALE = 0.5f;
    /**
     * Genio suelto (la app que se cierra cuando se abre otra): MIUI abandona su animación y el
     * genio sigue solo hasta el icono con un muelle propio, del estilo del de cerrar de MIUI.
     */
    private static final float GHOST_K = 300f, GHOST_ZETA = 0.92f;
    private static final long GHOST_MAX_NS = 1_500_000_000L;
    /** Al acabar, el icono real aparece y la capa se va un poco después (mejor solapar que un hueco). */
    private static final long GHOST_HANDOFF_MS = 40;
    /** Un genio que dibujó hace menos de esto ya pinta el suelto en su fotograma. */
    private static final long SHARED_FRAME_NS = 20_000_000L;

    static volatile boolean enabled;
    static volatile float strength = 2.2f;
    /**
     * Estilo de la animación de abrir y cerrar apps (ver los STYLE_ de abajo).
     */
    static volatile int style = 0;   // STYLE_CLASSIC (por defecto)
    /**
     * Estilos: genio clásico con cola y Profundidad (sin genio). Los números no cambian para que
     * sigan valiendo los ajustes guardados y los códigos compartidos; los de los estilos quitados
     * se leen como iOS (ver {@link #migrate}).
     */
    static final int STYLE_CLASSIC = 0, STYLE_DEPTH = 3;
    /**
     * iOS 27 (3.4.0), copia del boceto aprobado (con los ajustes del usuario: deformación 100 %,
     * rebote 4 %, muelle de 0,26 s, sin cristal lechoso). La ventana, opaca, sigue al rectángulo de
     * MIUI, al que se le pone el muelle del boceto ({@link Springs}; con Velocidad y Rebote encima,
     * y el dedo al cerrar). Cada esquina va con su propio muelle ({@link Session#iosStep}): al
     * abrir se retrasan las cercanas al icono (ese lado sale más estrecho, un genio suave); al
     * cerrar, algo las lejanas. En la zona del centro, recta (dibujo del usuario, ver
     * {@link #flipFor}). Sombra suave alrededor; el desenfoque del escritorio lo pone HyperOS con
     * «texturas avanzadas». ex/ex2 = sus 4 esquinas; cup 3.
     */
    static final int STYLE_IOS = 10;
    /**
     * HyperOS (3.1.2): la animación del sistema de verdad, dibujada por MIUI (no por nosotros): su
     * ventana en vivo, su icono, su radio y sus transparencias. Solo se cambian sus muelles
     * (velocidad y rebote, ver {@link Springs}). Sin dibujo propio no hay desenfoque de movimiento.
     */
    static final int STYLE_HYPEROS = 8;

    /**
     * Estilo guardado al que se usa: los quitados (Copa 1, Ice 2, el 4, Abanico 5, Pergamino 6,
     * Carta 7 e Iris 9) pasan a iOS, el más parecido a todos ellos (sale del icono sin cola).
     */
    static int migrate(int stored) {
        return stored == STYLE_CLASSIC || stored == STYLE_DEPTH || stored == STYLE_HYPEROS ? stored : STYLE_IOS;
    }

    /** Hay genio que dibujar: activado y con un estilo que no sea el nativo de HyperOS. */
    static boolean draws() {
        return enabled && style != STYLE_HYPEROS;
    }
    /**
     * Profundidad: sin genio. La ventana sigue el rectángulo de MIUI (su muelle de siempre, con
     * Velocidad y Rebote) y es una lámina rígida vista en perspectiva:
     * <ul>
     * <li><b>Sale del icono inclinada:</b> arriba al fondo y abajo hacia ti, así que se ve como un
     * trapecio (arriba más estrecha). La inclinación depende solo de cuánto ha crecido la ventana
     * ({@link #DEPTH_RISE}, {@link #DEPTH_SETTLE}): nada más salir del icono ya es un trapecio
     * pequeño, sigue inclinada casi hasta llenar la pantalla y se endereza en el último tramo.
     * Al cerrar es exactamente lo mismo al revés.</li>
     * <li>Gira sobre su borde de abajo: abajo mide siempre lo que el rectángulo de MIUI, así que
     * nunca se sale de la pantalla; arriba se aleja.</li>
     * <li><b>Desenfoque de movimiento:</b> como una cámara con el obturador abierto
     * {@link #DEPTH_SHUTTER_S}: cada punto se difumina en la dirección en la que va.</li>
     * <li>El escritorio se oscurece un poco detrás, para dar fondo.</li>
     * </ul>
     * Lo regulan {@link #depthTaper} (cuánto más estrecha arriba) y {@link #depthBlur} (la estela).
     */
    /**
     * iOS: retraso de cada esquina, constante de su muelle en s: IOS_T0 + IOS_T × intensidad/2,2 ×
     * retraso (0-1). Con la intensidad de serie es la deformación al 100 % del boceto.
     */
    private static final float IOS_T0 = 0.02f, IOS_T = 0.20f;
    /** iOS: muelle de la ventana del boceto (periodo en s y amortiguación: rebote del 4 %). */
    static final float IOS_RESPONSE = 0.26f, IOS_DAMPING = 0.96f;
    /** iOS: avance por debajo del cual se ve el icono (se va en seguida al abrir, vuelve al final al cerrar). */
    private static final float IOS_ICON = 0.10f;
    /** iOS: sombra alrededor de la ventana: fuerza y largo (fracción del ancho de la pantalla). */
    private static final float IOS_SHADOW = 0.3f, IOS_SHADOW_LEN = 0.061f;
    /**
     * iOS: zonas del icono (altura en la pantalla): por encima de IOS_TOP y por debajo de
     * IOS_BOTTOM, genio suave; en medio, muy sutil (IOS_CENTER de la deformación). Cada frontera se
     * funde en IOS_BAND para que no salte.
     */
    private static final float IOS_TOP = 0.33f, IOS_BOTTOM = 0.67f, IOS_BAND = 0.08f, IOS_CENTER = 0.25f;
    /** Avance (anchura, 0 = icono) en el que la inclinación llega a tope al salir del icono. */
    private static final float DEPTH_RISE = 0.06f;
    /** Desde este avance la hoja se endereza, y llega recta a pantalla completa. */
    private static final float DEPTH_SETTLE = 0.8f;
    /** Cuánto más estrecha se ve arriba que abajo (0-0,6) y fuerza de la estela (0-2). Ajustes. */
    static volatile float depthTaper = 0.3f, depthBlur = 0.3f;
    /** Obturador del desenfoque, en segundos (~3/4 de un fotograma a 60 Hz). */
    private static final float DEPTH_SHUTTER_S = 0.0125f;
    /** Estela máxima, en fracción de la altura de pantalla: con tirones no se emborrona de más. */
    private static final float DEPTH_MAX_BLUR = 0.09f;
    /** Profundidad: oscurecido del escritorio, muy suave (el desenfoque del fondo ya lo pone HyperOS). */
    private static final float DEPTH_DIM = 0.06f;
    /**
     * Sin "en vivo", al abrir: capturas a esta distancia hasta tener {@link #OPEN_EARLY_SHOTS}
     * (la primera suele llegar antes de que la app pinte nada: sin esto se veía blanca ~300 ms).
     */
    private static final long OPEN_EARLY_MS = 100;
    private static final int OPEN_EARLY_SHOTS = 3;
    /** Por debajo de esta apertura el genio se va convirtiendo en el rectángulo de MIUI (el icono). */
    private static final float CUP_FADE = 0.12f;
    static volatile boolean live = true;
    /** Resolución automática: baja sola si varias animaciones seguidas van a tirones. Ajuste. */
    static volatile boolean autoQuality = true;
    /** Animación paralela ligera: 0 no, 1 auto (si la última animación fue a tirones), 2 siempre. */
    static volatile int lightParallel = 1;
    private static volatile boolean slow;

    static boolean lightNow() {
        return lightParallel >= 2 || (lightParallel == 1 && slow);
    }
    /** Animaciones seguidas con más de {@link #AUTO_JANK} de sus fotogramas tarde. */
    private static int jankyRuns;
    private static final float AUTO_JANK = 0.2f, AUTO_STEP = 0.1f, AUTO_MIN = 0.5f;
    private static final int AUTO_RUNS = 3;

    /** Hilo de dibujo, al acabar cada animación: {@code janks} tirones en {@code frames} fotogramas. */
    private static void autoQuality(int janks, int frames) {
        if (frames < 10) return;                           // muy corta: no dice nada
        slow = janks / (float) frames > AUTO_JANK;        // para la animación paralela ligera (auto)
        if (janks / (float) frames <= AUTO_JANK) {
            jankyRuns = 0;
            return;
        }
        if (++jankyRuns < AUTO_RUNS || !autoQuality) return;
        jankyRuns = 0;
        float now = GlRenderer.wantedScale;
        if (now <= AUTO_MIN + 0.001f) return;
        GlRenderer.wantedScale = Math.max(AUTO_MIN, now - AUTO_STEP);
        Log.i("genio: resolución automática, baja al " + Math.round(GlRenderer.wantedScale * 100)
                + " % (" + AUTO_RUNS + " animaciones seguidas a tirones)");
    }
    /**
     * Fotogramas por segundo máximos del genio. Se dibuja al ritmo de los refrescos de la
     * pantalla, como mucho a este: con pantalla a 90-120 Hz y una GPU modesta, dibujar a cada
     * fotograma de MIUI es más trabajo sin más fluidez.
     */
    static volatile float maxFps = 60f;
    /** Margen al comparar con el periodo (los refrescos no llegan exactos). */
    private static final long PACE_SLACK_NS = 2_500_000L;
    /**
     * Predicción entre muestras de MIUI: como mucho este número de refrescos por delante de la
     * última (cubre los huecos medidos, de 1-3 refrescos, sin inventar si MIUI se para de verdad).
     */
    private static final float MAX_AHEAD_PERIODS = 2f;
    /** Sin muestras de MIUI durante estos refrescos, el bucle de dibujo se para hasta la siguiente. */
    private static final int KEEP_TICKING_PERIODS = 4;

    private static long periodNs() {
        return (long) (1e9 / Math.max(15f, maxFps)) - PACE_SLACK_NS;
    }

    /** Intensidad de las físicas de lámpara (0 = sin balanceo). */
    static volatile float physics = 1f;
    /** Escala de tiempo del muelle de MIUI (escala de animación × 1/Velocidad); la pone {@link Springs}. Los fundidos propios la siguen. */
    static volatile float timeScale = 1f;
    /** Superficie del inicio que MIUI escala ({@code HomeLayerElement}): antecesora de la capa. */
    static volatile SurfaceControl homeLeash;

    private static Handler renderThread, captureThread;
    private static Object retiredKey;
    private static int failures;
    private static long pausedUntilMs;
    private static int retiredTask = -1;
    private static long retiredMs;
    private static volatile Session session, ending;
    /** Genio suelto (solo hilo de dibujo, salvo lecturas). */
    private static volatile Ghost ghost;
    /** Ventana que se abre sin genio: el genio suelto va justo por debajo de ella. */
    private static volatile SurfaceControl ghostBelow;

    /** Mostrar u ocultar el icono real del escritorio (lo pone WindowAnim; hilo principal). */
    interface Icons {
        void setVisible(View icon, boolean visible);
    }

    static volatile Icons icons;

    // ---------------------------------------------------------------- precarga
    /** Una captura o un icono preparados de antemano caducan a este tiempo. */
    private static final long PRELOAD_MS = 3000;
    /** Captura de la app tomada al empezar el gesto de cerrar (antes del primer fotograma). */
    private static final Object PRE = new Object();
    private static Hidden.Shot preShot;
    private static int preTask = -1;
    private static long preShotMs;
    /** Icono subido a la GPU al tocarlo (antes de soltar el dedo y de que empiece la apertura). */
    private static final Object PREPARED_ICON = new Object();
    private static final RectF preparedRect = new RectF();
    private static volatile long preparedMs;
    /**
     * Caché de la última imagen de cada app (la captura al empezar el gesto de cerrarla). Al
     * volver a abrirla, si su tarea sigue en recientes, Android enseña al abrir esa misma imagen
     * (una "instantánea" de la tarea): el genio sale ya con ella en vez de con el color y el
     * icono. Pocas entradas: cada una ocupa ~1 MB de memoria de la GPU.
     */
    private static final int SNAPSHOTS = 4;
    private static final java.util.LinkedHashMap<String, Snap> snaps = new java.util.LinkedHashMap<>(8, 0.75f, true);
    /** Contenido precargado al tocar un icono (instantánea de su app). */
    private static final Object PREPARED_CONTENT = new Object();
    private static volatile String preparedPkg;

    private static final class Snap {
        final int task;
        final Hidden.Shot shot;

        Snap(int task, Hidden.Shot shot) {
            this.task = task;
            this.shot = shot;
        }
    }

    /** Hilo de capturas: guarda la imagen de una app (y suelta la más antigua si sobran). */
    private static void remember(String pkg, int task, Hidden.Shot shot) {
        synchronized (snaps) {
            Snap old = snaps.put(pkg, new Snap(task, shot));
            if (old != null && old.shot.buffer != shot.buffer) old.shot.buffer.close();
            java.util.Iterator<java.util.Map.Entry<String, Snap>> it = snaps.entrySet().iterator();
            while (snaps.size() > SNAPSHOTS && it.hasNext()) {
                it.next().getValue().shot.buffer.close();
                it.remove();
            }
        }
    }

    /** Una captura ya usada: se suelta, salvo que la guarde la caché. */
    private static void release(Hidden.Shot shot) {
        synchronized (snaps) {
            for (Snap s : snaps.values()) {
                if (s.shot.buffer == shot.buffer) return;
            }
        }
        shot.buffer.close();
    }

    /** La tarea sigue en recientes (al abrirla, Android enseñará su instantánea). */
    private static boolean taskAlive(int taskId) {
        android.content.Context c = TapCatcher.context;
        if (c == null || taskId == -1) return false;
        try {
            android.app.ActivityManager am = c.getSystemService(android.app.ActivityManager.class);
            @SuppressWarnings("deprecation")
            java.util.List<android.app.ActivityManager.RecentTaskInfo> tasks =
                    am.getRecentTasks(16, android.app.ActivityManager.RECENT_IGNORE_UNAVAILABLE);
            for (android.app.ActivityManager.RecentTaskInfo t : tasks) {
                if (t.taskId == taskId) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Superficie del launcher y tamaño de pantalla de la última preparación (para precargar). */
    private static volatile SurfaceControl lastRoot;
    private static volatile int lastW, lastH;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Un fotograma, rellenado por {@link WindowAnim} en el hilo de animación (se reutiliza). */
    static final class Frame {
        Object key;
        boolean open;
        /** Ventana de la app; null en el arranque rápido, antes de que exista. */
        SurfaceControl leash;
        /** Tarea de la ventana (-1 si aún no hay). */
        int taskId = -1;
        int originX, originY;
        final RectF cur = new RectF(), icon = new RectF(), full = new RectF();
        /** false al arrastrar: el extremo cercano sigue al dedo. */
        boolean hasIcon;
        float radius;
        // Solo al empezar una sesión:
        SurfaceControl root;
        SurfaceControl[] exclude;
        int bg;
        Drawable iconDrawable;
        /** Icono del escritorio al que vuelve la app (al cerrar), si MIUI lo sabe. */
        View targetView;
    }

    // ------------------------------------------------------------------ entrada desde los hooks

    /** Hay sesión de genio (aunque esté terminando). Para los filtros rápidos de los hooks. */
    static boolean hasSession() {
        return session != null;
    }

    /** Hay un genio suelto volviendo a su icono. Para los filtros rápidos de los hooks. */
    static boolean hasGhost() {
        return ghost != null;
    }

    /** Hay un genio en marcha (de cualquier animador). */
    static boolean active() {
        Session s = session;
        return s != null && !s.finishing;
    }

    /** Hay genio en marcha para este animador (en cualquier sentido). */
    static boolean running(Object key) {
        Session s = session;
        return s != null && s.key == key;
    }

    static synchronized void frame(Frame f) {
        long nowMs = SystemClock.uptimeMillis();
        // Una ventana cuya animación ya sustituyó otra más reciente se deja a MIUI un momento.
        if (nowMs < pausedUntilMs) return;                 // fallos seguidos: pausa (ver fail)
        if (f.key == handedKey) {
            // La cola del muelle la dibuja MIUI. Al cerrar, o pasado un rato (el animador puede
            // reutilizarse para la próxima app), vuelve el genio.
            if (f.open && nowMs - handedMs < 3000) return;
            handedKey = null;
        }
        if (nowMs - retiredMs < RETIRED_MS && (f.key == retiredKey || (f.taskId != -1 && f.taskId == retiredTask))) {
            return;
        }
        Session s = session;
        if (s != null && s.finishing) {
            stop("reinicio tras un fallo");                // no seguir alimentando una sesión muerta
            s = null;
        }
        if (s != null && s.taskId != -1 && f.taskId != -1 && s.taskId != f.taskId) {
            // Animaciones en paralelo de dos ventanas: gana la más reciente. Al animador antiguo
            // solo se le aparta si no es el mismo que anima la ventana nueva.
            retiredKey = s.key != f.key ? s.key : null;
            retiredTask = s.taskId;
            retiredMs = nowMs;
            stop("otra ventana");
            s = null;
        }
        if (s != null && s.key != f.key && f.open && !s.open && s.sameApp(f)) {
            // Se toca el icono mientras la app se cierra: la nueva apertura manda ya, sin esperar
            // a que acabe el cierre. El genio da la vuelta desde donde esté.
            retiredKey = s.key;
            retiredTask = -1;
            retiredMs = nowMs;
            s.keyEnded = false;
            s.handedOver = true;
            s.key = f.key;
            Log.i("genio: se reabre la app durante el cierre");
        } else if (s != null && s.key != f.key && f.open && !s.open) {
            // Otra app mientras se cierra la anterior: la nueva empieza su propio genio.
            Log.i("genio: otra app (tarea " + f.taskId + " frente a " + s.taskId + ", icono "
                    + (f.hasIcon ? f.icon.toShortString() : "—") + " frente a " + s.goalString() + ")");
            retiredKey = s.key;
            retiredTask = s.taskId;
            retiredMs = nowMs;
            // MIUI abandona el cierre: si el genio ya tiene contenido, sigue solo hasta el icono
            // mientras la otra se abre (las dos animaciones a la vez).
            boolean alone = s.canGoAlone();
            s.ghosted = alone;
            ghostBelow = null;                             // la nueva lleva genio: la capa es de las dos
            stop(alone ? "sigue sola mientras se abre otra app" : "se abre otra app");
            s = null;
        }
        if (s != null && s.key != f.key) {
            // Otro animador: en el arranque rápido MIUI pasa la animación de uno a otro cuando
            // llega la ventana, y al interrumpir puede seguir otro. El genio sigue igual, sin
            // cortes. Si el anterior sigue mandando fotogramas, manda él.
            if (!s.keyEnded && SystemClock.uptimeMillis() - s.lastFrameMs < HANDOVER_MS) return;
            s.keyEnded = false;
            if (!s.handedOver) Log.i("genio: relevo de animador");
            s.handedOver = true;
            s.key = f.key;
        }
        if (s == null) {
            if (f.root == null || !Hidden.ok()) return;
            s = new Session(f);
            session = s;
        }
        s.post(f);
    }

    /**
     * Se abre otra app con una animación que no lleva genio (desde un widget, p. ej.) mientras
     * una se cierra: la que se cierra sigue sola hasta su icono, por debajo de la ventana nueva.
     */
    static synchronized void otherAppOpening(Object key, SurfaceControl newWindow) {
        Session s = session;
        if (s == null || s.key == key || s.open) return;
        retiredKey = s.key;
        retiredTask = s.taskId;
        retiredMs = SystemClock.uptimeMillis();
        boolean alone = s.canGoAlone();
        s.ghosted = alone;
        ghostBelow = newWindow;
        stop(alone ? "sigue sola mientras se abre otra app" : "se abre otra app");
    }

    /** MIUI terminó o canceló una animación: si no encadena otra enseguida, se termina. */
    static void animEnded(Object key) {
        if (key == handedKey) handedKey = null;            // acabó la apertura devuelta antes
        final Session s = session;
        if (s == null || s.key != key) return;
        s.keyEnded = true;                                 // otro animador puede relevarlo ya
        final long at = SystemClock.uptimeMillis();
        renderHandler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (session != s || s.lastFrameMs > at) return;
                if (s.ownTail && SystemClock.uptimeMillis() - at < 1500) renderHandler().postDelayed(this, 16);
                else stop("fin");
            }
        }, GRACE_MS);
    }

    /** Lo que tarda en fundirse el último fotograma del genio al pasar a recientes. */
    private static final long FADE_MS = 160;

    /**
     * Funde la capa (con el último fotograma dibujado) y la oculta. Si mientras tanto otro genio
     * empieza a usarla, se deja: él pone su transparencia en cada fotograma.
     */
    private static void fadeOut(final SurfaceControl l) {
        final long t0 = SystemClock.uptimeMillis();
        renderHandler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Session s = session;
                    if (!l.isValid() || (s != null && s.started) || ghost != null) return;
                    float k = (SystemClock.uptimeMillis() - t0) / (FADE_MS * timeScale);
                    SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                    if (k >= 1f) t.setVisibility(l, false);
                    else t.setAlpha(l, 1f - smooth(k));
                    t.apply();
                    if (k < 1f) renderHandler().postDelayed(this, 8);
                } catch (Throwable e) {
                    Log.e("genio: fundido a recientes", e);
                }
            }
        });
    }

    /** La animación de este animador ya no lleva genio (otro tipo, gesto rápido…). */
    static void stopFor(Object key, String why) {
        Session s = session;
        if (s != null && s.key == key) stop(why);
    }

    static synchronized void stop(String why) {
        Session s = session;
        if (s == null) return;
        session = null;
        s.finishing = true;
        s.why = why;
        TapCatcher.want(null);
        ending = s;
        renderHandler().post(s.endTask);
    }

    /** Animador cuya apertura ya se devolvió a MIUI (entrega temprana): sus fotogramas de abrir se ignoran. */
    private static volatile Object handedKey;
    private static long handedMs;

    /** Devuelve ya la ventana real a MIUI (ver la entrega temprana en {@code render}). */
    private static synchronized void handBack(Session s) {
        if (session != s) return;
        handedKey = s.key;
        handedMs = SystemClock.uptimeMillis();
        stop("entrega temprana");
    }

    /** Un fallo al preparar o dibujar; varios seguidos apartan el genio un rato. */
    private static synchronized void fail() {
        if (++failures >= MAX_FAILURES) {
            failures = 0;
            pausedUntilMs = SystemClock.uptimeMillis() + PAUSE_MS;
            Log.w("genio: varios fallos seguidos, en pausa " + PAUSE_MS / 1000 + " s");
        }
    }

    private static synchronized void succeeded() {
        failures = 0;
    }

    /** Sin genio en marcha ni terminando: los hooks de cada transacción salen sin hacer nada. */
    static boolean idle() {
        return session == null && ending == null && ghost == null;
    }

    /** El genio suelto tiene este icono del escritorio: MIUI no debe volver a mostrarlo aún. */
    static boolean ghostHolds(Object view) {
        Ghost g = ghost;
        return g != null && g.iconView != null && g.iconView.get() == view;
    }

    /**
     * Prepara capa, contexto de dibujo y shader con un dibujo invisible, para que la primera
     * animación no pague su creación. Se llama al volver al escritorio; si ya están, no hace nada.
     */
    static void prewarm(final SurfaceControl root, final int w, final int h) {
        if (root == null || w <= 0 || h <= 0) return;
        lastRoot = root;
        lastW = w;
        lastH = h;
        renderHandler().post(new Runnable() {
            @Override
            public void run() {
                if (session != null || ending != null || !Hidden.ok()) return;
                GlRenderer.warm(root, w, h);
                if (!captureWarmed) {
                    captureWarmed = true;
                    warmCapture(root);
                }
            }
        });
    }

    private static boolean captureWarmed;
    private static final Object WARM_OWNER = new Object();

    /**
     * Una vez por arranque del launcher: una captura diminuta (16×16) del propio escritorio pasa
     * por todo el camino de una animación (capturar, meterla en la textura y leerla). La primera
     * vez el sistema y la GPU preparan muchas cosas (el primer cierre tardaba ~350 ms en dibujar
     * su primer fotograma); así lo pagan aquí, con el escritorio quieto. Hilo de dibujo.
     */
    private static void warmCapture(SurfaceControl root) {
        long t0 = SystemClock.uptimeMillis();
        try {
            Hidden.Shot shot = Hidden.capture(root, new Rect(0, 0, 16, 16), 1f, null);
            if (shot == null) return;
            try {
                if (GlRenderer.queue(shot.buffer, shot.colorSpace, WARM_OWNER)) {
                    GlRenderer.consume();
                    GlRenderer.claimWindow(null);   // que ninguna animación la tome por la imagen de una app
                }
            } finally {
                shot.buffer.close();
            }
            Log.i("genio: captura preparada en " + (SystemClock.uptimeMillis() - t0) + " ms");
        } catch (Throwable t) {
            Log.e("genio: preparar la captura", t);
        }
    }

    /**
     * Transparencia para el icono flotante de MIUI (que dibuja, en el rectángulo de su animación,
     * el icono, un velo del color de la app y a veces una captura). Con genio no se ve: al abrir,
     * solo hasta que el genio sale del icono (fundido corto); al cerrar, solo al final, cuando la
     * copa ya casi tiene el tamaño del icono.
     */
    static float floatingIconAlpha() {
        Session s = session;
        if (s == null) return 1f;
        // Al cerrar, MIUI dibuja su icono flotante (con un velo del color del icono) a la medida
        // de su rectángulo y por debajo de la ventana: de serie, la ventana lo tapa entero. Con
        // genio la ventana se oculta y la copa es más estrecha abajo, y el velo asomaba alrededor
        // como un marco claro. Aquí sobra (el genio ya dibuja el icono al final): fuera en cuanto
        // se ve el genio, o mientras la ventana aún es grande y lo tapa. Si el genio no llega a
        // verse, MIUI lo enseña como siempre.
        if (!s.open && (s.shown || s.pwNow > CLOSE_ICON_HIDE_PW)) return 0f;
        // El genio ya es el icono. El de MIUI (encima del genio) se quita un refresco después de
        // que se vea el genio: van en transacciones distintas y nunca debe haber un fotograma vacío.
        if (s.iconReady) return s.shown && SystemClock.elapsedRealtimeNanos() - s.shownNs > 2 * s.vsync ? 0f : 1f;
        if (s.open && s.shown && !s.shownEarly) return 0f;
        return 1f - s.layerAlpha();
    }

    /**
     * El velo de MIUI (rectángulo del color de la app que crece con su animación) nunca se ve con
     * genio: el genio ya pone ese color debajo de su contenido, con su forma.
     */
    static boolean hideVeil() {
        return session != null;
    }

    /**
     * Un toque en (x, y) de pantalla cae en el icono al que vuelve la app que se está cerrando
     * (con un margen: el dedo no es preciso).
     */
    static boolean tapOnClosingIcon(float x, float y) {
        Session s = session;
        if (s == null || s.open || s.following) return false;
        synchronized (s.lock) {
            RectF r = s.tapRect;
            if (r.isEmpty()) return false;
            float m = r.width() * 0.25f;
            return x >= r.left - m && x <= r.right + m && y >= r.top - m && y <= r.bottom + m;
        }
    }

    /**
     * El gesto de cerrar empieza (el sistema le da la ventana al launcher): se captura ya, antes
     * del primer fotograma, para que el genio no tenga que esperar a una captura al empezar.
     */
    static void precapture(final SurfaceControl leash, final int taskId, final int x, final int y, final String pkg) {
        if (!enabled || leash == null || !leash.isValid() || lastW <= 0 || taskId == -1) return;
        captureHandler().post(new Runnable() {
            @Override
            public void run() {
                if (!leash.isValid()) return;
                Rect crop = new Rect(x, y, x + lastW, y + lastH);
                Hidden.Shot shot = Hidden.capture(leash, crop, Math.min(CAPTURE_MAX_SCALE, GlRenderer.scale), null);
                if (shot == null) return;
                synchronized (PRE) {
                    if (preShot != null) release(preShot);
                    preShot = shot;
                    preTask = taskId;
                    preShotMs = SystemClock.uptimeMillis();
                }
                if (pkg != null) remember(pkg, taskId, shot);
            }
        });
    }

    /** La captura precargada de esta tarea, si es reciente (una sola vez). */
    private static Hidden.Shot takePreShot(int taskId) {
        synchronized (PRE) {
            Hidden.Shot s = preShot;
            if (s == null || taskId == -1 || preTask != taskId
                    || SystemClock.uptimeMillis() - preShotMs > PRELOAD_MS) {
                return null;
            }
            preShot = null;
            return s;
        }
    }

    /**
     * Se toca un icono: se sube ya a la GPU (y se prepara la capa) mientras el dedo está abajo,
     * para que al abrir la app el primer fotograma del genio salga sin esperas. {@code d} es una
     * copia propia; {@code screen}, dónde está el icono en pantalla.
     */
    static void prepareIcon(final Drawable d, final RectF screen, final String pkg) {
        if (!enabled || d == null || screen.isEmpty()) return;
        renderHandler().post(new Runnable() {
            @Override
            public void run() {
                // Con un genio en marcha su icono está en la GPU: no se toca.
                if (session != null || ending != null || ghost != null || !Hidden.ok()) return;
                SurfaceControl root = lastRoot;
                if (root == null || !root.isValid()) return;
                try {
                    GlRenderer.warm(root, lastW, lastH);
                    if (!GlRenderer.warmed) return;
                    Bitmap b = Bitmap.createBitmap(ICON_TEXTURE, ICON_TEXTURE, Bitmap.Config.ARGB_8888);
                    d.setBounds(0, 0, ICON_TEXTURE, ICON_TEXTURE);
                    d.draw(new Canvas(b));
                    GlRenderer.uploadIcon(b, PREPARED_ICON);
                    b.recycle();
                    preparedPkg = null;
                    // Su última imagen, si la hay y Android la va a enseñar al abrir.
                    Snap snap;
                    synchronized (snaps) {
                        snap = pkg == null ? null : snaps.get(pkg);
                    }
                    if (snap != null && taskAlive(snap.task)
                            && GlRenderer.queue(snap.shot.buffer, snap.shot.colorSpace, PREPARED_CONTENT)
                            && GlRenderer.consume() == PREPARED_CONTENT) {
                        preparedPkg = pkg;
                    }
                    synchronized (preparedRect) {
                        preparedRect.set(screen);
                    }
                    preparedMs = SystemClock.uptimeMillis();
                } catch (Throwable t) {
                    Log.e("genio: icono precargado", t);
                }
            }
        });
    }

    /** El icono precargado es el de esta apertura (mismo sitio, reciente y aún en la GPU). */
    private static boolean preparedFor(RectF icon) {
        if (GlRenderer.iconOwner() != PREPARED_ICON || SystemClock.uptimeMillis() - preparedMs > PRELOAD_MS) return false;
        synchronized (preparedRect) {
            float tol = Math.max(icon.width(), preparedRect.width()) * SAME_ICON;
            return Math.abs(icon.centerX() - preparedRect.centerX()) < tol
                    && Math.abs(icon.centerY() - preparedRect.centerY()) < tol;
        }
    }

    /** Oscurecido del escritorio detrás de la ventana (solo Profundidad): va con lo abierta que está. */
    private static float dimFor(float pw) {
        float k = style == STYLE_DEPTH ? DEPTH_DIM : 0f;
        return k * Math.max(0f, Math.min(1f, pw));
    }

    /** La sesión de este animador vuelve a un icono y aún no sabe cuál es su vista. */
    static boolean needsTarget(Object key) {
        Session s = session;
        return s != null && s.key == key && s.targetView == null;
    }

    /** La sesión de este animador aún no tiene el color de la app (MIUI lo da unos fotogramas tarde). */
    static boolean needsShade(Object key) {
        Session s = session;
        return s != null && s.key == key && !s.bgKnown;
    }

    /**
     * Color de fondo mientras MIUI no da el de la app: el de las ventanas de inicio del sistema,
     * negro en modo oscuro y blanco en claro. Antes era siempre blanco, y en modo oscuro se veía
     * un destello blanco que MIUI de serie no tiene.
     */
    private static int provisionalBg() {
        android.content.Context c = TapCatcher.context;
        try {
            if (c != null && (c.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                return 0xFF000000;
            }
        } catch (Throwable ignored) {
        }
        return 0xFFFFFFFF;
    }

    /** Mezcla de dos colores opacos (t = 0 → a, 1 → b). */
    private static int mixColor(int a, int b, float t) {
        int r = Math.round(lerp((a >> 16) & 255, (b >> 16) & 255, t));
        int g = Math.round(lerp((a >> 8) & 255, (b >> 8) & 255, t));
        int bl = Math.round(lerp(a & 255, b & 255, t));
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** La sesión de este animador aún no tiene el icono (al cerrar se conoce al soltar el dedo). */
    static boolean needsIcon(Object key) {
        Session s = session;
        return s != null && s.key == key && s.iconDrawable == null && !s.iconFailed;
    }

    /** Desde {@code SurfaceParams.applyTo}: añade nuestros cambios a la transacción de MIUI. */
    static void onApply(SurfaceControl.Transaction t, SurfaceControl surface, float alpha, boolean hasAlpha) {
        Ghost gh = ghost;
        if (gh != null && surface == gh.leash) {               // la app que vuelve sola: la dibuja el genio
            t.setAlpha(surface, 0f);
            return;
        }
        Session s = session;
        if (s != null && surface == s.oldLeash && !s.finishing && s.shown) {
            t.setAlpha(surface, 0f);
            return;
        }
        if (s == null || s.leash != surface) {
            s = ending;
            if (s == null || s.leash != surface) return;
        }
        if (hasAlpha) s.alpha = alpha;
        SurfaceControl layer = s.layer;
        if (layer == null || !layer.isValid()) return;
        if (s.finishing) {
            if (s.ghosted) {                               // sigue sola: la capa la usa el genio suelto
                t.setAlpha(surface, 0f);
                return;
            }
            t.setVisibility(layer, false);                 // MIUI ya pone su propia transparencia
            if (s.hidWindow && !hasAlpha) t.setAlpha(surface, s.alpha);
            s.handedBack = true;
            return;
        }
        if (!s.ready()) {
            // Al abrir, hasta que se vea el genio solo se ve el icono flotante de MIUI (que
            // crece igual que el genio): ni la ventana desnuda ni el velo, que parpadeaban.
            if (s.open) {
                t.setAlpha(surface, 0f);
                s.hidWindow = true;
            }
            return;
        }
        s.markShown();
        float a = s.layerAlpha();
        // La ventana real se oculta; el genio entra en un fundido de unos fotogramas sobre el icono flotante.
        // (Antes, sin icono preparado, la ventana se fundía con la capa: con la copa, que no tiene
        // la forma del rectángulo de MIUI, se veía un fotograma la ventana real detrás.)
        t.setAlpha(surface, 0f);
        t.setAlpha(layer, a);
        t.setVisibility(layer, true);
        Hidden.setRelativeLayer(t, layer, surface, Z);
    }

    /** Desde {@code TransactionCompat.setMatrix}: si MIUI escala el inicio, la capa no se escala. */
    static void onMatrix(SurfaceControl.Transaction t, SurfaceControl surface, Matrix m) {
        if (surface != homeLeash) return;
        Session s = session;
        if (s != null) s.compensate(t, m);
    }

    /**
     * Prioridad del hilo de dibujo (nice de Linux; menos es más prioridad). Al abrir una app las 8
     * CPU están al 100 % y la app que arranca lleva sus hilos de inicio a −12: con −8
     * (URGENT_DISPLAY) el genio esperaba hasta 30 ms por fotograma a que le dejaran una CPU. Con
     * −16 gana esas esperas; trabaja ~5 ms por fotograma y se duerme hasta el siguiente refresco,
     * así que no le quita tiempo apreciable a nadie. Una app puede pedirla sin permisos especiales
     * (el audio usa −19).
     */
    private static final int RENDER_PRIORITY = -16;

    private static synchronized Handler renderHandler() {
        if (renderThread == null) renderThread = start("IceHome-genie", RENDER_PRIORITY);
        return renderThread;
    }

    private static synchronized Handler captureHandler() {
        if (captureThread == null) captureThread = start("IceHome-capture", Process.THREAD_PRIORITY_DISPLAY);
        return captureThread;
    }

    /** Crea los dos hilos ya, al instalar (sin animación en marcha); ver {@link Threads}. */
    static void startThreads() {
        renderHandler();
        captureHandler();
    }

    private static Handler start(String name, int priority) {
        Handler h = Threads.start(name, priority);
        Log.i("hilo " + Threads.describe(h));
        return h;
    }

    // ------------------------------------------------------------------ sesión

    private static final class Session {

        /** Lo que dura un refresco de la pantalla al empezar (60, 90, 120 Hz…). */
        final long vsync = Tune.vsyncNs();
        /**
         * Tirón: más de 1,5 veces el ritmo real de dibujo, que es un múltiplo del refresco (a 120 Hz
         * con tope de 90 se dibuja a 60). Con 25 ms fijos, a 120 Hz los tirones no contaban nunca.
         */
        final long jankNs = vsync * Math.max(1L, (periodNs() + vsync - 1) / vsync) * 3 / 2;

        volatile Object key;
        /** Sentido actual (cambia si se interrumpe); el inicial, para el registro y la entrada. */
        volatile boolean open;
        final boolean startedOpen;
        final SurfaceControl root;
        final RectF full;
        /**
         * Color de la app bajo el contenido (el velo de MIUI). MIUI lo conoce unos fotogramas
         * después de empezar a abrir (ver WindowAnim): hasta entonces vale uno provisional según
         * el modo oscuro, y al llegar el bueno se pasa a él en {@link #BG_BLEND_S}.
         */
        private volatile int bgFrom, bgTo;
        private volatile long bgNs;
        volatile boolean bgKnown;
        volatile Drawable iconDrawable;
        /** El icono ya está en la GPU: el genio sale de él y vuelve a él. */
        volatile boolean iconReady, iconFailed;
        final long startMs = SystemClock.uptimeMillis();

        volatile SurfaceControl leash, layer;
        volatile int taskId = -1;
        volatile boolean finishing, shown, shownEarly, handedBack, handedOver, keyEnded, hidWindow, ghosted;
        /** Icono del escritorio al que vuelve (al cerrar). */
        volatile java.lang.ref.WeakReference<View> targetView;
        /** Color dominante del icono (para el rectángulo del modo ligero); 0 si no se sabe. */
        volatile int iconColor;
        /** Superficie anterior de la ventana, si MIUI la cambió: también se mantiene oculta. */
        volatile SurfaceControl oldLeash;
        volatile long shownNs, lastFrameMs, leashMs, shownMs;
        private float firstPw = -1f;
        volatile float alpha = 1f, iconFade, pwNow;
        volatile int posted;
        String why;

        // Estado de MIUI (hilo de animación → hilos propios).
        final Object lock = new Object();
        /** Icono destino, para reconocer un toque en él (bajo lock). */
        final RectF tapRect = new RectF();
        private final RectF pendingCur = new RectF(), pendingIcon = new RectF();
        private float pendingRadius, pendingDir;
        // Sentido: 0 = abrir, 1 = cerrar, con transición al interrumpir (hilo de animación).
        private float dirFrom, dirTo, dirNow;
        private long dirNs;
        private Rect crop;
        private final AtomicBoolean renderQueued = new AtomicBoolean(), captureQueued = new AtomicBoolean();
        // Extremo cercano: icono, dedo, o transición de uno a otro (solo hilo de animación).
        private final RectF iconNow = new RectF(), iconFrom = new RectF(), iconTo = new RectF(), iconGoal = new RectF();
        private boolean iconInit;
        volatile boolean following;
        private boolean landed;
        private long blendNs;
        // Lámpara: posición y velocidad del extremo lejano (centro de arriba), peso de las físicas.
        private float lampX, lampY, lampVx, lampVy, lampW;
        private long lampNs;
        private boolean lampInit;
        private float pendingBend, pendingLift, pendingFlip;
        private float flipNow;
        private long flipNs;
        // Velocidad de los bordes del rectángulo de MIUI (px/s): para que el genio suelto siga
        // igual y para predecir el rectángulo entre dos fotogramas de MIUI (ver predict).
        private final float[] edgeVel = new float[4];
        /**
         * Profundidad: velocidad de cada borde tal como se ve en pantalla (px/s), medida entre dos
         * dibujos nuestros, no entre fotogramas de MIUI. MIUI manda fotogramas a trompicones; con
         * su velocidad la estela se apagaba de golpe en un hueco y se quedaba puesta al final. Con
         * la de los dibujos, si la ventana no se mueve en pantalla, no hay estela: es lo que haría
         * una cámara.
         */
        private final float[] velNow = new float[4];
        private final RectF prevDraw = new RectF();
        private long prevDrawNs;

        private void drawVelocity(RectF cur) {
            long now = System.nanoTime();
            float dt = (now - prevDrawNs) / 1e9f;
            if (prevDrawNs == 0 || dt > 0.1f) {
                java.util.Arrays.fill(velNow, 0f);        // primer dibujo o tras una pausa larga
            } else if (dt > 0.004f) {
                // Suavizado ligero (60 %): sigue los cambios sin temblar con un fotograma irregular.
                velNow[0] = lerp(velNow[0], (cur.left - prevDraw.left) / dt, 0.6f);
                velNow[1] = lerp(velNow[1], (cur.top - prevDraw.top) / dt, 0.6f);
                velNow[2] = lerp(velNow[2], (cur.right - prevDraw.right) / dt, 0.6f);
                velNow[3] = lerp(velNow[3], (cur.bottom - prevDraw.bottom) / dt, 0.6f);
            }
            prevDraw.set(cur);
            prevDrawNs = now;
        }

        private final RectF prevCur = new RectF();
        private long prevCurNs;
        /** Cuándo llegó la última muestra de MIUI (System.nanoTime, bajo lock). */
        private long sampleNs;
        /** Hacia dónde va cada borde: el icono al cerrar, la pantalla al abrir (bajo lock). */
        private final RectF pendingGoal = new RectF();
        private boolean pendingHasGoal;

        // Capturas ("IceHome-capture"); la última espera en GlRenderer hasta el siguiente dibujo.
        private volatile long lastCaptureMs, lastCaptureCostMs;
        private final ArrayList<SurfaceControl> excludeList = new ArrayList<>();
        private SurfaceControl[] exclude;
        private int captures, failed;
        /** La última captura al cerrar fue la primera, a baja resolución: toca otra buena. */
        private volatile boolean lowResShot;
        private long captureNs;
        private boolean layerExcluded;
        private volatile boolean preloaded, iconPreloaded, contentPreloaded;
        private long windowSinceNs;
        /**
         * Para medir la curva de MIUI (el genio la sigue): ms desde el inicio en que la ventana
         * llevaba la mitad, el 90 % y el 99 % del camino (al abrir, hacia la pantalla; al cerrar,
         * hacia el icono). -1 = aún no.
         */
        private volatile long at50 = -1, at90 = -1, at99 = -1;

        // Solo en el hilo de dibujo.
        private final RectF cur = new RectF(), icon = new RectF();
        private final GlRenderer.Geo geoNow = new GlRenderer.Geo();
        /** iOS: las esquinas aún se mueven con sus muelles; el fin de MIUI espera a que lleguen. */
        volatile boolean ownTail;
        /** iOS: destino, posición y velocidad de cada esquina (x, y ×4). */
        private final float[] iosTarget = new float[8], iosPos = new float[8], iosVel = new float[8];
        /** iOS: cuánto se deforma según la zona del icono (0 en el centro, 1 arriba o abajo). */
        private float iosZone;
        private long iosNs;
        private long firstNs, firstPostMs, drawNs, lastDrawNs, maxGapNs;
        private int janks;
        /**
         * Por qué llegó tarde cada tirón (para el registro): 0 esperando búfer (eglSwapBuffers),
         * 1 dibujo lento, 2 el hilo de dibujo empezó tarde (CPU ocupada), 3 captura en marcha, 4 otro.
         */
        private final int[] jankWhy = new int[5];
        private long lastWorkNs, lastSwapNs;
        private volatile boolean capturing;
        private boolean lastCapturing;
        private final SurfaceControl.Transaction ownTx = new SurfaceControl.Transaction();
        private volatile long firstSwapNs;
        private boolean started;
        private volatile boolean fromWindow;

        // Compensación de la escala del inicio (aplicador / hilo de dibujo).
        private final Matrix inverse = new Matrix(), layerMatrix = new Matrix();
        private final float[] tmp = new float[9];
        private boolean compensated;

        final Runnable renderTask = new Runnable() {
            @Override
            public void run() {
                renderQueued.set(false);
                render(System.nanoTime());
            }
        };

        /**
         * Hay algo nuevo que dibujar. El primer fotograma, ya; los siguientes, en el próximo
         * refresco de la pantalla que respete {@link #maxFps}. Si el bucle de refrescos ya está
         * en marcha (ver {@link #pacer}), basta con marcarlo: lo recoge el siguiente refresco.
         */
        void requestRender() {
            dirty = true;
            if (!renderQueued.compareAndSet(false, true)) return;
            renderHandler().post(posted == 0 ? renderTask : schedulePacer);
        }

        private volatile boolean dirty;
        private long lastPacedNs;

        private final Runnable schedulePacer = new Runnable() {
            @Override
            public void run() {
                android.view.Choreographer.getInstance().postFrameCallback(pacer);
            }
        };

        /**
         * Bucle de refrescos del hilo de dibujo. MIUI calcula su muelle en el hilo principal del
         * launcher, que al abrir una app va justo (fotogramas de 15-20 ms y huecos de 25-60 ms):
         * si el genio solo dibujase cuando llega un fotograma de MIUI, cada hueco sería un tirón.
         * Así que, mientras MIUI siga animando, el genio dibuja en cada refresco de la pantalla y
         * rellena los huecos prediciendo el rectángulo ({@link #predict}).
         */
        private final android.view.Choreographer.FrameCallback pacer = new android.view.Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                android.view.Choreographer ch = android.view.Choreographer.getInstance();
                if (finishing) {
                    renderQueued.set(false);
                    return;
                }
                if (lastPacedNs != 0 && frameTimeNanos - lastPacedNs < periodNs()) {
                    ch.postFrameCallback(this);                  // aún no toca (límite de fps)
                    return;
                }
                lastPacedNs = frameTimeNanos;
                if (render(frameTimeNanos) && !finishing) {
                    ch.postFrameCallback(this);                  // sigue en el próximo refresco
                    return;
                }
                renderQueued.set(false);
                // Llegó una muestra justo mientras se decidía parar: que no se pierda.
                if (dirty && !finishing && renderQueued.compareAndSet(false, true)) ch.postFrameCallback(this);
            }
        };
        final Runnable captureTask = new Runnable() {
            @Override
            public void run() {
                capturing = true;
                try {
                    captureOnce();
                } finally {
                    capturing = false;
                }
            }
        };
        final Runnable endTask = new Runnable() {
            @Override
            public void run() {
                end();
            }
        };
        private final Runnable watchdog = new Runnable() {
            @Override
            public void run() {
                if (session == Session.this) stop("sin fotogramas");
            }
        };

        Session(Frame f) {
            key = f.key;
            open = startedOpen = f.open;
            dirTo = dirFrom = dirNow = f.open ? 0f : 1f;
            root = f.root;
            full = new RectF(f.full);
            if (f.open) {
                bgKnown = (f.bg >>> 24) != 0;
                bgFrom = bgTo = bgKnown ? f.bg | 0xFF000000 : provisionalBg();
            } else {
                // Al cerrar, MIUI solo encoge la ventana: nada debajo. Con el velo (claro en muchas
                // apps) asomaba un borde blanco por las esquinas y la barra de gestos de la captura.
                bgKnown = true;
                bgFrom = bgTo = 0;
            }
            iconDrawable = f.iconDrawable;
            if (f.exclude != null) Collections.addAll(excludeList, f.exclude);
        }

        /** Color de fondo de este fotograma (fundido del provisional al de MIUI). */
        int bgNow() {
            long ns = bgNs;
            if (ns == 0) return bgTo;
            float t = smooth((System.nanoTime() - ns) / 1e9f / (BG_BLEND_S * timeScale));
            if (t >= 1f) return bgTo;
            dirty = true;                                      // seguir dibujando hasta acabar
            return mixColor(bgFrom, bgTo, t);
        }

        /** Puede seguir sola hasta el icono: ya se ve, con su captura, y sabe a qué icono va. */
        boolean canGoAlone() {
            synchronized (lock) {
                return shown && fromWindow && !following && !tapRect.isEmpty() && !finishing;
            }
        }

        String goalString() {
            return iconInit ? iconGoal.toShortString() : "—";
        }

        /** La apertura nueva es de esta misma app: misma tarea, o sin ventana aún y mismo icono. */
        boolean sameApp(Frame f) {
            if (f.taskId != -1 && taskId != -1) return f.taskId == taskId;
            if (!f.hasIcon || !iconInit || iconGoal.isEmpty()) return false;
            float tol = Math.max(f.icon.width(), iconGoal.width()) * SAME_ICON;
            return Math.abs(f.icon.centerX() - iconGoal.centerX()) < tol
                    && Math.abs(f.icon.centerY() - iconGoal.centerY()) < tol;
        }

        /** Transparencia de nuestra capa: al abrir, fundido de entrada; al cerrar, de salida. */
        float layerAlpha() {
            if (iconReady) return shown ? 1f : 0f;
            if (!open) return 1f - iconFade;
            if (!shown) return 0f;
            return smooth((SystemClock.elapsedRealtimeNanos() - shownNs) / 1e9f / (OPEN_FADE_S * timeScale));
        }

        void markShown() {
            if (shown) return;
            succeeded();
            shownNs = SystemClock.elapsedRealtimeNanos();
            shownMs = SystemClock.uptimeMillis();
            shownEarly = pwNow < EARLY_PW;
            shown = true;
        }

        // ---------------------------------------------------------- hilo de animación

        void post(Frame f) {
            lastFrameMs = SystemClock.uptimeMillis();
            if (f.leash != null && f.leash != leash) {
                if (leash != null) {
                    // MIUI cambió de superficie (pantalla de inicio de la app → ventana real).
                    Log.i("genio: la ventana cambia de superficie");
                    oldLeash = leash;
                    synchronized (lock) {
                        crop = null;
                    }
                    lastCaptureMs = 0;                       // la ventana nueva, cuanto antes
                }
                leash = f.leash;
                if (leashMs == 0) leashMs = lastFrameMs;
            }
            if (f.iconDrawable != null && iconDrawable == null) iconDrawable = f.iconDrawable;
            if (!bgKnown && (f.bg >>> 24) != 0) {
                // Llega el color de la ventana de inicio de la app: del provisional a él, sin saltos.
                bgFrom = bgNow();
                bgTo = f.bg | 0xFF000000;
                bgNs = System.nanoTime();
                bgKnown = true;
                dirty = true;
            }
            if (f.targetView != null) targetView = new java.lang.ref.WeakReference<>(f.targetView);
            if (taskId == -1 && f.taskId != -1) taskId = f.taskId;
            long nowNs = SystemClock.elapsedRealtimeNanos();
            // Interrupción: el sentido cambia y la forma de abrir y la de cerrar se funden.
            float dirTarget = f.open ? 0f : 1f;
            if (dirTarget != dirTo) {
                if (f.open != open) {
                    Log.i("genio: interrupción, ahora " + (f.open ? "abrir" : "cerrar"));
                    lastCaptureMs = 0;                       // contenido al día para el nuevo sentido
                }
                dirFrom = dirNow;
                dirTo = dirTarget;
                dirNs = nowNs;
            }
            open = f.open;
            dirNow = lerp(dirFrom, dirTo, smooth((nowNs - dirNs) / 1e9f / (DIR_BLEND_S * timeScale)));
            updateIcon(f, nowNs);
            lamp(f.cur, nowNs);
            // Genio al derecho o al revés (iOS: su zona): con el dedo, al derecho e iOS recta (el dedo es el extremo
            // cercano, abajo); al soltar, cambia poco a poco hacia la que pide el icono.
            float flipTarget = following ? 0f : flipFor(f.hasIcon ? f.icon : iconNow, full);
            if (flipNs == 0) flipNow = flipTarget;
            else flipNow += (flipTarget - flipNow) * (1f - (float) Math.exp(-(nowNs - flipNs) / 1e9f / (FLIP_S * timeScale)));
            flipNs = nowNs;
            float pw = progress(f.cur.width(), iconNow.width(), full.width());
            pwNow = pw;
            if (firstPw < 0f) firstPw = pw;
            float done = open ? pw : 1f - pw;
            long sinceMs = SystemClock.uptimeMillis() - startMs;
            if (at50 < 0 && done >= 0.5f) at50 = sinceMs;
            if (at90 < 0 && done >= 0.9f) at90 = sinceMs;
            if (at99 < 0 && done >= 0.99f) at99 = sinceMs;
            if (!open) iconFade = 1f - smooth((pw - ICON_FADE_END) / (ICON_FADE_START - ICON_FADE_END));
            // Llega al icono: un tic de vibración (una vez; si se reabre, vuelve a poder sonar).
            if (open) landed = false;
            else if (!landed && !following && f.hasIcon && pw < LAND_PW) {
                landed = true;
                Haptics.land();
            }
            long frameAt = miuiFrameNs();
            synchronized (lock) {
                float dt = (frameAt - prevCurNs) / 1e9f;
                if (prevCurNs != 0 && dt > 0.002f && dt < 0.1f) {
                    float k = 0.5f;                            // suavizado: los fotogramas de MIUI no van parejos
                    edgeVel[0] = lerp(edgeVel[0], (f.cur.left - prevCur.left) / dt, k);
                    edgeVel[1] = lerp(edgeVel[1], (f.cur.top - prevCur.top) / dt, k);
                    edgeVel[2] = lerp(edgeVel[2], (f.cur.right - prevCur.right) / dt, k);
                    edgeVel[3] = lerp(edgeVel[3], (f.cur.bottom - prevCur.bottom) / dt, k);
                }
                prevCur.set(f.cur);
                prevCurNs = frameAt;
                sampleNs = frameAt;
                pendingCur.set(f.cur);
                pendingIcon.set(iconNow);
                pendingRadius = f.radius;
                pendingDir = dirNow;
                pendingFlip = flipNow;
                // Destino de la predicción: nunca se adelanta más allá de él.
                pendingHasGoal = open || f.hasIcon;
                if (open) pendingGoal.set(full);
                else if (f.hasIcon) pendingGoal.set(f.icon);
                if (f.hasIcon) tapRect.set(f.icon);
                else tapRect.setEmpty();
                // Volviendo al icono: se puede tocar para reabrir la app.
                TapCatcher.want(!open && !following && f.hasIcon ? f.icon : null);
                float w = lampW * physics;
                // Límites suaves (sin topes bruscos al deslizar en diagonal muy rápido).
                pendingBend = soft((lampX - f.cur.centerX()) * w, full.width() * LAMP_MAX_BEND);
                pendingLift = soft((lampY - f.cur.top) * w, full.height() * LAMP_MAX_LIFT);
                if (crop == null && f.leash != null) {
                    crop = new Rect(f.originX, f.originY, f.originX + Math.round(full.width()),
                            f.originY + Math.round(full.height()));
                }
            }
            requestRender();
            // Cuándo hace falta otra captura lo decide captureOnce (presupuesto de capturas).
            if (leash != null && captureDue() && captureQueued.compareAndSet(false, true)) {
                captureHandler().post(captureTask);
            }
        }

        /**
         * Extremo cercano: el icono; al arrastrar, un "icono" bajo el dedo. Al pasar de uno a otro
         * (soltar, o interrumpir una apertura con el dedo) se desliza suavemente, siguiendo al
         * destino aunque se mueva.
         */
        private void updateIcon(Frame f, long now) {
            boolean toIcon = f.hasIcon;
            if (toIcon) {
                iconTo.set(f.icon);
            } else {
                float w = full.width() * FINGER_ANCHOR;
                iconTo.set(f.cur.centerX() - w / 2, f.cur.bottom - w, f.cur.centerX() + w / 2, f.cur.bottom);
            }
            if (!iconInit) {
                iconNow.set(iconTo);
                iconFrom.set(iconTo);
                blendNs = now - (long) (ICON_BLEND_S * timeScale * 1e9f);
                following = !toIcon;
                iconInit = true;
            } else if (following == toIcon || (toIcon && !iconGoal.equals(f.icon))) {
                iconFrom.set(iconNow);                       // nuevo destino: desde donde está ahora
                blendNs = now;
                following = !toIcon;
            }
            if (toIcon) iconGoal.set(f.icon);
            float t = smooth((now - blendNs) / 1e9f / (ICON_BLEND_S * timeScale));
            iconNow.set(lerp(iconFrom.left, iconTo.left, t), lerp(iconFrom.top, iconTo.top, t),
                    lerp(iconFrom.right, iconTo.right, t), lerp(iconFrom.bottom, iconTo.bottom, t));
        }

        /**
         * Físicas de lámpara: el extremo lejano (centro de arriba) persigue al de MIUI con un
         * muelle. Al arrastrar con el dedo se queda atrás, se inclina y se balancea; al soltar,
         * las físicas se apagan suavemente mientras la copa vuela al icono.
         */
        private void lamp(RectF cur, long now) {
            float tx = cur.centerX(), ty = cur.top;
            if (!lampInit) {
                lampX = tx;
                lampY = ty;
                lampNs = now;
                lampInit = true;
            }
            float dt = Math.min(0.05f, Math.max(0f, (now - lampNs) / 1e9f));
            lampNs = now;
            // Peso: entra al arrastrar, sale al soltar o al abrir.
            float target = following ? 1f : 0f;
            float rate = dt / (following ? 0.12f : LAMP_FADE_S);
            lampW = target > lampW ? Math.min(target, lampW + rate) : Math.max(target, lampW - rate);
            if (lampW <= 0f) {                                  // sin físicas: pegado al de MIUI
                lampX = tx;
                lampY = ty;
                lampVx = lampVy = 0f;
                return;
            }
            float c = 2f * LAMP_ZETA * (float) Math.sqrt(LAMP_K);
            for (float left = dt; left > 0f; left -= 0.004f) {  // pasos cortos: estable con saltos
                float h = Math.min(0.004f, left);
                lampVx += (LAMP_K * (tx - lampX) - c * lampVx) * h;
                lampVy += (LAMP_K * (ty - lampY) - c * lampVy) * h;
                lampX += lampVx * h;
                lampY += lampVy * h;
            }
        }

        // ---------------------------------------------------------- hilo de capturas

        private void captureOnce() {
            captureQueued.set(false);
            SurfaceControl window = leash, l = layer;
            Rect c;
            synchronized (lock) {
                c = crop;
            }
            if (finishing || window == null || c == null) return;
            // Una captura cada vez, ya usada la anterior, y espaciadas: capturar carga al compositor.
            if (GlRenderer.hasQueued() || !captureDue()) return;
            // La primera puede ir antes de que exista la capa (al cerrar, así se ve antes); la capa
            // se excluye en cuanto se pone encima de la ventana.
            if (l != null && !layerExcluded) {
                excludeList.add(0, l);
                exclude = null;
                layerExcluded = true;
            }
            long t0 = System.nanoTime();
            Hidden.Shot shot = captures == 0 && !startedOpen ? takePreShot(taskId) : null;
            if (shot != null) {
                preloaded = true;                                 // tomada al empezar el gesto
            } else {
                float scale = Math.min(CAPTURE_MAX_SCALE, GlRenderer.scale);
                lowResShot = captures == 0 && !startedOpen && FIRST_CLOSE_SCALE < scale;
                if (lowResShot) scale = FIRST_CLOSE_SCALE;
                shot = Hidden.capture(window, c, scale, exclusions());
                long cost = System.nanoTime() - t0;
                captureNs += cost;
                lastCaptureCostMs = cost / 1_000_000L;
            }
            captures++;
            lastCaptureMs = SystemClock.uptimeMillis();
            if (shot == null) {
                failed++;
                return;
            }
            boolean ok = !finishing && window.isValid() && GlRenderer.queue(shot.buffer, shot.colorSpace, this);
            release(shot);                                       // la Surface guarda su referencia
            if (!ok) return;
            requestRender();
        }

        /** Toca capturar: la primera siempre; luego según el sentido, "en vivo" y lo que cuestan. */
        boolean captureDue() {
            if (lastCaptureMs == 0) return true;
            // Con el dedo en el escritorio (otra app, deslizar…), el compositor para el escritorio:
            // el contenido de la que se cierra ya no cambia.
            if (!open && TapCatcher.fingerDown()) return false;
            // Al cerrar, el contenido ya no cambia y cada captura frena a SurfaceFlinger 25-40 ms
            // (un tirón de toda la pantalla): solo se repite si la primera salió a baja resolución,
            // y entonces cuanto antes, mientras la ventana aún es grande. Antes se refrescaba cada
            // 700 ms, cuando la ventana ya casi era el icono y el refresco solo daba un tirón.
            if (!open && !lowResShot) return false;
            long budget = open ? (live ? (captures < OPEN_EARLY_SHOTS ? OPEN_EARLY_MS : OPEN_LIVE_MS)
                    : (captures < OPEN_EARLY_SHOTS ? OPEN_EARLY_MS : Long.MAX_VALUE))
                    : 0;
            if (budget == Long.MAX_VALUE) return false;
            long gap = Math.max(budget, (long) (lastCaptureCostMs * CAPTURE_IDLE));
            return SystemClock.uptimeMillis() - lastCaptureMs >= gap;
        }

        /** Capas que no deben salir en la captura; sin las que MIUI ya haya liberado. */
        private SurfaceControl[] exclusions() {
            boolean changed = exclude == null;
            for (int i = excludeList.size() - 1; i >= 0; i--) {
                if (!excludeList.get(i).isValid()) {
                    excludeList.remove(i);
                    changed = true;
                }
            }
            if (changed) exclude = excludeList.toArray(new SurfaceControl[0]);
            return exclude;
        }

        // ---------------------------------------------------------- hilo de dibujo

        /**
         * Dibuja el fotograma del refresco {@code frameNs} (System.nanoTime). Devuelve si el bucle
         * de refrescos debe seguir: sí mientras MIUI siga mandando muestras.
         */
        boolean render(long frameNs) {
            if (finishing) return false;
            float radius, dir, bend, lift, flip;
            boolean moved, fresh;
            synchronized (lock) {
                // MIUI lleva un rato sin muestras: ha acabado o el dedo está quieto. Se para el
                // bucle (la próxima muestra lo vuelve a poner en marcha).
                fresh = frameNs - sampleNs < KEEP_TICKING_PERIODS * periodNs();
                moved = predict(frameNs, cur);
                flip = pendingFlip;
                icon.set(pendingIcon);
                radius = pendingRadius;
                dir = pendingDir;
                bend = pendingBend;
                lift = pendingLift;
            }
            if (!dirty && !moved && posted > 0) return fresh;  // nada nuevo que enseñar
            dirty = false;
            SurfaceControl w = leash;
            if (w != null && !w.isValid()) {                   // el sistema canceló la transición
                stop("la ventana ya no existe");
                return false;
            }
            Handler h = renderHandler();
            h.removeCallbacks(watchdog);
            // Con el dedo quieto MIUI no manda fotogramas: no es el final.
            h.postDelayed(watchdog, following ? HOLD_MS : WATCHDOG_MS);
            if (!started && !begin()) {
                finishing = true;
                fail();
                return false;
            }
            boolean hadWindow = fromWindow;
            if (GlRenderer.consume() == this) fromWindow = true;
            // Al abrir, la primera captura entra fundida sobre la pantalla provisional.
            if (fromWindow && !hadWindow && startedOpen && posted > 0) windowSinceNs = System.nanoTime();
            if (!fromWindow && !startedOpen && lastCaptureMs == 0) {
                // Al cerrar, la captura precargada al empezar el gesto: se usa ya, sin esperar.
                Hidden.Shot pre = takePreShot(taskId);
                if (pre != null) {
                    boolean q = GlRenderer.queue(pre.buffer, pre.colorSpace, this);
                    release(pre);
                    if (q && GlRenderer.consume() == this) {
                        fromWindow = true;
                        preloaded = true;
                        captures++;
                        lastCaptureMs = SystemClock.uptimeMillis();
                    }
                }
            }
            if (!fromWindow) {
                if (!startedOpen) {                           // al cerrar, sin captura aún no hay nada
                    if (leash != null && captureQueued.compareAndSet(false, true)) captureHandler().post(captureTask);
                    return fresh;
                }
            }

            long now = SystemClock.elapsedRealtimeNanos();
            if (firstNs == 0) firstNs = now;
            // Entrada progresiva solo si el genio empieza sobre la ventana normal (al arrastrar).
            float ramp = startedOpen ? 1f : smooth((now - firstNs) / 1e9f / RAMP_IN_S);
            if (posted == 0 && startedOpen && !fromWindow && preparedPkg != null && preparedFor(icon)
                    && GlRenderer.windowOwner() == PREPARED_CONTENT) {
                // Al abrir una app que sigue en recientes: su última imagen desde el primer fotograma.
                GlRenderer.claimWindow(this);
                fromWindow = true;
                contentPreloaded = true;
            }
            if (!iconReady && !iconFailed && iconDrawable != null) {
                if (posted == 0 && preparedFor(icon)) {         // ya subido al tocar el icono
                    GlRenderer.claimIcon(this);
                    iconReady = true;
                    iconPreloaded = true;
                } else {
                    uploadIcon();
                }
            }
            // Estela en genio y Profundidad (solo se mide la velocidad si está puesta). Profundidad la
            // mide en el rectángulo de MIUI (su hoja se mueve con él); el genio, en la forma dibujada
            // (abajo), porque su punta lejana va mucho más deprisa que MIUI. iOS no lleva (el boceto tampoco).
            boolean blur = depthBlur > 0f && style != STYLE_IOS;
            if (blur && style == STYLE_DEPTH) drawVelocity(cur);
            shape(cur, icon, full, dir, ramp, lift, radius, bend, flip, iconReady, velNow, geoNow);
            if (blur && style != STYLE_DEPTH) drawVelocity(geoNow.cur);   // se usa en el siguiente fotograma
            geoNow.fade = windowSinceNs == 0 ? 1f : smooth((System.nanoTime() - windowSinceNs) / 1e9f / (CONTENT_FADE_S * timeScale));
            if (geoNow.fade < 1f) dirty = true;                  // seguir dibujando hasta acabar el fundido
            // iOS: cada esquina con su muelle, después de shape() (que pone sus destinos).
            // (geoNow.cup: si el estilo cambia a mitad, este fotograma aún no trae esquinas de iOS.)
            ownTail = style == STYLE_IOS && geoNow.cup > 2.5f && iosStep(icon, full, dir);
            if (ownTail) dirty = true;
            // Entrega temprana: al abrir, en cuanto la forma ya es la pantalla (a menos de 1,5 px) y
            // no queda fundido, vuelve la ventana real, en vivo y nítida. Antes se esperaba al
            // final del muelle de MIUI, cuya cola dura hasta 1 s, enseñando una captura vieja a
            // media resolución. Genio e iOS (con las esquinas ya quietas); Profundidad no llega plana.
            if (startedOpen && open && dir == 0f && !following && fromWindow && geoNow.fade >= 1f
                    && style != STYLE_DEPTH && !ownTail
                    && Math.abs(geoNow.cur.left - full.left) < 1.5f && Math.abs(geoNow.cur.right - full.right) < 1.5f
                    && Math.abs(geoNow.cur.top - full.top) < 1.5f && Math.abs(geoNow.cur.bottom - full.bottom) < 1.5f
                    && Math.abs(geoNow.bend) < 0.5f) {
                handBack(this);
            }
            // Con estela, otro fotograma: así se apaga sola aunque MIUI deje de mandar fotogramas.
            if (geoNow.vel[0] != 0f || geoNow.vel[1] != 0f || geoNow.vel[2] != 0f || geoNow.vel[3] != 0f) dirty = true;
            long t0 = System.nanoTime();
            if (lastDrawNs != 0) {
                long gap = t0 - lastDrawNs;
                if (gap > jankNs) {
                    janks++;
                    jankWhy[jankCause(t0 - frameNs)]++;
                }
                if (gap > maxGapNs) maxGapNs = gap;
            }
            lastDrawNs = t0;
            Ghost gh = ghost;
            boolean ok = GlRenderer.beginFrame(dimFor(Math.max(geoNow.pw, gh != null ? gh.lastPw : 0f)));
            if (ok && gh != null) ok = gh.drawStep(t0);               // el que vuelve solo, debajo
            ok = ok && redraw();
            long t1 = System.nanoTime();
            ok = ok && GlRenderer.endFrame();
            lastWorkNs = t1 - t0;
            lastSwapNs = System.nanoTime() - t1;
            lastCapturing = capturing;
            if (!ok) {
                finishing = true;
                fail();
                return false;
            }
            drawNs += System.nanoTime() - t0;
            if (posted == 0) {
                firstPostMs = SystemClock.uptimeMillis();
                firstSwapNs = System.nanoTime();
            }
            posted++;

            // Sin ventana, nadie más gobierna la capa: se muestra desde aquí.
            if (leash == null && ready()) {
                markShown();
                ownTx.setAlpha(layer, layerAlpha());
                ownTx.setVisibility(layer, true);
                ownTx.apply();
            }
            return fresh;
        }

        /**
         * Rectángulo de MIUI previsto para el refresco {@code frameNs}, a partir de la última
         * muestra y la velocidad de cada borde (bajo lock). Solo rellena huecos: se adelanta como
         * mucho {@link #MAX_AHEAD_PERIODS} refrescos (uno con el dedo, que puede pararse en seco) y
         * ningún borde pasa de su destino (el icono al cerrar, la pantalla al abrir). Devuelve si
         * el resultado difiere de la muestra (hay algo nuevo que dibujar).
         */
        private boolean predict(long frameNs, RectF out) {
            out.set(pendingCur);
            // Se predice para cuando se verá (un refresco después de dibujar), no para el que empieza.
            float ahead = (frameNs + vsync - sampleNs) / 1e9f;
            float max = (following ? 1f : MAX_AHEAD_PERIODS) / Math.max(15f, maxFps) + vsync / 1e9f;
            if (ahead <= 0f || sampleNs == 0) return false;
            ahead = Math.min(ahead, max);
            out.left = towards(pendingCur.left, out.left + edgeVel[0] * ahead, pendingGoal.left);
            out.top = towards(pendingCur.top, out.top + edgeVel[1] * ahead, pendingGoal.top);
            out.right = towards(pendingCur.right, out.right + edgeVel[2] * ahead, pendingGoal.right);
            out.bottom = towards(pendingCur.bottom, out.bottom + edgeVel[3] * ahead, pendingGoal.bottom);
            return Math.abs(out.left - pendingCur.left) + Math.abs(out.top - pendingCur.top)
                    + Math.abs(out.right - pendingCur.right) + Math.abs(out.bottom - pendingCur.bottom) > 0.5f;
        }

        /**
         * iOS, como el boceto: cada esquina sigue a la del rectángulo de MIUI (puesta por
         * {@link #shapeOnly} en ex/ex2) con un muelle críticamente amortiguado (sin rebote propio:
         * el rebote es el de la ventana). Al abrir se retrasan las esquinas cercanas al icono (ese
         * lado sale más estrecho); al cerrar, algo las lejanas. Con el dedo, casi 1:1. El muelle se
         * resuelve exacto (x = (A + Bt)·e^(−ωt)), no paso a paso: es estable con cualquier fotograma.
         * {@code dir}: 0 abrir, 1 cerrar, y en medio al interrumpir (se funde en DIR_BLEND_S): así el
         * retraso de cada esquina cambia poco a poco y la forma no salta al dar la vuelta.
         * Devuelve si alguna esquina aún no ha llegado (hay que seguir dibujando).
         */
        private boolean iosStep(RectF icon, RectF full, float dir) {
            float[] t = iosTarget;
            System.arraycopy(geoNow.ex, 0, t, 0, 4);
            System.arraycopy(geoNow.ex2, 0, t, 4, 4);
            long now = System.nanoTime();
            if (iosNs == 0) {
                // La zona se decide al empezar: con el dedo el icono se mueve y no debe cambiar.
                iosZone = lerp(IOS_CENTER, 1f, Math.abs(flipFor(icon, full)));
                System.arraycopy(t, 0, iosPos, 0, 8);
                java.util.Arrays.fill(iosVel, 0f);
                iosNs = now;
                return true;
            }
            float dt = Math.min(0.05f, (now - iosNs) / 1e9f) / timeScale;
            iosNs = now;
            // Cercanía de cada esquina al icono (1 la más cercana, 0 la más lejana).
            // Esquinas: 0 arriba-izq., 1 arriba-der., 2 abajo-der., 3 abajo-izq.
            float cx = icon.centerX(), cy = icon.centerY(), lo = Float.MAX_VALUE, hi = 0f;
            float[] dist = iosDist;
            for (int i = 0; i < 4; i++) {
                dist[i] = (float) Math.hypot(t[2 * i] - cx, t[2 * i + 1] - cy);
                lo = Math.min(lo, dist[i]);
                hi = Math.max(hi, dist[i]);
            }
            boolean moving = false;
            for (int i = 0; i < 4; i++) {
                float near = 1f - (dist[i] - lo) / Math.max(1f, hi - lo);
                float lag = lerp(near, 0.5f * (1f - near), dir) * iosZone;
                float w = (float) (2 * Math.PI / (IOS_T0 + IOS_T * strength / 2.2f * lag));
                float e = (float) Math.exp(-w * dt);
                for (int k = 2 * i; k < 2 * i + 2; k++) {
                    float a = iosPos[k] - t[k], b = iosVel[k] + w * a;
                    iosPos[k] = t[k] + (a + b * dt) * e;
                    iosVel[k] = (b - w * (a + b * dt)) * e;
                    if (Math.abs(iosPos[k] - t[k]) > 0.5f || Math.abs(iosVel[k]) > 20f) moving = true;
                }
            }
            if (!moving) System.arraycopy(t, 0, iosPos, 0, 8);   // quietas: justo en su sitio
            System.arraycopy(iosPos, 0, geoNow.ex, 0, 4);
            System.arraycopy(iosPos, 4, geoNow.ex2, 0, 4);
            return moving;
        }

        private final float[] iosDist = new float[4];

        /**
         * Hora del fotograma para el que MIUI calculó su muelle (System.nanoTime), no la de llegada
         * del hook, que llega 5-20 ms tarde según la carga: mezclarlas hacía temblar la predicción.
         * Si el hilo no está dentro de un fotograma, la hora actual.
         */
        private long miuiFrameNs() {
            // Dentro de un fotograma de Choreographer devuelve su hora (ms, reloj monotónico, el
            // mismo que System.nanoTime); fuera, la actual. Precisión de 1 ms, de sobra.
            long now = System.nanoTime(), t = android.view.animation.AnimationUtils.currentAnimationTimeMillis() * 1_000_000L;
            return now - t >= -1_000_000L && now - t < 50_000_000L ? Math.min(t, now) : now;
        }

        /** Un borde previsto que no cruza su destino (si lo hay): se queda en él. */
        private float towards(float from, float to, float goal) {
            if (!pendingHasGoal) return to;
            return (from - goal) * (to - goal) < 0f ? goal : to;
        }

        /** Este genio en el fotograma en curso (hilo de dibujo). */
        boolean redraw() {
            return GlRenderer.drawGenie(GlRenderer.main(), fromWindow, geoNow, full.width() * 0.5f, full.height(), bgNow());
        }

        /** Ya hay una imagen nuestra en pantalla: un dibujo hecho y un refresco después. */
        boolean ready() {
            return posted > 0 && System.nanoTime() - firstSwapNs > vsync + vsync / 4;
        }

        /** Primera vez en el hilo de dibujo: capa lista, oculta, arriba del todo y sin escala del inicio. */
        private boolean begin() {
            if (!GlRenderer.ensure(root, Math.round(full.width()), Math.round(full.height()))) return false;
            // Una captura en espera: si es de esta animación (al cerrar se pide antes de la capa), sirve.
            if (GlRenderer.hasQueued() && GlRenderer.consume() == this) fromWindow = true;
            SurfaceControl l = GlRenderer.layer;
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            GlRenderer.attach(t, root);                        // por si el launcher rehízo su superficie
            if (ghost == null) t.setVisibility(l, false);      // si hay un genio suelto, sigue a la vista
            t.setLayer(l, Z_TOP);                              // también deja de ser relativa
            synchronized (inverse) {
                Hidden.setMatrix(t, l, GlRenderer.layerMatrix(compensated ? inverse : null, layerMatrix), tmp);
                layer = l;
            }
            t.apply();
            started = true;
            return true;
        }

        /** El icono de la app, a la GPU (una vez por animación). */
        private void uploadIcon() {
            try {
                Bitmap b = Bitmap.createBitmap(ICON_TEXTURE, ICON_TEXTURE, Bitmap.Config.ARGB_8888);
                Drawable d = iconDrawable;
                d.setBounds(0, 0, ICON_TEXTURE, ICON_TEXTURE);
                d.draw(new Canvas(b));
                GlRenderer.uploadIcon(b, this);
                iconColor = accent(b);                       // antes de reciclarlo
                b.recycle();
                iconReady = true;
            } catch (Throwable e) {
                Log.e("genio: icono", e);
                iconFailed = true;
                iconDrawable = null;
            }
        }

        /**
         * Deshace en nuestra capa la escala que MIUI aplica al inicio, en la misma transacción.
         * Si la capa aún no es nuestra, se recuerda y se aplica al empezar.
         */
        void compensate(SurfaceControl.Transaction t, Matrix homeMatrix) {
            synchronized (inverse) {
                if (!homeMatrix.invert(inverse)) return;
                compensated = true;
                SurfaceControl l = layer;
                if (l != null && l.isValid()) Hidden.setMatrix(t, l, GlRenderer.layerMatrix(inverse, layerMatrix), tmp);
            }
        }

        /** Fin: la capa se oculta (se reutiliza) y la ventana real queda con la transparencia de MIUI. */
        void end() {
            renderHandler().removeCallbacks(watchdog);
            // Modo ligero: la que se cierra sigue como un rectángulo de color, sin OpenGL.
            boolean rect = ghosted && lightNow() && RectGhost.start(this);
            if (rect) ghosted = false;
            else if (ghosted && !goAlone()) ghosted = false;
            SurfaceControl l = layer;
            synchronized (inverse) {
                layer = null;                                  // ningún hook vuelve a tocarla
            }
            if (ending == this) ending = null;
            SurfaceControl window = leash;
            SurfaceControl fade = null;
            try {
                SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                Ghost gh = ghost;
                if (ghosted) {
                    // Sigue sola: la capa, arriba del todo y a la vista, hasta que llegue al icono.
                    if (window != null && window.isValid()) t.setAlpha(window, 0f);
                    if (l != null && l.isValid() && !otherSessionOnLayer()) {
                        SurfaceControl below = ghostBelow;
                        ghostBelow = null;
                        if (below != null && below.isValid()) Hidden.setRelativeLayer(t, l, below, -1);
                        else t.setLayer(l, Z_TOP);
                        t.setAlpha(l, 1f);
                        t.setVisibility(l, true);
                    }
                } else {
                    if (rect && window != null && window.isValid()) t.setAlpha(window, 0f);   // la lleva el rectángulo
                    else if ((shown || hidWindow) && !handedBack && window != null && window.isValid()) t.setAlpha(window, alpha);
                    if (gh != null && startedOpen) {
                        finishGhost();                         // la app abierta ya lo tapa todo
                        gh = null;
                    }
                    if (l != null && l.isValid()) {
                        // Al pasar a recientes a mitad del gesto, la forma del genio se funde sobre la
                        // tarjeta en vez de desaparecer de golpe (saltaba a un rectángulo, issue #22).
                        if (gh == null && shown && why != null && why.contains("RECENTS")) fade = l;
                        else if (gh == null) t.setVisibility(l, false);
                        else if (!otherSessionOnLayer()) t.setLayer(l, Z_TOP);
                    }
                }
                t.apply();
            } catch (Throwable e) {
                Log.e("genio: error al terminar", e);
            }
            if (fade != null) fadeOut(fade);
            Log.i(String.format(java.util.Locale.ROOT,
                    "genio %s a %.0f%%: empieza con anchura %.0f%%, %d fotogramas en %d ms (%d tirones, máx. %.0f ms); "
                            + "primer dibujo a %s, ventana a %s, visible a %s (%s); captura %.1f ms (%d/%d fallidas), "
                            + "dibujo %.1f ms%s; curva 50/90/99%% a %d/%d/%d ms; fin: %s; "
                            + "tirones por búfer/dibujo/hilo/captura/otro: %d/%d/%d/%d/%d",
                    startedOpen ? "abrir" : "cerrar", GlRenderer.scale * 100, firstPw * 100, posted,
                    SystemClock.uptimeMillis() - startMs, janks, maxGapNs / 1e6f,
                    ms(firstPostMs), ms(leashMs), ms(shownMs),
                    !shown ? "no llegó a verse" : !startedOpen ? "al cerrar" : shownEarly ? "desde el icono" : "tarde",
                    captures == 0 ? 0f : captureNs / 1e6f / captures, failed, captures,
                    posted == 0 ? 0f : drawNs / 1e6f / posted,
                    (preloaded ? "; captura precargada" : "") + (iconPreloaded ? "; icono precargado" : "")
                            + (contentPreloaded ? "; con su última imagen" : ""), at50, at90, at99, why,
                    jankWhy[0], jankWhy[1], jankWhy[2], jankWhy[3], jankWhy[4]));
            autoQuality(janks, posted);
        }

        /**
         * Causa probable de un tirón: lo que tardó el fotograma anterior (búfer o dibujo), lo tarde
         * que empezó este ({@code late}, desde el refresco) o una captura a la vez.
         */
        private int jankCause(long late) {
            if (lastSwapNs > 12_000_000L) return 0;
            if (lastWorkNs > 12_000_000L) return 1;
            if (late > 12_000_000L) return 2;
            if (capturing || lastCapturing) return 3;
            return 4;
        }

        /** Otra sesión ya colocó la capa sobre su ventana. */
        private boolean otherSessionOnLayer() {
            Session s = session;
            return s != null && s != this && s.started;
        }

        /** Hilo de dibujo: este genio pasa a seguir solo (se queda con su contenido). */
        private boolean goAlone() {
            GlRenderer.Content c = GlRenderer.detach(this);
            if (c == null) return false;
            Ghost old = ghost;
            if (old != null) finishGhost();
            RectF raw = new RectF(), goal = new RectF();
            float[] vel = new float[4];
            float r, b;
            synchronized (lock) {
                raw.set(pendingCur);
                goal.set(tapRect);
                System.arraycopy(edgeVel, 0, vel, 0, 4);
                r = pendingRadius;
                b = pendingBend;
            }
            java.lang.ref.WeakReference<View> v = targetView;
            ghost = new Ghost(c, full, raw, goal, vel, r, b, bgNow(), iconReady, leash, v);
            Ghost.schedule();
            final View iconView = v == null ? null : v.get();
            final Icons ic = icons;
            if (iconView != null && ic != null) {
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        ic.setVisible(iconView, false);         // MIUI lo enseña al abandonar el cierre
                    }
                });
            }
            Log.i("genio: la app que se cierra sigue sola hasta su icono");
            return true;
        }

        private String ms(long t) {
            return t == 0 ? "—" : (t - startMs) + " ms";
        }
    }

    /**
     * Estilo Profundidad (ver {@link #STYLE_DEPTH}): el rectángulo de MIUI tal cual (su
     * animación normal), inclinado según el avance {@code pw} y con desenfoque según la velocidad
     * de cada borde ({@code vel}, px/s, ya suavizada; null = sin estela).
     */
    private static void depth(RectF cur, RectF icon, RectF full, float dir, float ramp, float radius,
                              boolean iconReady, float[] vel, float pw, GlRenderer.Geo out) {
        out.cur.set(cur);
        out.icon.set(icon);
        out.pw = pw;
        out.lead = out.lag = 1f;
        out.cup = 2f;
        out.radius = radius;
        // Como en HyperOS: al salir, el icono crece y se funde con la app; al volver, al revés.
        out.iconMix = iconMix(pw, dir, iconReady);
        out.splashIcon = 0f;
        out.bend = 0f;
        out.flip = 0f;
        // La hoja: trapecio en cuanto sale del icono, inclinada casi todo el viaje y recta al
        // llegar. Solo depende del avance, así que cerrar es abrir al revés, fotograma a fotograma.
        // Arriba se ve 1 / (1 + k) de abajo: para que mida (1 − estrechamiento), k = e / (1 − e).
        float e = Math.max(0f, Math.min(0.9f, depthTaper));
        float k = e / (1f - e);
        float shape = smooth(pw / DEPTH_RISE) * (1f - smooth((pw - DEPTH_SETTLE) / (1f - DEPTH_SETTLE)));
        out.tiltTarget = k * shape * ramp;
        applyTilt(out, out.tiltTarget);
        motion(vel, ramp, pw, dir, full, out);
    }

    /**
     * Desenfoque de movimiento (todos los estilos desde la 3.1.0): cada borde se difumina en la
     * dirección en la que va, según su velocidad {@code vel} (px/s; null = sin estela).
     */
    private static void motion(float[] vel, float ramp, float pw, float dir, RectF full, GlRenderer.Geo out) {
        java.util.Arrays.fill(out.vel, 0f);
        if (vel == null || depthBlur <= 0f) return;
        float amt = depthBlur * ramp;
        // Muy cerca del icono la estela se apaga: aterriza nítido, sin «manchar» el icono. Y al
        // abrir, en el último tramo (el muelle llegando a pantalla completa), también: la app
        // queda nítida justo cuando la ventana de verdad la sustituye.
        float env = smooth((pw - 0.04f) / 0.16f) * lerp(smooth((1f - pw) / 0.07f), 1f, dir);
        float h = Math.max(1f, full.height());
        float maxBlur = DEPTH_MAX_BLUR * h;
        float most = 0f;
        for (int i = 0; i < 4; i++) {
            float v = vel[i] * DEPTH_SHUTTER_S * amt * env;
            out.vel[i] = Math.max(-maxBlur, Math.min(maxBlur, v));
            most = Math.max(most, Math.abs(out.vel[i]));
        }
        // Menos de medio píxel de estela no se ve: a cero, y así se deja de redibujar.
        if (most < 0.5f) java.util.Arrays.fill(out.vel, 0f);
    }

    /**
     * Pone la hoja inclinada {@code a}: arriba está {@code a} más lejos que abajo, en fracción de
     * la distancia a la cámara. Gira sobre el borde de abajo (z = 0 allí, z = a arriba; lineal,
     * ver GlRenderer.PERSP): abajo se ve del tamaño del rectángulo de MIUI y nunca se sale de la
     * pantalla; arriba se aleja y se ve más estrecha.
     */
    private static void applyTilt(GlRenderer.Geo g, float a) {
        float hh = Math.max(1f, g.cur.height() * 0.5f);
        float k = Math.max(0f, a);
        g.tilt = -k / (2f * hh);                            // y0 < 0 es arriba: allí más lejos
        g.curl = 0f;
        g.push = k / 2f;                                    // en el centro, a medio camino
    }

    /**
     * Forma del genio a partir del rectángulo de MIUI ({@code cur}, se modifica): extremo lejano
     * adelantado y cercano retrasado, en horizontal (shader) y en vertical (aquí, como
     * desplazamiento sobre el rectángulo: con fuerza 0 es el de MIUI), más la lámpara.
     */
    private static void shape(RectF cur, RectF icon, RectF full, float dir, float ramp, float lift,
                              float radius, float bend, float flip, boolean iconReady, float[] vel, GlRenderer.Geo out) {
        float pw = progress(cur.width(), icon.width(), full.width());
        shapeOnly(cur, icon, full, dir, ramp, lift, radius, bend, flip, iconReady, vel, out);
        // Profundidad ya pone su estela dentro e iOS no lleva; el genio, aquí.
        if (style == STYLE_CLASSIC) motion(vel, ramp, pw, dir, full, out);
    }

    private static void shapeOnly(RectF cur, RectF icon, RectF full, float dir, float ramp, float lift,
                                  float radius, float bend, float flip, boolean iconReady, float[] vel, GlRenderer.Geo out) {
        float pw = progress(cur.width(), icon.width(), full.width());
        float ph = progress(cur.height(), icon.height(), full.height());
        float g = strength * ramp;
        // Curvatura, datos 3D y estela a cero; cada estilo pone lo suyo.
        out.tilt = out.curl = out.push = 0f;
        java.util.Arrays.fill(out.ex, 0f);
        java.util.Arrays.fill(out.ex2, 0f);
        java.util.Arrays.fill(out.vel, 0f);
        if (style == STYLE_DEPTH) {
            depth(cur, icon, full, dir, ramp, radius, iconReady, vel, pw, out);
            return;
        }
        if (style == STYLE_IOS) {
            // Las 4 esquinas, las del rectángulo de MIUI: en una sesión les da su muelle iosStep.
            out.cur.set(cur);
            out.icon.set(icon);
            out.pw = pw;
            // g.y y g.z en el shader: fuerza y largo de la sombra (aparece al salir del icono).
            out.lead = IOS_SHADOW * smooth(pw * 3f);
            out.lag = IOS_SHADOW_LEN * full.width();
            out.cup = 3f;
            out.radius = radius;
            // El icono se va en seguida al abrir y vuelve en el último tramo al cerrar.
            out.iconMix = iconReady ? 1f - smooth(pw / IOS_ICON) : 0f;
            out.splashIcon = 0f;
            out.bend = 0f;
            out.flip = 0f;
            out.ex[0] = cur.left; out.ex[1] = cur.top; out.ex[2] = cur.right; out.ex[3] = cur.top;
            out.ex2[0] = cur.right; out.ex2[1] = cur.bottom; out.ex2[2] = cur.left; out.ex2[3] = cur.bottom;
            return;
        }
        out.cup = 0f;
        float lead = 1f / (1f + LEAD * g), lag = 1f + LAG * g;
        // Cerca del icono la deformación se apaga (CUP_FADE): q^lead tiene
        // pendiente infinita en 0 y, sin esto, al empezar a abrir el extremo lejano saltaba de
        // golpe y al acabar de cerrar el genio llegaba al icono con un tirón. El shader hace lo mismo.
        float env = smooth(ph / CUP_FADE);
        float far = ((float) Math.pow(ph, lead) - ph) * env, near = ((float) Math.pow(ph, lag) - ph) * env;
        far *= lerp(1f, CLOSE_RISE, dir);
        // El extremo lejano (arriba, o abajo si el icono está arriba) se adelanta; el cercano se retrasa.
        cur.top += (full.top - icon.top) * lerp(far, near, flip) + lift * (1f - flip);
        cur.bottom += (full.bottom - icon.bottom) * lerp(near, far, flip);
        float minH = Math.max(8f, icon.height() * 0.5f);
        if (cur.bottom - cur.top < minH) {
            float c = lerp(cur.bottom - minH, cur.top, flip);
            cur.top = c;
            cur.bottom = c + minH;
        }
        out.cur.set(cur);
        out.icon.set(icon);
        out.pw = pw;
        out.lead = lead;
        out.lag = lag;
        out.radius = radius;
        out.iconMix = iconMix(pw, dir, iconReady);
        out.splashIcon = 0f;
        out.bend = bend;
        out.flip = flip;
    }

    /**
     * Cuánto es icono (1) y cuánto app (0) el genio con esta apertura; {@code dir} 0 = abrir,
     * 1 = cerrar (en medio, al interrumpir). Ver {@link #OPEN_MIX_START}.
     */
    private static float iconMix(float pw, float dir, boolean iconReady) {
        if (!iconReady) return 0f;
        float start = lerp(OPEN_MIX_START, CLOSE_MIX_START, dir), end = lerp(OPEN_MIX_END, CLOSE_MIX_END, dir);
        return 1f - smooth((pw - end) / (start - end));
    }

    /**
     * Genio al derecho (0, icono en la parte de abajo) o al revés (1, icono arriba). En iOS, la zona
     * del icono: 1 abajo (copa), -1 arriba (copa al revés), 0 en el centro (recta).
     */
    private static float flipFor(RectF icon, RectF full) {
        float y = (icon.centerY() - full.top) / Math.max(1f, full.height());
        if (style == STYLE_IOS) return smooth((y - IOS_BOTTOM) / IOS_BAND + 0.5f) - smooth((IOS_TOP - y) / IOS_BAND + 0.5f);
        return smooth((FLIP_HI - y) / (FLIP_HI - FLIP_LO));
    }

    /** Hilo de dibujo: el genio suelto termina; su icono real vuelve y su contenido se libera. */
    private static void finishGhost() {
        final Ghost g = ghost;
        if (g == null) return;
        ghost = null;
        final View v = g.iconView == null ? null : g.iconView.get();
        final Icons ic = icons;
        if (v != null && ic != null) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    ic.setVisible(v, true);
                }
            });
        }
        renderHandler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (ghost == null && session == null && ending == null) {
                    SurfaceControl l = GlRenderer.layer;
                    if (l != null && l.isValid()) {
                        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                        t.setVisibility(l, false);
                        t.apply();
                    }
                }
                GlRenderer.release(g.content);
            }
        }, GHOST_HANDOFF_MS);
        Log.i(String.format(java.util.Locale.ROOT, "genio suelto: llegó al icono en %d ms",
                (System.nanoTime() - g.startNs) / 1_000_000L));
    }

    /**
     * La app que se cierra mientras se abre otra: MIUI deja su animación y el genio sigue solo
     * hasta el icono, con un muelle por cada borde que parte de la velocidad que llevaba. Se
     * dibuja en la misma capa, debajo del genio de la app que se abre. Solo hilo de dibujo.
     */
    private static final class Ghost {
        final GlRenderer.Content content;
        final RectF full, icon, cur = new RectF(), tmp = new RectF();
        final float[] pos = new float[4], vel, goal = new float[4];
        final float startRadius, iconRadius;
        final int bg;
        final boolean iconReady;
        final SurfaceControl leash;
        final java.lang.ref.WeakReference<View> iconView;
        /** Apertura del último fotograma (para el oscurecido del escritorio). */
        volatile float lastPw = 1f;
        final GlRenderer.Geo geo = new GlRenderer.Geo();
        final long startNs = System.nanoTime();
        long lastNs = startNs;
        float bend;

        Ghost(GlRenderer.Content content, RectF full, RectF cur, RectF icon, float[] vel, float radius,
              float bend, int bg, boolean iconReady, SurfaceControl leash, java.lang.ref.WeakReference<View> iconView) {
            this.content = content;
            this.full = new RectF(full);
            this.icon = new RectF(icon);
            this.vel = vel;
            this.startRadius = radius;
            this.iconRadius = icon.width() * 0.22f;
            this.bend = bend;
            this.bg = bg;
            this.iconReady = iconReady;
            this.leash = leash;
            this.iconView = iconView;
            this.lastPw = progress(cur.width(), icon.width(), full.width());
            pos[0] = cur.left;
            pos[1] = cur.top;
            pos[2] = cur.right;
            pos[3] = cur.bottom;
            goal[0] = icon.left;
            goal[1] = icon.top;
            goal[2] = icon.right;
            goal[3] = icon.bottom;
        }

        /** Avanza hasta {@code now} y lo dibuja; false si falla el dibujo. Si llega, termina. */
        boolean drawStep(long now) {
            float dt = Math.min(0.05f, Math.max(0f, (now - lastNs) / 1e9f));
            lastNs = now;
            float c = 2f * GHOST_ZETA * (float) Math.sqrt(GHOST_K);
            boolean still = true;
            for (int i = 0; i < 4; i++) {
                for (float left = dt; left > 0f; left -= 0.004f) {
                    float h = Math.min(0.004f, left);
                    vel[i] += (GHOST_K * (goal[i] - pos[i]) - c * vel[i]) * h;
                    pos[i] += vel[i] * h;
                }
                if (Math.abs(goal[i] - pos[i]) > 0.5f || Math.abs(vel[i]) > 8f) still = false;
            }
            bend *= (float) Math.exp(-dt / 0.12f);
            boolean arrived = still || now - startNs > GHOST_MAX_NS;
            if (arrived) {                                       // última imagen: justo el icono
                System.arraycopy(goal, 0, pos, 0, 4);
                bend = 0f;
            }
            cur.set(pos[0], pos[1], pos[2], pos[3]);
            float pw = progress(cur.width(), icon.width(), full.width());
            lastPw = pw;
            tmp.set(cur);
            shape(tmp, icon, full, 1f, 1f, 0f, lerp(iconRadius, startRadius, pw), bend, flipFor(icon, full), iconReady, vel, geo);
            boolean ok = GlRenderer.drawGenie(content, true, geo, full.width() * 0.5f, full.height(), bg);
            if (arrived) {
                Haptics.land();
                finishGhost();
            }
            return ok;
        }

        /**
         * Fotogramas propios mientras no los dé otra animación: si otro genio dibujó hace nada,
         * ya pintó este; si no, este fotograma lo pinta él (y al otro con su última forma).
         */
        static void schedule() {
            android.view.Choreographer.getInstance().postFrameCallback(TICK);
        }

        private static long lastTickNs;

        private static final android.view.Choreographer.FrameCallback TICK = new android.view.Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                Ghost g = ghost;
                if (g == null) return;
                if (lastTickNs != 0 && frameTimeNanos - lastTickNs < periodNs()) {
                    schedule();
                    return;
                }
                lastTickNs = frameTimeNanos;
                long now = System.nanoTime();
                Session s = session;
                boolean other = s != null && s.started && !s.finishing && s.posted > 0;
                if (other && now - s.lastDrawNs < Math.max(SHARED_FRAME_NS, periodNs() + PACE_SLACK_NS * 2)) {
                    schedule();
                    return;
                }
                boolean ok = GlRenderer.beginFrame(dimFor(g.lastPw)) && g.drawStep(now);
                if (ok && other) ok = s.redraw();
                ok = ok && GlRenderer.endFrame();
                if (!ok) {
                    finishGhost();
                    return;
                }
                if (ghost == g) schedule();
            }
        };
    }

    /** Color dominante de un icono: media de sus píxeles opacos, pesando más los más vivos. */
    static int accent(Bitmap b) {
        try {
            Bitmap s = Bitmap.createScaledBitmap(b, 12, 12, true);
            float r = 0f, g = 0f, bl = 0f, sum = 0f;
            float[] hsv = new float[3];
            for (int y = 0; y < 12; y++) {
                for (int x = 0; x < 12; x++) {
                    int c = s.getPixel(x, y);
                    if ((c >>> 24) < 160) continue;
                    android.graphics.Color.colorToHSV(c, hsv);
                    float w = hsv[1] * hsv[2] + 0.05f;
                    r += ((c >> 16) & 255) * w;
                    g += ((c >> 8) & 255) * w;
                    bl += (c & 255) * w;
                    sum += w;
                }
            }
            if (s != b) s.recycle();
            return sum <= 0f ? 0 : 0xFF000000 | (Math.round(r / sum) << 16) | (Math.round(g / sum) << 8) | Math.round(bl / sum);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Modo ligero de la animación paralela: la app que se cierra mientras se abre otra sigue hasta
     * su icono como un rectángulo de su color en una capa de color del sistema (sin OpenGL ni
     * captura), con el mismo muelle por borde que el genio suelto. Así la GPU solo dibuja el genio
     * de la app nueva. Hilo de dibujo.
     */
    private static final class RectGhost implements android.view.Choreographer.FrameCallback {
        final SurfaceControl sc;
        final float[] pos = new float[4], vel = new float[4], goal = new float[4];
        final RectF full;
        final float startRadius, iconRadius, iconW;
        final java.lang.ref.WeakReference<View> iconView;
        final long startNs = System.nanoTime();
        long lastNs = startNs;
        final Rect crop = new Rect();
        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();

        private RectGhost(SurfaceControl sc, Session s, RectF cur, RectF icon, float radius) {
            this.sc = sc;
            full = new RectF(s.full);
            pos[0] = cur.left;
            pos[1] = cur.top;
            pos[2] = cur.right;
            pos[3] = cur.bottom;
            goal[0] = icon.left;
            goal[1] = icon.top;
            goal[2] = icon.right;
            goal[3] = icon.bottom;
            startRadius = radius;
            iconRadius = icon.width() * 0.22f;
            iconW = icon.width();
            iconView = s.targetView;
        }

        /** Arranca el rectángulo desde donde iba el genio de {@code s}; false si no se puede. */
        static boolean start(Session s) {
            RectF cur = new RectF(), icon = new RectF();
            float[] v = new float[4];
            float radius;
            synchronized (s.lock) {
                if (!s.shown || s.tapRect.isEmpty()) return false;
                cur.set(s.pendingCur);
                icon.set(s.tapRect);
                System.arraycopy(s.edgeVel, 0, v, 0, 4);
                radius = s.pendingRadius;
            }
            SurfaceControl parent = GlRenderer.parent;
            if (parent == null || !parent.isValid() || cur.isEmpty()) return false;
            SurfaceControl sc = Hidden.colorLayer(parent, "IceHome rect");
            if (sc == null) return false;
            int c = s.iconColor;
            if (c == 0) {                                    // sin color: blanco o negro según el tema
                boolean night = (android.content.res.Resources.getSystem().getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
                c = night ? 0xFF1C1C1E : 0xFFF2F2F7;
            }
            RectGhost g = new RectGhost(sc, s, cur, icon, radius);
            try {
                System.arraycopy(v, 0, g.vel, 0, 4);
                Hidden.setColor(g.t, sc, c);
                SurfaceControl below = ghostBelow;
                ghostBelow = null;
                SurfaceControl gl = GlRenderer.layer;
                if (below != null && below.isValid()) Hidden.setRelativeLayer(g.t, sc, below, -1);
                else if (gl != null && gl.isValid()) Hidden.setRelativeLayer(g.t, sc, gl, -1);   // por debajo del genio nuevo
                else g.t.setLayer(sc, Z_TOP);
                g.place();
                g.t.setVisibility(sc, true);
                g.t.apply();
            } catch (Throwable e) {
                sc.release();                                // sin rectángulo: sigue el genio normal
                Log.w("genio: no se pudo poner el rectángulo: " + e);
                return false;
            }
            final View iv = g.iconView == null ? null : g.iconView.get();
            final Icons ic = icons;
            if (iv != null && ic != null) {
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        ic.setVisible(iv, false);              // lo enseña al llegar
                    }
                });
            }
            android.view.Choreographer.getInstance().postFrameCallback(g);
            Log.i("genio: la app que se cierra sigue como rectángulo (modo ligero)");
            return true;
        }

        private void place() {
            float l = pos[0], tp = pos[1], w = Math.max(1f, pos[2] - pos[0]), h = Math.max(1f, pos[3] - pos[1]);
            crop.set(0, 0, Math.round(w), Math.round(h));
            t.setPosition(sc, l, tp);
            t.setCrop(sc, crop);
            float pw = progress(w, iconW, full.width());
            Hidden.setCornerRadius(t, sc, Math.min(Math.min(w, h) / 2f, lerp(iconRadius, startRadius, pw)));
        }

        @Override
        public void doFrame(long frameNs) {
            try {
                if (!sc.isValid()) {
                    finish();                                // que el icono no se quede escondido
                    return;
                }
                long now = System.nanoTime();
                float dt = Math.min(0.05f, Math.max(0f, (now - lastNs) / 1e9f));
                lastNs = now;
                float c = 2f * GHOST_ZETA * (float) Math.sqrt(GHOST_K);
                boolean still = true;
                for (int i = 0; i < 4; i++) {
                    for (float left = dt; left > 0f; left -= 0.004f) {
                        float h = Math.min(0.004f, left);
                        vel[i] += (GHOST_K * (goal[i] - pos[i]) - c * vel[i]) * h;
                        pos[i] += vel[i] * h;
                    }
                    if (Math.abs(goal[i] - pos[i]) > 0.5f || Math.abs(vel[i]) > 8f) still = false;
                }
                if (still || now - startNs > GHOST_MAX_NS) {
                    finish();
                    return;
                }
                place();
                t.apply();
                android.view.Choreographer.getInstance().postFrameCallback(this);
            } catch (Throwable e) {
                Log.w("genio: fallo del rectángulo: " + e);
                finish();
            }
        }

        private void finish() {
            final View iv = iconView == null ? null : iconView.get();
            final Icons ic = icons;
            if (iv != null && ic != null) {
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        ic.setVisible(iv, true);
                    }
                });
            }
            // El icono se enseña en el hilo principal: el rectángulo se quita un poco después.
            renderHandler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (sc.isValid()) {
                            SurfaceControl.Transaction r = new SurfaceControl.Transaction();
                            r.reparent(sc, null);
                            r.apply();
                            sc.release();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }, GHOST_HANDOFF_MS);
            Haptics.land();
            Log.i(String.format(java.util.Locale.ROOT, "genio: rectángulo en el icono en %d ms",
                    (System.nanoTime() - startNs) / 1_000_000L));
        }
    }

    private static float progress(float v, float from, float to) {
        return clamp((v - from) / Math.max(1f, to - from));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Como un tope en ±max, pero que se acerca a él suavemente. */
    private static float soft(float v, float max) {
        return max <= 0f ? 0f : max * (float) Math.tanh(v / max);
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float smooth(float v) {
        v = clamp(v);
        return v * v * (3f - 2f * v);
    }

    private Genie() {
    }
}
