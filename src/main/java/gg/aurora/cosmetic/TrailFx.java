package gg.aurora.cosmetic;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import gg.aurora.module.ClientModules;
import gg.aurora.nametag.NametagSync;
import gg.aurora.render.FxRender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Traînée des joueurs du client (sprint, élytre) : ruban lissé (courbe Catmull-Rom) accroché au bas
 * du dos, qui s'affine et s'efface vers la queue avec un dégradé de deux couleurs et un cœur lumineux.
 * Styles : ruban, double ruban (aux épaules), comète (ruban + étincelles), fumée.
 */
public final class TrailFx {
    private TrailFx() {}

    private static final Identifier WHITE = Identifier.fromNamespaceAndPath("aurora", "textures/cosmetic/white.png");
    private static final Identifier GLOW = Identifier.fromNamespaceAndPath("aurora", "textures/ui/glow.png");

    /** Point de la traînée : position monde (centre) et vecteur « côté » (pour le double ruban), instant. */
    private record P(double x, double y, double z, float sx, float sz, long t) {}

    private static final Map<Integer, ArrayDeque<P>> POINTS = new HashMap<>();
    // tampons réutilisés (pas d'allocation par point)
    private static final Vector3f A = new Vector3f(), B = new Vector3f(), D = new Vector3f(), S = new Vector3f();

    /** Réglages de ma traînée, relus des options sans passer par le texte (aucune allocation par image). */
    private static final int[] MINE = new int[3];
    /** Codes des autres joueurs déjà décodés (un code ne change pas de sens : pas d'invalidation nécessaire). */
    private static final java.util.Map<String, int[]> PARSED = new java.util.HashMap<>();

    private static int[] trailOf(Player p) {
        if (!gg.aurora.Settings.showTrails) return null;
        Minecraft mc = Minecraft.getInstance();
        if (p == mc.player) {
            if (!ClientModules.TRAIL.enabled) return null;
            var t = ClientModules.TRAIL;
            MINE[0] = Math.max(0, Math.min(ClientModules.TRAIL_IDS.length - 1, (int) t.opt("style", 0)));
            MINE[1] = (int) t.opt("color", 0x9D7CFF) & 0xFFFFFF;
            MINE[2] = (int) t.opt("color2", 0xFF6EC7) & 0xFFFFFF;
            return MINE;
        }
        if (!gg.aurora.Settings.showOthers) return null;
        var prof = NametagSync.profile(p.getUUID());
        if (prof == null || prof.trail() == null || prof.trail().isEmpty()) return null;
        int[] r = PARSED.get(prof.trail());
        if (r == null && !PARSED.containsKey(prof.trail())) {
            if (PARSED.size() > 256) PARSED.clear(); // borne : codes de joueurs croisés
            r = ClientModules.parseTrail(prof.trail());
            PARSED.put(prof.trail(), r);
        }
        return r;
    }

