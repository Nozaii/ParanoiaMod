package gg.aurora;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * Mods interdits : si Velaris est présent, le jeu ne démarre pas et affiche pourquoi (avant que quoi
 * que ce soit ne se charge). Velaris se déguise en « Clumps » (id et nom d'un vrai mod) : il est donc
 * reconnu par son code (paquet com.slither.velaris, configuration velaris.*.mixins.json), pas par son nom.
 * Le vrai Clumps n'est pas concerné.
 */
public final class Guard implements PreLaunchEntrypoint {
    private static final String CODE = "com/slither/velaris/";

    @Override
    public void onPreLaunch() {
        String found = find();
        if (found != null) {
            throw new IllegalStateException("Paranoia Client: Velaris is forbidden (found: " + found + "). "
                    + "Remove it from the mods folder then restart the game.");
        }
    }

    private static String find() {
        // 1. code chargé par Fabric (même si le jar est renommé ou déguisé)
        if (Guard.class.getClassLoader().getResource(CODE + "Velaris.class") != null) return "mod chargé (com.slither.velaris)";
        for (ModContainer m : FabricLoader.getInstance().getAllMods()) {
            var meta = m.getMetadata();
            if (meta.getId().toLowerCase(Locale.ROOT).contains("velaris") || meta.getName().toLowerCase(Locale.ROOT).contains("velaris"))
                return meta.getName();
            for (Path root : m.getRootPaths()) {
                if (Files.exists(root.resolve(CODE + "Velaris.class")) || Files.exists(root.resolve("velaris.client.mixins.json")))
                    return meta.getName() + " (disguised)";
            }
        }
        // 2. jars du dossier mods (contenu inspecté, pas seulement le nom)
        Path mods = FabricLoader.getInstance().getGameDir().resolve("mods");
        if (!Files.isDirectory(mods)) return null;
        try (Stream<Path> s = Files.walk(mods, 2)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.getFileName().toString();
                if (!n.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
                if (n.toLowerCase(Locale.ROOT).contains("velaris")) return n;
                try (ZipFile z = new ZipFile(p.toFile())) {
                    if (z.getEntry(CODE + "Velaris.class") != null || z.getEntry("velaris.client.mixins.json") != null) return n;
                } catch (Exception ignored) {
                    // jar illisible : ce n'est pas notre problème
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }
}
