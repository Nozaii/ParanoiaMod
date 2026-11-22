package gg.aurora.mixin;

import gg.aurora.module.WorldModules;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Block Outline : couleur, opacité et épaisseur du contour du bloc visé. */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    private static final String SHAPE = "Lnet/minecraft/client/renderer/ShapeRenderer;renderShape(Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/phys/shapes/VoxelShape;DDDIF)V";

    @ModifyArg(method = "renderHitOutline", at = @At(value = "INVOKE", target = SHAPE), index = 6)
    private int aurora$outlineColor(int color) {
        return WorldModules.outlineColor(color);
    }

    @ModifyArg(method = "renderHitOutline", at = @At(value = "INVOKE", target = SHAPE), index = 7)
    private float aurora$outlineWidth(float width) {
        return WorldModules.outlineWidth(width);
    }
}
