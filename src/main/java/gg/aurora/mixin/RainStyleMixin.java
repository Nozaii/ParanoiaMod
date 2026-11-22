package gg.aurora.mixin;

import gg.aurora.module.VisualModules;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Pluie stylisée (sol miroir) : gouttes fines assorties aux flaques, sans éclaboussures vanilla. */
@Mixin(WeatherEffectRenderer.class)
public class RainStyleMixin {
    private static final Identifier AURORA_RAIN = Identifier.fromNamespaceAndPath("aurora", "textures/environment/rain_fine.png");

    private static boolean aurora$styled() {
        var m = VisualModules.MIRROR;
        return m.enabled && m.flag("puddles", true) && m.flag("rainStyle", true);
    }

    @ModifyArg(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/rendertype/RenderTypes;weather(Lnet/minecraft/resources/Identifier;Z)Lnet/minecraft/client/renderer/rendertype/RenderType;"),
            index = 0)
    private Identifier aurora$rainTexture(Identifier id) {
        return aurora$styled() && id.getPath().endsWith("rain.png") ? AURORA_RAIN : id;
    }

    @Redirect(method = "tickRainParticles", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
    private void aurora$splash(ClientLevel level, ParticleOptions p, double x, double y, double z, double dx, double dy, double dz) {
        if (p == ParticleTypes.RAIN && aurora$styled()) return; // les ondes des flaques remplacent les éclaboussures
        level.addParticle(p, x, y, z, dx, dy, dz);
    }
}
