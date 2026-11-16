package com.ifels.mixinshim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.ifels.mixinshim.MixinTweakShim.TweakState;

import net.minecraft.launchwrapper.ITweaker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shadow tweaker occupies a class name it does not own, so its behaviour has to be pinned
 * exactly.
 *
 * <p>Two properties matter:
 *
 * <ul>
 *   <li><b>With a provider present it must be invisible.</b> The provider's own {@code MixinTweaker}
 *       normally wins the classpath, but when ours does not, ours has to drive {@code MixinBootstrap}
 *       through the very same four calls in the very same order -- a shim that only called
 *       {@code init()} would silently drop the launcher's {@code --mixin} arguments.</li>
 *   <li><b>With no provider it must not throw.</b> An exception in the tweak phase has no error
 *       screen behind it, which is the exact failure this class exists to remove: the launch has to
 *       reach Forge, where the mod's own probe turns the missing runtime into a readable page.</li>
 * </ul>
 *
 * <p>The loader is passed explicitly (rather than read from {@code Launch}) so the same reflection
 * path is exercised against a stub bootstrap, and so this test needs no launch environment at all.
 */
public class MixinTweakShimTest {

    private static final ClassLoader LOADER = MixinTweakShimTest.class.getClassLoader();

    @BeforeEach
    void setUp() {
        MixinTweakShim.resetForTest();
        StubCalls.reset();
    }

    @AfterEach
    void tearDown() {
        MixinTweakShim.resetForTest();
        StubCalls.reset();
    }

    @Test
    @DisplayName("a provider is driven in the real tweaker's order: start, doInit, then inject")
    void delegatesInTheRealTweakersOrder() {
        MixinTweakShim.bindBootstrap(StubMixinBootstrap.class.getName());
        List<String> args = Arrays.asList("--username", "Dev");

        MixinTweakShim.acceptOptions(args, LOADER);
        MixinTweakShim.injectIntoClassLoader(LOADER);

        assertEquals(Arrays.asList("start", "doInit", "inject"), StubCalls.calls,
                "the order is the contract: doInit before start throws, and inject() before start "
                        + "leaves Mixin uninitialised");
        assertEquals(TweakState.DELEGATED, MixinTweakShim.currentState());
        assertTrue(MixinTweakShim.detail().contains("0.8.7"),
                "the log has to name which Mixin the launch actually uses");
    }

    @Test
    @DisplayName("the launch target comes from the provider's platform, not from us")
    void delegatesTheLaunchTarget() {
        MixinTweakShim.bindBootstrap(StubMixinBootstrap.class.getName());
        MixinTweakShim.acceptOptions(Collections.<String>emptyList(), LOADER);

        assertEquals("net.minecraft.launchwrapper.Launch", MixinTweakShim.getLaunchTarget(LOADER),
                "returning null here would silently hand the launch to whichever tweaker comes next");
    }

    @Test
    @DisplayName("no provider: no exception, a recorded ABSENT state, and a readable reason")
    void missingProviderIsRecordedNotThrown() {
        MixinTweakShim.bindBootstrap("org.spongepowered.asm.launch.DoesNotExist");

        MixinTweakShim.acceptOptions(Collections.<String>emptyList(), LOADER);
        MixinTweakShim.injectIntoClassLoader(LOADER);
        String target = MixinTweakShim.getLaunchTarget(LOADER);

        assertEquals(TweakState.ABSENT, MixinTweakShim.currentState());
        assertNull(target, "without a provider there is no launch target to return");
        assertFalse(MixinTweakShim.detail().isEmpty(),
                "the probe puts this text on the error screen; an empty detail would say nothing");
        assertTrue(StubCalls.calls.isEmpty(), "nothing may be called on a bootstrap that is not there");
    }

    @Test
    @DisplayName("probing for the bootstrap must not initialise it")
    void resolutionDoesNotInitialiseTheBootstrap() {
        MixinTweakShim.bindBootstrap(ProbeOnlyStubMixinBootstrap.class.getName());

        assertTrue(MixinTweakShim.isBootstrapVisible(LOADER));
        assertEquals(0, StubCalls.probeClinitCount,
                "Class.forName(name, false, loader) is the contract: MixinBootstrap's <clinit> boots "
                        + "the service, and a failure there poisons the class for the entire launch");

        MixinTweakShim.acceptOptions(Collections.<String>emptyList(), LOADER);
        assertEquals(1, StubCalls.probeClinitCount, "start() is what initialises it, exactly once");
    }

