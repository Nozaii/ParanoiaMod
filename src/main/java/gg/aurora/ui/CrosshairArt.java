package gg.aurora.ui;

import com.mojang.blaze3d.platform.NativeImage;
import gg.aurora.AuroraClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Viseur dessiné à la main : image carrée (16, 32 ou 64 px) sauvegardée dans config/aurora/crosshair.png. */
public final class CrosshairArt {
    private CrosshairArt() {}

    public static final Identifier TEX = Identifier.fromNamespaceAndPath("aurora", "custom_crosshair");
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("aurora").resolve("crosshair.png");

    public static int size = 16;
    public static int[] pixels = new int[16 * 16];
    private static boolean loaded, uploaded;

    public static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        if (Files.isRegularFile(FILE)) {
            try (InputStream in = Files.newInputStream(FILE); NativeImage img = NativeImage.read(in)) {
                int n = img.getWidth();
                if (n == img.getHeight() && (n == 16 || n == 32 || n == 64)) {
                    size = n;
                    pixels = new int[n * n];
                    for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) pixels[y * n + x] = img.getPixel(x, y);
                    return;
                }
            } catch (Exception e) {
                AuroraClient.LOGGER.warn("Viseur dessiné illisible : {}", e.toString());
            }
        }
        pixels = Presets.pixel(0, 16);
    }

    /** Envoie l'image au GPU (à chaque modification). */
    public static void upload() {
        ensureLoaded();
        NativeImage img = new NativeImage(size, size, true);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) img.setPixel(x, y, pixels[y * size + x]);
        Minecraft.getInstance().getTextureManager().register(TEX, new DynamicTexture(() -> "aurora_crosshair", img));
        uploaded = true;
    }

    public static boolean ready() {
        if (!uploaded) upload();
        return uploaded;
    }

    public static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (NativeImage img = new NativeImage(size, size, true)) {
                for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) img.setPixel(x, y, pixels[y * size + x]);
                img.writeToFile(FILE);
            }
        } catch (Exception e) {
            AuroraClient.LOGGER.warn("Impossible d'enregistrer le viseur : {}", e.toString());
        }
        upload();
    }

    /** Change la taille de la grille en gardant le dessin (mise à l'échelle au plus proche). */
    public static void resize(int n) {
        int[] out = new int[n * n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) out[y * n + x] = pixels[(y * size / n) * size + (x * size / n)];
        size = n;
        pixels = out;
    }

    /** Dessins prédéfinis (pixel art), générés à n'importe quelle taille. */
    public static final class Presets {
        private Presets() {}

        public static final String[] NAMES = {"Thin cross", "Cross + dot", "Dot", "Circle", "Target", "X", "Chevron", "Brackets",
                "Bold cross", "Wide gap", "Diamond", "Square", "Triangle", "Tri-dot", "Ring + cross", "Plus"};

        public static int[] pixel(int id, int n) {
            int[] p = new int[n * n];
            int w = 0xFFFFFFFF;
            float c = (n - 1) / 2f;
            int t = Math.max(1, n / 16);
            int gap = Math.max(1, n / 8), len = n / 2 - gap - 1;
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    float dx = x - c, dy = y - c;
                    float r = (float) Math.sqrt(dx * dx + dy * dy);
                    boolean on = switch (id) {
                        case 0 -> (Math.abs(dx) < t * 0.5f + 0.01f && Math.abs(dy) > gap && Math.abs(dy) <= gap + len)
                                || (Math.abs(dy) < t * 0.5f + 0.01f && Math.abs(dx) > gap && Math.abs(dx) <= gap + len);
                        case 1 -> (Math.abs(dx) < t * 0.5f + 0.01f && Math.abs(dy) > gap && Math.abs(dy) <= gap + len)
                                || (Math.abs(dy) < t * 0.5f + 0.01f && Math.abs(dx) > gap && Math.abs(dx) <= gap + len) || r <= t * 0.75f;
                        case 2 -> r <= Math.max(1, n / 10f);
                        case 3 -> Math.abs(r - n * 0.35f) <= t * 0.6f;
                        case 4 -> Math.abs(r - n * 0.4f) <= t * 0.6f || r <= Math.max(1, n / 14f);
                        case 5 -> (Math.abs(dx - dy) <= t * 0.7f || Math.abs(dx + dy) <= t * 0.7f) && r > gap && r < n * 0.45f;
                        case 6 -> dy > 0 && Math.abs(Math.abs(dx) - dy) <= t * 0.7f && dy < n * 0.35f;
                        case 8 -> (Math.abs(dx) <= t * 1.5f && Math.abs(dy) > gap && Math.abs(dy) <= gap + len)
                                || (Math.abs(dy) <= t * 1.5f && Math.abs(dx) > gap && Math.abs(dx) <= gap + len);
                        case 9 -> (Math.abs(dx) < t * 0.5f + 0.01f && Math.abs(dy) > n * 0.28f && Math.abs(dy) < n * 0.47f)
                                || (Math.abs(dy) < t * 0.5f + 0.01f && Math.abs(dx) > n * 0.28f && Math.abs(dx) < n * 0.47f);
                        case 10 -> Math.abs(Math.abs(dx) + Math.abs(dy) - n * 0.34f) <= t * 0.75f;
                        case 11 -> Math.max(Math.abs(dx), Math.abs(dy)) <= n * 0.3f && Math.max(Math.abs(dx), Math.abs(dy)) > n * 0.3f - t;
                        case 12 -> {
                            float e = n * 0.34f;
                            yield (Math.abs(dy - e * 0.6f) <= t * 0.6f && Math.abs(dx) <= e) || (dy <= e * 0.6f && dy >= -e
                                    && Math.abs(Math.abs(dx) - (dy + e) * 0.62f) <= t * 0.7f);
                        }
                        case 13 -> {
                            boolean d0 = Math.hypot(dx, dy + n * 0.3f) <= Math.max(1, n / 12f);
                            boolean d1 = Math.hypot(dx - n * 0.26f, dy - n * 0.15f) <= Math.max(1, n / 12f);
                            boolean d2 = Math.hypot(dx + n * 0.26f, dy - n * 0.15f) <= Math.max(1, n / 12f);
                            yield d0 || d1 || d2;
                        }
                        case 14 -> Math.abs(r - n * 0.42f) <= t * 0.6f || (Math.abs(dx) < t * 0.5f + 0.01f && Math.abs(dy) < n * 0.3f && Math.abs(dy) > gap)
                                || (Math.abs(dy) < t * 0.5f + 0.01f && Math.abs(dx) < n * 0.3f && Math.abs(dx) > gap);
                        case 15 -> (Math.abs(dx) < t * 0.5f + 0.01f && Math.abs(dy) <= n * 0.4f) || (Math.abs(dy) < t * 0.5f + 0.01f && Math.abs(dx) <= n * 0.4f);
                        default -> {
                            float a = n * 0.38f, b = n * 0.18f;
                            boolean edgeX = Math.abs(Math.abs(dx) - a) <= t * 0.5f && Math.abs(dy) <= a && Math.abs(dy) >= a - b;
                            boolean edgeY = Math.abs(Math.abs(dy) - a) <= t * 0.5f && Math.abs(dx) <= a && Math.abs(dx) >= a - b;
                            yield edgeX || edgeY;
                        }
                    };
                    if (on) p[y * n + x] = w;
                }
            }
            return p;
        }
    }
}
