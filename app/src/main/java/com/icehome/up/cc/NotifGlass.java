package com.icehome.up.cc;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

import com.icehome.up.xp.XC_MethodHook;
import com.icehome.up.xp.XposedBridge;
import com.icehome.up.xp.XposedHelpers;

/**
 * Cristal líquido en las notificaciones: cada tarjeta (en el panel, en la pantalla de bloqueo y
 * la emergente) es de cristal esmerilado oscuro: el fondo de detrás desenfocado y oscurecido
 * para que el texto se lea, con un canto de luz suave. Ligero a propósito: una lista que se
 * desliza repinta todas sus tarjetas en cada fotograma (ver {@link #darkFor}).
 *
 * <p><b>Dónde se dibuja.</b> En el fondo de la tarjeta: Android lo pinta con una vista propia,
 * {@code NotificationBackgroundView}, por debajo del texto y los iconos. Después de que HyperOS
 * pinte su fondo, se pinta encima el cristal, opaco: lo tapa entero y el texto queda por encima
 * de todo. (La 2.10.0 de pruebas lo ponía solo en el borde y por encima del contenido, y quedaba
 * raro.)
 *
 * <p><b>Una sola captura.</b> Todas las tarjetas usan la misma captura del fondo (la del centro
 * de control, {@link CcGlass}): se toma al abrir el panel o al salir una notificación emergente, no
 * en cada tarjeta ni en cada fotograma. Mientras llega (unas décimas), se ve el fondo de HyperOS y
 * el cristal entra con un fundido corto.
 *
 * <p><b>Tamaño real.</b> El fondo de una tarjeta no se dibuja a su tamaño de layout: se recorta a
 * su "altura actual" mientras se expande o se recoge, y a veces por arriba. Se leen los mismos
 * campos que usa Android al pintarlo ({@code mActualHeight}, {@code mClipTopAmount},
 * {@code mClipBottomAmount}, {@code mCornerRadii}), así el cristal coincide siempre con la tarjeta.
 *
 * <p>Todo va protegido: si falla algo varias veces seguidas, se anota y se apaga; las
 * notificaciones se siguen viendo como siempre.
 */
public final class NotifGlass {

    private static final String PKG = "com.android.systemui.statusbar.notification.row.";

    /** Interruptor en vivo (se lee en cada dibujo). Lo pone {@link Tune#load}. */
    static volatile boolean enabled;
    /** Oscuridad del cristal (0-1): más alta, más se lee el texto. Ajuste. */
    static volatile float dark = 0.5f;
    /** Sin dibujar cristal durante este tiempo, el panel se había cerrado: se toma otra captura. */
    private static final long REOPEN_MS = 2500;
    /** Fundido de entrada del cristal cuando llega la captura. */
    private static final long FADE_MS = 160;
    /** Por debajo de esta altura (dp) no se dibuja: tarjetas recogiéndose del todo. */
    private static final float MIN_DP = 24f;
    /** Fallos seguidos al dibujar antes de apagar el cristal de las notificaciones. */
    private static final int MAX_FAILS = 5;

    private static Field fActualHeight, fActualWidth, fClipTop, fClipBottom, fBackgroundTop, fRadii, fExpanding;
    /**
     * Fondos de tarjeta vistos, con el momento en que se les pintó cristal por primera vez desde
     * que llegó la captura (0 = aún sin cristal). WeakHashMap con un Long: no retiene la vista.
     */
    private static final Map<View, St> SHOWN = new WeakHashMap<>();

    /** Lo que se recuerda de cada fondo de tarjeta. No guarda la vista (ver SHOWN). */
    private static final class St {
        /** Cuándo empezó a verse el cristal (0 = aún sin cristal). */
        long since;
        /** Dónde estaba en pantalla, y a qué escala, la última vez que se pintó. */
        int x = Integer.MIN_VALUE, y;
        float sx, sy;
        /** El fondo ya preparado, como textura, y dónde va dentro de la tarjeta. */
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Matrix m = new android.graphics.Matrix();
        Bitmap bound;
        /** Tamaño con el que se hizo el degradado del canto. */
        float rimW, rimH;
        /** Ya se le quitó el desenfoque de HyperOS (lo tapa nuestro cristal). */
        boolean miCleared;
    }

