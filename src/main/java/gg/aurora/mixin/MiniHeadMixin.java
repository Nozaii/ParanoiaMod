package gg.aurora.mixin;

import gg.aurora.cosmetic.MiniSkinPet;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tête agrandie des familiers « Mini skin » (modèle du skin et modèle d'armure : le casque suit). */
@Mixin(PlayerModel.class)
public class MiniHeadMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
    private void aurora$bigHead(AvatarRenderState state, CallbackInfo ci) {
        if (state.id >= MiniSkinPet.ID_BASE) return;
        float k = MiniSkinPet.headScale(state.id);
        if (k == 1f) return;
        var head = ((HumanoidModel<?>) (Object) this).head;
        head.xScale = head.yScale = head.zScale = k;
    }
}
