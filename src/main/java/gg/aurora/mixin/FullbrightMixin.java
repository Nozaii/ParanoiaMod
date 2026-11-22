package gg.aurora.mixin;

import gg.aurora.module.Modules;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Fullbright: raises the night vision and brightness inputs of the lightmap directly. It doesn't depend
 * on the gamma option, which other mods, profiles and options.txt can reset at any time.
 */
@Mixin(LightTexture.class)
public class FullbrightMixin {
    private static final String PUT = "Lcom/mojang/blaze3d/buffers/Std140Builder;putFloat(F)Lcom/mojang/blaze3d/buffers/Std140Builder;";

    @ModifyArg(method = "updateLightTexture", at = @At(value = "INVOKE", target = PUT, ordinal = 0), index = 0)
    private float aurora$sky(float v) {
        return Math.max(v, Modules.fullbright());
    }

    @ModifyArg(method = "updateLightTexture", at = @At(value = "INVOKE", target = PUT, ordinal = 1), index = 0)
    private float aurora$block(float v) {
        return Math.max(v, Modules.fullbright());
    }

    @ModifyArg(method = "updateLightTexture", at = @At(value = "INVOKE", target = PUT, ordinal = 3), index = 0)
    private float aurora$nightVision(float v) {
        if (gg.aurora.module.ClarityModules.NO_NIGHT_VISION.enabled) v = 0;
        return Math.max(v, Modules.fullbright());
    }

    @ModifyArg(method = "updateLightTexture", at = @At(value = "INVOKE", target = PUT, ordinal = 6), index = 0)
    private float aurora$brightness(float v) {
        float f = Modules.fullbright();
        return f > 0 ? Math.max(v, 1 + 2 * f) : v;
    }
}
