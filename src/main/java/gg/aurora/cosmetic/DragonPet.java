package gg.aurora.cosmetic;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

import static gg.aurora.cosmetic.Mesh.argb;

/**
 * Familier « Shenron » : dragon serpent qui tourne autour du joueur. La tête suit une orbite lente qui
 * monte et descend ; le corps (44 anneaux) suit le chemin de la tête à distance fixe, ce qui donne les
 * ondulations. Écailles vertes, ventre doré, crête, quatre pattes griffues, bois sur la tête, moustaches
 * qui flottent et yeux rouges lumineux.
 */
final class DragonPet {
    private DragonPet() {}

    private static final int N = 44;
    private static final float SEG = 0.075f;
    private static final int GREEN = 0x2F9E47, GREEN_DARK = 0x1E6B30, GOLD = 0xE6C35C, HORN = 0xDCC79A, MANE = 0x9BD98A,
            CLAW = 0xF0E6C8, EYE = 0xFF3020;
    /** Par joueur : N points (x, y, z) puis vitesse de la tête (3). */
    private static final Map<Integer, float[]> STATE = new HashMap<>();
    private static final Vector3f T = new Vector3f(), S = new Vector3f(), U = new Vector3f(), REF = new Vector3f(0, 1, 0);
    private static final Vector3f[] CORNERS = {new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f(),
            new Vector3f(), new Vector3f()};
    private static final float[] FT = new float[N * 3], FS = new float[N * 3], FU = new float[N * 3], RAD = new float[N];

    static void forget(int id) {
        STATE.remove(id);
    }