    /**
     * El fondo de detrás ya desenfocado (la captura compartida) y además oscurecido y algo más
     * saturado, hecho UNA vez por captura. Cada tarjeta solo lo pega con su recorte: un acceso a
     * la textura por píxel, lo más barato que hay para la GPU. (Hasta la 2.12.1 se usaba el shader
     * de cristal líquido del centro de control en cada píxel de cada tarjeta y en cada fotograma
     * al deslizar: en una Mali-G52 la lista iba a pocos fotogramas por segundo.)
     */
    private static Bitmap darkFor(Bitmap src) {
        float d = Math.max(0f, Math.min(1f, dark));
        if (src == darkSrc && d == darkAmount && darkBmp != null) return darkBmp;
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
        cm.setSaturation(1.25f);
        float t = 1f - 0.8f * d;                            // con 0,5: al 60 % del brillo
        android.graphics.ColorMatrix tone = new android.graphics.ColorMatrix();
        tone.setScale(t, t, t, 1f);
        cm.postConcat(tone);
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
        new Canvas(out).drawBitmap(src, 0f, 0f, p);
        darkSrc = src;
        darkAmount = d;
        darkBmp = out;
        return out;
    }

    private static Bitmap darkSrc, darkBmp;
    private static float darkAmount = -1f;

    private static St st(View v) {
        St s = SHOWN.get(v);
        if (s == null) {
            s = new St();
            SHOWN.put(v, s);
            follow(v);
        }
        return s;
    }

