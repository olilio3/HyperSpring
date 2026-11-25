package com.icehome.up.cc;

import android.content.Context;
import android.provider.Settings;

/**
 * Ajustes del cristal que se pueden cambiar sin recompilar (app de ajustes o
 * "settings put global kashiplus_... valor"). Los lee SystemUI cada vez que se abre el centro
 * de control.
 */
public final class Tune {

    /** Clave, nombre, mínimo, máximo, por defecto, decimales (-1 = interruptor), explicación. */
    public static final Object[][] ITEMS = {
            {"#", "General"},
            {"kashiplus_cc_on", "Cristal en el centro de control", 0f, 1f, 1f, -1, "Las tarjetas y botones del centro de control pasan a ser cristal líquido. Probado con el centro de control iOS de ModsCenter y con el de HyperOS."},
            {"#", "Interior del cristal"},
            {"kashiplus_cc_blur", "Desenfoque", 0f, 40f, 8f, 0, "Cuánto se desenfoca lo que se ve a través de las piezas, en dp."},
            {"kashiplus_cc_sat", "Saturación de las tarjetas", 0.5f, 2.5f, 1.3f, 2, "Intensidad de los colores que se ven a través de las tarjetas grandes. 1 = sin cambio."},
            {"kashiplus_cc_sat_button", "Saturación de los botones", 0.5f, 2.5f, 1.2f, 2, "Lo mismo para los botones redondos y pequeños."},
            {"kashiplus_cc_tone", "Luz", 0.6f, 1.5f, 1.1f, 2, "Brillo del cristal. Más de 1 lo aclara; menos, lo oscurece."},
            {"kashiplus_cc_white", "Velo blanco", 0f, 0.4f, 0.03f, 2, "Capa blanca muy sutil sobre el cristal, para que se lea mejor el texto sobre fondos oscuros."},
            {"kashiplus_cc_gloss", "Brillo glossy", 0f, 160f, 34f, 0, "Reflejo suave en la parte de arriba de cada pieza."},
            {"kashiplus_cc_magnify", "Efecto lupa", 0f, 0.12f, 0.04f, 3, "Aumento de lo que se ve por el centro de la pieza, como una lente gruesa."},
            {"#", "Refracción del borde"},
            {"kashiplus_cc_refr", "Refracción", 0f, 80f, 20f, 0, "Cuánto se dobla lo de detrás en el borde de cada pieza, en dp. Es la lente de AndroidLiquidGlass."},
            {"kashiplus_cc_refr_h", "Ancho de la refracción", 0f, 60f, 14f, 0, "Hasta dónde llega la refracción desde el borde hacia dentro, en dp. En los botones pequeños se limita sola."},
            {"kashiplus_cc_chroma", "Dispersión de color", 0f, 3f, 1f, 2, "Separación de colores tipo arcoíris en las esquinas, como en un prisma. 0 la quita."},
            {"kashiplus_cc_bevel", "Bisel plano", 0f, 1f, 0f, 2, "Forma de la lente del borde. 0 = curva redonda de siempre; 1 = centro plano y toda la curva pegada al borde, como iOS 26. Con 1 conviene subir la refracción."},
            {"#", "Canto y línea de luz"},
            {"kashiplus_cc_edge", "Canto de lente", 0f, 16f, 4f, 1, "Grosor del canto iluminado de cada pieza, en dp."},
            {"kashiplus_cc_edge_over", "Color del borde", 0f, 10f, 3.5f, 1, "El canto toma el color de lo que hay justo fuera de la pieza, hasta esta distancia en dp."},
            {"kashiplus_cc_edge_sat", "Saturación del canto", 0f, 1.5f, 0.4f, 2, "Intensidad del color del canto."},
            {"kashiplus_cc_rim", "Grosor de la línea de luz", 0f, 2.5f, 0.69f, 2, "La línea brillante que recorre el borde, en dp."},
            {"kashiplus_cc_rim_gain", "Intensidad de la línea de luz", 0f, 2.5f, 1f, 2, "Brillo de esa línea. 0 la quita."},
            {"kashiplus_cc_fresnel", "Línea blanca del borde", 0f, 2f, 0f, 2, "Línea blanca fina por todo el borde: más brillante en el lado que mira a la luz y algo más suave en el contrario. 0 la quita."},
            {"kashiplus_cc_shade", "Sombra interior", 0f, 0.4f, 0f, 2, "Sombra suave por dentro del borde de abajo, para que el cristal parezca grueso. 0 la quita."},
            {"kashiplus_cc_depth", "Profundidad", 0f, 2f, 0f, 2, "La lente del borde se curva como una cúpula: lo de detrás se abre hacia fuera desde el centro. 0 la quita."},
            {"#", "Calidad y fluidez"},
            {"kashiplus_cc_quality", "Calidad", 0f, 2f, 1f, 0, "Resolución del fondo que se ve a través del cristal: 0 baja (más rápido), 1 media, 2 alta (más nítido, gasta más). Se nota en la próxima captura."},
            {"kashiplus_cc_fps", "FPS del cristal", 15f, 120f, 60f, 0, "Cuántas veces por segundo se redibuja el cristal de la notificación mientras se mueve. Menos gasta menos, pero el cristal va algo por detrás."},
            {"#", "Fondo"},
            {"kashiplus_cc_dim", "Oscurecer el fondo", 0f, 0.7f, 0.5f, 2, "Cuánto se oscurece la pantalla detrás del centro de control."},
            {"kashiplus_cc_bg_blur", "Desenfoque del fondo", 0f, 1.5f, 1f, 2, "Cuánto se desenfoca la pantalla detrás del centro de control. 1 = como HyperOS, 0 = sin desenfoque."},
    };

