package gg.aurora;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Pages de joueur : bannière (image ou GIF), bio, pose du skin, visuel + légende, onglet Discord, vues, likes,
 * historique des skins enregistré par le serveur à partir de maintenant. Tout passe par la session sociale.
 */
public final class PlayerPages {
    private PlayerPages() {}

    public static final String[] POSES = {"stand", "walk", "sneak", "swing", "fly"};
    public static final String[] POSE_NAMES = {"Standing", "Walking", "Sneaking", "Swinging", "Flying"};
    public static final int IMAGE_MAX = 512 * 1024, GIF_FRAMES = 24;

    public record Discord(String name, String username, String avatar, String banner, int accent, String decoration, List<String> badges) {}

    public record Skin(String url, String model, long first, long seen) {}

    /** Apparence de la page : fond, cadre d'avatar, titre, statut, liens, vitrine, confidentialité. */
    public record Look(String bgType, int bg1, int bg2, String background, String frame, String title, String status,
                       Map<String, String> links, boolean privateMode, int showcase, int accent, int cardStyle, int cardAlpha, int radius) {
        public static final String[] CARD_STYLES = {"Glass", "Solid", "Outline", "Clear"};
        public static final String[] RADII = {"Square", "Soft", "Round"};
        public static final int SHOW_COSMETICS = 1, SHOW_STATS = 2, SHOW_BADGES = 4, SHOW_SKINS = 8;

        public boolean shows(int flag) {
            return (showcase & flag) != 0;
        }
    }

    public record Stats(int playtime, int kills, int deaths, int bestStreak) {
        public String kd() {
            return deaths == 0 ? String.valueOf(kills) : String.format(Locale.ROOT, "%.2f", kills / (double) deaths);
        }
    }

    /** Badge gagné (calculé par le serveur). Rareté : 0 commun, 1 rare, 2 épique, 3 légendaire. */
    public record Badge(String id, String name, int rarity, String desc) {
        public static final String[] RARITY = {"Common", "Rare", "Epic", "Legendary"};
        public static final int[] COLORS = {0xB9B4CF, 0x5FA8F3, 0xB07CFF, 0xF5B54A};

        public int color() {
            return COLORS[Math.max(0, Math.min(3, rarity))];
        }
    }

    public static final String[] FRAMES = {"none", "ring", "double", "gold", "neon", "frost", "flame", "royal"};
    public static final String[] FRAME_NAMES = {"None", "Ring", "Double", "Gold", "Neon", "Frost", "Flame", "Royal"};
    public static final String[] BG_TYPES = {"color", "gradient", "image"};
    public static final String[] LINK_SITES = {"youtube", "twitch", "tiktok", "x", "instagram", "github"};
    public static final String[] LINK_NAMES = {"YouTube", "Twitch", "TikTok", "X", "Instagram", "GitHub"};

