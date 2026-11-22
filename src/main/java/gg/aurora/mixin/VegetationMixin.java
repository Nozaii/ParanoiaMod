package gg.aurora.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import gg.aurora.render.Vegetation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Set;

/**
 * Végétation masquée : le bloc n'a plus de modèle dans les chunks (vanilla et Sodium lisent cette forme).
 * Appelé des millions de fois pendant la construction des chunks : ModifyReturnValue n'alloue rien,
 * et rien n'est fait quand toute la végétation est visible (cas normal).
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class VegetationMixin {
    @Shadow
    public abstract Block getBlock();

    @ModifyReturnValue(method = "getRenderShape", at = @At("RETURN"))
    private RenderShape aurora$hideVegetation(RenderShape shape) {
        Set<Block> h = Vegetation.hidden;
        if (h == null || h.isEmpty() || shape == RenderShape.INVISIBLE) return shape;
        return h.contains(getBlock()) ? RenderShape.INVISIBLE : shape;
    }
}
