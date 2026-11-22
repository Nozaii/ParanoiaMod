package gg.aurora.ui;

import gg.aurora.ModManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Mod manager: installed mods (update, disable, remove) and a Modrinth catalog (install). */
public class ModsScreen extends SubScreen {
    private static final String[] TABS = {"Installed", "Browse"};
    private static String query = "";

    private final RowPanel panel;
    private final Spring tabSlide = new Spring(0, 220, 0.75f);
    private final Spring restartHover = new Spring(0, 320, 1f);
    private int tab;
    private Object shown;

    public ModsScreen(Screen parent) {
        super(parent, "Mods");
        panel = new RowPanel(() -> dt, new RowPanel.Callbacks() {
            public void pointer() {
                pointer = true;
            }

            public void sound(float pitch) {
                click(pitch);
            }

            public void changed() {}
        });
        ModManager.scan();
        if (ModManager.results.isEmpty()) ModManager.search(query);
    }

    @Override
    protected float maxW() {
        return 560;
    }

    @Override
    protected float maxH() {
        return 320;
    }

    // ---------------------------------------------------------------- rows

    /** Rebuilds the rows when the background lists change. */
    private void sync() {
        Object now = tab == 0 ? ModManager.installed : ModManager.results;
        if (now == shown) return;
        shown = now;
        panel.setRows(tab == 0 ? installedRows() : browseRows());
    }

    private List<Rows.Row> installedRows() {
        List<Rows.Row> rows = new ArrayList<>();
        int updates = ModManager.updateCount();
        if (updates > 0) {
            rows.add(new Rows.Button("Update all (" + updates + ")", "Downloads every new version from Modrinth", Icons.DOWNLOAD, ModManager::updateAll));
        }
        for (ModManager.Installed i : ModManager.installed) {
            List<Action> actions = new ArrayList<>();
            if (!i.self()) {
                if (i.enabled && !i.loaded) {
                    actions.add(new Action(() -> "Load now", true, () -> ModManager.loadNow(i), () -> i.pending == null && !i.working));
                }
                actions.add(new Action(() -> "Update", true, () -> ModManager.update(i), () -> i.update != null && i.pending == null && !i.working));
                actions.add(new Action(() -> i.enabled ? "Disable" : "Enable", false, () -> ModManager.toggle(i), () -> i.pending == null && !i.working));
                actions.add(new Action(() -> "Remove", false, () -> ModManager.remove(i), () -> i.pending == null && !i.working));
            }
            rows.add(new ModRow(i.name, () -> {
                if (i.working) return "Working…";
                if (i.pending != null) return i.pending;
                String v = i.version.isEmpty() ? i.file : i.version;
                String why = ModManager.refused.get(i.path);
                if (!i.enabled) v += " · disabled";
                else if (!i.loaded) v += why != null ? " · restart needed: " + why : " · not running yet";
                else if (i.modId != null && gg.aurora.HotLoader.LIVE.contains(i.modId)) v += " · loaded live";
                if (i.update != null) v += " · " + i.update.versionNumber + " available";
                if (i.self()) v += " · updated by Paranoia";
                return v;
            }, () -> i.pending != null ? Theme.accent() : i.enabled ? Theme.TEXT_DIM : Theme.TEXT_MUTED, actions));
        }
        return rows;
    }

    private List<Rows.Row> browseRows() {
        List<Rows.Row> rows = new ArrayList<>();
        rows.add(new Rows.TextField("Search Modrinth", "Sodium, Iris, Mod Menu… (Enter)", 60, () -> query, v -> query = v));
        for (ModManager.Hit h : ModManager.results) {
            List<Action> actions = List.of(new Action(() -> h.state != null ? h.state : h.installed() ? "Installed" : "Install", true,
                    () -> ModManager.install(h), () -> h.state == null && !h.installed()));
            rows.add(new ModRow(h.title, () -> h.author + " · " + downloads(h.downloads) + " · " + h.description, () -> Theme.TEXT_DIM, actions));
        }
        return rows;
    }

