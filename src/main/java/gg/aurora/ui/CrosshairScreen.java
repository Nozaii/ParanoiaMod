package gg.aurora.ui;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import gg.aurora.Config;
import gg.aurora.Settings;
import gg.aurora.module.VisualModules;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Éditeur de viseur : viseurs prédéfinis (formes vectorielles et dessins pixel) à gauche, grille de
 * dessin 16×16 / 32×32 / 64×64 à droite (crayon, gomme, symétrie, palette), aperçu en taille réelle.
 */
public class CrosshairScreen extends Screen {
    private final Screen parent;
    private long last = System.nanoTime();
    private float open;
    private boolean eraser, mirror = true, painting, dirty;
    private int color = 0xFFFFFFFF;
    private static final int[] PALETTE = {0xFFFFFFFF, 0xFF000000, 0xFFFF3B3B, 0xFF3DDC84, 0xFF3FB8FF, 0xFFFFD23F, 0xFFB98CFF, 0xFFFF6EC7};

    private record Hit(float x, float y, float w, float h, Runnable action) {}

    private final List<Hit> hits = new ArrayList<>();
    private float gx, gy, cell;

    public CrosshairScreen(Screen parent) {
        super(Component.literal("Crosshair"));
        this.parent = parent;
        CrosshairArt.ensureLoaded();
    }

