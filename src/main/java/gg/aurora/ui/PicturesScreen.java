package gg.aurora.ui;

import com.mojang.blaze3d.platform.NativeImage;
import gg.aurora.Friends;
import gg.aurora.Pictures;
import gg.aurora.Pictures.Shot;
import gg.aurora.Pictures.Tex;
import gg.aurora.Settings;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pictures : galerie des captures groupées par date, visionneuse avec actions (copier, partager, favori,
 * télécharger, retoucher, supprimer) et éditeur (dessin, recadrage, annuler / rétablir, enregistrer une copie).
 * Rail à droite : taille des miniatures et filtre favoris.
 */
public class PicturesScreen extends SubScreen {
    private enum Mode { GALLERY, VIEWER, EDITOR }

    private static final float RAIL = 26, GAP = 5;
    private static final int[] COLS = {5, 4, 3};
    private static final int[] PALETTE = {0xFFFFFF, 0x111118, 0xF04B4B, 0xF59B2F, 0xF2D64B, 0x5EE6A8, 0x4B8DF5, 0xE86BF0};
    private static final String[] TOOLS = {"Draw", "Crop"};
    private static final String[] SIZES = {"S", "M", "L", "XL"};
    private static final float[] BRUSH = {3, 7, 14, 26};

    private Mode mode = Mode.GALLERY;
    private List<Shot> shots = Pictures.all(true);
    private boolean onlyFavorites;
    private final Spring scroll = new Spring(0, 260, 1f);
    private float scrollTarget;
    private final List<Object[]> hits = new ArrayList<>(); // {x, y, w, h, Runnable}
    private final java.util.Map<Shot, Spring> hover = new java.util.HashMap<>();
    private long shownAt = System.nanoTime();

    // visionneuse
    private Shot current;
    private final Spring viewIn = new Spring(0, 220, 0.85f);
    private boolean sharing;
    private long confirmDeleteUntil;
    private String flash;
    private boolean flashError;
    private float flashAt = -10;

    // éditeur
    private Editor editor;
    private int tool, color = 2, brush = 1;
    private final Segmented toolSeg = new Segmented(), sizeSeg = new Segmented();
    private float ix, iy, iw, ih; // image affichée (visionneuse et éditeur)
    private float cropX0, cropY0, cropX1, cropY1; // en pixels image, -1 = pas de sélection
    private boolean cropDrag, painting;
    private float lastPx, lastPy;

    public PicturesScreen(Screen parent) {
        super(parent, "Pictures");
    }

    @Override
    protected float maxW() {
        return 600;
    }

    @Override
    protected float maxH() {
        return 340;
    }

    @Override
    public boolean consumesEscape() {
        return mode != Mode.GALLERY || sharing;
    }

    private void hit(float x, float y, float w, float h, Runnable r) {
        hits.add(new Object[]{x, y, w, h, r});
    }

    private void say(String text, boolean error) {
        flash = text;
        flashError = error;
        flashAt = time();
    }

    private List<Shot> visible() {
        if (!onlyFavorites) return shots;
        List<Shot> out = new ArrayList<>();
        for (Shot s : shots) if (Pictures.favorite(s)) out.add(s);
        return out;
    }

    // ======================================================================= rendu

    @Override
    protected void renderPage(GuiGraphics g, int mx, int my, float a) {
        hits.clear();
        if (mode == Mode.GALLERY) shots = Pictures.all(false);
        switch (mode) {
            case GALLERY -> gallery(g, mx, my, a);
            case VIEWER -> viewer(g, mx, my, a);
            case EDITOR -> editorView(g, mx, my, a);
        }
        if (sharing) sharePicker(g, mx, my, a);
        if (flash != null && time() - flashAt < 2.2f) {
            float e = Draw.clamp01(Math.min((time() - flashAt) * 6, (2.2f - (time() - flashAt)) * 4));
            float w = Draw.width(flash, 0.9f) + 20;
            float x = wx + (ww - w) / 2, y = wy + wh - 24 + (1 - e) * 6;
            Draw.rrect(g, x, y, w, 15, 7.5f, Draw.withAlpha(flashError ? 0x3A1C22 : Theme.ROW_HOVER, 0.97f * e));
            Draw.text(g, flash, x + 10, y + 4, 0.9f, Draw.withAlpha(flashError ? 0xF07878 : Theme.TEXT, e));
        }
    }

    private void header(GuiGraphics g, float a, String title, String sub) {
        float x = wx + 8 + (embedded ? 0 : Draw.width("Back", 1f) + 30);
        Draw.icon(g, Icons.IMAGE, x + 6, wy + 15, 10, Draw.withAlpha(Theme.accent(), a));
        Draw.text(g, title, x + 16, wy + 10, 1.05f, Draw.withAlpha(Theme.TEXT, a));
        if (sub != null) Draw.text(g, sub, x + 16 + Draw.width(title, 1.05f) + 8, wy + 12.5f, 0.72f, Draw.withAlpha(Theme.TEXT_MUTED, a));
    }

