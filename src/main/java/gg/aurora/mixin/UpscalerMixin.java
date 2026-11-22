package gg.aurora.mixin;

import gg.aurora.render.Upscaler;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Encadre le rendu du monde pour l'upscaling FSR. */
@Mixin(GameRenderer.class)
public class UpscalerMixin {
    private static final String RENDER_LEVEL = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V";

    @Inject(method = "render", at = @At(value = "INVOKE", target = RENDER_LEVEL))
    private void aurora$fsrBegin(DeltaTracker delta, boolean advance, CallbackInfo ci) {
        gg.aurora.render.LagWatch.frame();
        gg.aurora.ui.WorldPanel.renderOffscreen();
        int skyMode = gg.aurora.module.WorldModules.ambience();
        if (skyMode > 0 && skyMode < 8) gg.aurora.render.SkyFx.prepare(Minecraft.getInstance(), skyMode,
                gg.aurora.Settings.anim() ? (float) ((System.currentTimeMillis() % 3_600_000L) / 1000.0) : 120f);
        Upscaler.begin(Minecraft.getInstance());
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = RENDER_LEVEL, shift = At.Shift.AFTER))
    private void aurora$fsrEnd(DeltaTracker delta, boolean advance, CallbackInfo ci) {
        long fxT = gg.aurora.Prof.start();
        Upscaler.end(Minecraft.getInstance());
        gg.aurora.render.Filters.apply(Minecraft.getInstance());
        gg.aurora.Prof.stop(gg.aurora.Prof.POSTFX, fxT);
    }

    private static final String GUI_RENDER = "Lnet/minecraft/client/gui/render/GuiRenderer;render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V";

    /** Menu 3D : image du monde juste avant l'interface. */
    @Inject(method = "render", at = @At(value = "INVOKE", target = GUI_RENDER))
    private void aurora$beforeGui(DeltaTracker delta, boolean renderLevel, CallbackInfo ci) {
        gg.aurora.render.Panel3D.beforeGui();
    }

    /** Menu 3D : reprojection du panneau juste après l'interface. */
    @Inject(method = "render", at = @At(value = "INVOKE", target = GUI_RENDER, shift = At.Shift.AFTER))
    private void aurora$afterGui(DeltaTracker delta, boolean renderLevel, CallbackInfo ci) {
        gg.aurora.render.Panel3D.afterGui();
    }

    /** Sol miroir : avant le rendu de la main (la profondeur du monde est encore dans le tampon). */
    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"))
    private void aurora$ssr(DeltaTracker delta, CallbackInfo ci) {
        gg.aurora.DevWorld.frame();
        gg.aurora.Bench.frame();
        gg.aurora.render.FrameStats.frame();
        gg.aurora.render.Occlusion.newFrame();
        aurora$screenFx();
    }

    @org.spongepowered.asm.mixin.Unique
    private static void aurora$screenFx() {
        long ssrT = gg.aurora.Prof.start();
        gg.aurora.render.PostFx.ssr(Minecraft.getInstance());
        gg.aurora.Prof.stop(gg.aurora.Prof.SSR, ssrT);
        gg.aurora.render.PostFx.shockwaves(Minecraft.getInstance());
        long blurT = gg.aurora.Prof.start();
        gg.aurora.render.PostFx.motionBlur(Minecraft.getInstance());
        gg.aurora.Prof.stop(gg.aurora.Prof.BLUR, blurT);
    }
}
