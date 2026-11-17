package com.icehome.up.cc;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
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
 * la emergente) es entera de cristal, como las piezas del centro de control: el fondo de detrás
 * desenfocado y oscurecido para que el texto se lea, doblado hacia dentro junto al borde
 * (refracción) y con un canto de luz suave.
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
    private static final long REOPEN_MS = 1200;
    /** Fundido de entrada del cristal cuando llega la captura. */
    private static final long FADE_MS = 160;
    /** Por debajo de esta altura (dp) no se dibuja: tarjetas recogiéndose del todo. */
    private static final float MIN_DP = 24f;
    /** Ancho máximo de la lente del borde (dp). */
    private static final float MAX_LENS_DP = 14f;
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
    }

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
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static RuntimeShader shader;
    private static Bitmap bound;

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

        if (shader == null) shader = GlassShader.create();
        RuntimeShader rs = shader;
        if (bound != b) {
            rs.setInputShader("wall", GlassShader.linear(b));
            bound = b;
        }
        float d = Math.max(0f, Math.min(1f, dark));
        float thick = Math.min(Tune.edge * dp, Math.min(w, h) * CcGlass.THICK_MAX_FRACTION);
        float lensH = Math.min(Math.min(Tune.refrH, MAX_LENS_DP) * dp, Math.min(w, h) / 3f);
        float want = Tune.refrH * dp;
        rs.setFloatUniform("size", w, h);
        rs.setFloatUniform("band", Math.max(1f, thick));
        rs.setFloatUniform("disp", 0f);
        rs.setFloatUniform("dispersion", CcGlass.THICK_DISPERSION);
        rs.setFloatUniform("fill", 1f);                      // la tarjeta entera es cristal
        rs.setFloatUniform("bandAlpha", 1f);
        // Oscuro para que se lea: con 0,5, a un 60 % del brillo, y las luces altas apagadas.
        rs.setFloatUniform("tone", 1f - 0.8f * d);
        rs.setFloatUniform("darker", 0.15f + 0.35f * d);
        rs.setFloatUniform("sat", 1.25f);
        rs.setFloatUniform("whiteMix", 0f);
        rs.setFloatUniform("edgeLift", 0f);
        rs.setFloatUniform("stretch", CcGlass.EDGE_STRETCH);
        rs.setFloatUniform("overshoot", Tune.edgeOver * dp);
        rs.setFloatUniform("edgeSat", Tune.edgeSat * 0.5f);
        rs.setFloatUniform("mag", 0f);
        rs.setFloatUniform("refrH", lensH);
        rs.setFloatUniform("refrAmt", want > 0f ? Tune.refr * dp * lensH / want : 0f);
        // Arcoíris y canto de luz más suaves que en el centro de control: en una tarjeta con
        // texto distraían (era lo raro de la primera versión).
        rs.setFloatUniform("chroma", Tune.chroma * 0.35f);
        rs.setFloatUniform("rimw", Tune.rim * dp);
        rs.setFloatUniform("glow", 0f);
        rs.setFloatUniform("innerDark", CcGlass.INNER_DARK);
        rs.setFloatUniform("rimGain", Tune.rimGain * 0.55f);
        rs.setFloatUniform("light", 0.025f);
        rs.setFloatUniform("active", 0f);

        // Qué parte de la captura hay detrás de este trozo de pantalla.
        float k = CcGlass.backdropScaleNow();
        float[] sc = CcGlass.scaleOf(v);
        v.getLocationOnScreen(LOC);
        state.x = LOC[0];
        state.y = LOC[1];
        state.sx = sc[0];
        state.sy = sc[1];
        rs.setFloatUniform("origin", (LOC[0] + left * sc[0]) * k, (LOC[1] + top * sc[1]) * k);
        rs.setFloatUniform("scale", sc[0] * k, sc[1] * k);
        rs.setFloatUniform("hasWall", 1f);
        PAINT.setShader(rs);
        PAINT.setAlpha(Math.round(255 * fade));

        int save = c.save();
        c.translate(left, top);
        if (Math.abs(rt - rb) < 0.5f) {
            rs.setFloatUniform("radius", rt);
            c.drawRect(0, 0, w, h, PAINT);
        } else {
            // Radios distintos arriba y abajo (tarjetas de un grupo): cada mitad con el suyo.
            float mid = h / 2f;
            int s2 = c.save();
            c.clipRect(0, 0, w, mid);
            rs.setFloatUniform("radius", rt);
            c.drawRect(0, 0, w, h, PAINT);
            c.restoreToCount(s2);
            c.clipRect(0, mid, w, h);
            rs.setFloatUniform("radius", rb);
            c.drawRect(0, 0, w, h, PAINT);
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
