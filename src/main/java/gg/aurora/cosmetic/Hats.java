package gg.aurora.cosmetic;

import com.mojang.math.Axis;
import org.joml.Vector3f;

import static gg.aurora.cosmetic.Mesh.argb;
import static gg.aurora.cosmetic.Mesh.lerp;
import static gg.aurora.cosmetic.Mesh.shade;
import static gg.aurora.cosmetic.Mesh.v;

/**
 * Chapeaux en 3D procédurale, posés sur la tête (repère de la tête : Y vers le bas, face vers -Z).
 * Passe solide = le chapeau, passe lumineuse = pierres, rubans et pointes qui brillent.
 */
final class Hats {
    private Hats() {}

    /** Point au-dessus du sommet de la tête (up > 0 = plus haut). */
    private static Vector3f p(float x, float up, float z) {
        return v(x, -up, z);
    }

    static void hat(Mesh m, boolean glow, String id, float t) {
        m.ps.pushPose();
        m.ps.translate(0, -0.5f, 0);
        float pulse = 0.75f + 0.25f * Mesh.sin(t * 0.12f);
        switch (id) {
            case "hat_haut" -> topHat(m, glow, pulse);
            case "hat_couronne" -> crown(m, glow, pulse, t);
            case "hat_sorciere" -> witch(m, glow, pulse);
            case "hat_cornes" -> horns(m, glow, pulse);
            case "hat_chat" -> catEars(m, glow, pulse);
            case "hat_pharaon" -> pharaoh(m, glow, pulse);
            case "hat_lunettes" -> glasses(m, glow, pulse);
            default -> {
            }
        }
        m.ps.popPose();
    }

    // ================================================================ formes

    /** Tube (tronc de cône) entre a et b, rayons ra → rb, dégradé de couleur. */
    private static void tube(Mesh m, Vector3f a, Vector3f b, float ra, float rb, int seg, int ca, int cb) {
        Vector3f d = new Vector3f(b).sub(a);
        if (d.lengthSquared() < 1e-10f) return;
        d.normalize();
        Vector3f u = Math.abs(d.y) < 0.9f ? new Vector3f(d).cross(0, 1, 0) : new Vector3f(d).cross(1, 0, 0);
        u.normalize();
        Vector3f w = new Vector3f(d).cross(u).normalize();
        for (int i = 0; i < seg; i++) {
            double a0 = Math.PI * 2 * i / seg, a1 = Math.PI * 2 * (i + 1) / seg;
            float c0 = Mesh.cos(a0), s0 = Mesh.sin(a0), c1 = Mesh.cos(a1), s1 = Mesh.sin(a1);
            // relief : un côté légèrement plus clair
            float l0 = 0.86f + 0.14f * c0, l1 = 0.86f + 0.14f * c1;
            m.quad(ring(a, u, w, ra, c0, s0), ring(a, u, w, ra, c1, s1), ring(b, u, w, rb, c1, s1), ring(b, u, w, rb, c0, s0),
                    shade(ca, l0), shade(ca, l1), shade(cb, l1), shade(cb, l0));
        }
    }

    private static Vector3f ring(Vector3f o, Vector3f u, Vector3f w, float r, float c, float s) {
        return v(o.x + (u.x * c + w.x * s) * r, o.y + (u.y * c + w.y * s) * r, o.z + (u.z * c + w.z * s) * r);
    }

    /** Disque horizontal (plan XZ) à la hauteur up. */
    private static void disk(Mesh m, float up, float r, int seg, int color) {
        Vector3f c = p(0, up, 0);
        for (int i = 0; i < seg; i++) {
            double a0 = Math.PI * 2 * i / seg, a1 = Math.PI * 2 * (i + 1) / seg;
            m.tri(c, p(Mesh.cos(a0) * r, up, Mesh.sin(a0) * r), p(Mesh.cos(a1) * r, up, Mesh.sin(a1) * r),
                    color, color, color);
        }
    }

    /** Bord plat épais (disques dessus / dessous + tranche). */
    private static void brim(Mesh m, float up, float th, float r, int seg, int top, int under) {
        tube(m, p(0, up, 0), p(0, up + th, 0), r, r, seg, under, top);
        disk(m, up, r, seg, under);
        disk(m, up + th, r, seg, top);
    }

    /** Panneau rayé (bandes alternées le long de a→d). */
    private static void stripes(Mesh m, Vector3f a, Vector3f b, Vector3f c, Vector3f d, int n, int c1, int c2) {
        for (int i = 0; i < n; i++) {
            float s0 = i / (float) n, s1 = (i + 1) / (float) n;
            Vector3f a0 = mix(a, d, s0), b0 = mix(b, c, s0), b1 = mix(b, c, s1), a1 = mix(a, d, s1);
            m.quad(a0, b0, b1, a1, i % 2 == 0 ? c1 : c2);
        }
    }

