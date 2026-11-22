package gg.aurora.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import gg.aurora.module.WorldModules;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Optimizer +++ : ne dessine plus les entités lointaines ni les porte-armures décoratifs. */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {
    // chaque entité, chaque image : ModifyReturnValue, sans allocation
    @ModifyReturnValue(method = "shouldRender", at = @At("RETURN"))
    private <E extends Entity> boolean aurora$cull(boolean visible, E entity, Frustum frustum, double camX, double camY, double camZ) {
        if (visible && (!WorldModules.allowEntity(entity, camX, camY, camZ) || WorldModules.occluded(entity))) return false;
        return visible;
    }
}
