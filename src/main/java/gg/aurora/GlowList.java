package gg.aurora;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Friend Glow : joueurs à faire briller, par adresse de serveur (config/aurora-friendglow.json). */
public final class GlowList {
    private GlowList() {}

    private static final Gson GSON = new Gson();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("aurora-friendglow.json");
    /** serveur (minuscules) → pseudos (minuscules), dans l'ordre saisi. */
    private static Map<String, Set<String>> lists;

    /** Clé du serveur courant : adresse sans port par défaut, « singleplayer » en solo. */
    public static String server() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getCurrentServer() != null) {
            String ip = mc.getCurrentServer().ip.trim().toLowerCase(Locale.ROOT);
            return ip.endsWith(":25565") ? ip.substring(0, ip.length() - 6) : ip;
        }
        return "singleplayer";
    }

    private static Map<String, Set<String>> lists() {
        if (lists == null) {
            lists = new LinkedHashMap<>();
            try {
                if (Files.exists(FILE)) {
                    Map<String, Set<String>> read = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8),
                            new TypeToken<LinkedHashMap<String, LinkedHashSet<String>>>() {}.getType());
                    if (read != null) lists.putAll(read);
                }
            } catch (Exception e) {
                AuroraClient.LOGGER.warn("Friend Glow : liste illisible ({})", e.toString());
            }
        }
        return lists;
    }

    public static boolean has(String name) {
        Set<String> s = lists().get(server());
        return s != null && name != null && s.contains(name.toLowerCase(Locale.ROOT));
    }

    /** Liste du serveur courant, séparée par des virgules (champ texte des réglages). */
    public static String text() {
        Set<String> s = lists().get(server());
        return s == null ? "" : String.join(", ", s);
    }

    public static void setText(String raw) {
        Set<String> names = new LinkedHashSet<>();
        for (String n : raw.split("[,;\s]+")) if (!n.isBlank()) names.add(n.trim().toLowerCase(Locale.ROOT));
        if (names.isEmpty()) lists().remove(server());
        else lists().put(server(), names);
        save();
    }

    private static void save() {
        try {
            Files.writeString(FILE, GSON.toJson(lists), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AuroraClient.LOGGER.warn("Friend Glow : sauvegarde impossible ({})", e.toString());
        }
    }
}
