package gg.aurora.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import gg.aurora.module.WorldModules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clear Water : retire le filtre bleuté plaqué sur l'écran sous l'eau. */
@Mixin(ScreenEffectRenderer.class)
public class ScreenEffectRendererMixin {
    /** Low Fire : flammes abaissées (ou masquées) en vue à la 1re personne. */
    @Inject(method = "renderFire", at = @At("HEAD"), cancellable = true)
    private static void aurora$fireHead(PoseStack pose, MultiBufferSource buffers, net.minecraft.client.renderer.texture.TextureAtlasSprite sprite, CallbackInfo ci) {
        if (!gg.aurora.module.ExtraModules.LOW_FIRE.enabled) return;
        if (gg.aurora.module.ExtraModules.LOW_FIRE.opt("mode", 0) == 1) {
            ci.cancel();
            return;
        }
        pose.pushPose();
        pose.translate(0, -0.35, 0);
    }

    @Inject(method = "renderFire", at = @At("TAIL"))
    private static void aurora$fireTail(PoseStack pose, MultiBufferSource buffers, net.minecraft.client.renderer.texture.TextureAtlasSprite sprite, CallbackInfo ci) {
        if (gg.aurora.module.ExtraModules.LOW_FIRE.enabled && gg.aurora.module.ExtraModules.LOW_FIRE.opt("mode", 0) != 1) pose.popPose();
    }

    /** No Totem Animation : pas de totem géant à l'écran. */
    @Inject(method = "displayItemActivation", at = @At("HEAD"), cancellable = true)
    private void aurora$totem(net.minecraft.world.item.ItemStack stack, net.minecraft.util.RandomSource random, CallbackInfo ci) {
        if (!stack.is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)) return;
        if (gg.aurora.module.ExtraModules.NO_TOTEM_ANIM.enabled || gg.aurora.module.ClientModules.onTotem()) ci.cancel();
    }

    /** Animation du totem : taille réglable en mode vanilla. */
    @Inject(method = "renderItemActivationAnimation", at = @At("HEAD"))
    private void aurora$totemScaleHead(PoseStack pose, float pt, net.minecraft.client.renderer.SubmitNodeCollector collector, CallbackInfo ci) {
        pose.pushPose();
        var m = gg.aurora.module.ClientModules.TOTEM;
        if (m.enabled && m.opt("mode", 1) == 0) {
            float s = (float) m.opt("scale", 0.6);
            pose.scale(s, s, s);
        }
    }

    @Inject(method = "renderItemActivationAnimation", at = @At("TAIL"))
    private void aurora$totemScaleTail(PoseStack pose, float pt, net.minecraft.client.renderer.SubmitNodeCollector collector, CallbackInfo ci) {
        pose.popPose();
    }

    @Inject(method = "renderWater", at = @At("HEAD"), cancellable = true)
    private static void aurora$noWaterOverlay(Minecraft mc, PoseStack pose, MultiBufferSource buffers, CallbackInfo ci) {
        if (WorldModules.hideWaterOverlay()) ci.cancel();
    }
}
