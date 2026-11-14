package com.icehome.up;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.view.View;

/**
 * Número que cambia rodando, como un cuentakilómetros: cada cifra es una columna 0-9 que gira
 * hasta la cifra nueva (las webs con GSAP lo hacen con columnas que suben). Lo que no es cifra
 * (la coma, un signo) se dibuja quieto.
 * <p>
 * Cada columna persigue a su cifra objetivo un poco en cada fotograma, así que si el número cambia
 * deprisa (al arrastrar el deslizador) las columnas ruedan seguidas sin saltos. Con las
 * animaciones quitadas, cambia de golpe.
 */
final class Odometer extends View {

    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private String text = "";
    /** Cifra que se ve en cada posición (con decimales mientras rueda) y a la que va. */
    private float[] cur = new float[0];
    private int[] goal = new int[0];
    private final float digitW;
    private long last;
    private boolean moving;

    Odometer(Context c, float sp, int color) {
        super(c);
        paint.setTextSize(sp * c.getResources().getDisplayMetrics().scaledDensity);
        paint.setColor(color);
        paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        paint.setTextAlign(Paint.Align.CENTER);
        digitW = paint.measureText("0");
    }

    void setText(CharSequence s) {
        String t = s.toString();
        if (t.equals(text)) return;
        boolean same = t.length() == text.length();
        int[] g = new int[t.length()];
        float[] c = new float[t.length()];
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            g[i] = Character.isDigit(ch) ? ch - '0' : -1;
            // Misma longitud: cada columna sigue desde donde estaba. Si no, empieza ya en su sitio.
            c[i] = same && i < cur.length && g[i] >= 0 && goal[i] >= 0 ? cur[i] : Math.max(0, g[i]);
        }
        text = t;
        goal = g;
        cur = c;
        setContentDescription(t);
        if (!same) requestLayout();
        if (!ValueAnimator.areAnimatorsEnabled()) {
            for (int i = 0; i < cur.length; i++) cur[i] = Math.max(0, goal[i]);
        }
        if (!moving) {
            moving = true;
            last = 0;
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        setMeasuredDimension(Math.round(digitW * text.length()) + getPaddingLeft() + getPaddingRight(),
                Math.round(fm.descent - fm.ascent) + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = last == 0 ? 0.016f : Math.min(0.05f, (now - last) / 1e9f);
        last = now;
        Paint.FontMetrics fm = paint.getFontMetrics();
        float lineH = fm.descent - fm.ascent;
        float top = getPaddingTop(), base = top - fm.ascent;
        boolean still = true;
        c.save();
        c.clipRect(0, top, getWidth(), top + lineH);
        for (int i = 0; i < text.length(); i++) {
            float cx = getPaddingLeft() + digitW * (i + 0.5f);
            if (goal[i] < 0) {
                c.drawText(text, i, i + 1, cx, base, paint);
                continue;
            }
            // Se acerca a su cifra (rápido al principio, suave al final: como expo.out).
            float d = goal[i] - cur[i];
            if (Math.abs(d) < 0.004f) cur[i] = goal[i];
            else {
                cur[i] += d * Math.min(1f, dt * 14f);
                still = false;
            }
            int lo = (int) Math.floor(cur[i]);
            float f = cur[i] - lo;
            // La cifra de abajo sube y deja paso a la siguiente.
            c.drawText(String.valueOf(lo), cx, base - f * lineH, paint);
            if (f > 0.001f) c.drawText(String.valueOf(Math.min(9, lo + 1)), cx, base + (1f - f) * lineH, paint);
        }
        c.restore();
        if (still) moving = false;
        else postInvalidateOnAnimation();
    }
}
