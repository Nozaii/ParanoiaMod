package gg.aurora;

import com.google.gson.JsonObject;
import gg.aurora.module.Module;
import gg.aurora.module.Modules;
import gg.aurora.ui.Icons;
import gg.aurora.ui.Toasts;
import net.minecraft.client.Minecraft;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Fair-play : liste « serveur → modules interdits » tenue sur le serveur Paranoia (GET /v1/fairplay).
 * En arrivant sur un serveur listé, les modules concernés sont coupés le temps de la session ; leur réglage
 * enregistré ne change pas et revient en quittant. La liste est relue au démarrage puis toutes les 30 minutes.
 */
public final class FairPlay {
    private FairPlay() {}

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
    /** Suffixe d'adresse (« hypixel.net ») ou « * » pour tous les serveurs → ids de modules interdits. */
    private static volatile Map<String, Set<String>> rules = Map.of();
    private static long fetchedAt;
    private static final Set<Module> blocked = new HashSet<>(), restore = new HashSet<>();
    private static String currentHost;

    /** Module coupé par le serveur actuel (ne peut pas être réactivé). */
    public static boolean blocked(Module m) {
        return blocked.contains(m);
    }

    /** Module coupé par fair-play qui était activé : enregistré comme activé dans la config. */
    public static boolean restoring(Module m) {
        return restore.contains(m);
    }

    public static void notice(Module m) {
        Toasts.push(Icons.LOCK, m.name, "Disabled on this server", false);
    }

    public static void tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (now - fetchedAt > 30 * 60_000L) {
            fetchedAt = now;
            fetch();
        }
        String host = mc.getCurrentServer() == null || mc.level == null ? null : host(mc.getCurrentServer().ip);
        if (!java.util.Objects.equals(host, currentHost)) {
            leave();
            currentHost = host;
            if (host != null) apply(host);
        }
    }

    private static String host(String ip) {
        String h = ip.trim().toLowerCase(Locale.ROOT);
        int c = h.lastIndexOf(':');
        if (c > 0 && h.indexOf(':') == c) h = h.substring(0, c); // port (pas une adresse IPv6)
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h;
    }

    /** Modules interdits pour cette adresse : règles « * » + toute règle dont l'adresse se termine par le suffixe. */
    static Set<String> rulesFor(String host) {
        Set<String> out = new HashSet<>();
        rules.forEach((suffix, ids) -> {
            if (suffix.equals("*") || host.equals(suffix) || host.endsWith("." + suffix)) out.addAll(ids);
        });
        return out;
    }

    private static void apply(String host) {
        Set<String> ids = rulesFor(host);
        if (ids.isEmpty()) return;
        List<String> names = new java.util.ArrayList<>();
        for (String id : ids) {
            Module m = Modules.byId(id);
            if (m == null) continue;
            blocked.add(m);
            if (m.enabled) {
                restore.add(m);
                m.enabled = false;
                names.add(m.name);
            }
        }
        if (!names.isEmpty()) Toasts.push(Icons.LOCK, "Fair-play", String.join(", ", names) + (names.size() > 1 ? " are" : " is") + " off on this server", false);
    }

    private static void leave() {
        for (Module m : restore) m.enabled = true;
        restore.clear();
        blocked.clear();
    }

    private static void fetch() {
        Thread t = new Thread(() -> {
            try {
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(Friends.api() + "/v1/fairplay"))
                        .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) return;
                JsonObject o = new com.google.gson.Gson().fromJson(r.body(), JsonObject.class);
                if (o == null || !o.has("servers")) return;
                Map<String, Set<String>> next = new HashMap<>();
                for (var e : o.getAsJsonObject("servers").entrySet()) {
                    Set<String> ids = new HashSet<>();
                    for (var id : e.getValue().getAsJsonArray()) ids.add(id.getAsString());
                    next.put(e.getKey().toLowerCase(Locale.ROOT), ids);
                }
                rules = next;
                // liste changée pendant la partie : réappliquée au prochain tick
                Minecraft.getInstance().execute(() -> currentHost = null);
            } catch (Exception e) {
                AuroraClient.LOGGER.debug("Fair-play list: {}", e.toString());
            }
        }, "Paranoia fair-play");
        t.setDaemon(true);
        t.start();
    }
}
