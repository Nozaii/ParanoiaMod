package gg.aurora.waypoint;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import gg.aurora.Settings;
import gg.aurora.ui.Draw;
import gg.aurora.ui.Icons;
import gg.aurora.ui.ItemPickerScreen;
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
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

/**
 * Waypoints menu. Left: search, dimension filter, folders and waypoints (drag a waypoint onto a folder
 * to move it). Right: a single editor card where every setting has a fixed place, nothing to scroll:
 * name and preview on top, position and folder on the left, style, color and icon on the right,
 * display toggles below, actions at the bottom.
 */
public class WaypointsScreen extends Screen {
    private static final String[] DIMS = {"All", "World", "Nether", "End"};
    private static final String[] DIM_IDS = {"", "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"};
    private static final float ROW_H = 22, FOLDER_H = 18;
    /** List line for the waypoints outside any folder (also a drop target). */
    private static final Object NO_FOLDER = new Object();

    private final Screen parent;
    /** Waypoint being created (not in the list yet), or null. */
    private Waypoint creating;
    private Object selected;
    private final Spring open = new Spring(0, 160, 0.85f);
    private long last = System.nanoTime();
    private float dt;
    private boolean pointer;
    private String search = "";
    private boolean searchFocus;
    private int dimFilter;
    private float scroll, scrollTarget;
    /** Delete confirmation pending (2nd click). */
    private long confirmDeleteUntil;
    // drag & drop of a waypoint onto a folder
    private Waypoint drag;
    private double dragX, dragY;
    private boolean dragging;

    // ---- editor: inline text fields
    private record Field(String key, Supplier<String> get, Consumer<String> set, int max, boolean numeric) {}

    private Field focus;
    private String buf = "";
    /** Fields drawn this frame, in Tab order. */
    private final List<Field> fieldOrder = new ArrayList<>();
    // ---- editor: slider being dragged
    private DoubleConsumer slide;
    private float slideX, slideW;
    // ---- editor: share bar open
    private boolean sharing;
    private String shareTo = "";
    // ---- editor: fallback scroll when the card is taller than the panel (zone + highlight on a small GUI scale)
    private float cardScroll, cardMax;
    private float clipTop = -1e9f, clipBottom = 1e9f;

    private record Line(Object item, int depth, float y, float h) {}

    private final List<Line> lines = new ArrayList<>();
    private float wx, wy, ww, wh, lx, ly, lw, lh, ex, ey, ew, eh;
    /** Clickable areas of this frame (buttons, chips, swatches, fields). */
    private final List<float[]> actionRects = new ArrayList<>();
    private final List<Runnable> actionRuns = new ArrayList<>();

