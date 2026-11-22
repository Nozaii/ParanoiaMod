package gg.aurora;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import gg.aurora.module.Category;
import gg.aurora.module.Module;
import gg.aurora.module.Modules;
import gg.aurora.ui.Icons;
import gg.aurora.ui.Rows;
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

    public static final Module MODULE = Modules.register(new Module("mod_manager", "Mod Manager", Category.MISC, Icons.BAG, false) {
        @Override
        protected void onToggle() {
            enabled = false; // a shortcut, not a switch
            open();
        }
    }).withSettings(() -> List.of(
            new Rows.Button("Open mod manager", "Install and update mods from Modrinth", Icons.BAG, ModManager::open),
            new Rows.Info("Minecraft", ModManager::gameVersion),
            new Rows.Info("Pending changes", () -> restartNeeded() ? "Restart to apply" : "None")));

    /** Forces the class to load so the module is registered with the others. */
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
        public volatile boolean working;

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

    /** A search result. */
    public static final class Hit {
        public final String projectId, title, author, description;
        public final long downloads;
        public volatile String state;

        Hit(JsonObject o) {
            projectId = o.get("project_id").getAsString();
            title = o.get("title").getAsString();
            author = str(o, "author") == null ? "?" : str(o, "author");
            description = str(o, "description") == null ? "" : str(o, "description");
            downloads = o.get("downloads").getAsLong();
        }

        public boolean installed() {
            return installedProjects.contains(projectId);
        }
    }

    public static volatile List<Installed> installed = List.of();
    public static volatile List<Hit> results = List.of();
    public static volatile boolean scanning, searching;
    /** Last error shown in the list, or null. */
    public static volatile String error;

    private static final Set<String> installedProjects = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Set<String> installing = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Operations applied after the game closes: {"MOVE", from, to}. */
    private static final List<String[]> queued = new ArrayList<>();
    /** Something changed in the mods folder since launch. */
    private static volatile boolean changed;
    private static volatile boolean relaunch;

    public static boolean restartNeeded() {
        return changed;
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
        String q = "?loaders=" + enc("[\"fabric\"]") + "&game_versions=" + enc("[\"" + gameVersion() + "\"]");
        JsonArray a = GSON.fromJson(get("/project/" + enc(projectId) + "/version" + q), JsonArray.class);
        return a.isEmpty() ? null : new Remote(a.get(0).getAsJsonObject());
    }

    /** Downloads and checks a version file, then moves it to {@code target}. */
    private static void download(Remote v, Path target) throws Exception {
        URI uri = URI.create(v.url);
        if (!"https".equals(uri.getScheme()) || !"cdn.modrinth.com".equals(uri.getHost())) throw new IllegalStateException("unexpected download host");
        if (v.size > MAX_BYTES) throw new IllegalStateException("file too large");
        HttpResponse<byte[]> r = HTTP.send(request(v.url).timeout(Duration.ofSeconds(120)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        byte[] data = r.body();
        if (r.statusCode() != 200 || data.length != v.size || !hash("SHA-512", data).equals(v.sha512)) throw new IllegalStateException("download check failed");
        Path tmp = workDir().resolve(v.versionId + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String hash(String algo, byte[] data) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance(algo).digest(data));
    }

    /** Only a plain jar name from Modrinth: no folders, no hidden files. */
    private static String safeName(String name) {
        String n = name.replaceAll("[\\\\/:*?\"<>|]+", "_");
        if (n.startsWith(".") || !n.toLowerCase(Locale.ROOT).endsWith(".jar")) throw new IllegalStateException("unexpected file name");
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
                Map<Path, String> pendingByPath = new HashMap<>();
                for (Installed i : installed) if (i.pending != null) pendingByPath.put(i.path, i.pending);
                try (Stream<Path> s = Files.list(modsDir())) {
                    for (Path p : s.toList()) {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (n.startsWith(".") || !Files.isRegularFile(p) || !(n.endsWith(".jar") || n.endsWith(".jar.disabled"))) continue;
                        Installed i = read(p, loaded.contains(p.toAbsolutePath().normalize()));
                        i.pending = pendingByPath.get(i.path);
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
        if (searching) return;
        searching = true;
        CompletableFuture.runAsync(() -> {
            try {
                String facets = "[[\"project_type:mod\"],[\"categories:fabric\"],[\"versions:" + gameVersion() + "\"]]";
                String q = query.isBlank() ? "" : "query=" + enc(query.trim()) + "&";
                JsonObject o = GSON.fromJson(get("/search?" + q + "facets=" + enc(facets) + "&index=" + (query.isBlank() ? "downloads" : "relevance") + "&limit=30"),
                        JsonObject.class);
                List<Hit> out = new ArrayList<>();
                for (JsonElement e : o.getAsJsonArray("hits")) out.add(new Hit(e.getAsJsonObject()));
                results = out;
                error = null;
            } catch (Exception e) {
                error = "Could not reach Modrinth";
                AuroraClient.LOGGER.warn("Mod manager search: {}", e.toString());
            } finally {
                searching = false;
            }
        });
    }

    // =========================================================================== Actions

    public static void install(Hit h) {
        if (h.state != null || h.installed()) return;
        h.state = "Installing…";
        WORK.execute(() -> {
            try {
                List<Path> added = new ArrayList<>();
                installProject(h.projectId, added, 0);
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
            }
            scan();
        });
    }

    /** Installs a project and the required mods it lists that are missing. */
    private static void installProject(String projectId, List<Path> added, int depth) throws Exception {
        if (depth > 6 || installedProjects.contains(projectId) || !installing.add(projectId)) return;
        try {
            Remote v = latest(projectId);
            if (v == null) return;
            Path target = modsDir().resolve(safeName(v.fileName));
            if (!Files.exists(target)) download(v, target);
            installedProjects.add(projectId);
            for (String dep : v.requiredProjects) installProject(dep, added, depth + 1);
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
                if (i.loaded && sameFile) {
                    // same name as the jar in use: staged, swapped once the game is closed
                    Path staged = workDir().resolve(v.versionId + ".jar.new");
                    download(v, staged);
                    retire(i.path);
                    queue(staged, i.path);
                } else {
                    download(v, target);
                    if (!sameFile) retire(i.path);
                }
                changed = true;
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
                if (i.loaded) {
                    queue(i.path, target);
                    i.pending = "Disabled at restart";
                    changed = true;
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
            }
            if (!i.loaded) scan();
        });
    }

    /** Why each jar could not be loaded live (by path), shown in the list. */
    public static final Map<Path, String> refused = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Tries to load jars into the running game. Returns null when they run now, or why a restart is needed
     * (in that case the restart button shows).
     */
    private static String loadLive(List<Path> jars) {
        try {
            HotLoader.Result r = HotLoader.load(jars);
            jars.forEach(refused::remove);
            if (r.clean()) return null;
            changed = true;
            return String.join(", ", r.warnings());
        } catch (HotLoader.Refused e) {
            jars.forEach(p -> refused.put(p, e.getMessage()));
            changed = true;
            return e.getMessage();
        } catch (Exception e) {
            AuroraClient.LOGGER.warn("Hot load failed", e);
            jars.forEach(p -> refused.put(p, "could not load it live"));
            changed = true;
            return "could not load it live";
        }
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
                retire(i.path);
                if (i.loaded) i.pending = "Removed at restart";
                changed = true;
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
        if (loadedPaths().contains(p.toAbsolutePath().normalize())) queue(p, old);
        else Files.move(p, old);
    }

    private static void queue(Path from, Path to) {
        synchronized (queued) {
            queued.add(new String[]{"MOVE", from.toAbsolutePath().toString(), to.toAbsolutePath().toString()});
        }
    }

    private static void toast(String icon, String title, String body) {
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
        List<String[]> ops;
        synchronized (queued) {
            ops = new ArrayList<>(queued);
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
        cmd.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
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
