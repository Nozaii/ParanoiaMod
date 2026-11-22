package gg.aurora.ui;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import gg.aurora.Config;
import gg.aurora.Settings;
import gg.aurora.module.HudModule;
import gg.aurora.module.Modules;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Éditeur du HUD. Le jeu reste visible tel quel : chaque widget est dessiné exactement comme en partie.
 * Glisser pour déplacer (aimantation aux bords, au centre et aux autres widgets, guides visibles ; Maj = libre),
 * poignées d'angle ou molette pour la taille, flèches pour le pixel près (Maj = 10 px), Ctrl+Z / Ctrl+Y.
 * Un clic ouvre le tiroir de réglages du widget, dans le style du menu.
 */
public class HudEditScreen extends Screen {
    private static final float BAR_H = 22, DRAWER_W = 186, HANDLE = 7, SNAP = 4, MARGIN = 4;

    private final Screen parent;
    private HudModule dragging, selected, resizing, hoverMod;
    /** Coin tenu : le coin opposé reste en place. */
    private boolean resizeRight, resizeBottom;
    private float anchorX, anchorY, baseW, baseH;
    private double grabX, grabY, pressX, pressY;
    private boolean moved;
    private long lastFrame = System.nanoTime();
    private float dt;
    private boolean pointer, resizeCursor;
    private final Spring drawer = new Spring(0, 240, 0.9f);
    private final Spring barIn = new Spring(0, 200, 0.85f);
    private final Spring snapT = new Spring(1, 320, 0.9f);
    private final Spring listIn = new Spring(0, 260, 0.85f);
    private final RowPanel rows;
    private float dx, dy, dw, dh;          // tiroir
    private float bx, by, bw;              // barre d'outils
    private float lx, ly, lw, lh, listBtnX; // liste des widgets
    private boolean snap = true, widgetsOpen;
    private float guideX = Float.NaN, guideY = Float.NaN, guideFade;
    private final List<float[]> hits = new ArrayList<>();
    private final List<Runnable> hitActions = new ArrayList<>();

    // ---- annuler / rétablir : instantanés d'un ou plusieurs widgets
    private record State(HudModule m, float x, float y, float scale, boolean icon, boolean label, boolean bg, int color) {
        static State of(HudModule m) {
            return new State(m, m.x, m.y, m.scale, m.showIcon, m.showLabel, m.showBg, m.colorMode);
        }

        void apply() {
            m.x = x;
            m.y = y;
            m.scale = scale;
            m.showIcon = icon;
            m.showLabel = label;
            m.showBg = bg;
            m.colorMode = color;
        }
    }

    private final ArrayDeque<List<State>> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private List<State> pending;

