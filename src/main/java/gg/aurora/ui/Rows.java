package gg.aurora.ui;

import gg.aurora.Settings;
import net.minecraft.client.gui.GuiGraphics;

import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Lignes de réglages réutilisables (Paramètres, éditeur de nametag, éditeur de HUD). */
public final class Rows {
    private Rows() {}

    /** Ce que l'écran hôte fournit aux lignes. */
    public interface Host {
        float dt();

        void pointer();

        void sound(float pitch);

        /** La ligne est-elle visible à cette hauteur (zone de défilement) ? */
        boolean inView(double my);

        void startDrag(Draggable d);

        Draggable dragging();

        void focus(TextField f);

        TextField focused();

        /** Appelé après une modification (sauvegarde, synchro…). */
        void changed();
    }

    /** Ligne qui suit la souris pendant un glisser (curseur, sélecteur de couleur). */
    public interface Draggable {
        void drag(Host h, double mx, double my);

        default void release() {}
    }

    static boolean inside(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public abstract static class Row {
        public final String label, desc;
        protected final Spring hover = new Spring(0, 320, 1f);

        Row(String label, String desc) {
            this.label = label;
            this.desc = desc;
        }

        public float height() {
            return desc == null ? 22 : 28;
        }

        protected void base(Host h, GuiGraphics g, float x, float y, float w, int mx, int my, float a) {
            boolean hv = inside(mx, my, x, y, w, height()) && h.inView(my);
            hover.update(h.dt(), hv ? 1 : 0);
            // profondeur par surface : le survol monte d'un niveau, sans bordure
            Draw.rrect(g, x, y, w, height(), 6, Draw.withAlpha(Draw.lerpColor(Theme.ROW, Theme.ROW_HOVER, hover.value), 0.9f * a));
            if (desc == null) {
                Draw.text(g, label, x + 9, y + 7, 1f, Draw.withAlpha(Theme.TEXT, a));
            } else {
                Draw.text(g, label, x + 9, y + 5, 1f, Draw.withAlpha(Theme.TEXT, a));
                Draw.text(g, desc, x + 9, y + 15.5f, 0.78f, Draw.withAlpha(Theme.TEXT_DIM, a));
            }
        }

        public abstract void draw(Host h, GuiGraphics g, float x, float y, float w, int mx, int my, float a);

        public boolean click(Host h, double mx, double my, float x, float y, float w) {
            return false;
        }
    }

    public static class Toggle extends Row {
        final Supplier<Boolean> get;
        final Consumer<Boolean> set;
        final Spring knob;

        public Toggle(String label, String desc, Supplier<Boolean> get, Consumer<Boolean> set) {
            super(label, desc);
            this.get = get;
            this.set = set;
            this.knob = new Spring(get.get() ? 1 : 0, 300, 0.55f);
        }

        @Override
        public void draw(Host h, GuiGraphics g, float x, float y, float w, int mx, int my, float a) {
            base(h, g, x, y, w, mx, my, a);
            if (hover.target > 0) h.pointer();
            knob.update(h.dt(), get.get() ? 1 : 0);
            Draw.toggle(g, x + w - 29, y + (height() - 10.5f) / 2, knob.value, knob.velocity, a);
        }

        @Override
        public boolean click(Host h, double mx, double my, float x, float y, float w) {
            set.accept(!get.get());
            h.sound(get.get() ? 1.25f : 0.85f);
            h.changed();
            return true;
        }
    }

    public static class Slider extends Row implements Draggable {
        final double min, max, step;
        final DoubleSupplier get;
        final DoubleConsumer set;
        final DoubleFunction<String> fmt;
        final Spring anim;
        float trackX, trackW;

        public Slider(String label, String desc, double min, double max, double step, DoubleSupplier get, DoubleConsumer set,
                      DoubleFunction<String> fmt) {
            super(label, desc);
            this.min = min;
            this.max = max;
            this.step = step;
            this.get = get;
            this.set = set;
            this.fmt = fmt;
            this.anim = new Spring((float) ((get.getAsDouble() - min) / (max - min)), 260, 0.9f);
        }

        @Override
        public void draw(Host h, GuiGraphics g, float x, float y, float w, int mx, int my, float a) {
            base(h, g, x, y, w, mx, my, a);
            if (hover.target > 0) h.pointer();
            String v = fmt.apply(get.getAsDouble());
            float vw = 34;
            trackW = Math.min(110, w * 0.38f);
            trackX = x + w - 10 - vw - trackW;
            float ty = y + height() / 2 - 1.5f;
            anim.update(h.dt(), (float) ((get.getAsDouble() - min) / (max - min)));
            float f = Draw.clamp01(anim.value);
            Draw.rrect(g, trackX, ty, trackW, 3, 1.5f, Draw.withAlpha(0xFFFFFF, 0.10f * a));
            Draw.rrect(g, trackX, ty, Math.max(3, trackW * f), 3, 1.5f, Draw.withAlpha(Theme.accent(), a));
            float kx = trackX + trackW * f;
            boolean active = h.dragging() == this;
            float ks = 9 + (active ? 2 : hover.value);
            Draw.glow(g, kx - ks / 2, ty + 1.5f - ks / 2, ks, ks, 5, Draw.withAlpha(Theme.accent(), (active ? 0.5f : 0.25f) * a));
            Draw.rrect(g, kx - ks / 2, ty + 1.5f - ks / 2, ks, ks, ks / 2, Draw.withAlpha(0xFFFFFF, a));
            Draw.text(g, v, x + w - 9 - Draw.width(v, 0.9f), y + height() / 2 - 3.6f, 0.9f, Draw.withAlpha(Theme.TEXT_DIM, a));
        }

        @Override
        public void drag(Host h, double mx, double my) {
            double f = Math.max(0, Math.min(1, (mx - trackX) / trackW));
            double v = min + f * (max - min);
            v = Math.round(v / step) * step;
            set.accept(Math.max(min, Math.min(max, v)));
            h.changed();
        }

        @Override
        public boolean click(Host h, double mx, double my, float x, float y, float w) {
            if (mx >= trackX - 6 && mx <= trackX + trackW + 6) {
                h.startDrag(this);
                drag(h, mx, my);
                return true;
            }
            return false;
        }
    }

    public static class Choice extends Row {
        final String[] options;
        final IntSupplier get;
        final IntConsumer set;
        final Spring slide;
        float[] xs, ws;
        float oy;
        final float oh = 14;
        boolean compact;
        float cx, cw;

        public Choice(String label, String[] options, IntSupplier get, IntConsumer set) {
            super(label, null);
            this.options = options;
            this.get = get;
            this.set = set;
            this.slide = new Spring(get.getAsInt(), 220, 0.75f);
        }

        @Override
        public void draw(Host h, GuiGraphics g, float x, float y, float w, int mx, int my, float a) {
            base(h, g, x, y, w, mx, my, a);
            xs = new float[options.length];
            ws = new float[options.length];
            float total = 4;
            for (int i = 0; i < options.length; i++) {
                ws[i] = Draw.width(options[i], 0.82f) + 12;
                total += ws[i];
            }
            oy = y + (height() - oh) / 2;
            float labelRoom = w - 8 - Draw.width(label, 1f) - 24;
            compact = total > labelRoom;
            if (compact) {
                // trop d'options pour la ligne : sélecteur « ‹ valeur › » (clic gauche / droite pour changer)
                float vw = 0;
                for (String o : options) vw = Math.max(vw, Draw.width(o, 0.82f));
                cw = Math.min(labelRoom, vw + 36);
                cx = x + w - 8 - cw;
                Draw.rrect(g, cx, oy - 2, cw, oh + 4, 9, Draw.withAlpha(0x000000, 0.25f * a));
                Draw.rrect(g, cx + 14, oy, cw - 28, oh, 7, Draw.withAlpha(Theme.accent(), 0.9f * a));
                boolean hl = inside(mx, my, cx, oy - 2, cw / 2, oh + 4), hr = inside(mx, my, cx + cw / 2, oy - 2, cw / 2, oh + 4);
                if (hl || hr) h.pointer();
                Draw.centered(g, "‹", cx + 7, oy + 3.2f, 0.95f, Draw.withAlpha(hl ? 0xFFFFFF : Theme.TEXT_DIM, a));
                Draw.centered(g, "›", cx + cw - 7, oy + 3.2f, 0.95f, Draw.withAlpha(hr ? 0xFFFFFF : Theme.TEXT_DIM, a));
                int cur = Math.max(0, Math.min(options.length - 1, get.getAsInt()));
                String o = options[cur];
                float s = Math.min(0.82f, (cw - 32) / Math.max(1, Draw.width(o, 1f)));
                Draw.centered(g, o, cx + cw / 2, oy + 3.7f, s, Draw.withAlpha(0xFFFFFF, a));
                return;
            }
            float cx0 = x + w - 8 - total;
            Draw.rrect(g, cx0, oy - 2, total, oh + 4, 9, Draw.withAlpha(0x000000, 0.25f * a));
            float px = cx0 + 2;
            for (int i = 0; i < options.length; i++) {
                xs[i] = px;
                px += ws[i];
            }
            slide.update(h.dt(), get.getAsInt());
            int lo = Math.max(0, Math.min(options.length - 1, (int) Math.floor(slide.value)));
            int hi = Math.min(options.length - 1, lo + 1);
            float f = Draw.clamp01(slide.value - lo);
            float sx = Draw.lerp(xs[lo], xs[hi], f), sw = Draw.lerp(ws[lo], ws[hi], f);
            Draw.rrect(g, sx, oy, sw, oh, 7, Draw.withAlpha(Theme.accent(), 0.9f * a));
            for (int i = 0; i < options.length; i++) {
                boolean hv = inside(mx, my, xs[i], oy, ws[i], oh);
                if (hv && i != get.getAsInt()) h.pointer();
                int col = i == get.getAsInt() ? 0xFFFFFF : hv ? Theme.TEXT : Theme.TEXT_DIM;
                Draw.centered(g, options[i], xs[i] + ws[i] / 2, oy + 3.7f, 0.82f, Draw.withAlpha(col, a));
            }
        }

        @Override
        public boolean click(Host h, double mx, double my, float x, float y, float w) {
            if (compact) {
                if (!inside(mx, my, cx, oy - 2, cw, oh + 4)) return false;
                int n = options.length, cur = get.getAsInt();
                set.accept(mx < cx + cw / 2 ? (cur - 1 + n) % n : (cur + 1) % n);
                h.sound(1.1f);
                h.changed();
                return true;
            }
            if (xs == null) return false;
            for (int i = 0; i < options.length; i++) {
                if (inside(mx, my, xs[i], oy, ws[i], oh)) {
                    if (i != get.getAsInt()) h.sound(1.1f);
                    set.accept(i);
                    h.changed();
                    return true;
                }
            }
            return false;
        }
    }

    public static class Button extends Row {
        final String icon;
        final Runnable action;

        public Button(String label, String desc, String icon, Runnable action) {
            super(label, desc);
            this.icon = icon;
            this.action = action;
        }

        @Override
        public void draw(Host h, GuiGraphics g, float x, float y, float w, int mx, int my, float a) {
            base(h, g, x, y, w, mx, my, a);
            if (hover.target > 0) h.pointer();
            float bw = 26, bh = 16, bx = x + w - 8 - bw, by = y + (height() - bh) / 2;
            Draw.glass(g, bx, by, bw, bh, 8, Draw.withAlpha(Draw.lerpColor(Theme.tileHover(), Theme.accent(), hover.value * 0.6f), 0.95f * a),
                    Draw.withAlpha(Theme.accent(), 0.7f * a));
            Draw.icon(g, icon, bx + bw / 2 + hover.value, by + bh / 2, 9, Draw.withAlpha(0xFFFFFF, a));
        }

        @Override
        public boolean click(Host h, double mx, double my, float x, float y, float w) {
            h.sound(1f);
            action.run();
            return true;
        }
    }

    /** Ligne d'information. La valeur peut changer (statut de connexion…). */
    /** Image d'aperçu (effet d'un module Visuals), en haut des réglages. */
    public static class Picture extends Row {
        final net.minecraft.resources.Identifier texture;
        final int texW, texH, frames;

        public Picture(net.minecraft.resources.Identifier texture, int texW, int texH) {
            this(texture, texW, texH, 1);
        }

        /** Bande verticale de `frames` images de texW x texH, jouée en boucle (≈ 5 images / s). */
        public Picture(net.minecraft.resources.Identifier texture, int texW, int texH, int frames) {
            super("Preview", null);
            this.texture = texture;
            this.texW = texW;
            this.texH = texH;
            this.frames = Math.max(1, frames);
        }

        @Override
        public float height() {
            return 94;
        }

        @Override
        public void draw(Host h, GuiGraphics g, float x, float y, float w, int mx, int my, float a) {
            hover.update(h.dt(), 0);
            float ih = height(), iw = ih * texW / texH;
            if (iw > w) {
                iw = w;
                ih = w * texH / texW;
            }
            float ix = x + (w - iw) / 2, iy = y + (height() - ih) / 2;
            Draw.rrect(g, x, y, w, height(), 6, Draw.withAlpha(0x000000, 0.35f * a));
            int frame = frames == 1 ? 0 : (int) (System.currentTimeMillis() / 200 % frames);
            Draw.image(g, texture, ix, iy, iw, ih, texW, texH * frames, 0, frame * texH, texW, texH, Draw.withAlpha(0xFFFFFF, a));
            Draw.ring(g, x, y, w, height(), 6, Draw.withAlpha(0xFFFFFF, 0.1f * a));
            Draw.text(g, "PREVIEW", x + 6, y + 4, 0.6f, Draw.withAlpha(0xFFFFFF, 0.7f * a));
        }
    }

    public static class Info extends Row {
        final Supplier<String> value;

        public Info(String label, String value) {
            this(label, () -> value);
        }

        public Info(String label, Supplier<String> value) {
            super(label, null);
            this.value = value;
        }

        @Override
        public void draw(Host h, GuiGraphics g, float x, float y, float w, int mx, int my, float a) {
            hover.update(h.dt(), 0);
            Draw.rrect(g, x, y, w, height(), 6, Draw.withAlpha(Theme.ROW, 0.6f * a));
            Draw.text(g, label, x + 9, y + 7, 1f, Draw.withAlpha(Theme.TEXT, a));
            String v = value.get();
            // valeur longue : coupée avec « … » plutôt que de passer sous le libellé
            float max = w - 18 - Draw.width(label, 1f) - 8;
            if (Draw.width(v, 0.9f) > max) {
                while (v.length() > 1 && Draw.width(v + "…", 0.9f) > max) v = v.substring(0, v.length() - 1);
                v = v + "…";
            }
            Draw.text(g, v, x + w - 9 - Draw.width(v, 0.9f), y + 7.4f, 0.9f, Draw.withAlpha(Theme.TEXT_DIM, a));
        }
    }

    /** Champ texte d'une ligne (ex. adresse du serveur). */
    public static class TextField extends Row {
        final Supplier<String> get;
        final Consumer<String> set;
        final String placeholder;
        final int max;
        float fx, fw;
        /** What is being typed: a number field stores 0 for "-", the text must stay as typed. */
        private String draft;

        private String current() {
            return draft != null ? draft : get.get();
        }

        public TextField(String label, String placeholder, int max, Supplier<String> get, Consumer<String> set) {
            super(label, null);
            this.get = get;
            this.set = set;
            this.placeholder = placeholder;
            this.max = max;
        }

        @Override
        public float height() {
            return 40;
        }

        @Override
        public void draw(Host h, GuiGraphics g, float x, float y, float w, int mx, int my, float a) {
            boolean hv = inside(mx, my, x, y, w, height()) && h.inView(my);
            hover.update(h.dt(), hv ? 1 : 0);
            Draw.rrect(g, x, y, w, height(), 6, Draw.withAlpha(Draw.lerpColor(Theme.ROW, Theme.ROW_HOVER, hover.value), 0.9f * a));
            Draw.text(g, label, x + 9, y + 5, 1f, Draw.withAlpha(Theme.TEXT, a));
            boolean focused = h.focused() == this;
            fx = x + 8;
            fw = w - 16;
            float fy = y + 19;
            if (hv) h.pointer();
            Draw.glass(g, fx, fy, fw, 15, 5, Draw.withAlpha(0x000000, 0.3f * a),
                    Draw.withAlpha(focused ? Theme.accent() : 0xFFFFFF, (focused ? 0.75f : 0.15f) * a));
            if (!focused) draft = null;
            String v = current();
            // affiche la fin du texte s'il est trop long
            String shown = v;
            while (!shown.isEmpty() && Draw.width(shown, 0.85f) > fw - 12) shown = shown.substring(1);
            if (v.isEmpty() && !focused) Draw.text(g, placeholder, fx + 6, fy + 4.2f, 0.85f, Draw.withAlpha(Theme.TEXT_MUTED, a));
            else Draw.text(g, shown, fx + 6, fy + 4.2f, 0.85f, Draw.withAlpha(Theme.TEXT, a));
            if (focused && (System.currentTimeMillis() / 530) % 2 == 0) {
                Draw.rrect(g, fx + 6.5f + Draw.width(shown, 0.85f), fy + 3.5f, 0.8f, 8, 0.4f, Draw.withAlpha(0xFFFFFF, a));
            }
        }

        @Override
        public boolean click(Host h, double mx, double my, float x, float y, float w) {
            h.focus(this);
            return true;
        }

        public void type(Host h, String s) {
            String v = current();
            if (v.length() + s.length() <= max) {
                draft = v + s;
                set.accept(draft);
                h.changed();
            }
        }

        public void backspace(Host h, boolean all) {
            String v = current();
            if (!v.isEmpty()) {
                draft = all ? "" : v.substring(0, v.length() - 1);
                set.accept(draft);
                h.changed();
            }
        }

        public void paste(Host h, String clip) {
            if (clip != null) type(h, clip.replaceAll("\\s", ""));
        }
    }

    /**
     * Raccourci clavier : clic → « Appuie sur une touche… », la touche suivante est enregistrée.
     * Échap annule, Retour arrière / Suppr retire le raccourci.
     */
    public static class KeyBind extends Row {
        /** Ligne en attente d'une touche (une seule à la fois). */
        public static KeyBind listening;
        final IntSupplier get;
        final IntConsumer set;
        float px, pw;

        public KeyBind(String label, String desc, IntSupplier get, IntConsumer set) {
            super(label, desc);
            this.get = get;
            this.set = set;
        }

        public static String keyName(int code) {
            if (code < 0) return "None";
            return com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(code).getDisplayName().getString();
        }

        @Override
        public void draw(Host h, GuiGraphics g, float x, float y, float w, int mx, int my, float a) {
            base(h, g, x, y, w, mx, my, a);
            if (hover.target > 0) h.pointer();
            boolean on = listening == this;
            String t = on ? "Press a key…" : keyName(get.getAsInt());
            pw = Draw.width(t, 0.85f) + 16;
            px = x + w - 8 - pw;
            float py = y + (height() - 15) / 2;
            float pulse = on ? 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 160.0) : 0;
            Draw.glass(g, px, py, pw, 15, 7.5f, Draw.withAlpha(on ? Theme.accent() : 0x000000, (on ? 0.35f + 0.3f * pulse : 0.3f) * a),
                    Draw.withAlpha(on ? 0xFFFFFF : Theme.accent(), (on ? 0.8f : 0.45f) * a));
            Draw.centered(g, t, px + pw / 2, py + 3.8f, 0.85f, Draw.withAlpha(get.getAsInt() < 0 && !on ? Theme.TEXT_MUTED : Theme.TEXT, a));
        }

        @Override
        public boolean click(Host h, double mx, double my, float x, float y, float w) {
            listening = listening == this ? null : this;
            h.sound(1.1f);
            return true;
        }

        /** Touche reçue pendant l'écoute. */
        public void accept(Host h, int key) {
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                listening = null;
                return;
            }
            set.accept(key == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE || key == org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE ? -1 : key);
            listening = null;
            h.sound(1.2f);
            h.changed();
        }
    }

    /** Couleur réglable : pastille, et sélecteur graphique déplié au clic. */
    public static class ColorRow extends Row implements Draggable {
        final IntSupplier get;
        final IntConsumer set;
        final ColorPicker picker = new ColorPicker();
        final Spring open = new Spring(0, 220, 0.85f);
        public boolean expanded;
        float pickX, pickY, pickW, pickH;

        public ColorRow(String label, IntSupplier get, IntConsumer set) {
            super(label, null);
            this.get = get;
            this.set = set;
        }

        @Override
        public float height() {
            return 22 + 82 * Draw.clamp01(open.value);
        }

        @Override
        public void draw(Host h, GuiGraphics g, float x, float y, float w, int mx, int my, float a) {
            open.update(h.dt(), expanded ? 1 : 0);
            boolean hv = inside(mx, my, x, y, w, 22) && h.inView(my);
            hover.update(h.dt(), hv ? 1 : 0);
            if (hv) h.pointer();
            float ht = height();
            Draw.rrect(g, x, y, w, ht, 6, Draw.withAlpha(Draw.lerpColor(Theme.ROW, Theme.ROW_HOVER, hover.value), 0.9f * a));
            Draw.text(g, label, x + 9, y + 7, 1f, Draw.withAlpha(Theme.TEXT, a));
            int c = get.getAsInt();
            String hex = String.format(java.util.Locale.ROOT, "#%06X", c & 0xFFFFFF);
            Draw.text(g, hex, x + w - 34 - Draw.width(hex, 0.8f), y + 7.6f, 0.8f, Draw.withAlpha(Theme.TEXT_DIM, a));
            Draw.rrect(g, x + w - 27, y + 4, 18, 14, 4, Draw.withAlpha(c, a));
            Draw.ring(g, x + w - 27, y + 4, 18, 14, 4, Draw.withAlpha(0xFFFFFF, 0.5f * a));
            float oa = Draw.clamp01(open.value);
            if (oa > 0.05f) {
                pickX = x + 8;
                pickY = y + 26;
                pickW = w - 16;
                pickH = 74 * oa;
                if (!picker.dragging()) picker.setColor(c);
                picker.render(g, pickX, pickY, pickW, Math.max(4, pickH), a * oa);
            }
        }

        @Override
        public boolean click(Host h, double mx, double my, float x, float y, float w) {
            if (my < y + 22) {
                expanded = !expanded;
                if (expanded) picker.setColor(get.getAsInt());
                h.sound(expanded ? 1.1f : 0.9f);
                return true;
            }
            if (expanded && picker.click(mx, my)) {
                h.startDrag(this);
                set.accept(picker.color());
                h.changed();
                return true;
            }
            return false;
        }

        @Override
        public void drag(Host h, double mx, double my) {
            if (picker.drag(mx, my)) set.accept(picker.color());
        }

        @Override
        public void release() {
            picker.release();
        }
    }

    /** Aperçu de l'accent : les composants réels (interrupteurs, bouton, sélection, puce) recolorés en direct. */
    public static class ThemePreview extends Row {
        private final Spring demo = new Spring(1, 300, 0.55f);
        private long flip = System.nanoTime();

        public ThemePreview() {
            super("Preview", null);
        }

        @Override
        public float height() {
            return 58;
        }

        @Override
        public void draw(Host h, GuiGraphics g, float x, float y, float w, int mx, int my, float a) {
            hover.update(h.dt(), 0);
            Draw.rrect(g, x, y, w, height(), 6, Draw.withAlpha(Theme.ROW, 0.9f * a));
            Draw.text(g, label, x + 9, y + 6, 1f, Draw.withAlpha(Theme.TEXT, a));
            Draw.text(g, "How the accent looks on the menu", x + w - 9 - Draw.width("How the accent looks on the menu", 0.78f), y + 7.5f, 0.78f,
                    Draw.withAlpha(Theme.TEXT_DIM, a));
            // interrupteur qui bascule toutes les 2,2 s : montre la piste ON, le bouton et l'anneau
            long now = System.nanoTime();
            if (now - flip > 2_200_000_000L) {
                flip = now;
                demo.target = demo.target > 0.5f ? 0 : 1;
            }
            demo.update(h.dt());
            float ry = y + 26;
            Draw.toggle(g, x + 10, ry + 3, demo.value, demo.velocity, a);
            Draw.toggle(g, x + 36, ry + 3, 1, 0, a);
            // bouton principal
            Draw.rrect(g, x + 66, ry, 52, 16, 4, Draw.withAlpha(Theme.accent(), a));
            Draw.centered(g, "Equip", x + 92, ry + 4.4f, 0.8f, Draw.withAlpha(0x0B0A12, a));
            // élément sélectionné dans une liste
            Draw.rrect(g, x + 126, ry, 84, 16, 5, Draw.withAlpha(Theme.ROW_HOVER, a));
            Draw.rrect(g, x + 128, ry + 4, 2, 8, 1, Draw.withAlpha(Theme.accent(), a));
            Draw.icon(g, Icons.SPARKLES, x + 139, ry + 8, 8, Draw.withAlpha(Theme.accent(), a));
            Draw.text(g, "Selected", x + 148, ry + 4.4f, 0.8f, Draw.withAlpha(Theme.TEXT, a));
            // puce de rareté / état, et chiffre de HUD
            Draw.rrect(g, x + 218, ry + 2, 44, 12, 6, Draw.withAlpha(Theme.accent(), 0.18f * a));
            Draw.centered(g, "Equipped", x + 240, ry + 4.6f, 0.68f, Draw.withAlpha(Theme.accent(), a));
            if (w > 330) {
                Draw.rrect(g, x + 270, ry - 2, 1.5f, 20, 0.75f, Draw.withAlpha(Theme.accent(), a));
                Draw.text(g, "240", x + 276, ry - 1, 1.3f, Draw.withAlpha(Theme.TEXT, a));
                Draw.text(g, "FPS", x + 276 + Draw.width("240", 1.3f) + 3, ry + 5, 0.7f, Draw.withAlpha(Theme.TEXT_DIM, a));
            }
        }
    }

    /** Pastilles de couleur d'accent. */
    public static class Swatches extends Row {
        final Spring[] grow = new Spring[Theme.ACCENTS.length];

        public Swatches() {
            super("Accent color", null);
            for (int i = 0; i < grow.length; i++) grow[i] = new Spring(i == Settings.accent ? 1 : 0, 300, 0.6f);
        }

        @Override
        public float height() {
            return 52;
        }

        @Override
        public void draw(Host h, GuiGraphics g, float x, float y, float w, int mx, int my, float a) {
            hover.update(h.dt(), 0);
            Draw.rrect(g, x, y, w, height(), 6, Draw.withAlpha(Theme.ROW, 0.9f * a));
            Draw.text(g, label, x + 9, y + 6, 1f, Draw.withAlpha(Theme.TEXT, a));
            String name = Theme.ACCENTS[Settings.accent].name();
            Draw.text(g, name, x + w - 9 - Draw.width(name, 0.85f), y + 7, 0.85f, Draw.withAlpha(Theme.accent(), a));
            for (int i = 0; i < Theme.ACCENTS.length; i++) {
                float s = 16, gx = x + 10 + i * (s + 9), gy = y + 24;
                boolean hv = inside(mx, my, gx - 2, gy - 2, s + 4, s + 4);
                if (hv) h.pointer();
                grow[i].update(h.dt(), i == Settings.accent ? 1 : hv ? 0.4f : 0);
                float e = grow[i].value;
                int c = Theme.ACCENTS[i].color();
                float ss = s + 3 * e;
                float ox = gx + s / 2 - ss / 2, oy = gy + s / 2 - ss / 2;
                if (e > 0.01f) Draw.glow(g, ox, oy, ss, ss, 6, Draw.withAlpha(c, 0.45f * e * a));
                Draw.rrect(g, ox, oy, ss, ss, ss / 2, Draw.withAlpha(c, a));
                if (i == Settings.accent) {
                    Draw.ring(g, ox - 2.5f, oy - 2.5f, ss + 5, ss + 5, (ss + 5) / 2, Draw.withAlpha(0xFFFFFF, 0.9f * a));
                    Draw.icon(g, Icons.CHECK, gx + s / 2, gy + s / 2, 9, Draw.withAlpha(0x15122A, a * Draw.clamp01(e)));
                }
            }
        }

        @Override
        public boolean click(Host h, double mx, double my, float x, float y, float w) {
            for (int i = 0; i < Theme.ACCENTS.length; i++) {
                float s = 16, gx = x + 10 + i * (s + 9), gy = y + 24;
                if (inside(mx, my, gx - 2, gy - 2, s + 4, s + 4)) {
                    Settings.accent = i;
                    h.sound(1.15f);
                    h.changed();
                    return true;
                }
            }
            return false;
        }
    }
}
