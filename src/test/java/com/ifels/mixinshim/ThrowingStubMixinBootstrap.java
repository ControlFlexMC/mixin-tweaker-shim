package com.ifels.mixinshim;

import java.util.List;

/** A bootstrap whose {@code start()} throws, so the shim's failure path stays covered. */
public final class ThrowingStubMixinBootstrap {

    public static String VERSION = "0.8.7";

    private ThrowingStubMixinBootstrap() {
    }

    public static boolean start() {
        StubCalls.calls.add("start");
        throw new IllegalStateException("boom");
    }

    public static void doInit(List<String> args) {
        StubCalls.calls.add("doInit");
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
            return null;
        }
    }
}