    public HudEditScreen(Screen parent) {
        super(Component.literal("Edit HUD"));
        this.parent = parent;
        rows = new RowPanel(() -> dt, new RowPanel.Callbacks() {
            public void pointer() {
                pointer = true;
            }

            public void sound(float pitch) {
                click(pitch);
            }

            public void changed() {
                Config.save();
            }
        });
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public void debugSelect(HudModule h) {
        select(h);
    }

    // ======================================================================= historique

    private void begin(HudModule h) {
        pending = List.of(State.of(h));
    }

    private void beginAll() {
        List<State> all = new ArrayList<>();
        for (HudModule h : Modules.hudModules()) all.add(State.of(h));
        pending = all;
    }

    /** Garde l'instantané pris au début du geste seulement si quelque chose a changé. */
    private void commit() {
        if (pending == null) return;
        boolean changed = false;
        for (State s : pending) if (!State.of(s.m()).equals(s)) changed = true;
        if (changed) {
            undo.push(pending);
            redo.clear();
            while (undo.size() > 60) undo.removeLast();
            Config.save();
        }
        pending = null;
    }

    private void doUndo() {
        if (undo.isEmpty()) return;
        List<State> s = undo.pop();
        redo.push(s.stream().map(st -> State.of(st.m())).toList());
        s.forEach(State::apply);
        if (s.size() == 1) select(s.get(0).m());
        Config.save();
        click(0.95f);
    }

    private void doRedo() {
        if (redo.isEmpty()) return;
        List<State> s = redo.pop();
        undo.push(s.stream().map(st -> State.of(st.m())).toList());
        s.forEach(State::apply);
        if (s.size() == 1) select(s.get(0).m());
        Config.save();
        click(1.05f);
    }

    // ======================================================================= sélection

    private void select(HudModule h) {
        selected = h;
        if (h == null) return;
        List<Rows.Row> list = new ArrayList<>(h.ownSettings());
        list.add(new Rows.Slider("Size", null, 0.5, 3.0, 0.05, () -> h.scale, v -> h.scale = (float) v, v -> Math.round(v * 100) + " %"));
        list.add(new Rows.Choice("Text color", HudModule.COLOR_MODES, () -> h.colorMode, i -> h.colorMode = i));
        list.add(new Rows.Toggle("Background", null, () -> h.showBg, v -> h.showBg = v));
        list.add(new Rows.Toggle("Icon", "Hidden in Clean style", () -> h.showIcon, v -> h.showIcon = v));
        list.add(new Rows.Toggle("Label", "E.g. « FPS » next to the value", () -> h.showLabel, v -> h.showLabel = v));
        list.add(new Rows.Button("Reset", "Default position and style", Icons.ARROW_LEFT, () -> {
            begin(h);
            h.resetStyle();
            commit();
        }));
        rows.setRows(list);
    }

    private float left(HudModule h) {
        return h.x * (width - h.scaledW());
    }

    private float top(HudModule h) {
        return h.y * (height - h.scaledH());
    }

    private void place(HudModule h, float l, float t) {
        float w = h.scaledW(), hh = h.scaledH();
        h.x = Math.max(0, Math.min(1, l / Math.max(1, width - w)));
        h.y = Math.max(0, Math.min(1, t / Math.max(1, height - hh)));
    }

    // ======================================================================= rendu

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // rien : le jeu reste visible, les widgets se voient exactement comme en partie
        long t = System.nanoTime();
        float raw = Math.min(0.05f, (t - lastFrame) / 1e9f);
        lastFrame = t;
        dt = Settings.anim() ? raw * Settings.animFactor() : raw;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        pointer = false;
        resizeCursor = false;
        hits.clear();
        hitActions.clear();
        barIn.update(dt, 1);
        drawer.update(dt, selected != null ? 1 : 0);
        listIn.update(dt, widgetsOpen ? 1 : 0);
        snapT.update(dt, snap ? 1 : 0);
        layout();
        boolean overUi = overBar(mx, my) || overDrawer(mx, my) || overList(mx, my);
        boolean liveData = minecraft.level != null;

        // widgets : rendu identique au jeu
        hoverMod = null;
        List<HudModule> list = Modules.hudModules();
        for (HudModule h : list) {
            if (!h.enabled) continue;
            h.draw(g, !liveData);
            if (dragging == null && resizing == null && !overUi && inside(mx, my, left(h), top(h), h.scaledW(), h.scaledH())) hoverMod = h;
        }
        if (dragging != null) hoverMod = dragging;
        if (resizing != null) hoverMod = resizing;

        // guides d'aimantation
        boolean showGuides = dragging != null && (!Float.isNaN(guideX) || !Float.isNaN(guideY));
        guideFade = Draw.smooth(guideFade, showGuides ? 1 : 0, 18, dt);
        if (guideFade > 0.02f) {
            int gc = Draw.withAlpha(Theme.accent(), 0.85f * guideFade);
            if (!Float.isNaN(guideX)) Draw.rect(g, guideX - 0.5f, 0, 1, height, gc);
            if (!Float.isNaN(guideY)) Draw.rect(g, 0, guideY - 0.5f, width, 1, gc);
        }

        // contours : survol discret, sélection accent, poignées sur le widget actif
        for (HudModule h : list) {
            if (!h.enabled) continue;
            boolean sel = h == selected, hov = h == hoverMod;
            if (!sel && !hov) continue;
            float x = left(h), y = top(h), w = h.scaledW(), hh = h.scaledH();
            Draw.ring(g, x - 2, y - 2, w + 4, hh + 4, 4, sel ? Draw.withAlpha(Theme.accent(), 0.95f) : Draw.withAlpha(0xFFFFFF, 0.55f));
            if (hov && !sel && dragging == null) {
                float tw = Draw.width(h.name, 0.75f) + 10;
                float tx = Math.max(2, Math.min(width - tw - 2, x)), ty = y - 14 < 2 ? y + hh + 4 : y - 14;
                Draw.rrect(g, tx, ty, tw, 11, 3, Draw.withAlpha(0x0A0912, 0.9f));
                Draw.text(g, h.name, tx + 5, ty + 2.2f, 0.75f, Theme.TEXT_DIM | 0xFF000000);
            }
            if (sel || h == resizing) {
                for (int c = 0; c < 4; c++) {
                    float cx = handleX(h, c), cy = handleY(h, c);
                    boolean on = dragging == null && inside(mx, my, cx - HANDLE / 2, cy - HANDLE / 2, HANDLE, HANDLE);
                    resizeCursor |= on;
                    Draw.rrect(g, cx - 2.5f, cy - 2.5f, 5, 5, 1.5f, on || h == resizing ? Theme.accent() | 0xFF000000 : 0xFFFFFFFF);
                }
                if (dragging == h || resizing == h) {
                    String pos = Math.round(x) + ", " + Math.round(y) + "  ·  " + Math.round(h.scale * 100) + " %";
                    float tw = Draw.width(pos, 0.72f) + 10;
                    float tx = Math.max(2, Math.min(width - tw - 2, x + w / 2 - tw / 2)), ty = y + hh + 6 > height - 14 ? y - 14 : y + hh + 6;
                    Draw.rrect(g, tx, ty, tw, 11, 3, Draw.withAlpha(0x0A0912, 0.9f));
                    Draw.text(g, pos, tx + 5, ty + 2.2f, 0.72f, Theme.TEXT | 0xFF000000);
                }
            }
        }

        drawBar(g, mx, my);
        drawDrawer(g, mx, my);
        drawList(g, mx, my);

        if (dragging != null || resizing != null || resizeCursor) g.requestCursor(CursorTypes.RESIZE_ALL);
        else if (pointer || hoverMod != null) g.requestCursor(CursorTypes.POINTING_HAND);
        super.render(g, mx, my, partialTick);
    }

