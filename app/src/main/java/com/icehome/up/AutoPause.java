package com.icehome.up;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.Map;

/**
 * Pausa automática: HyperSpring se pausa solo (igual que con el tile de los ajustes rápidos)
 * cuando el móvil va justo, y se reanuda solo al acabar:
 * <ul>
 * <li><b>Calor:</b> el sistema avisa de que el móvil está caliente de verdad (nivel «grave» o
 * más, cuando ya baja el rendimiento).</li>
 * <li><b>Juegos:</b> Game Turbo está en marcha (HyperOS lo apunta en el ajuste
 * {@code gb_boosting}).</li>
 * <li><b>Ahorro de batería:</b> está encendido. Apagado de serie: hay quien lo lleva puesto
 * siempre y se quedaría sin animaciones sin saber por qué.</li>
 * </ul>
 *
 * <p>Vive en cada proceso enganchado (launcher e interfaz del sistema). No pregunta nada en cada
 * animación: se apunta a los avisos del sistema y guarda el resultado en memoria; cuando cambia,
 * llama a {@code onChange}, que relee los ajustes como si hubieras tocado el tile.
 */
public final class AutoPause {

    public static final String KEY_HEAT = "icehome_auto_heat", KEY_GAME = "icehome_auto_game",
            KEY_SAVER = "icehome_auto_saver";
    /** Ajuste de HyperOS/MIUI: 1 mientras Game Turbo acelera un juego. */
    private static final String GAME_SETTING = "gb_boosting";

    // Lo que quiere el usuario (ajustes) y lo que pasa ahora mismo (avisos del sistema).
    private static volatile boolean wantHeat = true, wantGame = true, wantSaver;
    private static volatile boolean hot, game, saver;
    private static boolean installed;
    private static boolean lastActive;
    private static boolean systemUi;
    private static Runnable onChange;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** ¿Hay que estar en pausa ahora? Solo mira memoria: se puede llamar desde cualquier sitio. */
    public static boolean active() {
        return (wantHeat && hot) || (wantGame && game) || (wantSaver && saver);
    }

    /** Lee los tres interruptores de los ajustes guardados (los llama Tune al releer). */
    public static void configure(Map<String, String> saved) {
        wantHeat = flag(saved, KEY_HEAT, true);
        wantGame = flag(saved, KEY_GAME, true);
        wantSaver = flag(saved, KEY_SAVER, false);
        lastActive = active();
    }

    private static boolean flag(Map<String, String> m, String key, boolean def) {
        try {
            String v = m == null ? null : m.get(key);
            return v == null ? def : Float.parseFloat(v.trim()) >= 0.5f;
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * Se apunta a los avisos del sistema. Una vez por proceso, en el hilo principal, con el
     * contexto de la aplicación. Cada parte va por separado: si una falla, las demás siguen.
     *
     * @param inSystemUi true en la interfaz del sistema (para el registro)
     * @param changed    se llama en el hilo principal cuando la pausa empieza o acaba
     */
    public static synchronized void install(final Context c, boolean inSystemUi, Runnable changed) {
        if (installed) return;
        installed = true;
        systemUi = inSystemUi;
        onChange = changed;
        final PowerManager pm = c.getSystemService(PowerManager.class);

        // Ahorro de batería.
        try {
            saver = pm != null && pm.isPowerSaveMode();
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent i) {
                    try {
                        saver = pm != null && pm.isPowerSaveMode();
                    } catch (Throwable ignored) {
                    }
                    update();
                }
            };
            IntentFilter f = new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            // Es un aviso que solo puede mandar el sistema: no hace falta exportar el receptor.
            if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
            else c.registerReceiver(r, f);
        } catch (Throwable t) {
            log("pausa automática: sin aviso de ahorro de batería (" + t + ")");
        }

        // Calor (Android 10+): el sistema llama al listener cada vez que cambia el nivel.
        try {
            if (Build.VERSION.SDK_INT >= 29 && pm != null) {
                hot = pm.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE;
                pm.addThermalStatusListener(c.getMainExecutor(), new PowerManager.OnThermalStatusChangedListener() {
                    @Override
                    public void onThermalStatusChanged(int status) {
                        hot = status >= PowerManager.THERMAL_STATUS_SEVERE;
                        update();
                    }
                });
            }
        } catch (Throwable t) {
            log("pausa automática: sin aviso de temperatura (" + t + ")");
        }

        // Game Turbo: se observa su ajuste (el sistema avisa cuando cambia).
        try {
            final android.content.ContentResolver cr = c.getContentResolver();
            game = Settings.Secure.getInt(cr, GAME_SETTING, 0) == 1;
            cr.registerContentObserver(Settings.Secure.getUriFor(GAME_SETTING), false, new ContentObserver(MAIN) {
                @Override
                public void onChange(boolean self) {
                    try {
                        game = Settings.Secure.getInt(cr, GAME_SETTING, 0) == 1;
                    } catch (Throwable ignored) {
                    }
                    update();
                }
            });
        } catch (Throwable t) {
            log("pausa automática: sin aviso de Game Turbo (" + t + ")");
        }
        lastActive = active();
        if (lastActive) log("pausa automática: en pausa (" + why() + ")");
    }

    /** Algo ha cambiado: si la pausa empieza o acaba, se anota y se releen los ajustes. */
    private static void update() {
        boolean now = active();
        if (now == lastActive) return;
        lastActive = now;
        log(now ? "pausa automática: en pausa (" + why() + ")" : "pausa automática: se reanuda");
        Runnable r = onChange;
        if (r == null) return;
        try {
            r.run();
        } catch (Throwable t) {
            log("pausa automática: no se pudo aplicar (" + t + ")");
        }
    }

    private static String why() {
        StringBuilder b = new StringBuilder();
        if (wantHeat && hot) b.append("móvil caliente");
        if (wantGame && game) b.append(b.length() > 0 ? ", " : "").append("Game Turbo");
        if (wantSaver && saver) b.append(b.length() > 0 ? ", " : "").append("ahorro de batería");
        return b.toString();
    }

    private static void log(String msg) {
        if (systemUi) com.icehome.up.telon.Log.i(msg);
        else Log.i(msg);
    }

    private AutoPause() {
    }
}
