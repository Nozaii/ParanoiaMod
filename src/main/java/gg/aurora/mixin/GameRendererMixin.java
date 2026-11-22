package gg.aurora.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import gg.aurora.module.Modules;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void aurora$zoom(Camera camera, float partialTick, boolean useFovSetting, CallbackInfoReturnable<Float> cir) {
        if (useFovSetting) cir.setReturnValue(gg.aurora.module.VisualModules.fov(cir.getReturnValueF()));
        // le zoom ne touche que la vue du monde (useFovSetting), pas la main
        if (!useFovSetting) return;
        float factor = Modules.zoomFactor();
        if (factor != 1f) cir.setReturnValue(cir.getReturnValueF() * factor);
    }

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void aurora$noHurtCam(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        if (Modules.NO_HURT_CAM.enabled) ci.cancel();
    }
}
