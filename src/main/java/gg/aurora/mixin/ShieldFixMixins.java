package gg.aurora.mixin;

import gg.aurora.module.ShieldModules;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Shield Fix (1re personne) : pose « bloque » seulement quand le bouclier bloque vraiment. */
@Mixin(ItemInHandRenderer.class)
public class ShieldFixMixins {
    @Redirect(method = "renderArmWithItem", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;getUseAnimation()Lnet/minecraft/world/item/ItemUseAnimation;"))
    private ItemUseAnimation aurora$shield(ItemStack stack, AbstractClientPlayer player) {
        ItemUseAnimation anim = stack.getUseAnimation();
        return anim == ItemUseAnimation.BLOCK && !ShieldModules.showRaised(player) ? ItemUseAnimation.NONE : anim;
    }
}
