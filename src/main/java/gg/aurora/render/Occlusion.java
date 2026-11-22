package gg.aurora.render;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Culling par occlusion (inspiré d'Entity Culling) : une entité ou un bloc-entité entièrement caché
 * derrière des blocs opaques n'est pas dessiné. Des rayons partent de la caméra vers les coins et le
 * centre de la boîte ; si aucun ne passe, l'objet est caché. Résultat mis en cache quelques ticks,
 * avec un budget de rayons par image pour garder un coût fixe.
 */
public final class Occlusion {
    private Occlusion() {}

    /** clé -> (tick du test << 1) | visible */
    private static final Long2LongOpenHashMap CACHE = new Long2LongOpenHashMap();
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();
    private static int budget;
    private static Level lastLevel;

    private static final int RECHECK_TICKS = 4, BUDGET = 96;

    /** Une fois par image : budget de rayons remis à zéro. */
    public static void newFrame() {
        budget = BUDGET;
        if (CACHE.size() > 8192) CACHE.clear();
    }

    /** Vrai si la boîte peut être visible depuis la caméra (faux uniquement si sûrement cachée). */
    public static boolean visible(long key, AABB box, double pad) {
        return visible(key, box.minX - pad, box.minY - pad, box.minZ - pad, box.maxX + pad, box.maxY + pad, box.maxZ + pad);
    }

    /** Version sans objet : coordonnées de la boîte (aucune allocation par entité et par image). */
    public static boolean visible(long key, double x0, double y0, double z0, double x1, double y1, double z1) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) return true;
        if (level != lastLevel) {
            lastLevel = level;
            CACHE.clear();
        }
        long now = level.getGameTime();
        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        double cx = Math.max(x0, Math.min(cam.x, x1)), cy = Math.max(y0, Math.min(cam.y, y1)), cz = Math.max(z0, Math.min(cam.z, z1));
        double d2 = (cx - cam.x) * (cx - cam.x) + (cy - cam.y) * (cy - cam.y) + (cz - cam.z) * (cz - cam.z);
        if (d2 < 6 * 6) return true; // tout près : toujours visible (pas de clignotement)
        long c = CACHE.getOrDefault(key, Long.MIN_VALUE);
        if (c != Long.MIN_VALUE && now - (c >> 1) < RECHECK_TICKS) return (c & 1) != 0;
        if (budget <= 0) return c == Long.MIN_VALUE || (c & 1) != 0; // pas le temps ce tour : dernier résultat
        budget--;
        boolean vis = test(level, cam, x0, y0, z0, x1, y1, z1);
        // caché -> visible immédiatement ; visible -> caché seulement si confirmé (évite le scintillement)
        if (!vis && c != Long.MIN_VALUE && (c & 1) != 0 && now - (c >> 1) < RECHECK_TICKS * 2) vis = true;
        CACHE.put(key, (now << 1) | (vis ? 1 : 0));
        return vis;
    }

    private static boolean test(Level level, Vec3 cam, double x0, double y0, double z0, double x1, double y1, double z1) {
        // caméra dans un bloc opaque (spectateur…) : on ne sait pas, on affiche
        POS.set(Math.floor(cam.x), Math.floor(cam.y), Math.floor(cam.z));
        if (level.getBlockState(POS).isSolidRender()) return true;
        double mx = (x0 + x1) / 2, my = (y0 + y1) / 2, mz = (z0 + z1) / 2;
        if (ray(level, cam, mx, my, mz)) return true;
        // coins légèrement rentrés (un coin pile sur un bloc serait toujours « caché »)
        double ex = (x1 - x0) * 0.45, ey = (y1 - y0) * 0.45, ez = (z1 - z0) * 0.45;
        for (int i = 0; i < 8; i++) {
            if (ray(level, cam, mx + ((i & 1) == 0 ? -ex : ex), my + ((i & 2) == 0 ? -ey : ey), mz + ((i & 4) == 0 ? -ez : ez))) return true;
        }
        return false;
    }

    /** Parcours voxel (DDA) de la caméra au point : faux dès qu'un bloc plein et opaque coupe le rayon. */
    private static boolean ray(Level level, Vec3 cam, double tx, double ty, double tz) {
        double dx = tx - cam.x, dy = ty - cam.y, dz = tz - cam.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) return true;
        dx /= len;
        dy /= len;
        dz /= len;
        int x = (int) Math.floor(cam.x), y = (int) Math.floor(cam.y), z = (int) Math.floor(cam.z);
        int ex = (int) Math.floor(tx), ey = (int) Math.floor(ty), ez = (int) Math.floor(tz);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tdx = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx), tdy = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy),
                tdz = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
        double tmx = dx == 0 ? Double.MAX_VALUE : ((dx > 0 ? x + 1 - cam.x : cam.x - x) * tdx);
        double tmy = dy == 0 ? Double.MAX_VALUE : ((dy > 0 ? y + 1 - cam.y : cam.y - y) * tdy);
        double tmz = dz == 0 ? Double.MAX_VALUE : ((dz > 0 ? z + 1 - cam.z : cam.z - z) * tdz);
        for (int i = 0; i < 256; i++) {
            if (x == ex && y == ey && z == ez) return true;
            if (tmx < tmy && tmx < tmz) {
                x += sx;
                tmx += tdx;
            } else if (tmy < tmz) {
                y += sy;
                tmy += tdy;
            } else {
                z += sz;
                tmz += tdz;
            }
            if (x == ex && y == ey && z == ez) return true; // la case de la cible ne cache pas la cible
            POS.set(x, y, z);
            if (level.getBlockState(POS).isSolidRender()) return false;
        }
        return true;
    }
}
