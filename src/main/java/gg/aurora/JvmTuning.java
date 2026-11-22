package gg.aurora;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Réglages de la JVM repris du launcher OneLauncher (Polyfrost, oneclient_mc/src/arguments.rs et
 * oneclient_java/src/platform.rs) :
 * - ramasse-miettes : ZGC générationnel (Java 21+, 64 bits, au moins 8 Go de tas), sinon G1 avec
 *   ParallelRefProcEnabled ; en-têtes d'objets compacts à partir de Java 24 ; jamais si le joueur a déjà
 *   choisi un ramasse-miettes ;
 * - tas initial de 512 Mo, tas maximal par défaut selon la RAM (2, 3 ou 4 Go) quand le launcher n'en donne pas ;
 * - Windows : préférence « GPU hautes performances » pour java.exe et javaw.exe (registre de l'utilisateur,
 *   comme Paramètres > Affichage > Graphiques), utile sur les portables à deux cartes.
 *
 * Une JVM lancée ne change pas de ramasse-miettes : les options servent au redémarrage depuis le jeu
 * (voir ModManager.relaunchCommand) et sont affichées pour être copiées dans le launcher.
 */
public final class JvmTuning {
    private JvmTuning() {}

    /** Demandé par « Redémarrer optimisé » : le prochain redémarrage ajoute les options manquantes. */
    public static volatile boolean optimizeNextLaunch;

    private static final String[] COLLECTORS = {"UseG1GC", "UseZGC", "UseShenandoahGC", "UseParallelGC", "UseParallelOldGC",
            "UseConcMarkSweepGC", "UseSerialGC", "UseEpsilonGC"};
    private static final int ZGC_MIN_HEAP_MB = 8192, INITIAL_HEAP_MB = 512;

    public static int javaMajor() {
        return Runtime.version().feature();
    }

    static boolean is64Bit() {
        return "64".equals(System.getProperty("sun.arch.data.model")) || System.getProperty("os.arch", "").contains("64");
    }

    public static List<String> currentArgs() {
        return ManagementFactory.getRuntimeMXBean().getInputArguments();
    }

    /** Tas maximal de cette JVM, en Mo. */
    public static int maxHeapMb() {
        return (int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / (1024 * 1024));
    }

