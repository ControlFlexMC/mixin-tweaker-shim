package com.ifels.mixinshim;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.CodeSource;
import java.util.List;

/**
 * The behaviour behind {@code org.spongepowered.asm.launch.MixinTweaker}, the class name every
 * 1.12.2 Mixin provider ships and every 1.12.2 Mixin container must declare.
 *
 * <p><b>Why a shim at all.</b> A mod that wants its mixin configs read by every provider generation
 * declares {@code TweakClass: org.spongepowered.asm.launch.MixinTweaker}; the pre-11 providers match
 * that manifest value LITERALLY. LaunchWrapper resolves the class before Forge constructs anything,
 * so on an instance with NO provider the launch used to die with a bare
 * {@code ClassNotFoundException} -- no window, no Forge, no error screen of ours. Supplying the name
 * keeps the launch alive so the mod's own probe can say what is wrong on screen.
 *
 * <p><b>Why it is safe to occupy someone else's name.</b> This class is a pure forwarder: it calls
 * the same {@code MixinBootstrap} entry points, in the same order, as the real tweaker. In a pack
 * where the provider's own tweaker wins the classpath this class is never loaded at all.
 *
 * <p><b>It never throws.</b> An exception here has no error screen behind it -- that is the whole
 * point of this file -- so every failure is logged, recorded in a system property and left for the
 * mod's construction-time probe.
 *
 * <p><b>No Minecraft, no ControlFlex classes in the constant pool.</b> This runs before the game
 * exists, and it is unit-tested against stubs; anything but {@code java.*} and reflection would make
 * both of those impossible.
 */
public final class MixinTweakShim {

    /** The bootstrap the real tweaker is a shell over; every 1.12.2 provider ships exactly one. */
    public static final String BOOTSTRAP_CLASS = "org.spongepowered.asm.launch.MixinBootstrap";

    /** Carries the {@link TweakState} name to the mod side, which cannot see this class's state. */
    public static final String STATE_PROPERTY = "controlflex.mixin.tweak";

    /** Free-text reason behind the state; surfaced on the error screen. */
    public static final String DETAIL_PROPERTY = "controlflex.mixin.tweak.detail";

    /**
     * A resource only the mod's own jar carries. Used to find that jar by content instead of by file
     * name -- pack authors rename jars, and a name pattern would quietly stop matching.
     */
    public static final String MOD_MARKER = "controlflex.mixins.json";

    /**
     * FML's coremod manager. Its {@code getIgnoredMods()} list is the one place FML records "this jar
     * is a tweaker/library, not a mod" -- and it is consulted twice, by {@code ModDiscoverer}
     * ("Skipping known library file") and by {@code Loader#loadMods} ("Skipping already parsed
     * coremod or tweaker").
     */
    private static final String FORGE_COREMOD_MANAGER = "net.minecraftforge.fml.relauncher.CoreModManager";

    /** What the tweak phase found. Deliberately tiny: supply/version logic lives in the mod probe. */
    public enum TweakState {
        /** No provider's bootstrap is visible from the launch class loader. */
        ABSENT,
        /** The provider's bootstrap was driven from scratch; the mod behaves exactly as without us. */
        DELEGATED,
        /**
         * A compatible subsystem was already registered, so {@code start()} returned {@code false}.
         *
         * <p>Not a failure: MixinBooter 5.0's {@code MixinBootstrap.start()} returns {@code false}
         * in exactly that case and its real tweaker pops the value and calls {@code doInit} anyway.
         * Treating it as a failure here would skip {@code doInit} and leave every mixin silently
         * dead -- reachable on 5.0, whose coremod calls {@code MixinBootstrap.init()} on its own.
         */
        ALREADY_BOOTED,
        /** The bootstrap is there but threw; the mod probe reports the same failure. */
        START_FAILED,
        /** Nothing ran yet (the provider's own tweaker won the classpath, so this class never loaded). */
        NOT_PROBED
    }

    /** Resolved once: log4j's Logger, or null when this JVM has no log4j. */
    private static Object logger;

