package gg.aurora.module;

import gg.aurora.AuroraClient;
import gg.aurora.Config;
import gg.aurora.Settings;
import gg.aurora.ui.Icons;
import gg.aurora.ui.Rows;
import gg.aurora.ui.Toasts;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Client tools: config profiles (with share codes) and screenshot helpers. */
public final class ClientTools {
    private ClientTools() {}

    private static final Path PROFILES = FabricLoader.getInstance().getConfigDir().resolve("aurora").resolve("profiles");
    private static String newName = "";

    // =========================================================================== Profiles

    /** Profiles live in Settings › Profiles and in the preset menu: they are not a module to switch on. */
    public static List<Rows.Row> profileRows() {
        List<Rows.Row> rows = new ArrayList<>();
        rows.add(new Rows.TextField("New profile", "Name", 32, () -> newName, v -> newName = v));
        rows.add(new Rows.Button("Save current settings", "Stores modules, HUD and settings under that name", Icons.CHECK, () -> {
            String n = newName.replaceAll("[^A-Za-z0-9 _-]", "").trim();
            if (n.isEmpty()) {
                Toasts.push(Icons.INFO, "Profiles", "Type a name first", false);
                return;
            }
            save(n);
            newName = "";
        }));
        for (String p : list()) {
            rows.add(new Rows.Button("Load: " + p, null, Icons.ARROW_DOWN, () -> load(p)));
        }
        rows.add(new Rows.Button("Copy share code", "Your current settings, ready to paste to a friend", Icons.GIFT, ClientTools::export));
        rows.add(new Rows.Button("Import from clipboard", "Replaces your settings with the copied code", Icons.ARROW_DOWN, ClientTools::importCode));
        return rows;
    }

    private static List<String> list() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(PROFILES)) return out;
        try (Stream<Path> s = Files.list(PROFILES)) {
            s.forEach(p -> {
                String n = p.getFileName().toString();
                if (n.endsWith(".json")) out.add(n.substring(0, n.length() - 5));
            });
        } catch (IOException ignored) {
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private static void save(String name) {
        try {
            Config.save();
            Files.createDirectories(PROFILES);
            Files.copy(Config.file(), PROFILES.resolve(name + ".json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Toasts.push(Icons.CHECK, "Profiles", "Saved " + name, false);
        } catch (IOException e) {
            Toasts.push(Icons.INFO, "Profiles", "Could not save", true);
        }
    }

    private static void load(String name) {
        try {
            Config.save();
            Files.copy(PROFILES.resolve(name + ".json"), Config.file(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Config.load();
            Toasts.push(Icons.CHECK, "Profiles", "Loaded " + name, false);
        } catch (IOException e) {
            Toasts.push(Icons.INFO, "Profiles", "Could not load " + name, true);
        }
    }

    private static void export() {
        try {
            Config.save();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (GZIPOutputStream z = new GZIPOutputStream(bo)) {
                z.write(sanitized());
            }
            Minecraft.getInstance().keyboardHandler.setClipboard("PARANOIA1:" + Base64.getEncoder().encodeToString(bo.toByteArray()));
            Toasts.push(Icons.CHECK, "Profiles", "Share code copied", false);
        } catch (IOException e) {
            Toasts.push(Icons.INFO, "Profiles", "Could not export", true);
        }
    }

    /** Share codes never carry the friends list, the account links or the session data. */
    private static byte[] sanitized() throws IOException {
        var root = com.google.gson.JsonParser.parseString(Files.readString(Config.file(), StandardCharsets.UTF_8)).getAsJsonObject();
        root.remove("friends");
        root.remove("boost");
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void importCode() {
        String code = Minecraft.getInstance().keyboardHandler.getClipboard().trim();
        if (!code.startsWith("PARANOIA1:")) {
            Toasts.push(Icons.INFO, "Profiles", "No share code in the clipboard", false);
            return;
        }
        try (GZIPInputStream z = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(code.substring(10))))) {
            var incoming = com.google.gson.JsonParser.parseString(new String(z.readNBytes(4_000_000), StandardCharsets.UTF_8)).getAsJsonObject();
            var current = com.google.gson.JsonParser.parseString(Files.readString(Config.file(), StandardCharsets.UTF_8)).getAsJsonObject();
            // friends stay yours, whatever the code contains
            incoming.remove("friends");
            if (current.has("friends")) incoming.add("friends", current.get("friends"));
            Files.writeString(Config.file(), incoming.toString(), StandardCharsets.UTF_8);
            Config.load();
            Toasts.push(Icons.CHECK, "Profiles", "Settings imported", false);
        } catch (IOException | RuntimeException e) {
            Toasts.push(Icons.INFO, "Profiles", "This code is not valid", true);
        }
    }

    // =========================================================================== Screenshots

    public static final Module SCREENSHOTS = Modules.register(new Module("screenshot_tools", "Screenshot Tools", Category.MISC, Icons.MONITOR, false))
            .withSettings(() -> List.of(
                    new Rows.Toggle("Copy to clipboard", "Every new screenshot, ready to paste", () -> ClientTools.SCREENSHOTS.flag("copy", true), v -> ClientTools.SCREENSHOTS.set("copy", v)),
                    new Rows.Button("Open gallery", null, Icons.SEARCH, () -> {
                        var mc = Minecraft.getInstance();
                        mc.setScreen(new gg.aurora.ui.PicturesScreen(mc.screen));
                    }),
                    new Rows.Button("Open folder", null, Icons.ARROW_DOWN, () -> Util.getPlatform().openPath(dir()))));

    public static Path dir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots");
    }

    public static List<Path> recent(int max) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir())) return out;
        try (Stream<Path> s = Files.list(dir())) {
            s.filter(p -> p.toString().toLowerCase().endsWith(".png"))
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed()).limit(max).forEach(out::add);
        } catch (IOException ignored) {
        }
        return out;
    }

    private static long lastSeen = System.currentTimeMillis(), lastCheck;

    public static void tick() {
        if (!SCREENSHOTS.enabled || !SCREENSHOTS.flag("copy", true)) return;
        long now = System.currentTimeMillis();
        if (now - lastCheck < 1500) return;
        lastCheck = now;
        Thread t = new Thread(() -> {
            List<Path> r = recent(1);
            if (r.isEmpty()) return;
            File f = r.get(0).toFile();
            if (f.lastModified() <= lastSeen) return;
            lastSeen = f.lastModified();
            copyImage(f);
        }, "Paranoia screenshot");
        t.setDaemon(true);
        t.start();
    }

    /** Windows clipboard through PowerShell (AWT is headless in Minecraft). */
    public static void copyImage(File f) {
        if (!Util.getPlatform().equals(Util.OS.WINDOWS)) return;
        String path = f.getAbsolutePath().replace("'", "''");
        String cmd = "Add-Type -AssemblyName System.Windows.Forms,System.Drawing;"
                + "[System.Windows.Forms.Clipboard]::SetImage([System.Drawing.Image]::FromFile('" + path + "'))";
        try {
            new ProcessBuilder("powershell", "-NoProfile", "-STA", "-Command", cmd).redirectErrorStream(true).start();
            Minecraft.getInstance().execute(() -> Toasts.push(Icons.CHECK, "Screenshot", "Copied to clipboard", false));
        } catch (IOException e) {
            AuroraClient.LOGGER.debug("Clipboard: {}", e.toString());
        }
    }

    public static void init() {
    }
}
