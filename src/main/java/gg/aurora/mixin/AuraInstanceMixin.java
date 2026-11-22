package gg.aurora.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderPass;
import gg.aurora.render.HandGlow;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Hand Aura instanciée : notre type de rendu est dessiné en N instances (une par décalage du contour). */
@Mixin(RenderType.class)
public class AuraInstanceMixin {
    @WrapOperation(method = "draw", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;drawIndexed(IIII)V"))
    private void aurora$instances(RenderPass pass, int baseVertex, int firstIndex, int count, int instances, Operation<Void> original) {
        if (HandGlow.isInstanced((RenderType) (Object) this)) {
            try {
                pass.setUniform("AuraOffsets", HandGlow.offsets());
                original.call(pass, baseVertex, firstIndex, count, HandGlow.instances());
                return;
            } catch (RuntimeException e) {
                HandGlow.instancingFailed(e);
            }
        }
        original.call(pass, baseVertex, firstIndex, count, instances);
    }
}
