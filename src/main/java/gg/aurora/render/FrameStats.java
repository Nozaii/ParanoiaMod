package gg.aurora.render;

import java.util.Arrays;

/**
 * Temps des dernières images (anneau de 1024) et « 1 % low » recalculé deux fois par seconde :
 * coût négligeable (un tri de 1024 valeurs toutes les 500 ms), rien par image hormis une écriture.
 */
public final class FrameStats {
    private FrameStats() {}

    private static final long[] RING = new long[1024];
    private static final long[] SORT = new long[1024];
    private static int pos, filled;
    private static long last, lastCalc;
    private static int low1;

    /** Appelé à chaque image du monde. */
    private static Object level;
    private static long settleUntil;

    /** Last frame times in ms, oldest first. Returns how many were written. */
    public static int recent(float[] out) {
        int n = Math.min(out.length, filled);
        for (int i = 0; i < n; i++) {
            int k = Math.floorMod(pos - n + i, RING.length);
            out[i] = RING[k] / 1e6f;
        }
        return n;
    }

    public static void frame() {
        long now = System.nanoTime();
        var lv = net.minecraft.client.Minecraft.getInstance().level;
        if (lv != level) {
            level = lv;
            filled = 0;
            pos = 0;
            low1 = 0;
            settleUntil = now + 4_000_000_000L;
        }
        if (now < settleUntil) {
            last = now;
            return;
        }
        if (last != 0) {
            long dt = now - last;
            // une pause (menu, chargement, fenêtre réduite) n'est pas une image lente
            if (dt < 250_000_000L) {
                RING[pos] = dt;
                pos = (pos + 1) & 1023;
                if (filled < 1024) filled++;
            }
        }
        last = now;
    }

    /** FPS moyen des 1 % d'images les plus lentes (sur les ~1024 dernières). */
    public static int low1() {
        long now = System.nanoTime();
        if (now - lastCalc > 500_000_000L && filled >= 100) {
            lastCalc = now;
            int n = filled;
            System.arraycopy(RING, 0, SORT, 0, n);
            Arrays.sort(SORT, 0, n);
            int k = Math.max(1, n / 100);
            long sum = 0;
            for (int i = n - k; i < n; i++) sum += SORT[i];
            low1 = (int) Math.round(1e9 / (sum / (double) k));
        }
        return low1;
    }
}
