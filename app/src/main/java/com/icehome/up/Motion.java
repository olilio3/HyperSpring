package com.icehome.up;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

/**
 * Piezas de movimiento que comparten la app y la presentación.
 * <p>
 * Todo pasa por animadores normales de Android, así que respeta la «escala de animación» de
 * opciones de desarrollador: con las animaciones quitadas, cada efecto salta a su estado final.
 */
final class Motion {

    /** Salida exponencial: arranca rápido y se posa muy suave. Es la curva típica de las webs modernas. */
    static final PathInterpolator EXPO_OUT = new PathInterpolator(0.16f, 1f, 0.3f, 1f);
    /** La curva estándar de la app. */
    static final PathInterpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);
    /**
     * Las curvas de GSAP con su nombre de allí, para que sea fácil copiar ideas de webs que usan
     * GSAP. «power4.out» es la de casi todas las webs virales: sale disparada y frena muy suave.
     */
    static final PathInterpolator POWER3_OUT = new PathInterpolator(0.215f, 0.61f, 0.355f, 1f);
    static final PathInterpolator POWER4_OUT = new PathInterpolator(0.165f, 0.84f, 0.44f, 1f);
    /** «back.out»: se pasa un poco y vuelve. Para cosas pequeñas que «aterrizan». */
    static final PathInterpolator BACK_OUT = new PathInterpolator(0.34f, 1.56f, 0.64f, 1f);

    /**
     * El color de acento: el coral del icono, uno solo y siempre liso (sin degradados). La app es
     * negra y este color solo aparece donde algo está encendido o es la acción principal: así
     * llama la atención justo donde tiene que llamarla.
     */
    static final int ACCENT = 0xFFFF5F45;

    /**
     * Antes eran tres luces del coral para hacer degradados. Ahora las tres son el mismo color: el
     * código que aún pinta «degradados» con esto (puntos, anillos, chispas) sale liso.
     */
    static final int[] BRAND = {ACCENT, ACCENT, ACCENT};

    /** Cápsula lisa del color de acento (fondo del botón principal). */
    static android.graphics.drawable.GradientDrawable brandPill(float radius) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(ACCENT);
        g.setCornerRadius(radius);
        return g;
    }

    /** Marca para {@link #cascade}: las filas de esta tarjeta entran una a una. */
    static final String CASCADE = "cascade";
    /** Marca de la barra de arriba de cada página, que va clavada: ni se inclina ni se desplaza al entrar. */
    static final String PINNED = "pinned";

    /**
     * Muelle normalizado: cuánto ha recorrido (de 0 a 1) a los {@code t} segundos.
     *
     * @param response periodo en segundos (menor = más rápido)
     * @param damping  1 = llega sin pasarse; menos de 1 = rebota
     */
    static float spring(float t, float response, float damping) {
        if (t <= 0f) return 0f;
        double z = Math.max(0.05, Math.min(0.999, damping));
        double w0 = 2 * Math.PI / Math.max(0.05, response);
        double wd = w0 * Math.sqrt(1 - z * z), zw = z * w0;
        return (float) (1 - Math.exp(-zw * t) * (Math.cos(wd * t) + zw / wd * Math.sin(wd * t)));
    }

    /** Cuánto se ha avanzado de {@code a} a {@code b} (0 antes, 1 después). */
    static float seg(float t, float a, float b) {
        return Math.max(0f, Math.min(1f, (t - a) / (b - a)));
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * Hace aparecer una vista subiendo un poco. Usa un ObjectAnimator propio (no {@code v.animate()})
     * para no dejarle un retraso pegado al animador de la vista, que luego retrasaría el hundimiento
     * al pulsar.
     */
    static void reveal(View v, long delay, float dy) {
        // Los títulos grandes entran letra a letra (como SplitText de GSAP), no en bloque.
        if (v instanceof SplitTitle) {
            ((SplitTitle) v).play(delay);
            return;
        }
        if (PINNED.equals(v.getTag())) dy = 0f;          // la barra clavada solo aparece
        v.setAlpha(0f);
        v.setTranslationY(dy);
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(v,
                PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, dy, 0f));
        a.setStartDelay(delay);
        a.setDuration(760);
        a.setInterpolator(POWER4_OUT);
        a.start();
    }

    /**
     * Efecto de scroll, como ScrollTrigger con «scrub» de GSAP: cada bloque de la página (título,
     * tarjeta, cabecera) va ligado a su posición en pantalla. Al asomar por abajo viene inclinado
     * hacia atrás y algo pequeño, y se endereza mientras sube; al irse por arriba se encoge un poco
     * y se apaga, como si se hundiera bajo la barra de estado. No es una animación con tiempo: lo
     * mueve el dedo, así que si paras de desplazar, se para.
     * <p>
     * Solo toca la rotación y la escala (cosas que la GPU hace sin volver a dibujar la vista), y
     * nunca la transparencia de lo que entra, que es de la entrada escalonada ({@link #cascade}).
     *
     * @param top alto de la zona de arriba tapada por la barra de estado
     */
    static void scrub(android.widget.ScrollView sv, ViewGroup list, float top, float dp) {
        int vh = sv.getHeight();
        if (vh <= 0) return;
        float sy = sv.getScrollY();
        float enter = vh * 0.24f, leave = 120f * dp;
        for (int i = 0; i < list.getChildCount(); i++) {
            View c = list.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE || c.getHeight() == 0 || c instanceof SplitTitle
                    || PINNED.equals(c.getTag())) continue;
            // Lo que no cabe en pantalla (el registro) no se inclina: se vería deformado.
            if (c.getHeight() > vh * 0.8f) continue;
            float y = list.getTop() + c.getTop() - sy;                  // borde de arriba en pantalla
            float bottom = y + c.getHeight();
            // Entrada: 0 con el borde de arriba tocando el de la pantalla, 1 cuando ya ha subido «enter».
            float in = clamp01((vh - y) / enter);
            in = 1f - (1f - in) * (1f - in) * (1f - in);                  // power3.out, pero con el dedo
            // Salida: empieza cuando su borde de abajo pasa por debajo de la barra de estado.
            float out = clamp01((top + leave - bottom) / leave);
            c.setCameraDistance(7000 * dp);
            c.setPivotX(c.getWidth() / 2f);
            c.setPivotY(in < 1f ? 0f : c.getHeight());
            c.setRotationX(18f * (1f - in));
            float s = (0.9f + 0.1f * in) * (1f - 0.06f * out);
            c.setScaleX(s);
            c.setScaleY(s);
            if (out > 0f || c.getTag(R_FADE) != null) {
                c.setTag(R_FADE, out > 0f ? Boolean.TRUE : null);
                c.setAlpha(1f - 0.85f * out);
            }
        }
    }

    /**
     * Cambia el texto «descifrándolo», como ScrambleText de GSAP: las letras nuevas se fijan de
     * izquierda a derecha y, mientras, las que faltan son símbolos al azar que cambian en cada
     * fotograma. Es barato (solo se reescribe un texto). Sin animaciones, pone el texto y ya.
     */
    static void scramble(final android.widget.TextView tv, final CharSequence target) {
        Object old = tv.getTag(R_SCRAMBLE);
        if (old instanceof android.animation.ValueAnimator) ((android.animation.ValueAnimator) old).cancel();
        if (!android.animation.ValueAnimator.areAnimatorsEnabled() || target.length() == 0) {
            tv.setText(target);
            return;
        }
        final String goal = target.toString();
        final String glyphs = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789#%&*+=<>";
        final java.util.Random rnd = new java.util.Random();
        final char[] buf = new char[goal.length()];
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(Math.min(1100, 300 + goal.length() * 28L));
        a.setInterpolator(POWER3_OUT);
        a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(android.animation.ValueAnimator va) {
                int n = Math.round((Float) va.getAnimatedValue() * goal.length());
                for (int i = 0; i < buf.length; i++) {
                    char c = goal.charAt(i);
                    buf[i] = i < n || c == ' ' ? c : glyphs.charAt(rnd.nextInt(glyphs.length()));
                }
                tv.setText(new String(buf));
            }
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator an) {
                tv.setText(target);
                tv.setTag(R_SCRAMBLE, null);
            }
        });
        tv.setTag(R_SCRAMBLE, a);
        a.start();
    }

    private static final int R_SCRAMBLE = 0x7f0f0002;

    /** Marca (con setTag de clave) de las vistas a las que {@link #scrub} ha bajado la transparencia. */
    private static final int R_FADE = 0x7f0f0001;

    static float clamp01(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }


    /**
     * Entrada escalonada de una columna: cada hijo aparece un poco después que el anterior. Las
     * tarjetas marcadas con {@link #CASCADE} entran y, justo detrás, sus filas una a una.
     *
     * @return el retraso al que ha llegado (para encadenar más cosas detrás)
     */
    static long cascade(ViewGroup col, long delay, float dy) {
        long d = delay;
        for (int i = 0; i < col.getChildCount(); i++) {
            View c = col.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE) continue;
            reveal(c, d, dy);
            if (CASCADE.equals(c.getTag()) && c instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) c;
                for (int j = 0; j < g.getChildCount(); j++) reveal(g.getChildAt(j), d + 70 + j * 38L, dy * 0.5f);
                d += Math.min(g.getChildCount(), 8) * 30L;
            }
            d += 55;
            if (d - delay > 700) d = delay + 700;          // en páginas largas, que no se haga eterno
        }
        return d;
    }

    private Motion() {
    }
}
