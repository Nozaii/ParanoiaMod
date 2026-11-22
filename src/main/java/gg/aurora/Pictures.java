package gg.aurora;

import com.mojang.blaze3d.platform.NativeImage;
import gg.aurora.module.ClientTools;
import gg.aurora.ui.Icons;
import gg.aurora.ui.Toasts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Galerie « Pictures » : les captures du dossier screenshots du jeu, leurs miniatures (chargées en arrière-plan),
 * les favoris (config), et les actions : copier, partager à un ami, télécharger, supprimer, enregistrer une retouche.
 */
public final class Pictures {
    private Pictures() {}

    public static final int THUMB_W = 320, FULL_MAX = 2048;
    private static final Pattern NAME = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})_(\\d{2})\\.(\\d{2})\\.(\\d{2}).*");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");

    public record Shot(Path path, long time) {
        public String name() {
            return path.getFileName().toString();
        }

        public String date() {
            LocalDateTime d = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(time), ZoneId.systemDefault());
            return d.format(DateTimeFormatter.ofPattern("d MMM yyyy  ·  HH:mm", Locale.ENGLISH));
        }
    }

    public record Tex(Identifier id, int w, int h) {}

    public record Group(String title, List<Shot> shots) {}

    // ------------------------------------------------------------------ dossier

    private static List<Shot> cache = List.of();
    private static long scannedAt;

    public static Path dir() {
        return ClientTools.dir();
    }

    /** Toutes les captures, les plus récentes d'abord. Rescanne au plus toutes les 2 s (ou sur demande). */
    public static List<Shot> all(boolean rescan) {
        long now = System.currentTimeMillis();
        if (!rescan && now - scannedAt < 2000) return cache;
        scannedAt = now;
        List<Shot> out = new ArrayList<>();
        if (Files.isDirectory(dir())) {
            try (Stream<Path> s = Files.list(dir())) {
                s.filter(p -> {
                    String n = p.toString().toLowerCase(Locale.ROOT);
                    return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg");
                }).forEach(p -> out.add(new Shot(p, timeOf(p))));
            } catch (IOException ignored) {
            }
        }
        out.sort((a, b) -> Long.compare(b.time, a.time));
        cache = out;
        return out;
    }

    private static long timeOf(Path p) {
        Matcher m = NAME.matcher(p.getFileName().toString());
        if (m.matches()) {
            try {
                LocalDateTime d = LocalDateTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                        Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)));
                return d.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (RuntimeException ignored) {
            }
        }
        return p.toFile().lastModified();
    }

    /** Groupes par date : ce mois-ci, puis les mois de l'année, puis « mois année » pour les années passées. */
    public static List<Group> groups(List<Shot> shots) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, List<Shot>> map = new LinkedHashMap<>();
        for (Shot s : shots) {
            LocalDateTime d = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(s.time), ZoneId.systemDefault());
            String key;
            if (d.getYear() == now.getYear() && d.getMonth() == now.getMonth()) key = "This month";
            else if (d.getYear() == now.getYear()) key = d.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            else key = d.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + d.getYear();
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }
        List<Group> out = new ArrayList<>();
        map.forEach((k, v) -> out.add(new Group(k, v)));
        return out;
    }

    // ------------------------------------------------------------------ favoris

    private static Set<String> favorites() {
        Set<String> s = new LinkedHashSet<>();
        for (String n : Settings.shotFavorites.split("\\|")) if (!n.isBlank()) s.add(n);
        return s;
    }

    public static boolean favorite(Shot s) {
        return favorites().contains(s.name());
    }

    public static void setFavorite(Shot s, boolean on) {
        Set<String> f = favorites();
        if (on) f.add(s.name());
        else f.remove(s.name());
        Settings.shotFavorites = String.join("|", f);
        Config.save();
    }

    public static int favoriteCount() {
        Set<String> f = favorites();
        int n = 0;
        for (Shot s : cache) if (f.contains(s.name())) n++;
        return n;
    }

    // ------------------------------------------------------------------ textures

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Paranoia pictures");
        t.setDaemon(true);
        return t;
    });
    private static final Map<Path, Tex> THUMBS = new ConcurrentHashMap<>();
    private static final Set<Path> LOADING = ConcurrentHashMap.newKeySet();
    private static final Map<Path, Tex> FULL = new LinkedHashMap<>();
    private static int seq;

    /** Miniature (320 px de large), ou null tant qu'elle se charge. */
    public static Tex thumb(Shot s) {
        Tex t = THUMBS.get(s.path);
        if (t != null || !LOADING.add(s.path)) return t;
        EXEC.execute(() -> {
            try {
                NativeImage full = read(s.path);
                int h = Math.max(1, full.getHeight() * THUMB_W / full.getWidth());
                NativeImage small = new NativeImage(THUMB_W, h, false);
                full.resizeSubRectTo(0, 0, full.getWidth(), full.getHeight(), small);
                full.close();
                Identifier id = Identifier.fromNamespaceAndPath("aurora", "dynamic/shot_thumb_" + (seq++));
                Minecraft.getInstance().execute(() -> {
                    Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "Paranoia thumbnail", small));
                    THUMBS.put(s.path, new Tex(id, THUMB_W, h));
                });
            } catch (Exception e) {
                THUMBS.put(s.path, new Tex(null, 0, 0)); // illisible
            }
        });
        return null;
    }

    /** Image en grand (au plus 2048 px), ou null tant qu'elle se charge ; les 4 dernières restent en mémoire. */
    public static Tex full(Shot s) {
        synchronized (FULL) {
            Tex t = FULL.get(s.path);
            if (t != null) return t;
        }
        Path key = s.path;
        if (!LOADING.add(key.resolveSibling(key.getFileName() + "#full"))) return null;
        EXEC.execute(() -> {
            try {
                NativeImage img = read(s.path);
                int w = img.getWidth(), h = img.getHeight();
                if (Math.max(w, h) > FULL_MAX) {
                    double k = FULL_MAX / (double) Math.max(w, h);
                    NativeImage small = new NativeImage(Math.max(1, (int) (w * k)), Math.max(1, (int) (h * k)), false);
                    img.resizeSubRectTo(0, 0, w, h, small);
                    img.close();
                    img = small;
                    w = img.getWidth();
                    h = img.getHeight();
                }
                NativeImage fin = img;
                int fw = w, fh = h;
                Identifier id = Identifier.fromNamespaceAndPath("aurora", "dynamic/shot_full_" + (seq++));
                Minecraft.getInstance().execute(() -> {
                    Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "Paranoia picture", fin));
                    synchronized (FULL) {
                        FULL.put(key, new Tex(id, fw, fh));
                        while (FULL.size() > 4) {
                            var first = FULL.entrySet().iterator().next();
                            Minecraft.getInstance().getTextureManager().release(first.getValue().id());
                            LOADING.remove(first.getKey().resolveSibling(first.getKey().getFileName() + "#full"));
                            FULL.remove(first.getKey());
                        }
                    }
                });
            } catch (Exception e) {
                synchronized (FULL) {
                    FULL.put(key, new Tex(null, 0, 0));
                }
            }
        });
        return null;
    }

    private static NativeImage read(Path p) throws IOException {
        try (var in = Files.newInputStream(p)) {
            return NativeImage.read(in);
        }
    }

    /** Pixels ARGB de l'image complète (pour l'éditeur et le partage) : {w, h} dans size. */
    public static int[] pixels(Path p, int[] size) throws IOException {
        try (NativeImage img = read(p)) {
            size[0] = img.getWidth();
            size[1] = img.getHeight();
            return img.getPixels();
        }
    }

    /** Oublie les textures d'une capture (supprimée ou remplacée). */
    private static void forget(Path p) {
        Tex t = THUMBS.remove(p);
        LOADING.remove(p);
        if (t != null && t.id != null) Minecraft.getInstance().getTextureManager().release(t.id);
        synchronized (FULL) {
            Tex f = FULL.remove(p);
            LOADING.remove(p.resolveSibling(p.getFileName() + "#full"));
            if (f != null && f.id != null) Minecraft.getInstance().getTextureManager().release(f.id);
        }
    }

    // ------------------------------------------------------------------ actions

    public static void copy(Shot s) {
        ClientTools.copyImage(s.path.toFile());
    }

    public static void openFolder() {
        net.minecraft.util.Util.getPlatform().openPath(dir());
    }

    /** « Télécharger » : enregistre une copie où l'utilisateur veut (boîte de dialogue système). */
    public static void download(Shot s) {
        Thread t = new Thread(() -> {
            String path;
            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                var filter = stack.mallocPointer(1);
                filter.put(stack.UTF8("*.png")).flip();
                String def = Path.of(System.getProperty("user.home"), "Downloads", s.name()).toString();
                path = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_saveFileDialog("Save picture", def, filter, "PNG picture");
            }
            if (path == null) return;
            try {
                Path dst = Path.of(path.toLowerCase(Locale.ROOT).endsWith(".png") ? path : path + ".png");
                Files.copy(s.path, dst, StandardCopyOption.REPLACE_EXISTING);
                Minecraft.getInstance().execute(() -> Toasts.push(Icons.DOWNLOAD, "Picture saved", dst.getFileName().toString(), false));
            } catch (IOException e) {
                Minecraft.getInstance().execute(() -> Toasts.push(Icons.INFO, "Picture", "Could not save: " + e.getMessage(), true));
            }
        }, "Paranoia save picture");
        t.setDaemon(true);
        t.start();
    }

    public static boolean delete(Shot s) {
        try {
            Files.deleteIfExists(s.path);
            forget(s.path);
            setFavorite(s, false);
            all(true);
            return true;
        } catch (IOException e) {
            Toasts.push(Icons.INFO, "Picture", "Could not delete: " + e.getMessage(), true);
            return false;
        }
    }

    /** Partage à un ami : réduite à 1024 px, par le canal des messages privés. done(null) = envoyée. */
    public static void share(Shot s, UUID to, Consumer<String> done) {
        EXEC.execute(() -> {
            try {
                int[] size = new int[2];
                int[] px = pixels(s.path, size);
                Media.sendImage(to, size[0], size[1], px, 1024, done);
            } catch (IOException e) {
                Minecraft.getInstance().execute(() -> done.accept("This picture can't be read"));
            }
        });
    }

    /** Enregistre une retouche à côté de l'original (« nom_edit.png », numéroté si besoin). */
    public static Shot saveEdited(Shot from, int w, int h, int[] argb) throws IOException {
        String base = from.name().replaceFirst("\\.[^.]+$", "");
        base = base.replaceFirst("_edit(-\\d+)?$", "");
        Path dst = dir().resolve(base + "_edit.png");
        for (int i = 2; Files.exists(dst); i++) dst = dir().resolve(base + "_edit-" + i + ".png");
        try (NativeImage img = new NativeImage(w, h, false)) {
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setPixel(x, y, argb[y * w + x] | 0xFF000000);
            img.writeToFile(dst);
        }
        all(true);
        return new Shot(dst, dst.toFile().lastModified());
    }

    /** Nom horodaté pour une nouvelle capture (même format que le jeu). */
    public static String stamp() {
        return LocalDateTime.now().format(STAMP);
    }
}