    /** Petit bouton d'action : icône + libellé. Retourne sa largeur. */
    private float action(GuiGraphics g, float x, float y, String icon, String label, boolean primary, boolean on, int mx, int my, float a, Runnable r) {
        float w = (label == null ? 0 : Draw.width(label, 0.8f) + 6) + 20, h = 16;
        boolean hv = inside(mx, my, x, y, w, h);
        int bg = primary ? Draw.lerpColor(Theme.accent(), 0xFFFFFF, hv ? 0.12f : 0) : hv ? Theme.ROW_HOVER : Theme.ROW;
        Draw.rrect(g, x, y, w, h, 4, Draw.withAlpha(bg, a));
        int fg = primary ? 0xFFFFFF : on ? Theme.accent() : Theme.TEXT;
        Draw.icon(g, icon, x + 10, y + h / 2, 9, Draw.withAlpha(fg, a));
        if (label != null) Draw.text(g, label, x + 18, y + 4, 0.8f, Draw.withAlpha(primary ? 0xFFFFFF : Theme.TEXT, a));
        hit(x, y, w, h, r);
        pointer |= hv;
        return w;
    }

    // ----------------------------------------------------------------------- galerie

    private void gallery(GuiGraphics g, int mx, int my, float a) {
        List<Shot> list = visible();
        int favCount = Pictures.favoriteCount();
        header(g, a, "Pictures", shots.size() + " pictures" + (favCount > 0 ? "  ·  " + favCount + " favorites" : ""));
        float bx = wx + ww - 10 - RAIL - 6;
        bx -= action(g, bx - (Draw.width("Open folder", 0.8f) + 26), wy + 8, Icons.FOLDER, "Open folder", false, false, mx, my, a, Pictures::openFolder) + 4;
        Draw.rrect(g, wx + 10, wy + 29, ww - 20, 0.8f, 0.4f, Draw.withAlpha(0xFFFFFF, 0.08f * a));

        // rail : taille des miniatures, favoris
        float rx = wx + ww - 10 - RAIL, ry = wy + 36;
        Draw.rrect(g, rx, ry, RAIL, wh - 46, 6, Draw.withAlpha(0x000000, 0.22f * a));
        String[] icons = {Icons.GRID_S, Icons.GRID_M, Icons.GRID_L};
        for (int i = 0; i < 3; i++) {
            int idx = i;
            float y = ry + 5 + i * 24;
            boolean on = Settings.shotThumb == i, hv = inside(mx, my, rx + 3, y, RAIL - 6, 20);
            if (on || hv) Draw.rrect(g, rx + 3, y, RAIL - 6, 20, 4, Draw.withAlpha(on ? Theme.ROW_HOVER : Theme.ROW, a));
            Draw.icon(g, icons[i], rx + RAIL / 2, y + 10, 10, Draw.withAlpha(on ? Theme.TEXT : Theme.TEXT_MUTED, a));
            hit(rx + 3, y, RAIL - 6, 20, () -> {
                if (Settings.shotThumb != idx) {
                    Settings.shotThumb = idx;
                    gg.aurora.Config.save();
                    click(1.05f);
                }
            });
            pointer |= hv;
        }
        Draw.rrect(g, rx + 6, ry + 78, RAIL - 12, 0.8f, 0.4f, Draw.withAlpha(0xFFFFFF, 0.1f * a));
        {
            float y = ry + 84;
            boolean hv = inside(mx, my, rx + 3, y, RAIL - 6, 20);
            if (onlyFavorites || hv) Draw.rrect(g, rx + 3, y, RAIL - 6, 20, 4, Draw.withAlpha(onlyFavorites ? Theme.ROW_HOVER : Theme.ROW, a));
            Draw.icon(g, onlyFavorites ? Icons.HEART_FILL : Icons.HEART, rx + RAIL / 2, y + 10, 10, Draw.withAlpha(onlyFavorites ? 0xF06A8A : Theme.TEXT_MUTED, a));
            hit(rx + 3, y, RAIL - 6, 20, () -> {
                onlyFavorites = !onlyFavorites;
                scrollTarget = 0;
                click(onlyFavorites ? 1.15f : 0.9f);
            });
            pointer |= hv;
        }

        // grille groupée
        float gx = wx + 10, gy = wy + 36, gw = ww - 20 - RAIL - 8, gh = wh - 46;
        if (list.isEmpty()) {
            Draw.centered(g, onlyFavorites ? "No favorites yet" : "No pictures yet", gx + gw / 2, gy + gh / 2 - 8, 1f, Draw.withAlpha(Theme.TEXT_DIM, a));
            Draw.centered(g, onlyFavorites ? "Open a picture and press the heart" : "Press F2 in game to take one", gx + gw / 2, gy + gh / 2 + 5, 0.8f,
                    Draw.withAlpha(Theme.TEXT_MUTED, a));
            return;
        }
        int cols = COLS[Math.max(0, Math.min(2, Settings.shotThumb))];
        float tw = (gw - (cols - 1) * GAP) / cols, th = tw * 9 / 16;
        float t = (System.nanoTime() - shownAt) / 1e9f;
        g.enableScissor((int) gx, (int) gy, (int) (gx + gw), (int) (gy + gh));
        float y = gy - scroll.value;
        int index = 0;
        for (Pictures.Group grp : Pictures.groups(list)) {
            if (y + 14 > gy && y < gy + gh) {
                Draw.text(g, grp.title().toUpperCase(java.util.Locale.ROOT), gx + 1, y + 2, 0.68f, Draw.withAlpha(Theme.TEXT_MUTED, a));
                String n = String.valueOf(grp.shots().size());
                Draw.text(g, n, gx + 1 + Draw.width(grp.title().toUpperCase(java.util.Locale.ROOT), 0.68f) + 5, y + 2, 0.68f, Draw.withAlpha(Theme.TEXT_MUTED, 0.6f * a));
            }
            y += 14;
            int rows = (grp.shots().size() + cols - 1) / cols;
            for (int i = 0; i < grp.shots().size(); i++) {
                Shot s = grp.shots().get(i);
                float tx = gx + (i % cols) * (tw + GAP), ty = y + (i / cols) * (th + GAP);
                index++;
                if (ty + th < gy || ty > gy + gh) continue;
                float in = animated() ? Draw.easeOutCubic(Draw.clamp01((t - index * 0.012f) / 0.25f)) : 1;
                float ca = a * in;
                boolean hv = inside(mx, my, tx, ty, tw, th) && inside(mx, my, gx, gy, gw, gh) && !sharing;
                Spring hs = hover.computeIfAbsent(s, k -> new Spring(0, 320, 1f));
                hs.update(dt, hv ? 1 : 0);
                pointer |= hv;
                tile(g, s, tx, ty + (1 - in) * 4, tw, th, ca, hs.value);
                boolean fav = Pictures.favorite(s);
                if (hs.value > 0.02f || fav) {
                    float hx = tx + tw - 13, hy = ty + 3;
                    boolean hh = hv && inside(mx, my, hx - 2, hy - 2, 14, 14);
                    Draw.icon(g, fav ? Icons.HEART_FILL : Icons.HEART, hx + 5, hy + 5, 8, Draw.withAlpha(fav ? 0xF06A8A : 0xFFFFFF, (fav ? 1 : 0.8f * hs.value) * ca));
                    if (hh) hit(hx - 2, hy - 2, 14, 14, () -> {
                        Pictures.setFavorite(s, !Pictures.favorite(s));
                        click(Pictures.favorite(s) ? 1.3f : 0.9f);
                    });
                }
                hit(tx, Math.max(gy, ty), tw, Math.min(th, gy + gh - ty), () -> open(s));
            }
            y += rows * (th + GAP) + 6;
        }
        g.disableScissor();
        float content = y + scroll.value - gy;
        scrollTarget = Math.max(0, Math.min(Math.max(0, content - gh), scrollTarget));
        scroll.update(dt, scrollTarget);
        if (content > gh) {
            float barH = Math.max(14, gh * gh / content), by = gy + (gh - barH) * (scroll.value / (content - gh));
            Draw.rrect(g, gx + gw + 2, by, 2, barH, 1, Draw.withAlpha(0xFFFFFF, 0.18f * a));
        }
    }

