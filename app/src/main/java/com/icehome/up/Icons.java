package com.icehome.up;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Iconos de línea dibujados con trazos (rejilla de 24 × 24, trazo de 1,8), sin recursos: casa,
 * escritorio, sistema, información, genio, recientes, carpeta, cristal, interruptores, telón,
 * centro de control, registro, reiniciar, flechas.
 * <p>
 * Cada icono tiene además su <b>gesto</b> (ver {@link #play}): una animación corta que cuenta lo
 * que hace su función. Siguen tres reglas de animación clásica:
 * <ul>
 * <li><b>Anticipación:</b> antes de saltar o girar, se encoge o retrocede un poco.</li>
 * <li><b>Aplastar y estirar:</b> se estira en el aire y se aplasta al caer, como si fuera blando.</li>
 * <li><b>Escalonado:</b> las partes no se mueven a la vez, sino una detrás de otra (las cuatro
 * baldosas, las líneas del menú, los rayos de la luz...). Algunas sueltan chispas o gotas.</li>
 * </ul>
 * Se anima solo: en cada fotograma se pide el siguiente con {@code scheduleSelf}, así que vale
 * igual en un ImageView que en una vista que lo dibuje a mano (si le hace de Callback).
 */
final class Icons extends Drawable {

    static final int HOME = 0, GRID = 1, PHONE = 2, INFO = 3, SPARKLE = 4, LAYERS = 5, FOLDER = 6, DROP = 7,
            TOGGLES = 8, CURTAIN = 9, SLIDERS = 10, LIST = 11, RESTART = 12, CHEVRON = 13, BACK = 14, WIDGETS = 15,
            LIGHT = 16, PALETTE = 17, CODE = 18, SHIELD = 19, DOWNLOAD = 20, GLOBE = 21, PLAY = 22;

    /**
     * Lo que dura un gesto, en ms (a velocidad de animación 1×). Con 1100 las gelatinas que
     * empiezan tarde (la casa, la gota) no habían terminado y el icono daba un saltito al cortar.
     */
    private static final float GESTURE_MS = 1400f;
    /** Deformación del gesto actual (ver {@link #gesture}). */
    private final Matrix gm = new Matrix();
    /** Tiempo «en reposo»: todas las curvas de abajo ya han llegado a su final. */
    private static final float REST = 1e6f;

    private final int kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Relleno para chispas, gotas y el destello de la captura (del mismo color que el trazo). */
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path(), part = new Path();
    private final Matrix pm = new Matrix();
    private final RectF r = new RectF();
    /** Cuándo empezó el gesto (reloj de animación), o -1 si el icono está quieto. */
    private long playStart = -1;
    private final Runnable nextFrame = new Runnable() {
        @Override
        public void run() {
            invalidateSelf();
        }
    };

    Icons(int kind, int color) {
        this.kind = kind;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
        dot.setStyle(Paint.Style.FILL);
    }

    void setColor(int c) {
        paint.setColor(c);
        invalidateSelf();
    }

    /** Hace el gesto del icono una vez (si ya lo estaba haciendo, vuelve a empezar). */
    void play() {
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return;
        playStart = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
        invalidateSelf();
    }

    /** Por dónde va el gesto, en ms, o -1 si no hay. Respeta la velocidad de animación de Android. */
    private float gestureTime() {
        if (playStart < 0) return -1f;
        float scale = android.animation.ValueAnimator.getDurationScale();
        float t = (android.view.animation.AnimationUtils.currentAnimationTimeMillis() - playStart) / (scale > 0f ? scale : 1f);
        if (t >= GESTURE_MS) {
            playStart = -1;
            return -1f;
        }
        return t;
    }

    // ---------------------------------------------------------------- curvas (t en ms)

    /** Muelle con retraso: 0 hasta {@code delay}, luego va a 1 (rebota si damping es menor que 1). */
    private static float sp(float t, float delay, float response, float damping) {
        return t <= delay ? 0f : Motion.spring((t - delay) / 1000f, response, damping);
    }

    /** Joroba: 0 → 1 → 0 entre {@code a} y {@code b} (media onda de seno). */
    private static float hump(float t, float a, float b) {
        return (float) Math.sin(Math.PI * Motion.seg(t, a, b));
    }

    /** Latido que se apaga: 0 antes de {@code delay}; crece, se pasa un poco al otro lado y se calma. */
    private static float bump(float t, float delay) {
        if (t <= delay) return 0f;
        float u = t - delay;
        return (float) (Math.exp(-u / 170f) * Math.sin(2 * Math.PI * u / 380f));
    }

    /** Gelatina tras un golpe en {@code at}: positivo = aplastado, negativo = estirado. */
    private static float jelly(float t, float at) {
        return t <= at ? 0f : IntroLogo.rubber(t - at);
    }

    /** Ida y vuelta: 0 → 1 → 0 en {@code ms} (con curva suave) y un pequeño rebote al final. */
    private static float wave(float t, float delay, float ms) {
        float u = t - delay;
        if (u <= 0f) return 0f;
        if (u < ms) return (float) Math.sin(Math.PI * Motion.EASE.getInterpolation(u / ms));
        return -0.25f * IntroLogo.rubber(u - ms);
    }

    // ---------------------------------------------------------------- gesto de todo el icono

    /**
     * Mueve el lienzo según el gesto de cada icono a los {@code t} ms. Las medidas van en la
     * rejilla de 24 (el centro es 12, 12), así que el gesto es igual en un icono grande que en uno
     * pequeño. Lo que mueve solo una parte va en {@link #draw}; las chispas, en {@link #effects}.
     */
    private void gesture(Matrix g, float t) {
        switch (kind) {
            case HOME: {
                // Se agacha, salta estirándose y al caer se aplasta como goma.
                float crouch = hump(t, 0, 150);
                g.preScale(1f + 0.12f * crouch, 1f - 0.16f * crouch, 12, 20);
                float j = Motion.seg(t, 110, 430);
                g.preTranslate(0, -6.5f * 4f * j * (1f - j));
                float air = hump(t, 110, 430);
                g.preScale(1f - 0.06f * air, 1f + 0.1f * air, 12, 20);
                float s = jelly(t, 430);
                g.preScale(1f + 0.22f * s, 1f - 0.24f * s, 12, 20);
                break;
            }
            case GRID:
                // Un cuarto de vuelta con rebote, mientras las baldosas laten una tras otra.
                g.preRotate(90f * sp(t, 60, 0.45f, 0.55f), 12, 12);
                break;
            case PHONE: {
                // Captura: la pantalla se encoge (con destello, ver draw) y luego tiembla.
                float sq = hump(t, 0, 240);
                g.preScale(1f - 0.16f * sq, 1f - 0.16f * sq, 12, 12);
                float u = t - 170f;
                if (u > 0f) g.preRotate((float) (12 * Math.exp(-u / 200f) * Math.sin(2 * Math.PI * u / 110f)), 12, 12);
                break;
            }
            case INFO: {
                // Vuelta de moneda (se estrecha hasta ser una raya y vuelve) y un saltito.
                float f = Motion.EASE.getInterpolation(Motion.clamp01(t / 520f));
                float sx = (float) Math.cos(2 * Math.PI * f);
                g.preScale(Math.abs(sx) < 0.08f ? 0.08f : sx, 1f, 12, 12);
                float r = IntroLogo.rubber(t);
                g.preScale(1f + 0.12f * r, 1f + 0.12f * r, 12, 12);
                break;
            }
            case SPARKLE: {
                // Genio: se recoge, gira media vuelta con muelle y destella (y suelta chispas).
                float pre = hump(t, 0, 160);
                float k = 1f - 0.22f * pre + 0.5f * bump(t, 120);
                g.preRotate(180f * sp(t, 90, 0.55f, 0.6f), 12, 12);
                g.preScale(k, k, 12, 12);
                break;
            }
            case LAYERS: {
                // Recientes que se doblan: la tarjeta se tuerce de un lado a otro, cada vez menos.
                float k = (float) (Math.exp(-t / 240f) * Math.sin(2 * Math.PI * t / 320f));
                g.preTranslate(12, 14);
                g.preSkew(0.3f * k, 0f);
                g.preTranslate(-12, -14);
                break;
            }
            case FOLDER: {
                // Carpeta con muelle: se agacha, da un brinco estirándose y cae como una gominola.
                float crouch = hump(t, 0, 140);
                g.preScale(1f + 0.14f * crouch, 1f - 0.16f * crouch, 12, 19.5f);
                float j = Motion.seg(t, 100, 360);
                g.preTranslate(0, -4.5f * 4f * j * (1f - j));
                float air = hump(t, 100, 360);
                g.preScale(1f - 0.06f * air, 1f + 0.08f * air, 12, 19.5f);
                float s = jelly(t, 360);
                g.preScale(1f + 0.26f * s, 1f - 0.26f * s, 12, 19.5f);
                break;
            }
            case DROP: {
                // La gota sube, cae acelerando y estirándose, y al tocar el suelo se aplasta y salpica.
                float y;
                if (t < 130f) y = -9f * Motion.POWER3_OUT.getInterpolation(t / 130f);
                else {
                    float f = Motion.seg(t, 130, 330);
                    y = -9f * (1f - f * f);
                }
                g.preTranslate(0, y);
                float fs = hump(t, 150, 340);
                g.preScale(1f - 0.1f * fs, 1f + 0.16f * fs, 12, 20.5f);
                float s = jelly(t, 330);
                g.preScale(1f + 0.3f * s, 1f - 0.3f * s, 12, 20.5f);
                break;
            }
            case LIST: {
                // Menú de atajos: se recoge hacia su esquina y sale de ella con rebote; las líneas
                // se escriben después, una a una (ver draw).
                float k = t < 100f ? 1f - 0.25f * Motion.EASE.getInterpolation(t / 100f)
                        : 0.75f + 0.25f * sp(t, 100, 0.42f, 0.45f);
                g.preScale(k, k, 4, 20.5f);
                break;
            }
            case LIGHT:
                // Los rayos giran un poco (y se alargan uno tras otro, ver draw).
                g.preRotate(45f * sp(t, 0, 0.5f, 0.6f), 12, 12);
                break;
            case RESTART: {
                // Retrocede un poco y da una vuelta entera, pasándose y volviendo.
                float a = t < 120f ? -30f * Motion.EASE.getInterpolation(t / 120f)
                        : -30f + 390f * Motion.BACK_OUT.getInterpolation(Motion.seg(t, 120, 820));
                g.preRotate(a, 12, 12);
                break;
            }
            case CURTAIN:
            case SLIDERS:
            case TOGGLES:
            case DOWNLOAD:
            case WIDGETS:
            case PALETTE:
            case CODE:
            case GLOBE:
            case PLAY:
                break;                                       // se mueven partes, al dibujar
            default: {
                float k = 1f + 0.3f * bump(t, 0);
                g.preScale(k, k, 12, 12);
                break;
            }
        }
    }

    /** Chispas y gotas, que no giran ni se escalan con el icono. */
    private void effects(Canvas c, float t) {
        switch (kind) {
            case SPARKLE:
                // Cuatro chispas salen en diagonal y se apagan.
                burst(c, t, 140, 560, 12, 12, 4, 45f, 7f, 12.5f, 1.4f, 0f);
                break;
            case DROP:
                // Al caer, dos gotitas saltan a los lados.
                splash(c, t, 330, 640, 12, 20.5f, 3f, 5.5f, 4.5f, 1.3f);
                break;
            case HOME:
                // Polvo al aterrizar.
                splash(c, t, 430, 700, 12, 20.5f, 5f, 4f, 1.5f, 1.1f);
                break;
            default:
                break;
        }
    }

    /**
     * {@code n} puntos que salen del centro (cx, cy) en abanico, desde el radio {@code r0} al
     * {@code r1}, encogiéndose y apagándose entre {@code a} y {@code b} ms.
     */
    private void burst(Canvas c, float t, float a, float b, float cx, float cy, int n, float angle0,
                       float r0, float r1, float size, float lift) {
        if (t <= a || t >= b) return;
        float f = Motion.POWER3_OUT.getInterpolation(Motion.seg(t, a, b));
        float rad = r0 + (r1 - r0) * f;
        dot.setColor(paint.getColor());
        dot.setAlpha(Math.round(paint.getAlpha() * (1f - Motion.seg(t, a, b))));
        for (int i = 0; i < n; i++) {
            double ang = Math.toRadians(angle0 + 360f / n * i);
            c.drawCircle(cx + (float) Math.cos(ang) * rad, cy + (float) Math.sin(ang) * rad - lift * f,
                    size * (1f - 0.7f * f), dot);
        }
    }

    /** Dos gotas que saltan a los lados desde (cx, cy) y caen haciendo un arco. */
    private void splash(Canvas c, float t, float a, float b, float cx, float cy, float gap, float out,
                        float up, float size) {
        if (t <= a || t >= b) return;
        float f = Motion.seg(t, a, b);
        float x = gap + out * Motion.POWER3_OUT.getInterpolation(f);
        float y = -up * 4f * f * (1f - f);
        dot.setColor(paint.getColor());
        dot.setAlpha(Math.round(paint.getAlpha() * (1f - f * f)));
        float s = size * (1f - 0.6f * f);
        c.drawCircle(cx - x, cy + y, s, dot);
        c.drawCircle(cx + x, cy + y, s, dot);
    }

    // ---------------------------------------------------------------- dibujo

    @Override
    public void draw(Canvas c) {
        android.graphics.Rect b = getBounds();
        if (b.isEmpty()) return;
        float t = gestureTime();
        // Para las partes que se mueven: en reposo, todas las curvas ya han terminado.
        float T = t >= 0f ? t : REST;
        c.save();
        c.translate(b.left, b.top);
        float s = Math.min(b.width(), b.height()) / 24f;
        c.scale(s, s);
        // El gesto (aplastar, estirar, torcer) se aplica a la forma, no al lienzo: si se deforma
        // el lienzo, el trazo se deforma con él y sale más gordo por un lado que por otro. Así el
        // trazo es siempre de 1,8, como en un icono de verdad.
        gm.reset();
        if (t >= 0f) gesture(gm, t);
        path.reset();
        switch (kind) {
            case HOME:
                path.moveTo(4, 10.5f);
                path.lineTo(12, 4);
                path.lineTo(20, 10.5f);
                path.lineTo(20, 19);
                path.quadTo(20, 20, 19, 20);
                path.lineTo(15, 20);
                path.lineTo(15, 14.5f);
                path.lineTo(9, 14.5f);
                path.lineTo(9, 20);
                path.lineTo(5, 20);
                path.quadTo(4, 20, 4, 19);
                path.close();
                break;
            case GRID: {
                // Cada baldosa late a su tiempo, en el sentido de las agujas del reloj.
                float[][] q = {{4, 4, 10.5f, 10.5f}, {13.5f, 4, 20, 10.5f}, {13.5f, 13.5f, 20, 20}, {4, 13.5f, 10.5f, 20}};
                for (int i = 0; i < 4; i++) {
                    float k = 1f + 0.45f * bump(T, 70f * i);
                    rr(part, q[i][0], q[i][1], q[i][2], q[i][3], 2.2f);
                    addPart(0, 0, 0, k, (q[i][0] + q[i][2]) / 2f, (q[i][1] + q[i][3]) / 2f);
                }
                break;
            }
            case PHONE:
                rr(path, 6.5f, 3, 17.5f, 21, 3);
                path.moveTo(10.5f, 18);
                path.lineTo(13.5f, 18);
                break;
            case INFO: {
                path.addCircle(12, 12, 8.5f, Path.Direction.CW);
                path.moveTo(12, 11);
                path.lineTo(12, 16.5f);
                // El punto da un saltito al acabar la vuelta.
                path.addCircle(12, 7.8f - 3.5f * hump(T, 420, 660), 0.4f, Path.Direction.CW);
                break;
            }
            case SPARKLE:
                path.moveTo(12, 3.5f);
                path.quadTo(13, 11, 20.5f, 12);
                path.quadTo(13, 13, 12, 20.5f);
                path.quadTo(11, 13, 3.5f, 12);
                path.quadTo(11, 11, 12, 3.5f);
                path.close();
                break;
            case LAYERS: {
                rr(path, 5, 8, 19, 20, 2.5f);
                // Las tarjetas de detrás se levantan, una después de otra, y vuelven a caer.
                float l1 = -2.2f * hump(T, 40, 330), l2 = -3.2f * hump(T, 100, 400);
                path.moveTo(7, 5.5f + l1);
                path.lineTo(17, 5.5f + l1);
                path.moveTo(9, 3 + l2);
                path.lineTo(15, 3 + l2);
                break;
            }
            case FOLDER:
                path.moveTo(3.5f, 7);
                path.quadTo(3.5f, 5, 5.5f, 5);
                path.lineTo(9.5f, 5);
                path.lineTo(11.5f, 7.5f);
                path.lineTo(18.5f, 7.5f);
                path.quadTo(20.5f, 7.5f, 20.5f, 9.5f);
                path.lineTo(20.5f, 17.5f);
                path.quadTo(20.5f, 19.5f, 18.5f, 19.5f);
                path.lineTo(5.5f, 19.5f);
                path.quadTo(3.5f, 19.5f, 3.5f, 17.5f);
                path.close();
                break;
            case DROP:
                path.moveTo(12, 3.5f);
                path.cubicTo(12, 3.5f, 5.5f, 10.5f, 5.5f, 14.5f);
                path.cubicTo(5.5f, 18.2f, 8.4f, 20.5f, 12, 20.5f);
                path.cubicTo(15.6f, 20.5f, 18.5f, 18.2f, 18.5f, 14.5f);
                path.cubicTo(18.5f, 10.5f, 12, 3.5f, 12, 3.5f);
                path.close();
                path.moveTo(9, 15);
                path.quadTo(9.2f, 17.3f, 11.5f, 17.6f);
                break;
            case TOGGLES: {
                // Los dos botones cambian de lado y vuelven, el segundo un poco después.
                rr(path, 3.5f, 5, 20.5f, 11, 3);
                path.addCircle(17.5f - 11f * wave(T, 0, 560), 8, 1.6f, Path.Direction.CW);
                rr(path, 3.5f, 13, 20.5f, 19, 3);
                path.addCircle(6.5f + 11f * wave(T, 90, 560), 16, 1.6f, Path.Direction.CW);
                break;
            }
            case CURTAIN: {
                // Las dos cortinas se juntan en el centro y se vuelven a abrir.
                rr(path, 4, 4, 20, 20, 3);
                float a = 3.2f * wave(T, 0, 520), bb = 3.2f * wave(T, 60, 520);
                path.moveTo(9 + a, 4);
                path.quadTo(8 + a, 13, 11 + a * 0.4f, 20);
                path.moveTo(15 - bb, 4);
                path.quadTo(16 - bb, 13, 13 - bb * 0.4f, 20);
                break;
            }
            case SLIDERS:
                // Los botones se deslizan en sentidos opuestos (como al ajustar el brillo), escalonados.
                path.moveTo(4, 7);
                path.lineTo(20, 7);
                path.moveTo(4, 17);
                path.lineTo(20, 17);
                path.addCircle(9 + 6f * wave(T, 0, 600), 7, 2.2f, Path.Direction.CW);
                path.addCircle(15 - 6f * wave(T, 90, 600), 17, 2.2f, Path.Direction.CW);
                break;
            case LIST: {
                rr(path, 4, 3.5f, 20, 20.5f, 2.5f);
                // Las líneas se escriben de izquierda a derecha, una tras otra.
                float[] ys = {9, 12.5f, 16}, ends = {16, 16, 12.5f};
                for (int i = 0; i < 3; i++) {
                    float k = sp(T, 140 + 70 * i, 0.32f, 0.75f);
                    if (k <= 0.01f) continue;
                    path.moveTo(8, ys[i]);
                    path.lineTo(8 + (ends[i] - 8) * k, ys[i]);
                }
                break;
            }
            case RESTART:
                r.set(4.5f, 4.5f, 19.5f, 19.5f);
                path.addArc(r, -60, 300);
                path.moveTo(15.8f, 3.6f);
                path.lineTo(16.3f, 6.6f);
                path.lineTo(13.3f, 7.2f);
                break;
            case CHEVRON:
                path.moveTo(9.5f, 6);
                path.lineTo(15.5f, 12);
                path.lineTo(9.5f, 18);
                break;
            case BACK:
                path.moveTo(14.5f, 5.5f);
                path.lineTo(8, 12);
                path.lineTo(14.5f, 18.5f);
                break;
            case WIDGETS: {
                // Se baraja: el widget de arriba sale hacia un lado girando y vuelve a la pila;
                // después los dos de abajo dan un saltito, uno tras otro.
                float h = wave(T, 0, 440);
                rr(part, 4, 4, 20, 11, 2.5f);
                addPart(7f * h, -1f * h, 12f * h, 1f, 12, 7.5f);
                rr(path, 4, 13.5f - 2.6f * hump(T, 220, 440), 11, 20 - 2.6f * hump(T, 220, 440), 2.5f);
                rr(path, 13.5f, 13.5f - 2.6f * hump(T, 310, 530), 20, 20 - 2.6f * hump(T, 310, 530), 2.5f);
                break;
            }
            case LIGHT: {
                // El núcleo late y los rayos se alargan uno detrás de otro, dando la vuelta.
                path.addCircle(12, 12, 3.5f * (1f + 0.35f * bump(T, 0)), Path.Direction.CW);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    float g = hump(T, 40 * i, 260 + 40 * i);
                    float in = 6.5f + 0.8f * g, out = 8.5f + 2.2f * g;
                    path.moveTo(12 + (float) Math.cos(a) * in, 12 + (float) Math.sin(a) * in);
                    path.lineTo(12 + (float) Math.cos(a) * out, 12 + (float) Math.sin(a) * out);
                }
                break;
            }
            case PALETTE: {
                path.addCircle(12, 12, 8.5f, Path.Direction.CW);
                // Las tres gotas de pintura laten una tras otra.
                float[][] d = {{8.5f, 10}, {12, 7.5f}, {15.5f, 10}};
                for (int i = 0; i < 3; i++) {
                    path.addCircle(d[i][0], d[i][1], 1.1f * (1f + 0.9f * bump(T, 60 + 80 * i)), Path.Direction.CW);
                }
                break;
            }
            case CODE: {
                // Los corchetes se separan y vuelven.
                float o = 2.2f * wave(T, 0, 500);
                path.moveTo(9 - o, 7);
                path.lineTo(4 - o, 12);
                path.lineTo(9 - o, 17);
                path.moveTo(15 + o, 7);
                path.lineTo(20 + o, 12);
                path.lineTo(15 + o, 17);
                break;
            }
            case SHIELD:
                path.moveTo(12, 3.5f);
                path.lineTo(19, 6.5f);
                path.lineTo(19, 11.5f);
                path.quadTo(19, 17.5f, 12, 20.5f);
                path.quadTo(5, 17.5f, 5, 11.5f);
                path.lineTo(5, 6.5f);
                path.close();
                {
                    // Una marca de «todo bien» que se escribe de un trazo después del latido.
                    float k = Motion.clamp01(sp(T, 140, 0.35f, 0.85f));
                    if (k > 0.01f) {
                        float u = Math.min(1f, k * 2f), v = Math.max(0f, k * 2f - 1f);
                        path.moveTo(8.8f, 12.2f);
                        path.lineTo(8.8f + 2f * u, 12.2f + 2f * u);
                        if (v > 0f) path.lineTo(10.8f + 4.4f * v, 14.2f - 4.6f * v);
                    }
                }
                break;
            case DOWNLOAD: {
                // La flecha baja hasta la bandeja, que se hunde un poco al recibirla.
                float fy = 3.5f * wave(T, 0, 420);
                float ty = 1.2f * hump(T, 200, 480);
                path.moveTo(12, 4 + fy);
                path.lineTo(12, 14.5f + fy);
                path.moveTo(7.5f, 10.5f + fy);
                path.lineTo(12, 15 + fy);
                path.lineTo(16.5f, 10.5f + fy);
                path.moveTo(4.5f, 15.5f + ty);
                path.lineTo(4.5f, 18.5f + ty);
                path.quadTo(4.5f, 20 + ty, 6, 20 + ty);
                path.lineTo(18, 20 + ty);
                path.quadTo(19.5f, 20 + ty, 19.5f, 18.5f + ty);
                path.lineTo(19.5f, 15.5f + ty);
                break;
            }
            case GLOBE: {
                // Idioma: el globo gira (los meridianos pasan de un lado a otro) y vuelve.
                path.addCircle(12, 12, 8.5f, Path.Direction.CW);
                path.moveTo(3.5f, 12);
                path.lineTo(20.5f, 12);
                // El meridiano es una elipse cuyo ancho sigue al seno del giro: media vuelta con
                // muelle y vuelve a quedar igual (4,2 de ancho en reposo).
                float spin = (float) (Math.PI * sp(T, 0, 0.6f, 0.7f));
                float w = Math.abs(8.5f * (float) Math.sin(0.517f + spin));
                if (w < 0.3f) {
                    path.moveTo(12, 3.5f);
                    path.lineTo(12, 20.5f);
                } else {
                    r.set(12 - w, 3.5f, 12 + w, 20.5f);
                    path.addOval(r, Path.Direction.CW);
                }
                break;
            }
            case PLAY: {
                // Ver la presentación: el triángulo se recoge y sale empujado hacia delante.
                path.addCircle(12, 12, 8.5f, Path.Direction.CW);
                float push = 2.2f * wave(T, 0, 380);
                part.moveTo(10, 8.3f);
                part.lineTo(16, 12);
                part.lineTo(10, 15.7f);
                part.close();
                addPart(push, 0, 0, 1f - 0.18f * hump(T, 0, 160), 12, 12);
                break;
            }
            default:
                break;
        }
        path.transform(gm);
        c.drawPath(path, paint);
        if (kind == PHONE && t >= 0f) {
            // Destello de la captura dentro de la pantalla.
            float a = hump(t, 60, 320);
            if (a > 0.01f) {
                dot.setColor(paint.getColor());
                dot.setAlpha(Math.round(paint.getAlpha() * 0.55f * a));
                r.set(8.4f, 4.9f, 15.6f, 16.2f);
                part.reset();
                part.addRoundRect(r, 1.6f, 1.6f, Path.Direction.CW);
                part.transform(gm);
                c.drawPath(part, dot);
                part.reset();
            }
        }
        if (t >= 0f) effects(c, t);
        c.restore();
        // Mientras dure el gesto, se pide el siguiente fotograma (por el Choreographer de la vista).
        if (playStart >= 0) scheduleSelf(nextFrame, android.os.SystemClock.uptimeMillis() + 8);
    }

    private void rr(Path p, float l, float t, float rr, float b, float rad) {
        r.set(l, t, rr, b);
        p.addRoundRect(r, rad, rad, Path.Direction.CW);
    }

    /** Mueve la parte suelta ({@link #part}): escala y gira en (px, py), desplaza, y la añade al icono. */
    private void addPart(float dx, float dy, float deg, float scale, float px, float py) {
        pm.setScale(scale, scale, px, py);
        pm.postRotate(deg, px, py);
        pm.postTranslate(dx, dy);
        part.transform(pm);
        path.addPath(part);
        part.reset();
    }

    @Override
    public void setAlpha(int a) {
        paint.setAlpha(a);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
        dot.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return -1;
    }

    @Override
    public int getIntrinsicHeight() {
        return -1;
    }
}
