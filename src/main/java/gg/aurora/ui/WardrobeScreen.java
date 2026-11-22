package gg.aurora.ui;

import gg.aurora.Capes;
import gg.aurora.cosmetic.Cosmetics;
import gg.aurora.Config;
import gg.aurora.Settings;
import gg.aurora.nametag.NameStyler;
import gg.aurora.nametag.NametagStyle;
import gg.aurora.nametag.NametagSync;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Wardrobe : aperçu du joueur (rotation à la souris), catégories, grille de capes à équiper. */
public class WardrobeScreen extends SubScreen {
    private static final String[] TABS = {"Wardrobe", "Store", "Rewards"};
    private static final String[][] CATEGORIES = {
            {"Capes", Icons.CAPE, ""}, {"Nametag", Icons.SPARKLES, ""}, {"Wings", Icons.WAND, ""},
            {"Auras", Icons.GEM, ""}, {"Head", Icons.SUN, ""}, {"Back", Icons.SWORD, ""}, {"Pets", Icons.SPARKLES, ""}, {"Shields", Icons.SHIELD, ""}};
    private static final String[] MODE_SHORT = {"Fixed", "Scroll", "Pulse", "Wave"};

    private int category;
    private float catTop, catX, catW, catRow = 21;
    private final float[] profileCard = new float[4];

    // éditeur de nametag
    private final ColorPicker picker = new ColorPicker();
    private int stop;
    private final RowPanel nameRows;
    private final List<float[]> presetRects = new ArrayList<>();
    private final List<float[]> chipRects = new ArrayList<>();
    private float addX = -1, addY, delX = -1, delY;
    private final Spring stopSlide = new Spring(0, 260, 0.7f);

    private int tab;
    private int tabHover = -1;
    private final Spring tabSlide = new Spring(0, 220, 0.75f);
    private long tabShownAt = System.nanoTime();

    private final Spring yaw = new Spring(200, 90, 0.9f);
    private final Spring zoom = new Spring(1, 160, 0.9f);
    private boolean draggingPreview;
    private final Spring scroll = new Spring(0, 260, 1f);
    private float scrollTarget;
    private String query = "";
    private boolean searchFocused;

    private final Map<String, Spring> cardHover = new HashMap<>();
    private final Map<String, Spring> cardPick = new HashMap<>();

    // zones (calculées au rendu)
    private float px, py, pw, ph;       // aperçu
    private float gx, gy, gw, gh;       // grille
    private float sbx, sby, sbw;        // recherche
    private final List<float[]> cardRects = new ArrayList<>();
    private static final String[] FILTERS = {"All", "Paranoia", "Official", "Mine"};
    private static final String[] WEAR = {"Cape", "Elytra"};
    private final float[] wearRect = new float[4], importRect = new float[4];
    private int filter;
    private final Spring filterSlide = new Spring(0, 260, 0.75f);
    private final List<float[]> filterRects = new ArrayList<>();
    private final List<String> cardIds = new ArrayList<>();
    private float toastAt = -10;
    private String toast;

    public WardrobeScreen(net.minecraft.client.gui.screens.Screen parent) {
        super(parent, "Wardrobe");
        Capes.scanImported();
        nameRows = new RowPanel(() -> dt, new RowPanel.Callbacks() {
            public void pointer() {
                pointer = true;
            }

            public void sound(float pitch) {
                click(pitch);
            }

            public void changed() {
                styleChanged();
            }
        });
        nameRows.gap = 3;
        nameRows.setRows(List.of(
                new Rows.Choice("", MODE_SHORT, () -> NametagStyle.own().modeIndex(),
                        i -> Settings.nametagMode = NametagStyle.MODES[i]),
                new Rows.Slider("Speed", null, 0.1, 4.0, 0.1, () -> Settings.nametagSpeed, v -> Settings.nametagSpeed = (float) v,
                        v -> String.format(Locale.ROOT, "%.1fx", v)),
                new Rows.Toggle("Logo", null, () -> Settings.nametagLogo, v -> Settings.nametagLogo = v),
                new Rows.Toggle("On", null, () -> Settings.nametagEnabled, v -> Settings.nametagEnabled = v)));
        picker.setColor(colors()[0]);
    }

    /** Type de cosmétique de la catégorie affichée (null = capes). */
    private Cosmetics.Type cosType() {
        return switch (category) {
            case 2 -> Cosmetics.Type.WINGS;
            case 3 -> Cosmetics.Type.AURA;
            case 4 -> Cosmetics.Type.HALO;
            case 5 -> Cosmetics.Type.BACK;
            case 6 -> Cosmetics.Type.PET;
            case 7 -> Cosmetics.Type.SHIELD;
            default -> null;
        };
    }

    private String selectedId() {
        Cosmetics.Type t = cosType();
        return t == null ? Settings.cape : Cosmetics.selected(t);
    }

    private String itemName(String id) {
        Cosmetics.Type t = cosType();
        if (t == null) {
            Capes.Cape c = Capes.byId(id);
            return c == null ? null : c.name();
        }
        Cosmetics.Item i = Cosmetics.find(t, id);
        return i == null ? null : i.name();
    }

    private Capes.Rarity itemRarity(String id) {
        Cosmetics.Type t = cosType();
        if (t == null) {
            Capes.Cape c = Capes.byId(id);
            return c == null ? null : c.rarity();
        }
        Cosmetics.Item i = Cosmetics.find(t, id);
        return i == null ? null : i.rarity();
    }

    private String categoryLabel() {
        return switch (category) {
            case 2 -> "Wings";
            case 3 -> "Aura";
            case 4 -> "Head";
            case 5 -> "Back";
            case 6 -> "Pet";
            case 7 -> "Shield";
            default -> "Cape";
        };
    }

    private int[] colors() {
        return NametagStyle.parseColors(Settings.nametagColors);
    }

    private void setColors(int[] c) {
        Settings.nametagColors = NametagStyle.toCsv(c);
        styleChanged();
    }

    private void styleChanged() {
        Config.save();
        NametagSync.markDirty();
    }

    private void selectStop(int i) {
        int[] c = colors();
        stop = Math.max(0, Math.min(c.length - 1, i));
        picker.setColor(c[stop]);
    }

    /** Changement de catégorie : seule la grille de droite se renouvelle (le reste ne bouge pas). */
    private long catShownAt = System.nanoTime() - 1_000_000_000L;

    public void debugCategory(int c) {
        category = c;
        catShownAt = System.nanoTime();
        scrollTarget = 0;
    }

