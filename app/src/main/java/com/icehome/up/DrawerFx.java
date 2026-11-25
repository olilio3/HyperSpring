package com.icehome.up;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import java.lang.ref.WeakReference;

import com.icehome.up.xp.XC_MethodHook;
import com.icehome.up.xp.XposedHelpers;

/**
 * Cajón de apps al estilo HyperOS 4.
 * - Profundidad: mientras se abre el cajón, el escritorio y el dock se alejan un poco (hasta el
 *   92 %) y se desenfocan.
 * - Desenfoque propio y progresivo: crece con el dedo. Usa el desenfoque de vista de Xiaomi
 *   (setMiBackgroundBlurMode/Radius, el de las carpetas), que funciona aunque Android tenga
 *   apagado el desenfoque de ventanas (pasa en el Redmi 14C). Va en la capa de oscurecido del
 *   cajón, que ocupa toda la pantalla y no se mueve. El de Xiaomi/Kashi va pegado al cajón, que
 *   sube desde abajo, así que con el nuestro encendido se apaga.
 * - Pestañas de categorías en cápsulas de cristal: velo translúcido, brillo arriba y un borde
 *   de luz en diagonal. La elegida, azul.
 * - Buscador flotante de cristal: la misma cápsula con desenfoque de verdad detrás, y sin la
 *   raya que lo separaba de la lista. Entra con un pequeño rebote antes de que acabe la
 *   animación (el launcher lo tenía oculto hasta el último fotograma y salía de golpe).
 * - Los iconos no se cortan en seco arriba y abajo: se desvanecen al irse (borde difuminado).
 */
final class DrawerFx {

    /** Fuerza del desenfoque (0 = el de Xiaomi; 1 = normal). Lo pone Tune. */
    static volatile float blur = 1f;
    /** Tamaño del escritorio con el cajón del todo abierto. */
    private static final float BACK_SCALE = 0.92f;
    /** Radio con fuerza 1 (Xiaomi usa 92-128 en carpetas y menús): ligero-mediano. */
    private static final float RADIUS = 60f;
    private static final int BLUE = 0xFF0A84FF;
    /** Marca las pestañas que ya son cápsula (getTag de la vista). */
    private static final String PILL = "hs_pill";

    /** Si el escritorio está tocado por nosotros (para devolverlo solo si lo tocamos). */
    private static boolean touched;
    private static int lastStep = -1;
    /** Métodos de Xiaomi en View (no están en el SDK); se buscan la primera vez. */
    private static java.lang.reflect.Method blurMode, blurRadius, passWindow, viewMode;
    /** Radio del desenfoque detrás del buscador. */
    private static final int BAR_BLUR = 40;
    /** Buscador del cajón y la raya de encima; se buscan una vez. */
    private static WeakReference<View> barRef, lineRef;
    private static boolean noBar;
    /** A partir de cuánto abierto se enseña el buscador (el launcher espera al final). */
    private static final float BAR_IN = 0.75f;
    /** Si el buscador lo hemos enseñado nosotros antes de tiempo; y lo abierto del fotograma anterior. */
    private static boolean barForced;
    private static float lastOpen;

