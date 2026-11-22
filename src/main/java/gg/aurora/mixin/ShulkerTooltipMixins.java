package gg.aurora.mixin;

import gg.aurora.module.ShulkerViewer;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.function.Consumer;

/** Shulker Viewer: grid in the tooltip instead of the "Contains" text list. */
public final class ShulkerTooltipMixins {
    private ShulkerTooltipMixins() {}

    @Mixin(ItemStack.class)
    public static class Image {
        @Inject(method = "getTooltipImage", at = @At("HEAD"), cancellable = true)
        private void aurora$shulker(CallbackInfoReturnable<Optional<TooltipComponent>> cir) {
            var img = ShulkerViewer.image((ItemStack) (Object) this);
            if (img.isPresent()) cir.setReturnValue(img);
        }
    }

    @Mixin(ItemContainerContents.class)
    public static class Lines {
        @Inject(method = "addToTooltip", at = @At("HEAD"), cancellable = true)
        private void aurora$hideLines(Item.TooltipContext ctx, Consumer<Component> out, TooltipFlag flag, DataComponentGetter getter, CallbackInfo ci) {
            // l'objet n'est pas transmis ici (seulement ses composants) : on le reconnaît à son modèle de shulker
            if (ShulkerViewer.hideTextFor(getter)) ci.cancel();
        }
    }
}
