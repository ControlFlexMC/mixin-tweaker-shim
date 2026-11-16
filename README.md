# mixin-tweaker-shim

A two-class tweaker jar for Minecraft 1.12.2 Forge. It keeps the launch alive on
instances that have **no Mixin provider installed**, so a mod that declares
`TweakClass: org.spongepowered.asm.launch.MixinTweaker` in its manifest can still
reach Forge and explain itself instead of dying in LaunchWrapper.

## What this is not

This is **not** a Mixin provider, and not a fallback for one. It ships no
`org.spongepowered.asm.*` runtime, no MixinExtras, and applies no mixins. If a
provider is installed, this jar's class is never loaded — the provider's own
copy of that name wins the classpath. If no provider is installed, this jar gets
the game as far as the mod's own startup probe, which then reports the missing
provider on screen.

The name says exactly what it does: it is a **shim for the tweaker entry point**,
not an implementation of Mixin.

## Why this exists

On 1.12.2, Mixin is not part of Forge — it arrives with a separate provider
(MixinBooter, MixinBootstrap, MixinCompat, malisiscore). A mod that wants its
mixin configs read by *every* provider generation has to declare
`TweakClass: org.spongepowered.asm.launch.MixinTweaker`, because the pre-11
providers match that manifest value literally when they decide which jars are
Mixin containers.

LaunchWrapper resolves that class **before Forge constructs any mod**. With a
provider present the class is there (the provider ships it). With no provider at
all it is not, and the launch aborts with:

```
[LaunchWrapper]: Loading tweak class name org.spongepowered.asm.launch.MixinTweaker
[LaunchWrapper]: Unable to launch
java.lang.ClassNotFoundException: org.spongepowered.asm.launch.MixinTweaker
```

No window, no Forge, no error screen. This jar supplies that class name so the
launch survives. ControlFlex then detects the missing runtime itself and starts
in a degraded mode with its controller input disabled and a guidance page.

## Contents

```
mixin-tweaker-shim-0.9.0.jar
├── META-INF/MANIFEST.MF                                # TweakClass only
├── org/spongepowered/asm/launch/MixinTweaker.class     # ITweaker, pure forwarder
├── com/ifels/mixinshim/MixinTweakShim.class            # logic, reflection only
└── com/ifels/mixinshim/MixinTweakShim$TweakState.class # diagnostics enum
```

The only class outside the project's own package is the one the manifest must
name. It holds no logic; every method forwards. Three class files in total — the
build fails if that number ever grows.

No `mcmod.info`: this is a tweaker library, not a mod. Because it declares
`TweakClass`, FML skips it as a "known library file" and never lists it as a mod.

Asking which Mixin the launch resolved:

```java
MixinTweakShim.currentState();      // ABSENT / DELEGATED / ALREADY_BOOTED / START_FAILED / NOT_PROBED
MixinTweakShim.detectedVersion();   // "0.8.7", "unknown", or null when there was no bootstrap
MixinTweakShim.detail();            // the sentence that goes to the screen
```

`detectedVersion()` answers the same thing a direct read of
`MixinBootstrap.VERSION` would, including when the shim never drove the tweak
phase: in that case (a provider's own tweaker won the classpath) the value is
resolved on first call. What it cannot do is answer when the jar is not installed
at all, which is why a mod that must work without this jar still reads the field
itself.

## Building

```bash
./tools/build-shim.sh                 # build + verify the three-class shape
MC_1_12_2_FORGE_PATH=~/.prism/instances/1.12.2-forge/minecraft ./tools/build-shim.sh
./tools/build-shim.sh --release       # + copy to RELEASE_DEPLOY_PATH
```

The script pins the build JVM to a JDK between 11 and 19. `options.release = 8`
rules out JDK 8 (the flag is javac 9+), and Gradle 8.1.1 refuses to run on
anything newer than 19, so 17 is the practical choice; the script finds one
rather than making every caller remember. Override with `JAVA_HOME`.

`gradle build` runs `verifyShimJar`, which fails the build if the jar carries
anything but the three classes, an `org/` class other than the tweaker,
`mcmod.info`, or a config/plugin manifest attribute.

## Status

Implemented and measured. The design document and the matrix evidence live in
the mod repository:

- `control-flex-1.12.2/docs/superpowers/specs/2026-11-16-mixin-tweaker-shim-split-design.md`
- `control-flex-1.12.2/docs/review/2026-11-16-mixin-shim-matrix.md`

## License

MIT, Copyright (c) 2026 ControlFlexMC. See [LICENSE](LICENSE).