    @Override
    protected void renderPage(GuiGraphics g, int mx, int my, float a) {
        float backW = embedded ? -8 : Draw.width("Back", 1f) + 26;
        tabHover = tabs(g, TABS, tab, wx + 8 + backW + 8, wy + 6, tabSlide, mx, my, a);

        // puce joueur à droite
        String name = minecraft.getUser().getName();
        float nw = Draw.width(name, 0.95f) + 30, nx = wx + ww - 8 - nw;
        Draw.glass(g, nx, wy + 8, nw, 16, 8, Draw.withAlpha(Theme.ROW, 0.9f * a), Draw.withAlpha(0xFFFFFF, 0.18f * a));
        if (minecraft.player != null) PlayerFaceRenderer.draw(g, minecraft.player.getSkin(), (int) (nx + 5), (int) (wy + 11), 10);
        else Draw.icon(g, Icons.PERSON, nx + 10, wy + 16, 9, Draw.withAlpha(Theme.accent(), a));
        Draw.text(g, name, nx + 19, wy + 12.2f, 0.95f, Draw.withAlpha(Theme.TEXT, a));
        Draw.rrect(g, wx + 10, wy + 31, ww - 20, 0.8f, 0.4f, Draw.withAlpha(0xFFFFFF, 0.08f * a));

        float t = (System.nanoTime() - tabShownAt) / 1e9f;
        float ca = a * (animated() ? Draw.easeOutCubic(t / 0.3f) : 1);
        float slide = animated() ? (1 - Draw.easeOutCubic(t / 0.35f)) * 10 : 0;
        if (tab == 0) wardrobe(g, mx, my, ca, slide, t);
        else placeholder(g, tab == 1 ? Icons.BAG : Icons.GIFT,
                tab == 1 ? "Store" : "Rewards",
                tab == 1 ? "Shop capes are coming soon." : "Daily streaks and goals coming soon.", ca, slide);

        if (toast != null && time() - toastAt < 1.8f) {
            float e = Draw.clamp01(Math.min((time() - toastAt) * 6, (1.8f - (time() - toastAt)) * 4));
            float w = Draw.width(toast, 0.95f) + 20;
            float x = wx + (ww - w) / 2, y = wy + wh - 26 + (1 - e) * 6;
            Draw.glow(g, x, y, w, 16, 8, Draw.withAlpha(Theme.accent(), 0.25f * e));
            Draw.glass(g, x, y, w, 16, 8, Draw.withAlpha(Theme.tileHover(), 0.95f * e), Draw.withAlpha(Theme.accent(), 0.7f * e));
            Draw.text(g, toast, x + 10, y + 4.4f, 0.95f, Draw.withAlpha(Theme.TEXT, e));
        }
    }

    private void placeholder(GuiGraphics g, String icon, String title, String text, float a, float slide) {
        float cxm = wx + ww / 2, cym = wy + 32 + (wh - 32) / 2 + slide;
        Draw.glow(g, cxm - 22, cym - 46, 44, 44, 14, Draw.withAlpha(Theme.accent(), 0.25f * a));
        Draw.glass(g, cxm - 22, cym - 46, 44, 44, 12, Draw.withAlpha(Theme.tileHover(), 0.9f * a), Draw.withAlpha(Theme.accent(), 0.6f * a));
        Draw.icon(g, icon, cxm, cym - 24, 20, Draw.withAlpha(0xFFFFFF, a));
        Draw.centered(g, title, cxm, cym + 6, 1.2f, Draw.withAlpha(Theme.TEXT, a));
        Draw.centered(g, text, cxm, cym + 22, 0.9f, Draw.withAlpha(Theme.TEXT_DIM, a));
    }

