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

    /** Exact world projection of this frame (bobbing, zoom, nausea): waypoint and ping labels use it to stay put. */
    @org.spongepowered.asm.mixin.injection.ModifyArg(method = "renderLevel", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/PerspectiveProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"))
    private org.joml.Matrix4f aurora$captureProjection(org.joml.Matrix4f m) {
        gg.aurora.waypoint.Projector.capture(m);
        return m;
    }

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void aurora$noHurtCam(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        if (Modules.NO_HURT_CAM.enabled) ci.cancel();
    }
}
