package gg.aurora.cosmetic;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

import static gg.aurora.cosmetic.Mesh.argb;
import static gg.aurora.cosmetic.Mesh.v;

/** Familiers « OVNI » (soucoupe qui flotte) et « Verity » (petite boule qui roule au sol). */
final class PetModels {
    private PetModels() {}

    private static final int FULL = 0xF000F0;

    // =================================================================== OVNI

    /** Soucoupe : coque métallique en lentille, dôme vitré, feux qui tournent, faisceau léger. */
    static void ufo(PoseStack pose, MultiBufferSource consumers, RenderType solidT, RenderType glassT, RenderType glowT,
                    float x, float y, float z, float vx, float vz, float t, int accent) {
        pose.pushPose();
        pose.translate(x, y, z);
        // inclinaison vers l'avant quand elle se déplace, léger roulis au repos
        float speed = (float) Math.sqrt(vx * vx + vz * vz);
        float dirA = (float) Math.atan2(vz, vx);
        pose.mulPose(com.mojang.math.Axis.YP.rotation(-dirA));
        pose.mulPose(com.mojang.math.Axis.ZP.rotation(-Math.min(0.35f, speed * 0.2f) + Mesh.sin(t * 1.3f) * 0.05f));
        pose.mulPose(com.mojang.math.Axis.YP.rotation(dirA));
        pose.mulPose(com.mojang.math.Axis.YP.rotation(t * 0.8f)); // la coque tourne doucement
        PoseStack.Pose base = pose.last();
        final int S = UFO_SEGMENTS;
        final float R = 0.30f, Rin = 0.13f;
        int hull = Mesh.lerp(0xB8BFCC, accent, 0.25f) & 0xFFFFFF, hullDark = 0x5A6070, rim = 0xE6EBF2;

        int lit = PetFx.petLight;
        Mesh m = Mesh.shared(base, consumers.getBuffer(solidT), lit);
        for (int i = 0; i < S; i++) {
            float c0 = COS[i], s0 = SIN[i], c1 = COS[i + 1], s1 = SIN[i + 1];
            // dessus : du bord vers la base du dôme
            m.quad(Mesh.p(R * c0, 0, R * s0), Mesh.p(Rin * c0, 0.075f, Rin * s0), Mesh.p(Rin * c1, 0.075f, Rin * s1), Mesh.p(R * c1, 0, R * s1),
                    argb(255, rim), argb(255, hull), argb(255, hull), argb(255, rim));
            // dessous : bord vers le moyeu
            m.quad(Mesh.p(R * c1, 0, R * s1), Mesh.p(Rin * c1, -0.06f, Rin * s1), Mesh.p(Rin * c0, -0.06f, Rin * s0), Mesh.p(R * c0, 0, R * s0),
                    argb(255, hull), argb(255, hullDark), argb(255, hullDark), argb(255, hull));
            // moyeu
            m.tri(Mesh.p(Rin * c0, -0.06f, Rin * s0), Mesh.p(Rin * c1, -0.06f, Rin * s1), Mesh.p(0, -0.075f, 0),
                    argb(255, hullDark), argb(255, hullDark), argb(255, 0x3A3F4A));
        }
        if (consumers instanceof MultiBufferSource.BufferSource bs) bs.endBatch(solidT);

        // dôme vitré (demi-sphère translucide)
        Mesh g = Mesh.shared(base, consumers.getBuffer(glassT), lit);
        final int L = DOME_ROWS;
        for (int j = 0; j < L; j++) {
            int ca = argb(Math.round(90 + 60 * j / (float) L), accent);
            for (int i = 0; i < S; i++) {
                g.quad(DOME[j][i], DOME[j][i + 1], DOME[j + 1][i + 1], DOME[j + 1][i], ca);
            }
        }
        if (consumers instanceof MultiBufferSource.BufferSource bs) bs.endBatch(glassT);

        // feux du bord (clignotent en tournant) et faisceau sous la soucoupe
        Mesh l = Mesh.shared(base, consumers.getBuffer(glowT), FULL).additive().gain(PetFx.gain());
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8;
            float on = 0.35f + 0.65f * (float) Math.max(0, Mesh.sin(t * 4 - i * 0.8));
            float cx = Mesh.cos(a) * (R + 0.005f), cz = Mesh.sin(a) * (R + 0.005f), s = 0.022f;
            int col = argb(Math.round(255 * on), i % 2 == 0 ? accent : 0xFFD65E);
            l.quad(Mesh.p(cx - s, -s, cz), Mesh.p(cx + s, -s, cz), Mesh.p(cx + s, s, cz), Mesh.p(cx - s, s, cz), col);
            l.quad(Mesh.p(cx, -s, cz - s), Mesh.p(cx, -s, cz + s), Mesh.p(cx, s, cz + s), Mesh.p(cx, s, cz - s), col);
        }
        float beam = 0.10f + 0.05f * Mesh.sin(t * 2.2f);
        for (int i = 0; i < S; i++) {
            l.quad(Mesh.p(0.06f * COS[i], -0.07f, 0.06f * SIN[i]), Mesh.p(0.06f * COS[i + 1], -0.07f, 0.06f * SIN[i + 1]),
                    Mesh.p(0.2f * COS[i + 1], -0.75f, 0.2f * SIN[i + 1]), Mesh.p(0.2f * COS[i], -0.75f, 0.2f * SIN[i]),
                    argb(Math.round(255 * beam), accent), argb(Math.round(255 * beam), accent), argb(0, accent), argb(0, accent));
        }
        if (consumers instanceof MultiBufferSource.BufferSource bs) bs.endBatch(glowT);
        pose.popPose();
    }

    private static final int UFO_SEGMENTS = 28, DOME_ROWS = 6;
    private static final float[] COS = new float[UFO_SEGMENTS + 1], SIN = new float[UFO_SEGMENTS + 1];
    /** The glass dome never changes shape: built once. */
    private static final Vector3f[][] DOME = new Vector3f[DOME_ROWS + 1][UFO_SEGMENTS + 1];

    static {
        for (int i = 0; i <= UFO_SEGMENTS; i++) {
            double a = Math.PI * 2 * i / UFO_SEGMENTS;
            COS[i] = Mesh.cos(a);
            SIN[i] = Mesh.sin(a);
            for (int j = 0; j <= DOME_ROWS; j++) {
                double p = Math.PI / 2 * j / DOME_ROWS;
                DOME[j][i] = new Vector3f((float) (Mesh.cos(a) * Mesh.cos(p)) * 0.13f, 0.07f + Mesh.sin(p) * 0.13f * 0.9f, (float) (Mesh.sin(a) * Mesh.cos(p)) * 0.13f);
            }
        }
    }

    // =================================================================== Verity

    /** x, y, z, vx, vz, quaternion (x, y, z, w) par joueur. */
    private static final Map<Integer, float[]> ROLL = new HashMap<>();
    static final float BALL = 0.28f;

    /**
     * Suit le joueur au sol en roulant : ressort amorti (pas d'à-coups), hauteur posée sur le bloc
     * en dessous, rotation = distance parcourue / rayon autour de l'axe perpendiculaire au mouvement.
     */
    static void verity(PoseStack pose, MultiBufferSource consumers, Player p, Vec3 cam, double px, double py, double pz,
                       float yaw, float dt, float t, String variant) {
        Minecraft mc = Minecraft.getInstance();
        float fx = -Mesh.sin(yaw), fz = Mesh.cos(yaw);
        float rx = -Mesh.cos(yaw), rz = -Mesh.sin(yaw);
        float tx = (float) px + rx * 1.3f - fx * 0.9f, tz = (float) pz + rz * 1.3f - fz * 0.9f;
        float[] s = ROLL.computeIfAbsent(p.getId(), k -> new float[]{tx, (float) py, tz, 0, 0, 0, 0, 0, 1});
        if (Math.abs(s[0] - tx) > 10 || Math.abs(s[2] - tz) > 10 || Math.abs(s[1] - py) > 6) {
            s[0] = tx;
            s[2] = tz;
            s[1] = (float) py;
            s[3] = s[4] = 0;
        }
        // ressort critique : accélère doucement, s'arrête sans rebond
        float w = 5.5f;
        float ax = w * w * (tx - s[0]) - 2 * w * s[3], az = w * w * (tz - s[2]) - 2 * w * s[4];
        s[3] += ax * dt;
        s[4] += az * dt;
        float sp = (float) Math.sqrt(s[3] * s[3] + s[4] * s[4]), max = 9f;
        if (sp > max) {
            s[3] *= max / sp;
            s[4] *= max / sp;
        }
        float mx = s[3] * dt, mz = s[4] * dt;
        s[0] += mx;
        s[2] += mz;
        // sol : dessus du bloc sous la boule (cherche jusqu'à 3 blocs plus bas), descente adoucie
        float ground = (float) py;
        if (mc.level != null) {
            BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
            for (int dy = 1; dy >= -3; dy--) {
                bp.set(Math.floor(s[0]), Math.floor(py) + dy - 1, Math.floor(s[2]));
                var st = mc.level.getBlockState(bp);
                var shape = st.getCollisionShape(mc.level, bp);
                if (!shape.isEmpty()) {
                    ground = (float) (bp.getY() + shape.max(net.minecraft.core.Direction.Axis.Y));
                    break;
                }
            }
        }
        s[1] += (ground - s[1]) * (1 - (float) Math.exp(-dt * 12));
        // roulement
        float dist = (float) Math.sqrt(mx * mx + mz * mz);
        Quaternionf q = new Quaternionf(s[5], s[6], s[7], s[8]);
        if (dist > 1e-5f) {
            Vector3f axis = new Vector3f(mz, 0, -mx).normalize();
            q.premul(new Quaternionf().rotationAxis(dist / BALL, axis)).normalize();
            s[5] = q.x;
            s[6] = q.y;
            s[7] = q.z;
            s[8] = q.w;
        }
        int light = mc.level == null ? FULL
                : net.minecraft.client.renderer.LevelRenderer.getLightColor(mc.level, BlockPos.containing(s[0], s[1] + BALL, s[2]));
        pose.pushPose();
        pose.translate(s[0] - cam.x, s[1] + BALL - cam.y, s[2] - cam.z);
        // petit écrasement au contact du sol quand il accélère
        float squash = Math.min(0.08f, sp * 0.012f);
        pose.scale(1 + squash * 0.5f, 1 - squash, 1 + squash * 0.5f);
        pose.mulPose(q);
        sphere(pose.last(), consumers, light, Identifier.fromNamespaceAndPath("aurora", "textures/cosmetic/verity_" + variant + ".png"));
        pose.popPose();
    }

    private static void sphere(PoseStack.Pose p, MultiBufferSource consumers, int light, Identifier texture) {
        RenderType type = RenderTypes.entityCutout(texture);
        VertexConsumer vc = consumers.getBuffer(type);
        final int LON = 24, LAT = 14;
        for (int j = 0; j < LAT; j++) {
            double t0 = Math.PI * j / LAT, t1 = Math.PI * (j + 1) / LAT;
            for (int i = 0; i < LON; i++) {
                double a0 = Math.PI * 2 * i / LON, a1 = Math.PI * 2 * (i + 1) / LON;
                vtx(vc, p, a0, t0, i / (float) LON, j / (float) LAT, light);
                vtx(vc, p, a0, t1, i / (float) LON, (j + 1) / (float) LAT, light);
                vtx(vc, p, a1, t1, (i + 1) / (float) LON, (j + 1) / (float) LAT, light);
                vtx(vc, p, a1, t0, (i + 1) / (float) LON, j / (float) LAT, light);
            }
        }
        if (consumers instanceof MultiBufferSource.BufferSource bs) bs.endBatch(type);
    }

    private static void vtx(VertexConsumer vc, PoseStack.Pose p, double lon, double lat, float u, float v, int light) {
        float nx = (float) (Mesh.sin(lat) * Mesh.cos(lon)), ny = Mesh.cos(lat), nz = (float) (Mesh.sin(lat) * Mesh.sin(lon));
        vc.addVertex(p, nx * BALL, ny * BALL, nz * BALL).setColor(0xFFFFFFFF).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(p, nx, ny, nz);
    }

    static void forget(int id) {
        ROLL.remove(id);
    }
}