    private float handleX(HudModule h, int c) {
        float x = left(h), w = h.scaledW();
        return Math.max(3, Math.min(width - 3, c % 2 == 0 ? x - 2 : x + w + 2));
    }

    private float handleY(HudModule h, int c) {
        float y = top(h), hh = h.scaledH();
        return Math.max(3, Math.min(height - 3, c < 2 ? y - 2 : y + hh + 2));
    }

    private void layout() {
        bw = Math.min(width - 16, 470);
        bx = (width - bw) / 2;
        by = 8 - (1 - barIn.value) * (BAR_H + 12);
        dw = DRAWER_W;
        dh = Math.min(height - BAR_H - 32, 300);
        dy = by + BAR_H + 10;
        dx = width - 8 - dw + (1 - Draw.clamp01(drawer.value)) * (dw + 12);
    }

    /** Barre d'outils en haut : Done, titre, annuler / rétablir, aimantation, widgets, tout réinitialiser. */
    private float barDim = 1;

    private void drawBar(GuiGraphics g, int mx, int my) {
        // la barre s'efface quand on déplace un widget dessous (coin haut de l'écran)
        barDim = Draw.smooth(barDim, dragging != null || resizing != null ? 0.25f : 1, 14, dt);
        float a = Draw.clamp01(barIn.value) * barDim;
        Draw.glow(g, bx, by + 3, bw, BAR_H, 10, Draw.withAlpha(0x000000, 0.4f * a));
        Draw.rrect(g, bx, by, bw, BAR_H, 6, Draw.withAlpha(Theme.PANEL, 0.96f * a));
        Draw.ring(g, bx, by, bw, BAR_H, 6, Draw.withAlpha(0xFFFFFF, 0.08f * a));
        float x = bx + 5, y = by + 4, h = BAR_H - 8;

        // Done (primaire)
        float doneW = Draw.width("Done", 0.85f) + 24;
        boolean dh0 = inside(mx, my, x, y, doneW, h);
        Draw.rrect(g, x, y, doneW, h, 4, Draw.withAlpha(Draw.lerpColor(Theme.accent(), 0xFFFFFF, dh0 ? 0.12f : 0), a));
        Draw.icon(g, Icons.CHECK, x + 9, y + h / 2, 8, Draw.withAlpha(0xFFFFFF, a));
        Draw.text(g, "Done", x + 16, y + 3, 0.85f, Draw.withAlpha(0xFFFFFF, a));
        hit(x, y, doneW, h, this::onClose);
        pointer |= dh0;
        x += doneW + 8;

        int n = 0, on = 0;
        for (HudModule m : Modules.hudModules()) {
            n++;
            if (m.enabled) on++;
        }
        Draw.text(g, "Edit HUD", x, y + 2.6f, 0.95f, Draw.withAlpha(Theme.TEXT, a));
        x += Draw.width("Edit HUD", 0.95f) + 6;
        String count = on + " / " + n + " widgets";
        Draw.text(g, count, x, y + 4, 0.7f, Draw.withAlpha(Theme.TEXT_MUTED, a));

        // droite : Reset all · Widgets · Snap · Redo · Undo
        float rx = bx + bw - 5;
        rx -= button(g, rx, y, h, "Reset all", !allDefault(), mx, my, a, true, () -> {
            beginAll();
            for (HudModule m : Modules.hudModules()) m.resetStyle();
            commit();
            click(0.9f);
        }) + 6;
        float wbW = Draw.width("Widgets", 0.8f) + 24;
        rx -= wbW;
        listBtnX = rx;
        boolean wh = inside(mx, my, rx, y, wbW, h);
        Draw.rrect(g, rx, y, wbW, h, 4, Draw.withAlpha(widgetsOpen || wh ? Theme.ROW_HOVER : Theme.ROW, a));
        Draw.text(g, "Widgets", rx + 7, y + 3.4f, 0.8f, Draw.withAlpha(Theme.TEXT, a));
        Draw.icon(g, Icons.CARET_DOWN, rx + wbW - 8, y + h / 2 + 0.5f, 7, Draw.withAlpha(Theme.TEXT_DIM, a));
        hit(rx, y, wbW, h, () -> {
            widgetsOpen = !widgetsOpen;
            click(widgetsOpen ? 1.1f : 0.9f);
        });
        pointer |= wh;
        rx -= 8;
        // aimantation
        float sw = Draw.width("Snap", 0.8f) + 30;
        rx -= sw;
        boolean sh = inside(mx, my, rx, y, sw, h);
        Draw.text(g, "Snap", rx, y + 3.4f, 0.8f, Draw.withAlpha(sh ? Theme.TEXT : Theme.TEXT_DIM, a));
        Draw.toggle(g, rx + sw - 22, y + 1.5f, snapT.value, snapT.velocity, a);
        hit(rx, y, sw, h, () -> {
            snap = !snap;
            click(snap ? 1.15f : 0.9f);
        });
        pointer |= sh;
        rx -= 8;
        rx -= button(g, rx, y, h, "Redo", !redo.isEmpty(), mx, my, a, false, this::doRedo) + 4;
        button(g, rx, y, h, "Undo", !undo.isEmpty(), mx, my, a, false, this::doUndo);
    }

