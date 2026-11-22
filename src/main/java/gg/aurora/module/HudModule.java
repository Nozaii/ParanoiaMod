package gg.aurora.module;

import gg.aurora.ui.Draw;
import gg.aurora.ui.HudKit;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.function.Supplier;

/**
 * Module qui dessine un widget déplaçable sur le HUD. Position stockée en fraction de l'écran.
 * Lignes : "valeur" ou "libellé|valeur". Une ligne = pilule, plusieurs = carte avec en-tête.
 */
public class HudModule extends Module {
    public float x, y;
    private final Supplier<List<String>> lines;
    private List<String> cached;
    private long cachedAt;

    /** Taille du dernier rendu (avant échelle), utilisée par l'éditeur de HUD. */
    public int lastW = 40, lastH = 14;

    // personnalisation par widget
    public float scale = 1f;
    public boolean showIcon = true, showLabel = true, showBg = true;
    /** 0 = blanc, 1 = accent, 2 = dégradé animé (celui du nametag). */
    public int colorMode = 0;
    public final float defX, defY;

    public static final String[] COLOR_MODES = {"White", "Accent", "Gradient"};

    public HudModule(String id, String name, String icon, boolean on, float x, float y, Supplier<List<String>> lines) {
        this(id, name, Category.HUD, icon, on, x, y, lines);
    }

    public HudModule(String id, String name, Category category, String icon, boolean on, float x, float y,
                     Supplier<List<String>> lines) {
        super(id, name, category, icon, on);
        this.x = x;
        this.y = y;
        this.defX = x;
        this.defY = y;
        this.lines = lines;
    }

    /** Reaction display: skips the HUD cache and the text refresh delay. */
    public boolean live;

    public float scaledW() {
        return lastW * scale;
    }

    public float scaledH() {
        return lastH * scale;
    }

    public int px(GuiGraphics g) {
        return Math.round(x * (g.guiWidth() - scaledW()));
    }

    public int py(GuiGraphics g) {
        return Math.round(y * (g.guiHeight() - scaledH()));
    }

    /** Rendu avec l'échelle et le style propres à ce widget. */
    public void draw(GuiGraphics g, boolean preview) {
        int x0 = px(g), y0 = py(g);
        HudKit.current = this;
        g.pose().pushMatrix();
        g.pose().translate(x0, y0);
        g.pose().scale(scale, scale);
        g.pose().translate(-x0, -y0);
        render(g, preview);
        g.pose().popMatrix();
        HudKit.current = null;
    }

    /** Tous les widgets ont des réglages : options propres au widget puis style (taille, fond, couleur…). */
    @Override
    public boolean hasSettings() {
        return true;
    }

    /** Réglages propres au widget (sans le style ni le bouton d'édition) : utilisés par l'éditeur de HUD. */
    public List<gg.aurora.ui.Rows.Row> ownSettings() {
        return super.settings();
    }

    @Override
    public List<gg.aurora.ui.Rows.Row> settings() {
        List<gg.aurora.ui.Rows.Row> rows = new java.util.ArrayList<>(super.settings());
        rows.add(new gg.aurora.ui.Rows.Slider("Widget size", null, 0.5, 3, 0.05, () -> scale, v -> scale = (float) v,
                v -> Math.round(v * 100) + " %"));
        rows.add(new gg.aurora.ui.Rows.Choice("Text color", COLOR_MODES, () -> colorMode, i -> colorMode = i));
        rows.add(new gg.aurora.ui.Rows.Toggle("Background", null, () -> showBg, v -> showBg = v));
        rows.add(new gg.aurora.ui.Rows.Toggle("Icon", "Hidden in Clean style", () -> showIcon, v -> showIcon = v));
        rows.add(new gg.aurora.ui.Rows.Toggle("Label", "E.g. « FPS » next to the value", () -> showLabel, v -> showLabel = v));
        rows.add(new gg.aurora.ui.Rows.Button("Move on screen", "Opens the HUD editor", gg.aurora.ui.Icons.MOVE, () -> {
            var mc = net.minecraft.client.Minecraft.getInstance();
            mc.setScreen(new gg.aurora.ui.HudEditScreen(mc.screen));
        }));
        return rows;
    }

    /** Aperçu centré en (cx, cy), réduit si besoin pour tenir dans maxW × maxH (réglages du module). */
    public void drawPreview(GuiGraphics g, float cx, float cy, float maxW, float maxH) {
        float w0 = Math.max(1, scaledW()), h0 = Math.max(1, scaledH());
        float fit = Math.min(1f, Math.min(maxW / w0, maxH / h0));
        int x0 = px(g), y0 = py(g);
        g.pose().pushMatrix();
        g.pose().translate(cx - w0 * fit / 2, cy - h0 * fit / 2);
        g.pose().scale(fit, fit);
        g.pose().translate(-x0, -y0);
        draw(g, true);
        g.pose().popMatrix();
    }