    private void wardrobe(GuiGraphics g, int mx, int my, float a, float slide, float t) {
        float top = wy + 38 + slide, bottom = wy + wh - 10;
        float lw = 104, rw = Math.min(170, ww * 0.36f);
        float lx = wx + 10, rx = wx + ww - 10 - rw;
        px = lx + lw + 8;
        pw = rx - 8 - px;
        py = top;
        ph = bottom - top;

        // ---- colonne gauche : profil + catégories
        Draw.rrect(g, lx, top, lw, ph, 8, Draw.withAlpha(0x000000, 0.18f * a));
        Draw.glass(g, lx + 5, top + 5, lw - 10, 26, 6, Draw.withAlpha(Theme.ROW, 0.9f * a), Draw.withAlpha(0xFFFFFF, 0.12f * a));
        if (minecraft.player != null) PlayerFaceRenderer.draw(g, minecraft.player.getSkin(), (int) (lx + 10), (int) (top + 10), 16);
        else Draw.icon(g, Icons.PERSON, lx + 18, top + 18, 12, Draw.withAlpha(Theme.accent(), a));
        Draw.text(g, minecraft.getUser().getName(), lx + 30, top + 9, 0.9f, Draw.withAlpha(Theme.TEXT, a));
        Draw.text(g, "Profile", lx + 30, top + 19, 0.75f, Draw.withAlpha(Theme.TEXT_DIM, a));
        profileCard[0] = lx + 5;
        profileCard[1] = top + 5;
        profileCard[2] = lw - 10;
        profileCard[3] = 26;
        pointer |= inside(mx, my, lx + 5, top + 5, lw - 10, 26);

        float cy = top + 38;
        catTop = cy;
        catX = lx + 5;
        catW = lw - 10;
        // lignes plus basses quand la fenêtre est petite (échelle d'interface 3), pour que tout tienne
        float rh = Math.max(13, Math.min(18, (bottom - cy - 4) / CATEGORIES.length - 3));
        catRow = rh + 3;
        for (int i = 0; i < CATEGORIES.length; i++) {
            String[] c = CATEGORIES[i];
            boolean active = i == category, locked = !c[2].isEmpty();
            if (!active && !locked && inside(mx, my, lx + 5, cy, lw - 10, rh)) {
                pointer = true;
                Draw.rrect(g, lx + 5, cy, lw - 10, rh, 6, Draw.withAlpha(Theme.ROW_HOVER, 0.8f * a));
            }
            if (active) {
                Draw.glass(g, lx + 5, cy, lw - 10, rh, 6, Draw.withAlpha(Theme.tileHover(), 0.9f * a), Draw.withAlpha(Theme.accent(), 0.5f * a));
            }
            int col = active ? Theme.TEXT : locked ? Theme.TEXT_MUTED : Theme.TEXT_DIM;
            Draw.icon(g, c[1], lx + 15, cy + rh / 2, 8, Draw.withAlpha(active ? Theme.accent() : col, a));
            Draw.text(g, c[0], lx + 24, cy + rh / 2 - 4, 0.88f, Draw.withAlpha(col, a));
            if (locked) Draw.icon(g, Icons.LOCK, lx + lw - 15, cy + rh / 2, 8, Draw.withAlpha(Theme.TEXT_MUTED, a));
            else if (i == 0 || i >= 2 && i <= 7) {
                Cosmetics.Type ct = i == 2 ? Cosmetics.Type.WINGS : i == 3 ? Cosmetics.Type.AURA : i == 4 ? Cosmetics.Type.HALO
                        : i == 5 ? Cosmetics.Type.BACK : i == 6 ? Cosmetics.Type.PET : Cosmetics.Type.SHIELD;
                int total = i == 0 ? Capes.ALL.size() : Cosmetics.of(ct).size();
                boolean on = i == 0 ? Capes.selected() != null : !Cosmetics.selected(ct).isEmpty();
                String count = (on ? 1 : 0) + "/" + total;
                Draw.text(g, count, lx + lw - 12 - Draw.width(count, 0.78f), cy + rh / 2 - 3.4f, 0.78f, Draw.withAlpha(Theme.TEXT_DIM, a));
            }
            cy += rh + 3;
        }

        if (category == 1) {
            nametag(g, mx, my, a, t, top, bottom, rx, rw);
            return;
        }

        // ---- centre : aperçu
        Draw.rrect(g, px, py, pw, ph, 8, Draw.withAlpha(0x000000, 0.22f * a));
        Draw.glow(g, px + pw * 0.2f, py + ph * 0.55f, pw * 0.6f, ph * 0.3f, 18, Draw.withAlpha(Theme.accent(), 0.10f * a));
        // titre masqué quand la colonne est trop étroite pour le titre et le sélecteur Cape / Elytra
        if (category != 0 || pw > 84 + 16 + Draw.width(CATEGORIES[0][0], 0.95f) + 8)
            Draw.text(g, CATEGORIES[category][0], px + 8, py + 7, 0.95f, Draw.withAlpha(Theme.TEXT, a));
        if (category == 0) {
            // what the pattern is worn on, previewed right here
            float sw = 84, sx = px + pw - sw - 6, sy = py + 5;
            wearRect[0] = sx;
            wearRect[1] = sy;
            wearRect[2] = sw;
            wearRect[3] = 14;
            Draw.rrect(g, sx, sy, sw, 14, 7, Draw.withAlpha(0x000000, 0.3f * a));
            int on = Settings.elytraCape ? 1 : 0;
            Draw.rrect(g, sx + on * sw / 2, sy, sw / 2, 14, 7, Draw.withAlpha(Theme.accent(), 0.9f * a));
            for (int i = 0; i < 2; i++) {
                boolean hv = inside(mx, my, sx + i * sw / 2, sy, sw / 2, 14);
                pointer |= hv && i != on;
                Draw.centered(g, WEAR[i], sx + i * sw / 2 + sw / 4, sy + 3.6f, 0.78f,
                        Draw.withAlpha(i == on ? 0xFFFFFF : hv ? Theme.TEXT : Theme.TEXT_DIM, a));
            }
        }
        if (!draggingPreview && animated()) yaw.target += dt * 12; // rotation lente au repos
        yaw.update(dt);
        zoom.update(dt);
        boolean overPreview = inside(mx, my, px, py, pw, ph);
        pointer |= overPreview;
        // socle
        float baseY = py + ph - 42;
        Draw.glow(g, px + pw / 2 - 30, baseY - 3, 60, 6, 10, Draw.withAlpha(Theme.accent(), 0.30f * a));
        Draw.rrect(g, px + pw / 2 - 30, baseY - 3, 60, 6, 3, Draw.withAlpha(Theme.accent(), 0.20f * a));

        LocalPlayer player = minecraft.player;
        if (player != null) {
            renderPlayer(g, player, (int) px, (int) (py + 14), (int) (px + pw), (int) baseY + 2, 38 * zoom.value, yaw.value);
        } else {
            flatCape(g, px + pw / 2, py + (ph - 42) / 2 + 8, a);
        }
        // carte d'info
        String selName = itemName(selectedId());
        Capes.Rarity selRarity = itemRarity(selectedId());
        float ib = py + ph - 30;
        Draw.glass(g, px + 6, ib, pw - 12, 24, 6, Draw.withAlpha(Theme.ROW, 0.9f * a), Draw.withAlpha(0xFFFFFF, 0.12f * a));
        Draw.text(g, categoryLabel(), px + 13, ib + 4, 0.75f, Draw.withAlpha(Theme.TEXT_DIM, a));
        String shownName = selName == null ? "None" : selName;
        if ("mini_skin".equals(selectedId()) && cosType() == Cosmetics.Type.PET) {
            shownName += " · " + (Settings.petSkin.isBlank() ? minecraft.getUser().getName() : Settings.petSkin) + " (click the card to edit)";
        }
        Draw.text(g, shownName, px + 13, ib + 13, 0.95f, Draw.withAlpha(Theme.TEXT, a));
        if (selRarity != null) {
            String r = selRarity.label;
            float rw2 = Draw.width(r, 0.75f) + 10;
            Draw.rrect(g, px + pw - 12 - rw2, ib + 8, rw2, 11, 5.5f, Draw.withAlpha(selRarity.color, 0.22f * a));
            Draw.text(g, r, px + pw - 7 - rw2, ib + 10.6f, 0.75f, Draw.withAlpha(selRarity.color, a));
        }
        String hint = player != null ? "Drag to rotate" : "3D preview in game";
        if (category != 0) Draw.text(g, hint, px + pw - 8 - Draw.width(hint, 0.72f), py + 8, 0.72f, Draw.withAlpha(Theme.TEXT_MUTED, a));

        // ---- droite : recherche + grille
        Draw.rrect(g, rx, top, rw, ph, 8, Draw.withAlpha(0x000000, 0.18f * a));
        sbx = rx + 5;
        sby = top + 5;
        sbw = rw - 10;
        Draw.glass(g, sbx, sby, sbw, 15, 7.5f, Draw.withAlpha(Theme.ROW, 0.95f * a),
                Draw.withAlpha(searchFocused ? Theme.accent() : 0xFFFFFF, (searchFocused ? 0.7f : 0.15f) * a));
        Draw.icon(g, Icons.SEARCH, sbx + 9, sby + 7.5f, 8, Draw.withAlpha(Theme.TEXT_DIM, a));
        boolean caret = searchFocused && (System.currentTimeMillis() / 530) % 2 == 0;
        if (query.isEmpty()) Draw.text(g, "Search", sbx + 17, sby + 4, 0.9f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        else Draw.text(g, query, sbx + 17, sby + 4, 0.9f, Draw.withAlpha(Theme.TEXT, a));
        if (caret) Draw.rrect(g, sbx + 17.5f + (query.isEmpty() ? 0 : Draw.width(query, 0.9f)), sby + 3.5f, 0.8f, 8, 0.4f, Draw.withAlpha(0xFFFFFF, a));
        pointer |= inside(mx, my, sbx, sby, sbw, 15);

        // filtre : toutes / Paranoia / officielles (capes uniquement)
        filterRects.clear();
        boolean capes = cosType() == null;
        float importW = 34, fx = rx + 5, fy = top + 24, fw = (rw - 10 - importW - 4) / FILTERS.length;
        if (capes) {
        importRect[0] = rx + rw - 5 - importW;
        importRect[1] = fy;
        importRect[2] = importW;
        importRect[3] = 14;
        boolean ih = inside(mx, my, importRect[0], fy, importW, 14);
        pointer |= ih;
        Draw.rrect(g, importRect[0], fy, importW, 14, 7, Draw.withAlpha(ih ? Theme.ROW_HOVER : Theme.ROW, 0.95f * a));
        Draw.centered(g, "Import", importRect[0] + importW / 2, fy + 3.6f, 0.78f, Draw.withAlpha(Theme.TEXT, a));
        if (ih) {
            String tip = "Click: file  ·  Ctrl+V: copied link (Tenor, Klipy, .gif, .png, .jpg)";
            float tw = Draw.width(tip, 0.72f) + 10, tx = Math.min(importRect[0] + importW - tw, wx + ww - tw - 6);
            Draw.rrect(g, tx, fy - 15, tw, 12, 3, Draw.withAlpha(0x0B0A12, 0.96f * a));
            Draw.text(g, tip, tx + 5, fy - 12, 0.72f, Draw.withAlpha(Theme.TEXT, a));
        }
        filterSlide.update(dt, filter);
        Draw.rrect(g, fx, fy, fw * FILTERS.length, 14, 7, Draw.withAlpha(0x000000, 0.25f * a));
        Draw.rrect(g, fx + filterSlide.value * fw, fy, fw, 14, 7, Draw.withAlpha(Theme.accent(), 0.9f * a));
        for (int i = 0; i < FILTERS.length; i++) {
            float bx = fx + i * fw;
            boolean hv = inside(mx, my, bx, fy, fw, 14);
            if (hv && i != filter) pointer = true;
            Draw.centered(g, FILTERS[i], bx + fw / 2, fy + 3.9f, 0.72f,
                    Draw.withAlpha(i == filter ? 0xFFFFFF : hv ? Theme.TEXT : Theme.TEXT_DIM, a));
            filterRects.add(new float[]{bx, fy, fw, 14});
        }
        }

        gx = rx + 5;
        gy = top + (capes ? 43 : 25);
        gw = rw - 10;
        gh = bottom - gy - 5;
        List<String> ids = new ArrayList<>();
        if ((query.isEmpty() || "none".contains(query.toLowerCase(Locale.ROOT))) && !(capes && filter == 3)) ids.add("");
        String q = query.toLowerCase(Locale.ROOT);
        if (capes) {
            for (Capes.Cape c : Capes.ALL) {
                if (filter == 1 && (c.official() || c.imported()) || filter == 2 && !c.official() || filter == 3 && !c.imported()) continue;
                if (c.name().toLowerCase(Locale.ROOT).contains(q)) ids.add(c.id());
            }
        } else {
            for (Cosmetics.Item c : cosType() == Cosmetics.Type.PET ? Cosmetics.petCards() : Cosmetics.of(cosType())) {
                if (c.name().toLowerCase(Locale.ROOT).contains(q)) ids.add(c.id());
            }
        }
        float cw = (gw - 5) / 2, chh = 82;
        int rows = (ids.size() + 1) / 2;
        float content = rows * (chh + 5) - 5;
        float maxScroll = Math.max(0, content - gh);
        scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
        scroll.update(dt, scrollTarget);

        cardRects.clear();
        cardIds.clear();
        g.enableScissor((int) gx - 3, (int) gy - 3, (int) Math.ceil(gx + gw + 3), (int) Math.ceil(gy + gh + 3));
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            float cx = gx + (i % 2) * (cw + 5), cyy = gy + (i / 2) * (chh + 5) - scroll.value;
            if (cyy + chh < gy - 4 || cyy > gy + gh + 4) {
                cardRects.add(new float[]{cx, cyy, cw, chh});
                cardIds.add(id);
                continue;
            }
            float tc = Math.min(t, (System.nanoTime() - catShownAt) / 1e9f);
            float e = animated() ? Draw.easeOutCubic((tc - Math.min(i, 8) * 0.015f) / 0.18f) : 1;
            card(g, id, cx, cyy + (1 - e) * 4, cw, chh, mx, my, a * e);
            cardRects.add(new float[]{cx, cyy, cw, chh});
            cardIds.add(id);
        }
        g.disableScissor();
        if (maxScroll > 0) {
            float tx = gx + gw + 1.5f;
            float barH = Math.max(14, gh * gh / content);
            Draw.rrect(g, tx, gy + (gh - barH) * (scroll.value / maxScroll), 2, barH, 1, Draw.withAlpha(Theme.accent(), 0.6f * a));
        }
    }

