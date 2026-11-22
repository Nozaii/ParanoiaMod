package gg.aurora;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Catalogue des capes : capes Paranoia (textures du mod, tools/gen_capes.py) et capes officielles
 * Java, dont la texture est téléchargée à la demande depuis les serveurs de Mojang
 * (textures.minecraft.net), comme le fait le jeu pour les capes des joueurs.
 */
public final class Capes {
    private Capes() {}

    public enum Rarity {
        RARE("Rare", 0x5FA8F3), EPIC("Epic", 0xB07CFF), LEGENDARY("Legendary", 0xF5B54A), MOJANG("Official", 0x5EE6A8),
        IMPORTED("Imported", 0xD7DCE5);
        public final String label;
        public final int color;

        Rarity(String label, int color) {
            this.label = label;
            this.color = color;
        }
    }

    /**
     * @param hash hash de texture Mojang (capes officielles), ou null pour les capes du mod
     */
    public record Cape(String id, String name, Rarity rarity, String hash) {
        public boolean official() {
            return hash != null && !imported();
        }

        /** PNG added by the player: seen only by them, hash holds the file name. */
        public boolean imported() {
            return rarity == Rarity.IMPORTED;
        }

        public Identifier texture() {
            if (imported()) return Identifier.fromNamespaceAndPath("aurora", "imported_cape/" + id.substring(IMPORTED_PREFIX.length()));
            return official()
                    ? Identifier.fromNamespaceAndPath("aurora", "official_cape/" + hash)
                    : Identifier.fromNamespaceAndPath("aurora", "textures/cape/" + id + ".png");
        }

        /** Capes tirées d'images : 1024x512 (16x vanilla). */
        public boolean photo() {
            return id.equals("hypergamy") || id.equals("johnpork");
        }

        /** Taille de la texture : 64x32 pour les officielles, 512x256 (HD) pour celles du mod, 1024x512 pour les images. */
        public int texW() {
            if (imported()) return SIZES.getOrDefault(id, new int[]{64, 32})[0];
            return official() ? 64 : photo() ? 1024 : 512;
        }

        public int texH() {
            if (imported()) return SIZES.getOrDefault(id, new int[]{64, 32})[1];
            return official() ? 32 : photo() ? 512 : 256;
        }

        public ClientAsset.Texture asset() {
            // asked for every player on every frame
            return ASSETS.computeIfAbsent(id, k -> official() || imported() ? new ClientAsset.ResourceTexture(texture(), texture())
                    : new ClientAsset.ResourceTexture(Identifier.fromNamespaceAndPath("aurora", "cape/" + id)));
        }

        /** Texture prête à l'emploi (les officielles doivent d'abord être téléchargées). */
        public boolean ready() {
            if (imported()) {
                if (!loadImported(this)) return false;
                Anim a = ANIMS.get(id);
                if (a != null) a.update();
                return true;
            }
            if (!official()) return true;
            if (LOADED.contains(hash)) return true;
            request(this);
            return false;
        }
    }

    public static final List<Cape> ALL = new ArrayList<>(List.of(
            new Cape("paranoia", "Paranoia", Rarity.LEGENDARY, null),
            new Cape("nocturne", "Nocturne", Rarity.EPIC, null),
            new Cape("aurore", "Aurora", Rarity.EPIC, null),
            new Cape("toile", "Cobweb", Rarity.RARE, null),
            new Cape("braise", "Ember", Rarity.RARE, null),
            new Cape("glitch", "Glitch", Rarity.LEGENDARY, null),
            new Cape("hypergamy", "Hypergamy", Rarity.LEGENDARY, null),
            new Cape("johnpork", "John Pork", Rarity.LEGENDARY, null)));