    /** Bouton compact de la barre, dessiné de son bord droit vers la gauche. Retourne sa largeur. */
    private float button(GuiGraphics g, float right, float y, float h, String label, boolean enabled, int mx, int my, float a, boolean quiet, Runnable r) {
        float w = Draw.width(label, 0.8f) + 14, x = right - w;
        boolean hv = enabled && inside(mx, my, x, y, w, h);
        if (!quiet || hv) Draw.rrect(g, x, y, w, h, 4, Draw.withAlpha(hv ? Theme.ROW_HOVER : Theme.ROW, (enabled ? 1 : 0.5f) * a));
        Draw.text(g, label, x + 7, y + 3.4f, 0.8f, Draw.withAlpha(enabled ? Theme.TEXT : Theme.TEXT_MUTED, a));
        if (enabled) hit(x, y, w, h, r);
        pointer |= hv;
        return w;
    }

    private boolean allDefault() {
        for (HudModule m : Modules.hudModules()) {
            if (m.x != m.defX || m.y != m.defY || m.scale != 1f || !m.showIcon || !m.showLabel || !m.showBg || m.colorMode != 0) return false;
        }
        return true;
    }

    /** Tiroir de réglages du widget sélectionné, à droite, comme dans le menu. */
    private void drawDrawer(GuiGraphics g, int mx, int my) {
        float a = Draw.clamp01(drawer.value);
        if (a < 0.01f || selected == null && a < 0.05f) return;
        HudModule h = selected != null ? selected : lastDrawn;
        if (h == null) return;
        lastDrawn = h;
        Draw.glow(g, dx, dy + 4, dw, dh, 14, Draw.withAlpha(0x000000, 0.45f * a));
        Draw.rrect(g, dx, dy, dw, dh, 8, Draw.withAlpha(Theme.PANEL, 0.97f * a));
        Draw.ring(g, dx, dy, dw, dh, 8, Draw.withAlpha(0xFFFFFF, 0.08f * a));
        Draw.icon(g, h.icon, dx + 14, dy + 13, 10, Draw.withAlpha(Theme.accent(), a));
        Draw.text(g, h.name, dx + 24, dy + 8.5f, 1f, Draw.withAlpha(Theme.TEXT, a));
        String sz = String.format(Locale.ROOT, "%d %%", Math.round(h.scale * 100));
        Draw.text(g, sz, dx + dw - 30 - Draw.width(sz, 0.75f), dy + 10, 0.75f, Draw.withAlpha(Theme.TEXT_DIM, a));
        boolean xh = inside(mx, my, dx + dw - 19, dy + 6, 14, 14);
        Draw.rrect(g, dx + dw - 19, dy + 6, 14, 14, 4, Draw.withAlpha(xh ? Theme.ROW_HOVER : Theme.ROW, a));
        Draw.text(g, "×", dx + dw - 15.5f, dy + 8.5f, 1f, Draw.withAlpha(Theme.TEXT_DIM, a));
        pointer |= xh;
        Draw.rrect(g, dx + 8, dy + 25, dw - 16, 0.8f, 0.4f, Draw.withAlpha(0xFFFFFF, 0.08f * a));
        rows.render(g, dx + 2, dy + 28, dw - 4, dh - 32, mx, my, a);
    }

