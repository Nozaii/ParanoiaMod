package gg.aurora.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Dessin lisse. Toutes les coordonnées sont en unités GUI (float) mais le rendu se fait
 * en pixels réels de l'écran : formes arrondies anticrénelées via des textures 9-slice filtrées,
 * icônes vectorielles, police TTF (Poppins).
 */
public final class Draw {
    private Draw() {}

    private static final Identifier FILL = tex("ui/fill"), RING = tex("ui/ring"), GLOW = tex("ui/glow");
    public static final FontDescription UI_FONT = new FontDescription.Resource(Identifier.fromNamespaceAndPath("aurora", "ui"));
    private static final FontDescription TITLE_FONT = new FontDescription.Resource(Identifier.fromNamespaceAndPath("aurora", "title"));
    private static final Map<String, Identifier> ICON_CACHE = new HashMap<>();

    static Identifier tex(String path) {
        return Identifier.fromNamespaceAndPath("aurora", "textures/" + path + ".png");
    }

    private static float gs() {
        return (float) Minecraft.getInstance().getWindow().getGuiScale();
    }

    // ================================================================ formes

    /** Rectangle arrondi plein, anticrénelé. */
    public static void rrect(GuiGraphics g, float x, float y, float w, float h, float r, int argb) {
        nine(g, FILL, 64, 24, x, y, w, h, r, argb);
    }

    /** Sharp rectangle snapped to real pixels. */
    public static void rect(GuiGraphics g, float x, float y, float w, float h, int argb) {
        push(g);
        float s = gs();
        g.fill(Math.round(x * s), Math.round(y * s), Math.round((x + w) * s), Math.round((y + h) * s), argb);
        g.pose().popMatrix();
    }

    /** Contour arrondi (épaisseur proportionnelle au rayon, ~1-2 px réels). */
    public static void ring(GuiGraphics g, float x, float y, float w, float h, float r, int argb) {
        nine(g, RING, 64, 24, x, y, w, h, r, argb);
    }

    /** Halo doux de taille spread autour du rectangle. */
    public static void glow(GuiGraphics g, float x, float y, float w, float h, float spread, int argb) {
        // halos discrets : la hiérarchie vient des surfaces et du texte, pas de la lumière
        argb = withAlpha(argb & 0xFFFFFF, (argb >>> 24) / 255f * 0.4f);
        if ((argb >>> 24) < 2) return;
        // texture : 24 de marge + rayon 12 = coin de 36 texels
        float m = spread;
        nine(g, GLOW, 96, 36, x - m, y - m, w + 2 * m, h + 2 * m, m * 1.5f, argb);
    }

    /** Minecraft-style raised box: flat fill, light top/left edge, dark bottom/right edge. */
    public static void bevel(GuiGraphics g, float x, float y, float w, float h, int fill, float a) {
        rect(g, x, y, w, h, withAlpha(fill & 0xFFFFFF, (fill >>> 24) / 255f * a));
        rect(g, x, y, w, 1, withAlpha(0xFFFFFF, 0.16f * a));
        rect(g, x, y + 1, 1, h - 2, withAlpha(0xFFFFFF, 0.10f * a));
        rect(g, x, y + h - 1, w, 1, withAlpha(0x000000, 0.55f * a));
        rect(g, x + w - 1, y, 1, h - 1, withAlpha(0x000000, 0.4f * a));
    }

    /** Sunken box (fields, lists): dark top/left edge, light bottom/right edge. */
    public static void inset(GuiGraphics g, float x, float y, float w, float h, int fill, float a) {
        rect(g, x, y, w, h, withAlpha(fill & 0xFFFFFF, (fill >>> 24) / 255f * a));
        rect(g, x, y, w, 1, withAlpha(0x000000, 0.55f * a));
        rect(g, x, y + 1, 1, h - 1, withAlpha(0x000000, 0.45f * a));
        rect(g, x, y + h - 1, w, 1, withAlpha(0xFFFFFF, 0.12f * a));
        rect(g, x + w - 1, y + 1, 1, h - 2, withAlpha(0xFFFFFF, 0.08f * a));
    }

    /** Inventory slot, as in vanilla containers. */
    public static void slot(GuiGraphics g, float x, float y, float s, float a) {
        rect(g, x, y, s, s, withAlpha(0x8B8B8B, a));
        rect(g, x, y, s - 1, 1, withAlpha(0x373737, a));
        rect(g, x, y, 1, s - 1, withAlpha(0x373737, a));
        rect(g, x + 1, y + s - 1, s - 1, 1, withAlpha(0xFFFFFF, a));
        rect(g, x + s - 1, y + 1, 1, s - 1, withAlpha(0xFFFFFF, a));
    }