    /** Capes officielles Java (liste de minecraftcapes.net, textures servies par Mojang). */
    private static final String[][] OFFICIAL = {
            {"Classic Mojang", "8f120319222a9f4a104e2f5cb97b2cda93199a2ee9e1585cb8d09d6f687cb761"},
            {"Millionth", "70efffaf86fe5bc089608d3cb297d3e276b9eb7a8f9f2fe6659c23a2d8b18edf"},
            {"dB", "bcfbe84c6542a4a5c213c1cacf8979b5e913dcb4ad783a8b80e3c4a7d5c8bdac"},
            {"Snowman", "23ec737f18bfe4b547c95935fc297dd767bb84ee55bfd855144d279ac9bfd9fe"},
            {"Translator", "1bf91499701404e21bd46b0191d63239a4ef76ebde88d27e4d430ac211df681e"},
            {"cheapsh0t", "ca29f5dd9e94fb1748203b92e36b66fda80750c87ebc18d6eafdb0e28cc1d05f"},
            {"Scrolls Champion", "3efadf6510961830f9fcc077f19b4daf286d502b5f5aafbd807c7bbffcaca245"},
            {"Moderator", "ae677f7d98ac70a533713518416df4452fe5700365c09cf45d0d156ea9396551"},
            {"Chinese Translator", "2262fb1d24912209490586ecae98aca8500df3eff91f2a07da37ee524e7e3cb6"},
            {"MrMessiah", "2e002d5e1758e79ba51d08d92a0f3a95119f2f435ae7704916507b6c565a7da8"},
            {"Mojang", "5786fe99be377dfb6858859f926c4dbc995751e91cee373468c5fbf4865e7151"},
            {"Realms Map Maker", "17912790ff164b93196f08ba71d0e62129304776d0f347334f8a6eae509f8a56"},
            {"Cobalt", "ca35c56efe71ed290385f4ab5346a1826b546a54d519e6a3ff01efa01acce81"},
            {"Prismarine", "d8f8d13a1adf9636a16c31d47f3ecc9bb8d8533108aa5ad2a01b13b1a0c55eac"},
            {"Turtle", "5048ea61566353397247d2b7d946034de926b997d5e66c86483dfb1e031aee95"},
            {"Founder's", "99aba02ef05ec6aa4d42db8ee43796d6cd50e4b2954ab29f0caeb85f96bf52a1"},
            {"Pan", "28de4a81688ad18b49e735a273e086c18f1e3966956123ccb574034c06f5d336"},
            {"Birthday", "2056f2eebd759cce93460907186ef44e9192954ae12b227d817eb4b55627a7fc"},
            {"Mojang Studios", "9e507afc56359978a3eb3e32367042b853cddd0995d17d0da995662913fb00f7"},
            {"Migrator", "2340c0e03dd24a11b15a8b33c2a7e9e32abb2051b2481d0ba7defd635ca7a933"},
            {"Valentine", "e578ef995fabcf0a94768f9651ac3aaba30c59ef85d2438e9b3e0cc1d810652b"},
            {"Vanilla", "f9a76537647989f9a0b6d001e320dac591c359e9e61a31f4ce11c88f207f0ad4"},
            {"Cherry Blossom", "afd553b39358a24edfe3b8a9a939fa5fa4faa4d9a9c3d6af8eafb377fa05c2bb"},
            {"Purple Heart", "cb40a92e32b57fd732a00fc325e7afb00a7ca74936ad50d8e860152e482cfbde"},
            {"15th Anniversary", "cd9d82ab17fd92022dbd4a86cde4c382a7540e117fae7b9a2853658505a80625"},
            {"Follower's", "569b7f2a1d00d26f30efe3f9ab9ac817b1e6d35f4f3cfb0324ef2d328223d350"},
            {"MCC 15th Year", "56c35628fe1c4d59dd52561a3d03bfa4e1a76d397c8b9c476c2f77cb6aebb1df"},
            {"Minecraft Experience", "7658c5025c77cfac7574aab3af94a46a8886e3b7722a895255fbf22ab8652434"},
            {"Mojang Office", "5c29410057e32abec02d870ecb52ec25fb45ea81e785a7854ae8429d7236ca26"},
            {"Menace", "dbc21e222528e30dc88445314f7be6ff12d3aeebc3c192054fba7e3b3f8c77b1"},
            {"Home", "1de21419009db483900da6298a1e6cbf9f1bc1523a0dcdc16263fab150693edd"},
            {"Yearn", "308b32a9e303155a0b4262f9e5483ad4a22e3412e84fe8385a0bdd73dc41fa89"},
            {"Common", "5ec930cdd2629c8771655c60eebeb867b4b6559b0e6d3bc71c40c96347fa03f0"},
            {"Copper", "5e6f3193e74cd16cdd6637d9bae5484e3a37ff2a14c2d157c659a07810b1bdca"},
            {"Zombie Horse", "a3f6e4f14801f3ea55e3d95b9b4ef3b5e8802d947f669de93d6ec4b9354a436b"},
            {"Moonlight Trail", "fe8a02dfe9e390e44ff33d69feef9d3943f76d3901015bbd50f0b67722d288bd"},
            {"Crafter", "479eacefa3cdd7aca94207f36c0dd449653ddf259daf40544a5866baf05eee22"},
            {"Builder", "2c579968c64c1719740fd8c2a451461879b238002574fce48f7d1a7c36a1c7d4"},
            {"Hero", "bb384a1079b9a6f2811520c7991b6e8150d02e5a96457e44509e30822f72f38e"},
            {"Twisted", "24aafc451aa2cc34ddc7265211678585c0ef4da4d32edb75ecec1bd8b5408381"},
            {"Aurora", "7c418dfbd37412a55e9f1425c9807a591ad10cf71b1edb576a587be9114c277f"},
            {"MineCon 2011", "953cac8b779fe41383e675ee2b86071a71658f2180f56fbce8aa315ea70e2ed6"},
            {"MineCon 2012", "a2e8d97ec79100e90a75d369d1b3ba81273c4f82bc1b737e934eed4a854be1b6"},
            {"MineCon 2013", "153b1a0dfcbae953cdeb6f2c2bf6bf79943239b1372780da44bcbb29273131da"},
            {"MineCon 2015", "b0cc08840700447322d953a02b965f1d65a13a603bf64b17c803c21446fe1635"},
            {"MineCon 2016", "e7dfea16dc83c97df01a12fabbd1216359c0cd0ea42f9999b6e97c584963e980"},
    };

