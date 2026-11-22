package gg.aurora.mixin;

import gg.aurora.module.ExtraModules;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Drop Protection : empêche de lâcher un objet précieux par erreur (sauf accroupi). */
@Mixin(LocalPlayer.class)
public class LocalPlayerDropMixin {
    @Inject(method = "drop", at = @At("HEAD"), cancellable = true)
    private void aurora$protect(boolean fullStack, CallbackInfoReturnable<Boolean> cir) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (ExtraModules.blockDrop(self, fullStack)) cir.setReturnValue(false);
    }
}
