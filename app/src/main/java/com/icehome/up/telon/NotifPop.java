package com.icehome.up.telon;

import android.graphics.Matrix;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.util.Property;
import android.view.Choreographer;
import android.view.View;
import android.view.animation.Interpolator;

import com.icehome.up.xp.XC_MethodHook;
import com.icehome.up.xp.XposedHelpers;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.WeakHashMap;

/**
 * Notificaciones emergentes como en iOS 27: nacen pequeñas y borrosas junto a la hora (arriba a la
 * izquierda), bajan creciendo y se pasan de su sitio, y vuelven arriba con un muelle. Al irse
 * hacen el arco al revés hasta la hora.
 *
 * HyperOS 3 anima la entrada y la salida con translationY y unas curvas propias
 * (MiuiNotificationAnimations.HEADS_UP_APPEAR/DISAPPEAR_INTERPOLATOR), que elige
 * StackStateAnimator$1.getCustomInterpolator. Ahí se cambia su curva por una que deja la fila
 * quieta (en su sitio al entrar; donde está hasta el final al salir) y el movimiento lo pone
 * HyperSpring con View.setAnimationMatrix, sin tocar la translationY de HyperOS.
 */
public final class NotifPop {
    static volatile boolean enabled;
    static volatile float bounce = 1f, speed = 1f, blur = 1f;

    private static final String STACK = "com.android.systemui.statusbar.notification.stack.";
    // Clave débil y el valor solo guarda una WeakReference: la fila se puede liberar.
    private static final WeakHashMap<View, Pop> pops = new WeakHashMap<>();
    private static Object appearCurve, disappearCurve;
    private static int logged;

    /** Al entrar: la fila ya está en su sitio desde el primer fotograma. */
    private static final Interpolator AT_END = new Interpolator() {
        @Override
        public float getInterpolation(float t) {
            return 1f;
        }
    };
    /** Al salir: la fila se queda donde está hasta el último fotograma (el arco lo dibujamos nosotros). */
    private static final Interpolator HOLD = new Interpolator() {
        @Override
        public float getInterpolation(float t) {
            return t >= 1f ? 1f : 0f;
        }
    };