    static {
        for (String[] o : OFFICIAL) ALL.add(new Cape("mc_" + o[1].substring(0, 12), o[0], Rarity.MOJANG, o[1]));
        scanImported();
    }

    private static final String IMPORTED_PREFIX = "imported_";
    private static final java.util.Map<String, ClientAsset.Texture> ASSETS = new ConcurrentHashMap<>();
    private static final java.util.Map<String, int[]> SIZES = new ConcurrentHashMap<>();
    private static final Set<String> LOADED_IMPORTS = ConcurrentHashMap.newKeySet();

    public static java.nio.file.Path folder() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("aurora/capes");
    }

    private static String slug(String file) {
        String base = file.replaceAll("(?i)\\.(png|gif)$", "").toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_");
        return base.isEmpty() ? "cape" : base;
    }

    /** Lists the PNG files of the folder. */
    public static void scanImported() {
        ALL.removeIf(Cape::imported);
        try (var files = java.nio.file.Files.list(folder())) {
            files.filter(f -> f.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png")).sorted().forEach(f -> {
                String file = f.getFileName().toString();
                ALL.add(new Cape(IMPORTED_PREFIX + slug(file), file.substring(0, file.length() - 4), Rarity.IMPORTED, file));
            });
        } catch (java.io.IOException e) {
            // no folder yet: nothing imported
        }
    }

    private static boolean loadImported(Cape c) {
        if (LOADED_IMPORTS.contains(c.id())) return true;
        if (!LOADED_IMPORTS.add(c.id() + "#tried")) return false;
        try (var in = java.nio.file.Files.newInputStream(folder().resolve(c.hash()))) {
            NativeImage img = NativeImage.read(in);
            int[] delays = CapeImport.readDelays(folder(), c.hash());
            int frameH = img.getWidth() / 2;
            if (delays != null && (frameH <= 0 || img.getHeight() != frameH * delays.length)) delays = null;
            String bad = problem(img.getWidth(), delays == null ? img.getHeight() : frameH);
            if (bad != null) {
                img.close();
                AuroraClient.LOGGER.warn("Imported cape {}: {}", c.name(), bad);
                return false;
            }
            Anim old = ANIMS.remove(c.id());
            if (old != null) old.close();
            if (delays == null) {
                SIZES.put(c.id(), new int[]{img.getWidth(), img.getHeight()});
                Minecraft.getInstance().getTextureManager().register(c.texture(), new DynamicTexture(() -> "Paranoia cape " + c.name(), img));
            } else {
                // animated: the strip is cut into frames kept in memory, the texture gets the current one
                int w = img.getWidth();
                NativeImage[] frames = new NativeImage[delays.length];
                for (int f = 0; f < frames.length; f++) {
                    frames[f] = new NativeImage(w, frameH, false);
                    for (int y = 0; y < frameH; y++) {
                        for (int x = 0; x < w; x++) frames[f].setPixel(x, y, img.getPixel(x, f * frameH + y));
                    }
                }
                img.close();
                NativeImage shown = new NativeImage(w, frameH, false);
                shown.copyFrom(frames[0]);
                DynamicTexture tex = new DynamicTexture(() -> "Paranoia cape " + c.name(), shown);
                SIZES.put(c.id(), new int[]{w, frameH});
                Minecraft.getInstance().getTextureManager().register(c.texture(), tex);
                ANIMS.put(c.id(), new Anim(frames, delays, tex));
            }
            LOADED_IMPORTS.add(c.id());
            return true;
        } catch (Exception e) {
            AuroraClient.LOGGER.warn("Imported cape {} unreadable", c.name());
            return false;
        }
    }

    /** Null when the image has the cape layout (2:1, multiple of 64 wide). */
    private static String problem(int w, int h) {
        if (w < 64 || w > 2048 || w % 64 != 0 || h * 2 != w) return "The image must be 64x32, or a multiple like 128x64 (it is " + w + "x" + h + ")";
        return null;
    }

    /** An animated imported cape: every frame in memory, the registered texture shows the current one. */
    private static final class Anim {
        final NativeImage[] frames;
        final int[] ends;
        final DynamicTexture tex;
        int shown;

        Anim(NativeImage[] frames, int[] delays, DynamicTexture tex) {
            this.frames = frames;
            this.tex = tex;
            ends = new int[delays.length];
            int t = 0;
            for (int i = 0; i < delays.length; i++) ends[i] = t += delays[i];
        }

        void update() {
            if (!com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) return;
            int t = (int) (System.currentTimeMillis() % ends[ends.length - 1]), f = 0;
            while (ends[f] <= t) f++;
            if (f == shown) return;
            shown = f;
            tex.getPixels().copyFrom(frames[f]);
            tex.upload();
        }

        void close() {
            for (NativeImage f : frames) f.close();
        }
    }

    private static final java.util.Map<String, Anim> ANIMS = new ConcurrentHashMap<>();

    /** File picker and decoding off the render thread, then check, crop if needed and select on the game thread. */
    public static void importDialog() {
        Thread t = new Thread(() -> {
            String path;
            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                var filter = stack.mallocPointer(2);
                filter.put(stack.UTF8("*.png")).put(stack.UTF8("*.gif")).flip();
                path = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog("Import a cape", "", filter, "PNG or GIF image", false);
            }
            if (path == null) return;
            java.nio.file.Path src = java.nio.file.Path.of(path);
            try {
                if (java.nio.file.Files.size(src) > 16L << 20) {
                    fail("The file is over 16 MB");
                    return;
                }
                CapeImport.Frames frames = CapeImport.decode(src);
                Minecraft.getInstance().execute(() -> imported(src, frames));
            } catch (Exception e) {
                AuroraClient.LOGGER.warn("Cape import failed", e);
                fail("This file is not a readable PNG or GIF");
            }
        }, "Paranoia cape import");
        t.setDaemon(true);
        t.start();
    }

    private static final java.util.regex.Pattern TENOR_MEDIA = java.util.regex.Pattern.compile("https://media1?[.]tenor[.]com/[A-Za-z0-9_/.%-]+?[.]gif");
    private static final long LINK_MAX = 15L << 20;
    /** Page Klipy (https://klipy.com/gifs/<slug>) : l'API publique donne l'adresse du GIF sans cle. */
    private static final java.util.regex.Pattern KLIPY_PAGE = java.util.regex.Pattern.compile(
            "https://(?:www[.])?klipy[.]com/(?:[a-z]{2}/)?(?:gifs|clips|stickers|ai-gifs|static-memes)/([A-Za-z0-9_-]+)/?(?:[?#].*)?");
    private static final java.util.regex.Pattern KLIPY_GIF = java.util.regex.Pattern.compile("https://static[.]klipy[.]com/[A-Za-z0-9_/.%-]+?[.]gif");

    /** Lien vers une page Tenor ou Klipy, ou vers une image : ce que le collage accepte comme image. */
    public static boolean looksLikeImageLink(String link) {
        if (!isImportLink(link)) return false;
        String l = link.trim();
        return l.contains("tenor.com/") || KLIPY_PAGE.matcher(l).matches()
                || l.toLowerCase(java.util.Locale.ROOT).matches(".*[.](gif|png|jpe?g)([?#].*)?$");
    }

    /** True when the text looks like a link this import accepts (HTTPS only). */
    public static boolean isImportLink(String s) {
        if (s == null) return false;
        s = s.trim();
        return s.length() < 600 && s.startsWith("https://") && !s.contains(" ") && s.indexOf(10) < 0;
    }

    /**
     * Cape from a link: a Tenor page (its GIF is looked up in the page) or a direct HTTPS link to a GIF, PNG or JPEG.
     * Downloaded off the render thread, 15 MB at most, then the same path as a file (crop if needed).
     */
    public static void importLink(String link) {
        if (!isImportLink(link)) {
            fail("Copy an https link first (Tenor, Klipy, or a direct .gif / .png / .jpg)");
            return;
        }
        gg.aurora.ui.Toasts.push(gg.aurora.ui.Icons.INFO, "Cape", "Downloading the image…", false);
        fetchImage(link, (frames, err) -> {
            if (frames == null) fail(err);
            else imported(java.nio.file.Path.of(lastLinkName), frames);
        });
    }

    private static volatile String lastLinkName = "link";
    /** Octets bruts du dernier lien téléchargé (pour renvoyer un GIF tel quel en message). */
    public static volatile byte[] lastLinkBytes;

    /**
     * Downloads and decodes the picture behind a link (Tenor page, or direct HTTPS GIF / PNG / JPEG), off the
     * render thread. The callback runs on the game thread with the frames, or null and the reason.
     */
    public static void fetchImage(String link, java.util.function.BiConsumer<CapeImport.Frames, String> done) {
        String url = link == null ? "" : link.trim();
        Minecraft mc = Minecraft.getInstance();
        if (!isImportLink(url)) {
            mc.execute(() -> done.accept(null, "This is not an https link"));
            return;
        }
        Thread t = new Thread(() -> {
            java.nio.file.Path tmp = null;
            try {
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
                URI uri = URI.create(url);
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
                if ((host.equals("tenor.com") || host.endsWith(".tenor.com")) && !host.startsWith("media")) {
                    // a Tenor page: the GIF address is inside it
                    HttpResponse<java.io.InputStream> page = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                            .header("User-Agent", "Mozilla/5.0").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                    String html;
                    try (var in = page.body()) {
                        html = new String(in.readNBytes(3 << 20), java.nio.charset.StandardCharsets.UTF_8);
                    }
                    var m = TENOR_MEDIA.matcher(html.replace("\\u002F", "/").replace("\\/", "/"));
                    if (page.statusCode() != 200 || !m.find()) {
                        mc.execute(() -> done.accept(null, "No GIF found on this Tenor page"));
                        return;
                    }
                    uri = URI.create(m.group());
                }
                var kp = KLIPY_PAGE.matcher(url);
                if (kp.matches()) {
                    // a Klipy page sits behind a bot check; its public API gives the GIF address from the slug
                    HttpResponse<String> api = http.send(HttpRequest.newBuilder(URI.create("https://api.klipy.com/api/v1/gifs/" + kp.group(1)))
                            .timeout(Duration.ofSeconds(15)).header("User-Agent", "Mozilla/5.0").GET().build(), HttpResponse.BodyHandlers.ofString());
                    String body = api.body() == null ? "" : api.body().replace("\\/", "/");
                    // the "hd" file block comes first; its .gif is the full-size one
                    int hd = body.indexOf("\"hd\"");
                    var m = KLIPY_GIF.matcher(hd < 0 ? body : body.substring(hd));
                    if (api.statusCode() != 200 || !m.find()) {
                        mc.execute(() -> done.accept(null, "No GIF found for this Klipy link"));
                        return;
                    }
                    uri = URI.create(m.group());
                }
                HttpResponse<java.io.InputStream> res = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                        .header("User-Agent", "Mozilla/5.0").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                byte[] data;
                try (var in = res.body()) {
                    data = in.readNBytes((int) LINK_MAX + 1);
                }
                if (res.statusCode() != 200 || !"https".equals(res.uri().getScheme())) {
                    mc.execute(() -> done.accept(null, "The link did not answer with an image"));
                    return;
                }
                if (data.length > LINK_MAX) {
                    mc.execute(() -> done.accept(null, "The image is over 15 MB"));
                    return;
                }
                // the real format comes from the first bytes, never from the link or the server's header
                String ext = data.length > 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F' ? ".gif"
                        : data.length > 8 && (data[0] & 255) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G' ? ".png"
                        : data.length > 3 && (data[0] & 255) == 0xFF && (data[1] & 255) == 0xD8 ? ".jpg" : null;
                if (ext == null) {
                    mc.execute(() -> done.accept(null, "Only GIF, PNG and JPEG images work (WebP and videos do not)"));
                    return;
                }
                String path = uri.getPath() == null ? "" : uri.getPath();
                String base = path.substring(path.lastIndexOf('/') + 1).replaceAll("(?i)[.](gif|png|jpe?g)$", "");
                lastLinkName = base.isBlank() ? "link" : base.length() > 40 ? base.substring(0, 40) : base;
                lastLinkBytes = data;
                tmp = java.nio.file.Files.createTempFile("paranoia-img-", ext);
                java.nio.file.Files.write(tmp, data);
                CapeImport.Frames frames = CapeImport.decode(tmp);
                mc.execute(() -> done.accept(frames, null));
            } catch (Exception e) {
                AuroraClient.LOGGER.warn("Image link failed: {}", e.toString());
                mc.execute(() -> done.accept(null, "Could not download this link"));
            } finally {
                if (tmp != null) {
                    try {
                        java.nio.file.Files.deleteIfExists(tmp);
                    } catch (java.io.IOException ignored) {
                    }
                }
            }
        }, "Paranoia image link");
        t.setDaemon(true);
        t.start();
    }

    private static void fail(String why) {
        Minecraft.getInstance().execute(() -> gg.aurora.ui.Toasts.push(gg.aurora.ui.Icons.INFO, "Cape not imported", why, false));
    }

    private static void imported(java.nio.file.Path src, CapeImport.Frames frames) {
        String slug = slug(src.getFileName().toString());
        if (problem(frames.w(), frames.h()) == null) {
            // already in the cape layout: kept as it is (every frame of a GIF)
            long bytes = 4L * frames.w() * frames.h() * frames.argb().size();
            if (bytes > 96L << 20) {
                gg.aurora.ui.Toasts.push(gg.aurora.ui.Icons.INFO, "Cape not imported", "Too many frames for this size", false);
                return;
            }
            saveAsync(slug, frames);
            return;
        }
        // any other picture: the player picks the part that goes on the cape
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new gg.aurora.ui.CapeCropScreen(mc.screen, frames, r -> {
            gg.aurora.ui.Toasts.push(gg.aurora.ui.Icons.INFO, "Cape", frames.animated() ? "Building " + frames.argb().size() + " frames…" : "Building the cape…", false);
            java.util.concurrent.CompletableFuture.supplyAsync(() -> CapeImport.build(frames, r[0], r[1], r[2], r[3]))
                    .whenComplete((cape, err) -> {
                        if (err != null) {
                            AuroraClient.LOGGER.warn("Cape build failed", err);
                            fail("Could not build the cape");
                        } else {
                            mc.execute(() -> saveAsync(slug, cape));
                        }
                    });
        }));
    }

    private static void saveAsync(String slug, CapeImport.Frames cape) {
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                CapeImport.save(folder(), slug, cape);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }).whenComplete((v, err) -> Minecraft.getInstance().execute(() -> {
            if (err != null) {
                AuroraClient.LOGGER.warn("Cape save failed", err);
                gg.aurora.ui.Toasts.push(gg.aurora.ui.Icons.INFO, "Cape not imported", "Could not write the file", false);
                return;
            }
            String id = IMPORTED_PREFIX + slug;
            LOADED_IMPORTS.remove(id);
            LOADED_IMPORTS.remove(id + "#tried");
            ASSETS.remove(id);
            scanImported();
            Settings.cape = id;
            Config.save();
            gg.aurora.ui.Toasts.push(gg.aurora.ui.Icons.CHECK, "Cape imported", Settings.shareImportedCape ? "Other players will see it in a moment" : "Only you can see it (sharing is off)", false);
        }));
    }

    private static final Set<String> LOADED = ConcurrentHashMap.newKeySet();
    private static final Set<String> REQUESTED = ConcurrentHashMap.newKeySet();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    private static void request(Cape c) {
        if (!REQUESTED.add(c.hash())) return;
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://textures.minecraft.net/texture/" + c.hash()))
                .timeout(Duration.ofSeconds(15)).GET().build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray()).whenComplete((res, err) -> {
            if (err != null || res.statusCode() != 200) {
                REQUESTED.remove(c.hash()); // nouvel essai plus tard
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                try {
                    NativeImage img = NativeImage.read(res.body());
                    mc.getTextureManager().register(c.texture(), new DynamicTexture(() -> "Paranoia cape " + c.name(), img));
                    LOADED.add(c.hash());
                } catch (Exception e) {
                    AuroraClient.LOGGER.warn("Cape {} illisible", c.name());
                }
            });
        });
    }

    public static Cape selected() {
        return byId(Settings.cape);
    }

    public static Cape byId(String id) {
        if (id == null || id.isEmpty()) return null;
        for (Cape c : ALL) if (c.id().equals(id)) return c;
        return null;
    }
}
