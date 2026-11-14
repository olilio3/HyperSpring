package com.icehome.up;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.view.View;

import java.util.ArrayList;

/**
 * Título grande que entra letra a letra, como SplitText de GSAP en las webs virales: cada línea
 * es una «ventana» y las letras suben desde debajo de ella, girando un poco, una detrás de otra.
 * <p>
 * Se dibuja a mano (no es un TextView) para poder mover cada letra por separado sin crear una
 * vista por letra. Corta el texto en líneas por palabras para que quepa en el ancho.
 * <p>
 * Sin {@link #play}, se ve entero y quieto. Con las animaciones quitadas en opciones de
 * desarrollador, {@link #play} no hace nada y el título sale ya puesto.
 */
final class SplitTitle extends View {

    /** Lo que tarda cada letra en llegar y cuánto se espera entre una y la siguiente. */
    private static final long CHAR_MS = 820, STAGGER_MS = 26;

    private final TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
    private final String text;
    private final ArrayList<String> lines = new ArrayList<>();
    /** Posición x de cada letra (por línea), medida con el texto anterior para respetar el kerning. */
    private final ArrayList<float[]> xs = new ArrayList<>();
    private float lineH, ascent;
    /** Tamaño pedido (px); el real puede ser menor si el título no cabe. */
    private final float baseSize;
    private int laidW = -1;
    private boolean centered;
    private ValueAnimator anim;
    /** Milisegundos desde que empezó la entrada; negativo = aún no han salido. Grande = quietas. */
    private float t = 1e9f;

    SplitTitle(Context c, String text, float sp, int color, int weight) {
        super(c);
        this.text = text;
        baseSize = sp * c.getResources().getDisplayMetrics().scaledDensity;
        paint.setTextSize(baseSize);
        paint.setColor(color);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, weight, false));
        // Letras algo más juntas: los titulares grandes respiran mejor así.
        paint.setLetterSpacing(-0.025f);
        setContentDescription(text);
    }

    void setCentered(boolean c) {
        centered = c;
        laidW = -1;
        requestLayout();
    }

    /** Empieza la entrada a los {@code delay} ms. Hasta entonces, las letras no se ven. */
    void play(long delay) {
        if (anim != null) anim.cancel();
        if (!ValueAnimator.areAnimatorsEnabled()) {
            t = 1e9f;
            invalidate();
            return;
        }
        int chars = text.length();
        final long total = CHAR_MS + STAGGER_MS * chars;
        t = -1f;
        anim = ValueAnimator.ofFloat(0f, total);
        anim.setDuration(total);
        anim.setStartDelay(delay);
        anim.setInterpolator(null);                          // lineal: la curva va en cada letra
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                t = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) anim.end();
        super.onDetachedFromWindow();
    }

    /** Parte el texto en líneas que quepan en {@code w} (por palabras). */
    private void layout(int w) {
        if (w == laidW) return;
        laidW = w;
        lines.clear();
        xs.clear();
        String[] words = text.split(" ");
        // Si una palabra sola no cabe (letra grande del sistema, pantalla estrecha), se encoge todo
        // el título lo justo para que quepa, en vez de cortarse por los lados.
        paint.setTextSize(baseSize);
        float widest = 0f;
        for (String word : words) widest = Math.max(widest, paint.measureText(word));
        if (widest > w) paint.setTextSize(baseSize * w / widest * 0.98f);
        StringBuilder cur = new StringBuilder();
        for (String word : words) {
            String tryLine = cur.length() == 0 ? word : cur + " " + word;
            if (cur.length() > 0 && paint.measureText(tryLine) > w) {
                lines.add(cur.toString());
                cur.setLength(0);
                cur.append(word);
            } else {
                cur.setLength(0);
                cur.append(tryLine);
            }
        }
        if (cur.length() > 0) lines.add(cur.toString());
        for (String l : lines) {
            float[] x = new float[l.length() + 1];
            for (int i = 0; i <= l.length(); i++) x[i] = paint.measureText(l, 0, i);
            xs.add(x);
        }
        TextPaint.FontMetrics fm = paint.getFontMetrics();
        ascent = -fm.ascent;
        lineH = (fm.descent - fm.ascent) * 1.02f;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        layout(Math.max(1, w - getPaddingLeft() - getPaddingRight()));
        int h = Math.round(lines.size() * lineH) + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        int avail = getWidth() - getPaddingLeft() - getPaddingRight();
        float y0 = getPaddingTop();
        // Ya han llegado todas: cada línea de una vez (más rápido que letra a letra).
        if (t >= CHAR_MS + STAGGER_MS * (text.length() + lines.size())) {
            for (int li = 0; li < lines.size(); li++) {
                String l = lines.get(li);
                float left = getPaddingLeft() + (centered ? (avail - xs.get(li)[l.length()]) / 2f : 0f);
                c.drawText(l, left, y0 + li * lineH + ascent, paint);
            }
            return;
        }
        int k = 0;                                            // número de letra en todo el título
        for (int li = 0; li < lines.size(); li++) {
            String l = lines.get(li);
            float[] x = xs.get(li);
            float left = getPaddingLeft() + (centered ? (avail - x[l.length()]) / 2f : 0f);
            float top = y0 + li * lineH, base = top + ascent;
            c.save();
            // La «ventana» de la línea: lo que está por debajo no se ve (las letras salen de ahí).
            c.clipRect(0, top - lineH * 0.2f, getWidth(), top + lineH);
            for (int i = 0; i < l.length(); i++, k++) {
                char ch = l.charAt(i);
                if (ch == ' ') continue;
                float p = Motion.clamp01((t - k * STAGGER_MS) / CHAR_MS);
                if (p <= 0f) continue;
                float e = Motion.EXPO_OUT.getInterpolation(p);
                float dy = (1f - e) * lineH;
                float cx = left + (x[i] + x[i + 1]) / 2f;
                paint.setAlpha(Math.round(255 * Math.min(1f, p * 2.5f)));
                if (e < 1f) {
                    c.save();
                    c.rotate(14f * (1f - e), cx, base + dy);
                    c.drawText(l, i, i + 1, left + x[i], base + dy, paint);
                    c.restore();
                } else {
                    c.drawText(l, i, i + 1, left + x[i], base, paint);
                }
            }
            k++;                                              // el espacio entre líneas también cuenta
            c.restore();
        }
        paint.setAlpha(255);
    }
}
