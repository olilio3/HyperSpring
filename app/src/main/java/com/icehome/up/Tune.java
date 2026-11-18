package com.icehome.up;

import android.content.Context;
import android.provider.Settings;

import java.util.HashMap;
import java.util.Map;

/**
 * Ajustes del launcher: desde la app (PrefsProvider) o con "settings put global icehome_… valor".
 * En el launcher se leen una vez al arrancar y cada vez que la app avisa (difusión TUNE), y se
 * guardan en memoria: las animaciones nunca consultan el proveedor en mitad de un fotograma.
 * Los interruptores de función se aplican al reiniciar el launcher; los valores, al momento.
 */
public final class Tune {

    /** Clave, nombre, mínimo, máximo, por defecto, decimales (-1 = interruptor), explicación. "#" = sección. */
    public static final Object[][] ITEMS = {
            {"#", "Funciones"},
            {"icehome_genie_on", "Efecto genio al abrir y cerrar apps", 0f, 1f, 1f, -1, "La ventana sale del icono y vuelve a él con forma de copa o de genio. Se aplica al reiniciar el launcher."},
            {"icehome_stack_on", "Pila de widgets", 0f, 1f, 1f, -1, "Los widgets apilados se barajan con un gesto y se pueden arrastrar libremente. Se aplica al reiniciar el launcher."},
            {"icehome_folder_morph_on", "Carpetas con muelle", 0f, 1f, 1f, -1, "Al abrir o cerrar una carpeta, cambia de tamaño con un rebote suave. Se aplica al reiniciar el launcher."},
            {"icehome_folder_glass_on", "Cristal en las carpetas", 0f, 1f, 1f, -1, "El fondo de las carpetas abiertas es cristal desenfocado en vez de un color plano. Se aplica al reiniciar el launcher."},
            {"icehome_curtain_on", "Recientes que se doblan", 0f, 1f, 1f, -1, "En la vista de recientes, las tarjetas se doblan hacia el lado contrario según la fuerza con la que las deslizas. Se aplica al reiniciar el launcher."},
            {"icehome_dock_glass_on", "Dock de cristal", 0f, 1f, 0f, -1, "La fila de iconos de abajo del escritorio pasa a ser una barra flotante de cristal líquido. Usa el cristal de las carpetas y necesita un fondo de pantalla estático. En pruebas: se aplica al reiniciar el launcher."},
            {"icehome_menu_on", "Menú de atajos con muelle", 0f, 1f, 1f, -1, "Al mantener pulsado un icono, su menú sale con un rebote y un borde de cristal líquido. Se aplica al reiniciar el launcher."},
            {"#", "Abrir y cerrar apps"},
            {"icehome_genie_style", "Estilo", 0f, 3f, 0f, 0, "0 = genio clásico con cola. 1 = copa como Samsung: arriba sube primero y luego baja el resto. 3 = Profundidad: sin genio; la ventana sale del icono como una hoja inclinada (arriba al fondo, más estrecha), se endereza al llenar la pantalla y al cerrar hace lo mismo al revés, con estela de movimiento. Se regula con Estrechamiento de arriba y Desenfoque de movimiento."},
            {"icehome_genie_strength", "Intensidad", 0f, 4f, 2.2f, 1, "Cuánto se deforma la ventana. 0 es un rectángulo normal; 1,8–2,2 se parece a Samsung."},
            {"icehome_depth_taper", "Estrechamiento de arriba", 0f, 60f, 30f, 0, "Solo en Profundidad: cuánto más estrecha se ve la ventana arriba que abajo mientras sale del icono o vuelve a él, en %. 0 la deja recta."},
            {"icehome_depth_blur", "Desenfoque de movimiento", 0f, 2f, 1f, 1, "Solo en Profundidad: la estela que deja la ventana al moverse. 0 la quita."},
            {"icehome_genie_quality", "Resolución", 50f, 100f, 75f, 0, "Nitidez de la animación. Más alta se ve más nítida pero gasta más GPU; en móviles modestos, 50–60 %."},
            {"icehome_genie_auto", "Resolución automática", 0f, 1f, 1f, -1, "Si varias animaciones seguidas van a tirones, baja sola la resolución un 10 % (hasta el 50 %). Vuelve a la tuya al tocar cualquier ajuste o reiniciar el launcher."},
            {"icehome_genie_fps", "Fotogramas por segundo", 0f, 6f, 0f, 0, "Tope de fotogramas del genio y del telón. Auto sigue a la pantalla hasta 90 (a 120 Hz o más daba tirones en muchos móviles). Más fotogramas se ve más fluido pero gasta más GPU; si va a tirones, prueba 60."},
            {"icehome_genie_physics", "Físicas al arrastrar", 0f, 2f, 1f, 1, "Al cerrar una app con el dedo, la ventana se balancea como una lámpara colgada. 0 la deja rígida."},
            {"icehome_genie_live", "Contenido en vivo", 0f, 1f, 1f, -1, "Durante la apertura, la app se actualiza mientras crece. Desactivado ahorra trabajo a la GPU y la imagen se queda quieta hasta el final."},
            {"icehome_press_on", "Iconos que se hunden", 0f, 1f, 1f, -1, "Al pulsar un icono se hace un poco más pequeño y vuelve con un rebote al soltarlo."},
            {"icehome_wallpaper_zoom", "Zoom del fondo", 0f, 1.5f, 1f, 2, "El fondo de pantalla se acerca al abrir una app y se aleja al volver. 0 deja el comportamiento de HyperOS."},
            {"icehome_unlock_zoom", "Zoom al desbloquear", 0f, 1.5f, 0f, 2, "Al desbloquear, el escritorio entra un poco más grande y se asienta a su tamaño, todo de una pieza. 0 lo quita."},
            {"icehome_haptic", "Vibración al llegar", 0f, 1f, 0.3f, 2, "Un toque de vibración muy suave cuando la ventana entra en su icono. 0 la quita. Respeta el ajuste de vibración del sistema."},
            {"icehome_anim_speed", "Velocidad", 0.5f, 2f, 1f, 2, "Velocidad de las animaciones de abrir y cerrar. Más de 1 es más rápida."},
            {"icehome_anim_bounce", "Rebote", 0f, 0.4f, 0f, 2, "Cuánto se pasa la ventana de su sitio al llegar antes de asentarse. 0 llega sin rebote."},
            {"#", "Pausa automática"},
            {"icehome_auto_heat", "Con el móvil caliente", 0f, 1f, 1f, -1, "Cuando el sistema avisa de que el móvil está muy caliente, HyperSpring se pausa solo (genio, telón y recientes) y vuelve al enfriarse. Como el tile de pausa, pero automático."},
            {"icehome_auto_game", "En juegos", 0f, 1f, 1f, -1, "Mientras Game Turbo acelera un juego, HyperSpring se pausa solo, para dejarle toda la potencia al juego."},
            {"icehome_auto_saver", "Con ahorro de batería", 0f, 1f, 0f, -1, "Con el ahorro de batería encendido, HyperSpring se pausa solo. Apagado de serie, por si llevas el ahorro siempre puesto."},
            {"#", "Recientes que se doblan"},
            {"icehome_curtain_strength", "Doblez", 0f, 3f, 1f, 1, "Cuánto se doblan las tarjetas de recientes según la velocidad del dedo."},
            {"icehome_curtain_style", "Estilo telón", 0f, 1f, 0f, -1, "Activado: la parte de arriba queda fija y se dobla la de abajo, como un telón. Desactivado: se dobla el centro, como una cortina."},
            {"#", "Menú de atajos"},
            {"icehome_menu_bounce", "Rebote", 0f, 1f, 0.5f, 2, "Cuánto se pasa el menú de su tamaño al salir del icono antes de asentarse. 0 lo deja como en HyperOS."},
            {"icehome_menu_glass", "Cristal líquido", 0f, 1f, 1f, -1, "Borde de cristal que dobla el fondo de pantalla, con brillo arriba. Usa la calidad, el grosor y la refracción de las carpetas. Necesita un fondo de pantalla estático."},
            {"#", "Carpetas"},
            {"icehome_folder_blur", "Desenfoque", 0f, 30f, 6f, 0, "Cuánto se desenfoca lo que hay detrás de una carpeta abierta, en dp."},
            {"icehome_folder_quality", "Calidad del cristal", 25f, 100f, 67f, 0, "Nitidez del fondo que ve el cristal, en %. Más alta se ve más fina pero usa más memoria; en móviles modestos, 50–67 %."},
            {"icehome_folder_lens", "Grosor del borde", 0f, 40f, 22f, 0, "Ancho de la lente de cristal líquido junto al borde, en dp. 0 la quita y deja solo el canto fino."},
            {"icehome_folder_refr", "Refracción", 0f, 60f, 30f, 0, "Cuánto dobla la lente el fondo hacia dentro, en dp. Más alto parece un cristal más grueso."},
            {"icehome_folder_chroma", "Dispersión de color", 0f, 3f, 1.2f, 1, "Separación de colores tipo arcoíris en las esquinas, como en un prisma. 0 la quita."},
            {"icehome_folder_light", "Brillo del borde", 0f, 0.3f, 0.1f, 2, "Luz que recoge el borde de la lente desde arriba a la izquierda."},
            {"icehome_folder_glow", "Brillo al tocar", 0f, 1.5f, 1f, 2, "Al tocar una carpeta, una luz suave sigue al dedo y el canto se enciende cerca de él. 0 lo quita."},
    };

