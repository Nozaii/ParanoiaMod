package gg.aurora.ui;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import gg.aurora.Boost;
import gg.aurora.Config;
import gg.aurora.Settings;
import gg.aurora.module.Category;
import gg.aurora.module.Module;
import gg.aurora.module.Modules;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Menu "Paranoia" : les tuiles de catégories se transforment en panneau (morph), les autres
 * glissent sur les côtés. Tout est animé par ressorts et rendu en pixels réels.
 */
public class AuroraScreen extends Screen {
    private static final Category[] CATS = Category.values();
    private static final String[] BOTTOM_LABELS = {"Wardrobe", "Search", "Friends", "Profiles", "Settings", "Boost"};
    private static final String[] BOTTOM_ICONS = {Icons.PERSON, Icons.SEARCH, Icons.FRIENDS, Icons.LAYERS, Icons.GEAR, Icons.BOLT};

    private static final float TILE_W = 60, TILE_H = 66, TILE_GAP = 12;
    private static final float SIDE_W = 48, SIDE_H = 56;
    private static final float PANEL_W = 216;
    private static final float ROW_H = 17, ROW_GAP = 3;
    private static final float BTN_W = 42, BTN_H = 32, BTN_GAP = 7;

    /** Rectangle animé (position, taille, opacité) piloté par ressorts. */
    private static final class Box {
        final Spring x, y, w, h, alpha;
        final Spring hover = new Spring(0, 320, 0.62f);
        final Spring press = new Spring(0, 420, 0.5f);

        Box(float x, float y, float w, float h, float a) {
            this.x = new Spring(x, 170, 0.8f);
            this.y = new Spring(y, 170, 0.8f);
            this.w = new Spring(w, 170, 0.82f);
            this.h = new Spring(h, 170, 0.82f);
            this.alpha = new Spring(a, 140, 1f);
        }

        void target(float tx, float ty, float tw, float th, float ta) {
            x.target = tx;
            y.target = ty;
            w.target = tw;
            h.target = th;
            alpha.target = ta;
        }

        void update(float dt) {
            x.update(dt);
            y.update(dt);
            w.update(dt);
            h.update(dt);
            alpha.update(dt);
            hover.update(dt);
            press.update(dt);
        }

        boolean contains(double mx, double my) {
            return mx >= x.value && mx < x.value + w.value && my >= y.value && my < y.value + h.value;
        }
    }

    private final AuroraBackground background = new AuroraBackground();
    /** Délai avant l'apparition du contenu : laisse les montagnes monter d'abord. */
    private float contentDelay = 0.38f;
    private final Box[] tiles = new Box[CATS.length];
    private final Box searchBox = new Box(0, 0, BTN_W, BTN_H, 0);
    private final Spring[] bottomHover = new Spring[BOTTOM_LABELS.length];
    private final Spring intro = new Spring(1, 200, 1f);
    private final Spring titleY = new Spring(0, 150, 0.85f), titleScale = new Spring(1.05f, 150, 0.85f);
    private final Spring barY = new Spring(0, 160, 0.82f);
    private final Spring scroll = new Spring(0, 260, 1f);
    private final Spring editHover = new Spring(0, 320, 1f), backHover = new Spring(0, 320, 0.7f);
    private final Spring parX = new Spring(0, 30, 1f), parY = new Spring(0, 30, 1f);
    private final Spring toastY = new Spring(0, 200, 0.7f);
    private final Map<Module, Spring> knobs = new HashMap<>();
    private final Map<Module, Spring> rowHover = new HashMap<>();

    private final long openedAt = System.nanoTime();
    private long lastFrame = System.nanoTime();
    private boolean firstFrame = true;

    private Category selected;
    private boolean searching, closing;
    private long contentShownAt;
    private String query = "";
    private float scrollTarget;

    private String toast;
    private float boostClickAt = -10;
    private long toastAt;

    // géométrie du panneau (cible), recalculée à chaque frame
    private float panelX, panelY, panelH;

