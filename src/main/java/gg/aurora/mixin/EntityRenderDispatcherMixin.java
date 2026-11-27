package gg.aurora.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import com.mojang.blaze3d.vertex.PoseStack;
import gg.aurora.module.WorldModules;
import gg.aurora.render.FriendGlow;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optimizer +++ (entités lointaines, porte-armures) et Friend Glow (silhouette soumise avant l'entité). */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {
    // chaque entité, chaque image : ModifyReturnValue, sans allocation
    @ModifyReturnValue(method = "shouldRender", at = @At("RETURN"))
    private <E extends Entity> boolean aurora$cull(boolean visible, E entity, Frustum frustum, double camX, double camY, double camZ) {
        if (visible && (!WorldModules.allowEntity(entity, camX, camY, camZ) || WorldModules.occluded(entity))) return false;
        return visible;
    }

    @ModifyReturnValue(method = "extractEntity", at = @At("RETURN"))
    private <E extends Entity> EntityRenderState aurora$markGlow(EntityRenderState state, E entity, float partialTick) {
        FriendGlow.mark(entity, state);
        return state;
    }

    @Inject(method = "submit", at = @At("HEAD"))
    private <S extends EntityRenderState> void aurora$friendGlow(S state, CameraRenderState cam, double x, double y, double z,
                                                                PoseStack pose, SubmitNodeCollector collector, CallbackInfo ci) {
        FriendGlow.submit((net.minecraft.client.renderer.entity.EntityRenderDispatcher) (Object) this, state, cam, x, y, z, pose, collector);
    }
}
