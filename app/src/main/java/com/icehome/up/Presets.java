package com.icehome.up;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Perfiles: grupos de valores que se aplican con un toque.
 *
 * <p>Un perfil solo toca los ajustes de "cómo se sienten" las animaciones (forma, velocidad,
 * rebote, calidad). No enciende ni apaga funciones, no cambia el aspecto del cristal y no toca
 * los fotogramas por segundo (dependen de la pantalla de cada móvil). Los ajustes que un perfil
 * no nombra vuelven a su valor por defecto, para que el resultado sea siempre el mismo se venga
 * de donde se venga.
 */
final class Presets {

    /** Ajustes que gestionan los perfiles. Los demás no se tocan nunca. */
    static final String[] KEYS = {
            "icehome_genie_style", "icehome_genie_strength", "icehome_genie_quality", "icehome_genie_physics",
            "icehome_genie_live", "icehome_wallpaper_zoom", "icehome_anim_speed", "icehome_anim_bounce",
            "icehome_curtain_strength",
            "telon_strength", "telon_curve", "telon_blur", "telon_bg_blur", "telon_quality",
    };

    /** Nombre (es, en), explicación (es, en) y pares clave-valor. */
    static final Object[][] ALL = {
            {"Suave", "Smooth",
                    "Animaciones tranquilas, con formas redondeadas y un rebote leve.",
                    "Calm animations with rounded shapes and a slight bounce.",
                    new String[]{"icehome_genie_style", "1", "icehome_genie_strength", "1.6", "icehome_anim_speed", "0.85",
                            "icehome_anim_bounce", "0.05", "icehome_curtain_strength", "0.8",
                            "telon_strength", "0.8", "telon_curve", "0.6"}},
            {"Rápido", "Fast",
                    "Todo más deprisa y sin rebote, para quien no quiere esperar a la animación.",
                    "Everything quicker and without bounce, for those who don't want to wait for the animation.",
                    new String[]{"icehome_genie_strength", "1.5", "icehome_anim_speed", "1.4", "icehome_anim_bounce", "0",
                            "icehome_genie_physics", "0.5", "telon_strength", "0.7"}},
            {"Rendimiento", "Performance",
                    "Para móviles modestos o para gastar menos: menos resolución, sin contenido en vivo y sin desenfoques del telón.",
                    "For modest phones or to save power: lower resolution, no live content and no curtain blurs.",
                    new String[]{"icehome_genie_quality", "50", "icehome_genie_live", "0", "icehome_anim_speed", "1.2",
                            "icehome_wallpaper_zoom", "0", "telon_quality", "30", "telon_blur", "0", "telon_bg_blur", "0"}},
    };

    /** El perfil Rendimiento, el que propone «¿Cómo va mi móvil?». */
    static final Object[] PERFORMANCE = ALL[ALL.length - 1];

    /** Ajustes del aspecto del cristal: los que gestionan los estilos de cristal. */
    static final String[] GLASS_KEYS = {
            "kashiplus_cc_blur", "kashiplus_cc_sat", "kashiplus_cc_sat_button", "kashiplus_cc_tone",
            "kashiplus_cc_white", "kashiplus_cc_gloss", "kashiplus_cc_magnify", "kashiplus_cc_refr",
            "kashiplus_cc_refr_h", "kashiplus_cc_chroma", "kashiplus_cc_rim_gain",
            "icehome_folder_blur", "icehome_folder_lens", "icehome_folder_refr", "icehome_folder_chroma",
            "icehome_folder_light",
    };

    /**
     * Estilos de cristal: cambian de un toque el aspecto del cristal del centro de control, las
     * notificaciones, las carpetas y el menú de atajos. Solo tocan {@link #GLASS_KEYS}. El sexto
     * elemento dice qué ajustes gestiona (ver {@link #keys}).
     */
    static final Object[][] GLASS = {
            {"Líquido", "Liquid",
                    "El de serie: desenfoque medio, refracción en el borde y un poco de arcoíris en las esquinas.",
                    "The default: medium blur, edge refraction and a little rainbow in the corners.",
                    new String[]{}, GLASS_KEYS},
            {"Transparente", "Clear",
                    "Casi sin desenfoque: se ve lo de detrás, con la lente del borde bien marcada.",
                    "Hardly any blur: you see what is behind, with a well-defined edge lens.",
                    new String[]{"kashiplus_cc_blur", "3", "kashiplus_cc_white", "0", "kashiplus_cc_tone", "1.25",
                            "kashiplus_cc_gloss", "20", "icehome_folder_blur", "2"}, GLASS_KEYS},
            {"Ahumado", "Smoked",
                    "Cristal oscuro y sobrio, con menos color. El texto se lee muy bien.",
                    "Dark, sober glass with less colour. Text reads very well.",
                    new String[]{"kashiplus_cc_tone", "0.8", "kashiplus_cc_sat", "1.1", "kashiplus_cc_sat_button", "1",
                            "kashiplus_cc_white", "0", "kashiplus_cc_gloss", "16",
                            "icehome_folder_light", "0.06"}, GLASS_KEYS},
            {"Esmerilado", "Frosted",
                    "Muy desenfocado y lechoso, sin arcoíris: como un cristal al ácido.",
                    "Heavily blurred and milky, with no rainbow: like etched glass.",
                    new String[]{"kashiplus_cc_blur", "24", "kashiplus_cc_white", "0.14", "kashiplus_cc_sat", "1.1",
                            "kashiplus_cc_refr", "8", "kashiplus_cc_chroma", "0", "kashiplus_cc_magnify", "0",
                            "icehome_folder_blur", "20", "icehome_folder_chroma", "0", "icehome_folder_refr", "12"}, GLASS_KEYS},
            {"Prisma", "Prism",
                    "Más refracción y mucho arcoíris en los bordes, como un prisma.",
                    "More refraction and lots of rainbow on the edges, like a prism.",
                    new String[]{"kashiplus_cc_chroma", "3", "kashiplus_cc_refr", "34", "kashiplus_cc_refr_h", "20",
                            "kashiplus_cc_rim_gain", "1.4", "icehome_folder_chroma", "3", "icehome_folder_refr", "45"}, GLASS_KEYS},
    };

