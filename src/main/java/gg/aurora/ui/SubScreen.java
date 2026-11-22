package gg.aurora.ui;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import gg.aurora.Config;
import gg.aurora.Settings;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

/**
 * Base des pages (Paramètres, Wardrobe) : même fond que le menu, grande fenêtre en verre
 * qui monte en douceur, en-tête avec bouton retour. Échap / Retour referment vers le menu.
 */
public abstract class SubScreen extends Screen {
    protected final net.minecraft.client.gui.screens.Screen parent;
    protected final AuroraBackground background;

    protected final Spring open = new Spring(0, 150, 0.8f);
    protected final Spring backHover = new Spring(0, 320, 1f);
    private final long openedAt = System.nanoTime();
    private long lastFrame = System.nanoTime();
    private boolean closing;
    protected float dt;
    protected boolean pointer;

    // fenêtre (cible)
    protected float wx, wy, ww, wh;

    // page intégrée dans la coque du menu (ClickGui) : pas de fond, pas de bouton retour, la coque relaie les événements
    protected boolean embedded;
    protected Screen host;

    /** Intègre la page dans un écran hôte : elle se dessine dans le rectangle donné à chaque {@link #layout}. */
    public void embed(Screen host, net.minecraft.client.Minecraft mc, int width, int height) {
        this.host = host;
        this.embedded = true;
        init(width, height);
    }

    public void layout(float x, float y, float w, float h) {
        wx = x;
        wy = y;
        ww = w;
        wh = h;
    }

    public void renderEmbedded(GuiGraphics g, int mx, int my, float hostDt, float a) {
        dt = hostDt;
        pointer = false;
        renderPage(g, mx, my, a);
    }

    public boolean pointerWanted() {
        return pointer;
    }

    public boolean clickEmbedded(double mx, double my) {
        return clickPage(mx, my);
    }

    /** La coque quitte la page : même nettoyage qu'un écran retiré. */
    public void leaveEmbedded() {
        removed();
    }

    /** Écran parent à donner aux sous-écrans ouverts depuis la page (la coque quand la page y est intégrée). */
    protected Screen host() {
        return embedded ? host : this;
    }

    /** La page attend du texte (champ actif) : la coque ouvre la saisie clavier dans le monde. */
    public boolean typing() {
        return false;
    }

    /** Échap sert à la page (fermer une recherche, désélectionner) plutôt qu'à la coque. */
    public boolean consumesEscape() {
        return false;
    }

