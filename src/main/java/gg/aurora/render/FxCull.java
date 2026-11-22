package gg.aurora.render;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Évite de calculer les effets des joueurs hors du champ de vision (derrière la caméra). */
public final class FxCull {
    private FxCull() {}

    private static final Vector3f FWD = new Vector3f();
    private static float minDot = -1;

    /** À appeler une fois par image, avant les tests. */
    public static void begin() {
        var mc = Minecraft.getInstance();
        mc.gameRenderer.getMainCamera().rotation().transform(FWD.set(0, 0, -1));
        // demi-angle du champ de vision le plus large (diagonale) + marge
        double fov = Math.toRadians(mc.options.fov().get() * 1.6);
        minDot = (float) Math.cos(Math.min(Math.PI, fov * 0.5 + 0.35));
    }

    /** Vrai si l'entité (avec un rayon de marge pour ses effets) peut être visible. */
    public static boolean visible(Entity e, Vec3 cam, double margin) {
        double dx = e.getX() - cam.x, dy = e.getY() + e.getBbHeight() * 0.5 - cam.y, dz = e.getZ() - cam.z;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d <= margin + 1) return true;
        double dot = (dx * FWD.x + dy * FWD.y + dz * FWD.z) / d;
        // marge angulaire due à la taille de l'effet
        return dot >= minDot - margin / d;
    }
}
