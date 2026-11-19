package com.icehome.up;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

import com.icehome.up.cc.GlassShader;
import com.icehome.up.xp.XC_MethodHook;
import com.icehome.up.xp.XposedBridge;
import com.icehome.up.xp.XposedHelpers;

/**
 * Material "cristal suave" de HyperOS 4 para el fondo de las carpetas.
 *
 * Se dibuja encima del fondo real de la carpeta (y por debajo de sus iconos) y respeta el ajuste
 * de texturas avanzadas:
 *  - Activado: el interior muestra el fondo de pantalla con un desenfoque propio (FOLDER_BLUR_DP,
 *    algo menor que el del sistema) y una lupa leve; en el canto ese mismo fondo se dobla
 *    (refracción con dispersión de color) y el borde se ilumina con el color de lo que hay
 *    detrás, como si el cristal llegara hasta el canto.
 *  - Desactivado: fondo plano del sistema; solo el canto refracta y se ilumina.
 * Con fondo legible, además, cristal líquido: el borde actúa de lente gruesa (como en el centro
 * de control) y dobla hacia dentro lo que hay detrás, con algo de arcoíris en las esquinas.
 * Encima: velo blanco suave, brillo especular desde arriba-izquierda y un filo blanco fino.
 * Sin AGSL (Android < 13) o con fondo animado: velo, brillo y canto blanco como antes.
 */
final class GlassFolder {

    // ---------------------------------------------------------------- ajustes
    static final boolean ENABLED = true;
    /** Velo (alfa arriba → abajo) con desenfoque del sistema y sin él. */
    static final int VEIL_TOP_BLUR = 0x2E, VEIL_BOTTOM_BLUR = 0x0D;
    static final int VEIL_TOP_FLAT = 0x47, VEIL_BOTTOM_FLAT = 0x1F;
    static final int SHEEN_ALPHA = 0x30;
    static final float RIM_DP = 1.1f;
    static final int RIM_TOP = 0xD9, RIM_SIDE = 0x24, RIM_BOTTOM = 0x66;
    static final float INNER_DP = 2.2f;
    static final int INNER_ALPHA = 0x33;
    /** Refracción: anchura de la franja del canto, desplazamiento máximo y opacidad. */
    static final boolean REFRACTION = true;
    static final float BAND_DP = 3f, BAND_MAX_FRACTION = 0.07f;   // como las fichas del centro de control
    static final float STRENGTH_DP = 0f;
    /** Canto: estira el color de hasta EDGE_OVER_DP por fuera (igual que CcGlass). */
    static final float EDGE_OVER_DP = 3f, EDGE_SAT = 0.4f;
    static final float GLASS_SAT = 1.55f, GLASS_DARKER = 0.08f, GLASS_WHITE = 0.03f, GLASS_TONE = 1.1f, INNER_DARK = 0.02f;
    static final float DISPERSION = 0.06f;       // separación de colores (aberración cromática)
    static final float REFRACT_ALPHA = 0.9f;
    /**
     * Con texturas avanzadas: el interior muestra el fondo de pantalla con un desenfoque propio,
     * algo menor que el del sistema, para que se vea cómo el canto lo dobla.
     */
    static final float FOLDER_BLUR_DP = 6f;
    static final float MAGNIFY = 0.035f;                // lupa leve en el interior
    /** Canto iluminado con el color de lo que hay detrás. */
    static final float TINT_RIM_DP = 0.8f, GLOW_DP = 0f, TINT_RIM_GAIN = 1.0f;
    static final float LENS_LIGHT = 0.04f;
    /**
     * Cristal líquido (la misma lente que el centro de control, de Kyant0/AndroidLiquidGlass):
     * cerca del borde el fondo se dobla hacia dentro como en un bisel de vidrio grueso, y en las
     * esquinas los colores se separan un poco, como en un prisma. La lente no pasa de un tercio
     * del lado corto, para que en las carpetas pequeñas no se coma el centro; el desplazamiento
     * se reduce en la misma proporción.
     *
     * Los valores vienen de los deslizadores de la app (Tune.reload los pone aquí); estos son los
     * de por defecto. volatile: los escribe el receptor de ajustes y los lee el dibujo.
     */
    static volatile float liquidH = 22f, liquidAmt = 30f, liquidChroma = 1.2f;
    /** Brillo de la lente con cristal líquido: más que el canto fino para que se note el volumen. */
    static volatile float liquidLight = 0.10f;
    /**
     * Calidad (25–100 %): cuántos dp de pantalla resume cada píxel del fondo que usa el cristal.
     * 100 % = 1 dp por muestra (lo más nítido); 67 % ≈ 1,5 dp (lo de siempre); 25 % = 4 dp.
     */
    static volatile float quality = 67f;
    /** Brillo al tocar (0 = sin): una luz suave bajo el dedo y el canto que se enciende cerca. */
    static volatile float touchGlow = 1f;

