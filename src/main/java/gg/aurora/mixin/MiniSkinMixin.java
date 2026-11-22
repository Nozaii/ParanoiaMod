package gg.aurora.mixin;

import gg.aurora.cosmetic.MiniSkinPet;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.state.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Familiers « Mini skin » : ajoutés aux entités à dessiner, sans exister dans le monde. */
@Mixin(LevelRenderer.class)
public class MiniSkinMixin {
    @Shadow
    @Final
    private EntityRenderDispatcher entityRenderDispatcher;

    @Inject(method = "extractVisibleEntities", at = @At("TAIL"))
    private void aurora$minis(Camera camera, Frustum frustum, DeltaTracker delta, LevelRenderState state, CallbackInfo ci) {
        MiniSkinPet.extract(state, entityRenderDispatcher, camera.position(), delta.getGameTimeDeltaPartialTick(false));
    }
}
