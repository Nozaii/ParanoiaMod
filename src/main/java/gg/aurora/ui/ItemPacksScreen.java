package gg.aurora.ui;

import gg.aurora.Config;
import gg.aurora.ItemPacks;
import gg.aurora.Settings;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Catalogue des textures : une case par objet (cristal, obsidienne, épées…), classées par mode PvP.
 * Chaque case montre l'objet tel qu'il est rendu et le pack choisi ; un clic ouvre les styles des packs
 * qui le contiennent (aperçu de leur texture), clic droit = vanilla. Vue « Par pack » : les cases que
 * fournit un pack, à appliquer une par une.
 */
public class ItemPacksScreen extends SubScreen {
    private final Screen vanilla;
    private final String before = Settings.itemPacks;
    private final List<String> packs;
    private final Map<String, PackResources> opened = new ConcurrentHashMap<>();
    /** Cases couvertes par chaque pack (calculé en arrière-plan). */
    private final Map<String, Set<String>> coverage = new ConcurrentHashMap<>();
    private volatile boolean scanned;

    private boolean byPack, community, newest, publishing;
    private boolean communityAsked;
    private int mode;          // index dans ItemPacks.MODES, MODES.size() = objets seuls
    private int packIndex;
    private String search = "";
    private boolean searchFocus;
    private float scroll, sideScroll;
    /** Case dont on choisit le style (null = pas de choix ouvert). */
    private String picking;
    private float pickScroll;

    private record Hit(float x, float y, float w, float h, Runnable left, Runnable right) {}

    private final List<Hit> hits = new ArrayList<>();

    public ItemPacksScreen(Screen parent, Screen vanilla) {
        super(parent, "Textures per item");
        this.vanilla = vanilla;
        this.packs = new ArrayList<>(ItemPacks.available());
        Thread t = new Thread(this::scan, "Paranoia textures");
        t.setDaemon(true);
        t.start();
    }

    @Override
    protected float maxW() {
        return 640;
    }

    @Override
    protected float maxH() {
        return 350;
    }

    private void scan() {
        for (String p : packs) {
            PackResources r = pack(p);
            if (r == null) continue;
            Set<String> s = new HashSet<>();
            for (ItemPacks.Category c : ItemPacks.CATEGORIES) {
                if (ItemPacks.covers(r, ItemPacks.itemsOf(c.key()))) s.add(c.key());
            }
            coverage.put(p, s);
        }
        scanned = true;
    }

    private PackResources pack(String name) {
        if (ItemPacks.VANILLA.equals(name)) return minecraft.getVanillaPackResources();
        PackResources r = opened.get(name);
        if (r == null) {
            r = ItemPacks.open(name);
            if (r != null) opened.put(name, r);
        }
        return r;
    }

    /** Packs proposant cette case (catégorie : liste scannée ; objet seul : vérifié à la demande). */
    private List<String> packsFor(String key) {
        List<String> out = new ArrayList<>();
        for (String p : packs) {
            Set<String> s = coverage.get(p);
            if (key.startsWith("item:")) {
                if (s == null) continue;
                if (!s.contains(key)) {
                    PackResources r = pack(p);
                    if (r != null && ItemPacks.covers(r, ItemPacks.itemsOf(key))) s.add(key);
                    else continue;
                }
                out.add(p);
            } else if (s != null && s.contains(key)) {
                out.add(p);
            }
        }
        return out;
    }

    private List<String> tilesOfMode() {
        if (mode < ItemPacks.MODES.size()) {
            List<String> out = new ArrayList<>();
            for (String t : ItemPacks.MODES.get(mode).tiles()) out.add("cat:" + t);
            return out;
        }
        List<String> out = new ArrayList<>();
        for (String k : ItemPacks.mapping().keySet()) if (k.startsWith("item:")) out.add(k);
        for (String k : customSeen) if (!out.contains(k)) out.add(k);
        return out;
    }

    private final List<String> customSeen = new ArrayList<>();

    private static String nameOf(String key) {
        if (key.startsWith("item:")) return ItemPacks.itemName(key.substring(5));
        for (ItemPacks.Category c : ItemPacks.CATEGORIES) if (c.key().equals(key)) return c.name();
        return key;
    }

