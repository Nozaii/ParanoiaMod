package gg.aurora.mixin;

import gg.aurora.module.CombatModules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adaptive Detail : les blocs-entités lointains (coffres, panneaux, bannières…) ne sont plus dessinés. */
@Mixin(BlockEntityRenderDispatcher.class)
public class BlockEntityDetailMixin {
    @Inject(method = "tryExtractRenderState", at = @At("HEAD"), cancellable = true)
    private void aurora$detail(BlockEntity be, float pt, ModelFeatureRenderer.CrumblingOverlay crumbling, CallbackInfoReturnable<Object> cir) {
        if (gg.aurora.module.WorldModules.occluded(be)) {
            cir.setReturnValue(null);
            return;
        }
        double opt = gg.aurora.module.WorldModules.blockEntityDistance();
        if (!CombatModules.DETAIL.enabled && opt < 0) return;
        var cam = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        var p = be.getBlockPos();
        double dx = p.getX() + 0.5 - cam.x, dy = p.getY() + 0.5 - cam.y, dz = p.getZ() + 0.5 - cam.z;
        double d2 = dx * dx + dy * dy + dz * dz;
        // Optimizer +++ : coffres, panneaux… au-delà de la distance choisie
        if (opt >= 0 && d2 > opt * opt || CombatModules.DETAIL.enabled && !CombatModules.allowDetail(d2)) cir.setReturnValue(null);
    }
}
