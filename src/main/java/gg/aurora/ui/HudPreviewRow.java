package gg.aurora.ui;

import gg.aurora.module.HudModule;
import net.minecraft.client.gui.GuiGraphics;

/** Aperçu en direct d'un widget du HUD, en haut de ses réglages : chaque changement se voit tout de suite. */
public class HudPreviewRow extends Rows.Row {
    private final HudModule module;

    public HudPreviewRow(HudModule module) {
        super("Preview", null);
        this.module = module;
    }

    @Override
    public float height() {
        return 64;
    }

    @Override
    public void draw(Rows.Host h, GuiGraphics g, float x, float y, float w, int mx, int my, float a) {
        hover.update(h.dt(), 0);
        // petite scène de jeu : ciel et herbe, pour juger la lisibilité du widget
        Draw.rrect(g, x, y, w, height(), 6, Draw.withAlpha(0x6E95C8, a));
        Draw.rrect(g, x, y + height() * 0.62f, w, height() * 0.38f, 6, Draw.withAlpha(0x4E6E30, a));
        Draw.rrect(g, x, y + height() * 0.62f, w, 2, 0, Draw.withAlpha(0x3C5524, a));
        Draw.ring(g, x, y, w, height(), 6, Draw.withAlpha(0xFFFFFF, 0.12f * a));
        Draw.text(g, "PREVIEW", x + 6, y + 4, 0.6f, Draw.withAlpha(0xFFFFFF, 0.7f * a));
        if (a > 0.6f) module.drawPreview(g, x + w / 2, y + height() / 2 + 3, w - 20, height() - 16);
    }
}