    public static void renderWorld(PoseStack pose, MultiBufferSource consumers, Vec3 cam, float pt) {
        Minecraft mc = Minecraft.getInstance();
        if (consumers == null || mc.level == null) return;
        if (!Cosmetics.anyTrail()) {
            if (!POINTS.isEmpty()) POINTS.clear();
            return;
        }
        long now = System.currentTimeMillis();
        long life = Math.round(ClientModules.TRAIL.opt("life", 0.8) * 1000);
        float width = (float) ClientModules.TRAIL.opt("width", 0.14);
        RenderType ribbonT = FxRender.additive(WHITE), glowT = FxRender.additive(GLOW);
        VertexConsumer ribbon = null, glow = null;
        Quaternionf rot = mc.gameRenderer.getMainCamera().rotation();
        Vector3f right = rot.transform(new Vector3f(1, 0, 0)), up = rot.transform(new Vector3f(0, 1, 0));
        Matrix4f m = pose.last().pose();
        for (Player p : mc.level.players()) {
            int[] tr = trailOf(p);
            if (tr == null || p.isInvisible() || p.distanceToSqr(cam) > 64 * 64) {
                POINTS.remove(p.getId());
                continue;
            }
            ArrayDeque<P> pts = POINTS.computeIfAbsent(p.getId(), k -> new ArrayDeque<>());
            boolean flying = p.isFallFlying();
            boolean moving = p.getDeltaMovement().horizontalDistanceSqr() > 0.004;
            boolean active = flying || p.isSprinting() && moving || (p == mc.player && ClientModules.TRAIL.flag("walk", false) && moving);
            if (active) {
                float yaw = (float) Math.toRadians(Mth.rotLerp(pt, p.yBodyRotO, p.yBodyRot));
                float fx = -Mesh.sin(yaw), fz = Mesh.cos(yaw);
                // bas du dos, un peu derrière le joueur (sur le ventre en élytre)
                double x = Mth.lerp(pt, p.xo, p.getX()) - fx * 0.28, z = Mth.lerp(pt, p.zo, p.getZ()) - fz * 0.28;
                double y = Mth.lerp(pt, p.yo, p.getY()) + (flying ? 0.3 : 0.85);
                P last = pts.peekLast();
                if (last == null || (last.x() - x) * (last.x() - x) + (last.y() - y) * (last.y() - y) + (last.z() - z) * (last.z() - z) > 0.0025) {
                    pts.addLast(new P(x, y, z, -fz, fx, now));
                    if (pts.size() > 160) pts.pollFirst();
                }
            }
            while (!pts.isEmpty() && now - pts.peekFirst().t() > life) pts.pollFirst();
            if (pts.size() < 3) continue;
            int style = tr[0], c1 = tr[1] & 0xFFFFFF, c2 = (tr.length > 2 ? tr[2] : tr[1]) & 0xFFFFFF;
            P[] arr = pts.toArray(new P[0]);
            if (style == 3) {
                if (glow == null) glow = consumers.getBuffer(glowT);
                smoke(glow, m, right, up, arr, cam, now, life, c1, c2, width);
                continue;
            }
            if (ribbon == null) ribbon = consumers.getBuffer(ribbonT);
            if (style == 1) {
                // deux rubans fins aux épaules
                ribbon(ribbon, m, arr, cam, now, life, c1, c2, width * 0.6f, 0.22f);
                ribbon(ribbon, m, arr, cam, now, life, c1, c2, width * 0.6f, -0.22f);
            } else {
                ribbon(ribbon, m, arr, cam, now, life, c1, c2, width, 0);
            }
            if (style == 2) {
                if (glow == null) glow = consumers.getBuffer(glowT);
                sparks(glow, m, right, up, arr, cam, now, life, c1);
            }
        }
        if (consumers instanceof MultiBufferSource.BufferSource bs) {
            if (ribbon != null) bs.endBatch(ribbonT);
            if (glow != null) bs.endBatch(glowT);
        }
    }