    private static Vector3f mix(Vector3f a, Vector3f b, float s) {
        return v(a.x + (b.x - a.x) * s, a.y + (b.y - a.y) * s, a.z + (b.z - a.z) * s);
    }

    // ================================================================ chapeaux

    private static void topHat(Mesh m, boolean glow, float pulse) {
        int black = argb(255, 0x17161C), band = argb(255, 0x7A4DFF);
        m.ps.mulPose(Axis.XP.rotationDegrees(-4));
        m.ps.mulPose(Axis.ZP.rotationDegrees(3));
        if (glow) {
            tube(m, p(0, 0.05f, 0), p(0, 0.12f, 0), 0.27f, 0.272f, 28, argb(Math.round(90 * pulse), 0x9D7CFF), argb(Math.round(90 * pulse), 0x9D7CFF));
            tube(m, p(0, 0.03f, 0), p(0, 0.14f, 0), 0.30f, 0.30f, 28, argb(Math.round(28 * pulse), 0x9D7CFF), argb(Math.round(28 * pulse), 0x9D7CFF));
            return;
        }
        brim(m, 0.02f, 0.025f, 0.41f, 28, black, shade(black, 0.8f));
        tube(m, p(0, 0.045f, 0), p(0, 0.42f, 0), 0.245f, 0.262f, 24, black, shade(black, 1.3f));
        disk(m, 0.42f, 0.262f, 24, shade(black, 1.2f));
        tube(m, p(0, 0.05f, 0), p(0, 0.12f, 0), 0.252f, 0.255f, 24, band, shade(band, 1.15f));
    }

    private static void crown(Mesh m, boolean glow, float pulse, float t) {
        int gold = argb(255, 0xF2C14E), deep = argb(255, 0xA8761F), light = argb(255, 0xFFE9A8);
        int[] gems = {0xE0283C, 0x2E7BFF, 0x2ECC71, 0x9D4DFF};
        if (glow) {
            for (int k = 0; k < 4; k++) {
                double a = Math.toRadians(k * 90 - 90);
                m.crystal(0.30f * Mesh.cos(a), -0.06f, 0.30f * Mesh.sin(a), 0.05f, 0.07f, argb(Math.round(150 * pulse), gems[k]));
            }
            for (int k = 0; k < 8; k++) {
                double a = Math.toRadians(k * 45 + 22.5);
                m.crystal(0.278f * Mesh.cos(a), -0.27f, 0.278f * Mesh.sin(a), 0.035f, 0.035f, argb(Math.round(70 * pulse), 0xFFF3C4));
            }
            return;
        }
        tube(m, p(0, 0, 0), p(0, 0.12f, 0), 0.272f, 0.285f, 32, deep, gold);
        tube(m, p(0, 0.005f, 0), p(0, 0.11f, 0), 0.262f, 0.274f, 32, shade(deep, 0.7f), deep);
        tube(m, p(0, 0, 0), p(0, 0.022f, 0), 0.29f, 0.29f, 32, light, gold);
        for (int k = 0; k < 8; k++) {
            double a = Math.toRadians(k * 45 + 22.5);
            float x = 0.278f * Mesh.cos(a), z = 0.278f * Mesh.sin(a);
            m.spike(x, -0.118f, z, 0.055f, 0.13f, gold, light);
            m.crystal(x, -0.255f, z, 0.022f, 0.022f, argb(255, 0xFFF6DA));
        }
        for (int k = 0; k < 4; k++) {
            double a = Math.toRadians(k * 90 - 90);
            m.crystal(0.293f * Mesh.cos(a), -0.06f, 0.293f * Mesh.sin(a), 0.03f, 0.042f, argb(255, gems[k]));
        }
    }

    private static Vector3f witchAxis(float s) {
        return p(0, 0.04f + 0.64f * s, 0.24f * (float) Math.pow(s, 2.2));
    }

