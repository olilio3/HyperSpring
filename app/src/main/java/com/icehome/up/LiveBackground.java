package com.icehome.up;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.ComposeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;

/**
 * Fondo de las páginas de la app: negro puro con una rejilla de puntos muy tenue, como las webs
 * de estudios y herramientas (Vercel, Linear…). Sin colores ni degradados: el color se reserva para
 * lo que está encendido.
 * <p>
 * Va de fondo del ScrollView de cada página: el fondo de una vista no se desplaza con el
 * contenido, así que la rejilla queda quieta mientras las tarjetas pasan por encima (sensación de
 * profundidad gratis). Es una imagen fija repetida: no gasta nada en cada fotograma (antes había un
 * fondo animado que redibujaba toda la pantalla sin parar).
 */
final class LiveBackground extends Drawable {

    /** Color del icono (el acento de la app). */
    static final int CORAL = Motion.ACCENT;

    private final int base;
    private final Paint dots = new Paint();
    /**
     * Solo en las de portada: bajo el dedo, los puntos se encienden del color de acento en un
     * círculo que se apaga hacia fuera, y al soltar se apagan poco a poco. Es la misma rejilla de
     * puntos (más gruesos y de color) recortada por un degradado circular: un solo rectángulo.
     */
    private final boolean reactive;
    private final Paint lit = new Paint();
    private BitmapShader litDots;
    private final float dp;
    private float gx, gy, glow;
    private ValueAnimator fade;
    private float fadeGoal;
    /** Onda de la entrada de la app ({@link AppEntrance}): centro, radio y fuerza (0 = apagada). */
    private float wx, wy, wr, wa;
    private final Paint wave = new Paint();

