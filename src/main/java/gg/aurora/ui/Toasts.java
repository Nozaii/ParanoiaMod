package gg.aurora.ui;

import gg.aurora.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;

/** Notifications Paranoia en haut à droite : glissent depuis le bord, restent 6 s, puis s'effacent. */
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
    private static final float W = 170, H = 32;

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
            float x = g.guiWidth() - 6 - W + (1 - t.slide.value) * (W + 12);
            float y = t.y.value;
            int acc = Theme.accent();
            Draw.glow(g, x, y + 3, W, H, 10, Draw.withAlpha(0x000000, 0.5f * a));
            if (t.important) Draw.glow(g, x, y, W, H, 8, Draw.withAlpha(acc, 0.35f * a));
            Draw.glass(g, x, y, W, H, 8, Draw.withAlpha(Theme.PANEL, 0.95f * a), Draw.withAlpha(t.important ? acc : 0xFFFFFF, (t.important ? 0.8f : 0.3f) * a));
            Draw.rrect(g, x + 6, y + 6, 20, 20, 6, Draw.withAlpha(acc, 0.25f * a));
            Draw.icon(g, t.icon, x + 16, y + 16, 11, Draw.withAlpha(acc, a));
            Draw.text(g, clip(t.title, W - 40, 0.95f), x + 32, y + 6.5f, 0.95f, Draw.withAlpha(Theme.TEXT, a));
            Draw.text(g, clip(t.body, W - 40, 0.78f), x + 32, y + 18, 0.78f, Draw.withAlpha(Theme.TEXT_DIM, a));
            // barre de temps restant
            float left = Draw.clamp01(1 - age / 6f);
            Draw.rrect(g, x + 8, y + H - 2.5f, (W - 16) * left, 1.2f, 0.6f, Draw.withAlpha(acc, 0.6f * a));
            top += (H + 5) * a;
        }
    }

    private static String clip(String s, float max, float scale) {
        if (Draw.width(s, scale) <= max) return s;
        while (s.length() > 1 && Draw.width(s + "…", scale) > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }
}
