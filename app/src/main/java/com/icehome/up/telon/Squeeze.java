package com.icehome.up.telon;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;

import com.icehome.up.Threads;
import com.icehome.up.xp.XC_MethodHook;
import com.icehome.up.xp.XposedBridge;
import com.icehome.up.xp.XposedHelpers;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Pantalla que se encoge al hacer una captura, como al llamar a Gemini en los Pixel: toda la
 * pantalla se hunde hacia dentro (y un poco a la izquierda) con un marco negro de esquinas
 * redondeadas, y vuelve con un muelle.
 *
 * <p>Cómo se encoge "toda la pantalla": Android 16 agrupa las ventanas de apps, escritorio y casi
 * todo lo del sistema en zonas llamadas AppZoomOut (es lo que usa el Pixel). Quien "organiza" esas
 * zonas recibe sus capas (leash) y puede escalarlas y moverlas. HyperOS 3 trae las zonas, pero su
 * SystemUI no llega a registrar el organizador, así que nadie las usa: lo registramos nosotros. La
 * barra de estado, la de navegación y la ventana de la captura de MIUI quedan fuera de esas zonas,
 * por eso no se encogen.
 *
 * <p>Cuándo: cuando la imagen ya está hecha, con el aviso de {@link SqueezeShot#ACTION_CAPTURED}.
 * La difusión "miui.intent.TAKE_SCREENSHOT" (IsFinished=false) de MIUI llega antes de la foto:
 * solo se usa como plan B. Si encogiéramos con ella, la captura saldría encogida.
 *
 * <p>Todo pasa en el hilo principal de SystemUI salvo el registro, que es una llamada al sistema y
 * va en su propio hilo para no bloquear la interfaz.
 */
public final class Squeeze {

    // Ajustes: los pone Tune.reload.
    static volatile boolean enabled;
    /** También al abrir el asistente (Gemini). */
    static volatile boolean assist;
    /** Tamaño de la pantalla en el punto más hundido (0,75 = 75 %; el Pixel se queda en 0,975). */
    static volatile float size = 0.75f;
    /** 0 = se encoge recta hacia el centro; 1 = se va bastante a la izquierda. */
    static volatile float left = 0.5f;
    static volatile float speed = 1f;
    static volatile boolean frame = true;
    // Gemini (página propia en Sistema). Usa la misma forma de encoger que la captura.
    /** Tamaño de la pantalla en el punto más hundido al llamar a Gemini. */
    static volatile float geminiSize = 0.75f;
    /** Velocidad al llamar a Gemini (más lenta que la captura: hay luz que ver). */
    static volatile float geminiSpeed = 0.55f;
    /** Segundos por vuelta de los colores; 0 = quietos. */
    static volatile float geminiSpin = 3.5f;
    /** Intensidad de la luz: 0 = sin luz, 1 = normal. */
    static volatile float geminiGlow = 1f;
    /** Colores de la luz: 0 = Gemini, 1 = Monet, 2 = arcoíris. */
    static volatile int geminiColors = 1;
    /** Color del marco: 0 = negro, 1 = Monet (el tono oscuro del fondo de pantalla), 2 = marrón. */
    static volatile int color = 2;

    // Medidas del vídeo del Pixel (fracciones del ancho de la pantalla).
    /** Cuánto se mete el marco negro por encima del borde de la pantalla encogida. */
    private static final float OVERLAP = 0.026f;
    /** Radio de las esquinas del hueco del marco, en el punto más hundido. */
    private static final float HOLE_RADIUS = 0.045f;
    /** Desplazamiento máximo a la izquierda (con "Hacia la izquierda" = 1). */
    private static final float MAX_LEFT = 0.05f;

    // Curva medida en el vídeo: entra con un muelle sin rebote (ω = 9 rad/s, ~0,4 s) y sale con
    // otro más rápido (ω = 22 rad/s, ~0,2 s más una cola corta). Con velocidad 1.
    private static final float W_IN = 9f, T_IN = 0.42f, W_OUT = 22f, X_OUT_END = 8f;

    private static Context ctx;
    private static Handler main;
    private static boolean installed;

    /** Capas de las zonas AppZoomOut (solo se tocan en el hilo principal). */
    private static final List<SurfaceControl> leashes = new ArrayList<>();
    private static boolean registering;
    /** Organizador de AppZoomOut de SystemUI, si su código lo crea (para no pisarlo). */
    private static volatile Object shellOrganizer;

    private static WindowManager wm;
    private static FrameView view;
    private static ValueAnimator anim;
    private static Method setCornerRadius;
    private static float cornerRadius;
    private static float lastP;
    private static long lastStart;
    /** Cuándo avisó la app de capturas de que la imagen ya estaba hecha. */
    private static volatile long capturedAt = -100000;

    /**
     * Al cargar SystemUI, antes de que exista su Dagger: anota el organizador AppZoomOut de
     * SystemUI si llega a crearse. En HyperOS 3 se crea pero no se registra; si alguna versión sí
     * lo registra, usamos sus capas en vez de registrar el nuestro (registrar otro lo echaría).
     */
    public static void watch(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClassIfExists(
                "com.android.wm.shell.appzoomout.AppZoomOutDisplayAreaOrganizer", cl);
        if (c == null) return;
        XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                shellOrganizer = p.thisObject;
            }
        });
    }

    /** Con el contexto de SystemUI: escucha las capturas. */
    public static void install(Context c) {
        ctx = c;
        main = new Handler(Looper.getMainLooper());
        IntentFilter f = new IntentFilter("miui.intent.TAKE_SCREENSHOT");
        f.addAction(SqueezeShot.ACTION_CAPTURED);
        // El sistema cierra los diálogos con motivo «assist» al abrir el asistente (Gemini), sea con
        // el botón de encendido, la tecla de asistente o el gesto de la esquina. Lo recibe SystemUI.
        f.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (SqueezeShot.ACTION_CAPTURED.equals(i.getAction())) {
                    // La app de capturas ya tiene la imagen: ahora sí se puede encoger.
                    capturedAt = android.os.SystemClock.uptimeMillis();
                    start(enabled, false);
                } else if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(i.getAction())) {
                    // Aquí no hay foto que estropear: se encoge en cuanto se llama al asistente,
                    // y Gemini aparece mientras la pantalla vuelve, como en los Pixel.
                    if ("assist".equals(i.getStringExtra("reason"))) {
                        Log.i("captura: asistente abierto");
                        start(assist, true);
                    }
                } else if (!i.getBooleanExtra("IsFinished", true)) {
                    // IsFinished=false llega ANTES de la foto (ver SqueezeShot): encoger aquí
                    // hacía que a veces la captura saliera encogida y con el marco. Solo es el plan
                    // B, por si la app de capturas no puede avisar (sin su ámbito en LSPosed).
                    main.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (android.os.SystemClock.uptimeMillis() - capturedAt > SqueezeShot.FALLBACK_MS + 900) {
                                // La app de capturas no avisó: casi siempre es que falta su ámbito en
                                // LSPosed. Se apunta para que la app de HyperSpring lo avise en Inicio.
                                if (enabled) Log.state("shot_missing", String.valueOf(System.currentTimeMillis()));
                                start(enabled, false);
                            }
                        }
                    }, SqueezeShot.FALLBACK_MS);
                }
            }
        };
        // La envía otra app (com.miui.screenshot), así que el receptor tiene que ser exportado.
        if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(r, f, null, main, Context.RECEIVER_EXPORTED);
        else c.registerReceiver(r, f, null, main);
        installed = true;
        main.post(new Runnable() {
            @Override
            public void run() {
                prepare();
            }
        });
    }

    /** Tras cambiar los ajustes: si se acaba de encender, se prepara ya. */
    static void retune() {
        if (!installed) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                prepare();
            }
        });
    }

    /**
     * Prepara la ventana del marco y las capas, solo si la función está encendida. La ventana se
     * añade ahora y no en la primera captura: así la de MIUI, que se crea en cada captura, queda
     * siempre por encima del marco.
     */
    private static void prepare() {
        if (!enabled && !assist) return;
        try {
            if (view == null) addWindow();
        } catch (Throwable t) {
            Log.e("captura: no se pudo añadir el marco", t);
        }
        if (leashes.isEmpty() && !registering) {
            registering = true;
            Threads.start("HyperSpring-squeeze", android.os.Process.THREAD_PRIORITY_BACKGROUND).post(new Runnable() {
                @Override
                public void run() {
                    register();
                }
            });
        }
    }

    // ------------------------------------------------------------------ capas AppZoomOut

    /** En su hilo: consigue las capas de las zonas AppZoomOut de la pantalla principal. */
    private static void register() {
        final List<SurfaceControl> got = new ArrayList<>();
        try {
            Object shell = shellOrganizer;
            Map<?, ?> map = null;
            if (shell != null) {
                try {
                    map = (Map<?, ?>) XposedHelpers.getObjectField(shell, "mDisplayAreaTokenMap");
                } catch (Throwable ignored) {
                }
            }
            if (map != null && !map.isEmpty()) {
                // SystemUI ya las organiza: usamos sus capas (son las mismas superficies).
                for (Object v : new ArrayList<>(map.values())) {
                    if (v instanceof SurfaceControl) got.add((SurfaceControl) v);
                }
                Log.i("captura: usando el organizador de SystemUI (" + got.size() + " zonas)");
            } else {
                // DisplayAreaOrganizer es una API oculta: por reflexión (SystemUI puede usarla).
                // La clase base no hace nada al recibir avisos; solo queremos las capas iniciales.
                Class<?> dao = Class.forName("android.window.DisplayAreaOrganizer");
                int feature = dao.getField("FEATURE_APP_ZOOM_OUT").getInt(null);
                Executor ex = ctx.getMainExecutor();
                Object org = dao.getConstructor(Executor.class).newInstance(ex);
                List<?> infos = (List<?>) dao.getMethod("registerOrganizer", int.class).invoke(org, feature);
                for (Object info : infos) {
                    Object dai = info.getClass().getMethod("getDisplayAreaInfo").invoke(info);
                    int display = dai.getClass().getField("displayId").getInt(dai);
                    SurfaceControl sc = (SurfaceControl) info.getClass().getMethod("getLeash").invoke(info);
                    if (display == Display.DEFAULT_DISPLAY && sc != null) got.add(sc);
                }
                Log.i("captura: " + got.size() + " zonas AppZoomOut listas");
            }
        } catch (Throwable t) {
            Log.e("captura: no se pudieron conseguir las zonas AppZoomOut (¿otra versión de Android?)", t);
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                leashes.addAll(got);
                // Si falló, no se reintenta en cada captura: hasta reiniciar la interfaz.
            }
        });
    }

    // ------------------------------------------------------------------ animación

    /**
     * @param on si esta función (captura o asistente) está encendida.
     * @param gemini si es el asistente: además del marco, entra un halo de luz que gira.
     */
    private static void start(boolean on, boolean gemini) {
        if (!on || speed <= 0f) return;
        if (leashes.isEmpty()) {
            prepare();
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastStart < 150) return;       // capturas seguidas: una sola animación
        lastStart = now;
        if (anim != null) anim.cancel();
        // Si ya estaba encogida (otra captura enseguida), sigue desde donde estaba.
        // Gemini tiene su propia velocidad y tamaño (en su página).
        final Curve curve = new Curve(gemini ? geminiSpeed : speed, lastP);
        geo.size = gemini ? geminiSize : -1f;
        final Rect screen = screenBounds(ctx);
        cornerRadius = windowCornerRadius(ctx);
        if (view != null) {
            // El color se mira en cada captura: el de Monet cambia con el fondo de pantalla.
            view.paint.setColor(frameColor());
            view.frame = frame;
            view.glow = gemini && geminiGlow > 0.01f;
            if (view.glow) view.setColors(glowColors());
            view.setVisibility(frame || view.glow ? View.VISIBLE : View.INVISIBLE);
        }
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setInterpolator(new LinearInterpolator());
        anim.setDuration(curve.durationMs());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                if (!apply(curve.at(a.getAnimatedFraction()), screen)) a.cancel();
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                reset();
            }
        });
        anim.start();
        Log.i("captura: pantalla encogida (" + leashes.size() + " zonas, marco #" + Integer.toHexString(frameColor()) + ")");
    }

    /**
     * Curva de la animación (compartida con la vista previa de MIUI, {@link SqueezeShot}, para
     * que las dos vayan a la par). Medida en el vídeo del Pixel: entra con un muelle sin rebote y
     * sale con otro más rápido.
     */
    static final class Curve {
        private final float tIn, tOut, wIn, wOut, peak, from;

        /** @param from dónde empieza (0 = en reposo; más si ya estaba encogida). */
        Curve(float speed, float from) {
            float sp = Math.max(0.3f, speed);
            tIn = T_IN / sp;
            wIn = W_IN * sp;
            wOut = W_OUT * sp;
            tOut = X_OUT_END / wOut;
            // Valor al final de la entrada (sin normalizar), para que el punto más hundido sea 1.
            peak = 1f - (1f + wIn * tIn) * (float) Math.exp(-wIn * tIn);
            this.from = from;
        }

        long durationMs() {
            return (long) ((tIn + tOut) * 1000f);
        }

        /** Progreso (0 = en reposo, 1 = lo más hundido) para una fracción del tiempo total. */
        float at(float fraction) {
            float t = fraction * (tIn + tOut);
            if (t < tIn) {
                // Entrada: muelle sin rebote, como al pulsar el botón en el Pixel.
                float in = (1f - (1f + wIn * t) * (float) Math.exp(-wIn * t)) / peak;
                return from + (1f - from) * in;
            }
            // Salida: arranca despacio, cae de golpe y deja una cola corta.
            float x = wOut * (t - tIn);
            return (1f + x) * (float) Math.exp(-x);
        }
    }

    /** Geometría de un fotograma: cómo se encoge la pantalla y dónde está el hueco del marco. */
    static final class Geo {
        float scale, x, y;           // escala y posición de la pantalla encogida
        float l, t, r, b, radius;    // hueco del marco
        /** Tamaño en el punto más hundido; menor que 0 = el de la captura ({@link Squeeze#size}). */
        float size = -1f;

        void set(float p, float w, float h, float corner) {
            float s = (1f - (size >= 0f ? size : Squeeze.size)) * p;   // fracción que encoge
            float dx = MAX_LEFT * left * w * p;           // hacia la izquierda
            scale = 1f - s;
            x = s * w / 2f - dx;
            y = s * h / 2f;
            // Hueco: la pantalla encogida, metiéndose un poco por encima de su borde. El borde
            // tapado no crece con el tamaño: si no, el marco salía muy grueso.
            float o = OVERLAP * w * p;
            l = x + o;
            t = y + o;
            r = x + w * scale - o;
            b = y + h * scale - o;
            radius = corner + (Math.max(corner, HOLE_RADIUS * w) - corner) * p;
        }
    }

    private static final Geo geo = new Geo();

    /** Un fotograma: p = 0 en reposo, 1 en el punto más hundido. False si algo falla. */
    private static boolean apply(float p, Rect screen) {
        lastP = p;
        float w = screen.width(), h = screen.height();
        geo.set(p, w, h, cornerRadius);
        float scale = geo.scale, x = geo.x, y = geo.y;
        try {
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            Rect crop = new Rect(0, 0, screen.width(), screen.height());
            for (SurfaceControl sc : leashes) {
                if (!sc.isValid()) continue;
                t.setCrop(sc, crop);
                t.setScale(sc, scale, scale);
                t.setPosition(sc, x, y);
                corner(t, sc, cornerRadius);
            }
            t.apply();
        } catch (Throwable e) {
            Log.e("captura: falló un fotograma", e);
            return false;
        }
        if (view != null && (view.frame || view.glow)) view.set(geo.l, geo.t, geo.r, geo.b, geo.radius, p);
        return true;
    }

    /** Deja las capas como estaban y esconde el marco. */
    private static void reset() {
        lastP = 0f;
        try {
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            for (SurfaceControl sc : leashes) {
                if (!sc.isValid()) continue;
                t.setCrop(sc, (Rect) null);
                t.setScale(sc, 1f, 1f);
                t.setPosition(sc, 0f, 0f);
                corner(t, sc, 0f);
            }
            t.apply();
        } catch (Throwable e) {
            Log.e("captura: no se pudo dejar la pantalla como estaba", e);
        }
        if (view != null) view.setVisibility(View.INVISIBLE);
    }

    /** setCornerRadius es oculta: por reflexión. Sin ella, esquinas rectas (las tapa el marco). */
    private static void corner(SurfaceControl.Transaction t, SurfaceControl sc, float r) {
        try {
            if (setCornerRadius == null) {
                setCornerRadius = SurfaceControl.Transaction.class.getMethod("setCornerRadius",
                        SurfaceControl.class, float.class);
            }
            setCornerRadius.invoke(t, sc, r);
        } catch (Throwable ignored) {
        }
    }

    static Rect screenBounds(Context c) {
        try {
            return new Rect(c.getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds());
        } catch (Throwable t) {
            android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
            return new Rect(0, 0, dm.widthPixels, dm.heightPixels);
        }
    }

    /** Radio de las esquinas de la pantalla, como lo calcula el propio sistema. */
    static float windowCornerRadius(Context ctx) {
        try {
            Class<?> u = Class.forName("com.android.internal.policy.ScreenDecorationsUtils");
            return (Float) u.getMethod("getWindowCornerRadius", Context.class).invoke(null, ctx);
        } catch (Throwable ignored) {
        }
        try {
            int id = ctx.getResources().getIdentifier("rounded_corner_radius", "dimen", "android");
            if (id != 0) return ctx.getResources().getDimension(id);
        } catch (Throwable ignored) {
        }
        return 0f;
    }

    /** Color del marco según el ajuste. */
    private static int frameColor() {
        if (color == 1) {
            try {
                // Monet (Android 12+): neutral2 es el gris teñido del color del fondo de pantalla;
                // el 800 es oscuro, para que el marco siga pareciendo el borde del móvil.
                return ctx.getColor(android.R.color.system_neutral2_800);
            } catch (Throwable ignored) {
            }
        } else if (color == 2) {
            try {
                // Marrón al estilo Monet: el tono oscuro de Monet llevado a un marrón cálido
                // (mismo brillo y algo de saturación, con el tono fijo en naranja-marrón).
                float[] hsv = new float[3];
                Color.colorToHSV(ctx.getColor(android.R.color.system_neutral2_800), hsv);
                hsv[0] = 24f;
                hsv[1] = Math.max(0.35f, Math.min(0.55f, hsv[1] * 3f));
                hsv[2] = Math.max(0.22f, Math.min(0.32f, hsv[2]));
                return Color.HSVToColor(hsv);
            } catch (Throwable ignored) {
            }
            return 0xFF3B2A20;
        }
        return Color.BLACK;
    }

    private static WindowManager wm() {
        if (wm == null) wm = ctx.getSystemService(WindowManager.class);
        return wm;
    }

    // ------------------------------------------------------------------ marco negro

    private static void addWindow() {
        // TYPE_NAVIGATION_BAR_PANEL (2024): por encima de las barras de estado y navegación, y en
        // la misma capa que la ventana de la captura de MIUI, que se crea después y queda encima.
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                2024,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setTitle("HyperSpringSqueeze");
        FrameView v = new FrameView(ctx);
        // Escondida hasta la primera captura: una ventana invisible no se dibuja ni gasta.
        v.setVisibility(View.INVISIBLE);
        wm().addView(v, lp);
        view = v;
    }

    /** Marco (negro o de color) que rodea la pantalla encogida, con el hueco de esquinas redondeadas. */
    private static final class FrameView extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private float l, t, r, b, radius, p;
        /** Qué se dibuja en esta animación: el marco (ajuste) y el halo (solo con Gemini). */
        boolean frame = true, glow;

        // Colores del halo; el primero se repite al final para que la vuelta no tenga corte.
        private int[] colors = GEMINI;
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Matrix spin = new android.graphics.Matrix();
        /** Degradado de colores alrededor del centro de la pantalla. Se crea una vez por paleta
         *  (crear uno en cada fotograma obliga a subir una textura nueva a la GPU). */
        private android.graphics.SweepGradient sweep;
        /** Shader de la luz (Android 13+). Null hasta el primer uso; si no compila, se usa
         *  el dibujo sencillo y no se vuelve a intentar. */
        private android.graphics.RuntimeShader glowShader;
        private boolean glowFailed;

        FrameView(Context c) {
            super(c);
            paint.setColor(Color.BLACK);
            path.setFillType(Path.FillType.EVEN_ODD);
            glowPaint.setStyle(Paint.Style.STROKE);
            glowPaint.setStrokeJoin(Paint.Join.ROUND);
        }

        void set(float l, float t, float r, float b, float radius, float p) {
            this.l = l;
            this.t = t;
            this.r = r;
            this.b = b;
            this.radius = radius;
            this.p = p;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (p <= 0.002f) return;
            if (frame) {
                // Pantalla entera menos el hueco (EVEN_ODD hace el agujero).
                path.reset();
                path.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
                path.addRoundRect(l, t, r, b, radius, radius, Path.Direction.CW);
                c.drawPath(path, paint);
            }
            if (glow) drawGlow(c);
        }

        /**
         * Halo de Gemini: la luz sale de los cuatro bordes del móvil y rellena el marco que rodea la
         * pantalla encogida, con los colores dando vueltas. Crece y se apaga con p, así
         * que llega con el hundimiento y desaparece cuando la pantalla vuelve.
         */
        private void drawGlow(Canvas c) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            if (sweep == null) sweep = new android.graphics.SweepGradient(cx, cy, colors, null);
            // Los colores giran con el reloj, no con p: así siguen dando vueltas aunque la
            // pantalla esté quieta en el punto más hundido.
            long turn = (long) (geminiSpin * 1000f);
            float angle = turn <= 0 ? 0f : (android.os.SystemClock.uptimeMillis() % turn) / (float) turn * 360f;
            spin.setRotate(angle, cx, cy);
            sweep.setLocalMatrix(spin);

            float light = Math.min(1f, p * 1.4f) * geminiGlow;
            // Grosor del marco: la distancia más grande del borde del móvil al hueco.
            float thick = Math.max(Math.max(l, t), Math.max(w - r, h - b));
            glowPaint.setStyle(Paint.Style.FILL);
            if (Build.VERSION.SDK_INT >= 33 && !glowFailed) {
                try {
                    // Todo en la GPU, con un solo rectángulo: el shader calcula para cada píxel
                    // si está en el marco y a qué distancia del borde. Antes eran un recorte y
                    // seis contornos grandes que la CPU tenía que rehacer en cada fotograma
                    // (cambiaban de tamaño), y eso daba tirones.
                    if (glowShader == null) glowShader = new android.graphics.RuntimeShader(GLOW_AGSL);
                    glowShader.setInputShader("sweep", sweep);
                    glowShader.setFloatUniform("size", w, h);
                    glowShader.setFloatUniform("hole", l, t, r, b);
                    glowShader.setFloatUniform("holeR", radius);
                    glowShader.setFloatUniform("outerR", cornerRadius);
                    glowShader.setFloatUniform("thick", thick);
                    glowShader.setFloatUniform("light", light);
                    glowPaint.setShader(glowShader);
                    glowPaint.setAlpha(255);
                    c.drawRect(0, 0, w, h, glowPaint);
                    postInvalidateOnAnimation();
                    return;
                } catch (Throwable e) {
                    glowFailed = true;
                    Log.e("captura: el shader de la luz de Gemini no compila, uso el sencillo", e);
                }
            }
            // Sin shader (Android 12 o si falla): el marco relleno de color, sin degradado.
            path.reset();
            path.addRect(0, 0, w, h, Path.Direction.CW);
            path.addRoundRect(l, t, r, b, radius, radius, Path.Direction.CW);
            glowPaint.setShader(sweep);
            glowPaint.setAlpha(alpha(light * 0.6f));
            c.drawPath(path, glowPaint);
            // Mientras haya halo, se sigue dibujando para que los colores no se paren.
            postInvalidateOnAnimation();
        }

        /** Cambia la paleta; el degradado se rehace en el siguiente fotograma. */
        void setColors(int[] c) {
            if (java.util.Arrays.equals(c, colors)) return;
            colors = c;
            sweep = null;
        }

        /** De 0..1 (o algo más, con la intensidad subida) a la opacidad de Paint, sin pasarse. */
        private static int alpha(float a) {
            return Math.max(0, Math.min(255, (int) (255f * a)));
        }
    }

    /**
     * Luz del marco de Gemini (AGSL). Para cada píxel: fuera del hueco (box &gt; 0) se pinta; la luz
     * es más fuerte junto al borde del móvil (e) y hay un filo nítido pegado a la pantalla
     * encogida (rim). El color viene del degradado que gira (sweep). Sale premultiplicado.
     */
    static final String GLOW_AGSL = ""
            + "uniform shader sweep;\n"
            + "uniform float2 size;\n"
            + "uniform float4 hole;\n"
            + "uniform float holeR;\n"
            + "uniform float outerR;\n"
            + "uniform float thick;\n"
            + "uniform float light;\n"
            // Distancia con signo a un rectángulo redondeado (negativa dentro).
            + "float box(float2 p, float2 c, float2 h, float r) {\n"
            + "  float2 q = abs(p - c) - h + r;\n"
            + "  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;\n"
            + "}\n"
            + "half4 main(float2 p) {\n"
            + "  float dHole = box(p, (hole.xy + hole.zw) * 0.5, (hole.zw - hole.xy) * 0.5, holeR);\n"
            + "  float inFrame = clamp(dHole + 0.5, 0.0, 1.0);\n"
            + "  if (inFrame <= 0.0) { return half4(0.0); }\n"
            + "  float dEdge = -box(p, size * 0.5, size * 0.5, outerR);\n"
            + "  float e = 1.0 - clamp(dEdge / max(thick, 1.0), 0.0, 1.0);\n"
            + "  float rim = exp(-max(dHole, 0.0) / max(thick * 0.06, 1.5));\n"
            + "  float a = clamp(light * (0.4 + 0.6 * e + 0.3 * e * e + 0.9 * rim), 0.0, 1.0) * inFrame;\n"
            + "  half4 col = sweep.eval(p);\n"
            + "  return half4(col.rgb * half(a), half(a));\n"
            + "}\n";

    /** Azul, violeta, rosa y naranja de Gemini; el azul se repite para cerrar la vuelta. */
    private static final int[] GEMINI = {0xFF4C8DF6, 0xFF9B72CB, 0xFFE0607E, 0xFFF29B5C, 0xFF4C8DF6};
    private static final int[] RAINBOW = {0xFFFF5A5F, 0xFFFFB443, 0xFFFFE25A, 0xFF4CD486, 0xFF4C8DF6,
            0xFF9B72CB, 0xFFFF5A5F};

    /** Colores de la luz según el ajuste. Monet se mira en cada llamada: cambia con el fondo. */
    private static int[] glowColors() {
        if (geminiColors == 1) {
            try {
                // Los tres acentos de Monet en un tono claro, para que brillen sobre el marco.
                int a = ctx.getColor(android.R.color.system_accent1_300);
                int b = ctx.getColor(android.R.color.system_accent2_300);
                int c = ctx.getColor(android.R.color.system_accent3_300);
                return new int[]{a, b, c, a};
            } catch (Throwable ignored) {
            }
        } else if (geminiColors == 2) {
            return RAINBOW;
        }
        return GEMINI;
    }

    private Squeeze() {
    }
}
