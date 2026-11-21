package com.icehome.up;

import android.animation.ValueAnimator;
import android.appwidget.AppWidgetHostView;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Empujar iconos: al abrir una app, los demás iconos de la página (y del dock) se apartan de ella,
 * a la par que crece la ventana; al cerrarla vuelven igual, al revés. Va con cualquier estilo de
 * genio, y también sin genio.
 * <p>
 * Cada fotograma de MIUI ({@link WindowAnim}) dice cuánto ha crecido la ventana (0 = en el icono,
 * 1 = pantalla completa) y dónde está el icono. En el hilo principal se mueve cada icono con su
 * translationX/Y: más cuanto más cerca del icono (cae exponencialmente con la distancia).
 */
final class IconPush {

    /** Cuánto se apartan, como fracción del ancho de la pantalla, con fuerza 1 y pegados al icono. */
    private static final float REACH = 0.6f;
    /** A qué distancia (fracción del ancho) el empuje cae a 1/e: los lejanos apenas se mueven. */
    private static final float FALLOFF = 0.63f;
    /** Sin fotogramas durante este tiempo con el launcher delante: la animación se cortó. */
    private static final long STALE_MS = 300;
    /** Lo que tardan en volver a su sitio si la animación se cortó. */
    private static final long RESET_MS = 320;

    static volatile float strength = 0.4f;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // Lo escribe el hilo de animación de MIUI (frame) y lo lee el principal (APPLY).
    private static volatile float wanted, centerX = -1f, centerY = -1f, screenW = 1080f;
    private static volatile long lastFrameMs;
    private static volatile boolean posted;
    private static volatile WeakReference<View> root = new WeakReference<>(null);

    // Solo en el hilo principal.
    private static final ArrayList<Moved> moved = new ArrayList<>();
    private static float applied;
    private static boolean gaveUp;
    private static ValueAnimator reset;
    private static WeakReference<ViewTreeObserver> watched = new WeakReference<>(null);

    /** Un icono movido y dónde estaba (su translation de antes y su centro en pantalla). */
    private static final class Moved {
        final WeakReference<View> view;
        final float tx, ty, cx, cy, l, t, r, b;

        Moved(View v, int[] loc) {
            view = new WeakReference<>(v);
            tx = v.getTranslationX();
            ty = v.getTranslationY();
            l = loc[0];
            t = loc[1];
            r = l + v.getWidth();
            b = t + v.getHeight();
            cx = (l + r) * 0.5f;
            cy = (t + b) * 0.5f;
        }

        /** El punto cae dentro (es el icono que se abre o cierra: MIUI da el centro de la imagen, no del icono con su nombre). */
        boolean contains(float x, float y) {
            return x >= l && x < r && y >= t && y < b;
        }
    }

    static boolean on() {
        return strength > 0f && !Exclude.paused;
    }

    /** Hace falta la vista raíz del launcher (no la hay o se quitó de la ventana). */
    static boolean needsRoot() {
        View r = root.get();
        return r == null || !r.isAttachedToWindow();
    }

    static void setRoot(View v) {
        if (v != null) root = new WeakReference<>(v);
    }

    /**
     * Un fotograma de la animación (hilo de animación de MIUI). {@code cur} = ventana ahora,
     * {@code icon} = el icono (null al cerrar arrastrando: se queda el último centro), {@code full} =
     * pantalla completa.
     */
    static void frame(RectF cur, RectF icon, RectF full) {
        boolean hasIcon = icon != null && !icon.isEmpty();
        float iw = hasIcon ? icon.width() : 0f, ih = hasIcon ? icon.height() : 0f;
        // Lo que ha crecido la ventana, media de ancho y alto, de 0 (icono) a 1 (pantalla).
        float p = ((cur.width() - iw) / Math.max(1f, full.width() - iw)
                + (cur.height() - ih) / Math.max(1f, full.height() - ih)) * 0.5f;
        wanted = p < 0f ? 0f : Math.min(p, 1f);
        screenW = Math.max(1f, full.width());
        if (hasIcon) {
            centerX = icon.centerX();
            centerY = icon.centerY();
        }
        lastFrameMs = SystemClock.uptimeMillis();
        if (posted) return;
        posted = true;
        MAIN.post(SCHEDULE);
    }

