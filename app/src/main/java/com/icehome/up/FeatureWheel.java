package com.icehome.up;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.Choreographer;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Rueda de funciones: las fichas de Inicio en un tambor que gira en vertical, como el
 * selector de hora de iOS pero con tarjetas, y que da la vuelta (después de la última vuelve la
 * primera).
 *
 * <p><b>Cómo se ve.</b> La ficha del centro, nítida y a tamaño completo, dentro de un marco de
 * "lente". Las demás están en la superficie del tambor: cuanto más lejos del centro, más giradas
 * (la de arriba con su borde de arriba hacia el fondo, la de abajo al revés), más pequeñas, más
 * transparentes y más desenfocadas. Arriba y abajo se funden con el fondo de la página.
 *
 * <p><b>Cómo se mueve.</b> El dedo la arrastra 1:1. Al soltar con velocidad, sigue girando y
 * frena poco a poco; al frenar, un muelle la encaja en la ficha más cercana. Un tic de vibración
 * cada vez que una ficha pasa por el centro.
 *
 * <p><b>Toques.</b> En la ficha del centro, lo de siempre: encender/apagar, mantener pulsado para
 * ver qué hace. En otra ficha: la trae al centro (no la enciende, para no cambiar nada sin
 * querer). Debajo de la rueda se lee qué hace la ficha del centro.
 *
 * <p><b>Rendimiento.</b> Solo cambian propiedades de las vistas (posición, giro, escala,
 * transparencia), que la GPU aplica sin volver a dibujar las fichas. El desenfoque se rehace solo
 * cuando cambia en un píxel entero. El bucle de fotogramas solo corre mientras la rueda se mueve.
 */
final class FeatureWheel extends FrameLayout {

    /** Le dice a quien la usa qué ficha queda en el centro (para enseñar su explicación). */
    interface OnCenter {
        void center(int index);
    }

    /** Grados del tambor entre una ficha y la siguiente. */
    private static final float STEP_DEG = 26f;
    /** Más allá de este ángulo la ficha ya no se ve (está en la parte de atrás del tambor). */
    private static final float MAX_DEG = 82f;
    /** Separación entre fichas en el centro, respecto a la altura de una ficha. */
    private static final float GAP = 1.06f;
    /** Muelle que encaja la ficha en el centro (rigidez 1/s² y amortiguación relativa). */
    private static final float SNAP_K = 170f, SNAP_ZETA = 0.82f;
    /** Rozamiento al girar suelta (1/s): más alto, frena antes. */
    private static final float FRICTION = 3.2f;
    /** Por debajo de esta velocidad (fichas/s) deja de rodar y encaja. */
    private static final float SETTLE_SPEED = 2.2f;

    private final List<FrameLayout> slots = new ArrayList<>();
    private final float dp;
    private final int cardPx;
    private final float radius;                   // radio del tambor (px)
    private final float stepPx;                   // px de dedo por ficha
    private final int bg, accent;
    private final Paint fade = new Paint(), lens = new Paint(Paint.ANTI_ALIAS_FLAG), lensEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF lensRect = new RectF();
    private final float[] blurNow;
    private OnCenter onCenter;

    /** Ficha en el centro, continua (2,4 = entre la 2 y la 3). Siempre en [0, n). */
    private float pos;
    private float vel;                            // fichas/s
    private float target = Float.NaN;             // encajando hacia esta (sin dar la vuelta)
    private int centerShown = -1, tickAt = Integer.MIN_VALUE;
    private boolean running;
    private long lastNs;

    // Toque
    private final int slop;
    private VelocityTracker vt;
    private float downY, lastY;
    private boolean dragging, tapOther;
    private int tapIndex = -1;