    static int totalRamMb() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                return (int) Math.min(Integer.MAX_VALUE, os.getTotalMemorySize() / (1024 * 1024));
            }
        } catch (Throwable ignored) {
            // JVM sans l'extension com.sun.management
        }
        return 0;
    }

    /** Tas par défaut de OneLauncher : 2 Go sous 7 Go de RAM, 3 Go sous 11 Go, 4 Go au-delà. */
    static int defaultMemMaxMb(int totalMb) {
        if (totalMb < 7 * 1024) return 2048;
        if (totalMb < 11 * 1024) return 3072;
        return 4096;
    }

    private static boolean isCollectorFlag(String arg) {
        if (!arg.startsWith("-XX:")) return false;
        for (String c : COLLECTORS) if (arg.contains(c)) return true;
        return false;
    }

    private static boolean has(List<String> args, String prefix) {
        for (String a : args) if (a.startsWith(prefix)) return true;
        return false;
    }

    /** Options de OneLauncher pour une JVM (performance_flags + Xmx + module vectoriel). */
    static List<String> recommended(int major, boolean x64, int memMaxMb, List<String> existing) {
        List<String> flags = new ArrayList<>();
        flags.add("-Xms" + Math.min(memMaxMb, INITIAL_HEAP_MB) + "M");
        boolean chosen = false;
        for (String a : existing) chosen |= isCollectorFlag(a);
        boolean zgc = !chosen && major >= 21 && x64 && memMaxMb >= ZGC_MIN_HEAP_MB;
        boolean g1 = !chosen && !zgc && major >= 8;
        if ((zgc || g1) && major == 24) flags.add("-XX:+UnlockExperimentalVMOptions");
        if (zgc) {
            flags.add("-XX:+UseZGC");
            if (major <= 22) flags.add("-XX:+ZGenerational");
        } else if (g1) {
            flags.add("-XX:+UseG1GC");
            flags.add("-XX:+ParallelRefProcEnabled");
        }
        if ((zgc || g1) && major >= 24 && x64) flags.add("-XX:+UseCompactObjectHeaders");
        if (major >= 21) flags.add("--add-modules=jdk.incubator.vector");
        flags.add("-Xmx" + memMaxMb + "M");
        return flags;
    }

    /** Tas maximal à viser : celui du launcher s'il en donne un, sinon la valeur par défaut selon la RAM. */
    static int targetMemMb(List<String> args) {
        return has(args, "-Xmx") ? maxHeapMb() : defaultMemMaxMb(totalRamMb());
    }

    /** Options recommandées absentes de la ligne de commande actuelle (rien n'écrase un choix du joueur). */
    public static List<String> missing() {
        List<String> args = currentArgs();
        List<String> out = new ArrayList<>();
        for (String f : recommended(javaMajor(), is64Bit(), targetMemMb(args), args)) {
            String key = f.startsWith("-Xms") ? "-Xms" : f.startsWith("-Xmx") ? "-Xmx" : f;
            if (!has(args, key)) out.add(f);
        }
        return out;
    }

    /** Ligne de commande complète conseillée, à coller dans les arguments JVM du launcher. */
    public static String recommendedLine() {
        List<String> args = currentArgs();
        List<String> keep = new ArrayList<>();
        for (String a : args) if (isCollectorFlag(a)) keep.add(a);
        List<String> rec = recommended(javaMajor(), is64Bit(), targetMemMb(args), keep);
        rec.addAll(0, keep);
        return String.join(" ", rec);
    }

    /** Ramasse-miettes actif, d'après les MXBeans (« G1 Young Generation » -> « G1 »). */
    public static String collector() {
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            String n = b.getName();
            if (n.startsWith("G1")) return "G1";
            if (n.startsWith("ZGC")) return "ZGC";
            if (n.startsWith("Shenandoah")) return "Shenandoah";
            if (n.startsWith("PS ")) return "Parallel";
            if (n.equals("Copy") || n.equals("MarkSweepCompact")) return "Serial";
        }
        return "?";
    }

    public static String summary() {
        return String.format(Locale.ROOT, "Java %d · %s · %d MB heap", javaMajor(), collector(), maxHeapMb());
    }

    public static String status() {
        List<String> m = missing();
        return m.isEmpty() ? "Already optimal" : m.size() + " missing: " + String.join(" ", m);
    }

    /** Redémarre le jeu avec les options manquantes ajoutées (voir ModManager.relaunchCommand). */
    public static void restartOptimized() {
        optimizeNextLaunch = true;
        ModManager.restart();
    }

    /** Ajoute les options manquantes devant celles du launcher (ses choix restent prioritaires). */
    static List<String> tune(List<String> jvmArgs) {
        if (!optimizeNextLaunch) return jvmArgs;
        List<String> out = new ArrayList<>(missing());
        out.addAll(jvmArgs);
        AuroraClient.LOGGER.info("Restart with JVM options from OneLauncher: {}", out);
        return out;
    }

    // ======================================================================= GPU Windows

    private static final String GPU_KEY = "HKCU\\Software\\Microsoft\\DirectX\\UserGpuPreferences";
    private static final String GPU_DEDICATED = "GpuPreference=2;";

    static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** java.exe et javaw.exe du Java qui fait tourner le jeu (un launcher peut passer par l'un ou l'autre). */
    private static List<Path> javaExecutables() {
        List<Path> out = new ArrayList<>();
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        for (String exe : new String[]{"javaw.exe", "java.exe"}) {
            Path p = bin.resolve(exe);
            if (Files.exists(p)) out.add(p.toAbsolutePath());
        }
        return out;
    }

    /** reg.exe par chemin absolu : un reg.exe placé plus tôt dans le PATH ne peut pas le remplacer. */
    private static String reg() {
        return Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "reg.exe").toString();
    }

    private static String run(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        p.waitFor();
        return out;
    }

    /**
     * Préférence GPU dédié : écrite (on) ou retirée (off) pour le Java du jeu. Effet au prochain lancement.
     * Hors du thread de rendu : reg.exe prend quelques dizaines de ms.
     */
    public static void applyGpuPreference(boolean dedicated) {
        if (!windows()) return;
        Thread t = new Thread(() -> {
            try {
                String query = run(List.of(reg(), "query", GPU_KEY));
                for (Path exe : javaExecutables()) {
                    String name = exe.toString();
                    String current = registered(query, name);
                    if (dedicated) {
                        if (GPU_DEDICATED.equals(current)) continue;
                        run(List.of(reg(), "add", GPU_KEY, "/v", name, "/t", "REG_SZ", "/d", GPU_DEDICATED, "/f"));
                        AuroraClient.LOGGER.info("Dedicated GPU preference set for {}", name);
                    } else if (GPU_DEDICATED.equals(current)) {
                        // seulement « GPU dédié » : un autre choix fait dans Windows (économie d'énergie) reste
                        run(List.of(reg(), "delete", GPU_KEY, "/v", name, "/f"));
                        AuroraClient.LOGGER.info("Dedicated GPU preference removed for {}", name);
                    }
                }
            } catch (Exception e) {
                AuroraClient.LOGGER.warn("GPU preference: {}", e.toString());
            }
        }, "Paranoia-GpuPreference");
        t.setDaemon(true);
        t.start();
    }

    private static String registered(String query, String name) {
        for (String line : query.split("\\R")) {
            int i = line.indexOf("REG_SZ");
            if (i < 0) continue;
            if (line.substring(0, i).trim().equalsIgnoreCase(name)) return line.substring(i + 6).trim();
        }
        return null;
    }
}