    private static String downloads(long n) {
        if (n >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", n / 1e6);
        if (n >= 1_000) return String.format(Locale.ROOT, "%.0fk", n / 1e3);
        return Long.toString(n);
    }

    private void search() {
        ModManager.search(query);
        shown = null;
    }

    // ---------------------------------------------------------------- render

    @Override
    protected void renderPage(GuiGraphics g, int mx, int my, float a) {
        sync();
        float tx = wx + 8 + Draw.width("Back", 1f) + 36;
        Draw.text(g, "Mods", tx, wy + 11, 1.05f, Draw.withAlpha(Theme.TEXT, a));
        int hv = tabs(g, TABS, tab, tx + Draw.width("Mods", 1.05f) + 12, wy + 6, tabSlide, mx, my, a);
        hoveredTab = hv;

        if (ModManager.restartNeeded()) {
            String label = "Restart to apply";
            float bw = Draw.width(label, 0.9f) + 20, bh = 16, bx = wx + ww - 10 - bw, by = wy + 8;
            boolean rh = inside(mx, my, bx, by, bw, bh);
            restartHover.update(dt, rh ? 1 : 0);
            pointer |= rh;
            Draw.glow(g, bx, by, bw, bh, 5, Draw.withAlpha(Theme.accent(), (0.2f + 0.2f * restartHover.value) * a));
            Draw.glass(g, bx, by, bw, bh, 8, Draw.withAlpha(Draw.lerpColor(Theme.tileHover(), Theme.accent(), 0.4f + 0.4f * restartHover.value), 0.95f * a),
                    Draw.withAlpha(Theme.accent(), 0.8f * a));
            Draw.text(g, label, bx + 10, by + 4.6f, 0.9f, Draw.withAlpha(0xFFFFFF, a));
        }

        Draw.rrect(g, wx + 8, wy + 28, ww - 16, 0.6f, 0.3f, Draw.withAlpha(0xFFFFFF, 0.07f * a));
        float cx = wx + 10, cy = wy + 34, cw = ww - 20, ch = wh - 44;
        panel.render(g, cx, cy, cw, ch, mx, my, a);

        String note = null;
        boolean busy = tab == 0 ? ModManager.scanning : ModManager.searching;
        boolean empty = tab == 0 ? ModManager.installed.isEmpty() : ModManager.results.isEmpty();
        if (busy && empty) note = tab == 0 ? "Reading the mods folder…" : "Searching Modrinth…";
        else if (ModManager.error != null && empty) note = ModManager.error;
        else if (empty && tab == 1) note = "No Fabric mod found for Minecraft " + ModManager.gameVersion();
        if (note != null) Draw.centered(g, note, wx + ww / 2, cy + ch / 2, 0.9f, Draw.withAlpha(Theme.TEXT_DIM, a));
    }

    private int hoveredTab = -1;

    @Override
    protected boolean clickPage(double mx, double my) {
        if (hoveredTab >= 0 && hoveredTab != tab) {
            click(1.05f);
            tab = hoveredTab;
            shown = null;
            if (tab == 0) ModManager.scan();
            return true;
        }
        if (ModManager.restartNeeded()) {
            String label = "Restart to apply";
            float bw = Draw.width(label, 0.9f) + 20, bx = wx + ww - 10 - bw;
            if (inside(mx, my, bx, wy + 8, bw, 16)) {
                click(1f);
                ModManager.restart();
                return true;
            }
        }
        return panel.click(mx, my);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return panel.drag(event.x(), event.y()) || super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        panel.release();
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        panel.scroll(mx, my, sy);
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (event.isAllowedChatCharacter() && panel.charTyped(event.codepointAsString())) return true;
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (panel.focused() != null) {
            if (tab == 1 && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
                search();
                return true;
            }
            if (panel.keyPressed(event.key(), event.modifiers())) return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean typing() {
        return panel.focused() != null;
    }

    // ---------------------------------------------------------------- row type

    record Action(Supplier<String> label, boolean primary, Runnable run, Supplier<Boolean> enabled) {}

    /** A mod: name, a live status line, and small action buttons on the right. */
    static final class ModRow extends Rows.Row {
        private final Supplier<String> status;
        private final Supplier<Integer> statusColor;
        private final List<Action> actions;
        private final float[] ax, aw;

        ModRow(String name, Supplier<String> status, Supplier<Integer> statusColor, List<Action> actions) {
            super(name, "");
            this.status = status;
            this.statusColor = statusColor;
            this.actions = actions;
            ax = new float[actions.size()];
            aw = new float[actions.size()];
        }

        @Override
        public float height() {
            return 28;
        }

        @Override
        public void draw(Rows.Host h, GuiGraphics g, float x, float y, float w, int mx, int my, float a) {
            boolean hv = Rows.inside(mx, my, x, y, w, height()) && h.inView(my);
            hover.update(h.dt(), hv ? 1 : 0);
            Draw.rrect(g, x, y, w, height(), 6, Draw.withAlpha(Draw.lerpColor(Theme.ROW, Theme.ROW_HOVER, hover.value), 0.9f * a));

            // buttons, right to left
            float bx = x + w - 8;
            for (int i = actions.size() - 1; i >= 0; i--) {
                Action act = actions.get(i);
                String l = act.label().get();
                aw[i] = Draw.width(l, 0.8f) + 14;
                bx -= aw[i];
                ax[i] = bx;
                float by = y + 6;
                boolean on = act.enabled().get();
                boolean bh = on && hv && Rows.inside(mx, my, ax[i], by, aw[i], 16);
                if (bh) h.pointer();
                int fill = !on ? Theme.ROW : act.primary() ? Draw.lerpColor(Theme.tileHover(), Theme.accent(), bh ? 0.75f : 0.45f)
                        : Draw.lerpColor(Theme.TILE, Theme.ROW_HOVER, bh ? 1f : 0.4f);
                Draw.glass(g, ax[i], by, aw[i], 16, 8, Draw.withAlpha(fill, 0.95f * a),
                        Draw.withAlpha(act.primary() && on ? Theme.accent() : 0xFFFFFF, (on ? 0.5f : 0.12f) * a));
                Draw.text(g, l, ax[i] + 7, by + 4.8f, 0.8f, Draw.withAlpha(on ? Theme.TEXT : Theme.TEXT_MUTED, a));
                bx -= 4;
            }

            float max = bx - x - 14;
            Draw.text(g, fit(label, 1f, max), x + 9, y + 5, 1f, Draw.withAlpha(Theme.TEXT, a));
            Draw.text(g, fit(status.get(), 0.78f, max), x + 9, y + 15.5f, 0.78f, Draw.withAlpha(statusColor.get(), a));
        }

        @Override
        public boolean click(Rows.Host h, double mx, double my, float x, float y, float w) {
            for (int i = 0; i < actions.size(); i++) {
                Action act = actions.get(i);
                if (Rows.inside(mx, my, ax[i], y + 6, aw[i], 16) && act.enabled().get()) {
                    h.sound(act.primary() ? 1.15f : 0.95f);
                    act.run().run();
                    return true;
                }
            }
            return false;
        }

        private static String fit(String s, float scale, float max) {
            if (Draw.width(s, scale) <= max) return s;
            while (s.length() > 1 && Draw.width(s + "…", scale) > max) s = s.substring(0, s.length() - 1);
            return s + "…";
        }
    }
}