    private void card(GuiGraphics g, String id, float x, float y, float w, float h, int mx, int my, float a) {
        Cosmetics.Type type = cosType();
        if (type != null) {
            cosmeticCard(g, type, id, x, y, w, h, mx, my, a);
            if (type == Cosmetics.Type.PET && !id.isEmpty() && Cosmetics.family(id).size() > 1) {
                // variants: arrows on the card, one dot per variant
                var fam = Cosmetics.family(id);
                float cy = y + h / 2 - 8;
                for (int sd = 0; sd < 2; sd++) {
                    float ax = sd == 0 ? x + 3 : x + w - 15;
                    boolean hv = inside(mx, my, ax, cy, 12, 16) && my >= gy && my < gy + gh;
                    pointer |= hv;
                    Draw.rrect(g, ax, cy, 12, 16, 3, Draw.withAlpha(hv ? Theme.ROW_HOVER : 0x000000, (hv ? 0.95f : 0.35f) * a));
                    Draw.centered(g, sd == 0 ? "‹" : "›", ax + 6, cy + 4, 1f, Draw.withAlpha(Theme.TEXT, a));
                }
                float dw = fam.size() * 6 - 2, dx = x + w / 2 - dw / 2;
                for (int i = 0; i < fam.size(); i++) {
                    Draw.rrect(g, dx + i * 6, y + h - 21, 3, 3, 1.5f, Draw.withAlpha(fam.get(i).equals(id) ? Theme.accent() : 0xFFFFFF, (fam.get(i).equals(id) ? 1f : 0.25f) * a));
                }
            }
            return;
        }
        Capes.Cape cape = null;
        for (Capes.Cape c : Capes.ALL) if (c.id().equals(id)) cape = c;
        boolean selected = id.equals(Settings.cape);
        boolean hv = inside(mx, my, x, y, w, h) && my >= gy && my < gy + gh;
        pointer |= hv && !selected;
        Spring hs = cardHover.computeIfAbsent(id, k -> new Spring(0, 320, 0.65f));
        hs.update(dt, hv ? 1 : 0);
        Spring pick = cardPick.computeIfAbsent(id, k -> new Spring(0, 380, 0.45f));
        pick.update(dt, 0);
        float hvv = Draw.clamp01(hs.value);
        int rc = cape == null ? 0xFFFFFF : cape.rarity().color;

        float s = 1 + 0.035f * hs.value + 0.06f * pick.value;
        g.pose().pushMatrix();
        g.pose().translate(x + w / 2, y + h / 2 - 1.5f * hvv);
        g.pose().scale(s, s);
        g.pose().translate(-(x + w / 2), -(y + h / 2));
        if (selected) Draw.glow(g, x, y, w, h, 8, Draw.withAlpha(Theme.accent(), 0.35f * a));
        else if (hvv > 0.01f) Draw.glow(g, x, y, w, h, 8, Draw.withAlpha(rc, 0.18f * hvv * a));
        int fill = cape == null ? Draw.lerpColor(0x3A3550, 0x4A4466, hvv) : Draw.lerpColor(Theme.ROW, Draw.lerpColor(Theme.ROW, rc, 0.18f), hvv);
        Draw.glass(g, x, y, w, h, 7, Draw.withAlpha(fill, 0.95f * a),
                Draw.withAlpha(selected ? Theme.accent() : rc, (selected ? 0.95f : cape == null ? 0.2f : 0.45f + 0.35f * hvv) * a));
        if (cape == null) {
            Draw.centered(g, "None", x + w / 2, y + h / 2 - 4, 0.95f, Draw.withAlpha(Theme.TEXT, a));
        } else {
            String r = cape.rarity().label;
            float tw = Draw.width(r, 0.7f) + 8;
            Draw.rrect(g, x + 4, y + 4, tw, 10, 5, Draw.withAlpha(rc, 0.2f * a));
            Draw.ring(g, x + 4, y + 4, tw, 10, 5, Draw.withAlpha(rc, 0.6f * a));
            Draw.text(g, r, x + 8, y + 6.3f, 0.7f, Draw.withAlpha(rc, a));
            float aw = 26, ah = 41.6f;
            if (cape.ready()) {
                int k = cape.texW() / 64;
                Draw.image(g, cape.texture(), x + w / 2 - aw / 2, y + 17 - 1.5f * hvv, aw, ah, cape.texW(), cape.texH(),
                        k, k, 10 * k, 16 * k, Draw.withAlpha(0xFFFFFF, a));
            } else {
                // chargement depuis Mojang : petite roue qui tourne
                float ang = (System.currentTimeMillis() % 1000) / 1000f * 6.283f;
                for (int d = 0; d < 8; d++) {
                    float aa = ang + d * 0.785f;
                    Draw.rrect(g, x + w / 2 + (float) Math.cos(aa) * 7 - 1.2f, y + 38 + (float) Math.sin(aa) * 7 - 1.2f, 2.4f, 2.4f, 1.2f,
                            Draw.withAlpha(0xFFFFFF, a * (0.15f + 0.85f * d / 8f)));
                }
            }
            float ns = Math.min(0.9f, (w - 8) / Math.max(1, Draw.width(cape.name(), 1f)));
            Draw.centered(g, cape.name(), x + w / 2, y + h - 13, ns, Draw.withAlpha(Theme.TEXT, a));
        }
        if (selected) {
            Draw.rrect(g, x + w - 15, y + 4, 11, 11, 5.5f, Draw.withAlpha(Theme.accent(), a));
            Draw.icon(g, Icons.CHECK, x + w - 9.5f, y + 9.5f, 7, Draw.withAlpha(0xFFFFFF, a));
        }
        g.pose().popMatrix();
    }

