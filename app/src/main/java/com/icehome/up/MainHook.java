package com.icehome.up;

import com.icehome.up.xp.XposedBridge;

/**
 * Arranque de HyperSpring en cada proceso enganchado. Lo llaman las dos entradas del módulo:
 * {@link LegacyEntry} (API clásica de Xposed, 93) y {@link ModernEntry} (libxposed, API 102).
 * Reúne lo que antes eran tres módulos:
 * <ul>
 * <li>com.miui.home: animaciones del escritorio (antes Ice Home).</li>
 * <li>com.android.systemui: telón al cambiar de pantalla (antes Telón) y cristal y apertura del
 * centro de control (antes KashiPlus, con miui.systemui.plugin).</li>
 * </ul>
 * Si sigue instalado alguno de los módulos viejos, su parte no se carga aquí (dos ganchos sobre lo
 * mismo romperían la interfaz).
 */
public class MainHook {

    static final String OLD_TELON = "com.telon.fx", OLD_KASHIPLUS = "com.kashiplus.tweaks";
    /** En SystemUI: el centro de control lo lleva este módulo (sin KashiPlus viejo ni modo seguro). */
    static volatile boolean ccInstalled;
    private static volatile boolean sysuiChecked;
    /** Cargadores del plugin ya enganchados (el plugin puede recargarse con otro cargador). */
    private static final java.util.Map<ClassLoader, Boolean> ccDone = new java.util.WeakHashMap<>();

    private static volatile boolean launcherDone;
    /** El paquete del launcher enganchado en este proceso (com.miui.home o POCO Launcher). */
    static volatile String launcherPkg = Launchers.HYPEROS;
    /** Cargador de la WM Shell (donde vive com.android.wm.shell.transition.*) en HyperOS 4. */
    private static volatile ClassLoader shellCl;
    /** El vigilante de cargadores de la Shell ya está activo en este proceso. */
    private static volatile boolean shellWatchDone;