    /** Guards the one-time lookup in {@link #logger()}. */
    private static boolean loggerResolved;

    /** Overridable so the test can point the same reflection path at a stub. */
    private static String bootstrapClassName = BOOTSTRAP_CLASS;

    /** Guards the duplicate {@code acceptOptions} some launchers issue. */
    private static boolean started;

    /** True only once start() and doInit() both succeeded; gates inject()/getLaunchTarget(). */
    private static boolean delegated;

    /**
     * True when {@code start()} had to fall back to {@code init()} (an engine old enough to have
     * neither {@code start()} nor {@code doInit(List)}). {@code init()} is {@code start()} plus
     * {@code doInit()} in one call, so the follow-up {@code doInit()} must then be a no-op rather
     * than a failure.
     */
    private static boolean initialisedByInitFallback;

    private MixinTweakShim() {
    }

    /** @param name the bootstrap class to drive, or {@code null} to restore the real one */
    public static void bindBootstrap(String name) {
        bootstrapClassName = name == null ? BOOTSTRAP_CLASS : name;
    }

    /**
     * Test hook: forget the launch state between cases. The launch itself runs exactly once, so the
     * real code never needs this -- and keeping it package-private keeps it that way.
     */
    static void resetForTest() {
        started = false;
        delegated = false;
        initialisedByInitFallback = false;
        bootstrapClassName = BOOTSTRAP_CLASS;
        System.clearProperty(STATE_PROPERTY);
        System.clearProperty(DETAIL_PROPERTY);
    }

    /** Resolves the bootstrap WITHOUT initialising it: a failed {@code <clinit>} would poison the class. */
    public static boolean isBootstrapVisible(ClassLoader loader) {
        return resolve(loader) != null;
    }

    /** The state published by {@link #acceptOptions}; {@link TweakState#NOT_PROBED} when unset. */
    public static TweakState currentState() {
        String raw = System.getProperty(STATE_PROPERTY);
        if (raw == null || raw.isEmpty()) {
            return TweakState.NOT_PROBED;
        }
        try {
            return TweakState.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            return TweakState.NOT_PROBED;
        }
    }

    /** The reason behind {@link #currentState()}, or the empty string. Never null. */
    public static String detail() {
        String raw = System.getProperty(DETAIL_PROPERTY);
        return raw == null ? "" : raw;
    }

    /**
     * The line that tells a log reader which tweaker ran. If the provider's own class won the
     * classpath this line is absent -- which is exactly the information needed when a pack's mixins
     * behave unexpectedly.
     */
    public static void logIdentity() {
        out("Mixin tweaker shim is active (no provider's MixinTweaker won the classpath)");
    }

    /** The tweak-phase half: resolve, start, doInit. Mirrors the real tweaker's call order. */
    public static void acceptOptions(List<String> args, ClassLoader loader) {
        if (started) {
            return;
        }
        started = true;
        Class<?> bootstrap = resolve(loader);
        if (bootstrap == null) {
            publish(TweakState.ABSENT, "no " + BOOTSTRAP_CLASS + " on the launch class path");
            out("no Mixin provider found (" + BOOTSTRAP_CLASS + " is not loadable); the mod will"
                    + " report this at startup instead of the launcher dying with a stack trace");
            return;
        }
        boolean alreadyBooted;
        try {
            // The real tweaker pops this boolean. It is false when a compatible subsystem is
            // already registered -- a state, not an error -- so it decides the state name only.
            alreadyBooted = !start(bootstrap);
        } catch (Throwable failed) {
            publish(TweakState.START_FAILED, "start() failed: " + describe(failed));
            out(BOOTSTRAP_CLASS + ".start() failed: " + describe(failed));
            return;
        }
        try {
            doInit(bootstrap, args);
        } catch (Throwable failed) {
            publish(TweakState.START_FAILED, "doInit() failed: " + describe(failed));
            out(BOOTSTRAP_CLASS + ".doInit() failed: " + describe(failed));
            return;
        }
        delegated = true;
        String version = version(bootstrap);
        String where = location(bootstrap);
        if (alreadyBooted) {
            publish(TweakState.ALREADY_BOOTED, "Mixin " + version + " was already bootstrapped by"
                    + " another container; doInit ran without re-registering (" + where + ")");
            out("Mixin " + version + " was already bootstrapped elsewhere; drove doInit only ("
                    + where + ")");
        } else {
            publish(TweakState.DELEGATED, "delegated to Mixin " + version + " from " + where);
            out("delegating to the instance's own Mixin " + version + " (" + where + ")");
        }
    }

