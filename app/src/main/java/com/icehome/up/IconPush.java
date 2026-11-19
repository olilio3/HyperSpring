package com.icehome.up;

import android.animation.ValueAnimator;
import android.appwidget.AppWidgetHostView;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Iconos que se apartan (3.1.0, diseñado con el usuario sobre bocetos animados): al abrir una app,
 * los demás iconos del escritorio y del dock se apartan suavemente de ella, cada uno en línea recta
 * alejándose del icono tocado. Cuanto más lejos, menos se mueven, y la caída es suave: los vecinos
 * se mueven casi igual y los iconos siguen juntos, como una tela que se aparta. Sin desenfoque.
 *
 * <p><b>El ritmo</b> lo da la animación de la ventana de MIUI: {@link WindowAnim} llama a
 * {@link #frame} en cada fotograma (en el hilo de animación de MIUI) con lo abierta que está la
 * ventana, y el empuje es proporcional (sin frenar al final): los iconos se mueven hasta que la
 * ventana llena la pantalla. Cerrar es abrir al revés, también siguiendo al dedo. Las vistas solo se
 * pueden tocar en el hilo principal: se guarda el último valor y se aplica allí.
 *
 * <p><b>Cómo:</b> solo se cambia la traslación de cada icono (translationX/Y), que el RenderThread
 * aplica sin volver a dibujar nada: casi gratis. Se guarda la que tenía cada uno para dejarla igual.
 *
 * <p><b>Red de seguridad:</b> si una animación se corta sin llegar a cerrar (otra que MIUI no
 * avisa, un cuelgue de la app…), al volver el foco al launcher y no llegar más fotogramas, los
 * iconos vuelven solos a su sitio. Nunca se quedan descolocados.
 */
final class IconPush {

    /** Ajuste «Empujar iconos»: 0 = apagado; 0,4 = lo de serie. */
    static volatile float strength = 0.4f;

    /** Lo que se aparta un icono pegado al tocado, a fuerza 1, en anchos de pantalla. */
    private static final float REACH = 0.96f;
    /** Distancia (en anchos de pantalla) a la que el empuje se queda en 1/e (~37 %). */
    private static final float FALLOFF = 0.63f;
    /** Red de seguridad: sin fotogramas durante esto y con el launcher delante, se vuelve. */
    private static final long STALE_MS = 300;
    private static final long RESET_MS = 320;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // ---- Entre hilos: lo escribe el hilo de animación de MIUI, lo lee el principal.
    private static volatile float wanted;
    private static volatile float centerX = -1f, centerY = -1f, screenW = 1080f;
    private static volatile long lastFrameMs;
    private static volatile boolean posted;
    /** Raíz de la ventana del launcher (la del último icono tocado). */
    private static volatile WeakReference<View> root = new WeakReference<>(null);

    // ---- Solo hilo principal.
    /** Iconos movidos, con su traslación original y su centro en pantalla. */
    private static final ArrayList<Moved> moved = new ArrayList<>();
    private static float applied;
    private static ValueAnimator reset;
    /** Raíz que ya tiene el aviso de foco puesto. */
    private static WeakReference<View> watched = new WeakReference<>(null);

    private static final class Moved {
        final WeakReference<View> view;
        final float tx, ty, cx, cy;

        Moved(View v, int[] loc) {
            view = new WeakReference<>(v);
            tx = v.getTranslationX();
            ty = v.getTranslationY();
            cx = loc[0] + v.getWidth() * 0.5f;
            cy = loc[1] + v.getHeight() * 0.5f;
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
        boolean has = icon != null && !icon.isEmpty();
        float iw = has ? icon.width() : 0f, ih = has ? icon.height() : 0f;
        // Media de lo abierta que está en ancho y en alto: solo llega a 1 cuando la ventana llena
        // la pantalla en las dos direcciones, así que los iconos no se paran antes.
        float pw = (cur.width() - iw) / Math.max(1f, full.width() - iw);
        float ph = (cur.height() - ih) / Math.max(1f, full.height() - ih);
        float p = (pw + ph) * 0.5f;
        wanted = p < 0f ? 0f : (p > 1f ? 1f : p);
        screenW = Math.max(1f, full.width());
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

    /** Hilo principal: pone los iconos a {@code p} (0 = en su sitio, 1 = app abierta del todo). */
    private static void apply(float p) {
        if (p <= 0.002f) {
            restore();
            return;
        }
        if (moved.isEmpty() && !collect()) return;
        applied = p;
        float w = screenW, cx = centerX, cy = centerY;
        float reach = p * REACH * strength * w, fall = FALLOFF * w;
        for (int i = moved.size() - 1; i >= 0; i--) {
            Moved m = moved.get(i);
            View v = m.view.get();
            if (v == null) {
                moved.remove(i);
                continue;
            }
            float dx = m.cx - cx, dy = m.cy - cy;
            float d = (float) Math.hypot(dx, dy);
            if (cx < 0f || d < 1f) continue;                  // el icono tocado (o sin centro) no se mueve
            float dist = reach * (float) Math.exp(-d / fall);
            v.setTranslationX(m.tx + dx / d * dist);
            v.setTranslationY(m.ty + dy / d * dist);
        }
    }

    /** Hilo principal: todo como estaba. */
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
     * Hilo principal: busca los iconos (y widgets) de la página visible del escritorio y del dock.
     * Solo de las páginas que se ven en pantalla. Devuelve si encontró alguno.
     */
    private static boolean collect() {
        View r = root.get();
        if (r == null || !r.isAttachedToWindow()) return false;
        watch(r);
        ArrayList<View> pages = new ArrayList<>();
        find(r, pages, 0);
        int[] loc = new int[2];
        android.graphics.Rect vis = new android.graphics.Rect();
        ArrayList<View> items = new ArrayList<>();
        for (View page : pages) {
            if (!page.isShown() || page.getWidth() == 0 || !page.getGlobalVisibleRect(vis)) continue;
            // Una página de al lado asoma solo un poco (o nada): se deja quieta.
            if (vis.width() < page.getWidth() / 2) continue;
            items.clear();
            items(page, items, 0);
            for (View it : items) {
                if (!it.isShown()) continue;
                it.getLocationOnScreen(loc);
                moved.add(new Moved(it, loc));
            }
        }
        if (moved.isEmpty()) {
            Log.i("empujar iconos: no se encontró la página ni el dock");
            return false;
        }
        return true;
    }

    /** Página (CellLayout) o dock (Hotseat). */
    private static void find(View v, ArrayList<View> out, int depth) {
        if (depth > 12) return;
        if (named(v.getClass(), ".CellLayout") || named(v.getClass(), ".Hotseat")) {
            out.add(v);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) find(g.getChildAt(i), out, depth + 1);
        }
    }

    /**
     * Lo que se mueve dentro de una página: iconos, carpetas y widgets. Un hijo del contenedor de
     * iconos de Launcher3 (ShortcutAndWidgetContainer) lo es siempre; si MIUI los pone directamente
     * en la página, se reconocen por su clase.
     */
    private static void items(View v, ArrayList<View> out, int depth) {
        if (depth > 6 || !(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        boolean holder = named(g.getClass(), "ShortcutAndWidgetContainer");
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (holder || isItem(c)) out.add(c);
            else items(c, out, depth + 1);
        }
    }

    private static boolean isItem(View v) {
        if (v instanceof AppWidgetHostView) return true;
        Class<?> c = v.getClass();
        return named(c, "ShortcutIcon") || named(c, "FolderIcon") || named(c, "ItemIcon")
                || named(c, "WidgetView") || named(c, "HostView");
    }

    /** La clase (o una de sus madres) del launcher termina en {@code end}. */
    private static boolean named(Class<?> c, String end) {
        for (; c != null && c != View.class && c != ViewGroup.class; c = c.getSuperclass()) {
            String n = c.getName();
            if (n.startsWith("com.miui.home.") && n.endsWith(end)) return true;
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
