package gg.aurora.mixin;

import gg.aurora.nametag.NameStyler;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Liste Tab : même dégradé et même logo que les nametags. */
@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin {
    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
    private void aurora$tabName(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
        cir.setReturnValue(NameStyler.apply(cir.getReturnValue(), info.getProfile().id(), info.getProfile().name()));
    }
}
