package com.icehome.up.cc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import com.icehome.up.xp.XC_MethodHook;
import com.icehome.up.xp.XposedBridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Reloj de la pantalla de bloqueo en cristal líquido, al estilo iOS 26 / HyperOS 4.
 *
 * <p>Por qué no se usa el de HyperOS 4: allí el cristal lo pinta el sistema
 * ({@code View.setMiGlass}), y el framework de HyperOS 3 no lo tiene.
 *
 * <p>Qué hace: el reloj que tengas puesto en HyperOS (cualquier estilo del editor) sirve de
 * molde. Se pinta aparte en una imagen y de ella sale la forma de sus letras; esa forma se
 * convierte en cristal en un hilo aparte ({@link #render}) y se pega encima, con el reloj de
 * serie escondido. Solo se rehace cuando el reloj cambia (cada minuto, o si se mueve): con la
 * pantalla quieta no gasta nada.
 *
 * <p>El cristal ({@link #glassify}): la forma desenfocada es la "altura" del cristal (plana dentro
 * de la letra, curva en el borde). Su pendiente dobla lo de detrás (foto del fondo de bloqueo,
 * desenfocada) y da la luz del canto, desde arriba a la izquierda.
 *
 * <p>Se engancha {@code KeyguardClockContainer}: ahí se añade la vista del cristal. El reloj de
 * serie puede estar en ese contenedor o, con el efecto de profundidad, en
 * {@code miui_keyguard_foreground_clock_container}.
 */
public final class LockClock {

    /** Ajustes (los pone telon.Tune.reload). */
    public static volatile boolean enabled;
    public static volatile float glass = 1f;
    /** Cristal: ancho de la franja que refracta y cuánto desvía el fondo (dp), y opacidad del color del reloj. */
    public static volatile float edge = 8f, refr = 30f, opacity = 0.15f;

    private static final Set<ClockView> VIEWS = Collections.newSetFromMap(new WeakHashMap<ClockView, Boolean>());
    private static Handler worker;
    private static Handler main;

    // Foto del fondo de bloqueo (desenfocada) y px de la foto por px de pantalla.
    private static volatile Bitmap backdrop;
    private static volatile float backdropScale = 1f;
    private static boolean captureBusy;
    private static long lastCapture;

    public static void install(ClassLoader cl) throws Throwable {
        Class<?> c = cl.loadClass("com.android.keyguard.clock.KeyguardClockContainer");
        XposedBridge.hookMethod(c.getDeclaredMethod("onAttachedToWindow"), new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    attach((FrameLayout) p.thisObject);
                } catch (Throwable t) {
                    com.icehome.up.telon.Log.e("reloj de cristal: no se pudo añadir", t);
                }
            }
        });
        com.icehome.up.telon.Log.i("reloj de cristal: hook instalado");
    }

    /** Ajustes cambiados desde la app: se rehace la imagen al momento. */
    public static void retune() {
        mainHandler().post(new Runnable() {
            @Override
            public void run() {
                for (ClockView v : new ArrayList<>(VIEWS)) v.settingsChanged();
            }
        });
    }

    private static Handler mainHandler() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }

    private static Handler worker() {
        if (worker == null) worker = com.icehome.up.Threads.start("HyperSpring-lockclock",
                android.os.Process.THREAD_PRIORITY_BACKGROUND);
        return worker;
    }

    private static void attach(FrameLayout container) {
        for (int i = 0; i < container.getChildCount(); i++) {
            if (container.getChildAt(i) instanceof ClockView) return;
        }
        container.addView(new ClockView(container.getContext(), container), new FrameLayout.LayoutParams(-1, -1));
    }

    // ------------------------------------------------------------------ captura del fondo

    /**
     * Foto de lo que hay detrás de la pantalla de bloqueo (el fondo), sin la ventana de SystemUI.
     * Solo con la pantalla encendida: apagada sale negra.
     */
    private static void capture(final View host) {
        if (captureBusy || android.os.SystemClock.uptimeMillis() - lastCapture < 800) return;
        android.os.PowerManager pm = host.getContext().getSystemService(android.os.PowerManager.class);
        if (pm != null && !pm.isInteractive()) return;
        final SurfaceControl own = CcGlass.surfaceOf(host);
        final int display = host.getDisplay() != null ? host.getDisplay().getDisplayId() : 0;
        final android.util.DisplayMetrics dm = host.getResources().getDisplayMetrics();
        final Context ctx = host.getContext();
        captureBusy = true;
        lastCapture = android.os.SystemClock.uptimeMillis();
        worker().post(new Runnable() {
            @Override
            public void run() {
                Bitmap small = null;
                final float pxPerSample = 1.5f * dm.density;   // fondo casi nítido, como el cristal de iOS
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 34 && own != null) {
                        float frame = 0.5f;   // media resolución: el fondo se ve nítido a través
                        Bitmap shot = CcGlass.screenshot(display, own, frame);
                        if (shot != null) small = GlassShader.shrinkBlur(shot, 1f / frame, pxPerSample, 2.5f * dm.density);
                    }
                } catch (Throwable t) {
                    com.icehome.up.telon.Log.e("reloj de cristal: captura", t);
                }
                // Negra (fondo dibujado dentro de SystemUI o pantalla apagándose): el fondo de bloqueo.
                if (small == null || dark(small)) {
                    try {
                        Bitmap w = lockWallpaper(ctx, dm.widthPixels, dm.heightPixels);
                        if (w != null) small = GlassShader.shrinkBlur(w, 1f, pxPerSample, 2.5f * dm.density);
                    } catch (Throwable t) {
                        com.icehome.up.telon.Log.e("reloj de cristal: fondo de bloqueo", t);
                    }
                }
                final Bitmap b = small;
                mainHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        captureBusy = false;
                        if (b == null) return;
                        backdrop = b;
                        backdropScale = 1f / pxPerSample;
                        for (ClockView v : new ArrayList<>(VIEWS)) v.requestBake(0);
                    }
                });
            }
        });
    }

    private static boolean dark(Bitmap b) {
        long sum = 0;
        int n = 0;
        for (int y = 0; y < b.getHeight(); y += 4) {
            for (int x = 0; x < b.getWidth(); x += 4) {
                int c = b.getPixel(x, y);
                sum += ((c >> 16) & 255) + ((c >> 8) & 255) + (c & 255);
                n++;
            }
        }
        return n == 0 || sum / (3 * n) < 3;
    }

    /** El fondo de bloqueo a tamaño de pantalla (recortado al centro). */
    private static Bitmap lockWallpaper(Context c, int w, int h) {
        android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(c);
        android.graphics.drawable.Drawable d = null;
        if (android.os.Build.VERSION.SDK_INT >= 34) d = wm.getDrawable(android.app.WallpaperManager.FLAG_LOCK);
        if (d == null) d = wm.getDrawable();
        if (d == null) return null;
        int iw = Math.max(1, d.getIntrinsicWidth()), ih = Math.max(1, d.getIntrinsicHeight());
        float s = Math.max(w / (float) iw, h / (float) ih);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        cv.translate((w - iw * s) / 2f, (h - ih * s) / 2f);
        cv.scale(s, s);
        d.setBounds(0, 0, iw, ih);
        d.draw(cv);
        return out;
    }

    // ------------------------------------------------------------------ la vista

    static final class ClockView extends View {
        private final FrameLayout container;
        private final Set<View> hidden = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
        private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final int[] loc = new int[2], tmpLoc = new int[2];
        private int fgId;

        // Cristal ya hecho: la imagen y su esquina (px de esta vista).
        private Bitmap baked;
        private int bakedX, bakedY;
        private String lastSig = "";
        // Pieza del reloj de serie que se sigue (posición y transparencia) y dónde estaba al hacer el cristal.
        private java.lang.ref.WeakReference<View> anchor;
        private int anchorX, anchorY;
        private int bakeGen;
        private boolean bakePending;

        private static java.lang.reflect.Field toAod;

        private final ViewTreeObserver.OnPreDrawListener preDraw = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                // Corre en cada fotograma de SystemUI: un fallo aquí no puede salir del hook.
                try {
                    follow();
                } catch (Throwable t) {
                    com.icehome.up.telon.Log.e("reloj de cristal: fotograma", t);
                }
                return true;
            }
        };

        private void follow() {
                syncStock();
                // El reloj de serie se ha movido o ha cambiado de tamaño: cristal nuevo.
                if (active()) {
                    String s = signature();
                    if (!s.equals(lastSig)) {
                        lastSig = s;
                        requestBake(60);
                    }
                    // Solo se ha movido (desbloqueo, notificaciones): el cristal lo sigue sin rehacerse.
                    View a = anchor != null ? anchor.get() : null;
                    if (a != null) {
                        a.getLocationOnScreen(tmpLoc);
                        int dx = tmpLoc[0] - anchorX, dy = tmpLoc[1] - anchorY;
                        int al = Math.round(255 * effectiveAlpha(a));
                        if (dx != followX || dy != followY || al != followA) {
                            followX = dx;
                            followY = dy;
                            followA = al;
                            invalidate();
                        }
                    }
                }
        }

        private final BroadcastReceiver ticks = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                if (Intent.ACTION_SCREEN_ON.equals(a) || Intent.ACTION_WALLPAPER_CHANGED.equals(a)) {
                    if (Intent.ACTION_WALLPAPER_CHANGED.equals(a)) backdrop = null;
                    postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (active()) capture(ClockView.this);
                        }
                    }, 350);                                // que el fondo ya esté en pantalla
                }
                // El reloj de serie también recibe el aviso y cambia la hora: después, el molde nuevo.
                requestBake(400);
            }
        };

        ClockView(Context c, FrameLayout container) {
            super(c);
            this.container = container;
            setWillNotDraw(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            VIEWS.add(this);
        }

        boolean active() {
            if (!enabled) return false;
            // De camino al AOD: se deja el reloj de serie, que es el que sabe animarse hacia allí.
            try {
                if (toAod == null) {
                    toAod = container.getClass().getDeclaredField("mToAod");
                    toAod.setAccessible(true);
                }
                if (toAod.getBoolean(container)) return false;
            } catch (Throwable ignored) {
            }
            return true;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            VIEWS.add(this);
            getViewTreeObserver().addOnPreDrawListener(preDraw);
            IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
            f.addAction(Intent.ACTION_TIME_CHANGED);
            f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_WALLPAPER_CHANGED);
            f.addAction(Intent.ACTION_LOCALE_CHANGED);
            try {
                getContext().registerReceiver(ticks, f, Context.RECEIVER_EXPORTED);
            } catch (Throwable t) {
                com.icehome.up.telon.Log.e("reloj de cristal: receptor", t);
            }
            requestBake(0);
        }

        @Override
        protected void onDetachedFromWindow() {
            getViewTreeObserver().removeOnPreDrawListener(preDraw);
            try {
                getContext().unregisterReceiver(ticks);
            } catch (Throwable ignored) {
            }
            super.onDetachedFromWindow();
        }

        /** Contenedores con el reloj de serie: el nuestro y, con profundidad, la capa de delante. */
        private List<ViewGroup> groups() {
            List<ViewGroup> g = new ArrayList<>(2);
            g.add(container);
            if (fgId == 0) fgId = getResources().getIdentifier("miui_keyguard_foreground_clock_container", "id", "com.android.systemui");
            View parent = container.getParent() instanceof View ? (View) container.getParent() : null;
            View fg = fgId != 0 && parent != null ? parent.findViewById(fgId) : null;
            if (fg instanceof ViewGroup) g.add((ViewGroup) fg);
            return g;
        }

        /** Hijos que forman el reloj de serie: los visibles y los que hemos escondido nosotros. */
        private List<View> stock() {
            List<View> out = new ArrayList<>();
            for (ViewGroup g : groups()) {
                for (int i = 0; i < g.getChildCount(); i++) {
                    View ch = g.getChildAt(i);
                    if (ch != this && (ch.getVisibility() == VISIBLE || hidden.contains(ch))) out.add(ch);
                }
            }
            return out;
        }

        /**
         * Esconde el reloj de serie solo cuando ya está el cristal (si no, se quedaría sin hora) y
         * lo devuelve al apagar la función o al ir al AOD.
         */
        private void syncStock() {
            boolean hide = active() && baked != null;
            for (ViewGroup g : groups()) {
                for (int i = 0; i < g.getChildCount(); i++) {
                    View ch = g.getChildAt(i);
                    if (ch == this) continue;
                    if (hide) {
                        if (ch.getVisibility() == VISIBLE) {
                            ch.setVisibility(INVISIBLE);
                            hidden.add(ch);
                        }
                    } else if (hidden.remove(ch) && ch.getVisibility() == INVISIBLE) {
                        ch.setVisibility(VISIBLE);
                    }
                }
            }
        }

        /** Dónde está y qué tamaño tiene cada pieza del reloj de serie. */
        private int followX, followY, followA = 255;

        /** Transparencia de la pieza contando la de sus padres hasta el contenedor. */
        private float effectiveAlpha(View v) {
            float a = 1f;
            for (View p = v; p != null && p != container && p != container.getParent(); ) {
                a *= p.getAlpha();
                p = p.getParent() instanceof View ? (View) p.getParent() : null;
            }
            return a;
        }

        private String signature() {
            StringBuilder b = new StringBuilder();
            for (View ch : stock()) sign(b, ch, 0, true);
            return b.toString();
        }

        /**
         * Tamaño, sitio, escala y tamaño de letra de una vista y de lo que lleva dentro. Al
         * encender, HyperOS agranda la hora por dentro (escala o tamaño de letra) y la encoge
         * después: si eso no cuenta, el molde se queda con el reloj gigante a medio animar.
         */
        private static void sign(StringBuilder b, View v, int depth, boolean root) {
            b.append(v.getLeft()).append(',').append(v.getTop()).append(',').append(v.getWidth()).append(',')
                    .append(v.getHeight());
            if (!root) {                                    // la escala del hijo directo ya la seguimos aparte
                b.append(',').append(Math.round(v.getScaleX() * 100)).append(',').append(Math.round(v.getScaleY() * 100));
            }
            if (v instanceof android.widget.TextView) b.append(',').append(Math.round(((android.widget.TextView) v).getTextSize()));
            b.append(';');
            if (v instanceof ViewGroup && depth < 4) {
                ViewGroup vg = (ViewGroup) v;
                for (int i = 0; i < vg.getChildCount(); i++) sign(b, vg.getChildAt(i), depth + 1, false);
            }
        }

        void settingsChanged() {
            try {
                if (!active()) {
                    baked = null;
                    bakeGen++;                              // la imagen que se esté haciendo ya no vale
                }
                syncStock();
                requestBake(0);
                invalidate();
            } catch (Throwable t) {
                com.icehome.up.telon.Log.e("reloj de cristal: ajustes", t);
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            if (!active()) return;
            try {
                if (backdrop == null) capture(this);        // la foto del fondo hace falta para el cristal
            } catch (Throwable t) {
                com.icehome.up.telon.Log.e("reloj de cristal: captura", t);
            }
            if (baked == null) return;
            bmpPaint.setAlpha(followA);
            c.drawBitmap(baked, bakedX + followX, bakedY + followY, bmpPaint);
        }

        void requestBake(long delay) {
            if (bakePending || !isAttachedToWindow()) return;
            bakePending = true;
            postDelayed(new Runnable() {                    // varios avisos seguidos: una sola imagen
                @Override
                public void run() {
                    bakePending = false;
                    try {
                        bake();
                    } catch (Throwable t) {                 // p. ej. sin memoria para el molde
                        com.icehome.up.telon.Log.e("reloj de cristal: molde", t);
                    }
                }
            }, Math.max(delay, 16));
        }

        /** Pinta el reloj de serie en una imagen (hilo principal) y hace el cristal en el hilo del reloj. */
        private void bake() {
            if (!active() || getWidth() == 0 || getHeight() == 0) return;
            final Bitmap bd = backdrop;
            final float bs = backdropScale;
            if (bd == null) {
                capture(this);
                return;
            }
            getLocationOnScreen(loc);
            final Bitmap mask = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
            Canvas mc = new Canvas(mask);
            int drawn = 0;
            for (View ch : stock()) {
                ch.getLocationOnScreen(tmpLoc);
                // getLocationOnScreen ya incluye su desplazamiento (translation); draw() no lo aplica.
                int save = mc.save();
                mc.translate(tmpLoc[0] - loc[0], tmpLoc[1] - loc[1]);
                try {
                    ch.draw(mc);                            // dibuja aunque esté escondida
                    drawn++;
                } catch (Throwable t) {
                    // p. ej. una imagen HARDWARE, que no se puede pintar en una imagen normal
                }
                mc.restoreToCount(save);
            }
            if (drawn == 0) {                              // no se pudo pintar: vuelve el reloj de serie
                baked = null;
                syncStock();
                return;
            }
            // La pieza que se seguirá: la más grande (el reloj), con su posición de ahora.
            View big = null;
            for (View ch : stock()) if (big == null || ch.getWidth() * ch.getHeight() > big.getWidth() * big.getHeight()) big = ch;
            final View anchorNow = big;
            final int ax, ay;
            if (big != null) {
                big.getLocationOnScreen(tmpLoc);
                ax = tmpLoc[0];
                ay = tmpLoc[1];
            } else {
                ax = ay = 0;
            }
            final int sx = loc[0], sy = loc[1];
            final float density = getResources().getDisplayMetrics().density;
            final float g = glass;
            final int gen = ++bakeGen;
            worker().post(new Runnable() {
                @Override
                public void run() {
                    try {
                        final int[] at = new int[2];
                        final Bitmap out = render(mask, bd, bs, sx, sy, density, g, at);
                        mainHandler().post(new Runnable() {
                            @Override
                            public void run() {
                                if (gen != bakeGen) return;            // ya hay una más nueva en camino
                                baked = out;
                                bakedX = at[0];
                                bakedY = at[1];
                                anchor = anchorNow != null ? new java.lang.ref.WeakReference<>(anchorNow) : null;
                                anchorX = ax;
                                anchorY = ay;
                                followX = followY = 0;
                                syncStock();
                                invalidate();
                            }
                        });
                    } catch (Throwable t) {
                        com.icehome.up.telon.Log.e("reloj de cristal: imagen", t);
                    }
                }
            });
        }
    }

    // ------------------------------------------------------------------ molde → cristal (CPU)

    /**
     * Del molde (el reloj de serie pintado en una imagen del tamaño de la pantalla) al cristal. Lo
     * usan SystemUI y la vista previa de la app. Recorta lo que tiene letras y hace el cristal.
     *
     * @param sx    px de pantalla de la esquina del molde
     * @param sy    ídem
     * @param outAt devuelve dónde va la imagen, en px del molde
     * @return la imagen del cristal, o null si el molde está vacío
     */
    public static Bitmap render(Bitmap mask, Bitmap bd, float bs, float sx, float sy, float density, float g,
                                int[] outAt) {
        int w = mask.getWidth(), h = mask.getHeight();
        int[] px = new int[w * h];
        mask.getPixels(px, 0, w, 0, 0, w, h);
        int left = w, right = -1, top = h, bot = -1;
        for (int y = 0; y < h; y++) {
            int o = y * w;
            for (int x = 0; x < w; x++) {
                if ((px[o + x] >>> 24) > 8) {
                    if (x < left) left = x;
                    if (x > right) right = x;
                    if (y < top) top = y;
                    bot = y;
                }
            }
        }
        if (right < 0) return null;
        int margin = Math.round(4f * density);
        int cx0 = Math.max(0, left - margin), cx1 = Math.min(w, right + 1 + margin);
        int cy0 = Math.max(0, top - margin), cy1 = Math.min(h, bot + 1 + margin);
        Bitmap crop = Bitmap.createBitmap(mask, cx0, cy0, cx1 - cx0, cy1 - cy0);
        outAt[0] = cx0;
        outAt[1] = cy0;
        return glassify(crop, bd, bs, sx + cx0, sy + cy0, density, g);
    }

    /**
     * Convierte las cifras en cristal como el reloj de iOS 26. Sacado midiendo capturas de iOS
     * ampliadas:
     * <ul>
     * <li><b>Qué es cifra y qué no:</b> cada trozo suelto del molde (cada cifra, cada punto, cada
     * letra de la fecha) se mide por lo grueso de su trazo. Los gruesos (las cifras, en cualquier
     * estilo: en una fila, en dos, en columna) pasan a cristal; los finos (fecha, día, iconos) se
     * quedan como en el reloj de serie, sólidos y legibles.</li>
     * <li><b>Mucha refracción:</b> en una franja de {@link #edge} dp desde el canto (como mucho
     * medio trazo) el fondo se toma desde lejos hacia fuera ({@link #refr} dp, lente circleMap de
     * Kyant0/AndroidLiquidGlass, Apache-2.0). Por eso los colores de alrededor "gotean" dentro de
     * las cifras, como el azul en la cola del 9 de iOS.</li>
     * <li><b>Color del reloj:</b> el color que tenga el reloj de serie (el que eligió el usuario
     * en el editor de HyperOS) se mezcla encima con {@link #opacity}. Con el blanco de siempre es
     * un velo suave.</li>
     * <li><b>Línea de luz de 1 px con el color del fondo:</b> fuerte en los cantos que miran
     * arriba y a la derecha; en los que miran a la izquierda, una línea oscura finita. Su fuerza es
     * {@code g} («Luz del canto»).</li>
     * </ul>
     * {@code ox, oy}: esquina de la imagen en px de pantalla; {@code bd}: fondo con {@code bs} px
     * por px de pantalla.
     */
    static Bitmap glassify(Bitmap img, Bitmap bd, float bs, float ox, float oy, float density, float g) {
        int w = img.getWidth(), h = img.getHeight(), n = w * h;
        int[] px = new int[n];
        img.getPixels(px, 0, w, 0, 0, w, h);
        // Cobertura (0..1). El molde puede venir semitransparente: se lleva a opaco.
        float[] a = new float[n];
        for (int i = 0; i < n; i++) a[i] = Math.min(1f, (px[i] >>> 24) / 255f / 0.7f);
        // Distancia al borde dentro de la letra (chaflán en dos pasadas, en px).
        float[] d = new float[n];
        for (int i = 0; i < n; i++) d[i] = a[i] > 0.5f ? 1e6f : 0f;
        // Fuera de la imagen cuenta como fondo (reloj cortado en el borde): el borde vale 1.
        for (int x = 0; x < w; x++) {
            if (d[x] > 0f) d[x] = 1f;
            if (d[(h - 1) * w + x] > 0f) d[(h - 1) * w + x] = 1f;
        }
        for (int y = 0; y < h; y++) {
            if (d[y * w] > 0f) d[y * w] = 1f;
            if (d[y * w + w - 1] > 0f) d[y * w + w - 1] = 1f;
        }
        for (int y = 1; y < h; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                if (d[i] == 0f) continue;
                float m = Math.min(d[i], Math.min(d[i - 1], d[i - w]) + 1f);
                d[i] = Math.min(m, Math.min(d[i - w - 1], d[i - w + 1]) + 1.414f);
            }
        }
        for (int y = h - 2; y >= 0; y--) {
            for (int x = w - 2; x >= 1; x--) {
                int i = y * w + x;
                if (d[i] == 0f) continue;
                float m = Math.min(d[i], Math.min(d[i + 1], d[i + w]) + 1f);
                d[i] = Math.min(m, Math.min(d[i + w + 1], d[i + w - 1]) + 1.414f);
            }
        }
        // Trozos sueltos (8 vecinos) y lo grueso de cada uno: su distancia máxima al borde.
        int[] lab = new int[n];
        float[] thick = new float[16];
        boolean[] solid = new boolean[16];
        int comps = 0;
        float maxD = 1f;
        int[] stack = new int[n];
        for (int s = 0; s < n; s++) {
            if (a[s] <= 0.5f || lab[s] != 0) continue;
            if (++comps >= thick.length) {
                thick = java.util.Arrays.copyOf(thick, thick.length * 2);
                solid = java.util.Arrays.copyOf(solid, solid.length * 2);
            }
            float t = 0f;
            int maxA = 0;
            // Cola (no pila): al acabar, stack[0..end) son justo los píxeles de este trozo.
            int head = 0, end = 0;
            stack[end++] = s;
            lab[s] = comps;
            while (head < end) {
                int i = stack[head++];
                if (d[i] > t) t = d[i];
                maxA = Math.max(maxA, px[i] >>> 24);
                int x = i % w, y = i / w;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) continue;
                        int j = yy * w + xx;
                        if (lab[j] == 0 && a[j] > 0.5f) {
                            lab[j] = comps;
                            stack[end++] = j;
                        }
                    }
                }
            }
            thick[comps] = t;
            // Un fondo de serie (caja o cápsula semitransparente detrás de la fecha, con el texto
            // encima) es casi todo más translúcido que su parte más opaca: no es cifra, se queda
            // como está y no cuenta para el más grueso. Una caja vacía muy translúcida (máximo bajo)
            // tampoco. Una cifra algo transparente pero pareja sí cuenta.
            int opaque = 0, cut = maxA * 9 / 10;
            for (int k = 0; k < end; k++) if ((px[stack[k]] >>> 24) >= cut) opaque++;
            solid[comps] = maxA >= 190 && opaque * 2 >= end;
            if (solid[comps] && t > maxD) maxD = t;
        }
        // Cifra = trozo opaco con al menos el 45 % del trazo más grueso (la fecha es mucho más fina).
        boolean[] glassComp = new boolean[comps + 1];
        for (int c = 1; c <= comps; c++) glassComp[c] = solid[c] && thick[c] >= maxD * 0.45f;
        // El borde suavizado (cobertura ≤ 0,5) toma el trozo de al lado (dos pasadas = 2 px).
        for (int pass = 0; pass < 2; pass++) {
            int[] nl = lab.clone();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    if (lab[i] != 0 || a[i] <= 0f) continue;
                    if (x > 0 && lab[i - 1] != 0) nl[i] = lab[i - 1];
                    else if (x < w - 1 && lab[i + 1] != 0) nl[i] = lab[i + 1];
                    else if (y > 0 && lab[i - w] != 0) nl[i] = lab[i - w];
                    else if (y < h - 1 && lab[i + w] != 0) nl[i] = lab[i + w];
                }
            }
            lab = nl;
        }
        // Distancia suavizada: normal y canto sin escalones.
        float[] ds = d.clone(), tmp = new float[n];
        for (int pass = 0; pass < 2; pass++) {
            boxF(ds, tmp, w, h, 1, true);
            boxF(tmp, ds, w, h, 1, false);
        }

        int bw = bd.getWidth(), bh = bd.getHeight();
        int[] bp = new int[bw * bh];
        bd.getPixels(bp, 0, bw, 0, 0, bw, bh);

        // Franja que refracta: como mucho medio trazo (el centro del trazo queda sin desviar).
        float bevel = Math.max(0.5f, Math.min(edge * density, maxD));
        float refrAmt = refr * density;                    // hasta dónde va a buscar el fondo
        float op = Math.max(0f, Math.min(1f, opacity));
        float lineW = Math.max(1f, 0.8f * density);        // grosor de la línea de luz
        final float lx = 0.45f, ly = -0.89f;               // luz: arriba y algo a la derecha
        float[] col = new float[3], far = new float[3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float al = a[i];
                if (al <= 0.003f || !glassComp[lab[i]]) continue;   // fecha y demás: tal cual
                // Normal hacia fuera = contra el gradiente de la distancia.
                float gx = ds[x < w - 1 ? i + 1 : i] - ds[x > 0 ? i - 1 : i];
                float gy = ds[y < h - 1 ? i + w : i] - ds[y > 0 ? i - w : i];
                float gl = (float) Math.sqrt(gx * gx + gy * gy);
                float nx = gl > 1e-4f ? -gx / gl : 0f, ny = gl > 1e-4f ? -gy / gl : 0f;
                float dist = Math.max(0f, d[i] > 0f ? Math.max(ds[i], 0.5f) - 0.5f : al - 0.5f);
                sample(bp, bw, bh, (ox + x) * bs, (oy + y) * bs, col);
                // Refracción: el fondo se toma desde lejos hacia fuera, más cuanto más cerca del canto.
                if (dist < bevel) {
                    float xx = 1f - dist / bevel;
                    float lens = (1f - (float) Math.sqrt(Math.max(0f, 1f - xx * xx))) * refrAmt + xx * refrAmt * 0.25f;
                    sample(bp, bw, bh, (ox + x + nx * lens) * bs, (oy + y + ny * lens) * bs, far);
                    float t = Math.min(1f, xx * 1.4f);
                    for (int k = 0; k < 3; k++) col[k] += (far[k] - col[k]) * t;
                }
                // El color del reloj de serie encima, con la opacidad elegida.
                int sc = px[i];
                col[0] += (((sc >> 16) & 255) / 255f - col[0]) * op;
                col[1] += (((sc >> 8) & 255) / 255f - col[1]) * op;
                col[2] += ((sc & 255) / 255f - col[2]) * op;
                // Línea de luz con el color del fondo: fuerte arriba/derecha; oscura a la izquierda.
                float cov = Math.max(0f, Math.min(1f, lineW - dist + 0.5f));
                float facing = nx * lx + ny * ly;
                float spec = cov * (0.80f * (float) Math.pow(Math.max(facing, 0f), 0.8f) + 0.18f) * g;
                float dark = cov * Math.max(-nx, 0f) * Math.max(0f, 1f - Math.max(facing, 0f) * 2f) * 0.30f * g;
                for (int k = 0; k < 3; k++) {
                    float v = col[k] * (1f - dark);
                    float lit = Math.min(1f, col[k] * 1.25f + 0.35f);   // claro, pero del mismo color
                    col[k] = v + (lit - v) * Math.min(1f, spec);
                }
                // setPixels quiere color SIN premultiplicar.
                px[i] = (clamp255(al * 255f) << 24) | (clamp255(col[0] * 255f) << 16)
                        | (clamp255(col[1] * 255f) << 8) | clamp255(col[2] * 255f);
            }
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    private static int clamp255(float v) {
        return v <= 0f ? 0 : v >= 255f ? 255 : Math.round(v);
    }

    /** Lectura bilineal del fondo (px del fondo), recortada al borde. */
    private static void sample(int[] bp, int bw, int bh, float fx, float fy, float[] out) {
        fx = Math.max(0f, Math.min(bw - 1.001f, fx - 0.5f));
        fy = Math.max(0f, Math.min(bh - 1.001f, fy - 0.5f));
        int x = (int) fx, y = (int) fy;
        float tx = fx - x, ty = fy - y;
        int c00 = bp[y * bw + x], c10 = bp[y * bw + x + 1], c01 = bp[(y + 1) * bw + x], c11 = bp[(y + 1) * bw + x + 1];
        for (int k = 0; k < 3; k++) {
            int sft = 16 - 8 * k;
            float v0 = ((c00 >> sft) & 255) * (1 - tx) + ((c10 >> sft) & 255) * tx;
            float v1 = ((c01 >> sft) & 255) * (1 - tx) + ((c11 >> sft) & 255) * tx;
            out[k] = (v0 * (1 - ty) + v1 * ty) / 255f;
        }
    }

    /** Caja de radio r en una dirección. Fuera de la imagen vale 0: la letra no "crece" en el borde. */
    private static void boxF(float[] in, float[] out, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        float div = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int base = horizontal ? l * w : l, step = horizontal ? 1 : w;
            float acc = 0;
            for (int i = 0; i <= r && i < len; i++) acc += in[base + i * step];
            for (int i = 0; i < len; i++) {
                out[base + i * step] = acc / div;
                int add = i + r + 1, sub = i - r;
                if (add < len) acc += in[base + add * step];
                if (sub >= 0) acc -= in[base + sub * step];
            }
        }
    }

    private LockClock() {
    }
}