    /**
     * @param base color de fondo (negro)
     * @param dp   densidad de la pantalla
     * @param dim  cuánto se ven los puntos (0-255)
     */
    private LiveBackground(int base, float dp, int dim, boolean reactive) {
        this.base = base;
        this.dp = dp;
        this.reactive = reactive;
        // Una baldosa con un punto en medio; el shader la repite por toda la pantalla.
        int cell = Math.max(8, Math.round(22 * dp));
        Bitmap b = Bitmap.createBitmap(cell, cell, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor((dim << 24) | 0xFFFFFF);
        c.drawCircle(cell / 2f, cell / 2f, Math.max(1f, 0.9f * dp), p);
        dots.setShader(new BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        {
            Bitmap l = Bitmap.createBitmap(cell, cell, Bitmap.Config.ARGB_8888);
            p.setColor(CORAL);
            new Canvas(l).drawCircle(cell / 2f, cell / 2f, Math.max(1.5f, 1.7f * dp), p);
            litDots = new BitmapShader(l, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        }
    }

    /**
     * Se le pasan los toques de la página ({@code host}, coordenadas de la vista): mientras el dedo
     * está puesto, el círculo de puntos encendidos lo sigue; al soltar, se apaga.
     */
    void touch(View host, MotionEvent e) {
        if (!reactive || !ValueAnimator.areAnimatorsEnabled()) return;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                gx = e.getX();
                gy = e.getY();
                fadeTo(1f, 220);
                invalidateSelf();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                fadeTo(0f, 700);
                break;
            default:
                break;
        }
    }

    private void fadeTo(float to, long ms) {
        if (to == fadeGoal) return;                         // ya va (o está) ahí
        fadeGoal = to;
        if (fade != null) fade.cancel();
        fade = ValueAnimator.ofFloat(glow, to);
        fade.setDuration(ms);
        fade.setInterpolator(Motion.POWER3_OUT);
        fade.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                glow = (Float) a.getAnimatedValue();
                invalidateSelf();
            }
        });
        fade.start();
    }

    /** Fondo normal de las páginas. */
    static LiveBackground dots(int base, float dp) {
        return new LiveBackground(base, dp, 0x1A, false);
    }

    /** Fondo de las pantallas «de portada» («Acerca de», la bienvenida): los puntos algo más marcados. */
    static LiveBackground hero(int base, float dp) {
        return new LiveBackground(base, dp, 0x2A, true);
    }

    /**
     * Un ScrollView que pasa a su fondo todos los toques (aunque los recoja una tarjeta de dentro),
     * para que la rejilla de las páginas de portada se encienda bajo el dedo.
     */
    static android.widget.ScrollView scrollView(android.content.Context c) {
        return new android.widget.ScrollView(c) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent e) {
                if (getBackground() instanceof LiveBackground) ((LiveBackground) getBackground()).touch(this, e);
                return super.dispatchTouchEvent(e);
            }
        };
    }

    /**
     * Un anillo de puntos de color que se abre desde ({@code x}, {@code y}) hasta el radio
     * {@code r}: la onda que recorre la página al abrir la app. {@code alpha} 0 la apaga.
     */
    void wave(float x, float y, float r, float alpha) {
        wx = x;
        wy = y;
        wr = r;
        wa = alpha;
        invalidateSelf();
    }

    void attachTo(View page) {
        page.setBackground(this);
        if (!ambient) return;
        // La deriva solo corre mientras la página está en la ventana (y con animaciones activas).
        page.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                startDrift();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                if (drift != null) drift.cancel();
            }
        });
        if (page.isAttachedToWindow()) startDrift();
    }

    // ---------------------------------------------------------------- fondo vivo

    /**
     * Páginas principales: dos luces coral muy suaves recorren la rejilla despacio, encendiendo los
     * puntos por donde pasan (como un foco que barre una pared de LEDs). Siguen curvas de
     * Lissajous con periodos que no encajan entre sí (31 s y 23 s), así que el dibujo no se repite
     * a la vista. Son dos círculos con el mismo shader de puntos de color que el dedo: barato.
     */
    private boolean ambient;
    private ValueAnimator drift;
    private float clock;                                 // segundos desde que empezó la deriva
    private final Paint amb = new Paint();

    /** Igual que {@link #dots}, pero con las luces que se mueven solas (páginas principales). */
    static LiveBackground ambient(int base, float dp, boolean hero) {
        LiveBackground b = new LiveBackground(base, dp, hero ? 0x2A : 0x1A, true);
        b.ambient = true;
        return b;
    }

    private void startDrift() {
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        if (drift != null && drift.isRunning()) return;
        final long t0 = android.os.SystemClock.uptimeMillis() - Math.round(clock * 1000);
        drift = ValueAnimator.ofFloat(0f, 1f);
        drift.setDuration(1000);
        drift.setRepeatCount(ValueAnimator.INFINITE);
        drift.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                clock = (android.os.SystemClock.uptimeMillis() - t0) / 1000f;
                invalidateSelf();
            }
        });
        drift.start();
    }

    /** Una luz: centro ({@code x}, {@code y}), radio y fuerza (0-1). */
    private void light(Canvas canvas, float x, float y, float r, float a) {
        RadialGradient mask = new RadialGradient(x, y, r,
                new int[]{0xFF000000, 0x66000000, 0x00000000}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        amb.setShader(new ComposeShader(mask, litDots, PorterDuff.Mode.SRC_IN));
        amb.setAlpha(Math.round(255 * a));
        canvas.drawCircle(x, y, r, amb);
    }

    private void drawAmbient(Canvas canvas, Rect b) {
        if (litDots == null || b.isEmpty()) return;
        float w = b.width(), h = b.height(), t = clock;
        double p1 = 2 * Math.PI * t / 31.0, p2 = 2 * Math.PI * t / 23.0;
        // Luz 1: arriba, por detrás del título. Luz 2: más abajo, más pequeña y más tenue.
        float x1 = w * (0.5f + 0.38f * (float) Math.sin(p1)), y1 = h * (0.22f + 0.12f * (float) Math.sin(2 * p1 + 0.7));
        float x2 = w * (0.5f + 0.42f * (float) Math.cos(p2 * 1.3)), y2 = h * (0.68f + 0.16f * (float) Math.sin(p2));
        // Respiran despacio: nunca del todo apagadas ni fuertes (el color es para lo encendido).
        float a1 = 0.30f + 0.10f * (float) Math.sin(2 * Math.PI * t / 7.0);
        float a2 = 0.20f + 0.08f * (float) Math.sin(2 * Math.PI * t / 9.0 + 1.3);
        // Entran poco a poco al abrir la página.
        float in = Math.min(1f, t / 1.6f);
        light(canvas, x1, y1, 190f * dp, a1 * in);
        light(canvas, x2, y2, 150f * dp, a2 * in);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        canvas.drawColor(base);
        canvas.drawRect(b, dots);
        if (ambient) drawAmbient(canvas, b);
        if (glow > 0.01f && litDots != null) {
            float r = 120f * dp;
            RadialGradient mask = new RadialGradient(gx, gy, r, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP);
            lit.setShader(new ComposeShader(mask, litDots, PorterDuff.Mode.SRC_IN));
            lit.setAlpha(Math.round(255 * glow));
            canvas.drawCircle(gx, gy, r, lit);
        }
        if (wa > 0.01f && wr > 1f) {
            // Banda de ~90 dp detrás del frente de la onda: delante, nada; en el frente, coral
            // fuerte; detrás se apaga poco a poco (como una estela).
            float band = 90f * dp, outer = wr + 6f * dp;
            float in = Math.max(0f, (wr - band) / outer), mid = wr / outer;
            RadialGradient ring = new RadialGradient(wx, wy, outer,
                    new int[]{0x00000000, 0x00000000, 0xFF000000, 0x00000000},
                    new float[]{0f, in, mid, 1f}, Shader.TileMode.CLAMP);
            wave.setShader(new ComposeShader(ring, litDots, PorterDuff.Mode.SRC_IN));
            wave.setAlpha(Math.round(255 * Math.min(1f, wa)));
            canvas.drawCircle(wx, wy, outer, wave);
        }
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
