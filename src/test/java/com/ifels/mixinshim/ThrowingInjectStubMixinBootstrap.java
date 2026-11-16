package com.ifels.mixinshim;

import java.util.List;

/**
 * A bootstrap whose {@code inject()} exists but throws -- the shape where falling through to another
 * injection entry point would inject twice, and where swallowing the failure would hide the reason.
 */
public final class ThrowingInjectStubMixinBootstrap {

    public static String VERSION = "0.8.7";

    private ThrowingInjectStubMixinBootstrap() {
    }

    public static boolean start() {
        StubCalls.calls.add("start");
        return true;
    }

    public static void doInit(List<String> args) {
        StubCalls.calls.add("doInit");
    }

    public static void inject() {
        StubCalls.calls.add("inject");
        throw new IllegalStateException("platform exploded");
    }

    public static Platform getPlatform() {
        return new Platform();
    }

    /** If this is reached, the shim tried a second entry point after the first one threw. */
    public static final class Platform {

        public void inject() {
            StubCalls.calls.add("platform.inject");
        }

        public String getLaunchTarget() {
            return "net.minecraft.launchwrapper.Launch";
        }
    }
}
