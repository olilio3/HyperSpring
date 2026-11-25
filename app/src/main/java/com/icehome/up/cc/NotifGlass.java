package com.icehome.up.cc;

import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewParent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

import com.icehome.up.xp.XC_MethodHook;
import com.icehome.up.xp.XposedBridge;
import com.icehome.up.xp.XposedHelpers;

/**
 * Cristal líquido estilo iOS 27 en la notificación emergente (solo la flotante: en el panel de
 * notificaciones y en la pantalla de bloqueo se quedan como las pinta HyperOS).
 *
 * <p>Viene de las notificaciones de cristal de la 2.12.2 (se quitaron porque en el panel una lista
 * entera de cristal iba a tirones). Se pinta en el fondo de la tarjeta
 * ({@code NotificationBackgroundView}), por debajo del texto: el fondo de detrás desenfocado (la
 * captura que comparte con el centro de control), un velo claro u oscuro según el modo del sistema
 * para que el texto se lea, y un canto de luz brillante arriba a la izquierda como en iOS.
 *
 * <p>Mientras la notificación hace la animación de iOS 27 ({@link com.icehome.up.telon.NotifPop}) la
 * tarjeta se mueve con una matriz que getLocationOnScreen no tiene en cuenta: se usa esa matriz
 * para que el cristal enseñe lo que hay detrás de donde está de verdad.
 *
 * <p>Todo va protegido: si falla varias veces seguidas, se apaga y la notificación se ve como siempre.
 */
public final class NotifGlass {

    private static final String PKG = "com.android.systemui.statusbar.notification.row.";

    /** Interruptor en vivo (se lee en cada dibujo). Lo pone telon.Tune. */
    public static volatile boolean enabled;
    /** Velo encima del fondo (0-1): más alto, el texto se lee mejor y se ve menos lo de detrás. */
    public static volatile float tint = 0.35f;

    private static final float MIN_DP = 24f;
    private static final int MAX_FAILS = 5;

    private static Field fActualHeight, fActualWidth, fClipTop, fClipBottom, fBackgroundTop, fRadii;
    // Claves débiles; los valores no guardan la vista.
    private static final Map<View, St> SHOWN = new WeakHashMap<>();
    private static final Map<Class<?>, Method[]> PIN = new WeakHashMap<>();

    private static final class St {
        long seenAt, drawnAt;
        final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Matrix b = new Matrix();
        final float[] mv = new float[9];
        // Shaders del cristal (los mismos que el centro de control, ver GlassLook). Se rehacen solo
        // si cambia el tamaño o un ajuste; si solo cambia el fondo, se le pone el nuevo.
        android.graphics.RuntimeShader body, lens;
        Bitmap bound;
        boolean bodyBaked;                                  // el cuerpo ya usa la captura con el color hecho
        float bw, bh, br;
        int sig;
        float tx = Float.NaN, ty = Float.NaN;
    }

    private static long requestedAt;
    private static volatile long hideUntil;
    private static java.lang.ref.WeakReference<View> hidden;
    private static long movedAt;
    private static int fails;
    private static boolean broken, logged;
    private static final int[] LOC = new int[2], ROW = new int[2];
    private static final Path PATH = new Path();

