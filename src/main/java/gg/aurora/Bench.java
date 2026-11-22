package gg.aurora;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/**
 * /paranoia bench : mesure les performances réelles du jeu, là où le joueur se trouve.
 * Phase 1 : temps de chaque image sans profileur (chiffres propres : moyenne, 1 % et 0,1 % low, pics, GC).
 * Phase 2 : enregistrement JFR (où part le temps CPU, quelles allocations, quels mods).
 * Résultats dans .minecraft/paranoia-bench/ (rapport texte + fichier .jfr).
 */
public final class Bench {
    private Bench() {}

    private static final int WARMUP_TICKS = 60;
    private static int state; // 0 inactif, 1 préchauffage, 2 mesure, 3 préchauffage JFR, 4 JFR
    private static int ticks, seconds;
    private static final long[] FRAMES = new long[400_000];
    private static int count;
    private static long last;
    private static long gcCount0, gcTime0;
    private static String report = "";
    private static jdk.jfr.Recording rec;
    private static Path dir;
    private static String stamp;

    public static boolean running() {
        return state != 0;
    }

    public static void start(int sec) {
        if (state != 0) return;
        seconds = Math.max(10, Math.min(120, sec));
        ticks = 0;
        state = 1;
        msg("Bench lancé : ne touche à rien, joue normalement (" + (seconds * 2 + 6) + " s)", ChatFormatting.LIGHT_PURPLE);
    }

    /** Appelé à chaque image. */
    public static void frame() {
        if (state != 2) return;
        long now = System.nanoTime();
        if (last != 0 && count < FRAMES.length) FRAMES[count++] = now - last;
        last = now;
    }

    /** Appelé à chaque tick client. */
    public static void tick(Minecraft mc) {
        if (state == 0) return;
        if (mc.level == null) {
            stop("Bench annulé : quitte pas le monde pendant la mesure");
            return;
        }
        mc.getFramerateLimitTracker().onInputReceived(); // pas de limite AFK pendant la mesure
        ticks++;
        switch (state) {
            case 1 -> {
                if (ticks >= WARMUP_TICKS) {
                    state = 2;
                    Prof.reset();
                    Prof.on = true;
                    ticks = 0;
                    count = 0;
                    last = 0;
                    long[] gc = gc();
                    gcCount0 = gc[0];
                    gcTime0 = gc[1];
                }
            }
            case 2 -> {
                if (ticks >= seconds * 20) {
                    state = 3;
                    ticks = 0;
                    Prof.on = false;
                    report = summarize(mc) + Prof.report(count);
                    try {
                        rec = recording();
                        rec.start();
                    } catch (Exception e) {
                        rec = null;
                    }
                }
            }
            case 3 -> {
                if (ticks >= WARMUP_TICKS) {
                    state = 4;
                    ticks = 0;
                    if (rec != null) {
                        // on jette le démarrage du profileur (instrumentation) : seule la suite compte
                        try {
                            rec.stop();
                            rec.close();
                            rec = recording();
                            rec.start();
                        } catch (Exception e) {
                            rec = null;
                        }
                    }
                }
            }
            case 4 -> {
                if (ticks >= seconds * 20) finish(mc);
            }
            default -> {}
        }
    }

