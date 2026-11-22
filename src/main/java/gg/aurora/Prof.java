package gg.aurora;

/**
 * Chronomètre par fonctionnalité, actif seulement pendant /paranoia bench (sinon un seul test de booléen).
 * Donne le temps CPU réel de chaque système par image, sans le biais d'attribution du profileur
 * (l'inlining du JIT range souvent le coût d'une boucle sous la dernière méthode appelée).
 * Usage : {@code long t = Prof.start(); ... Prof.stop(Prof.PET, t);}
 */
public final class Prof {
    private Prof() {}

    public static final int PET = 0, PLAYER_COSMETICS = 1, TRAIL = 2, AURA = 3, KILL = 4, HAND_GLOW = 5, HUD = 6, SSR = 7, SKY = 8,
            WORLD_PANEL = 9, OCCLUSION = 10, POSTFX = 11, AMBIENT = 12, VISUAL_WORLD = 13, TICK = 14, BLUR = 15, NAMETAG = 16, PET_FLUSH = 17, PET_BELL = 18, PET_GLOW = 19, PET_PRE = 20, SSR_SETUP = 21, SSR_PASS = 22, SSR_COPY = 23, FX_FLUSH = 24;
    public static final String[] NAMES = {"Familier", "Cosmétiques joueur", "Traînée", "Aura (particules)", "Hit Effects", "Hand Aura",
            "HUD Paranoia", "Sol miroir (SSR)", "Ciel custom", "Menu monde", "Occlusion", "Filtres / FSR", "Particules d'ambiance",
            "Rendu monde (modules)", "Tick client Paranoia", "Motion blur", "Nametags", "  dont familier : envoi GPU", "  dont familier : cloche", "  dont familier : lumière", "  dont familier : avant dessin", "  dont SSR : préparation", "  dont SSR : passe", "  dont SSR : copie", "Lumière des effets (lot commun)"};

    /** Vrai pendant la mesure : les appels coûtent alors deux nanoTime. */
    public static boolean on; // rendu et ticks : même thread, pas besoin de volatile
    private static final long[] NS = new long[NAMES.length];
    private static final long[] CALLS = new long[NAMES.length];
    /** Sommets émis par les cosmétiques procéduraux (Mesh). */
    public static long vertices;

    public static long start() {
        return on ? System.nanoTime() : 0;
    }

    public static void stop(int id, long t0) {
        if (!on || t0 == 0) return;
        NS[id] += System.nanoTime() - t0;
        CALLS[id]++;
    }

    public static void reset() {
        java.util.Arrays.fill(NS, 0);
        java.util.Arrays.fill(CALLS, 0);
        vertices = 0;
    }

    /** Tableau : µs par image pour chaque système (moyenne sur la mesure). */
    public static String report(long frames) {
        StringBuilder sb = new StringBuilder("Temps CPU Paranoia par image (µs, moyenne sur ").append(frames).append(" images) :\n");
        long total = 0;
        Integer[] idx = new Integer[NAMES.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Long.compare(NS[b], NS[a]));
        for (int i : idx) {
            if (CALLS[i] == 0) continue;
            total += NS[i];
            sb.append(String.format(java.util.Locale.ROOT, "  %-26s %8.1f µs  (%d appels)%n", NAMES[i], NS[i] / 1000.0 / Math.max(1, frames), CALLS[i]));
        }
        sb.append(String.format(java.util.Locale.ROOT, "  %-26s %8.1f µs%n", "TOTAL mesuré", total / 1000.0 / Math.max(1, frames)));
        sb.append(String.format(java.util.Locale.ROOT, "  Sommets cosmétiques : %d par image%n", vertices / Math.max(1, frames)));
        return sb.toString();
    }
}
