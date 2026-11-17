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

    /** Margen de la barra respecto a la vista del dock, y radio de sus esquinas (dp). */
    private static final float SIDE_DP = 10f, TOP_DP = 2f, BOTTOM_DP = 6f, RADIUS_DP = 30f;
    private static final Map<View, GlassFolder.Glass> GLASS = new WeakHashMap<>();
    private static final Map<View, Drawable> WRAPPED = new WeakHashMap<>();

    static void install(ClassLoader cl) {
        Class<?> dock = XposedHelpers.findClass("com.miui.home.launcher.hotseats.HotSeats", cl);
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
        if (bg == null || bg != WRAPPED.get(dock)) {
            // Lo que HyperOS tuviera de fondo va debajo; el cristal, encima, con su margen.
            Drawable under = bg != null ? bg : new ColorDrawable(0);
            InsetDrawable bar = new InsetDrawable(g, Math.round(SIDE_DP * dp), Math.round(TOP_DP * dp),
                    Math.round(SIDE_DP * dp), Math.round(BOTTOM_DP * dp));
            LayerDrawable layers = new LayerDrawable(new Drawable[]{under, bar});
            WRAPPED.put(dock, layers);
            dock.setBackground(layers);
        }
        g.configure(true, RADIUS_DP * dp);
        g.invalidateSelf();
    }

    private GlassDock() {
    }
}
