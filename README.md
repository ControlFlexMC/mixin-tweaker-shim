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
(MixinBooter, MixinBootstrap, FermiumBooter, malisiscore). A mod that wants its
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
├── META-INF/MANIFEST.MF                                  # TweakClass + ForceLoadAsMod
├── mcmod.info                                            # modid: mixintweakershim
├── org/spongepowered/asm/launch/MixinTweaker.class       # ITweaker, pure forwarder
├── com/ifels/mixinshim/MixinTweakShim.class              # logic, reflection only
├── com/ifels/mixinshim/MixinTweakShim$TweakState.class   # state enum
├── com/ifels/mixinshim/MixinTweakShim$VersionCheck.class # at-least / below / unknown
└── com/ifels/mixinshim/MixinShimMod.class                # @Mod container, identity only
```

The only class outside the project's own package is the one the manifest must
name. It holds no logic; every method forwards. Five class files, named one by one
in the build's gate -- a class swapped for another fails the build.

It **is** a mod, deliberately: `required-after:mixintweakershim` is checked against
the mod list, so the jar has to be in that list. Seeing "Mixin Tweaker Shim" in the
mod list is expected, not a sign that something went wrong.

Asking which Mixin the launch resolved:

```java
MixinTweakShim.hasMixinRuntime();   // is there a MixinBootstrap to drive?
MixinTweakShim.currentState();      // ABSENT / DELEGATED / ALREADY_BOOTED / START_FAILED / NOT_PROBED
MixinTweakShim.detectedVersion();   // "0.8.7", "unknown", or null when there was no bootstrap
MixinTweakShim.checkVersion("0.7.10"); // VersionCheck.AT_LEAST / BELOW / UNKNOWN
MixinTweakShim.detail();            // the sentence that goes to the screen
```

`checkVersion` is three-valued because "readable but too old" and "not readable"
are different facts: 0.7-era engines have no `VERSION` field at all, and turning
that into `false` would tell a player to upgrade something that is fine.

`hasMixinRuntime()` checks for `MixinBootstrap` without initialising it. That is
not the same question the mod's probe asks (`MixinEnvironment` loads), and the two
can disagree in a malformed install; each is right for its own caller.

`detectedVersion()` answers the same thing a direct read of
`MixinBootstrap.VERSION` would, including when the shim never drove the tweak
phase: in that case (a provider's own tweaker won the classpath) the value is
resolved on first call. What it cannot do is answer when the jar is not installed
at all, which is why a mod that must work without this jar still reads the field
itself.

## Using it from another mod

Depend on it from your own container, and do not bundle your own `MixinTweaker`:

```java
@Mod(modid = "mymod", dependencies = "required-after:mixintweakershim")
public final class MyMod { }
```

The dependency belongs in the annotation, not in `mcmod.info`: 1.12.2 only reads
`mcmod.info` dependencies when that file also sets
`"useDependencyInformation": true`, and a dependency FML never reads is worse than
none -- you find out at runtime instead of at launch.

Your own manifest still needs both attributes:

```
TweakClass: org.spongepowered.asm.launch.MixinTweaker
ForceLoadAsMod: true
```

`TweakClass` is what makes legacy providers read your `MixinConfigs`; `ForceLoadAsMod`
is what tells FML (and this shim) that the jar is a mod rather than a library. On an
instance with no provider, the shim restores every ignored jar that sets the second
attribute, so your mod loads and can report the situation itself instead of vanishing
from the mod list. A jar that sets only `TweakClass` stays a library. This jar used to be that jar; it no longer is
-- it declares the attribute too, because dependents check its modid.


Two different things happen when a player installs your mod but not this jar, and
they are worth telling apart in your own documentation:

- **with a Mixin provider installed** -- FML refuses the launch and names the
  missing dependency, because `required-after` is checked against the mod list;
- **with no provider at all** -- the launch dies earlier, with the original
  `ClassNotFoundException` for the tweaker class, before FML gets a say.

The second case is why this jar exists; the first is why it is a mod.

Nothing here names a mod, so the contract is stable: `ForceLoadAsMod` is an attribute
you needed anyway to be force-loaded by a provider's agent.

## A note on sharing

This jar is meant to be installed **once**, next to everything that needs it.

- **Never bundle a copy** inside another mod. `org.spongepowered.asm.launch.MixinTweaker` can
  only be supplied by one jar: LaunchWrapper resolves it by name, the first copy on the classpath
  wins, and the loser's behaviour disappears without a word. Two copies also means two different
  un-ignore rules running, or none.
- **Never shade or relocate it.** The whole point is the binary name; a relocated copy is a
  different class that no provider will recognise.
- **The opt-in will not change meaning.** `ForceLoadAsMod` keeps meaning "this jar is a mod", and
  a jar that sets only `TweakClass` keeps meaning "library". New queries and new attributes may be
  added; a breaking change would arrive as a new artifact, not as a new meaning for an old name.
- **It provides no Mixin.** Without a provider, mixins still do not apply. What it buys is a
  launch that happens and a mod that can explain itself, instead of a `ClassNotFoundException`
  before the window exists.
- **It never throws.** Every failure is logged and recorded; this jar must not become the reason a
  launch dies.

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

`gradle build` runs `verifyShimJar`, which fails the build unless the jar carries
exactly the five named classes, an `org/` class other than the tweaker,
`mcmod.info`, or a config/plugin manifest attribute.

## Status

Implemented and measured. The design document and the matrix evidence live in
the mod repository:

- `control-flex-1.12.2/docs/superpowers/specs/2026-11-16-mixin-tweaker-shim-split-design.md`
- `control-flex-1.12.2/docs/review/2026-11-16-mixin-shim-matrix.md`

## License

MIT, Copyright (c) 2026 ControlFlexMC. See [LICENSE](LICENSE).
