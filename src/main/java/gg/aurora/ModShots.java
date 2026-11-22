package gg.aurora;

import gg.aurora.ui.ClickGuiScreen;
import gg.aurora.ui.ModsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;

/**
 * Outil de développement : avec -Daurora.modshot=true et un monde ouvert (--quickPlaySingleplayer),
 * montre le menu RShift (sans page Mods), le menu Échap avec le bouton Mods, puis l'écran Mods ouvert
 * depuis ce bouton (Installed, Store, dialog), et enregistre des captures dans screenshots/ (mods_*.png).
 * Inactif en jeu normal.
 */
final class ModShots {
    private ModShots() {}

    static final boolean ENABLED = Boolean.getBoolean("aurora.modshot");
    private static int tick = -1;
    private static ModsScreen mods;

    static void tick(Minecraft mc) {
        if (!ENABLED) return;
        if (tick < 0) {
            if (mc.player == null || mc.level == null || mc.screen != null) return;
            tick = 0;
        }
        tick++;
        mc.getFramerateLimitTracker().onInputReceived();
        switch (tick) {
            case 40 -> {
                ClickGuiScreen gui = new ClickGuiScreen();
                gui.debugTab(1);
                mc.setScreen(gui);
            }
            case 80 -> shot(mc, "mods_rshift");
            case 82 -> mc.setScreen(new PauseScreen(true));
            case 110 -> shot(mc, "mods_pause");
            case 112 -> {
                mods = new ModsScreen(mc.screen);
                mc.setScreen(mods);
            }
            case 170 -> shot(mc, "mods_installed");
            case 171 -> mods.debugMenu();
            case 180 -> shot(mc, "mods_menu");
            case 181 -> mods.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(0, 0, new net.minecraft.client.input.MouseButtonInfo(0, 0)), false);
            case 182 -> mods.debugPage(1, 0);
            case 250 -> shot(mc, "mods_store");
            case 252 -> {
                mods.debugPage(0, 0);
                mods.debugDialog();
            }
            case 280 -> shot(mc, "mods_dialog");
            case 282 -> {
                mc.setScreen(mods = new ModsScreen(new PauseScreen(true)));
                mods.debugPage(4, 0);
            }
            case 320 -> shot(mc, "mods_library");
            case 322 -> gg.aurora.ui.ModConfigs.open("sodium", mods);
            case 350 -> shot(mc, "mods_settings_opened");
            case 354 -> mc.stop();
            default -> {}
        }
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), 1, msg -> {});
    }
}
