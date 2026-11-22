package gg.aurora.ui;

import gg.aurora.Settings;
import net.minecraft.client.gui.GuiGraphics;

/** Style commun des widgets du HUD (fond, texte, icônes) selon les paramètres. */
public final class HudKit {
    private HudKit() {}

    /** Widget en cours de rendu (pour ses réglages propres). */
    public static gg.aurora.module.HudModule current;

    public static boolean minimal() {
        return Settings.hudStyle == Settings.HudStyle.MINIMAL || (current != null && !current.showBg);
    }

    /** Style épuré : fond sombre compact, fine barre d'accent, libellés discrets en majuscules. */
    public static boolean clean() {
        return Settings.hudStyle == Settings.HudStyle.CLEAN;
    }

    public static void background(GuiGraphics g, float x, float y, float w, float h, float r) {
        if (current != null && !current.showBg) return;
        float op = Settings.hudOpacity;
        switch (Settings.hudStyle) {
            case GLASS -> {
                if (Settings.hudShadow) Draw.glow(g, x, y + 2, w, h, 8, Draw.withAlpha(0x000000, 0.45f * op));
                Draw.rrect(g, x, y, w, h, r, Draw.withAlpha(Theme.PANEL, op));
                Draw.ring(g, x, y, w, h, r, Draw.withAlpha(0xFFFFFF, 0.10f + 0.08f * op));
            }
            case SOLID -> {
                if (Settings.hudShadow) Draw.glow(g, x, y + 2, w, h, 8, Draw.withAlpha(0x000000, 0.55f));
                Draw.rrect(g, x, y, w, h, r, Draw.withAlpha(Draw.lerpColor(Theme.PANEL, Theme.accent(), 0.12f), Math.min(1f, op + 0.25f)));
                Draw.ring(g, x, y, w, h, r, Draw.withAlpha(0xFFFFFF, 0.08f));
            }
            case MINIMAL -> {
            }
            case CLEAN -> {
                if (Settings.hudShadow) Draw.glow(g, x, y + 1.5f, w, h, 6, Draw.withAlpha(0x000000, 0.32f * op));
                Draw.rrect(g, x, y, w, h, Math.min(r, 3.5f), Draw.withAlpha(0x09080F, 0.35f + 0.45f * op));
                Draw.rrect(g, x, y, w, 0.6f, 0.3f, Draw.withAlpha(0xFFFFFF, 0.05f));
            }
        }
    }

    public static void text(GuiGraphics g, String s, float x, float y, float scale, int argb) {
        if (minimal() && Settings.hudShadow) Draw.text(g, s, x + 0.6f, y + 0.6f, scale, Draw.withAlpha(0x000000, 0.7f * (argb >>> 24) / 255f));
        Draw.text(g, s, x, y, scale, argb);
    }

    public static int iconColor() {
        return 0xFF000000 | (Settings.hudAccentIcons ? Theme.accent() : Theme.TEXT);
    }

    public static int textColor() {
        if (current != null && current.colorMode == 1) return 0xFF000000 | Theme.accent();
        return 0xFF000000 | Theme.TEXT;
    }

    /** Texte principal d'un widget : blanc, accent ou dégradé animé selon le widget. */
    public static void value(GuiGraphics g, String s, float x, float y, float scale) {
        if (current != null && current.colorMode == 2) {
            var style = gg.aurora.nametag.NametagStyle.own();
            var comp = gg.aurora.nametag.NameStyler.gradient(s, style,
                    net.minecraft.network.chat.Style.EMPTY.withFont(Draw.UI_FONT), gg.aurora.nametag.NameStyler.time());
            if (minimal() && Settings.hudShadow) Draw.text(g, s, x + 0.6f, y + 0.6f, scale, 0xB3000000);
            Draw.text(g, comp, x, y, scale, 0xFFFFFFFF);
            return;
        }
        text(g, s, x, y, scale, textColor());
    }

    public static int dimColor() {
        return 0xFF000000 | Theme.TEXT_DIM;
    }
}
