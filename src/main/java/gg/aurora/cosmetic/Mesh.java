package gg.aurora.cosmetic;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Vector3f;

/**
 * Petit constructeur de géométrie procédurale pour les cosmétiques : quads avec normales calculées,
 * prismes, tores, octaèdres et sprites lumineux. Les couleurs sont en ARGB.
 */
final class Mesh {
    final PoseStack ps = new PoseStack();
    private VertexConsumer vc;
    private int light;
    private final Vector3f e1 = new Vector3f(), e2 = new Vector3f(), n = new Vector3f();
    /** Tampons de transformation : aucune allocation par sommet (les méthodes vanilla en créent une). */
    private final Vector3f tp = new Vector3f(), tn = new Vector3f();
    /** Points de travail réutilisés par les primitives (rendu sur un seul thread). */
    private final Vector3f[] tmp = new Vector3f[8];
    /**
     * Lumière additive (pipeline « eyes » : ONE + ONE) : la couleur est prémultipliée par l'alpha et
     * les faces sont visibles des deux côtés (pipeline sans élimination).
     */
    boolean additive;

    Mesh additive() {
        additive = true;
        return this;
    }

    /** Dims additive light: shader packs add their own bloom on top of it. */
    float gain = 1;

    Mesh gain(float g) {
        gain = g;
        return this;
    }

    Mesh(PoseStack.Pose base, VertexConsumer vc, int light) {
        for (int i = 0; i < tmp.length; i++) tmp[i] = new Vector3f();
        reset(base, vc, light);
    }

    /** Réutilise ce constructeur pour un nouveau tampon (évite PoseStack + tampons à chaque passe). */
    Mesh reset(PoseStack.Pose base, VertexConsumer vc, int light) {
        ps.last().set(base);
        this.vc = vc;
        this.light = light;
        this.additive = false;
        this.gain = 1;
        return this;
    }

    private static Mesh shared;

    /** Instance partagée (rendu séquentiel sur le thread de rendu : une construction à la fois). */
    static Mesh shared(PoseStack.Pose base, VertexConsumer vc, int light) {
        if (shared == null) shared = new Mesh(base, vc, light);
        return shared.reset(base, vc, light);
    }

    // ================================================================ couleurs