    private static ItemStack iconOf(String key) {
        List<Identifier> items = ItemPacks.itemsOf(key);
        if (items.isEmpty()) return ItemStack.EMPTY;
        return new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(items.get(0)));
    }

    private static String shortName(String pack) {
        String n = pack.toLowerCase(Locale.ROOT).endsWith(".zip") ? pack.substring(0, pack.length() - 4) : pack;
        return n.replaceAll("§.", "");
    }

    private static String fit(String s, float scale, float w) {
        if (Draw.width(s, scale) <= w) return s;
        while (s.length() > 2 && Draw.width(s + "…", scale) > w) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private int changedIn(List<String> keys) {
        Map<String, String> m = ItemPacks.mapping();
        int n = 0;
        for (String k : keys) if (m.containsKey(k)) n++;
        return n;
    }

    // ===================================================================== rendu

    @Override
    protected void renderPage(GuiGraphics g, int mx, int my, float a) {
        hits.clear();
        Map<String, String> map = ItemPacks.mapping();
        float left = wx + 8, top = wy + 32, sideW = 132, bottom = wy + wh - 8;

        // en-tête : titre + bascule de vue
        Draw.text(g, "Textures per item", wx + 8 + Draw.width("Back", 1f) + 36, wy + 11, 1.05f, Draw.withAlpha(Theme.TEXT, a));
        String[] views = {"By item", "By pack", "Community"};
        float vx = wx + ww - 8;
        for (int i = views.length - 1; i >= 0; i--) {
            float w = Draw.width(views[i], 0.8f) + 14;
            vx -= w;
            boolean on = i == (community ? 2 : byPack ? 1 : 0), hv = inside(mx, my, vx, wy + 9, w, 14);
            pointer |= hv && !on;
            Draw.rrect(g, vx, wy + 9, w, 14, 7, Draw.withAlpha(on ? Theme.accent() : hv ? Theme.ROW_HOVER : Theme.ROW, a));
            Draw.centered(g, views[i], vx + w / 2, wy + 12.5f, 0.8f, Draw.withAlpha(on ? 0xFFFFFF : Theme.TEXT_DIM, a));
            boolean pk = i == 1, cm = i == 2;
            hits.add(new Hit(vx, wy + 9, w, 14, () -> {
                byPack = pk;
                community = cm;
                publishing = false;
                scroll = 0;
                sideScroll = 0;
                picking = null;
                click(1.05f);
            }, null));
            vx -= 4;
        }
        Draw.rrect(g, wx + 8, wy + 28, ww - 16, 0.6f, 0.3f, Draw.withAlpha(0xFFFFFF, 0.07f * a));

        sidebar(g, mx, my, a, map, left, top, sideW, bottom);
        float gx = left + sideW + 10, gw = wx + ww - 8 - gx;
        if (community) communityView(g, mx, my, a, gx, top, gw, bottom);
        else if (byPack) packView(g, mx, my, a, map, gx, top, gw, bottom);
        else itemView(g, mx, my, a, map, gx, top, gw, bottom);
        if (picking != null && !byPack && !community) picker(g, mx, my, a, map, gx, top, gw, bottom);
    }

    private void sidebar(GuiGraphics g, int mx, int my, float a, Map<String, String> map, float x, float y, float w, float bottom) {
        Draw.rrect(g, x, y, w, bottom - y, 4, Draw.withAlpha(0x000000, 0.16f * a));
        float cy = y + 6;
        int total = ItemPacks.CATEGORIES.size(), changed = 0;
        for (String k : map.keySet()) if (k.startsWith("cat:")) changed++;
        Draw.text(g, "Your PvP setup", x + 8, cy, 0.85f, Draw.withAlpha(Theme.TEXT, a));
        Draw.text(g, changed + " / " + total + " changed", x + 8, cy + 10, 0.72f, Draw.withAlpha(Theme.TEXT_DIM, a));
        cy += 26;
        float listBottom = bottom - 61;
        g.enableScissor((int) x, (int) cy, (int) (x + w), (int) listBottom);
        if (!byPack) {
            int n = ItemPacks.MODES.size() + 1;
            for (int i = 0; i < n; i++) {
                float ry = cy + i * 17 - sideScroll;
                if (i == 6) {
                    Draw.text(g, "ALL", x + 8, ry + 1, 0.65f, Draw.withAlpha(Theme.TEXT_MUTED, a));
                }
                float rowY = ry + (i >= 6 ? 9 : 0);
                if (rowY + 16 < cy || rowY > listBottom) continue;
                boolean on = i == mode, hv = inside(mx, my, x + 4, rowY, w - 8, 15) && my < listBottom;
                pointer |= hv && !on;
                if (on || hv) Draw.rrect(g, x + 4, rowY, w - 8, 15, 3, Draw.withAlpha(on ? Theme.ROW_HOVER : Theme.ROW, (on ? 0.95f : 0.7f) * a));
                if (on) Draw.rrect(g, x + 4, rowY + 3, 1.5f, 9, 0.75f, Draw.withAlpha(Theme.accent(), a));
                String label;
                List<String> keys;
                ItemStack icon;
                if (i < ItemPacks.MODES.size()) {
                    ItemPacks.Mode md = ItemPacks.MODES.get(i);
                    label = md.name();
                    keys = new ArrayList<>();
                    for (String t : md.tiles()) keys.add("cat:" + t);
                    icon = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(md.icon())));
                } else {
                    label = "Single items";
                    keys = new ArrayList<>();
                    for (String k : map.keySet()) if (k.startsWith("item:")) keys.add(k);
                    icon = new ItemStack(net.minecraft.world.item.Items.ITEM_FRAME);
                }
                g.pose().pushMatrix();
                g.pose().translate(x + 9, rowY + 2.5f);
                g.pose().scale(0.625f, 0.625f);
                g.renderItem(icon, 0, 0);
                g.pose().popMatrix();
                Draw.text(g, fit(label, 0.78f, w - 50), x + 22, rowY + 4, 0.78f, Draw.withAlpha(on ? Theme.TEXT : Theme.TEXT_DIM, a));
                String cnt = i < ItemPacks.MODES.size() ? changedIn(keys) + "/" + keys.size() : String.valueOf(keys.size());
                Draw.text(g, cnt, x + w - 8 - Draw.width(cnt, 0.7f), rowY + 4.5f, 0.7f, Draw.withAlpha(Theme.TEXT_MUTED, a));
                int idx = i;
                hits.add(new Hit(x + 4, Math.max(cy, rowY), w - 8, 15, () -> {
                    mode = idx;
                    scroll = 0;
                    picking = null;
                    click(1.05f);
                }, null));
            }
        } else {
            if (packs.isEmpty()) Draw.text(g, "No packs", x + 8, cy + 2, 0.78f, Draw.withAlpha(Theme.TEXT_MUTED, a));
            for (int i = 0; i < packs.size(); i++) {
                float ry = cy + i * 17 - sideScroll;
                if (ry + 16 < cy || ry > listBottom) continue;
                boolean on = i == packIndex, hv = inside(mx, my, x + 4, ry, w - 8, 15) && my < listBottom;
                pointer |= hv && !on;
                if (on || hv) Draw.rrect(g, x + 4, ry, w - 8, 15, 3, Draw.withAlpha(on ? Theme.ROW_HOVER : Theme.ROW, (on ? 0.95f : 0.7f) * a));
                if (on) Draw.rrect(g, x + 4, ry + 3, 1.5f, 9, 0.75f, Draw.withAlpha(Theme.accent(), a));
                Set<String> cov = coverage.get(packs.get(i));
                String cnt = cov == null ? "…" : String.valueOf(cov.size());
                Draw.text(g, fit(shortName(packs.get(i)), 0.76f, w - 34), x + 10, ry + 4, 0.76f, Draw.withAlpha(on ? Theme.TEXT : Theme.TEXT_DIM, a));
                Draw.text(g, cnt, x + w - 8 - Draw.width(cnt, 0.7f), ry + 4.5f, 0.7f, Draw.withAlpha(Theme.TEXT_MUTED, a));
                int idx = i;
                hits.add(new Hit(x + 4, Math.max(cy, ry), w - 8, 15, () -> {
                    packIndex = idx;
                    scroll = 0;
                    click(1.05f);
                }, null));
            }
        }
        g.disableScissor();
        // pied : nombre de packs, tout réinitialiser, écran vanilla
        float fy = bottom - 57;
        Draw.rrect(g, x + 6, fy - 3, w - 12, 0.6f, 0.3f, Draw.withAlpha(0xFFFFFF, 0.07f * a));
        Draw.text(g, packs.size() + " packs" + (scanned ? "" : " · scanning…"), x + 8, fy + 1, 0.72f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        float half = (w - 18) / 3;
        button(g, mx, my, a, x + 6, fy + 11, half, "Share", () -> {
            click(1.05f);
            if (ItemPacks.copyShareCode()) Toasts.push(Icons.CHECK, "Item packs", "Share code copied", false);
            else Toasts.push(Icons.INFO, "Item packs", "Pick a pack for an item first", false);
        });
        button(g, mx, my, a, x + 12 + 2 * half, fy + 11, half, "Merge", () -> {
            click(1.05f);
            List<String> missing = ItemPacks.importShareCode(true);
            if (missing == null) {
                Toasts.push(Icons.INFO, "Item packs", "No share code in the clipboard", false);
                return;
            }
            apply();
            String body = ItemPacks.lastMergeAdded + " added" + (ItemPacks.lastMergeKept > 0 ? ", " + ItemPacks.lastMergeKept + " kept yours" : "");
            if (missing.isEmpty()) Toasts.push(Icons.CHECK, "Setups merged", body, false);
            else Toasts.push(Icons.INFO, "Merged, packs missing", String.join(", ", missing.subList(0, Math.min(3, missing.size())))
                    + (missing.size() > 3 ? " +" + (missing.size() - 3) : ""), true);
        });
        button(g, mx, my, a, x + 9 + half, fy + 11, half, "Import", () -> {
            click(1.05f);
            List<String> missing = ItemPacks.importShareCode();
            if (missing == null) {
                Toasts.push(Icons.INFO, "Item packs", "No share code in the clipboard", false);
                return;
            }
            apply();
            if (missing.isEmpty()) Toasts.push(Icons.CHECK, "Item packs", "Setup imported", false);
            else Toasts.push(Icons.INFO, "Imported, packs missing", String.join(", ", missing.subList(0, Math.min(3, missing.size())))
                    + (missing.size() > 3 ? " +" + (missing.size() - 3) : ""), true);
        });
        button(g, mx, my, a, x + 6, fy + 26, w - 12, "Reset all", () -> {
            Settings.itemPacks = "";
            click(0.9f);
            apply();
        });
        if (vanilla != null) button(g, mx, my, a, x + 6, fy + 41, w - 12, "Classic packs", () -> {
            WorldPanel.bypassPacks = true;
            try {
                minecraft.setScreen(vanilla);
            } finally {
                WorldPanel.bypassPacks = false;
            }
        });
    }

    private void button(GuiGraphics g, int mx, int my, float a, float x, float y, float w, String label, Runnable r) {
        boolean hv = inside(mx, my, x, y, w, 13);
        pointer |= hv;
        Draw.rrect(g, x, y, w, 13, 3, Draw.withAlpha(hv ? Theme.ROW_HOVER : Theme.ROW, 0.9f * a));
        Draw.centered(g, label, x + w / 2, y + 3, 0.75f, Draw.withAlpha(hv ? Theme.TEXT : Theme.TEXT_DIM, a));
        hits.add(new Hit(x, y, w, 13, r, null));
    }

    /** En-tête de la zone principale + champ de recherche. Renvoie le haut de la grille. */
    private float header(GuiGraphics g, int mx, int my, float a, String title, String desc, float x, float y, float w) {
        Draw.text(g, title, x, y + 2, 1f, Draw.withAlpha(Theme.TEXT, a));
        Draw.text(g, desc, x, y + 13, 0.72f, Draw.withAlpha(Theme.TEXT_DIM, a));
        float sw = Math.min(150, w * 0.35f), sx = x + w - sw;
        Draw.rrect(g, sx, y + 2, sw, 14, 7, Draw.withAlpha(0x000000, 0.3f * a));
        if (searchFocus) Draw.ring(g, sx, y + 2, sw, 14, 7, Draw.withAlpha(Theme.accent(), 0.8f * a));
        Draw.icon(g, Icons.SEARCH, sx + 8, y + 9, 7, Draw.withAlpha(Theme.TEXT_DIM, a));
        boolean caret = searchFocus && (System.currentTimeMillis() / 500) % 2 == 0;
        Draw.text(g, search.isEmpty() && !searchFocus ? "Search…" : search + (caret ? "|" : ""), sx + 15, y + 5.5f, 0.75f,
                Draw.withAlpha(search.isEmpty() && !searchFocus ? Theme.TEXT_MUTED : Theme.TEXT, a));
        pointer |= inside(mx, my, sx, y + 2, sw, 14);
        hits.add(new Hit(sx, y + 2, sw, 14, () -> searchFocus = true, null));
        return y + 26;
    }

    private boolean matches(String key) {
        if (search.isEmpty()) return true;
        String q = search.toLowerCase(Locale.ROOT);
        if (nameOf(key).toLowerCase(Locale.ROOT).contains(q)) return true;
        for (Identifier id : ItemPacks.itemsOf(key)) if (id.getPath().contains(q)) return true;
        return false;
    }

    private static final float TW = 62, TH = 66, GAP = 5;

    private void itemView(GuiGraphics g, int mx, int my, float a, Map<String, String> map, float x, float y, float w, float bottom) {
        boolean custom = mode >= ItemPacks.MODES.size();
        String title = custom ? "Single items" : ItemPacks.MODES.get(mode).name();
        String desc = custom ? "Any block or item, one by one" : ItemPacks.MODES.get(mode).desc();
        float gy = header(g, mx, my, a, title, desc, x, y, w);
        List<String> keys = new ArrayList<>();
        for (String k : tilesOfMode()) if (matches(k)) keys.add(k);
        int cols = Math.max(1, (int) ((w + GAP) / (TW + GAP)));
        float realW = (w - (cols - 1) * GAP) / cols;
        int count = keys.size() + (custom ? 1 : 0);
        float content = ((count + cols - 1) / cols) * (TH + GAP);
        float gh = bottom - gy - 12;
        scroll = Math.max(0, Math.min(Math.max(0, content - gh), scroll));
        g.enableScissor((int) x - 2, (int) gy - 2, (int) (x + w + 2), (int) (gy + gh));
        for (int i = 0; i < count; i++) {
            float tx = x + (i % cols) * (realW + GAP), ty = gy + (i / cols) * (TH + GAP) - scroll;
            if (ty + TH < gy || ty > gy + gh) continue;
            boolean hv = inside(mx, my, tx, ty, realW, TH) && my < gy + gh && picking == null;
            pointer |= hv;
            if (i == keys.size()) { // « + » : ajouter un objet
                Draw.rrect(g, tx, ty, realW, TH, 4, Draw.withAlpha(hv ? Theme.ROW_HOVER : Theme.ROW, 0.6f * a));
                Draw.centered(g, "+", tx + realW / 2, ty + TH / 2 - 10, 1.6f, Draw.withAlpha(Theme.TEXT_DIM, a));
                Draw.centered(g, "Add", tx + realW / 2, ty + TH - 16, 0.75f, Draw.withAlpha(Theme.TEXT_DIM, a));
                hits.add(new Hit(tx, Math.max(gy, ty), realW, TH, () -> minecraft.setScreen(new ItemPickerScreen(this, "", id -> {
                    if (id != null && !id.isEmpty() && !customSeen.contains("item:" + id)) customSeen.add("item:" + id);
                })), null));
                continue;
            }
            String key = keys.get(i);
            String chosen = map.get(key);
            int avail = scanned || key.startsWith("item:") ? packsFor(key).size() : -1;
            Draw.rrect(g, tx, ty, realW, TH, 4, Draw.withAlpha(hv ? Theme.ROW_HOVER : Theme.ROW, (hv ? 0.95f : 0.75f) * a));
            if (chosen != null) Draw.rrect(g, tx, ty + TH - 2, realW, 2, 1, Draw.withAlpha(Theme.accent(), a));
            // chosen pack: its own texture right away (no need to wait for the reload)
            icon(g, key, chosen, tx + realW / 2 - 16, ty + 8, 32, a);
            Draw.text(g, fit(nameOf(key), 0.75f, realW - 8), tx + 5, ty + TH - 22, 0.75f, Draw.withAlpha(Theme.TEXT, a));
            Draw.text(g, fit(chosen == null ? "Vanilla" : shortName(chosen), 0.68f, realW - 8), tx + 5, ty + TH - 12, 0.68f,
                    Draw.withAlpha(chosen == null ? Theme.TEXT_MUTED : Theme.accent(), a));
            String badge = avail < 0 ? "…" : String.valueOf(avail);
            float bw = Draw.width(badge, 0.62f) + 7;
            Draw.rrect(g, tx + realW - bw - 3, ty + 3, bw, 9, 4.5f, Draw.withAlpha(0x000000, 0.35f * a));
            Draw.centered(g, badge, tx + realW - bw / 2 - 3, ty + 4.6f, 0.62f, Draw.withAlpha(Theme.TEXT_DIM, a));
            hits.add(new Hit(tx, Math.max(gy, ty), realW, Math.min(TH, gy + gh - ty), () -> {
                picking = key;
                pickScroll = 0;
                click(1.1f);
            }, () -> {
                ItemPacks.set(key, null);
                click(0.85f);
            }));
        }
        g.disableScissor();
        Draw.centered(g, "Click: pick a style · right click: vanilla", x + w / 2, bottom - 8, 0.7f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        if (!applied.equals(Settings.itemPacks)) button(g, mx, my, a, x + w - 70, bottom - 11, 70, "Apply now", this::apply);
        if (keys.isEmpty() && !custom) Draw.centered(g, "No items", x + w / 2, gy + 30, 0.85f, Draw.withAlpha(Theme.TEXT_MUTED, a));
    }

    /** Choix du style d'une case : vanilla puis chaque pack qui la contient, avec l'aperçu de sa texture. */
    private void picker(GuiGraphics g, int mx, int my, float a, Map<String, String> map, float x, float y, float w, float bottom) {
        List<String> opts = new ArrayList<>();
        opts.add(null);
        opts.addAll(packsFor(picking));
        Draw.rrect(g, x - 2, y - 2, w + 4, bottom - y + 4, 4, Draw.withAlpha(Theme.PANEL, 0.98f * a));
        Draw.text(g, nameOf(picking), x + 4, y + 3, 1f, Draw.withAlpha(Theme.TEXT, a));
        Draw.text(g, (opts.size() - 1) + " styles available", x + 4, y + 14, 0.72f, Draw.withAlpha(Theme.TEXT_DIM, a));
        button(g, mx, my, a, x + w - 64, y + 3, 60, "Close", () -> picking = null);
        boolean linked = isArmor(picking) && Settings.armorPacksLinked;
        if (isArmor(picking)) {
            button(g, mx, my, a, x + w - 64 - 96, y + 3, 92, linked ? "All armors: on" : "All armors: off", () -> {
                Settings.armorPacksLinked = !Settings.armorPacksLinked;
                gg.aurora.Config.save();
                click(1f);
            });
        }
        float gy = y + 28, gh = bottom - gy;
        final float pw = 70, ph = 70;
        int cols = Math.max(1, (int) ((w + GAP) / (pw + GAP)));
        float realW = (w - (cols - 1) * GAP) / cols;
        float content = ((opts.size() + cols - 1) / cols) * (ph + GAP);
        pickScroll = Math.max(0, Math.min(Math.max(0, content - gh), pickScroll));
        String chosen = map.get(picking);
        Identifier first = ItemPacks.itemsOf(picking).isEmpty() ? null : ItemPacks.itemsOf(picking).get(0);
        g.enableScissor((int) x - 2, (int) gy, (int) (x + w + 2), (int) bottom);
        for (int i = 0; i < opts.size(); i++) {
            float tx = x + (i % cols) * (realW + GAP), ty = gy + (i / cols) * (ph + GAP) - pickScroll;
            if (ty + ph < gy || ty > bottom) continue;
            String p = opts.get(i);
            boolean on = p == null ? chosen == null : p.equals(chosen);
            boolean hv = inside(mx, my, tx, ty, realW, ph) && my >= gy;
            pointer |= hv;
            Draw.rrect(g, tx, ty, realW, ph, 4, Draw.withAlpha(on ? Theme.ROW_HOVER : hv ? Theme.ROW_HOVER : Theme.ROW, (on || hv ? 0.95f : 0.75f) * a));
            if (on) Draw.ring(g, tx, ty, realW, ph, 4, Draw.withAlpha(Theme.accent(), a));
            float s = 40, ix = tx + realW / 2 - s / 2, iy = ty + 6;
            icon(g, picking, p, ix, iy, s, a);
            Draw.centered(g, fit(p == null ? "Vanilla" : shortName(p), 0.7f, realW - 6), tx + realW / 2, ty + ph - 14, 0.7f,
                    Draw.withAlpha(on ? Theme.TEXT : Theme.TEXT_DIM, a));
            String key = picking;
            hits.add(new Hit(tx, Math.max(gy, ty), realW, ph, () -> {
                if (linked) {
                    // same pack for every armor the pack covers (vanilla resets them all)
                    for (var c : ItemPacks.CATEGORIES) {
                        if (!isArmor(c.key())) continue;
                        if (p == null || c.key().equals(key) || packsFor(c.key()).contains(p)) ItemPacks.set(c.key(), p);
                    }
                } else {
                    ItemPacks.set(key, p);
                }
                picking = null;
                click(1.2f);
            }, null));
        }
        g.disableScissor();
        // clic hors des styles : rien (le panneau couvre toute la zone)
        hits.add(0, new Hit(x - 2, y - 2, w + 4, bottom - y + 4, () -> {}, null));
    }

    private static String count(int n) {
        return n >= 1000 ? String.format(Locale.ROOT, "%.1fk", n / 1000f) : String.valueOf(n);
    }

    private boolean chip(GuiGraphics g, int mx, int my, float a, String label, float x, float y, float w, boolean on, Runnable run) {
        boolean hv = inside(mx, my, x, y, w, 14);
        pointer |= hv && !on;
        Draw.rrect(g, x, y, w, 14, 7, Draw.withAlpha(on ? Theme.accent() : Theme.ROW_HOVER, (on || hv ? 1f : 0.7f) * a));
        Draw.centered(g, label, x + w / 2, y + 3.6f, 0.78f, Draw.withAlpha(on ? 0xFFFFFF : hv ? Theme.TEXT : Theme.TEXT_DIM, a));
        hits.add(new Hit(x, y, w, 14, run, null));
        return hv;
    }

    private void communityView(GuiGraphics g, int mx, int my, float a, float x, float y, float w, float bottom) {
        if (!communityAsked) {
            communityAsked = true;
            gg.aurora.CommunityPacks.refresh(newest);
        }
        Draw.text(g, "Community packs", x, y + 2, 1f, Draw.withAlpha(Theme.TEXT, a));
        Draw.text(g, "Packs shared by players", x, y + 13, 0.72f, Draw.withAlpha(Theme.TEXT_DIM, a));
        float bx = x + w - 150;
        for (int i = 0; i < 2; i++) {
            boolean nw = i == 1;
            chip(g, mx, my, a, nw ? "New" : "Popular", bx + i * 48, y + 2, 44, newest == nw, () -> {
                if (newest == nw) return;
                newest = nw;
                scroll = 0;
                click(1.05f);
                gg.aurora.CommunityPacks.refresh(nw);
            });
        }
        chip(g, mx, my, a, "Publish", x + w - 52, y + 2, 52, publishing, () -> {
            publishing = !publishing;
            scroll = 0;
            click(1.05f);
        });
        float gy = y + 26, gh = bottom - gy - 4;
        if (publishing) {
            publishList(g, mx, my, a, x, gy, w, gh);
            return;
        }
        var list = gg.aurora.CommunityPacks.list;
        String state = gg.aurora.CommunityPacks.error;
        if (state == null && list.isEmpty()) state = gg.aurora.CommunityPacks.loading ? "Loading packs…" : "No packs yet. Be the first to publish one.";
        if (state != null && list.isEmpty()) {
            Draw.centered(g, state, x + w / 2, gy + gh / 2 - 6, 0.85f, Draw.withAlpha(Theme.TEXT_DIM, a));
            return;
        }
        float cw = (w - GAP) / 2, ch = 46;
        float content = (list.size() + 1) / 2 * (ch + GAP);
        scroll = Math.max(0, Math.min(Math.max(0, content - gh), scroll));
        g.enableScissor((int) x - 2, (int) gy - 2, (int) (x + w + 2), (int) (gy + gh));
        for (int i = 0; i < list.size(); i++) {
            float cx = x + i % 2 * (cw + GAP), cy = gy + i / 2 * (ch + GAP) - scroll;
            if (cy + ch < gy || cy > gy + gh) continue;
            var e = list.get(i);
            gg.aurora.CommunityPacks.seen(e);
            Draw.rrect(g, cx, cy, cw, ch, 5, Draw.withAlpha(Theme.ROW, 0.9f * a));
            Draw.rrect(g, cx + 6, cy + 6, 22, 22, 4, Draw.withAlpha(Theme.ROW_HOVER, a));
            Draw.centered(g, e.name.substring(0, 1).toUpperCase(Locale.ROOT), cx + 17, cy + 13, 1f, Draw.withAlpha(Theme.TEXT, a));
            Draw.text(g, fit(e.name, 0.85f, cw - 66), cx + 33, cy + 7, 0.85f, Draw.withAlpha(Theme.TEXT, a));
            Draw.text(g, "by " + e.author, cx + 33, cy + 18, 0.7f, Draw.withAlpha(Theme.TEXT_DIM, a));
            float sy = cy + ch - 14;
            boolean lh = inside(mx, my, cx + 5, sy - 2, 32, 13) && my >= gy && my < gy + gh;
            pointer |= lh;
            Draw.icon(g, Icons.HEART, cx + 11, sy + 4, 7, Draw.withAlpha(e.liked ? 0xF0506E : lh ? Theme.TEXT : Theme.TEXT_DIM, a));
            Draw.text(g, count(e.likes), cx + 17, sy + 1, 0.7f, Draw.withAlpha(Theme.TEXT_DIM, a));
            hits.add(new Hit(cx + 5, sy - 2, 32, 13, () -> {
                gg.aurora.CommunityPacks.like(e);
                click(e.liked ? 1.25f : 0.85f);
            }, null));
            Draw.icon(g, Icons.EYE, cx + 45, sy + 4, 7, Draw.withAlpha(Theme.TEXT_DIM, a));
            Draw.text(g, count(e.views), cx + 51, sy + 1, 0.7f, Draw.withAlpha(Theme.TEXT_DIM, a));
            Draw.icon(g, Icons.ARROW_DOWN, cx + 75, sy + 4, 7, Draw.withAlpha(Theme.TEXT_DIM, a));
            Draw.text(g, count(e.downloads), cx + 81, sy + 1, 0.7f, Draw.withAlpha(Theme.TEXT_DIM, a));
            boolean have = e.have;
            float aw = 46, ax = cx + cw - aw - 5, ay = cy + ch - 18;
            chip(g, mx, my, a, have ? "Added" : "Download", ax, ay, aw, have, () -> {
                if (have) return;
                click(1.05f);
                gg.aurora.CommunityPacks.download(e, this::reloadPacks);
            });
            String small = e.mine ? "Delete" : "Report";
            float rw = Draw.width(small, 0.62f) + 2, rx = cx + cw - rw - 5;
            boolean rh = inside(mx, my, rx, cy + 5, rw, 9);
            pointer |= rh;
            Draw.text(g, small, rx, cy + 6, 0.62f, Draw.withAlpha(rh ? Theme.TEXT : Theme.TEXT_MUTED, a));
            hits.add(new Hit(rx, cy + 5, rw, 9, () -> confirmAction(e), null));
        }
        g.disableScissor();
    }

    private void confirmAction(gg.aurora.CommunityPacks.Entry e) {
        var title = net.minecraft.network.chat.Component.literal((e.mine ? "Delete « " : "Report « ") + e.name + " »?");
        var body = net.minecraft.network.chat.Component.literal(e.mine ? "It will disappear for everyone, likes and downloads included."
                : "Use this for stolen, offensive or broken packs. It is hidden after several reports.");
        minecraft.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(ok -> {
            minecraft.setScreen(this);
            if (!ok) return;
            if (e.mine) gg.aurora.CommunityPacks.delete(e, () -> gg.aurora.CommunityPacks.refresh(newest));
            else gg.aurora.CommunityPacks.report(e);
        }, title, body));
    }

    public void debugCommunity() {
        community = true;
    }

    private void reloadPacks() {
        packs.clear();
        packs.addAll(ItemPacks.available());
        scan();
    }

    private void publishList(GuiGraphics g, int mx, int my, float a, float x, float gy, float w, float gh) {
        List<String> zips = new ArrayList<>();
        for (String p : packs) if (p.toLowerCase(Locale.ROOT).endsWith(".zip")) zips.add(p);
        if (zips.isEmpty()) {
            Draw.centered(g, "Put a .zip pack in the resourcepacks folder first", x + w / 2, gy + gh / 2 - 6, 0.85f, Draw.withAlpha(Theme.TEXT_DIM, a));
            return;
        }
        Draw.text(g, "Pick the pack to share", x, gy, 0.75f, Draw.withAlpha(Theme.TEXT_DIM, a));
        float ly = gy + 12, lh = gh - 12, rh = 20;
        scroll = Math.max(0, Math.min(Math.max(0, zips.size() * (rh + 3) - lh), scroll));
        g.enableScissor((int) x - 2, (int) ly, (int) (x + w + 2), (int) (ly + lh));
        for (int i = 0; i < zips.size(); i++) {
            float ry = ly + i * (rh + 3) - scroll;
            if (ry + rh < ly || ry > ly + lh) continue;
            String p = zips.get(i);
            boolean hv = inside(mx, my, x, ry, w, rh) && my >= ly && my < ly + lh;
            pointer |= hv;
            Draw.rrect(g, x, ry, w, rh, 5, Draw.withAlpha(hv ? Theme.ROW_HOVER : Theme.ROW, 0.9f * a));
            Draw.text(g, fit(shortName(p), 0.85f, w - 16), x + 8, ry + 6, 0.85f, Draw.withAlpha(Theme.TEXT, a));
            hits.add(new Hit(x, ry, w, rh, () -> confirmPublish(p), null));
        }
        g.disableScissor();
    }

    private void confirmPublish(String pack) {
        String file = pack.startsWith("file/") ? pack.substring(5) : pack;
        var title = net.minecraft.network.chat.Component.literal("Publish « " + shortName(pack) + " »?");
        var body = net.minecraft.network.chat.Component.literal("Every Paranoia player will be able to download it, with your Minecraft name on it. "
                + "Only publish a pack you made or have the right to share.");
        minecraft.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(ok -> {
            minecraft.setScreen(this);
            if (!ok) return;
            gg.aurora.CommunityPacks.publish(file, () -> {
                publishing = false;
                newest = true;
                gg.aurora.CommunityPacks.refresh(true);
            });
        }, title, body));
    }

    private void packView(GuiGraphics g, int mx, int my, float a, Map<String, String> map, float x, float y, float w, float bottom) {
        if (packs.isEmpty()) {
            Draw.centered(g, "Put packs in the resourcepacks folder", x + w / 2, y + 40, 0.85f, Draw.withAlpha(Theme.TEXT_MUTED, a));
            return;
        }
        packIndex = Math.max(0, Math.min(packs.size() - 1, packIndex));
        String pack = packs.get(packIndex);
        Set<String> cov = coverage.get(pack);
        float gy = header(g, mx, my, a, fit(shortName(pack), 1f, w - 170),
                cov == null ? "Scanning pack…" : cov.size() + " items in this pack · click: apply / remove", x, y, w);
        if (cov == null) return;
        List<String> keys = new ArrayList<>();
        for (ItemPacks.Category c : ItemPacks.CATEGORIES) if (cov.contains(c.key()) && matches(c.key())) keys.add(c.key());
        int cols = Math.max(1, (int) ((w + GAP) / (TW + GAP)));
        float realW = (w - (cols - 1) * GAP) / cols;
        float content = ((keys.size() + cols - 1) / cols) * (TH + GAP);
        float gh = bottom - gy - 12;
        scroll = Math.max(0, Math.min(Math.max(0, content - gh), scroll));
        PackResources r = pack(pack);
        g.enableScissor((int) x - 2, (int) gy - 2, (int) (x + w + 2), (int) (gy + gh));
        for (int i = 0; i < keys.size(); i++) {
            float tx = x + (i % cols) * (realW + GAP), ty = gy + (i / cols) * (TH + GAP) - scroll;
            if (ty + TH < gy || ty > gy + gh) continue;
            String key = keys.get(i);
            boolean applied = pack.equals(map.get(key));
            boolean hv = inside(mx, my, tx, ty, realW, TH) && my < gy + gh;
            pointer |= hv;
            Draw.rrect(g, tx, ty, realW, TH, 4, Draw.withAlpha(hv ? Theme.ROW_HOVER : Theme.ROW, (hv ? 0.95f : 0.75f) * a));
            if (applied) Draw.rrect(g, tx, ty + TH - 2, realW, 2, 1, Draw.withAlpha(Theme.accent(), a));
            icon(g, key, pack, tx + realW / 2 - 16, ty + 8, 32, a);
            Draw.text(g, fit(nameOf(key), 0.75f, realW - 8), tx + 5, ty + TH - 22, 0.75f, Draw.withAlpha(Theme.TEXT, a));
            String other = map.get(key);
            String state = applied ? "Applied" : other == null ? "Vanilla" : fit(shortName(other), 0.68f, realW - 8);
            Draw.text(g, state, tx + 5, ty + TH - 12, 0.68f, Draw.withAlpha(applied ? Theme.accent() : Theme.TEXT_MUTED, a));
            hits.add(new Hit(tx, Math.max(gy, ty), realW, Math.min(TH, gy + gh - ty), () -> {
                ItemPacks.set(key, applied ? null : pack);
                click(applied ? 0.9f : 1.2f);
            }, () -> {
                ItemPacks.set(key, null);
                click(0.85f);
            }));
        }
        g.disableScissor();
        if (keys.isEmpty()) Draw.centered(g, "This pack changes no item in the catalog", x + w / 2, gy + 30, 0.85f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        float bx = x, by = bottom - 11;
        button(g, mx, my, a, bx, by, 120, "Apply all", () -> {
            for (String k : keys) ItemPacks.set(k, pack);
            click(1.2f);
        });
    }

    private static boolean isArmor(String key) {
        return key.endsWith("_armor");
    }

    private static boolean isCrystal(String key) {
        return key.equals("cat:crystal");
    }

    /**
     * Tile picture. No pack = the real vanilla texture (never whatever happens to be loaded), so a label
     * and its picture always agree. Crystals and armor show the entity, not the item.
     */
    private void icon(GuiGraphics g, String key, String pack, float x, float y, float s, float a) {
        Identifier first = ItemPacks.itemsOf(key).isEmpty() ? null : ItemPacks.itemsOf(key).get(0);
        String name = pack == null ? ItemPacks.VANILLA : pack;
        PackResources r = pack(name);
        if (first != null && r != null && (isArmor(key) || isCrystal(key))) {
            // armor: a real armor stand wearing the pack's textures, exactly like the vanilla preview
            if (isArmor(key) && minecraft.level != null && entity(g, key, name, r, first, x, y, s)) return;
            // already applied: the real 3D entity with the loaded textures
            String loaded = ItemPacks.mapping(applied).get(key);
            boolean live = java.util.Objects.equals(loaded, pack) && minecraft.level != null;
            if (live && entity(g, key, null, null, null, x, y, s)) return;
            if (isArmor(key) ? mannequin(g, name, r, first, x, y, s, a) : crystal(g, name, r, first, x, y, s, a)) return;
        }
        Identifier tex = r == null || first == null ? null : ItemPacks.previewTexture(name, r, first);
        if (tex == null) {
            g.pose().pushMatrix();
            g.pose().translate(x + (s - 32) / 2, y + (s - 32) / 2);
            g.pose().scale(2, 2);
            g.renderItem(iconOf(key), 0, 0);
            g.pose().popMatrix();
            return;
        }
        if (iconOf(key).getItem() instanceof net.minecraft.world.item.BlockItem && !ItemPacks.flatBlock(first)) cube(g, tex, x + s / 2, y + s / 2, s * 0.5f, a);
        else picture(g, tex, x, y, s, a);
    }

    /** Draws a loaded texture, playing its animation if it has one. */
    private void picture(GuiGraphics g, Identifier tex, float x, float y, float s, float a) {
        int[] i = ItemPacks.info(tex);
        if (i == null) {
            Draw.image(g, tex, x, y, s, s, 1, 1, 0, 0, 1, 1, Draw.withAlpha(0xFFFFFF, a));
            return;
        }
        int frame = i[2] <= 1 ? 0 : (int) (System.currentTimeMillis() / (50L * i[3]) % i[2]);
        Draw.image(g, tex, x, y, s, s, i[0], i[1] * i[2], 0, frame * i[1], i[0], i[1], Draw.withAlpha(0xFFFFFF, a));
    }

    private final Map<String, net.minecraft.world.entity.Entity> entities = new HashMap<>();

    /**
     * Real entity in the tile (armor stand with the full set, end crystal). False if it can't be built.
     * With a pack, the armor pieces point to a preview equipment asset built from that pack's textures
     * (vanilla asset if the pack has none for this armor, so the tile still shows the real thing).
     */
    private boolean entity(GuiGraphics g, String key, String packName, PackResources r, Identifier first, float x, float y, float s) {
        net.minecraft.resources.ResourceKey<net.minecraft.world.item.equipment.EquipmentAsset> asset =
                packName == null || r == null || first == null ? null : ItemPacks.previewAsset(packName, r, first);
        if (packName != null && asset == null && !ItemPacks.VANILLA.equals(packName)) return false;
        try {
            var e = entities.computeIfAbsent(packName == null ? key : key + "|" + packName, k -> {
                if (isCrystal(key)) return new net.minecraft.world.entity.boss.enderdragon.EndCrystal(minecraft.level, 0, 0, 0);
                var st = new net.minecraft.world.entity.decoration.ArmorStand(minecraft.level, 0, 0, 0);
                for (Identifier id : ItemPacks.itemsOf(key)) {
                    ItemStack it = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id));
                    var eq = it.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
                    if (eq == null) continue;
                    if (asset != null) {
                        it.set(net.minecraft.core.component.DataComponents.EQUIPPABLE,
                                net.minecraft.world.item.equipment.Equippable.builder(eq.slot()).setAsset(asset).setEquipSound(eq.equipSound()).build());
                    }
                    st.setItemSlot(eq.slot(), it);
                }
                return st;
            });
            var render = minecraft.getEntityRenderDispatcher().getRenderer(e);
            var state = render.createRenderState(e, 1f);
            state.lightCoords = 0xF000F0;
            state.shadowPieces.clear();
            state.outlineColor = 0;
            state.ageInTicks = (System.currentTimeMillis() % 100000L) / 50f;
            float height = isCrystal(key) ? 2f : 2.1f;
            if (state instanceof net.minecraft.client.renderer.entity.state.LivingEntityRenderState l) {
                l.bodyRot = 200;
                l.yRot = 0;
                l.xRot = 0;
            }
            var rot = new org.joml.Quaternionf().rotateZ((float) Math.PI);
            g.submitEntityRenderState(state, s / height, new org.joml.Vector3f(0, height / 2, 0), rot, null,
                    Math.round(x), Math.round(y), Math.round(x + s), Math.round(y + s));
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Pack preview of a full armor set: front view on a small stand, drawn from the worn textures. */
    private boolean mannequin(GuiGraphics g, String name, PackResources r, Identifier first, float x, float y, float s, float a) {
        var files = ItemPacks.entityFiles(first);
        if (files.size() < 2) return false;
        Identifier body = ItemPacks.load(name, r, files.get(0)), legs = ItemPacks.load(name, r, files.get(1));
        if (body == null) {
            PackResources van = pack(ItemPacks.VANILLA);
            body = ItemPacks.load(ItemPacks.VANILLA, van, files.get(0));
            legs = ItemPacks.load(ItemPacks.VANILLA, van, files.get(1));
        }
        if (body == null) return false;
        float k = s / 44f, ox = x + s / 2 - 1 * k, oy = y + s * 0.5f - 15 * k;
        int tint = first.getPath().contains("leather") ? 0xA06540 : 0xFFFFFF;
        // painter's order for a three-quarter view from the front right
        box(g, STAND, 64, 64, ox, oy, k, -6, 31, -6, 12, 1, 12, 0, 32, 0xFFFFFF, a);
        box(g, STAND, 64, 64, ox, oy, k, -3, 20, -1, 2, 11, 2, 8, 0, 0xFFFFFF, a);
        box(g, STAND, 64, 64, ox, oy, k, 1, 20, -1, 2, 11, 2, 40, 16, 0xFFFFFF, a);
        box(g, STAND, 64, 64, ox, oy, k, -4, 18, -1, 8, 2, 2, 0, 48, 0xFFFFFF, a);
        box(g, STAND, 64, 64, ox, oy, k, -6, 8, -1.5f, 12, 3, 3, 0, 26, 0xFFFFFF, a);
        box(g, STAND, 64, 64, ox, oy, k, -1, 1, -1, 2, 7, 2, 0, 0, 0xFFFFFF, a);
        int tw = texW(body), th = texH(body);
        box(g, body, tw, th, ox, oy, k, -9, 7, -3, 6, 14, 6, 40, 16, 4, 12, 4, tint, a);
        if (legs != null) {
            int lw = texW(legs), lh = texH(legs);
            box(g, legs, lw, lh, ox, oy, k, -4.5f, 19.5f, -2.5f, 5, 13, 5, 0, 16, 4, 12, 4, tint, a);
            box(g, legs, lw, lh, ox, oy, k, -0.5f, 19.5f, -2.5f, 5, 13, 5, 0, 16, 4, 12, 4, tint, a);
        }
        box(g, body, tw, th, ox, oy, k, -5, 19, -3, 6, 14, 6, 0, 16, 4, 12, 4, tint, a);
        box(g, body, tw, th, ox, oy, k, -1, 19, -3, 6, 14, 6, 0, 16, 4, 12, 4, tint, a);
        box(g, body, tw, th, ox, oy, k, -5, 7, -3, 10, 14, 6, 16, 16, 8, 12, 4, tint, a);
        box(g, body, tw, th, ox, oy, k, 3, 7, -3, 6, 14, 6, 40, 16, 4, 12, 4, tint, a);
        box(g, body, tw, th, ox, oy, k, -5, -1, -5, 10, 10, 10, 0, 0, 8, 8, 8, tint, a);
        return true;
    }

    private static final Identifier STAND = Identifier.withDefaultNamespace("textures/entity/armorstand/wood.png");
    /** Screen axes of the three-quarter view: x to the right, y down, z towards the viewer. */
    private static final float EXX = 0.87f, EXY = 0.17f, EZX = -0.5f, EZY = 0.3f;

    private static int texW(Identifier t) {
        int[] i = ItemPacks.info(t);
        return i == null ? 64 : i[0];
    }

    private static int texH(Identifier t) {
        int[] i = ItemPacks.info(t);
        return i == null ? 32 : i[1];
    }

    private void box(GuiGraphics g, Identifier tex, int tw, int th, float ox, float oy, float k, float x, float y, float z, float w, float h, float d,
                     int u, int v, int tint, float a) {
        box(g, tex, tw, th, ox, oy, k, x, y, z, w, h, d, u, v, (int) w, (int) h, (int) d, tint, a);
    }

    /** A model cube: position and size in model pixels, then its texture net (inflated cubes keep their net). */
    private void box(GuiGraphics g, Identifier tex, int tw, int th, float ox, float oy, float k, float x, float y, float z, float w, float h, float d,
                     int u, int v, int nw, int nh, int nd, int tint, float a) {
        float q = tw / 64f;
        float x1 = x + w, z1 = z + d;
        // right side, top, front: the three faces this view shows
        face(g, tex, tw, th, ox + k * (x1 * EXX + z1 * EZX), oy + k * (x1 * EXY + z1 * EZY + y), -EZX * k, -EZY * k, 0, k, d, h,
                u * q, (v + nd) * q, nd * q, nh * q, tint, 0.68f, a);
        face(g, tex, tw, th, ox + k * (x * EXX + z * EZX), oy + k * (x * EXY + z * EZY + y), EXX * k, EXY * k, EZX * k, EZY * k, w, d,
                (u + nd) * q, v * q, nw * q, nd * q, tint, 1f, a);
        face(g, tex, tw, th, ox + k * (x * EXX + z1 * EZX), oy + k * (x * EXY + z1 * EZY + y), EXX * k, EXY * k, 0, k, w, h,
                (u + nd) * q, (v + nd) * q, nw * q, nh * q, tint, 0.86f, a);
    }

    private void face(GuiGraphics g, Identifier tex, int tw, int th, float px, float py, float ax, float ay, float bx, float by, float w, float h,
                      float u, float v, float uw, float vh, int tint, float shade, float a) {
        g.pose().pushMatrix();
        g.pose().translate(px, py);
        g.pose().mul(new org.joml.Matrix3x2f(ax, ay, bx, by, 0, 0));
        int r = Math.round((tint >> 16 & 255) * shade), gr = Math.round((tint >> 8 & 255) * shade), b = Math.round((tint & 255) * shade);
        Draw.image(g, tex, 0, 0, w, h, tw, th, u, v, uw, vh, Draw.withAlpha(r << 16 | gr << 8 | b, a));
        g.pose().popMatrix();
    }

    /** Part of a 64x32 entity texture (scaled for HD packs). */
    private void part(GuiGraphics g, Identifier tex, float x, float y, float w, float h, int u, int v, int uw, int vh, int color) {
        int[] i = ItemPacks.info(tex);
        float q = i == null ? 1 : i[0] / 64f;
        Draw.image(g, tex, x, y, w, h, i == null ? 64 : i[0], i == null ? 32 : i[1], u * q, v * q, uw * q, vh * q, color);
    }

    /** Pack preview of the crystal: its entity texture on a cube over the bedrock base. */
    private boolean crystal(GuiGraphics g, String name, PackResources r, Identifier first, float x, float y, float s, float a) {
        Identifier f = ItemPacks.entityFiles(first).get(0);
        Identifier tex = ItemPacks.load(name, r, f);
        if (tex == null) tex = ItemPacks.load(ItemPacks.VANILLA, pack(ItemPacks.VANILLA), f);
        if (tex == null) return false;
        float c = s * 0.34f, cx = x + s / 2, cy = y + s * 0.5f;
        int[] i = ItemPacks.info(tex);
        float q = i == null ? 1 : i[0] / 64f;
        int tw = i == null ? 64 : i[0], th = i == null ? 32 : i[1];
        float[][] faces = {{1, 0.5f, -1, 0.5f, 0, -c, 1f}, {1, 0.5f, 0, 1, -c, -c / 2, 0.62f}, {1, -0.5f, 0, 1, 0, 0, 0.8f}};
        for (float[] fc : faces) {
            g.pose().pushMatrix();
            g.pose().translate(cx + fc[4], cy + fc[5]);
            g.pose().mul(new org.joml.Matrix3x2f(fc[0], fc[1], fc[2], fc[3], 0, 0));
            int v = Math.round(255 * fc[6]);
            Draw.image(g, tex, 0, 0, c, c, tw, th, 8 * q, 8 * q, 8 * q, 8 * q, Draw.withAlpha((v << 16) | (v << 8) | v, a));
            Draw.image(g, tex, c * 0.2f, c * 0.2f, c * 0.6f, c * 0.6f, tw, th, 40 * q, 8 * q, 8 * q, 8 * q, Draw.withAlpha((v << 16) | (v << 8) | v, a));
            g.pose().popMatrix();
        }
        return true;
    }

    /** Isometric cube from one texture: three sheared faces with simple shading. */
    private void cube(GuiGraphics g, Identifier tex, float cx, float cy, float r, float a) {
        int[] i = ItemPacks.info(tex);
        int w = i == null ? 1 : i[0], h = i == null ? 1 : i[1], total = i == null ? 1 : i[1] * i[2];
        int frame = i == null || i[2] <= 1 ? 0 : (int) (System.currentTimeMillis() / (50L * i[3]) % i[2]);
        float[][] faces = {{1, 0.5f, -1, 0.5f, 0, -r, 1f}, {1, 0.5f, 0, 1, -r, -r / 2, 0.62f}, {1, -0.5f, 0, 1, 0, 0, 0.8f}};
        for (float[] f : faces) {
            g.pose().pushMatrix();
            g.pose().translate(cx + f[4], cy + f[5]);
            g.pose().mul(new org.joml.Matrix3x2f(f[0], f[1], f[2], f[3], 0, 0));
            int c = Math.round(255 * f[6]);
            Draw.image(g, tex, 0, 0, r, r, w, total, 0, frame * h, w, h, Draw.withAlpha((c << 16) | (c << 8) | c, a));
            g.pose().popMatrix();
        }
    }

    /** Applies the current choices now (same reload as the vanilla pack screen). */
    private void apply() {
        Config.save();
        applied = Settings.itemPacks;
        minecraft.reloadResourcePacks();
    }

    private String applied = Settings.itemPacks;

    // ===================================================================== entrées

    @Override
    protected boolean clickPage(double mx, double my) {
        searchFocus = false;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (mx >= h.x() && mx < h.x() + h.w() && my >= h.y() && my < h.y() + h.h() && h.left() != null) {
                h.left().run();
                return true;
            }
        }
        if (picking != null) {
            picking = null;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (event.x() >= h.x() && event.x() < h.x() + h.w() && event.y() >= h.y() && event.y() < h.y() + h.h() && h.right() != null) {
                    h.right().run();
                    return true;
                }
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        float sideRight = wx + 8 + 132;
        if (picking != null) pickScroll -= (float) sy * 24;
        else if (mx < sideRight) sideScroll = Math.max(0, sideScroll - (float) sy * 17);
        else scroll -= (float) sy * 24;
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (!searchFocus) return super.charTyped(event);
        search += event.codepointAsString();
        scroll = 0;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && picking != null) {
            picking = null;
            return true;
        }
        if (searchFocus) {
            if (event.key() == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) {
                search = search.substring(0, search.length() - 1);
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_ESCAPE) {
                searchFocus = false;
                return true;
            }
            if (event.key() != GLFW.GLFW_KEY_ESCAPE) return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        super.onClose();
        Config.save();
        // pack changé : on recharge les ressources (comme l'écran vanilla)
        if (!applied.equals(Settings.itemPacks)) minecraft.reloadResourcePacks();
    }

    @Override
    public void removed() {
        super.removed();
        // fermé pour de bon (pas pendant le sélecteur d'objet)
        if (!(minecraft.screen instanceof ItemPickerScreen)) {
            opened.values().forEach(PackResources::close);
            opened.clear();
        }
    }
}
