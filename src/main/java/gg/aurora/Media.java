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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Images des joueurs qui passent par le serveur Paranoia :
 * - capes importées : envoyées en version allégée (256 px de large, 24 images, 512 Ko au plus) pour que
 *   les autres joueurs les voient ; chacun choisit de les afficher pour tous, ses amis ou personne ;
 * - images dans les messages privés : PNG de 512 px au plus, gardé 7 jours par le serveur, lisible
 *   seulement par les deux amis de la conversation. Le message contient « [img:identifiant] ».
 * Tout le réseau et le décodage se font hors du thread de rendu.
 */
public final class Media {
    private Media() {}

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Paranoia-Media");
        t.setDaemon(true);
        return t;
    });
    private static final int CAPE_MAX = 512 * 1024, CAPE_FRAMES = 24, IMAGE_MAX = 1024 * 1024, IMAGE_SIDE = 512;

    private static HttpRequest.Builder req(String path) throws Exception {
        Friends.ensureSession();
        return HttpRequest.newBuilder(URI.create(Friends.api() + path)).timeout(Duration.ofSeconds(30))
                .header("authorization", "Bearer " + Friends.token());
    }

    /** PNG bytes of ARGB pixels (Minecraft's encoder writes to a file: a temporary one, deleted right after). */
    static byte[] png(int w, int h, int[] argb) throws java.io.IOException {
        Path tmp = Files.createTempFile("paranoia-img-", ".png");
        try (NativeImage img = new NativeImage(w, h, false)) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) img.setPixel(x, y, argb[y * w + x]);
            }
            img.writeToFile(tmp);
            return Files.readAllBytes(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ===================================================================== capes importées : envoi

    /** Fichier de cape déjà envoyé : « nom|date de modification » -> code « u_empreinte ». */
    private static final Map<String, String> SHARED = new ConcurrentHashMap<>();
    private static final java.util.Set<String> UPLOADING = ConcurrentHashMap.newKeySet();

    /**
     * Code à publier dans le profil pour la cape importée portée (« u_… »), ou vide tant qu'elle n'est pas
     * sur le serveur. Le premier appel lance l'envoi ; à la fin, le profil est republié.
     */
    public static String sharedCode(Capes.Cape cape) {
        if (cape == null || !cape.imported() || !Settings.shareImportedCape) return "";
        Path file = Capes.folder().resolve(cape.hash());
        long stamp;
        try {
            stamp = Files.getLastModifiedTime(file).toMillis();
        } catch (java.io.IOException e) {
            return "";
        }
        String key = cape.hash() + "|" + stamp;
        String code = SHARED.get(key);
        if (code != null) return code;
        if (UPLOADING.add(key)) {
            EXEC.execute(() -> {
                try {
                    String hash = upload(file, cape.hash());
                    if (hash != null) {
                        SHARED.put(key, "u_" + hash);
                        gg.aurora.nametag.NametagSync.markDirty();
                    }
                } catch (Exception e) {
                    AuroraClient.LOGGER.warn("Cape share failed: {}", e.toString());
                }
                // an error is retried at the next profile publication, not in a loop
                if (!SHARED.containsKey(key)) EXEC.execute(() -> {
                    try {
                        Thread.sleep(60_000);
                    } catch (InterruptedException ignored) {
                    }
                    UPLOADING.remove(key);
                });
            });
        }
        return "";
    }

    /** Version allégée de la cape (largeur, puis nombre d'images réduits jusqu'à tenir dans 512 Ko), envoyée au serveur. */
    private static String upload(Path file, String name) throws Exception {
        int[] px;
        int w, fullH;
        try (var in = Files.newInputStream(file); NativeImage img = NativeImage.read(in)) {
            w = img.getWidth();
            fullH = img.getHeight();
            px = img.getPixels();
        }
        int[] delays = CapeImport.readDelays(Capes.folder(), name);
        int frameH = w / 2, n = delays == null ? 1 : delays.length;
        if (frameH <= 0 || fullH != frameH * n) return null;
        for (int width : new int[]{256, 128, 64}) {
            if (width > w) continue;
            for (int frames : new int[]{CAPE_FRAMES, 12, 6, 1}) {
                int keep = Math.min(n, frames);
                int fh = width / 2;
                int[] out = new int[width * fh * keep];
                int[] outDelays = new int[keep];
                for (int f = 0; f < keep; f++) {
                    // images réparties régulièrement ; chaque image gardée dure le temps de celles qu'elle remplace
                    int from = (int) ((long) f * n / keep), to = (int) ((long) (f + 1) * n / keep);
                    int[] frame = java.util.Arrays.copyOfRange(px, from * w * frameH, (from + 1) * w * frameH);
                    int[] small = width == w ? frame : CapeImport.sample(frame, w, frameH, 0, 0, w, frameH, width, fh);
                    System.arraycopy(small, 0, out, f * width * fh, width * fh);
                    int d = 0;
                    for (int i = from; i < to; i++) d += delays == null ? 100 : delays[i];
                    outDelays[f] = Math.max(20, Math.min(10000, d));
                }
                byte[] bytes = png(width, fh * keep, out);
                if (bytes.length > CAPE_MAX) continue;
                StringBuilder ds = new StringBuilder();
                for (int d : outDelays) ds.append(ds.isEmpty() ? "" : ",").append(d);
                HttpResponse<String> r = HTTP.send(req("/v2/cape").header("content-type", "image/png")
                        .header("x-cape-frames", String.valueOf(keep)).header("x-cape-delays", ds.toString())
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) {
                    AuroraClient.LOGGER.warn("Cape share refused ({})", r.statusCode());
                    return null;
                }
                var o = new com.google.gson.Gson().fromJson(r.body(), com.google.gson.JsonObject.class);
                String hash = o != null && o.has("hash") ? o.get("hash").getAsString() : null;
                // animated: the front goes too, as a GIF with every frame; the players who see the cape rebuild it
                // from that at full speed (the PNG above stays for older versions of the mod)
                if (hash != null && n > 1) uploadFace(px, w, delays);
                return hash;
            }
        }
        return null;
    }

    private static final int FACE_MAX = 1024 * 1024, FACE_SIDE = 192;

    /** Front of every frame, as a GIF (≤ 1 MB): GIF keeps far more frames than a PNG strip in the same size. */
    private static void uploadFace(int[] strip, int w, int[] delays) {
        try {
            byte[] gif = GifEncoder.fit(CapeImport.faces(strip, w, delays), FACE_SIDE, FACE_MAX);
            if (gif == null) return;
            HttpResponse<String> r = HTTP.send(req("/v2/cape/face").header("content-type", "image/gif")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(gif)).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) AuroraClient.LOGGER.warn("Cape GIF refused ({})", r.statusCode());
        } catch (Exception e) {
            // the PNG is there: others still see the cape, only less smooth
            AuroraClient.LOGGER.warn("Cape GIF not sent: {}", e.toString());
        }
    }

    /**
     * The animated front of another player's cape, built here into the cape layout. Long animations get a smaller
     * texture so a crowd of capes stays light in memory. Null when the server has no GIF for them.
     */
    private static CapeImport.Frames remoteFace(String raw) throws Exception {
        HttpResponse<byte[]> r = HTTP.send(req("/v2/cape/" + raw + "/face").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        byte[] b = r.body();
        if (r.statusCode() != 200 || b.length > FACE_MAX || b.length < 6 || b[0] != 'G' || b[1] != 'I' || b[2] != 'F') return null;
        CapeImport.Frames face = CapeImport.decodeGif(b);
        if (face.argb().size() < 2) return null;
        // same size as on the owner's screen up to 100 frames, a bit smaller beyond (memory per cape stays near 12 MB)
        int k = face.argb().size() > 100 ? 3 : 4;
        return CapeImport.build(face, 0, 0, face.w(), face.h(), k);
    }

    // ===================================================================== capes importées : affichage chez les autres

    private static final class RemoteCape {
        ClientAsset.Texture asset;
        NativeImage[] frames;
        int[] ends;
        DynamicTexture tex;
        int shown;
        boolean failed;
        long retryAt;
    }

    private static final Map<String, RemoteCape> REMOTE = new ConcurrentHashMap<>();

    /** Réglage « capes importées des autres » : 0 tous, 1 amis, 2 personne. */
    private static boolean allowed(UUID owner) {
        return Settings.importedCapes == 0 || Settings.importedCapes == 1 && Friends.isFriend(owner);
    }

    /**
     * Cape importée d'un autre joueur (code « u_… » de son profil), prête à être portée, ou null
     * (réglage, téléchargement en cours, signalée). Appelé à chaque image pour chaque joueur : ne fait
     * que lire une map, sauf la première fois.
     */
    public static ClientAsset.Texture remoteCape(UUID owner, String code) {
        if (!allowed(owner)) return null;
        String key = owner + "|" + code;
        RemoteCape c = REMOTE.get(key);
        if (c == null) {
            if (REMOTE.size() > 256) return null;
            c = new RemoteCape();
            REMOTE.put(key, c);
            download(owner, key, c);
            return null;
        }
        if (c.failed) {
            if (System.currentTimeMillis() > c.retryAt) {
                c.failed = false;
                download(owner, key, c);
            }
            return null;
        }
        if (c.asset == null) return null;
        if (c.frames != null && com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
            int t = (int) (System.currentTimeMillis() % c.ends[c.ends.length - 1]), f = 0;
            while (c.ends[f] <= t) f++;
            if (f != c.shown) {
                c.shown = f;
                c.tex.getPixels().copyFrom(c.frames[f]);
                c.tex.upload();
            }
        }
        return c.asset;
    }

    private static void download(UUID owner, String key, RemoteCape c) {
        EXEC.execute(() -> {
            try {
                String raw = owner.toString().replace("-", "");
                // first the GIF front (every frame, at its own speed), built here; the PNG strip otherwise
                CapeImport.Frames built = null;
                try {
                    built = remoteFace(raw);
                } catch (Exception e) {
                    AuroraClient.LOGGER.debug("Cape GIF of {} not used: {}", raw, e.toString());
                }
                if (built != null) {
                    int w = built.w(), fh = built.h(), n = built.argb().size();
                    NativeImage[] frames = new NativeImage[n];
                    int[] ends = new int[n];
                    int t = 0;
                    for (int f = 0; f < n; f++) {
                        frames[f] = new NativeImage(w, fh, false);
                        int[] px = built.argb().get(f);
                        for (int y = 0; y < fh; y++) {
                            for (int x = 0; x < w; x++) frames[f].setPixel(x, y, px[y * w + x]);
                        }
                        ends[f] = t += Math.max(20, built.delays()[f]);
                    }
                    NativeImage first = new NativeImage(w, fh, false);
                    first.copyFrom(frames[0]);
                    show(raw, key, c, first, frames, ends);
                    return;
                }
                HttpResponse<byte[]> r = HTTP.send(req("/v2/cape/" + raw).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() != 200 || r.body().length > CAPE_MAX) throw new java.io.IOException("status " + r.statusCode());
                int n = Math.max(1, Math.min(CAPE_FRAMES, Integer.parseInt(r.headers().firstValue("x-cape-frames").orElse("1"))));
                String[] ds = r.headers().firstValue("x-cape-delays").orElse("").split(",");
                NativeImage img = NativeImage.read(r.body());
                int w = img.getWidth(), fh = w / 2;
                if (w < 64 || w > 512 || w % 64 != 0 || img.getHeight() != fh * n) {
                    img.close();
                    throw new java.io.IOException("bad cape layout");
                }
                NativeImage[] frames = null;
                int[] ends = null;
                NativeImage first = img;
                if (n > 1) {
                    frames = new NativeImage[n];
                    ends = new int[n];
                    int t = 0;
                    for (int f = 0; f < n; f++) {
                        frames[f] = new NativeImage(w, fh, false);
                        for (int y = 0; y < fh; y++) {
                            for (int x = 0; x < w; x++) frames[f].setPixel(x, y, img.getPixel(x, f * fh + y));
                        }
                        int d = 100;
                        try {
                            d = Math.max(20, Integer.parseInt(ds[f].trim()));
                        } catch (RuntimeException ignored) {
                        }
                        ends[f] = t += d;
                    }
                    img.close();
                    first = new NativeImage(w, fh, false);
                    first.copyFrom(frames[0]);
                }
                show(raw, key, c, first, frames, ends);
            } catch (Exception e) {
                c.failed = true;
                c.retryAt = System.currentTimeMillis() + 120_000;
            }
        });
    }

    /** Registers the texture on the game thread; the frames then play in {@link #remoteCape}. */
    private static void show(String raw, String key, RemoteCape c, NativeImage shown, NativeImage[] fr, int[] en) {
        Identifier id = Identifier.fromNamespaceAndPath("aurora", "remote_cape/" + raw + "_" + Integer.toHexString(key.hashCode()));
        Minecraft.getInstance().execute(() -> {
            DynamicTexture tex = new DynamicTexture(() -> "Paranoia remote cape", shown);
            Minecraft.getInstance().getTextureManager().register(id, tex);
            c.tex = tex;
            c.frames = fr;
            c.ends = en;
            c.asset = new ClientAsset.ResourceTexture(id, id);
        });
    }

    /** Signale la cape importée d'un joueur (masquée pour tous après 3 signalements) et la cache chez soi. */
    public static void reportCape(UUID owner) {
        EXEC.execute(() -> {
            try {
                HTTP.send(req("/v2/cape/" + owner.toString().replace("-", "") + "/report").POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {
            }
        });
        REMOTE.entrySet().removeIf(e -> e.getKey().startsWith(owner + "|"));
    }

    // ===================================================================== images en message privé

    /** Image de message : une texture par image (GIF animé) et leurs délais en ms. */
    public record Image(Identifier texture, int w, int h, boolean failed, List<Identifier> frames, int[] delays) {
        Image(Identifier texture, int w, int h, boolean failed) {
            this(texture, w, h, failed, texture == null ? List.of() : List.of(texture), new int[]{100});
        }

        /** Image à afficher à cet instant (boucle). */
        public Identifier at(long ms) {
            if (frames.size() <= 1) return texture;
            int total = 0;
            for (int d : delays) total += Math.max(20, d);
            long t = ms % Math.max(1, total);
            for (int i = 0; i < frames.size(); i++) {
                t -= Math.max(20, delays[i]);
                if (t < 0) return frames.get(i);
            }
            return texture;
        }
    }

    private static final Image LOADING = new Image(null, 0, 0, false), FAILED = new Image(null, 0, 0, true);
    private static final int GIF_FRAMES_MAX = 60;
    private static final Map<String, Image> IMAGES = new ConcurrentHashMap<>();
    private static final java.util.regex.Pattern TAG = java.util.regex.Pattern.compile("^\\[img:([0-9a-f]{16})]$");

    /** Identifiant d'image si le message en est une, sinon null. */
    public static String imageId(String text) {
        if (text == null || text.length() != 22 || text.charAt(0) != '[') return null;
        var m = TAG.matcher(text);
        return m.matches() ? m.group(1) : null;
    }

    /** Image d'un message : texture null tant qu'elle se charge, failed = expirée ou introuvable. */
    public static Image image(String id) {
        Image i = IMAGES.get(id);
        if (i != null) return i;
        if (IMAGES.size() > 200) return FAILED;
        IMAGES.put(id, LOADING);
        EXEC.execute(() -> {
            try {
                HttpResponse<byte[]> r = HTTP.send(req("/v2/media/" + id).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() != 200 || r.body().length > IMAGE_MAX) throw new java.io.IOException("status " + r.statusCode());
                byte[] b = r.body();
                if (b.length > 3 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') registerGif(id, b);
                else register(id, NativeImage.read(b));
            } catch (Exception e) {
                IMAGES.put(id, FAILED);
            }
        });
        return LOADING;
    }

    private static void register(String id, NativeImage img) {
        int w = img.getWidth(), h = img.getHeight();
        Identifier tex = Identifier.fromNamespaceAndPath("aurora", "chat_image/" + id);
        Minecraft.getInstance().execute(() -> {
            Minecraft.getInstance().getTextureManager().register(tex, new gg.aurora.render.SmoothTexture(() -> "Paranoia chat image", img));
            IMAGES.put(id, new Image(tex, w, h, false));
        });
    }

    /** GIF animé reçu : chaque image devient une texture (au plus 60, et ~32 Mo de pixels). */
    private static void registerGif(String id, byte[] bytes) throws java.io.IOException {
        Path tmp = Files.createTempFile("paranoia-gif-", ".gif");
        CapeImport.Frames f;
        try {
            Files.write(tmp, bytes);
            f = CapeImport.decode(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
        int w = f.w(), h = f.h();
        int n = Math.max(1, Math.min(Math.min(GIF_FRAMES_MAX, f.argb().size()), (int) ((32L << 20) / Math.max(1, 4L * w * h))));
        List<NativeImage> imgs = new java.util.ArrayList<>();
        int[] delays = new int[n];
        for (int i = 0; i < n; i++) {
            NativeImage img = new NativeImage(w, h, false);
            int[] px = f.argb().get(i);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setPixel(x, y, px[y * w + x]);
            imgs.add(img);
            delays[i] = f.delays()[i]; // déjà en ms (CapeImport)
        }
        Minecraft.getInstance().execute(() -> {
            List<Identifier> ids = new java.util.ArrayList<>();
            for (int i = 0; i < imgs.size(); i++) {
                Identifier tex = Identifier.fromNamespaceAndPath("aurora", "chat_image/" + id + "_" + i);
                Minecraft.getInstance().getTextureManager().register(tex, new gg.aurora.render.SmoothTexture(() -> "Paranoia chat GIF", imgs.get(i)));
                ids.add(tex);
            }
            IMAGES.put(id, new Image(ids.get(0), w, h, false, ids, delays));
        });
    }

    /** Envoie un GIF tel quel (animé) s'il tient dans 1 Mo et 1024 px ; sinon sa première image en PNG. */
    public static void sendGif(UUID to, byte[] gifIn, CapeImport.Frames frames, Consumer<String> done) {
        if (frames.argb().size() <= 1) {
            sendImage(to, frames.w(), frames.h(), frames.argb().get(0), done);
            return;
        }
        EXEC.execute(() -> {
            String err = null;
            try {
                // trop lourd ou trop grand : ré-encodé plus petit, toujours animé (jamais aplati en image fixe)
                byte[] gif = gifIn;
                if (gif == null || gif.length > IMAGE_MAX || frames.w() > 1024 || frames.h() > 1024) gif = GifEncoder.fit(frames, 360, IMAGE_MAX);
                if (gif == null) {
                    sendImage(to, frames.w(), frames.h(), frames.argb().get(0), done);
                    return;
                }
                HttpResponse<String> r = HTTP.send(req("/v2/media").header("content-type", "image/gif")
                        .header("x-to", to.toString().replace("-", ""))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(gif)).build(), HttpResponse.BodyHandlers.ofString());
                var o = new com.google.gson.Gson().fromJson(r.body(), com.google.gson.JsonObject.class);
                if (r.statusCode() != 200 || o == null || !o.has("id")) {
                    err = o != null && o.has("message") ? o.get("message").getAsString() : "GIF not sent (" + r.statusCode() + ")";
                } else {
                    String id = o.get("id").getAsString();
                    registerGif(id, gif);
                    Minecraft.getInstance().execute(() -> Friends.send(to, "[img:" + id + "]", e -> {}));
                }
            } catch (Exception e) {
                AuroraClient.LOGGER.warn("GIF send failed: {}", e.toString());
                err = "GIF not sent";
            }
            String result = err;
            Minecraft.getInstance().execute(() -> done.accept(result));
        });
    }

    /** Résultat de recherche de GIF : aperçu léger et fichier envoyé. */
    public record Gif(String id, String preview, String url, int w, int h) {}

    /** Recherche Klipy via le serveur Paranoia (tendances si q est vide). done(liste, erreur) sur le fil du jeu. */
    public static void searchGifs(String q, java.util.function.BiConsumer<List<Gif>, String> done) {
        EXEC.execute(() -> {
            List<Gif> out = new java.util.ArrayList<>();
            String err = null;
            try {
                String path = "/v2/gif/search" + (q == null || q.isBlank() ? "" : "?q=" + java.net.URLEncoder.encode(q.trim(), java.nio.charset.StandardCharsets.UTF_8));
                HttpResponse<String> r = HTTP.send(req(path).GET().build(), HttpResponse.BodyHandlers.ofString());
                var o = new com.google.gson.Gson().fromJson(r.body(), com.google.gson.JsonObject.class);
                if (r.statusCode() != 200 || o == null || !o.has("results")) {
                    err = o != null && o.has("message") ? o.get("message").getAsString() : "GIF search unavailable";
                } else {
                    for (var e : o.getAsJsonArray("results")) {
                        var g = e.getAsJsonObject();
                        out.add(new Gif(g.get("id").getAsString(), g.get("preview").getAsString(), g.get("url").getAsString(),
                                g.get("w").getAsInt(), g.get("h").getAsInt()));
                    }
                }
            } catch (Exception e) {
                err = "Server unreachable";
            }
            String e2 = err;
            Minecraft.getInstance().execute(() -> done.accept(out, e2));
        });
    }

    /** Envoie un GIF choisi dans la recherche : téléchargé puis envoyé animé. */
    public static void sendGifUrl(UUID to, String url, Consumer<String> done) {
        Capes.fetchImage(url, (frames, err) -> {
            if (frames == null) done.accept(err);
            else sendGif(to, Capes.lastLinkBytes, frames, done);
        });
    }

    /** Envoie une image à un ami : réduite à 512 px, envoyée au serveur, puis le message « [img:id] ». done(null) = envoyé. */
    public static void sendImage(UUID to, int w, int h, int[] argb, Consumer<String> done) {
        sendImage(to, w, h, argb, IMAGE_SIDE, done);
    }

    /** Même envoi avec une taille maximale choisie (captures d'écran : 1024 px, le maximum accepté par le serveur). */
    public static void sendImage(UUID to, int w, int h, int[] argb, int side, Consumer<String> done) {
        EXEC.execute(() -> {
            String err = null;
            try {
                int tw = w, th = h;
                int[] px = argb;
                if (Math.max(w, h) > side) {
                    double k = side / (double) Math.max(w, h);
                    tw = Math.max(1, (int) Math.round(w * k));
                    th = Math.max(1, (int) Math.round(h * k));
                    px = CapeImport.sample(argb, w, h, 0, 0, w, h, tw, th);
                }
                byte[] bytes = png(tw, th, px);
                if (bytes.length > IMAGE_MAX) {
                    // photo très détaillée : moitié de la taille
                    int hw = Math.max(1, tw / 2), hh = Math.max(1, th / 2);
                    px = CapeImport.sample(px, tw, th, 0, 0, tw, th, hw, hh);
                    tw = hw;
                    th = hh;
                    bytes = png(tw, th, px);
                }
                HttpResponse<String> r = HTTP.send(req("/v2/media").header("content-type", "image/png")
                        .header("x-to", to.toString().replace("-", ""))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofString());
                var o = new com.google.gson.Gson().fromJson(r.body(), com.google.gson.JsonObject.class);
                if (r.statusCode() != 200 || o == null || !o.has("id")) {
                    err = o != null && o.has("message") ? o.get("message").getAsString() : "Picture not sent (" + r.statusCode() + ")";
                } else {
                    String id = o.get("id").getAsString();
                    // already on this PC: no need to download my own picture back
                    try (var in = new java.io.ByteArrayInputStream(bytes)) {
                        register(id, NativeImage.read(in));
                    }
                    Minecraft.getInstance().execute(() -> Friends.send(to, "[img:" + id + "]", e -> {}));
                }
            } catch (Exception e) {
                AuroraClient.LOGGER.warn("Picture send failed: {}", e.toString());
                err = "Picture not sent";
            }
            String result = err;
            Minecraft.getInstance().execute(() -> done.accept(result));
        });
    }

    /** Sélecteur de fichiers (PNG, JPEG, GIF : première image), puis envoi. */
    public static void pickAndSend(UUID to, Consumer<String> done) {
        Thread t = new Thread(() -> {
            String path;
            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                var filter = stack.mallocPointer(4);
                filter.put(stack.UTF8("*.png")).put(stack.UTF8("*.jpg")).put(stack.UTF8("*.jpeg")).put(stack.UTF8("*.gif")).flip();
                path = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog("Send a picture", "", filter, "Pictures", false);
            }
            if (path == null) return;
            try {
                Path src = Path.of(path);
                if (Files.size(src) > 16L << 20) {
                    Minecraft.getInstance().execute(() -> done.accept("The file is over 16 MB"));
                    return;
                }
                CapeImport.Frames f = CapeImport.decode(src);
                if (f.argb().size() > 1) sendGif(to, Files.readAllBytes(src), f, done);
                else sendImage(to, f.w(), f.h(), f.argb().get(0), done);
            } catch (Exception e) {
                Minecraft.getInstance().execute(() -> done.accept("This file is not a readable picture"));
            }
        }, "Paranoia picture");
        t.setDaemon(true);
        t.start();
    }

    /** Image copiée dans le presse-papiers de Windows (capture d'écran, « copier l'image »), ou null. */
    public static CapeImport.Frames clipboardImage() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return null;
        try {
            return WinClipboard.read();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Lecture du format CF_DIB par l'API Windows (JNA est livré avec Minecraft). */
    private static final class WinClipboard {
        interface User32 extends com.sun.jna.win32.StdCallLibrary {
            User32 I = com.sun.jna.Native.load("user32", User32.class);

            boolean OpenClipboard(com.sun.jna.Pointer owner);

            boolean CloseClipboard();

            boolean IsClipboardFormatAvailable(int format);

            com.sun.jna.Pointer GetClipboardData(int format);
        }

        interface Kernel32 extends com.sun.jna.win32.StdCallLibrary {
            Kernel32 I = com.sun.jna.Native.load("kernel32", Kernel32.class);

            com.sun.jna.Pointer GlobalLock(com.sun.jna.Pointer mem);

            boolean GlobalUnlock(com.sun.jna.Pointer mem);

            com.sun.jna.Pointer GlobalSize(com.sun.jna.Pointer mem);
        }

        static CapeImport.Frames read() {
            final int CF_DIB = 8;
            if (!User32.I.IsClipboardFormatAvailable(CF_DIB) || !User32.I.OpenClipboard(null)) return null;
            try {
                com.sun.jna.Pointer handle = User32.I.GetClipboardData(CF_DIB);
                if (handle == null) return null;
                long size = com.sun.jna.Pointer.nativeValue(Kernel32.I.GlobalSize(handle));
                com.sun.jna.Pointer p = Kernel32.I.GlobalLock(handle);
                if (p == null || size < 40 || size > 256L << 20) return null;
                try {
                    int header = p.getInt(0), w = p.getInt(4), rawH = p.getInt(8), bpp = p.getShort(14) & 0xFFFF, comp = p.getInt(16);
                    int h = Math.abs(rawH);
                    if (w <= 0 || h <= 0 || (long) w * h > 64_000_000L || (bpp != 24 && bpp != 32) || (comp != 0 && comp != 3)) return null;
                    long off = header + (comp == 3 && header == 40 ? 12 : 0);
                    int stride = ((w * bpp + 31) / 32) * 4;
                    if (off + (long) stride * h > size) return null;
                    int[] out = new int[w * h];
                    boolean anyAlpha = false;
                    byte[] row = new byte[stride];
                    for (int y = 0; y < h; y++) {
                        p.read(off + (long) (rawH > 0 ? h - 1 - y : y) * stride, row, 0, stride);
                        for (int x = 0; x < w; x++) {
                            int i = x * (bpp / 8);
                            int b = row[i] & 255, g = row[i + 1] & 255, r = row[i + 2] & 255, a = bpp == 32 ? row[i + 3] & 255 : 255;
                            anyAlpha |= a != 0;
                            out[y * w + x] = a << 24 | r << 16 | g << 8 | b;
                        }
                    }
                    // most programs leave the alpha byte at 0 for an opaque picture
                    if (!anyAlpha) for (int i = 0; i < out.length; i++) out[i] |= 0xFF000000;
                    return new CapeImport.Frames(w, h, List.of(out), new int[]{100});
                } finally {
                    Kernel32.I.GlobalUnlock(handle);
                }
            } finally {
                User32.I.CloseClipboard();
            }
        }
    }

    /** Image depuis un lien HTTPS copié (Tenor ou lien direct) : téléchargée puis envoyée. */
    public static void sendLink(UUID to, String link, Consumer<String> done) {
        Capes.fetchImage(link, (frames, err) -> {
            if (frames == null) done.accept(err);
            else if (frames.argb().size() > 1) sendGif(to, Capes.lastLinkBytes, frames, done);
            else sendImage(to, frames.w(), frames.h(), frames.argb().get(0), done);
        });
    }
}
