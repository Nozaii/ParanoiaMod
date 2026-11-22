package gg.aurora.nametag;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import gg.aurora.AuroraClient;
import gg.aurora.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Profils cosmétiques partagés entre joueurs du mod (nametag, cape, ailes, aura, halo).
 * Publication : avec la session du serveur Paranoia (clé de profil Mojang, voir Friends).
 * Recherche : joueurs du mod déjà connus toutes les 15 s (pour voir les changements en direct),
 * découverte des autres joueurs de la liste Tab toutes les 60 s.
 */
public final class NametagSync {
    private NametagSync() {}

    /** Serveur officiel, utilisé quand aucune autre adresse n'est saisie dans les paramètres. */
    public static final String DEFAULT_API = "https://paranoia-nametags.paranoiaclient-fr.workers.dev";

    /** Profil public d'un joueur du mod. Chaînes vides = rien d'équipé. */
    public record Profile(NametagStyle style, String cape, String wings, String aura, String halo, String pet, String shield,
                          String trail, String discord, String back, Mini mini) {}

    /** Réglages du mini skin d'un autre joueur (taille, tête, équipement, cosmétiques du mini). */
    public record Mini(float head, float size, String helmet, String chest, String legs, String boots, String main, String off,
                       String cape, String wings, String halo, String back) {
        static Mini from(JsonObject o) {
            if (o == null) return null;
            return new Mini(num(o, "head", 1.3f), num(o, "size", 0.4f), str(o, "helmet"), str(o, "chest"), str(o, "legs"), str(o, "boots"),
                    str(o, "main"), str(o, "off"), str(o, "cape"), str(o, "wings"), str(o, "halo"), str(o, "back"));
        }

        private static float num(JsonObject o, String k, float def) {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsFloat() : def;
        }
    }

