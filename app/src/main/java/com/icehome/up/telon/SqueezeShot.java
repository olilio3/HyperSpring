package com.icehome.up.telon;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Outline;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;

import com.icehome.up.Threads;
import com.icehome.up.xp.XC_MethodHook;
import com.icehome.up.xp.XposedBridge;

import java.lang.ref.WeakReference;

/**
 * La otra mitad de {@link Squeeze}, dentro de la app de capturas de MIUI (com.miui.screenshot).
 *
 * <p>Por qué hace falta: al hacer una captura, MIUI enseña la imagen a pantalla completa en su
 * propia ventana ("ScreenshotAnimation") y la encoge hasta la miniatura de la esquina. Esa
 * ventana va por encima de todo y Android no la encoge, así que se veía la imagen grande
 * encima de la pantalla encogida y del marco. Aquí encogemos esa ventana igual que la pantalla
 * (misma curva y misma geometría, ver {@link Squeeze.Curve} y {@link Squeeze.Geo}) y la
 * recortamos al hueco del marco: la vista previa queda dentro de la pantalla, sin tapar el marco.
 *
 * <p>Cuándo arrancan las dos mitades: cuando la imagen YA está hecha. MIUI manda su difusión
 * "miui.intent.TAKE_SCREENSHOT" (IsFinished=false) justo ANTES de hacer la foto (lo hace
 * TakeScreenshotService, y con la bola flotante la foto llega 150 ms después). Si la pantalla se
 * encogía con esa difusión, a veces la foto salía encogida y con el marco marrón. Por eso aquí se
 * engancha el momento en que el sistema entrega la imagen ({@link #watchCapture}) y desde aquí se
 * avisa a SystemUI con {@link #ACTION_CAPTURED}.
 */
public final class SqueezeShot {

    /** Aviso a SystemUI: la captura ya está hecha, ya se puede encoger la pantalla. */
    public static final String ACTION_CAPTURED = "com.icehome.up.SQUEEZE_CAPTURED";
    /** Si no llega la imagen en este tiempo (ganchos rotos en otra versión), se arranca igual. */
    static final long FALLBACK_MS = 600;

    /** Cuándo anunció MIUI una captura (antes de hacerla); 0 = no hay ninguna pendiente. */
    private static volatile long armedAt;
    /** Cuándo llegó la última imagen de una captura anunciada. */
    private static volatile long capturedAt = -100000;

    private static Context ctx;
    private static Handler main;
    private static WeakReference<View> window = new WeakReference<>(null);
    private static ValueAnimator anim;
    private static long lastStart;