    private static void witch(Mesh m, boolean glow, float pulse) {
        int cloth = argb(255, 0x2B1840), band = argb(255, 0x9D7CFF), gold = argb(255, 0xF2C14E);
        m.ps.mulPose(Axis.ZP.rotationDegrees(-4));
        if (glow) {
            tube(m, p(0, 0.045f, 0), p(0, 0.10f, 0), 0.29f, 0.29f, 24, argb(Math.round(80 * pulse), 0x9D7CFF), argb(Math.round(80 * pulse), 0x9D7CFF));
            Vector3f tip = witchAxis(1);
            m.crystal(tip.x, tip.y - 0.03f, tip.z, 0.05f, 0.06f, argb(Math.round(170 * pulse), 0xC9A8FF));
            return;
        }
        brim(m, 0.02f, 0.022f, 0.52f, 32, cloth, shade(cloth, 0.75f));
        int n = 8;
        for (int i = 0; i < n; i++) {
            float s0 = i / (float) n, s1 = (i + 1) / (float) n;
            float r0 = 0.285f * (float) Math.pow(1 - s0, 0.9) + 0.006f, r1 = 0.285f * (float) Math.pow(1 - s1, 0.9) + 0.006f;
            tube(m, witchAxis(s0), witchAxis(s1), r0, r1, 16, shade(cloth, 1 + 0.25f * s0), shade(cloth, 1 + 0.25f * s1));
        }
        tube(m, p(0, 0.045f, 0), p(0, 0.10f, 0.003f), 0.282f, 0.276f, 24, band, shade(band, 1.1f));
        // boucle dorée sur l'avant
        m.quad(p(-0.055f, 0.04f, -0.288f), p(0.055f, 0.04f, -0.288f), p(0.055f, 0.105f, -0.284f), p(-0.055f, 0.105f, -0.284f), gold);
        m.quad(p(-0.03f, 0.055f, -0.292f), p(0.03f, 0.055f, -0.292f), p(0.03f, 0.09f, -0.289f), p(-0.03f, 0.09f, -0.289f), band);
    }

    private static Vector3f horn(int side, float s) {
        return p(side * (0.20f + 0.13f * s + 0.04f * s * s), -0.10f + 0.34f * s - 0.06f * s * s * s, 0.02f + 0.16f * s * s);
    }

    private static void horns(Mesh m, boolean glow, float pulse) {
        int base = argb(255, 0x6A0E18), tip = argb(255, 0x140709);
        for (int side = -1; side <= 1; side += 2) {
            if (glow) {
                Vector3f e = horn(side, 1);
                m.crystal(e.x, e.y, e.z, 0.035f, 0.05f, argb(Math.round(150 * pulse), 0xFF3A1A));
                continue;
            }
            int n = 9;
            for (int i = 0; i < n; i++) {
                float s0 = i / (float) n, s1 = (i + 1) / (float) n;
                float r0 = 0.07f * (1 - s0) + 0.006f, r1 = 0.07f * (1 - s1) + 0.006f;
                // stries : un anneau sur deux plus sombre
                int c0 = lerp(base, tip, s0), c1 = lerp(base, tip, s1);
                if (i % 2 == 1) {
                    c0 = shade(c0, 0.82f);
                    c1 = shade(c1, 0.82f);
                }
                tube(m, horn(side, s0), horn(side, s1), r0, r1, 10, c0, c1);
            }
        }
    }

    private static void catEars(Mesh m, boolean glow, float pulse) {
        int fur = argb(255, 0x2C2A33), inner = argb(255, 0xFF8FC8);
        for (int side = -1; side <= 1; side += 2) {
            float x0 = side * 0.05f, x1 = side * 0.25f, xt = side * 0.19f;
            if (glow) {
                m.tri(p(x0 + side * 0.03f, 0.02f, -0.042f), p(x1 - side * 0.03f, 0.02f, -0.042f), p(xt, 0.19f, -0.02f),
                        argb(Math.round(70 * pulse), 0xFF8FC8), argb(Math.round(70 * pulse), 0xFF8FC8), argb(0, 0xFF8FC8));
                continue;
            }
            Vector3f a = p(x0, 0, -0.03f), b = p(x1, 0, -0.03f), c = p(xt, 0.22f, 0.0f);
            Vector3f a2 = p(x0, 0, 0.04f), b2 = p(x1, 0, 0.04f), c2 = p(xt, 0.22f, 0.03f);
            m.tri(a, b, c, fur, fur, shade(fur, 1.2f));
            m.tri(a2, c2, b2, fur, shade(fur, 1.2f), fur);
            m.quad(a, a2, c2, c, shade(fur, 0.85f));
            m.quad(b, c, c2, b2, shade(fur, 0.95f));
            m.tri(p(x0 + side * 0.035f, 0.015f, -0.036f), p(x1 - side * 0.035f, 0.015f, -0.036f), p(xt, 0.17f, -0.016f), inner, inner,
                    shade(inner, 1.1f));
        }
    }

