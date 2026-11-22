package gg.aurora.mixin;

import gg.aurora.module.ClientModules;
import gg.aurora.module.ExtraModules;
import net.minecraft.client.Minecraft;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Totem Pop Counter : l'événement 35 signale l'utilisation d'un totem. */
@Mixin(LivingEntity.class)
public class LivingEntityEventMixin {
    @Inject(method = "handleEntityEvent", at = @At("HEAD"))
    private void aurora$totem(byte id, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (id == 35 && self.level().isClientSide()) ExtraModules.onTotemPop(self);
        if (id == 30 && self.level().isClientSide()) gg.aurora.module.ShieldModules.onShieldBroken(self);
        if (id == 3 && self.level().isClientSide()) gg.aurora.PlayerStats.onDeath(self);
    }

    /** Sons de chute Paranoia : dégâts de chute reçus par le joueur local. */
    @Inject(method = "handleDamageEvent", at = @At("HEAD"))
    private void aurora$fall(DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self == Minecraft.getInstance().player && source.is(DamageTypes.FALL)) ClientModules.onFall();
    }
}
