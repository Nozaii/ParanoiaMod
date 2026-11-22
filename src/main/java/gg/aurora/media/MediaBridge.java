package gg.aurora.media;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gg.aurora.AuroraClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Musique en cours (Windows) : un petit script PowerShell lit les contrôles média du système
 * (Spotify, Deezer, navigateur…) et renvoie l'état toutes les 500 ms ; les commandes lecture/pause,
 * suivante et précédente lui sont envoyées sur son entrée standard. Rien ne sort de la machine.
 */
public final class MediaBridge {
    private MediaBridge() {}

    /** Morceau en cours, ou null si rien ne joue / non disponible. */
    public record Track(String title, String artist, boolean playing, double pos, double dur, long updated, String app, String art) {
        /** Position extrapolée depuis la dernière mise à jour du lecteur. */
        public double position() {
            double p = pos;
            if (playing && updated > 0) p += Math.max(0, System.currentTimeMillis() - updated) / 1000.0;
            return dur > 0 ? Math.min(dur, p) : p;
        }
    }

    public static final boolean SUPPORTED = System.getProperty("os.name", "").toLowerCase().contains("win");
    public static volatile Track track;
    private static Process proc;
    private static BufferedWriter stdin;
    private static long nextStart;
    private static volatile boolean starting;
    private static int failures;

    /** Démarre / arrête le lecteur d'état selon que le module est actif. */
    public static void tick(boolean wanted) {
        if (!SUPPORTED || starting) return; // never wait for the process start on the game thread
        boolean alive = proc != null && proc.isAlive();
        if (wanted && !alive && !starting && System.currentTimeMillis() >= nextStart) {
            // copie du script + lancement de PowerShell : hors du thread de rendu (évite un à-coup)
            starting = true;
            nextStart = System.currentTimeMillis() + 2000;
            Thread t = new Thread(() -> {
                synchronized (MediaBridge.class) {
                    try {
                        start();
                    } finally {
                        starting = false;
                    }
                }
            }, "Paranoia-NowPlaying-start");
            t.setDaemon(true);
            t.start();
        }
        else if (!wanted && alive) new Thread(MediaBridge::stop, "Paranoia-NowPlaying-stop").start();
    }

    private static void start() {
        try {
            Path script = FabricLoader.getInstance().getConfigDir().resolve("aurora").resolve("now_playing.ps1");
            Files.createDirectories(script.getParent());
            try (InputStream in = MediaBridge.class.getResourceAsStream("/assets/aurora/media/now_playing.ps1")) {
                if (in == null) return;
                Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
            }
            proc = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass",
                    "-File", script.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            stdin = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
            Process p = proc;
            Thread t = new Thread(() -> read(p), "Paranoia-NowPlaying");
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            AuroraClient.LOGGER.warn("Now Playing : impossible de lancer le lecteur d'état ({})", e.toString());
            backoff();
        }
    }

    private static void backoff() {
        failures = Math.min(6, failures + 1);
        nextStart = System.currentTimeMillis() + (2000L << failures);
    }

    private static void read(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.startsWith("{")) continue;
                try {
                    JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                    if (!o.has("ok") || !o.get("ok").getAsBoolean()) {
                        track = null;
                        continue;
                    }
                    track = new Track(str(o, "title"), str(o, "artist"), o.has("playing") && o.get("playing").getAsBoolean(),
                            num(o, "pos"), num(o, "dur"), o.has("updated") ? o.get("updated").getAsLong() : 0, str(o, "app"), str(o, "art"));
                    failures = 0;
                } catch (RuntimeException ignored) {
                    // ligne illisible : on garde l'état précédent
                }
            }
        } catch (Exception ignored) {
            // processus arrêté
        }
        track = null;
        synchronized (MediaBridge.class) {
            if (proc == p) backoff();
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static double num(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : 0;
    }

    /** Envoie une commande au lecteur : toggle, next ou prev. */
    public static void send(String cmd) {
        if (stdin == null || proc == null || !proc.isAlive()) return;
        Thread w = new Thread(() -> write(cmd), "Paranoia-NowPlaying-send");
        w.setDaemon(true);
        w.start();
        Track t = track;
        // instant on-screen feedback without waiting for the player
        if (t != null && cmd.equals("toggle")) {
            track = new Track(t.title(), t.artist(), !t.playing(), t.position(), t.dur(), System.currentTimeMillis(), t.app(), t.art());
        }
    }

    private static synchronized void write(String cmd) {
        if (stdin == null) return;
        try {
            stdin.write(cmd);
            stdin.newLine();
            stdin.flush();
        } catch (Exception e) {
            stop();
        }
    }

    public static synchronized void stop() {
        if (proc != null) {
            proc.destroy();
            proc = null;
        }
        stdin = null;
        track = null;
    }
}
