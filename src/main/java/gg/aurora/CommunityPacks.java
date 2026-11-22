package gg.aurora;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import gg.aurora.ui.Icons;
import gg.aurora.ui.Toasts;
import net.minecraft.client.Minecraft;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Community texture packs: list, like, download and publish through the Paranoia API. All network work runs off the game thread. */
public final class CommunityPacks {
    private CommunityPacks() {}

    private static final long MAX_BYTES = 20L << 20;
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public static final class Entry {
        public final String id, name, author;
        public final long size;
        public int likes, views, downloads;
        public boolean liked;
        public final boolean mine;
        /** Already in the resourcepacks folder. */
        public boolean have;

        Entry(JsonObject o) {
            id = o.get("id").getAsString();
            name = o.get("name").getAsString();
            author = o.get("author").getAsString();
            size = o.get("size").getAsLong();
            likes = o.get("likes").getAsInt();
            views = o.get("views").getAsInt();
            downloads = o.get("downloads").getAsInt();
            liked = o.get("liked").getAsBoolean();
            mine = o.get("mine").getAsBoolean();
            have = Files.exists(fileFor(this));
        }
    }

    public static volatile List<Entry> list = List.of();
    public static volatile boolean loading;
    /** Why the list could not be loaded, or null. */
    public static volatile String error;
    public static volatile boolean busy;

    private static final Set<String> seen = new HashSet<>();
    private static final List<String> pendingViews = new ArrayList<>();
    private static long lastViews;

    private static HttpRequest.Builder request(String path) throws Exception {
        if (!Friends.ensureSession()) throw new IllegalStateException("session");
        return HttpRequest.newBuilder(URI.create(Friends.api() + path)).timeout(Duration.ofSeconds(60))
                .header("authorization", "Bearer " + Friends.token());
    }

    private static String message(HttpResponse<String> r) {
        try {
            return GSON.fromJson(r.body(), JsonObject.class).get("message").getAsString();
        } catch (RuntimeException e) {
            return "Error " + r.statusCode();
        }
    }

    /** Dev screenshots only: fills the list without the server. */
    public static void devSample(String json) {
        List<Entry> out = new ArrayList<>();
        for (JsonElement e : GSON.fromJson(json, JsonArray.class)) out.add(new Entry(e.getAsJsonObject()));
        list = out;
    }

    public static void refresh(boolean newest) {
        if (loading) return;
        loading = true;
        CompletableFuture.runAsync(() -> {
            try {
                HttpResponse<String> r = HTTP.send(request("/v2/packs?sort=" + (newest ? "new" : "popular")).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 404) {
                    error = "Community packs are not online yet";
                    return;
                }
                if (r.statusCode() != 200) {
                    error = message(r);
                    return;
                }
                JsonArray a = GSON.fromJson(r.body(), JsonObject.class).getAsJsonArray("packs");
                List<Entry> out = new ArrayList<>();
                for (JsonElement e : a) out.add(new Entry(e.getAsJsonObject()));
                list = out;
                error = null;
            } catch (IllegalStateException e) {
                error = "Sign in with your Minecraft account to use Community";
            } catch (Exception e) {
                error = "Could not reach the community server";
            } finally {
                loading = false;
            }
        });
    }

