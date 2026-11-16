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

    /** Overridable so the test can point the same reflection path at a stub. */
    private static String bootstrapClassName = BOOTSTRAP_CLASS;

    /** Guards the duplicate {@code acceptOptions} some launchers issue. */
    private static boolean started;

    /** True only once start() and doInit() both succeeded; gates inject()/getLaunchTarget(). */
    private static boolean delegated;

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
            return;
        }
        Class<?> bootstrap = resolve(loader);
        if (bootstrap == null) {
            return;
        }
        if (invokeStatic(bootstrap, "inject")) {
            return;
        }
        Object platform = platform(bootstrap);
        if (platform != null && invoke(platform, "inject")) {
            return;
        }
        out("MixinBootstrap.inject() was not callable; the platform manager was not injected");
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
            init(bootstrap);
            return;
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

    private static boolean invokeStatic(Class<?> owner, String name) {
        Method method = findDeclared(owner, name);
        if (method == null) {
            return false;
        }
        try {
            method.invoke(null);
            return true;
        } catch (Throwable unreachable) {
            return false;
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

    private static boolean invoke(Object owner, String name) {
        try {
            Method method = owner.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(owner);
            return true;
        } catch (Throwable unreachable) {
            return false;
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

    /** stdout, not a logger: this runs before the mod and must not drag mod classes in. */
    private static void out(String message) {
        System.out.println("[ControlFlex] " + message);
    }
}
