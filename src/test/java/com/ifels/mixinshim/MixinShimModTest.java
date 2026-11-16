package com.ifels.mixinshim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.minecraftforge.fml.common.Mod;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The container exists only to give this jar a modid other mods can write in a dependency. */
public class MixinShimModTest {

    @Test
    @DisplayName("the container carries the modid other mods will depend on")
    void containerHasTheModId() {
        Mod mod = MixinShimMod.class.getAnnotation(Mod.class);
        assertNotNull(mod, "without @Mod this jar never reaches the mod list, and required-after "
                + "in a dependent's annotation would fail at launch");
        assertEquals("mixintweakershim", mod.modid());
        assertEquals("*", mod.acceptableRemoteVersions());
        assertEquals("[1.12.2]", mod.acceptedMinecraftVersions());
    }
}
