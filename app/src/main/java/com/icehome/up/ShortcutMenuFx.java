package com.icehome.up;

import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

import com.icehome.up.xp.XC_MethodHook;
import com.icehome.up.xp.XposedBridge;
import com.icehome.up.xp.XposedHelpers;

/**
 * Menú de atajos (el que sale al mantener pulsado un icono) con muelle y cristal líquido.
 *
 * <p><b>El muelle.</b> HyperOS ya anima el menú: {@code ShortcutAnimHelper} lleva una "fracción"
 * de 0 a 1 con un muelle de Folme (amortiguación 0,82, respuesta 0,33) y, en cada fotograma, coloca
 * el menú entre el tamaño del icono (0) y su tamaño final (1). Esa mezcla no se recorta por
 * arriba: si el muelle se pasa de 1, el menú crece un poco más que su tamaño y vuelve. Así que para
 * el rebote basta con darle un muelle menos amortiguado; no hay que dibujar nada propio ni pelear
 * con la animación de serie. Al cerrar se deja el muelle de serie: por debajo de 0 sí se recorta, y
 * con rebote el menú se quedaría quieto en el icono un instante antes de desaparecer.
 *
 * <p><b>El cristal.</b> Es el mismo de las carpetas ({@link GlassFolder.Glass}), puesto como fondo
 * del menú: se dibuja debajo de las opciones y el contorno redondeado del propio menú lo recorta.
 * Va en modo "solo canto": el interior sigue siendo el desenfoque de HyperOS (que deja ver los
 * iconos de detrás) y el borde dobla el fondo de pantalla como una lente.
 */
final class ShortcutMenuFx {

    /** Rebote (0 = como HyperOS, 1 = mucho). Lo pone Tune.reload; se lee al abrir cada menú. */
    static volatile float bounce = 0.5f;
    /** Cristal líquido en el borde del menú. */
    static volatile boolean glass = true;

    /** El muelle de serie de HyperOS para este menú. */
    private static final float STOCK_DAMPING = 0.82f, RESPONSE = 0.33f;
    /**
     * Con rebote 1, la amortiguación baja hasta 0,42: el menú se pasa un ~23 % y vuelve. Con el 0,5
     * por defecto se queda en 0,62 (un ~8 %), que se nota sin parecer de goma.
     */
    private static final float MIN_DAMPING = 0.42f;
    /** Velo blanco del cristal en el menú, respecto al de las carpetas. */
    private static final float MENU_VEIL = 0.35f;

    private static Method spring, setEase;
    private static Field animConfig;
    /** El cristal de cada menú y el fondo con el que se puso (si HyperOS lo cambia, se vuelve a poner). */
    private static final Map<View, GlassFolder.Glass> GLASS = new WeakHashMap<>();
    private static final Map<View, Drawable> WRAPPED = new WeakHashMap<>();

    static void install(ClassLoader cl) throws Exception {
        Class<?> helper = XposedHelpers.findClass("com.miui.home.launcher.shortcuts.ShortcutAnimHelper", cl);
        Class<?> menu = XposedHelpers.findClass("com.miui.home.launcher.shortcuts.ShortcutMenu", cl);
        Class<?> ease = XposedHelpers.findClass("miuix.animation.FolmeEase", cl);
        Class<?> config = XposedHelpers.findClass("miuix.animation.base.AnimConfig", cl);
        // Se busca todo antes de enganchar nada: si algo ha cambiado en esta versión de HyperOS,
        // se sale con un error en el registro y el menú se queda como de serie.
        spring = ease.getMethod("spring", float.class, float.class);
        setEase = config.getMethod("setEase", spring.getReturnType());
        animConfig = XposedHelpers.findField(helper, "mAnimConfig");

        // Al abrir: muelle con rebote. showWithAnim prepara la animación y la lanza en el siguiente
        // fotograma con mAnimConfig, así que basta con cambiarle el muelle justo antes.
        XposedBridge.hookAllMethods(helper, "showWithAnim", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                float b = Math.max(0f, Math.min(1f, bounce));
                setSpring(p.thisObject, STOCK_DAMPING - (STOCK_DAMPING - MIN_DAMPING) * b);
            }
        });
        // Al cerrar: el muelle de serie (ver arriba por qué).
        XposedBridge.hookAllMethods(helper, "dismissWithAnim", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                setSpring(p.thisObject, STOCK_DAMPING);
            }
        });
        // Cristal: después de que HyperOS ponga su fondo y su desenfoque, y antes del primer fotograma.
        XposedBridge.hookAllMethods(menu, "showWithAnim", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    applyGlass((View) p.thisObject);
                } catch (Throwable t) {
                    Log.e("menú de atajos: cristal", t);
                }
            }
        });
        GlassFolder.trackOffsets();
        Log.i("menú de atajos con muelle activo");
    }

    private static void setSpring(Object helper, float damping) {
        try {
            Object cfg = animConfig.get(helper);
            if (cfg == null) return;
            setEase.invoke(cfg, spring.invoke(null, damping, RESPONSE));
        } catch (Throwable t) {
            // Nunca romper el menú por un adorno: se queda con el muelle que tuviera.
            Log.e("menú de atajos: muelle", t);
        }
    }

    /** Pone (o quita) el cristal como capa de arriba del fondo del menú. */
    private static void applyGlass(final View menu) {
        GlassFolder.Glass g = GLASS.get(menu);
        Drawable bg = menu.getBackground();
        boolean ours = bg != null && bg == WRAPPED.get(menu);
        if (!glass) {
            if (ours) menu.setBackground(((LayerDrawable) bg).getDrawable(0));
            WRAPPED.remove(menu);
            return;
        }
        if (g == null) {
            g = new GlassFolder.Glass(menu);
            g.veilScale = MENU_VEIL;
            GLASS.put(menu, g);
        }
        GlassFolder.loadWallpaper(menu.getContext());
        if (!ours) {
            // HyperOS pone o cambia su fondo al abrir (o con el modo oscuro): el suyo va debajo
            // y el cristal encima. Sin fondo, una capa transparente ocupa su sitio.
            Drawable under = bg != null ? bg : new android.graphics.drawable.ColorDrawable(0);
            LayerDrawable layers = new LayerDrawable(new Drawable[]{under, g});
            WRAPPED.put(menu, layers);
            menu.setBackground(layers);
        }
        final GlassFolder.Glass glassNow = g;
        Runnable radius = new Runnable() {
            @Override
            public void run() {
                glassNow.configure(false, outlineRadius(menu));
                glassNow.invalidateSelf();
            }
        };
        // El contorno se redondea mientras crece: se lee al empezar y otra vez al terminar.
        menu.post(radius);
        menu.postDelayed(radius, 450);
    }

    /** Radio de las esquinas del menú (el de su contorno, que HyperOS anima). */
    private static float outlineRadius(View v) {
        try {
            if (v.getOutlineProvider() != null && v.getWidth() > 0) {
                Outline o = new Outline();
                v.getOutlineProvider().getOutline(v, o);
                float r = o.getRadius();
                if (r > 0f) return r;
            }
        } catch (Throwable ignored) {
        }
        return 20f * v.getResources().getDisplayMetrics().density;
    }

    private ShortcutMenuFx() {
    }
}
