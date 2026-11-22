package gg.aurora.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import gg.aurora.module.WorldModules;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.SkyRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Custom Sky : couleur du ciel, étoiles toujours visibles et ambiances en couches (panorama de fond,
 * couche lumineuse additive animée, planète).
 */
@Mixin(SkyRenderer.class)
public class SkyRendererMixin {

    /** Ambiance : ciel procédural (shader) dessiné juste après le ciel vanilla. */
    @Inject(method = "renderSkyDisc", at = @At("TAIL"))
    private void aurora$ambience(int color, CallbackInfo ci) {
        int mode = WorldModules.ambience();
        if (mode <= 0 || mode > 8 || aurora$skyFailed) return;
        long skyT = gg.aurora.Prof.start();
        var mc = net.minecraft.client.Minecraft.getInstance();
        var buffers = mc.renderBuffers().bufferSource();
        org.joml.Matrix4fStack mv = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
        mv.pushMatrix();
        mv.identity();
        mv.rotate(new org.joml.Quaternionf(mc.gameRenderer.getMainCamera().rotation()).conjugate());
        float alpha = WorldModules.ambienceAlpha();
        boolean anim = gg.aurora.Settings.anim();
        // temps en secondes (modulo une heure pour garder la précision des flottants), figé sans animations
        float secs = anim ? (float) ((System.currentTimeMillis() % 3_600_000L) / 1000.0) : 120f;
        try {
            if (mode == 8) {
                RenderType user = gg.aurora.render.SkyFx.userType(mc);
                if (user != null) {
                    gg.aurora.render.SkyFx.sphereTex(buffers.getBuffer(user), 10f, Math.round(255 * alpha));
                    buffers.endBatch(user);
                }
            } else if (gg.aurora.render.SkyFx.useTexture()) {
                // pack de shaders : ciel précalculé en texture, dessiné comme un « ciel texturé »
                RenderType type = gg.aurora.render.SkyFx.texType();
                gg.aurora.render.SkyFx.sphereTex(buffers.getBuffer(type), 10f, Math.round(255 * alpha));
                buffers.endBatch(type);
            } else {
                RenderType type = gg.aurora.render.SkyFx.type();
                gg.aurora.render.SkyFx.sphere(buffers.getBuffer(type), 10f, secs, mode, Math.round(255 * alpha));
                buffers.endBatch(type);
            }
        } catch (RuntimeException e) {
            // moteur de rendu incompatible (shader refusé) : ciel vanilla plutôt qu'un crash
            aurora$skyFailed = true;
            gg.aurora.AuroraClient.LOGGER.error("Ciel personnalisé désactivé : shader refusé par le moteur de rendu", e);
        } finally {
            mv.popMatrix();
            gg.aurora.Prof.stop(gg.aurora.Prof.SKY, skyT);
        }
    }

    @Unique
    private static boolean aurora$skyFailed;

    @Unique
    private static void aurora$vertex(VertexConsumer vc, float x, float y, float z, float u, float v, int a) {
        vc.addVertex(x, y, z).setColor(255, 255, 255, a).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(0, 1, 0);
    }

    /** Ambiance active : pas de soleil, de lune ni d'étoiles vanilla par-dessus le panorama. */
    @Inject(method = "renderSunMoonAndStars", at = @At("HEAD"), cancellable = true)
    private void aurora$hideCelestial(CallbackInfo ci) {
        if (WorldModules.ambience() > 0) ci.cancel();
    }

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void aurora$sky(ClientLevel level, float partialTick, Camera camera, SkyRenderState state, CallbackInfo ci) {
        state.skyColor = WorldModules.skyColor(state.skyColor);
        if (WorldModules.starsAlways()) state.starBrightness = Math.max(state.starBrightness, 0.8f);
        // ciels avec leurs propres étoiles : pas d'étoiles vanilla
        if (WorldModules.ambience() >= 1) state.starBrightness = 0;
    }
}