    static void render(PoseStack pose, MultiBufferSource consumers, RenderType solidT, RenderType glowT, Player p, Vec3 cam,
                       double px, double py, double pz, float dt, float time, int light) {
        float seed = p.getId() * 0.71f;
        float ang = time * 0.45f + seed;
        float tx = (float) px + Mesh.cos(ang) * 1.7f, tz = (float) pz + Mesh.sin(ang) * 1.7f;
        float ty = (float) py + 2.3f + 0.45f * Mesh.sin(time * 0.9f + seed) + 0.25f * Mesh.sin(time * 0.37f);
        float[] s = STATE.computeIfAbsent(p.getId(), k -> init(tx, ty, tz));
        if (Math.abs(s[0] - tx) > 12 || Math.abs(s[1] - ty) > 12 || Math.abs(s[2] - tz) > 12) {
            float[] fresh = init(tx, ty, tz);
            System.arraycopy(fresh, 0, s, 0, s.length);
        }
        // tête : ressort amorti vers l'orbite (mouvement souple, sans à-coups)
        int v = N * 3;
        float w = 3.2f;
        for (int a = 0; a < 3; a++) {
            float target = a == 0 ? tx : a == 1 ? ty : tz;
            s[v + a] += (w * w * (target - s[a]) - 2 * w * s[v + a]) * dt;
            s[a] += s[v + a] * dt;
        }
        // corps : chaque anneau reste à SEG du précédent (il suit le chemin de la tête)
        for (int i = 1; i < N; i++) {
            int c = i * 3, q = c - 3;
            float dx = s[c] - s[q], dy = s[c + 1] - s[q + 1], dz = s[c + 2] - s[q + 2];
            float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d > SEG && d > 1e-6f) {
                float k = SEG / d;
                s[c] = s[q] + dx * k;
                s[c + 1] = s[q + 1] + dy * k;
                s[c + 2] = s[q + 2] + dz * k;
            }
        }
        // repères le long du corps (avant = vers la tête)
        for (int i = 0; i < N; i++) {
            int a = Math.max(0, i - 1) * 3, b = Math.min(N - 1, i + 1) * 3;
            T.set(s[a] - s[b], s[a + 1] - s[b + 1], s[a + 2] - s[b + 2]);
            if (T.lengthSquared() < 1e-10f) T.set(0, 0, 1);
            T.normalize();
            T.cross(REF, S);
            if (S.lengthSquared() < 1e-6f) S.set(1, 0, 0);
            S.normalize();
            S.cross(T, U);
            int o = i * 3;
            FT[o] = T.x; FT[o + 1] = T.y; FT[o + 2] = T.z;
            FS[o] = S.x; FS[o + 1] = S.y; FS[o + 2] = S.z;
            FU[o] = U.x; FU[o + 1] = U.y; FU[o + 2] = U.z;
            float u = i / (float) (N - 1);
            RAD[i] = (0.085f * (1 - 0.78f * u) + 0.012f) * (i < 3 ? 0.82f : 1f);
        }
        float ox = (float) cam.x, oy = (float) cam.y, oz = (float) cam.z;
        Mesh m = Mesh.shared(pose.last(), consumers.getBuffer(solidT), light);
        // ---- corps : tube à 8 faces, dessus vert, ventre doré, écailles en bandes
        for (int i = 0; i < N - 1; i++) {
            int a = i * 3, b = a + 3;
            for (int k = 0; k < 8; k++) {
                double a0 = Math.PI * 2 * k / 8, a1 = Math.PI * 2 * (k + 1) / 8;
                int c0 = bodyColor(Mesh.sin(a0), i), c1 = bodyColor(Mesh.sin(a1), i);
                m.quad(ring(s, a, i, a0, ox, oy, oz), ring(s, b, i + 1, a0, ox, oy, oz), ring(s, b, i + 1, a1, ox, oy, oz),
                        ring(s, a, i, a1, ox, oy, oz), c0, c0, c1, c1);
            }
        }
        // ---- crête dorsale
        for (int i = 4; i < N - 4; i += 2) {
            int o = i * 3;
            float r = RAD[i], h = 0.06f * (1 - i / (float) N) + 0.015f;
            Vector3f base0 = at(s, o, FU, r, ox, oy, oz).add(FT[o] * 0.025f, FT[o + 1] * 0.025f, FT[o + 2] * 0.025f);
            Vector3f base1 = at(s, o, FU, r, ox, oy, oz).sub(FT[o] * 0.025f, FT[o + 1] * 0.025f, FT[o + 2] * 0.025f);
            Vector3f tip = at(s, o, FU, r + h, ox, oy, oz).sub(FT[o] * 0.04f, FT[o + 1] * 0.04f, FT[o + 2] * 0.04f);
            m.tri(base0, base1, tip, argb(255, GREEN_DARK), argb(255, GREEN_DARK), argb(255, MANE));
        }
        // ---- quatre pattes griffues
        for (int leg : new int[]{7, 24}) {
            int o = leg * 3;
            for (int side = -1; side <= 1; side += 2) {
                float r = RAD[leg];
                Vector3f sh = Mesh.v(s[o] - ox + FS[o] * side * r * 0.8f, s[o + 1] - oy + FS[o + 1] * side * r * 0.8f, s[o + 2] - oz + FS[o + 2] * side * r * 0.8f);
                Vector3f knee = Mesh.v(sh.x + FS[o] * side * 0.07f - FU[o] * 0.05f, sh.y + FS[o + 1] * side * 0.07f - FU[o + 1] * 0.05f,
                        sh.z + FS[o + 2] * side * 0.07f - FU[o + 2] * 0.05f);
                float swing = Mesh.sin(time * 2.4f + leg + side) * 0.02f;
                Vector3f foot = Mesh.v(knee.x - FU[o] * 0.06f + FT[o] * (0.03f + swing), knee.y - FU[o + 1] * 0.06f + FT[o + 1] * (0.03f + swing),
                        knee.z - FU[o + 2] * 0.06f + FT[o + 2] * (0.03f + swing));
                m.rod(sh, knee, 0.02f, 0.015f, argb(255, GREEN), argb(255, GREEN_DARK));
                m.rod(knee, foot, 0.015f, 0.011f, argb(255, GREEN_DARK), argb(255, GOLD));
                for (int c = -1; c <= 1; c++) {
                    Vector3f tip = Mesh.v(foot.x + FT[o] * 0.03f + FS[o] * c * 0.014f - FU[o] * 0.01f,
                            foot.y + FT[o + 1] * 0.03f + FS[o + 1] * c * 0.014f - FU[o + 1] * 0.01f,
                            foot.z + FT[o + 2] * 0.03f + FS[o + 2] * c * 0.014f - FU[o + 2] * 0.01f);
                    m.rod(foot, tip, 0.005f, 0.001f, argb(255, CLAW), argb(255, CLAW));
                }
            }
        }
        // ---- tête (repère : x = côté, y = haut, z = avant)
        head(m, s, ox, oy, oz, time);
        if (consumers instanceof MultiBufferSource.BufferSource bs) bs.endBatch(solidT);
        // yeux lumineux
        Mesh g = Mesh.shared(pose.last(), consumers.getBuffer(glowT), 0xF000F0).additive();
        for (int side = -1; side <= 1; side += 2) {
            Vector3f e = local(s, side * 0.052f, 0.045f, 0.11f, ox, oy, oz);
            g.bloom(e.x, e.y, e.z, 0.03f, argb(255, EYE));
            g.bloom(e.x, e.y, e.z, 0.012f, argb(255, 0xFFE0D0));
        }
        if (consumers instanceof MultiBufferSource.BufferSource bs) bs.endBatch(glowT);
    }

    private static int bodyColor(float up, int i) {
        int c = Mesh.lerp(GOLD, GREEN, Math.max(0, Math.min(1, up * 0.9f + 0.55f)));
        if ((i & 1) == 1) c = Mesh.shade(argb(255, c), 0.86f);
        return argb(255, c);
    }

    private static float[] init(float x, float y, float z) {
        float[] s = new float[N * 3 + 3];
        for (int i = 0; i < N; i++) {
            s[i * 3] = x - i * SEG;
            s[i * 3 + 1] = y;
            s[i * 3 + 2] = z;
        }
        return s;
    }

    /** Point de l'anneau i à l'angle a (dans le plan côté / haut), relatif à la caméra. */
    private static Vector3f ring(float[] s, int o, int i, double a, float ox, float oy, float oz) {
        float c = Mesh.cos(a) * RAD[i], sn = Mesh.sin(a) * RAD[i];
        int f = i * 3;
        return Mesh.p(s[o] - ox + FS[f] * c + FU[f] * sn, s[o + 1] - oy + FS[f + 1] * c + FU[f + 1] * sn, s[o + 2] - oz + FS[f + 2] * c + FU[f + 2] * sn);
    }

    private static Vector3f at(float[] s, int o, float[] dir, float d, float ox, float oy, float oz) {
        return Mesh.v(s[o] - ox + dir[o] * d, s[o + 1] - oy + dir[o + 1] * d, s[o + 2] - oz + dir[o + 2] * d);
    }

    /** Point dans le repère de la tête (x côté, y haut, z avant). */
    private static Vector3f local(float[] s, float x, float y, float z, float ox, float oy, float oz) {
        return Mesh.v(s[0] - ox + FS[0] * x + FU[0] * y + FT[0] * z, s[1] - oy + FS[1] * x + FU[1] * y + FT[1] * z,
                s[2] - oz + FS[2] * x + FU[2] * y + FT[2] * z);
    }

    /** Pavé orienté dans le repère de la tête. */
    private static void obox(Mesh m, float[] s, float cx, float cy, float cz, float hx, float hy, float hz, int color, float ox, float oy, float oz) {
        Vector3f[] c = CORNERS;
        for (int i = 0; i < 8; i++) {
            float x = cx + ((i & 1) == 0 ? -hx : hx), y = cy + ((i & 2) == 0 ? -hy : hy), z = cz + ((i & 4) == 0 ? -hz : hz);
            c[i].set(s[0] - ox + FS[0] * x + FU[0] * y + FT[0] * z, s[1] - oy + FS[1] * x + FU[1] * y + FT[1] * z,
                    s[2] - oz + FS[2] * x + FU[2] * y + FT[2] * z);
        }
        int top = argb(255, Mesh.lerp(color, 0xFFFFFF, 0.08f)), side = argb(255, color), under = argb(255, Mesh.shade(argb(255, color), 0.75f));
        m.quad(c[4], c[5], c[7], c[6], side); // avant
        m.quad(c[1], c[0], c[2], c[3], side); // arrière
        m.quad(c[2], c[6], c[7], c[3], top); // dessus
        m.quad(c[0], c[1], c[5], c[4], under); // dessous
        m.quad(c[1], c[3], c[7], c[5], side); // côté +x
        m.quad(c[0], c[4], c[6], c[2], side); // côté -x
    }

    private static void head(Mesh m, float[] s, float ox, float oy, float oz, float time) {
        obox(m, s, 0, 0.02f, 0.03f, 0.07f, 0.055f, 0.07f, GREEN, ox, oy, oz);       // crâne
        obox(m, s, 0, 0.065f, 0.08f, 0.062f, 0.012f, 0.035f, GREEN_DARK, ox, oy, oz); // arcades
        obox(m, s, 0, 0.0f, 0.15f, 0.045f, 0.034f, 0.07f, GREEN, ox, oy, oz);       // museau
        obox(m, s, 0, -0.045f, 0.12f, 0.04f, 0.014f, 0.07f, GOLD, ox, oy, oz);      // mâchoire
        obox(m, s, 0, 0.02f, 0.222f, 0.03f, 0.02f, 0.008f, GREEN_DARK, ox, oy, oz); // bout du nez
        for (int side = -1; side <= 1; side += 2) {
            // bois ramifiés
            Vector3f b0 = local(s, side * 0.04f, 0.07f, 0.0f, ox, oy, oz), b1 = local(s, side * 0.075f, 0.17f, -0.10f, ox, oy, oz);
            Vector3f b2 = local(s, side * 0.115f, 0.22f, -0.05f, ox, oy, oz), b3 = local(s, side * 0.06f, 0.25f, -0.17f, ox, oy, oz);
            m.rod(b0, b1, 0.016f, 0.011f, argb(255, HORN), argb(255, HORN));
            m.rod(b1, b2, 0.009f, 0.003f, argb(255, HORN), argb(255, CLAW));
            m.rod(b1, b3, 0.009f, 0.003f, argb(255, HORN), argb(255, CLAW));
            // crocs
            m.rod(local(s, side * 0.028f, -0.03f, 0.19f, ox, oy, oz), local(s, side * 0.028f, -0.065f, 0.19f, ox, oy, oz), 0.006f, 0.001f,
                    argb(255, CLAW), argb(255, CLAW));
            // moustaches qui flottent vers l'arrière
            Vector3f prev = local(s, side * 0.045f, 0.0f, 0.2f, ox, oy, oz);
            for (int k = 1; k <= 9; k++) {
                float wave = Mesh.sin(time * 3f + k * 0.7f + side) * 0.025f * k / 9f;
                Vector3f next = local(s, side * (0.045f + 0.032f * k), -0.012f * k + wave, 0.2f - 0.04f * k, ox, oy, oz);
                m.rod(prev, next, 0.006f * (1 - k / 11f), 0.006f * (1 - (k + 1) / 11f), argb(255, CLAW), argb(255, CLAW));
                prev = next;
            }
            // crinière
            for (int k = 0; k < 4; k++) {
                Vector3f r0 = local(s, side * 0.05f, 0.03f - k * 0.02f, -0.03f - k * 0.02f, ox, oy, oz);
                Vector3f r1 = local(s, side * 0.11f, 0.06f - k * 0.03f, -0.11f - k * 0.02f, ox, oy, oz);
                m.rod(r0, r1, 0.014f, 0.002f, argb(255, MANE), argb(255, Mesh.lerp(MANE, 0xFFFFFF, 0.3f)));
            }
        }
    }
}
