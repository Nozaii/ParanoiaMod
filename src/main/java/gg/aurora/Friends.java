package gg.aurora;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import gg.aurora.nametag.NameStyler;
import gg.aurora.nametag.NametagSync;
import gg.aurora.ui.Icons;
import gg.aurora.ui.Toasts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Système d'amis en ligne (serveur Cloudflare, voir server/src/social.js) : demandes avec
 * notification, acceptation / refus, présence en ligne et serveur actuel, messages privés.
 * L'identité est prouvée par Mojang une fois, puis un jeton de session est utilisé.
 */
public final class Friends {
    private Friends() {}

    public record Friend(UUID id, String name, boolean online, String server, long lastSeen, String discord) {}

    /** Nom Discord lié à mon compte ("" = non lié). */
    public static volatile String myDiscord = "";
    /** Mon UUID côté serveur social (celui de la session), pour reconnaître ma propre page. */
    public static volatile java.util.UUID myId;

    public record Request(UUID id, String name, long created) {}

    public record Message(UUID friend, boolean mine, String text, long created) {}

    private static final Gson GSON = new Gson();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Paranoia-Social");
        t.setDaemon(true);
        return t;
    });

    private static volatile List<Friend> friends = List.of();
    private static volatile List<Request> incoming = List.of();
    private static volatile List<Request> outgoing = List.of();
    private static final Map<UUID, List<Message>> CHATS = new HashMap<>();
    private static final Map<UUID, Integer> UNREAD = new HashMap<>();
    private static long lastMessageId;
    private static boolean firstSync = true;
    private static volatile boolean busy;
    private static int ticks, syncCount;
    private static String lastServer = "";
    private static long nextAuthTry;

    /** État affiché dans la page Amis. */
    public static volatile String status = "Connecting…";
    public static volatile boolean connected;

    // ======================================================== lecture

    public static List<Friend> all() {
        return friends;
    }

    public static List<Request> incoming() {
        return incoming;
    }

    public static List<Request> outgoing() {
        return outgoing;
    }

    private static final Map<UUID, java.util.function.Supplier<net.minecraft.world.entity.player.PlayerSkin>> SKINS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<UUID> SKIN_ASKED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Head of an offline friend: profile fetched once in the background, then the skin is cached. */
    public static net.minecraft.world.entity.player.PlayerSkin skin(UUID id, String name) {
        var s = SKINS.get(id);
        if (s != null) return s.get();
        if (SKIN_ASKED.add(id)) {
            var mc = net.minecraft.client.Minecraft.getInstance();
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    var res = mc.services().sessionService().fetchProfile(id, false);
                    if (res != null) SKINS.put(id, mc.getSkinManager().createLookup(res.profile(), false));
                } catch (RuntimeException ignored) {
                    // no head for this friend: the letter stays
                }
            });
        }
        return null;
    }

    public static boolean isFriend(UUID id) {
        for (Friend f : friends) if (f.id().equals(id)) return true;
        return false;
    }

    public static Friend get(UUID id) {
        for (Friend f : friends) if (f.id().equals(id)) return f;
        return null;
    }

    public static synchronized List<Message> chat(UUID id) {
        return List.copyOf(CHATS.getOrDefault(id, List.of()));
    }

    public static synchronized int unread(UUID id) {
        return UNREAD.getOrDefault(id, 0);
    }

    public static synchronized void markRead(UUID id) {
        UNREAD.remove(id);
    }

    public static synchronized int totalUnread() {
        int n = 0;
        for (int v : UNREAD.values()) n += v;
        return n;
    }

    // ======================================================== réseau

    private static String base() {
        String u = Settings.apiUrl.trim();
        if (u.isEmpty()) u = NametagSync.DEFAULT_API;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private static UUID uuid(String raw) {
        return UUID.fromString(raw.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"));
    }

    private static String raw(UUID id) {
        return id.toString().replace("-", "");
    }

    record Res(int code, JsonObject body) {
        String message() {
            return body != null && body.has("message") ? body.get("message").getAsString() : "Error " + code;
        }
    }

    static Res http(String method, String path, JsonObject body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(12))
                .header("content-type", "application/json");
        if (!Settings.socialToken.isEmpty()) b.header("authorization", "Bearer " + Settings.socialToken);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonObject o = null;
        try {
            o = GSON.fromJson(r.body(), JsonObject.class);
        } catch (Exception ignored) {
        }
        if (r.statusCode() == 401 && !path.startsWith("/v2/auth")) {
            Settings.socialToken = "";
            connected = false;
        }
        return new Res(r.statusCode(), o);
    }

    /**
     * Garantit une session valide (appelable depuis n'importe quel thread réseau).
     * Mojang bloquant les appels venant de Cloudflare, l'identité est prouvée avec la clé de
     * profil Mojang du joueur (celle qui signe le chat) : le serveur vérifie la signature de
     * Mojang sur cette clé, puis notre signature d'un défi à usage unique.
     */
    /** Verrou de l'authentification seule : jamais celui des messages (lus à chaque image par les menus). */
    private static final Object AUTH_LOCK = new Object();

    public static boolean ensureSession() throws Exception {
        if (!Settings.socialToken.isEmpty()) return true;
        synchronized (AUTH_LOCK) {
            if (!Settings.socialToken.isEmpty()) return true;
            return authenticate();
        }
    }

    private static boolean authenticate() throws Exception {
        Minecraft mc = Minecraft.getInstance();
        User user = mc.getUser();
        JsonObject body = new JsonObject();
        String dev = System.getProperty("aurora.devauth", "");
        Res r;
        if (!dev.isEmpty()) {
            String[] p = dev.split(":");
            String key = p[0];
            // -Daurora.devauth=uuid:name -Daurora.devauthfile=<.dev.vars> : la clé est lue dans le fichier, jamais sur la ligne de commande
            String file = System.getProperty("aurora.devauthfile", "");
            if (p.length == 2 && !file.isEmpty()) {
                for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of(file))) {
                    if (line.startsWith("DEV_AUTH_KEY=")) key = line.substring("DEV_AUTH_KEY=".length()).trim().replace("\"", "");
                }
                p = new String[]{key, p[0], p[1]};
            }
            body.addProperty("key", key);
            body.addProperty("uuid", p[1]);
            body.addProperty("name", p[2]);
            r = http("POST", "/v2/auth/dev", body);
        } else {
            ProfileKeys keys = ProfileKeys.get(mc);
            if (keys == null) {
                status = "Mojang profile key unavailable";
                return false;
            }
            Res ch = http("GET", "/v2/challenge", null);
            if (ch.code() != 200) {
                status = ch.message();
                return false;
            }
            String nonce = ch.body().get("nonce").getAsString();
            String uuid = raw(user.getProfileId());
            java.util.Base64.Encoder b64 = java.util.Base64.getEncoder();
            body.addProperty("uuid", uuid);
            body.addProperty("name", user.getName());
            body.addProperty("nonce", nonce);
            body.addProperty("expiresAt", keys.expiresAt());
            body.addProperty("publicKey", b64.encodeToString(keys.publicKeyDer()));
            body.addProperty("keySignature", b64.encodeToString(keys.mojangSignature()));
            body.addProperty("signature", b64.encodeToString(keys.sign(("paranoia:" + nonce + ":" + uuid).getBytes(StandardCharsets.UTF_8))));
            r = http("POST", "/v2/auth/key", body);
        }
        if (r.code() != 200) {
            status = r.message();
            AuroraClient.LOGGER.warn("Social : connexion refusée ({}) : {}", r.code(), r.message());
            return false;
        }
        Settings.socialToken = r.body().get("token").getAsString();
        Config.save();
        status = "Connected";
        return true;
    }

    private static String discordName(JsonObject o) {
        if (!o.has("discord") || !o.get("discord").isJsonObject()) return "";
        JsonObject d = o.getAsJsonObject("discord");
        return d.has("name") && d.get("name").isJsonPrimitive() ? d.get("name").getAsString() : "";
    }

    // ======================================================== Discord

    public static volatile String discordStatus = "";

    /** Ouvre la page d'autorisation Discord dans le navigateur ; le serveur lie le compte au retour. */
    public static void linkDiscord() {
        discordStatus = "Opening Discord…";
        EXEC.execute(() -> {
            try {
                if (!ensureSession()) {
                    discordStatus = status.isEmpty() ? "Sign-in failed: restart from the launcher" : status;
                    Toasts.push(Icons.INFO, "Discord", discordStatus, true);
                    return;
                }
                Res r = http("POST", "/v2/discord/start", new JsonObject());
                if (r.code() == 401 && ensureSession()) r = http("POST", "/v2/discord/start", new JsonObject());
                if (r.code() != 200 || r.body() == null || !r.body().has("url")) {
                    discordStatus = r.message();
                    Toasts.push(Icons.INFO, "Discord", discordStatus, true);
                    return;
                }
                String url = r.body().get("url").getAsString();
                if (!url.startsWith("https://discord.com/")) {
                    discordStatus = "Link refused";
                    return;
                }
                net.minecraft.util.Util.getPlatform().openUri(url);
                discordStatus = "Authorize in the browser, then come back here";
                Toasts.push(Icons.FRIENDS, "Discord", discordStatus, false);
            } catch (Exception e) {
                discordStatus = "Offline";
                Toasts.push(Icons.INFO, "Discord", "Could not reach the server: " + e.getClass().getSimpleName(), true);
            }
        });
    }

    public static void unlinkDiscord() {
        EXEC.execute(() -> {
            try {
                if (!ensureSession()) return;
                Res r = http("POST", "/v2/discord/unlink", new JsonObject());
                if (r.code() == 200) {
                    myDiscord = "";
                    discordStatus = "Discord account unlinked";
                }
            } catch (Exception ignored) {
                discordStatus = "Offline";
            }
        });
    }

    // ======================================================== état en direct (écrit / emote)

    /** Publie mon état : en train d'écrire, ou emote lancée ("" = aucune). */
    public static void sendLive(boolean typing, String emote) {
        if (Settings.socialToken.isEmpty()) return;
        JsonObject b = new JsonObject();
        b.addProperty("typing", typing);
        if (emote != null && !emote.isEmpty()) b.addProperty("emote", emote);
        EXEC.execute(() -> {
            try {
                http("POST", "/v2/live", b);
            } catch (Exception ignored) {
                // pas grave : l'état est éphémère
            }
        });
    }

    /** Demande l'état en direct de plusieurs joueurs (appel bloquant, à faire hors du thread de rendu). */
    public static JsonObject fetchLive(java.util.Collection<UUID> ids) throws Exception {
        JsonObject b = new JsonObject();
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (UUID id : ids) arr.add(id.toString().replace("-", ""));
        b.add("uuids", arr);
        Res r = http("POST", "/v2/live/who", b);
        return r.code() == 200 ? r.body() : null;
    }

    /** Pour la publication du nametag (même session). */
    public static String token() {
        return Settings.socialToken;
    }

    public static String api() {
        return base();
    }

    private static boolean loginNotice;
    private static final List<Message> offlineMessages = new ArrayList<>();
    private static final Map<Message, Long> MESSAGE_IDS = new HashMap<>();

    private static boolean lastSeenBefore(Message m, long seen) {
        Long id = MESSAGE_IDS.get(m);
        return id != null && id > seen;
    }
    private static int inWorldTicks;

    public static void tick(Minecraft mc) {
        ticks++;
        inWorldTicks = mc.player == null ? 0 : inWorldTicks + 1;
        if (loginNotice && inWorldTicks > 60) {
            loginNotice = false;
            if (Settings.notifyRequests && !incoming.isEmpty()) {
                Toasts.push(Icons.FRIENDS, incoming.size() == 1 ? "1 friend request" : incoming.size() + " friend requests",
                        "Waiting for your answer", true);
                for (Request r : incoming) requestMessage(r);
            }
            if (Settings.notifyMessages && !offlineMessages.isEmpty()) {
                Toasts.push(Icons.LIST, offlineMessages.size() == 1 ? "1 new message" : offlineMessages.size() + " new messages",
                        "Received while you were away", true);
                int from = Math.max(0, offlineMessages.size() - 5);
                for (Message m : offlineMessages.subList(from, offlineMessages.size())) messageLine(m);
            }
            offlineMessages.clear();
        }
        // the full sync is heavy: new messages come through a small dedicated request
        int fast = mc.screen instanceof gg.aurora.ui.FriendsScreen ? 30 : 80; // 1.5 s in the chat, 4 s in game
        if (ticks % fast == 0) inbox();
        String server = currentServer(mc);
        boolean serverChanged = !server.equals(lastServer);
        int every = System.getProperty("aurora.devauth") != null ? 40 : 200; // 10 s (2 s en test)
        if (!serverChanged && ticks % every != 0 && ticks != 40) return;
        lastServer = server;
        sync(serverChanged || syncCount % 6 == 0);
    }

    private static String currentServer(Minecraft mc) {
        if (!Settings.shareServer || mc.getCurrentServer() == null || mc.isSingleplayer()) return "";
        return mc.getCurrentServer().ip;
    }

    /** Synchronise amis, demandes et messages ; presence = met aussi à jour "en ligne" + serveur. */
    public static void sync(boolean presence) {
        if (busy) return;
        busy = true;
        Minecraft mc = Minecraft.getInstance();
        String server = currentServer(mc);
        syncCount++;
        EXEC.execute(() -> {
            try {
                if (Settings.socialToken.isEmpty()) {
                    if (System.currentTimeMillis() < nextAuthTry) return;
                    if (!ensureSession()) {
                        nextAuthTry = System.currentTimeMillis() + 60_000;
                        return;
                    }
                }
                String q = "/v2/sync?since=" + lastMessageId + (presence ? "&presence=1&server="
                        + URLEncoder.encode(server, StandardCharsets.UTF_8) : "");
                Res r = http("GET", q, null);
                if (r.code() != 200) {
                    status = r.code() == 401 ? "Reconnecting…" : r.message();
                    return;
                }
                JsonObject o = r.body();
                if (o.has("me") && o.get("me").isJsonObject()) {
                    JsonObject me = o.getAsJsonObject("me");
                    myDiscord = discordName(me);
                    if (me.has("uuid")) {
                        String u = me.get("uuid").getAsString().replace("-", "");
                        if (u.length() == 32) myId = java.util.UUID.fromString(u.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
                    }
                }
                List<Friend> fr = new ArrayList<>();
                for (JsonElement e : o.getAsJsonArray("friends")) {
                    JsonObject f = e.getAsJsonObject();
                    fr.add(new Friend(uuid(f.get("uuid").getAsString()), f.get("name").getAsString(),
                            f.get("online").getAsBoolean(), f.get("server").isJsonNull() ? null : f.get("server").getAsString(),
                            f.get("lastSeen").getAsLong(), discordName(f)));
                }
                List<Request> in = requests(o.getAsJsonArray("incoming"));
                List<Request> out = requests(o.getAsJsonArray("outgoing"));
                List<Message> fresh = new ArrayList<>();
                for (JsonElement e : o.getAsJsonArray("messages")) {
                    JsonObject m = e.getAsJsonObject();
                    lastMessageId = Math.max(lastMessageId, m.get("id").getAsLong());
                    Message msg = new Message(uuid(m.get("from").getAsString()), false, m.get("text").getAsString(), m.get("created").getAsLong());
                    MESSAGE_IDS.put(msg, m.get("id").getAsLong());
                    fresh.add(msg);
                }
                mc.execute(() -> apply(fr, in, out, fresh));
                connected = true;
                status = "Connected";
                PlayerPages.reportSkin();
            } catch (Exception e) {
                status = "Offline";
                AuroraClient.LOGGER.warn("Social : {}", e.toString());
            } finally {
                busy = false;
            }
        });
    }

    private static volatile boolean inboxBusy;

    private static void inbox() {
        if (inboxBusy || firstSync || Settings.socialToken.isEmpty()) return;
        inboxBusy = true;
        long since = lastMessageId;
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                Res r = http("GET", "/v2/inbox?since=" + since, null);
                if (r.code() != 200 || r.body() == null) return;
                List<Message> fresh = new ArrayList<>();
                for (JsonElement e : r.body().getAsJsonArray("messages")) {
                    JsonObject m = e.getAsJsonObject();
                    long id = m.get("id").getAsLong();
                    if (id <= lastMessageId) continue;
                    lastMessageId = id;
                    Message msg = new Message(uuid(m.get("from").getAsString()), false, m.get("text").getAsString(), m.get("created").getAsLong());
                    MESSAGE_IDS.put(msg, id);
                    fresh.add(msg);
                }
                if (!fresh.isEmpty()) Minecraft.getInstance().execute(() -> apply(friends, incoming, outgoing, fresh));
            } catch (Exception e) {
                AuroraClient.LOGGER.debug("Inbox: {}", e.toString());
            } finally {
                inboxBusy = false;
            }
        });
    }

    private static final Set<UUID> HISTORY = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Past messages of a conversation, both ways: fetched once per session, when it opens. */
    public static void loadHistory(UUID id) {
        if (!HISTORY.add(id)) return;
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                if (!ensureSession()) {
                    HISTORY.remove(id);
                    return;
                }
                Res r = http("GET", "/v2/messages/history?with=" + raw(id), null);
                if (r.code() != 200 || r.body() == null) {
                    HISTORY.remove(id);
                    return;
                }
                List<Message> past = new ArrayList<>();
                for (JsonElement e : r.body().getAsJsonArray("messages")) {
                    JsonObject m = e.getAsJsonObject();
                    past.add(new Message(id, m.get("mine").getAsBoolean(), m.get("text").getAsString(), m.get("created").getAsLong()));
                }
                synchronized (Friends.class) {
                    List<Message> chat = CHATS.computeIfAbsent(id, k -> new ArrayList<>());
                    for (Message local : chat) {
                        boolean known = false;
                        for (Message p : past) {
                            if (p.mine() == local.mine() && p.text().equals(local.text()) && Math.abs(p.created() - local.created()) < 120_000) {
                                known = true;
                                break;
                            }
                        }
                        if (!known) past.add(local);
                    }
                    past.sort(java.util.Comparator.comparingLong(Message::created));
                    chat.clear();
                    chat.addAll(past);
                }
            } catch (Exception e) {
                HISTORY.remove(id);
            }
        });
    }

    private static List<Request> requests(JsonArray arr) {
        List<Request> out = new ArrayList<>();
        for (JsonElement e : arr) {
            JsonObject r = e.getAsJsonObject();
            out.add(new Request(uuid(r.get("uuid").getAsString()), r.get("name").getAsString(), r.get("created").getAsLong()));
        }
        return out;
    }

    /** Applique la synchro sur le thread du jeu et déclenche les notifications. */
    private static void apply(List<Friend> fr, List<Request> in, List<Request> out, List<Message> fresh) {
        boolean notify = !firstSync;
        Set<UUID> oldIn = new HashSet<>(), oldFriends = new HashSet<>(), wasOnline = new HashSet<>(), oldOut = new HashSet<>();
        for (Request r : incoming) oldIn.add(r.id());
        for (Request r : outgoing) oldOut.add(r.id());
        for (Friend f : friends) {
            oldFriends.add(f.id());
            if (f.online()) wasOnline.add(f.id());
        }
        friends = List.copyOf(fr);
        incoming = List.copyOf(in);
        outgoing = List.copyOf(out);
        synchronized (Friends.class) {
            for (Message m : fresh) {
                CHATS.computeIfAbsent(m.friend(), k -> new ArrayList<>()).add(m);
                if (notify) UNREAD.merge(m.friend(), 1, Integer::sum);
            }
        }
        boolean first = firstSync;
        firstSync = false;
        long seen = 0;
        try {
            seen = Long.parseLong(Settings.socialLastMessage);
        } catch (NumberFormatException ignored) {
        }
        if (lastMessageId > seen) {
            Settings.socialLastMessage = String.valueOf(lastMessageId);
            Config.save();
        }
        if (first) {
            // demandes et messages reçus hors ligne : annoncés dès que le joueur est en jeu
            loginNotice = !in.isEmpty();
            offlineMessages.clear();
            synchronized (Friends.class) {
                for (Message m : fresh) {
                    if (m.created() > 0 && lastSeenBefore(m, seen)) {
                        offlineMessages.add(m);
                        UNREAD.merge(m.friend(), 1, Integer::sum);
                    }
                }
            }
            loginNotice |= !offlineMessages.isEmpty();
            return;
        }

        for (Request r : in) {
            if (!oldIn.contains(r.id()) && Settings.notifyRequests) {
                Toasts.push(Icons.FRIENDS, "Friend request", r.name() + " wants to add you", true);
                requestMessage(r);
            }
        }
        for (Friend f : fr) {
            if (!oldFriends.contains(f.id()) && oldOut.contains(f.id())) {
                Toasts.push(Icons.CHECK, "Request accepted", f.name() + " is now your friend", false);
                chat(Component.empty().append(prefix()).append(Component.literal(f.name() + " accepted your friend request")
                        .withStyle(Style.EMPTY.withColor(0x5EE6A8))));
            } else if (oldFriends.contains(f.id()) && f.online() && !wasOnline.contains(f.id()) && Settings.friendNotify) {
                Toasts.push(Icons.FRIENDS, f.name() + " is online", f.server() != null ? "Sur " + f.server() : "Connected to the game", false);
            }
        }
        if (Settings.notifyMessages) {
            for (Message m : fresh) {
                // conversation déjà ouverte à l'écran : le message s'affiche là, pas de notification
                if (watching(m.friend())) continue;
                Friend f = get(m.friend());
                Toasts.push(Icons.LIST, f == null ? "Message" : f.name(),
                        gg.aurora.module.ClientModules.hidePm() ? "Message hidden (stream mode)" : shown(m.text()), true);
                messageLine(m);
            }
        }
    }

    /** Conversation affichée en ce moment (mise à jour à chaque image par la page Amis). */
    public static volatile UUID viewing;
    public static volatile long viewingAt;

    /** Vrai si la conversation avec cet ami est à l'écran (dessinée il y a moins d'une demi-seconde). */
    public static boolean watching(UUID id) {
        return id != null && id.equals(viewing) && System.currentTimeMillis() - viewingAt < 500;
    }

    /** Texte affiché hors de la conversation : une image envoyée se lit « Sent a picture », pas son identifiant. */
    public static String shown(String text) {
        return Media.imageId(text) != null ? "Sent a picture" : text;
    }

    private static void messageLine(Message m) {
        Friend f = get(m.friend());
        String name = f == null ? "Friend" : f.name();
        chat(Component.empty().append(prefix())
                .append(Component.literal("» " + name + " : ").withStyle(Style.EMPTY.withColor(0xB07CFF)))
                .append(Component.literal(shown(m.text())).withStyle(Style.EMPTY.withColor(0xF3F1FB)))
                .append(Component.literal("  "))
                .append(button("[Reply]", 0x9D7CFF, "/paranoia friends", "Open the conversation")));
    }

    private static void requestMessage(Request r) {
        chat(Component.empty().append(prefix())
                .append(Component.literal(r.name()).withStyle(Style.EMPTY.withColor(0xF5B54A)))
                .append(Component.literal(" sent you a friend request  ").withStyle(Style.EMPTY.withColor(0xBDB8D6)))
                .append(button("[Accept]", 0x5EE6A8, "/paranoia accept " + r.name(), "Become friends with " + r.name()))
                .append(Component.literal(" "))
                .append(button("[Ignore]", 0xF2545B, "/paranoia ignore " + r.name(), "Decline request")));
    }

    private static MutableComponent prefix() {
        return Component.empty().append(NameStyler.logo());
    }

    private static MutableComponent button(String label, int color, String command, String hover) {
        return Component.literal(label).withStyle(Style.EMPTY.withColor(color).withBold(true)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    private static void chat(Component c) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui != null && mc.player != null) mc.gui.getChat().addMessage(c);
    }

    // ======================================================== actions

    private static void action(String path, JsonObject body, Consumer<String> done) {
        EXEC.execute(() -> {
            String err = null;
            try {
                if (!ensureSession()) err = status;
                else {
                    Res r = http("POST", path, body);
                    if (r.code() != 200) err = r.message();
                }
            } catch (Exception e) {
                err = "Server unreachable";
            }
            String e2 = err;
            Minecraft.getInstance().execute(() -> {
                done.accept(e2);
                sync(false);
            });
        });
    }

    /** Envoie une demande d'ami (le joueur doit avoir le mod). */
    public static void add(String name, Consumer<String> done) {
        String n = name.trim();
        if (!n.matches("[A-Za-z0-9_]{2,16}")) {
            done.accept("Invalid username");
            return;
        }
        JsonObject b = new JsonObject();
        b.addProperty("name", n);
        action("/v2/friends/request", b, done);
    }

    public static void respond(UUID from, boolean accept, Consumer<String> done) {
        JsonObject b = new JsonObject();
        b.addProperty("uuid", raw(from));
        b.addProperty("accept", accept);
        action("/v2/friends/respond", b, done);
    }

    /** Retire un ami, ou annule une demande envoyée. */
    public static void remove(UUID id) {
        JsonObject b = new JsonObject();
        b.addProperty("uuid", raw(id));
        synchronized (Friends.class) {
            CHATS.remove(id);
            UNREAD.remove(id);
        }
        action("/v2/friends/remove", b, e -> {});
    }

    public static void send(UUID to, String text, Consumer<String> done) {
        String t = text.trim();
        if (t.isEmpty()) return;
        JsonObject b = new JsonObject();
        b.addProperty("to", raw(to));
        b.addProperty("text", t);
        synchronized (Friends.class) {
            CHATS.computeIfAbsent(to, k -> new ArrayList<>()).add(new Message(to, true, t, System.currentTimeMillis()));
        }
        action("/v2/messages", b, done);
    }

    /** Rejoint le serveur où joue un ami (s'il le partage). */
    public static void join(Friend f) {
        if (f.server() == null) return;
        Minecraft mc = Minecraft.getInstance();
        String ip = f.server();
        if (mc.level != null) mc.disconnectFromWorld(Component.literal("Join " + f.name()));
        ServerData data = new ServerData(f.name(), ip, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), mc, ServerAddress.parseString(ip), data, false, null);
    }

    /** Pour les commandes /paranoia accept|ignore <pseudo>. */
    public static Request findIncoming(String name) {
        for (Request r : incoming) if (r.name().equalsIgnoreCase(name)) return r;
        return null;
    }

    public static Friend findFriend(String name) {
        for (Friend f : friends) if (f.name().equalsIgnoreCase(name)) return f;
        return null;
    }

    public static String lastSeen(Friend f) {
        if (f.online()) return f.server() != null ? "Online · " + f.server() : "Online";
        if (f.lastSeen() <= 0) return "Offline";
        long m = (System.currentTimeMillis() - f.lastSeen()) / 60000;
        if (m < 60) return "Vu il y a " + Math.max(1, m) + " min";
        if (m < 1440) return "Vu il y a " + m / 60 + " h";
        return "Vu il y a " + m / 1440 + " j";
    }

    public static String time(long ms) {
        return String.format(Locale.ROOT, "%tH:%<tM", ms);
    }

    // l'ancienne liste locale n'est plus utilisée (tout est sur le serveur)
    static JsonArray write() {
        return new JsonArray();
    }

    static void read(JsonArray arr) {}
}
