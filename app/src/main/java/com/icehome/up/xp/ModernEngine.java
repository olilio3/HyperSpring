package com.icehome.up.xp;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * Motor para libxposed (API 101/102). Aquí un hook es un "interceptor": recibe la llamada
 * ({@code chain}), decide si deja seguir al método original con {@code chain.proceed()} y
 * devuelve el resultado. Este motor lo convierte en nuestro antes/después de siempre.
 */
public final class ModernEngine implements Engine {

    private static final String TAG = "HyperSpring";
    private final XposedInterface x;

    public ModernEngine(XposedInterface x) {
        this.x = x;
    }

    @Override
    public String name() {
        return "libxposed " + x.getApiVersion() + " (" + x.getFrameworkName() + " " + x.getFrameworkVersion() + ")";
    }

    @Override
    public void hook(Member m, final XC_MethodHook cb) {
        x.hook((Executable) m)
                // PASSTHROUGH: los errores de nuestros hooks ya se capturan y anotan aquí abajo.
                // Así una excepción del método original (o una puesta con setThrowable) llega a
                // quien llamó, igual que con Xposed clásico.
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        // 0) Filtro rápido: si este hook no tiene nada que hacer ahora, el método
                        // original sin crear ningún objeto (ver XC_MethodHook.skip).
                        boolean pass;
                        try {
                            pass = cb.skip(chain.getThisObject());
                        } catch (Throwable t) {
                            pass = false;
                        }
                        if (pass) return chain.proceed();
                        XC_MethodHook.MethodHookParam p = new XC_MethodHook.MethodHookParam();
                        p.method = chain.getExecutable();
                        p.thisObject = chain.getThisObject();
                        p.args = chain.getArgs().toArray();
                        // 1) Antes. Si nuestro hook falla, se anota y el método sigue como si nada.
                        try {
                            cb.beforeHookedMethod(p);
                        } catch (Throwable t) {
                            log(t);
                            p.result = null;
                            p.throwable = null;
                            p.returnEarly = false;
                        }
                        // 2) El método original (y los hooks de otros módulos), salvo que "antes"
                        // ya haya puesto un resultado.
                        if (!p.returnEarly) {
                            try {
                                p.result = chain.proceed(p.args);
                            } catch (Throwable t) {
                                p.throwable = t;
                            }
                        }
                        // 3) Después. Si falla, se conserva el resultado que había.
                        Object result = p.result;
                        Throwable thrown = p.throwable;
                        p.returnEarly = false;
                        try {
                            cb.afterHookedMethod(p);
                        } catch (Throwable t) {
                            log(t);
                            p.result = result;
                            p.throwable = thrown;
                        }
                        if (p.throwable != null) throw p.throwable;
                        return p.result;
                    }
                });
    }

    /**
     * Invokers ya preparados, uno por método. El centro de control llama a métodos originales en
     * cada pieza cada vez que se abre; pedir un invoker nuevo en cada llamada era trabajo de más.
     * Siempre son de tipo ORIGIN y no se vuelven a cambiar, así que se pueden compartir.
     */
    private final java.util.concurrent.ConcurrentHashMap<Member, Object> invokers =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public Object invokeOriginal(Member m, Object thisObject, Object[] args) throws Throwable {
        // Un "invoker" de tipo ORIGIN llama al método original saltándose todos los hooks.
        Object inv = invokers.get(m);
        if (inv == null) {
            if (m instanceof Method) {
                XposedInterface.Invoker<?, Method> mi = x.getInvoker((Method) m);
                mi.setType(XposedInterface.Invoker.Type.ORIGIN);
                inv = mi;
            } else {
                XposedInterface.CtorInvoker<?> ci = x.getInvoker((Constructor<?>) m);
                ci.setType(XposedInterface.Invoker.Type.ORIGIN);
                inv = ci;
            }
            invokers.put(m, inv);
        }
        if (m instanceof Method) return ((XposedInterface.Invoker<?, Method>) inv).invoke(thisObject, args);
        return ((XposedInterface.CtorInvoker<?>) inv).invoke(thisObject, args);
    }

    @Override
    public boolean deoptimize(Member m) {
        return x.deoptimize((Executable) m);
    }

    @Override
    public void log(String text) {
        x.log(android.util.Log.INFO, TAG, text);
    }

    @Override
    public void log(Throwable t) {
        x.log(android.util.Log.ERROR, TAG, String.valueOf(t), t);
    }
}