    public AuroraScreen() {
        super(Component.literal(Theme.NAME));
        for (int i = 0; i < tiles.length; i++) tiles[i] = new Box(0, 0, TILE_W, TILE_H, 1);
        for (int i = 0; i < bottomHover.length; i++) bottomHover[i] = new Spring(0, 320, 0.62f);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private boolean animated() {
        return Settings.anim();
    }

    private float now() {
        return (System.nanoTime() - openedAt) / 1e9f;
    }

    // ===================================================================== layout

    private boolean open() {
        return selected != null || searching;
    }

    private float homeTilesY() {
        return height * 0.40f;
    }

    private float homeTileX(int i) {
        float total = CATS.length * TILE_W + (CATS.length - 1) * TILE_GAP;
        return (width - total) / 2f + i * (TILE_W + TILE_GAP);
    }

    private float sideCenterX(int d) {
        float near = PANEL_W / 2 + 16 + SIDE_W / 2;
        float far = near + SIDE_W + 12;
        float off = Math.abs(d) == 1 ? near : far;
        off = Math.min(off, width / 2f - SIDE_W / 2 - 6 - (Math.abs(d) == 1 ? SIDE_W + 8 : 0));
        return width / 2f + Math.signum(d) * off;
    }

    private void layout() {
        boolean small = height < 330;
        float homeScale = small ? 0.8f : 1.05f, panelScale = small ? 0.6f : 0.75f;
        titleScale.target = open() ? panelScale : homeScale;
        // la police TTF dessine le titre ~13*scale au-dessus de y et ~7*scale en dessous
        float panelTitleY = 8 + 13 * panelScale;
        titleY.target = open() ? panelTitleY : height * 0.19f;
        float titleBottom = panelTitleY + 7 * panelScale;
        panelY = titleBottom + 12;
        panelH = Math.max(130, Math.min(280, height - panelY - BTN_H - 24));
        panelX = (width - PANEL_W) / 2f;
        barY.target = open() ? panelY + panelH + 10 : homeTilesY() + TILE_H + 28;

        for (int i = 0; i < CATS.length; i++) {
            Box b = tiles[i];
            if (searching) {
                b.target(homeTileX(i), homeTilesY() + 36, TILE_W, TILE_H, 0);
            } else if (selected == null) {
                b.target(homeTileX(i), homeTilesY(), TILE_W, TILE_H, 1);
            } else if (CATS[i] == selected) {
                b.target(panelX, panelY, PANEL_W, panelH, 1);
            } else {
                int d = Math.floorMod(i - selected.ordinal() + 2, CATS.length) - 2;
                float cx = sideCenterX(d), cy = panelY + panelH / 2;
                b.target(cx - SIDE_W / 2, cy - SIDE_H / 2, SIDE_W, SIDE_H, Math.abs(d) == 1 ? 1f : 0.72f);
            }
        }
        // une tuile qui doit changer de côté ne traverse pas l'écran : elle ressort par le bord
        if (selected != null) {
            float mid = width / 2f;
            for (int i = 0; i < CATS.length; i++) {
                Box b = tiles[i];
                if (CATS[i] == selected) continue;
                float cur = b.x.value + b.w.value / 2 - mid, tgt = b.x.target + b.w.target / 2 - mid;
                if (Math.abs(cur) > PANEL_W / 2f && Math.signum(cur) != Math.signum(tgt)) {
                    b.x.set(b.x.target + Math.signum(tgt) * 40);
                    b.x.target = b.x.value - Math.signum(tgt) * 40;
                    b.y.set(b.y.target);
                    b.w.set(b.w.target);
                    b.h.set(b.h.target);
                    b.alpha.set(0);
                }
            }
        }
        if (searching) {
            searchBox.target(panelX, panelY, PANEL_W, panelH, 1);
        } else {
            float bx = barX(1);
            searchBox.target(bx, barY.value, BTN_W, BTN_H, 0);
        }
    }

    private float barX(int i) {
        float total = BOTTOM_LABELS.length * BTN_W + (BOTTOM_LABELS.length - 1) * BTN_GAP;
        return (width - total) / 2f + i * (BTN_W + BTN_GAP);
    }

    private void snapToTargets() {
        for (Box b : tiles) {
            b.x.set(b.x.target);
            b.y.set(b.y.target);
            b.w.set(b.w.target);
            b.h.set(b.h.target);
            b.alpha.set(b.alpha.target);
        }
        searchBox.x.set(searchBox.x.target);
        searchBox.y.set(searchBox.y.target);
        titleY.set(titleY.target);
        titleScale.set(titleScale.target);
        barY.set(barY.target);
    }

    // ===================================================================== rendu

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long t = System.nanoTime();
        float dt = Math.min(0.05f, (t - lastFrame) / 1e9f);
        lastFrame = t;
        // temps réel partout : sans animations, ressorts et lissages sautent directement (Spring / Draw.smooth)
        frameDt = animated() ? dt * Settings.animSpeed : dt;
        background.update(frameDt);
        boolean inWorld = minecraft.level != null;
        if (inWorld && Settings.blur) renderBlurredBackground(g);
        background.render(g, width, height, inWorld ? 0.8f : 1f, parX.value, parY.value);
    }

    private float frameDt;

    /** Fond partagé avec les sous-écrans (paramètres, wardrobe). */
    public AuroraBackground background() {
        return background;
    }

