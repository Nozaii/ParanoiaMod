package gg.aurora.mixin;

import com.mojang.blaze3d.platform.Window;
import gg.aurora.Borderless;
import gg.aurora.Settings;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** F11 et la case Plein écran basculent le mode sans bordure au lieu du plein écran exclusif (voir Borderless). */
@Mixin(Window.class)
public class BorderlessMixin {
    @Inject(method = "toggleFullScreen", at = @At("HEAD"), cancellable = true)
    private void aurora$borderless(CallbackInfo ci) {
        if (!Settings.borderless || Borderless.vanilla) return;
        // déjà en exclusif (lancé ainsi) : vanilla en sort normalement
        if (((Window) (Object) this).isFullscreen()) return;
        Borderless.toggle(Minecraft.getInstance());
        ci.cancel();
    }
}
