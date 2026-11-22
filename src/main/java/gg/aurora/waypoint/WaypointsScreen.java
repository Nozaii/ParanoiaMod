package gg.aurora.waypoint;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import gg.aurora.Settings;
import gg.aurora.ui.Draw;
import gg.aurora.ui.Icons;
import gg.aurora.ui.RowPanel;
import gg.aurora.ui.Rows;
import gg.aurora.ui.Spring;
import gg.aurora.ui.Theme;
import gg.aurora.ui.WaypointRows;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Gestion des waypoints. À gauche : recherche, filtre de dimension, dossiers et waypoints
 * (nom, coordonnées, distance, œil pour masquer). À droite : éditeur par sections avec aperçu
 * en direct, et barre d'actions fixe en bas (Créer / Annuler, Dupliquer / Supprimer).
 */
public class WaypointsScreen extends Screen {
    private static final String[] DIMS = {"All", "World", "Nether", "End"};
    private static final String[] DIM_IDS = {"", "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"};
    private static final float ROW_H = 22, FOLDER_H = 18;

    private final Screen parent;
    /** Waypoint en cours de création (pas encore dans la liste), ou null. */
    private Waypoint creating;
    private Object selected;
    private final RowPanel panel;
    private final Spring open = new Spring(0, 160, 0.85f);
    private long last = System.nanoTime();
    private float dt;
    private boolean pointer;
    private String search = "";
    private boolean searchFocus;
    private int dimFilter;
    private float scroll, scrollTarget;
    /** Confirmation de suppression en attente (2e clic). */
    private long confirmDeleteUntil;

    private record Line(Object item, int depth, float y, float h) {}

    private final List<Line> lines = new ArrayList<>();
    private float wx, wy, ww, wh, lx, ly, lw, lh, ex, ey, ew, eh;
    private final List<float[]> actionRects = new ArrayList<>();
    private final List<Runnable> actionRuns = new ArrayList<>();