    /** The class-loader half: {@code MixinBootstrap.inject()}, exactly as the real tweaker does. */
    public static void injectIntoClassLoader(ClassLoader loader) {
        if (!delegated) {
            if (currentState() == TweakState.ABSENT) {
                // Runs after FML filled its ignored-mod list (the list does not exist yet when
                // acceptOptions is called) and before ModDiscoverer reads it.
                unignoreModJars(loader);
            }
            return;
        }
        Class<?> bootstrap = resolve(loader);
        if (bootstrap == null) {
            return;
        }
        // Each entry point is tried only until one answers. A method that is ABSENT means "this
        // engine has a different shape" and the next one is tried; a method that THREW means the
        // engine's own injection failed, and trying another entry point after that risks injecting
        // twice -- so it stops, records the reason and returns.
        int call = invokeStatic(bootstrap, "inject");
        if (call == DONE) {
            return;
        }
        if (call == THREW) {
            out("MixinBootstrap.inject() threw (" + lastFailure + "); not trying other entry points");
            return;
        }
        // Mixin 0.6.x has no inject(): the entry point is injectIntoClassLoader(LaunchClassLoader),
        // on the bootstrap and on the platform manager. Measured with javap on 0.6.15.
        call = invokeWithLoader(bootstrap, "injectIntoClassLoader", loader);
        if (call == DONE) {
            return;
        }
        if (call == THREW) {
            out("MixinBootstrap.injectIntoClassLoader() threw (" + lastFailure + ")");
            return;
        }
        Object platform = platform(bootstrap);
        if (platform != null) {
            call = invoke(platform, "inject");
            if (call == DONE) {
                return;
            }
            if (call == THREW) {
                out("the platform manager's inject() threw (" + lastFailure + ")");
                return;
            }
            call = invokeWithLoader(platform, "injectIntoClassLoader", loader);
            if (call == DONE) {
                return;
            }
            if (call == THREW) {
                out("the platform manager's injectIntoClassLoader() threw (" + lastFailure + ")");
                return;
            }
        }
        out("no injection entry point answered: MixinBootstrap has neither inject() nor"
                + " injectIntoClassLoader(LaunchClassLoader) on this engine, and neither does its"
                + " platform manager -- nothing was injected");
    }

