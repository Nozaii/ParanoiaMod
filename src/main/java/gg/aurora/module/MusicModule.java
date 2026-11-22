package gg.aurora.module;

import com.mojang.blaze3d.platform.NativeImage;
import gg.aurora.media.MediaBridge;
import gg.aurora.ui.Draw;
import gg.aurora.ui.HudKit;
import gg.aurora.ui.Icons;
import gg.aurora.ui.Rows;
import gg.aurora.ui.Theme;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Now Playing : carte dans un coin de l'écran avec la musique en cours (pochette, titre, artiste,
 * progression) et ses contrôles. Clic sur les boutons quand le chat est ouvert, ou touches dédiées.
 */
public final class MusicModule {
    private MusicModule() {}

    private static final Identifier ART = Identifier.fromNamespaceAndPath("aurora", "now_playing_art");
    private static String artLoaded = "";
    private static boolean hasArt;
    public static KeyMapping toggleKey, nextKey, prevKey;

    /** Zones des boutons (coordonnées locales du widget, avant échelle) : précédent, lecture, suivant. */
    private static final float[][] BTN = new float[3][4];

    public static final HudModule NOW_PLAYING = Modules.register(new HudModule("now_playing", "Now Playing", Category.HUD, Icons.CLOCK, false,
            0.99f, 0.97f, () -> null) {
        @Override
        public void render(GuiGraphics g, boolean preview) {
            MusicModule.render(this, g, preview);
        }
    }).withSettings(() -> List.of(
            new Rows.Info("Players", MediaBridge.SUPPORTED ? "Spotify, Deezer, browser… (Windows)" : "Windows only"),
            new Rows.Info("Control", "Open the chat (T) and click, or assign keys"),
            new Rows.Toggle("Hide without music", null, () -> MusicModule.NOW_PLAYING.flag("hide", true), v -> MusicModule.NOW_PLAYING.set("hide", v)),
            new Rows.Toggle("Pouch", null, () -> MusicModule.NOW_PLAYING.flag("art", true), v -> MusicModule.NOW_PLAYING.set("art", v)),
            new Rows.Toggle("Progress bar", null, () -> MusicModule.NOW_PLAYING.flag("bar", true), v -> MusicModule.NOW_PLAYING.set("bar", v)),
            new Rows.Toggle("Buttons", "Previous, play / pause, next", () -> MusicModule.NOW_PLAYING.flag("controls", true),
                    v -> MusicModule.NOW_PLAYING.set("controls", v))));

    public static void init(KeyMapping.Category category) {
        toggleKey = net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.aurora.music_toggle", com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, -1, category));
        nextKey = net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.aurora.music_next", com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, -1, category));
        prevKey = net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.aurora.music_prev", com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, -1, category));
    }

    public static void tick(Minecraft mc) {
        MediaBridge.tick(NOW_PLAYING.enabled);
        if (toggleKey == null) return;
        while (toggleKey.consumeClick()) MediaBridge.send("toggle");
        while (nextKey.consumeClick()) MediaBridge.send("next");
        while (prevKey.consumeClick()) MediaBridge.send("prev");
    }

