package gg.aurora;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gg.aurora.nametag.NametagSync;
import gg.aurora.ui.Icons;
import gg.aurora.ui.Toasts;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Mises à jour automatiques : au démarrage, le mod demande au serveur Paranoia la dernière version.
 * Si elle est plus récente, le jar est téléchargé, vérifié (taille + SHA-256) et mis de côté ; il
 * remplace l'ancien seulement après la fermeture du jeu (jamais pendant : un jar ouvert remplacé
 * corrompt les ressources du mod).
 */
public final class Updater {
    private Updater() {}

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    public static volatile String status = "Not checked yet";
    private static volatile Path pending;

    public static String currentVersion() {
        return FabricLoader.getInstance().getModContainer(AuroraClient.MOD_ID)
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("0");
    }

    /** Jar du mod actuellement chargé (absent en développement). */
    private static Optional<Path> currentJar() {
        Optional<ModContainer> m = FabricLoader.getInstance().getModContainer(AuroraClient.MOD_ID);
        if (m.isEmpty()) return Optional.empty();
        for (Path p : m.get().getOrigin().getPaths()) {
            if (p.toString().toLowerCase().endsWith(".jar") && Files.isRegularFile(p)) return Optional.of(p);
        }
        return Optional.empty();
    }

    /** Compare des versions « 1.2.3 » numériquement. */
    static int compare(String a, String b) {
        String[] x = a.split("[.+-]"), y = b.split("[.+-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int u = i < x.length ? num(x[i]) : 0, v = i < y.length ? num(y[i]) : 0;
            if (u != v) return Integer.compare(u, v);
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static void checkAsync(boolean manual) {
        if (!Settings.autoUpdate && !manual) return;
        Thread t = new Thread(() -> {
            check(manual);
            if (manual) return;
            while (pending == null) { // long sessions: new versions are still picked up
                try {
                    Thread.sleep(30 * 60_000L);
                } catch (InterruptedException e) {
                    return;
                }
                check(false);
            }
        }, "Paranoia updater");
        t.setDaemon(true);
        t.start();
    }

    private static void check(boolean manual) {
        try {
            Optional<Path> jar = currentJar();
            if (jar.isEmpty()) {
                status = "Unavailable in development";
                return;
            }
            status = "Checking…";
            String api = NametagSync.DEFAULT_API;
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(api + "/v1/update")).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                status = "Server unavailable (" + r.statusCode() + ")";
                return;
            }
            JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
            String latest = o.get("version").getAsString();
            String sha = o.get("sha256").getAsString().toLowerCase();
            long size = o.get("size").getAsLong();
            if (compare(latest, currentVersion()) <= 0) {
                status = "Up to date (" + currentVersion() + ")";
                if (manual) Toasts.push(Icons.CHECK, "Paranoia", "You already have the latest version", false);
                return;
            }
            status = "Downloading " + latest + "…";
            HttpResponse<byte[]> jr = HTTP.send(HttpRequest.newBuilder(URI.create(api + "/v1/update/jar")).timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            byte[] data = jr.body();
            String got = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            if (jr.statusCode() != 200 || data.length != size || !got.equals(sha)) {
                status = "Invalid download, ignored";
                return;
            }
            Path dir = jar.get().getParent();
            Path tmp = dir.resolve(".aurora-update.tmp"), ready = dir.resolve(".aurora-update.jar");
            Files.write(tmp, data);
            Files.move(tmp, ready, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            pending = ready;
            status = "Version " + latest + " ready: installed when the game closes";
            Toasts.push(Icons.GIFT, "Update " + latest, "It installs when you close the game", true);
        } catch (Exception e) {
            status = "Check failed";
            AuroraClient.LOGGER.warn("Mise à jour : {}", e.toString());
        }
    }

    /**
     * À la fermeture : un petit processus séparé attend la fin du jeu puis remplace le jar.
     * (Windows verrouille les jars ouverts, et les remplacer en cours de partie casse les ressources.)
     */
    public static void applyOnExit() {
        Path ready = pending;
        Optional<Path> jar = currentJar();
        if (ready == null || jar.isEmpty() || !Files.exists(ready)) return;
        try {
            long pid = ProcessHandle.current().pid();
            String target = jar.get().toAbsolutePath().toString(), src = ready.toAbsolutePath().toString();
            ProcessBuilder pb;
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                String ps = "Wait-Process -Id " + pid + " -ErrorAction SilentlyContinue; Start-Sleep -Milliseconds 500; "
                        + "Move-Item -LiteralPath '" + src.replace("'", "''") + "' -Destination '" + target.replace("'", "''") + "' -Force";
                // « start » detaches it: launchers that kill the game's child processes can't stop the swap
                pb = new ProcessBuilder("cmd", "/c", "start", "\"\"", "/min", "powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", ps);
            } else {
                pb = new ProcessBuilder("sh", "-c", "while kill -0 " + pid + " 2>/dev/null; do sleep 0.5; done; mv -f \"$0\" \"$1\"", src, target);
            }
            pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (Exception e) {
            AuroraClient.LOGGER.warn("Installation de la mise à jour impossible : {}", e.toString());
        }
    }
}
