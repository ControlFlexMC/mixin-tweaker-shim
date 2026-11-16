package com.ifels.mixinshim;

import java.util.List;

/**
 * A bootstrap used by exactly ONE test, so "the class was not initialised" is observable: a class
 * initialises once per JVM, and the shared {@link StubMixinBootstrap} is initialised by the other
 * cases in this class.
 */
public final class ProbeOnlyStubMixinBootstrap {

    static {
        StubCalls.probeClinitCount++;
    }

    private ProbeOnlyStubMixinBootstrap() {
    }

    public static boolean start() {
        return true;
    }

    public static void doInit(List<String> args) {
    }

    public static void inject() {
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
