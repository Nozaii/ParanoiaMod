package gg.aurora.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import gg.aurora.nametag.NameStyler;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Nametag au-dessus des joueurs : dégradé animé + logo. Côté client uniquement. */
@Mixin(Player.class)
public class PlayerMixin {
    @ModifyReturnValue(method = "getDisplayName", at = @At("RETURN"))
    private Component aurora$nametag(Component original) {
        Player self = (Player) (Object) this;
        if (!self.level().isClientSide()) return original;
        return NameStyler.apply(original, self.getUUID(), self.getGameProfile().name());
    }
}
