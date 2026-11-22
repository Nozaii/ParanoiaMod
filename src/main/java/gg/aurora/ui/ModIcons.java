package gg.aurora.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Icônes des mods : celle du jar (fabric.mod.json « icon ») pour les mods installés, celle de Modrinth pour
 * le Store, et les images des galeries Modrinth. Chargées hors du thread de jeu, une fois, puis gardées en texture.
 * PNG lu par Minecraft ; WebP (TwelveMonkeys), JPEG et GIF par ImageIO. Illisible ou absente : la tuile à l'initiale.
 */
public final class ModIcons {
    private ModIcons() {}

    private record Icon(Identifier id, int w, int h) {}

    private static final Icon NONE = new Icon(null, 0, 0);
    private static final Map<String, Icon> CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService LOAD = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Paranoia mod icons");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final long MAX = 1 << 20;
    private static final long MAX_PICTURE = 8L << 20;

    static {
        // Fabric's class loader hides the jar's ServiceLoader file from ImageIO: register the WebP reader by hand
        try {
            javax.imageio.spi.IIORegistry.getDefaultInstance().registerServiceProvider(new com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi());
        } catch (Throwable t) {
            gg.aurora.AuroraClient.LOGGER.warn("WebP pictures unavailable: {}", t.toString());
        }
    }

    /** Whether an icon or picture is ready (a test reads it). */
    public static boolean loaded(String url) {
        Icon i = CACHE.get("url:" + url);
        if (i == null) i = CACHE.get("pic:" + url);
        return i != null && i.id() != null;
    }

    /**
     * A gallery picture fitted into the box (aspect kept, centred); a quiet placeholder while it loads.
     * @return false while it is not there yet
     */
    public static boolean picture(GuiGraphics g, String url, float x, float y, float w, float h, float a) {
        Icon i = url == null ? NONE : get("pic:" + url, () -> fromUrl(url, MAX_PICTURE), 480);
        if (i.id() == null) {
            Draw.rrect(g, x, y, w, h, 6, Draw.withAlpha(Theme.ROW_HOVER, 0.9f * a));
            return false;
        }
        float s = Math.min(w / i.w(), h / i.h());
        float dw = i.w() * s, dh = i.h() * s;
        Draw.image(g, i.id(), x + (w - dw) / 2, y + (h - dh) / 2, dw, dh, i.w(), i.h(), 0, 0, i.w(), i.h(), Draw.withAlpha(0xFFFFFF, a));
        return true;
    }

    /** Icône d'un jar installé, ou l'initiale tant qu'elle n'est pas là. */
    public static void jar(GuiGraphics g, Path jar, String name, float x, float y, float size, float a) {
        if (jar == null) {
            draw(g, null, name, x, y, size, a);
            return;
        }
        String key = "jar:" + jar.toAbsolutePath();
        draw(g, get(key, () -> fromJar(jar), 96), name, x, y, size, a);
    }

    /** Icône Modrinth (URL du projet), ou l'initiale. */
    public static void remote(GuiGraphics g, String url, String name, float x, float y, float size, float a) {
        if (url == null || url.isBlank()) {
            draw(g, null, name, x, y, size, a);
            return;
        }
        draw(g, get("url:" + url, () -> fromUrl(url, MAX), 96), name, x, y, size, a);
    }

    private interface Source {
        byte[] bytes() throws Exception;
    }

    private static Icon get(String key, Source src, int maxPx) {
        Icon i = CACHE.get(key);
        if (i != null) return i;
        CACHE.put(key, NONE); // en cours : l'initiale en attendant
        LOAD.execute(() -> {
            try {
                byte[] data = src.bytes();
                if (data == null || data.length < 8) return;
                NativeImage read = decode(data, maxPx);
                if (read == null) return;
                // pixel art (32 px or less) stays sharp; anything bigger is shrunk on the CPU then sampled smoothly
                boolean smooth = Math.max(read.getWidth(), read.getHeight()) > 32;
                NativeImage img = smooth ? shrink(read, maxPx) : read;
                Identifier id = Identifier.fromNamespaceAndPath("aurora", "modicon/" + Integer.toHexString(key.hashCode()));
                Minecraft.getInstance().execute(() -> {
                    DynamicTexture tex = new DynamicTexture(() -> "Paranoia mod icon", img) {
                        {
                            if (smooth) sampler = com.mojang.blaze3d.systems.RenderSystem.getSamplerCache()
                                    .getClampToEdge(com.mojang.blaze3d.textures.FilterMode.LINEAR);
                        }
                    };
                    Minecraft.getInstance().getTextureManager().register(id, tex);
                    CACHE.put(key, new Icon(id, img.getWidth(), img.getHeight()));
                });
            } catch (Exception ignored) {
                // icône illisible : l'initiale reste
            }
        });
        return NONE;
    }

