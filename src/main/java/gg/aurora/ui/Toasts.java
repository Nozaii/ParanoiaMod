package gg.aurora.ui;

import gg.aurora.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;

/** Notifications Paranoia : pilules en haut au centre, descendent du bord, restent 6 s, puis remontent. */
public final class Toasts {
    private Toasts() {}

    private static final class Toast {
        final String icon, title, body;
        final boolean important;
        final long born = System.currentTimeMillis();
        final Spring slide = new Spring(0, 160, 0.72f);
        final Spring y = new Spring(-1, 200, 0.85f);

        Toast(String icon, String title, String body, boolean important) {
            this.icon = icon;
            this.title = title;
            this.body = body;
            this.important = important;
        }
    }

    private static final List<Toast> LIST = new ArrayList<>();
    private static long last = System.nanoTime();
    /** W = largeur max de la pilule, H = hauteur. */
    private static final float W = 260, H = 20;

    public static void push(String icon, String title, String body, boolean important) {
        Minecraft client = Minecraft.getInstance();
        if (client != null && !client.isSameThread()) { // social thread: the list is render-thread only
            client.execute(() -> push(icon, title, body, important));
            return;
        }
        if (LIST.size() >= 4) LIST.remove(0);
        LIST.add(new Toast(icon, title, body, important));
        Minecraft mc = Minecraft.getInstance();
        if (Settings.sounds && mc != null && mc.getSoundManager() != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(
                    important ? SoundEvents.NOTE_BLOCK_CHIME.value() : SoundEvents.NOTE_BLOCK_BELL.value(), 1.4f, 0.4f));
        }
    }

    public static void render(GuiGraphics g) {
        long now = System.nanoTime();
        float dt = Math.min(0.05f, (now - last) / 1e9f);
        last = now;
        long ms = System.currentTimeMillis();
        LIST.removeIf(t -> ms - t.born > 7000);
        float top = 6;
        for (int i = LIST.size() - 1; i >= 0; i--) {
            Toast t = LIST.get(i);
            float age = (ms - t.born) / 1000f;
            t.slide.update(dt, age < 6f ? 1 : 0);
            if (t.y.value < 0) t.y.set(top);
            t.y.update(dt, top);
            float a = Draw.clamp01(t.slide.value);
            // pilule compacte en haut au centre : icône + « titre · corps » sur une ligne
            String body = t.body == null || t.body.isBlank() ? "" : " · " + t.body;
            float tw = Math.min(Draw.width(t.title, 0.9f), W - 34);
            float bw = Math.min(Draw.width(body, 0.8f), Math.max(0, W - 34 - tw));
            if (bw < 16) { body = ""; bw = 0; }
            float w = 28 + tw + bw + 10;
            float x = (g.guiWidth() - w) / 2f;
            float y = t.y.value - (1 - t.slide.value) * (H + 10);
            float r = H / 2f;
            int acc = Theme.accent();
            Draw.glow(g, x, y + 2, w, H, 8, Draw.withAlpha(0x000000, 0.45f * a));
            if (t.important) Draw.glow(g, x, y, w, H, 6, Draw.withAlpha(acc, 0.3f * a));
            Draw.glass(g, x, y, w, H, r, Draw.withAlpha(Theme.PANEL, 0.95f * a), Draw.withAlpha(t.important ? acc : 0xFFFFFF, (t.important ? 0.7f : 0.2f) * a));
            Draw.rrect(g, x + 3, y + 3, H - 6, H - 6, (H - 6) / 2f, Draw.withAlpha(acc, 0.25f * a));
            Draw.icon(g, t.icon, x + r, y + r, 9, Draw.withAlpha(acc, a));
            Draw.text(g, clip(t.title, tw, 0.9f), x + 24, y + 6.5f, 0.9f, Draw.withAlpha(Theme.TEXT, a));
            if (!body.isEmpty()) Draw.text(g, clip(body, bw, 0.8f), x + 24 + tw, y + 7.2f, 0.8f, Draw.withAlpha(Theme.TEXT_DIM, a));
            top += (H + 4) * a;
        }
    }

    private static String clip(String s, float max, float scale) {
        if (Draw.width(s, scale) <= max) return s;
        while (s.length() > 1 && Draw.width(s + "…", scale) > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }
}