    /** A card reached the screen: counted once per session, sent in small batches. */
    public static void seen(Entry e) {
        if (seen.add(e.id)) pendingViews.add(e.id);
        long now = System.currentTimeMillis();
        if (pendingViews.isEmpty() || now - lastViews < 3000) return;
        lastViews = now;
        JsonArray ids = new JsonArray();
        pendingViews.forEach(ids::add);
        pendingViews.clear();
        JsonObject body = new JsonObject();
        body.add("ids", ids);
        CompletableFuture.runAsync(() -> {
            try {
                HTTP.send(request("/v2/packs/views").header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))).build(), HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {
                // a lost view count is harmless
            }
        });
    }

    public static void like(Entry e) {
        e.liked = !e.liked;
        e.likes += e.liked ? 1 : -1;
        post(e, "like", null);
    }

    public static void report(Entry e) {
        post(e, "report", "Thanks, this pack will be reviewed");
    }

    public static void delete(Entry e, Runnable done) {
        CompletableFuture.runAsync(() -> {
            try {
                HttpResponse<String> r = HTTP.send(request("/v2/packs/" + e.id + "/delete").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) Minecraft.getInstance().execute(done);
                else toast("Pack not deleted", message(r));
            } catch (Exception ex) {
                toast("Pack not deleted", "Could not reach the community server");
            }
        });
    }

    private static void post(Entry e, String action, String ok) {
        CompletableFuture.runAsync(() -> {
            try {
                HttpResponse<String> r = HTTP.send(request("/v2/packs/" + e.id + "/" + action).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) toast("Community", message(r));
                else if (ok != null) toast("Community", ok);
            } catch (Exception ex) {
                toast("Community", "Could not reach the community server");
            }
        });
    }

    private static void toast(String title, String text) {
        Minecraft.getInstance().execute(() -> Toasts.push(Icons.INFO, title, text, false));
    }

    public static Path fileFor(Entry e) {
        String safe = e.name.replaceAll("[^A-Za-z0-9 _.-]+", "_").replaceAll("\\.+", ".").trim();
        return ItemPacks.packDir().resolve((safe.isEmpty() ? "pack" : safe) + " (" + e.id.substring(0, 6) + ").zip");
    }

    public static void download(Entry e, Runnable done) {
        if (busy) return;
        busy = true;
        CompletableFuture.runAsync(() -> {
            try {
                HttpResponse<byte[]> r = HTTP.send(request("/v2/packs/" + e.id + "/file").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                byte[] b = r.body();
                if (r.statusCode() != 200 || b.length < 4 || b.length > MAX_BYTES || b[0] != 'P' || b[1] != 'K') {
                    toast("Download failed", "This pack is not available");
                    return;
                }
                Files.write(fileFor(e), b);
                e.downloads++;
                e.have = true;
                Minecraft.getInstance().execute(() -> {
                    Toasts.push(Icons.CHECK, "Pack downloaded", e.name, false);
                    done.run();
                });
            } catch (Exception ex) {
                toast("Download failed", "Could not reach the community server");
            } finally {
                busy = false;
            }
        });
    }

    /** Only a zip from the resourcepacks folder; the caller has asked the player first. */
    public static void publish(String fileName, Runnable done) {
        if (busy) return;
        busy = true;
        CompletableFuture.runAsync(() -> {
            try {
                Path file = ItemPacks.packDir().resolve(fileName);
                if (!fileName.toLowerCase(Locale.ROOT).endsWith(".zip") || !Files.isRegularFile(file)) {
                    toast("Pack not published", "Only .zip packs can be published");
                    return;
                }
                if (Files.size(file) > MAX_BYTES) {
                    toast("Pack not published", "Packs are limited to 20 MB");
                    return;
                }
                String name = java.net.URLEncoder.encode(fileName.substring(0, fileName.length() - 4), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
                HttpResponse<String> r = HTTP.send(request("/v2/packs").header("x-pack-name", name).header("content-type", "application/zip")
                        .POST(HttpRequest.BodyPublishers.ofFile(file)).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    Minecraft.getInstance().execute(() -> {
                        Toasts.push(Icons.CHECK, "Pack published", fileName, false);
                        done.run();
                    });
                } else {
                    toast("Pack not published", r.statusCode() == 404 ? "Community packs are not online yet" : message(r));
                }
            } catch (IllegalStateException e) {
                toast("Pack not published", "Sign in with your Minecraft account first");
            } catch (Exception e) {
                toast("Pack not published", "Could not reach the community server");
            } finally {
                busy = false;
            }
        });
    }
}
