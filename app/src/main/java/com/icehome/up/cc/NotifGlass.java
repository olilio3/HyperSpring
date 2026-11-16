package com.icehome.up.cc;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

import com.icehome.up.xp.XC_MethodHook;
import com.icehome.up.xp.XposedBridge;
import com.icehome.up.xp.XposedHelpers;

/**
 * Cristal líquido en las notificaciones: cada tarjeta (en el panel y la emergente, heads-up)
 * lleva el mismo borde de cristal que las piezas del centro de control: el fondo de detrás
 * doblado hacia dentro junto al borde (refracción), con la separación de colores en las esquinas
 * y el canto de luz arriba a la izquierda.
 *
 * <p><b>Cómo se dibuja.</b> Una capa encima de cada tarjeta (el "overlay" de la vista) con el
 * shader de {@link GlassShader}. Solo el borde: el interior de la tarjeta queda como lo pinta
 * HyperOS, así el texto se lee igual. El shader solo se evalúa en el anillo del borde (el centro
 * se recorta), para no gastar GPU en píxeles que saldrían transparentes.
 *
 * <p><b>Una sola captura.</b> Todas las tarjetas usan la misma captura del fondo (la del centro
 * de control, {@link CcGlass}): se toma al abrir el panel o al salir una notificación emergente, no
 * en cada tarjeta ni en cada fotograma.
 *
 * <p><b>Tamaño real.</b> Una tarjeta de notificación no se dibuja a su tamaño de layout: Android
 * la recorta a su "altura actual" ({@code getActualHeight}) mientras se expande o se recoge, y a
 * veces por arriba ({@code getClipTopAmount}). El borde se dibuja a esa altura, con los radios de
 * arriba y de abajo de cada momento (en un grupo, las esquinas de dentro son más pequeñas).
 *
 * <p>Todo va protegido: si falla algo, se anota una vez y esa parte se apaga; la notificación
 * se sigue viendo como siempre.
 */
public final class NotifGlass {

    private static final String PKG = "com.android.systemui.statusbar.notification.row.";

    /** Interruptor en vivo (se lee en cada dibujo). Lo pone {@link Tune#load}. */
    static volatile boolean enabled;
    /** Sin captura del fondo durante este tiempo, el panel se había cerrado: se toma otra. */
    private static final long REOPEN_MS = 1200;
    /** Por debajo de esta altura (dp) no se dibuja: tarjetas recogiéndose del todo. */
    private static final float MIN_DP = 24f;
    /** Ancho máximo de la lente del borde (dp), para que nunca tape el texto de la tarjeta. */
    private static final float MAX_LENS_DP = 12f;
    /** Fallos seguidos al dibujar antes de apagar el cristal de las notificaciones. */
    private static final int MAX_FAILS = 5;

    private static Class<?> rowClass;
    private static Method mActualHeight, mClipTop, mClipBottom, mTopRadius, mBottomRadius;
    /** Una capa por tarjeta. El valor no retiene la vista (ver {@link Skin}), así no hay fugas. */
    private static final Map<View, Skin> SKINS = new WeakHashMap<>();
    private static long lastDrawMs;
    private static int fails;
    private static boolean broken, logged;

    /**
     * Engancha las tarjetas de notificación. Se llama una vez, con la interfaz del sistema ya
     * arrancada y solo si el interruptor está encendido (como las demás funciones).
     */
    public static void install(android.content.Context c, ClassLoader cl) {
        if (Build.VERSION.SDK_INT < 33) {
            CcGlass.note("HyperSpring CC: cristal en notificaciones: necesita Android 13");
            return;
        }
        Tune.load(c);
        Class<?> expandable = XposedHelpers.findClass(PKG + "ExpandableView", cl);
        rowClass = XposedHelpers.findClass(PKG + "ExpandableNotificationRow", cl);
        // Se busca todo antes de enganchar nada. Lo que no exista en esta versión se sustituye
        // por el tamaño de la vista o un radio por defecto.
        mActualHeight = method(rowClass, "getActualHeight");
        mClipTop = method(rowClass, "getClipTopAmount");
        mClipBottom = method(rowClass, "getClipBottomAmount");
        mTopRadius = method(rowClass, "getTopCornerRadius");
        mBottomRadius = method(rowClass, "getBottomCornerRadius");

        // Cada vez que una tarjeta cambia de altura, de recorte o de sitio: se le pone su capa (la
        // primera vez) y se redibuja. Solo las tarjetas: ExpandableView también es la "estantería"
        // de iconos, el pie del panel, los títulos de sección...
        XC_MethodHook changed = new XC_MethodHook() {
            @Override
            protected boolean skip(Object self) {
                return !enabled || broken || !rowClass.isInstance(self);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                attach((View) p.thisObject);
            }
        };
        int n = 0;
        for (String name : new String[]{"setActualHeight", "setClipTopAmount", "setClipBottomAmount", "onLayout"}) {
            n += XposedBridge.hookAllMethods(expandable, name, changed).size();
        }
        if (n == 0) throw new NoSuchMethodError(PKG + "ExpandableView: no hay métodos de altura");
        CcGlass.note("HyperSpring CC: cristal en notificaciones activo (" + n + " hooks"
                + (mActualHeight == null ? ", sin altura actual" : "")
                + (mTopRadius == null ? ", sin radios" : "") + ")");
    }