    public void resetStyle() {
        scale = 1f;
        showIcon = showLabel = showBg = true;
        colorMode = 0;
        x = defX;
        y = defY;
    }

    /** Dessine le module à sa position. preview = rendu dans l'éditeur (contenu factice si besoin). */
    public void render(GuiGraphics g, boolean preview) {
        // texte recalculé 10 fois par seconde au lieu de chaque image (moins d'allocations à haut FPS)
        long now = System.nanoTime();
        if (cached == null || preview || live || now - cachedAt > 100_000_000L) {
            cached = lines.get();
            cachedAt = now;
        }
        List<String> text = cached;
        if (text == null || text.isEmpty()) {
            if (!preview) return;
            text = List.of(name);
        }
        if (text.size() == 1) pill(g, text.get(0));
        else card(g, text);
    }

    private static String[] split(String line) {
        int i = line.indexOf('|');
        return i < 0 ? new String[]{null, line} : new String[]{line.substring(0, i), line.substring(i + 1)};
    }

    private void pill(GuiGraphics g, String line) {
        String[] p = split(line);
        if (HudKit.clean()) {
            // « 144 FPS » : valeur en avant, libellé discret derrière
            String label = showLabel && p[0] != null ? p[0].toUpperCase(java.util.Locale.ROOT) : null;
            float vw = Draw.width(p[1], 1f), lw = label == null ? 0 : Draw.width(label, 0.68f) + 3.5f;
            float bar = showBg ? 7.5f : 1;
            lastW = (int) Math.ceil(bar + vw + lw + 5.5f);
            lastH = 13;
            float x0 = px(g), y0 = py(g);
            HudKit.background(g, x0, y0, lastW, lastH, 3.5f);
            HudKit.value(g, p[1], x0 + bar, y0 + 2.9f, 1f);
            if (label != null) HudKit.text(g, label, x0 + bar + vw + 3.5f, y0 + 4.5f, 0.68f, HudKit.dimColor());
            return;
        }
        if (!showLabel) p[0] = null;
        float iconW = showIcon ? 13 : 0;
        float labelW = p[0] == null ? 0 : Draw.width(p[0], 0.9f) + 4;
        float w = 6 + iconW + labelW + Draw.width(p[1], 1f) + 7;
        lastW = (int) Math.ceil(w);
        lastH = 15;
        float x0 = px(g), y0 = py(g);
        HudKit.background(g, x0, y0, lastW, lastH, 7.5f);
        if (showIcon) Draw.icon(g, icon, x0 + 6 + 4.5f, y0 + 7.5f, 9, HudKit.iconColor());
        float tx = x0 + 6 + iconW;
        if (p[0] != null) {
            HudKit.text(g, p[0], tx, y0 + 4.4f, 0.9f, HudKit.dimColor());
            tx += labelW;
        }
        HudKit.value(g, p[1], tx, y0 + 3.9f, 1f);
    }

    private void card(GuiGraphics g, List<String> rows) {
        float labelW = 0, valueW = 0;
        for (String r : rows) {
            String[] p = split(r);
            if (p[0] != null && showLabel) labelW = Math.max(labelW, Draw.width(p[0], 0.9f));
            valueW = Math.max(valueW, Draw.width(p[1], 1f));
        }
        boolean clean = HudKit.clean();
        boolean icon = showIcon && !clean;
        String title = clean ? name.toUpperCase(java.util.Locale.ROOT) : name;
        float ts = clean ? 0.7f : 0.85f;
        float headW = (icon ? 13 : 0) + Draw.width(title, ts) + (clean ? 4 : 0);
        float w = Math.max(headW, labelW + (labelW > 0 ? 12 : 0) + valueW) + 14;
        lastW = (int) Math.ceil(w);
        lastH = (clean ? 15 : 18) + rows.size() * 10 + 4;
        float x0 = px(g), y0 = py(g);
        HudKit.background(g, x0, y0, lastW, lastH, 6);
        if (icon) Draw.icon(g, this.icon, x0 + 7 + 4.5f, y0 + 8.5f, 9, HudKit.iconColor());
        HudKit.text(g, title, x0 + (icon ? 20 : clean ? 8 : 7), y0 + (clean ? 4.5f : 5f), ts, HudKit.dimColor());
        if (!HudKit.minimal() && !clean) Draw.rrect(g, x0 + 7, y0 + 16, lastW - 14, 0.8f, 0.4f, Draw.withAlpha(0xFFFFFF, 0.08f));
        float ry = y0 + (clean ? 15f : 19.5f);
        for (String r : rows) {
            String[] p = split(r);
            if (p[0] != null && showLabel) {
                HudKit.text(g, p[0], x0 + (clean ? 8 : 7), ry + 0.5f, 0.9f, HudKit.dimColor());
                HudKit.value(g, p[1], x0 + lastW - 7 - Draw.width(p[1], 1f), ry, 1f);
            } else {
                HudKit.value(g, p[1], x0 + 7, ry, 1f);
            }
            ry += 10;
        }
    }
}
