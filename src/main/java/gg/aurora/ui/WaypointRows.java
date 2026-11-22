package gg.aurora.ui;

import gg.aurora.waypoint.Waypoint;
import gg.aurora.waypoint.Waypoints;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Lignes spécifiques à l'éditeur de waypoints : titres de section, coordonnées, palette, styles. */
public final class WaypointRows {
    private WaypointRows() {}

    /** Titre de section (sans fond). */
    public static class Header extends Rows.Row {
        final String icon;

        public Header(String title, String icon) {
            super(title, null);
            this.icon = icon;
        }

        @Override
        public float height() {
            return 15;
        }

        @Override
        public void draw(Rows.Host h, GuiGraphics g, float x, float y, float w, int mx, int my, float a) {
            hover.update(h.dt(), 0);
            Draw.icon(g, icon, x + 5, y + 8, 8, Draw.withAlpha(Theme.accent(), a));
            Draw.text(g, label.toUpperCase(), x + 13, y + 4.5f, 0.75f, Draw.withAlpha(Theme.accent(), a));
            float lx = x + 17 + Draw.width(label.toUpperCase(), 0.75f);
            Draw.rrect(g, lx, y + 8, x + w - lx, 0.7f, 0.35f, Draw.withAlpha(Theme.accent(), 0.25f * a));
        }
    }

    /** Objet de l'icône : aperçu + nom, un clic ouvre le sélecteur façon inventaire créatif. */
    public static class ItemPick extends Rows.Row {
        final java.util.function.Supplier<String> get;
        final java.util.function.Consumer<String> set;

        public ItemPick(String label, java.util.function.Supplier<String> get, java.util.function.Consumer<String> set) {
            super(label, "Click to pick from the list");
            this.get = get;
            this.set = set;
        }

        private net.minecraft.world.item.ItemStack stack() {
            var id = net.minecraft.resources.Identifier.tryParse(get.get());
            var item = id == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id);
            return item == null || item == net.minecraft.world.item.Items.AIR ? net.minecraft.world.item.ItemStack.EMPTY
                    : new net.minecraft.world.item.ItemStack(item);
        }

        @Override
        public void draw(Rows.Host h, GuiGraphics g, float x, float y, float w, int mx, int my, float a) {
            base(h, g, x, y, w, mx, my, a);
            if (hover.target > 0) h.pointer();
            var s = stack();
            String name = s.isEmpty() ? "None" : s.getHoverName().getString();
            float nw = Math.min(110, Draw.width(name, 0.85f));
            float bx = x + w - 8 - 22, by = y + (height() - 22) / 2;
            Draw.rrect(g, bx, by, 22, 22, 6, Draw.withAlpha(Draw.lerpColor(0x000000, Theme.accent(), 0.25f + 0.35f * hover.value), 0.6f * a));
            Draw.ring(g, bx, by, 22, 22, 6, Draw.withAlpha(Theme.accent(), (0.5f + 0.4f * hover.value) * a));
            if (!s.isEmpty()) g.renderItem(s, Math.round(bx + 3), Math.round(by + 3));
            String shown = name;
            while (shown.length() > 3 && Draw.width(shown, 0.85f) > nw) shown = shown.substring(0, shown.length() - 2) + "…";
            Draw.text(g, shown, bx - 6 - Draw.width(shown, 0.85f), y + height() / 2 - 3.5f, 0.85f, Draw.withAlpha(Theme.TEXT_DIM, a));
        }

