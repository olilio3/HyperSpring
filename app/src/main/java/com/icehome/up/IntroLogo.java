package com.icehome.up;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * La presentación de la app, como el tráiler de una película. Diseño del usuario (14-11-2026):
 * <ol>
 *   <li><b>Barrido:</b> «Hyp» + 40 «e» + «rSpring» en una sola línea. La cámara la recorre de la
 *   «H» a «Spring» con la velocidad del dibujo del usuario: x0,5 al arrancar, x3 enseguida, x2,5
 *   hacia los dos tercios y x1 al llegar. Solo lleva estela de movimiento (el usuario quitó la
 *   separación de colores y las rayas de velocidad).</li>
 *   <li><b>Se juntan:</b> las 40 «e» chocan en una y queda «HyperSpring»; «Spring» se vuelve
 *   coral. Con el choque, la palabra se aplasta y rebota como goma.</li>
 *   <li><b>Impacto:</b> cae la gota coral, la palabra se encoge un poco antes del golpe y al
 *   chocar sale despedida en letras sueltas, con fogonazo, temblor y vibración.</li>
 *   <li>La gota se convierte en el icono: crecen las patas de la H, se dibuja el muelle, salen
 *   aros de luz y un brillo cruza el icono.</li>
 * </ol>
 * Después {@link #flyTo} lo lleva con un muelle a su sitio en la pantalla de bienvenida.
 * <p>
 * Ocupa toda la pantalla. El icono se dibuja en la rejilla de 512 ({@link LogoDrawable}) escalando
 * el lienzo; las letras, en "unidades de diseño" (una pantalla de 300 de ancho, como en la vista
 * previa), para que se vea igual en cualquier móvil.
 */
final class IntroLogo extends View {

    /** Duración de la construcción del icono (desde que cae la gota), en ms. */
    static final int BUILD_MS = 1900;
    /** Barrido + letras que se juntan + goma, antes de la gota, en ms. */
    static final int SWEEP_MS = 3450;
    /** Todo: barrido + construcción. */
    static final int TOTAL_MS = SWEEP_MS + BUILD_MS;

    // ---------------------------------------------------------------- la palabra
    private static final int E_COUNT = 40;
    /** «Hyp» + 40 «e» + «rSpring». La primera «e» (índice 3) es la que se queda al juntarse. */
    private static final String WORD;
    private static final String COMPACT = "HyperSpring";

    static {
        StringBuilder sb = new StringBuilder("Hyp");
        for (int i = 0; i < E_COUNT; i++) sb.append('e');
        WORD = sb.append("rSpring").toString();
    }

    private static final int E_FIRST = 3, E_LAST = 3 + E_COUNT - 1;
    /**
     * Momentos del barrido (ms): letras que entran, cámara, letras que se juntan y, al formarse
     * «HyperSpring», la palabra se aplasta y rebota como goma hasta SWEEP_MS.
     */
    private static final float LETTERS_MS = 400f, PAN_MS = 1700f, PAN_END = LETTERS_MS + PAN_MS,
            GATHER_END = PAN_END + 550f;
    /** Zoom de la cámara durante el recorrido (1 = la palabra final). */
    private static final float PAN_ZOOM = 2.6f;
    /** Momento del golpe de la gota contra la palabra, desde que empieza a caer (ms). */
    private static final float IMPACT_MS = 245f;

    // ---------------------------------------------------------------- velocidad de la cámara
    /** Puntos del dibujo: {posición en el recorrido, velocidad}. Entre ellos, curva suave. */
    private static final float[][] SPEED = {{0f, 0.5f}, {0.12f, 3f}, {0.65f, 2.5f}, {1f, 1f}};
    private static final float PEAK = 3f;
    private static final int STEPS = 400;
    /**
     * Tiempo acumulado para llegar a cada punto del recorrido. Como la velocidad depende de la
     * posición (no del tiempo), se integra una vez aquí y luego se busca al revés.
     */
    private static final float[] TIME_AT = new float[STEPS + 1];

    static {
        for (int i = 1; i <= STEPS; i++) TIME_AT[i] = TIME_AT[i - 1] + 1f / speed((i - 0.5f) / STEPS) / STEPS;
    }

    // ---------------------------------------------------------------- efectos
    /** Desenfoque de movimiento: la escena se dibuja varias veces, cada una un poco antes en el tiempo. */
    private static final int SAMPLES = 8, SAMPLES_FAST = 16;
    private static final float SHUTTER_MS = 24f;
    /** Para la explosión de letras: dirección, subida y giro de cada letra (fijos, no al azar en cada fotograma). */
    private final float[] blastX = new float[COMPACT.length()], blastUp = new float[COMPACT.length()],
            blastSpin = new float[COMPACT.length()];

    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG), flare = new Paint(), flash = new Paint();
    private final float[] letterX = new float[WORD.length()], compactX = new float[COMPACT.length()];
    private final float camStart, camEnd;

    // ---------------------------------------------------------------- icono
    private final float dp;
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG), white = new Paint(Paint.ANTI_ALIAS_FLAG),
            coil = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG),
            shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path coilPath = new Path(), coilPart = new Path(), clip = new Path();
    private final PathMeasure coilMeasure;
    private final float coilLen;

    /** Milisegundos desde el principio del barrido (TOTAL_MS = terminado). */
    private float t;
    /** Centro y tamaño del logo cuando está en reposo (antes y después de volar). */
    private float cx = -1, cy, size;
    private ValueAnimator clock, fly;
    private boolean thumped;

    IntroLogo(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        // Cuerpo liso del color de acento (sin degradado).
        body.setColor(Motion.ACCENT);
        // Sombra de color debajo, como si el icono flotara sobre el brillo del fondo.
        body.setShadowLayer(26 * dp, 0, 12 * dp, 0x66F0604D);
        white.setColor(0xFFFFFFFF);
        coil.setColor(0xFFFFFFFF);
        coil.setStyle(Paint.Style.STROKE);
        coil.setStrokeCap(Paint.Cap.ROUND);
        coil.setStrokeJoin(Paint.Join.ROUND);
        coil.setStrokeWidth(22);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0xFFFFFFFF);
        float[] pts = {196, 256, 208, 224, 228, 288, 248, 224, 268, 288, 288, 224, 304, 256, 316, 256};
        coilPath.moveTo(pts[0], pts[1]);
        for (int i = 2; i < pts.length; i += 2) coilPath.lineTo(pts[i], pts[i + 1]);
        coilMeasure = new PathMeasure(coilPath, false);
        coilLen = coilMeasure.getLength();

        // La letra de Apple (San Francisco) no se puede incluir por su licencia: la del sistema en
        // negrita, un poco apretada, es lo más parecido.
        glyph.setTypeface(Typeface.create(Typeface.create("sans-serif", Typeface.BOLD), 700, false));
        glyph.setTextSize(40f);
        glyph.setTextAlign(Paint.Align.CENTER);
        // Suma de luz (como "lighter" en la web): las 8 copias del desenfoque, cada una a 1/8,
        // dan blanco entero cuando la letra está quieta y una estela cuando se mueve. Con la
        // mezcla normal se quedaría gris.
        glyph.setBlendMode(BlendMode.PLUS);
        flare.setBlendMode(BlendMode.PLUS);
        flash.setColor(0xFFFFFFFF);

        float acc = 0f;                                     // la palabra larga empieza en 0
        for (int k = 0; k < WORD.length(); k++) {
            float w = glyph.measureText(WORD, k, k + 1);
            letterX[k] = acc + w / 2f;
            acc += w;
        }
        float total = 0f;
        float[] cw = new float[COMPACT.length()];
        for (int k = 0; k < cw.length; k++) total += cw[k] = glyph.measureText(COMPACT, k, k + 1);
        acc = -total / 2f;                                  // la palabra final, centrada en 0
        for (int k = 0; k < cw.length; k++) {
            compactX[k] = acc + cw[k] / 2f;
            acc += cw[k];
        }
        camStart = letterX[0] + 40f;
        camEnd = letterX[WORD.length() - 4];                // centrado en «Spring»

        // Valores fijos "al azar" (siempre los mismos, así la intro es idéntica cada vez).
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < blastX.length; i++) {
            blastX[i] = 0.7f + rnd.nextFloat() * 0.8f;
            blastUp[i] = 0.4f + rnd.nextFloat();
            blastSpin[i] = (rnd.nextFloat() - 0.5f) * 2f;
        }
        setLayerType(LAYER_TYPE_NONE, null);
    }

    /**
     * Empieza la presentación; el icono acabará en el centro de la vista con el tamaño dado (en px).
     * {@code almostDone} se llama una vez, 260 ms (de la intro) antes de acabar, o al momento si
     * las animaciones están quitadas.
     */
    void play(float sizePx, final Runnable almostDone) {
        size = sizePx;
        final boolean[] fired = {false};
        cx = -1;                                             // se coloca en el centro al dibujar
        t = 0;
        thumped = false;
        if (clock != null) clock.cancel();
        clock = ValueAnimator.ofFloat(0f, TOTAL_MS);
        clock.setDuration(TOTAL_MS);
        clock.setInterpolator(new LinearInterpolator());
        clock.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                t = (Float) a.getAnimatedValue();
                invalidate();
                if (!fired[0] && t >= TOTAL_MS - 260) {
                    fired[0] = true;
                    post(almostDone);
                }
            }
        });
        clock.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator an) {
                if (!fired[0]) {                            // sin animaciones o cancelada
                    fired[0] = true;
                    post(almostDone);
                }
            }
        });
        clock.start();
    }

    /** Pone el logo ya construido en un sitio, sin animar. */
    void place(float x, float y, float sizePx) {
        if (clock != null) clock.cancel();
        if (fly != null) fly.cancel();
        t = TOTAL_MS;
        cx = x;
        cy = y;
        size = sizePx;
        invalidate();
    }

    /** Lleva el logo con un muelle a su sitio (centro x, y y tamaño, en px de esta vista). */
    void flyTo(final float x, final float y, final float sizePx) {
        if (cx < 0) {
            cx = getWidth() / 2f;
            cy = getHeight() / 2f;
        }
        final float x0 = cx, y0 = cy, s0 = size;
        if (fly != null) fly.cancel();
        fly = ValueAnimator.ofFloat(0f, 1f);
        Spring.apply(fly, 0f, 1f, 0f, 0.62f, 0.74f);
        fly.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float f = (Float) a.getAnimatedValue();
                cx = Motion.lerp(x0, x, f);
                cy = Motion.lerp(y0, y, f);
                size = Motion.lerp(s0, sizePx, f);
                invalidate();
            }
        });
        fly.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (clock != null) clock.cancel();
        if (fly != null) fly.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (size <= 0) return;
        if (cx < 0) {
            cx = getWidth() / 2f;
            cy = getHeight() / 2f;
        }
        if (this.t < SWEEP_MS) {
            drawSweep(c, this.t);
            return;
        }
        // Desde aquí, el tiempo cuenta desde que empieza a caer la gota.
        float t = this.t - SWEEP_MS;
        float sec = t / 1000f;
        float midX = getWidth() / 2f, midY = getHeight() / 2f, u = getWidth() / 300f;

        // Temblor de cámara al golpe: fuerte y muy corto.
        float hit = t - IMPACT_MS;
        float shake = hit > 0f && hit < 500f ? 7f * dp * (float) (Math.exp(-hit / 70f) * Math.sin(hit / 16f)) : 0f;
        c.save();
        c.translate(shake * 0.4f, shake);

        // 1. Caída: de encima de la pantalla al centro con un muelle que rebota.
        float fall = Motion.spring(sec, 0.56f, 0.6f);
        float startY = -size;
        float y = Motion.lerp(startY, cy, fall);
        // Velocidad (px por ms) mirando un poco atrás: con ella se estira la gota.
        float prev = Motion.lerp(startY, cy, Motion.spring(sec - 0.012f, 0.56f, 0.6f));
        float v = Math.abs(y - prev) / 12f;
        float stretch = t >= BUILD_MS ? 1f : 1f + Math.min(0.42f, v * 0.11f);

        // 2. De gota a icono: crece y las esquinas pasan de círculo al redondeo del icono.
        float morph = Motion.spring(sec - 0.42f, 0.5f, 0.68f);
        float ball = 0.58f;                                 // la gota mide el 58 % del icono
        float k = Motion.lerp(ball, 1f, morph);             // tamaño relativo (con algo de rebote)
        float corner = Motion.lerp(256f, 512f * 0.27f, Math.max(0f, Math.min(1f, morph)));

        // 3. Las patas de la H y el muelle.
        float legs = Motion.spring(sec - 0.78f, 0.42f, 0.55f);
        float coilP = Motion.EXPO_OUT.getInterpolation(Motion.seg(t, 980, 1560));

        // La palabra: se encoge un poco esperando el golpe y luego sale despedida.
        if (hit < 700f) drawBlast(c, midX, midY, u, fall, hit);

        float s = size * k / 512f;
        c.save();
        c.translate(cx, y);
        c.scale(s / stretch, s * stretch);
        c.translate(-256, -256);
        r.set(0, 0, 512, 512);
        c.drawRoundRect(r, corner, corner, body);

        if (legs > 0.001f) {
            white.setAlpha(Math.round(255 * Math.min(1f, legs * 2.2f)));
            float up = (256 - 132) * legs, down = (380 - 256) * legs;
            r.set(136, 256 - up, 196, 256 + down);
            c.drawRoundRect(r, 30, 30, white);
            r.set(316, 256 - up, 376, 256 + down);
            c.drawRoundRect(r, 30, 30, white);
        }
        if (coilP > 0.001f) {
            coilPart.reset();
            coilMeasure.getSegment(0, coilLen * coilP, coilPart, true);
            c.drawPath(coilPart, coil);
        }

        // 4b. Brillo que cruza el icono en diagonal, recortado a su forma.
        float sh = Motion.seg(t, 1250, 1900);
        if (sh > 0f && sh < 1f) {
            float pos = Motion.lerp(-400f, 900f, Motion.EASE.getInterpolation(sh));
            shine.setShader(new LinearGradient(pos - 140, pos - 140, pos + 140, pos + 140,
                    new int[]{0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            clip.reset();
            r.set(0, 0, 512, 512);
            clip.addRoundRect(r, corner, corner, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawRect(r, shine);
            c.restore();
        }
        c.restore();

        // 4a. Dos aros de luz que salen del icono al terminar de formarse.
        for (int i = 0; i < 2; i++) {
            float q = Motion.seg(t, 1080 + i * 140, 1840 + i * 140);
            if (q <= 0f || q >= 1f) continue;
            float e = Motion.EXPO_OUT.getInterpolation(q);
            ring.setStrokeWidth((2.2f - i * 0.8f) * dp);
            ring.setAlpha(Math.round(150 * (1 - q) * (1 - q)));
            c.drawCircle(cx, cy, size * (0.55f + e * 1.25f), ring);
        }
        c.restore();

        // Golpe: vibración, fogonazo y destello horizontal. El de «se juntan» se apaga aquí.
        if (hit >= 0f && !thumped) {
            thumped = true;
            if (clock != null && clock.isRunning()) performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        }
        if (hit > 0f && hit < 400f) {
            flash.setAlpha(Math.round(255 * 0.2f * (float) Math.exp(-hit / 90f)));
            c.drawRect(0, 0, getWidth(), getHeight(), flash);
        }
        if (hit > 0f && hit < 700f) drawFlare(c, midX, midY, hit / 700f, 1f, 1.4f);
        if (this.t < TOTAL_MS && (clock == null || !clock.isRunning())) this.t = TOTAL_MS;
    }

    // ------------------------------------------------------------------ barrido

    /** Dibuja el barrido con desenfoque de movimiento: varias copias de la escena un poco antes. */
    private void drawSweep(Canvas c, float t) {
        if (clock == null || !clock.isRunning()) {          // sin animaciones: directo al final
            this.t = TOTAL_MS;
            invalidate();
            return;
        }
        float u = getWidth() / 300f;
        // A toda velocidad, más copias: con 8 se veían las letras repetidas a saltos en vez de una estela.
        float v = t >= LETTERS_MS && t < PAN_END ? speed(travelAt(t - LETTERS_MS)) : 0f;
        int n = v > 1.2f ? SAMPLES_FAST : SAMPLES;
        for (int s = 0; s < n; s++) sweepFrame(c, Math.max(0f, t - s * SHUTTER_MS / n), u, 1f / n);
    }

    /** Un instante del barrido, con la opacidad {@code a} (1/SAMPLES por copia). */
    private void sweepFrame(Canvas c, float t, float u, float a) {
        float s = travelAt(t - LETTERS_MS);
        float v = t < LETTERS_MS ? SPEED[0][1] : t < PAN_END ? speed(s) : 1f;
        float fast = Motion.clamp01((v - SPEED[0][1]) / (PEAK - SPEED[0][1]));   // 0 parada, 1 a tope
        float camX = camStart + (camEnd - camStart) * s;
        float zoom = PAN_ZOOM - 0.5f * fast;                // a toda velocidad se aleja un poco
        float gather = 0f, merge = 0f;
        if (t >= PAN_END) {                                 // las «e» se juntan y la cámara se aleja
            float p = Motion.seg(t, PAN_END, GATHER_END);
            gather = inOut3(p);                             // cámara: suave
            merge = p * p;                                  // letras: aceleran y chocan de golpe
            camX *= 1f - gather;
            zoom += (1f - zoom) * gather;
        }
        // Goma: al chocar las letras, la palabra se aplasta (más ancha y más baja), se estira al
        // revés y rebota unas veces hasta quedarse quieta. Es un muelle amortiguado: seno que se apaga.
        float jt = t - GATHER_END;
        float wide = 1f + 0.14f * rubber(jt);               // la palabra entera se ensancha al aplastarse
        float w = getWidth(), h = getHeight();

        c.save();
        c.translate(w / 2f, h / 2f);
        c.scale(zoom * u, zoom * u);
        float half = w / 2f / (zoom * u) + 40f;             // letras fuera de pantalla: no se dibujan
        for (int i = 0; i < WORD.length(); i++) {
            float x = letterX[i] - camX;
            if (merge > 0f) x = Motion.lerp(letterX[i], target(i) + camX, merge) - camX;
            else if (Math.abs(x) > half) continue;
            float alpha = 1f, dy = 0f;
            if (t < LETTERS_MS + 400f) {                    // entrada: suben una detrás de otra, deprisa
                float d = Motion.seg(t, i * 16f, i * 16f + 320f);
                if (d <= 0f) continue;
                float rr = 1f - (1f - d) * (1f - d) * (1f - d);
                alpha = rr;
                dy = 20f * (1f - rr);
            }
            if (i > E_FIRST && i <= E_LAST) alpha *= 1f - Motion.clamp01(merge * 1.4f);
            if (alpha <= 0f) continue;
            String ch = WORD.substring(i, i + 1);
            // La onda de la goma sale del centro: las letras de los lados rebotan un poco después.
            float li = rubber(jt - Math.abs(x) * 0.6f);
            c.save();
            c.translate(x * wide, 14f + dy);
            c.scale(1f + 0.3f * li, 1f - 0.36f * li);      // anclada a la línea base
            if (i >= WORD.length() - 6 && gather > 0f) {
                // «Spring» pasa a coral: con la suma de luz, blanco·(1-m) + coral·m es la mezcla exacta.
                letter(c, ch, 0xFFFFFFFF, a * alpha * (1f - gather), 0f, 0f);
                letter(c, ch, Motion.ACCENT, a * alpha * gather, 0f, 0f);
            } else {
                letter(c, ch, 0xFFFFFFFF, a * alpha, 0f, 0f);
            }
            c.restore();
        }
        c.restore();
    }

    /**
     * Rebote de goma a los {@code ms} del choque: 0 antes; luego sube (aplastada), baja (estirada)
     * y se apaga. Positivo = aplastada.
     */
    static float rubber(float ms) {
        if (ms <= 0f) return 0f;
        return (float) (Math.exp(-ms / 200f) * Math.sin(2 * Math.PI * ms / 340f));
    }

    /**
     * La palabra ya junta, mientras cae la gota. Antes del golpe se aplasta un poco (como si
     * esperara el impacto); después cada letra sale despedida hacia su lado, gira, cae y se apaga.
     */
    private void drawBlast(Canvas c, float midX, float midY, float u, float fall, float hit) {
        float q = hit > 0f ? Motion.seg(hit, 0f, 650f) : 0f;
        if (q >= 1f) return;
        float e = Motion.EXPO_OUT.getInterpolation(q);
        float squash = hit < 0f ? 0.14f * fall * fall * fall : 0f;
        c.save();
        c.translate(midX, midY);
        c.scale(u, u);
        for (int i = 0; i < COMPACT.length(); i++) {
            float x = compactX[i], side = x < 0f ? -1f : 1f;
            float dx = side * (50f + Math.abs(x) * 1.4f) * blastX[i] * e;
            float dy = -70f * blastUp[i] * e + 160f * q * q;           // sube y luego cae
            float alpha = (float) Math.pow(1f - q, 1.5f);
            c.save();
            c.translate(x + dx, 14f + dy);
            c.rotate(140f * blastSpin[i] * e);
            c.scale(1f + squash * 0.5f, 1f - squash);                  // anclado a la línea base
            letter(c, COMPACT.substring(i, i + 1), i >= 5 ? Motion.ACCENT : 0xFFFFFFFF, alpha, 0f, 0f);
            c.restore();
        }
        c.restore();
    }

    /**
     * Destello anamórfico: una raya de luz horizontal (blanca en el centro, coral a los lados)
     * que se abre de lado a lado y se apaga. {@code p} va de 0 a 1.
     */
    private void drawFlare(Canvas c, float x, float y, float p, float strength, float width) {
        if (p <= 0f || p >= 1f) return;
        float e = 1f - (1f - p) * (1f - p) * (1f - p);
        float half = getWidth() * width * (0.12f + 0.6f * e);
        flare.setShader(new LinearGradient(x - half, 0, x + half, 0,
                new int[]{0x00FF5F45, 0xFFFF5F45, 0xFFFFFFFF, 0xFFFF5F45, 0x00FF5F45},
                new float[]{0f, 0.3f, 0.5f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        float al = 255 * strength * (1f - p) * (1f - p);
        // Tres capas de distinto grosor: el núcleo fino y un halo que se difumina.
        float[] thick = {1.2f, 3.5f, 9f}, fade = {1f, 0.3f, 0.1f};
        for (int i = 0; i < 3; i++) {
            flare.setAlpha(Math.round(al * fade[i]));
            float hw = half * (1f - i * 0.18f);
            c.drawRect(x - hw, y - thick[i] * dp, x + hw, y + thick[i] * dp, flare);
        }
    }

    /** Una letra centrada en x, con la línea base en y. */
    private void letter(Canvas c, String s, int color, float alpha, float x, float y) {
        glyph.setColor(color);                              // setColor pone la opacidad a 255: va antes
        glyph.setAlpha(Math.round(255 * Motion.clamp01(alpha)));
        c.drawText(s, x, y, glyph);
    }

    /** Dónde acaba cada letra de la palabra larga al juntarse (todas las «e» en la misma). */
    private float target(int i) {
        if (i < E_FIRST) return compactX[i];
        if (i <= E_LAST) return compactX[E_FIRST];
        return compactX[i - E_COUNT + 1];
    }

    /** Velocidad de la cámara en un punto del recorrido (0 = la «H», 1 = «Spring»). */
    private static float speed(float s) {
        for (int i = 0; i < SPEED.length - 1; i++) {
            float a0 = SPEED[i][0], a1 = SPEED[i + 1][0];
            if (s <= a1) {
                float f = (s - a0) / (a1 - a0), e = f * f * (3f - 2f * f);
                return SPEED[i][1] + (SPEED[i + 1][1] - SPEED[i][1]) * e;
            }
        }
        return SPEED[SPEED.length - 1][1];
    }

    /** Parte del recorrido hecha a los {@code ms} de empezar a moverse la cámara (0..1). */
    private static float travelAt(float ms) {
        float q = Motion.clamp01(ms / PAN_MS) * TIME_AT[STEPS];
        int lo = 0, hi = STEPS;
        while (hi - lo > 1) {
            int m = (lo + hi) >>> 1;
            if (TIME_AT[m] < q) lo = m;
            else hi = m;
        }
        float span = TIME_AT[hi] - TIME_AT[lo];
        float f = span > 0f ? (q - TIME_AT[lo]) / span : 0f;
        return Motion.clamp01((lo + f) / STEPS);
    }

    private static float inOut3(float u) {
        return u < 0.5f ? 4f * u * u * u : 1f - (float) Math.pow(-2f * u + 2f, 3) / 2f;
    }
}