    /** Miniature ajustée dans la tuile (bandes si le format diffère). */
    private void tile(GuiGraphics g, Shot s, float x, float y, float w, float h, float a, float hv) {
        Draw.rrect(g, x, y, w, h, 3, Draw.withAlpha(0x000000, 0.35f * a));
        Tex t = Pictures.thumb(s);
        if (t == null) {
            Draw.rrect(g, x, y, w, h, 3, Draw.withAlpha(Theme.ROW, 0.6f * a));
        } else if (t.id() == null) {
            Draw.centered(g, "Unreadable", x + w / 2, y + h / 2 - 4, 0.7f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        } else {
            float k = Math.min(w / t.w(), h / t.h()), dw = t.w() * k, dh = t.h() * k;
            Draw.image(g, t.id(), x + (w - dw) / 2, y + (h - dh) / 2, dw, dh, t.w(), t.h(), 0, 0, t.w(), t.h(), Draw.withAlpha(0xFFFFFF, (0.88f + 0.12f * hv) * a));
        }
        if (hv > 0.02f) Draw.ring(g, x, y, w, h, 3, Draw.withAlpha(0xFFFFFF, 0.5f * hv * a));
    }

    private void open(Shot s) {
        current = s;
        mode = Mode.VIEWER;
        viewIn.set(0);
        confirmDeleteUntil = 0;
        click(1.1f);
    }

    // ----------------------------------------------------------------------- visionneuse

    private void back() {
        if (mode == Mode.EDITOR) {
            if (editor != null) editor.close();
            editor = null;
            mode = Mode.VIEWER;
            viewIn.set(0.6f);
        } else {
            mode = Mode.GALLERY;
            shownAt = System.nanoTime();
        }
        sharing = false;
        click(0.9f);
    }

    private float backButton(GuiGraphics g, int mx, int my, float a) {
        float x = wx + 8, y = wy + 8, w = Draw.width("Back", 0.85f) + 24;
        boolean hv = inside(mx, my, x, y, w, 16);
        Draw.rrect(g, x, y, w, 16, 4, Draw.withAlpha(hv ? Theme.ROW_HOVER : Theme.ROW, a));
        Draw.icon(g, Icons.ARROW_LEFT, x + 9, y + 8, 8, Draw.withAlpha(Theme.TEXT, a));
        Draw.text(g, "Back", x + 17, y + 4, 0.85f, Draw.withAlpha(Theme.TEXT, a));
        hit(x, y, w, 16, this::back);
        pointer |= hv;
        return w;
    }

    /** Cadre de l'image dans la zone (x, y, w, h) en gardant ses proportions. */
    private void fit(float x, float y, float w, float h, int pw, int ph) {
        float k = Math.min(w / pw, h / ph);
        iw = pw * k;
        ih = ph * k;
        ix = x + (w - iw) / 2;
        iy = y + (h - ih) / 2;
    }

    private void viewer(GuiGraphics g, int mx, int my, float a) {
        viewIn.update(dt, 1);
        float in = Draw.clamp01(viewIn.value);
        float ca = a * in;
        List<Shot> list = visible();
        int idx = list.indexOf(current);
        float bw = backButton(g, mx, my, a);
        String name = current.name();
        Draw.text(g, name, wx + 8 + bw + 8, wy + 9, 0.95f, Draw.withAlpha(Theme.TEXT, a));
        Draw.text(g, current.date() + (idx >= 0 ? "  ·  " + (idx + 1) + " / " + list.size() : ""), wx + 8 + bw + 8, wy + 19, 0.7f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        Draw.rrect(g, wx + 10, wy + 29, ww - 20, 0.8f, 0.4f, Draw.withAlpha(0xFFFFFF, 0.08f * a));

        float ax = wx + 10, ay = wy + 35, aw = ww - 20, ah = wh - 35 - 34;
        Tex t = Pictures.full(current);
        Tex th = t == null ? Pictures.thumb(current) : null;
        Tex use = t != null && t.id() != null ? t : th != null && th.id() != null ? th : null;
        if (use != null) {
            fit(ax + 18, ay, aw - 36, ah, use.w(), use.h());
            float lift = (1 - in) * 6;
            Draw.glow(g, ix, iy + 3 + lift, iw, ih, 10, Draw.withAlpha(0x000000, 0.4f * ca));
            Draw.image(g, use.id(), ix, iy + lift, iw, ih, use.w(), use.h(), 0, 0, use.w(), use.h(), Draw.withAlpha(0xFFFFFF, ca));
        } else if (t != null) {
            Draw.centered(g, "This picture can't be read", ax + aw / 2, ay + ah / 2 - 4, 0.9f, Draw.withAlpha(Theme.TEXT_DIM, a));
        }
        // précédent / suivant
        if (idx > 0) arrow(g, ax, ay + ah / 2 - 10, false, mx, my, a, () -> open(list.get(idx - 1)));
        if (idx >= 0 && idx < list.size() - 1) arrow(g, ax + aw - 14, ay + ah / 2 - 10, true, mx, my, a, () -> open(list.get(idx + 1)));

        // barre d'actions
        boolean fav = Pictures.favorite(current);
        String[] labels = {"Copy", "Share", fav ? "Favorite" : "Favorite", "Download", "Edit", confirmDeleteUntil > System.currentTimeMillis() ? "Sure?" : "Delete"};
        String[] icons = {Icons.COPY, Icons.SHARE, fav ? Icons.HEART_FILL : Icons.HEART, Icons.DOWNLOAD, Icons.PENCIL, Icons.TRASH};
        float total = 0;
        for (String l : labels) total += Draw.width(l, 0.8f) + 26 + 4;
        float x = wx + (ww - total) / 2, y = wy + wh - 26;
        Shot cur = current;
        Runnable[] acts = {
                () -> {
                    Pictures.copy(cur);
                    say("Copied to clipboard", false);
                },
                () -> {
                    sharing = true;
                    click(1.05f);
                },
                () -> {
                    Pictures.setFavorite(cur, !Pictures.favorite(cur));
                    click(Pictures.favorite(cur) ? 1.3f : 0.9f);
                },
                () -> Pictures.download(cur),
                this::edit,
                () -> {
                    long now = System.currentTimeMillis();
                    if (confirmDeleteUntil > now) {
                        confirmDeleteUntil = 0;
                        if (Pictures.delete(cur)) {
                            shots = Pictures.all(true);
                            List<Shot> v = visible();
                            if (v.isEmpty()) back();
                            else current = v.get(Math.min(Math.max(0, idx), v.size() - 1));
                            say("Picture deleted", false);
                        }
                    } else {
                        confirmDeleteUntil = now + 3000;
                        click(0.8f);
                    }
                }};
        for (int i = 0; i < labels.length; i++) {
            boolean on = i == 2 && fav;
            x += action(g, x, y, icons[i], labels[i], false, on, mx, my, a, acts[i]) + 4;
        }
    }

    private void arrow(GuiGraphics g, float x, float y, boolean right, int mx, int my, float a, Runnable r) {
        boolean hv = inside(mx, my, x, y, 14, 20);
        Draw.rrect(g, x, y, 14, 20, 4, Draw.withAlpha(hv ? Theme.ROW_HOVER : Theme.ROW, 0.9f * a));
        g.pose().pushMatrix();
        g.pose().translate(x + 7, y + 10);
        if (right) g.pose().scale(-1, 1);
        Draw.icon(g, Icons.ARROW_LEFT, 0, 0, 9, Draw.withAlpha(Theme.TEXT, a));
        g.pose().popMatrix();
        hit(x, y, 14, 20, r);
        pointer |= hv;
    }

    /** Choix de l'ami à qui envoyer l'image (réduite à 1024 px). */
    private void sharePicker(GuiGraphics g, int mx, int my, float a) {
        List<Friends.Friend> friends = new ArrayList<>(Friends.all());
        friends.sort((p, q) -> Boolean.compare(q.online(), p.online()));
        float pw = 200, rowH = 20, ph = Math.min(wh - 40, 44 + Math.max(1, friends.size()) * rowH);
        float px = wx + (ww - pw) / 2, py = wy + (wh - ph) / 2;
        hit(wx, wy, ww, wh, () -> sharing = false); // clic à côté : ferme
        Draw.rrect(g, wx, wy, ww, wh, 10, Draw.withAlpha(0x000000, 0.45f * a));
        Draw.glow(g, px, py + 4, pw, ph, 14, Draw.withAlpha(0x000000, 0.5f * a));
        Draw.rrect(g, px, py, pw, ph, 8, Draw.withAlpha(Theme.PANEL, 0.98f * a));
        Draw.ring(g, px, py, pw, ph, 8, Draw.withAlpha(0xFFFFFF, 0.1f * a));
        Draw.icon(g, Icons.SHARE, px + 14, py + 13, 10, Draw.withAlpha(Theme.accent(), a));
        Draw.text(g, "Share with a friend", px + 24, py + 8.5f, 1f, Draw.withAlpha(Theme.TEXT, a));
        Draw.text(g, "Sent as a private message, 1024 px", px + 24, py + 19, 0.68f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        Draw.rrect(g, px + 8, py + 30, pw - 16, 0.8f, 0.4f, Draw.withAlpha(0xFFFFFF, 0.08f * a));
        hit(px, py, pw, ph, () -> {});
        if (friends.isEmpty()) {
            Draw.centered(g, Friends.connected ? "No friends yet" : "Not connected", px + pw / 2, py + 40, 0.85f, Draw.withAlpha(Theme.TEXT_DIM, a));
            return;
        }
        Shot cur = current;
        for (int i = 0; i < friends.size(); i++) {
            Friends.Friend f = friends.get(i);
            float y = py + 36 + i * rowH;
            if (y + rowH > py + ph - 4) break;
            boolean hv = inside(mx, my, px + 6, y, pw - 12, rowH - 2);
            if (hv) Draw.rrect(g, px + 6, y, pw - 12, rowH - 2, 4, Draw.withAlpha(Theme.ROW_HOVER, a));
            Draw.rrect(g, px + 13, y + rowH / 2 - 3, 4, 4, 2, Draw.withAlpha(f.online() ? 0x5EE6A8 : Theme.TEXT_MUTED, a));
            Draw.text(g, f.name(), px + 22, y + 5, 0.9f, Draw.withAlpha(Theme.TEXT, a));
            if (!f.online()) Draw.text(g, "offline", px + pw - 12 - Draw.width("offline", 0.68f), y + 6.5f, 0.68f, Draw.withAlpha(Theme.TEXT_MUTED, a));
            hit(px + 6, y, pw - 12, rowH - 2, () -> {
                sharing = false;
                say("Sending to " + f.name() + "…", false);
                Pictures.share(cur, f.id(), err -> say(err == null ? "Sent to " + f.name() : err, err != null));
                click(1.2f);
            });
            pointer |= hv;
        }
    }

    // ----------------------------------------------------------------------- éditeur

    private void edit() {
        try {
            editor = new Editor(current);
        } catch (Exception e) {
            say("This picture can't be edited", true);
            return;
        }
        mode = Mode.EDITOR;
        cropX0 = -1;
        click(1.1f);
    }

    private void editorView(GuiGraphics g, int mx, int my, float a) {
        float bw = backButton(g, mx, my, a);
        Draw.text(g, "Edit  ·  " + current.name(), wx + 8 + bw + 8, wy + 9, 0.95f, Draw.withAlpha(Theme.TEXT, a));
        Draw.text(g, editor.w + " × " + editor.h + (editor.ops.isEmpty() ? "" : "  ·  " + editor.ops.size() + " changes"), wx + 8 + bw + 8, wy + 19, 0.7f,
                Draw.withAlpha(Theme.TEXT_MUTED, a));
        // droite : annuler / rétablir, enregistrer
        float rx = wx + ww - 8;
        float sw = Draw.width("Save copy", 0.8f) + 26;
        rx -= sw;
        action(g, rx, wy + 8, Icons.CHECK, "Save copy", true, false, mx, my, a, this::save);
        rx -= 6 + 20;
        boolean canRedo = !editor.redo.isEmpty(), canUndo = !editor.ops.isEmpty();
        action(g, rx, wy + 8, Icons.REDO, null, false, false, mx, my, canRedo ? a : 0.4f * a, canRedo ? editor::redo : () -> {});
        rx -= 4 + 20;
        action(g, rx, wy + 8, Icons.UNDO, null, false, false, mx, my, canUndo ? a : 0.4f * a, canUndo ? editor::undo : () -> {});
        Draw.rrect(g, wx + 10, wy + 29, ww - 20, 0.8f, 0.4f, Draw.withAlpha(0xFFFFFF, 0.08f * a));

        // image
        float ax = wx + 10, ay = wy + 35, aw = ww - 20, ah = wh - 35 - 34;
        fit(ax, ay, aw, ah, editor.w, editor.h);
        editor.upload();
        Draw.glow(g, ix, iy + 3, iw, ih, 10, Draw.withAlpha(0x000000, 0.4f * a));
        Draw.image(g, editor.tex, ix, iy, iw, ih, editor.w, editor.h, 0, 0, editor.w, editor.h, Draw.withAlpha(0xFFFFFF, a));
        boolean overImg = inside(mx, my, ix, iy, iw, ih);
        if (tool == 1 && cropX0 >= 0) {
            float x0 = ix + Math.min(cropX0, cropX1) * iw / editor.w, x1 = ix + Math.max(cropX0, cropX1) * iw / editor.w;
            float y0 = iy + Math.min(cropY0, cropY1) * ih / editor.h, y1 = iy + Math.max(cropY0, cropY1) * ih / editor.h;
            int dim = Draw.withAlpha(0x000000, 0.55f * a);
            Draw.rect(g, ix, iy, iw, y0 - iy, dim);
            Draw.rect(g, ix, y1, iw, iy + ih - y1, dim);
            Draw.rect(g, ix, y0, x0 - ix, y1 - y0, dim);
            Draw.rect(g, x1, y0, ix + iw - x1, y1 - y0, dim);
            Draw.ring(g, x0, y0, x1 - x0, y1 - y0, 0.5f, Draw.withAlpha(0xFFFFFF, 0.9f * a));
            String sz = Math.round(Math.abs(cropX1 - cropX0)) + " × " + Math.round(Math.abs(cropY1 - cropY0));
            Draw.text(g, sz, x0 + 3, y0 + 3, 0.7f, Draw.withAlpha(0xFFFFFF, a));
        } else if (tool == 0 && overImg && !painting) {
            float r = BRUSH[brush] / 2 * iw / editor.w;
            Draw.ring(g, mx - r, my - r, r * 2, r * 2, r, Draw.withAlpha(0xFFFFFF, 0.8f * a));
        }
        if (overImg) pointer = true;

        // barre d'outils
        float y = wy + wh - 26, x = wx + 10;
        toolSeg.draw(g, x, y, 16, TOOLS, tool, mx, my, dt, a);
        x += toolSeg.width(TOOLS, 0.8f) + 10;
        if (tool == 0) {
            for (int i = 0; i < PALETTE.length; i++) {
                int ci = i;
                float cx = x + i * 15, cy = y + 8;
                boolean on = color == i, hv = inside(mx, my, cx - 7, cy - 7, 14, 14);
                Draw.rrect(g, cx - 5, cy - 5, 10, 10, 5, Draw.withAlpha(PALETTE[i], a));
                if (on) Draw.ring(g, cx - 7, cy - 7, 14, 14, 7, Draw.withAlpha(0xFFFFFF, 0.9f * a));
                else if (hv) Draw.ring(g, cx - 6.5f, cy - 6.5f, 13, 13, 6.5f, Draw.withAlpha(0xFFFFFF, 0.4f * a));
                hit(cx - 7, cy - 7, 14, 14, () -> {
                    color = ci;
                    click(1.05f);
                });
                pointer |= hv;
            }
            x += PALETTE.length * 15 + 4;
            sizeSeg.draw(g, x, y, 16, SIZES, brush, mx, my, dt, a);
            x += sizeSeg.width(SIZES, 0.8f) + 8;
            Draw.text(g, "Drag on the picture to draw", x, y + 4.5f, 0.7f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        } else {
            if (cropX0 >= 0 && Math.abs(cropX1 - cropX0) >= 2 && Math.abs(cropY1 - cropY0) >= 2) {
                x += action(g, x, y, Icons.CROP, "Apply crop", true, false, mx, my, a, this::applyCrop) + 4;
                x += action(g, x, y, Icons.ARROW_LEFT, "Clear", false, false, mx, my, a, () -> cropX0 = -1) + 8;
            }
            Draw.text(g, "Drag a rectangle on the picture", x, y + 4.5f, 0.7f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        }
    }

    private void applyCrop() {
        if (cropX0 < 0) return;
        int x0 = Math.round(Math.min(cropX0, cropX1)), y0 = Math.round(Math.min(cropY0, cropY1));
        int x1 = Math.round(Math.max(cropX0, cropX1)), y1 = Math.round(Math.max(cropY0, cropY1));
        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        x1 = Math.min(editor.w, x1);
        y1 = Math.min(editor.h, y1);
        if (x1 - x0 < 2 || y1 - y0 < 2) return;
        editor.push(new Editor.Crop(x0, y0, x1 - x0, y1 - y0));
        cropX0 = -1;
        click(1.15f);
    }

    private void save() {
        try {
            Shot s = Pictures.saveEdited(current, editor.w, editor.h, editor.cur);
            shots = Pictures.all(true);
            editor.close();
            editor = null;
            current = s;
            mode = Mode.VIEWER;
            viewIn.set(0.6f);
            say("Saved " + s.name(), false);
            click(1.25f);
        } catch (Exception e) {
            say("Could not save: " + e.getMessage(), true);
        }
    }

    private float imgX(double mx) {
        return (float) ((mx - ix) / iw * editor.w);
    }

    private float imgY(double my) {
        return (float) ((my - iy) / ih * editor.h);
    }

    /** Image de travail : original + liste d'opérations rejouables (annuler = rejouer sans la dernière). */
    static final class Editor {
        interface Op {}

        record Crop(int x, int y, int w, int h) implements Op {}

        static final class Stroke implements Op {
            final int color;
            final float radius;
            final List<float[]> pts = new ArrayList<>();

            Stroke(int color, float radius) {
                this.color = color;
                this.radius = radius;
            }
        }

        final int[] orig;
        final int ow, oh;
        int[] cur;
        int w, h;
        final List<Op> ops = new ArrayList<>(), redo = new ArrayList<>();
        final Identifier tex = Identifier.fromNamespaceAndPath("aurora", "dynamic/shot_edit");
        private NativeImage img;
        private int imgW, imgH;
        private int dx0 = Integer.MAX_VALUE, dy0 = Integer.MAX_VALUE, dx1 = -1, dy1 = -1;
        private boolean all = true;

        Editor(Shot s) throws java.io.IOException {
            int[] size = new int[2];
            orig = Pictures.pixels(s.path(), size);
            ow = size[0];
            oh = size[1];
            rebuild();
        }

        void rebuild() {
            cur = orig.clone();
            w = ow;
            h = oh;
            for (Op op : ops) apply(op);
            all = true;
        }

        private void apply(Op op) {
            if (op instanceof Crop c) {
                int[] n = new int[c.w * c.h];
                for (int y = 0; y < c.h; y++) System.arraycopy(cur, (c.y + y) * w + c.x, n, y * c.w, c.w);
                cur = n;
                w = c.w;
                h = c.h;
                all = true;
            } else if (op instanceof Stroke s) {
                for (int i = 0; i < s.pts.size(); i++) {
                    float[] p = s.pts.get(i), q = s.pts.get(Math.max(0, i - 1));
                    segment(q[0], q[1], p[0], p[1], s.radius, s.color);
                }
            }
        }

        void push(Op op) {
            ops.add(op);
            redo.clear();
            apply(op);
        }

        /** Trait en cours : ajoute un point et dessine seulement le nouveau segment. */
        void extend(Stroke s, float x, float y) {
            float[] last = s.pts.isEmpty() ? new float[]{x, y} : s.pts.get(s.pts.size() - 1);
            s.pts.add(new float[]{x, y});
            segment(last[0], last[1], x, y, s.radius, s.color);
        }

        void undo() {
            if (ops.isEmpty()) return;
            redo.add(ops.remove(ops.size() - 1));
            rebuild();
        }

        void redo() {
            if (redo.isEmpty()) return;
            Op op = redo.remove(redo.size() - 1);
            ops.add(op);
            apply(op);
        }

        private void segment(float x0, float y0, float x1, float y1, float r, int color) {
            float d = (float) Math.hypot(x1 - x0, y1 - y0);
            int steps = Math.max(1, (int) (d / Math.max(0.5f, r * 0.35f)));
            for (int i = 0; i <= steps; i++) {
                float t = steps == 0 ? 0 : i / (float) steps;
                disc(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, r, color);
            }
        }

        private void disc(float cx, float cy, float r, int color) {
            int x0 = Math.max(0, (int) Math.floor(cx - r)), x1 = Math.min(w - 1, (int) Math.ceil(cx + r));
            int y0 = Math.max(0, (int) Math.floor(cy - r)), y1 = Math.min(h - 1, (int) Math.ceil(cy + r));
            float r2 = r * r;
            int argb = 0xFF000000 | color;
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    float ddx = x + 0.5f - cx, ddy = y + 0.5f - cy;
                    if (ddx * ddx + ddy * ddy <= r2) cur[y * w + x] = argb;
                }
            }
            dx0 = Math.min(dx0, x0);
            dy0 = Math.min(dy0, y0);
            dx1 = Math.max(dx1, x1);
            dy1 = Math.max(dy1, y1);
        }

        /** Envoie au GPU ce qui a changé depuis la dernière image. */
        void upload() {
            var tm = net.minecraft.client.Minecraft.getInstance().getTextureManager();
            if (img == null || imgW != w || imgH != h) {
                if (img != null) tm.release(tex);
                img = new NativeImage(w, h, false);
                imgW = w;
                imgH = h;
                tm.register(tex, new DynamicTexture(() -> "Paranoia picture editor", img));
                all = true;
            }
            if (!all && dx1 < 0) return;
            int x0 = all ? 0 : dx0, y0 = all ? 0 : dy0, x1 = all ? w - 1 : dx1, y1 = all ? h - 1 : dy1;
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) img.setPixel(x, y, cur[y * w + x] | 0xFF000000);
            var t = tm.getTexture(tex);
            if (t instanceof DynamicTexture d) d.upload();
            all = false;
            dx0 = dy0 = Integer.MAX_VALUE;
            dx1 = dy1 = -1;
        }

        void close() {
            if (img != null) net.minecraft.client.Minecraft.getInstance().getTextureManager().release(tex);
            img = null;
        }
    }

    // ======================================================================= événements

    @Override
    protected boolean clickPage(double mx, double my) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Object[] h = hits.get(i);
            if (inside(mx, my, (float) h[0], (float) h[1], (float) h[2], (float) h[3])) {
                ((Runnable) h[4]).run();
                return true;
            }
        }
        if (mode == Mode.EDITOR && editor != null) {
            int ts = toolSeg.hit(mx, my);
            if (ts >= 0) {
                tool = ts;
                cropX0 = -1;
                click(1f);
                return true;
            }
            int ss = sizeSeg.hit(mx, my);
            if (ss >= 0 && tool == 0) {
                brush = ss;
                click(1f);
                return true;
            }
            if (inside(mx, my, ix, iy, iw, ih)) {
                float px = imgX(mx), py = imgY(my);
                if (tool == 0) {
                    Editor.Stroke s = new Editor.Stroke(PALETTE[color], BRUSH[brush] / 2);
                    editor.push(s);
                    editor.extend(s, px, py);
                    painting = true;
                    lastPx = px;
                    lastPy = py;
                } else {
                    cropX0 = cropX1 = Math.max(0, Math.min(editor.w, px));
                    cropY0 = cropY1 = Math.max(0, Math.min(editor.h, py));
                    cropDrag = true;
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (mode == Mode.EDITOR && editor != null) {
            if (painting && !editor.ops.isEmpty() && editor.ops.get(editor.ops.size() - 1) instanceof Editor.Stroke s) {
                float px = Math.max(-50, Math.min(editor.w + 50, imgX(event.x()))), py = Math.max(-50, Math.min(editor.h + 50, imgY(event.y())));
                if (Math.abs(px - lastPx) + Math.abs(py - lastPy) >= 0.5f) {
                    editor.extend(s, px, py);
                    lastPx = px;
                    lastPy = py;
                }
                return true;
            }
            if (cropDrag) {
                cropX1 = Math.max(0, Math.min(editor.w, imgX(event.x())));
                cropY1 = Math.max(0, Math.min(editor.h, imgY(event.y())));
                return true;
            }
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        painting = false;
        cropDrag = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (mode == Mode.GALLERY) {
            scrollTarget -= (float) sy * 30;
            return true;
        }
        if (mode == Mode.VIEWER && !sharing) {
            List<Shot> list = visible();
            int idx = list.indexOf(current);
            int to = idx - (int) Math.signum(sy);
            if (idx >= 0 && to >= 0 && to < list.size()) open(list.get(to));
            return true;
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        boolean ctrl = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0, shift = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (sharing) sharing = false;
            else if (mode == Mode.EDITOR && cropX0 >= 0) cropX0 = -1;
            else if (mode != Mode.GALLERY) back();
            else return super.keyPressed(event);
            return true;
        }
        if (mode == Mode.EDITOR && editor != null) {
            if (ctrl && key == GLFW.GLFW_KEY_Z) {
                if (shift) editor.redo();
                else editor.undo();
                return true;
            }
            if (ctrl && key == GLFW.GLFW_KEY_Y) {
                editor.redo();
                return true;
            }
            if (ctrl && key == GLFW.GLFW_KEY_S) {
                save();
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER && tool == 1) {
                applyCrop();
                return true;
            }
        }
        if (mode == Mode.VIEWER && !sharing) {
            List<Shot> list = visible();
            int idx = list.indexOf(current);
            if (key == GLFW.GLFW_KEY_LEFT && idx > 0) {
                open(list.get(idx - 1));
                return true;
            }
            if (key == GLFW.GLFW_KEY_RIGHT && idx >= 0 && idx < list.size() - 1) {
                open(list.get(idx + 1));
                return true;
            }
            if (key == GLFW.GLFW_KEY_F) {
                Pictures.setFavorite(current, !Pictures.favorite(current));
                click(Pictures.favorite(current) ? 1.3f : 0.9f);
                return true;
            }
            if (ctrl && key == GLFW.GLFW_KEY_C) {
                Pictures.copy(current);
                say("Copied to clipboard", false);
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public void removed() {
        if (editor != null) editor.close();
        super.removed();
    }

    // ----------------------------------------------------------------------- outils de dev

    public void debugOpen(int index) {
        List<Shot> v = visible();
        if (index < v.size()) open(v.get(index));
    }

    public void debugEdit() {
        if (current != null) edit();
    }

    public void debugStroke(float x0, float y0, float x1, float y1, int colorIndex, int size) {
        if (editor == null) return;
        color = colorIndex;
        brush = size;
        Editor.Stroke s = new Editor.Stroke(PALETTE[color], BRUSH[brush] / 2);
        editor.push(s);
        int n = 24;
        for (int i = 0; i <= n; i++) {
            float t = i / (float) n;
            editor.extend(s, editor.w * (x0 + (x1 - x0) * t), editor.h * (y0 + (y1 - y0) * t + (float) Math.sin(t * Math.PI * 2) * 0.05f));
        }
    }

    public void debugCrop(float x0, float y0, float x1, float y1) {
        if (editor == null) return;
        tool = 1;
        cropX0 = editor.w * x0;
        cropY0 = editor.h * y0;
        cropX1 = editor.w * x1;
        cropY1 = editor.h * y1;
    }

    public void debugShare() {
        sharing = true;
    }

    public void debugSize(int i) {
        Settings.shotThumb = i;
    }

    public UUID debugFirstFriend() {
        return Friends.all().isEmpty() ? null : Friends.all().get(0).id();
    }
}
