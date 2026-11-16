package com.icehome.up.xp;

import java.lang.reflect.Member;

/**
 * Lo que se ejecuta antes y después de un método enganchado. Tiene a propósito la misma forma
 * que el {@code XC_MethodHook} de Xposed clásico, para que los hooks del módulo se lean igual
 * que siempre; pero es una clase nuestra y funciona con los dos motores (ver {@link Engine}).
 */
public abstract class XC_MethodHook {

    /**
     * Filtro rápido, antes de todo lo demás. Si devuelve true, este hook no hace nada en esta
     * llamada: el motor llama al método original directamente, sin preparar los datos de la
     * llamada (cada llamada enganchada crea varios objetos, y algunos métodos se llaman miles de
     * veces por segundo, como el dibujo del fondo de cada vista de SystemUI).
     *
     * <p>Tiene que ser muy barato (mirar un campo, nada más) y no lanzar excepciones; si lanza,
     * se toma como false y el hook se ejecuta normal. Por defecto no salta nunca.
     */
    protected boolean skip(Object thisObject) {
        return false;
    }

    /** Antes del método original. Con {@code p.setResult(...)} el original ya no se ejecuta. */
    protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
    }

    /** Después del método original. Con {@code p.setResult(...)} se cambia lo que devuelve. */
    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
    }

    /** Datos de una llamada al método enganchado. */
    public static final class MethodHookParam {
        /** El método o constructor enganchado. */
        public Member method;
        /** El objeto sobre el que se llama ({@code null} si el método es estático). */
        public Object thisObject;
        /** Los argumentos. Si se cambian en "before", el original recibe los nuevos. */
        public Object[] args;

        Object result;
        Throwable throwable;
        /** true si "before" ya dio un resultado: el original no se llama. */
        boolean returnEarly;
        private java.util.Map<String, Object> extra;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.returnEarly = true;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
            this.returnEarly = true;
        }

        /** Guarda algo en "before" para recogerlo en "after" de la misma llamada. */
        public void setObjectExtra(String key, Object value) {
            if (extra == null) extra = new java.util.HashMap<>();
            extra.put(key, value);
        }

        public Object getObjectExtra(String key) {
            return extra == null ? null : extra.get(key);
        }
    }
}
