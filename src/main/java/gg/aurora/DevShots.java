package gg.aurora;

import gg.aurora.module.Category;
import gg.aurora.module.HudModule;
import gg.aurora.module.Modules;
import gg.aurora.ui.AuroraScreen;
import gg.aurora.ui.ParanoiaTitleScreen;
import gg.aurora.ui.HudEditScreen;
import gg.aurora.ui.SettingsScreen;
import gg.aurora.ui.WardrobeScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * Outil de développement : avec -Daurora.devshot=true, ouvre le menu, parcourt les écrans
 * et enregistre des captures dans screenshots/. Inactif en jeu normal.
 */
final class DevShots {
    private DevShots() {}

    static final boolean ENABLED = Boolean.getBoolean("aurora.devshot");
    private static int tick = -1, walkShots;
    private static ParanoiaTitleScreen title;
    private static AuroraScreen screen;
    private static SettingsScreen settings;
    private static WardrobeScreen wardrobe;
    private static HudEditScreen edit;

    static void tick(Minecraft mc) {
        if (!ENABLED) return;
        if (tick < 0) {
            if (mc.getOverlay() != null || !(mc.screen instanceof ParanoiaTitleScreen t)) return;
            title = t;
            tick = 0;
        }
        tick++;
        if (tick > 42 && walkShots < 2 && title.walkProgress() > (walkShots == 0 ? 0.3f : 0.6f)) {
            shot(mc, walkShots == 0 ? "t_walk1" : "t_walk2");
            walkShots++;
        }
        switch (tick) {
            case 40 -> shot(mc, "t_splash");
            case 42 -> title.debugEnter();
            case 47 -> shot(mc, "t_enter_mid");
            case 110 -> title.debugMouse(0.85f, 0.15f);
            case 130 -> shot(mc, "t_menu");
            case 132 -> title.debugMouse(0.5f, 0.56f);
            case 150 -> shot(mc, "t_menu_hover");
            case 152 -> mc.stop();
            default -> {}
        }
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), 1, msg -> {});
    }
}
