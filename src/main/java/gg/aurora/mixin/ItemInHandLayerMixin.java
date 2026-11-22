package gg.aurora.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import gg.aurora.module.ShieldModules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shield Status en 3e personne : couleur du bouclier des joueurs (le tien et ceux des autres). */
@Mixin(ItemInHandLayer.class)
public class ItemInHandLayerMixin {
    @Inject(method = "submitArmWithItem", at = @At("HEAD"))
    private void aurora$tintHead(ArmedEntityRenderState state, ItemStackRenderState item, ItemStack stack, HumanoidArm arm, PoseStack pose,
                                 SubmitNodeCollector collector, int light, CallbackInfo ci) {
        ShieldModules.renderTint = -1;
        ShieldModules.renderDesign = null;
        var level = Minecraft.getInstance().level;
        if (state instanceof AvatarRenderState av && level != null && level.getEntity(av.id) instanceof LivingEntity le) {
            ShieldModules.renderTint = ShieldModules.tintFor(le, stack);
            ShieldModules.renderDesign = ShieldModules.designFor(le, stack);
        }
    }

    @Inject(method = "submitArmWithItem", at = @At("TAIL"))
    private void aurora$tintTail(ArmedEntityRenderState state, ItemStackRenderState item, ItemStack stack, HumanoidArm arm, PoseStack pose,
                                 SubmitNodeCollector collector, int light, CallbackInfo ci) {
        ShieldModules.renderTint = -1;
        ShieldModules.renderDesign = null;
    }
}