    /** Al cargar la app de capturas: anota su ventana de vista previa cuando se añade. */
    public static void watch() {
        Class<?> g;
        try {
            g = Class.forName("android.view.WindowManagerGlobal");
        } catch (Throwable t) {
            return;
        }
        XposedBridge.hookAllMethods(g, "addView", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (!(p.args[0] instanceof View) || !(p.args[1] instanceof WindowManager.LayoutParams)) return;
                    CharSequence title = ((WindowManager.LayoutParams) p.args[1]).getTitle();
                    if (title != null && "ScreenshotAnimation".contentEquals(title)) {
                        window = new WeakReference<>((View) p.args[0]);
                    }
                } catch (Throwable ignored) {
                }
            }
        });
        watchCapture();
    }

    /**
     * Engancha el anuncio de MIUI (para saber que viene una captura normal) y la entrega de la
     * imagen por parte del sistema. Cada gancho va por separado: si uno no existe en esta versión
     * de Android, los demás siguen (y si fallan todos, queda el plan B de {@link #FALLBACK_MS}).
     */
    private static void watchCapture() {
        // 1) El anuncio, en el momento en que MIUI lo envía (síncrono, antes de la foto). Escuchar
        //    la difusión no vale: llega por el hilo principal, a veces después de la imagen.
        try {
            XposedBridge.hookAllMethods(Class.forName("android.app.ContextImpl"), "sendBroadcast", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args.length == 0 || !(p.args[0] instanceof Intent)) return;
                        Intent i = (Intent) p.args[0];
                        if ("miui.intent.TAKE_SCREENSHOT".equals(i.getAction()) && !i.getBooleanExtra("IsFinished", true)) {
                            armedAt = android.os.SystemClock.uptimeMillis();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            Log.e("captura: no se pudo enganchar el anuncio de MIUI", t);
        }
        // 2) La imagen. En Android 14-16 MIUI pide la captura con un "oyente síncrono" y luego le
        //    pide la imagen con getBuffer(), que espera hasta tenerla. El oyente es una clase
        //    anónima que crea createSyncCaptureListener: se engancha su getBuffer al verla.
        for (String owner : new String[]{"android.window.ScreenCaptureInternal", "android.window.ScreenCapture"}) {
            try {
                XposedBridge.hookAllMethods(Class.forName(owner), "createSyncCaptureListener", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        Object l = p.getResult();
                        if (l != null) hookBuffer(l.getClass());
                    }
                });
            } catch (Throwable ignored) {
                // no existe en esta versión
            }
        }
        // Caminos antiguos (Android 12-13 o capas sueltas): devuelven la imagen directamente.
        String[][] direct = {{"android.window.ScreenCapture", "captureLayers"},
                {"android.view.SurfaceControl", "captureDisplay"}, {"android.view.SurfaceControl", "captureLayers"}};
        for (String[] d : direct) {
            try {
                XposedBridge.hookAllMethods(Class.forName(d[0]), d[1], CAPTURED_HOOK);
            } catch (Throwable ignored) {
            }
        }
    }

    private static final java.util.Set<Class<?>> HOOKED = java.util.Collections.synchronizedSet(new java.util.HashSet<Class<?>>());

    private static void hookBuffer(Class<?> c) {
        if (!HOOKED.add(c)) return;
        try {
            XposedBridge.hookAllMethods(c, "getBuffer", CAPTURED_HOOK);
        } catch (Throwable t) {
            Log.e("captura: no se pudo enganchar getBuffer", t);
        }
    }

    /** Tras devolver una imagen: si había una captura anunciada, ya está hecha. */
    private static final XC_MethodHook CAPTURED_HOOK = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            if (p.getResult() != null) captured();
        }
    };

    private static void captured() {
        long now = android.os.SystemClock.uptimeMillis();
        long armed = armedAt;
        // Solo la primera imagen tras el anuncio: la captura larga hace muchas más después.
        if (armed == 0 || now - armed > 3000) return;
        armedAt = 0;
        capturedAt = now;
        Context c = ctx;
        Handler h = main;
        if (c == null || h == null) return;
        try {
            Intent i = new Intent(ACTION_CAPTURED);
            i.setPackage("com.android.systemui");
            c.sendBroadcast(i);
        } catch (Throwable t) {
            Log.e("captura: no se pudo avisar a SystemUI", t);
        }
        h.post(new Runnable() {
            @Override
            public void run() {
                startWhenReady(0);
            }
        });
    }

    /** La ventana de vista previa puede llegar unos fotogramas después de la imagen: se espera. */
    private static void startWhenReady(final int tries) {
        if (window.get() != null || tries >= 25) {
            start();
            return;
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                startWhenReady(tries + 1);
            }
        }, 16);
    }

    /** Con el contexto de la app de capturas: lee los ajustes y escucha las capturas. */
    public static void install(Context c) {
        ctx = c;
        main = new Handler(Looper.getMainLooper());
        reloadLater();
        IntentFilter f = new IntentFilter("miui.intent.TAKE_SCREENSHOT");
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (!i.getBooleanExtra("IsFinished", true)) {
                    // Normalmente arranca captured(). Plan B: si la imagen no se ha visto (ganchos
                    // que no existen en esta versión), arranca igual un poco después, cuando la
                    // foto seguro que ya está hecha.
                    main.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (android.os.SystemClock.uptimeMillis() - capturedAt > FALLBACK_MS + 900) startWhenReady(0);
                        }
                    }, FALLBACK_MS);
                } else {
                    reloadLater();       // al terminar: ajustes frescos para la siguiente
                }
            }
        };
        if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(r, f, null, main, Context.RECEIVER_EXPORTED);
        else c.registerReceiver(r, f, null, main);
    }

    /**
     * Los ajustes se leen del proveedor de HyperSpring en otro hilo (es una llamada a otra app) y
     * quedan en los mismos campos que usa SystemUI (Squeeze.size, left…), aquí en este proceso.
     */
    private static void reloadLater() {
        Threads.start("HyperSpring-shot", android.os.Process.THREAD_PRIORITY_BACKGROUND).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Tune.reloadSqueeze(ctx);
                } catch (Throwable ignored) {
                }
                Looper l = Looper.myLooper();
                if (l != null) l.quitSafely();
            }
        });
    }

    private static void start() {
        if (!Squeeze.enabled || Squeeze.speed <= 0f) return;
        final View v = window.get();
        if (v == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastStart < 150) return;
        lastStart = now;
        if (anim != null) anim.cancel();
        final Rect screen = Squeeze.screenBounds(ctx);
        final float corner = Squeeze.windowCornerRadius(ctx);
        final Squeeze.Curve curve = new Squeeze.Curve(Squeeze.speed, 0f);
        final Squeeze.Geo geo = new Squeeze.Geo();
        // Lo que tuviera la ventana antes, para dejarlo igual al terminar.
        final float sx = v.getScaleX(), sy = v.getScaleY(), tx = v.getTranslationX(), ty = v.getTranslationY();
        final float px = v.getPivotX(), py = v.getPivotY();
        final boolean clip = v.getClipToOutline();
        final ViewOutlineProvider oldOutline = v.getOutlineProvider();
        final float w = screen.width(), h = screen.height();
        // Recorte al hueco del marco, en coordenadas de la ventana sin encoger.
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                float s = Math.max(0.01f, geo.scale);
                if (Squeeze.frame) {
                    o.setRoundRect(Math.round((geo.l - geo.x) / s), Math.round((geo.t - geo.y) / s),
                            Math.round((geo.r - geo.x) / s), Math.round((geo.b - geo.y) / s), geo.radius / s);
                } else {
                    o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), corner);
                }
            }
        });
        v.setClipToOutline(true);
        v.setPivotX(0f);
        v.setPivotY(0f);
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setInterpolator(new LinearInterpolator());
        anim.setDuration(curve.durationMs());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                geo.set(curve.at(a.getAnimatedFraction()), w, h, corner);
                // La ventana es de pantalla completa en (0, 0): mismo cambio que las capas.
                v.setScaleX(sx * geo.scale);
                v.setScaleY(sy * geo.scale);
                v.setTranslationX(tx + geo.x);
                v.setTranslationY(ty + geo.y);
                v.invalidateOutline();
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                v.setScaleX(sx);
                v.setScaleY(sy);
                v.setTranslationX(tx);
                v.setTranslationY(ty);
                v.setPivotX(px);
                v.setPivotY(py);
                v.setClipToOutline(clip);
                v.setOutlineProvider(oldOutline);
            }
        });
        anim.start();
    }

    private SqueezeShot() {
    }
}