    private static float sampleDp() {
        return 100f / Math.max(25f, Math.min(100f, quality));
    }
    static final int VEIL_TOP_GLASS = 0x0C, VEIL_BOTTOM_GLASS = 0x00;

    private static boolean shaderBroken;
    /** volatile: lo escribe el hilo del fondo y lo lee el dibujo. */
    private static volatile android.graphics.Bitmap wall;
    private static volatile int wallId = Integer.MIN_VALUE;
    /** Hilo para leer y desenfocar el fondo (pesado: no puede ir en el hilo principal). */
    private static android.os.Handler wallHandler;
    /** Todos los cristales (carpetas, dock, menú), para redibujarlos cuando llega un fondo nuevo. */
    private static final Map<Glass, Boolean> ALL = new WeakHashMap<>();
    private static java.lang.reflect.Field fConfigBlur;
    private static boolean configBlurMissing;
    /** Última vez que se preguntó qué fondo hay (uptime) y cada cuánto se vuelve a preguntar. */
    private static long wallCheckedMs;
    private static final long WALL_CHECK_MS = 2000;
    private static float wallOffsetX = 0.5f, wallOffsetY = 0.5f;

    private static Method mIconImageView, mBlurSupported;
    private static final Map<View, Glass> GLASS = new WeakHashMap<>();
    private static final Map<View, View.OnLayoutChangeListener> LISTENING = new WeakHashMap<>();

