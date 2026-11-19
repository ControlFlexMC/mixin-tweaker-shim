# Mixin Tweaker Shim


## What is this?

**Mixin Tweaker Shim** is a tiny two-class tweaker jar for **Minecraft 1.12.2 (Forge)**.

On 1.12.2, Mixin is not part of Forge — it arrives with a separate provider such as MixinBooter, MixinBootstrap, FermiumBooter, or malisiscore. A mod that wants its mixin configs read by *every* provider generation declares this in its jar manifest:

```
TweakClass: org.spongepowered.asm.launch.MixinTweaker
ForceLoadAsMod: true
```

LaunchWrapper resolves that tweaker class **before Forge constructs any mod**. On an instance with a Mixin provider, the provider ships the class and everything works. On an instance with **no provider at all**, the launch dies before a single window appears:

```
[LaunchWrapper]: Loading tweak class name org.spongepowered.asm.launch.MixinTweaker
[LaunchWrapper]: Unable to launch
java.lang.ClassNotFoundException: org.spongepowered.asm.launch.MixinTweaker
```

No window. No Forge. No error screen. Nothing in the mod list to blame.

This jar supplies that class name so the launch **survives**. Mods that depend on it still load, detect the missing Mixin runtime themselves, and explain the situation on screen — instead of the whole game vanishing with a `ClassNotFoundException`.

## What this is NOT

Read this part twice:

- **It is not a Mixin provider.** It ships no `org.spongepowered.asm.*` runtime, no MixinExtras, and applies no mixins. If your mod needs mixins to work, you still need a real provider (MixinBooter, MixinBootstrap, FermiumBooter, etc.).
- **It is not a fallback that makes mixins apply.** What it buys is a launch that happens and a mod that can explain itself — nothing more.
- **You will see it in your mod list.** That is deliberate: other mods depend on it by modid (`mixintweakershim`), and FML can only check dependencies against the mod list. Seeing "Mixin Tweaker Shim" in the list is expected, not a sign that something broke.

The name says exactly what it does: it is a **shim for the tweaker entry point**, not an implementation of Mixin.

## Why a mod would use it

A mod declaring `TweakClass: org.spongepowered.asm.launch.MixinTweaker` faces two different failure modes when it lands in a mods folder without this shim:

| Instance state | What happens without the shim |
| --- | --- |
| A Mixin provider **is** installed | Fine — the provider ships the class. |
| A provider is installed but the mod jar is not force-loaded | The mod silently stays a "library" and never loads. |
| **No provider at all** | `ClassNotFoundException` before the window exists. No log you can act on, no mod list to inspect. |

With the shim installed:

- The launch always reaches Forge, even on provider-less instances.
- Every jar that declares `ForceLoadAsMod: true` is restored to the mod list, so provider-less installs still see the mods that need Mixin — degraded, but present and explaining themselves.
- Mods can query the shim for what the launch actually resolved (developer API: see the source repository's README).

## Installation (players)

1. Install **Minecraft 1.12.2 + Forge** as usual.
2. Drop `mixin-tweaker-shim-x.y.z.jar` into your `mods` folder — **once per instance**.
3. Install whatever mod told you to get this file next to it.

That's it. The jar is a few KB and contains exactly five class files.

### Rules for distribution

- **Never bundle a copy inside another mod.** `org.spongepowered.asm.launch.MixinTweaker` can only be supplied by one jar: LaunchWrapper resolves it by name, the first copy on the classpath wins, and the loser's behaviour disappears without a word. Two copies also means two different un-ignore rules running — or none.
- **Never shade or relocate it.** The whole point is the binary name; a relocated copy is a different class that no provider will recognise.
- Link to this page instead. The jar is meant to be installed once, next to everything that needs it.

## FAQ

**Does this add Mixin support to 1.12.2?**

No. If you need mixins to apply, install a provider such as MixinBooter, MixinBootstrap, or FermiumBooter. This shim only makes the launch survivable when no provider exists.

**The mod list shows "Mixin Tweaker Shim" — is something wrong?**

No. It is a real mod on purpose, so that other mods' `required-after:mixintweakershim` dependency can resolve. Its presence is the normal state.

**My game used to crash with `ClassNotFoundException: org.spongepowered.asm.launch.MixinTweaker`.**

That is exactly the failure this jar prevents. Install it and the launch reaches Forge; the affected mod will then tell you on screen that its Mixin runtime is missing.

**Can two mods each ship their own copy?**

They shouldn't, and good mods won't. Only the first copy on the classpath wins, and duplicate un-ignore rules are undefined behaviour. Install one copy per instance.

**Will it change meaning in future updates?**

`ForceLoadAsMod` keeps meaning "this jar is a mod", and a jar that sets only `TweakClass` keeps meaning "library". New queries and attributes may be added; a breaking change would arrive as a new artifact, not as a new meaning for an old name.

**Is it safe?**

It never throws. Every failure is logged and recorded; the jar must not become the reason a launch dies. The build carries a shape gate that fails if the jar ever ships anything beyond its five named classes, a config registration, or a core plugin entry — the jar stays auditable at a glance.


## Links

- Source & issue tracker: see the project repository.
- Companion project: ControlFlex, which uses the shim's probe API to report missing Mixin runtimes in a guidance page.

---