    /** Horloge du contenu : démarre une fois les montagnes en place. */
    private float contentTime() {
        return now() - contentDelay;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float dt = frameDt;

        layout();
        if (firstFrame) {
            snapToTargets();
            firstFrame = false;
        }
        intro.update(dt, closing ? 0f : 1f);
        if (closing && (background.sunk() || !animated())) {
            minecraft.setScreen(null);
            return;
        }
        for (Box b : tiles) b.update(dt);
        searchBox.update(dt);
        titleY.update(dt);
        titleScale.update(dt);
        barY.update(dt);
        parX.update(dt, (mouseX / (float) width - 0.5f) * -2f);
        parY.update(dt, (mouseY / (float) height - 0.5f) * -2f);

        float in = Draw.clamp01(intro.value);
        g.pose().pushMatrix();

        boolean pointer = false;
        drawTitle(g, in);

        // tuiles : non sélectionnées d'abord, puis la sélectionnée (au-dessus)
        float time = contentTime();
        for (int i = 0; i < CATS.length; i++) {
            if (CATS[i] != selected) pointer |= drawTile(g, i, mouseX, mouseY, dt, in, time);
        }
        if (selected != null) pointer |= drawTile(g, selected.ordinal(), mouseX, mouseY, dt, in, time);
        pointer |= drawSearchPanel(g, mouseX, mouseY, dt, in);
        pointer |= drawBottomBar(g, mouseX, mouseY, dt, in);
        pointer |= drawBackButton(g, mouseX, mouseY, dt, in);
        drawToast(g, dt);
        pointer |= drawModal(g, mouseX, mouseY, dt);

        g.pose().popMatrix();
        if (pointer) g.requestCursor(CursorTypes.POINTING_HAND);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawTitle(GuiGraphics g, float in) {
        String t = Theme.NAME;
        float scale = titleScale.value;
        float total = Draw.width(Draw.title(t), scale);
        float x = width / 2f - total / 2f;
        float y = titleY.value;
        float time = now();
        for (int i = 0; i < t.length(); i++) {
            String c = String.valueOf(t.charAt(i));
            float cw = Draw.width(Draw.title(c), scale);
            // révélation lettre par lettre, puis une lueur qui balaye le titre
            float e = Draw.easeOutCubic((contentTime() - 0.05f - i * 0.05f) / 0.5f);
            if (!animated()) e = 1;
            float sweep = (float) Math.exp(-Math.pow(((time * 0.35f) % 2.2f - 0.4f) * 6f - i, 2) * 0.6f);
            int col = Draw.lerpColor(Theme.TEXT, Theme.aurora(), sweep * 0.8f);
            float a = in * e;
            Draw.text(g, Draw.title(c), x, y + (1 - e) * 8 + 1.2f * scale, scale, Draw.withAlpha(0x000000, 0.35f * a));
            Draw.text(g, Draw.title(c), x, y + (1 - e) * 8, scale, Draw.withAlpha(col, a));
            x += cw;
        }
    }

    /** Dessine la tuile i. Retourne true si la souris pointe un élément cliquable. */
    private boolean drawTile(GuiGraphics g, int i, int mx, int my, float dt, float in, float time) {
        Box b = tiles[i];
        Category c = CATS[i];
        float x = b.x.value, y = b.y.value, w = b.w.value, h = b.h.value;
        float morph = Draw.clamp01((h - TILE_H) / Math.max(1, panelH - TILE_H));
        // apparition en cascade
        float stagger = animated() ? Draw.easeOutCubic((time - 0.12f - i * 0.055f) / 0.55f) : 1;
        float alpha = Draw.clamp01(b.alpha.value) * in * stagger;
        if (alpha < 0.01f) return false;
        y += (1 - stagger) * 16;

        boolean hovered = !closing && b.contains(mx, my) && morph < 0.2f && b.alpha.target > 0.5f;
        b.hover.target = hovered ? 1 : 0;
        float hv = Draw.clamp01(b.hover.value) * (1 - morph);
        float scale = 1 + 0.06f * b.hover.value * (1 - morph) - 0.05f * b.press.value;
        float lift = -2.5f * hv;

        g.pose().pushMatrix();
        g.pose().translate(x + w / 2, y + h / 2 + lift);
        g.pose().scale(scale, scale);
        g.pose().translate(-(x + w / 2), -(y + h / 2));

        float r = Draw.lerp(8, 10, morph);
        Draw.glow(g, x, y + 4, w, h, 14, Draw.withAlpha(0x000000, 0.55f * alpha));
        Draw.glow(g, x, y, w, h, 10, Draw.withAlpha(Theme.accent(), (0.30f * hv + 0.06f * morph) * alpha));
        int fill = Draw.lerpColor(Draw.lerpColor(Theme.TILE, Theme.tileHover(), hv), Theme.PANEL, morph);
        float fa = Draw.lerp(0.62f + 0.18f * hv, 0.84f, morph);
        Draw.glass(g, x, y, w, h, r, Draw.withAlpha(fill, fa * alpha),
                Draw.withAlpha(0xFFFFFF, Draw.lerp(0.30f + 0.55f * hv, 0.42f, morph) * alpha));

        // icône + libellé : glissent du centre de la tuile vers l'en-tête du panneau
        float sizeK = w / TILE_W;
        float baseH = Math.min(h, TILE_H);
        float iconSize = Draw.lerp(18 * Math.min(1, sizeK), 13, morph);
        float iconY = Draw.lerp(y + baseH * 0.40f - 2 * hv, y + 15, morph);
        int fg = Draw.withAlpha(Theme.TEXT, alpha);
        Draw.icon(g, c.icon, x + w / 2, iconY, iconSize, fg);
        float ls = Draw.lerp(sizeK < 0.9f ? 0.9f : 1.05f, 1.1f, morph);
        float labelY = Draw.lerp(y + baseH * 0.64f, y + 25, morph);
        Draw.centered(g, c.label, x + w / 2, labelY, ls, fg);
        float uw = 9 + 12 * hv + 6 * morph;
        Draw.rrect(g, x + w / 2 - uw / 2, labelY + 11.5f * ls, uw, 1.2f, 0.6f, Draw.withAlpha(Draw.lerpColor(0xFFFFFF, Theme.accent(), Math.max(hv, morph)), (0.45f + 0.5f * Math.max(hv, morph)) * alpha));

        boolean pointer = hovered;
        float content = Draw.easeOutCubic((morph - 0.75f) / 0.25f);
        if (content > 0.01f && c == selected) pointer |= drawContent(g, x, y, w, h, alpha * content, mx, my, dt, false);
        g.pose().popMatrix();
        return pointer;
    }

    private boolean drawSearchPanel(GuiGraphics g, int mx, int my, float dt, float in) {
        Box b = searchBox;
        float alpha = Draw.clamp01(b.alpha.value) * in;
        if (alpha < 0.01f) return false;
        float x = b.x.value, y = b.y.value, w = b.w.value, h = b.h.value;
        float morph = Draw.clamp01((h - BTN_H) / Math.max(1, panelH - BTN_H));
        Draw.glow(g, x, y + 4, w, h, 14, Draw.withAlpha(0x000000, 0.55f * alpha));
        Draw.glow(g, x, y, w, h, 10, Draw.withAlpha(0xFFFFFF, 0.07f * alpha));
        Draw.glass(g, x, y, w, h, Draw.lerp(6, 10, morph), Draw.withAlpha(Theme.PANEL, 0.86f * alpha), Draw.withAlpha(0xFFFFFF, 0.42f * alpha));
        int fg = Draw.withAlpha(Theme.TEXT, alpha);
        Draw.icon(g, Icons.SEARCH, x + w / 2, Draw.lerp(y + 11, y + 15, morph), Draw.lerp(9, 13, morph), fg);
        Draw.centered(g, "Search", x + w / 2, Draw.lerp(y + 19, y + 25, morph), Draw.lerp(0.78f, 1.1f, morph), fg);
        Draw.rrect(g, x + w / 2 - 7.5f, y + 25 + 12.6f, 15, 1.2f, 0.6f, Draw.withAlpha(0xFFFFFF, 0.6f * alpha * morph));
        float content = Draw.easeOutCubic((morph - 0.75f) / 0.25f);
        if (content > 0.01f) return drawContent(g, x, y, w, h, alpha * content, mx, my, dt, true);
        return false;
    }

    /** Contenu d'un panneau ouvert : bouton Edit HUD ou champ de recherche, puis la liste des modules. */
    private boolean drawContent(GuiGraphics g, float x, float y, float w, float h, float alpha, int mx, int my, float dt, boolean search) {
        boolean pointer = false;
        float innerX = x + 9, innerW = w - 18;
        float cursor = y + 44;
        float time = now() - contentShownAt / 1e9f;
        float head = animated() ? Draw.easeOutCubic(time / 0.35f) : 1;

        if (search) {
            float a = alpha * head;
            Draw.glass(g, innerX, cursor, innerW, 16, 5, Draw.withAlpha(Theme.ROW, 0.95f * a), Draw.withAlpha(0xFFFFFF, 0.38f * a));
            Draw.icon(g, Icons.SEARCH, innerX + 9, cursor + 8, 8, Draw.withAlpha(Theme.TEXT_DIM, a));
            boolean caret = (System.currentTimeMillis() / 530) % 2 == 0;
            if (query.isEmpty()) {
                Draw.text(g, "Search a module...", innerX + 18, cursor + 4.5f, 1f, Draw.withAlpha(Theme.TEXT_MUTED, a));
                if (caret) Draw.rrect(g, innerX + 18, cursor + 4, 0.8f, 8, 0.4f, Draw.withAlpha(0xFFFFFF, a));
            } else {
                Draw.text(g, query, innerX + 18, cursor + 4.5f, 1f, Draw.withAlpha(Theme.TEXT, a));
                if (caret) Draw.rrect(g, innerX + 19 + Draw.width(query, 1f), cursor + 4, 0.8f, 8, 0.4f, Draw.withAlpha(0xFFFFFF, a));
            }
            cursor += 21;
        } else if (selected == Category.HUD) {
            boolean hv = inside(mx, my, innerX, cursor, innerW, 15);
            editHover.update(dt, hv ? 1 : 0);
            float e = editHover.value;
            float a = alpha * head;
            Draw.glow(g, innerX, cursor, innerW, 15, 6, Draw.withAlpha(Theme.accent(), 0.25f * e * a));
            Draw.glass(g, innerX, cursor, innerW, 15, 7.5f, Draw.withAlpha(Draw.lerpColor(Theme.ROW, Theme.tileHover(), e), 0.95f * a),
                    Draw.withAlpha(0xFFFFFF, (0.55f + 0.4f * e) * a));
            float tw = Draw.width("Edit HUD", 1f);
            Draw.icon(g, Icons.MOVE, x + w / 2 - tw / 2 - 7, cursor + 7.5f, 8, Draw.withAlpha(Theme.TEXT, a));
            Draw.text(g, "Edit HUD", x + w / 2 - tw / 2 + 2, cursor + 3.5f, 1f, Draw.withAlpha(Theme.TEXT, a));
            pointer |= hv;
            cursor += 20;
        }

        float listY = cursor, listH = y + h - 8 - cursor;
        List<Module> mods = visibleModules();
        float[] ys = rowOffsets(mods);
        float content = ys[mods.size()];
        float maxScroll = Math.max(0, content - listH);
        scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
        scroll.update(dt, scrollTarget);
        float sc = Math.max(0, Math.min(maxScroll, scroll.value));

        g.enableScissor((int) Math.floor(innerX - 2), (int) Math.floor(listY), (int) Math.ceil(innerX + innerW + 2), (int) Math.ceil(listY + listH));
        for (int i = 0; i < mods.size(); i++) {
            Module m = mods.get(i);
            float ry = listY + ys[i] - sc;
            if (ry + ROW_H < listY - 2 - HEAD_H || ry > listY + listH + 2) continue;
            float re = animated() ? Draw.easeOutCubic((time - 0.06f - i * 0.03f) / 0.38f) : 1;
            float ra = alpha * re;
            float rx = innerX + (1 - re) * 18;
            // en-tête de groupe au-dessus du premier module du groupe
            if (!searching && (i == 0 || !mods.get(i - 1).group.equals(m.group))) {
                String gname = m.group.toUpperCase(java.util.Locale.ROOT);
                float hy = ry - HEAD_H + 3.5f;
                Draw.text(g, gname, rx + 2, hy, 0.68f, Draw.withAlpha(Theme.accent(), 0.95f * ra));
                float lx = rx + 6 + Draw.width(gname, 0.68f);
                Draw.rrect(g, lx, hy + 3, innerW - (lx - rx) - 4, 0.6f, 0.3f, Draw.withAlpha(Theme.accent(), 0.22f * ra));
            }
            boolean hv = my >= listY && my < listY + listH && inside(mx, my, innerX, ry, innerW, ROW_H);
            pointer |= hv;
            Spring rh = rowHover.computeIfAbsent(m, k -> new Spring(0, 320, 1f));
            rh.update(dt, hv ? 1 : 0);
            Spring kn = knobs.computeIfAbsent(m, k -> new Spring(k.enabled ? 1 : 0, 300, 0.55f));
            kn.update(dt, m.enabled ? 1 : 0);

            float sw = innerW - (maxScroll > 0 ? 6 : 0);
            Draw.rrect(g, rx, ry, sw, ROW_H, 4.5f, Draw.withAlpha(Draw.lerpColor(Theme.ROW, Theme.ROW_HOVER, rh.value), 0.88f * ra));
            if (rh.value > 0.01f) Draw.ring(g, rx, ry, sw, ROW_H, 4.5f, Draw.withAlpha(0xFFFFFF, 0.22f * rh.value * ra));
            int iconCol = Draw.lerpColor(Theme.TEXT_DIM, Theme.accent(), Draw.clamp01(kn.value));
            Draw.icon(g, m.icon, rx + 10.5f, ry + ROW_H / 2, 9, Draw.withAlpha(iconCol, ra));
            Draw.text(g, m.name, rx + 20 + rh.value * 1.5f, ry + 4.6f, 1f, Draw.withAlpha(Theme.TEXT, ra));
            float gearW = 0;
            if (m.hasSettings()) {
                gearW = 16;
                float gxx = rx + sw - 44, gyy = ry + 2.5f;
                boolean gh = inside(mx, my, gxx, gyy, 12, 12) && my >= listY && my < listY + listH;
                pointer |= gh;
                Spring gs = gearHover.computeIfAbsent(m, k -> new Spring(0, 320, 0.6f));
                gs.update(dt, gh ? 1 : 0);
                if (gs.value > 0.01f) Draw.rrect(g, gxx, gyy, 12, 12, 4, Draw.withAlpha(Theme.tileHover(), Draw.clamp01(gs.value) * ra));
                g.pose().pushMatrix();
                g.pose().translate(gxx + 6, gyy + 6);
                g.pose().rotate(gs.value * 1.2f);
                Draw.icon(g, Icons.GEAR, 0, 0, 8, Draw.withAlpha(Draw.lerpColor(Theme.TEXT_DIM, 0xFFFFFF, Draw.clamp01(gs.value)), ra));
                g.pose().popMatrix();
                gearRects.put(m, new float[]{gxx, gyy, 12, 12});
            }
            if (search) {
                String cat = m.category.label;
                Draw.text(g, cat, rx + sw - 30 - gearW - Draw.width(cat, 0.8f), ry + 5.6f, 0.8f, Draw.withAlpha(Theme.TEXT_MUTED, ra));
            }
            Draw.toggle(g, rx + sw - 26, ry + (ROW_H - 10.5f) / 2, kn.value, kn.velocity, ra);
        }
        if (mods.isEmpty()) {
            Draw.centered(g, "No modules", x + w / 2, listY + 10, 1f, Draw.withAlpha(Theme.TEXT_DIM, alpha));
        }
        g.disableScissor();
        // fondu en haut / en bas de la liste quand elle défile
        if (sc > 0.5f) g.fillGradient((int) innerX, (int) listY, (int) (innerX + innerW), (int) (listY + 8),
                Draw.withAlpha(Theme.PANEL, 0.9f * alpha), Theme.PANEL & 0xFFFFFF);
        if (sc < maxScroll - 0.5f) g.fillGradient((int) innerX, (int) (listY + listH - 10), (int) (innerX + innerW), (int) Math.ceil(listY + listH),
                Theme.PANEL & 0xFFFFFF, Draw.withAlpha(Theme.PANEL, 0.95f * alpha));

        if (maxScroll > 0) {
            float trackX = innerX + innerW - 2.5f;
            Draw.rrect(g, trackX, listY, 2.5f, listH, 1.25f, Draw.withAlpha(0xFFFFFF, 0.07f * alpha));
            float barH = Math.max(16, listH * listH / content);
            float barY = listY + (listH - barH) * (sc / maxScroll);
            Draw.rrect(g, trackX, barY, 2.5f, barH, 1.25f, Draw.withAlpha(Theme.accent(), 0.7f * alpha));
        }
        return pointer;
    }

    private boolean drawBottomBar(GuiGraphics g, int mx, int my, float dt, float in) {
        boolean pointer = false;
        float y = barY.value;
        float time = contentTime();
        for (int i = 0; i < BOTTOM_LABELS.length; i++) {
            float st = animated() ? Draw.easeOutCubic((time - 0.4f - i * 0.05f) / 0.5f) : 1;
            float a = in * st;
            if (a < 0.01f) continue;
            float x = barX(i), by = y + (1 - st) * 12;
            boolean hv = !closing && inside(mx, my, x, by, BTN_W, BTN_H);
            boolean boost = i == 5 && Boost.active;
            boolean active = (i == 1 && searching) || boost;
            pointer |= hv;
            bottomHover[i].update(dt, hv ? 1 : active ? 0.7f : 0);
            float h = Draw.clamp01(bottomHover[i].value);
            float s = 1 + 0.07f * bottomHover[i].value;
            g.pose().pushMatrix();
            g.pose().translate(x + BTN_W / 2, by + BTN_H / 2 - 1.5f * h);
            g.pose().scale(s, s);
            g.pose().translate(-(x + BTN_W / 2), -(by + BTN_H / 2));
            Draw.glow(g, x, by + 3, BTN_W, BTN_H, 10, Draw.withAlpha(0x000000, 0.45f * a));
            float pulse = boost ? 0.5f + 0.5f * (float) Math.sin(now() * 3.2f) : 0;
            Draw.glow(g, x, by, BTN_W, BTN_H, 8 + 4 * pulse, Draw.withAlpha(Theme.accent(), (0.28f * h + 0.35f * pulse) * a));
            int fill = boost ? Draw.lerpColor(Theme.tileHover(), Theme.accent(), 0.35f + 0.15f * pulse) : Draw.lerpColor(Theme.TILE, Theme.tileHover(), h);
            Draw.glass(g, x, by, BTN_W, BTN_H, 6.5f, Draw.withAlpha(fill, (0.66f + 0.16f * h + (boost ? 0.15f : 0)) * a),
                    Draw.withAlpha(boost ? Theme.accent() : 0xFFFFFF, (0.32f + 0.5f * h + (boost ? 0.3f : 0)) * a));
            // onde qui s'échappe du bouton Boost au clic
            float wave = (now() - boostClickAt) / 0.7f;
            if (i == 5 && wave >= 0 && wave < 1) {
                float e = Draw.easeOutCubic(wave), grow = 18 * e;
                Draw.ring(g, x - grow, by - grow, BTN_W + grow * 2, BTN_H + grow * 2, 6.5f + grow, Draw.withAlpha(Theme.accent(), (1 - wave) * 0.9f * a));
            }
            int fg = Draw.withAlpha(Theme.TEXT, a);
            Draw.icon(g, BOTTOM_ICONS[i], x + BTN_W / 2, by + 11 - h, 10, boost ? Draw.withAlpha(0xFFFFFF, a) : fg);
            Draw.centered(g, boost ? "Boost ON" : BOTTOM_LABELS[i], x + BTN_W / 2, by + 19.5f, 0.74f, fg);
            if (i == 2) {
                int n = gg.aurora.Friends.incoming().size() + gg.aurora.Friends.totalUnread();
                if (n > 0) {
                    float beat = 0.9f + 0.1f * (float) Math.sin(now() * 5);
                    float bs = 11 * beat, bxx = x + BTN_W - bs + 3, byy = by - 3;
                    Draw.glow(g, bxx, byy, bs, bs, 4, Draw.withAlpha(0xF2545B, 0.5f * a));
                    Draw.rrect(g, bxx, byy, bs, bs, bs / 2, Draw.withAlpha(0xF2545B, a));
                    Draw.centered(g, n > 9 ? "9+" : String.valueOf(n), bxx + bs / 2, byy + 2.2f, 0.65f, Draw.withAlpha(0xFFFFFF, a));
                }
            }
            g.pose().popMatrix();
        }
        return pointer;
    }

    private boolean drawBackButton(GuiGraphics g, int mx, int my, float dt, float in) {
        float a = Draw.clamp01((open() ? 1 : 0) - (1 - in)) * Draw.clamp01(Math.max(
                selected != null ? tiles[selected.ordinal()].alpha.value : 0, searchBox.alpha.value));
        float shown = Draw.easeOutCubic(Draw.clamp01((Math.max(selected != null ? tiles[selected.ordinal()].h.value : 0, searchBox.h.value) - TILE_H)
                / Math.max(1, panelH - TILE_H)));
        a *= shown;
        if (a < 0.01f) return false;
        float s = 20, x = width - 30, y = panelY + panelH / 2 - s / 2;
        boolean hv = inside(mx, my, x, y, s, s);
        backHover.update(dt, hv ? 1 : 0);
        float h = backHover.value;
        Draw.glow(g, x, y, s, s, 8, Draw.withAlpha(Theme.accent(), 0.28f * h * a));
        Draw.glass(g, x, y, s, s, s / 2, Draw.withAlpha(Draw.lerpColor(Theme.ROW, Theme.tileHover(), h), 0.85f * a), Draw.withAlpha(0xFFFFFF, (0.35f + 0.4f * h) * a));
        Draw.icon(g, Icons.CARET_DOWN, x + s / 2, y + s / 2 + 0.5f + h, 10, Draw.withAlpha(0xFFFFFF, a));
        return hv;
    }

    // ===================================================================== réglages d'un module

    private final Map<Module, Spring> gearHover = new HashMap<>();
    private final Map<Module, float[]> gearRects = new HashMap<>();
    private final Spring modalOpen = new Spring(0, 220, 0.78f);
    private Module modalModule;
    private boolean modalPointer;
    private final RowPanel modalRows = new RowPanel(() -> frameDt, new RowPanel.Callbacks() {
        public void pointer() {
            modalPointer = true;
        }

        public void sound(float pitch) {
            click(pitch);
        }

        public void changed() {
            Config.save();
        }
    });
    private float mX, mY, mW, mH;

    public void debugSettings(Module m) {
        openSettings(m);
        if (!modalRows.rows().isEmpty() && modalRows.rows().get(0) instanceof Rows.ColorRow c) c.expanded = true;
    }

    private void openSettings(Module m) {
        modalModule = m;
        java.util.List<Rows.Row> rows = new java.util.ArrayList<>();
        // widgets du HUD : aperçu en direct en haut des réglages
        if (m instanceof gg.aurora.module.HudModule h) rows.add(new HudPreviewRow(h));
        rows.addAll(m.settings());
        rows.add(new Rows.Button("Reset module", "Resets all its settings to default", Icons.ARROW_LEFT, () -> {
            m.opts.clear();
            if (m instanceof gg.aurora.module.HudModule h) h.resetStyle();
            Config.save();
            // reconstruit la liste après le clic (pas pendant son parcours)
            minecraft.execute(() -> {
                if (modalModule == m) openSettings(m);
            });
        }));
        modalRows.setRows(rows);
        click(1.15f);
    }

    private void closeSettings() {
        modalModule = null;
        Config.save();
    }

    private boolean drawModal(GuiGraphics g, int mx, int my, float dt) {
        modalOpen.update(dt, modalModule != null ? 1 : 0);
        float a = Draw.clamp01(modalOpen.value);
        if (a < 0.01f) return false;
        Module m = modalModule;
        modalPointer = false;
        g.fill(0, 0, width, height, Draw.withAlpha(0x05040C, 0.55f * a));
        float content = 0;
        if (m != null) for (Rows.Row r : modalRows.rows()) content += r.height() + 4;
        mW = Math.min(260, width - 40);
        mH = Math.min(height - 40, Math.max(90, 46 + content + 10));
        mX = (width - mW) / 2;
        mY = (height - mH) / 2 + (1 - modalOpen.value) * 24;
        float sc = 0.94f + 0.06f * modalOpen.value;
        g.pose().pushMatrix();
        g.pose().translate(width / 2f, height / 2f);
        g.pose().scale(sc, sc);
        g.pose().translate(-width / 2f, -height / 2f);
        Draw.glow(g, mX, mY + 6, mW, mH, 18, Draw.withAlpha(0x000000, 0.6f * a));
        Draw.glow(g, mX, mY, mW, mH, 10, Draw.withAlpha(Theme.accent(), 0.12f * a));
        Draw.glass(g, mX, mY, mW, mH, 10, Draw.withAlpha(Theme.PANEL, 0.97f * a), Draw.withAlpha(0xFFFFFF, 0.4f * a));
        if (m != null) {
            Draw.icon(g, m.icon, mX + 16, mY + 15, 11, Draw.withAlpha(Theme.accent(), a));
            Draw.text(g, m.name, mX + 28, mY + 10, 1.1f, Draw.withAlpha(Theme.TEXT, a));
            Draw.text(g, m.enabled ? "On" : "Off", mX + 28, mY + 21, 0.75f,
                    Draw.withAlpha(m.enabled ? Theme.accent() : Theme.TEXT_MUTED, a));
            boolean ch = inside(mx, my, mX + mW - 24, mY + 8, 16, 16);
            modalPointer |= ch;
            Draw.rrect(g, mX + mW - 24, mY + 8, 16, 16, 8, Draw.withAlpha(ch ? Theme.tileHover() : Theme.ROW, a));
            Draw.centered(g, "\u00D7", mX + mW - 16, mY + 11.5f, 1.1f, Draw.withAlpha(Theme.TEXT, a));
            Draw.rrect(g, mX + 10, mY + 33, mW - 20, 0.8f, 0.4f, Draw.withAlpha(0xFFFFFF, 0.08f * a));
            modalRows.render(g, mX + 4, mY + 36, mW - 8, mH - 42, mx, my, a);
        }
        g.pose().popMatrix();
        return modalPointer;
    }

    private void drawToast(GuiGraphics g, float dt) {
        if (toast == null) return;
        float age = (System.currentTimeMillis() - toastAt) / 1000f;
        if (age > 2.4f) {
            toast = null;
            return;
        }
        toastY.update(dt, age < 2.0f ? 1 : 0);
        float a = Draw.clamp01(toastY.value);
        float w = Draw.width(toast, 1f) + 20;
        float x = (width - w) / 2, y = height - 10 - 22 * toastY.value;
        Draw.glow(g, x, y, w, 17, 10, Draw.withAlpha(0x000000, 0.5f * a));
        Draw.glass(g, x, y, w, 17, 8.5f, Draw.withAlpha(Theme.PANEL, 0.95f * a), Draw.withAlpha(0xFFFFFF, 0.45f * a));
        Draw.text(g, toast, x + 10, y + 4.5f, 1f, Draw.withAlpha(Theme.TEXT, a));
    }

    // ===================================================================== interactions

    private static final float HEAD_H = 14;

    /** Position de chaque ligne dans la liste (en-têtes de groupe compris) ; dernière case = hauteur totale. */
    private float[] rowOffsets(List<Module> mods) {
        float[] ys = new float[mods.size() + 1];
        float y = 0;
        String last = null;
        for (int i = 0; i < mods.size(); i++) {
            Module m = mods.get(i);
            if (!searching && !m.group.equals(last)) {
                y += HEAD_H;
                last = m.group;
            }
            ys[i] = y;
            y += ROW_H + ROW_GAP;
        }
        ys[mods.size()] = Math.max(0, y - ROW_GAP);
        return ys;
    }

    private List<Module> visibleModules() {
        if (searching) {
            String q = query.trim().toLowerCase(Locale.ROOT);
            List<Module> out = new ArrayList<>();
            for (Module m : Modules.ALL) {
                if (q.isEmpty() || m.name.toLowerCase(Locale.ROOT).contains(q)
                        || m.category.label.toLowerCase(Locale.ROOT).contains(q)) out.add(m);
            }
            return out;
        }
        return selected == null ? List.of() : Modules.of(selected);
    }

    private void openCategory(Category c) {
        selected = c;
        searching = false;
        resetContent();
    }

    private void openSearch() {
        searching = true;
        selected = null;
        query = "";
        resetContent();
    }

    /** Utilisé par l'outil de captures de développement. */
    public void debugOpen(Category c) {
        openCategory(c);
    }

    public void debugSkip() {
        contentDelay = -10;
    }

    public void debugSearch(String q) {
        openSearch();
        query = q;
    }

    private void goHome() {
        selected = null;
        searching = false;
    }

    private void resetContent() {
        scroll.set(0);
        scrollTarget = 0;
        contentShownAt = System.nanoTime() - openedAt + 120_000_000L; // laisse le morph commencer
    }

    private void showToast(String text) {
        toast = text;
        toastAt = System.currentTimeMillis();
        toastY.set(0);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (closing || event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubleClick);
        double mx = event.x(), my = event.y();
        if (modalModule != null) {
            if (!inside(mx, my, mX, mY, mW, mH) || inside(mx, my, mX + mW - 24, mY + 8, 16, 16)) {
                click(0.9f);
                closeSettings();
                return true;
            }
            modalRows.click(mx, my);
            return true;
        }

        for (int i = 0; i < BOTTOM_LABELS.length; i++) {
            if (inside(mx, my, barX(i), barY.value, BTN_W, BTN_H)) {
                click(1f);
                bottomHover[i].velocity -= 14;
                if (i == 1) {
                    if (searching) goHome();
                    else openSearch();
                } else if (i == 0) {
                    minecraft.setScreen(new WardrobeScreen(this));
                } else if (i == 5) {
                    Boost.toggle();
                    boostClickAt = now();
                    showToast(Boost.active ? "Boost on: maximum performance settings" : "Boost off: settings restored");
                } else if (i == 2) {
                    minecraft.setScreen(new FriendsScreen(this));
                } else if (i == 4) {
                    minecraft.setScreen(new SettingsScreen(this));
                } else {
                    showToast(BOTTOM_LABELS[i] + " : coming soon");
                }
                return true;
            }
        }

        if (!open()) {
            for (int i = 0; i < CATS.length; i++) {
                if (tiles[i].contains(mx, my)) {
                    click(1f);
                    tiles[i].press.velocity += 10;
                    openCategory(CATS[i]);
                    return true;
                }
            }
            return super.mouseClicked(event, doubleClick);
        }

        float bs = 20, bx = width - 30, by = panelY + panelH / 2 - bs / 2;
        if (inside(mx, my, bx, by, bs, bs)) {
            click(0.9f);
            goHome();
            return true;
        }

        if (selected != null) {
            for (int i = 0; i < CATS.length; i++) {
                if (CATS[i] != selected && tiles[i].contains(mx, my)) {
                    click(1.05f);
                    openCategory(CATS[i]);
                    return true;
                }
            }
        }

        if (inside(mx, my, panelX, panelY, PANEL_W, panelH)) {
            float innerX = panelX + 9, innerW = PANEL_W - 18;
            float cursor = panelY + 44;
            if (searching) {
                cursor += 21;
            } else if (selected == Category.HUD) {
                if (inside(mx, my, innerX, cursor, innerW, 15)) {
                    click(1f);
                    minecraft.setScreen(new HudEditScreen(this));
                    return true;
                }
                cursor += 20;
            }
            float listH = panelY + panelH - 8 - cursor;
            if (my >= cursor && my < cursor + listH) {
                List<Module> mods = visibleModules();
                float[] ys = rowOffsets(mods);
                int idx = -1;
                for (int i = 0; i < mods.size(); i++) {
                    float ry = cursor + ys[i] - scroll.value;
                    if (my >= ry && my < ry + ROW_H) idx = i;
                }
                if (idx >= 0) {
                    {
                        Module m = mods.get(idx);
                        float[] gr = gearRects.get(m);
                        if (m.hasSettings() && gr != null && inside(mx, my, gr[0] - 2, gr[1] - 2, gr[2] + 4, gr[3] + 4)) {
                            openSettings(m);
                            return true;
                        }
                        m.toggle();
                        click(m.enabled ? 1.25f : 0.85f);
                        Config.save();
                    }
                }
            }
            return true;
        }

        goHome();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (modalModule != null) return modalRows.scroll(mx, my, sy) || true;
        if (open()) {
            scrollTarget -= (float) sy * (ROW_H + ROW_GAP) * 1.6f;
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (modalModule != null && modalRows.drag(event.x(), event.y())) return true;
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (modalRows.release()) return true;
        return super.mouseReleased(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (searching && event.isAllowedChatCharacter() && query.length() < 32) {
            query += event.codepointAsString();
            scrollTarget = 0;
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (modalModule != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) closeSettings();
            return true;
        }
        if (searching && key == GLFW.GLFW_KEY_BACKSPACE) {
            if (!query.isEmpty()) {
                boolean ctrl = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
                query = ctrl ? "" : query.substring(0, query.length() - 1);
                scrollTarget = 0;
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (open()) goHome();
            else onClose();
            return true;
        }
        if (key == GLFW.GLFW_KEY_RIGHT_SHIFT) {
            onClose();
            return true;
        }
        if (searching) return true; // les touches servent à la recherche
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        if (!animated()) {
            minecraft.setScreen(null);
            return;
        }
        closing = true;
        background.sink();
    }

    @Override
    public void removed() {
        Config.save();
    }

    private void click(float pitch) {
        if (!Settings.sounds) return;
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch, 0.3f));
    }

    private static boolean inside(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
