package com.icehome.up;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;

import java.util.Map;
import java.util.WeakHashMap;

import com.icehome.up.xp.XC_MethodHook;
import com.icehome.up.xp.XposedBridge;
import com.icehome.up.xp.XposedHelpers;

/**
 * Dock de cristal (EN PRUEBAS): la fila de iconos de abajo del escritorio pasa a ser una barra
 * flotante de cristal líquido, con el mismo cristal que las carpetas ({@link GlassFolder.Glass}):
 * el fondo de pantalla desenfocado dentro y doblado en el borde.
 *
 * <p>El cristal va como fondo de la vista del dock ({@code HotSeats}), por debajo de los iconos,
 * con un margen alrededor para que parezca una barra suelta. Usa los ajustes de cristal de las
 * carpetas y, como ellas, necesita un fondo de pantalla estático.
 */
final class GlassDock {

    /** Sin iconos que medir: margen de la barra respecto a la vista del dock (dp). */
    private static final float SIDE_DP = 10f, TOP_DP = 2f, BOTTOM_DP = 6f;
    /** Con iconos: lo que sobresale la barra alrededor de ellos (dp; ajuste icehome_dock_pad). */
    static volatile float padDp = 12f;
    /** Radio de las esquinas (dp); nunca más que media altura de la barra. */
    private static final float RADIUS_DP = 30f;
    private static final Map<View, GlassFolder.Glass> GLASS = new WeakHashMap<>();
    private static final Map<View, LayerDrawable> WRAPPED = new WeakHashMap<>();
    /** ShortcutIcon.getIconBounds: el cuadrado del icono dentro de su celda (que es mucho más grande). */
    private static Class<?> shortcut;
    private static java.lang.reflect.Method iconBounds;

    static void install(ClassLoader cl) {
        Class<?> dock = XposedHelpers.findClass("com.miui.home.launcher.hotseats.HotSeats", cl);
        try {
            shortcut = XposedHelpers.findClass("com.miui.home.launcher.ShortcutIcon", cl);
            iconBounds = XposedHelpers.findMethodExact(shortcut, "getIconBounds", android.graphics.Rect.class);
            iconBounds.setAccessible(true);
        } catch (Throwable t) {
            Log.w("dock de cristal: sin getIconBounds, la barra ocupa todo el dock");
        }
        // Al crearse el dock se le pone un aviso de cambio de tamaño; ahí se coloca el cristal.
        // El aviso usa la vista que recibe (no captura el dock): así no lo retiene.
        XposedBridge.hookAllConstructors(dock, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                ((View) p.thisObject).addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                    @Override
                    public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                        try {
                            apply(v);
                        } catch (Throwable e) {
                            Log.e("dock de cristal", e);
                        }
                    }
                });
            }
        });
        GlassFolder.trackOffsets();
        Log.i("dock de cristal activo");
    }

    private static void apply(View dock) {
        if (dock.getWidth() == 0 || dock.getHeight() == 0) return;
        float dp = dock.getResources().getDisplayMetrics().density;
        GlassFolder.Glass g = GLASS.get(dock);
        if (g == null) {
            g = new GlassFolder.Glass(dock);
            g.veilScale = 0.5f;
            GLASS.put(dock, g);
            g.track();
        }
        GlassFolder.loadWallpaper(dock.getContext());
        Drawable bg = dock.getBackground();
        LayerDrawable layers = WRAPPED.get(dock);
        if (bg == null || bg != layers) {
            // Lo que HyperOS tuviera de fondo va debajo; el cristal, encima, con su margen.
            Drawable under = bg != null ? bg : new ColorDrawable(0);
            layers = new LayerDrawable(new Drawable[]{under, g});
            WRAPPED.put(dock, layers);
            dock.setBackground(layers);
        }
        // La barra abraza los iconos (como el dock de Kashi), no toda la vista del dock: las celdas
        // de los iconos son mucho más altas que el icono y la barra salía enorme (issue #23).
        android.graphics.Rect fit = icons(dock);
        int l, t, r, b;
        if (fit != null) {
            int pad = Math.round(padDp * dp);
            l = Math.max(0, fit.left - pad);
            t = Math.max(0, fit.top - pad);
            r = Math.max(0, dock.getWidth() - fit.right - pad);
            b = Math.max(0, dock.getHeight() - fit.bottom - pad);
        } else {
            l = r = Math.round(SIDE_DP * dp);
            t = Math.round(TOP_DP * dp);
            b = Math.round(BOTTOM_DP * dp);
        }
        if (layers.getLayerInsetLeft(1) != l || layers.getLayerInsetTop(1) != t
                || layers.getLayerInsetRight(1) != r || layers.getLayerInsetBottom(1) != b) {
            layers.setLayerInset(1, l, t, r, b);
            Log.i("dock de cristal: " + dock.getWidth() + "x" + dock.getHeight() + ", iconos " + fit
                    + ", márgenes " + l + "," + t + "," + r + "," + b);
            // setLayerInset no recoloca la capa hasta que cambian los límites: se fuerza.
            layers.setBounds(0, 0, 0, 0);
            layers.setBounds(0, 0, dock.getWidth(), dock.getHeight());
        }
        g.configure(true, Math.min(RADIUS_DP * dp, (dock.getHeight() - t - b) * 0.5f));
        g.invalidateSelf();
    }

    /** Unión de los cuadrados de los iconos del dock, en coordenadas del dock (null si no hay). */
    private static android.graphics.Rect icons(View dock) {
        if (iconBounds == null || !(dock instanceof android.view.ViewGroup)) return null;
        android.graphics.Rect all = null, one = new android.graphics.Rect();
        java.util.ArrayDeque<View> todo = new java.util.ArrayDeque<>();
        todo.add(dock);
        while (!todo.isEmpty()) {
            View v = todo.poll();
            if (v.getVisibility() != View.VISIBLE) continue;
            if (shortcut.isInstance(v)) {
                try {
                    iconBounds.invoke(v, one);
                } catch (Throwable e) {
                    return null;
                }
                if (one.isEmpty()) continue;
                ((android.view.ViewGroup) dock).offsetDescendantRectToMyCoords(v, one);
                if (all == null) all = new android.graphics.Rect(one);
                else all.union(one);
            } else if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup vg = (android.view.ViewGroup) v;
                for (int i = 0; i < vg.getChildCount(); i++) todo.add(vg.getChildAt(i));
            }
        }
        return all;
    }

    private GlassDock() {
    }
}