    public record Page(UUID uuid, String name, String bio, String pose, String caption, boolean showDiscord, String banner, String visual,
                       int views, int likes, boolean liked, Discord discord, List<Skin> skins, String error,
                       Look look, Stats stats, List<Badge> badges, long created, boolean online, long lastSeen,
                       Map<String, String> cosmetics, boolean hidden) {
        public boolean failed() {
            return error != null;
        }

        public Badge titleBadge() {
            if (look == null || look.title() == null) return null;
            for (Badge b : badges) if (b.id().equals(look.title())) return b;
            return null;
        }

        public int poseIndex() {
            for (int i = 0; i < POSES.length; i++) if (POSES[i].equals(pose)) return i;
            return 0;
        }
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Paranoia pages");
        t.setDaemon(true);
        return t;
    });
    private static final Map<String, Page> PAGES = new ConcurrentHashMap<>();
    private static final java.util.Set<String> LOADING = ConcurrentHashMap.newKeySet();

    private static String key(String k) {
        return k.toLowerCase(Locale.ROOT).replace("-", "");
    }

    public static UUID me() {
        return Friends.myId != null ? Friends.myId : Minecraft.getInstance().getUser().getProfileId();
    }

    public static boolean mine(Page p) {
        return p != null && p.uuid() != null && p.uuid().equals(me());
    }

    /** Page d'un joueur (UUID ou pseudo), null tant qu'elle se charge. */
    public static Page get(String userKey) {
        String k = key(userKey);
        Page p = PAGES.get(k);
        if (p != null || !LOADING.add(k)) return p;
        EXEC.execute(() -> {
            Page out;
            try {
                if (!Friends.ensureSession()) out = error(Friends.status.isEmpty() ? "Not connected" : Friends.status);
                else {
                    // « me » : ma page, résolue par le serveur avec la session (l'UUID local peut différer en dev)
                    Friends.Res r = Friends.http("GET", k.equals("me") ? "/v2/page" : "/v2/page?user=" + k, null);
                    out = r.code() == 200 && r.body() != null ? parse(r.body()) : error(r.message());
                }
            } catch (Exception e) {
                out = error("Server unreachable");
            }
            PAGES.put(k, out);
            if (out.uuid() != null) PAGES.put(out.uuid().toString().replace("-", ""), out);
            LOADING.remove(k);
        });
        return null;
    }

    /** Recharge (après une modification ou pour revoir les compteurs). */
    public static void refresh(String userKey) {
        String k = key(userKey);
        if (!k.equals("me")) {
            Page mine = PAGES.get("me");
            if (mine != null && mine.uuid() != null && mine.uuid().toString().replace("-", "").equals(k)) {
                PAGES.remove("me");
                LOADING.remove("me");
            }
        } else {
            LOADING.remove("me");
            Page mine = PAGES.remove("me");
            if (mine != null && mine.uuid() != null) k = mine.uuid().toString().replace("-", "");
        }
        Page old = PAGES.remove(k);
        if (old != null && old.uuid() != null) PAGES.remove(old.uuid().toString().replace("-", ""));
        LOADING.remove(k);
    }

    private static Page error(String msg) {
        return new Page(null, "", "", "stand", "", false, null, null, 0, 0, false, null, List.of(), msg == null ? "Unavailable" : msg,
                defaultLook(), new Stats(0, 0, 0, 0), List.of(), 0, false, 0, Map.of(), false);
    }

    public static Look defaultLook() {
        return new Look("gradient", -1, -1, null, "ring", null, "", Map.of(), false, 15, -1, 0, 55, 1);
    }

    private static int intOr(JsonObject o, String k, int def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : def;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private static Page parse(JsonObject o) {
        UUID id = UUID.fromString(o.get("uuid").getAsString().replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
        if (o.has("private") && o.get("private").getAsBoolean() && !o.has("views")) {
            // page privée d'un joueur dont je ne suis pas ami
            return new Page(id, str(o, "name"), "", "stand", "", false, null, null, 0, 0, false, null, List.of(), null,
                    defaultLook(), new Stats(0, 0, 0, 0), List.of(), 0, false, 0, Map.of(), true);
        }
        Discord d = null;
        if (o.has("discord") && o.get("discord").isJsonObject()) {
            JsonObject j = o.getAsJsonObject("discord");
            List<String> badges = new ArrayList<>();
            if (j.has("badges") && j.get("badges").isJsonArray()) for (var b : j.getAsJsonArray("badges")) badges.add(b.getAsString());
            d = new Discord(str(j, "name"), str(j, "username"), str(j, "avatar"), str(j, "banner"),
                    j.has("accent") && !j.get("accent").isJsonNull() ? j.get("accent").getAsInt() : -1, str(j, "decoration"), badges);
        }
        List<Skin> skins = new ArrayList<>();
        if (o.has("skins") && o.get("skins").isJsonArray()) {
            JsonArray a = o.getAsJsonArray("skins");
            for (var e : a) {
                JsonObject s = e.getAsJsonObject();
                skins.add(new Skin(s.get("url").getAsString(), s.get("model").getAsString(), s.get("first").getAsLong(), s.get("seen").getAsLong()));
            }
        }
        java.util.LinkedHashMap<String, String> links = new java.util.LinkedHashMap<>();
        if (o.has("links") && o.get("links").isJsonObject()) for (var e : o.getAsJsonObject("links").entrySet()) links.put(e.getKey(), e.getValue().getAsString());
        Look look = new Look(o.has("bg_type") ? str(o, "bg_type") : "gradient", intOr(o, "bg1", -1), intOr(o, "bg2", -1), str(o, "background"),
                o.has("frame") ? str(o, "frame") : "ring", str(o, "title"), o.has("status") ? str(o, "status") : "", links,
                o.has("private") && o.get("private").getAsBoolean(), intOr(o, "showcase", 15),
                intOr(o, "accent", -1), intOr(o, "card_style", 0), intOr(o, "card_alpha", 55), intOr(o, "radius", 1));
        Stats stats = new Stats(0, 0, 0, 0);
        if (o.has("stats") && o.get("stats").isJsonObject()) {
            JsonObject s = o.getAsJsonObject("stats");
            stats = new Stats(intOr(s, "playtime", 0), intOr(s, "kills", 0), intOr(s, "deaths", 0), intOr(s, "best_streak", 0));
        }
        List<Badge> badges = new ArrayList<>();
        if (o.has("badges") && o.get("badges").isJsonArray()) {
            for (var e : o.getAsJsonArray("badges")) {
                JsonObject b = e.getAsJsonObject();
                badges.add(new Badge(str(b, "id"), str(b, "name"), intOr(b, "rarity", 0), str(b, "desc")));
            }
        }
        java.util.LinkedHashMap<String, String> cos = new java.util.LinkedHashMap<>();
        if (o.has("cosmetics") && o.get("cosmetics").isJsonObject()) for (var e : o.getAsJsonObject("cosmetics").entrySet()) cos.put(e.getKey(), e.getValue().getAsString());
        return new Page(id, str(o, "name"), str(o, "bio"), str(o, "pose"), str(o, "caption"), o.has("show_discord") && o.get("show_discord").getAsBoolean(),
                str(o, "banner"), str(o, "visual"), o.get("views").getAsInt(), o.get("likes").getAsInt(), o.get("liked").getAsBoolean(), d, skins, null,
                look, stats, badges, o.has("created") ? o.get("created").getAsLong() : 0, o.has("online") && o.get("online").getAsBoolean(),
                o.has("last_seen") ? o.get("last_seen").getAsLong() : 0, cos, false);
    }

    // ------------------------------------------------------------------ actions

    public static void save(String bio, String pose, String caption, boolean showDiscord, Look look, Consumer<String> done) {
        JsonObject b = new JsonObject();
        b.addProperty("bio", bio);
        b.addProperty("pose", pose);
        b.addProperty("caption", caption);
        b.addProperty("show_discord", showDiscord);
        b.addProperty("bg_type", look.bgType());
        if (look.bg1() >= 0) b.addProperty("bg1", look.bg1());
        if (look.bg2() >= 0) b.addProperty("bg2", look.bg2());
        b.addProperty("frame", look.frame());
        if (look.title() != null) b.addProperty("title", look.title());
        b.addProperty("status", look.status());
        JsonObject links = new JsonObject();
        look.links().forEach(links::addProperty);
        b.add("links", links);
        b.addProperty("private", look.privateMode());
        b.addProperty("showcase", look.showcase());
        if (look.accent() >= 0) b.addProperty("accent", look.accent());
        b.addProperty("card_style", look.cardStyle());
        b.addProperty("card_alpha", look.cardAlpha());
        b.addProperty("radius", look.radius());
        post("/v2/page", b, err -> {
            if (err == null) refresh(me().toString());
            done.accept(err);
        });
    }

    public static void like(UUID target, Consumer<String> done) {
        JsonObject b = new JsonObject();
        b.addProperty("uuid", target.toString().replace("-", ""));
        post("/v2/page/like", b, err -> {
            if (err == null) refresh(target.toString());
            done.accept(err);
        });
    }

    /** Statistiques de jeu comptées par le mod (PlayerStats), envoyées sans attendre de réponse. */
    static void reportStats(JsonObject body) {
        EXEC.execute(() -> {
            try {
                if (Friends.ensureSession()) Friends.http("POST", "/v2/page/stats", body);
            } catch (Exception e) {
                AuroraClient.LOGGER.debug("Stats report: {}", e.toString());
            }
        });
    }

    private static void post(String path, JsonObject body, Consumer<String> done) {
        EXEC.execute(() -> {
            String err = null;
            try {
                if (!Friends.ensureSession()) err = Friends.status;
                else {
                    Friends.Res r = Friends.http("POST", path, body);
                    if (r.code() != 200) err = r.message();
                }
            } catch (Exception e) {
                err = "Server unreachable";
            }
            String e2 = err;
            Minecraft.getInstance().execute(() -> done.accept(e2));
        });
    }

    /**
     * Bannière ou visuel : PNG ou GIF (512 Ko, 24 images max), envoyé tel quel ; JPEG converti en PNG.
     * done(null) = en ligne.
     */
    public static void upload(Path file, String kind, Consumer<String> done) {
        EXEC.execute(() -> {
            String err = null;
            try {
                String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
                byte[] bytes;
                String type;
                if (n.endsWith(".gif")) {
                    bytes = Files.readAllBytes(file);
                    type = "image/gif";
                    if (CapeImport.decode(file).argb().size() > GIF_FRAMES) err = "GIFs are limited to " + GIF_FRAMES + " frames";
                } else if (n.endsWith(".png")) {
                    bytes = Files.readAllBytes(file);
                    type = "image/png";
                } else {
                    CapeImport.Frames f = CapeImport.decode(file);
                    bytes = Media.png(f.w(), f.h(), f.argb().get(0));
                    type = "image/png";
                }
                if (err == null && bytes.length > IMAGE_MAX) err = "Pictures are limited to 512 KB";
                if (err == null) {
                    Friends.ensureSession();
                    HttpRequest req = HttpRequest.newBuilder(URI.create(Friends.api() + "/v2/page/" + kind))
                            .timeout(Duration.ofSeconds(30)).header("authorization", "Bearer " + Friends.token()).header("content-type", type)
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
                    HttpResponse<String> r = Friends.HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                    if (r.statusCode() != 200) {
                        var o = new com.google.gson.Gson().fromJson(r.body(), JsonObject.class);
                        err = o != null && o.has("message") ? o.get("message").getAsString() : "Upload failed (" + r.statusCode() + ")";
                    } else {
                        refresh("me");
                    }
                }
            } catch (Exception e) {
                err = "This file is not a readable picture";
            }
            String e2 = err;
            Minecraft.getInstance().execute(() -> done.accept(e2));
        });
    }

    public static void removeImage(String kind, Consumer<String> done) {
        EXEC.execute(() -> {
            String err = null;
            try {
                Friends.ensureSession();
                Friends.Res r = Friends.http("DELETE", "/v2/page/" + kind, null);
                if (r.code() != 200) err = r.message();
                else refresh("me");
            } catch (Exception e) {
                err = "Server unreachable";
            }
            String e2 = err;
            Minecraft.getInstance().execute(() -> done.accept(e2));
        });
    }

    /** Sélecteur de fichier puis envoi. */
    public static void pickAndUpload(String kind, Consumer<String> done) {
        Thread t = new Thread(() -> {
            String path;
            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                var filter = stack.mallocPointer(4);
                filter.put(stack.UTF8("*.png")).put(stack.UTF8("*.gif")).put(stack.UTF8("*.jpg")).put(stack.UTF8("*.jpeg")).flip();
                path = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog("Profile " + kind, "", filter, "Pictures", false);
            }
            if (path == null) return;
            upload(Path.of(path), kind, done);
        }, "Paranoia page picture");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ skin actuel -> historique serveur

    private static boolean skinReported;

    /** Signale au serveur le skin Mojang actuel (une fois par session) : l'historique démarre à partir de là. */
    public static void reportSkin() {
        if (skinReported) return;
        skinReported = true;
        EXEC.execute(() -> {
            try {
                Minecraft mc = Minecraft.getInstance();
                var tex = mc.services().sessionService().getTextures(mc.getGameProfile());
                if (tex == null || tex.skin() == null) return;
                JsonObject b = new JsonObject();
                b.addProperty("url", tex.skin().getUrl());
                b.addProperty("model", "slim".equals(tex.skin().getMetadata("model")) ? "slim" : "wide");
                if (Friends.ensureSession()) Friends.http("POST", "/v2/page/skin", b);
            } catch (Exception e) {
                AuroraClient.LOGGER.debug("Skin report: {}", e.toString());
                skinReported = false;
            }
        });
    }

    // ------------------------------------------------------------------ images (bannières, visuels, Discord, skins)

    /** Image animée ou fixe : une texture par image, délais en ms. */
    public record Anim(List<Identifier> frames, int[] delays, int w, int h, boolean failed) {
        public Identifier at(long ms) {
            if (frames.size() == 1) return frames.get(0);
            int total = 0;
            for (int d : delays) total += Math.max(20, d);
            long t = ms % Math.max(1, total);
            for (int i = 0; i < frames.size(); i++) {
                t -= Math.max(20, delays[i]);
                if (t < 0) return frames.get(i);
            }
            return frames.get(frames.size() - 1);
        }
    }

    private static final Map<String, Anim> IMAGES = new ConcurrentHashMap<>();
    private static final Anim LOADING_IMG = new Anim(List.of(), new int[0], 0, 0, false), FAILED_IMG = new Anim(List.of(), new int[0], 0, 0, true);
    private static int seq;

    /** Bannière ou visuel d'une page (serveur Paranoia). Null tant que ça charge, failed si absent. */
    public static Anim pageImage(Page p, boolean visual) {
        return pageImage(p, visual ? "visual" : "banner");
    }

    /** Image d'une page : « banner », « visual » ou « background ». */
    public static Anim pageImage(Page p, String kind) {
        String id = switch (kind) {
            case "visual" -> p.visual();
            case "background" -> p.look() == null ? null : p.look().background();
            default -> p.banner();
        };
        if (id == null || p.uuid() == null) return FAILED_IMG;
        String key = "page:" + kind + ":" + p.uuid().toString().replace("-", "") + ":" + id;
        return load(key, () -> {
            Friends.ensureSession();
            HttpRequest req = HttpRequest.newBuilder(URI.create(Friends.api() + "/v2/page/" + kind + "/" + p.uuid().toString().replace("-", "")))
                    .timeout(Duration.ofSeconds(30)).header("authorization", "Bearer " + Friends.token()).GET().build();
            HttpResponse<byte[]> r = Friends.HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) throw new java.io.IOException("status " + r.statusCode());
            return r.body();
        });
    }

    /** Image publique (avatar ou bannière Discord, skin Mojang). */
    public static Anim remoteImage(String url) {
        if (url == null || !(url.startsWith("https://cdn.discordapp.com/") || url.startsWith("http://textures.minecraft.net/") || url.startsWith("https://textures.minecraft.net/")
                || url.startsWith("https://static.klipy.com/")))
            return FAILED_IMG;
        return load("url:" + url, () -> {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<byte[]> r = Friends.HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200 || r.body().length > 4 << 20) throw new java.io.IOException("status " + r.statusCode());
            return r.body();
        });
    }

    private interface Fetch {
        byte[] get() throws Exception;
    }

    private static Anim load(String key, Fetch fetch) {
        Anim a = IMAGES.get(key);
        if (a != null) return a == LOADING_IMG ? null : a;
        IMAGES.put(key, LOADING_IMG);
        EXEC.execute(() -> {
            try {
                byte[] bytes = fetch.get();
                boolean gif = bytes.length > 3 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F';
                List<NativeImage> imgs = new ArrayList<>();
                int[] delays;
                int w, h;
                if (gif) {
                    Path tmp = Files.createTempFile("paranoia-page-", ".gif");
                    CapeImport.Frames f;
                    try {
                        Files.write(tmp, bytes);
                        f = CapeImport.decode(tmp);
                    } finally {
                        Files.deleteIfExists(tmp);
                    }
                    w = f.w();
                    h = f.h();
                    int n = Math.min(GIF_FRAMES, f.argb().size());
                    delays = new int[n];
                    for (int i = 0; i < n; i++) {
                        NativeImage img = new NativeImage(w, h, false);
                        int[] px = f.argb().get(i);
                        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setPixel(x, y, px[y * w + x]);
                        imgs.add(img);
                        delays[i] = f.delays()[i]; // déjà en ms
                    }
                } else {
                    NativeImage img = NativeImage.read(bytes);
                    w = img.getWidth();
                    h = img.getHeight();
                    imgs.add(img);
                    delays = new int[]{100};
                }
                int fw = w, fh = h;
                Minecraft.getInstance().execute(() -> {
                    List<Identifier> ids = new ArrayList<>();
                    for (NativeImage img : imgs) {
                        Identifier id = Identifier.fromNamespaceAndPath("aurora", "dynamic/page_" + (seq++));
                        Minecraft.getInstance().getTextureManager().register(id, new gg.aurora.render.SmoothTexture(() -> "Paranoia page image", img));
                        ids.add(id);
                    }
                    IMAGES.put(key, new Anim(ids, delays, fw, fh, false));
                });
            } catch (Exception e) {
                AuroraClient.LOGGER.debug("Page image {}: {}", key, e.toString());
                IMAGES.put(key, FAILED_IMG);
            }
        });
        return null;
    }

    /** Skin de l'historique en PlayerSkin (texture téléchargée depuis Mojang), ou null tant qu'il charge. */
    public static PlayerSkin skinOf(Skin s) {
        Anim a = remoteImage(s.url());
        if (a == null || a.failed() || a.frames().isEmpty()) return null;
        Identifier id = a.frames().get(0);
        if (a.h() == 32) {
            // ancien format 64x32 : converti une fois en 64x64 pour le modèle moderne
            Anim up = IMAGES.get("legacy:" + s.url());
            if (up == null) {
                Anim converted = legacy(a, "legacy:" + s.url());
                if (converted == null) return null;
                up = converted;
            }
            id = up.frames().get(0);
        }
        return PlayerSkin.insecure(new ClientAsset.ResourceTexture(id, id), null, null, "slim".equals(s.model()) ? PlayerModelType.SLIM : PlayerModelType.WIDE);
    }

    private static Anim legacy(Anim a, String key) {
        var tm = Minecraft.getInstance().getTextureManager();
        if (!(tm.getTexture(a.frames().get(0)) instanceof DynamicTexture d) || d.getPixels() == null) return null;
        NativeImage src = d.getPixels();
        NativeImage out = new NativeImage(64, 64, true);
        out.copyFrom(src);
        // jambes et bras gauches : copie miroir des droits (comme le fait le jeu)
        copyMirrored(src, out, 4, 16, 4, 4, 20, 48);
        copyMirrored(src, out, 8, 16, 4, 4, 24, 48);
        copyMirrored(src, out, 0, 20, 4, 12, 24, 52);
        copyMirrored(src, out, 4, 20, 4, 12, 20, 52);
        copyMirrored(src, out, 8, 20, 4, 12, 16, 52);
        copyMirrored(src, out, 12, 20, 4, 12, 28, 52);
        copyMirrored(src, out, 44, 16, 4, 4, 36, 48);
        copyMirrored(src, out, 48, 16, 4, 4, 40, 48);
        copyMirrored(src, out, 40, 20, 4, 12, 40, 52);
        copyMirrored(src, out, 44, 20, 4, 12, 36, 52);
        copyMirrored(src, out, 48, 20, 4, 12, 32, 52);
        copyMirrored(src, out, 52, 20, 4, 12, 44, 52);
        Identifier id = Identifier.fromNamespaceAndPath("aurora", "dynamic/page_" + (seq++));
        tm.register(id, new DynamicTexture(() -> "Paranoia legacy skin", out));
        Anim anim = new Anim(List.of(id), new int[]{100}, 64, 64, false);
        IMAGES.put(key, anim);
        return anim;
    }

    private static void copyMirrored(NativeImage src, NativeImage dst, int sx, int sy, int w, int h, int dx, int dy) {
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) dst.setPixel(dx + (w - 1 - x), dy + y, src.getPixel(sx + x, sy + y));
    }
}