    private HudModule lastDrawn;

    /** Liste des widgets : activer / désactiver sans quitter l'éditeur. */
    private void drawList(GuiGraphics g, int mx, int my) {
        float a = Draw.clamp01(listIn.value);
        if (a < 0.01f) return;
        List<HudModule> all = Modules.hudModules();
        float rowH = 16;
        int cols = all.size() * rowH + 10 > height - BAR_H - 40 ? 2 : 1;
        int perCol = (all.size() + cols - 1) / cols;
        lw = 126 * cols + 8;
        lh = perCol * rowH + 10;
        lx = Math.max(8, Math.min(width - lw - 8, listBtnX + 40 - lw));
        ly = by + BAR_H + 6 - (1 - a) * 6;
        Draw.glow(g, lx, ly + 3, lw, lh, 12, Draw.withAlpha(0x000000, 0.4f * a));
        Draw.rrect(g, lx, ly, lw, lh, 6, Draw.withAlpha(Theme.PANEL, 0.97f * a));
        Draw.ring(g, lx, ly, lw, lh, 6, Draw.withAlpha(0xFFFFFF, 0.08f * a));
        for (int i = 0; i < all.size(); i++) {
            HudModule m = all.get(i);
            float x = lx + 4 + (i / perCol) * 126, y = ly + 5 + (i % perCol) * rowH;
            boolean hv = widgetsOpen && inside(mx, my, x, y, 122, rowH);
            if (hv) Draw.rrect(g, x, y, 122, rowH, 3, Draw.withAlpha(Theme.ROW_HOVER, a));
            Draw.icon(g, m.icon, x + 9, y + rowH / 2, 8, Draw.withAlpha(m.enabled ? Theme.accent() : Theme.TEXT_MUTED, a));
            Draw.text(g, m.name, x + 17, y + 4, 0.8f, Draw.withAlpha(m.enabled ? Theme.TEXT : Theme.TEXT_DIM, a));
            Spring s = toggleSprings.computeIfAbsent(m, k -> new Spring(m.enabled ? 1 : 0, 320, 0.8f));
            s.update(dt, m.enabled ? 1 : 0);
            Draw.toggle(g, x + 122 - 24, y + 2.5f, s.value, s.velocity, a);
            if (widgetsOpen) hit(x, y, 122, rowH, () -> {
                m.toggle();
                if (!m.enabled && selected == m) select(null);
                Config.save();
                click(m.enabled ? 1.2f : 0.85f);
            });
            pointer |= hv;
        }
    }

    private final java.util.Map<HudModule, Spring> toggleSprings = new java.util.HashMap<>();