    /** Vanilla Minecraft font with its drop shadow, snapped to GUI pixels (titles, tabs, buttons). */
    public static void mcText(GuiGraphics g, String s, float x, float y, int argb) {
        if ((argb >>> 24) < 4) return;
        g.drawString(font(), s, Math.round(x), Math.round(y), argb, true);
    }

    public static int mcWidth(String s) {
        return font().width(s);
    }

    /** Panneau "verre" : fond translucide + liseré. */
    public static void glass(GuiGraphics g, float x, float y, float w, float h, float r, int fill, int border) {
        rrect(g, x, y, w, h, r, fill);
        ring(g, x, y, w, h, r, withAlpha(border & 0xFFFFFF, (border >>> 24) / 255f * 0.5f));
    }

    /**
     * Arrondis sobres : les formes en pilule (interrupteurs, puces) gardent leur forme, les panneaux
     * et cartes passent à un petit rayon (style épuré, moins « client de cheat »).
     */
    public static float radiusScale = 0.4f;

    /**
     * Square mode (Minecraft look): while above 0, rounded shapes are drawn with sharp corners and
     * rings become 1 px frames. Screens enable it around their render with {@link #squareOn()}.
     */
    public static int square;

    public static void squareOn() {
        square++;
    }

    public static void squareOff() {
        if (square > 0) square--;
    }

    private static void nine(GuiGraphics g, Identifier tex, int size, int corner, float x, float y, float w, float h, float r, int argb) {
        if ((argb >>> 24) == 0 || w <= 0 || h <= 0) return;
        if (square > 0 && tex != GLOW) r = 0.01f;
        else if (tex != GLOW && r < Math.min(w, h) / 2 - 0.75f) r = Math.max(1f, r * radiusScale);
        push(g);
        float s = gs();
        int X = Math.round(x * s), Y = Math.round(y * s);
        int W = Math.max(1, Math.round((x + w) * s) - X), H = Math.max(1, Math.round((y + h) * s) - Y);
        int R = Math.max(1, Math.min(Math.round(r * s), Math.min(W, H) / 2));
        int mid = size - 2 * corner;
        int innerW = W - 2 * R, innerH = H - 2 * R;
        // coins
        blit(g, tex, X, Y, R, R, 0, 0, corner, corner, size, argb);
        blit(g, tex, X + W - R, Y, R, R, size - corner, 0, corner, corner, size, argb);
        blit(g, tex, X, Y + H - R, R, R, 0, size - corner, corner, corner, size, argb);
        blit(g, tex, X + W - R, Y + H - R, R, R, size - corner, size - corner, corner, corner, size, argb);
        // bords
        if (innerW > 0) {
            blit(g, tex, X + R, Y, innerW, R, corner, 0, mid, corner, size, argb);
            blit(g, tex, X + R, Y + H - R, innerW, R, corner, size - corner, mid, corner, size, argb);
        }
        if (innerH > 0) {
            blit(g, tex, X, Y + R, R, innerH, 0, corner, corner, mid, size, argb);
            blit(g, tex, X + W - R, Y + R, R, innerH, size - corner, corner, corner, mid, size, argb);
        }
        if (innerW > 0 && innerH > 0) blit(g, tex, X + R, Y + R, innerW, innerH, corner, corner, mid, mid, size, argb);
        g.pose().popMatrix();
    }