    /**
     * Un paquete acaba de cargarse en este proceso.
     *
     * @param pkg         paquete cargado (launcher, SystemUI o su plugin)
     * @param processName nombre del proceso; puede ser null
     * @param cl          cargador de clases del paquete: con él se buscan las clases a enganchar
     * @param dataDir     carpeta de datos del paquete (para el modo seguro del launcher)
     */
    static void onPackage(String pkg, String processName, ClassLoader cl, String dataDir) {
        if ("com.android.systemui".equals(pkg) || "miui.systemui.plugin".equals(pkg) || Launchers.is(pkg)) {
            // Aún no hay animaciones: el hilo semilla nace con todas las CPU (ver Threads).
            try {
                Threads.init();
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
        // HyperOS 4: la WM Shell (com.android.wm.shell) puede vivir en su propio proceso. La
        // espiamos para ver las transiciones de abrir/cerrar apps, porque el launcher de HyperOS 4
        // ya las anima en código nativo y sus clases Java (com.miui.home.recents.*) ya no existen.
        watchShell();
        if ("com.android.wm.shell".equals(pkg)) {
            installTransitionProbe(cl);
            return;
        }
        if ("com.android.systemui".equals(pkg)) {
            systemUi(processName, cl);
            return;
        }
        if ("com.miui.screenshot".equals(pkg)) {
            screenshotApp(processName);
            return;
        }
        if ("miui.systemui.plugin".equals(pkg)) {
            // El plugin también se carga en procesos auxiliares (p. ej. :flashlight) sin SystemUI.
            if (processName == null || processName.equals("com.android.systemui")) {
                checkSystemUi();
                installCc(cl);
            }
            return;
        }
        // El launcher: el de HyperOS o POCO Launcher (ver Launchers), solo en su proceso principal.
        if (!Launchers.is(pkg)) return;
        if (processName != null && !processName.equals(pkg)) return;
        if (launcherDone) return;                           // una sola vez por proceso
        launcherDone = true;
        launcherPkg = pkg;
        XposedBridge.log("IceHome: " + Launchers.name(pkg) + " enganchado, motor " + engineName());
        try {
            Remote.install(false);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        // Modo seguro: si el launcher se está cayendo una y otra vez, arranca sin animaciones.
        if (!LauncherSafe.checkAtLoad(dataDir)) return;
        // Los interruptores se leen cuando el launcher ya tiene su contexto: antes, con el del
        // sistema, el proveedor de ajustes rechaza la consulta y todo salía activado.
        final ClassLoader fcl = cl;
        onAttach(new WithContext() {
            @Override
            public void run(android.content.Context c) {
                installLauncher(c, fcl);
            }
        });
    }

    private static void installLauncher(android.content.Context c, ClassLoader cl) {
        // HyperOS 4: el launcher anima en nativo y sus clases Java (com.miui.home.recents.*) pueden
        // no existir. Anotamos cuántas encontramos para saber si los ganchos del launcher tienen
        // donde enganchar o hay que hacerlo desde System UI (transiciones de la WM Shell).
        logLauncherClasses(cl);
        Log.i("funciones: genio " + onOff(Tune.on(c, "icehome_genie_on"))
                + ", recientes " + onOff(Tune.on(c, "icehome_curtain_on"))
                + ", pila " + onOff(Tune.on(c, "icehome_stack_on"))
                + ", carpetas " + onOff(Tune.on(c, "icehome_folder_morph_on"))
                + ", cristal carpetas " + onOff(Tune.on(c, "icehome_folder_glass_on"))
                + ", menú de atajos " + onOff(Tune.on(c, "icehome_menu_on")));
        if (Tune.on(c, "icehome_genie_on")) {
            fxTotal++;
            try {
                WindowAnim.install(cl);
                Genie.enabled = true;
                Genie.startThreads();
                fxOk++;
            } catch (Throwable t) {
                fxFailed.append(fxFailed.length() > 0 ? ", " : "").append("icehome_genie_on");
                Log.e("no se pudo instalar el genio (¿otra versión del launcher?)", t);
            }
        }
        fxTotal++;
        try {
            WallpaperZoom.install(cl);
            fxOk++;
        } catch (Throwable t) {
            fxFailed.append(fxFailed.length() > 0 ? ", " : "").append("icehome_wallpaper_zoom");
            Log.e("no se pudo instalar el zoom del fondo", t);
        }
        fxTotal++;
        try {
            IconPress.install(cl);
            fxOk++;
        } catch (Throwable t) {
            fxFailed.append(fxFailed.length() > 0 ? ", " : "").append("icehome_press_on");
            Log.e("no se pudo instalar el icono que se hunde", t);
        }
        if (Tune.on(c, "icehome_curtain_on")) {
            fxTotal++;
            try {
                RecentsCurtain.install(cl);
                fxOk++;
            } catch (Throwable t) {
                fxFailed.append(fxFailed.length() > 0 ? ", " : "").append("icehome_curtain_on");
                Log.e("no se pudo instalar la cortina de recientes", t);
            }
        }
        if (Launchers.kashiFeatures(launcherPkg) && Tune.on(c, "icehome_stack_on")) {
            fxTotal++;
            try {
                StackSwipe.install(cl);
                fxOk++;
            } catch (Throwable t) {
                fxFailed.append(fxFailed.length() > 0 ? ", " : "").append("icehome_stack_on");
                XposedBridge.log("IceHome: error instalando StackSwipe");
                XposedBridge.log(t);
            }
        }
        if (Tune.on(c, "icehome_folder_glass_on")) {
            fxTotal++;
            try {
                GlassFolder.install(cl);
                fxOk++;
            } catch (Throwable t) {
                fxFailed.append(fxFailed.length() > 0 ? ", " : "").append("icehome_folder_glass_on");
                XposedBridge.log("IceHome: error instalando GlassFolder");
                XposedBridge.log(t);
            }
        }
        if (Launchers.kashiFeatures(launcherPkg) && Tune.on(c, "icehome_folder_morph_on")) {
            fxTotal++;
            try {
                FolderMorph.install(cl);
                fxOk++;
            } catch (Throwable t) {
                fxFailed.append(fxFailed.length() > 0 ? ", " : "").append("icehome_folder_morph_on");
                XposedBridge.log("IceHome: error instalando FolderMorph");
                XposedBridge.log(t);
            }
        }
        if (Tune.on(c, "icehome_dock_glass_on")) {
            fxTotal++;
            try {
                GlassDock.install(cl);
                fxOk++;
            } catch (Throwable t) {
                fxFailed.append(fxFailed.length() > 0 ? ", " : "").append("icehome_dock_glass_on");
                Log.e("no se pudo instalar el dock de cristal (¿otra versión del launcher?)", t);
            }
        }
        if (Tune.on(c, "icehome_menu_on")) {
            fxTotal++;
            try {
                ShortcutMenuFx.install(cl);
                fxOk++;
            } catch (Throwable t) {
                fxFailed.append(fxFailed.length() > 0 ? ", " : "").append("icehome_menu_on");
                Log.e("no se pudo instalar el menú de atajos (¿otra versión del launcher?)", t);
            }
        }
        // Para «Compatibilidad» en la app: cuántos efectos encendidos se engancharon y cuáles no.
        Remote.state(c, "launcher_fx", fxOk + "/" + fxTotal + "|" + fxFailed);
    }

    /** Efectos del launcher encendidos, los que se instalaron bien y las claves de los que fallaron. */
    private static int fxTotal, fxOk;
    private static final StringBuilder fxFailed = new StringBuilder();

    /** Para el registro: con qué API se ha cargado el módulo aquí. */
    static String engineName() {
        try {
            return XposedBridge.engine().name();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String onOff(boolean on) {
        return on ? "sí" : "no";
    }

    interface WithContext {
        void run(android.content.Context c);
    }

    /** Ejecuta {@code w} una vez, justo después de Application.attach (ya hay contexto propio). */
    static void onAttach(final WithContext w) {
        com.icehome.up.xp.XposedHelpers.findAndHookMethod(android.app.Application.class, "attach",
                android.content.Context.class, new com.icehome.up.xp.XC_MethodHook() {
                    private boolean done;

                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        if (done) return;
                        done = true;
                        try {
                            w.run((android.content.Context) p.thisObject);
                        } catch (Throwable t) {
                            XposedBridge.log(t);
                        }
                    }
                });
    }

    // ------------------------------------------------------------------ app de capturas de MIUI

    private static volatile boolean screenshotDone;

    /**
     * App de capturas (com.miui.screenshot): su vista previa se encoge a la par que la pantalla
     * (la otra mitad de la captura que encoge, en SystemUI). Sin este ámbito marcado en LSPosed,
     * la pantalla se encoge igual pero la vista previa sale a pantalla completa por encima.
     */
    private static void screenshotApp(String processName) {
        if (processName != null && !processName.equals("com.miui.screenshot")) return;
        if (screenshotDone) return;
        screenshotDone = true;
        try {
            com.icehome.up.telon.SqueezeShot.watch();
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        onAttach(new WithContext() {
            @Override
            public void run(android.content.Context c) {
                try {
                    com.icehome.up.telon.SqueezeShot.install(c);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });
    }

    // ------------------------------------------------------------------ interfaz del sistema

    private static boolean telonOn, sysuiSafe;

    /** Una vez por proceso de SystemUI: módulos viejos y modo seguro. */
    private static synchronized void checkSystemUi() {
        if (sysuiChecked) return;
        sysuiChecked = true;
        android.content.Context c = com.icehome.up.telon.Tune.systemContext();
        // Modo seguro: si la interfaz se está cayendo una y otra vez, no se carga nada de aquí.
        boolean safe = com.icehome.up.telon.SafeMode.checkAtLoad(c);
        boolean oldTelon = installed(c, OLD_TELON), oldKashi = installed(c, OLD_KASHIPLUS);
        if (oldTelon) com.icehome.up.telon.Log.w("sigue instalado el módulo Telón: desinstálalo; el telón de HyperSpring no se carga");
        if (oldKashi) com.icehome.up.telon.Log.w("sigue instalado KashiPlus: desinstálalo; el centro de control de HyperSpring no se carga");
        sysuiSafe = safe;
        telonOn = safe && !oldTelon;
        ccInstalled = safe && !oldKashi;
    }

    private static boolean installed(android.content.Context c, String pkg) {
        if (c == null) return false;
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static volatile boolean sysuiDone;

    private static void systemUi(String processName, ClassLoader cl) {
        // Solo el proceso principal y una vez: LSPosed puede avisar de SystemUI también en otros
        // procesos suyos (sin nombre de proceso); ahí no hay WMShell y además contaba como otro
        // arranque para el modo seguro (que saltaba con dos reinicios en vez de tres).
        String proc = processName;
        if (proc == null) {
            try {
                proc = android.app.Application.getProcessName();
            } catch (Throwable ignored) {
            }
        }
        if (proc != null && !proc.equals("com.android.systemui")) return;
        if (sysuiDone) return;
        sysuiDone = true;
        XposedBridge.log("IceHome: SystemUI enganchado, motor " + engineName());
        try {
            Remote.install(true);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        checkSystemUi();
        if (sysuiSafe) {
            // Pantalla que se encoge al hacer una captura. Va aparte del telón: tiene su propio
            // interruptor (se lee en cada captura, sin reiniciar) y no depende del módulo viejo.
            try {
                com.icehome.up.telon.Squeeze.watch(cl);
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
            onAttach(new WithContext() {
                @Override
                public void run(android.content.Context c) {
                    try {
                        com.icehome.up.telon.Squeeze.install(c);
                    } catch (Throwable t) {
                        com.icehome.up.telon.Log.e("no se pudo instalar la captura que encoge la pantalla", t);
                    }
                }
            });
        }
        if (telonOn) {
            final ClassLoader fcl = cl;
            onAttach(new WithContext() {
                @Override
                public void run(android.content.Context c) {
                    boolean on = com.icehome.up.telon.Tune.on(c, "telon_on");
                    com.icehome.up.telon.Log.i("telón " + onOff(on));
                    if (!on) return;
                    try {
                        com.icehome.up.telon.Telon.install(fcl);
                    } catch (Throwable t) {
                        com.icehome.up.telon.Log.e("no se pudo instalar el telón (¿otra versión de HyperOS?)", t);
                    }
                }
            });
        }
        if (ccInstalled) {
            try {
                com.icehome.up.cc.CcGlass.installFramework();
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
            watchPluginLoader();
        }
        // Sonda de HyperOS 4 (solo registra, no cambia nada): apunta la WM Shell para ver qué
        // transiciones de abrir/cerrar apps ve System UI. Es el primer paso para mover la animación
        // del genie desde el launcher (que ya es nativo) a System UI.
        final ClassLoader pcl = shellCl != null ? shellCl : cl;
        onAttach(new WithContext() {
            @Override
            public void run(android.content.Context c) {
                try {
                    com.icehome.up.telon.TransitionProbe.install(pcl);
                } catch (Throwable t) {
                    com.icehome.up.telon.Log.e("probe: could not install", t);
                }
            }
        });
    }

    private static synchronized void installCc(ClassLoader cl) {
        if (!ccInstalled || ccDone.containsKey(cl)) return;
        ccDone.put(cl, Boolean.TRUE);
        try {
            com.icehome.up.cc.CcGlass.install(cl);
        } catch (Throwable t) {
            XposedBridge.log("IceHome: error instalando el centro de control");
            XposedBridge.log(t);
        }
    }

    /**
     * Respaldo: si este LSPosed no avisa con el paquete del plugin, se detecta el cargador de
     * clases que SystemUI crea para MIUISystemUIPlugin.apk.
     */
    private static void watchPluginLoader() {
        try {
            XposedBridge.hookAllConstructors(dalvik.system.BaseDexClassLoader.class, new com.icehome.up.xp.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    Object path = p.args.length > 0 ? p.args[0] : null;
                    if (!(path instanceof String)) return;
                    String s = (String) path;
                    if (!s.contains("SystemUIPlugin") && !s.contains("miui.systemui.plugin")) return;
                    ClassLoader cl = (ClassLoader) p.thisObject;
                    try {
                        cl.loadClass("miui.systemui.controlcenter.qs.tileview.QSTileItemIconView");
                    } catch (Throwable t) {
                        return;
                    }
                    installCc(cl);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    /** HyperOS 4: localiza el cargador de la WM Shell (com.android.wm.shell) para espiar las
     *  transiciones de abrir/cerrar apps desde System UI, porque el launcher ya las anima en nativo.
     *  El cargador puede aparecer en cualquier proceso donde viva la Shell. */
    private static synchronized void watchShell() {
        if (shellWatchDone) return;
        shellWatchDone = true;
        try {
            XposedBridge.hookAllConstructors(dalvik.system.BaseDexClassLoader.class, new com.icehome.up.xp.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    Object path = p.args.length > 0 ? p.args[0] : null;
                    if (!(path instanceof String)) return;
                    String s = ((String) path).toLowerCase();
                    if (!s.contains("shell")) return;
                    final ClassLoader cl = (ClassLoader) p.thisObject;
                    shellCl = cl;
                    android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                com.icehome.up.telon.TransitionProbe.install(cl);
                            } catch (Throwable t) {
                                XposedBridge.log(t);
                            }
                        }
                    });
                }
            });
            XposedBridge.log("IceHome: vigilante de la WM Shell activado");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    /** Instala la sonda de transiciones (HyperOS 4) con el cargador que tenga las clases de la Shell. */
    private static void installTransitionProbe(ClassLoader cl) {
        try {
            com.icehome.up.telon.TransitionProbe.install(shellCl != null ? shellCl : cl);
        } catch (Throwable t) {
            com.icehome.up.telon.Log.e("probe: could not install", t);
        }
    }

    /** HyperOS 4: ¿siguen existiendo las clases Java del launcher de HyperOS 3? Lo anota en el
     *  registro para saber si los ganchos del launcher tienen donde enganchar. */
    private static void logLauncherClasses(ClassLoader cl) {
        String[] names = {
                "com.miui.home.launcher.Launcher",
                "com.miui.home.launcher.BaseLauncher",
                "com.miui.home.launcher.Application",
                "com.miui.home.recents.anim.windowanim.LocalWindowAnimImplementor",
                "com.miui.home.launcher.hotseats.HotSeats",
                "com.android.systemui.shared.recents.system.RemoteAnimationTargetCompat",
        };
        StringBuilder sb = new StringBuilder("launcher class check:");
        for (String n : names) {
            try {
                cl.loadClass(n);
                sb.append(" OK=").append(n);
            } catch (Throwable t) {
                sb.append(" MISS=").append(n);
            }
        }
        Log.i(sb.toString());
    }
}
