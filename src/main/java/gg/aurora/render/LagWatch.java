package gg.aurora.render;

import gg.aurora.AuroraClient;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lag watchdog: a daemon thread samples the render thread during freezes and sustained FPS drops, then
 * appends the hottest stacks to paranoia-bench/lag.log. Random stutters can't be reproduced on demand,
 * so this captures the real cause the moment it happens. Idle cost: one volatile write per frame.
 */
public final class LagWatch {
    private LagWatch() {}

    private static final long FREEZE_NS = 100_000_000L;
    private static final int SLOW_FPS = 45, SLOW_SECONDS = 2;
    private static final long MAX_LOG = 2_000_000;

    private static volatile long lastFrame;
    private static volatile Thread render;
    private static volatile int frames;
    private static boolean started;

    /** Called once per frame from the render thread. */
    public static void frame() {
        lastFrame = System.nanoTime();
        frames++;
        if (!started) {
            started = true;
            render = Thread.currentThread();
            Thread t = new Thread(LagWatch::run, "Paranoia-LagWatch");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        }
    }

    private static void run() {
        Map<String, Integer> samples = new HashMap<>();
        long freezeStart = 0, secondStart = System.nanoTime();
        int framesAtSecond = frames, slowSeconds = 0, slowSamples = 0;
        boolean slowActive = false;
        while (true) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.nanoTime();
            Minecraft mc = Minecraft.getInstance();
            boolean watch = mc != null && mc.level != null && mc.getOverlay() == null;
            // freeze: no frame for over 100 ms
            if (now - lastFrame > FREEZE_NS && watch) {
                if (freezeStart == 0) {
                    freezeStart = lastFrame;
                    samples.clear();
                }
                sample(samples);
            } else if (freezeStart != 0) {
                long ms = (now - freezeStart) / 1_000_000;
                if (ms >= 150) write("FREEZE " + ms + " ms", samples);
                freezeStart = 0;
                samples.clear();
            }
            // sustained low FPS (window focused, outside loading)
            if (now - secondStart >= 1_000_000_000L) {
                int fps = frames - framesAtSecond;
                framesAtSecond = frames;
                secondStart = now;
                boolean slow = watch && mc.isWindowActive() && fps < SLOW_FPS && fps > 0;
                slowSeconds = slow ? slowSeconds + 1 : 0;
                if (slowSeconds >= SLOW_SECONDS && !slowActive) {
                    slowActive = true;
                    slowSamples = 0;
                    samples.clear();
                }
                if (slowActive && !slow) slowActive = false;
                if (slowActive && slowSamples >= 150) {
                    write("LOW FPS " + fps + " screen=" + (mc.screen == null ? "none" : mc.screen.getClass().getSimpleName()), samples);
                    samples.clear();
                    slowSamples = 0;
                    slowSeconds = -30; // at most one report every 30 s
                    slowActive = false;
                }
            }
            if (slowActive && freezeStart == 0) {
                sample(samples);
                slowSamples++;
            }
        }
    }

    private static void sample(Map<String, Integer> samples) {
        Thread t = render;
        if (t == null) return;
        StackTraceElement[] st = t.getStackTrace();
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (StackTraceElement e : st) {
            sb.append("    ").append(e.getClassName()).append('.').append(e.getMethodName()).append(':').append(e.getLineNumber()).append('\n');
            if (++n >= 28) break;
        }
        samples.merge(sb.toString(), 1, Integer::sum);
    }

    private static void write(String title, Map<String, Integer> samples) {
        try {
            Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("paranoia-bench");
            Files.createDirectories(dir);
            Path log = dir.resolve("lag.log");
            if (Files.exists(log) && Files.size(log) > MAX_LOG) Files.move(log, dir.resolve("lag.old.log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            StringBuilder sb = new StringBuilder("=== ").append(LocalDateTime.now()).append("  ").append(title).append('\n');
            List<Map.Entry<String, Integer>> top = new ArrayList<>(samples.entrySet());
            top.sort((a, b) -> b.getValue() - a.getValue());
            for (int i = 0; i < Math.min(4, top.size()); i++) sb.append("  x").append(top.get(i).getValue()).append('\n').append(top.get(i).getKey());
            Files.writeString(log, sb, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            AuroraClient.LOGGER.warn("LagWatch: {}", e.toString());
        }
    }
}