        @Override
        public boolean click(Rows.Host h, double mx, double my, float x, float y, float w) {
            h.sound(1.1f);
            var mc = Minecraft.getInstance();
            mc.setScreen(new ItemPickerScreen(mc.screen, get.get(), v -> {
                set.accept(v);
                h.changed();
            }));
            return true;
        }
    }

    /** X / Y / Z côte à côte + bouton « Ici ». */
    public static class Coords extends Rows.Row {
        final Rows.TextField[] fields;
        final Runnable here;
        float hereX, hereY;

        public Coords(Rows.TextField fx, Rows.TextField fy, Rows.TextField fz, Runnable here) {
            super("Coordinates", null);
            this.fields = new Rows.TextField[]{fx, fy, fz};
            this.here = here;
        }

        @Override
        public float height() {
            return 40;
        }

        private float fieldW(float w) {
            return (w - 44 - 8) / 3;
        }

        @Override
        public void draw(Rows.Host h, GuiGraphics g, float x, float y, float w, int mx, int my, float a) {
            hover.update(h.dt(), 0);
            float fw = fieldW(w);
            for (int i = 0; i < 3; i++) fields[i].draw(h, g, x + i * (fw + 4), y, fw, mx, my, a);
            hereX = x + w - 44;
            hereY = y;
            boolean hv = Rows.inside(mx, my, hereX, y, 44, 40) && h.inView(my);
            if (hv) h.pointer();
            Draw.glass(g, hereX, y, 44, 40, 6, Draw.withAlpha(hv ? Theme.tileHover() : Theme.ROW, 0.92f * a),
                    Draw.withAlpha(Theme.accent(), (hv ? 0.8f : 0.35f) * a));
            Draw.icon(g, Icons.PIN, hereX + 22, y + 15, 10, Draw.withAlpha(Theme.accent(), a));
            Draw.centered(g, "Ici", hereX + 22, y + 25, 0.8f, Draw.withAlpha(Theme.TEXT, a));
        }

        @Override
        public boolean click(Rows.Host h, double mx, double my, float x, float y, float w) {
            if (mx >= x + w - 44) {
                h.sound(1.1f);
                here.run();
                h.changed();
                return true;
            }
            float fw = fieldW(w);
            int i = (int) Math.min(2, Math.max(0, (mx - x) / (fw + 4)));
            return fields[i].click(h, mx, my, x + i * (fw + 4), y, fw);
        }
    }

    /** Pastilles de couleur rapides. */
    public static class Palette extends Rows.Row {
        public static final int[] COLORS = {0xFFFFFF, 0x9AA0AA, 0xE8473E, 0xFF9A2E, 0xFFD43A, 0x34C759, 0x3AB7F0, 0x6B5CFF, 0xFF5FB0};
        final IntSupplier get;
        final IntConsumer set;

        public Palette(String label, IntSupplier get, IntConsumer set) {
            super(label, null);
            this.get = get;
            this.set = set;
        }

        @Override
        public float height() {
            return 24;
        }

        private float sx(float x, float w, int i) {
            return x + w - 8 - (COLORS.length - i) * 15;
        }

        @Override
        public void draw(Rows.Host h, GuiGraphics g, float x, float y, float w, int mx, int my, float a) {
            hover.update(h.dt(), 0);
            Draw.rrect(g, x, y, w, height(), 6, Draw.withAlpha(Theme.ROW, 0.9f * a));
            Draw.text(g, label, x + 9, y + 8, 1f, Draw.withAlpha(Theme.TEXT, a));
            int cur = get.getAsInt() & 0xFFFFFF;
            for (int i = 0; i < COLORS.length; i++) {
                float gx = sx(x, w, i), gy = y + 5;
                boolean hv = Rows.inside(mx, my, gx, gy, 13, 13);
                if (hv) h.pointer();
                float grow = hv ? 1 : 0;
                Draw.rrect(g, gx - grow / 2, gy - grow / 2, 13 + grow, 13 + grow, 6.5f, Draw.withAlpha(COLORS[i], a));
                if (COLORS[i] == cur) Draw.ring(g, gx - 2, gy - 2, 17, 17, 8.5f, Draw.withAlpha(0xFFFFFF, 0.95f * a));
            }
        }

        @Override
        public boolean click(Rows.Host h, double mx, double my, float x, float y, float w) {
            for (int i = 0; i < COLORS.length; i++) {
                if (Rows.inside(mx, my, sx(x, w, i) - 1, y + 4, 15, 15)) {
                    set.accept(COLORS[i]);
                    h.sound(1.15f);
                    h.changed();
                    return true;
                }
            }
            return false;
        }
    }

    /** Choix du style d'étiquette par cartes avec aperçu réel. */
    public static class Presets extends Rows.Row {
        final Waypoint w;
        float cx0, cw;

        public Presets(Waypoint w) {
            super("Label style", null);
            this.w = w;
        }

        @Override
        public float height() {
            return 66;
        }

        @Override
        public void draw(Rows.Host h, GuiGraphics g, float x, float y, float width, int mx, int my, float a) {
            hover.update(h.dt(), 0);
            Draw.rrect(g, x, y, width, height(), 6, Draw.withAlpha(Theme.ROW, 0.9f * a));
            Draw.text(g, label, x + 9, y + 5, 1f, Draw.withAlpha(Theme.TEXT, a));
            int n = Waypoint.PRESETS.length;
            cx0 = x + 6;
            cw = (width - 12 - (n - 1) * 4) / n;
            int saved = w.preset;
            for (int i = 0; i < n; i++) {
                float px = cx0 + i * (cw + 4), py = y + 17;
                boolean sel = i == saved, hv = Rows.inside(mx, my, px, py, cw, 44);
                if (hv && !sel) h.pointer();
                Draw.rrect(g, px, py, cw, 44, 5, Draw.withAlpha(sel ? 0x2A2F3A : 0x181B22, a));
                Draw.ring(g, px, py, cw, 44, 5, Draw.withAlpha(sel ? Theme.accent() : 0xFFFFFF, (sel ? 0.95f : hv ? 0.35f : 0.1f) * a));
                g.enableScissor((int) px, (int) py, (int) (px + cw), (int) (py + 44));
                w.preset = i;
                Waypoints.drawLabel(g, Minecraft.getInstance().font, w, px + cw / 2, py + 22, 24, 0.62f);
                g.disableScissor();
                String name = Waypoint.PRESETS[i];
                float sc = Math.min(0.68f, (cw - 4) / Math.max(1, Draw.width(name, 1f)));
                Draw.centered(g, name, px + cw / 2, py + 35, sc, Draw.withAlpha(sel ? Theme.TEXT : Theme.TEXT_DIM, a));
            }
            w.preset = saved;
        }

        @Override
        public boolean click(Rows.Host h, double mx, double my, float x, float y, float width) {
            for (int i = 0; i < Waypoint.PRESETS.length; i++) {
                if (Rows.inside(mx, my, cx0 + i * (cw + 4), y + 17, cw, 44)) {
                    w.preset = i;
                    h.sound(1.1f);
                    h.changed();
                    return true;
                }
            }
            return false;
        }
    }
}
