package gg.aurora.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import gg.aurora.module.PvpOptimizers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.StuckInBodyLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Projectile Optimizer : ne dessine plus les flèches et dards plantés dans les joueurs. */
@Mixin(StuckInBodyLayer.class)
public class StuckInBodyLayerMixin {
    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
            at = @At("HEAD"), cancellable = true)
    private void aurora$hide(PoseStack pose, SubmitNodeCollector c, int light, AvatarRenderState state, float a, float b, CallbackInfo ci) {
        if (PvpOptimizers.hideStuckArrows()) ci.cancel();
    }
}
