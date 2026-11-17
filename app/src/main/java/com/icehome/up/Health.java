package com.icehome.up;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * «¿Cómo va mi móvil?»: lee del registro las líneas que deja el genio al acabar cada animación
 * («genio abrir a 75%: …, 38 fotogramas en 640 ms (2 tirones, …») y saca una nota sencilla.
 */
final class Health {

    /** Hacen falta al menos estas animaciones para opinar. */
    static final int MIN_RUNS = 5;
    static final int UNKNOWN = 0, SMOOTH = 1, TIGHT = 2, STRUGGLING = 3;

    int runs, frames, janks;
    long ms;
    int verdict = UNKNOWN;

    private static final Pattern LINE = Pattern.compile(
            "genio (?:abrir|cerrar) a .*?(\\d+) fotogramas en (\\d+) ms \\((\\d+) tirones");

    static Health analyze(List<String> log) {
        Health h = new Health();
        for (String l : log) {
            Matcher m = LINE.matcher(l);
            if (!m.find()) continue;
            try {
                int f = Integer.parseInt(m.group(1));
                if (f < 5) continue;                       // animaciones cortadas: no cuentan
                h.runs++;
                h.frames += f;
                h.ms += Long.parseLong(m.group(2));
                h.janks += Integer.parseInt(m.group(3));
            } catch (NumberFormatException ignored) {
            }
        }
        if (h.runs >= MIN_RUNS && h.frames > 0) {
            float r = h.jankRatio();
            h.verdict = r < 0.05f ? SMOOTH : r < 0.15f ? TIGHT : STRUGGLING;
        }
        return h;
    }

    /** Parte de los fotogramas que llegaron tarde (0-1). */
    float jankRatio() {
        return frames == 0 ? 0f : janks / (float) frames;
    }

    /** Fotogramas por segundo de media durante las animaciones. */
    float fps() {
        return ms == 0 ? 0f : frames * 1000f / ms;
    }

    private Health() {
    }
}