    static volatile boolean ccOn = true;
    static volatile float ccBlur = 8f, sat = 1.3f, satButton = 1.2f, tone = 1.1f, white = 0.03f,
            gloss = 34f, edge = 4f, edgeOver = 3.5f, edgeSat = 0.4f, magnify = 0.04f,
            rim = 0.69f, rimGain = 1f, dim = 0.5f, refr = 20f, refrH = 14f, chroma = 1f, fps = 60f,
            bevel = 0f, fresnel = 0f, shade = 0f, depth = 0f, bgBlur = 1f;
    static volatile int quality = 1;

    /** Valores guardados por la app (vía PrefsProvider); si no, "settings put global". */
    private static java.util.Map<String, String> read(Context c) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        try (android.database.Cursor cur = c.getContentResolver().query(com.icehome.up.PrefsProvider.URI, null, null, null, null)) {
            while (cur != null && cur.moveToNext()) m.put(cur.getString(0), cur.getString(1));
        } catch (Throwable ignored) {
        }
        return m;
    }

    static float get(Context c, String key) {
        return get(c, key, read(c));
    }

    /** Interruptor de función al arrancar la interfaz del sistema (con su contexto propio). */
    public static boolean on(Context c, String key) {
        return get(c, key) >= 0.5f;
    }

    private static float get(Context c, String key, java.util.Map<String, String> saved) {
        for (Object[] it : ITEMS) {
            if (!it[0].equals(key)) continue;
            float def = (Float) it[4];
            try {
                String v = saved.get(key);
                if (v == null) v = Settings.Global.getString(c.getContentResolver(), key);
                if (v != null) return Math.max((Float) it[2], Math.min((Float) it[3], Float.parseFloat(v.trim())));
            } catch (Throwable ignored) {
            }
            return def;
        }
        return 0f;
    }

    /** Relee todo (en SystemUI, al abrir el centro de control o al cambiar un ajuste). */
    static void load(Context c) {
        java.util.Map<String, String> m = read(c);
        load(c, m);
    }

    private static void load(Context c, java.util.Map<String, String> saved) {
        ccOn = get(c, "kashiplus_cc_on", saved) >= 0.5f;
        ccBlur = get(c, "kashiplus_cc_blur", saved);
        sat = get(c, "kashiplus_cc_sat", saved);
        satButton = get(c, "kashiplus_cc_sat_button", saved);
        tone = get(c, "kashiplus_cc_tone", saved);
        white = get(c, "kashiplus_cc_white", saved);
        gloss = get(c, "kashiplus_cc_gloss", saved);
        edge = get(c, "kashiplus_cc_edge", saved);
        edgeOver = get(c, "kashiplus_cc_edge_over", saved);
        edgeSat = get(c, "kashiplus_cc_edge_sat", saved);
        magnify = get(c, "kashiplus_cc_magnify", saved);
        rim = get(c, "kashiplus_cc_rim", saved);
        rimGain = get(c, "kashiplus_cc_rim_gain", saved);
        dim = get(c, "kashiplus_cc_dim", saved);
        bgBlur = get(c, "kashiplus_cc_bg_blur", saved);
        refr = get(c, "kashiplus_cc_refr", saved);
        refrH = get(c, "kashiplus_cc_refr_h", saved);
        chroma = get(c, "kashiplus_cc_chroma", saved);
        bevel = get(c, "kashiplus_cc_bevel", saved);
        fresnel = get(c, "kashiplus_cc_fresnel", saved);
        shade = get(c, "kashiplus_cc_shade", saved);
        depth = get(c, "kashiplus_cc_depth", saved);
        quality = Math.round(get(c, "kashiplus_cc_quality", saved));
        fps = get(c, "kashiplus_cc_fps", saved);
    }

    private Tune() {
    }
}