    /** The launcher half: the platform's launch target, or {@code null} to let Forge's tweaker win. */
    public static String getLaunchTarget(ClassLoader loader) {
        if (!delegated) {
            return null;
        }
        Class<?> bootstrap = resolve(loader);
        Object platform = bootstrap == null ? null : platform(bootstrap);
        if (platform == null) {
            return null;
        }
        try {
            Method target = platform.getClass().getMethod("getLaunchTarget");
            Object value = target.invoke(platform);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable unreachable) {
            out("could not read the platform launch target: " + unreachable);
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------
    // FML's "this jar is a library, not a mod" list
    // ------------------------------------------------------------------------------------------

    /**
     * Takes this jar and the mod jar off FML's ignored-mod list.
     *
     * <p><b>Why it happens here.</b> CoreModManager adds the FILE NAME of every jar that declares
     * {@code TweakClass} to {@code ignoredModFiles} the moment it reads that attribute. On a pack
     * with a provider nobody notices -- the provider's platform agent reads {@code ForceLoadAsMod}
     * and force-loads such jars. With no provider there is nobody to do it, so a coremod that would
     * normally undo it is never even instantiated: FML stops after "Loading tweaker" (measured
     * 2026-11-16, this instance, no provider: no "Instantiating coremod class", then "Skipping known
     * library file ...controlflex....jar" and the mod never constructs). The tweaker runs earlier
     * than any coremod could, so this is the only place left.
     *
     * <p><b>Why the mod jar is found by content.</b> Pack authors rename jars; a name pattern would
     * quietly stop matching. {@link #MOD_MARKER} is a resource only that jar carries.
     */
    private static void unignoreModJars(ClassLoader loader) {
        try {
            Class<?> manager = Class.forName(FORGE_COREMOD_MANAGER, false, loader);
            Object value = manager.getMethod("getIgnoredMods").invoke(null);
            if (!(value instanceof List)) {
                out("FML's ignored-mod list was not readable; the mod may not load, so its startup"
                        + " screen may not appear");
                return;
            }
            List<?> ignored = (List<?>) value;
            String own = ownJarName();
            String mod = findJarContaining(loader, MOD_MARKER);
            int removed = 0;
            if (removeEntry(ignored, own)) {
                removed++;
            }
            if (mod != null && !mod.equals(own) && removeEntry(ignored, mod)) {
                removed++;
            }
            if (removed == 0) {
                out("FML's ignored-mod list did not contain this jar or the mod jar; nothing to undo");
            } else {
                out("removed " + removed + " entr" + (removed == 1 ? "y" : "ies") + " from FML's"
                        + " known-library list (" + own + ", " + mod + ") so the mod can load and"
                        + " explain itself");
            }
        } catch (Throwable unavailable) {
            out("could not undo FML's known-library mark (" + describe(unavailable) + "); the mod may"
                    + " not load, so its startup screen may not appear");
        }
    }

    /** The jar this class was loaded from; null when running from a directory (unit test, dev run). */
    static String ownJarName() {
        try {
            CodeSource source = MixinTweakShim.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            // toURI() decodes %20 and friends; getPath() would return the URL-encoded form.
            java.io.File file = new java.io.File(source.getLocation().toURI());
            return file.isFile() ? file.getName() : null;
        } catch (Throwable unavailable) {
            return null;
        }
    }

    /**
     * The first jar on {@code loader}'s source list that contains {@code marker}, by file name.
     *
     * <p>Reflection rather than a cast: this class's constant pool must stay free of LaunchWrapper
     * types so it can be unit-tested on a bare JVM.
     */
    static String findJarContaining(ClassLoader loader, String marker) {
        try {
            Object sources = loader.getClass().getMethod("getSources").invoke(loader);
            if (!(sources instanceof List)) {
                return null;
            }
            for (Object element : (List<?>) sources) {
                if (!(element instanceof java.net.URL)) {
                    continue;
                }
                java.net.URL url = (java.net.URL) element;
                if (!"file".equals(url.getProtocol())) {
                    continue;
                }
                java.io.File file = new java.io.File(url.toURI());
                if (!file.isFile()) {
                    continue;
                }
                if (hasEntry(file, marker)) {
                    return file.getName();
                }
            }
        } catch (Throwable unavailable) {
            return null;
        }
        return null;
    }

    private static boolean hasEntry(java.io.File jar, String entry) {
        java.util.zip.ZipFile zip = null;
        try {
            zip = new java.util.zip.ZipFile(jar);
            return zip.getEntry(entry) != null;
        } catch (Throwable unreadable) {
            return false;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (java.io.IOException ignored) {
                    // nothing useful to do about a failed close on a jar we only read
                }
            }
        }
    }

    /** @return true when the entry was on the list and is now gone */
    static boolean removeEntry(List<?> ignored, String jarName) {
        if (ignored == null || jarName == null || jarName.isEmpty()) {
            return false;
        }
        return ignored.remove(jarName);
    }

    // ------------------------------------------------------------------------------------------
    // reflection, all of it failing soft
    // ------------------------------------------------------------------------------------------

    private static Class<?> resolve(ClassLoader loader) {
        ClassLoader probe = loader == null ? MixinTweakShim.class.getClassLoader() : loader;
        try {
            // false: do NOT run <clinit>. MixinBootstrap's static initialiser boots the service and
            // would lock the class into a failed state if anything below is not ready yet.
            return Class.forName(bootstrapClassName, false, probe);
        } catch (Throwable absent) {
            return null;
        }
    }

    /**
     * @return the value {@code start()} reported, exactly as the real tweaker would read it
     * @throws Throwable when the call itself failed -- that is the only failure this method keeps
     */
    private static boolean start(Class<?> bootstrap) throws Throwable {
        Method method = findDeclared(bootstrap, "start");
        if (method == null) {
            init(bootstrap);
            initialisedByInitFallback = true;
            return true;
        }
        Object result = method.invoke(null);
        return !(result instanceof Boolean) || (Boolean) result;
    }

    private static void init(Class<?> bootstrap) throws Throwable {
        bootstrap.getMethod("init").invoke(null);
    }

    private static void doInit(Class<?> bootstrap, List<String> args) throws Throwable {
        Method method = findDeclared(bootstrap, "doInit", List.class);
        if (method == null) {
            if (initialisedByInitFallback) {
                return;
            }
            // Deliberately NOT init(): init() is start() + doInit(), and start() has already been
            // consumed here, so its second start() call returns false and it returns immediately --
            // a no-op that would be reported as a successful delegation while every mixin stayed dead.
            throw new UnsupportedOperationException(BOOTSTRAP_CLASS + " has no doInit(List); the"
                    + " engine predates 0.7.10 or changed its signature, so Mixin was never initialised");
        }
        method.invoke(null, args);
    }

    private static Object platform(Class<?> bootstrap) {
        try {
            return bootstrap.getMethod("getPlatform").invoke(null);
        } catch (Throwable unreachable) {
            return null;
        }
    }

    // Three outcomes of an optional call. ints, not an enum: a nested enum is its own class file,
    // and this jar's gate allows exactly three (the tweaker, the shim, TweakState).
    /** The method is not on this engine -- a shape difference, try the next entry point. */
    private static final int ABSENT = 0;
    /** The call ran. */
    private static final int DONE = 1;
    /** The method exists and threw -- stop, record, do not try another entry point. */
    private static final int THREW = 2;

    /** Why the last call threw; set only for {@link Call#THREW}, read only for the log line. */
    private static String lastFailure = "";

    private static int invokeStatic(Class<?> owner, String name) {
        Method method = findDeclared(owner, name);
        if (method == null) {
            return ABSENT;
        }
        try {
            method.invoke(null);
            return DONE;
        } catch (Throwable failed) {
            lastFailure = describe(failed);
            return THREW;
        }
    }

    /**
     * Finds a method regardless of visibility.
     *
     * <p>Every real provider declares {@code start()}, {@code doInit(List)} and {@code inject()}
     * package-private (javap: Mixin 0.8.7 in MixinBooter 12.3, 0.8.2 in MixinBooter 5.0, 0.7.11 in
     * malisiscore), so {@link Class#getMethod} cannot see them and the delegation would silently
     * fall back to {@code init()}, dropping the launcher's {@code --mixin} arguments.
     * {@code setAccessible} can be refused under a security manager; the caller then falls back to
     * {@code init()} as before.
     */
    private static Method findDeclared(Class<?> owner, String name, Class<?>... types) {
        try {
            Method method = owner.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method;
        } catch (Throwable absent) {
            return null;
        }
    }


    /**
     * Calls a loader-taking injection entry point, if the owner has one and {@code loader} really is
     * a {@code LaunchClassLoader}.
     *
     * <p>Reflection on the parameter type keeps LaunchWrapper out of this class's constant pool --
     * the same reason the platform calls are reflective -- while still matching the exact signature
     * the older engines declare.
     */
    private static int invokeWithLoader(Object owner, String name, ClassLoader loader) {
        Class<?> loaderType;
        Method method;
        try {
            loaderType = Class.forName("net.minecraft.launchwrapper.LaunchClassLoader", false, loader);
            if (!loaderType.isInstance(loader)) {
                return ABSENT;
            }
            method = findDeclared(owner.getClass(), name, loaderType);
        } catch (Throwable unavailable) {
            return ABSENT;
        }
        if (method == null) {
            return ABSENT;
        }
        try {
            method.invoke(owner, loader);
            return DONE;
        } catch (Throwable failed) {
            lastFailure = describe(failed);
            return THREW;
        }
    }

    private static int invoke(Object owner, String name) {
        Method method = findDeclared(owner.getClass(), name);
        if (method == null) {
            return ABSENT;
        }
        try {
            method.invoke(owner);
            return DONE;
        } catch (Throwable failed) {
            lastFailure = describe(failed);
            return THREW;
        }
    }

    /** {@code MixinBootstrap.VERSION}; "unknown" when the field is absent (0.7-era engines lack it). */
    private static String version(Class<?> bootstrap) {
        try {
            Field field = bootstrap.getField("VERSION");
            Object value = field.get(null);
            return value == null ? "unknown" : String.valueOf(value);
        } catch (Throwable absent) {
            return "unknown";
        }
    }

    private static String location(Class<?> bootstrap) {
        try {
            CodeSource source = bootstrap.getProtectionDomain().getCodeSource();
            return source == null || source.getLocation() == null ? "unknown" : source.getLocation().toString();
        } catch (Throwable absent) {
            return "unknown";
        }
    }

    /** InvocationTargetException hides the interesting cause; unwrap it for the log. */
    private static String describe(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getClass().getName().endsWith("InvocationTargetException")) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return cause.getClass().getName() + (message == null ? "" : ": " + message);
    }