    /**
     * El cristal enseña lo que hay DETRÁS de la tarjeta, así que depende de dónde está en
     * pantalla. Al deslizar la lista o apartar una notificación, Android mueve la tarjeta sin
     * volver a pintarla (solo cambia su posición en la GPU) y el cristal se quedaba con el trozo
     * de fondo de antes, como una foto pegada. Antes de cada fotograma se mira si la tarjeta se
     * ha movido y, si es así, se repinta su fondo.
     */
    private static void follow(View v) {
        final java.lang.ref.WeakReference<View> ref = new java.lang.ref.WeakReference<>(v);
        final int[] at = new int[2];
        final android.view.ViewTreeObserver.OnPreDrawListener pre = new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                View h = ref.get();
                if (h == null || !enabled || broken || !h.isAttachedToWindow() || !h.isShown()) return true;
                St s = SHOWN.get(h);
                if (s == null || s.since == 0L) return true;
                h.getLocationOnScreen(at);
                float[] sc = CcGlass.scaleOf(h);
                if (at[0] != s.x || at[1] != s.y || sc[0] != s.sx || sc[1] != s.sy) h.invalidate();
                return true;
            }
        };
        // El listener es de la ventana: se pone al entrar en ella y se quita al salir.
        v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View a) {
                a.getViewTreeObserver().removeOnPreDrawListener(pre);
                a.getViewTreeObserver().addOnPreDrawListener(pre);
            }

            @Override
            public void onViewDetachedFromWindow(View a) {
                a.getViewTreeObserver().removeOnPreDrawListener(pre);
            }
        });
        if (v.isAttachedToWindow()) v.getViewTreeObserver().addOnPreDrawListener(pre);
    }
    private static long lastDrawMs;
    private static int fails;
    private static boolean broken, logged;
    private static final int[] LOC = new int[2];
    private static final android.graphics.Path PATH = new android.graphics.Path();

    /**
     * Engancha el fondo de las tarjetas. Se llama una vez, con la interfaz del sistema ya
     * arrancada y solo si el interruptor está encendido (como las demás funciones).
     */
    public static void install(android.content.Context c, ClassLoader cl) {
        if (Build.VERSION.SDK_INT < 33) {
            CcGlass.note("HyperSpring CC: cristal en notificaciones: necesita Android 13");
            return;
        }
        Tune.load(c);
        Class<?> bg = XposedHelpers.findClass(PKG + "NotificationBackgroundView", cl);
        // Se busca todo antes de enganchar nada. Lo que no exista en esta versión se sustituye
        // por el tamaño de la vista o un radio por defecto.
        fActualHeight = field(bg, "mActualHeight");
        fActualWidth = field(bg, "mActualWidth");
        fClipTop = field(bg, "mClipTopAmount");
        fClipBottom = field(bg, "mClipBottomAmount");
        fBackgroundTop = field(bg, "mBackgroundTop");
        fRadii = field(bg, "mCornerRadii");
        fExpanding = field(bg, "mExpandAnimationRunning");

        XC_MethodHook paint = new XC_MethodHook() {
            @Override
            protected boolean skip(Object self) {
                return !enabled || broken;
            }

            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (p.args.length == 1 && p.args[0] instanceof Canvas) draw((View) p.thisObject, (Canvas) p.args[0]);
            }
        };
        int n = XposedBridge.hookAllMethods(bg, "onDraw", paint).size();
        if (n == 0) throw new NoSuchMethodError(PKG + "NotificationBackgroundView.onDraw");
        CcGlass.note("HyperSpring CC: cristal en notificaciones activo"
                + (fActualHeight == null ? " (sin altura actual)" : "")
                + (fRadii == null ? " (sin radios)" : ""));
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

    /** Un dibujo fallido; varios seguidos apagan el cristal de las notificaciones. */
    private static void failed(Throwable t) {
        if (++fails < MAX_FAILS || broken) return;
        broken = true;
        CcGlass.note("HyperSpring CC: cristal en notificaciones desactivado tras " + MAX_FAILS
                + " fallos seguidos (" + t + ")");
        XposedBridge.log(t);
        for (View v : new ArrayList<>(SHOWN.keySet())) if (v != null) v.invalidate();
    }

    /** Después de que HyperOS pinte el fondo de una tarjeta: el cristal encima (hilo principal). */
    private static void draw(View v, Canvas c) {
        if (!c.isHardwareAccelerated()) return;
        try {
            paintGlass(v, c);
            fails = 0;
        } catch (Throwable t) {
            failed(t);
        }
    }

    private static void paintGlass(View v, Canvas c) {
        float dp = v.getResources().getDisplayMetrics().density;
        // Lo mismo que hace Android al pintar este fondo (NotificationBackgroundView.draw).
        boolean expanding = false;
        try {
            expanding = fExpanding != null && fExpanding.getBoolean(v);
        } catch (Throwable ignored) {
        }
        int actual = intOf(fActualHeight, v, -1);
        if (actual <= 0) actual = v.getHeight();
        int top = Math.max(intOf(fBackgroundTop, v, 0), expanding ? 0 : intOf(fClipTop, v, 0));
        int bottom = actual - (expanding ? 0 : Math.max(0, intOf(fClipBottom, v, 0)));
        float left = 0f, right = v.getWidth();
        int aw = intOf(fActualWidth, v, -1);
        if (expanding && aw > 0) {                         // abriéndose a pantalla completa
            left = (v.getWidth() - aw) / 2f;
            right = left + aw;
        }
        float w = right - left, h = bottom - top;
        if (w <= 0 || h < MIN_DP * dp) return;

        // El panel se acaba de abrir (o sale una emergente): captura nueva del fondo.
        long now = SystemClock.uptimeMillis();
        if (now - lastDrawMs > REOPEN_MS) CcGlass.markStale(v);
        lastDrawMs = now;

        Bitmap b = CcGlass.backdropFor(v);
        St state = st(v);
        if (b == null) {                                    // aún sin captura: se queda el de HyperOS
            state.since = 0L;
            return;
        }
        if (state.since == 0L) state.since = now;
        float fade = Math.min(1f, (now - state.since) / (float) FADE_MS);
        if (fade < 1f) v.postInvalidateOnAnimation();       // seguir hasta acabar el fundido

        float half = Math.min(w, h) / 2f;
        float rt = 16f * dp, rb = rt;
        if (fRadii != null) {
            try {
                float[] r = (float[]) fRadii.get(v);
                if (r != null && r.length >= 8) {
                    rt = r[0];
                    rb = r[4];
                }
            } catch (Throwable ignored) {
            }
        }
        rt = Math.min(half, Math.max(0f, rt));
        rb = Math.min(half, Math.max(0f, rb));

        Bitmap dk = darkFor(b);
        if (state.bound != dk) {
            // Cada tarjeta comparte la textura, pero con su propia matriz (dónde cae en pantalla).
            state.fill.setShader(new android.graphics.BitmapShader(dk, android.graphics.Shader.TileMode.CLAMP,
                    android.graphics.Shader.TileMode.CLAMP));
            state.bound = dk;
        }

        // Nuestro cristal es opaco y tapa la tarjeta entera: el desenfoque que HyperOS calcula
        // debajo (caro, y más al repintar mientras se desliza la lista) ya no se ve. Se quita una
        // vez, cuando el cristal ya se ve del todo.
        if (fade >= 1f && !state.miCleared) {
            state.miCleared = true;
            CcGlass.miClear(v, true);
        }

        // Qué parte del fondo hay detrás: el punto (x, y) de la tarjeta está en la pantalla en
        // LOC + (x, y)·escala, y en la captura en eso por k. La matriz lleva la captura ahí.
        float k = CcGlass.backdropScaleNow();
        float[] sc = CcGlass.scaleOf(v);
        v.getLocationOnScreen(LOC);
        state.x = LOC[0];
        state.y = LOC[1];
        state.sx = sc[0];
        state.sy = sc[1];
        float ox = (LOC[0] + left * sc[0]) * k, oy = (LOC[1] + top * sc[1]) * k;
        state.m.setTranslate(-ox, -oy);
        state.m.postScale(1f / Math.max(1e-4f, sc[0] * k), 1f / Math.max(1e-4f, sc[1] * k));
        state.fill.getShader().setLocalMatrix(state.m);
        state.fill.setAlpha(Math.round(255 * fade));

        // Canto de luz: más brillante arriba a la izquierda, como el del centro de control.
        if (w != state.rimW || h != state.rimH) {
            state.rimW = w;
            state.rimH = h;
            state.rim.setStyle(Paint.Style.STROKE);
            state.rim.setStrokeWidth(Math.max(1f, Tune.rim * dp));
            state.rim.setShader(new android.graphics.LinearGradient(0, 0, w, h,
                    new int[]{0x8CFFFFFF, 0x1AFFFFFF, 0x0DFFFFFF, 0x40FFFFFF},
                    new float[]{0f, 0.35f, 0.7f, 1f}, android.graphics.Shader.TileMode.CLAMP));
        }
        state.rim.setAlpha(Math.round(255 * fade * Math.max(0f, Math.min(1.5f, Tune.rimGain)) / 1.5f));

        int save = c.save();
        c.translate(left, top);
        float sw = state.rim.getStrokeWidth() / 2f;
        if (Math.abs(rt - rb) < 0.5f) {
            c.drawRoundRect(0, 0, w, h, rt, rt, state.fill);
            c.drawRoundRect(sw, sw, w - sw, h - sw, Math.max(0f, rt - sw), Math.max(0f, rt - sw), state.rim);
        } else {
            // Radios distintos arriba y abajo (tarjetas de un grupo).
            PATH.reset();
            PATH.addRoundRect(0, 0, w, h, new float[]{rt, rt, rt, rt, rb, rb, rb, rb}, android.graphics.Path.Direction.CW);
            c.drawPath(PATH, state.fill);
            PATH.reset();
            float it = Math.max(0f, rt - sw), ib = Math.max(0f, rb - sw);
            PATH.addRoundRect(sw, sw, w - sw, h - sw, new float[]{it, it, it, it, ib, ib, ib, ib}, android.graphics.Path.Direction.CW);
            c.drawPath(PATH, state.rim);
        }
        c.restoreToCount(save);

        if (!logged) {
            logged = true;
            CcGlass.note("HyperSpring CC: cristal en la primera notificación ("
                    + (v.getParent() != null ? v.getParent().getClass().getSimpleName() : "-") + " "
                    + Math.round(w) + "x" + Math.round(h) + ", radios " + Math.round(rt) + "/" + Math.round(rb) + ")");
        }
    }

    private NotifGlass() {
    }
}