    private void hit(float x, float y, float w, float h, Runnable r) {
        hits.add(new float[]{x, y, w, h});
        hitActions.add(r);
    }

    private boolean overBar(double mx, double my) {
        return inside(mx, my, bx, by, bw, BAR_H);
    }

    private boolean overDrawer(double mx, double my) {
        return selected != null && drawer.value > 0.5f && inside(mx, my, dx, dy, dw, dh);
    }

    private boolean overList(double mx, double my) {
        return widgetsOpen && inside(mx, my, lx, ly, lw, lh);
    }

    private static boolean inside(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ======================================================================= souris

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubleClick);
        double mx = event.x(), my = event.y();
        for (int i = hits.size() - 1; i >= 0; i--) {
            float[] r = hits.get(i);
            if (inside(mx, my, r[0], r[1], r[2], r[3])) {
                hitActions.get(i).run();
                return true;
            }
        }
        if (overBar(mx, my) || overList(mx, my)) return true;
        if (widgetsOpen) {
            widgetsOpen = false;
            return true;
        }
        if (overDrawer(mx, my)) {
            if (inside(mx, my, dx + dw - 19, dy + 6, 14, 14)) {
                click(0.9f);
                select(null);
                return true;
            }
            begin(selected);
            rows.click(mx, my);
            return true;
        }
        // poignées du widget sélectionné
        if (selected != null && selected.enabled) {
            HudModule h = selected;
            float x = left(h), y = top(h), w = h.scaledW(), hh = h.scaledH();
            for (int c = 0; c < 4; c++) {
                if (!inside(mx, my, handleX(h, c) - HANDLE / 2, handleY(h, c) - HANDLE / 2, HANDLE, HANDLE)) continue;
                begin(h);
                resizing = h;
                resizeRight = c % 2 == 1;
                resizeBottom = c >= 2;
                anchorX = resizeRight ? x : x + w;
                anchorY = resizeBottom ? y : y + hh;
                baseW = w / h.scale;
                baseH = hh / h.scale;
                return true;
            }
        }
        var list = Modules.hudModules();
        for (int i = list.size() - 1; i >= 0; i--) {
            HudModule h = list.get(i);
            if (!h.enabled || !inside(mx, my, left(h), top(h), h.scaledW(), h.scaledH())) continue;
            begin(h);
            dragging = h;
            grabX = mx - left(h);
            grabY = my - top(h);
            pressX = mx;
            pressY = my;
            moved = false;
            guideX = guideY = Float.NaN;
            return true;
        }
        if (selected != null) select(null);
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double ddx, double ddy) {
        if (rows.drag(event.x(), event.y())) return true;
        if (resizing != null) {
            float wanted = Math.max(Math.abs((float) event.x() - anchorX) / Math.max(1, baseW), Math.abs((float) event.y() - anchorY) / Math.max(1, baseH));
            resizing.scale = Math.round(Math.max(0.5f, Math.min(3f, wanted)) * 20) / 20f;
            float w = resizing.scaledW(), h = resizing.scaledH();
            place(resizing, resizeRight ? anchorX : anchorX - w, resizeBottom ? anchorY : anchorY - h);
            return true;
        }
        if (dragging == null) return super.mouseDragged(event, ddx, ddy);
        if (Math.abs(event.x() - pressX) + Math.abs(event.y() - pressY) > 2) moved = true;
        float w = dragging.scaledW(), h = dragging.scaledH();
        float nx = (float) (event.x() - grabX), ny = (float) (event.y() - grabY);
        guideX = guideY = Float.NaN;
        if (snap && !shiftDown()) {
            // candidats : marges, centre de l'écran, bords et centres des autres widgets (alignés ou accolés)
            List<float[]> cx = new ArrayList<>(), cy = new ArrayList<>(); // {position de notre bord gauche/haut, ligne guide}
            cx.add(new float[]{MARGIN, MARGIN});
            cx.add(new float[]{width - MARGIN - w, width - MARGIN});
            cx.add(new float[]{(width - w) / 2, width / 2f});
            cy.add(new float[]{MARGIN, MARGIN});
            cy.add(new float[]{height - MARGIN - h, height - MARGIN});
            cy.add(new float[]{(height - h) / 2, height / 2f});
            for (HudModule o : Modules.hudModules()) {
                if (!o.enabled || o == dragging) continue;
                float ol = left(o), ot = top(o), ow = o.scaledW(), oh = o.scaledH();
                cx.add(new float[]{ol, ol});
                cx.add(new float[]{ol + ow - w, ol + ow});
                cx.add(new float[]{ol + ow / 2 - w / 2, ol + ow / 2});
                cx.add(new float[]{ol - w - MARGIN, ol - MARGIN / 2f});
                cx.add(new float[]{ol + ow + MARGIN, ol + ow + MARGIN / 2f});
                cy.add(new float[]{ot, ot});
                cy.add(new float[]{ot + oh - h, ot + oh});
                cy.add(new float[]{ot + oh / 2 - h / 2, ot + oh / 2});
                cy.add(new float[]{ot - h - MARGIN, ot - MARGIN / 2f});
                cy.add(new float[]{ot + oh + MARGIN, ot + oh + MARGIN / 2f});
            }
            float best = SNAP;
            for (float[] c : cx) if (Math.abs(nx - c[0]) < best) {
                best = Math.abs(nx - c[0]);
                nx = c[0];
                guideX = c[1];
            }
            best = SNAP;
            for (float[] c : cy) if (Math.abs(ny - c[0]) < best) {
                best = Math.abs(ny - c[0]);
                ny = c[0];
                guideY = c[1];
            }
        }
        place(dragging, nx, ny);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (rows.release()) {
            commit();
            return true;
        }
        if (pending != null && dragging == null && resizing == null) commit(); // clic simple dans le tiroir
        if (resizing != null) {
            resizing = null;
            commit();
            return true;
        }
        if (dragging != null) {
            if (!moved) {
                click(1.1f);
                select(dragging == selected ? null : dragging);
            }
            dragging = null;
            guideX = guideY = Float.NaN;
            commit();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (overDrawer(mx, my)) return rows.scroll(mx, my, sy);
        if (overBar(mx, my) || overList(mx, my)) return true;
        // molette sur un widget : taille par pas de 5 %, autour de son centre
        HudModule h = hoverMod != null ? hoverMod : selected;
        if (h != null && h.enabled && inside(mx, my, left(h) - 3, top(h) - 3, h.scaledW() + 6, h.scaledH() + 6)) {
            begin(h);
            float cx = left(h) + h.scaledW() / 2, cy = top(h) + h.scaledH() / 2;
            h.scale = Math.round(Math.max(0.5f, Math.min(3f, h.scale + (float) sy * 0.05f)) * 20) / 20f;
            place(h, cx - h.scaledW() / 2, cy - h.scaledH() / 2);
            commit();
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    // ======================================================================= clavier

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        boolean ctrl = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0, shift = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        if (rows.focused() != null && rows.keyPressed(key, event.modifiers())) return true;
        if (ctrl && key == GLFW.GLFW_KEY_Z) {
            if (shift) doRedo();
            else doUndo();
            return true;
        }
        if (ctrl && key == GLFW.GLFW_KEY_Y) {
            doRedo();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (widgetsOpen) widgetsOpen = false;
            else if (selected != null) select(null);
            else onClose();
            return true;
        }
        if (selected != null) {
            int mx = key == GLFW.GLFW_KEY_LEFT ? -1 : key == GLFW.GLFW_KEY_RIGHT ? 1 : 0;
            int my = key == GLFW.GLFW_KEY_UP ? -1 : key == GLFW.GLFW_KEY_DOWN ? 1 : 0;
            if (mx != 0 || my != 0) {
                float step = shift ? 10 : 1;
                begin(selected);
                place(selected, left(selected) + mx * step, top(selected) + my * step);
                commit();
                return true;
            }
            if (key == GLFW.GLFW_KEY_DELETE) {
                selected.toggle();
                Config.save();
                select(null);
                click(0.85f);
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (rows.focused() != null && rows.charTyped(event.codepointAsString())) return true;
        return super.charTyped(event);
    }

    private boolean shiftDown() {
        var w = minecraft.getWindow();
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(w, GLFW.GLFW_KEY_LEFT_SHIFT) || com.mojang.blaze3d.platform.InputConstants.isKeyDown(w, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    private void click(float pitch) {
        if (!Settings.sounds) return;
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch, 0.3f));
    }

    @Override
    public void onClose() {
        Config.save();
        minecraft.setScreen(parent);
    }
}
