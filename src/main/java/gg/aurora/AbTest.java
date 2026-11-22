package gg.aurora;

import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * A/B dans une même session (outil de dev) : alterne deux implémentations toutes les 120 images,
 * pour mesurer la différence de temps d'image sans le bruit d'un run à l'autre (autres programmes,
 * boost du CPU, chauffe). Résultat dans les logs : médiane et moyenne par variante, écart et
 * intervalle de confiance approximatif (écart-type des moyennes par fenêtre).
 */
public final class AbTest {
    private AbTest() {}

    private static IntConsumer apply;
    private static String[] names;
    private static int variants, current, frameInWindow, windows, maxWindows;
    private static long last;
    private static final int WINDOW = 120, SKIP = 10;
    private static long[][] frames;
    private static int[] counts;
    private static double[][] windowMeans;
    private static int[] windowCounts;
    private static double acc;
    private static int accN;
    public static boolean running;

    /** Démarre : apply(i) active la variante i. seconds ≈ durée totale. */
    public static void start(String[] variantNames, IntConsumer apply, int totalWindows) {
        AbTest.apply = apply;
        names = variantNames;
        variants = variantNames.length;
        maxWindows = totalWindows;
        frames = new long[variants][200_000];
        counts = new int[variants];
        windowMeans = new double[variants][totalWindows];
        windowCounts = new int[variants];
        current = 0;
        windows = 0;
        frameInWindow = 0;
        last = 0;
        apply.accept(0);
        running = true;
    }

    /** À chaque image. */
    public static void frame() {
        if (!running) return;
        long now = System.nanoTime();
        if (last != 0 && frameInWindow >= SKIP) { // les premières images après bascule sont jetées
            long d = now - last;
            if (counts[current] < frames[current].length) frames[current][counts[current]++] = d;
            acc += d;
            accN++;
        }
        last = now;
        if (++frameInWindow >= WINDOW) {
            if (accN > 0 && windowCounts[current] < maxWindows) windowMeans[current][windowCounts[current]++] = acc / accN;
            acc = 0;
            accN = 0;
            frameInWindow = 0;
            windows++;
            if (windows >= maxWindows) {
                running = false;
                apply.accept(0);
                report();
                return;
            }
            current = (current + 1) % variants;
            apply.accept(current);
        }
    }

    public static boolean done() {
        return !running && frames != null;
    }

    private static void report() {
        StringBuilder sb = new StringBuilder("A/B résultat :\n");
        double base = 0;
        for (int v = 0; v < variants; v++) {
            long[] f = Arrays.copyOf(frames[v], counts[v]);
            Arrays.sort(f);
            double sum = 0;
            for (long x : f) sum += x;
            double mean = sum / Math.max(1, f.length) / 1000.0, median = f.length == 0 ? 0 : f[f.length / 2] / 1000.0;
            int k = Math.max(1, f.length / 100);
            double low = 0;
            for (int i = f.length - k; i < f.length; i++) low += f[i];
            low = low / k / 1000.0;
            double[] wm = Arrays.copyOf(windowMeans[v], windowCounts[v]);
            double wmMean = 0;
            for (double x : wm) wmMean += x;
            wmMean /= Math.max(1, wm.length);
            double var = 0;
            for (double x : wm) var += (x - wmMean) * (x - wmMean);
            double se = Math.sqrt(var / Math.max(1, wm.length - 1) / Math.max(1, wm.length)) / 1000.0;
            if (v == 0) base = mean;
            sb.append(String.format(java.util.Locale.ROOT, "  %-28s moyenne %7.1f µs  médiane %7.1f µs  1%%-pire %7.1f µs  (±%.1f µs, %d images)  écart vs A %+7.1f µs%n",
                    names[v], mean, median, low, 1.96 * se, f.length, mean - base));
        }
        AuroraClient.LOGGER.info(sb.toString());
    }
}