    static void install(ClassLoader cl) {
        if (!ENABLED) return;
        Class<?> folderIcon = XposedHelpers.findClassIfExists("com.miui.home.folder.FolderIcon", cl);
        Class<?> itemIcon = XposedHelpers.findClassIfExists("com.miui.home.ItemIcon", cl);
        if (folderIcon == null || itemIcon == null) {
            XposedBridge.log("IceHome: no se encuentra FolderIcon, sin cristal en carpetas");
            return;
        }
        try {
            mIconImageView = itemIcon.getMethod("getIconImageView");
            mBlurSupported = StackSwipe.method(folderIcon, "isFolderBlurSupported");
        } catch (Throwable t) {
            XposedBridge.log("IceHome: FolderIcon ha cambiado, sin cristal en carpetas");
            return;
        }
        XposedHelpers.findAndHookMethod(folderIcon, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                final View folder = (View) p.thisObject;
                if (LISTENING.containsKey(folder)) return;
                // El listener usa la vista que le llega (v), no "folder": si la capturase, el valor
                // del WeakHashMap apuntaría a su propia clave y la carpeta no se liberaría nunca.
                View.OnLayoutChangeListener l = new View.OnLayoutChangeListener() {
                    @Override
                    public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                        apply(v);
                    }
                };
                LISTENING.put(folder, l);
                folder.addOnLayoutChangeListener(l);
                folder.post(new Runnable() {
                    @Override
                    public void run() {
                        apply(folder);
                    }
                });
            }
        });
        XposedHelpers.findAndHookMethod(folderIcon, "onLayout",
                boolean.class, int.class, int.class, int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        apply((View) p.thisObject);
                    }
                });
        // Brillo al tocar: se mira el dedo antes de que la carpeta reciba el toque (sin quitarle
        // nada: no se consume el evento, el launcher lo sigue gestionando igual).
        // FolderIcon no declara dispatchTouchEvent: se busca subiendo por sus clases del launcher
        // el primer método de toque que sí declare, y se filtra para quedarse solo con carpetas.
        try {
            final Class<?> fi = folderIcon;
            Method hooked = null;
            String[] names = {"dispatchTouchEvent", "onInterceptTouchEvent", "onTouchEvent"};
            for (Class<?> c = folderIcon; hooked == null && c != null
                    && !c.getName().startsWith("android."); c = c.getSuperclass()) {
                for (String n : names) {
                    for (Method m : XposedHelpers.declaredMethods(c)) {
                        if (m.getName().equals(n) && m.getParameterCount() == 1
                                && m.getParameterTypes()[0] == android.view.MotionEvent.class) {
                            hooked = m;
                            break;
                        }
                    }
                    if (hooked != null) break;
                }
            }
            if (hooked == null) throw new NoSuchMethodException("toque");
            XposedBridge.hookMethod(hooked, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    // En las carpetas grandes cada app de dentro es su propio icono y se queda el
                    // toque: se busca la carpeta subiendo unos pocos padres.
                    if (touchGlow <= 0f) return;
                    Object v = p.thisObject;
                    for (int i = 0; i < 6 && v instanceof View; i++) {
                        if (fi.isInstance(v)) {
                            touch((View) v, (android.view.MotionEvent) p.args[0]);
                            return;
                        }
                        v = ((View) v).getParent();
                    }
                }
            });
            XposedBridge.log("IceHome: brillo al tocar en " + hooked.getDeclaringClass().getSimpleName()
                    + "." + hooked.getName());
        } catch (Throwable t) {
            XposedBridge.log("IceHome: sin brillo al tocar las carpetas");
        }
        trackOffsets();
        XposedBridge.log("IceHome: cristal suave en carpetas activo");
    }

    private static boolean offsetsHooked;

    /**
     * Desplazamiento del fondo entre páginas (lo fija el launcher): para saber qué parte del fondo
     * hay debajo del cristal. Lo usan las carpetas y el menú de atajos; se engancha una sola vez.
     */
    static synchronized void trackOffsets() {
        if (offsetsHooked) return;
        offsetsHooked = true;
        try {
            XposedHelpers.findAndHookMethod(android.app.WallpaperManager.class, "setWallpaperOffsets",
                    android.os.IBinder.class, float.class, float.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            float x = (Float) p.args[1], y = (Float) p.args[2];
                            if (x >= 0f && x <= 1f) wallOffsetX = x;
                            if (y >= 0f && y <= 1f) wallOffsetY = y;
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    /** Pone (o actualiza) el cristal sobre el fondo real de la carpeta. */
    static void apply(View folder) {
        try {
            View bg = (View) mIconImageView.invoke(folder);
            if (bg == null || bg.getWidth() == 0 || bg.getHeight() == 0) return;
            boolean blur = texturesOn(folder.getContext());
            try {
                blur |= (Boolean) mBlurSupported.invoke(folder);
            } catch (Throwable ignored) {
            }
            Glass g = GLASS.get(bg);
            if (g == null) {
                g = new Glass(bg);
                GLASS.put(bg, g);
                bg.getOverlay().add(g);
                g.track();
            }
            if (REFRACTION) loadWallpaper(bg.getContext());
            // Esto corre en cada onLayout (muchas veces al redimensionar una carpeta): el radio solo
            // se vuelve a calcular si cambia el tamaño, y solo se redibuja si algo cambió.
            int bw = bg.getWidth(), bh = bg.getHeight();
            if (bw != g.radiusW || bh != g.radiusH) {
                g.radiusW = bw;
                g.radiusH = bh;
                g.radiusPx = radius(bg);
            }
            boolean changed = g.configure(blur, g.radiusPx);
            if (g.plain == blur) {
                g.plain = !blur;
                changed = true;
            }
            Rect cur = g.getBounds();
            if (cur.right != bw || cur.bottom != bh || cur.left != 0 || cur.top != 0) {
                g.setBounds(0, 0, bw, bh);
                changed = true;
            }
            if (changed) g.invalidateSelf();
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    /** Pasa el dedo al cristal de la carpeta: dónde está y si se enciende o se apaga el brillo. */
    private static void touch(View folder, android.view.MotionEvent e) {
        try {
            if (touchGlow <= 0f) return;
            View bg = (View) mIconImageView.invoke(folder);
            Glass g = bg == null ? null : GLASS.get(bg);
            if (g == null) return;
            // Del dedo en pantalla al sistema del fondo de la carpeta (que puede estar encogido
            // por el hundido del icono: por eso se divide por la escala acumulada).
            int[] at = new int[2];
            bg.getLocationOnScreen(at);
            float[] sc = chainScale(bg, new float[2]);
            float x = (e.getRawX() - at[0]) / Math.max(sc[0], 0.01f);
            float y = (e.getRawY() - at[1]) / Math.max(sc[1], 0.01f);
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    g.press(x, y, true);
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    g.press(x, y, g.glowTarget > 0f);
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    g.press(x, y, false);
                    break;
                default:
                    break;
            }
        } catch (Throwable ignored) {
            // Nunca romper el toque del launcher por un adorno.
        }
    }

    /** Texturas avanzadas activas: campo Configuration.blur de MIUI (1 = activado). */
    private static boolean texturesOn(android.content.Context ctx) {
        if (configBlurMissing) return false;
        try {
            if (fConfigBlur == null) {          // se busca una vez: va en cada onLayout
                fConfigBlur = android.content.res.Configuration.class.getDeclaredField("blur");
                fConfigBlur.setAccessible(true);
            }
            return fConfigBlur.getInt(ctx.getResources().getConfiguration()) == 1;
        } catch (Throwable t) {
            configBlurMissing = true;
            return false;
        }
    }

    /** Ajustes cambiados desde la app: se vuelve a desenfocar el fondo y se redibujan las carpetas. */
    static void retune(android.content.Context ctx) {
        wallId = Integer.MIN_VALUE;
        loadWallpaper(ctx);                     // en su hilo; al terminar redibuja todos los cristales
        redrawAll();
    }

    /** Redibuja todos los cristales (hilo principal). */
    private static void redrawAll() {
        for (Glass g : new java.util.ArrayList<>(ALL.keySet())) {
            if (g == null) continue;
            g.invalidateSelf();
            View h = g.hostRef.get();
            if (h != null) h.invalidate();
        }
    }

    /** Fondo de pantalla actual (reducido al tamaño de la pantalla). Null si es animado o no se puede leer. */
    static void loadWallpaper(android.content.Context ctx) {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        // Preguntar qué fondo hay es una llamada al servicio del sistema, y esto se llama en cada
        // onLayout de cada carpeta (también durante sus animaciones), en el hilo principal. El
        // fondo cambia muy de vez en cuando: basta con mirarlo cada pocos segundos. retune() (al
        // cambiar un ajuste) pone wallId a MIN_VALUE y obliga a mirarlo ya.
        long now = android.os.SystemClock.uptimeMillis();
        if (wallId != Integer.MIN_VALUE && now - wallCheckedMs < WALL_CHECK_MS) return;
        wallCheckedMs = now;
        final android.content.Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        // Todo lo demás en otro hilo: leer el fondo entero y desenfocarlo tarda decenas de ms y,
        // en el hilo principal, daba tirones al tocar un ajuste desde la app.
        synchronized (GlassFolder.class) {
            if (wallHandler == null) wallHandler = Threads.start("HyperSpring-wall", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        }
        wallHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(app);
                    int id = wm.getWallpaperId(android.app.WallpaperManager.FLAG_SYSTEM);
                    if (id == wallId) return;
                    android.graphics.Bitmap next = null;
                    if (wm.getWallpaperInfo() == null) {          // fondo animado: sin refracción
                        Drawable d = wm.getDrawable();
                        android.graphics.Bitmap b = d instanceof android.graphics.drawable.BitmapDrawable
                                ? ((android.graphics.drawable.BitmapDrawable) d).getBitmap() : null;
                        if (b != null) {
                            android.util.DisplayMetrics dm = app.getResources().getDisplayMetrics();
                            int sw = Math.max(dm.widthPixels, 1), sh = Math.max(dm.heightPixels, 1);
                            float screenPerSrc = Math.max((float) sw / b.getWidth(), (float) sh / b.getHeight());
                            float blurDp = Tune.get(app, "icehome_folder_blur");
                            next = GlassShader.shrinkBlur(b, screenPerSrc, sampleDp() * dm.density, blurDp * dm.density);
                        }
                    }
                    wall = next;
                    wallId = id;
                } catch (Throwable t) {
                    wall = null;
                }
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        redrawAll();
                    }
                });
            }
        });
    }

    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Radio de las esquinas del fondo: el de su contorno, o el de icono / widget de MIUI. */
    private static float radius(View bg) {
        try {
            if (bg.getOutlineProvider() != null) {
                Outline o = new Outline();
                bg.getOutlineProvider().getOutline(bg, o);
                float r = o.getRadius();
                if (r > 0f) return r;
            }
        } catch (Throwable ignored) {
        }
        float w = Math.min(bg.getWidth(), bg.getHeight());
        boolean small = Math.abs(bg.getWidth() - bg.getHeight()) < w * 0.2f
                && w < 90f * bg.getResources().getDisplayMetrics().density;
        String m = small ? "computeEnforcedIconRadius" : "computeEnforcedRadius";
        try {
            Class<?> rc = Class.forName("com.miui.home.common.widget.RoundedCornerEnforcement", false,
                    bg.getClass().getClassLoader());
            Number r = (Number) rc.getMethod(m, android.content.Context.class).invoke(null, bg.getContext());
            if (r.floatValue() > 0f) return r.floatValue();
        } catch (Throwable ignored) {
        }
        return small ? w * 0.24f : 20f * bg.getResources().getDisplayMetrics().density;
    }

    /** Fondo real de la carpeta (la vista que lleva el cristal), o null. */
    static View background(View folder) {
        try {
            return mIconImageView == null ? null : (View) mIconImageView.invoke(folder);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Cristal suelto, en coordenadas de la ventana, igual que el de esta carpeta: para dibujar la
     * tarjeta a cualquier tamaño mientras se redimensiona. Null si la carpeta no lleva cristal.
     */
    static Glass cardFor(View folder, float scale) {
        View bg = background(folder);
        Glass real = bg == null ? null : GLASS.get(bg);
        if (real == null) return null;
        Glass c = new Glass(folder.getRootView());
        c.configure(real.blur, real.radiusPx * scale);
        c.plain = real.plain;
        return c;
    }

    /** El cristal se rellena él solo con el fondo desenfocado (no hace falta el fondo de MIUI debajo). */
    static boolean opaque(Glass g) {
        return REFRACTION && g.blur && wall != null && !shaderBroken && android.os.Build.VERSION.SDK_INT >= 33;
    }

    /** Dibujo del cristal (en coordenadas del fondo de la carpeta). */
    static final class Glass extends Drawable {
        /**
         * La vista que lleva el cristal. Referencia débil: los Glass se guardan en WeakHashMap
         * cuya clave es esta misma vista, y si el valor la retuviera, nunca se liberaría (cada
         * carpeta o menú creado se quedaba en memoria para siempre).
         */
        private final java.lang.ref.WeakReference<View> hostRef;
        private final float density;
        private final Paint refract = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Object runtimeShader;              // android.graphics.RuntimeShader (API 33+)
        private android.graphics.Bitmap shaderWall;
        private final int[] loc = new int[2], lastLoc = {Integer.MIN_VALUE, 0};
        private float lastSx = -1f, lastSy = -1f;
        private final float[] sc = new float[2], preSc = new float[2];   // escala acumulada, sin crear arrays
        /** Radio de las esquinas, calculado para este tamaño del fondo (apply lo reutiliza). */
        int radiusW = -1, radiusH = -1;
        float radiusPx;
        /** Valores fijos que ya tiene el shader: solo se vuelven a poner si cambian. */
        private final float[] shaderCfg = new float[9];
        private boolean shaderCfgSet;
        /** Degradados del brillo, centrados en 0,0: se mueven con una matriz en vez de crearlos. */
        private RadialGradient glowFillGrad, glowRimGrad;
        private float glowFillR = -1f, glowRimR = -1f;
        private final android.graphics.Matrix glowM = new android.graphics.Matrix();
        private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glassVeil = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF box = new RectF();
        private final RectF tmp = new RectF();   // rectángulo de trabajo: sin crear uno en cada fotograma
        private LinearGradient veilG, glassVeilG, innerG, rimG;
        private RadialGradient sheenG;
        private boolean veilBlur;
        private final android.graphics.Matrix gm = new android.graphics.Matrix();
        private boolean blur;
        private float radius;
        private int alpha = 255;
        /**
         * Cuánto velo blanco se pone encima (1 = el de las carpetas). El menú de atajos ya trae su
         * propio fondo (claro u oscuro según el tema) y con el velo entero se vería lechoso.
         */
        float veilScale = 1f;
        /** Carpeta sin texturas avanzadas: no se dibuja nada, queda el fondo gris de MIUI tal cual. */
        boolean plain;
        // Brillo al tocar: posición del dedo, intensidad actual (0..1) y a la que va.
        private final Paint glowFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowRim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float glowX, glowY, glow;
        float glowTarget;
        private android.animation.ValueAnimator glowAnim;

        /** El dedo se mueve, baja (on) o se levanta (off). Se enciende rápido y se apaga despacio. */
        void press(float x, float y, boolean on) {
            glowX = x;
            glowY = y;
            float target = on ? 1f : 0f;
            if (target != glowTarget) {
                glowTarget = target;
                if (glowAnim != null) glowAnim.cancel();
                glowAnim = android.animation.ValueAnimator.ofFloat(glow, target);
                glowAnim.setDuration(on ? 160 : 520);
                glowAnim.setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f));
                glowAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(android.animation.ValueAnimator a) {
                        glow = (Float) a.getAnimatedValue();
                        invalidateSelf();
                    }
                });
                glowAnim.start();
            }
            if (glow > 0f) invalidateSelf();
        }

        /**
         * Una luz suave bajo el dedo (como en el cristal líquido de iOS 26) y el canto que se
         * enciende cerca del dedo. Formas con antialias propio, sin clipPath (que dentaría).
         */
        private void drawGlow(Canvas canvas, float r) {
            float k = glow * Math.min(1.5f, touchGlow);
            if (k <= 0.01f) return;
            float w = box.width(), h = box.height();
            float reach = Math.max(w, h);
            float x = Math.max(box.left, Math.min(box.right, box.left + glowX));
            float y = Math.max(box.top, Math.min(box.bottom, box.top + glowY));
            // Los degradados se crean una vez por tamaño (con el brillo máximo, k = 1,5) y la
            // intensidad va en el alfa del pincel: antes eran dos objetos nuevos por fotograma.
            float fr = Math.max(1f, Math.min(reach * 0.55f, 120f * density));
            if (glowFillGrad == null || fr != glowFillR) {
                glowFillR = fr;
                glowFillGrad = new RadialGradient(0f, 0f, fr,
                        new int[]{white(0x57), white(0x1E), white(0)}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
                glowFill.setShader(glowFillGrad);
            }
            glowM.setTranslate(x, y);
            glowFillGrad.setLocalMatrix(glowM);
            glowFill.setAlpha(Math.round(255f * (k / 1.5f) * alpha / 255f));
            canvas.drawRoundRect(box, r, r, glowFill);
            float sw = 1.4f * density;
            glowRim.setStyle(Paint.Style.STROKE);
            glowRim.setStrokeWidth(sw);
            float rr = Math.max(1f, Math.min(reach * 0.7f, 160f * density));
            if (glowRimGrad == null || rr != glowRimR) {
                glowRimR = rr;
                glowRimGrad = new RadialGradient(0f, 0f, rr, new int[]{white(0xE6), white(0)}, null, Shader.TileMode.CLAMP);
                glowRim.setShader(glowRimGrad);
            }
            glowRimGrad.setLocalMatrix(glowM);
            glowRim.setAlpha(Math.round(255f * Math.min(1f, k) * alpha / 255f));
            tmp.set(box.left + sw / 2f, box.top + sw / 2f, box.right - sw / 2f, box.bottom - sw / 2f);
            float er = Math.max(0f, r - sw / 2f);
            canvas.drawRoundRect(tmp, er, er, glowRim);
        }

        Glass(View host) {
            this.hostRef = new java.lang.ref.WeakReference<>(host);
            this.density = host.getResources().getDisplayMetrics().density;
            inner.setStyle(Paint.Style.STROKE);
            rim.setStyle(Paint.Style.STROKE);
            ALL.put(this, Boolean.TRUE);
        }

        /** Si la carpeta se mueve o cambia de escala, el fondo de debajo cambia: redibujar. */
        void track() {
            if (!REFRACTION) return;
            View host = hostRef.get();
            if (host == null) return;
            final android.view.ViewTreeObserver.OnPreDrawListener pre = new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    View h = hostRef.get();
                    if (wall == null || h == null || !h.isAttachedToWindow()) return true;
                    h.getLocationOnScreen(loc);
                    float[] sc = chainScale(h, preSc);
                    if (loc[0] != lastLoc[0] || loc[1] != lastLoc[1] || sc[0] != lastSx || sc[1] != lastSy) {
                        invalidateSelf();
                    }
                    return true;
                }
            };
            // El listener va en el ViewTreeObserver de la ventana: se pone al entrar en ella y se
            // quita al salir. Antes se ponía una vez y ya: si la carpeta salía de la ventana y
            // volvía (al reordenar el escritorio), se perdía; y las carpetas borradas seguían
            // colgadas de la ventana, retenidas y revisadas en cada fotograma.
            host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    v.getViewTreeObserver().removeOnPreDrawListener(pre);
                    v.getViewTreeObserver().addOnPreDrawListener(pre);
                    invalidateSelf();
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    v.getViewTreeObserver().removeOnPreDrawListener(pre);
                    // Si el padre se quedó el toque (arrastre del icono), no llega el UP: que no se
                    // quede el brillo encendido.
                    if (glowAnim != null) glowAnim.cancel();
                    glow = glowTarget = 0f;
                }
            });
            if (host.isAttachedToWindow()) host.getViewTreeObserver().addOnPreDrawListener(pre);
        }

        float getRadius() {
            return radius;
        }

        /** Devuelve si cambió algo (para no redibujar en cada onLayout). */
        boolean configure(boolean blur, float radius) {
            if (blur == this.blur && radius == this.radius) return false;
            this.blur = blur;
            this.radius = radius;
            rebuild(getBounds());
            return true;
        }

        @Override
        protected void onBoundsChange(Rect b) {
            rebuild(b);
        }

        private void rebuild(Rect b) {
            if (b.isEmpty()) return;
            box.set(b);
            float w = box.width(), h = box.height();
            float r = Math.min(radius, Math.min(w, h) / 2f);
            clip.reset();
            clip.addRoundRect(box, r, r, Path.Direction.CW);

            // Degradados de tamaño 1, creados una sola vez y colocados con una matriz: al
            // redimensionar una carpeta el tamaño cambia en cada fotograma y antes se creaban
            // cinco degradados nuevos cada vez.
            if (veilG == null || veilBlur != blur) {
                veilBlur = blur;
                int top = blur ? VEIL_TOP_BLUR : VEIL_TOP_FLAT, bottom = blur ? VEIL_BOTTOM_BLUR : VEIL_BOTTOM_FLAT;
                veilG = new LinearGradient(0, 0, 0, 1, white(top), white(bottom), Shader.TileMode.CLAMP);
                veil.setShader(veilG);
            }
            if (sheenG == null) {
                glassVeilG = new LinearGradient(0, 0, 0, 1,
                        white(VEIL_TOP_GLASS), white(VEIL_BOTTOM_GLASS), Shader.TileMode.CLAMP);
                glassVeil.setShader(glassVeilG);
                sheenG = new RadialGradient(0, 0, 1, new int[]{white(SHEEN_ALPHA), white(SHEEN_ALPHA / 3), white(0)},
                        new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
                sheen.setShader(sheenG);
                innerG = new LinearGradient(0, 0, 0, 1, white(INNER_ALPHA), white(0), Shader.TileMode.CLAMP);
                inner.setShader(innerG);
                inner.setStrokeWidth(INNER_DP * density);
                // A lo largo del eje x (0..1); la matriz lo gira hacia la diagonal de la caja.
                rimG = new LinearGradient(0, 0, 1, 0,
                        new int[]{white(RIM_TOP), white(RIM_SIDE), white(RIM_SIDE), white(RIM_BOTTOM)},
                        new float[]{0f, 0.38f, 0.66f, 1f}, Shader.TileMode.CLAMP);
                rim.setShader(rimG);
                rim.setStrokeWidth(RIM_DP * density);
            }
            gm.setScale(1f, h);
            gm.postTranslate(0f, box.top);
            veilG.setLocalMatrix(gm);
            glassVeilG.setLocalMatrix(gm);
            float sr = Math.max(w, h) * 0.85f;
            gm.setScale(sr, sr);
            gm.postTranslate(box.left + w * 0.22f, box.top);
            sheenG.setLocalMatrix(gm);
            gm.setScale(1f, Math.min(h, 48f * density));
            gm.postTranslate(0f, box.top);
            innerG.setLocalMatrix(gm);
            gm.setScale((float) Math.hypot(w, h), 1f);
            gm.postRotate((float) Math.toDegrees(Math.atan2(h, w)));
            gm.postTranslate(box.left, box.top);
            rimG.setLocalMatrix(gm);
        }

        private static int white(int a) {
            return (Math.max(0, Math.min(255, a)) << 24) | 0xFFFFFF;
        }

        @Override
        public void draw(Canvas canvas) {
            if (plain || box.isEmpty()) return;
            float w = box.width(), h = box.height();
            float r = Math.min(radius, Math.min(w, h) / 2f);
            boolean lens = REFRACTION && drawRefraction(canvas, w, h, r);
            int save = canvas.save();
            if (!lens) canvas.clipPath(clip);       // con lente, sin recorte: el clipPath no tiene antialias
            int veilAlpha = Math.round(alpha * veilScale);
            if (lens && blur) {
                glassVeil.setAlpha(veilAlpha);
                canvas.drawRoundRect(box, r, r, glassVeil);
            } else {
                veil.setAlpha(veilAlpha);
                canvas.drawRoundRect(box, r, r, veil);
            }
            sheen.setAlpha(alpha);
            canvas.drawRoundRect(box, r, r, sheen);
            if (!lens) {
                float iw = inner.getStrokeWidth();
                tmp.set(box.left + iw * 1.2f, box.top + iw * 1.2f, box.right - iw * 1.2f, box.bottom - iw * 1.2f);
                float ir = Math.max(0f, r - iw * 1.2f);
                inner.setAlpha(alpha);
                canvas.drawRoundRect(tmp, ir, ir, inner);
            }
            canvas.restoreToCount(save);

            // Con lente, el canto ya sale iluminado del color de detrás: el blanco solo lo afina.
            float rw = rim.getStrokeWidth();
            tmp.set(box.left + rw / 2f, box.top + rw / 2f, box.right - rw / 2f, box.bottom - rw / 2f);
            float er = Math.max(0f, r - rw / 2f);
            rim.setAlpha(lens ? 0 : alpha);
            canvas.drawRoundRect(tmp, er, er, rim);
            drawGlow(canvas, r);
        }

        /** Fondo, refracción y canto teñido. Devuelve false si no hay lente (sin fondo o sin AGSL). */
        private boolean drawRefraction(Canvas canvas, float w, float h, float r) {
            android.graphics.Bitmap bmp = wall;
            if (bmp == null || shaderBroken || android.os.Build.VERSION.SDK_INT < 33
                    || !canvas.isHardwareAccelerated()) return false;
            try {
                android.graphics.RuntimeShader rs;
                if (runtimeShader == null) {
                    runtimeShader = GlassShader.create();
                    shaderCfgSet = false;
                    shaderWall = null;
                }
                rs = (android.graphics.RuntimeShader) runtimeShader;
                if (shaderWall != bmp) {
                    rs.setInputShader("wall", GlassShader.linear(bmp));
                    shaderWall = bmp;
                }
                // Qué parte del fondo hay bajo la carpeta: el fondo cubre la pantalla (center-crop)
                // y se desplaza horizontalmente entre páginas según wallOffsetX.
                View host = hostRef.get();
                if (host == null) return false;
                View root = host.getRootView();
                float sw = root.getWidth(), sh = root.getHeight();
                if (sw <= 0f || sh <= 0f) return false;
                float bw = bmp.getWidth(), bh = bmp.getHeight();
                float s = Math.max(sw / bw, sh / bh);
                float left = (bw * s - sw) * wallOffsetX, top = (bh * s - sh) * wallOffsetY;
                host.getLocationOnScreen(loc);
                float[] sc = chainScale(host, this.sc);
                lastLoc[0] = loc[0];
                lastLoc[1] = loc[1];
                lastSx = sc[0];
                lastSy = sc[1];
                // box.left/top: la caja puede ir con margen dentro de la vista (el dock).
                // Solo esto cambia en cada fotograma; cada uniform que se pone rehace el búfer del shader.
                rs.setFloatUniform("origin", (loc[0] + box.left * sc[0] + left) / s, (loc[1] + box.top * sc[1] + top) / s);
                rs.setFloatUniform("scale", sc[0] / s, sc[1] / s);
                boolean cfg = !shaderCfgSet || shaderCfg[3] != (blur ? 1f : 0f) || shaderCfg[4] != liquidH
                        || shaderCfg[5] != liquidAmt || shaderCfg[6] != liquidChroma || shaderCfg[7] != liquidLight;
                if (cfg) {
                    shaderCfgSet = true;
                    shaderCfg[3] = blur ? 1f : 0f;
                    shaderCfg[4] = liquidH;
                    shaderCfg[5] = liquidAmt;
                    shaderCfg[6] = liquidChroma;
                    shaderCfg[7] = liquidLight;
                    setConfigUniforms(rs);
                }
                // Al redimensionar una carpeta el tamaño cambia en cada fotograma: solo estos cinco.
                if (cfg || shaderCfg[0] != w || shaderCfg[1] != h || shaderCfg[2] != r) {
                    shaderCfg[0] = w;
                    shaderCfg[1] = h;
                    shaderCfg[2] = r;
                    setSizeUniforms(rs, w, h, r);
                }
                refract.setShader(rs);
                refract.setAlpha(alpha);
                int save = canvas.save();
                try {
                    canvas.translate(box.left, box.top);
                    canvas.drawRect(0, 0, w, h, refract);
                } finally {
                    canvas.restoreToCount(save);      // aunque falle, el lienzo queda como estaba
                }
                return true;
            } catch (Throwable t) {
                runtimeShader = null;
                shaderBroken = true;          // no reintentar en cada fotograma
                XposedBridge.log("IceHome: refracción desactivada");
                XposedBridge.log(t);
                return false;
            }
        }

        /** Los uniforms que dependen del tamaño y el radio. */
        private void setSizeUniforms(android.graphics.RuntimeShader rs, float w, float h, float r) {
            rs.setFloatUniform("size", w, h);
            rs.setFloatUniform("radius", r);
            rs.setFloatUniform("band", Math.min(BAND_DP * density, Math.min(w, h) * BAND_MAX_FRACTION));
            float want = liquidH * density;
            float lensH = Math.min(want, Math.min(w, h) / 3f);
            rs.setFloatUniform("refrH", lensH);
            rs.setFloatUniform("refrAmt", want > 0f ? liquidAmt * density * lensH / want : 0f);
        }

        /** Los uniforms de los ajustes (no cambian de un fotograma a otro). */
        private void setConfigUniforms(android.graphics.RuntimeShader rs) {
            {
                rs.setFloatUniform("disp", STRENGTH_DP * density);
                rs.setFloatUniform("dispersion", DISPERSION);
                rs.setFloatUniform("hasWall", 1f);
                rs.setFloatUniform("fill", blur ? 1f : 0f);       // sin texturas avanzadas: solo el canto
                rs.setFloatUniform("bandAlpha", REFRACT_ALPHA);
                rs.setFloatUniform("tone", GLASS_TONE);
                rs.setFloatUniform("darker", GLASS_DARKER);
                rs.setFloatUniform("whiteMix", GLASS_WHITE);
                rs.setFloatUniform("stretch", 1f);
                rs.setFloatUniform("overshoot", EDGE_OVER_DP * density);
                rs.setFloatUniform("edgeSat", EDGE_SAT);
                rs.setFloatUniform("innerDark", INNER_DARK);
                rs.setFloatUniform("mag", blur ? MAGNIFY : 0f);
                rs.setFloatUniform("rimw", TINT_RIM_DP * density);
                rs.setFloatUniform("glow", GLOW_DP * density);
                rs.setFloatUniform("sat", GLASS_SAT);
                rs.setFloatUniform("rimGain", TINT_RIM_GAIN);
                rs.setFloatUniform("chroma", liquidChroma);
                rs.setFloatUniform("light", liquidLight);
                rs.setFloatUniform("active", 0f);
            }
        }

        @Override
        public void setAlpha(int a) {
            alpha = a;
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private static float[] chainScale(View v, float[] out) {
        float sx = 1f, sy = 1f;
        for (View c = v; c != null; c = c.getParent() instanceof View ? (View) c.getParent() : null) {
            sx *= c.getScaleX();
            sy *= c.getScaleY();
        }
        out[0] = sx;
        out[1] = sy;
        return out;
    }

    private GlassFolder() {
    }
}
