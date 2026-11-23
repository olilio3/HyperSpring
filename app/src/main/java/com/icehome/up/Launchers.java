package com.icehome.up;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

/**
 * Los launchers que HyperSpring sabe enganchar.
 *
 * <ul>
 * <li><b>HyperOS</b> ({@code com.miui.home}), también con el HyperOS Launcher Mod de Kashi.</li>
 * <li><b>POCO Launcher</b> ({@code com.mi.android.globallauncher}): es el mismo launcher de
 * Xiaomi por dentro, con las mismas clases {@code com.miui.home.*}, así que el genio, los muelles,
 * las carpetas de cristal, el menú de atajos y lo demás funcionan igual. No tiene la pila de
 * widgets ni el redimensionado de carpetas, que son del mod de Kashi.</li>
 * </ul>
 *
 * <p>Basado en el trabajo del fork comunitario de ToolDroidYT (github.com/ToolDroidYT/hyperspring),
 * que analizó POCO Launcher (RELEASE-7.50.06.2597). Ver NOTICE.
 */
public final class Launchers {

    public static final String HYPEROS = "com.miui.home";
    public static final String POCO = "com.mi.android.globallauncher";
    /** Todos, en orden de preferencia. */
    public static final String[] ALL = {HYPEROS, POCO};

    /** ¿Es uno de los launchers que se enganchan? */
    public static boolean is(String pkg) {
        return HYPEROS.equals(pkg) || POCO.equals(pkg);
    }

    /** Nombre para el registro y la app. */
    static String name(String pkg) {
        return POCO.equals(pkg) ? "POCO Launcher" : "HyperOS Launcher";
    }

    /**
     * ¿Puede tener las funciones del mod de Kashi (pila de widgets, redimensionado de carpetas)?
     * Solo el de HyperOS; aunque no lleve el mod, esas funciones ya se apartan solas si no
     * encuentran sus clases.
     */
    static boolean kashiFeatures(String pkg) {
        return HYPEROS.equals(pkg);
    }

    /**
     * El launcher de este móvil: el que el sistema abre al pulsar Inicio, si es uno de los
     * nuestros; si no, el primero instalado. Para la app (estado, reiniciar, informe de fallos).
     */
    static String current(Context c) {
        PackageManager pm = c.getPackageManager();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo r = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            String pkg = r != null && r.activityInfo != null ? r.activityInfo.packageName : null;
            if (is(pkg)) return pkg;
        } catch (Throwable ignored) {
        }
        for (String p : ALL) {
            try {
                pm.getPackageInfo(p, 0);
                return p;
            } catch (Throwable ignored) {
            }
        }
        return HYPEROS;
    }

    /**
     * El launcher de HyperOS 4 (8.x) está hecho en Flutter y no trae código Java (hasCode=false):
     * LSPosed no carga el módulo ahí y no hay nada que enganchar (#30, #33).
     */
    static boolean noJava(Context c) {
        try {
            return (c.getPackageManager().getApplicationInfo(current(c), 0).flags
                    & android.content.pm.ApplicationInfo.FLAG_HAS_CODE) == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private Launchers() {
    }
}
