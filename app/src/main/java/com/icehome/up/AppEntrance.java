package com.icehome.up;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.ArrayList;

/**
 * Entrada «de goma» al abrir la app (solo al arrancar en frío, no al volver a ella):
 * <ol>
 * <li>El título grande cae desde arriba y, al chocar, se aplasta y rebota como si fuera de goma.</li>
 * <li>Del golpe sale una onda coral que enciende la rejilla de puntos del fondo.</li>
 * <li>Cada bloque de la página aparece subiendo justo cuando la onda llega a él; las baldosas de
 * funciones, además, hacen un pequeño rebote de goma al aterrizar.</li>
 * <li>La barra de abajo sube la última, con un muelle.</li>
 * </ol>
 * Todo lo mueve un solo ValueAnimator (el reloj): así va sincronizado aunque el sistema tenga la
 * escala de animaciones cambiada (con {@code postDelayed} se desfasaría). Un toque la termina al
 * momento. Si el sistema tiene las animaciones quitadas, no se hace.
 */
final class AppEntrance {

    /** Marca de las filas de baldosas: se anima cada baldosa por separado, con rebote. */
    static final String TILE_ROW = "tileRow";

    private static final float FALL_MS = 300f;      // lo que tarda el título en caer
    private static final float WAVE_MS = 1050f;     // lo que tarda la onda en cruzar la página
    private static final float BAR_AT = 950f;       // cuándo sube la barra
    private static final float BAR_MS = 1100f;

    /** Un bloque que entra: cuándo (ms) y si rebota (las baldosas). */
    private static final class Item {
        final View v;
        final float at;
        final boolean tile;

        Item(View v, float at, boolean tile) {
            this.v = v;
            this.at = at;
            this.tile = tile;
        }
    }

    private AppEntrance() {
    }

