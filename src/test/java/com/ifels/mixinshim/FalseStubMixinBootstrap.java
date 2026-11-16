package com.ifels.mixinshim;

import java.util.List;

/**
 * A bootstrap whose {@code start()} reports "a compatible subsystem is already registered".
 *
 * <p>This is not a failure: MixinBooter 5.0's {@code MixinBootstrap.start()} returns {@code false}
 * in exactly that situation, and its real tweaker pops the value and calls {@code doInit} anyway.
 */
public final class FalseStubMixinBootstrap {

    public static String VERSION = "0.8.2";

    private FalseStubMixinBootstrap() {
    }

    public static boolean start() {
        StubCalls.calls.add("start");
        return false;
    }

    public static void init() {
        StubCalls.calls.add("init");
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

        public void inject() {
        }

        public String getLaunchTarget() {
            return null;
        }
    }
}
