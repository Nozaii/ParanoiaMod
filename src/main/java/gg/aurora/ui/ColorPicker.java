package gg.aurora.ui;

import net.minecraft.client.gui.GuiGraphics;

import java.util.Locale;

/** Sélecteur de couleur graphique : carré saturation/luminosité + barre de teinte. */
public class ColorPicker {
    private float hue, sat, val;
    private int dragPart = -1; // 0 = carré, 1 = teinte
    private float sx, sy, sw, sh, hx, hw;

    public void setColor(int rgb) {
        float r = (rgb >> 16 & 255) / 255f, g = (rgb >> 8 & 255) / 255f, b = (rgb & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float h = 0;
        if (d > 0) {
            if (max == r) h = ((g - b) / d) % 6;
            else if (max == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h /= 6;
            if (h < 0) h += 1;
        }
        // garde la teinte courante pour les gris
        if (d > 0) hue = h;
        sat = max == 0 ? 0 : d / max;
        val = max;
    }

    public int color() {
        return hsv(hue, sat, val);
    }

    public static int hsv(float h, float s, float v) {
        float c = v * s, x = c * (1 - Math.abs((h * 6) % 2 - 1)), m = v - c;
        float r, g, b;
        int seg = (int) Math.floor(h * 6) % 6;
        switch (seg) {
            case 0 -> { r = c; g = x; b = 0; }
            case 1 -> { r = x; g = c; b = 0; }
            case 2 -> { r = 0; g = c; b = x; }
            case 3 -> { r = 0; g = x; b = c; }
            case 4 -> { r = x; g = 0; b = c; }
            default -> { r = c; g = 0; b = x; }
        }
        return (Math.round((r + m) * 255) << 16) | (Math.round((g + m) * 255) << 8) | Math.round((b + m) * 255);
    }

    public void render(GuiGraphics g, float x, float y, float w, float h, float a) {
        hw = 9;
        sw = w - hw - 6;
        sh = h;
        sx = x;
        sy = y;
        hx = x + sw + 6;
        int cols = 48;
        // carré : chaque colonne = saturation fixe, du clair (haut) au noir (bas)
        Draw.rrect(g, sx - 1, sy - 1, sw + 2, sh + 2, 4, Draw.withAlpha(0xFFFFFF, 0.2f * a));
        for (int i = 0; i < cols; i++) {
            float x0 = sx + sw * i / cols, x1 = sx + sw * (i + 1) / cols;
            int top = hsv(hue, (i + 0.5f) / cols, 1f);
            g.fillGradient(Math.round(x0), Math.round(sy), Math.round(x1), Math.round(sy + sh),
                    Draw.withAlpha(top, a), Draw.withAlpha(0x000000, a));
        }
        // barre de teinte
        int steps = 36;
        for (int i = 0; i < steps; i++) {
            float y0 = sy + sh * i / steps, y1 = sy + sh * (i + 1) / steps;
            g.fillGradient(Math.round(hx), Math.round(y0), Math.round(hx + hw), Math.round(y1),
                    Draw.withAlpha(hsv(i / (float) steps, 1, 1), a), Draw.withAlpha(hsv((i + 1) / (float) steps % 1f, 1, 1), a));
        }
        // curseurs
        float cx = sx + sat * sw, cy = sy + (1 - val) * sh;
        Draw.ring(g, cx - 4, cy - 4, 8, 8, 4, Draw.withAlpha(0x000000, 0.6f * a));
        Draw.rrect(g, cx - 3, cy - 3, 6, 6, 3, Draw.withAlpha(color(), a));
        Draw.ring(g, cx - 3.5f, cy - 3.5f, 7, 7, 3.5f, Draw.withAlpha(0xFFFFFF, a));
        float hy = sy + hue * sh;
        Draw.rrect(g, hx - 1.5f, hy - 2, hw + 3, 4, 2, Draw.withAlpha(0xFFFFFF, a));
        Draw.ring(g, hx - 1.5f, hy - 2, hw + 3, 4, 2, Draw.withAlpha(0x000000, 0.5f * a));
    }

    public String hex() {
        return String.format(Locale.ROOT, "#%06X", color());
    }

    /** true si le clic touche le sélecteur. */
    public boolean click(double mx, double my) {
        if (Rows.inside(mx, my, sx - 2, sy - 2, sw + 4, sh + 4)) dragPart = 0;
        else if (Rows.inside(mx, my, hx - 3, sy - 2, hw + 6, sh + 4)) dragPart = 1;
        else return false;
        drag(mx, my);
        return true;
    }

    public boolean drag(double mx, double my) {
        if (dragPart == 0) {
            sat = Draw.clamp01((float) ((mx - sx) / sw));
            val = 1 - Draw.clamp01((float) ((my - sy) / sh));
            return true;
        }
        if (dragPart == 1) {
            hue = Math.min(0.999f, Draw.clamp01((float) ((my - sy) / sh)));
            return true;
        }
        return false;
    }

    public boolean dragging() {
        return dragPart >= 0;
    }

    public void release() {
        dragPart = -1;
    }
}
