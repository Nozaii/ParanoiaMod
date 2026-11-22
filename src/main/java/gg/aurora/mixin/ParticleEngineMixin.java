package gg.aurora.mixin;

import gg.aurora.module.WorldModules;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optimizer +++ : supprime ou réduit les particules. */
@Mixin(ParticleEngine.class)
public class ParticleEngineMixin {
    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void aurora$particles(Particle particle, CallbackInfo ci) {
        if (!WorldModules.allowParticle(particle) || WorldModules.offscreenParticle(particle)) ci.cancel();
    }
}