    FeatureWheel(Context c, List<View> tiles, int cardDp, int bg, int accent) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        cardPx = Math.round(cardDp * dp);
        float step = (float) Math.toRadians(STEP_DEG);
        radius = cardPx * GAP / (float) Math.sin(step);
        stepPx = radius * (float) Math.sin(step);
        this.bg = bg;
        this.accent = accent;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setClipChildren(true);
        setWillNotDraw(false);
        for (View t : tiles) {
            // Un soporte por ficha: la rueda gira el soporte, y la ficha conserva sus propias
            // animaciones (hundirse al pulsar, la onda al encender otra) sin que se pisen.
            FrameLayout holder = new FrameLayout(c);
            holder.setClipChildren(false);
            holder.setCameraDistance(9000 * dp);
            if (t.getParent() instanceof ViewGroup) ((ViewGroup) t.getParent()).removeView(t);
            holder.addView(t, new FrameLayout.LayoutParams(-1, -1));
            LayoutParams lp = new LayoutParams(-1, cardPx);
            lp.gravity = android.view.Gravity.CENTER_VERTICAL;
            lp.leftMargin = Math.round(4 * dp);
            lp.rightMargin = Math.round(4 * dp);
            addView(holder, lp);
            slots.add(holder);
        }
        blurNow = new float[slots.size()];
        java.util.Arrays.fill(blurNow, -1f);
        lens.setColor((accent & 0x00FFFFFF) | 0x10000000);
        lensEdge.setStyle(Paint.Style.STROKE);
        lensEdge.setStrokeWidth(Math.max(1f, 1.2f * dp));
        lensEdge.setColor((accent & 0x00FFFFFF) | 0x59000000);
    }

    void setOnCenter(OnCenter l) {
        onCenter = l;
        centerShown = -1;
        layoutWheel();
    }

    /** Alto: la ficha del centro y lo que se ve del tambor por encima y por debajo. */
    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int h = Math.round(2f * radius * (float) Math.sin(Math.toRadians(64)) + cardPx);
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // Fundido arriba y abajo: una máscara que borra las fichas hacia los bordes (DST_OUT), así
        // se ve el fondo real de la página (los puntos y las luces), no una franja de color.
        fade.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0xFF000000, 0x00000000, 0x00000000, 0xFF000000}, new float[]{0f, 0.32f, 0.68f, 1f},
                Shader.TileMode.CLAMP));
        fade.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT));
        float m = 1.5f * dp;
        lensRect.set(m, h / 2f - cardPx / 2f - 4 * dp, w - m, h / 2f + cardPx / 2f + 4 * dp);
        layoutWheel();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        layoutWheel();
    }

    /** Coloca cada ficha en el tambor según {@link #pos}. */
    private void layoutWheel() {
        int n = slots.size();
        if (n == 0) return;
        for (int i = 0; i < n; i++) {
            FrameLayout s = slots.get(i);
            float o = i - pos;                            // distancia al centro, en fichas…
            o -= n * (float) Math.floor(o / n + 0.5f);    // …por el camino corto (da la vuelta)
            float deg = o * STEP_DEG;
            if (Math.abs(deg) > MAX_DEG) {
                s.setVisibility(INVISIBLE);
                continue;
            }
            s.setVisibility(VISIBLE);
            double a = Math.toRadians(deg);
            float cos = (float) Math.cos(a);
            s.setTranslationY(radius * (float) Math.sin(a));
            s.setRotationX(-deg * 0.92f);                // arriba: su borde de arriba se aleja
            float k = 0.84f + 0.16f * cos;
            s.setScaleX(k);
            s.setScaleY(k);
            s.setAlpha(Math.max(0f, Math.min(1f, (float) Math.pow(cos, 1.6f))));
            s.setTranslationZ(-Math.abs(o) * dp);        // la del centro, por encima
            float d = Math.abs(o);
            s.setImportantForAccessibility(d < 0.5f ? IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            blur(i, s, Math.min(9f, d * 4.5f) * dp);
        }
        int c = Math.round(pos) % n;
        if (c != centerShown) {
            centerShown = c;
            if (onCenter != null) onCenter.center(c);
        }
    }

    /** Desenfoque de una ficha, solo si cambia en un píxel entero (rehacerlo cuesta). */
    private void blur(int i, View v, float r) {
        if (Build.VERSION.SDK_INT < 31) return;
        float q = Math.round(r);
        if (q < 1f) q = 0f;
        if (blurNow[i] == q) return;
        blurNow[i] = q;
        try {
            v.setRenderEffect(q == 0f ? null : RenderEffect.createBlurEffect(q, q, Shader.TileMode.DECAL));
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ dibujo

    @Override
    protected void dispatchDraw(Canvas c) {
        // La lente: un marco suave alrededor de la ficha del centro, por debajo de las fichas.
        float r = 19f * dp;
        int layer = c.saveLayer(0, 0, getWidth(), getHeight(), null);
        c.drawRoundRect(lensRect, r, r, lens);
        c.drawRoundRect(lensRect, r, r, lensEdge);
        super.dispatchDraw(c);
        // Arriba y abajo, las fichas se desvanecen hasta dejar ver la página.
        c.drawRect(0, 0, getWidth(), getHeight(), fade);
        c.restoreToCount(layer);
    }

    // ------------------------------------------------------------------ toques

    /** Qué ficha hay bajo una altura de la rueda (la inversa del tambor). */
    private int indexAt(float y) {
        float dy = y - getHeight() / 2f;
        if (Math.abs(dy) < cardPx / 2f) return Math.round(pos) % slots.size();
        float s = Math.max(-1f, Math.min(1f, dy / radius));
        float o = (float) Math.toDegrees(Math.asin(s)) / STEP_DEG;
        int n = slots.size();
        return ((Math.round(pos + o) % n) + n) % n;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = lastY = e.getY();
                dragging = false;
                // La página no se desplaza mientras el dedo esté en la rueda.
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                if (vt == null) vt = VelocityTracker.obtain();
                else vt.clear();
                vt.addMovement(e);
                boolean moving = running && (Math.abs(vel) > 0.5f || !Float.isNaN(target));
                stop();
                tapIndex = indexAt(e.getY());
                tapOther = tapIndex != Math.round(pos) % slots.size();
                // Girando: el toque solo la para. En otra ficha: la trae al centro. En ambos
                // casos la rueda se queda el toque (la ficha no se enciende).
                return moving || tapOther;
            case MotionEvent.ACTION_MOVE:
                if (vt != null) vt.addMovement(e);
                if (Math.abs(e.getY() - downY) > slop) {
                    dragging = true;
                    lastY = e.getY();
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
        int n = slots.size();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return true;
            case MotionEvent.ACTION_MOVE: {
                float y = e.getY();
                if (!dragging && Math.abs(y - downY) > slop) {
                    dragging = true;
                    lastY = y;
                }
                if (dragging) {
                    pos = wrap(pos - (y - lastY) / stepPx);
                    lastY = y;
                    tick();
                    layoutWheel();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    vt.computeCurrentVelocity(1000);
                    fling(-vt.getYVelocity() / stepPx);
                } else if (tapOther && tapIndex >= 0) {
                    // Al centro por el camino corto.
                    float o = tapIndex - pos;
                    o -= n * (float) Math.floor(o / n + 0.5f);
                    snapTo(pos + o);
                } else {
                    snapTo(Math.round(pos));
                }
                dragging = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                snapTo(Math.round(pos));
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return true;
        }
    }

    // ------------------------------------------------------------------ física

    private void fling(float v) {
        if (!ValueAnimator.areAnimatorsEnabled()) {                 // sin animaciones del sistema
            snapTo(Math.round(pos + v * 0.3f));
            return;
        }
        target = Float.NaN;
        vel = Math.max(-40f, Math.min(40f, v));
        start();
    }

    private void snapTo(float t) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            pos = wrap(t);
            vel = 0f;
            target = Float.NaN;
            tick();
            layoutWheel();
            return;
        }
        target = t;
        start();
    }

    private void stop() {
        running = false;
        vel = 0f;
        target = Float.NaN;
        Choreographer.getInstance().removeFrameCallback(frame);
    }

    private void start() {
        if (running) return;
        running = true;
        lastNs = 0;
        Choreographer.getInstance().postFrameCallback(frame);
    }

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long now) {
            if (!running) return;
            float dt = lastNs == 0 ? 1f / 60f : Math.min(1f / 30f, (now - lastNs) / 1e9f);
            lastNs = now;
            if (Float.isNaN(target)) {
                // Rodando suelta: frena con rozamiento y, al ir despacio, elige dónde encajar
                // (teniendo en cuenta hacia dónde iba, para que no dé marcha atrás).
                vel *= (float) Math.exp(-FRICTION * dt);
                pos += vel * dt;
                if (Math.abs(vel) < SETTLE_SPEED) target = Math.round(pos + vel * 0.12f);
            } else {
                // Muelle hacia la ficha (en pasos cortos: estable aunque un fotograma llegue tarde).
                float c = 2f * SNAP_ZETA * (float) Math.sqrt(SNAP_K);
                for (float left = dt; left > 0f; left -= 0.004f) {
                    float h = Math.min(0.004f, left);
                    vel += (SNAP_K * (target - pos) - c * vel) * h;
                    pos += vel * h;
                }
                if (Math.abs(target - pos) < 0.0015f && Math.abs(vel) < 0.02f) {
                    pos = target;
                    running = false;
                }
            }
            float t = target;
            float wrapped = wrap(pos);
            if (!Float.isNaN(t)) target = t + (wrapped - pos);      // el destino se mueve con la vuelta
            pos = wrapped;
            if (!running) {
                pos = wrap(Math.round(pos));
                vel = 0f;
                target = Float.NaN;
            }
            tick();
            layoutWheel();
            if (running) Choreographer.getInstance().postFrameCallback(this);
        }
    };

    /** Tic de vibración cada vez que una ficha pasa por el centro. */
    private void tick() {
        int at = Math.round(pos) % slots.size();              // al dar la vuelta, n y 0 son la misma
        if (at == tickAt) return;
        boolean first = tickAt == Integer.MIN_VALUE;
        tickAt = at;
        if (!first) {
            performHapticFeedback(Build.VERSION.SDK_INT >= 34
                    ? HapticFeedbackConstants.SEGMENT_FREQUENT_TICK : HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    private float wrap(float p) {
        int n = slots.size();
        p %= n;
        return p < 0 ? p + n : p;
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        pos = wrap(Math.round(pos));
        if (vt != null) {
            vt.recycle();
            vt = null;
        }
        super.onDetachedFromWindow();
    }
}