    /**
     * @param root  donde se pone la capa que recoge el toque para saltarla
     * @param page  la página de Inicio (ScrollView con la rejilla de puntos de fondo)
     * @param bar   la barra de pestañas
     */
    static void play(final FrameLayout root, final ViewGroup page, final View bar, final float dp) {
        if (!ValueAnimator.areAnimatorsEnabled() || page.getChildCount() == 0) return;
        final ViewGroup list = (ViewGroup) page.getChildAt(0);
        // Hasta el primer fotograma no se sabe dónde está cada cosa: se espera a que se coloque.
        page.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                page.getViewTreeObserver().removeOnPreDrawListener(this);
                start(root, page, list, bar, dp);
                return true;
            }
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private static void start(final FrameLayout root, final ViewGroup page, ViewGroup list, final View bar, final float dp) {
        // El título: el primer SplitTitle de la página. De su centro sale la onda.
        View title = null;
        for (int i = 0; i < list.getChildCount() && title == null; i++) {
            if (list.getChildAt(i) instanceof SplitTitle) title = list.getChildAt(i);
        }
        final float ox, oy;
        if (title != null) {
            ox = title.getLeft() + title.getWidth() * 0.3f;
            oy = title.getTop() + title.getHeight() * 0.8f;
        } else {
            ox = page.getWidth() / 2f;
            oy = 0f;
        }
        final float maxR = (float) Math.hypot(Math.max(ox, page.getWidth() - ox), page.getHeight() - oy) + 90f * dp;

        // Todo lo demás, en orden de página; las filas de baldosas se abren en baldosas sueltas.
        final ArrayList<Item> items = new ArrayList<>();
        collect(list, list, title, ox, oy, maxR, items);
        float last = 0f;
        for (Item it : items) last = Math.max(last, it.at);
        final float total = Math.max(BAR_AT + BAR_MS, last + 900f);

        final LiveBackground bg = page.getBackground() instanceof LiveBackground ? (LiveBackground) page.getBackground() : null;
        final View titleView = title;
        final float barDrop = 140f * dp, rise = 26f * dp;

        final ValueAnimator clock = ValueAnimator.ofFloat(0f, total);
        clock.setDuration((long) total);
        clock.setInterpolator(null);                        // lineal: cada cosa lleva su curva

        // Una capa invisible encima de todo: el primer toque termina la entrada (y no pulsa nada).
        final View skip = new View(root.getContext());
        skip.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) clock.end();
                return true;
            }
        });
        root.addView(skip, new FrameLayout.LayoutParams(-1, -1));

        clock.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                frame((Float) a.getAnimatedValue(), titleView, items, bg, bar, ox, oy, maxR, barDrop, rise);
            }
        });
        clock.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                // Todo en su sitio, pase lo que pase (también si se ha saltado con un toque).
                frame(1e6f, titleView, items, bg, bar, ox, oy, maxR, barDrop, rise);
                root.removeView(skip);
            }
        });
        frame(0f, titleView, items, bg, bar, ox, oy, maxR, barDrop, rise);   // el primer fotograma ya es el de salida
        clock.start();
    }

    /**
     * Recorre la página y apunta cuándo le llega la onda a cada bloque. La onda va deprisa al
     * principio y frena (radio = maxR·(1 − (1 − p)²)), así que se despeja p de la distancia.
     */
    private static void collect(ViewGroup g, ViewGroup list, View title, float ox, float oy, float maxR, ArrayList<Item> out) {
        boolean tiles = TILE_ROW.equals(g.getTag()) || hasRows(g);     // dentro de la rejilla de funciones
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c == title || c.getVisibility() != View.VISIBLE || Motion.PINNED.equals(c.getTag())) continue;
            // Se abren (se anima lo de dentro, no el bloque entero): la columna de contenido de la
            // página, la rejilla de funciones y sus filas.
            boolean column = g == list && c instanceof LinearLayout
                    && ((LinearLayout) c).getOrientation() == LinearLayout.VERTICAL && c.getTag() == null;
            if (c instanceof ViewGroup && (column || TILE_ROW.equals(c.getTag()) || hasRows((ViewGroup) c))) {
                collect((ViewGroup) c, list, title, ox, oy, maxR, out);
                continue;
            }
            float[] xy = posIn(c, list);
            float d = (float) Math.hypot(xy[0] + c.getWidth() / 2f - ox, xy[1] - oy);
            float p = 1f - (float) Math.sqrt(Math.max(0f, 1f - Math.min(1f, d / maxR)));
            out.add(new Item(c, FALL_MS + p * WAVE_MS - 80f, tiles));
        }
    }

    private static boolean hasRows(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) if (TILE_ROW.equals(g.getChildAt(i).getTag())) return true;
        return false;
    }

    /** Posición de {@code v} dentro de {@code list} (sumando las de sus padres). */
    private static float[] posIn(View v, ViewGroup list) {
        float x = 0f, y = 0f;
        View cur = v;
        while (cur != null && cur != list) {
            x += cur.getLeft();
            y += cur.getTop();
            cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
        }
        return new float[]{x, y};
    }

    private static void frame(float t, View title, ArrayList<Item> items, LiveBackground bg, View bar,
                              float ox, float oy, float maxR, float barDrop, float rise) {
        // 1. El título cae (acelerando, como con gravedad) y al chocar se aplasta y rebota.
        if (title != null) {
            float f = Motion.clamp01(t / FALL_MS);
            float h = title.getHeight();
            title.setAlpha(Math.min(1f, f * 3f));
            title.setTranslationY(-(h * 1.8f + 40f) * (1f - f * f));
            float r = IntroLogo.rubber(t - FALL_MS);
            title.setPivotX(title.getWidth() * 0.3f);
            title.setPivotY(h * 0.85f);
            title.setScaleY(1f - 0.30f * r);
            title.setScaleX(1f + 0.16f * r);
        }
        // 2. La onda de puntos coral, desde el golpe hasta pasar del borde.
        if (bg != null) {
            float p = Motion.clamp01((t - FALL_MS) / WAVE_MS);
            float radius = maxR * (1f - (1f - p) * (1f - p));
            float alpha = t < FALL_MS ? 0f : 0.9f * (1f - Motion.seg(p, 0.55f, 1f));
            bg.wave(ox, oy, radius, alpha);
        }
        // 3. Cada bloque sube cuando le llega la onda; las baldosas, con un rebote de goma.
        for (Item it : items) {
            float lt = t - it.at;
            View v = it.v;
            if (lt <= 0f) {
                v.setAlpha(0f);
                v.setTranslationY(rise);
                continue;
            }
            float e = Motion.POWER4_OUT.getInterpolation(Motion.clamp01(lt / 620f));
            v.setAlpha(Math.min(1f, lt / 240f));
            v.setTranslationY(rise * (1f - e));
            if (it.tile) {
                float r = IntroLogo.rubber(lt - 150f);
                float s = Motion.lerp(0.9f, 1f, e);
                v.setScaleX(s * (1f + 0.06f * r));
                v.setScaleY(s * (1f - 0.08f * r));
            }
        }
        // 4. La barra sube la última, con un muelle que se pasa un poco.
        if (bar != null) {
            float s = Motion.spring((t - BAR_AT) / 1000f, 0.6f, 0.7f);
            bar.setTranslationY(t >= 1e5f ? 0f : barDrop * (1f - s));
            bar.invalidate();                    // el cristal vuelve a mirar lo que tiene debajo al moverse
        }
    }
}