    private static void blit(GuiGraphics g, Identifier tex, int x, int y, int w, int h, int u, int v, int uw, int vh, int size, int argb) {
        g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, u, v, w, h, uw, vh, size, size, argb);
    }

    /** Passe en espace "pixels réels". */
    private static void push(GuiGraphics g) {
        g.pose().pushMatrix();
        float inv = 1f / gs();
        g.pose().scale(inv, inv);
    }

    /** Dessine une texture entière (étirée) en coordonnées GUI, au pixel réel près. */
    public static void image(GuiGraphics g, Identifier tex, float x, float y, float w, float h, int texW, int texH,
                             float u, float v, float uw, float vh, int argb) {
        if ((argb >>> 24) == 0) return;
        push(g);
        float s = gs();
        int X = Math.round(x * s), Y = Math.round(y * s);
        int W = Math.round((x + w) * s) - X, H = Math.round((y + h) * s) - Y;
        g.blit(RenderPipelines.GUI_TEXTURED, tex, X, Y, u, v, W, H, Math.round(uw), Math.round(vh), texW, texH, argb);
        g.pose().popMatrix();
    }

    /** Icône centrée en (cx, cy), taille en unités GUI. */
    public static void icon(GuiGraphics g, String name, float cx, float cy, float size, int argb) {
        if ((argb >>> 24) == 0) return;
        float s = gs();
        int px = Math.round(size * s);
        boolean small = px <= 36;
        Identifier id = ICON_CACHE.computeIfAbsent(name + (small ? "_s" : ""), n -> tex("icon/" + n));
        int ts = small ? 32 : 64;
        push(g);
        int X = Math.round(cx * s - px / 2f), Y = Math.round(cy * s - px / 2f);
        g.blit(RenderPipelines.GUI_TEXTURED, id, X, Y, 0, 0, px, px, ts, ts, ts, ts, argb);
        g.pose().popMatrix();
    }

    /** Interrupteur on/off. t = position animée (peut dépasser 0..1 avec le ressort), vel = vitesse (étirement). */
    public static void toggle(GuiGraphics g, float x, float y, float t, float vel, float alpha) {
        float w = 20, h = 10.5f;
        float c = clamp01(t);
        // piste : gris neutre à l'arrêt (pas de teinte accent en OFF), accent en ON ; la position du bouton suffit à lire l'état
        int bg = lerpColor(0x2B2A3C, Theme.accent(), c);
        rrect(g, x, y, w, h, h / 2, withAlpha(bg, alpha));
        if (c < 0.99f) ring(g, x, y, w, h, h / 2, withAlpha(0xFFFFFF, alpha * 0.10f * (1 - c)));
        // anneau unique au passage ON : visible seulement pendant le dépassement du ressort
        float over = clamp01((t - 1f) * 10f);
        if (over > 0.01f) ring(g, x - 2 * over, y - 2 * over, w + 4 * over, h + 4 * over, (h + 4 * over) / 2, withAlpha(Theme.accent(), 0.45f * (1 - over * 0.5f) * alpha));
        float knob = h - 3;
        float stretch = Math.min(3f, Math.abs(vel) * 0.3f);
        float kx = x + 1.5f + c * (w - knob - 3) - (vel > 0 ? stretch : 0);
        int kc = lerpColor(0xC9C9D8, 0xFFFFFF, c);
        rrect(g, kx, y + 1.5f, knob + stretch, knob, knob / 2, withAlpha(kc, alpha));
    }

    // ================================================================ texte

    /** Composants immuables mis en cache (textes de HUD et de menus redessinés à chaque image). */
    private static final java.util.HashMap<String, Component> UI_CACHE = new java.util.HashMap<>();

    public static Component ui(String s) {
        Component c = UI_CACHE.get(s);
        if (c != null) return c;
        if (UI_CACHE.size() > 1024) UI_CACHE.clear();
        c = Component.literal(s).withStyle(st -> st.withFont(UI_FONT));
        UI_CACHE.put(s, c);
        return c;
    }

    public static Component title(String s) {
        return Component.literal(s).withStyle(st -> st.withFont(TITLE_FONT));
    }

    private static Font font() {
        return Minecraft.getInstance().font;
    }

    public static float width(Component c, float scale) {
        return font().width(c) * scale;
    }

    public static float width(String s, float scale) {
        return width(ui(s), scale);
    }

    /** Texte à (x, y) en unités GUI, avec mise à l'échelle et position au pixel réel près. */
    public static void text(GuiGraphics g, Component c, float x, float y, float scale, int argb) {
        if ((argb >>> 24) < 4) return;
        float s = gs();
        g.pose().pushMatrix();
        g.pose().translate(Math.round(x * s) / s, Math.round(y * s) / s);
        g.pose().scale(scale, scale);
        g.drawString(font(), c, 0, 0, argb, false);
        g.pose().popMatrix();
    }

    public static void text(GuiGraphics g, String str, float x, float y, float scale, int argb) {
        text(g, ui(str), x, y, scale, argb);
    }

    public static void centered(GuiGraphics g, Component c, float cx, float y, float scale, int argb) {
        text(g, c, cx - width(c, scale) / 2f, y, scale, argb);
    }

    public static void centered(GuiGraphics g, String s, float cx, float y, float scale, int argb) {
        centered(g, ui(s), cx, y, scale, argb);
    }

    // ================================================================ couleurs / maths

    public static int lerpColor(int a, int b, float t) {
        t = clamp01(t);
        int ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255;
        int br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return (Math.round(ar + (br - ar) * t) << 16) | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }

    public static int withAlpha(int rgb, float alpha) {
        return (Math.round(clamp01(alpha) * 255) << 24) | (rgb & 0xFFFFFF);
    }

    public static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    public static float easeOutCubic(float t) {
        t = clamp01(t);
        return 1f - (1f - t) * (1f - t) * (1f - t);
    }

    public static float easeInOut(float t) {
        t = clamp01(t);
        return t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
    }

    /** Lissage exponentiel indépendant du framerate. */
    public static float smooth(float cur, float target, float speed, float dt) {
        if (!gg.aurora.Settings.anim()) return target;
        float k = 1f - (float) Math.exp(-speed * dt);
        float v = cur + (target - cur) * k;
        return Math.abs(target - v) < 0.0005f ? target : v;
    }
}
