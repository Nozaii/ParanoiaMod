package gg.aurora.mixin;

import gg.aurora.module.WorldModules;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.WaterFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clear Water : repousse le brouillard sous l'eau jusqu'à la distance de rendu. */
@Mixin(WaterFogEnvironment.class)
public class WaterFogEnvironmentMixin {
    @Inject(method = "setupFog", at = @At("TAIL"))
    private void aurora$clear(FogData data, Camera camera, ClientLevel level, float renderDistance, DeltaTracker delta, CallbackInfo ci) {
        float k = WorldModules.waterClarity();
        if (k <= 0) return;
        // data.renderDistance* are only filled after this call: use the argument
        data.environmentalStart += (renderDistance * 0.9f - data.environmentalStart) * k;
        data.environmentalEnd += (renderDistance - data.environmentalEnd) * k;
        data.skyEnd += (renderDistance - data.skyEnd) * k;
        data.cloudEnd += (renderDistance - data.cloudEnd) * k;
    }
}