    /** Carte d'un cosmétique (ailes, aura, halo) : aperçu de sa texture, rareté, nom. */
    private void cosmeticCard(GuiGraphics g, Cosmetics.Type type, String id, float x, float y, float w, float h, int mx, int my, float a) {
        Cosmetics.Item item = Cosmetics.find(type, id);
        boolean selected = id.equals(Cosmetics.selected(type));
        boolean hv = inside(mx, my, x, y, w, h) && my >= gy && my < gy + gh;
        pointer |= hv && !selected;
        String key = type + ":" + id;
        Spring hs = cardHover.computeIfAbsent(key, k -> new Spring(0, 320, 0.65f));
        hs.update(dt, hv ? 1 : 0);
        Spring pick = cardPick.computeIfAbsent(key, k -> new Spring(0, 380, 0.45f));
        pick.update(dt, 0);
        float hvv = Draw.clamp01(hs.value);
        int rc = item == null ? 0xFFFFFF : item.rarity().color;
        float s = 1 + 0.035f * hs.value + 0.06f * pick.value;
        g.pose().pushMatrix();
        g.pose().translate(x + w / 2, y + h / 2 - 1.5f * hvv);
        g.pose().scale(s, s);
        g.pose().translate(-(x + w / 2), -(y + h / 2));
        if (selected) Draw.glow(g, x, y, w, h, 8, Draw.withAlpha(Theme.accent(), 0.35f * a));
        else if (hvv > 0.01f) Draw.glow(g, x, y, w, h, 8, Draw.withAlpha(rc, 0.18f * hvv * a));
        int fill = item == null ? Draw.lerpColor(0x3A3550, 0x4A4466, hvv) : Draw.lerpColor(Theme.ROW, Draw.lerpColor(Theme.ROW, rc, 0.18f), hvv);
        Draw.glass(g, x, y, w, h, 7, Draw.withAlpha(fill, 0.95f * a),
                Draw.withAlpha(selected ? Theme.accent() : rc, (selected ? 0.95f : item == null ? 0.2f : 0.45f + 0.35f * hvv) * a));
        if (item == null) {
            Draw.centered(g, "None", x + w / 2, y + h / 2 - 4, 0.95f, Draw.withAlpha(Theme.TEXT, a));
        } else {
            String r = item.rarity().label;
            float tw = Draw.width(r, 0.7f) + 8;
            Draw.rrect(g, x + 4, y + 4, tw, 10, 5, Draw.withAlpha(rc, 0.2f * a));
            Draw.ring(g, x + 4, y + 4, tw, 10, 5, Draw.withAlpha(rc, 0.6f * a));
            Draw.text(g, r, x + 8, y + 6.3f, 0.7f, Draw.withAlpha(rc, a));
            float art = 40, cx = x + w / 2, cy = y + 39 - 1.5f * hvv;
            if (type == Cosmetics.Type.WINGS) {
                // les deux ailes, la gauche en miroir
                Draw.image(g, item.texture(), cx, cy - art / 2, art * 0.8f, art, 256, 256, 0, 0, 256, 256, Draw.withAlpha(0xFFFFFF, a));
                // aile gauche : même texture lue à l'envers (u de droite à gauche)
                Draw.image(g, item.texture(), cx - art * 0.8f, cy - art / 2, art * 0.8f, art, 256, 256, 256, 0, -256, 256, Draw.withAlpha(0xFFFFFF, a));
            } else {
                Draw.glow(g, cx - art / 3, cy - art / 3, art / 1.5f, art / 1.5f, 8, Draw.withAlpha(item.color(), 0.25f * a));
                g.pose().pushMatrix();
                g.pose().translate(cx, cy);
                if (type != Cosmetics.Type.PET && type != Cosmetics.Type.SHIELD && type != Cosmetics.Type.BACK
                        && !gg.aurora.cosmetic.Cosmetics.isHat(id)) g.pose().rotate((float) (time() * 0.8));
                Draw.image(g, item.texture(), -art / 2, -art / 2, art, art, 256, 256, 0, 0, 256, 256, Draw.withAlpha(0xFFFFFF, a));
                g.pose().popMatrix();
            }
            float ns = Math.min(0.9f, (w - 8) / Math.max(1, Draw.width(item.name(), 1f)));
            Draw.centered(g, item.name(), x + w / 2, y + h - 13, ns, Draw.withAlpha(Theme.TEXT, a));
        }
        if (selected) {
            Draw.rrect(g, x + w - 15, y + 4, 11, 11, 5.5f, Draw.withAlpha(Theme.accent(), a));
            Draw.icon(g, Icons.CHECK, x + w - 9.5f, y + 9.5f, 7, Draw.withAlpha(0xFFFFFF, a));
        }
        g.pose().popMatrix();
    }

