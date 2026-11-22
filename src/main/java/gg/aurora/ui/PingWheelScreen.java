package gg.aurora.ui;

import gg.aurora.waypoint.Pings;
import gg.aurora.waypoint.Waypoints;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Ping wheel: hold the ping key, move the mouse toward a ping, release (or click) to place it where you
 * were aiming when the key went down. Releasing in the middle or Escape cancels.
 */
public class PingWheelScreen extends Screen {
    private final Pings.Target target;
    private final long opened = System.nanoTime();
    private int hovered = -1;
    private final Spring[] grow = new Spring[Pings.WHEEL.length];
    private long last = System.nanoTime();

    public PingWheelScreen(Pings.Target target) {
        super(Component.literal("Ping"));
        this.target = target;
        for (int i = 0; i < grow.length; i++) grow[i] = new Spring(0, 420, 0.8f);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        // no blur: the world stays visible while choosing
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        long nowNs = System.nanoTime();
        float dt = Math.min(0.1f, (nowNs - last) / 1e9f);
        last = nowNs;
        float e = Draw.easeOutCubic(Math.min(1, (nowNs - opened) / 1.4e8f));
        float cx = width / 2f, cy = height / 2f, R = 68 * (0.85f + 0.15f * e);
        int n = Pings.WHEEL.length;
        double dx = mx - cx, dy = my - cy;
        hovered = Math.hypot(dx, dy) < 20 ? -1 : (int) Math.floorMod(Math.round(Math.atan2(dy, dx) / (Math.PI * 2 / n) + n / 4.0), n);

        // backdrop disc + track
        Draw.rrect(g, cx - R - 30, cy - R - 30, 2 * R + 60, 2 * R + 60, R + 30, Draw.withAlpha(0x000000, 0.42f * e));
        Draw.ring(g, cx - R, cy - R, 2 * R, 2 * R, R, Draw.withAlpha(0xFFFFFF, 0.08f * e));
        // direction cue on the track
        if (hovered >= 0) {
            double a = Math.atan2(dy, dx);
            float px = cx + (float) Math.cos(a) * (R - 30), py = cy + (float) Math.sin(a) * (R - 30);
            Draw.rrect(g, px - 2.5f, py - 2.5f, 5, 5, 2.5f, Draw.withAlpha(Pings.WHEEL[hovered].color, e));
        }
        for (int i = 0; i < n; i++) {
            Pings.Kind k = Pings.WHEEL[i];
            boolean hv = i == hovered;
            grow[i].update(dt, hv ? 1 : 0);
            float gv = grow[i].value;
            double a = (i - n / 4.0) * Math.PI * 2 / n;
            float x = cx + (float) Math.cos(a) * R, y = cy + (float) Math.sin(a) * R;
            float r = 16 + 4 * gv;
            int fg = hv ? Waypoints.contrast(k.color) : k.color;
            Draw.rrect(g, x - r, y - r, 2 * r, 2 * r, r, Draw.withAlpha(Draw.lerpColor(Theme.PANEL, k.color, gv), 0.92f * e));
            Draw.ring(g, x - r, y - r, 2 * r, 2 * r, r, Draw.withAlpha(k.color, (0.45f + 0.5f * gv) * e));
            if ("!".equals(k.icon)) Draw.centered(g, "!", x, y - 6, 1.5f + 0.2f * gv, Draw.withAlpha(fg, e));
            else Draw.icon(g, k.icon, x, y, 13 + 3 * gv, Draw.withAlpha(fg, e));
            // label outside the button, pushed away from the center
            float lx = x + (float) Math.cos(a) * (r + 9), ly = y + (float) Math.sin(a) * (r + 9) - 3.5f;
            float lw = Draw.width(k.title, 0.8f);
            float tx = Math.cos(a) > 0.3 ? lx : Math.cos(a) < -0.3 ? lx - lw : lx - lw / 2;
            Waypoints.shadowText(g, k.title, tx, ly, 0.8f, Draw.withAlpha(0xFFFFFF, (hv ? 1f : 0.75f) * e));
        }
        // center: what will happen on release
        Draw.rrect(g, cx - 20, cy - 20, 40, 40, 20, Draw.withAlpha(Theme.PANEL, 0.92f * e));
        Draw.ring(g, cx - 20, cy - 20, 40, 40, 20, Draw.withAlpha(0xFFFFFF, 0.12f * e));
        if (hovered < 0) Draw.centered(g, "×", cx, cy - 5, 1.2f, Draw.withAlpha(Theme.TEXT_DIM, e));
        else Draw.centered(g, Pings.WHEEL[hovered].title, cx, cy - 3, Math.min(0.72f, 34 / Math.max(1, Draw.width(Pings.WHEEL[hovered].title, 1f))),
                Draw.withAlpha(0xFFFFFF, e));
        String hint = target == null ? "Nothing to ping here: aim at a block" : hovered < 0 ? "Release to cancel"
                : target.what() != null ? "Release to ping · " + target.what() : "Release to ping";
        Waypoints.shadowText(g, hint, cx - Draw.width(hint, 0.8f) / 2, cy + R + 34, 0.8f, Draw.withAlpha(target == null ? 0xFF8A80 : 0xD0D0D8, e));
    }

    @Override
    public void tick() {
        if (!Pings.physicallyDown()) choose();
    }

    private void choose() {
        int h = hovered;
        onClose();
        if (h >= 0) Pings.choose(target, Pings.WHEEL[h]);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean dbl) {
        choose();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }
}
