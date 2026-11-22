package gg.aurora.mixin;

import gg.aurora.module.WorldModules;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Custom Sky : le brouillard qui cache les chunks non chargés prend la couleur de l'horizon du ciel
 * choisi. Sinon le terrain lointain se fond dans le bleu vanilla et une bande nette apparaît.
 */
@Mixin(FogRenderer.class)
public class SkyFogMixin {
    /** Couleur d'horizon de chaque ambiance (même valeurs que sky_fx.fsh, près de l'horizon). */
    private static final float[][] AURORA$HORIZON = {
            {0.20f, 0.10f, 0.38f}, {0.55f, 0.12f, 0.02f}, {0.20f, 0.06f, 0.36f}, {0.16f, 0.17f, 0.20f},
            {0.15f, 0.06f, 0.25f}, {0.22f, 0.29f, 0.31f}, {0.11f, 0.12f, 0.15f}};

    @Inject(method = "computeFogColor", at = @At("RETURN"))
    private void aurora$skyFog(Camera camera, float pt, ClientLevel level, int renderDistance, float darken,
                               CallbackInfoReturnable<Vector4f> cir) {
        if (!WorldModules.CUSTOM_SKY.enabled || camera.getFluidInCamera() != FogType.NONE) return;
        var e = camera.entity();
        if (e instanceof net.minecraft.world.entity.LivingEntity le && (le.hasEffect(MobEffects.BLINDNESS) || le.hasEffect(MobEffects.DARKNESS))) return;
        Vector4f c = cir.getReturnValue();
        int mode = WorldModules.ambience();
        if (mode >= 1 && mode <= AURORA$HORIZON.length) {
            float a = WorldModules.ambienceAlpha();
            float[] h = AURORA$HORIZON[mode - 1];
            c.set(c.x + (h[0] - c.x) * a, c.y + (h[1] - c.y) * a, c.z + (h[2] - c.z) * a, c.w);
            return;
        }
        int packed = (Math.round(c.x * 255) << 16) | (Math.round(c.y * 255) << 8) | Math.round(c.z * 255);
        int t = WorldModules.skyColor(0xFF000000 | packed);
        if ((t & 0xFFFFFF) != packed) c.set((t >> 16 & 255) / 255f, (t >> 8 & 255) / 255f, (t & 255) / 255f, c.w);
    }
}
