package com.icehome.up;

import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Iconos que se apartan (3.1.0, sacado de vídeos de ColorOS): al abrir una app, el escritorio se
 * acerca a la cámara desde el icono que tocaste, así que los demás iconos crecen, se van hacia
 * los bordes (los de fuera se salen de la pantalla) y se desenfocan, como si la app los empujara
 * al salir. Al cerrarla vuelven a su sitio, al mismo ritmo que la ventana.
 *
 * <p><b>Cómo:</b> no se mueve cada icono, sino sus contenedores: la página visible del escritorio
 * (CellLayout) y el dock (Hotseat). Se escalan con el centro en el icono tocado, que es lo mismo
 * que acercar la cámara a ese punto. Son dos o tres vistas, y escalar una vista solo cambia su
 * matriz en el RenderThread: casi gratis. Lo único que gasta GPU es el desenfoque
 * ({@link RenderEffect}, Android 12+), y por eso tiene su propio ajuste.
 *
 * <p><b>El ritmo</b> lo da la animación de la ventana de MIUI: {@link WindowAnim} llama a
 * {@link #frame} en cada fotograma (en el hilo de animación de MIUI) con lo abierta que está la
 * ventana. Así sigue también al dedo al cerrar con el gesto. Las vistas solo se pueden tocar en el
 * hilo principal: se guarda el último valor y se aplica allí en el siguiente fotograma.
 *
 * <p><b>Red de seguridad:</b> si una animación se corta sin llegar a cerrar (otra que MIUI no
 * avisa, un cuelgue de la app…), al volver el foco al launcher y no llegar más fotogramas, los
 * iconos vuelven solos a su sitio. Nunca se quedan descolocados.
 */
final class IconPush {

    /** Ajuste «Empujar iconos»: 0 = apagado, 1 = lo de serie (se acercan un {@link #ZOOM}). */
    static volatile float strength = 1f;
    /** Ajuste «Desenfoque de iconos»: 0 = sin desenfoque, 1 = {@link #BLUR_DP}. */
    static volatile float blur = 1f;

    /** Cuánto se acerca el escritorio a fuerza 1, con la app abierta del todo (+35 %). */
    private static final float ZOOM = 0.35f;
    /** Desenfoque a fuerza 1 con la app abierta, en dp. */
    private static final float BLUR_DP = 12f;
    /** Cuánto se apagan los iconos con la app abierta (dejan protagonismo a la ventana). */
    private static final float DIM = 0.25f;
    /** Red de seguridad: sin fotogramas durante esto y con el launcher delante, se vuelve. */
    private static final long STALE_MS = 300;
    private static final long RESET_MS = 320;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // ---- Entre hilos: lo escribe el hilo de animación de MIUI, lo lee el principal.
    private static volatile float wanted;
    private static volatile float centerX = -1f, centerY = -1f;
    private static volatile long lastFrameMs;
    private static volatile boolean posted;
    /** Raíz de la ventana del launcher (la del último icono tocado). */
    private static volatile WeakReference<View> root = new WeakReference<>(null);

    // ---- Solo hilo principal.
    /** Contenedores movidos y su estado original, para dejarlos exactamente igual. */
    private static final ArrayList<Moved> moved = new ArrayList<>();
    private static float applied;
    private static ValueAnimator reset;
    /** Raíz que ya tiene el aviso de foco puesto. */
    private static WeakReference<View> watched = new WeakReference<>(null);

    private static final class Moved {
        final WeakReference<View> view;
        final float scaleX, scaleY, alpha, pivotX, pivotY;
        final boolean pivotSet;
        /** Esquina de la vista en pantalla, antes de moverla. */
        final int left, top;
        float lastBlur = -1f;

        Moved(View v, int[] loc) {
            view = new WeakReference<>(v);
            scaleX = v.getScaleX();
            scaleY = v.getScaleY();
            alpha = v.getAlpha();
            pivotX = v.getPivotX();
            pivotY = v.getPivotY();
            pivotSet = v.isPivotSet();
            left = loc[0];
            top = loc[1];
        }
    }

    private IconPush() {
    }

    static boolean on() {
        return strength > 0f && !Exclude.paused;
    }

    /** Hilo principal: se ha tocado un icono del escritorio (de aquí sale la raíz de la ventana). */
    static void touched(View icon) {
        View r = icon.getRootView();
        if (r != null && r != root.get()) root = new WeakReference<>(r);
    }

    /**
     * Hilo de animación de MIUI, en cada fotograma de abrir o cerrar una app.
     *
     * @param cur  rectángulo de la ventana ahora
     * @param icon icono del que sale o al que vuelve (null al arrastrar: aún no se sabe)
     * @param full la ventana a pantalla completa
     */
    static void frame(RectF cur, RectF icon, RectF full) {
        float iw = icon != null && !icon.isEmpty() ? icon.width() : 0f;
        float p = (cur.width() - iw) / Math.max(1f, full.width() - iw);
        wanted = p < 0f ? 0f : (p > 1f ? 1f : p);
        if (icon != null && !icon.isEmpty()) {
            centerX = icon.centerX();
            centerY = icon.centerY();
        }
        lastFrameMs = SystemClock.uptimeMillis();
        if (!posted) {
            posted = true;
            MAIN.post(APPLY);
        }
    }

    private static final Runnable APPLY = new Runnable() {
        @Override
        public void run() {
            posted = false;
            try {
                if (reset != null) {                       // una animación nueva manda sobre la vuelta
                    reset.cancel();
                    reset = null;
                }
                apply(on() ? wanted : 0f);
            } catch (Throwable t) {
                Log.e("empujar iconos", t);
                restore();
            }
        }
    };

    /** Hilo principal: pone el escritorio a {@code p} (0 = normal, 1 = app abierta del todo). */
    private static void apply(float p) {
        if (p <= 0.002f) {
            restore();
            return;
        }
        if (moved.isEmpty() && !collect()) return;
        applied = p;
        float z = 1f + ZOOM * strength * p;
        float dim = 1f - DIM * p;
        float r = 0f;
        if (Build.VERSION.SDK_INT >= 31 && blur > 0f) {
            View any = moved.get(0).view.get();
            float dp = any == null ? 3f : any.getResources().getDisplayMetrics().density;
            // A medio píxel: no se crea un efecto nuevo si el cambio no se iba a notar.
            r = Math.round(BLUR_DP * dp * blur * p * 2f) / 2f;
        }
        float cx = centerX, cy = centerY;
        for (int i = moved.size() - 1; i >= 0; i--) {
            Moved m = moved.get(i);
            View v = m.view.get();
            if (v == null) {
                moved.remove(i);
                continue;
            }
            if (cx >= 0f) {
                v.setPivotX(cx - m.left);
                v.setPivotY(cy - m.top);
            }
            v.setScaleX(m.scaleX * z);
            v.setScaleY(m.scaleY * z);
            v.setAlpha(m.alpha * dim);
            if (Build.VERSION.SDK_INT >= 31 && r != m.lastBlur) {
                m.lastBlur = r;
                v.setRenderEffect(r < 0.5f ? null : RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
            }
        }
    }

    /** Hilo principal: todo como estaba. */
    private static void restore() {
        applied = 0f;
        for (Moved m : moved) {
            View v = m.view.get();
            if (v == null) continue;
            v.setScaleX(m.scaleX);
            v.setScaleY(m.scaleY);
            v.setAlpha(m.alpha);
            if (m.pivotSet) {
                v.setPivotX(m.pivotX);
                v.setPivotY(m.pivotY);
            } else {
                v.resetPivot();
            }
            if (Build.VERSION.SDK_INT >= 31 && m.lastBlur > 0f) v.setRenderEffect(null);
        }
        moved.clear();
    }

    /**
     * Hilo principal: busca la página visible del escritorio y el dock. Solo los que se ven en
     * pantalla (no las páginas de al lado). Devuelve si encontró alguno.
     */
    private static boolean collect() {
        View r = root.get();
        if (r == null || !r.isAttachedToWindow()) return false;
        watch(r);
        ArrayList<View> found = new ArrayList<>();
        find(r, found, 0);
        int[] loc = new int[2];
        Rect vis = new Rect();
        for (View v : found) {
            if (!v.isShown() || v.getWidth() == 0 || !v.getGlobalVisibleRect(vis)) continue;
            // Una página de al lado asoma solo un poco (o nada): se deja quieta.
            if (vis.width() < v.getWidth() / 2) continue;
            v.getLocationOnScreen(loc);
            moved.add(new Moved(v, loc));
        }
        if (moved.isEmpty()) {
            Log.i("empujar iconos: no se encontró la página ni el dock");
            return false;
        }
        return true;
    }

    private static void find(View v, ArrayList<View> out, int depth) {
        if (depth > 12) return;
        if (isContainer(v.getClass())) {
            out.add(v);
            return;                                   // lo de dentro va con él
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) find(g.getChildAt(i), out, depth + 1);
        }
    }

    /** CellLayout (una página) o Hotseat (el dock), también subclases (launcher de Kashi). */
    private static boolean isContainer(Class<?> c) {
        for (; c != null && c != View.class; c = c.getSuperclass()) {
            String n = c.getName();
            if (n.startsWith("com.miui.home.") && (n.endsWith(".CellLayout") || n.endsWith(".Hotseat"))) return true;
        }
        return false;
    }

    /**
     * Red de seguridad: cuando el launcher vuelve a tener el foco, si quedó algo movido y ya no
     * llegan fotogramas, vuelve a su sitio con una animación corta. Se mira otra vez en un rato por
     * si la animación de cerrar aún iba por la mitad.
     */
    private static void watch(View r) {
        if (watched.get() == r) return;
        watched = new WeakReference<>(r);
        r.getViewTreeObserver().addOnWindowFocusChangeListener(new ViewTreeObserver.OnWindowFocusChangeListener() {
            @Override
            public void onWindowFocusChanged(boolean hasFocus) {
                if (hasFocus) MAIN.postDelayed(CHECK, STALE_MS);
            }
        });
    }

    private static final Runnable CHECK = new Runnable() {
        @Override
        public void run() {
            if (moved.isEmpty() || reset != null) return;
            View r = watched.get();
            if (r == null || !r.hasWindowFocus()) return;
            if (SystemClock.uptimeMillis() - lastFrameMs < STALE_MS) {
                MAIN.postDelayed(CHECK, STALE_MS);       // aún va la animación: luego se mira
                return;
            }
            Log.i("empujar iconos: la animación se cortó; los iconos vuelven a su sitio");
            final ValueAnimator a = ValueAnimator.ofFloat(applied, 0f);
            a.setDuration(RESET_MS);
            a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator va) {
                    if (reset != va) return;
                    try {
                        apply((Float) va.getAnimatedValue());
                        if (va.getAnimatedFraction() >= 1f) reset = null;   // ya está en su sitio
                    } catch (Throwable t) {
                        Log.e("empujar iconos: vuelta", t);
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
}
