package gg.aurora.mixin;

import gg.aurora.Settings;
import gg.aurora.ui.ParanoiaTitleScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Remplace l'écran titre vanilla par le menu Paranoia (désactivable dans les paramètres). */
@Mixin(Minecraft.class)
public class MinecraftScreenMixin {
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen aurora$title(Screen screen) {
        if (screen instanceof TitleScreen && Settings.customTitle) return new ParanoiaTitleScreen();
        return screen;
    }

    /** setScreen(null) hors partie crée lui-même un TitleScreen : on l'intercepte aussi. */
    @ModifyVariable(method = "setScreen", at = @At("STORE"), argsOnly = true, require = 0)
    private Screen aurora$titleStore(Screen screen) {
        return aurora$title(screen);
    }
}
