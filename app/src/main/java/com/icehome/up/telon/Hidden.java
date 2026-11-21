package com.icehome.up.telon;

import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.view.Surface;
import android.view.SurfaceControl;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * APIs ocultas del framework que usa el genio. Se resuelven una sola vez; si alguna no existe
 * en esta versión de Android, {@link #ok()} devuelve false y el genio no se activa.
 * (SystemUI tiene acceso a las APIs ocultas y a las capturas de capas.)
 */
final class Hidden {

    private static Constructor<?> argsBuilder;
    private static Method setCrop, setScale, setChildrenOnly, setExclude, build, capture, colorSpace, hardwareBuffer;
    private static Method attachAndQueue;
    private static Method relativeLayer, matrix;
    private static boolean resolved, ok;

    static synchronized boolean ok() {
        if (resolved) return ok;
        resolved = true;
        try {
            Class<?> args = null, owner = null;
            for (String name : CAPTURE_OWNERS) {
                try {
                    Class<?> o = Class.forName(name);
                    Class<?> a = Class.forName(name + "$LayerCaptureArgs");
                    capture = o.getMethod("captureLayers", a);
                    owner = o;
                    args = a;
                    break;
                } catch (Throwable ignored) {
                }
            }
            if (owner == null) {
                Log.w("APIs ocultas no disponibles, genio desactivado: no hay captureLayers; " + captureInfo());
                return false;
            }
            Class<?> b = Class.forName(args.getName() + "$Builder");
            argsBuilder = b.getConstructor(SurfaceControl.class);
            setCrop = b.getMethod("setSourceCrop", Rect.class);
            setScale = b.getMethod("setFrameScale", float.class);
            setChildrenOnly = b.getMethod("setChildrenOnly", boolean.class);
            setExclude = b.getMethod("setExcludeLayers", SurfaceControl[].class);
            build = b.getMethod("build");
            colorSpace = capture.getReturnType().getMethod("getColorSpace");
            attachAndQueue = Surface.class.getMethod("attachAndQueueBufferWithColorSpace",
                    HardwareBuffer.class, ColorSpace.class);
            hardwareBuffer = capture.getReturnType().getMethod("getHardwareBuffer");
            relativeLayer = SurfaceControl.Transaction.class.getMethod("setRelativeLayer",
                    SurfaceControl.class, SurfaceControl.class, int.class);
            matrix = SurfaceControl.Transaction.class.getMethod("setMatrix",
                    SurfaceControl.class, Matrix.class, float[].class);
            ok = true;
        } catch (Throwable t) {
            Log.w("APIs ocultas no disponibles, genio desactivado: " + t);
        }
        return ok;
    }

    /**
     * Dónde está captureLayers, de más nuevo a más viejo. Android 17: ScreenCaptureInternal (el
     * nombre ScreenCapture pasó a una API pública distinta). Android 14-16: ScreenCapture.
     * Android 12-13: SurfaceControl.
     */
    private static final String[] CAPTURE_OWNERS = {
            "android.window.ScreenCaptureInternal", "android.window.ScreenCapture", "android.view.SurfaceControl"};

    /** Para el registro: qué clases y métodos de captura hay en este Android (para adaptarlo). */
    private static String captureInfo() {
        StringBuilder s = new StringBuilder();
        for (String name : CAPTURE_OWNERS) {
            try {
                Class<?> c = Class.forName(name);
                s.append(c.getSimpleName()).append(" [");
                for (Class<?> in : c.getDeclaredClasses()) s.append(in.getSimpleName()).append(' ');
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().toLowerCase().contains("capture")) s.append(m.getName()).append('/').append(m.getParameterCount()).append(' ');
                }
                s.append("] ");
            } catch (Throwable t) {
                s.append(name.substring(name.lastIndexOf('.') + 1)).append(" no; ");
            }
        }
        return s.toString();
    }

    /** Una captura: búfer de la GPU y su espacio de color. */
    static final class Shot {
        final HardwareBuffer buffer;
        final ColorSpace colorSpace;

        Shot(HardwareBuffer buffer, ColorSpace colorSpace) {
            this.buffer = buffer;
            this.colorSpace = colorSpace;
        }
    }

    /**
     * Captura el contenido de {@code layer} en su propio espacio (sin su matriz, recorte ni
     * transparencia: solo los hijos), a {@code scale} de resolución. Null si falla.
     */
    static Shot capture(SurfaceControl layer, Rect crop, float scale, SurfaceControl[] exclude) {
        try {
            Object b = argsBuilder.newInstance(layer);
            setCrop.invoke(b, crop);
            setScale.invoke(b, scale);
            setChildrenOnly.invoke(b, true);
            if (exclude != null) setExclude.invoke(b, (Object) exclude);
            Object shot = capture.invoke(null, build.invoke(b));
            if (shot == null) return null;
            HardwareBuffer hb = (HardwareBuffer) hardwareBuffer.invoke(shot);
            return hb == null ? null : new Shot(hb, (ColorSpace) colorSpace.invoke(shot));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Pone un búfer de la GPU en una Surface sin copiarlo (como la animación de girar la pantalla). */
    static boolean queueBuffer(Surface surface, HardwareBuffer buffer, ColorSpace cs) {
        try {
            attachAndQueue.invoke(surface, buffer, cs != null ? cs : ColorSpace.get(ColorSpace.Named.SRGB));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static void setRelativeLayer(SurfaceControl.Transaction t, SurfaceControl sc, SurfaceControl to, int z) {
        try {
            relativeLayer.invoke(t, sc, to, z);
        } catch (Throwable ignored) {
        }
    }

    static void setMatrix(SurfaceControl.Transaction t, SurfaceControl sc, Matrix m, float[] tmp) {
        try {
            matrix.invoke(t, sc, m, tmp);
        } catch (Throwable ignored) {
        }
    }

    private Hidden() {
    }
}
