package gg.aurora.ui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Sélecteur segmenté : options de largeur égale dans un conteneur sombre, la pastille de l'option
 * active glisse d'une option à l'autre (elle ne disparaît jamais pour réapparaître ailleurs).
 */
public final class Segmented {
    private final Spring slide = new Spring(0, 260, 0.85f);
    private float x, y, h, optW;
    private int count;
    private boolean first = true;

    /** Largeur totale pour ces libellés (toutes les options ont la largeur du plus long). */
    public float width(String[] labels, float scale) {
        float w = 0;
        for (String l : labels) w = Math.max(w, Draw.width(l, scale));
        return 4 + labels.length * (w + 14);
    }

    public void draw(GuiGraphics g, float x, float y, float h, String[] labels, int selected, int mx, int my, float dt, float a) {
        this.x = x;
        this.y = y;
        this.h = h;
        count = labels.length;
        float scale = 0.75f, w = 0;
        for (String l : labels) w = Math.max(w, Draw.width(l, scale));
        optW = w + 14;
        if (first) {
            slide.set(selected);
            first = false;
        }
        slide.update(dt, selected);
        Draw.rrect(g, x, y, 4 + count * optW, h, 4, Draw.withAlpha(0x000000, 0.28f * a));
        Draw.rrect(g, x + 2 + slide.value * optW, y + 2, optW, h - 4, 3, Draw.withAlpha(Theme.ROW_HOVER, 0.95f * a));
        for (int i = 0; i < count; i++) {
            float ox = x + 2 + i * optW;
            boolean hv = Rows.inside(mx, my, ox, y, optW, h);
            int col = i == selected ? Theme.TEXT : hv ? Draw.lerpColor(Theme.TEXT_DIM, Theme.TEXT, 0.6f) : Theme.TEXT_DIM;
            Draw.centered(g, labels[i], ox + optW / 2, y + (h - 7) / 2 - 0.2f, scale, Draw.withAlpha(col, a));
        }
    }

    public boolean hovered(double mx, double my) {
        return hit(mx, my) >= 0;
    }

    /** Option sous le pointeur, ou -1. */
    public int hit(double mx, double my) {
        if (!Rows.inside(mx, my, x, y, 4 + count * optW, h)) return -1;
        int i = (int) ((mx - x - 2) / optW);
        return i < 0 || i >= count ? -1 : i;
    }
}