    private static void publish(TweakState state, String detail) {
        try {
            System.setProperty(STATE_PROPERTY, state.name());
            System.setProperty(DETAIL_PROPERTY, detail == null ? "" : detail);
        } catch (SecurityException blocked) {
            // A launcher with a restrictive security manager simply loses the diagnostic; the probe
            // still reports the failure on its own.
        }
    }

    /**
     * Logs through log4j, which is what FML and every 1.12.2 launcher already route to the console
     * and to {@code logs/latest.log}.
     *
     * <p><b>Why reflection.</b> This jar advertises no dependencies: it is loaded before the game and
     * must survive an environment that has no log4j at all (the unit tests run on a bare JVM). The
     * logger is looked up once, reflectively, and if anything about that fails the message goes to
     * stdout instead -- still captured by FML's appender, just without a level or logger name.
     *
     * <p><b>Why the catch is outside.</b> Printing diagnostics may never become the thing that
     * breaks the launch. This method is called from catch blocks whose whole purpose is to keep the
     * launch alive; a throwing {@code println} (stdout replaced or closed) would undo that.
     */
    private static void out(String message) {
        if (logThroughLog4j("info", message)) {
            return;
        }
        try {
            System.out.println("[ControlFlex] " + message);
        } catch (Throwable ignored) {
            // Nowhere left to write. The state property still carries the reason to the mod.
        }
    }

    /** @return true when log4j took the message */
    private static boolean logThroughLog4j(String level, String message) {
        try {
            Object logger = logger();
            if (logger == null) {
                return false;
            }
            Class<?> api = Class.forName("org.apache.logging.log4j.Logger", false,
                    MixinTweakShim.class.getClassLoader());
            java.lang.reflect.Method method = api.getMethod(level, String.class);
            method.invoke(logger, message);
            return true;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /** The log4j logger, resolved once; null when this JVM has none. */
    private static Object logger() {
        if (loggerResolved) {
            return logger;
        }
        loggerResolved = true;
        try {
            Class<?> manager = Class.forName("org.apache.logging.log4j.LogManager", false,
                    MixinTweakShim.class.getClassLoader());
            logger = manager.getMethod("getLogger", String.class).invoke(null, "ControlFlex");
        } catch (Throwable unavailable) {
            logger = null;
        }
        return logger;
    }
}
