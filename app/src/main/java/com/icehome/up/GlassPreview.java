package com.icehome.up;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;

import com.icehome.up.cc.GlassLook;
import com.icehome.up.cc.GlassShader;

/**
 * Vista previa del cristal líquido en la página «Cristal líquido»: un fondo de colores con tres
 * piezas de cristal encima (una tarjeta del centro de control, un botón redondo y una
 * notificación), dibujadas con el MISMO shader que el sistema (GlassLook). La notificación se puede
 * arrastrar para ver cómo se dobla lo de detrás en el borde. Cada deslizador la cambia al momento.
 *
 * Necesita Android 13 (RuntimeShader); antes solo enseña el fondo.
 */
final class GlassPreview extends View {
    final GlassLook.Params params = new GlassLook.Params();
    private final float dp;
    private Bitmap sharp, blurred;
    private float blurredFor = -1f;
    private final Paint plain = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF[] rects = {new RectF(), new RectF(), new RectF()};   // tarjeta, botón, notificación
    private final float[] radii = new float[3];
    // Shaders hechos (compilar uno cuesta): se rehacen solo si cambia un ajuste o el tamaño.
    private final RuntimeShader[] bodies = new RuntimeShader[3], lenses = new RuntimeShader[3];
    private int version, builtFor = -1;
    private Bitmap wallFor;
    private float dragDx, dragDy;
    private boolean dragging, broken;

    GlassPreview(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
    }

    /** Un ajuste cambió (también mientras se arrastra el deslizador). */
    void set(String key, float v) {
        if (params.set(key, v)) {
            version++;
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, Math.round(w * 0.62f));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w <= 0 || h <= 0) return;
        sharp = backdrop(w, h);
        blurredFor = -1f;
        version++;
        rects[0].set(w * 0.06f, h * 0.1f, w * 0.52f, h * 0.56f);
        radii[0] = 22 * dp;
        float br = h * 0.13f;
        rects[1].set(w * 0.64f, h * 0.14f, w * 0.64f + 2 * br, h * 0.14f + 2 * br);
        radii[1] = br;
        rects[2].set(w * 0.08f, h * 0.66f, w * 0.92f, h * 0.9f);
        radii[2] = 20 * dp;
    }

    /** Fondo de la vista previa: 0 = la flor (foto), 1 = red de líneas rectas. */
    int scene;

    void setScene(int s) {
        if (scene == s) return;
        scene = s;
        if (getWidth() > 0) {
            sharp = backdrop(getWidth(), getHeight());
            blurred = null;
            invalidate();
        }
    }

    private Bitmap backdrop(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        if (scene == 0) {
            // La flor, recortada para que llene la vista (la foto es vertical: se coge su centro).
            int id = getResources().getIdentifier("glass_flower", "drawable", getContext().getPackageName());
            Bitmap photo = id == 0 ? null : android.graphics.BitmapFactory.decodeResource(getResources(), id);
            if (photo != null) {
                float s = Math.max(w / (float) photo.getWidth(), h / (float) photo.getHeight());
                float pw = w / s, ph = h / s;
                float cx = photo.getWidth() / 2f, cy = photo.getHeight() * 0.42f;   // la flor está algo arriba
                float l = Math.max(0, Math.min(photo.getWidth() - pw, cx - pw / 2f));
                float t = Math.max(0, Math.min(photo.getHeight() - ph, cy - ph / 2f));
                android.graphics.Rect src = new android.graphics.Rect(Math.round(l), Math.round(t), Math.round(l + pw), Math.round(t + ph));
                c.drawBitmap(photo, src, new RectF(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG));
                photo.recycle();
                return b;
            }
        }
        // Red: líneas rectas verticales y horizontales; en ellas se ve muy bien cómo dobla el borde.
        c.drawColor(0xFF101418);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStrokeWidth(1.5f * dp);
        float step = 18 * dp;
        int i = 0;
        for (float x = step / 2; x < w; x += step, i++) {
            line.setColor(i % 4 == 0 ? 0xFFFF6A3D : 0xB3FFFFFF);       // una de cada cuatro, de color
            c.drawLine(x, 0, x, h, line);
        }
        i = 0;
        for (float y = step / 2; y < h; y += step, i++) {
            line.setColor(i % 4 == 0 ? 0xFF3DB8FF : 0xB3FFFFFF);
            c.drawLine(0, y, w, y, line);
        }
        return b;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (sharp == null) return;
        c.drawBitmap(sharp, 0, 0, plain);
        if (Build.VERSION.SDK_INT < 33 || broken) return;
        try {
            // El fondo desenfocado como lo hace el sistema: copia reducida (un píxel cada 1,5 dp).
            float sample = 1.5f * dp, k = 1f / sample;          // px del fondo por px de pantalla
            if (blurred == null || blurredFor != params.blur) {
                blurred = GlassShader.shrinkBlur(sharp, 1f, sample, params.blur * dp);
                blurredFor = params.blur;
            }
            boolean rebuild = builtFor != version || wallFor != blurred;
            for (int i = 0; i < 3; i++) {
                RectF r = rects[i];
                if (rebuild) {
                    bodies[i] = GlassLook.body(params, r.width(), r.height(), radii[i], dp);
                    lenses[i] = GlassLook.lens(params, r.width(), r.height(), radii[i], dp);
                    GlassLook.place(bodies[i], blurred, 0, 0, k, k);
                    GlassLook.place(lenses[i], blurred, 0, 0, k, k);
                }
                GlassLook.move(bodies[i], r.left * k, r.top * k, k, k);
                GlassLook.move(lenses[i], r.left * k, r.top * k, k, k);
                piece(c, r, radii[i], bodies[i], lenses[i]);
            }
            builtFor = version;
            wallFor = blurred;
        } catch (Throwable t) {
            broken = true;                                      // sin shader: se queda el fondo
            android.util.Log.w("HyperSpring", "vista previa del cristal", t);
        }
    }

    private void piece(Canvas c, RectF r, float radius, RuntimeShader body, RuntimeShader lens) {
        float w = r.width(), h = r.height(), rr = Math.min(radius, Math.min(w, h) / 2f);
        int save = c.save();
        c.translate(r.left, r.top);
        glass.setShader(body);
        c.drawRect(0, 0, w, h, glass);
        // Brillo "glossy" de arriba, como en el centro de control.
        int a = Math.round(Math.max(0f, Math.min(255f, params.gloss)));
        gloss.setShader(new LinearGradient(0, 0, 0, h * 0.55f, (a << 24) | 0xFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(0, 0, w, h, rr, rr, gloss);
        glass.setShader(lens);
        c.drawRect(0, 0, w, h, glass);
        c.restoreToCount(save);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        RectF n = rects[2];
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!n.contains(e.getX(), e.getY())) return false;
                dragging = true;
                dragDx = e.getX() - n.left;
                dragDy = e.getY() - n.top;
                getParent().requestDisallowInterceptTouchEvent(true);   // que no se mueva la página
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return false;
                float w = n.width(), h = n.height();
                float x = Math.max(-w * 0.3f, Math.min(getWidth() - w * 0.7f, e.getX() - dragDx));
                float y = Math.max(-h * 0.3f, Math.min(getHeight() - h * 0.7f, e.getY() - dragDy));
                n.set(x, y, x + w, y + h);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;
        }
        return super.onTouchEvent(e);
    }
}
