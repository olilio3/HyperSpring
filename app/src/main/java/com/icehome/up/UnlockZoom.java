package com.icehome.up;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.animation.PathInterpolator;

import java.lang.ref.WeakReference;

/**
 * Zoom suave al desbloquear: el escritorio entra un poco más grande y se asienta a su tamaño,
 * todo de una pieza (sin animar cada icono).
 *
 * <p>El launcher recibe el aviso del sistema de «el usuario ha desbloqueado»
 * ({@code ACTION_USER_PRESENT}). Si en ese momento el escritorio es lo que se ve, se escala su
 * contenido y vuelve a 1 con una curva suave. Solo se toca la escala, y siempre acaba en 1.
 */
final class UnlockZoom {

    /** 0 = apagado; 1 = un 6 % más grande al entrar. Ajuste «Zoom al desbloquear». */
    static volatile float strength;
    private static final float GROW = 0.06f;
    private static final long MS = 520;
    private static final PathInterpolator EASE = new PathInterpolator(0.16f, 1f, 0.3f, 1f);
    private static WeakReference<Activity> resumed = new WeakReference<>(null);
    private static boolean installed;

    static synchronized void install(Application app) {
        if (installed) return;
        installed = true;
        // Qué actividad del launcher está en pantalla ahora.
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity a) {
                resumed = new WeakReference<>(a);
            }

            @Override
            public void onActivityPaused(Activity a) {
                if (resumed.get() == a) resumed = new WeakReference<>(null);
            }

            @Override
            public void onActivityCreated(Activity a, Bundle b) {
            }

            @Override
            public void onActivityStarted(Activity a) {
            }

            @Override
            public void onActivityStopped(Activity a) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity a, Bundle b) {
            }

            @Override
            public void onActivityDestroyed(Activity a) {
            }
        });
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                try {
                    play();
                } catch (Throwable t) {
                    Log.e("zoom al desbloquear", t);
                }
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_USER_PRESENT);
        // Aviso que solo manda el sistema: el receptor no se exporta.
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        else app.registerReceiver(r, f);
    }

    private static void play() {
        float k = Math.max(0f, Math.min(1.5f, strength));
        Activity a = resumed.get();
        if (k <= 0f || Exclude.paused || a == null || a.getWindow() == null) return;
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return;
        final View v = a.getWindow().getDecorView().findViewById(android.R.id.content);
        if (v == null || v.getWidth() == 0) return;
        v.animate().cancel();
        v.setPivotX(v.getWidth() / 2f);
        v.setPivotY(v.getHeight() / 2f);
        v.setScaleX(1f + GROW * k);
        v.setScaleY(1f + GROW * k);
        v.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(MS).setInterpolator(EASE)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        v.setScaleX(1f);
                        v.setScaleY(1f);
                    }
                }).start();
    }

    private UnlockZoom() {
    }
}