    private void nametag(GuiGraphics g, int mx, int my, float a, float t, float top, float bottom, float rx, float rw) {
        NametagStyle style = NametagStyle.own();
        int[] cols = colors();
        if (stop >= cols.length) selectStop(cols.length - 1);
        if (picker.dragging()) {
            int c = picker.color();
            if (c != cols[stop]) {
                cols[stop] = c;
                Settings.nametagColors = NametagStyle.toCsv(cols);
            }
        }

        // ---- centre : aperçu + sélecteur de couleur
        Draw.rrect(g, px, py, pw, ph, 8, Draw.withAlpha(0x000000, 0.22f * a));
        Draw.text(g, "Nametag", px + 8, py + 7, 0.95f, Draw.withAlpha(Theme.TEXT, a));
        String hint = "In-game preview";
        Draw.text(g, hint, px + pw - 8 - Draw.width(hint, 0.72f), py + 8, 0.72f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        String name = minecraft.getUser().getName();
        var comp = net.minecraft.network.chat.Component.empty();
        if (Settings.nametagLogo) comp.append(NameStyler.logo());
        comp.append(Settings.nametagEnabled
                ? NameStyler.gradient(name, style, net.minecraft.network.chat.Style.EMPTY, NameStyler.time())
                : net.minecraft.network.chat.Component.literal(name));
        float sc = Math.min(2f, (pw - 24) / Math.max(1, Draw.width(comp, 1f)));
        float nw = Draw.width(comp, sc), nh = 9 * sc;
        float ncx = px + pw / 2, ncy = py + 38;
        Draw.glow(g, ncx - nw / 2, ncy - nh / 2, nw, nh, 14, Draw.withAlpha(style.colorAt(0.5f, NameStyler.time()), 0.18f * a));
        Draw.rrect(g, ncx - nw / 2 - 5, ncy - nh / 2 - 3, nw + 10, nh + 4, 3, Draw.withAlpha(0x000000, 0.35f * a));
        Draw.text(g, comp, ncx - nw / 2, ncy - nh / 2, sc, Draw.withAlpha(0xFFFFFF, a));

        float pkY = py + 62, pkH = ph - 62 - 30;
        picker.render(g, px + 10, pkY, pw - 20, pkH, a);
        float ib = py + ph - 24;
        Draw.rrect(g, px + 10, ib, 14, 14, 4, Draw.withAlpha(cols[stop], a));
        Draw.ring(g, px + 10, ib, 14, 14, 4, Draw.withAlpha(0xFFFFFF, 0.5f * a));
        Draw.text(g, "Color " + (stop + 1) + "  " + picker.hex(), px + 30, ib + 3.5f, 0.85f, Draw.withAlpha(Theme.TEXT, a));
        if (inside(mx, my, px + 8, pkY - 2, pw - 16, pkH + 4)) pointer = true;

        // ---- droite : préréglages, couleurs, réglages
        Draw.rrect(g, rx, top, rw, ph, 8, Draw.withAlpha(0x000000, 0.18f * a));
        Draw.text(g, "Presets", rx + 8, top + 6, 0.8f, Draw.withAlpha(Theme.TEXT_DIM, a));
        presetRects.clear();
        float pwid = (rw - 15) / 2, phei = 15;
        for (int i = 0; i < NametagStyle.PRESETS.length; i++) {
            NametagStyle.Preset pr = NametagStyle.PRESETS[i];
            float x = rx + 5 + (i % 2) * (pwid + 5), y = top + 16 + (i / 2) * (phei + 3);
            boolean hv = inside(mx, my, x, y, pwid, phei);
            boolean cur = pr.colors().equalsIgnoreCase(Settings.nametagColors);
            if (hv) pointer = true;
            int[] pc = NametagStyle.parseColors(pr.colors());
            NametagStyle ps = new NametagStyle(pc, "static", 1, false);
            Draw.rrect(g, x, y, pwid, phei, 5, Draw.withAlpha(Theme.ROW, 0.95f * a));
            // barre de dégradé
            int seg = 14;
            float bx = x + 4, bw = 22, by = y + 4.5f;
            for (int k = 0; k < seg; k++) {
                g.fill(Math.round(bx + bw * k / seg), Math.round(by), Math.round(bx + bw * (k + 1) / seg), Math.round(by + 6),
                        Draw.withAlpha(ps.sample(k / (float) (seg - 1)), a));
            }
            Draw.text(g, pr.name(), x + 30, y + 4.2f, 0.72f, Draw.withAlpha(cur ? Theme.TEXT : Theme.TEXT_DIM, a));
            Draw.ring(g, x, y, pwid, phei, 5, Draw.withAlpha(cur ? Theme.accent() : 0xFFFFFF, (cur ? 0.9f : hv ? 0.35f : 0.08f) * a));
            presetRects.add(new float[]{x, y, pwid, phei});
        }

        float cy = top + 16 + 4 * (phei + 3) + 6;
        Draw.text(g, "Colors", rx + 8, cy, 0.8f, Draw.withAlpha(Theme.TEXT_DIM, a));
        cy += 10;
        chipRects.clear();
        float cs = 18;
        stopSlide.update(dt, stop);
        for (int i = 0; i < cols.length; i++) {
            float x = rx + 8 + i * (cs + 6);
            boolean hv = inside(mx, my, x, cy, cs, cs);
            if (hv) pointer = true;
            Draw.rrect(g, x, cy, cs, cs, 5, Draw.withAlpha(cols[i], a));
            Draw.ring(g, x, cy, cs, cs, 5, Draw.withAlpha(0xFFFFFF, (hv ? 0.6f : 0.25f) * a));
            chipRects.add(new float[]{x, cy, cs, cs});
        }
        float selX = rx + 8 + stopSlide.value * (cs + 6);
        Draw.glow(g, selX - 2, cy - 2, cs + 4, cs + 4, 5, Draw.withAlpha(Theme.accent(), 0.35f * a));
        Draw.ring(g, selX - 2.5f, cy - 2.5f, cs + 5, cs + 5, 6.5f, Draw.withAlpha(0xFFFFFF, 0.95f * a));
        float bx = rx + 8 + cols.length * (cs + 6);
        addX = delX = -1;
        if (cols.length < 4) {
            addX = bx;
            addY = cy;
            boolean hv = inside(mx, my, bx, cy, cs, cs);
            if (hv) pointer = true;
            Draw.glass(g, bx, cy, cs, cs, 5, Draw.withAlpha(hv ? Theme.tileHover() : Theme.ROW, 0.95f * a), Draw.withAlpha(Theme.accent(), 0.6f * a));
            Draw.centered(g, "+", bx + cs / 2, cy + 3.5f, 1.1f, Draw.withAlpha(Theme.TEXT, a));
            bx += cs + 6;
        }
        if (cols.length > 1) {
            delX = bx;
            delY = cy;
            boolean hv = inside(mx, my, bx, cy, cs, cs);
            if (hv) pointer = true;
            Draw.glass(g, bx, cy, cs, cs, 5, Draw.withAlpha(hv ? Theme.tileHover() : Theme.ROW, 0.95f * a), Draw.withAlpha(0xFFFFFF, 0.25f * a));
            Draw.centered(g, "\u2212", bx + cs / 2, cy + 3.5f, 1.1f, Draw.withAlpha(Theme.TEXT, a));
        }
        cy += cs + 6;
        nameRows.render(g, rx, cy, rw, bottom - cy, mx, my, a);
    }

    /** Cape à plat qui "tourne" (sans joueur, sur l'écran titre). */
    private void flatCape(GuiGraphics g, float cx, float cy, float a) {
        Capes.Cape c = Capes.selected();
        float w = 46, h = 73.6f;
        if (c == null) {
            Draw.glass(g, cx - w / 2, cy - h / 2, w, h, 6, Draw.withAlpha(Theme.ROW, 0.6f * a), Draw.withAlpha(0xFFFFFF, 0.2f * a));
            Draw.icon(g, Icons.CAPE, cx, cy, 20, Draw.withAlpha(Theme.TEXT_MUTED, a));
            return;
        }
        float ang = (float) Math.toRadians(yaw.value);
        float sx = (float) Math.cos(ang);
        boolean back = sx < 0;
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().scale(Math.max(0.04f, Math.abs(sx)) * zoom.value, zoom.value);
        Draw.glow(g, -w / 2, -h / 2, w, h, 10, Draw.withAlpha(c.rarity().color, 0.25f * a));
        int k = c.texW() / 64;
        if (c.ready()) Draw.image(g, c.texture(), -w / 2, -h / 2, w, h, c.texW(), c.texH(), (back ? 12 : 1) * k, k, 10 * k, 16 * k,
                Draw.withAlpha(0xFFFFFF, a));
        g.pose().popMatrix();
    }

    /** Joueur en 3D avec rotation libre (même principe que l'inventaire, sans suivre la souris). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void renderPlayer(GuiGraphics g, LocalPlayer player, int x0, int y0, int x1, int y1, float scale, float yawDeg) {
        EntityRenderer renderer = minecraft.getEntityRenderDispatcher().getRenderer(player);
        EntityRenderState state = renderer.createRenderState(player, 1f);
        state.lightCoords = 15728880;
        state.shadowPieces.clear();
        state.outlineColor = 0;
        if (category == 0 && Settings.elytraCape && state instanceof net.minecraft.client.renderer.entity.state.HumanoidRenderState hs) {
            hs.chestEquipment = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ELYTRA);
        }
        if (state instanceof LivingEntityRenderState ls) {
            ls.bodyRot = 180f + yawDeg;
            ls.yRot = 0;
            ls.xRot = 0;
            ls.boundingBoxWidth /= ls.scale;
            ls.boundingBoxHeight /= ls.scale;
            ls.scale = 1f;
        }
        Quaternionf rot = new Quaternionf().rotateZ((float) Math.PI);
        Quaternionf cam = new Quaternionf().rotateX((float) Math.toRadians(8));
        rot.mul(cam);
        Vector3f translation = new Vector3f(0, state.boundingBoxHeight / 2f + 0.0625f, 0);
        g.submitEntityRenderState(state, scale, translation, rot, cam, x0, y0, x1, y1);
    }

    @Override
    protected boolean clickPage(double mx, double my) {
        if (tab == 0 && inside(mx, my, profileCard[0], profileCard[1], profileCard[2], profileCard[3])) {
            click(1.1f);
            minecraft.setScreen(new ProfileScreen(host()));
            return true;
        }
        if (tabHover >= 0) {
            if (tabHover != tab) {
                click(1.05f);
                tab = tabHover;
                tabShownAt = System.nanoTime();
            }
            return true;
        }
        if (tab != 0) return false;
        for (int i = 0; i < CATEGORIES.length; i++) {
            if (CATEGORIES[i][2].isEmpty() && inside(mx, my, catX, catTop + i * catRow, catW, catRow - 3)) {
                if (i != category) {
                    click(1.05f);
                    debugCategory(i);
                }
                return true;
            }
        }
        if (category == 1) return clickNametag(mx, my);
        if (category == 0 && inside(mx, my, wearRect[0], wearRect[1], wearRect[2], wearRect[3])) {
            boolean elytra = mx >= wearRect[0] + wearRect[2] / 2;
            if (elytra != Settings.elytraCape) {
                click(1.05f);
                Settings.elytraCape = elytra;
                gg.aurora.Config.save();
            }
            return true;
        }
        if (cosType() == null && inside(mx, my, importRect[0], importRect[1], importRect[2], importRect[3])) {
            click(1.05f);
            filter = 3;
            scrollTarget = 0;
            Capes.importDialog();
            return true;
        }
        for (int i = 0; i < filterRects.size(); i++) {
            float[] r = filterRects.get(i);
            if (inside(mx, my, r[0], r[1], r[2], r[3])) {
                if (i != filter) {
                    click(1.05f);
                    filter = i;
                    scrollTarget = 0;
                }
                return true;
            }
        }
        searchFocused = inside(mx, my, sbx, sby, sbw, 15);
        if (searchFocused) return true;
        if (inside(mx, my, px, py, pw, ph)) {
            draggingPreview = true;
            return true;
        }
        if (my >= gy && my < gy + gh) {
            for (int i = 0; i < cardRects.size(); i++) {
                float[] r = cardRects.get(i);
                if (inside(mx, my, r[0], r[1], r[2], r[3])) {
                    String id = cardIds.get(i);
                    Cosmetics.Type type = cosType();
                    if (type == Cosmetics.Type.PET && !id.isEmpty() && Cosmetics.family(id).size() > 1) {
                        boolean l = mx < r[0] + 15, rr = mx > r[0] + r[2] - 15;
                        if (l || rr) {
                            Cosmetics.cycleVariant(id, l ? -1 : 1);
                            NametagSync.markDirty();
                            Config.save();
                            click(1.1f);
                            return true;
                        }
                    }
                    // mini skin : choisir le pseudo (au premier choix, ou en recliquant la carte déjà portée)
                    if (type == Cosmetics.Type.PET && id.equals("mini_skin") && (id.equals(selectedId()) || Settings.petSkin.isBlank())) {
                        if (!id.equals(selectedId())) {
                            Cosmetics.select(type, id);
                            NametagSync.markDirty();
                            Config.save();
                        }
                        click(1.2f);
                        minecraft.setScreen(new MiniSkinScreen(host()));
                        return true;
                    }
                    if (!id.equals(selectedId())) {
                        if (type == null) Settings.cape = id;
                        else Cosmetics.select(type, id);
                        NametagSync.markDirty();
                        Config.save();
                        click(1.2f);
                        cardPick.computeIfAbsent(type == null ? id : type + ":" + id, k -> new Spring(0, 380, 0.45f)).velocity += 9;
                        String n = itemName(id);
                        toast = n == null ? categoryLabel() + " removed" : categoryLabel() + " equipped: " + n;
                        toastAt = time();
                    }
                    return true;
                }
            }
        }
        return true;
    }

    private boolean clickNametag(double mx, double my) {
        if (picker.click(mx, my)) return true;
        for (int i = 0; i < presetRects.size(); i++) {
            float[] r = presetRects.get(i);
            if (inside(mx, my, r[0], r[1], r[2], r[3])) {
                click(1.15f);
                setColors(NametagStyle.parseColors(NametagStyle.PRESETS[i].colors()));
                selectStop(0);
                return true;
            }
        }
        for (int i = 0; i < chipRects.size(); i++) {
            float[] r = chipRects.get(i);
            if (inside(mx, my, r[0], r[1], r[2], r[3])) {
                click(1.05f);
                selectStop(i);
                return true;
            }
        }
        int[] cols = colors();
        if (addX >= 0 && inside(mx, my, addX, addY, 18, 18)) {
            int[] n = java.util.Arrays.copyOf(cols, cols.length + 1);
            n[n.length - 1] = cols[cols.length - 1];
            click(1.2f);
            setColors(n);
            selectStop(n.length - 1);
            return true;
        }
        if (delX >= 0 && inside(mx, my, delX, delY, 18, 18)) {
            int[] n = new int[cols.length - 1];
            for (int i = 0, k = 0; i < cols.length; i++) if (i != stop) n[k++] = cols[i];
            click(0.85f);
            setColors(n);
            selectStop(Math.min(stop, n.length - 1));
            return true;
        }
        return nameRows.click(mx, my);
    }

    @Override
    public boolean typing() {
        return searchFocused;
    }

    @Override
    public boolean consumesEscape() {
        return searchFocused;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (category == 1 && picker.drag(event.x(), event.y())) return true;
        if (category == 1 && nameRows.drag(event.x(), event.y())) return true;
        if (draggingPreview) {
            yaw.target += (float) dx * 2.2f;
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (picker.dragging()) {
            picker.release();
            styleChanged();
        }
        nameRows.release();
        draggingPreview = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (category == 1) return nameRows.scroll(mx, my, sy);
        if (tab == 0 && inside(mx, my, px, py, pw, ph)) {
            zoom.target = Math.max(0.6f, Math.min(1.8f, zoom.target + (float) sy * 0.12f));
            return true;
        }
        scrollTarget -= (float) sy * 30;
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (searchFocused && event.isAllowedChatCharacter() && query.length() < 24) {
            query += event.codepointAsString();
            scrollTarget = 0;
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Ctrl+V on the capes page: cape from the copied link (Tenor, or a direct image link)
        if (!searchFocused && tab == 0 && category == 0 && event.key() == GLFW.GLFW_KEY_V && (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0) {
            Capes.importLink(minecraft.keyboardHandler.getClipboard());
            return true;
        }
        if (searchFocused && event.key() == GLFW.GLFW_KEY_BACKSPACE) {
            if (!query.isEmpty()) query = query.substring(0, query.length() - 1);
            return true;
        }
        if (searchFocused && event.key() == GLFW.GLFW_KEY_ESCAPE) {
            searchFocused = false;
            return true;
        }
        return super.keyPressed(event);
    }
}