    private static volatile Map<String, String> cache;

    /** Relee todo y lo aplica a las animaciones (en el launcher). */
    static void reload(Context c) {
        cache = read(c);
        Genie.strength = f(c, "icehome_genie_strength");
        int style = Math.round(f(c, "icehome_genie_style"));
        // El 2 era la copa «Ice» (quitada en la 2.13.0): quien la tuviera pasa a la Copa.
        Genie.style = style == 2 ? Genie.STYLE_SAMSUNG : style;
        Genie.depthTaper = f(c, "icehome_depth_taper") / 100f;
        Genie.depthBlur = f(c, "icehome_depth_blur");
        Genie.live = f(c, "icehome_genie_live") >= 0.5f;
        Genie.physics = f(c, "icehome_genie_physics");
        Genie.maxFps = fpsCap(c, cache);
        Haptics.strength = f(c, "icehome_haptic");
        IconPress.enabled = f(c, "icehome_press_on") >= 0.5f;
        WallpaperZoom.strength = f(c, "icehome_wallpaper_zoom");
        RecentsCurtain.strength = f(c, "icehome_curtain_strength");
        RecentsCurtain.mode = f(c, "icehome_curtain_style") >= 0.5f ? 1f : 0f;
        GlRenderer.wantedScale = f(c, "icehome_genie_quality") / 100f;
        Genie.autoQuality = f(c, "icehome_genie_auto") >= 0.5f;
        UnlockZoom.strength = f(c, "icehome_unlock_zoom");
        Springs.speed = f(c, "icehome_anim_speed");
        Springs.bounce = f(c, "icehome_anim_bounce");
        GlassFolder.quality = f(c, "icehome_folder_quality");
        GlassFolder.liquidH = f(c, "icehome_folder_lens");
        GlassFolder.liquidAmt = f(c, "icehome_folder_refr");
        GlassFolder.liquidChroma = f(c, "icehome_folder_chroma");
        GlassFolder.liquidLight = f(c, "icehome_folder_light");
        GlassFolder.touchGlow = f(c, "icehome_folder_glow");
        Map<String, String> m = cache;
        Exclude.genie = Exclude.parse(m == null ? null : m.get(Exclude.KEY));
        // Pausa (tile de ajustes rápidos): sin genio y sin recientes que se doblan, al momento.
        // La pausa del tile, o la automática (calor, juegos, ahorro de batería; ver AutoPause).
        AutoPause.configure(m);
        boolean paused = paused(m) || AutoPause.active();
        Exclude.paused = paused;
        if (paused) RecentsCurtain.strength = 0f;
        ShortcutMenuFx.bounce = f(c, "icehome_menu_bounce");
        ShortcutMenuFx.glass = f(c, "icehome_menu_glass") >= 0.5f;
    }