    public WaypointsScreen(Screen parent, Waypoint create) {
        super(Component.literal("Waypoints"));
        this.parent = parent;
        this.creating = create;
        panel = new RowPanel(() -> dt, new RowPanel.Callbacks() {
            public void pointer() {
                pointer = true;
            }

            public void sound(float pitch) {
                click(pitch);
            }

            public void changed() {
                if (creating == null) Waypoints.save();
            }
        });
        select(create);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void click(float pitch) {
        if (Settings.sounds) minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch, 0.3f));
    }

    private static boolean inside(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ===================================================================== éditeur

    private void select(Object o) {
        selected = o;
        confirmDeleteUntil = 0;
        if (o instanceof Waypoint w) panel.setRows(waypointRows(w));
        else if (o instanceof Waypoint.Folder f) panel.setRows(folderRows(f));
        else panel.setRows(List.of());
    }

    private static int parseInt(String s, int def) {
        String t = s.trim();
        if (t.isEmpty() || t.equals("-")) return 0;
        try {
            return Integer.parseInt(t);
        } catch (Exception e) {
            return def;
        }
    }

    private String shareTo = "";

    private List<Rows.Row> waypointRows(Waypoint w) {
        List<Rows.Row> r = new ArrayList<>();
        r.add(new WaypointRows.Header("General", Icons.PIN));
        r.add(new Rows.TextField("Name", "E.g. Base, Mine, Portal…", 32, () -> w.name, v -> w.name = v));
        r.add(new WaypointRows.Coords(
                new Rows.TextField("X", "0", 9, () -> String.valueOf(w.x), v -> w.x = parseInt(v, w.x)),
                new Rows.TextField("Y", "64", 9, () -> String.valueOf(w.y), v -> w.y = parseInt(v, w.y)),
                new Rows.TextField("Z", "0", 9, () -> String.valueOf(w.z), v -> w.z = parseInt(v, w.z)),
                () -> {
                    if (minecraft.player == null) return;
                    BlockPos p = minecraft.player.blockPosition().below();
                    w.x = p.getX();
                    w.y = p.getY();
                    w.z = p.getZ();
                    w.dim = minecraft.level.dimension().identifier().toString();
                }));
        if (w.area) {
            r.add(new WaypointRows.Coords(
                    new Rows.TextField("X2", "0", 9, () -> String.valueOf(w.x2), v -> w.x2 = parseInt(v, w.x2)),
                    new Rows.TextField("Y2", "64", 9, () -> String.valueOf(w.y2), v -> w.y2 = parseInt(v, w.y2)),
                    new Rows.TextField("Z2", "0", 9, () -> String.valueOf(w.z2), v -> w.z2 = parseInt(v, w.z2)),
                    () -> {
                        if (minecraft.player == null) return;
                        BlockPos p = minecraft.player.blockPosition();
                        w.x2 = p.getX();
                        w.y2 = p.getY();
                        w.z2 = p.getZ();
                    }));
            r.add(new Rows.Info("Zone size", w::sizeText));
            r.add(new Rows.Button("Reselect with the camera", "Closes the menu: aim at the 2 corners with " + Waypoints.zoneKey.getTranslatedKeyMessage().getString(),
                    Icons.MOVE, () -> {
                        if (w != creating) Waypoints.LIST.remove(w);
                        Waypoints.save();
                        minecraft.setScreen(null);
                        Waypoints.startZone();
                    }));
        }
        List<Waypoint.Folder> fs = Waypoints.FOLDERS;
        if (!fs.isEmpty()) {
            r.add(new Rows.Slider("Folder", null, 0, fs.size(), 1, () -> {
                for (int i = 0; i < fs.size(); i++) if (fs.get(i).id.equals(w.folder)) return i + 1;
                return 0;
            }, v -> {
                int i = (int) Math.round(v);
                w.folder = i == 0 ? "" : fs.get(i - 1).id;
            }, v -> {
                int i = (int) Math.round(v);
                return i == 0 ? "None" : fs.get(Math.min(fs.size(), i) - 1).name;
            }));
        }

        r.add(new WaypointRows.Header("Appearance", Icons.PALETTE));
        r.add(new WaypointRows.Palette("Color", () -> w.color, v -> w.color = v));
        r.add(new Rows.ColorRow("Custom color", () -> w.color, v -> w.color = v & 0xFFFFFF));
        r.add(new WaypointRows.Presets(w));
        r.add(new Rows.Choice("Icon", new String[]{"None", "Item", "Initial"}, () -> w.iconType, i -> w.iconType = i));
        r.add(new WaypointRows.ItemPick("Icon item", () -> w.icon, v -> {
            w.icon = v;
            w.iconType = 1;
        }));
        r.add(new Rows.Button("Use held item", "Uses the item you're holding", Icons.BAG, () -> {
            if (minecraft.player == null) return;
            ItemStack s = minecraft.player.getMainHandItem();
            if (!s.isEmpty()) {
                w.icon = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
                w.iconType = 1;
            }
        }));

        r.add(new WaypointRows.Header("Share", Icons.FRIENDS));
        r.add(new Rows.Button("Share in chat", "Asks first: everyone can read it, mod players can save it", Icons.GLOBE,
                () -> WaypointShare.confirm(this, w, null)));
        r.add(new Rows.TextField("Send to player", "Username", 16, () -> shareTo, v -> shareTo = v));
        r.add(new Rows.Button("Send by private message", "Asks first", Icons.FRIENDS, () -> {
            if (!shareTo.isBlank()) WaypointShare.confirm(this, w, shareTo);
        }));
        if (WaypointShare.canTeleport()) r.add(new Rows.Button("Teleport here", "You have operator rights on this server", Icons.ARROW_DOWN,
                () -> WaypointShare.teleport(w)));

        r.add(new WaypointRows.Header("Block highlight", Icons.LAYERS));
        r.add(new Rows.Toggle(w.area ? "Show the zone" : "Highlight the block", w.area ? "Box around the whole zone" : "Hugs its shape (slab, door, ladder…)",
                () -> w.block, v -> w.block = v));
        r.add(new Rows.Toggle("Visible through walls", null, () -> w.walls, v -> w.walls = v));
        r.add(new Rows.Slider("Fill", null, 0, 1, 0.05, () -> w.fill, v -> w.fill = (float) v, v -> Math.round(v * 100) + " %"));
        r.add(new Rows.Slider("Outline", null, 0, 1, 0.05, () -> w.outline, v -> w.outline = (float) v, v -> Math.round(v * 100) + " %"));
        return r;
    }

    private List<Rows.Row> folderRows(Waypoint.Folder f) {
        return List.of(
                new WaypointRows.Header("Folder", Icons.LIST),
                new Rows.TextField("Name", "Folder", 32, () -> f.name, v -> f.name = v),
                new WaypointRows.Palette("Color", () -> f.color, v -> f.color = v),
                new Rows.Toggle("Visible", "Hides or shows all its waypoints", () -> f.visible, v -> f.visible = v),
                new Rows.Toggle("Locked", "Prevents deleting its waypoints", () -> f.locked, v -> f.locked = v));
    }

    // ===================================================================== liste

    private boolean matches(Waypoint w) {
        if (dimFilter > 0 && !w.dim.equals(DIM_IDS[dimFilter])) return false;
        return search.isEmpty() || w.name.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT));
    }

    private int count(Waypoint.Folder f) {
        int n = 0;
        for (Waypoint w : Waypoints.LIST) if (w.folder.equals(f.id) && matches(w)) n++;
        return n;
    }

    private void rebuild(float top) {
        lines.clear();
        float y = top;
        for (Waypoint.Folder f : Waypoints.FOLDERS) {
            lines.add(new Line(f, 0, y, FOLDER_H));
            y += FOLDER_H;
            if (f.collapsed) continue;
            for (Waypoint w : Waypoints.LIST) {
                if (w.folder.equals(f.id) && matches(w)) {
                    lines.add(new Line(w, 1, y, ROW_H));
                    y += ROW_H;
                }
            }
        }
        for (Waypoint w : Waypoints.LIST) {
            if (Waypoints.folder(w.folder) == null && matches(w)) {
                lines.add(new Line(w, 0, y, ROW_H));
                y += ROW_H;
            }
        }
    }

    private double distance(Waypoint w) {
        if (minecraft.player == null || minecraft.level == null) return -1;
        if (!w.dim.equals(minecraft.level.dimension().identifier().toString())) return -1;
        return Math.sqrt(minecraft.player.distanceToSqr((w.minX() + w.maxX() + 1) / 2.0, (w.minY() + w.maxY() + 1) / 2.0, (w.minZ() + w.maxZ() + 1) / 2.0));
    }

    // ===================================================================== rendu

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        long now = System.nanoTime();
        float raw = Math.min(0.05f, (now - last) / 1e9f);
        last = now;
        dt = Settings.anim() ? raw * Settings.animFactor() : raw;
        open.update(dt, 1);
        float a = Draw.clamp01(open.value);
        pointer = false;
        actionRects.clear();
        actionRuns.clear();
        g.fill(0, 0, width, height, Draw.withAlpha(0x000000, 0.55f * a));

        ww = Math.min(width - 24, 560);
        wh = Math.min(height - 20, 330);
        wx = (width - ww) / 2;
        wy = (height - wh) / 2 + (1 - open.value) * 18;
        Draw.glow(g, wx, wy + 6, ww, wh, 20, Draw.withAlpha(0x000000, 0.6f * a));
        Draw.glass(g, wx, wy, ww, wh, 10, Draw.withAlpha(Theme.PANEL, 0.95f * a), Draw.withAlpha(0xFFFFFF, 0.3f * a));

        // ---- en-tête
        Draw.icon(g, Icons.PIN, wx + 16, wy + 15, 11, Draw.withAlpha(Theme.accent(), a));
        Draw.text(g, "Waypoints", wx + 26, wy + 9.5f, 1.15f, Draw.withAlpha(Theme.TEXT, a));
        String sub = Waypoints.LIST.size() + " in this world  ·  J: open  ·  K: add at target block";
        Draw.text(g, sub, wx + 28 + Draw.width("Waypoints", 1.15f) + 8, wy + 12, 0.75f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        float cx = wx + ww - 23, cy = wy + 7;
        boolean chv = inside(mx, my, cx, cy, 16, 16);
        pointer |= chv;
        Draw.rrect(g, cx, cy, 16, 16, 8, Draw.withAlpha(chv ? 0xE8473E : Theme.ROW, 0.9f * a));
        Draw.centered(g, "×", cx + 8, cy + 3.5f, 1f, Draw.withAlpha(Theme.TEXT, a));

        renderList(g, mx, my, a, now);
        renderEditor(g, mx, my, a);

        if (pointer) g.requestCursor(CursorTypes.POINTING_HAND);
    }

    private void renderList(GuiGraphics g, int mx, int my, float a, long now) {
        lx = wx + 10;
        ly = wy + 30;
        lw = Math.max(160, ww * 0.38f);
        lh = wh - 40;
        Draw.rrect(g, lx, ly, lw, lh, 8, Draw.withAlpha(0x000000, 0.22f * a));
        // recherche
        float sw = lw - 10;
        Draw.rrect(g, lx + 5, ly + 5, sw, 16, 6, Draw.withAlpha(searchFocus ? Theme.ROW_HOVER : Theme.ROW, 0.95f * a));
        if (searchFocus) Draw.ring(g, lx + 5, ly + 5, sw, 16, 6, Draw.withAlpha(Theme.accent(), 0.7f * a));
        pointer |= inside(mx, my, lx + 5, ly + 5, sw, 16);
        Draw.icon(g, Icons.SEARCH, lx + 13, ly + 13, 7, Draw.withAlpha(Theme.TEXT_DIM, a));
        boolean blink = searchFocus && (now / 500_000_000L) % 2 == 0;
        Draw.text(g, search.isEmpty() && !searchFocus ? "Search a waypoint…" : search + (blink ? "|" : ""), lx + 20, ly + 9.5f, 0.85f,
                Draw.withAlpha(search.isEmpty() && !searchFocus ? Theme.TEXT_MUTED : Theme.TEXT, a));
        // filtre de dimension en pilules
        float px = lx + 5, py = ly + 25, pw = (lw - 10 - 3 * 3) / 4;
        for (int i = 0; i < DIMS.length; i++) {
            float bx = px + i * (pw + 3);
            boolean sel = i == dimFilter, hv = inside(mx, my, bx, py, pw, 13);
            pointer |= hv && !sel;
            Draw.rrect(g, bx, py, pw, 13, 6.5f, Draw.withAlpha(sel ? Theme.accent() : hv ? Theme.ROW_HOVER : Theme.ROW, (sel ? 0.9f : 0.8f) * a));
            Draw.centered(g, DIMS[i], bx + pw / 2, py + 3, 0.75f, Draw.withAlpha(sel ? 0xFFFFFF : Theme.TEXT_DIM, a));
        }

        float listY = ly + 43, listH = lh - 43 - 26;
        rebuild(0);
        float content = lines.isEmpty() ? 0 : lines.get(lines.size() - 1).y() + lines.get(lines.size() - 1).h();
        float maxScroll = Math.max(0, content - listH);
        scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
        scroll = Draw.smooth(scroll, scrollTarget, 16, Math.min(0.05f, dt));
        g.enableScissor((int) lx, (int) listY, (int) (lx + lw), (int) (listY + listH));
        boolean inList = my >= listY && my < listY + listH;
        for (Line l : lines) {
            float ry = listY + l.y() - scroll;
            if (ry + l.h() < listY || ry > listY + listH) continue;
            drawLine(g, l, lx + 4, ry, lw - 8, mx, my, a, inList);
        }
        g.disableScissor();
        if (maxScroll > 0) {
            float barH = Math.max(14, listH * listH / content);
            Draw.rrect(g, lx + lw - 3, listY + (listH - barH) * (scroll / maxScroll), 2, barH, 1, Draw.withAlpha(Theme.accent(), 0.6f * a));
        }
        if (lines.isEmpty()) {
            float cy = listY + listH / 2 - 16;
            Draw.icon(g, Icons.PIN, lx + lw / 2, cy, 18, Draw.withAlpha(Theme.TEXT_MUTED, a));
            boolean filtered = !search.isEmpty() || dimFilter > 0;
            Draw.centered(g, filtered ? "No results" : "No waypoints", lx + lw / 2, cy + 14, 0.9f, Draw.withAlpha(Theme.TEXT_DIM, a));
            Draw.centered(g, filtered ? "Change the search or the filter" : "Click « + Waypoint » or press K",
                    lx + lw / 2, cy + 26, 0.72f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        }
        // boutons du bas
        float by = ly + lh - 21, bw = (lw - 16) / 3;
        action(g, "+ Waypoint", lx + 5, by, bw, 16, true, false, mx, my, a, this::newWaypoint);
        action(g, "+ Zone", lx + 8 + bw, by, bw, 16, false, false, mx, my, a, () -> {
            Waypoints.save();
            minecraft.setScreen(null);
            Waypoints.startZone();
        });
        action(g, "+ Folder", lx + 11 + bw * 2, by, bw, 16, false, false, mx, my, a, this::newFolder);
    }

    private void drawLine(GuiGraphics g, Line l, float x, float y, float w, int mx, int my, float a, boolean inList) {
        float h = l.h();
        boolean sel = l.item() == selected;
        boolean hv = inList && inside(mx, my, x, y, w, h);
        pointer |= hv;
        if (sel || hv) Draw.rrect(g, x, y + 1, w, h - 2, 5, Draw.withAlpha(sel ? Theme.tileHover() : Theme.ROW_HOVER, (sel ? 0.95f : 0.55f) * a));
        if (sel) Draw.ring(g, x, y + 1, w, h - 2, 5, Draw.withAlpha(Theme.accent(), 0.6f * a));
        float ix = x + 4 + l.depth() * 10;
        float eyeX = x + w - 10;
        if (l.item() instanceof Waypoint.Folder f) {
            g.pose().pushMatrix();
            g.pose().translate(ix + 4, y + h / 2);
            if (f.collapsed) g.pose().rotate((float) Math.toRadians(-90));
            Draw.icon(g, Icons.CARET_DOWN, 0, 0, 7, Draw.withAlpha(Theme.TEXT_DIM, a));
            g.pose().popMatrix();
            Draw.rrect(g, ix + 11, y + h / 2 - 3.5f, 7, 7, 2, Draw.withAlpha(f.color, a));
            Draw.text(g, f.name, ix + 22, y + 5, 0.88f, Draw.withAlpha(f.visible ? Theme.TEXT : Theme.TEXT_MUTED, a));
            String n = String.valueOf(count(f));
            float nx = ix + 24 + Draw.width(f.name, 0.88f);
            Draw.rrect(g, nx, y + 4, Draw.width(n, 0.7f) + 7, 10, 5, Draw.withAlpha(0x000000, 0.3f * a));
            Draw.text(g, n, nx + 3.5f, y + 6, 0.7f, Draw.withAlpha(Theme.TEXT_DIM, a));
            if (f.locked) Draw.icon(g, Icons.LOCK, eyeX - 14, y + h / 2, 7, Draw.withAlpha(Theme.accent(), a));
            eye(g, eyeX, y + h / 2, f.visible, mx, my, a);
        } else if (l.item() instanceof Waypoint wp) {
            Draw.rrect(g, ix, y + 4, 2, h - 8, 1, Draw.withAlpha(wp.color, a));
            ItemStack s = wp.iconType == 1 ? Waypoints.iconStack(wp) : ItemStack.EMPTY;
            float tx = ix + 6;
            if (!s.isEmpty()) {
                g.pose().pushMatrix();
                g.pose().translate(ix + 5, y + 4);
                g.pose().scale(0.875f, 0.875f);
                g.renderItem(s, 0, 0);
                g.pose().popMatrix();
                tx += 17;
            } else if (wp.iconType == 2) {
                Draw.rrect(g, ix + 5, y + 4, 14, 14, 3, Draw.withAlpha(wp.color, 0.85f * a));
                Draw.centered(g, wp.name.isEmpty() ? "?" : wp.name.substring(0, 1).toUpperCase(), ix + 12, y + 7.5f, 0.85f, Draw.withAlpha(0xFFFFFF, a));
                tx += 17;
            }
            double d = distance(wp);
            String dist = d < 0 ? "" : d < 1000 ? Math.round(d) + " m" : String.format(Locale.ROOT, "%.1f km", d / 1000);
            float distW = dist.isEmpty() ? 0 : Draw.width(dist, 0.75f) + 6;
            String name = wp.name;
            float maxW = eyeX - 10 - distW - tx;
            while (name.length() > 1 && Draw.width(name, 0.88f) > maxW) name = name.substring(0, name.length() - 2) + "…";
            Draw.text(g, name, tx, y + 3.5f, 0.88f, Draw.withAlpha(wp.visible ? Theme.TEXT : Theme.TEXT_MUTED, a));
            Draw.text(g, wp.area ? "Zone " + wp.sizeText() : wp.x + " " + wp.y + " " + wp.z, tx, y + 12.5f, 0.68f, Draw.withAlpha(Theme.TEXT_MUTED, a));
            if (!dist.isEmpty()) Draw.text(g, dist, eyeX - 9 - distW + 6, y + 8, 0.75f, Draw.withAlpha(Theme.TEXT_DIM, a));
            if (wp.temp) Draw.icon(g, Icons.SKULL, eyeX - 12 - distW, y + h / 2, 7, Draw.withAlpha(0xE8473E, a));
            eye(g, eyeX, y + h / 2, wp.visible, mx, my, a);
        }
    }

    private void eye(GuiGraphics g, float cx, float cy, boolean on, int mx, int my, float a) {
        boolean hv = inside(mx, my, cx - 7, cy - 7, 14, 14);
        if (hv) Draw.rrect(g, cx - 7, cy - 7, 14, 14, 7, Draw.withAlpha(0xFFFFFF, 0.1f * a));
        Draw.icon(g, Icons.EYE, cx, cy, 8, Draw.withAlpha(on ? (hv ? Theme.TEXT : Theme.TEXT_DIM) : 0x50545C, a));
        if (!on) Draw.rrect(g, cx - 5, cy - 0.5f, 10, 1, 0.5f, Draw.withAlpha(0x8A8E96, a));
    }

    private void renderEditor(GuiGraphics g, int mx, int my, float a) {
        ex = lx + lw + 8;
        ey = ly;
        ew = wx + ww - 10 - ex;
        eh = lh;
        Draw.rrect(g, ex, ey, ew, eh, 8, Draw.withAlpha(0x000000, 0.22f * a));
        if (selected == null) {
            float cy = ey + eh / 2 - 30;
            Draw.icon(g, Icons.PIN, ex + ew / 2, cy, 22, Draw.withAlpha(Theme.accent(), 0.8f * a));
            Draw.centered(g, "Select a waypoint", ex + ew / 2, cy + 18, 1f, Draw.withAlpha(Theme.TEXT, a));
            Draw.centered(g, "or create a new one", ex + ew / 2, cy + 30, 0.8f, Draw.withAlpha(Theme.TEXT_DIM, a));
            float bw = 120;
            action(g, "+ New waypoint here", ex + ew / 2 - bw / 2, cy + 46, bw, 18, true, false, mx, my, a, this::newWaypoint);
            return;
        }
        float barH = 26;
        float top = ey + 4;
        if (selected instanceof Waypoint w) {
            // en-tête : titre + aperçu de l'étiquette
            boolean isNew = w == creating;
            Draw.text(g, isNew ? (w.area ? "New zone" : "New waypoint") : "Edit", ex + 9, ey + 8, 1f, Draw.withAlpha(Theme.TEXT, a));
            String where = dimName(w.dim) + (w.area ? "  ·  " + w.sizeText() : "");
            Draw.text(g, where, ex + 9, ey + 20, 0.75f, Draw.withAlpha(Theme.TEXT_MUTED, a));
            float pvw = Math.min(150, ew * 0.5f), pvh = 40, pvx = ex + ew - pvw - 6, pvy = ey + 5;
            Draw.rrect(g, pvx, pvy, pvw, pvh, 6, Draw.withAlpha(0x2A2F38, a));
            for (int i = 0; i * 8 < pvw - 6; i++) for (int j = 0; j * 8 < pvh - 6; j++) if ((i + j) % 2 == 0)
                g.fill((int) (pvx + 3 + i * 8), (int) (pvy + 3 + j * 8), (int) Math.min(pvx + pvw - 3, pvx + 3 + i * 8 + 8),
                        (int) Math.min(pvy + pvh - 3, pvy + 3 + j * 8 + 8), Draw.withAlpha(0x343A45, a));
            double d = distance(w);
            Waypoints.drawLabel(g, font, w, pvx + pvw / 2, pvy + pvh / 2 + 6, d < 0 ? 50 : d, 1f);
            top = ey + 50;
            // barre d'actions
            float by = ey + eh - barH + 4, half = (ew - 15) / 2;
            if (isNew) {
                action(g, "Cancel", ex + 5, by, half, 18, false, false, mx, my, a, () -> {
                    creating = null;
                    select(null);
                });
                action(g, "Create waypoint", ex + 10 + half, by, half, 18, true, false, mx, my, a, () -> {
                    if (w.name.isBlank()) w.name = "Waypoint";
                    Waypoints.LIST.add(w);
                    creating = null;
                    Waypoints.save();
                    select(w);
                });
            } else {
                boolean confirm = System.currentTimeMillis() < confirmDeleteUntil;
                Waypoint.Folder f = Waypoints.folder(w.folder);
                boolean locked = f != null && f.locked;
                action(g, "Duplicate", ex + 5, by, half, 18, false, false, mx, my, a, () -> {
                    Waypoint c = w.copy();
                    c.name = w.name + " (copy)";
                    Waypoints.LIST.add(c);
                    Waypoints.save();
                    select(c);
                });
                action(g, locked ? "Locked" : confirm ? "Confirm?" : "Delete", ex + 10 + half, by, half, 18, false, !locked, mx, my, a, () -> {
                    if (locked) return;
                    if (!confirm) {
                        confirmDeleteUntil = System.currentTimeMillis() + 2500;
                        return;
                    }
                    Waypoints.LIST.remove(w);
                    Waypoints.save();
                    select(null);
                });
            }
        } else if (selected instanceof Waypoint.Folder f) {
            Draw.text(g, "Folder", ex + 9, ey + 8, 1f, Draw.withAlpha(Theme.TEXT, a));
            Draw.text(g, count(f) + " waypoint(s)", ex + 9, ey + 20, 0.75f, Draw.withAlpha(Theme.TEXT_MUTED, a));
            top = ey + 32;
            float by = ey + eh - barH + 4;
            boolean confirm = System.currentTimeMillis() < confirmDeleteUntil;
            action(g, f.locked ? "Locked" : confirm ? "Confirm: the waypoints stay" : "Delete folder", ex + 5, by, ew - 10, 18, false, !f.locked,
                    mx, my, a, () -> {
                        if (f.locked) return;
                        if (!confirm) {
                            confirmDeleteUntil = System.currentTimeMillis() + 2500;
                            return;
                        }
                        for (Waypoint w : Waypoints.LIST) if (w.folder.equals(f.id)) w.folder = "";
                        Waypoints.FOLDERS.remove(f);
                        Waypoints.save();
                        select(null);
                    });
        }
        Draw.rrect(g, ex + 6, ey + eh - barH, ew - 12, 0.7f, 0.35f, Draw.withAlpha(0xFFFFFF, 0.08f * a));
        panel.render(g, ex, top, ew, ey + eh - barH - 2 - top, mx, my, a);
    }

    private static String dimName(String dim) {
        return switch (dim) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "End";
            default -> dim;
        };
    }

    /** Bouton d'action (primaire = accent, danger = rouge). Mémorisé pour le clic. */
    private void action(GuiGraphics g, String label, float x, float y, float w, float h, boolean primary, boolean danger,
                        int mx, int my, float a, Runnable run) {
        boolean hv = inside(mx, my, x, y, w, h);
        pointer |= hv;
        int fill = primary ? Draw.lerpColor(Theme.accent(), 0xFFFFFF, hv ? 0.12f : 0) : danger ? (hv ? 0x8A2A2A : 0x4A2226) : hv ? Theme.tileHover() : Theme.ROW;
        int border = primary ? 0xFFFFFF : danger ? 0xE8473E : Theme.accent();
        Draw.glass(g, x, y, w, h, h / 2, Draw.withAlpha(fill, 0.95f * a), Draw.withAlpha(border, (hv ? 0.7f : 0.3f) * a));
        float sc = Math.min(0.88f, (w - 8) / Math.max(1, Draw.width(label, 1f)));
        Draw.centered(g, label, x + w / 2, y + h / 2 - 3.5f, sc, Draw.withAlpha(primary ? 0xFFFFFF : danger ? 0xFFC9C9 : Theme.TEXT, a));
        actionRects.add(new float[]{x, y, w, h});
        actionRuns.add(run);
    }

    /** Dev : fait défiler l'éditeur. */
    public void debugScroll(double amount) {
        panel.scroll(ex + 20, ey + 80, amount);
    }

    /** Dev : sélectionne un élément. */
    public void debugSelect(Object o) {
        creating = null;
        select(o);
    }

    private void newWaypoint() {
        if (minecraft.player == null) return;
        creating = Waypoints.create(minecraft);
        select(creating);
    }

    private void newFolder() {
        Waypoint.Folder f = new Waypoint.Folder();
        f.name = "Folder " + (Waypoints.FOLDERS.size() + 1);
        Waypoints.FOLDERS.add(f);
        Waypoints.save();
        select(f);
    }

    // ===================================================================== entrées

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean dbl) {
        double mx = event.x(), my = event.y();
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(event, dbl);
        if (inside(mx, my, wx + ww - 23, wy + 7, 16, 16)) {
            onClose();
            return true;
        }
        for (int i = 0; i < actionRects.size(); i++) {
            float[] r = actionRects.get(i);
            if (inside(mx, my, r[0], r[1], r[2], r[3])) {
                click(1f);
                actionRuns.get(i).run();
                return true;
            }
        }
        searchFocus = inside(mx, my, lx + 5, ly + 5, lw - 10, 16);
        if (searchFocus) return true;
        float pw = (lw - 10 - 3 * 3) / 4;
        for (int i = 0; i < DIMS.length; i++) {
            if (inside(mx, my, lx + 5 + i * (pw + 3), ly + 25, pw, 13)) {
                dimFilter = i;
                click(1.1f);
                return true;
            }
        }
        float listY = ly + 43, listH = lh - 43 - 26;
        if (inside(mx, my, lx, listY, lw, listH)) {
            for (Line l : lines) {
                float ry = listY + l.y() - scroll;
                if (my < ry || my >= ry + l.h()) continue;
                float eyeX = lx + 4 + lw - 8 - 10;
                boolean eye = mx >= eyeX - 8;
                if (l.item() instanceof Waypoint.Folder f) {
                    if (eye) f.visible = !f.visible;
                    else if (mx < lx + 4 + 10 || selected == f || dbl) f.collapsed = !f.collapsed;
                    else select(f);
                } else if (l.item() instanceof Waypoint w) {
                    if (eye) w.visible = !w.visible;
                    else {
                        creating = null;
                        select(w);
                    }
                }
                Waypoints.save();
                click(1.05f);
                return true;
            }
            return true;
        }
        if (panel.click(mx, my)) return true;
        return super.mouseClicked(event, dbl);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return panel.drag(event.x(), event.y()) || super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (panel.release() && creating == null) Waypoints.save();
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (inside(mx, my, lx, ly, lw, lh)) scrollTarget -= (float) sy * 30;
        else panel.scroll(mx, my, sy);
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (!event.isAllowedChatCharacter()) return super.charTyped(event);
        if (searchFocus) {
            if (search.length() < 32) search += event.codepointAsString();
            return true;
        }
        return panel.charTyped(event.codepointAsString()) || super.charTyped(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (searchFocus) {
            if (event.key() == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) search = search.substring(0, search.length() - 1);
            else if (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_ENTER) searchFocus = false;
            return true;
        }
        if (panel.focused() != null && panel.keyPressed(event.key(), event.modifiers())) return true;
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        // Ctrl+F : recherche
        if (event.key() == GLFW.GLFW_KEY_F && (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0) {
            searchFocus = true;
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        Waypoints.save();
        minecraft.setScreen(parent);
    }
}
