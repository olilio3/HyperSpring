package com.icehome.up.telon;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.icehome.up.xp.XC_MethodHook;
import com.icehome.up.xp.XposedBridge;
import com.icehome.up.xp.XposedHelpers;

/**
 * TEST PROBE, not a feature. It only writes lines to the HyperSpring log; it changes nothing.
 *
 * Why it exists: on HyperOS 4 the launcher plays the app open/close animation itself (native
 * code), so the old genie hooks in the launcher find nothing. Before writing a new genie we need
 * to know what System UI can see of each transition. This records, for every transition:
 * <ul>
 * <li>REQUEST: the start request (type, trigger task, and which remote transition was asked for).</li>
 * <li>PLAYED BY: which handler plays it (the launcher's remote one, the default one, ...) and the
 *     full description of the transition (type, windows, their modes and bounds).</li>
 * <li>REMOTE: which remote transition (the launcher's) was attached to that transition.</li>
 * </ul>
 * Every hook is wrapped in try/catch and does nothing after {@link #MAX_LINES} lines, so it can
 * never break or slow down an animation. See the log in the app: About, Copy log.
 *
 * <p>HyperOS 4 port: the transition classes live in the WM Shell
 * ({@code com.android.wm.shell.transition.*}), whose class loader is not always the SystemUI one.
 * {@code MainHook} feeds this probe the right class loader (the Shell's when it finds it). The
 * probe is also defensive: each hook target is tried on its own, so a renamed method on HyperOS 4
 * is reported instead of taking the whole probe down.
 */
public final class TransitionProbe {

    /** Lines to record before going quiet (restart System UI to record again). */
    private static final int MAX_LINES = 300;
    /** Longest single line. A transition description can be long. */
    private static final int MAX_CHARS = 1400;

    private static final AtomicInteger lines = new AtomicInteger();
    private static boolean installed;

    public static synchronized void install(ClassLoader cl) {
        if (installed) return;
        // Each target is tried on its own so one missing class/method does not hide the others.
        int ok = 0;

        // 1) Which handler plays each transition, and what the transition looks like.
        try {
            Class<?> transitions = XposedHelpers.findClass("com.android.wm.shell.transition.Transitions", cl);
            XposedBridge.hookAllMethods(transitions, "dispatchTransition", new XC_MethodHook() {
                @Override
                protected boolean skip(Object self) {
                    return lines.get() >= MAX_LINES;
                }

                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object handler = p.getResult();
                        String who = handler == null ? "nobody" : handler.getClass().getName();
                        Object info = p.args.length > 1 ? p.args[1] : null;
                        emit("PLAYED BY " + who + " | " + describe(info));
                    } catch (Throwable t) {
                        Log.e("probe: dispatchTransition", t);
                    }
                }
            });
            ok++;
            Log.i("probe: hooked Transitions.dispatchTransition");
        } catch (Throwable t) {
            Log.w("probe: Transitions.dispatchTransition not found on this class loader: " + t);
        }

        // 2) The request: who asked for the transition and with which remote transition.
        try {
            Class<?> transitions = XposedHelpers.findClass("com.android.wm.shell.transition.Transitions", cl);
            XposedBridge.hookAllMethods(transitions, "requestStartTransition", new XC_MethodHook() {
                @Override
                protected boolean skip(Object self) {
                    return lines.get() >= MAX_LINES;
                }

                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        emit("REQUEST " + String.valueOf(p.args.length > 1 ? p.args[1] : null));
                    } catch (Throwable t) {
                        Log.e("probe: requestStartTransition", t);
                    }
                }
            });
            ok++;
            Log.i("probe: hooked Transitions.requestStartTransition");
        } catch (Throwable t) {
            Log.w("probe: Transitions.requestStartTransition not found on this class loader: " + t);
        }

        // 3) The remote transition (the launcher's) attached to this transition's token.
        try {
            Class<?> remoteHandler = XposedHelpers.findClass("com.android.wm.shell.transition.RemoteTransitionHandler", cl);
            XposedBridge.hookAllMethods(remoteHandler, "startAnimation", new XC_MethodHook() {
                @Override
                protected boolean skip(Object self) {
                    return lines.get() >= MAX_LINES;
                }

                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        emit("REMOTE " + remoteFor(p.thisObject, p.args.length > 0 ? p.args[0] : null));
                    } catch (Throwable t) {
                        Log.e("probe: remote startAnimation", t);
                    }
                }
            });
            ok++;
            Log.i("probe: hooked RemoteTransitionHandler.startAnimation");
        } catch (Throwable t) {
            Log.w("probe: RemoteTransitionHandler.startAnimation not found on this class loader: " + t);
        }

        Log.i("probe active: ok=" + ok + " (class loader " + cl + ")");
        // Only stop retrying once at least one hook is in. If every target missed (wrong class
        // loader, or HyperOS 4 renamed them), stay open so MainHook can try the Shell class loader.
        if (ok > 0) installed = true;
        else Log.w("probe: nothing installed on this class loader; will retry if a better one appears");
    }

    /** The transition as text, plus its animation options when the framework has them. */
    private static String describe(Object info) {
        if (info == null) return "info=null";
        StringBuilder sb = new StringBuilder(String.valueOf(info));
        try {
            Method m = info.getClass().getMethod("getAnimationOptions");
            sb.append(" | options=").append(String.valueOf(m.invoke(info)));
        } catch (Throwable ignored) {
            // Not available in this version: the description above is enough.
        }
        return sb.toString();
    }

    /** What RemoteTransitionHandler holds for this token (field mRequestedRemotes, a map). */
    private static String remoteFor(Object handler, Object token) {
        try {
            Field f = handler.getClass().getDeclaredField("mRequestedRemotes");
            f.setAccessible(true);
            Object map = f.get(handler);
            if (map == null) return "no map";
            Method get = map.getClass().getMethod("get", Object.class);
            Object rt = get.invoke(map, token);
            return rt == null ? "token has no remote" : String.valueOf(rt);
        } catch (Throwable t) {
            return "could not read remote: " + t;
        }
    }

    private static void emit(String text) {
        int n = lines.incrementAndGet();
        if (n > MAX_LINES) {
            if (n == MAX_LINES + 1) Log.i("probe: line limit reached; restart System UI to record again");
            return;
        }
        String s = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) + "..." : text;
        Log.i("transition: " + s);
    }

    private TransitionProbe() {
    }
}
