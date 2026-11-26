package com.icehome.up;

import android.content.Context;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Textos de la app en español e inglés. Por defecto sigue el idioma del sistema (español para
 * es/ca/gl/eu, inglés para el resto); en «Acerca de» se puede fijar uno. Los ajustes (Tune.ITEMS)
 * tienen el texto en español en el propio código; aquí está su traducción por clave.
 */
final class I18n {

    static final String PREF = "lang";               // "auto", "es" o "en" (en las preferencias de la app)
    static boolean en;

    static void init(Context c) {
        String mode = c.getSharedPreferences("app", 0).getString(PREF, "auto");
        if ("es".equals(mode)) en = false;
        else if ("en".equals(mode)) en = true;
        else {
            String l = Locale.getDefault().getLanguage();
            en = !(l.equals("es") || l.equals("ca") || l.equals("gl") || l.equals("eu"));
        }
    }

    static String mode(Context c) {
        return c.getSharedPreferences("app", 0).getString(PREF, "auto");
    }

    static void setMode(Context c, String mode) {
        c.getSharedPreferences("app", 0).edit().putString(PREF, mode).commit();
    }

    static String t(String es, String english) {
        return en ? english : es;
    }

    /** Título de un ajuste. */
    static String title(Object[] it) {
        if (!en) return (String) it[1];
        String[] e = ITEMS.get((String) it[0]);
        return e != null ? e[0] : (String) it[1];
    }

    /** Explicación de un ajuste (o null). */
    static String desc(Object[] it) {
        String es = it.length > 6 && it[6] instanceof String ? (String) it[6] : null;
        if (!en || es == null) return es;
        String[] e = ITEMS.get((String) it[0]);
        return e != null && e.length > 1 ? e[1] : es;
    }

    /** Nombre de una sección (las secciones se identifican por su nombre en español). */
    static String section(String es) {
        if (!en) return es;
        String e = SECTIONS.get(es);
        return e != null ? e : es;
    }

    private static final Map<String, String> SECTIONS = new HashMap<>();
    private static final Map<String, String[]> ITEMS = new HashMap<>();

    private static void s(String es, String english) {
        SECTIONS.put(es, english);
    }

    private static void i(String key, String title, String desc) {
        ITEMS.put(key, new String[]{title, desc});
    }