    private static Method method(Class<?> c, String name) {
        try {
            Method m = c.getMethod(name);                 // pública, también heredada o de interfaz
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Pone la capa a una tarjeta (si no la tenía) y la redibuja. Hilo principal. */
    private static void attach(View row) {
        Skin s = SKINS.get(row);
        if (s == null) {
            s = new Skin(row);
            SKINS.put(row, s);
            row.getOverlay().add(s);
        }
        int w = row.getWidth(), h = row.getHeight();
        if (w > 0 && h > 0) s.setBounds(0, 0, w, h);
        s.invalidateSelf();
    }

    /** Llegó otra captura del fondo (la llama {@link CcGlass}): las tarjetas se redibujan. */
    static void backdropChanged() {
        if (!enabled || SKINS.isEmpty()) return;
        for (View v : new ArrayList<>(SKINS.keySet())) {
            if (v != null && v.isAttachedToWindow()) v.invalidate();
        }
    }

    private static int intOf(Method m, View v, int def) {
        if (m == null) return def;
        try {
            Object o = m.invoke(v);
            return o instanceof Number ? ((Number) o).intValue() : def;
        } catch (Throwable t) {
            return def;
        }
    }

    private static float floatOf(Method m, View v, float def) {
        if (m == null) return def;
        try {
            Object o = m.invoke(v);
            return o instanceof Number ? ((Number) o).floatValue() : def;
        } catch (Throwable t) {
            return def;
        }
    }

    /** Un dibujo fallido; varios seguidos apagan el cristal de las notificaciones. */
    private static void failed(Throwable t) {
        if (++fails < MAX_FAILS || broken) return;
        broken = true;
        CcGlass.note("HyperSpring CC: cristal en notificaciones desactivado tras " + MAX_FAILS
                + " fallos seguidos (" + t + ")");
        XposedBridge.log(t);
        for (View v : new ArrayList<>(SKINS.keySet())) if (v != null) v.invalidate();
    }

    /**
     * La capa de una tarjeta. Guarda la vista con una referencia débil: SKINS es un WeakHashMap
     * cuya clave es la propia vista, y si el valor la retuviera no se liberaría nunca. La vista sí
     * retiene a la capa (está en su overlay), que es lo que la mantiene viva.
     */
    private static final class Skin extends Drawable {
        private final java.lang.ref.WeakReference<View> rowRef;
        private final float dp;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path inner = new Path();
        private final RectF box = new RectF();
        private final int[] loc = new int[2];
        private RuntimeShader shader;
        private Bitmap bound;

        Skin(View row) {
            rowRef = new java.lang.ref.WeakReference<>(row);
            dp = row.getResources().getDisplayMetrics().density;
        }

        @Override
        public void draw(Canvas c) {
            View row = rowRef.get();
            if (row == null || !enabled || broken || !c.isHardwareAccelerated()) return;
            try {
                drawGlass(c, row);
                fails = 0;
            } catch (Throwable t) {
                failed(t);
            }
        }

        private void drawGlass(Canvas c, View row) {
            int w = row.getWidth();
            int top = Math.max(0, intOf(mClipTop, row, 0));
            int bottom = intOf(mActualHeight, row, row.getHeight()) - Math.max(0, intOf(mClipBottom, row, 0));
            float h = bottom - top;
            if (w <= 0 || h < MIN_DP * dp) return;

            // El panel se acaba de abrir (o sale una emergente): captura nueva del fondo.
            long now = SystemClock.uptimeMillis();
            if (now - lastDrawMs > REOPEN_MS) CcGlass.markStale(row);
            lastDrawMs = now;

            float half = Math.min(w, h) / 2f;
            float def = 16f * dp;
            float rt = Math.min(half, Math.max(0f, floatOf(mTopRadius, row, def)));
            float rb = Math.min(half, Math.max(0f, floatOf(mBottomRadius, row, def)));

            if (shader == null) shader = GlassShader.create();
            RuntimeShader rs = shader;
            float band = Math.min(CcGlass.BAND_DP * dp, Math.min(w, h) * CcGlass.BAND_MAX_FRACTION);
            float lensH = Math.min(Math.min(Tune.refrH, MAX_LENS_DP) * dp, Math.min(w, h) / 3f);
            rs.setFloatUniform("size", w, h);
            rs.setFloatUniform("band", band);
            rs.setFloatUniform("disp", 0f);
            rs.setFloatUniform("stretch", 1f);
            rs.setFloatUniform("overshoot", Tune.edgeOver * dp);
            rs.setFloatUniform("dispersion", CcGlass.DISPERSION);
            rs.setFloatUniform("fill", 0f);                    // solo el borde: el texto se sigue leyendo
            rs.setFloatUniform("bandAlpha", CcGlass.BAND_ALPHA);
            rs.setFloatUniform("tone", CcGlass.TONE);
            rs.setFloatUniform("sat", CcGlass.SATURATION);
            rs.setFloatUniform("edgeSat", Tune.edgeSat);
            rs.setFloatUniform("mag", 0f);
            rs.setFloatUniform("rimw", Tune.rim * dp);
            rs.setFloatUniform("glow", CcGlass.GLOW_DP * dp);
            rs.setFloatUniform("innerDark", CcGlass.INNER_DARK);
            rs.setFloatUniform("rimGain", Tune.rimGain);
            rs.setFloatUniform("light", CcGlass.LENS_LIGHT);
            rs.setFloatUniform("active", 0f);
            float want = Tune.refrH * dp;
            rs.setFloatUniform("refrH", lensH);
            rs.setFloatUniform("refrAmt", want > 0f ? Tune.refr * dp * lensH / want : 0f);
            rs.setFloatUniform("chroma", Tune.chroma);

            // El fondo que hay detrás: la captura compartida, si ya es la de esta ventana.
            Bitmap b = CcGlass.backdropFor(row);
            if (b != null) {
                if (bound != b) {
                    rs.setInputShader("wall", GlassShader.linear(b));
                    bound = b;
                }
                float k = CcGlass.backdropScaleNow();
                float[] sc = CcGlass.scaleOf(row);
                row.getLocationOnScreen(loc);
                rs.setFloatUniform("origin", loc[0] * k, (loc[1] + top * sc[1]) * k);
                rs.setFloatUniform("scale", sc[0] * k, sc[1] * k);
                rs.setFloatUniform("hasWall", 1f);
            } else {
                rs.setFloatUniform("hasWall", 0f);           // mientras llega la captura: solo la luz
            }
            paint.setShader(rs);

            int save = c.save();
            c.translate(0f, top);
            // Solo el anillo del borde: el centro se recorta (ahí el shader no pinta nada).
            float inset = Math.max(band, lensH) + Math.max(2f * dp, Tune.rim * dp * 3f);
            if (w > 2 * inset && h > 2 * inset) {
                inner.reset();
                box.set(inset, inset, w - inset, h - inset);
                float ri = Math.max(0f, Math.min(rt, rb) - inset);
                inner.addRoundRect(box, ri, ri, Path.Direction.CW);
                c.clipOutPath(inner);
            }
            if (Math.abs(rt - rb) < 0.5f) {
                rs.setFloatUniform("radius", rt);
                c.drawRect(0, 0, w, h, paint);
            } else {
                // Radios distintos arriba y abajo (tarjetas de un grupo): cada mitad con el suyo.
                float mid = h / 2f;
                int s2 = c.save();
                c.clipRect(0, 0, w, mid);
                rs.setFloatUniform("radius", rt);
                c.drawRect(0, 0, w, h, paint);
                c.restoreToCount(s2);
                c.clipRect(0, mid, w, h);
                rs.setFloatUniform("radius", rb);
                c.drawRect(0, 0, w, h, paint);
            }
            c.restoreToCount(save);

            if (!logged) {
                logged = true;
                CcGlass.note("HyperSpring CC: cristal en la primera notificación ("
                        + row.getClass().getSimpleName() + " " + w + "x" + Math.round(h)
                        + ", radios " + Math.round(rt) + "/" + Math.round(rb) + ", fondo "
                        + (b != null ? "listo" : "pendiente") + ")");
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private NotifGlass() {
    }
}
