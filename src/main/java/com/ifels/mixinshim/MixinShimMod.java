package com.ifels.mixinshim;

import net.minecraftforge.fml.common.Mod;

/**
 * The container, and nothing else.
 *
 * <p>Its only job is to give this jar a modid. Other mods write
 * {@code required-after:mixintweakershim} in their own {@code @Mod(dependencies = ...)}, and FML
 * resolves that against the mod list -- so the jar has to be in the list, which means it has to be
 * a mod.
 *
 * <p>No {@code version} attribute: {@code mcmod.info} carries it, expanded from {@code build.gradle}
 * at build time, so the number lives in exactly one place.
 *
 * <p>{@code acceptableRemoteVersions = "*"}: this is launch-time infrastructure and behaves the same
 * on both sides, so a client/server mismatch is not a reason to refuse a connection.
 */
@Mod(modid = MixinShimMod.MOD_ID, name = MixinShimMod.MOD_NAME,
        acceptableRemoteVersions = "*", acceptedMinecraftVersions = "[1.12.2]")
public final class MixinShimMod {

    /** The id other mods depend on. */
    public static final String MOD_ID = "mixintweakershim";

    /** The name shown in the mod list. */
    public static final String MOD_NAME = "Mixin Tweaker Shim";
}