    private void click(float pitch) {
        if (Settings.sounds) minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch, 0.3f));
    }

    private boolean in(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        long now = System.nanoTime();
        float dt = Math.min(0.05f, (now - last) / 1e9f);
        last = now;
        open = Settings.anim() ? Math.min(1, open + dt * 6) : 1;
        float a = open;
        hits.clear();
        boolean pointer = false;
        g.fill(0, 0, width, height, Draw.withAlpha(0x000000, 0.6f * a));

        float pw = Math.min(width - 20, 470), ph = Math.min(height - 20, 300);
        float px = (width - pw) / 2, py = (height - ph) / 2 + (1 - Draw.easeOutCubic(a)) * 10;
        Draw.glow(g, px, py + 4, pw, ph, 18, Draw.withAlpha(0x000000, 0.5f * a));
        Draw.glass(g, px, py, pw, ph, 10, Draw.withAlpha(Theme.PANEL, 0.97f * a), Draw.withAlpha(0xFFFFFF, 0.18f * a));
        Draw.text(g, "Crosshair", px + 12, py + 10, 1.1f, Draw.withAlpha(Theme.TEXT, a));
        Draw.text(g, "Pick a template or draw your own", px + 12 + Draw.width("Crosshair", 1.1f) + 8, py + 12, 0.8f, Draw.withAlpha(Theme.TEXT_DIM, a));
        float cx = px + pw - 20, cy = py + 8;
        boolean hvClose = in(mx, my, cx, cy, 12, 12);
        pointer |= hvClose;
        Draw.centered(g, "×", cx + 6, cy + 1, 1.1f, Draw.withAlpha(hvClose ? 0xFFFFFF : Theme.TEXT_DIM, a));
        hits.add(new Hit(cx, cy, 12, 12, this::onClose));

        int style = (int) VisualModules.CROSSHAIR.opt("style", 2);
        // ---- modèles : formes vectorielles
        float lx = px + 12, ly = py + 30, lw = pw * 0.42f;
        Draw.text(g, "SHAPES", lx, ly, 0.68f, Draw.withAlpha(Theme.accent(), a));
        float tile = (lw - 3 * 4) / 4;
        int vecCount = VisualModules.CROSS_STYLES.length - 1;
        for (int i = 0; i < vecCount; i++) {
            float x = lx + (i % 4) * (tile + 4), y = ly + 10 + (i / 4) * (tile + 4);
            boolean sel = style == i, hv = in(mx, my, x, y, tile, tile);
            pointer |= hv;
            Draw.rrect(g, x, y, tile, tile, 6, Draw.withAlpha(sel ? Draw.lerpColor(Theme.ROW, Theme.accent(), 0.4f) : hv ? Theme.ROW_HOVER : Theme.ROW, 0.95f * a));
            if (sel) Draw.ring(g, x, y, tile, tile, 6, Draw.withAlpha(Theme.accent(), a));
            VisualModules.drawCrosshairShape(g, x + tile / 2, y + tile / 2, i, 0xFFFFFFFF, tile / 40f);
            int idx = i;
            hits.add(new Hit(x, y, tile, tile, () -> {
                VisualModules.CROSSHAIR.set("style", idx);
                Config.save();
                click(1.1f);
            }));
        }
        // ---- modèles : dessins pixel (chargés dans la grille)
        float py2 = ly + 10 + 2 * (tile + 4) + 8;
        Draw.text(g, "DRAWINGS (open in the grid)", lx, py2, 0.68f, Draw.withAlpha(Theme.accent(), a));
        for (int i = 0; i < CrosshairArt.Presets.NAMES.length; i++) {
            float t2 = (tile * 4 + 12 - 7 * 3) / 8f; // templates: 8 per row, 2 rows
            float x = lx + (i % 8) * (t2 + 3), y = py2 + 10 + (i / 8) * (t2 + 3);
            boolean hv = in(mx, my, x, y, t2, t2);
            pointer |= hv;
            Draw.rrect(g, x, y, t2, t2, 6, Draw.withAlpha(hv ? Theme.ROW_HOVER : Theme.ROW, 0.95f * a));
            int[] pre = CrosshairArt.Presets.pixel(i, 16);
            float ps = (t2 - 4) / 16f;
            for (int k = 0; k < pre.length; k++) {
                if (pre[k] == 0) continue;
                g.fill(Math.round(x + 2 + (k % 16) * ps), Math.round(y + 2 + (k / 16) * ps), Math.round(x + 2 + (k % 16 + 1) * ps),
                        Math.round(y + 2 + (k / 16 + 1) * ps), 0xFFFFFFFF);
            }
            int idx = i;
            hits.add(new Hit(x, y, t2, t2, () -> {
                CrosshairArt.pixels = CrosshairArt.Presets.pixel(idx, CrosshairArt.size);
                useArt();
                click(1.2f);
            }));
        }

        // ---- grille de dessin
        float rx = px + pw * 0.46f, rw = px + pw - 12 - rx;
        Draw.text(g, "DRAWING", rx, ly, 0.68f, Draw.withAlpha(Theme.accent(), a));
        // tailles
        float bx = rx + 40;
        for (int n : new int[]{16, 32, 64}) {
            String lab = n + "×" + n;
            float bw = Draw.width(lab, 0.75f) + 10;
            boolean sel = CrosshairArt.size == n, hv = in(mx, my, bx, ly - 2, bw, 11);
            pointer |= hv;
            Draw.rrect(g, bx, ly - 2, bw, 11, 5.5f, Draw.withAlpha(sel ? Theme.accent() : hv ? Theme.ROW_HOVER : Theme.ROW, 0.95f * a));
            Draw.centered(g, lab, bx + bw / 2, ly + 0.5f, 0.75f, Draw.withAlpha(sel ? 0xFFFFFF : Theme.TEXT_DIM, a));
            hits.add(new Hit(bx, ly - 2, bw, 11, () -> {
                CrosshairArt.resize(n);
                useArt();
                click(1.1f);
            }));
            bx += bw + 4;
        }
        int n = CrosshairArt.size;
        float gsz = Math.min(rw - 50, ph - 70);
        cell = gsz / n;
        gx = rx;
        gy = ly + 12;
        Draw.rrect(g, gx - 2, gy - 2, n * cell + 4, n * cell + 4, 3, Draw.withAlpha(0x000000, 0.5f * a));
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int c = CrosshairArt.pixels[y * n + x];
                int bg = ((x + y) % 2 == 0) ? 0xFF1C1A26 : 0xFF23202F;
                if (x == n / 2 || y == n / 2) bg = Draw.lerpColor(bg, Theme.accent(), 0.12f) | 0xFF000000;
                int fx = Math.round(gx + x * cell), fy = Math.round(gy + y * cell), tx = Math.round(gx + (x + 1) * cell), ty = Math.round(gy + (y + 1) * cell);
                g.fill(fx, fy, tx, ty, (c >>> 24) > 0 ? c : bg);
            }
        }
        if (in(mx, my, gx, gy, n * cell, n * cell)) {
            int hx = (int) ((mx - gx) / cell), hy = (int) ((my - gy) / cell);
            Draw.ring(g, gx + hx * cell, gy + hy * cell, cell, cell, 0, Draw.withAlpha(0xFFFFFF, 0.8f * a));
            pointer = true;
            if (painting) paint(hx, hy);
        }

        // ---- outils (colonne à droite de la grille)
        float tx = gx + n * cell + 8, ty = gy;
        tool(g, mx, my, tx, ty, "Pencil", !eraser, () -> eraser = false, a);
        tool(g, mx, my, tx, ty + 15, "Eraser", eraser, () -> eraser = true, a);
        tool(g, mx, my, tx, ty + 30, "Symmetry", mirror, () -> mirror = !mirror, a);
        tool(g, mx, my, tx, ty + 45, "Clear", false, () -> {
            CrosshairArt.pixels = new int[n * n];
            useArt();
        }, a);
        for (int i = 0; i < PALETTE.length; i++) {
            float sx = tx + (i % 2) * 15, sy = ty + 66 + (i / 2) * 15;
            boolean sel = color == PALETTE[i] && !eraser, hv = in(mx, my, sx, sy, 12, 12);
            pointer |= hv;
            Draw.rrect(g, sx, sy, 12, 12, 3, PALETTE[i]);
            Draw.ring(g, sx, sy, 12, 12, 3, Draw.withAlpha(sel ? Theme.accent() : 0xFFFFFF, (sel ? 1f : 0.25f) * a));
            int col = PALETTE[i];
            hits.add(new Hit(sx, sy, 12, 12, () -> {
                color = col;
                eraser = false;
                click(1.3f);
            }));
        }
        // aperçu en taille réelle sur un fond de jeu
        float pvY = ty + 66 + 4 * 15 + 6, pv = 28;
        Draw.rrect(g, tx, pvY, pv, pv, 4, 0xFF5B7A3A);
        Draw.rrect(g, tx, pvY, pv, pv / 2, 4, 0xFF8FB4E8);
        if (CrosshairArt.ready()) {
            float ds = (float) VisualModules.CROSSHAIR.opt("drawSize", 16);
            float s = Math.min(pv - 4, ds);
            Draw.image(g, CrosshairArt.TEX, tx + pv / 2 - s / 2, pvY + pv / 2 - s / 2, s, s, n, n, 0, 0, n, n, 0xFFFFFFFF);
        }
        Draw.text(g, "Preview", tx, pvY + pv + 3, 0.65f, Draw.withAlpha(Theme.TEXT_MUTED, a));

        // utiliser le dessin
        boolean using = style == VisualModules.CROSS_STYLES.length - 1;
        String lab = using ? "Drawing in use ✓" : "Use this drawing";
        float uw = Draw.width(lab, 0.85f) + 18, ux = rx, uy = py + ph - 22;
        boolean hvU = in(mx, my, ux, uy, uw, 15);
        pointer |= hvU;
        Draw.rrect(g, ux, uy, uw, 15, 7.5f, Draw.withAlpha(using ? Theme.ROW : Theme.accent(), (hvU ? 1f : 0.9f) * a));
        Draw.centered(g, lab, ux + uw / 2, uy + 3.8f, 0.85f, 0xFFFFFFFF);
        hits.add(new Hit(ux, uy, uw, 15, () -> {
            useArt();
            click(1.25f);
        }));
        if (pointer) g.requestCursor(CursorTypes.POINTING_HAND);
    }

    private void tool(GuiGraphics g, int mx, int my, float x, float y, String label, boolean on, Runnable action, float a) {
        float w = 46;
        boolean hv = in(mx, my, x, y, w, 12);
        Draw.rrect(g, x, y, w, 12, 6, Draw.withAlpha(on ? Theme.accent() : hv ? Theme.ROW_HOVER : Theme.ROW, 0.95f * a));
        Draw.centered(g, label, x + w / 2, y + 2.5f, 0.72f, Draw.withAlpha(on ? 0xFFFFFF : Theme.TEXT_DIM, a));
        hits.add(new Hit(x, y, w, 12, () -> {
            action.run();
            click(1.1f);
        }));
    }

    /** Le dessin devient le viseur actif (style « Dessin perso »). */
    private void useArt() {
        VisualModules.CROSSHAIR.set("style", VisualModules.CROSS_STYLES.length - 1);
        dirty = true;
        CrosshairArt.upload();
    }

    private void paint(int x, int y) {
        int n = CrosshairArt.size;
        int c = eraser ? 0 : color;
        set(x, y, c);
        if (mirror) {
            set(n - 1 - x, y, c);
            set(x, n - 1 - y, c);
            set(n - 1 - x, n - 1 - y, c);
        }
    }

    private void set(int x, int y, int c) {
        int n = CrosshairArt.size;
        if (x < 0 || y < 0 || x >= n || y >= n) return;
        if (CrosshairArt.pixels[y * n + x] != c) {
            CrosshairArt.pixels[y * n + x] = c;
            dirty = true;
            CrosshairArt.upload();
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean dbl) {
        double mx = event.x(), my = event.y();
        int n = CrosshairArt.size;
        if (in(mx, my, gx, gy, n * cell, n * cell)) {
            // clic droit = gomme
            if (event.button() == 1) eraser = true;
            painting = true;
            VisualModules.CROSSHAIR.set("style", VisualModules.CROSS_STYLES.length - 1);
            paint((int) ((mx - gx) / cell), (int) ((my - gy) / cell));
            return true;
        }
        if (event.button() != 0) return true;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (in(mx, my, h.x(), h.y(), h.w(), h.h())) {
                h.action().run();
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        painting = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        if (dirty) CrosshairArt.save();
        Config.save();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return parent == null || parent.isPauseScreen();
    }
}