    /** Catmull-Rom entre p1 et p2 (p0, p3 voisins), t dans [0, 1]. */
    private static double cr(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    private static final int SUB = 4;

    /** Ruban face caméra lissé, effilé vers la queue, dégradé c1 (tête) -> c2 (queue), cœur blanc. */
    private static void ribbon(VertexConsumer vc, Matrix4f m, P[] a, Vec3 cam, long now, long life, int c1, int c2, float width, float side) {
        int n = a.length;
        double px = 0, py = 0, pz = 0, pu = 0;
        boolean first = true;
        for (int i = 0; i < n - 1; i++) {
            P p0 = a[Math.max(0, i - 1)], p1 = a[i], p2 = a[i + 1], p3 = a[Math.min(n - 1, i + 2)];
            for (int k = 0; k <= SUB; k++) {
                if (k == 0 && !first) continue;
                double t = k / (double) SUB;
                double x = cr(p0.x() + p0.sx() * side, p1.x() + p1.sx() * side, p2.x() + p2.sx() * side, p3.x() + p3.sx() * side, t);
                double y = cr(p0.y(), p1.y(), p2.y(), p3.y(), t);
                double z = cr(p0.z() + p0.sz() * side, p1.z() + p1.sz() * side, p2.z() + p2.sz() * side, p3.z() + p3.sz() * side, t);
                double age = Mth.lerp(t, now - p1.t(), now - p2.t());
                double u = Math.max(0, Math.min(1, 1 - age / life)); // 1 = tête, 0 = queue
                if (!first) segment(vc, m, cam, px, py, pz, pu, x, y, z, u, c1, c2, width);
                px = x;
                py = y;
                pz = z;
                pu = u;
                first = false;
            }
        }
    }

    private static void segment(VertexConsumer vc, Matrix4f m, Vec3 cam, double ax, double ay, double az, double ua,
                                double bx, double by, double bz, double ub, int c1, int c2, float width) {
        A.set((float) (ax - cam.x), (float) (ay - cam.y), (float) (az - cam.z));
        B.set((float) (bx - cam.x), (float) (by - cam.y), (float) (bz - cam.z));
        D.set(B).sub(A);
        // côté = perpendiculaire au segment et à la direction de la caméra
        S.set(D).cross(A.x + B.x, A.y + B.y, A.z + B.z);
        if (S.lengthSquared() < 1e-10f) return;
        S.normalize();
        float wa = width * (float) Math.pow(ua, 0.7), wb = width * (float) Math.pow(ub, 0.7);
        int ca = Mesh.lerp(c2, c1, (float) ua) & 0xFFFFFF, cb = Mesh.lerp(c2, c1, (float) ub) & 0xFFFFFF;
        float aa = (float) (ua * ua), ab = (float) (ub * ub);
        quad(vc, m, wa, wb, argb(Math.round(150 * aa), ca), argb(Math.round(150 * ab), cb));
        // cœur lumineux plus fin
        quad(vc, m, wa * 0.28f, wb * 0.28f, argb(Math.round(170 * aa), 0xFFFFFF), argb(Math.round(170 * ab), 0xFFFFFF));
    }

    private static void quad(VertexConsumer vc, Matrix4f m, float wa, float wb, int ca, int cb) {
        vtx(vc, m, A.x - S.x * wa, A.y - S.y * wa, A.z - S.z * wa, 0, 0, ca);
        vtx(vc, m, A.x + S.x * wa, A.y + S.y * wa, A.z + S.z * wa, 0, 1, ca);
        vtx(vc, m, B.x + S.x * wb, B.y + S.y * wb, B.z + S.z * wb, 1, 1, cb);
        vtx(vc, m, B.x - S.x * wb, B.y - S.y * wb, B.z - S.z * wb, 1, 0, cb);
    }

    /** Comète : étincelles qui se détachent du ruban et tombent doucement. */
    private static void sparks(VertexConsumer vc, Matrix4f m, Vector3f r, Vector3f u, P[] a, Vec3 cam, long now, long life, int col) {
        for (int i = 0; i < a.length; i += 2) {
            P p = a[i];
            float age = (now - p.t()) / (float) life, f = 1 - age;
            int seed = (int) (p.t() * 31 + i * 17);
            float jx = ((seed * 1103515245 >>> 8) & 255) / 255f - 0.5f, jz = ((seed * 22695477 >>> 8) & 255) / 255f - 0.5f;
            float x = (float) (p.x() - cam.x) + jx * 0.4f * age, y = (float) (p.y() - cam.y) - age * age * 0.5f, z = (float) (p.z() - cam.z) + jz * 0.4f * age;
            sprite(vc, m, r, u, x, y, z, 0.035f * f + 0.01f, 0xFFFFFF, 0.9f * f);
            sprite(vc, m, r, u, x, y, z, 0.11f * f, col, 0.3f * f);
        }
    }

    /** Fumée : bouffées douces qui grossissent et s'éclaircissent en vieillissant. */
    private static void smoke(VertexConsumer vc, Matrix4f m, Vector3f r, Vector3f u, P[] a, Vec3 cam, long now, long life, int c1, int c2, float width) {
        for (int i = 0; i < a.length; i++) {
            P p = a[i];
            float age = (now - p.t()) / (float) life, f = 1 - age;
            float x = (float) (p.x() - cam.x), y = (float) (p.y() - cam.y) + age * 0.25f, z = (float) (p.z() - cam.z);
            sprite(vc, m, r, u, x, y, z, width * (1.2f + age * 3f), Mesh.lerp(c1, c2, age) & 0xFFFFFF, 0.16f * f * f);
        }
    }

    private static int argb(int a, int rgb) {
        return (Math.max(0, Math.min(255, a)) << 24) | (rgb & 0xFFFFFF);
    }

    private static void sprite(VertexConsumer vc, Matrix4f m, Vector3f r, Vector3f u, float x, float y, float z, float s, int rgb, float a) {
        if (a <= 0.004f) return;
        int col = argb(Math.round(a * 255), rgb);
        float rx = r.x * s, ry = r.y * s, rz = r.z * s, ux = u.x * s, uy = u.y * s, uz = u.z * s;
        vtx(vc, m, x - rx - ux, y - ry - uy, z - rz - uz, 0, 1, col);
        vtx(vc, m, x + rx - ux, y + ry - uy, z + rz - uz, 1, 1, col);
        vtx(vc, m, x + rx + ux, y + ry + uy, z + rz + uz, 1, 0, col);
        vtx(vc, m, x - rx + ux, y - ry + uy, z - rz + uz, 0, 0, col);
    }

    private static void vtx(VertexConsumer vc, Matrix4f m, float x, float y, float z, float u, float v, int col) {
        vc.addVertex(m, x, y, z).setColor(col).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(0, 1, 0);
    }
}