    /** PNG through Minecraft; anything else (WebP, JPEG, GIF) through ImageIO, scaled to at most {@code maxPx} first. */
    private static NativeImage decode(byte[] data, int maxPx) throws Exception {
        if ((data[0] & 0xFF) == 0x89 && data[1] == 'P') return NativeImage.read(new java.io.ByteArrayInputStream(data));
        java.awt.image.BufferedImage b = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data));
        if (b == null) return null;
        int w = b.getWidth(), h = b.getHeight();
        float f = Math.min(1f, (float) maxPx / Math.max(w, h));
        int nw = Math.max(1, Math.round(w * f)), nh = Math.max(1, Math.round(h * f));
        java.awt.image.BufferedImage argb = new java.awt.image.BufferedImage(nw, nh, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D gr = argb.createGraphics();
        gr.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        gr.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        gr.drawImage(b, 0, 0, nw, nh, null);
        gr.dispose();
        NativeImage out = new NativeImage(nw, nh, false);
        for (int y = 0; y < nh; y++) for (int x = 0; x < nw; x++) out.setPixel(x, y, argb.getRGB(x, y));
        return out;
    }

    /** Box filter down to at most {@code max} px: averaging keeps big icons clean instead of aliased. */
    private static NativeImage shrink(NativeImage src, int max) {
        int w = src.getWidth(), h = src.getHeight();
        int f = 1;
        while (Math.max(w, h) / (f * 2) >= max) f *= 2;
        if (f == 1) return src;
        int nw = w / f, nh = h / f;
        NativeImage out = new NativeImage(nw, nh, false);
        for (int y = 0; y < nh; y++) {
            for (int x = 0; x < nw; x++) {
                long sa = 0, sr = 0, sg = 0, sb = 0;
                for (int dy = 0; dy < f; dy++) {
                    for (int dx = 0; dx < f; dx++) {
                        int c = src.getPixel(x * f + dx, y * f + dy);
                        int al = c >>> 24;
                        sa += al;
                        // premultiplied, so transparent edges don't darken
                        sr += (long) (c >> 16 & 255) * al;
                        sg += (long) (c >> 8 & 255) * al;
                        sb += (long) (c & 255) * al;
                    }
                }
                int n = f * f;
                int al = (int) (sa / n);
                int r = sa == 0 ? 0 : (int) (sr / sa), gg = sa == 0 ? 0 : (int) (sg / sa), b = sa == 0 ? 0 : (int) (sb / sa);
                out.setPixel(x, y, al << 24 | r << 16 | gg << 8 | b);
            }
        }
        src.close();
        return out;
    }

    /** Icône d'un mod chargé (y compris les bibliothèques internes), lue dans son conteneur. */
    public static void mod(GuiGraphics g, net.fabricmc.loader.api.ModContainer m, float x, float y, float size, float a) {
        String name = m.getMetadata().getName();
        draw(g, get("mod:" + m.getMetadata().getId(), () -> {
            var icon = m.getMetadata().getIconPath(64);
            if (icon.isEmpty()) return null;
            var p = m.findPath(icon.get());
            if (p.isEmpty() || java.nio.file.Files.size(p.get()) > MAX) return null;
            return java.nio.file.Files.readAllBytes(p.get());
        }, 96), name, x, y, size, a);
    }

    private static byte[] fromJar(Path jar) throws Exception {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry meta = z.getEntry("fabric.mod.json");
            if (meta == null) return null;
            String path;
            try (InputStream in = z.getInputStream(meta)) {
                JsonObject o = com.google.gson.JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonElement icon = o.get("icon");
                if (icon == null) return null;
                if (icon.isJsonPrimitive()) path = icon.getAsString();
                else {
                    // plusieurs tailles : la plus proche de 64
                    path = null;
                    int best = Integer.MAX_VALUE;
                    for (var e : icon.getAsJsonObject().entrySet()) {
                        int s = Integer.parseInt(e.getKey());
                        if (Math.abs(s - 64) < best) {
                            best = Math.abs(s - 64);
                            path = e.getValue().getAsString();
                        }
                    }
                }
            }
            if (path == null) return null;
            ZipEntry e = z.getEntry(path.startsWith("/") ? path.substring(1) : path);
            if (e == null || e.getSize() > MAX) return null;
            try (InputStream in = z.getInputStream(e)) {
                return in.readNBytes((int) MAX);
            }
        }
    }

    private static byte[] fromUrl(String url, long max) throws Exception {
        URI u = URI.create(url);
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equals(u.getScheme()) || !host.endsWith("modrinth.com")) return null;
        HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        return r.statusCode() == 200 && r.body().length <= max ? r.body() : null;
    }

    private static void draw(GuiGraphics g, Icon icon, String name, float x, float y, float size, float a) {
        if (icon != null && icon.id() != null) {
            Draw.image(g, icon.id(), x, y, size, size, icon.w(), icon.h(), 0, 0, icon.w(), icon.h(), Draw.withAlpha(0xFFFFFF, a));
            return;
        }
        // no icon: a quiet tile with the initial, tinted by the name so the list doesn't look uniform
        int tint = Draw.lerpColor(Theme.ROW_HOVER, java.awt.Color.HSBtoRGB((name == null ? 0 : name.hashCode() & 255) / 255f, 0.35f, 0.75f) & 0xFFFFFF, 0.22f);
        Draw.rrect(g, x, y, size, size, size * 0.28f, Draw.withAlpha(tint, 0.95f * a));
        String l = name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
        float sc = Math.max(0.75f, size / 20f);
        Draw.centered(g, l, x + size / 2, y + size / 2 - 3.5f * sc, sc, Draw.withAlpha(Theme.TEXT, 0.85f * a));
    }
}