    /**
     * El refresco más alto que admite la pantalla. Antes era un ajuste («Fotogramas por segundo»);
     * el genio dibuja al ritmo de los refrescos y esto solo es el techo, así que pasarse no gasta más.
     */
    public static float maxRefresh(Context c) {
        float max = 60f;
        try {
            android.view.Display d = c.getSystemService(android.hardware.display.DisplayManager.class)
                    .getDisplay(android.view.Display.DEFAULT_DISPLAY);
            for (android.view.Display.Mode m : d.getSupportedModes()) max = Math.max(max, m.getRefreshRate());
        } catch (Throwable ignored) {
            // sin pantalla (no debería pasar en el launcher): se queda en 60
        }
        return max;
    }

    /** Opciones de «Fotogramas por segundo»; el ajuste guarda la posición (0 = Auto). */
    public static final int[] FPS_CHOICES = {0, 30, 60, 90, 120, 144, 185};
    /** En Auto, tope de 90: a 120 Hz el genio dibujaba el doble y daba tirones en pantallas 1.5K (Reddit, Redmi Note 13 Pro). */
    static final float FPS_AUTO_MAX = 90f;

    /** Tope de fotogramas del genio y del telón (lo usan los dos procesos, cada uno con sus ajustes leídos). */
    public static float fpsCap(Context c, Map<String, String> saved) {
        int i = 0;
        try {
            i = Math.round(Float.parseFloat(saved.get("icehome_genie_fps")));
        } catch (Throwable ignored) {
            // sin guardar o mal escrito: Auto
        }
        float screen = maxRefresh(c);
        if (i <= 0 || i >= FPS_CHOICES.length) return Math.min(FPS_AUTO_MAX, screen);
        return Math.min(FPS_CHOICES[i], screen);   // pedir más de lo que da la pantalla no sirve de nada
    }

