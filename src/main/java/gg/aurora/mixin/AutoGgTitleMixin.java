package gg.aurora.mixin;

import gg.aurora.module.AutoGG;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Auto GG : les victoires annoncées en titre (« VICTORY! ») comptent aussi. */
@Mixin(Gui.class)
public class AutoGgTitleMixin {
    @Inject(method = "setTitle", at = @At("HEAD"))
    private void aurora$title(Component title, CallbackInfo ci) {
        AutoGG.onServerText(title);
    }

    @Inject(method = "setSubtitle", at = @At("HEAD"))
    private void aurora$subtitle(Component title, CallbackInfo ci) {
        AutoGG.onServerText(title);
    }
}