    static {
        s("Funciones", "Features");
        s("Abrir y cerrar apps", "Opening and closing apps");
        s("Recientes que se doblan", "Bending recents");
        s("Pausa automática", "Automatic pause");
        s("Carpetas", "Folders");
        s("Menú de atajos", "Shortcut menu");
        s("Cajón de apps", "App drawer");
        s("Función", "Feature");
        s("Telón", "Curtain");
        s("Captura que encoge", "Squeezing screenshot");
        s("Gemini", "Gemini");
        s("Notificaciones", "Notifications");
        s("Reloj de bloqueo", "Lock screen clock");
        s("General", "General");
        s("Interior del cristal", "Inside the glass");
        s("Refracción del borde", "Edge refraction");
        s("Calidad y fluidez", "Quality and smoothness");
        s("Canto y línea de luz", "Edge and light rim");
        s("Fondo", "Background");
        s("Ajustes", "Settings");

        i("icehome_genie_on", "Genie effect when opening and closing apps",
                "The window comes out of its icon and goes back into it in a cup or genie shape. Applies after restarting the launcher.");
        i("icehome_stack_on", "Widget stack",
                "Stacked widgets shuffle with a gesture and can be dragged freely. Applies after restarting the launcher.");
        i("icehome_folder_glass_on", "Glass folders",
                "Open folders get a blurred glass background instead of a flat colour. Applies after restarting the launcher.");
        i("icehome_curtain_on", "Bending recents",
                "In recents, cards bend the other way depending on how hard you swipe them. Applies after restarting the launcher.");
        i("icehome_genie_style", "Style",
                "0 = classic genie with a tail. 1 = Samsung-like cup: the top rises first, then the rest follows. 3 = Depth: no genie; the window comes out of the icon as a tilted sheet (top away, narrower), straightens as it fills the screen and does the same in reverse when closing, with a motion blur trail. Set it with Top narrowing and Motion blur. 5 = Fan: the two icon corners on the side nearest the edge go straight to the screen corners, and the other two follow towards the other side. 6 = Scroll: the app comes out rolled up, goes to the nearest edge and unrolls to the other one, with 3D light and shadow. 7 = Card: the window spins out in 3D like a card, fast at first and slowing down as it arrives. 8 = HyperOS: the real system animation, drawn by HyperOS; only the speed and bounce set here change (no motion blur).");
        i("icehome_push", "Push icons",
                "When an app opens, the other icons move gently away from it, in step with the window; the farthest ones barely move. When it closes they come back the same way, in reverse. Works with any style. 0 turns it off.");
        i("icehome_push_wave", "Wave",
                "With Push icons: the icons don't all move at once but one after another, depending on how far they are from the app, like a wave, and settle with a small bounce. 0 moves them all at once, in step with the window.");
        i("icehome_genie_strength", "Strength",
                "How much the window bends. 0 is a plain rectangle; 1.8–2.2 looks like Samsung.");
        i("icehome_depth_taper", "Top narrowing",
                "Depth only: how much narrower the window looks at the top than at the bottom while it leaves the icon or goes back to it, in %. 0 keeps it straight.");
        i("icehome_depth_blur", "Motion blur",
                "All styles: the trail the window leaves as it moves, like a photo with the shutter open. Higher, more trail. 0 turns it off. Uses some GPU while the window moves fast.");
        i("icehome_genie_quality", "Resolution",
                "Sharpness of the animation. Higher looks sharper but uses more GPU; on modest phones, 50–60 %.");
        i("icehome_genie_fps", "Frames per second",
                "Frame cap for the genie and the curtain. Auto follows the screen up to 90 (at 120 Hz or more it stuttered on many phones). More frames look smoother but use more GPU; if it stutters, try 60.");
        i("icehome_genie_physics", "Drag physics",
                "When you close an app with your finger, the window swings like a hanging lamp. 0 keeps it rigid.");
        i("icehome_genie_live", "Live content",
                "While opening, the app keeps updating as it grows. Off saves GPU work and the image stays still until the end.");
        i("icehome_press_on", "Icons sink when pressed",
                "Pressing an icon shrinks it a little and it bounces back when released.");
        i("icehome_wallpaper_zoom", "Wallpaper zoom",
                "The wallpaper zooms in when an app opens and out when you go back. 0 keeps the HyperOS behaviour.");
        i("icehome_haptic", "Haptic on arrival",
                "A very soft vibration when the window lands in its icon. 0 turns it off. Follows the system vibration setting.");
        i("icehome_anim_speed", "Speed",
                "Speed of the open and close animations. Above 1 is faster.");
        i("icehome_anim_bounce", "Bounce",
                "How far the window overshoots before settling. 0 lands without bouncing.");
        i("icehome_curtain_strength", "Bend",
                "How much recents cards bend depending on your finger's speed.");
        i("icehome_curtain_style", "Curtain style",
                "On: the top stays fixed and the bottom bends, like a stage curtain. Off: the middle bends, like a drape.");
        i("icehome_drawer_on", "HyperOS 4 style drawer",
                "When the drawer opens, the home screen moves back and blurs little by little, and the categories become glass capsules. Applies after restarting the launcher.");
        i("icehome_drawer_blur", "Drawer blur",
                "How much the background blurs when the drawer opens; it grows with your finger. 0 uses Xiaomi's blur (if you turned it on in the drawer settings).");
        i("icehome_menu_on", "Springy shortcut menu",
                "When you long-press an icon, its menu pops out with a bounce and liquid glass. Applies after restarting the launcher.");
        i("icehome_menu_bounce", "Bounce",
                "How far the menu overshoots its size as it leaves the icon before settling. 0 keeps the HyperOS behaviour.");
        i("icehome_menu_glass", "Liquid glass",
                "Glass that shows the blurred wallpaper and bends it at the edge, with a highlight on top. Uses the folders' quality, thickness and refraction. Needs a static wallpaper.");
        i("icehome_folder_blur", "Blur",
                "How much the content behind an open folder is blurred, in dp.");
        i("icehome_folder_quality", "Glass quality",
                "How sharp the background seen through the glass is, in %. Higher looks finer but uses more memory; on modest phones, 50–67 %.");
        i("icehome_folder_lens", "Edge thickness",
                "Width of the liquid-glass lens along the edge, in dp. 0 removes it and leaves only the thin rim.");
        i("icehome_folder_refr", "Refraction",
                "How much the lens bends the background inwards, in dp. Higher looks like thicker glass.");
        i("icehome_folder_chroma", "Color dispersion",
                "Rainbow-like color split in the corners, like a prism. 0 removes it.");
        i("icehome_folder_light", "Edge shine",
                "Light the lens edge catches from the top left.");
        i("icehome_folder_glow", "Touch glow",
                "When you touch a folder, a soft light follows your finger and the rim lights up near it. 0 turns it off.");
        i("icehome_folder_haptic", "Haptic on resize",
                "A tick each time the folder jumps to another size while you stretch it. 0 turns it off. Follows the system haptics setting.");
        i("icehome_dock_pad", "Glass dock margin",
                "How far the glass dock bar reaches around the icons, in dp. Lower, a tighter bar. Applies after restarting the launcher.");

        i("telon_on", "Curtain when switching screens",
                "Inside an app, the window coming in or going out tilts with its speed and the one below blurs. Applies after restarting System UI.");
        i("telon_squeeze_on", "Screen that squeezes on screenshot",
                "When you take a screenshot, the screen sinks inwards and a little to the left, as if you pushed it, and springs back. Like calling Gemini on Pixel phones.");
        i("telon_squeeze_assist", "Squeeze when calling Gemini",
                "The screen squeezes when you open the assistant (Gemini), with the power button or the corner gesture, and a coloured light fills the frame. Like on Pixel phones.");
        i("telon_gemini_size", "Size",
                "Size of the screen, in %, at the deepest point. Pixel phones stay at about 97 %.");
        i("telon_gemini_speed", "Speed",
                "Lower is calmer. 1 = as fast as the screenshot.");
        i("telon_gemini_glow", "Light intensity",
                "Brightness of the light in the frame. 0 removes it and leaves only the frame.");
        i("telon_gemini_spin", "Colour spin",
                "Seconds for the colours to go once around the frame. Higher is slower. 0 = still.");
        i("telon_gemini_colors", "Colours",
                "0 = Gemini's: blue, violet and pink. 1 = Monet: the accents of your wallpaper. 2 = rainbow.");
        i("telon_notif_on", "iOS 27 style notifications",
                "Pop-up notifications are born small next to the clock, drop down growing, overshoot a little and spring back into place. When they leave they arc back to the clock.");
        i("telon_notif_bounce", "Bounce",
                "How far the notification overshoots its place as it drops. 0 lands without bouncing.");
        i("telon_notif_speed", "Speed", "1 = like iOS 27. Higher is faster.");
        i("telon_notif_blur", "Blur",
                "The notification starts blurred and sharpens as it grows. 0 turns it off (lighter).");
        i("telon_lockclock_on", "Glass clock",
                "Your lock screen clock (any style from the HyperOS editor) becomes liquid glass, like iOS 26 and HyperOS 4: the digits bend the wallpaper at the edge and shine on the rim. AOD keeps the usual one.");
        i("telon_lockclock_glass", "Rim light", "The thin light line on the edge (in the wallpaper's colour) and the faint shade on left edges. 0 removes it.");
        i("telon_lockclock_edge", "Refraction width", "How many dp from the edge inwards bend the wallpaper. At most half a stroke: the centre stays unbent.");
        i("telon_lockclock_refr", "Refraction", "How far the wallpaper is bent, in dp. More = surrounding colours bleed further into the digits.");
        i("telon_lockclock_opacity", "Colour opacity", "How much of the clock colour (the one you pick in the lock screen editor) shows over the glass. 0 = fully clear glass; 1 = solid clock.");
        i("telon_notif_glass", "Liquid glass",
                "The floating notification is glass, like iOS 27: you see what's behind it blurred, with a rim of light. Only the floating one; the shade stays as usual.");
        i("kashiplus_cc_quality", "Quality", "Resolution of the background seen through the glass: 0 low (faster), 1 medium, 2 high (sharper, uses more). Applies on the next capture.");
        i("kashiplus_cc_fps", "Glass FPS", "How many times per second the notification glass is redrawn while it moves. Lower uses less, but the glass lags slightly.");
        i("glass_easy_clear", "Transparency", "Higher: you see more of what is behind. Lower: milkier, more readable glass.");
        i("glass_easy_refr", "Refraction", "How much the background bends at the edge, with its rainbow. 0 leaves it flat.");
        i("glass_easy_rim", "Edge shine", "The line of light running along the glass edge.");
        i("icehome_light_parallel", "Light parallel animation", "If you open another app while one is closing, the closing one flies to its icon as a rectangle of its colour instead of with the genie (two genies at once stutter on modest phones). 0 off, 1 auto (only if the phone stutters), 2 always.");
        i("telon_notif_tint", "Glass veil",
                "A light (or dark, in dark mode) layer over the glass so the text is readable. 0 leaves the glass clearer.");
        i("telon_squeeze_size", "Size",
                "Size of the screen, in %, at the deepest point. Pixel phones stay at about 97 %, a soft push.");
        i("telon_squeeze_left", "To the left",
                "How far the screen moves to the left while it shrinks. 0 = it shrinks straight to the centre, like on Pixel phones.");
        i("telon_squeeze_speed", "Speed",
                "1 = like on Pixel phones. Higher makes the push faster.");
        i("telon_squeeze_frame", "Frame",
                "A frame with rounded corners surrounds the screen while it shrinks. Without it you see whatever is behind, like the wallpaper.");
        i("telon_squeeze_color", "Frame colour",
                "0 = black, like on Pixel phones. 1 = Monet: the dark tone taken from your wallpaper. 2 = Monet-style brown.");
        i("telon_strength", "Tilt",
                "How much the window tilts depending on how fast it moves. 0 keeps it straight.");
        i("telon_curve", "Shape",
                "0 = straight tilt, like a parallelogram. 1 = curved like a curtain, with the bottom bending more.");
        i("telon_blur", "Motion trail",
                "Blur along the direction of movement when the window moves fast. 0 turns it off.");
        i("telon_bg_blur", "Blur the screen below",
                "The screen behind blurs while the new one comes in and sharpens again when you go back. 0 turns it off.");
        i("telon_quality", "Resolution",
                "Sharpness of the window during the curtain. Higher looks sharper but costs more.");

        i("icehome_dock_glass_on", "Glass dock",
                "The row of icons at the bottom of the home screen becomes a floating liquid-glass bar. Uses the folders' glass and needs a static wallpaper. Beta: applies after restarting the launcher.");
        i("icehome_genie_auto", "Automatic resolution",
                "If several animations in a row stutter, the resolution drops by 10 % on its own (down to 50 %). It goes back to yours when you touch any setting or restart the launcher.");
        i("icehome_auto_heat", "When the phone is hot",
                "When the system reports that the phone is very hot, HyperSpring pauses itself (genie, curtain and recents) and comes back once it cools down. Like the pause tile, but automatic.");
        i("icehome_auto_game", "In games",
                "While Game Turbo is boosting a game, HyperSpring pauses itself, to leave all the power to the game.");
        i("icehome_auto_saver", "With battery saver",
                "With battery saver on, HyperSpring pauses itself. Off by default, in case you keep battery saver on all the time.");
        i("kashiplus_cc_on", "Glass control center",
                "Control center tiles and buttons become liquid glass. Tested with the ModsCenter iOS control center and the stock HyperOS one.");
        i("kashiplus_cc_blur", "Blur",
                "How much what you see through the tiles is blurred, in dp.");
        i("kashiplus_cc_sat", "Tile saturation",
                "Colour intensity seen through the large tiles. 1 = unchanged.");
        i("kashiplus_cc_sat_button", "Button saturation",
                "The same for the small round buttons.");
        i("kashiplus_cc_tone", "Light",
                "Glass brightness. Above 1 lightens it; below 1 darkens it.");
        i("kashiplus_cc_white", "White veil",
                "A very subtle white layer on the glass so text reads better over dark backgrounds.");
        i("kashiplus_cc_gloss", "Gloss",
                "A soft reflection at the top of each tile.");
        i("kashiplus_cc_magnify", "Magnifier",
                "Magnifies what you see through the centre of the tile, like a thick lens.");
        i("kashiplus_cc_refr", "Refraction",
                "How much the background bends at the edge of each tile, in dp. This is the AndroidLiquidGlass lens.");
        i("kashiplus_cc_refr_h", "Refraction width",
                "How far the refraction reaches inwards from the edge, in dp. Small buttons limit it automatically.");
        i("kashiplus_cc_chroma", "Colour dispersion",
                "Rainbow-like colour fringing at the corners, like a prism. 0 turns it off.");
        i("kashiplus_cc_bevel", "Flat bevel",
                "Shape of the edge lens. 0 = the usual round curve; 1 = flat centre with all the bending right at the edge, like iOS 26. With 1, raise the refraction.");
        i("kashiplus_cc_fresnel", "White edge line",
                "A thin white line all around the edge: brightest on the side facing the light and a little softer on the opposite one. 0 turns it off.");
        i("kashiplus_cc_shade", "Inner shadow",
                "A soft shadow inside the bottom edge, so the glass looks thick. 0 turns it off.");
        i("kashiplus_cc_depth", "Depth",
                "The edge lens curves like a dome: what is behind opens outwards from the centre. 0 turns it off.");
        i("kashiplus_cc_edge", "Lens edge",
                "Thickness of each tile's lit edge, in dp.");
        i("kashiplus_cc_edge_over", "Edge colour",
                "The edge takes the colour of what is just outside the tile, up to this distance in dp.");
        i("kashiplus_cc_edge_sat", "Edge saturation",
                "Intensity of the edge colour.");
        i("kashiplus_cc_rim", "Light rim width",
                "The bright line running along the edge, in dp.");
        i("kashiplus_cc_rim_gain", "Light rim strength",
                "Brightness of that line. 0 turns it off.");
        i("kashiplus_cc_dim", "Dim the background",
                "How much the screen behind the control center is darkened.");
        i("kashiplus_cc_bg_blur", "Background blur",
                "How much the screen behind the control center is blurred. 1 = like HyperOS, 0 = no blur.");
    }

    private I18n() {
    }
}
