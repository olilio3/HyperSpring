package com.icehome.up;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.view.View;

/**
 * Cinta de texto enorme que pasa sin fin de lado a lado, la «marquee» de las webs virales hechas
 * con GSAP. Las palabras se alternan: una rellena de blanco y otra solo con el contorno, separadas
 * por un rombo del color de acento.
 * <p>
 * Como en esas webs, el desplazamiento de la página la empuja: si bajas, acelera; si subes, va
 * hacia el otro lado un momento; y luego vuelve sola a su paso tranquilo ({@link #push}).
 * <p>
 * Solo gasta fotogramas mientras está en pantalla, y con las animaciones quitadas se queda quieta.
 */
final class Marquee extends View {

    private final TextPaint fill = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint stroke = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String[] words;
    private final float[] widths;
    private final float gap, dp;
    private float period;
    /** Desplazamiento actual (px) y velocidad (px/s). La velocidad tiende a la de reposo. */
    private float offset, speed;
    private final float idle;
    private long last;
    private boolean running;

    Marquee(Context c, String[] words, int accent) {
        super(c);
        this.words = words;
        dp = c.getResources().getDisplayMetrics().density;
        // En dp, no en sp: es decoración, y con la letra del sistema al 125 % ocupaba media pantalla.
        float size = 48 * c.getResources().getDisplayMetrics().density;
        Typeface tf = Typeface.create(Typeface.DEFAULT, 800, false);
        fill.setTypeface(tf);
        fill.setTextSize(size);
        fill.setColor(0xFFF5F5F7);
        fill.setLetterSpacing(-0.03f);
        stroke.set(fill);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.2f * dp);
        stroke.setColor(0x80FFFFFF);
        dot.setColor(accent);
        gap = 26 * dp;
        widths = new float[words.length];
        for (int i = 0; i < words.length; i++) {
            widths[i] = fill.measureText(words[i]);
            period += widths[i] + gap * 2;
        }
        idle = 42 * dp;                                       // paso tranquilo: 42 dp por segundo
        speed = idle;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // es decoración
    }

    /** Empujón del desplazamiento de la página ({@code dy} en px: positivo = bajando). */
    void push(int dy) {
        speed += dy * 9f;
        float max = idle * 22f;
        speed = Math.max(-max, Math.min(max, speed));
        if (running) postInvalidateOnAnimation();
    }

    @Override
    protected void onMeasure(int w, int h) {
        Paint.FontMetrics fm = fill.getFontMetrics();
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round(fm.descent - fm.ascent + 8 * dp));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = ValueAnimator.areAnimatorsEnabled();
        last = 0;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        if (running && last != 0) {
            float dt = Math.min(0.05f, (now - last) / 1e9f);
            // La velocidad vuelve a la de reposo poco a poco (como el «inercia» de GSAP).
            speed += (idle - speed) * Math.min(1f, dt * 3.2f);
            offset = (offset + speed * dt) % period;
            if (offset < 0) offset += period;
        }
        last = now;
        Paint.FontMetrics fm = fill.getFontMetrics();
        float base = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
        float x = -offset;
        int i = 0;
        // Se repiten las palabras hasta tapar todo el ancho.
        while (x < getWidth()) {
            int k = i % words.length;
            if (x + widths[k] > 0) c.drawText(words[k], x, base, k % 2 == 0 ? fill : stroke);
            x += widths[k] + gap;
            float cy = getHeight() / 2f, r = 7 * dp;
            // Rombo de acento entre palabra y palabra.
            c.save();
            c.rotate(45f, x, cy);
            c.drawRect(x - r / 2f, cy - r / 2f, x + r / 2f, cy + r / 2f, dot);
            c.restore();
            x += gap;
            i++;
        }
        if (running) postInvalidateOnAnimation();
    }
}
