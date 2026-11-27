package com.icehome.up;

import android.graphics.RectF;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import com.icehome.up.xp.XC_MethodHook;
import com.icehome.up.xp.XposedHelpers;

/**
 * Muelles de abrir y cerrar apps. MIUI da un muelle (amortiguación, periodo) a cada propiedad:
 * centerX, centerY, Width, Height, radio, transparencia… Aquí se ajustan después de MIUI y del
 * mod iOS de Kashi:
 * <ul>
 * <li>Velocidad: divide el periodo.</li>
 * <li>Rebote: limita la amortiguación (1 = sin rebote).</li>
 * </ul>
 */
final class Springs {

    static volatile float speed = 1f, bounce = 0f;

    private static Constructor<?> make;
    private static Field key, damping, response, impulse;

    static void install(ClassLoader cl) {
        final Class<?> param = XposedHelpers.findClass("com.miui.home.recents.util.SpringParamsProvider$SpringParam", cl);
        make = XposedHelpers.findConstructorExact(param, String.class, float.class, float.class, float.class);
        key = WindowAnim.field(param, "key");
        damping = WindowAnim.field(param, "damping");
        response = WindowAnim.field(param, "response");
        impulse = WindowAnim.field(param, "impulse");
        Class<?> type = XposedHelpers.findClass("com.miui.home.recents.util.RectFSpringAnim$AnimType", cl);

        XposedHelpers.findAndHookMethod("com.miui.home.recents.util.SpringParamsProvider$Companion", cl,
                "generateSpringBundlesByAnimType", type, RectF.class, RectF.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            String t = ((Enum<?>) p.args[0]).name();
                            if (!"OPEN_FROM_HOME".equals(t) && !"CLOSE_TO_HOME".equals(t)) return;
                            Object out = adjust(param, (Object[]) p.getResult(), "OPEN_FROM_HOME".equals(t));
                            if (out != null) p.setResult(out);
                        } catch (Throwable e) {
                            Log.e("muelles", e);
                        }
                    }
                });
        // Ritmo real del muelle de MIUI: getModifyResponse multiplica el periodo por la escala de
        // animación (la del sistema o la del mod de Kashi, «anim_speed»). Se guarda para que los
        // fundidos propios del genio vayan al mismo ritmo que la ventana. Va aparte: si no existe
        // en este launcher, el genio sigue con escala 1. Solo acorta (escala < 1): alargarlos con
        // escalas lentas hacía el genio pesado; con escalas rápidas evita que se coman el viaje.
        try {
            XposedHelpers.findAndHookMethod("com.miui.home.recents.util.RectFSpringAnim", cl,
                    "getModifyResponse", float.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                float in = (Float) p.args[0], out = (Float) p.getResult();
                                float t = in > 0f ? out / in / speed : 0f;
                                if (t > 0f) Genie.timeScale = Math.max(0.05f, Math.min(1f, t));   // NaN o 0: se deja como está
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable e) {
            Log.e("escala de animación", e);
        }
    }

    /** Copia ajustada del array de muelles, o null si no hay nada que cambiar. */
    private static Object adjust(Class<?> param, Object[] in, boolean open) throws Exception {
        // (El Pergamino ya no frena el muelle de MIUI: desde la 3.4 se desenrolla con su propio reloj.)
        float s = speed, b = bounce;
        // iOS: la ventana se mueve con el muelle del boceto (mucho más rápido que el de MIUI, que
        // tardaba ~470 ms en llegar al 90 %); Velocidad y Rebote siguen valiendo encima.
        boolean ios = Genie.draws() && Genie.style == Genie.STYLE_IOS;
        if (in == null || (s == 1f && b == 0f && !ios)) return null;
        Object out = Array.newInstance(param, in.length);
        for (int i = 0; i < in.length; i++) {
            Object sp = in[i];
            String k = (String) key.get(sp);
            float d = damping.getFloat(sp), r = response.getFloat(sp) / s;
            // El rebote solo en posición y tamaño (como iOS): si el radio o la transparencia se
            // pasan y vuelven, las esquinas "laten" y la ventana parpadea.
            if (ios && isGeometry(k)) {
                d = Math.min(Genie.IOS_DAMPING, 1f - b);
                r = Genie.IOS_RESPONSE / s;
            } else if (b > 0f && isGeometry(k)) d = Math.min(d, 1f - b);
            Array.set(out, i, make.newInstance(k, d, r, impulse.getFloat(sp)));
        }
        return out;
    }

    /** Claves de MIUI (SpringParamsProvider) que mueven o escalan la ventana. */
    private static boolean isGeometry(String k) {
        return "centerX".equals(k) || "centerY".equals(k) || "Width".equals(k) || "Height".equals(k);
    }

    private Springs() {
    }
}
