package gg.aurora;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Separate process started by {@link ModManager} while the game closes. Plain JDK only: it runs outside
 * Minecraft and Fabric. It waits for the game to exit, moves the queued jars, then optionally starts the game again.
 * <p>
 * Plan file: line 1 = game pid, then {@code MOVE\tfrom\tto}, {@code CWD\tdir} and {@code ARG\t<base64>} lines.
 * The plan holds the game's launch arguments, so it is deleted as soon as it is read.
 */
public final class ModSwap {
    private ModSwap() {}

    public static void main(String[] args) throws Exception {
        Path plan = Path.of(args[0]);
        Path log = plan.resolveSibling("swap.log");
        List<String> lines = Files.readAllLines(plan, StandardCharsets.UTF_8);
        Files.deleteIfExists(plan);
        StringBuilder out = new StringBuilder(LocalDateTime.now() + "\n");

        long pid = Long.parseLong(lines.get(0).trim());
        ProcessHandle.of(pid).ifPresent(p -> {
            try {
                p.onExit().get(2, TimeUnit.MINUTES);
            } catch (Exception ignored) {
                // still running after 2 minutes: try anyway, moves retry below
            }
        });
        Thread.sleep(700); // Windows releases file handles a moment after the process ends

        List<String> cmd = new ArrayList<>();
        Path cwd = null;
        for (String line : lines.subList(1, lines.size())) {
            String[] p = line.split("\t");
            switch (p[0]) {
                case "MOVE" -> out.append(move(Path.of(p[1]), Path.of(p[2]))).append('\n');
                case "CWD" -> cwd = Path.of(p[1]);
                case "ARG" -> cmd.add(new String(Base64.getDecoder().decode(p[1]), StandardCharsets.UTF_8));
                default -> { }
            }
        }
        if (!cmd.isEmpty()) {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            if (cwd != null) pb.directory(cwd.toFile());
            pb.start();
            out.append("Game started again\n");
        }
        Files.writeString(log, out.toString(), StandardCharsets.UTF_8);
    }

    private static String move(Path from, Path to) throws InterruptedException {
        for (int attempt = 0; attempt < 40; attempt++) {
            try {
                if (!Files.exists(from)) return "missing " + from;
                Files.createDirectories(to.getParent());
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
                return "moved " + from.getFileName() + " -> " + to;
            } catch (Exception e) {
                Thread.sleep(250); // still locked
            }
        }
        return "FAILED " + from;
    }
}
