package com.icehome.up;

import android.content.SharedPreferences;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Tile de los ajustes rápidos: pausa y reanuda HyperSpring con un toque, sin abrir la app ni
 * reiniciar nada.
 *
 * <p>En pausa vuelven las animaciones de HyperOS al abrir y cerrar apps, no hay telón y las
 * tarjetas de recientes no se doblan. Sirve para un juego, para grabar la pantalla o para
 * comprobar si un fallo viene de HyperSpring. El cristal (carpetas, centro de control) no cambia:
 * es un aspecto, no una animación que pueda molestar.
 *
 * <p>Funciona igual que los deslizadores: guarda un ajuste ({@code icehome_paused}) y avisa al
 * launcher y a la interfaz con {@link Remote#send}; ellos lo leen al momento.
 * Mantener pulsado el tile abre la app (intent QS_TILE_PREFERENCES en el manifiesto).
 */
public class PauseTile extends TileService {

    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        boolean nowPaused = !paused(this);
        setPaused(this, nowPaused);
        refresh();
    }

    /** ¿Está HyperSpring en pausa? */
    static boolean paused(android.content.Context c) {
        return "1".equals(c.getSharedPreferences(PrefsProvider.PREFS, 0)
                .getString(com.icehome.up.telon.Tune.PAUSE_KEY, null));
    }

    /** Pausa o reanuda, y avisa a los procesos enganchados. También lo usa la app. */
    static void setPaused(android.content.Context c, boolean paused) {
        SharedPreferences.Editor e = c.getSharedPreferences(PrefsProvider.PREFS, 0).edit();
        if (paused) e.putString(com.icehome.up.telon.Tune.PAUSE_KEY, "1");
        else e.remove(com.icehome.up.telon.Tune.PAUSE_KEY);
        e.commit();
        Remote.send(c, Remote.TUNE);
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean p = paused(this);
        I18n.init(this);                      // el tile puede arrancar sin que se haya abierto la app
        // Encendido (color) = HyperSpring funcionando; apagado = en pausa.
        t.setState(p ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
        t.setLabel("HyperSpring");
        if (Build.VERSION.SDK_INT >= 29) {
            t.setSubtitle(p ? I18n.t("En pausa", "Paused") : I18n.t("Activo", "On"));
        }
        t.updateTile();
    }
}