    private static final Gson GSON = new Gson();
    private static final long KNOWN_REFRESH_MS = 15_000, UNKNOWN_REFRESH_MS = 60_000;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Paranoia-Profiles");
        t.setDaemon(true);
        return t;
    });

    private record Entry(Profile profile, long fetchedAt) {}

    private static final Map<UUID, Entry> CACHE = new ConcurrentHashMap<>();
    private static final AtomicBoolean busy = new AtomicBoolean();
    private static int ticks;
    private static long dirtyAt = -1;
    private static boolean startupPublished;

    /** Dernier état affiché dans les paramètres. */
    public static volatile String status = "Pending";

    public static Profile profile(UUID id) {
        Entry e = CACHE.get(id);
        return e == null ? null : e.profile();
    }

    public static NametagStyle get(UUID id) {
        Profile p = profile(id);
        return p == null ? null : p.style();
    }

    public static boolean hasMod(UUID id) {
        return profile(id) != null;
    }

    private static String base() {
        String u = Settings.apiUrl.trim();
        if (u.isEmpty()) u = DEFAULT_API;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    /** À appeler quand le profil local change : publication 2 s après la dernière modification. */
    public static void markDirty() {
        dirtyAt = System.currentTimeMillis();
    }

    public static void tick(Minecraft mc) {
        if (!startupPublished && mc.getUser() != null && ++ticks > 60) {
            startupPublished = true;
            publish();
        }
        if (dirtyAt > 0 && System.currentTimeMillis() - dirtyAt > 2000) {
            dirtyAt = -1;
            publish();
        }
        if (++ticks % 40 != 0 || mc.getConnection() == null) return;
        long now = System.currentTimeMillis();
        List<UUID> wanted = new ArrayList<>();
        UUID self = mc.getUser().getProfileId();
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            UUID id = info.getProfile().id();
            if (id.equals(self)) continue;
            Entry e = CACHE.get(id);
            long max = e != null && e.profile() != null ? KNOWN_REFRESH_MS : UNKNOWN_REFRESH_MS;
            if (e == null || now - e.fetchedAt() > max) wanted.add(id);
            if (wanted.size() >= 100) break;
        }
        // les amis aussi (pour la page Amis, même s'ils sont sur un autre serveur)
        for (var f : gg.aurora.Friends.all()) {
            Entry e = CACHE.get(f.id());
            if ((e == null || now - e.fetchedAt() > UNKNOWN_REFRESH_MS) && wanted.size() < 100 && !wanted.contains(f.id())) wanted.add(f.id());
        }
        if (!wanted.isEmpty()) lookup(wanted);
    }

    public static JsonObject ownProfileJson() {
        JsonObject body = new JsonObject();
        body.add("style", NametagStyle.own().toJson());
        // imported capes stay on this PC
        body.addProperty("cape", Settings.cape.startsWith("imported_") ? gg.aurora.Media.sharedCode(gg.aurora.Capes.selected()) : Settings.cape);
        body.addProperty("wings", Settings.wings);
        body.addProperty("aura", Settings.aura);
        body.addProperty("halo", Settings.halo);
        // mini skin : le pseudo voyage dans le nom du familier (« skin_pseudo », accepté par le serveur tel quel)
        body.addProperty("pet", Settings.pet.equals("mini_skin") && !Settings.petSkin.isBlank()
                ? "skin_" + Settings.petSkin.toLowerCase(java.util.Locale.ROOT) : Settings.pet);
        body.addProperty("back", Settings.back);
        body.addProperty("shield", Settings.shieldDesign);
        body.addProperty("trail", gg.aurora.module.ClientModules.trailCode());
        if (Settings.pet.equals("mini_skin")) {
            JsonObject m = new JsonObject();
            m.addProperty("head", Settings.miniHead);
            m.addProperty("size", Settings.miniSize);
            m.addProperty("helmet", Settings.miniHelmet);
            m.addProperty("chest", Settings.miniChest);
            m.addProperty("legs", Settings.miniLegs);
            m.addProperty("boots", Settings.miniBoots);
            m.addProperty("main", Settings.miniMain);
            m.addProperty("off", Settings.miniOff);
            m.addProperty("cape", Settings.miniCape.startsWith("imported_") ? "" : Settings.miniCape);
            m.addProperty("wings", Settings.miniWings);
            m.addProperty("halo", Settings.miniHalo);
            m.addProperty("back", Settings.miniBack);
            body.add("mini", m);
        }
        return body;
    }

    public static void publish() {
        String url = base();
        JsonObject body = ownProfileJson();
        EXEC.execute(() -> {
            try {
                status = "Connecting…";
                if (!gg.aurora.Friends.ensureSession()) {
                    status = gg.aurora.Friends.status;
                    return;
                }
                HttpRequest req = HttpRequest.newBuilder(URI.create(url + "/v2/profile"))
                        .timeout(Duration.ofSeconds(10))
                        .header("content-type", "application/json")
                        .header("authorization", "Bearer " + gg.aurora.Friends.token())
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                        .build();
                HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 401) Settings.socialToken = "";
                status = res.statusCode() == 200 ? "Connected • profile published" : "Refused (" + res.statusCode() + ")";
            } catch (Exception e) {
                status = "Offline";
                AuroraClient.LOGGER.warn("Profil : publication impossible : {}", e.toString());
            }
        });
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static void lookup(List<UUID> ids) {
        if (!busy.compareAndSet(false, true)) return;
        String url = base();
        EXEC.execute(() -> {
            try {
                JsonObject body = new JsonObject();
                JsonArray arr = new JsonArray();
                for (UUID id : ids) arr.add(id.toString().replace("-", ""));
                body.add("uuids", arr);
                HttpRequest req = HttpRequest.newBuilder(URI.create(url + "/v2/lookup"))
                        .timeout(Duration.ofSeconds(10))
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                        .build();
                HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                long now = System.currentTimeMillis();
                if (res.statusCode() == 200) {
                    JsonObject profiles = GSON.fromJson(res.body(), JsonObject.class).getAsJsonObject("profiles");
                    for (UUID id : ids) {
                        String key = id.toString().replace("-", "");
                        Profile p = null;
                        if (profiles != null && profiles.has(key)) {
                            JsonObject o = profiles.getAsJsonObject(key);
                            NametagStyle st = o.has("style") ? NametagStyle.fromJson(o.getAsJsonObject("style")) : null;
                            String discord = o.has("discord") && o.get("discord").isJsonObject() ? str(o.getAsJsonObject("discord"), "name") : "";
                            p = new Profile(st, str(o, "cape"), str(o, "wings"), str(o, "aura"), str(o, "halo"), str(o, "pet"),
                                    str(o, "shield"), str(o, "trail"), discord, str(o, "back"),
                                    Mini.from(o.has("mini") && o.get("mini").isJsonObject() ? o.getAsJsonObject("mini") : null));
                        }
                        CACHE.put(id, new Entry(p, now));
                    }
                }
            } catch (Exception e) {
                AuroraClient.LOGGER.debug("Profils : recherche impossible : {}", e.toString());
            } finally {
                busy.set(false);
            }
        });
    }

    /** Vide le cache (changement de serveur, d'URL…). */
    public static void reset() {
        CACHE.clear();
        startupPublished = false;
    }
}