    /** ¿Está puesta la pausa? Es texto ("1") fuera de ITEMS, como la lista de apps sin genio. */
    static boolean paused(Map<String, String> m) {
        return m != null && "1".equals(m.get(com.icehome.up.telon.Tune.PAUSE_KEY));
    }

    /** Valor de un ajuste (de la memoria si ya se leyó; si no, del proveedor). */
    static float get(Context c, String key) {
        Map<String, String> m = cache;
        return value(c, key, m != null ? m : read(c));
    }

    /** En la app de ajustes: siempre del proveedor, sin memoria. */
    static float fresh(Context c, String key) {
        return value(c, key, read(c));
    }

    /**
     * Interruptor de función al cargar el launcher, antes de que exista su Application: se usa el
     * contexto del sistema. Si no se puede leer, vale lo de por defecto.
     */
    static boolean on(String key) {
        Context c = null;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) c = (Context) app;
            if (c == null) {
                Object thread = at.getMethod("currentActivityThread").invoke(null);
                if (thread != null) c = (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
            }
        } catch (Throwable ignored) {
        }
        if (c == null) return (Float) item(key)[4] >= 0.5f;
        if (cache == null) cache = read(c);
        return get(c, key) >= 0.5f;
    }

    /** Interruptor leído con el contexto propio del proceso enganchado (el que el proveedor acepta). */
    static boolean on(Context c, String key) {
        Map<String, String> m = attachRead;
        if (m == null) cache = attachRead = m = read(c);       // una sola consulta al arrancar
        return value(c, key, m) >= 0.5f;
    }

    private static Map<String, String> attachRead;

    private static float f(Context c, String key) {
        return value(c, key, cache);
    }

    private static Map<String, String> read(Context c) {
        Map<String, String> m = new HashMap<>();
        try (android.database.Cursor cur = c.getContentResolver().query(PrefsProvider.URI, null, null, null, null)) {
            while (cur != null && cur.moveToNext()) m.put(cur.getString(0), cur.getString(1));
        } catch (Throwable ignored) {
        }
        return m;
    }

    private static Object[] item(String key) {
        for (Object[] it : ITEMS) if (it[0].equals(key)) return it;
        throw new IllegalArgumentException(key);
    }

    private static float value(Context c, String key, Map<String, String> saved) {
        Object[] it = item(key);
        float def = (Float) it[4];
        try {
            String v = saved == null ? null : saved.get(key);
            if (v == null && c != null) v = Settings.Global.getString(c.getContentResolver(), key);
            if (v != null) return Math.max((Float) it[2], Math.min((Float) it[3], Float.parseFloat(v.trim())));
        } catch (Throwable ignored) {
        }
        return def;
    }

    private Tune() {
    }
}
