package gg.aurora;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import gg.aurora.ui.Icons;
import gg.aurora.ui.Toasts;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Mod manager backed by Modrinth: search, install, update, disable and remove Fabric mods from inside the game.
 * <p>
 * Fabric loads mods once, at startup, so changes take effect on the next launch. Files that are not loaded
 * (new downloads, disabled jars) are changed right away. Loaded jars are locked by Windows and still in use,
 * so their changes are queued and applied by {@link ModSwap}, a small separate process that waits for the game
 * to close. "Restart now" closes the game and lets that process start it again with the same arguments.
 * Removed and replaced jars are moved to {@code mods/.aurora-mods/old}, never deleted.
 */
public final class ModManager {
    private ModManager() {}

    private static final String API = "https://api.modrinth.com/v2";
    private static final long MAX_BYTES = 200L << 20;
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    /** File work runs one task at a time, off the game thread. */
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Paranoia mod manager");
        t.setDaemon(true);
        return t;
    });

    /** The manager opens from the « Mods » button of the pause menu, not from the module menu. */
    public static void init() {}

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new gg.aurora.ui.ModsScreen(mc.screen));
    }

    // =========================================================================== Data

    /** A file in the mods folder. */
    public static final class Installed {
        public final Path path;
        public final String file, name, modId, version;
        public final boolean enabled, loaded;
        final String sha1;
        /** Modrinth project, or null when Modrinth does not know this file. */
        public volatile String projectId;
        public volatile String versionId;
        public volatile Remote update;
        /** What will happen at the next launch (null = nothing). */
        public volatile String pending;
        /** File written for a pending update (new jar or staged copy), deleted on undo. */
        volatile Path added;
        public volatile boolean working;
        /** Run by the hot mods runtime (installed, turned on or off, updated without a restart). */
        public volatile boolean live;

        Installed(Path path, String name, String modId, String version, String sha1, boolean loaded) {
            this.path = path;
            this.file = path.getFileName().toString();
            this.enabled = file.toLowerCase(Locale.ROOT).endsWith(".jar");
            this.name = name;
            this.modId = modId;
            this.version = version;
            this.sha1 = sha1;
            this.loaded = loaded;
        }

        /** Paranoia itself: managed by its own updater. */
        public boolean self() {
            return AuroraClient.MOD_ID.equals(modId);
        }
    }

    /** A downloadable Modrinth version. */
    public static final class Remote {
        public final String projectId, versionId, versionNumber, url, fileName, sha512;
        public final long size;
        final List<String> requiredProjects = new ArrayList<>();

        Remote(JsonObject v) {
            projectId = v.get("project_id").getAsString();
            versionId = v.get("id").getAsString();
            versionNumber = v.get("version_number").getAsString();
            JsonObject file = null;
            for (JsonElement e : v.getAsJsonArray("files")) {
                JsonObject f = e.getAsJsonObject();
                if (file == null || f.get("primary").getAsBoolean()) file = f;
                if (f.get("primary").getAsBoolean()) break;
            }
            if (file == null) throw new IllegalStateException("version without files");
            url = file.get("url").getAsString();
            fileName = file.get("filename").getAsString();
            sha512 = file.getAsJsonObject("hashes").get("sha512").getAsString().toLowerCase(Locale.ROOT);
            size = file.get("size").getAsLong();
            if (v.has("dependencies")) {
                for (JsonElement e : v.getAsJsonArray("dependencies")) {
                    JsonObject d = e.getAsJsonObject();
                    if ("required".equals(str(d, "dependency_type")) && str(d, "project_id") != null) requiredProjects.add(str(d, "project_id"));
                }
            }
        }
    }

    /** What the store lists, and where each kind is saved. */
    public enum Kind {
        MOD("Mods", "mod", "fabric", "mods", ".jar"),
        RESOURCE_PACK("Resource packs", "resourcepack", "minecraft", "resourcepacks", ".zip"),
        SHADER("Shaders", "shader", "iris", "shaderpacks", ".zip");

        public final String label;
        final String projectType, loader, folder, ext;

        Kind(String label, String projectType, String loader, String folder, String ext) {
            this.label = label;
            this.projectType = projectType;
            this.loader = loader;
            this.folder = folder;
            this.ext = ext;
        }
    }

    /** A search result. */
    public static final class Hit {
        public final String projectId, slug, title, author, description, iconUrl, updated;
        public final long downloads, follows;
        public final Kind kind;
        public final List<String> gallery = new ArrayList<>();
        public volatile String state;
        /** Download progress of the file being fetched for an install, 0 to 1, or -1. */
        public volatile float progress = -1;
        /** Progress updates seen between start and end (a test reads it: the download is streamed, not buffered). */
        public volatile int progressSteps;
        /** Whether it can start without a restart (mods only), found by reading its newest jar. */
        public volatile Verdict verdict = Verdict.UNKNOWN;
        /** The reasons for {@link Verdict#RESTART}, or a note. */
        public volatile String verdictWhy;
        /** Its newest version for this game, once checked. */
        public volatile Remote latest;

        Hit(JsonObject o, Kind kind) {
            projectId = o.get("project_id").getAsString();
            slug = str(o, "slug");
            iconUrl = str(o, "icon_url");
            title = o.get("title").getAsString();
            author = str(o, "author") == null ? "?" : str(o, "author");
            description = str(o, "description") == null ? "" : str(o, "description");
            downloads = o.get("downloads").getAsLong();
            follows = o.has("follows") ? o.get("follows").getAsLong() : 0;
            updated = str(o, "date_modified");
            if (o.has("gallery") && o.get("gallery").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("gallery")) if (e.isJsonPrimitive()) gallery.add(e.getAsString());
            }
            this.kind = kind;
        }

        public boolean installed() {
            return installedProjects.contains(projectId);
        }
    }

    /** Can a store mod start without a restart: found by reading its jar before it is installed. */
    public enum Verdict {
        /** Not checked yet (or the check failed). */
        UNKNOWN,
        CHECKING,
        /** Starts now, no restart. */
        HOT,
        /** Only at the next launch. */
        RESTART,
        /** No version for this game. */
        NONE
    }

    public static volatile List<Installed> installed = List.of();
    public static volatile List<Hit> results = List.of();
    public static volatile boolean scanning, searching;
    /** Last error shown in the list, or null. */
    public static volatile String error;

    private static final Set<String> installedProjects = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Set<String> installing = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Operations applied after the game closes: {"MOVE", from, to}, with the jar they belong to. */
    private record Op(Path owner, String[] move) {}

    private static final List<Op> queued = new ArrayList<>();
    /** A restart is needed for something the queue does not hold (a jar that could not load live). */
    private static volatile boolean changed;
    private static volatile boolean relaunch;

    public static boolean restartNeeded() {
        synchronized (queued) {
            return changed || !queued.isEmpty();
        }
    }

    /** Mods with a change waiting for the next launch. */
    public static List<Installed> pendingList() {
        List<Installed> out = new ArrayList<>();
        for (Installed i : installed) if (i.pending != null) out.add(i);
        return out;
    }

    /** A queued change can be taken back while the game still runs the old jar. */
    public static boolean canUndo(Installed i) {
        return i.pending != null && i.loaded && !i.working;
    }

    /** Drops the queued change of a mod: the jar in use stays, a downloaded update is deleted. */
    public static void undo(Installed i) {
        if (!canUndo(i)) return;
        i.working = true;
        WORK.execute(() -> {
            try {
                synchronized (queued) {
                    queued.removeIf(op -> op.owner().equals(i.path));
                }
                Path added = i.added;
                if (added != null) Files.deleteIfExists(added);
                i.added = null;
                i.pending = null;
                toast(Icons.UNDO, i.name, "Change taken back, nothing happens at restart");
            } catch (Exception e) {
                toast(Icons.INFO, "Could not undo", i.name);
                AuroraClient.LOGGER.warn("Mod manager undo: {}", e.toString());
            } finally {
                i.working = false;
            }
            scan();
        });
    }

    public static void undoAll() {
        for (Installed i : pendingList()) undo(i);
    }

    public static String gameVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    private static Path modsDir() {
        return FabricLoader.getInstance().getGameDir().resolve("mods");
    }

    private static Path workDir() throws java.io.IOException {
        return Files.createDirectories(modsDir().resolve(".aurora-mods"));
    }

    private static Set<Path> loadedPaths() {
        Set<Path> out = new HashSet<>();
        for (ModContainer m : FabricLoader.getInstance().getAllMods()) {
            for (Path p : m.getOrigin().getKind() == net.fabricmc.loader.api.metadata.ModOrigin.Kind.PATH ? m.getOrigin().getPaths() : List.<Path>of()) {
                out.add(p.toAbsolutePath().normalize());
            }
        }
        return out;
    }

    // =========================================================================== Network

    private static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("user-agent", "Paranoia-Client/" + Updater.currentVersion() + " (mod manager)");
    }

    private static String get(String path) throws Exception {
        HttpResponse<String> r = HTTP.send(request(API + path).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("Modrinth answered " + r.statusCode());
        return r.body();
    }

    private static String post(String path, JsonObject body) throws Exception {
        HttpResponse<String> r = HTTP.send(request(API + path).header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("Modrinth answered " + r.statusCode());
        return r.body();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Newest Fabric version of a project for this Minecraft version, or null. */
    private static Remote latest(String projectId) throws Exception {
        return latest(projectId, Kind.MOD);
    }

    private static Remote latest(String projectId, Kind kind) throws Exception {
        String q = "?loaders=" + enc("[\"" + kind.loader + "\"]") + "&game_versions=" + enc("[\"" + gameVersion() + "\"]");
        JsonArray a = GSON.fromJson(get("/project/" + enc(projectId) + "/version" + q), JsonArray.class);
        return a.isEmpty() ? null : new Remote(a.get(0).getAsJsonObject());
    }

    /** Downloads and checks a version file, then moves it to {@code target}. */
    private static void download(Remote v, Path target) throws Exception {
        download(v, target, null);
    }

    /** Streamed, so {@code progress} (0 to 1) follows it; checked (size, SHA-512) before it is moved in place. */
    private static void download(Remote v, Path target, java.util.function.DoubleConsumer progress) throws Exception {
        URI uri = URI.create(v.url);
        if (!"https".equals(uri.getScheme()) || !"cdn.modrinth.com".equals(uri.getHost())) throw new IllegalStateException("unexpected download host");
        if (v.size > MAX_BYTES) throw new IllegalStateException("file too large");
        HttpResponse<InputStream> r = HTTP.send(request(v.url).timeout(Duration.ofSeconds(120)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) {
            r.body().close();
            throw new IllegalStateException("download answered " + r.statusCode());
        }
        Path tmp = workDir().resolve(v.versionId + "-" + System.nanoTime() + ".tmp");
        MessageDigest md = MessageDigest.getInstance("SHA-512");
        long n = 0;
        try (InputStream in = r.body(); java.io.OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            for (int k; (k = in.read(buf)) > 0; ) {
                n += k;
                if (n > v.size) throw new IllegalStateException("download larger than announced");
                md.update(buf, 0, k);
                out.write(buf, 0, k);
                if (progress != null && v.size > 0) progress.accept((double) n / v.size);
            }
        } catch (Exception e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        if (n != v.size || !java.util.HexFormat.of().formatHex(md.digest()).equals(v.sha512)) {
            Files.deleteIfExists(tmp);
            throw new IllegalStateException("download check failed");
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String hash(String algo, byte[] data) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance(algo).digest(data));
    }

    /** Only a plain jar name from Modrinth: no folders, no hidden files. */
    private static String safeName(String name) {
        return safeName(name, ".jar");
    }

    private static String safeName(String name, String ext) {
        String n = name.replaceAll("[\\\\/:*?\"<>|]+", "_");
        if (n.startsWith(".") || !n.toLowerCase(Locale.ROOT).endsWith(ext)) throw new IllegalStateException("unexpected file name");
        return n;
    }

    // =========================================================================== Scan

    public static void scan() {
        if (scanning) return;
        scanning = true;
        WORK.execute(() -> {
            try {
                Set<Path> loaded = loadedPaths();
                List<Installed> out = new ArrayList<>();
                Map<String, Installed> bySha = new HashMap<>();
                Map<Path, Installed> pendingByPath = new HashMap<>();
                Set<Path> updateFiles = new HashSet<>();
                for (Installed i : installed) {
                    if (i.pending == null) continue;
                    pendingByPath.put(i.path, i);
                    if (i.added != null) updateFiles.add(i.added);
                }
                try (Stream<Path> s = Files.list(modsDir())) {
                    for (Path p : s.toList()) {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (n.startsWith(".") || !Files.isRegularFile(p) || !(n.endsWith(".jar") || n.endsWith(".jar.disabled"))) continue;
                        if (updateFiles.contains(p)) continue; // the new version of a mod shown below, listed with it
                        Installed i = read(p, loaded.contains(p.toAbsolutePath().normalize()));
                        if (!i.loaded && HotBridge.state(i.modId) != null) {
                            // started by the hot mods runtime from its own copy: running (or off) in this session
                            i = new Installed(i.path, i.name, i.modId, i.version, i.sha1, true);
                            i.live = true;
                        }
                        Installed before = pendingByPath.get(i.path);
                        if (before != null) {
                            i.pending = before.pending;
                            i.added = before.added;
                        }
                        out.add(i);
                        bySha.put(i.sha1, i);
                    }
                }
                out.sort(Comparator.comparing((Installed i) -> i.name.toLowerCase(Locale.ROOT)));
                installed = out;
                error = null;
                if (bySha.isEmpty()) return;

                JsonArray hashes = new JsonArray();
                bySha.keySet().forEach(hashes::add);
                JsonObject body = new JsonObject();
                body.add("hashes", hashes);
                body.addProperty("algorithm", "sha1");
                JsonObject known = GSON.fromJson(post("/version_files", body), JsonObject.class);
                installedProjects.clear();
                for (Map.Entry<String, JsonElement> e : known.entrySet()) {
                    Installed i = bySha.get(e.getKey());
                    if (i == null) continue;
                    i.projectId = e.getValue().getAsJsonObject().get("project_id").getAsString();
                    i.versionId = e.getValue().getAsJsonObject().get("id").getAsString();
                    installedProjects.add(i.projectId);
                }
                JsonArray loaders = new JsonArray();
                loaders.add("fabric");
                JsonArray games = new JsonArray();
                games.add(gameVersion());
                body.add("loaders", loaders);
                body.add("game_versions", games);
                JsonObject updates = GSON.fromJson(post("/version_files/update", body), JsonObject.class);
                for (Map.Entry<String, JsonElement> e : updates.entrySet()) {
                    Installed i = bySha.get(e.getKey());
                    if (i == null || i.versionId == null) continue;
                    Remote r = new Remote(e.getValue().getAsJsonObject());
                    if (!r.versionId.equals(i.versionId)) i.update = r;
                }
            } catch (Exception e) {
                error = "Could not reach Modrinth";
                AuroraClient.LOGGER.warn("Mod manager scan: {}", e.toString());
            } finally {
                scanning = false;
            }
        });
    }

    private static Installed read(Path p, boolean loaded) throws Exception {
        String sha1 = hash("SHA-1", Files.readAllBytes(p));
        String file = p.getFileName().toString();
        String name = file.replaceAll("(?i)\\.jar(\\.disabled)?$", ""), id = null, version = "";
        try (ZipFile z = new ZipFile(p.toFile())) {
            ZipEntry e = z.getEntry("fabric.mod.json");
            if (e != null) {
                try (InputStream in = z.getInputStream(e)) {
                    JsonObject o = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
                    if (str(o, "name") != null) name = str(o, "name");
                    id = str(o, "id");
                    if (str(o, "version") != null) version = str(o, "version");
                }
            }
        } catch (Exception ignored) {
            // not a readable jar: still listed by file name
        }
        return new Installed(p, name, id, version, sha1, loaded);
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    // =========================================================================== Search

    public static void search(String query) {
        search(query, Kind.MOD);
    }

    /** Kind of the results in {@link #results}. */
    public static volatile Kind resultsKind = Kind.MOD;

    public static void search(String query, Kind kind) {
        search(query, kind, null);
    }

    /** Category of the results in {@link #results} (Modrinth slug, null = all). */
    public static volatile String resultsCategory;

    /** How many projects Modrinth has for the current search (the list grows page by page up to it). */
    public static volatile int totalHits;
    public static volatile boolean loadingMore;
    private static final int PAGE = 40;
    private static volatile String searchUrl;
    private static final java.util.concurrent.atomic.AtomicInteger searchGen = new java.util.concurrent.atomic.AtomicInteger();

    public static void search(String query, Kind kind, String category) {
        if (searching) return;
        searching = true;
        searchGen.incrementAndGet();
        CompletableFuture.runAsync(() -> {
            try {
                String loader = kind == Kind.RESOURCE_PACK ? "" : "[\"categories:" + kind.loader + "\"],";
                String cat = category == null ? "" : "[\"categories:" + category + "\"],";
                String facets = "[[\"project_type:" + kind.projectType + "\"]," + loader + cat + "[\"versions:" + gameVersion() + "\"]]";
                String q = query.isBlank() ? "" : "query=" + enc(query.trim()) + "&";
                String url = "/search?" + q + "facets=" + enc(facets) + "&index=" + (query.isBlank() ? "downloads" : "relevance") + "&limit=" + PAGE;
                JsonObject o = GSON.fromJson(get(url), JsonObject.class);
                List<Hit> out = new ArrayList<>();
                for (JsonElement e : o.getAsJsonArray("hits")) out.add(new Hit(e.getAsJsonObject(), kind));
                resultsKind = kind;
                resultsCategory = category;
                searchUrl = url;
                totalHits = o.has("total_hits") ? o.get("total_hits").getAsInt() : out.size();
                results = out;
                out.forEach(ModManager::queueCheck);
                error = null;
            } catch (Exception e) {
                error = "Could not reach Modrinth";
                AuroraClient.LOGGER.warn("Mod manager search: {}", e.toString());
            } finally {
                searching = false;
            }
        });
    }

    /** Whether Modrinth has more results than the list holds. */
    public static boolean more() {
        return searchUrl != null && results.size() < totalHits;
    }

    /** The next page of the current search, added to the end of the list. */
    public static void loadMore() {
        if (searching || loadingMore || !more()) return;
        loadingMore = true;
        int gen = searchGen.get();
        String url = searchUrl;
        Kind kind = resultsKind;
        int offset = results.size();
        CompletableFuture.runAsync(() -> {
            try {
                JsonObject o = GSON.fromJson(get(url + "&offset=" + offset), JsonObject.class);
                List<Hit> page = new ArrayList<>();
                Set<String> have = new HashSet<>();
                for (Hit h : results) have.add(h.projectId);
                for (JsonElement e : o.getAsJsonArray("hits")) {
                    Hit h = new Hit(e.getAsJsonObject(), kind);
                    if (have.add(h.projectId)) page.add(h);
                }
                if (gen != searchGen.get() || results.size() != offset) return; // another search, or another page, came first
                List<Hit> all = new ArrayList<>(results);
                all.addAll(page);
                if (o.has("total_hits")) totalHits = o.get("total_hits").getAsInt();
                if (o.getAsJsonArray("hits").isEmpty()) totalHits = all.size();
                results = all;
                page.forEach(ModManager::queueCheck);
            } catch (Exception e) {
                AuroraClient.LOGGER.warn("Mod manager next page: {}", e.toString());
            } finally {
                loadingMore = false;
            }
        });
    }

    // =========================================================================== Restart-free check

    private static final ExecutorService CHECK = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "Paranoia mod check");
        t.setDaemon(true);
        return t;
    });
    /** Jars bigger than this are not downloaded only to be checked. */
    private static final long CHECK_MAX = 40L << 20;
    /** The runtime's answer for each Modrinth version already read (kept on disk: a version's files never change). */
    private static final Map<String, String> verdicts = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<Answer>> projectAnswers = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile boolean verdictsRead;

    private record Answer(Verdict verdict, String why, Remote latest) {}

    /** Whether checks can run: the hot mods runtime reads the jars. */
    public static boolean canCheck() {
        return HotBridge.PRESENT;
    }

    private static void queueCheck(Hit h) {
        if (h.kind != Kind.MOD || h.verdict != Verdict.UNKNOWN || !canCheck()) return;
        h.verdict = Verdict.CHECKING;
        CHECK.execute(() -> {
            boolean listed = false;
            for (Hit x : results) {
                if (x == h) {
                    listed = true;
                    break;
                }
            }
            if (!listed) { // gone from the list (another search): not worth the download
                h.verdict = Verdict.UNKNOWN;
                return;
            }
            try {
                Answer a = answer(h.projectId, 0, new HashSet<>());
                h.latest = a.latest();
                h.verdictWhy = a.why();
                h.verdict = a.verdict();
            } catch (Exception e) {
                h.verdictWhy = "could not be checked";
                h.verdict = Verdict.UNKNOWN;
                AuroraClient.LOGGER.debug("Mod check {}: {}", h.title, e.toString());
            }
        });
    }

    /** Checked again (after a failed check, or once more mods are installed). */
    public static void recheck(Hit h) {
        if (h.verdict == Verdict.CHECKING) return;
        projectAnswers.remove(h.projectId);
        h.verdict = Verdict.UNKNOWN;
        queueCheck(h);
    }

    /** A project's verdict, its required mods included; computed once per session and shared by the check threads. */
    private static Answer answer(String projectId, int depth, Set<String> seen) throws Exception {
        CompletableFuture<Answer> mine = new CompletableFuture<>();
        CompletableFuture<Answer> was = projectAnswers.putIfAbsent(projectId, mine);
        if (was != null) {
            if (seen.contains(projectId)) return new Answer(Verdict.HOT, "", null); // a cycle: judged by its first visit
            return was.get();
        }
        try {
            Answer a = compute(projectId, depth, seen);
            mine.complete(a);
            if (a.verdict() == Verdict.UNKNOWN) projectAnswers.remove(projectId, mine); // tried again next time
            return a;
        } catch (Exception e) {
            projectAnswers.remove(projectId, mine);
            mine.completeExceptionally(e);
            throw e;
        }
    }

    private static Answer compute(String projectId, int depth, Set<String> seen) throws Exception {
        seen.add(projectId);
        Remote v = latest(projectId);
        if (v == null) return new Answer(Verdict.NONE, "No Fabric version for Minecraft " + gameVersion(), null);
        String a = verdictOf(v);
        int t = a.indexOf('\t');
        String kind = t < 0 ? a : a.substring(0, t), why = t < 0 ? "" : a.substring(t + 1);
        switch (kind) {
            case "INGAME":
                installedProjects.add(projectId); // loaded at startup (from a jar Modrinth does not know, or bundled)
                return new Answer(Verdict.HOT, "Already in the game", v);
            case "RESTART":
                return new Answer(Verdict.RESTART, why, v);
            case "HOT":
                break;
            default:
                return new Answer(Verdict.UNKNOWN, why.isEmpty() ? "could not be checked" : why, v);
        }
        if (depth < 4) {
            for (String dep : v.requiredProjects) {
                if (installedProjects.contains(dep) || seen.contains(dep)) continue;
                Answer d = answer(dep, depth + 1, seen);
                String depName = d.latest() == null ? "a required mod" : d.latest().fileName;
                if (d.verdict() == Verdict.RESTART) return new Answer(Verdict.RESTART, "needs " + depName + ": " + d.why(), v);
                if (d.verdict() == Verdict.NONE) return new Answer(Verdict.NONE, "a required mod has no version for " + gameVersion(), v);
                if (d.verdict() == Verdict.UNKNOWN) return new Answer(Verdict.UNKNOWN, depName + " could not be checked", v);
            }
        }
        return new Answer(Verdict.HOT, why, v);
    }

    /** The runtime's reading of one version's jar: downloaded to a scratch folder, read, deleted; the answer kept. */
    private static String verdictOf(Remote v) throws Exception {
        readVerdicts();
        String c = verdicts.get(v.versionId);
        if (c != null) return c;
        if (v.size > CHECK_MAX) return "FAILED\ttoo large to check before installing (" + (v.size >> 20) + " MB)";
        Path dir = Files.createDirectories(workDir().resolve("check"));
        Path jar = dir.resolve(v.versionId + "-" + Thread.currentThread().threadId() + ".jar");
        String a;
        try {
            download(v, jar);
            a = HotBridge.check(jar);
        } finally {
            Files.deleteIfExists(jar);
        }
        if (a == null) a = "FAILED\tno answer";
        a = a.replace('\n', ' ').replace('\r', ' ');
        if (a.startsWith("HOT\t") || a.startsWith("RESTART\t") || a.startsWith("INGAME\t")) {
            verdicts.put(v.versionId, a);
            synchronized (verdicts) {
                Files.writeString(dir.resolve("verdicts.txt"), v.versionId + "\t" + a + "\n", StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            }
        }
        return a;
    }

    private static void readVerdicts() {
        if (verdictsRead) return;
        synchronized (verdicts) {
            if (verdictsRead) return;
            try {
                Path dir = Files.createDirectories(workDir().resolve("check"));
                Path f = dir.resolve("verdicts.txt");
                // answers belong to the runtime that gave them: another version may judge differently
                String runtime = FabricLoader.getInstance().getModContainer("paranoia_dynamic").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("");
                Path stamp = dir.resolve("runtime.txt");
                if (!Files.exists(stamp) || !Files.readString(stamp).trim().equals(runtime)) Files.deleteIfExists(f);
                Files.writeString(stamp, runtime);
                if (Files.exists(f)) {
                    for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                        int t = line.indexOf('\t');
                        if (t > 0) verdicts.put(line.substring(0, t), line.substring(t + 1));
                    }
                }
            } catch (Exception e) {
                AuroraClient.LOGGER.warn("Mod check cache: {}", e.toString());
            }
            verdictsRead = true;
        }
    }

    // =========================================================================== Project page

    /** A version listed on a project's page. */
    public static final class Version {
        public final Remote remote;
        public final String name, number, type, date;
        public final List<String> gameVersions = new ArrayList<>();
        /** For this Minecraft version: can be installed. */
        public final boolean fits;

        Version(JsonObject o) {
            remote = new Remote(o);
            name = str(o, "name") == null ? remote.versionNumber : str(o, "name");
            number = remote.versionNumber;
            type = str(o, "version_type") == null ? "release" : str(o, "version_type");
            String d = str(o, "date_published");
            date = d == null || d.length() < 10 ? "" : d.substring(0, 10);
            if (o.has("game_versions")) for (JsonElement e : o.getAsJsonArray("game_versions")) gameVersions.add(e.getAsString());
            fits = gameVersions.contains(gameVersion());
        }
    }

    /** What a project's page shows, fetched once. */
    public static final class Details {
        public volatile boolean loaded, failed;
        /** The description, as plain text. */
        public volatile String body = "";
        public volatile List<String> gallery = List.of();
        public volatile List<Version> versions = List.of();
        public volatile String license = "", source;
    }

    private static final Map<String, Details> details = new java.util.concurrent.ConcurrentHashMap<>();

    public static Details details(Hit h) {
        return details.computeIfAbsent(h.projectId, id -> {
            Details d = new Details();
            if (!h.gallery.isEmpty()) d.gallery = List.copyOf(h.gallery);
            CompletableFuture.runAsync(() -> {
                try {
                    JsonObject p = GSON.fromJson(get("/project/" + enc(id)), JsonObject.class);
                    d.body = plainText(str(p, "body") == null ? h.description : str(p, "body"));
                    List<String> g = new ArrayList<>();
                    if (p.has("gallery")) {
                        for (JsonElement e : p.getAsJsonArray("gallery")) {
                            String u = str(e.getAsJsonObject(), "url");
                            if (u != null) g.add(u);
                        }
                    }
                    if (!g.isEmpty()) d.gallery = g;
                    if (p.has("license") && p.get("license").isJsonObject()) d.license = str(p.getAsJsonObject("license"), "id");
                    d.source = str(p, "source_url");
                    String loader = h.kind == Kind.RESOURCE_PACK ? "" : "?loaders=" + enc("[\"" + h.kind.loader + "\"]");
                    JsonArray a = GSON.fromJson(get("/project/" + enc(id) + "/version" + loader), JsonArray.class);
                    List<Version> vs = new ArrayList<>();
                    for (JsonElement e : a) {
                        try {
                            vs.add(new Version(e.getAsJsonObject()));
                        } catch (RuntimeException ignored) {
                            // a version without files
                        }
                        if (vs.size() >= 40) break;
                    }
                    d.versions = vs;
                } catch (Exception e) {
                    d.failed = true;
                    details.remove(id);
                    AuroraClient.LOGGER.warn("Mod manager project page: {}", e.toString());
                } finally {
                    d.loaded = true;
                }
            });
            return d;
        });
    }

    /** Modrinth's markdown/HTML description as readable plain text. */
    public static String plainText(String md) {
        if (md == null) return "";
        String s = md.replace("\r", "");
        s = s.replaceAll("(?s)<!--.*?-->", "");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</(p|div|h\\d|li|tr)>", "\n").replaceAll("(?i)<li[^>]*>", "• ");
        s = s.replaceAll("(?s)<[^>]+>", "");
        s = s.replaceAll("\\[!\\[[^\\]]*]\\([^)]*\\)]\\([^)]*\\)", ""); // badges
        s = s.replaceAll("!\\[[^\\]]*]\\([^)]*\\)", "");                 // images
        s = s.replaceAll("\\[([^\\]]*)]\\([^)]*\\)", "$1");               // links
        s = s.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "");
        s = s.replaceAll("(?m)^\\s*[-*+]\\s+", "• ");
        s = s.replaceAll("(?m)^\\s*>\\s?", "");
        s = s.replaceAll("(?m)^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$", "");
        s = s.replace("**", "").replace("__", "").replace("`", "");
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
        s = s.replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
        return s.length() > 8000 ? s.substring(0, 8000) + "…" : s;
    }

    // =========================================================================== Actions

    public static void install(Hit h) {
        installVersion(h, null);
    }

    /** Installs one chosen version (from the project page), or the newest when {@code chosen} is null. */
    public static void installVersion(Hit h, Remote chosen) {
        if (h.state != null || h.installed()) return;
        if (h.kind != Kind.MOD) {
            installPack(h);
            return;
        }
        h.state = "Downloading…";
        h.progress = 0;
        WORK.execute(() -> {
            try {
                List<Path> added = new ArrayList<>();
                installProject(h.projectId, chosen, added, 0, h);
                h.progress = -1;
                h.state = added.isEmpty() ? "No version for " + gameVersion() : "Installed";
                if (!added.isEmpty()) {
                    h.state = "Loading…";
                    String extra = added.size() > 1 ? " (+" + (added.size() - 1) + " required)" : "";
                    String why = loadLive(added);
                    h.state = why == null ? "Running" : "Installed";
                    if (why == null) toast(Icons.BOLT, h.title + " is running", "Loaded live, no restart" + extra);
                    else toast(Icons.CHECK, h.title + " installed" + extra, "Restart to load it: " + why);
                } else {
                    toast(Icons.INFO, h.title, "No Fabric version for Minecraft " + gameVersion());
                }
            } catch (Exception e) {
                h.state = null;
                toast(Icons.INFO, "Install failed", h.title);
                AuroraClient.LOGGER.warn("Mod manager install: {}", e.toString());
            } finally {
                h.progress = -1;
            }
            scan();
        });
    }

    /** Resource packs and shaders: a zip in their folder, picked in the game's own pack screens. */
    private static void installPack(Hit h) {
        h.state = "Downloading…";
        WORK.execute(() -> {
            try {
                Remote v = latest(h.projectId, h.kind);
                if (v == null) {
                    h.state = "None for " + gameVersion();
                    return;
                }
                Path dir = Files.createDirectories(FabricLoader.getInstance().getGameDir().resolve(h.kind.folder));
                Path target = dir.resolve(safeName(v.fileName, h.kind.ext));
                if (!Files.exists(target)) {
                    h.progress = 0;
                    download(v, target, p -> {
                    h.progress = (float) p;
                    if (p > 0 && p < 1) h.progressSteps++;
                });
                }
                h.progress = -1;
                installedProjects.add(h.projectId);
                h.state = "Installed";
                boolean shader = h.kind == Kind.SHADER;
                if (shader && !FabricLoader.getInstance().isModLoaded("iris")) toast(Icons.INFO, h.title + " downloaded", "Shaders need Iris to run");
                else toast(Icons.CHECK, h.title + " downloaded", shader ? "Pick it in Iris' shader packs" : "Turn it on in Options > Resource Packs");
            } catch (Exception e) {
                h.state = null;
                h.progress = -1;
                toast(Icons.INFO, "Download failed", h.title);
                AuroraClient.LOGGER.warn("Mod manager pack: {}", e.toString());
            }
        });
    }

    /** Installs a project and the required mods it lists that are missing. */
    private static void installProject(String projectId, Remote chosen, List<Path> added, int depth, Hit h) throws Exception {
        if (depth > 6 || installedProjects.contains(projectId) || !installing.add(projectId)) return;
        try {
            Remote v = chosen != null ? chosen : latest(projectId);
            if (v == null) return;
            Path target = modsDir().resolve(safeName(v.fileName));
            if (!Files.exists(target)) {
                h.state = depth == 0 ? "Downloading…" : "Downloading " + v.fileName;
                h.progress = 0;
                download(v, target, p -> {
                    h.progress = (float) p;
                    if (p > 0 && p < 1) h.progressSteps++;
                });
            }
            installedProjects.add(projectId);
            for (String dep : v.requiredProjects) installProject(dep, null, added, depth + 1, h);
            added.add(target); // after its dependencies
        } finally {
            installing.remove(projectId);
        }
    }

    public static void update(Installed i) {
        Remote v = i.update;
        if (v == null || i.working || i.pending != null) return;
        i.working = true;
        WORK.execute(() -> {
            try {
                String name = safeName(v.fileName) + (i.enabled ? "" : ".disabled");
                Path target = modsDir().resolve(name);
                boolean sameFile = target.equals(i.path);
                if (i.live && i.enabled && !sameFile) {
                    download(v, target);
                    String a = HotBridge.update(target);
                    if (HotBridge.done(a)) {
                        retire(i.path);
                        toast(Icons.BOLT, i.name + " updated", v.versionNumber + " is running · no restart");
                        scan();
                        return;
                    }
                    // the runtime brought the old version back: the new jar waits for the restart, like before
                    AuroraClient.LOGGER.info("Hot mods: {} cannot be updated now ({}), at restart instead", i.name, HotBridge.reason(a));
                }
                if (i.loaded && sameFile) {
                    // same name as the jar in use: staged, swapped once the game is closed
                    Path staged = workDir().resolve(v.versionId + ".jar.new");
                    download(v, staged);
                    i.added = staged;
                    retire(i.path);
                    queue(i.path, staged, i.path);
                } else {
                    download(v, target);
                    if (i.loaded) i.added = target;
                    if (!sameFile) retire(i.path);
                }
                if (!i.loaded && i.enabled) changed = true;
                i.pending = "Updates to " + v.versionNumber + " at restart";
                toast(Icons.CHECK, i.name + " updated", "Restart to load " + v.versionNumber);
            } catch (Exception e) {
                toast(Icons.INFO, "Update failed", i.name);
                AuroraClient.LOGGER.warn("Mod manager update: {}", e.toString());
            } finally {
                i.working = false;
            }
            if (!i.loaded) scan();
        });
    }

    public static void updateAll() {
        for (Installed i : installed) if (i.update != null && i.pending == null && !i.self()) update(i);
    }

    public static int updateCount() {
        int n = 0;
        for (Installed i : installed) if (i.update != null && i.pending == null && !i.self()) n++;
        return n;
    }

    /** Disables an enabled jar, or enables a disabled one. */
    public static void toggle(Installed i) {
        if (i.working || i.pending != null || i.self()) return;
        i.working = true;
        WORK.execute(() -> {
            try {
                String f = i.file;
                Path target = i.path.resolveSibling(i.enabled ? f + ".disabled" : f.substring(0, f.length() - ".disabled".length()));
                if (i.live) {
                    String a = HotBridge.setEnabled(i.modId, !i.enabled);
                    if (HotBridge.done(a)) {
                        // the file follows, so the next launch matches
                        Files.move(i.path, target, StandardCopyOption.REPLACE_EXISTING);
                        toast(Icons.BOLT, i.name + (i.enabled ? " turned off" : " is running"), "No restart");
                        return;
                    }
                    AuroraClient.LOGGER.info("Hot mods: {} cannot change now ({}), at restart instead", i.name, HotBridge.reason(a));
                }
                if (i.loaded) {
                    queue(i.path, i.path, target);
                    i.pending = "Turns off at restart";
                } else {
                    Files.move(i.path, target, StandardCopyOption.REPLACE_EXISTING);
                    if (!i.enabled) {
                        String why = loadLive(List.of(target));
                        if (why == null) toast(Icons.BOLT, i.name + " is running", "Enabled live, no restart");
                        else toast(Icons.CHECK, i.name + " enabled", "Restart to load it: " + why);
                    }
                }
            } catch (Exception e) {
                toast(Icons.INFO, "Could not change", i.name);
            } finally {
                i.working = false;
                if (i.live || !i.loaded) scan();
            }
        });
    }

    /** Why each jar could not be loaded live (by path), shown in the list. */
    public static final Map<Path, String> refused = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Tries to load jars into the running game. Returns null when they run now, or why a restart is needed
     * (in that case the restart button shows).
     */
    private static String loadLive(List<Path> jars) {
        String runtimeWhy = null;
        if (HotBridge.PRESENT) {
            // the hot mods runtime first: it also applies mixins to classes the game already loaded
            String a = HotBridge.installForSession(jars);
            if (HotBridge.done(a)) {
                jars.forEach(refused::remove);
                return null;
            }
            if (!HotBridge.restart(a)) {
                // refused (e.g. a missing dependency) or failed while starting: nothing of it was kept; a restart is
                // the safe way to load it
                jars.forEach(p -> refused.put(p, HotBridge.reason(a)));
                changed = true;
                return HotBridge.reason(a);
            }
            runtimeWhy = HotBridge.reason(a);
        }
        try {
            HotLoader.Result r = HotLoader.load(jars);
            jars.forEach(refused::remove);
            if (r.clean()) return null;
            changed = true;
            return String.join(", ", r.warnings());
        } catch (HotLoader.Refused e) {
            String why = runtimeWhy != null ? runtimeWhy : e.getMessage();
            jars.forEach(p -> refused.put(p, why));
            changed = true;
            return why;
        } catch (Exception e) {
            AuroraClient.LOGGER.warn("Hot load failed", e);
            jars.forEach(p -> refused.put(p, "could not load it live"));
            changed = true;
            return "could not load it live";
        }
    }

    /**
     * Jars dropped onto the manager (or picked from the « Add » page): copied into the mods folder, then started
     * without a restart when they can be.
     */
    public static void addJars(List<Path> files) {
        WORK.execute(() -> {
            List<Path> added = new ArrayList<>();
            for (Path f : files) {
                try {
                    String n = f.getFileName().toString();
                    if (!n.toLowerCase(Locale.ROOT).endsWith(".jar") || !Files.isRegularFile(f)) continue;
                    Path target = modsDir().resolve(safeName(n));
                    if (Files.exists(target)) {
                        toast(Icons.INFO, n, "Already in the mods folder");
                        continue;
                    }
                    Files.copy(f, target);
                    added.add(target);
                } catch (Exception e) {
                    toast(Icons.INFO, "Could not add", f.getFileName().toString());
                }
            }
            if (added.isEmpty()) return;
            String why = loadLive(added);
            String what = added.size() == 1 ? added.get(0).getFileName().toString() : added.size() + " mods";
            if (why == null) toast(Icons.BOLT, what + " running", "Added and loaded live, no restart");
            else toast(Icons.CHECK, what + " added", "Restart to load: " + why);
            scan();
        });
    }

    public static Path modsFolder() {
        return modsDir();
    }

    /** A jar that is in the folder but not running (dropped in by hand, or refused earlier): try again. */
    public static void loadNow(Installed i) {
        if (i.working || i.loaded || !i.enabled || i.pending != null) return;
        i.working = true;
        WORK.execute(() -> {
            String why = loadLive(List.of(i.path));
            if (why == null) toast(Icons.BOLT, i.name + " is running", "Loaded live, no restart");
            else toast(Icons.INFO, i.name, "Needs a restart: " + why);
            i.working = false;
            scan();
        });
    }

    public static void remove(Installed i) {
        if (i.working || i.pending != null || i.self()) return;
        i.working = true;
        WORK.execute(() -> {
            try {
                if (i.live) {
                    String a = HotBridge.remove(i.modId);
                    if (HotBridge.done(a)) {
                        retire(i.path);
                        toast(Icons.TRASH, i.name + " removed", "No restart · kept in mods/.aurora-mods/old");
                        scan();
                        return;
                    }
                    AuroraClient.LOGGER.info("Hot mods: {} cannot be removed now ({}), at restart instead", i.name, HotBridge.reason(a));
                }
                retire(i.path);
                if (i.loaded) i.pending = "Removed at restart";
                toast(Icons.TRASH, i.name + " removed", "Kept in mods/.aurora-mods/old if you need it back");
            } catch (Exception e) {
                toast(Icons.INFO, "Could not remove", i.name);
            } finally {
                i.working = false;
            }
            if (!i.loaded) scan();
        });
    }

    /** Moves a jar out of the mods folder, now or at exit when the game uses it. */
    private static void retire(Path p) throws Exception {
        Path old = Files.createDirectories(workDir().resolve("old")).resolve(p.getFileName() + "." + System.currentTimeMillis() + ".old");
        if (loadedPaths().contains(p.toAbsolutePath().normalize())) queue(p, p, old);
        else Files.move(p, old);
    }

    private static void queue(Path owner, Path from, Path to) {
        synchronized (queued) {
            queued.add(new Op(owner, new String[]{"MOVE", from.toAbsolutePath().toString(), to.toAbsolutePath().toString()}));
        }
    }

    /** A loaded jar turned off for the next launch (a server forbids it); shown as pending in the list. */
    static void disableAtRestart(Path jar, Path off) {
        synchronized (queued) {
            if (queued.stream().anyMatch(op -> op.owner().equals(jar))) return;
        }
        queue(jar, jar, off);
        for (Installed i : installed) if (i.path.toAbsolutePath().normalize().equals(jar)) i.pending = "Turns off at restart";
    }

    public static void toast(String icon, String title, String body) {
        Minecraft.getInstance().execute(() -> Toasts.push(icon, title, body, false));
    }

    // =========================================================================== Restart

    /** Closes the game; {@link #applyOnExit()} starts it again once the files are in place. */
    public static void restart() {
        relaunch = true;
        Minecraft.getInstance().stop();
    }

    /** Called while the game closes: hands queued moves (and the relaunch) to {@link ModSwap}. */
    public static void applyOnExit() {
        List<String[]> ops = new ArrayList<>();
        synchronized (queued) {
            for (Op op : queued) ops.add(op.move());
        }
        if (ops.isEmpty() && !relaunch) return;
        try {
            List<String> plan = new ArrayList<>();
            plan.add(Long.toString(ProcessHandle.current().pid()));
            for (String[] op : ops) plan.add(String.join("\t", op));
            if (relaunch) {
                plan.add("CWD\t" + Path.of("").toAbsolutePath());
                for (String arg : relaunchCommand()) {
                    plan.add("ARG\t" + Base64.getEncoder().encodeToString(arg.getBytes(StandardCharsets.UTF_8)));
                }
            }
            Path file = workDir().resolve("plan.txt");
            Files.write(file, plan, StandardCharsets.UTF_8);

            String javaExe = ProcessHandle.current().info().command()
                    .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            String cp = String.join(java.io.File.pathSeparator, FabricLoader.getInstance().getModContainer(AuroraClient.MOD_ID)
                    .map(m -> m.getOrigin().getPaths().stream().map(Path::toString).toList()).orElse(List.of()));
            List<String> cmd = new ArrayList<>();
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                // « start » detaches it: launchers that end the game's child processes can't stop it
                cmd.addAll(List.of("cmd", "/c", "start", "\"\"", "/min"));
            }
            cmd.addAll(List.of(javaExe, "-cp", cp, ModSwap.class.getName(), file.toAbsolutePath().toString()));
            new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (Exception e) {
            AuroraClient.LOGGER.warn("Mod manager: could not hand over pending changes: {}", e.toString());
        }
    }

    /** The command line of this game: same Java, same JVM options, same main class, same game arguments. */
    private static List<String> relaunchCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(ProcessHandle.current().info().command().orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        cmd.addAll(JvmTuning.tune(ManagementFactory.getRuntimeMXBean().getInputArguments()));
        String main = System.getProperty("sun.java.command", "").split(" ")[0];
        if (main.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            cmd.add("-jar");
            cmd.add(main);
        } else {
            cmd.add("-cp");
            cmd.add(System.getProperty("java.class.path"));
            cmd.add(main);
        }
        cmd.addAll(Arrays.asList(FabricLoader.getInstance().getLaunchArguments(false)));
        return cmd;
    }
}