    @Test
    @DisplayName("start() returning false means a compatible subsystem is already up, not a failure")
    void startFalseMeansAlreadyBooted() {
        MixinTweakShim.bindBootstrap(FalseStubMixinBootstrap.class.getName());

        MixinTweakShim.acceptOptions(Collections.<String>emptyList(), LOADER);
        MixinTweakShim.injectIntoClassLoader(LOADER);

        assertEquals(TweakState.ALREADY_BOOTED, MixinTweakShim.currentState());
        assertEquals(Arrays.asList("start", "doInit", "inject"), StubCalls.calls,
                "MixinBooter 5.0's real tweaker pops start()'s boolean and calls doInit anyway; "
                        + "dropping doInit here leaves every mixin silently dead");
    }

    @Test
    @DisplayName("a start() that throws is still a recorded failure, and inject() is skipped")
    void throwingStartIsRecorded() {
        MixinTweakShim.bindBootstrap(ThrowingStubMixinBootstrap.class.getName());

        MixinTweakShim.acceptOptions(Collections.<String>emptyList(), LOADER);
        MixinTweakShim.injectIntoClassLoader(LOADER);

        assertEquals(TweakState.START_FAILED, MixinTweakShim.currentState());
        assertTrue(MixinTweakShim.detail().contains("start"), "the reason has to survive to the screen");
        assertFalse(StubCalls.calls.contains("inject"),
                "injecting into a service that never started cannot work");
    }

    @Test
    @DisplayName("acceptOptions is idempotent: a second call does not restart Mixin")
    void acceptOptionsIsIdempotent() {
        MixinTweakShim.bindBootstrap(StubMixinBootstrap.class.getName());

        MixinTweakShim.acceptOptions(Collections.<String>emptyList(), LOADER);
        MixinTweakShim.acceptOptions(Collections.<String>emptyList(), LOADER);

        assertEquals(Collections.singletonList("start"),
                StubCalls.calls.subList(0, 1), "the second call must not start the service again");
        assertEquals(2, StubCalls.calls.size(), "start and doInit, nothing else");
    }

    @Test
    @DisplayName("package-private entry points are delegated to, not replaced by init()")
    void delegatesPackagePrivateEntryPoints() {
        MixinTweakShim.bindBootstrap(
                com.ifels.mixinshim.support.PackagePrivateStubMixinBootstrap.class.getName());

        MixinTweakShim.acceptOptions(Collections.<String>emptyList(), LOADER);
        MixinTweakShim.injectIntoClassLoader(LOADER);

        assertEquals(Arrays.asList("start", "doInit", "inject"), StubCalls.calls,
                "every real provider declares these package-private; getMethod would fall back to init()");
    }

    @Test
    @DisplayName("the ignored-mod entry is removed by value, and only when it is there")
    void removesTheIgnoredModEntry() {
        List<String> ignored = new java.util.ArrayList<String>(
                Arrays.asList("jei.jar", "controlflex.jar"));

        assertTrue(MixinTweakShim.removeEntry(ignored, "controlflex.jar"),
                "FML keys the list by file name, so the removal has to match it exactly");
        assertEquals(Arrays.asList("jei.jar"), ignored);

        assertFalse(MixinTweakShim.removeEntry(ignored, "controlflex.jar"));
        assertFalse(MixinTweakShim.removeEntry(null, "controlflex.jar"));
        assertFalse(MixinTweakShim.removeEntry(ignored, ""));
        assertFalse(MixinTweakShim.removeEntry(ignored, null));
    }

    @Test
    @DisplayName("an engine with start() but no doInit(List) is a failure, not a silent no-op")
    void missingDoInitIsRecordedAsFailure() {
        MixinTweakShim.bindBootstrap(NoDoInitStubMixinBootstrap.class.getName());

        MixinTweakShim.acceptOptions(Collections.<String>emptyList(), LOADER);
        MixinTweakShim.injectIntoClassLoader(LOADER);

        assertEquals(TweakState.START_FAILED, MixinTweakShim.currentState(),
                "falling back to init() here cannot work: init() is start() plus doInit(), and "
                        + "start() was already consumed, so the second start() returns false");
        assertTrue(MixinTweakShim.detail().contains("doInit"), "the screen has to name what is missing");
        assertFalse(StubCalls.calls.contains("init"),
                "init() must not be called at all -- it would look like success while doing nothing");
        assertNull(MixinTweakShim.getLaunchTarget(LOADER), "no delegation happened, so no launch target");
    }

