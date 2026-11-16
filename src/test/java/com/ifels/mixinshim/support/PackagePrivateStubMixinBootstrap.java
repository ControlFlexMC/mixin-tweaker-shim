package com.ifels.mixinshim.support;

import java.util.List;

import com.ifels.mixinshim.StubCalls;

/**
 * Mirrors the real {@code MixinBootstrap} visibility and lives in a DIFFERENT package on purpose.
 *
 * <p>Measured with javap on Mixin 0.8.7 (MixinBooter 12.3), 0.8.2 (MixinBooter 5.0) and 0.7.11
 * (malisiscore): {@code start()}, {@code doInit(List)} and {@code inject()} are package-private. A
 * shim that uses {@code Class#getMethod} cannot see them and silently degrades to {@code init()},
 * dropping the launcher's {@code --mixin} arguments.
 *
 * <p>The foreign package is the point: the shim's reflection has to reach a package-private method
 * across packages, exactly like it will at runtime, so {@code setAccessible(true)} is genuinely
 * exercised instead of being satisfied by same-package access.
 */
public final class PackagePrivateStubMixinBootstrap {

    public static String VERSION = "0.8.7";

    private PackagePrivateStubMixinBootstrap() {
    }

    static boolean start() {
        StubCalls.calls.add("start");
        return true;
    }

    static void doInit(List<String> args) {
        StubCalls.calls.add("doInit");
    }

    public static void init() {
        StubCalls.calls.add("init");
    }

    static void inject() {
        StubCalls.calls.add("inject");
    }

    public static Platform getPlatform() {
        return new Platform();
    }

    /** Minimal platform manager stand-in. */
    public static final class Platform {

        public String getLaunchTarget() {
            return null;
        }
    }
}