    /** Clic de souris (chat ouvert) : true si un bouton du widget a été pressé. */
    public static boolean click(double mx, double my) {
        if (!NOW_PLAYING.enabled || !NOW_PLAYING.flag("controls", true) || MediaBridge.track == null) return false;
        String[] cmd = {"prev", "toggle", "next"};
        for (int i = 0; i < 3; i++) {
            if (hit(i, mx, my)) {
                MediaBridge.send(cmd[i]);
                Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.2f, 0.25f));
                return true;
            }
        }
        return false;
    }

    private static float originX, originY;

    private static boolean hit(int i, double mx, double my) {
        float s = NOW_PLAYING.scale;
        float[] b = BTN[i];
        double lx = originX + (mx - originX) / s, ly = originY + (my - originY) / s;
        return lx >= b[0] && lx < b[0] + b[2] && ly >= b[1] && ly < b[1] + b[3];
    }

    private static void loadArt(String path) {
        if (path.equals(artLoaded)) return;
        artLoaded = path;
        hasArt = false;
        if (path.isEmpty()) return;
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            NativeImage img = NativeImage.read(in);
            Minecraft.getInstance().getTextureManager().register(ART, new DynamicTexture(() -> "aurora_now_playing", img));
            hasArt = true;
        } catch (Exception ignored) {
            // pochette illisible : carré de couleur à la place
        }
    }

    private static String time(double s) {
        int t = (int) Math.max(0, s);
        return String.format(Locale.ROOT, "%d:%02d", t / 60, t % 60);
    }

    private static String fit(String s, float scale, float max) {
        if (Draw.width(s, scale) <= max) return s;
        while (s.length() > 1 && Draw.width(s + "…", scale) > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private static void render(HudModule m, GuiGraphics g, boolean preview) {
        MediaBridge.Track t = MediaBridge.track;
        if (t == null) {
            if (!preview && m.flag("hide", true)) return;
            t = new MediaBridge.Track(MediaBridge.SUPPORTED ? "Nothing playing" : "Windows only", "Start Spotify or a video",
                    false, 12, 46, 0, "", "");
        }
        loadArt(t.art());
        boolean art = m.flag("art", true), bar = m.flag("bar", true), controls = m.flag("controls", true);
        float w = 196, h = 52;
        m.lastW = (int) w;
        m.lastH = (int) h;
        float x = m.px(g), y = m.py(g);
        originX = x;
        originY = y;
        HudKit.background(g, x, y, w, h, 9);

        // pochette
        float tx = x + 9;
        if (art) {
            float as = h - 14;
            if (hasArt) {
                Draw.image(g, ART, x + 7, y + 7, as, as, 128, 128, 0, 0, 128, 128, 0xFFFFFFFF);
                Draw.ring(g, x + 7, y + 7, as, as, 5, Draw.withAlpha(0xFFFFFF, 0.12f));
            } else {
                Draw.rrect(g, x + 7, y + 7, as, as, 6, 0xFF000000 | Draw.lerpColor(Theme.accent(), 0x000000, 0.35f));
                Draw.centered(g, "♪", x + 7 + as / 2, y + 7 + as / 2 - 5, 1.3f, 0xFFFFFFFF);
            }
            tx = x + 7 + as + 8;
        }
        float right = controls ? x + w - 70 : x + w - 9;

        // titre, artiste
        HudKit.value(g, fit(t.title(), 1f, right - tx), tx, y + 8, 1f);
        HudKit.text(g, fit(t.artist(), 0.82f, right - tx), tx, y + 19.5f, 0.82f, HudKit.dimColor());

        // progression
        if (bar) {
            double pos = t.position(), dur = Math.max(1, t.dur());
            float bw = right - tx, by = y + 33;
            Draw.rrect(g, tx, by, bw, 2.5f, 1.25f, Draw.withAlpha(0xFFFFFF, 0.14f));
            float f = (float) Math.min(1, pos / dur);
            if (f > 0) Draw.rrect(g, tx, by, Math.max(2.5f, bw * f), 2.5f, 1.25f, 0xFF000000 | Theme.accent());
            HudKit.text(g, time(pos), tx, by + 6, 0.68f, HudKit.dimColor());
            String d = time(t.dur());
            HudKit.text(g, d, right - Draw.width(d, 0.68f), by + 6, 0.68f, HudKit.dimColor());
        }

        // boutons : précédent, lecture / pause (rond blanc), suivant
        if (controls) {
            Minecraft mc = Minecraft.getInstance();
            boolean mouse = mc.screen instanceof ChatScreen;
            double mx = mouse ? mc.mouseHandler.xpos() * g.guiWidth() / mc.getWindow().getScreenWidth() : -1;
            double my = mouse ? mc.mouseHandler.ypos() * g.guiHeight() / mc.getWindow().getScreenHeight() : -1;
            float cy = y + h / 2;
            float[] cxs = {x + w - 56, x + w - 34, x + w - 12};
            for (int i = 0; i < 3; i++) {
                float r = i == 1 ? 11 : 8;
                BTN[i] = new float[]{cxs[i] - r, cy - r, r * 2, r * 2};
                boolean hv = mouse && hit(i, mx, my);
                if (i == 1) {
                    Draw.rrect(g, cxs[i] - r, cy - r, r * 2, r * 2, r, hv ? 0xFFFFFFFF : 0xFFEDE9F7);
                    int ink = 0xFF1A1626;
                    if (t.playing()) {
                        Draw.rrect(g, cxs[i] - 3.6f, cy - 4.5f, 2.6f, 9, 0.8f, ink);
                        Draw.rrect(g, cxs[i] + 1, cy - 4.5f, 2.6f, 9, 0.8f, ink);
                    } else {
                        triangle(g, cxs[i] - 2.5f, cy, 8, 9, true, ink);
                    }
                } else {
                    int col = hv ? 0xFFFFFFFF : 0xFF000000 | Theme.TEXT_DIM;
                    if (hv) Draw.rrect(g, cxs[i] - r, cy - r, r * 2, r * 2, r, Draw.withAlpha(0xFFFFFF, 0.1f));
                    boolean fwd = i == 2;
                    triangle(g, cxs[i] + (fwd ? -3.5f : 3.5f), cy, 6.5f, 8, fwd, col);
                    Draw.rrect(g, cxs[i] + (fwd ? 3 : -4.6f), cy - 4, 1.6f, 8, 0.6f, col);
                }
            }
        }
    }

    /** Triangle plein (lecture / précédent / suivant) dessiné en fines tranches verticales. */
    private static void triangle(GuiGraphics g, float x, float cy, float w, float h, boolean right, int col) {
        for (float k = 0; k < w; k += 0.5f) {
            float hh = h * (1 - k / w);
            float xx = right ? x + k : x - k - 0.5f;
            Draw.rrect(g, xx, cy - hh / 2, 0.6f, hh, 0, col);
        }
    }

    /** Force le chargement (enregistrement du module). */
    public static void touch() {}
}
