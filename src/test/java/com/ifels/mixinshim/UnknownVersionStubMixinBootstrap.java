package com.ifels.mixinshim;

import java.util.List;

/** Stub reporting {@code VERSION = "unknown"}, for the version-comparison tests. */
public final class UnknownVersionStubMixinBootstrap {

    public static final String VERSION = "unknown";

    private UnknownVersionStubMixinBootstrap() {
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
    }

    public static Platform getPlatform() {
        return new Platform();
    }

    /** Minimal platform manager stand-in. */
    public static final class Platform {

        public String getLaunchTarget() {
            return "net.minecraft.launchwrapper.Launch";
        }

        public void inject() {
            StubCalls.calls.add("platform.inject");
        }
    }
}