    static int argb(int alpha, int rgb) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
    }

    static int shade(int argb, float f) {
        int r = Math.min(255, Math.round(((argb >> 16) & 255) * f));
        int g = Math.min(255, Math.round(((argb >> 8) & 255) * f));
        int b = Math.min(255, Math.round((argb & 255) * f));
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    static int lerp(int a, int b, float t) {
        int r = 0;
        for (int sh = 0; sh <= 24; sh += 8) {
            int ca = (a >>> sh) & 255, cb = (b >>> sh) & 255;
            r |= Math.round(ca + (cb - ca) * t) << sh;
        }
        return r;
    }

    static int withAlpha(int argb, float k) {
        return argb(Math.round(((argb >>> 24) & 255) * k), argb);
    }

    // ================================================================ primitives

    /** tn (normale transformée) est calculée une fois par quad dans normal(), pas à chaque sommet. */
    private void vertex(org.joml.Matrix4f m, Vector3f p, int color, float u, float v) {
        m.transformPosition(p.x, p.y, p.z, tp);
        if (gain < 1) color = Math.round((color >>> 24) * gain) << 24 | color & 0xFFFFFF;
        // un seul appel : BufferBuilder écrit le sommet d'un bloc (format entité) au lieu de 6 appels vérifiés
        // VulkanMod's fast path stores this int as is (vanilla converts ARGB to ABGR first): red and blue would swap
        if (VULKAN) color = color & 0xFF00FF00 | (color >>> 16 & 0xFF) | (color & 0xFF) << 16;
        vc.addVertex(tp.x, tp.y, tp.z, color, u, v, OverlayTexture.NO_OVERLAY, light, tn.x, tn.y, tn.z);
    }

    private static final boolean VULKAN = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("vulkanmod");

    private void normal(Vector3f a, Vector3f b, Vector3f c, Vector3f d) {
        if (gg.aurora.Prof.on) gg.aurora.Prof.vertices += 4;
        if (additive) {
            // lumière additive : le shader (EMISSIVE, sans éclairage) n'utilise pas la normale
            tn.set(0, 1, 0);
            return;
        }
        b.sub(a, e1);
        d.sub(a, e2);
        e1.cross(e2, n);
        if (n.lengthSquared() < 1e-12f) {
            c.sub(a, e2);
            e1.cross(e2, n);
        }
        if (n.lengthSquared() < 1e-12f) n.set(0, 0, 1);
        else n.normalize();
        ps.last().transformNormal(n.x, n.y, n.z, tn);
    }

    void quad(Vector3f a, Vector3f b, Vector3f c, Vector3f d, int ca, int cb, int cc, int cd) {
        if (additive && (ca | cb | cc | cd) >>> 24 == 0) return; // invisible : rien à ajouter
        normal(a, b, c, d);
        org.joml.Matrix4f m = ps.last().pose();
        vertex(m, a, ca, 0.5f, 0.5f);
        vertex(m, b, cb, 0.5f, 0.5f);
        vertex(m, c, cc, 0.5f, 0.5f);
        vertex(m, d, cd, 0.5f, 0.5f);
    }

    /** Sommet déjà dans le repère de la pose (transformé une fois, partagé par plusieurs quads). */
    private void emit(Vector3f p, int color, float u, float v) {
        if (gain < 1) color = Math.round((color >>> 24) * gain) << 24 | color & 0xFFFFFF;
        if (VULKAN) color = color & 0xFF00FF00 | (color >>> 16 & 0xFF) | (color & 0xFF) << 16;
        vc.addVertex(p.x, p.y, p.z, color, u, v, OverlayTexture.NO_OVERLAY, light, tn.x, tn.y, tn.z);
    }

    /**
     * Quad aux sommets déjà transformés (ta..td) : la normale vient des points locaux (la..ld), comme
     * quad(). Évite de retransformer un point partagé par quatre quads (grille de la cloche).
     */
    void quadPre(Vector3f la, Vector3f lb, Vector3f lc, Vector3f ld, Vector3f ta, Vector3f tb, Vector3f tc, Vector3f td,
                 int ca, int cb, int cc, int cd) {
        if (additive && (ca | cb | cc | cd) >>> 24 == 0) return;
        normal(la, lb, lc, ld);
        emit(ta, ca, 0.5f, 0.5f);
        emit(tb, cb, 0.5f, 0.5f);
        emit(tc, cc, 0.5f, 0.5f);
        emit(td, cd, 0.5f, 0.5f);
    }

    /** Lumière additive aux sommets déjà transformés (ce shader n'utilise pas la normale). */
    void quadT(Vector3f ta, Vector3f tb, Vector3f tc, Vector3f td, int ca, int cb, int cc, int cd) {
        if ((ca | cb | cc | cd) >>> 24 == 0) return;
        normal(ta, tb, tc, td); // additive : normale fixe, sans calcul
        emit(ta, ca, 0.5f, 0.5f);
        emit(tb, cb, 0.5f, 0.5f);
        emit(tc, cc, 0.5f, 0.5f);
        emit(td, cd, 0.5f, 0.5f);
    }

    void quad(Vector3f a, Vector3f b, Vector3f c, Vector3f d, int color) {
        quad(a, b, c, d, color, color, color, color);
    }

    void tri(Vector3f a, Vector3f b, Vector3f c, int ca, int cb, int cc) {
        quad(a, b, c, c, ca, cb, cc, cc);
    }

    /** Quad texturé (UV complètes), pour les sprites lumineux. */
    void sprite(Vector3f a, Vector3f b, Vector3f c, Vector3f d, int color) {
        if (additive && color >>> 24 == 0) return;
        normal(a, b, c, d);
        org.joml.Matrix4f m = ps.last().pose();
        vertex(m, a, color, 0, 0);
        vertex(m, b, color, 0, 1);
        vertex(m, c, color, 1, 1);
        vertex(m, d, color, 1, 0);
    }

    /** Table lookups: cosmetics call these thousands of times per frame, the error is far below a pixel. */
    static float sin(double a) {
        return net.minecraft.util.Mth.sin((float) a);
    }

    static float cos(double a) {
        return net.minecraft.util.Mth.cos((float) a);
    }

    static Vector3f v(float x, float y, float z) {
        return new Vector3f(x, y, z);
    }

    private static final Vector3f[] RING = new Vector3f[32];
    private static int ringAt;

    static {
        for (int i = 0; i < RING.length; i++) RING[i] = new Vector3f();
    }

    /** Short-lived point for a quad drawn right away: no allocation, valid for the next 31 calls. */
    static Vector3f p(float x, float y, float z) {
        ringAt = ringAt + 1 & 31;
        return RING[ringAt].set(x, y, z);
    }

    /** Pavé aligné sur les axes (repère courant), faces orientées vers l'extérieur. */
    void box(float x0, float y0, float z0, float x1, float y1, float z1, int color) {
        quad(p(x0, y0, z1), p(x1, y0, z1), p(x1, y1, z1), p(x0, y1, z1), color);
        quad(p(x1, y0, z0), p(x0, y0, z0), p(x0, y1, z0), p(x1, y1, z0), color);
        quad(p(x0, y1, z0), p(x0, y1, z1), p(x1, y1, z1), p(x1, y1, z0), shade(color, 1.08f));
        quad(p(x0, y0, z1), p(x0, y0, z0), p(x1, y0, z0), p(x1, y0, z1), shade(color, 0.8f));
        quad(p(x1, y0, z1), p(x1, y0, z0), p(x1, y1, z0), p(x1, y1, z1), shade(color, 0.92f));
        quad(p(x0, y0, z0), p(x0, y0, z1), p(x0, y1, z1), p(x0, y1, z0), shade(color, 0.92f));
    }

    private final Vector3f rA = new Vector3f(), rB = new Vector3f(), rD = new Vector3f();
    private static final float[][] SQ = {{1, 1}, {-1, 1}, {-1, -1}, {1, -1}};

    /** Tige à 4 faces entre a et b (n'importe quelle direction), rayon r0 → r1, dégradé c0 → c1. */
    void rod(Vector3f a, Vector3f b, float r0, float r1, int c0, int c1) {
        b.sub(a, rD);
        float len = rD.length();
        if (len < 1e-6f) return;
        rD.div(len);
        // deux axes perpendiculaires au segment
        if (Math.abs(rD.y) < 0.9f) rA.set(0, 1, 0).cross(rD).normalize();
        else rA.set(1, 0, 0).cross(rD).normalize();
        rD.cross(rA, rB);
        float ax = rA.x, ay = rA.y, az = rA.z, bx = rB.x, by = rB.y, bz = rB.z;
        for (int i = 0; i < 4; i++) {
            // coins i et i+1 du carré (A, B) : (1,1) (-1,1) (-1,-1) (1,-1)
            float ua = SQ[i][0], va = SQ[i][1], ub = SQ[(i + 1) % 4][0], vb = SQ[(i + 1) % 4][1];
            quad(p(a.x + (ax * ua + bx * va) * r0, a.y + (ay * ua + by * va) * r0, a.z + (az * ua + bz * va) * r0),
                    p(a.x + (ax * ub + bx * vb) * r0, a.y + (ay * ub + by * vb) * r0, a.z + (az * ub + bz * vb) * r0),
                    p(b.x + (ax * ub + bx * vb) * r1, b.y + (ay * ub + by * vb) * r1, b.z + (az * ub + bz * vb) * r1),
                    p(b.x + (ax * ua + bx * va) * r1, b.y + (ay * ua + by * va) * r1, b.z + (az * ua + bz * va) * r1),
                    c0, c0, c1, c1);
        }
    }

    /** Prisme effilé entre p et q (dans le plan XY), largeur w0 → w1, épaisseur th en Z. */
    void bone(Vector3f p, Vector3f q, float w0, float w1, float th, int color) {
        float dx = q.x - p.x, dy = q.y - p.y, len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-5f) return;
        float px = -dy / len, py = dx / len;
        Vector3f[] c = tmp;
        for (int i = 0; i < 8; i++) {
            Vector3f o = i < 4 ? p : q;
            float w = (i < 4 ? w0 : w1) / 2, t = (i < 4 ? th : th * 0.7f) / 2;
            int k = i % 4;
            float sw = k == 0 || k == 3 ? -w : w, st = k < 2 ? -t : t;
            c[i].set(o.x + px * sw, o.y + py * sw, o.z + st);
        }
        int lit = shade(color, 1.15f), dark = shade(color, 0.75f);
        quad(c[0], c[1], c[5], c[4], dark);
        quad(c[1], c[2], c[6], c[5], color);
        quad(c[2], c[3], c[7], c[6], lit);
        quad(c[3], c[0], c[4], c[7], color);
        quad(c[0], c[3], c[2], c[1], dark);
        quad(c[4], c[5], c[6], c[7], lit);
    }

    /** Tore horizontal (plan XZ) de rayon R, section r. */
    /** Tables cos/sin par nombre de pas (tores : plus aucun sinus par quad). Quelques entrées au plus. */
    private static final java.util.Map<Integer, float[][]> TRIG = new java.util.HashMap<>();

    private static float[][] trig(int n) {
        float[][] t = TRIG.get(n);
        if (t != null) return t;
        t = new float[2][n + 1];
        for (int i = 0; i <= n; i++) {
            t[0][i] = Mesh.cos(Math.PI * 2 * i / n);
            t[1][i] = Mesh.sin(Math.PI * 2 * i / n);
        }
        TRIG.put(n, t);
        return t;
    }

    void torus(float R, float r, int seg, int sides, int color, int inner) {
        float[][] ta = trig(seg), tb = trig(sides);
        Vector3f p00 = tmp[0], p10 = tmp[1], p11 = tmp[2], p01 = tmp[3];
        for (int i = 0; i < seg; i++) {
            float ca0 = ta[0][i], sa0 = ta[1][i], ca1 = ta[0][i + 1], sa1 = ta[1][i + 1];
            for (int j = 0; j < sides; j++) {
                float cb0 = tb[0][j], sb0 = tb[1][j], cb1 = tb[0][j + 1], sb1 = tb[1][j + 1];
                float rr0 = R + r * cb0, rr1 = R + r * cb1;
                p00.set(rr0 * ca0, r * sb0, rr0 * sa0);
                p10.set(rr0 * ca1, r * sb0, rr0 * sa1);
                p11.set(rr1 * ca1, r * sb1, rr1 * sa1);
                p01.set(rr1 * ca0, r * sb1, rr1 * sa0);
                // dessus plus clair, dessous plus sombre : relief métallique
                int c0 = lerp(inner, color, 0.5f - 0.5f * sb0), c1 = lerp(inner, color, 0.5f - 0.5f * sb1);
                quad(p00, p01, p11, p10, c0, c1, c1, c0);
            }
        }
    }

    /** Octaèdre (cristal) centré, demi-hauteur h et demi-largeur w. */
    void crystal(float x, float y, float z, float w, float h, int color) {
        Vector3f top = tmp[0].set(x, y - h, z), bot = tmp[1].set(x, y + h, z);
        Vector3f[] ring = {tmp[2].set(x + w, y, z), tmp[3].set(x, y, z + w), tmp[4].set(x - w, y, z), tmp[5].set(x, y, z - w)};
        for (int i = 0; i < 4; i++) {
            Vector3f a = ring[i], b = ring[(i + 1) % 4];
            int c = shade(color, 0.8f + 0.12f * i);
            tri(top, a, b, shade(c, 1.25f), c, c);
            tri(bot, b, a, shade(c, 0.7f), c, c);
        }
    }

    /** Pyramide (pointe) de base carrée, pointant vers -Y. */
    void spike(float x, float y, float z, float w, float h, int base, int tip) {
        Vector3f top = tmp[0].set(x, y - h, z);
        Vector3f[] ring = {tmp[2].set(x + w, y, z), tmp[3].set(x, y, z + w), tmp[4].set(x - w, y, z), tmp[5].set(x, y, z - w)};
        for (int i = 0; i < 4; i++) tri(top, ring[i], ring[(i + 1) % 4], tip, base, base);
    }

    /** Trois quads croisés texturés : halo lumineux visible sous tous les angles. */
    void bloom(float x, float y, float z, float s, int color) {
        Vector3f a = tmp[0], b = tmp[1], c = tmp[2], d = tmp[3];
        sprite(a.set(x - s, y - s, z), b.set(x - s, y + s, z), c.set(x + s, y + s, z), d.set(x + s, y - s, z), color);
        sprite(a.set(x, y - s, z - s), b.set(x, y + s, z - s), c.set(x, y + s, z + s), d.set(x, y - s, z + s), color);
        sprite(a.set(x - s, y, z - s), b.set(x - s, y, z + s), c.set(x + s, y, z + s), d.set(x + s, y, z - s), color);
    }
}
