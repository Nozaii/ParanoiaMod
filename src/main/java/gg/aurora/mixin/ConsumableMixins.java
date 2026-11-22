package gg.aurora.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import gg.aurora.module.PvpOptimizers;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Consumable Optimizer : la consommation se termine côté client au tick exact, sans attendre le serveur. */
public final class ConsumableMixins {
    private ConsumableMixins() {}

    @Mixin(LivingEntity.class)
    public abstract static class Living {
        @Shadow
        protected int useItemRemaining;

        @Shadow
        protected abstract void completeUsingItem();

        @Inject(method = "updateUsingItem", at = @At("TAIL"))
        private void aurora$finishLocally(ItemStack stack, CallbackInfo ci) {
            if ((Object) this instanceof LocalPlayer p && useItemRemaining == 0 && p.isUsingItem() && PvpOptimizers.consumeLocally(stack)) {
                completeUsingItem();
                PvpOptimizers.onLocalConsume(p);
            }
        }
    }

    @Mixin(LocalPlayer.class)
    public abstract static class Local {
        /** Le serveur, en retard, renvoie « en train de manger » : on n'en relance pas un second. */
        @WrapOperation(method = "onSyncedDataUpdated", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/client/player/LocalPlayer;startUsingItem(Lnet/minecraft/world/InteractionHand;)V"))
        private void aurora$noRecall(LocalPlayer self, InteractionHand hand, Operation<Void> original) {
            if (PvpOptimizers.suppressRecall(self)) return;
            original.call(self, hand);
        }
    }
}
