package gg.aurora.ui;

import com.mojang.blaze3d.platform.InputConstants;
import gg.aurora.module.Live;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Roue d'emotes : maintenir la touche, viser une emote avec la souris, relâcher (ou cliquer) pour la
 * lancer. Échap ou relâcher au centre annule.
 */
public class EmoteWheelScreen extends Screen {
    private final KeyMapping key;
    private long opened = System.nanoTime();
    private int hovered = -1;

    public EmoteWheelScreen(KeyMapping key) {
        super(Component.literal("Emotes"));
        this.key = key;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        // pas de flou : on voit son personnage pendant le choix
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        float open = Math.min(1, (System.nanoTime() - opened) / 1.2e8f);
        float e = Draw.easeOutCubic(open);
        float cx = width / 2f, cy = height / 2f, R = 62 * (0.8f + 0.2f * e);
        int n = Live.EMOTE_IDS.length;
        double dx = mx - cx, dy = my - cy;
        hovered = Math.hypot(dx, dy) < 18 ? -1 : (int) Math.floorMod(Math.round(Math.atan2(dy, dx) / (Math.PI * 2 / n) + n / 4.0), n);
        Draw.glow(g, cx - R - 16, cy - R - 16, 2 * R + 32, 2 * R + 32, 24, Draw.withAlpha(0x000000, 0.45f * e));
        Draw.ring(g, cx - R, cy - R, 2 * R, 2 * R, R, Draw.withAlpha(0xFFFFFF, 0.12f * e));
        for (int i = 0; i < n; i++) {
            double a = (i - n / 4.0) * Math.PI * 2 / n;
            float x = cx + (float) Math.cos(a) * R, y = cy + (float) Math.sin(a) * R;
            boolean hv = i == hovered;
            float s = hv ? 24 : 20;
            Draw.rrect(g, x - s, y - s * 0.62f, 2 * s, s * 1.24f, s * 0.62f,
                    Draw.withAlpha(hv ? Theme.accent() : Theme.PANEL, (hv ? 0.95f : 0.85f) * e));
            Draw.ring(g, x - s, y - s * 0.62f, 2 * s, s * 1.24f, s * 0.62f, Draw.withAlpha(0xFFFFFF, (hv ? 0.6f : 0.15f) * e));
            Draw.centered(g, Live.EMOTE_NAMES[i], x, y - 3.5f, hv ? 0.85f : 0.75f, Draw.withAlpha(hv ? 0xFFFFFF : Theme.TEXT, e));
        }
        Draw.rrect(g, cx - 16, cy - 16, 32, 32, 16, Draw.withAlpha(Theme.PANEL, 0.9f * e));
        Draw.centered(g, hovered < 0 ? "×" : "✓", cx, cy - 4.5f, 1.1f, Draw.withAlpha(hovered < 0 ? Theme.TEXT_DIM : Theme.accent(), e));
        Draw.centered(g, "Release to play", cx, cy + R + 28, 0.75f, Draw.withAlpha(Theme.TEXT_DIM, e));
    }

    @Override
    public void tick() {
        // touche relâchée : on lance l'emote visée
        InputConstants.Key k = InputConstants.getKey(key.saveString());
        if (k.getType() == InputConstants.Type.KEYSYM && !InputConstants.isKeyDown(minecraft.getWindow(), k.getValue())) choose();
    }

    private void choose() {
        int h = hovered;
        onClose();
        if (h >= 0) Live.playEmote(Live.EMOTE_IDS[h]);
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
