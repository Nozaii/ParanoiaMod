package gg.aurora.ui;

import gg.aurora.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

/** Liste de lignes de réglages avec défilement, apparition en cascade, glisser et saisie texte. */
public class RowPanel implements Rows.Host {
    public interface Callbacks {
        void pointer();

        void sound(float pitch);

        void changed();
    }

    private final DoubleSupplier dtSource;
    private final Callbacks cb;
    private List<Rows.Row> rows = new ArrayList<>();
    private final Spring scroll = new Spring(0, 260, 1f);
    private float scrollTarget;
    private long shownAt = System.nanoTime();
    private Rows.Draggable dragging;
    private Rows.TextField focused;
    private float x, y, w, h;
    public float gap = 4;

    public RowPanel(DoubleSupplier dt, Callbacks cb) {
        this.dtSource = dt;
        this.cb = cb;
    }

    public void setRows(List<Rows.Row> rows) {
        Rows.KeyBind.listening = null;
        this.rows = rows;
        scroll.set(0);
        scrollTarget = 0;
        shownAt = System.nanoTime();
        dragging = null;
        focused = null;
    }

    /** Swaps the rows in place (filter typed, a list refreshed): same scroll, no entrance cascade. */
    public void replaceRows(List<Rows.Row> rows) {
        Rows.KeyBind.listening = null;
        this.rows = rows;
        dragging = null;
        focused = null;
    }

    private float lastMax;

    /** How far the list can still scroll down, in pixels (0 at the end, or when it all fits). */
    public float remaining() {
        return Math.max(0, lastMax - scrollTarget);
    }

    public List<Rows.Row> rows() {
        return rows;
    }

    public void render(GuiGraphics g, float x, float y, float w, float h, int mx, int my, float a) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        float content = 6;
        for (Rows.Row r : rows) content += r.height() + gap;
        float maxScroll = Math.max(0, content - h + 2);
        lastMax = maxScroll;
        scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
        scroll.update(dt(), scrollTarget);
        float t = (System.nanoTime() - shownAt) / 1e9f;
        g.enableScissor((int) x, (int) y, (int) Math.ceil(x + w), (int) Math.ceil(y + h));
        float ry = y + 6 - scroll.value;
        int i = 0;
        for (Rows.Row r : rows) {
            float e = Settings.anim() ? Draw.easeOutCubic((t - i * 0.04f) / 0.35f) : 1;
            if (ry + r.height() > y - 2 && ry < y + h + 2) r.draw(this, g, x + 6 + (1 - e) * 14, ry, w - 12, mx, my, a * e);
            ry += r.height() + gap;
            i++;
        }
        g.disableScissor();
        if (maxScroll > 0) {
            float barH = Math.max(14, h * h / content);
            Draw.rrect(g, x + w - 3, y + (h - barH) * (scroll.value / maxScroll), 2, barH, 1, Draw.withAlpha(Theme.accent(), 0.6f * a));
        }
    }

    public boolean click(double mx, double my) {
        if (!Rows.inside(mx, my, x, y, w, h)) {
            focused = null;
            return false;
        }
        Rows.TextField before = focused;
        float ry = y + 6 - scroll.value;
        for (Rows.Row r : rows) {
            if (my >= ry && my < ry + r.height()) {
                boolean used = r.click(this, mx, my, x + 6, ry, w - 12);
                if (focused == before && !(r instanceof Rows.TextField)) focused = null;
                return used || true;
            }
            ry += r.height() + gap;
        }
        focused = null;
        return true;
    }

    public boolean drag(double mx, double my) {
        if (dragging == null) return false;
        dragging.drag(this, mx, my);
        return true;
    }

    public boolean release() {
        if (dragging == null) return false;
        dragging.release();
        dragging = null;
        cb.changed();
        return true;
    }

    public boolean scroll(double mx, double my, double sy) {
        if (!Rows.inside(mx, my, x, y, w, h)) return false;
        scrollTarget -= (float) sy * 24;
        return true;
    }

    public boolean charTyped(String s) {
        if (focused == null) return false;
        focused.type(this, s);
        return true;
    }

    public boolean keyPressed(int key, int modifiers) {
        if (Rows.KeyBind.listening != null && rows.contains(Rows.KeyBind.listening)) {
            Rows.KeyBind.listening.accept(this, key);
            return true;
        }
        if (focused == null) return false;
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        if (key == GLFW.GLFW_KEY_BACKSPACE) focused.backspace(this, ctrl);
        else if (key == GLFW.GLFW_KEY_V && ctrl) focused.paste(this, Minecraft.getInstance().keyboardHandler.getClipboard());
        else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_ESCAPE) focused = null;
        return true;
    }

    // ---- Host
    @Override
    public float dt() {
        return (float) dtSource.getAsDouble();
    }

    @Override
    public void pointer() {
        cb.pointer();
    }

    @Override
    public void sound(float pitch) {
        cb.sound(pitch);
    }

    @Override
    public boolean inView(double my) {
        return my >= y && my < y + h;
    }

    @Override
    public void startDrag(Rows.Draggable d) {
        dragging = d;
    }

    @Override
    public Rows.Draggable dragging() {
        return dragging;
    }

    @Override
    public void focus(Rows.TextField f) {
        focused = f;
    }

    @Override
    public Rows.TextField focused() {
        return focused;
    }

    @Override
    public void changed() {
        cb.changed();
    }
}
