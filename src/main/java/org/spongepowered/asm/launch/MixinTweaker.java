package org.spongepowered.asm.launch;

import com.ifels.mixinshim.MixinTweakShim;

import java.io.File;
import java.util.List;

import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;

/**
 * ControlFlex's SHADOW of the Mixin tweaker -- deliberately the same binary name as the one every
 * 1.12.2 Mixin provider ships.
 *
 * <p><b>Why this class exists.</b> A mod that wants its mixin configs read by every provider
 * generation has to declare {@code TweakClass: org.spongepowered.asm.launch.MixinTweaker} in its
 * manifest. Mixin's classpath scan matches that value literally -- that is the only reason such a
 * jar is treated as a Mixin container and its {@code MixinConfigs} are read -- so the attribute
 * cannot be dropped without breaking registration on MixinBootstrap / MixinCompat / MixinBooter
 * &lt; 11.0. LaunchWrapper resolves the class before Forge constructs any mod, so an instance with
 * no provider at all used to abort with {@code ClassNotFoundException} before the game window
 * existed: no Forge, no probe, no readable message. This class makes the name always resolvable
 * without changing anybody's manifest.
 *
 * <p><b>It holds no logic.</b> Every method forwards to {@link MixinTweakShim}, which drives the
 * same {@code MixinBootstrap} entry points the real tweaker drives, in the same order. When a
 * provider's own copy wins the classpath this class is never loaded at all.
 *
 * <p><b>Nothing here may reference Minecraft.</b> It runs before the game classes exist; the only
 * types in its signature are LaunchWrapper's own.
 */
public final class MixinTweaker implements ITweaker {

    public MixinTweaker() {
        MixinTweakShim.logIdentity();
    }

    @Override
    public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
        MixinTweakShim.acceptOptions(args, getClass().getClassLoader());
    }

    @Override
    public void injectIntoClassLoader(LaunchClassLoader classLoader) {
        MixinTweakShim.injectIntoClassLoader(classLoader);
    }

    @Override
    public String getLaunchTarget() {
        return MixinTweakShim.getLaunchTarget(getClass().getClassLoader());
    }

    @Override
    public String[] getLaunchArguments() {
        return new String[0];
    }
}
