package com.ifels.mixinshim;

import java.util.List;

/**
 * Stands in for {@code org.spongepowered.asm.launch.MixinBootstrap} so the shim can be tested
 * without booting the real Mixin service (which expects a launch environment). Only the four touch
 * points the real tweaker uses are modelled.
 */
public final class StubMixinBootstrap {

    /** Mutable on purpose: the version string must survive into the published detail. */
    public static String VERSION = "0.8.7";

    static {
        StubCalls.clinitCount++;
    }

    private StubMixinBootstrap() {
    }

    public static boolean start() {
        StubCalls.calls.add("start");
        return true;
    }

    public static void doInit(List<String> args) {
        StubCalls.calls.add("doInit");
    }

    public static void init() {
        StubCalls.calls.add("init");
    }

    public static void inject() {
        StubCalls.calls.add("inject");
    }

    public static Platform getPlatform() {
        StubCalls.calls.add("getPlatform");
        return new Platform();
    }

    /** Mirrors {@code MixinPlatformManager} for the calls the shim delegates to it. */
    public static final class Platform {

        public void inject() {
            StubCalls.calls.add("platform.inject");
        }

        public String getLaunchTarget() {
            return "net.minecraft.launchwrapper.Launch";
        }
    }
}