    // Se aplica en el siguiente fotograma del launcher (no en mitad de uno ya dibujado).
    private static final Choreographer.FrameCallback TICK = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long t) {
            apply();
        }
    };
    private static final Runnable SCHEDULE = new Runnable() {
        @Override
        public void run() {
            Choreographer.getInstance().postFrameCallback(TICK);
        }
    };

    private static void apply() {
        posted = false;
        try {
            if (reset != null) {
                reset.cancel();
                reset = null;
            }
            apply(on() ? wanted : 0f);
        } catch (Throwable e) {
            Log.e("empujar iconos", e);
            restore();
        }
    }

    private static void apply(float f) {
        if (f <= 0.002f) {                     // de vuelta en el icono: todo en su sitio
            restore();
            gaveUp = false;
            return;
        }
        if (moved.isEmpty()) {
            if (gaveUp) return;                // ya se buscó en esta animación y no había nada
            if (!collect()) {
                gaveUp = true;
                return;
            }
        }
        applied = f;
        float w = screenW, x = centerX, y = centerY;
        float push = f * f * (3f - 2f * f) * REACH * strength * w;   // smoothstep: arranca y llega suave
        float fall = FALLOFF * w;
        for (int i = moved.size() - 1; i >= 0; i--) {
            Moved m = moved.get(i);
            View v = m.view.get();
            if (v == null) {
                moved.remove(i);
                continue;
            }
            float dx = m.cx - x, dy = m.cy - y, d = (float) Math.hypot(dx, dy);
            if (x < 0f || d < 1f || m.contains(x, y)) continue;    // sin icono conocido, o es el propio icono
            float k = (float) Math.exp(-d / fall) * push;
            v.setTranslationX(m.tx + dx / d * k);
            v.setTranslationY(m.ty + dy / d * k);
        }
    }

    /** Busca los iconos y widgets de la página que se ve y del dock. */
    private static boolean collect() {
        View r = root.get();
        if (r == null || !r.isAttachedToWindow()) return false;
        watch(r);
        ArrayList<View> groups = new ArrayList<>();
        find(r, groups, 0);
        int[] loc = new int[2];
        Rect vis = new Rect();
        ArrayList<View> items = new ArrayList<>();
        for (View g : groups) {
            // Solo la página que se ve (las de los lados están fuera de la pantalla).
            if (!g.isShown() || g.getWidth() == 0 || !g.getGlobalVisibleRect(vis) || vis.width() < g.getWidth() / 2) continue;
            items.clear();
            items(g, items, 0);
            for (View v : items) {
                if (!v.isShown()) continue;
                v.getLocationOnScreen(loc);
                moved.add(new Moved(v, loc));
            }
        }
        if (!moved.isEmpty()) {
            // Para el registro: el icono de la app no se mueve (si ninguno lo contiene, se movería todo).
            String still = "ninguno";
            for (Moved m : moved) {
                View v = m.view.get();
                if (v != null && m.contains(centerX, centerY)) still = v.getClass().getSimpleName();
            }
            Log.i("empujar iconos: " + moved.size() + " iconos; se queda quieto: " + still);
            return true;
        }
        Log.i("empujar iconos: no se encontró la página ni el dock");
        return false;
    }

    /** Páginas (CellLayout) y dock (Hotseat; HotSeats en el launcher de Kashi). */
    private static void find(View v, ArrayList<View> out, int depth) {
        if (depth > 24) return;
        Class<?> c = v.getClass();
        if (named(c, ".CellLayout") || named(c, ".Hotseat") || named(c, ".HotSeats")) {
            out.add(v);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) find(g.getChildAt(i), out, depth + 1);
        }
    }

    /** Iconos, carpetas y widgets dentro de una página (todo lo de su ShortcutAndWidgetContainer). */
    private static void items(View v, ArrayList<View> out, int depth) {
        if (depth > 8 || !(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        boolean container = named(g.getClass(), "ShortcutAndWidgetContainer");
        for (int i = 0; i < g.getChildCount(); i++) {
            View child = g.getChildAt(i);
            if (container || isItem(child)) out.add(child);
            else items(child, out, depth + 1);
        }
    }

    private static boolean isItem(View v) {
        if (v instanceof AppWidgetHostView) return true;
        Class<?> c = v.getClass();
        return named(c, "ShortcutIcon") || named(c, "FolderIcon") || named(c, "ItemIcon")
                || named(c, "WidgetView") || named(c, "HostView");
    }

    /** La clase o alguna madre es del launcher y su nombre acaba así. */
    private static boolean named(Class<?> c, String end) {
        for (; c != null && c != View.class && c != ViewGroup.class; c = c.getSuperclass()) {
            String n = c.getName();
            if (n.startsWith("com.miui.home.") && n.endsWith(end)) return true;
        }
        return false;
    }

    /** Todos a su sitio, sin animar. */
    private static void restore() {
        applied = 0f;
        for (Moved m : moved) {
            View v = m.view.get();
            if (v == null) continue;
            v.setTranslationX(m.tx);
            v.setTranslationY(m.ty);
        }
        moved.clear();
    }

    /**
     * Si la animación se corta (volver al escritorio desde recientes, otra app que se pone
     * delante…) no llega el fotograma final y los iconos se quedarían apartados. Cuando el launcher
     * vuelve a tener el foco y ya no llegan fotogramas, vuelven solos a su sitio.
     */
    private static void watch(View r) {
        // El ViewTreeObserver cambia si la ventana se quita y se vuelve a poner (y se pierde el
        // listener): se compara con él, no con la vista.
        ViewTreeObserver o = r.getViewTreeObserver();
        if (watched.get() == o) return;
        watched = new WeakReference<>(o);
        o.addOnWindowFocusChangeListener(new ViewTreeObserver.OnWindowFocusChangeListener() {
            @Override
            public void onWindowFocusChanged(boolean focus) {
                if (focus) MAIN.postDelayed(CHECK, STALE_MS);
            }
        });
    }

    private static final Runnable CHECK = new Runnable() {
        @Override
        public void run() {
            if (moved.isEmpty() || reset != null) return;
            View r = root.get();
            if (r == null || !r.hasWindowFocus()) return;
            if (SystemClock.uptimeMillis() - lastFrameMs < STALE_MS) {      // sigue animando
                MAIN.postDelayed(this, STALE_MS);
                return;
            }
            Log.i("empujar iconos: la animación se cortó; los iconos vuelven a su sitio");
            ValueAnimator a = ValueAnimator.ofFloat(applied, 0f);
            a.setDuration(RESET_MS);
            a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator va) {
                    if (reset != va) return;
                    try {
                        apply((Float) va.getAnimatedValue());
                        if (va.getAnimatedFraction() >= 1f) reset = null;
                    } catch (Throwable e) {
                        Log.e("empujar iconos: vuelta", e);
                        reset = null;
                        va.cancel();
                        restore();
                    }
                }
            });
            reset = a;
            a.start();
        }
    };

    private IconPush() {
    }
}
