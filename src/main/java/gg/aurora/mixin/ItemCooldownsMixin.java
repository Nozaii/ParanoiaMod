package gg.aurora.mixin;

import gg.aurora.module.ShieldModules;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemCooldowns;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shield Status : mémorise la durée réelle des cooldowns (bouclier désactivé par une hache…). */
@Mixin(ItemCooldowns.class)
public class ItemCooldownsMixin {
    @Inject(method = "addCooldown(Lnet/minecraft/resources/Identifier;I)V", at = @At("HEAD"))
    private void aurora$cooldown(Identifier group, int ticks, CallbackInfo ci) {
        ShieldModules.onCooldown(group, ticks);
    }
}