    static void install(ClassLoader cl) {
        Class<?> ctl = XposedHelpers.findClass("com.miui.home.launcher.allapps.AllAppsTransitionController", cl);
        XposedHelpers.findAndHookMethod(ctl, "setProgress", float.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                // progress 1 = cajón cerrado (abajo del todo), 0 = abierto.
                float open = 1f - Math.max(0f, Math.min(1f, (Float) p.args[0]));
                if (open <= 0f && !touched) return;
                Object launcher = XposedHelpers.getObjectField(p.thisObject, "mLauncher");
                View ws = (View) XposedHelpers.callMethod(launcher, "getWorkspace");
                View hs = (View) XposedHelpers.callMethod(launcher, "getHotSeats");
                float s = 1f - (1f - BACK_SCALE) * open;
                back(ws, s);
                back(hs, s);
                if (blur > 0f) blurTo(p.thisObject, open);
                if (open > 0f && launcher instanceof android.app.Activity) searchBar((android.app.Activity) launcher);
                barIn(open);
                lastOpen = open;
                touched = open > 0f;
            }
        });
        // Con el desenfoque propio, el de Xiaomi fuera (si no, se suman los dos).
        try {
            XposedHelpers.findAndHookMethod("com.miui.home.launcher.common.Utilities", cl, "isDrawerBlurEnabled", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (blur > 0f) p.setResult(false);
                }
            });
        } catch (Throwable t) {
            Log.e("cajón: no se pudo apagar el desenfoque de Xiaomi", t);
        }

        Class<?> titles = XposedHelpers.findClass("com.miui.home.launcher.allapps.category.CategoryTitleAdapter", cl);
        XposedHelpers.findAndHookMethod(titles, "getTitleView", android.content.Context.class, int.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (!(p.getResult() instanceof TextView)) return;
                TextView tv = (TextView) p.getResult();
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                XposedHelpers.callMethod(tv, "setSelectedColor", 0xFFFFFFFF);
                XposedHelpers.callMethod(tv, "setNormalColor", 0xF2FFFFFF);
                tv.setShadowLayer(tv.getResources().getDisplayMetrics().density * 2f, 0f, 0f, 0x33000000);
                tv.setTag(PILL);
                pill(tv, false);
            }
        });
        XposedHelpers.findAndHookMethod(titles, "getIndicator", android.content.Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                // La rayita de debajo sobra con las cápsulas.
                if (p.getResult() instanceof View) ((View) p.getResult()).setVisibility(View.INVISIBLE);
            }
        });
        Class<?> title = XposedHelpers.findClass("com.miui.home.launcher.allapps.category.CategoryTitleAdapter$2", cl);
        XposedHelpers.findAndHookMethod(title, "onSelected", int.class, int.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                pill((TextView) p.thisObject, true);
            }
        });
        XposedHelpers.findAndHookMethod(title, "onDeselected", int.class, int.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                pill((TextView) p.thisObject, false);
            }
        });
        // Borde difuminado de la lista: Android lo pinta solo, y solo en el lado por el que queda
        // algo por ver (arriba, cuando has bajado; abajo, mientras queden iconos).
        try {
            com.icehome.up.xp.XposedBridge.hookAllConstructors(XposedHelpers.findClass(
                    "com.miui.home.launcher.allapps.AllAppsRecyclerView", cl), new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    View v = (View) p.thisObject;
                    v.setVerticalFadingEdgeEnabled(true);
                    v.setFadingEdgeLength(Math.round(56 * v.getResources().getDisplayMetrics().density));
                }
            });
        } catch (Throwable t) {
            Log.e("cajón: sin borde difuminado", t);
        }
        Log.i("cajón estilo HyperOS 4");
    }

    private static void back(View v, float s) {
        if (v == null) return;
        v.setScaleX(s);
        v.setScaleY(s);
    }

    /**
     * Desenfoque según lo abierto que esté el cajón (con curva: arranca suave). Solo se manda
     * cuando cambia el radio entero, para no repetir trabajo en cada fotograma.
     */
    private static void blurTo(Object ctl, float open) {
        float e = open * open * (3f - 2f * open);
        int r = Math.round(RADIUS * blur * e);
        if (r == lastStep) return;
        Object o = XposedHelpers.getObjectField(ctl, "mScrimView");
        if (!(o instanceof View)) return;
        View v = (View) o;
        try {
            miBlur();
            // Mismos pasos que Xiaomi en las carpetas. «PassWindow» deja que el desenfoque llegue
            // al fondo de pantalla, que es otra ventana; sin él no se ve nada.
            if (lastStep <= 0 && r > 0) {
                blurMode.invoke(v, 1);
                passWindow.invoke(v, true);
                viewMode.invoke(v, 1);
            }
            blurRadius.invoke(v, r);
            if (r == 0) { // cajón cerrado: fuera el desenfoque del todo
                blurMode.invoke(v, 0);
                passWindow.invoke(v, false);
                viewMode.invoke(v, 0);
            }
            lastStep = r;
        } catch (Throwable t) {
            blur = 0f; // sin la API de Xiaomi: no lo intentamos en cada fotograma
            Log.e("cajón: no se pudo desenfocar", t);
        }
    }

    /** Busca los métodos de desenfoque de Xiaomi en View (no están en el SDK). Lanza si no existen. */
    private static void miBlur() throws NoSuchMethodException {
        if (blurRadius != null) return;
        blurMode = View.class.getMethod("setMiBackgroundBlurMode", int.class);
        passWindow = View.class.getMethod("setPassWindowBlurEnabled", boolean.class);
        viewMode = View.class.getMethod("setMiViewBlurMode", int.class);
        blurRadius = View.class.getMethod("setMiBackgroundBlurRadius", int.class);
    }

    /**
     * Buscador del cajón en cápsula de cristal flotante. El launcher a veces le vuelve a poner su
     * fondo, así que se mira en cada apertura (sale barato: solo compara el fondo).
     */
    private static void searchBar(android.app.Activity a) {
        if (noBar) return;
        View bar = barRef == null ? null : barRef.get();
        if (bar == null) {
            int id = a.getResources().getIdentifier("search_bar_drawer_layout", "id", a.getPackageName());
            int line = a.getResources().getIdentifier("all_apps_search_bar_divider", "id", a.getPackageName());
            if (id == 0) { noBar = true; return; }   // otro launcher: nada que hacer
            bar = a.findViewById(id);
            if (bar == null) return;
            barRef = new WeakReference<>(bar);
            if (line != 0) lineRef = new WeakReference<>(a.findViewById(line));
        }
        View line = lineRef == null ? null : lineRef.get();
        if (line != null && line.getAlpha() != 0f) line.setAlpha(0f);
        if (bar.getBackground() instanceof Pill) return;
        float dp = bar.getResources().getDisplayMetrics().density;
        bar.setBackground(new Pill(dp, 0, false));
        // El desenfoque de Xiaomi sigue el contorno de la vista: cápsula.
        bar.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), v.getHeight() / 2f);
            }
        });
        bar.setClipToOutline(true);
        try {
            miBlur();
            blurMode.invoke(bar, 1);
            passWindow.invoke(bar, true);
            viewMode.invoke(bar, 1);
            blurRadius.invoke(bar, BAR_BLUR);
        } catch (Throwable t) {
            Log.e("cajón: buscador sin desenfoque", t);
        }
    }

    /**
     * Entrada del buscador. Su padre (SearchBar) está oculto (GONE) toda la animación y el
     * launcher lo enseña en el último fotograma. Lo enseñamos al 75 % mientras sube, con un
     * pequeño rebote de escala y posición; la transparencia ya la lleva el launcher.
     */
    private static void barIn(float open) {
        View bar = barRef == null ? null : barRef.get();
        if (bar == null || !(bar.getParent() instanceof View)) return;
        View parent = (View) bar.getParent();
        if (open >= 1f) { barForced = false; return; }        // ya es cosa del launcher
        if (barForced && open < BAR_IN) {                     // el dedo se arrepintió: como estaba
            barForced = false;
            parent.setVisibility(View.GONE);
            return;
        }
        if (barForced || open < BAR_IN || open <= lastOpen || parent.getVisibility() == View.VISIBLE) return;
        barForced = true;
        parent.setVisibility(View.VISIBLE);
        float dp = bar.getResources().getDisplayMetrics().density;
        bar.setScaleX(0.9f);
        bar.setScaleY(0.9f);
        bar.setTranslationY(14 * dp);
        bar.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(420)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
    }

    /** Pone el fondo de cápsula y el relleno (el launcher pisa el relleno al colocar las pestañas). */
    private static void pill(TextView tv, boolean on) {
        if (tv == null || !PILL.equals(tv.getTag())) return;
        float dp = tv.getResources().getDisplayMetrics().density;
        int gap = Math.round(4 * dp);                   // separación entre cápsulas
        Drawable cur = tv.getBackground();
        if (cur instanceof Pill) ((Pill) cur).set(on);
        else tv.setBackground(new Pill(dp, gap, on));
        tv.setPadding(gap + Math.round(14 * dp), Math.round(7 * dp), gap + Math.round(14 * dp), Math.round(7 * dp));
    }

    /**
     * Cápsula de cristal sin capturar el fondo (barato): velo translúcido, brillo en la mitad de
     * arriba y un borde de luz en diagonal, fuerte arriba a la izquierda y suave abajo a la
     * derecha, como la línea del cristal del centro de control.
     */
    private static final class Pill extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), sheen = new Paint(Paint.ANTI_ALIAS_FLAG),
                rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float dp;
        private final int gap;
        private boolean on;

        Pill(float dp, int gap, boolean on) {
            this.dp = dp;
            this.gap = gap;
            this.on = on;
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(Math.max(1f, 1.1f * dp));
        }

        void set(boolean on) {
            if (this.on == on) return;
            this.on = on;
            onBoundsChange(getBounds());
            invalidateSelf();
        }

        @Override
        protected void onBoundsChange(Rect b) {
            r.set(b.left + gap, b.top, b.right - gap, b.bottom);
            r.inset(rim.getStrokeWidth() / 2f, rim.getStrokeWidth() / 2f);
            fill.setColor(on ? BLUE : 0x26FFFFFF);
            sheen.setShader(new LinearGradient(0, r.top, 0, r.centerY(),
                    on ? 0x40FFFFFF : 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            rim.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                    new int[]{0xB3FFFFFF, 0x1AFFFFFF, 0x1AFFFFFF, 0x66FFFFFF}, new float[]{0f, 0.35f, 0.65f, 1f},
                    Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas c) {
            float rad = r.height() / 2f;
            c.drawRoundRect(r, rad, rad, fill);
            c.drawRoundRect(r, rad, rad, sheen);
            c.drawRoundRect(r, rad, rad, rim);
        }

        @Override
        public void setAlpha(int a) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private DrawerFx() {
    }
}