    protected SubScreen(net.minecraft.client.gui.screens.Screen parent, String title) {
        super(Component.literal(title));
        this.parent = parent;
        this.background = parent instanceof AuroraScreen a ? a.background() : new AuroraBackground();
        background.skipIntro();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    protected boolean animated() {
        return Settings.anim();
    }

    protected float time() {
        return (System.nanoTime() - openedAt) / 1e9f;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long t = System.nanoTime();
        float raw = Math.min(0.05f, (t - lastFrame) / 1e9f);
        lastFrame = t;
        dt = animated() ? raw * Settings.animFactor() : raw;
        background.update(dt);
        if (WorldPanel.rendering) return; // panneau dans le monde : pas de fond plein écran
        boolean inWorld = minecraft.level != null;
        if (inWorld && seeThrough()) { // verre dépoli : la vue du joueur reste là, floutée (pas de blocs pixelisés derrière le texte)
            renderBlurredBackground(g);
            g.fill(0, 0, width, height, Draw.withAlpha(0x05040C, 0.28f * Draw.clamp01(open.value)));
            return;
        }
        if (inWorld && Settings.blur) renderBlurredBackground(g);
        background.render(g, width, height, inWorld ? 0.8f : 1f, 0, 0);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        open.update(dt, closing ? 0 : 1);
        if (closing && (open.value < 0.04f || !animated())) {
            minecraft.setScreen(parent);
            return;
        }
        ww = Math.min(width - 32, maxW());
        wh = Math.min(height - 28, maxH());
        wx = (width - ww) / 2;
        wy = (height - wh) / 2;
        float a = Draw.clamp01(open.value);
        float lift = (1 - open.value) * 26;
        float sc = 0.96f + 0.04f * open.value;
        pointer = false;

        g.pose().pushMatrix();
        g.pose().translate(width / 2f, height / 2f + lift);
        g.pose().scale(sc, sc);
        g.pose().translate(-width / 2f, -height / 2f);

        if (!WorldPanel.rendering) { // dans le monde, la dalle 3D sert de fond
            Draw.glow(g, wx, wy + 6, ww, wh, 20, Draw.withAlpha(0x000000, 0.6f * a));
            Draw.glow(g, wx, wy, ww, wh, 12, Draw.withAlpha(Theme.accent(), 0.07f * a));
            boolean see = seeThrough() && minecraft.level != null;
            Draw.glass(g, wx, wy, ww, wh, see ? 14 : 10, Draw.withAlpha(Theme.PANEL, (see ? 0.72f : 0.88f) * a), Draw.withAlpha(0xFFFFFF, (see ? 0.14f : 0.38f) * a));
        }

        if (ownChrome()) {
            renderPage(g, mouseX, mouseY, a);
            g.pose().popMatrix();
            if (pointer) g.requestCursor(CursorTypes.POINTING_HAND);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }

        // en-tête : retour
        float bx = wx + 8, by = wy + 8, bw = Draw.width("Back", 1f) + 26, bh = 16;
        boolean bhv = !closing && inside(mouseX, mouseY, bx, by, bw, bh);
        backHover.update(dt, bhv ? 1 : 0);
        float h = backHover.value;
        Draw.glass(g, bx, by, bw, bh, 8, Draw.withAlpha(Draw.lerpColor(Theme.ROW, Theme.tileHover(), h), 0.9f * a),
                Draw.withAlpha(0xFFFFFF, (0.25f + 0.4f * h) * a));
        Draw.icon(g, Icons.ARROW_LEFT, bx + 10 - 1.5f * h, by + 8, 9, Draw.withAlpha(Theme.TEXT, a));
        Draw.text(g, "Back", bx + 18, by + 4.2f, 1f, Draw.withAlpha(Theme.TEXT, a));
        pointer |= bhv;

        renderPage(g, mouseX, mouseY, a);

        g.pose().popMatrix();
        if (pointer) g.requestCursor(CursorTypes.POINTING_HAND);
        super.render(g, mouseX, mouseY, partialTick);
    }

    /** La page dessine toute sa navigation (pas de bouton Back en haut à gauche). */
    protected boolean ownChrome() {
        return false;
    }

    /** Referme la page (même chemin que le bouton Back). */
    protected void leave() {
        click(0.9f);
        onClose();
    }

    /** En partie : fenêtre translucide sans flou ni fond étoilé, le monde se voit à travers. */
    protected boolean seeThrough() {
        return false;
    }

    /** Taille maximale de la fenêtre (unités GUI). */
    protected float maxW() {
        return 470;
    }

    protected float maxH() {
        return 290;
    }

    /** Fenêtre de la page (unités GUI), utilisée par le menu dans le monde. */
    public float[] panelRect() {
        return new float[]{wx, wy, ww, wh};
    }

    /** Contenu de la page, dans la fenêtre (wx, wy, ww, wh). L'en-tête occupe les 30 premières unités. */
    protected abstract void renderPage(GuiGraphics g, int mx, int my, float alpha);

    /** Clic dans la page (hors bouton retour). */
    protected abstract boolean clickPage(double mx, double my);

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (closing || event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubleClick);
        double mx = event.x(), my = event.y();
        float bw = Draw.width("Back", 1f) + 26;
        if (!ownChrome() && inside(mx, my, wx + 8, wy + 8, bw, 16)) {
            click(0.9f);
            onClose();
            return true;
        }
        if (clickPage(mx, my)) return true;
        return super.mouseClicked(event, doubleClick);
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
        Config.save();
        if (embedded) return; // la coque gère la navigation
        closing = true;
    }

    protected void click(float pitch) {
        if (!Settings.sounds) return;
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch, 0.3f));
    }

    protected static boolean inside(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** Onglets en pilule avec surbrillance qui glisse. Retourne l'index survolé ou -1. */
    protected int tabs(GuiGraphics g, String[] labels, int selected, float x, float y, Spring slide, int mx, int my, float a) {
        float pad = 9, h = 15, gap = 2;
        float[] xs = new float[labels.length], ws = new float[labels.length];
        float cx = x + 2;
        for (int i = 0; i < labels.length; i++) {
            xs[i] = cx;
            ws[i] = Draw.width(labels[i], 0.95f) + pad * 2;
            cx += ws[i] + gap;
        }
        float total = cx - x + 0;
        Draw.glass(g, x, y, total, h + 4, 9.5f, Draw.withAlpha(Theme.ROW, 0.85f * a), Draw.withAlpha(0xFFFFFF, 0.14f * a));
        slide.update(dt, selected);
        int lo = Math.max(0, Math.min(labels.length - 1, (int) Math.floor(slide.value)));
        int hi = Math.min(labels.length - 1, lo + 1);
        float f = Draw.clamp01(slide.value - lo);
        float sx = Draw.lerp(xs[lo], xs[hi], f), sw = Draw.lerp(ws[lo], ws[hi], f);
        Draw.glow(g, sx, y + 2, sw, h, 5, Draw.withAlpha(Theme.accent(), 0.25f * a));
        Draw.glass(g, sx, y + 2, sw, h, 7.5f, Draw.withAlpha(Theme.tileHover(), 0.95f * a), Draw.withAlpha(Theme.accent(), 0.7f * a));
        int hovered = -1;
        for (int i = 0; i < labels.length; i++) {
            boolean hv = inside(mx, my, xs[i], y + 2, ws[i], h);
            if (hv) hovered = i;
            int col = i == selected ? Theme.TEXT : hv ? Draw.lerpColor(Theme.TEXT_DIM, Theme.TEXT, 0.6f) : Theme.TEXT_DIM;
            Draw.text(g, labels[i], xs[i] + pad, y + 6.2f, 0.95f, Draw.withAlpha(col, a));
        }
        pointer |= hovered >= 0 && hovered != selected;
        return hovered;
    }
}