    @Test
    @DisplayName("an inject() that throws stops the chain: no second entry point, no exception")
    void throwingInjectStopsTheChain() {
        MixinTweakShim.bindBootstrap(ThrowingInjectStubMixinBootstrap.class.getName());

        MixinTweakShim.acceptOptions(Collections.<String>emptyList(), LOADER);
        MixinTweakShim.injectIntoClassLoader(LOADER);

        assertTrue(StubCalls.calls.contains("inject"), "the first entry point has to be tried");
        assertFalse(StubCalls.calls.contains("platform.inject"),
                "trying another entry point after one threw risks injecting twice; the shim must"
                        + " stop and report instead");
        assertEquals(TweakState.DELEGATED, MixinTweakShim.currentState(),
                "the tweak phase succeeded; only the injection failed, and that is logged, not thrown");
    }

    @Test
    @DisplayName("asked before the tweak phase, the API still answers the same as reflection")
    void answersWithoutHavingDrivenTheTweakPhase() {
        // The common real case: a provider's own MixinTweaker won the classpath, so acceptOptions
        // never ran. The API must not answer null here -- that would disagree with the field it
        // claims to report, and callers would stop trusting it.
        MixinTweakShim.bindBootstrap(StubMixinBootstrap.class.getName());

        assertEquals("0.8.7", MixinTweakShim.detectedVersion());
        assertEquals(TweakState.NOT_PROBED, MixinTweakShim.currentState(),
                "answering the version does not pretend the tweak phase ran");

        MixinTweakShim.resetForTest();
        MixinTweakShim.bindBootstrap("org.spongepowered.asm.launch.DoesNotExist");
        assertNull(MixinTweakShim.detectedVersion(),
                "no bootstrap is still null, lazily or not");
    }

    @Test
    @DisplayName("the caller can ask which Mixin this launch resolved, and null means none did")
    void reportsTheDetectedVersion() {
        assertNull(MixinTweakShim.detectedVersion(), "nothing has been probed yet");

        MixinTweakShim.bindBootstrap(StubMixinBootstrap.class.getName());
        MixinTweakShim.acceptOptions(Collections.<String>emptyList(), LOADER);
        assertEquals("0.8.7", MixinTweakShim.detectedVersion(),
                "the same string the log line and the detail text carry, available without parsing");

        MixinTweakShim.resetForTest();
        MixinTweakShim.bindBootstrap("org.spongepowered.asm.launch.DoesNotExist");
        MixinTweakShim.acceptOptions(Collections.<String>emptyList(), LOADER);
        assertNull(MixinTweakShim.detectedVersion(),
                "no bootstrap means no version; null is distinct from the 'unknown' an engine "
                        + "without a VERSION field would report");
    }

    @Test
    @DisplayName("a failed start still leaves the version the shim was talking to")
    void reportsTheVersionEvenWhenStartFails() {
        MixinTweakShim.bindBootstrap(ThrowingStubMixinBootstrap.class.getName());

        MixinTweakShim.acceptOptions(Collections.<String>emptyList(), LOADER);

        assertEquals(TweakState.START_FAILED, MixinTweakShim.currentState());
        assertEquals("0.8.7", MixinTweakShim.detectedVersion(),
                "the detail text only carries the failure reason; without this the screen could not "
                        + "say which Mixin failed");
    }

    @Test
    @DisplayName("the shadow class itself is the jar's TweakClass entry point")
    void shadowTweakerForwardsToTheShim() {
        MixinTweakShim.bindBootstrap(StubMixinBootstrap.class.getName());

        // The very class the manifest names; if it ever stops implementing ITweaker or stops
        // forwarding, LaunchWrapper cannot load the jar at all.
        ITweaker tweaker = new org.spongepowered.asm.launch.MixinTweaker();
        tweaker.acceptOptions(Collections.<String>emptyList(), null, null, null);

        assertNotNull(tweaker.getLaunchTarget());
        assertEquals(TweakState.DELEGATED, MixinTweakShim.currentState());
        assertTrue(StubCalls.calls.contains("start"));
    }

    @Test
    @DisplayName("outside a jar there is no file name to un-ignore, and that is not an error")
    void ownJarNameIsNullWhenRunningFromClasses() {
        assertNull(MixinTweakShim.ownJarName(),
                "the unit test runs from build/classes; the launch path must treat that as 'nothing"
                        + " to remove' instead of guessing a name");
    }
}
