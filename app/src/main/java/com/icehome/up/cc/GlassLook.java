package com.icehome.up.cc;

import android.graphics.Bitmap;
import android.graphics.RuntimeShader;

/**
 * El aspecto del cristal líquido del centro de control, para usarlo fuera de él: la notificación
 * flotante y la vista previa de la página «Cristal líquido» de la app. Así todo el cristal se ve
 * igual y lo cambian los mismos ajustes (los kashiplus_cc_*).
 *
 * Son las mismas uniformes que pone CcGlass a una tarjeta (el cuerpo con refracción) y a su canto
 * de luz (buildLens). Si cambias una allí, cámbiala también aquí.
 */
public final class GlassLook {

    /** Los ajustes del cristal. Valores por defecto = los de cc/Tune. */
    public static final class Params {
        public float blur = 8f, sat = 1.3f, tone = 1.1f, white = 0.03f, gloss = 34f, magnify = 0.04f,
                refr = 20f, refrH = 14f, chroma = 1f, edge = 4f, edgeOver = 3.5f, edgeSat = 0.4f,
                rim = 0.69f, rimGain = 1f, bevel = 0f, fresnel = 0f, shade = 0f, depth = 0f;

        /** Pone un ajuste por su clave (kashiplus_cc_*). Devuelve false si no es del cristal. */
        public boolean set(String key, float v) {
            switch (key) {
                case "kashiplus_cc_blur": blur = v; return true;
                case "kashiplus_cc_sat": sat = v; return true;
                case "kashiplus_cc_tone": tone = v; return true;
                case "kashiplus_cc_white": white = v; return true;
                case "kashiplus_cc_gloss": gloss = v; return true;
                case "kashiplus_cc_magnify": magnify = v; return true;
                case "kashiplus_cc_refr": refr = v; return true;
                case "kashiplus_cc_refr_h": refrH = v; return true;
                case "kashiplus_cc_chroma": chroma = v; return true;
                case "kashiplus_cc_edge": edge = v; return true;
                case "kashiplus_cc_edge_over": edgeOver = v; return true;
                case "kashiplus_cc_edge_sat": edgeSat = v; return true;
                case "kashiplus_cc_rim": rim = v; return true;
                case "kashiplus_cc_rim_gain": rimGain = v; return true;
                case "kashiplus_cc_bevel": bevel = v; return true;
                case "kashiplus_cc_fresnel": fresnel = v; return true;
                case "kashiplus_cc_shade": shade = v; return true;
                case "kashiplus_cc_depth": depth = v; return true;
                default: return false;
            }
        }

        /** Huella de los ajustes: si cambia, hay que rehacer los shaders. */
        public int sig() {
            float[] all = {blur, sat, tone, white, gloss, magnify, refr, refrH, chroma, edge, edgeOver, edgeSat, rim, rimGain, bevel, fresnel, shade, depth};
            int h = 17;
            for (float f : all) h = h * 31 + Float.floatToIntBits(f);
            return h;
        }
    }

    /** Los ajustes tal como los tiene ahora el proceso (los carga cc/Tune). */
    public static Params current() {
        Params p = new Params();
        p.blur = Tune.ccBlur;
        p.sat = Tune.sat;
        p.tone = Tune.tone;
        p.white = Tune.white;
        p.gloss = Tune.gloss;
        p.magnify = Tune.magnify;
        p.refr = Tune.refr;
        p.refrH = Tune.refrH;
        p.chroma = Tune.chroma;
        p.edge = Tune.edge;
        p.edgeOver = Tune.edgeOver;
        p.edgeSat = Tune.edgeSat;
        p.rim = Tune.rim;
        p.rimGain = Tune.rimGain;
        p.bevel = Tune.bevel;
        p.fresnel = Tune.fresnel;
        p.shade = Tune.shade;
        p.depth = Tune.depth;
        return p;
    }

    /**
     * El cuerpo del cristal: lo de detrás procesado, con la refracción del borde. Falta ponerle el
     * fondo con {@link #place}. {@code w, h, r} en px; {@code dp} = densidad.
     */
    public static RuntimeShader body(Params p, float w, float h, float r, float dp) {
        RuntimeShader u = GlassShader.create();
        r = Math.min(r, Math.min(w, h) / 2f);
        float thick = Math.min(p.edge * dp, Math.min(w, h) * CcGlass.THICK_MAX_FRACTION);
        u.setFloatUniform("size", w, h);
        u.setFloatUniform("radius", r);
        u.setFloatUniform("band", thick);
        u.setFloatUniform("disp", CcGlass.THICK_DISP_DP * dp);
        u.setFloatUniform("dispersion", CcGlass.THICK_DISPERSION);
        u.setFloatUniform("tone", p.tone);
        u.setFloatUniform("sat", p.sat);
        u.setFloatUniform("darker", CcGlass.BIO_DARKER);
        u.setFloatUniform("whiteMix", p.white);
        u.setFloatUniform("edgeLift", CcGlass.BIO_EDGE_LIFT);
        u.setFloatUniform("stretch", CcGlass.EDGE_STRETCH);
        u.setFloatUniform("overshoot", p.edgeOver * dp);
        u.setFloatUniform("edgeSat", p.edgeSat);
        u.setFloatUniform("mag", p.magnify);
        // Hasta la mitad del lado corto (el centro): las piezas altas, como el volumen, doblan
        // mucho como en HyperLight. Las pequeñas ya las frena lensScale.
        float side = Math.min(w, h);
        float want = p.refrH * dp * lensScale(side, dp, false);
        float lensH = Math.min(want, side / 2f);
        u.setFloatUniform("refrH", lensH);
        u.setFloatUniform("refrAmt", p.refr * dp * lensScale(side, dp, true) * (lensH / Math.max(1f, want)));
        u.setFloatUniform("chroma", p.chroma);
        u.setFloatUniform("bevel", p.bevel);
        u.setFloatUniform("depth", p.depth);
        u.setFloatUniform("rimGain", 0f);                  // el canto de luz va encima (lens)
        u.setFloatUniform("light", 0f);
        u.setFloatUniform("fill", 1f);
        u.setFloatUniform("bandAlpha", 1f);
        u.setFloatUniform("active", 0f);
        return u;
    }

