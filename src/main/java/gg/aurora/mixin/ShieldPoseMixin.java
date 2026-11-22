package gg.aurora.mixin;

import gg.aurora.module.ShieldModules;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Shield Fix (3e personne) : bras en garde seulement quand le bouclier bloque vraiment. */
@Mixin(AvatarRenderer.class)
public class ShieldPoseMixin {
    @Redirect(method = "getArmPose(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/client/model/HumanoidModel$ArmPose;",
            require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;getUseAnimation()Lnet/minecraft/world/item/ItemUseAnimation;"))
    private static ItemUseAnimation aurora$shield(ItemStack stack, Avatar avatar, ItemStack s2, InteractionHand hand) {
        ItemUseAnimation anim = stack.getUseAnimation();
        return anim == ItemUseAnimation.BLOCK && !ShieldModules.showRaised(avatar) ? ItemUseAnimation.NONE : anim;
    }
}