    public WaypointsScreen(Screen parent, Waypoint create) {
        super(Component.literal("Waypoints"));
        this.parent = parent;
        this.creating = create;
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

    private void select(Object o) {
        selected = o;
        confirmDeleteUntil = 0;
        focus = null;
        sharing = false;
        cardScroll = 0;
    }

    private void changed() {
        if (creating == null || selected != creating) Waypoints.save();
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

    // ===================================================================== list

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
        boolean header = !Waypoints.FOLDERS.isEmpty();
        boolean any = false;
        for (Waypoint w : Waypoints.LIST) {
            if (Waypoints.folder(w.folder) != null || !matches(w)) continue;
            if (header && !any) {
                lines.add(new Line(NO_FOLDER, 0, y, FOLDER_H));
                y += FOLDER_H;
            }
            any = true;
            lines.add(new Line(w, header ? 1 : 0, y, ROW_H));
            y += ROW_H;
        }
        // while dragging: the "No folder" target must exist even if empty
        if (header && !any && dragging) lines.add(new Line(NO_FOLDER, 0, y, FOLDER_H));
    }

    private double distance(Waypoint w) {
        if (minecraft.player == null || minecraft.level == null) return -1;
        if (!w.dim.equals(minecraft.level.dimension().identifier().toString())) return -1;
        return Math.sqrt(minecraft.player.distanceToSqr((w.minX() + w.maxX() + 1) / 2.0, (w.minY() + w.maxY() + 1) / 2.0, (w.minZ() + w.maxZ() + 1) / 2.0));
    }

    // ===================================================================== rendering

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

        ww = Math.min(width - 24, 580);
        wh = Math.min(height - 20, 340);
        wx = (width - ww) / 2;
        wy = (height - wh) / 2 + (1 - open.value) * 18;
        Draw.glow(g, wx, wy + 6, ww, wh, 20, Draw.withAlpha(0x000000, 0.6f * a));
        Draw.glass(g, wx, wy, ww, wh, 10, Draw.withAlpha(Theme.PANEL, 0.95f * a), Draw.withAlpha(0xFFFFFF, 0.3f * a));

        // ---- header
        Draw.icon(g, Icons.PIN, wx + 16, wy + 14, 11, Draw.withAlpha(Theme.accent(), a));
        Draw.text(g, "Waypoints", wx + 26, wy + 8.5f, 1.15f, Draw.withAlpha(Theme.TEXT, a));
        String sub = Waypoints.LIST.size() + " here  ·  " + key(Waypoints.openKey) + ": open  ·  " + key(Waypoints.addKey) + ": add at the aimed block";
        Draw.text(g, sub, wx + 34 + Draw.width("Waypoints", 1.15f), wy + 11, 0.75f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        float cx = wx + ww - 22, cy = wy + 6;
        boolean chv = inside(mx, my, cx, cy, 16, 16);
        pointer |= chv;
        Draw.rrect(g, cx, cy, 16, 16, 8, Draw.withAlpha(chv ? 0xE8473E : Theme.ROW, 0.9f * a));
        Draw.centered(g, "×", cx + 8, cy + 3.5f, 1f, Draw.withAlpha(Theme.TEXT, a));

        renderList(g, mx, my, a, now);
        renderEditor(g, mx, my, a);
        if (dragging && drag != null) {
            String n = drag.name;
            float tw = Draw.width(n, 0.85f) + 12;
            Draw.rrect(g, mx + 6, my + 2, tw, 14, 7, Draw.withAlpha(Theme.accent(), 0.95f));
            Draw.text(g, n, mx + 12, my + 5.5f, 0.85f, 0xFFFFFFFF);
        }

        if (pointer) g.requestCursor(CursorTypes.POINTING_HAND);
    }

    private static String key(net.minecraft.client.KeyMapping k) {
        return k == null || k.isUnbound() ? "-" : k.getTranslatedKeyMessage().getString();
    }

    private void renderList(GuiGraphics g, int mx, int my, float a, long now) {
        lx = wx + 10;
        ly = wy + 30;
        lw = Math.max(165, ww * 0.38f);
        lh = wh - 40;
        Draw.rrect(g, lx, ly, lw, lh, 8, Draw.withAlpha(0x000000, 0.22f * a));
        // search field (sunken)
        float sw = lw - 10;
        Draw.rrect(g, lx + 5, ly + 5, sw, 16, 6, Draw.withAlpha(searchFocus ? Theme.ROW_HOVER : Theme.ROW, 0.95f * a));
        if (searchFocus) Draw.ring(g, lx + 5, ly + 5, sw, 16, 6, Draw.withAlpha(Theme.accent(), 0.7f * a));
        pointer |= inside(mx, my, lx + 5, ly + 5, sw, 16);
        Draw.icon(g, Icons.SEARCH, lx + 13, ly + 13, 7, Draw.withAlpha(Theme.TEXT_DIM, a));
        boolean blink = searchFocus && (now / 500_000_000L) % 2 == 0;
        Draw.text(g, search.isEmpty() && !searchFocus ? "Search a waypoint…" : search + (blink ? "|" : ""), lx + 20, ly + 9.5f, 0.85f,
                Draw.withAlpha(search.isEmpty() && !searchFocus ? Theme.TEXT_MUTED : Theme.TEXT, a));
        // dimension filter: square toggle buttons
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
            Draw.centered(g, filtered ? "Change the search or the filter" : "Click « + Waypoint » or press " + key(Waypoints.addKey),
                    lx + lw / 2, cy + 26, 0.72f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        }
        // bottom buttons
        float by = ly + lh - 21, bw = (lw - 16) / 3;
        action(g, "+ Waypoint", lx + 5, by, bw, 16, true, false, mx, my, a, this::newWaypoint);
        action(g, "+ Zone", lx + 8 + bw, by, bw, 16, false, false, mx, my, a, () -> {
            Waypoints.save();
            minecraft.setScreen(null);
            Waypoints.startZone();
        });
        action(g, "+ Folder", lx + 11 + bw * 2, by, bw, 16, false, false, mx, my, a, () -> {
            Waypoint.Folder f = addFolder();
            select(f);
            focusName();
        });
    }

    /** Folder a drop at this line would move the dragged waypoint to ("" = no folder, null = none). */
    private String dropFolder(Line l) {
        if (l.item() instanceof Waypoint.Folder f) return f.id;
        if (l.item() == NO_FOLDER) return "";
        if (l.item() instanceof Waypoint w && w != drag) return Waypoints.folder(w.folder) != null ? w.folder : "";
        return null;
    }

    private void drawLine(GuiGraphics g, Line l, float x, float y, float w, int mx, int my, float a, boolean inList) {
        float h = l.h();
        boolean sel = l.item() == selected;
        boolean hv = inList && inside(mx, my, x, y, w, h);
        pointer |= hv && !dragging;
        boolean drop = dragging && hv && dropFolder(l) != null;
        if (drop) {
            Draw.rrect(g, x, y + 1, w, h - 2, 5, Draw.withAlpha(Theme.accent(), 0.35f * a));
            Draw.ring(g, x, y + 1, w, h - 2, 5, Draw.withAlpha(Theme.accent(), a));
        } else if (sel || hv) {
            Draw.rrect(g, x, y + 1, w, h - 2, 5, Draw.withAlpha(sel ? Theme.tileHover() : Theme.ROW_HOVER, (sel ? 0.95f : 0.55f) * a));
            if (sel) Draw.ring(g, x, y + 1, w, h - 2, 5, Draw.withAlpha(Theme.accent(), 0.6f * a));
        }
        float ix = x + 4 + l.depth() * 10;
        float eyeX = x + w - 10;
        if (l.item() instanceof Waypoint.Folder f) {
            // tree guide for the open folder
            g.pose().pushMatrix();
            g.pose().translate(ix + 3, y + h / 2);
            if (f.collapsed) g.pose().rotate((float) Math.toRadians(-90));
            Draw.icon(g, Icons.CARET_DOWN, 0, 0, 7, Draw.withAlpha(Theme.TEXT_DIM, a));
            g.pose().popMatrix();
            Draw.icon(g, Icons.FOLDER, ix + 14, y + h / 2, 9, Draw.withAlpha(f.color, a));
            Draw.text(g, f.name, ix + 22, y + 5, 0.88f, Draw.withAlpha(f.visible ? Theme.TEXT : Theme.TEXT_MUTED, a));
            String n = String.valueOf(count(f));
            float nx = ix + 24 + Draw.width(f.name, 0.88f);
            Draw.rrect(g, nx, y + 4, Draw.width(n, 0.7f) + 7, 10, 5, Draw.withAlpha(0x000000, 0.3f * a));
            Draw.text(g, n, nx + 3.5f, y + 6, 0.7f, Draw.withAlpha(Theme.TEXT_DIM, a));
            if (f.locked) Draw.icon(g, Icons.LOCK, eyeX - 14, y + h / 2, 7, Draw.withAlpha(Theme.accent(), a));
            eye(g, eyeX, y + h / 2, f.visible, mx, my, a);
        } else if (l.item() == NO_FOLDER) {
            Draw.icon(g, Icons.LIST, ix + 3, y + h / 2, 7, Draw.withAlpha(Theme.TEXT_MUTED, a));
            Draw.text(g, "No folder", ix + 12, y + 5.5f, 0.8f, Draw.withAlpha(Theme.TEXT_DIM, a));
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

    // ===================================================================== editor card

    private void renderEditor(GuiGraphics g, int mx, int my, float a) {
        ex = lx + lw + 8;
        ey = ly;
        ew = wx + ww - 10 - ex;
        eh = lh;
        fieldOrder.clear();
        Draw.rrect(g, ex, ey, ew, eh, 8, Draw.withAlpha(0x000000, 0.22f * a));
        if (selected == null) {
            float cy = ey + eh / 2 - 30;
            Draw.icon(g, Icons.PIN, ex + ew / 2, cy, 22, Draw.withAlpha(Theme.accent(), 0.8f * a));
            Draw.centered(g, "Select a waypoint", ex + ew / 2, cy + 18, 1f, Draw.withAlpha(Theme.TEXT, a));
            Draw.centered(g, "or create a new one", ex + ew / 2, cy + 30, 0.8f, Draw.withAlpha(Theme.TEXT_DIM, a));
            float bw = 130;
            action(g, "+ New waypoint here", ex + ew / 2 - bw / 2, cy + 46, bw, 18, true, false, mx, my, a, this::newWaypoint);
            return;
        }
        g.enableScissor((int) ex, (int) ey, (int) (ex + ew), (int) (ey + eh));
        if (selected instanceof Waypoint w) waypointCard(g, w, mx, my, a);
        else if (selected instanceof Waypoint.Folder f) folderCard(g, f, mx, my, a);
        g.disableScissor();
    }

    private static final float PAD = 10, GAP = 14;

    private void waypointCard(GuiGraphics g, Waypoint w, int mx, int my, float a) {
        boolean isNew = w == creating;
        float x0 = ex + PAD, top = ey + PAD - cardScroll, W = ew - 2 * PAD;
        float by = ey + eh - 24, bh = 17;
        g.enableScissor((int) ex, (int) ey, (int) (ex + ew), (int) (by - 6));
        clipTop = ey;
        clipBottom = by - 6;

        // ---- top: name + meta, live preview on the right
        float pvw = Math.min(118, W * 0.38f), pvh = 28, pvx = ex + ew - PAD - pvw;
        Draw.rrect(g, pvx, top, pvw, pvh, 6, Draw.withAlpha(0x2A2F38, a));
        for (int i = 0; i * 8 < pvw - 6; i++) for (int j = 0; j * 8 < pvh - 6; j++) if ((i + j) % 2 == 0)
            g.fill((int) (pvx + 3 + i * 8), (int) (top + 3 + j * 8), (int) Math.min(pvx + pvw - 3, pvx + 3 + i * 8 + 8),
                    (int) Math.min(top + pvh - 3, top + 3 + j * 8 + 8), Draw.withAlpha(0x343A45, a));
        double d = distance(w);
        Waypoints.drawLabel(g, font, w, pvx + pvw / 2, top + pvh / 2 + 7, d < 0 ? 50 : d, 0.85f);
        float nameW = pvx - 8 - x0;
        bigField(g, new Field("name", () -> w.name, v -> w.name = v, 32, false), x0 - 3, top - 2, nameW + 3, isNew ? "Name your waypoint" : "Name", mx, my, a);
        Waypoint.Folder in = Waypoints.folder(w.folder);
        StringBuilder meta = new StringBuilder(dimName(w.dim));
        if (w.area) meta.append("  ·  ").append(w.sizeText());
        if (d >= 0) meta.append("  ·  ").append(Waypoints.distText(d));
        float mxo = x0;
        if (isNew) {
            String t = w.area ? "NEW ZONE" : "NEW";
            float tw = Draw.width(t, 0.6f) + 8;
            Draw.rrect(g, x0, top + 19, tw, 10, 5, Draw.withAlpha(Theme.accent(), 0.9f * a));
            Draw.text(g, t, x0 + 4, top + 21.5f, 0.6f, Draw.withAlpha(0xFFFFFF, a));
            mxo += tw + 5;
        }
        Draw.text(g, meta.toString(), mxo, top + 20.5f, 0.72f, Draw.withAlpha(Theme.TEXT_MUTED, a));

        // ---- one column, always the same order: where, folder, style, color + icon, display
        float y = top + pvh + 7;
        Draw.rect(g, x0, y - 4, W, 1, Draw.withAlpha(0xFFFFFF, 0.06f * a));
        caption(g, w.area ? "CORNERS" : "POSITION", x0, y, a);
        float rx = x0 + W;
        if (w.area) {
            float rw = Draw.width("Reselect with the camera", 0.65f);
            rx -= rw;
            link(g, "Reselect with the camera", rx, y - 0.5f, mx, my, a, () -> {
                if (w != creating) Waypoints.LIST.remove(w);
                Waypoints.save();
                minecraft.setScreen(null);
                Waypoints.startZone();
            });
            rx -= 10;
        }
        if (WaypointShare.canTeleport()) {
            float tw = Draw.width("Teleport", 0.65f);
            link(g, "Teleport", rx - tw, y - 0.5f, mx, my, a, () -> WaypointShare.teleport(w));
        }
        if (w.area) { // both corners on one line
            float half = (W - 10) / 2;
            xyz(g, w, false, x0, y + 9, half, mx, my, a);
            Draw.icon(g, Icons.CHEVRONS, x0 + half + 5, y + 18, 6, Draw.withAlpha(Theme.TEXT_MUTED, a));
            y = xyz(g, w, true, x0 + half + 10, y + 9, half, mx, my, a);
        } else {
            y = xyz(g, w, false, x0, y + 9, W, mx, my, a);
        }

        y += 6;
        caption(g, "FOLDER", x0, y, a);
        y = folderChips(g, w, x0, y + 9, W, mx, my, a);

        y += 6;
        caption(g, "STYLE  ·  " + Waypoint.PRESETS[Math.max(0, Math.min(Waypoint.PRESETS.length - 1, w.preset))].toUpperCase(Locale.ROOT), x0, y, a);
        y = styles(g, w, x0, y + 9, W, mx, my, a);

        // color on the left, icon on the right, same line
        y += 6;
        float iconW = 124;
        caption(g, "COLOR", x0, y, a);
        caption(g, "ICON", x0 + W - iconW, y, a);
        float cy = y + 9;
        colors(g, () -> w.color, v -> w.color = v, x0, cy + 4, W - iconW - 12, true, mx, my, a);
        icon(g, w, x0 + W - iconW, cy, iconW, mx, my, a);
        y = cy + 20;

        // ---- display
        y += 6;
        Draw.rect(g, x0, y - 4, W, 1, Draw.withAlpha(0xFFFFFF, 0.06f * a));
        float cx = x0;
        cx += chip(g, Icons.LAYERS, w.area ? "Show zone" : "Highlight block", w.block, cx, y, mx, my, a, () -> w.block = !w.block) + 4;
        float end = y + 15;
        if (w.block) {
            cx += chip(g, Icons.GLOBE, "Through walls", w.walls, cx, y, mx, my, a, () -> w.walls = !w.walls) + 10;
            float rem = x0 + W - cx;
            if (rem >= 190) { // room on the same line
                float sw = (rem - 10) / 2;
                slider(g, "Fill", w.fill, v -> w.fill = (float) v, cx, y + 2.5f, sw, mx, my, a);
                slider(g, "Outline", w.outline, v -> w.outline = (float) v, cx + sw + 10, y + 2.5f, sw, mx, my, a);
            } else {
                float sw = (W - 14) / 2, sy = y + 20;
                slider(g, "Fill", w.fill, v -> w.fill = (float) v, x0, sy, sw, mx, my, a);
                slider(g, "Outline", w.outline, v -> w.outline = (float) v, x0 + sw + 14, sy, sw, mx, my, a);
                end = sy + 10;
            }
        }
        g.disableScissor();
        clipTop = -1e9f;
        clipBottom = 1e9f;
        // only scrolls when it really does not fit
        cardMax = end + cardScroll - (by - 8) > 2 ? end + cardScroll - (by - 8) : 0;
        cardScroll = Math.max(0, Math.min(cardMax, cardScroll));
        if (cardMax > 2) {
            float vh = by - 6 - ey, barH = Math.max(12, vh * vh / (vh + cardMax));
            Draw.rrect(g, ex + ew - 4, ey + (vh - barH) * (cardScroll / cardMax), 2, barH, 1, Draw.withAlpha(Theme.accent(), 0.6f * a));
        }

        // ---- actions
        Draw.rect(g, x0, by - 5, W, 1, Draw.withAlpha(0xFFFFFF, 0.06f * a));
        if (sharing) {
            float fw = W - 3 * 52 - 12;
            smallField(g, new Field("share", () -> shareTo, v -> shareTo = v, 16, false), x0, by, fw, bh, "Player name", null, mx, my, a);
            action(g, "Send", x0 + fw + 4, by, 52, bh, true, false, mx, my, a, () -> {
                if (!shareTo.isBlank()) WaypointShare.confirm(this, w, shareTo.trim());
            });
            action(g, "In chat", x0 + fw + 60, by, 52, bh, false, false, mx, my, a, () -> WaypointShare.confirm(this, w, null));
            action(g, "Close", x0 + fw + 116, by, 52, bh, false, false, mx, my, a, () -> sharing = false);
        } else if (isNew) {
            float half = (W - 4) / 2;
            action(g, "Cancel", x0, by, half, bh, false, false, mx, my, a, () -> {
                creating = null;
                select(null);
            });
            action(g, w.area ? "Create zone" : "Create waypoint", x0 + half + 4, by, half, bh, true, false, mx, my, a, () -> {
                if (w.name.isBlank()) w.name = w.area ? "Zone" : "Waypoint";
                Waypoints.LIST.add(w);
                creating = null;
                Waypoints.save();
                select(w);
            });
        } else {
            float third = (W - 8) / 3;
            boolean confirm = System.currentTimeMillis() < confirmDeleteUntil;
            boolean locked = in != null && in.locked;
            action(g, "Share", x0, by, third, bh, false, false, mx, my, a, () -> sharing = true);
            action(g, "Duplicate", x0 + third + 4, by, third, bh, false, false, mx, my, a, () -> {
                Waypoint c = w.copy();
                c.name = w.name + " (copy)";
                Waypoints.LIST.add(c);
                Waypoints.save();
                select(c);
            });
            action(g, locked ? "Locked" : confirm ? "Sure?" : "Delete", x0 + 2 * third + 8, by, third, bh, false, !locked, mx, my, a, () -> {
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
    }

    private void folderCard(GuiGraphics g, Waypoint.Folder f, int mx, int my, float a) {
        float x0 = ex + PAD, top = ey + PAD, W = ew - 2 * PAD;
        Draw.icon(g, Icons.FOLDER, x0 + 6, top + 9, 12, Draw.withAlpha(f.color, a));
        bigField(g, new Field("name", () -> f.name, v -> f.name = v, 32, false), x0 + 13, top, W - 13, "Folder name", mx, my, a);
        int n = 0;
        for (Waypoint w : Waypoints.LIST) if (w.folder.equals(f.id)) n++;
        Draw.text(g, n + (n == 1 ? " waypoint" : " waypoints"), x0 + 16, top + 23.5f, 0.72f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        float y = top + 46;
        Draw.rect(g, x0, y - 5, W, 1, Draw.withAlpha(0xFFFFFF, 0.06f * a));
        caption(g, "COLOR", x0, y, a);
        y = colors(g, () -> f.color, v -> f.color = v, x0, y + 10, Math.min(W, 200), false, mx, my, a) + 9;
        caption(g, "DISPLAY", x0, y, a);
        y += 10;
        float cx = x0;
        cx += chip(g, Icons.EYE, "Shown", f.visible, cx, y, mx, my, a, () -> f.visible = !f.visible) + 4;
        chip(g, Icons.LOCK, "Locked", f.locked, cx, y, mx, my, a, () -> f.locked = !f.locked);
        y += 24;
        chip(g, Icons.PIN, "New waypoint in this folder", false, x0, y, mx, my, a, this::newWaypoint);
        Draw.text(g, "Tip: drag waypoints from the list onto a folder", x0, y + 22, 0.66f, Draw.withAlpha(Theme.TEXT_MUTED, a));

        float by = ey + eh - 24;
        Draw.rect(g, x0, by - 5, W, 1, Draw.withAlpha(0xFFFFFF, 0.06f * a));
        boolean confirm = System.currentTimeMillis() < confirmDeleteUntil;
        action(g, f.locked ? "Locked" : confirm ? "Sure? Its waypoints stay, without folder" : "Delete folder", x0, by, W, 17, false, !f.locked,
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

    // ===================================================================== editor widgets

    private void hit(float x, float y, float w, float h, Runnable run) {
        // clipped by the card's scroll area: only the visible part is clickable
        float y0 = Math.max(y, clipTop), y1 = Math.min(y + h, clipBottom);
        if (y1 <= y0) return;
        y = y0;
        h = y1 - y0;
        actionRects.add(new float[]{x, y, w, h});
        actionRuns.add(run);
    }

    private static void caption(GuiGraphics g, String s, float x, float y, float a) {
        Draw.text(g, s, x, y, 0.62f, Draw.withAlpha(Theme.TEXT_MUTED, a));
    }

    private void link(GuiGraphics g, String s, float x, float y, int mx, int my, float a, Runnable run) {
        float w = Draw.width(s, 0.65f);
        boolean hv = inside(mx, my, x - 2, y - 2, w + 4, 9);
        pointer |= hv;
        Draw.text(g, s, x, y, 0.65f, Draw.withAlpha(Theme.accent(), (hv ? 1f : 0.8f) * a));
        if (hv) Draw.rect(g, x, y + 6.5f, w, 0.6f, Draw.withAlpha(Theme.accent(), a));
        hit(x - 2, y - 2, w + 4, 9, () -> {
            run.run();
            changed();
        });
    }

    private boolean focused(Field f) {
        return focus != null && focus.key().equals(f.key());
    }

    private void focusField(Field f) {
        focus = f;
        buf = f.get().get();
        fresh = false;
    }

    /** True right after « + Folder »: the first key typed replaces the default name. */
    private boolean fresh;

    private String shown(Field f, boolean on) {
        boolean blink = on && (System.nanoTime() / 500_000_000L) % 2 == 0;
        return (on ? buf : f.get().get()) + (blink ? "|" : "");
    }

    /** Title-size field without a box: a box only shows on hover / while typing. */
    private void bigField(GuiGraphics g, Field f, float x, float y, float w, String placeholder, int mx, int my, float a) {
        fieldOrder.add(f);
        boolean on = focused(f), hv = inside(mx, my, x, y, w, 19);
        pointer |= hv && !on;
        if (on || hv) Draw.rrect(g, x, y, w, 19, 5, Draw.withAlpha(on ? Theme.ROW_HOVER : Theme.ROW, (on ? 0.95f : 0.6f) * a));
        if (on) Draw.ring(g, x, y, w, 19, 5, Draw.withAlpha(Theme.accent(), 0.7f * a));
        String t = shown(f, on);
        boolean empty = f.get().get().isEmpty() && !on;
        float sc = 1.15f;
        while (t.length() > 1 && Draw.width(t, sc) > w - 18) t = t.substring(1);
        Draw.text(g, empty ? placeholder : t, x + 4, y + 4.5f, sc, Draw.withAlpha(empty ? Theme.TEXT_MUTED : Theme.TEXT, a));
        if (hv && !on) Draw.icon(g, Icons.PENCIL, x + w - 8, y + 9.5f, 7, Draw.withAlpha(Theme.TEXT_DIM, a));
        hit(x, y, w, 19, () -> focusField(f));
    }

    private void smallField(GuiGraphics g, Field f, float x, float y, float w, float h, String placeholder, String prefix, int mx, int my, float a) {
        fieldOrder.add(f);
        boolean on = focused(f), hv = inside(mx, my, x, y, w, h);
        pointer |= hv && !on;
        Draw.rrect(g, x, y, w, h, 5, Draw.withAlpha(on ? Theme.ROW_HOVER : hv ? Draw.lerpColor(Theme.ROW, Theme.ROW_HOVER, 0.5f) : Theme.ROW, 0.95f * a));
        if (on) Draw.ring(g, x, y, w, h, 5, Draw.withAlpha(Theme.accent(), 0.8f * a));
        float tx = x + 5;
        if (prefix != null) {
            Draw.text(g, prefix, tx, y + h / 2 - 3, 0.72f, Draw.withAlpha(Theme.TEXT_MUTED, a));
            tx += Draw.width(prefix, 0.72f) + 4;
        }
        String t = shown(f, on);
        boolean empty = f.get().get().isEmpty() && !on;
        while (t.length() > 1 && Draw.width(t, 0.82f) > x + w - 4 - tx) t = t.substring(1);
        Draw.text(g, empty ? placeholder : t, tx, y + h / 2 - 3.2f, 0.82f, Draw.withAlpha(empty ? Theme.TEXT_MUTED : Theme.TEXT, a));
        hit(x, y, w, h, () -> focusField(f));
    }

    /** X / Y / Z on one line + a button that takes the current position. Returns the next y. */
    private float xyz(GuiGraphics g, Waypoint w, boolean second, float x, float y, float colW, int mx, int my, float a) {
        float h = 16, bw = 16, fw = (colW - bw - 3 * 3) / 3;
        String sfx = second ? "2" : "";
        smallField(g, new Field("x" + sfx, () -> String.valueOf(second ? w.x2 : w.x), v -> {
            if (second) w.x2 = parseInt(v, w.x2);
            else w.x = parseInt(v, w.x);
        }, 9, true), x, y, fw, h, null, "X", mx, my, a);
        smallField(g, new Field("y" + sfx, () -> String.valueOf(second ? w.y2 : w.y), v -> {
            if (second) w.y2 = parseInt(v, w.y2);
            else w.y = parseInt(v, w.y);
        }, 9, true), x + fw + 3, y, fw, h, null, "Y", mx, my, a);
        smallField(g, new Field("z" + sfx, () -> String.valueOf(second ? w.z2 : w.z), v -> {
            if (second) w.z2 = parseInt(v, w.z2);
            else w.z = parseInt(v, w.z);
        }, 9, true), x + 2 * (fw + 3), y, fw, h, null, "Z", mx, my, a);
        float bx = x + colW - bw;
        boolean hv = inside(mx, my, bx, y, bw, h);
        pointer |= hv;
        Draw.rrect(g, bx, y, bw, h, 5, Draw.withAlpha(hv ? Theme.accent() : Theme.ROW, 0.95f * a));
        Draw.icon(g, Icons.PIN, bx + bw / 2, y + h / 2, 8, Draw.withAlpha(hv ? 0xFFFFFF : Theme.accent(), a));
        if (hv) tooltip(g, "Use my position", bx + bw / 2, y - 11, a);
        hit(bx, y, bw, h, () -> {
            if (minecraft.player == null) return;
            BlockPos p = second ? minecraft.player.blockPosition() : minecraft.player.blockPosition().below();
            if (second) {
                w.x2 = p.getX();
                w.y2 = p.getY();
                w.z2 = p.getZ();
            } else {
                w.x = p.getX();
                w.y = p.getY();
                w.z = p.getZ();
                w.dim = minecraft.level.dimension().identifier().toString();
            }
            focus = null;
            changed();
        });
        return y + h;
    }

    private void tooltip(GuiGraphics g, String s, float cx, float y, float a) {
        float tw = Draw.width(s, 0.7f) + 8;
        Draw.rrect(g, cx - tw / 2, y, tw, 10, 4, Draw.withAlpha(0x000000, 0.85f * a));
        Draw.text(g, s, cx - tw / 2 + 4, y + 2.2f, 0.7f, Draw.withAlpha(0xFFFFFF, a));
    }

    /** Folder chips that wrap: No folder, each folder, + New. Returns the next y. */
    private float folderChips(GuiGraphics g, Waypoint w, float x, float y, float colW, int mx, int my, float a) {
        float cx = x, cy = y, h = 14;
        List<Waypoint.Folder> fs = Waypoints.FOLDERS;
        for (int i = -1; i <= fs.size(); i++) {
            Waypoint.Folder f = i >= 0 && i < fs.size() ? fs.get(i) : null;
            boolean add = i == fs.size();
            String label = add ? "+ New" : f == null ? "None" : f.name;
            float cw = Draw.width(label, 0.75f) + (f != null ? 17 : 10);
            if (cx + cw > x + colW && cx > x) {
                cx = x;
                cy += h + 3;
            }
            String id = f == null ? "" : f.id;
            boolean sel = !add && id.equals(w.folder);
            boolean hv = inside(mx, my, cx, cy, cw, h);
            pointer |= hv && !sel;
            if (sel) Draw.rrect(g, cx, cy, cw, h, 7, Draw.withAlpha(Theme.accent(), 0.95f * a));
            else {
                Draw.rrect(g, cx, cy, cw, h, 7, Draw.withAlpha(hv ? Theme.ROW_HOVER : Theme.ROW, 0.9f * a));
                if (add) Draw.ring(g, cx, cy, cw, h, 7, Draw.withAlpha(Theme.accent(), 0.5f * a));
            }
            float tx = cx + 5;
            if (f != null) {
                Draw.rrect(g, cx + 5, cy + h / 2 - 2.5f, 5, 5, 2.5f, Draw.withAlpha(f.color, a));
                tx += 7;
            }
            Draw.text(g, label, tx, cy + 3.8f, 0.75f, Draw.withAlpha(sel ? 0xFFFFFF : add ? Theme.accent() : Theme.TEXT, a));
            final Waypoint.Folder ff = f;
            hit(cx, cy, cw, h, () -> {
                if (add) {
                    Waypoint.Folder nf = addFolder();
                    w.folder = nf.id;
                } else {
                    w.folder = ff == null ? "" : ff.id;
                    if (ff != null) ff.collapsed = false;
                }
                changed();
            });
            cx += cw + 3;
        }
        return cy + h;
    }

    /** The 5 label styles as small live thumbnails. Returns the next y. */
    private float styles(GuiGraphics g, Waypoint w, float x, float y, float colW, int mx, int my, float a) {
        int n = Waypoint.PRESETS.length;
        float tw = (colW - (n - 1) * 3) / n, th = 24;
        int saved = w.preset;
        for (int i = 0; i < n; i++) {
            float tx = x + i * (tw + 3);
            boolean sel = i == saved, hv = inside(mx, my, tx, y, tw, th);
            pointer |= hv && !sel;
            Draw.rrect(g, tx, y, tw, th, 5, Draw.withAlpha(sel ? Theme.ROW_HOVER : hv ? Draw.lerpColor(Theme.ROW, Theme.ROW_HOVER, 0.5f) : Theme.ROW, 0.95f * a));
            if (sel) Draw.ring(g, tx, y, tw, th, 5, Draw.withAlpha(Theme.accent(), a));
            g.enableScissor((int) tx + 1, (int) y + 1, (int) (tx + tw - 1), (int) (y + th - 1));
            w.preset = i;
            Waypoints.drawLabel(g, font, w, tx + tw / 2, y + th - 5, 24, 0.5f);
            g.disableScissor();
            if (hv) tooltip(g, Waypoint.PRESETS[i], tx + tw / 2, y - 11, a);
            final int k = i;
            hit(tx, y, tw, th, () -> {
                w.preset = k;
                changed();
            });
        }
        w.preset = saved;
        return y + th;
    }

    /** Color dots + hex field. Returns the next y. */
    private float colors(GuiGraphics g, Supplier<Integer> get, Consumer<Integer> set, float x, float y, float colW, boolean hex, int mx, int my, float a) {
        int[] cols = WaypointRows.Palette.COLORS;
        // the hex field only shows when there is room for it next to full-size dots
        if (hex && colW < cols.length * 15 + 56) hex = false;
        float hexW = hex ? 50 : 0;
        float s = Math.min(12, (colW - hexW - (hex ? 6 : 0) - (cols.length - 1) * 3) / cols.length);
        int cur = get.get() & 0xFFFFFF;
        for (int i = 0; i < cols.length; i++) {
            float sx = x + i * (s + 3);
            boolean hv = inside(mx, my, sx, y, s, s);
            pointer |= hv;
            if (cols[i] == cur) Draw.ring(g, sx - 2, y - 2, s + 4, s + 4, (s + 4) / 2, Draw.withAlpha(0xFFFFFF, 0.9f * a));
            Draw.rrect(g, sx, y, s, s, s / 2, Draw.withAlpha(cols[i], a));
            if (hv && cols[i] != cur) Draw.ring(g, sx, y, s, s, s / 2, Draw.withAlpha(0xFFFFFF, 0.5f * a));
            final int c = cols[i];
            hit(sx, y, s, s, () -> {
                set.accept(c);
                changed();
            });
        }
        if (hex) {
            float hx = x + colW - hexW;
            smallField(g, new Field("hex", () -> String.format(Locale.ROOT, "%06X", get.get() & 0xFFFFFF), v -> {
                String t = v.replace("#", "").trim();
                if (t.length() == 6) {
                    try {
                        set.accept(Integer.parseInt(t, 16));
                    } catch (NumberFormatException ignored) {
                        // keep the old color until the code is valid
                    }
                }
            }, 7, false), hx, y - 1.5f, hexW, s + 3, null, "#", mx, my, a);
        }
        return y + s;
    }

    /** Icon slot (click: pick an item) + kind switch + "held item". Returns the next y. */
    private float icon(GuiGraphics g, Waypoint w, float x, float y, float colW, int mx, int my, float a) {
        float s = 20;
        boolean hv = inside(mx, my, x, y, s, s);
        pointer |= hv;
        Draw.rrect(g, x, y, s, s, 5, Draw.withAlpha(hv ? Theme.ROW_HOVER : Theme.ROW, 0.95f * a));
        Draw.ring(g, x, y, s, s, 5, Draw.withAlpha(Theme.accent(), (hv ? 0.8f : 0.3f) * a));
        ItemStack st = Waypoints.iconStack(w);
        if (w.iconType == 1 && !st.isEmpty()) g.renderItem(st, Math.round(x + 2), Math.round(y + 2));
        else if (w.iconType == 2) Draw.centered(g, w.name.isEmpty() ? "?" : w.name.substring(0, 1).toUpperCase(), x + s / 2, y + 6.5f, 0.9f, Draw.withAlpha(Theme.TEXT, a));
        else Draw.rrect(g, x + s / 2 - 1.5f, y + s / 2 - 1.5f, 3, 3, 1.5f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        if (hv) tooltip(g, st.isEmpty() ? "Pick an item" : st.getHoverName().getString(), x + s / 2, y - 11, a);
        hit(x, y, s, s, () -> minecraft.setScreen(new ItemPickerScreen(this, w.icon, v -> {
            w.icon = v;
            w.iconType = 1;
            changed();
        })));
        // kind: none / item / initial, as a small segmented switch
        String[] kinds = {"None", "Item", "Aa"};
        float kx = x + s + 6, kh = 14, ky = y + (s - kh) / 2;
        float kw = 0;
        for (String k : kinds) kw += Draw.width(k, 0.72f) + 10;
        Draw.rrect(g, kx, ky, kw + 2, kh, 7, Draw.withAlpha(Theme.ROW, 0.9f * a));
        float cx = kx + 1;
        for (int i = 0; i < kinds.length; i++) {
            float cw = Draw.width(kinds[i], 0.72f) + 10;
            boolean sel = w.iconType == i, h2 = inside(mx, my, cx, ky, cw, kh);
            pointer |= h2 && !sel;
            if (sel) Draw.rrect(g, cx, ky + 1, cw, kh - 2, 6, Draw.withAlpha(Theme.accent(), 0.95f * a));
            Draw.text(g, kinds[i], cx + 5, ky + 3.8f, 0.72f, Draw.withAlpha(sel ? 0xFFFFFF : Theme.TEXT_DIM, a));
            final int k = i;
            hit(cx, ky, cw, kh, () -> {
                w.iconType = k;
                changed();
            });
            cx += cw;
        }
        // take the item in hand
        float hx = kx + kw + 8, hw = 16;
        if (hx + hw <= x + colW) {
            boolean h3 = inside(mx, my, hx, ky - 1, hw, kh + 2);
            pointer |= h3;
            Draw.rrect(g, hx, ky - 1, hw, kh + 2, 5, Draw.withAlpha(h3 ? Theme.ROW_HOVER : Theme.ROW, 0.9f * a));
            Draw.icon(g, Icons.BAG, hx + hw / 2, ky + kh / 2, 8, Draw.withAlpha(Theme.TEXT_DIM, a));
            if (h3) tooltip(g, "Use the item in my hand", hx + hw / 2, ky - 12, a);
            hit(hx, ky - 1, hw, kh + 2, () -> {
                if (minecraft.player == null) return;
                ItemStack h = minecraft.player.getMainHandItem();
                if (h.isEmpty()) return;
                w.icon = BuiltInRegistries.ITEM.getKey(h.getItem()).toString();
                w.iconType = 1;
                changed();
            });
        }
        return y + s;
    }

    /** Toggle chip: icon + label, filled when on. Returns its width. */
    private float chip(GuiGraphics g, String icon, String label, boolean on, float x, float y, int mx, int my, float a, Runnable toggle) {
        float h = 15, w = Draw.width(label, 0.75f) + 22;
        boolean hv = inside(mx, my, x, y, w, h);
        pointer |= hv;
        Draw.rrect(g, x, y, w, h, 7.5f, Draw.withAlpha(on ? Theme.accent() : hv ? Theme.ROW_HOVER : Theme.ROW, (on ? 0.9f : 0.9f) * a));
        if (!on) Draw.ring(g, x, y, w, h, 7.5f, Draw.withAlpha(0xFFFFFF, 0.1f * a));
        Draw.icon(g, icon, x + 9, y + h / 2, 7, Draw.withAlpha(on ? 0xFFFFFF : Theme.TEXT_DIM, a));
        Draw.text(g, label, x + 16, y + 4.3f, 0.75f, Draw.withAlpha(on ? 0xFFFFFF : Theme.TEXT_DIM, a));
        hit(x, y, w, h, () -> {
            toggle.run();
            changed();
        });
        return w;
    }

    /** Compact slider: label, thin track, value. Drag anywhere on it. */
    private void slider(GuiGraphics g, String label, float v, DoubleConsumer set, float x, float y, float w, int mx, int my, float a) {
        float lw0 = 38, vw = 26, tx = x + lw0, tw = w - lw0 - vw;
        Draw.text(g, label, x, y + 2, 0.72f, Draw.withAlpha(Theme.TEXT_DIM, a));
        boolean hv = inside(mx, my, tx - 3, y - 2, tw + 6, 12);
        pointer |= hv;
        Draw.rrect(g, tx, y + 4, tw, 2, 1, Draw.withAlpha(Theme.ROW_HOVER, a));
        Draw.rrect(g, tx, y + 4, tw * v, 2, 1, Draw.withAlpha(Theme.accent(), a));
        float k = tx + tw * v;
        Draw.rrect(g, k - 4, y + 1, 8, 8, 4, Draw.withAlpha(hv ? 0xFFFFFF : 0xE6E4F0, a));
        String t = v < 0.01f ? "None" : Math.round(v * 100) + "%";
        Draw.text(g, t, x + w - Draw.width(t, 0.7f), y + 2, 0.7f, Draw.withAlpha(Theme.TEXT_DIM, a));
        hit(tx - 3, y - 2, tw + 6, 12, () -> {
            slide = set;
            slideX = tx;
            slideW = tw;
        });
    }

    private static String dimName(String dim) {
        return switch (dim) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "End";
            default -> dim;
        };
    }

    /** Action button (primary = accent, danger = red). Remembered for the click. */
    private void action(GuiGraphics g, String label, float x, float y, float w, float h, boolean primary, boolean danger,
                        int mx, int my, float a, Runnable run) {
        boolean hv = inside(mx, my, x, y, w, h);
        pointer |= hv;
        int fill = primary ? Draw.lerpColor(Theme.accent(), 0xFFFFFF, hv ? 0.12f : 0) : danger ? (hv ? 0x8A2A2A : 0x4A2226) : hv ? Theme.tileHover() : Theme.ROW;
        int border = primary ? 0xFFFFFF : danger ? 0xE8473E : Theme.accent();
        Draw.glass(g, x, y, w, h, h / 2, Draw.withAlpha(fill, 0.95f * a), Draw.withAlpha(border, (hv ? 0.7f : 0.3f) * a));
        float sc = Math.min(0.85f, (w - 8) / Math.max(1, Draw.width(label, 1f)));
        Draw.centered(g, label, x + w / 2, y + h / 2 - 3.3f, sc, Draw.withAlpha(primary ? 0xFFFFFF : danger ? 0xFFC9C9 : Theme.TEXT, a));
        hit(x, y, w, h, run);
    }

    /** Dev: selects an item. */
    public void debugSelect(Object o) {
        creating = null;
        select(o);
    }

    /** New waypoint, in the folder you are working in (selected folder, or the selected waypoint's). */
    private void newWaypoint() {
        if (minecraft.player == null) return;
        String folder = selected instanceof Waypoint.Folder f ? f.id : selected instanceof Waypoint w ? w.folder : "";
        creating = Waypoints.create(minecraft);
        creating.folder = folder == null ? "" : folder;
        select(creating);
    }

    private Waypoint.Folder addFolder() {
        Waypoint.Folder f = new Waypoint.Folder();
        f.name = "Folder " + (Waypoints.FOLDERS.size() + 1);
        f.color = WaypointRows.Palette.COLORS[(Waypoints.FOLDERS.size() + 4) % WaypointRows.Palette.COLORS.length];
        Waypoints.FOLDERS.add(f);
        Waypoints.save();
        return f;
    }

    /** New folder: the name field is focused and the first key typed replaces the default name. */
    private void focusName() {
        if (selected instanceof Waypoint.Folder f) {
            focus = new Field("name", () -> f.name, v -> f.name = v, 32, false);
            buf = f.name;
            fresh = true;
        }
    }

    // ===================================================================== input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean dbl) {
        double mx = event.x(), my = event.y();
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(event, dbl);
        if (inside(mx, my, wx + ww - 22, wy + 6, 16, 16)) {
            onClose();
            return true;
        }
        Field before = focus;
        focus = null;
        // last drawn first: tooltips and overlapping chips on top win
        for (int i = actionRects.size() - 1; i >= 0; i--) {
            float[] r = actionRects.get(i);
            if (inside(mx, my, r[0], r[1], r[2], r[3])) {
                actionRuns.get(i).run();
                if (focus == null || before == null || !focus.key().equals(before.key())) click(1f);
                if (slide != null) slideTo(mx);
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
                        if (w != selected) creating = null;
                        select(w);
                        drag = w;
                        dragX = mx;
                        dragY = my;
                        dragging = false;
                    }
                }
                Waypoints.save();
                click(1.05f);
                return true;
            }
            return true;
        }
        return super.mouseClicked(event, dbl);
    }

    private void slideTo(double mx) {
        slide.accept(Math.max(0, Math.min(1, Math.round((mx - slideX) / slideW * 20) / 20.0)));
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (slide != null) {
            slideTo(event.x());
            return true;
        }
        if (drag != null) {
            if (!dragging && Math.hypot(event.x() - dragX, event.y() - dragY) > 4 && !Waypoints.FOLDERS.isEmpty()) dragging = true;
            if (dragging) return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (slide != null) {
            slide = null;
            changed();
            return true;
        }
        if (dragging && drag != null) {
            float listY = ly + 43;
            for (Line l : lines) {
                float ry = listY + l.y() - scroll;
                if (event.y() < ry || event.y() >= ry + l.h() || !inside(event.x(), event.y(), lx, ry, lw, l.h())) continue;
                String to = dropFolder(l);
                if (to != null && !to.equals(drag.folder)) {
                    drag.folder = to;
                    Waypoint.Folder f = Waypoints.folder(to);
                    if (f != null) f.collapsed = false;
                    Waypoints.save();
                    click(1.3f);
                }
                break;
            }
            drag = null;
            dragging = false;
            return true;
        }
        drag = null;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (inside(mx, my, lx, ly, lw, lh)) scrollTarget -= (float) sy * 30;
        else if (inside(mx, my, ex, ey, ew, eh) && cardMax > 0) cardScroll = Math.max(0, Math.min(cardMax, cardScroll - (float) sy * 20));
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (!event.isAllowedChatCharacter()) return super.charTyped(event);
        String c = event.codepointAsString();
        if (searchFocus) {
            if (search.length() < 32) search += c;
            return true;
        }
        if (focus != null) {
            if (focus.numeric() && !c.matches("[0-9-]")) return true;
            if (fresh) {
                buf = "";
                fresh = false;
            }
            if (buf.length() < focus.max()) {
                buf += c;
                focus.set().accept(buf);
                changed();
            }
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int k = event.key();
        if (searchFocus) {
            if (k == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) search = search.substring(0, search.length() - 1);
            else if (k == GLFW.GLFW_KEY_ESCAPE || k == GLFW.GLFW_KEY_ENTER) searchFocus = false;
            return true;
        }
        if (focus != null) {
            if (k == GLFW.GLFW_KEY_BACKSPACE) {
                if (fresh) {
                    buf = "";
                    fresh = false;
                } else if (!buf.isEmpty()) buf = buf.substring(0, buf.length() - 1);
                focus.set().accept(buf);
                changed();
            } else if (k == GLFW.GLFW_KEY_TAB) {
                // next field (Shift: previous), as in a form
                int i = 0;
                for (int j = 0; j < fieldOrder.size(); j++) if (fieldOrder.get(j).key().equals(focus.key())) i = j;
                int n = fieldOrder.size();
                if (n > 0) focusField(fieldOrder.get(((i + ((event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1)) % n + n) % n));
            } else if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER || k == GLFW.GLFW_KEY_ESCAPE) {
                focus = null;
                changed();
            }
            return true;
        }
        if (k == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        // Ctrl+F: search
        if (k == GLFW.GLFW_KEY_F && (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0) {
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