    /**
     * Lunettes néon : deux verres rectangulaires teintés sur les yeux, monture fine et branches ; en
     * passe lumineuse, un éclat blanc sur les verres et un halo autour (comme une visière lumineuse).
     */
    private static void glasses(Mesh m, boolean glow, float pulse) {
        float z = -0.275f, top = -0.19f, bot = -0.31f;
        float[][] lens = {{-0.225f, -0.035f}, {0.035f, 0.225f}};
        if (glow) {
            for (float[] l : lens) {
                m.quad(p(l[0], top, z - 0.004f), p(l[1], top, z - 0.004f), p(l[1], bot, z - 0.004f), p(l[0], bot, z - 0.004f),
                        argb(Math.round(150 + 60 * pulse), 0xFFFFFF));
                float gx = 0.03f, gy = 0.03f;
                m.quad(p(l[0] - gx, top + gy, z - 0.006f), p(l[1] + gx, top + gy, z - 0.006f), p(l[1] + gx, bot - gy, z - 0.006f),
                        p(l[0] - gx, bot - gy, z - 0.006f), argb(Math.round(45 * pulse + 25), 0xE6DEFF));
            }
            return;
        }
        int frame = argb(255, 0x101014), lensCol = argb(255, 0x2A2638);
        for (float[] l : lens) {
            m.quad(p(l[0], top, z), p(l[1], top, z), p(l[1], bot, z), p(l[0], bot, z), lensCol);
            // monture : quatre bords
            float w = 0.012f;
            m.quad(p(l[0] - w, top + w, z - 0.002f), p(l[1] + w, top + w, z - 0.002f), p(l[1] + w, top, z - 0.002f), p(l[0] - w, top, z - 0.002f), frame);
            m.quad(p(l[0] - w, bot, z - 0.002f), p(l[1] + w, bot, z - 0.002f), p(l[1] + w, bot - w, z - 0.002f), p(l[0] - w, bot - w, z - 0.002f), frame);
        }
        // pont et branches jusqu'aux oreilles
        m.quad(p(-0.035f, top - 0.005f, z), p(0.035f, top - 0.005f, z), p(0.035f, top - 0.03f, z), p(-0.035f, top - 0.03f, z), frame);
        for (int s = -1; s <= 1; s += 2) {
            float x = s * 0.27f;
            m.quad(p(x, top, z), p(x, top, 0.12f), p(x, top - 0.022f, 0.12f), p(x, top - 0.022f, z), frame);
            m.quad(p(s * 0.225f, top, z), p(x, top, z), p(x, top - 0.022f, z), p(s * 0.225f, top - 0.022f, z), frame);
        }
    }

    private static void pharaoh(Mesh m, boolean glow, float pulse) {
        int blue = argb(255, 0x1E4FA8), gold = argb(255, 0xE8B931);
        if (glow) {
            m.crystal(0, -0.06f, -0.315f, 0.035f, 0.05f, argb(Math.round(160 * pulse), 0xFFD65E));
            m.crystal(0, -0.13f, -0.31f, 0.025f, 0.025f, argb(Math.round(170 * pulse), 0xE0283C));
            return;
        }
        float w = 0.295f, top = 0.035f;
        // dessus de la tête
        stripes(m, p(-w, top, -w), p(-w, top, w), p(w, top, w), p(w, top, -w), 9, blue, gold);
        for (int side = -1; side <= 1; side += 2) {
            float x = side * w, xe = side * 0.31f, xl = side * 0.36f;
            // côté collé à la tête jusqu'à la hauteur de l'oreille (le visage reste dégagé)
            stripes(m, p(x, top, -w + 0.04f), p(x, top, w), p(xe, -0.28f, w), p(xe, -0.28f, -w + 0.06f), 6, gold, blue);
            // rabat derrière l'oreille qui tombe sur l'épaule en s'élargissant légèrement
            stripes(m, p(xe, -0.28f, -0.08f), p(xe, -0.28f, w), p(xl, -0.86f, 0.18f), p(xl, -0.86f, -0.14f), 9, blue, gold);
            // tranche avant du rabat (épaisseur), dorée
            m.quad(p(xe, -0.28f, -0.08f), p(xl, -0.86f, -0.14f), p(xl - side * 0.03f, -0.86f, -0.14f), p(xe - side * 0.03f, -0.28f, -0.08f), gold);
        }
        // arrière : tombe en se resserrant
        stripes(m, p(-w, top, w), p(w, top, w), p(0.13f, -0.7f, 0.31f), p(-0.13f, -0.7f, 0.31f), 12, blue, gold);
        // bandeau doré sur le front et cobra
        m.quad(p(-w, -0.02f, -w - 0.003f), p(w, -0.02f, -w - 0.003f), p(w, top, -w - 0.003f), p(-w, top, -w - 0.003f), gold);
        m.spike(0, -0.03f, -w - 0.02f, 0.03f, 0.1f, gold, argb(255, 0xFFF0B8));
        m.crystal(0, -0.13f, -w - 0.018f, 0.018f, 0.018f, argb(255, 0xE0283C));
    }
}
