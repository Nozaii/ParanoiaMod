package gg.aurora;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import gg.aurora.module.HudModule;
import gg.aurora.module.Module;
import gg.aurora.module.Modules;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Sauvegarde config/aurora.json : état on/off et position de chaque module + paramètres. */
public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("aurora.json");

    private Config() {}

    public static Path file() {
        return FILE;
    }

    private static final Path BACKUP = FILE.resolveSibling("aurora.json.bak");

    public static void load() {
        if (!Files.exists(FILE)) {
            if (Files.exists(BACKUP)) loadFrom(BACKUP); // fichier perdu : on reprend la dernière copie saine
            return;
        }
        if (!loadFrom(FILE)) {
            // fichier illisible (coupure, disque plein…) : on le garde de côté et on reprend la copie saine,
            // au lieu de tout remettre par défaut puis d'écraser
            try {
                Files.copy(FILE, FILE.resolveSibling("aurora.json.broken-" + System.currentTimeMillis()));
            } catch (Exception ignored) {
            }
            if (Files.exists(BACKUP)) loadFrom(BACKUP);
            return;
        }
        try {
            Files.copy(FILE, BACKUP, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
        repair();
    }

    /** One time per install: performance defaults that older configs stored as off. */
    private static void repair() {
        if (Settings.migrated >= 186) return;
        Settings.migrated = 186;
        for (String id : new String[]{"hud_cache", "smooth_scroll"}) {
            var m = gg.aurora.module.Modules.byId(id);
            if (m != null) m.enabled = true;
        }
    }

    private static boolean loadFrom(Path file) {
        try (Reader r = Files.newBufferedReader(file)) {
            JsonObject root = GSON.fromJson(r, JsonObject.class);
            if (root == null) return false;
            if (root.has("settings")) readSettings(root.getAsJsonObject("settings"));
            if (root.has("boost")) Boost.read(root.getAsJsonObject("boost"));
            if (root.has("friends")) Friends.read(root.getAsJsonArray("friends"));
            for (Module m : Modules.ALL) {
                if (!root.has(m.id) || !root.get(m.id).isJsonObject()) continue;
                try {
                JsonObject o = root.getAsJsonObject(m.id);
                if (o.has("enabled")) m.enabled = o.get("enabled").getAsBoolean();
                if (o.has("key")) m.key = o.get("key").getAsInt();
                if (o.has("opts")) {
                    JsonObject op = o.getAsJsonObject("opts");
                    for (String k : op.keySet()) {
                        try {
                            m.opts.put(k, op.get(k).getAsDouble());
                        } catch (Exception ignored) {
                        }
                    }
                }
                m.toggleAnim = m.enabled ? 1f : 0f;
                if (m instanceof HudModule h) {
                    if (o.has("x")) h.x = clamp(o.get("x").getAsFloat());
                    if (o.has("y")) h.y = clamp(o.get("y").getAsFloat());
                    if (o.has("scale")) h.scale = Math.max(0.5f, Math.min(2f, o.get("scale").getAsFloat()));
                    if (o.has("icon")) h.showIcon = o.get("icon").getAsBoolean();
                    if (o.has("label")) h.showLabel = o.get("label").getAsBoolean();
                    if (o.has("bg")) h.showBg = o.get("bg").getAsBoolean();
                    if (o.has("color")) h.colorMode = o.get("color").getAsInt();
                }
                } catch (Exception e) {
                    // une entrée abîmée ne fait pas perdre les autres
                    AuroraClient.LOGGER.warn("Module ignoré dans la config : {}", m.id);
                }
            }
            return true;
        } catch (Exception e) {
            AuroraClient.LOGGER.warn("Impossible de lire {}", file, e);
            return false;
        }
    }

    /** Écrit depuis le rendu et depuis le thread social : un seul à la fois, fichier remplacé d'un bloc. */
    public static void save() {
        var client = net.minecraft.client.Minecraft.getInstance();
        if (client != null && !client.isSameThread()) { // social thread: maps are render-thread only
            client.execute(Config::save);
            return;
        }
        JsonObject root = new JsonObject();
        for (Module m : Modules.ALL) {
            JsonObject o = new JsonObject();
            o.addProperty("enabled", m.enabled || gg.aurora.module.PerfModules.pausedByAdaptive(m) || gg.aurora.FairPlay.restoring(m));
            if (m.key >= 0) o.addProperty("key", m.key);
            if (!m.opts.isEmpty()) {
                JsonObject op = new JsonObject();
                m.opts.forEach(op::addProperty);
                o.add("opts", op);
            }
            if (m instanceof HudModule h) {
                o.addProperty("x", h.x);
                o.addProperty("y", h.y);
                o.addProperty("scale", h.scale);
                o.addProperty("icon", h.showIcon);
                o.addProperty("label", h.showLabel);
                o.addProperty("bg", h.showBg);
                o.addProperty("color", h.colorMode);
            }
            root.add(m.id, o);
        }
        JsonObject settings = writeSettings();
        // mode adaptatif : on enregistre la qualité choisie, pas celle baissée temporairement
        int q = gg.aurora.module.PerfModules.userQuality();
        if (q >= 0) settings.addProperty("cosmeticQuality", q);
        root.add("settings", settings);
        root.add("boost", Boost.write());
        root.add("friends", Friends.write());
        // the disk write (antivirus included) never runs on the render thread; bursts collapse into one write
        pending = root;
        if (!writerStarted) {
            writerStarted = true;
            Thread t = new Thread(Config::writer, "Paranoia-Config");
            t.setDaemon(true);
            t.start();
            Runtime.getRuntime().addShutdownHook(new Thread(Config::flush, "Paranoia-Config-flush"));
        }
    }

    private static volatile JsonObject pending;
    private static boolean writerStarted;

    private static void writer() {
        while (true) {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                return;
            }
            flush();
        }
    }

    /** Writes the latest snapshot now, if any. */
    public static synchronized void flush() {
        JsonObject root = pending;
        if (root == null) return;
        pending = null;
        try {
            Files.createDirectories(FILE.getParent());
            Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp)) {
                GSON.toJson(root, w);
            }
            try {
                Files.move(tmp, FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            AuroraClient.LOGGER.warn("Impossible d'écrire {}", FILE, e);
        }
    }

    private static Iterable<Field> settingFields() {
        java.util.List<Field> out = new java.util.ArrayList<>();
        for (Field f : Settings.class.getFields()) if (Modifier.isStatic(f.getModifiers())) out.add(f);
        return out;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void readSettings(JsonObject o) {
        for (Field f : settingFields()) {
            if (!o.has(f.getName())) continue;
            try {
                var v = o.get(f.getName());
                Class<?> t = f.getType();
                if (t == boolean.class) f.setBoolean(null, v.getAsBoolean());
                else if (t == float.class) f.setFloat(null, v.getAsFloat());
                else if (t == int.class) f.setInt(null, v.getAsInt());
                else if (t == double.class) f.setDouble(null, v.getAsDouble());
                else if (t == long.class) f.setLong(null, v.getAsLong());
                else if (t == String.class) f.set(null, v.getAsString());
                else if (t.isEnum()) f.set(null, Enum.valueOf((Class) t, v.getAsString()));
            } catch (Exception e) {
                AuroraClient.LOGGER.warn("Paramètre ignoré : {}", f.getName());
            }
        }
    }

    private static JsonObject writeSettings() {
        JsonObject o = new JsonObject();
        for (Field f : settingFields()) {
            try {
                Object v = f.get(null);
                if (v instanceof Boolean b) o.addProperty(f.getName(), b);
                else if (v instanceof Number n) o.addProperty(f.getName(), n);
                else if (v instanceof Enum<?> e) o.addProperty(f.getName(), e.name());
                else if (v != null) o.addProperty(f.getName(), v.toString());
            } catch (IllegalAccessException ignored) {
            }
        }
        return o;
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
