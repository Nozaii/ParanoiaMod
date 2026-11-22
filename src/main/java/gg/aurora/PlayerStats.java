package gg.aurora;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Statistiques de la page de joueur, comptées par le mod : temps de jeu (en monde), kills de joueurs
 * (un joueur que j'ai frappé dans les 4 dernières secondes et qui meurt), morts, meilleure série.
 * Envoyées au serveur toutes les 5 minutes (le serveur plafonne chaque envoi).
 */
public final class PlayerStats {
    private PlayerStats() {}

    private static int ticks, kills, deaths, streak, bestStreak;
    private static boolean wasDead;
    private static Entity lastHit;
    private static long lastHitAt, lastSent = System.currentTimeMillis();

    /** Appelé à chaque coup porté (Modules.onAttack). */
    public static void onAttack(Entity e) {
        if (e instanceof Player) {
            lastHit = e;
            lastHitAt = System.currentTimeMillis();
        }
    }

    /** Mort d'une entité vue par le client (événement 3). */
    public static void onDeath(LivingEntity e) {
        Minecraft mc = Minecraft.getInstance();
        if (e == mc.player || !(e instanceof Player)) return;
        if (e == lastHit && System.currentTimeMillis() - lastHitAt < 4000) {
            kills++;
            streak++;
            bestStreak = Math.max(bestStreak, streak);
            lastHit = null;
        }
    }

    public static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        if (!mc.isPaused()) ticks++;
        boolean dead = mc.player.isDeadOrDying();
        if (dead && !wasDead) {
            deaths++;
            streak = 0;
        }
        wasDead = dead;
        if (System.currentTimeMillis() - lastSent > 5 * 60_000L) flush();
    }

    /** Envoie ce qui a été compté depuis le dernier envoi (rien si rien de neuf). */
    public static void flush() {
        lastSent = System.currentTimeMillis();
        int minutes = ticks / 1200;
        if (minutes == 0 && kills == 0 && deaths == 0) return;
        JsonObject b = new JsonObject();
        b.addProperty("minutes", minutes);
        b.addProperty("kills", kills);
        b.addProperty("deaths", deaths);
        b.addProperty("streak", bestStreak);
        ticks -= minutes * 1200;
        kills = 0;
        deaths = 0;
        bestStreak = streak;
        PlayerPages.reportStats(b);
    }
}