    public static void install(ClassLoader cl) {
        Class<?> anims = XposedHelpers.findClass(STACK + "MiuiNotificationAnimations", cl);
        appearCurve = staticField(anims, "HEADS_UP_APPEAR_INTERPOLATOR");
        disappearCurve = staticField(anims, "HEADS_UP_DISAPPEAR_INTERPOLATOR");
        XposedHelpers.findAndHookMethod(STACK + "StackStateAnimator$1", cl, "getCustomInterpolator",
                Property.class, View.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        if (p.args[0] != View.TRANSLATION_Y) return;
                        Object r = p.getResult();
                        boolean in = r != null && r == appearCurve, out = r != null && r == disappearCurve;
                        if (!in && !out) return;
                        if (!enabled) {
                            // Animación normal de HyperOS: solo se esconde mientras el cristal captura lo de detrás.
                            if (in) com.icehome.up.cc.NotifGlass.hideForCapture((View) p.args[1]);
                            return;
                        }
                        try {
                            start((View) p.args[1], in);
                            p.setResult(in ? AT_END : HOLD);
                        } catch (Throwable t) {
                            Log.e("notificaciones iOS 27", t);
                        }
                    }
                });
        Log.i("notificaciones iOS 27: instaladas");
    }

    /** La fila se está animando ahora (para el cristal de la emergente). */
    public static boolean animating(View row) {
        Pop p = pops.get(row);
        return p != null && p.running;
    }

    /** La matriz de animación que lleva ahora la fila, o null (para que el cristal la siga). */
    public static Matrix matrixOf(View row) {
        Pop p = pops.get(row);
        return p != null && p.running ? p.m : null;
    }

    private static Object staticField(Class<?> c, String name) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) {
            throw new IllegalStateException("falta " + c.getSimpleName() + "." + name, t);
        }
    }

    private static void start(View v, boolean in) {
        Pop p = pops.get(v);
        if (p == null) {
            p = new Pop(v);
            pops.put(v, p);
        }
        p.begin(in);
        if (in) com.icehome.up.cc.NotifGlass.appearing(v);
        if (logged < 5) {
            logged++;
            Log.i("notificaciones iOS 27: " + (in ? "entra" : "sale") + " " + v.getWidth() + "x" + v.getHeight());
        }
    }

    /**
     * Una notificación animándose. Escala desde su esquina de arriba a la izquierda (crece hacia la
     * derecha y hacia abajo, como en iOS), más un desplazamiento en x e y. Al entrar son muelles; la
     * y sale con velocidad hacia abajo para pasarse de su sitio y volver.
     */
    private static final class Pop implements Choreographer.FrameCallback {
        final WeakReference<View> view;
        final Matrix m = new Matrix();
        boolean in, running;
        long lastNs, startNs;
        // Escala, x e y (en fracciones del ancho/alto de la fila) y sus velocidades.
        float s = 1f, sv, x, xv, y, yv;
        // Anchura aparte, con un muelle más suelto: al llegar se estira hacia la derecha y vuelve.
        float q = 1f, qv;
        /** Anchura máxima: el borde derecho no pasa del de la pantalla (menos un margen). */
        float qMax = 1.2f;
        // Al salir: de dónde parte el arco.
        float s0, x0, y0;
        float lastBlur = -1f;

        Pop(View v) {
            view = new WeakReference<>(v);
        }

        void begin(boolean entering) {
            in = entering;
            if (entering) {
                s = 0.14f;                       // pastilla pequeña junto a la hora
                q = 0.14f;
                qv = 0f;
                View v = view.get();
                if (v != null && v.getWidth() > 0) {
                    int[] loc = new int[2];
                    v.getLocationOnScreen(loc);
                    float room = v.getResources().getDisplayMetrics().widthPixels - loc[0]
                            - 4f * v.getResources().getDisplayMetrics().density;
                    qMax = Math.max(1f, room / v.getWidth());
                }
                x = 0.06f;
                y = -0.45f;                      // un poco por encima de su sitio (a la altura de la hora)
                sv = 0f;
                xv = 0f;
                yv = 7.5f * (0.4f + 0.6f * clamp(bounce, 0f, 1.5f));   // cae con fuerza y se pasa
            } else {
                s0 = s;
                x0 = x;
                y0 = y;
            }
            startNs = 0;
            lastNs = 0;
            if (!running) {
                running = true;
                Choreographer.getInstance().postFrameCallback(this);
            }
        }

        @Override
        public void doFrame(long now) {
            View v = view.get();
            if (v == null) {
                running = false;
                return;
            }
            try {
                if (startNs == 0) startNs = now;
                float dt = lastNs == 0 ? 1f / 60f : Math.min(1f / 30f, (now - lastNs) / 1e9f);
                lastNs = now;
                if (in && com.icehome.up.cc.NotifGlass.hidden(v)) {
                    // Aún se captura lo de detrás: la animación espera (escondida) en su punto de salida.
                    startNs = 0;
                    lastNs = 0;
                    apply(v);
                    Choreographer.getInstance().postFrameCallback(this);
                    return;
                }
                boolean done = in ? stepIn(dt) : stepOut((now - startNs) / 1e9f);
                if (done || !v.isAttachedToWindow()) {
                    finish(v);
                    return;
                }
                apply(v);
                Choreographer.getInstance().postFrameCallback(this);
            } catch (Throwable t) {
                Log.e("notificaciones iOS 27: fotograma", t);
                finish(v);
            }
        }

        /** Muelles hacia el sitio final (escala 1, sin desplazamiento). */
        boolean stepIn(float dt) {
            float sp = clamp(speed, 0.3f, 3f);
            float b = clamp(bounce, 0f, 1.5f);
            float w = 2f * (float) Math.PI / (0.55f / sp);          // periodo de ~0,55 s
            float zs = 0.78f - 0.18f * b, zy = 0.62f - 0.22f * b, zx = 0.9f, zq = 0.66f - 0.1f * b;
            for (int i = 0; i < 3; i++) {                            // subpasos: estable a 30 fps
                float h = dt / 3f;
                sv += (w * w * (1f - s) - 2f * zs * w * sv) * h;
                s += sv * h;
                qv += (w * w * (1f - q) - 2f * zq * w * qv) * h;
                q += qv * h;
                xv += (w * w * (0f - x) - 2f * zx * w * xv) * h;
                x += xv * h;
                yv += (w * w * (0f - y) - 2f * zy * w * yv) * h;
                y += yv * h;
            }
            return Math.abs(1f - s) < 0.002f && Math.abs(sv) < 0.02f && Math.abs(1f - q) < 0.002f && Math.abs(qv) < 0.02f
                    && Math.abs(x) < 0.002f && Math.abs(y) < 0.002f && Math.abs(yv) < 0.02f;
        }

        /** El arco de salida: se encoge, baja un poco y sube hasta la hora. */
        boolean stepOut(float t) {
            float dur = 0.36f / clamp(speed, 0.3f, 3f);
            float u = Math.min(1f, t / dur);
            float e = u * u * (3f - 2f * u);
            s = s0 + (0.12f - s0) * (u * u);                         // se encoge cada vez más rápido
            q = s;
            x = x0 + (0.06f - x0) * e;
            y = y0 + (-0.45f - y0) * e + 0.5f * (float) Math.sin(Math.PI * u) * (1f - e);
            return u >= 1f;
        }

        void apply(View v) {
            float w = v.getWidth(), h = v.getHeight();
            m.setScale(soft(q, qMax), s);                                     // desde la esquina de arriba a la izquierda
            if (com.icehome.up.cc.NotifGlass.hidden(v)) m.setScale(0f, 0f);   // se está capturando lo de detrás
            m.postTranslate(x * w, y * h);
            v.setAnimationMatrix(m);
            com.icehome.up.cc.NotifGlass.moved(v);                  // el cristal enseña lo de detrás de donde está
            if (Build.VERSION.SDK_INT >= 31) {
                // Borrosa mientras es pequeña; nítida al llegar a su tamaño.
                float r = blur <= 0f ? 0f : Math.round(24f * blur * clamp((1f - s) / 0.86f, 0f, 1f));
                if (r != lastBlur) {
                    lastBlur = r;
                    v.setRenderEffect(r < 1f ? null : RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
                }
            }
        }

        void finish(View v) {
            running = false;
            s = q = 1f;
            x = y = 0f;
            sv = qv = xv = yv = 0f;
            v.setAnimationMatrix(null);
            com.icehome.up.cc.NotifGlass.moved(v);
            if (Build.VERSION.SDK_INT >= 31 && lastBlur > 0f) v.setRenderEffect(null);
            lastBlur = -1f;
        }
    }

    /** Por encima de 1 se acerca a {@code max} sin pasarlo ni frenar en seco (tanh). */
    static float soft(float v, float max) {
        if (v <= 1f) return v;
        float room = max - 1f;
        return room <= 0f ? 1f : 1f + room * (float) Math.tanh((v - 1f) / room);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private NotifPop() {
    }
}
