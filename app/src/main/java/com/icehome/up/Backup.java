package com.icehome.up;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;

/**
 * Exportar e importar los ajustes en un archivo de texto (JSON). Sirve para guardarlos antes de
 * probar cosas, pasarlos a otro móvil o adjuntarlos a un reporte de error: por eso el archivo
 * lleva también la versión del módulo, el móvil y con qué API se cargó. No lleva datos personales.
 */
final class Backup {

    static final String FILE_NAME = "hyperspring-ajustes.json";
    private static final String MARK = "HyperSpring";

    /** Todas las tablas de ajustes del módulo: launcher, telón y centro de control. */
    private static Object[][][] tables() {
        return new Object[][][]{Tune.ITEMS, com.icehome.up.telon.Tune.ITEMS, com.icehome.up.cc.Tune.ITEMS};
    }

    /** El ajuste con esa clave, o null si el módulo no lo conoce. */
    private static Object[] item(String key) {
        for (Object[][] t : tables()) {
            for (Object[] it : t) if (it[0].equals(key)) return it;
        }
        return null;
    }

    /** Texto JSON con los ajustes guardados (los que no aparecen están en su valor por defecto). */
    static String export(Context c) throws Exception {
        JSONObject root = new JSONObject();
        root.put("app", MARK);
        root.put("version", Version.NAME);
        root.put("versionCode", Version.CODE);
        root.put("device", Build.MANUFACTURER + " " + Build.MODEL);
        root.put("android", Build.VERSION.RELEASE);
        SharedPreferences state = c.getSharedPreferences("state", 0);
        root.put("launcher", String.valueOf(state.getString("launcher_seen", "")));
        root.put("systemui", String.valueOf(state.getString("systemui_seen", "")));
        JSONObject s = new JSONObject();
        for (Map.Entry<String, ?> e : c.getSharedPreferences(PrefsProvider.PREFS, 0).getAll().entrySet()) {
            // La lista de apps sin genio es texto, no un número de las tablas, pero también va.
            if (item(e.getKey()) != null || Exclude.KEY.equals(e.getKey())) s.put(e.getKey(), String.valueOf(e.getValue()));
        }
        root.put("settings", s);
        return root.toString(2);
    }

    /** Comienzo de un código para compartir. El 1 es la versión del formato, por si cambia. */
    static final String CODE_PREFIX = "HS1:";

    /**
     * Código corto para compartir la configuración en un mensaje (Telegram, Reddit…): los ajustes
     * en JSON, comprimidos y en Base64. A diferencia de {@link #export}, no lleva el móvil ni la
     * lista de apps sin genio: esa lista diría qué apps tiene instaladas quien lo comparte.
     */
    static String shareCode(Context c) throws Exception {
        JSONObject root = new JSONObject();
        root.put("app", MARK);
        root.put("version", Version.NAME);
        JSONObject s = new JSONObject();
        for (Map.Entry<String, ?> e : c.getSharedPreferences(PrefsProvider.PREFS, 0).getAll().entrySet()) {
            if (item(e.getKey()) != null) s.put(e.getKey(), String.valueOf(e.getValue()));
        }
        root.put("settings", s);
        byte[] raw = root.toString().getBytes("UTF-8");
        java.util.zip.Deflater d = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION);
        d.setInput(raw);
        d.finish();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        d.end();
        return CODE_PREFIX + android.util.Base64.encodeToString(out.toByteArray(),
                android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE);
    }

    /**
     * Busca un código {@code HS1:…} dentro de un texto (puede venir con el mensaje alrededor) y
     * devuelve el JSON de dentro, o null si no hay ninguno válido.
     */
    static String fromCode(String text) {
        if (text == null) return null;
        int at = text.indexOf(CODE_PREFIX);
        if (at < 0) return null;
        int end = at + CODE_PREFIX.length();
        while (end < text.length() && isCodeChar(text.charAt(end))) end++;
        try {
            byte[] packed = android.util.Base64.decode(text.substring(at + CODE_PREFIX.length(), end),
                    android.util.Base64.URL_SAFE);
            java.util.zip.Inflater inf = new java.util.zip.Inflater();
            inf.setInput(packed);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            // Unos pocos KB como mucho: se corta en 256 KB por si alguien pega algo raro.
            while (!inf.finished() && out.size() < (256 << 10)) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                out.write(buf, 0, n);
            }
            inf.end();
            return out.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isCodeChar(char ch) {
        return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                || ch == '-' || ch == '_' || ch == '=';
    }

    /**
     * Sustituye los ajustes por los del archivo. Solo acepta claves que el módulo conoce y
     * valores numéricos, recortados al mínimo y máximo de cada ajuste: un archivo viejo, de otra
     * versión o tocado a mano no puede dejar nada en un estado raro.
     *
     * @return cuántos ajustes se han importado, o -1 si el archivo no es de HyperSpring
     */
    static int restore(Context c, String json) {
        return restore(c, json, false);
    }

    /**
     * @param keepExclude true con un código compartido: se conserva tu propia lista de apps sin
     *                    genio (el código no la lleva, y si no se borraría).
     */
    static int restore(Context c, String json, boolean keepExclude) {
        try {
            JSONObject root = new JSONObject(json);
            if (!MARK.equals(root.optString("app"))) return -1;
            JSONObject s = root.optJSONObject("settings");
            if (s == null) return -1;
            SharedPreferences prefs = c.getSharedPreferences(PrefsProvider.PREFS, 0);
            String ownExclude = prefs.getString(Exclude.KEY, null);
            SharedPreferences.Editor e = prefs.edit();
            e.clear();
            if (keepExclude && ownExclude != null) e.putString(Exclude.KEY, ownExclude);
            int n = 0;
            for (Iterator<String> it = s.keys(); it.hasNext(); ) {
                String key = it.next();
                if (Exclude.KEY.equals(key)) {
                    if (keepExclude) continue;
                    // Se limpia: solo pasan nombres de paquete válidos.
                    String list = Exclude.join(Exclude.parse(s.optString(key)));
                    if (!list.isEmpty()) {
                        e.putString(key, list);
                        n++;
                    }
                    continue;
                }
                Object[] item = item(key);
                if (item == null) continue;
                float v;
                try {
                    v = Float.parseFloat(s.optString(key).trim());
                } catch (NumberFormatException bad) {
                    continue;
                }
                if (Float.isNaN(v)) continue;
                v = Math.max((Float) item[2], Math.min((Float) item[3], v));
                int decimals = (Integer) item[5];
                e.putString(key, decimals <= 0 ? String.valueOf(Math.round(v))
                        : String.format(java.util.Locale.ROOT, "%." + decimals + "f", v));
                n++;
            }
            e.commit();
            return n;
        } catch (Throwable t) {
            return -1;
        }
    }

    private Backup() {
    }
}
