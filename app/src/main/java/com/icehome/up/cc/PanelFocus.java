package com.icehome.up.cc;

import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

import java.lang.ref.WeakReference;

import com.icehome.up.xp.XC_MethodHook;
import com.icehome.up.xp.XposedBridge;
import com.icehome.up.xp.XposedHelpers;

/**
 * Cambio de página entre las notificaciones y el centro de control con «enfoque», al estilo de
 * ColorOS: la página que entra llega un poco más grande y desenfocada y se asienta nítida a su
 * tamaño; la que se va se encoge un poco y se desenfoca. Como cuando una cámara cambia el foco de
 * un plano a otro.
 *
 * <p>No tiene animación propia: va atada al avance con el que HyperOS abre el centro de control
 * (el mismo número, de 0 a 1, con el que desenfoca el fondo; ver {@code CcGlass.hookOpen}). Así
 * sigue al dedo, se puede dejar a medias y nunca va por libre.
 *
 * <p>Solo cambia la escala y el desenfoque de las dos vistas, nunca su transparencia ni su
 * posición (eso lo lleva HyperOS). Al acabar, y también un momento después por si la apertura se
 * cortó a medias, se deja todo como estaba.
 */
public final class PanelFocus {

    /** Fuerza (0 = apagado, 1 = normal, 2 = el doble). Ajuste «Enfoque al abrir». */
    static volatile float amount = 1f;
    /** Cuánto más grande llega el centro de control y cuánto se encogen las notificaciones. */
    private static final float GROW = 0.07f, SHRINK = 0.05f;
    /** Desenfoque máximo, en dp. */
    private static final float BLUR_DP = 18f;

    private static WeakReference<View> stack = new WeakReference<>(null);
    private static WeakReference<View> lastRoot = new WeakReference<>(null);
    private static float blurRoot = -1f, blurStack = -1f;
    private static boolean touched;

    /** En la interfaz del sistema: apunta la lista de notificaciones cuando se crea. */
    public static void install(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout", cl);
        XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                stack = new WeakReference<>((View) p.thisObject);
            }
        });
        CcGlass.note("HyperSpring CC: enfoque al cambiar de página listo");
    }

    /**
     * El centro de control va por {@code ratio} (0 = cerrado, 1 = abierto). Hilo principal.
     */
    static void apply(final View root, float ratio) {
        float k = Math.max(0f, Math.min(2f, amount));
        if (root == null || (k <= 0f && !touched)) return;
        float p = Math.max(0f, Math.min(1f, ratio));
        if (k <= 0f || p <= 0.001f || p >= 0.999f) {
            reset(root);
            return;
        }
        touched = true;
        lastRoot = new WeakReference<>(root);
        float dp = root.getResources().getDisplayMetrics().density;
        // Suavizado: empieza y acaba despacio.
        float e = p * p * (3f - 2f * p);
        // El centro de control: de grande y borroso a su sitio y nítido.
        float in = 1f - e;
        root.setPivotX(root.getWidth() / 2f);
        root.setPivotY(root.getHeight() / 2f);
        root.setScaleX(1f + GROW * k * in);
        root.setScaleY(1f + GROW * k * in);
        blurRoot = blur(root, BLUR_DP * dp * Math.min(1.5f, k) * in, blurRoot);
        // Las notificaciones (si se ven): se encogen y se desenfocan al irse.
        View s = stack.get();
        if (s != null && s.isAttachedToWindow() && s.isShown()) {
            s.setPivotX(s.getWidth() / 2f);
            s.setPivotY(s.getHeight() / 2f);
            s.setScaleX(1f - SHRINK * k * e);
            s.setScaleY(1f - SHRINK * k * e);
            blurStack = blur(s, BLUR_DP * dp * Math.min(1.5f, k) * e, blurStack);
        }
        // Seguro: si la apertura se corta a medias y no llega el último aviso, se limpia.
        root.removeCallbacks(SAFETY);
        root.postDelayed(SAFETY, 700);
    }

    private static final Runnable SAFETY = new Runnable() {
        @Override
        public void run() {
            View r = lastRoot.get();
            if (r != null) reset(r);
        }
    };

    /** Todo como estaba. */
    private static void reset(View root) {
        if (!touched) return;
        touched = false;
        try {
            root.removeCallbacks(SAFETY);
            root.setScaleX(1f);
            root.setScaleY(1f);
            blurRoot = blur(root, 0f, blurRoot);
            View s = stack.get();
            if (s != null) {
                s.setScaleX(1f);
                s.setScaleY(1f);
                blurStack = blur(s, 0f, blurStack);
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    /** Desenfoque en pasos de 2 px (rehacerlo en cada fotograma cuesta). Devuelve el puesto. */
    private static float blur(View v, float px, float now) {
        if (Build.VERSION.SDK_INT < 31) return now;
        float q = Math.round(px / 2f) * 2f;
        if (q < 2f) q = 0f;
        if (q == now) return now;
        try {
            v.setRenderEffect(q == 0f ? null : RenderEffect.createBlurEffect(q, q, Shader.TileMode.CLAMP));
        } catch (Throwable ignored) {
        }
        return q;
    }

    private PanelFocus() {
    }
}