    /** Profil JFR : échantillons CPU fins (2 ms au lieu de 20) et échantillons d'allocation plus nombreux. */
    private static jdk.jfr.Recording recording() throws Exception {
        jdk.jfr.Recording r = new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"));
        r.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(2));
        r.enable("jdk.ObjectAllocationSample").with("throttle", "3000/s");
        return r;
    }

    private static void finish(Minecraft mc) {
        state = 0;
        try {
            dir = mc.gameDirectory.toPath().resolve("paranoia-bench");
            Files.createDirectories(dir);
            stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path txt = dir.resolve("bench-" + stamp + ".txt");
            Files.writeString(txt, report, StandardCharsets.UTF_8);
            if (rec != null) {
                rec.dump(dir.resolve("bench-" + stamp + ".jfr"));
                rec.close();
                rec = null;
            }
            String first = report.lines().filter(l -> l.startsWith("FPS")).findFirst().orElse("");
            msg("Bench terminé — " + first, ChatFormatting.GREEN);
            msg("Rapport : paranoia-bench/bench-" + stamp + ".txt", ChatFormatting.GRAY);
        } catch (Exception e) {
            msg("Bench : impossible d'écrire le rapport (" + e.getMessage() + ")", ChatFormatting.RED);
        }
    }

    private static void stop(String why) {
        state = 0;
        if (rec != null) {
            try {
                rec.close();
            } catch (Exception ignored) {
            }
            rec = null;
        }
        msg(why, ChatFormatting.RED);
    }

    private static long[] gc() {
        long c = 0, t = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            c += Math.max(0, b.getCollectionCount());
            t += Math.max(0, b.getCollectionTime());
        }
        return new long[]{c, t};
    }

    private static String summarize(Minecraft mc) {
        int n = count;
        StringBuilder b = new StringBuilder();
        b.append("Paranoia bench ").append(LocalDateTime.now()).append('\n');
        if (n < 10) return b.append("Pas assez d'images mesurées\n").toString();
        long[] f = Arrays.copyOf(FRAMES, n);
        Arrays.sort(f);
        double sum = 0;
        for (long v : f) sum += v;
        double avg = sum / n;
        int k1 = Math.max(1, n / 100), k01 = Math.max(1, n / 1000);
        double l1 = 0, l01 = 0;
        for (int i = n - k1; i < n; i++) l1 += f[i];
        for (int i = n - k01; i < n; i++) l01 += f[i];
        l1 /= k1;
        l01 /= k01;
        int over5 = 0, over10 = 0, under400 = 0;
        for (long v : f) {
            if (v > 5_000_000) over5++;
            if (v > 10_000_000) over10++;
            if (v > 2_500_000) under400++;
        }
        long[] gc = gc();
        var rt = Runtime.getRuntime();
        var o = mc.options;
        b.append(String.format("FPS moy %.0f | 1%% low %.0f | 0.1%% low %.0f | pire image %.1f ms%n",
                1e9 / avg, 1e9 / l1, 1e9 / l01, f[n - 1] / 1e6));
        b.append(String.format("Images %d | médiane %.2f ms | p99 %.2f ms | >2.5 ms (sous 400 FPS) %.1f %% | >5 ms %d | >10 ms %d%n",
                n, f[n / 2] / 1e6, f[(int) (n * 0.99)] / 1e6, 100.0 * under400 / n, over5, over10));
        b.append(String.format("GC : %d collections, %d ms | Heap %d / %d Mo (max %d)%n", gc[0] - gcCount0, gc[1] - gcTime0,
                (rt.totalMemory() - rt.freeMemory()) / 1048576, rt.totalMemory() / 1048576, rt.maxMemory() / 1048576));
        b.append(String.format("Fenêtre %dx%d | distance %d | simulation %d | limite FPS %d | vsync %s | GUI x%d%n",
                mc.getWindow().getWidth(), mc.getWindow().getHeight(), o.renderDistance().get(), o.simulationDistance().get(),
                o.framerateLimit().get(), o.enableVsync().get(), (int) mc.getWindow().getGuiScale()));
        if (mc.level != null) {
            b.append("Entités chargées ").append(mc.level.getEntityCount()).append(" | joueurs ").append(mc.level.players().size())
                    .append(" | serveur ").append(mc.getCurrentServer() != null ? "multijoueur" : "solo").append('\n');
        }
        b.append("Modules Paranoia actifs :");
        for (var m : gg.aurora.module.Modules.ALL) if (m.enabled) b.append(' ').append(m.id);
        b.append('\n').append("Cosmétiques : ailes=").append(Settings.wings).append(" aura=").append(Settings.aura).append(" tête=")
                .append(Settings.halo).append(" familier=").append(Settings.pet).append(" cape=").append(Settings.cape)
                .append(" qualité=").append(Settings.cosmeticQuality).append('\n');
        b.append("Mods (").append(FabricLoader.getInstance().getAllMods().size()).append(") :");
        for (var m : FabricLoader.getInstance().getAllMods()) {
            String id = m.getMetadata().getId();
            if (!id.startsWith("fabric-") && !id.equals("java") && !id.equals("minecraft") && !id.equals("fabricloader"))
                b.append(' ').append(id);
        }
        return b.append('\n').toString();
    }

    private static void msg(String text, ChatFormatting color) {
        var mc = Minecraft.getInstance();
        if (mc.gui != null) mc.gui.getChat().addMessage(Component.literal("[Paranoia] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal(text).withStyle(color)));
    }
}