    /**
     * Lente según el tamaño de la pieza (lado corto en dp), como HyperLight: en las pequeñas
     * (≤ 28 dp) y medianas (≤ 40 dp) dobla mucho menos, si no se come el icono del centro.
     * amount = true para el desplazamiento; false para el ancho de la lente.
     */
    static float lensScale(float side, float dp, boolean amount) {
        float s = side / dp;
        if (s <= 28f) return amount ? 0.15f : 0.2f;
        if (s <= 40f) return amount ? 0.3f : 0.35f;
        return 1f;
    }

    /** Grosor de la línea blanca según el tamaño, como HyperLight: más fina en botones pequeños. */
    static float rimScale(float side, float dp) {
        float s = side / dp;
        return s <= 28f ? 0.4f : s <= 40f ? 0.6f : 1f;
    }

    /** El canto de luz que va encima del cuerpo (la línea brillante y la luz de la lente). */
    public static RuntimeShader lens(Params p, float w, float h, float r, float dp) {
        RuntimeShader u = GlassShader.create();
        r = Math.min(r, Math.min(w, h) / 2f);
        u.setFloatUniform("size", w, h);
        u.setFloatUniform("radius", r);
        u.setFloatUniform("band", Math.min(CcGlass.BAND_DP * dp, Math.min(w, h) * CcGlass.BAND_MAX_FRACTION));
        u.setFloatUniform("disp", 0f);
        u.setFloatUniform("stretch", 1f);
        u.setFloatUniform("overshoot", p.edgeOver * dp);
        u.setFloatUniform("dispersion", CcGlass.DISPERSION);
        u.setFloatUniform("fill", 0f);
        u.setFloatUniform("bandAlpha", 0f);                // sobre el cuerpo: solo la luz, sin otra franja
        u.setFloatUniform("tone", CcGlass.TONE);
        u.setFloatUniform("mag", 0f);
        u.setFloatUniform("rimw", p.rim * dp * rimScale(Math.min(w, h), dp));
        u.setFloatUniform("glow", CcGlass.GLOW_DP * dp);
        u.setFloatUniform("innerDark", p.edge > 0f ? CcGlass.INNER_DARK : 0f);   // limpio: sin línea oscura
        u.setFloatUniform("sat", CcGlass.SATURATION);
        u.setFloatUniform("rimGain", p.rimGain);
        u.setFloatUniform("light", CcGlass.LENS_LIGHT);
        u.setFloatUniform("fresnel", p.fresnel);
        u.setFloatUniform("shade", p.shade);
        u.setFloatUniform("active", 0f);
        return u;
    }

    /** Hasta dónde pinta algo {@link #lens} desde el borde (px): el anillo de GlassShader.drawRing. */
    public static float lensReach(Params p, float w, float h, float dp) {
        float band = Math.min(CcGlass.BAND_DP * dp, Math.min(w, h) * CcGlass.BAND_MAX_FRACTION);
        return Math.max(band, Math.max(p.rim * dp * 6.5f, CcGlass.GLOW_DP * dp * 6f));
    }

    /**
     * Dónde cae la pieza sobre el fondo: {@code wall} es lo de detrás ya desenfocado; el punto (0,0)
     * de la pieza está en {@code ox, oy} px del fondo, y {@code sx, sy} px del fondo por px de la pieza.
     */
    public static void place(RuntimeShader u, Bitmap wall, float ox, float oy, float sx, float sy) {
        u.setInputShader("wall", GlassShader.linear(wall));
        u.setFloatUniform("origin", ox, oy);
        u.setFloatUniform("scale", sx, sy);
        u.setFloatUniform("hasWall", 1f);
    }

    /** Solo cambia dónde cae la pieza (el fondo ya está puesto). Barato: para cada fotograma. */
    public static void move(RuntimeShader u, float ox, float oy, float sx, float sy) {
        u.setFloatUniform("origin", ox, oy);
        u.setFloatUniform("scale", sx, sy);
    }

    private GlassLook() {
    }
}
