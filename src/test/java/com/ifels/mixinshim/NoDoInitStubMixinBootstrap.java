package com.ifels.mixinshim;

import java.util.List;

/**
 * A bootstrap with {@code start()} but no {@code doInit(List)} -- the shape an engine would have
 * if it predates the doInit entry point or changed its signature.
 *
 * <p>Falling back to {@code init()} here cannot work: {@code init()} is {@code start()} plus
 * {@code doInit()}, and {@code start()} has already been consumed by the time this is reached, so
 * the second {@code start()} returns false and {@code init()} returns immediately. The shim must
 * report that instead of claiming it delegated.
 */
public final class NoDoInitStubMixinBootstrap {

    public static String VERSION = "0.6.9";

    private NoDoInitStubMixinBootstrap() {
    }

    public static boolean start() {
        StubCalls.calls.add("start");
        return true;
    }

    public static void init() {
        StubCalls.calls.add("init");
    }

    public static void inject() {
        StubCalls.calls.add("inject");
    }

    public static Platform getPlatform() {
        return new Platform();
    }

    /** Minimal platform manager stand-in. */
    public static final class Platform {

        public String getLaunchTarget() {
            return "net.minecraft.launchwrapper.Launch";
        }
    }
}