    /** Animaciones y cristal a la vez: lo que gestionan los modos de sistema. */
    static final String[] MODE_KEYS = join(KEYS, GLASS_KEYS, new String[]{"icehome_menu_bounce"});

    /**
     * Modos de sistema: un perfil grande que pone a la vez el estilo de abrir y cerrar apps, los
     * rebotes y el tipo de cristal, para acercarse al aire de cada sistema. Son aproximaciones
     * hechas con los ajustes que ya hay, no copias.
     */
    static final Object[][] MODES = {
            {"HyperOS 4", "HyperOS 4",
                    "Copa con un rebote leve y el cristal líquido de serie.",
                    "Cup with a slight bounce and the default liquid glass.",
                    new String[]{"icehome_genie_style", "1", "icehome_genie_strength", "1.8", "icehome_anim_bounce", "0.05",
                            "icehome_menu_bounce", "0.4"}, MODE_KEYS},
            {"iOS 26", "iOS 26",
                    "La ventana crece desde el icono casi sin deformarse, con rebote, más zoom del fondo y un cristal claro con la lente marcada.",
                    "The window grows out of the icon with little distortion, a bounce, more wallpaper zoom and a clear glass with a marked lens.",
                    new String[]{"icehome_genie_style", "1", "icehome_genie_strength", "0.6", "icehome_anim_speed", "1.1",
                            "icehome_anim_bounce", "0.12", "icehome_wallpaper_zoom", "1.2", "icehome_genie_physics", "0.5",
                            "icehome_menu_bounce", "0.7", "telon_strength", "0.5", "telon_blur", "0.3",
                            "kashiplus_cc_blur", "5", "kashiplus_cc_white", "0", "kashiplus_cc_chroma", "1.6",
                            "kashiplus_cc_refr", "28", "icehome_folder_blur", "3",
                            "icehome_folder_chroma", "1.8"}, MODE_KEYS},
            {"iOS 27", "iOS 27",
                    "Estilo Profundidad (la ventana como una hoja que se inclina), rebote suave y un cristal más esmerilado y tranquilo.",
                    "Depth style (the window as a tilting sheet), a soft bounce and a calmer, more frosted glass.",
                    new String[]{"icehome_genie_style", "3", "icehome_anim_speed", "1.05", "icehome_anim_bounce", "0.1",
                            "icehome_wallpaper_zoom", "1.2", "icehome_menu_bounce", "0.6", "telon_strength", "0.6",
                            "kashiplus_cc_blur", "16", "kashiplus_cc_white", "0.08", "kashiplus_cc_chroma", "0.5",
                            "icehome_folder_blur", "12", "icehome_folder_chroma", "0.6"}, MODE_KEYS},
    };

    private static String[] join(String[]... parts) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String[] p : parts) java.util.Collections.addAll(out, p);
        return out.toArray(new String[0]);
    }

    /** Qué ajustes gestiona un perfil: su sexto elemento, o {@link #KEYS} en los de siempre. */
    static String[] keys(Object[] preset) {
        return preset.length > 5 && preset[5] instanceof String[] ? (String[]) preset[5] : KEYS;
    }

    static String name(Object[] preset) {
        return I18n.t((String) preset[0], (String) preset[1]);
    }

    static String about(Object[] preset) {
        return I18n.t((String) preset[2], (String) preset[3]);
    }

    /** Aplica un perfil: sus valores, y por defecto el resto de {@link #KEYS}. */
    static void apply(Context c, Object[] preset) {
        SharedPreferences.Editor e = c.getSharedPreferences(PrefsProvider.PREFS, 0).edit();
        for (String k : keys(preset)) e.remove(k);          // sin valor guardado = valor por defecto
        String[] kv = (String[]) preset[4];
        for (int i = 0; i + 1 < kv.length; i += 2) e.putString(kv[i], kv[i + 1]);
        e.commit();
    }

    /**
     * ¿Está puesto este perfil? Sí, si cada ajuste que nombra tiene su valor y los demás de
     * {@link #KEYS} siguen sin tocar (como los deja {@link #apply}). En cuanto mueves un
     * deslizador de esos, ya no cuenta como el perfil.
     */
    static boolean active(Context c, Object[] preset) {
        SharedPreferences p = c.getSharedPreferences(PrefsProvider.PREFS, 0);
        String[] kv = (String[]) preset[4];
        for (String k : keys(preset)) {
            String want = null;
            for (int i = 0; i + 1 < kv.length; i += 2) if (kv[i].equals(k)) want = kv[i + 1];
            String have;
            try {
                have = p.getString(k, null);
            } catch (ClassCastException e) {
                return false;
            }
            if (want == null) {
                if (have != null) return false;
            } else {
                try {
                    if (have == null || Math.abs(Float.parseFloat(have) - Float.parseFloat(want)) > 1e-4f) return false;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
        }
        return true;
    }

    private Presets() {
    }
}
