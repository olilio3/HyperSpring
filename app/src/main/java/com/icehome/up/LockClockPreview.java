package com.icehome.up;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import com.icehome.up.cc.GlassShader;
import com.icehome.up.cc.LockClock;

/**
 * Vista previa del reloj de cristal en la app: un reloj de ejemplo sobre la foto de la flor, con
 * el mismo cristal que en la pantalla de bloqueo ({@link LockClock#render}). Los deslizadores de
 * luz, refracción y opacidad la cambian al momento.
 *
 * <p>Es un ejemplo: en el móvil el molde es el reloj que tengas en HyperOS, y la app no puede leer
 * tu fondo de bloqueo sin permisos del sistema.
 */
final class LockClockPreview extends View {
    private final float dp;
    private float glass = 1f;
    private Bitmap sharp, blurred;
    private Bitmap baked;
    private int bakedX, bakedY;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

    LockClockPreview(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
    }

    void set(String key, float v) {
        if ("telon_lockclock_glass".equals(key)) glass = v;
        // El resto los lee LockClock directamente (en la app no hay SystemUI que los pise).
        else if ("telon_lockclock_edge".equals(key)) LockClock.edge = v;
        else if ("telon_lockclock_refr".equals(key)) LockClock.refr = v;
        else if ("telon_lockclock_opacity".equals(key)) LockClock.opacity = v;
        else return;
        bake();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round(330 * dp));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w == 0 || h == 0) return;
        sharp = flower(w, h);
        blurred = GlassShader.shrinkBlur(sharp, 1f, 1.5f * dp, 2.5f * dp);
        bake();
    }

    /** La foto de la flor recortada al tamaño de la vista (como en la vista previa del cristal). */
    private Bitmap flower(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int id = getResources().getIdentifier("glass_flower", "drawable", getContext().getPackageName());
        Bitmap photo = id == 0 ? null : android.graphics.BitmapFactory.decodeResource(getResources(), id);
        if (photo == null) {
            c.drawColor(0xFF2A3F8F);
            return b;
        }
        float s = Math.max(w / (float) photo.getWidth(), h / (float) photo.getHeight());
        float pw = w / s, ph = h / s;
        float l = Math.max(0, (photo.getWidth() - pw) / 2f), t = Math.max(0, (photo.getHeight() - ph) * 0.3f);
        c.drawBitmap(photo, new Rect(Math.round(l), Math.round(t), Math.round(l + pw), Math.round(t + ph)),
                new RectF(0, 0, w, h), paint);
        photo.recycle();
        return b;
    }

    /** Molde de ejemplo: fecha pequeña y hora grande, como un reloj de HyperOS. */
    private Bitmap mask(int w, int h) {
        Bitmap m = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(m);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, 600, false));
        p.setTextSize(15 * dp);
        c.drawText(I18n.t("Jueves, 26 de noviembre", "Thursday, 26 November"), w / 2f, 34 * dp, p);
        p.setTextSize(78 * dp);
        c.drawText("12:34", w / 2f, 112 * dp, p);
        return m;
    }

    private boolean busy, dirty;

    /**
     * Hace el cristal en otro hilo. Uno cada vez: si se mueve un deslizador mientras tanto, al
     * acabar se hace otra con el último valor (arrastrar no amontona hilos ni memoria).
     */
    private void bake() {
        final int w = getWidth(), h = getHeight();
        if (w == 0 || blurred == null) return;
        if (busy) {
            dirty = true;
            return;
        }
        busy = true;
        dirty = false;
        final Bitmap bd = blurred;
        final float gl = glass;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int[] at = new int[2];
                Bitmap made = null;
                try {
                    made = LockClock.render(mask(w, h), bd, 1f / (1.5f * dp), 0, 0, dp, gl, at);
                } catch (Throwable t) {                     // sin vista previa, pero la app sigue
                    Log.e("vista previa del reloj", t);
                }
                final Bitmap out = made;
                post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        if (out != null) {
                            baked = out;
                            bakedX = at[0];
                            bakedY = at[1];
                            invalidate();
                        }
                        if (dirty) bake();
                    }
                });
            }
        }, "HyperSpring-clockpreview").start();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (sharp != null) c.drawBitmap(sharp, 0, 0, paint);
        if (baked != null) c.drawBitmap(baked, bakedX, bakedY, paint);
    }
}