    public static void install(ClassLoader cl) {
        if (Build.VERSION.SDK_INT < 33) {
            CcGlass.note("HyperSpring CC: cristal en notificaciones: necesita Android 13");
            return;
        }
        Class<?> bg = XposedHelpers.findClass(PKG + "NotificationBackgroundView", cl);
        fActualHeight = field(bg, "mActualHeight");
        fActualWidth = field(bg, "mActualWidth");
        fClipTop = field(bg, "mClipTopAmount");
        fClipBottom = field(bg, "mClipBottomAmount");
        fBackgroundTop = field(bg, "mBackgroundTop");
        fRadii = field(bg, "mCornerRadii");
        XC_MethodHook paint = new XC_MethodHook() {
            @Override
            protected boolean skip(Object self) {
                return !enabled || broken;
            }

            // Antes del de HyperOS: si hay cristal, el fondo blanco de HyperOS ya no se pinta
            // (pintado debajo dejaba un filo claro en el borde).
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args.length == 1 && p.args[0] instanceof Canvas
                        && draw((View) p.thisObject, (Canvas) p.args[0])) p.setResult(null);
            }
        };
        int n = XposedBridge.hookAllMethods(bg, "onDraw", paint).size();
        if (n == 0) throw new NoSuchMethodError(PKG + "NotificationBackgroundView.onDraw");
        CcGlass.note("HyperSpring CC: cristal en la notificación emergente instalado");
    }

    private static Field field(Class<?> c, String name) {
        try {
            return XposedHelpers.findField(c, name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int intOf(Field f, Object o, int def) {
        if (f == null) return def;
        try {
            return f.getInt(o);
        } catch (Throwable t) {
            return def;
        }
    }

    /** Llegó otra captura del fondo (la llama {@link CcGlass}): las tarjetas se redibujan. */
    static void backdropChanged() {
        if (!enabled || SHOWN.isEmpty()) return;
        for (View v : new ArrayList<>(SHOWN.keySet())) {
            if (v != null && v.isAttachedToWindow()) v.invalidate();
        }
    }

    /**
     * Va a entrar una emergente (lo dice NotifPop): se esconde un instante y se captura lo de detrás,
     * limpio. Se espera a que el fotograma sin ella llegue a la pantalla (unos dos fotogramas).
     */
    public static void appearing(final View row) {
        if (!enabled || broken || !CcGlass.canCapture()) return;   // sin captura no se esconde 800 ms
        long now = SystemClock.uptimeMillis();
        hidden = new java.lang.ref.WeakReference<>(row);
        hideUntil = now + 800;                              // contador: si la captura no llega, sale igual
        requestedAt = now;
        row.postDelayed(new Runnable() {
            @Override
            public void run() {
                CcGlass.captureFor(row);
            }
        }, 40);
    }

    private static final Matrix NONE = new Matrix();

    static {
        NONE.setScale(0f, 0f);
    }

    /**
     * Con la animación normal de HyperOS (sin NotifPop): la emergente se esconde con una matriz de
     * escala 0 mientras se captura lo de detrás, y vuelve en cuanto está la foto (o al rato).
     */
    public static void hideForCapture(final View row) {
        if (!enabled || broken || !CcGlass.canCapture()) return;
        appearing(row);
        row.setAnimationMatrix(NONE);
        android.view.Choreographer.getInstance().postFrameCallback(new android.view.Choreographer.FrameCallback() {
            @Override
            public void doFrame(long t) {
                if (hidden(row)) android.view.Choreographer.getInstance().postFrameCallback(this);
                else row.setAnimationMatrix(null);
            }
        });
    }

    /** La captura ya tiene su foto (desde el hilo de la captura): se deja de esconder. */
    static void shotTaken() {
        hideUntil = 0L;
    }

    /** La emergente está escondida mientras se captura lo de detrás (NotifPop no la dibuja). */
    public static boolean hidden(View row) {
        return hidden != null && hidden.get() == row && SystemClock.uptimeMillis() < hideUntil;
    }

    /**
     * La fila se ha movido con su matriz de animación (NotifPop, en cada fotograma): Android no
     * repinta el fondo por eso, y el cristal enseñaría el trozo de fondo de antes.
     */
    public static void moved(View row) {
        if (!enabled || broken || SHOWN.isEmpty() || hidden(row)) return;   // escondida: nada que repintar
        long now = SystemClock.uptimeMillis();
        if (now - movedAt < (long) (1000f / Math.max(15f, Tune.fps)) - 4) return;   // FPS del cristal
        movedAt = now;
        for (View v : SHOWN.keySet()) {
            if (v != null && v.getParent() == row) v.invalidate();
        }
    }

    /**
     * Es la notificación flotante: está fijada arriba (isPinned), se está yendo (HyperOS cambia el
     * nombre de isHeadsUpAnimatingAway, se prueban los dos) o la anima NotifPop. En el panel no.
     */
    private static boolean floating(View row) {
        Method[] m = PIN.get(row.getClass());
        if (m == null) {
            m = new Method[]{find(row.getClass(), "isPinned"), find(row.getClass(), "isHeadsUpAnimatingAway"),
                    find(row.getClass(), "isHeadsUpAnimatingAway$1")};
            PIN.put(row.getClass(), m);
        }
        for (Method x : m) {
            try {
                if (x != null && Boolean.TRUE.equals(x.invoke(row))) return true;
            } catch (Throwable ignored) {
            }
        }
        return com.icehome.up.telon.NotifPop.animating(row);
    }

    private static Method find(Class<?> c, String name) {
        for (; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void failed(Throwable t) {
        if (++fails < MAX_FAILS || broken) return;
        broken = true;
        CcGlass.note("HyperSpring CC: cristal en notificaciones desactivado tras " + MAX_FAILS
                + " fallos seguidos (" + t + ")");
        XposedBridge.log(t);
        for (View v : new ArrayList<>(SHOWN.keySet())) if (v != null) v.invalidate();
    }

    /** Pinta el cristal si es la emergente y ya hay fondo. true = HyperOS no pinta el suyo. */
    private static boolean draw(View v, Canvas c) {
        if (!c.isHardwareAccelerated()) return false;
        try {
            ViewParent p = v.getParent();
            if (!(p instanceof View) || !floating((View) p)) return false;   // en el panel: como siempre
            boolean done = paintGlass(v, (View) p, c);
            fails = 0;
            return done;
        } catch (Throwable t) {
            failed(t);
            return false;
        }
    }

    private static boolean paintGlass(View v, View row, Canvas c) {
        float dp = v.getResources().getDisplayMetrics().density;
        int actual = intOf(fActualHeight, v, -1);
        if (actual <= 0) actual = v.getHeight();
        // Los mismos bordes que usa HyperOS para su fondo (ancho real, arriba el recorte).
        int top = Math.max(intOf(fBackgroundTop, v, 0), intOf(fClipTop, v, 0));
        int bottom = actual - Math.max(0, intOf(fClipBottom, v, 0));
        int aw = intOf(fActualWidth, v, -1);
        float w = aw > 0 ? Math.min(aw, v.getWidth()) : v.getWidth(), h = bottom - top;
        if (w <= 0 || h < MIN_DP * dp) return false;

        St st = SHOWN.get(v);
        if (st == null) {
            st = new St();
            SHOWN.put(v, st);
        }
        long now = SystemClock.uptimeMillis();
        // Una sola captura por emergente, y nunca con ella en pantalla: en HyperOS la captura no deja
        // fuera la ventana de las notificaciones y el cristal se capturaba a sí mismo (cada vez más
        // blanco). NotifPop la pide al entrar, con la tarjeta escondida (appearing); sin NotifPop, aquí.
        if (now - st.seenAt > 6000 && now - requestedAt > 1000) {     // 6 s sin verla: es otra emergente
            CcGlass.captureFor(v);
            requestedAt = now;
        }
        st.seenAt = now;
        Bitmap b = CcGlass.backdropFor(v);
        if (b == null) {                                    // aún sin captura: se queda el de HyperOS
            return false;
        }

        float r = 16f * dp;
        if (fRadii != null) {
            try {
                float[] rr = (float[]) fRadii.get(v);
                if (rr != null && rr.length >= 8) r = rr[0];
            } catch (Throwable ignored) {
            }
        }
        r = Math.min(Math.min(w, h) / 2f, Math.max(0f, r));

        GlassLook.Params prm = GlassLook.current();
        int sig = prm.sig();
        if (st.body == null || st.bw != w || st.bh != h || st.br != r || st.sig != sig) {
            st.body = GlassLook.body(prm, w, h, r, dp);
            st.lens = GlassLook.lens(prm, w, h, r, dp);
            st.bw = w;
            st.bh = h;
            st.br = r;
            st.sig = sig;
            st.bound = null;
        }
        if (st.bound != b) {
            GlassLook.place(st.body, b, 0, 0, 1, 1);
            GlassLook.place(st.lens, b, 0, 0, 1, 1);
            st.bound = b;
            st.bodyBaked = false;
        }
        // El cuerpo, con la captura que ya trae vivid() hecho (GlassShader.baked): se ve igual y
        // la GPU se ahorra ese cálculo en toda la tarjeta. Hasta que esté, la normal.
        if (!st.bodyBaked) {
            final View inv = v;
            Bitmap bk = GlassShader.baked(b, prm.sat, prm.tone, CcGlass.BIO_DARKER, prm.white, new Runnable() {
                @Override
                public void run() {
                    inv.postInvalidate();
                }
            });
            if (bk != null) {
                st.body.setInputShader("wall", GlassShader.linear(bk));
                st.body.setFloatUniform("baked", 1f);
                st.bodyBaked = true;
            }
        }

        // Punto de la tarjeta → captura. Android aplica a la fila primero su matriz de animación
        // (NotifPop) y luego su translation, y getLocationOnScreen ya lleva la translation pero no
        // la matriz: (ROW − T) + anim·(T + desplazamiento dentro de la fila). Luego por k.
        float k = CcGlass.backdropScaleNow();
        float tx = row.getTranslationX(), ty = row.getTranslationY();
        v.getLocationOnScreen(LOC);
        row.getLocationOnScreen(ROW);
        st.b.setTranslate(LOC[0] - ROW[0] + tx, LOC[1] - ROW[1] + ty + top);
        Matrix anim = com.icehome.up.telon.NotifPop.matrixOf(row);
        if (anim != null) st.b.postConcat(anim);
        st.b.postTranslate(ROW[0] - tx, ROW[1] - ty);
        st.b.postScale(k, k);
        st.b.getValues(st.mv);                              // solo escala y desplazamiento
        GlassLook.move(st.body, st.mv[Matrix.MTRANS_X], st.mv[Matrix.MTRANS_Y], st.mv[Matrix.MSCALE_X], st.mv[Matrix.MSCALE_Y]);
        GlassLook.move(st.lens, st.mv[Matrix.MTRANS_X], st.mv[Matrix.MTRANS_Y], st.mv[Matrix.MSCALE_X], st.mv[Matrix.MSCALE_Y]);
        // HyperOS (o el dedo) la mueve con translation: Android no repinta el fondo por eso.
        // Solo se pide otro dibujo si se ha movido de verdad.
        if (tx != st.tx || ty != st.ty) {
            st.tx = tx;
            st.ty = ty;
            long gap = (long) (1000f / Math.max(15f, Tune.fps));
            long wait = st.drawnAt + gap - now;
            if (wait <= 4) v.postInvalidateOnAnimation();
            else v.postInvalidateDelayed(wait);              // FPS del cristal: menos dibujos
        }

        // Velo claro en modo claro y oscuro en modo oscuro: el texto de HyperOS se lee igual.
        boolean night = (v.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        float t = Math.max(0f, Math.min(1f, tint));
        st.veil.setColor(night ? 0xFF000000 : 0xFFFFFFFF);
        st.veil.setAlpha(Math.round(255 * 0.6f * t));

        int save = c.save();
        c.translate(0, top);
        PATH.reset();
        PATH.addRoundRect(0, 0, w, h, r, r, Path.Direction.CW);
        st.glass.setShader(st.body);
        c.drawRect(0, 0, w, h, st.glass);
        c.drawPath(PATH, st.veil);                          // el cuerpo y la luz ya se recortan solos
        st.glass.setShader(st.lens);
        GlassShader.drawRing(c, w, h, r, GlassLook.lensReach(prm, w, h, dp), st.glass);   // más adentro no pinta nada
        c.restoreToCount(save);

        st.drawnAt = now;

        if (!logged) {
            logged = true;
            CcGlass.note("HyperSpring CC: cristal en la notificación emergente (" + Math.round(w) + "x"
                    + Math.round(h) + ", radio " + Math.round(r) + ")");
        }
        return true;
    }

    private NotifGlass() {
    }
}
