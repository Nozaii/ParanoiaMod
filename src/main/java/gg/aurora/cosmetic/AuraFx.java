package gg.aurora.cosmetic;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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

/**
 * Auras : feux follets lumineux qui évoluent dans tout le volume autour du joueur (des pieds
 * au-dessus de la tête), chacun sur sa propre trajectoire pseudo-aléatoire avec une traînée. Rendu en
 * espace monde : l'aura ne tourne pas avec le corps, elle l'entoure. Lumière additive.
 */
public final class AuraFx {
    private AuraFx() {}

    private static final Identifier GLOW = Identifier.fromNamespaceAndPath("aurora", "textures/ui/glow.png");

    /** Comportements : braises qui montent, givre en spirale, esprits à longues traînées, néant aspiré, étoiles, cœurs. */
    private enum Kind { EMBER, FROST, SPIRIT, VOID, STARS, HEARTS }

    private record Style(Kind kind, int core, int edge, int count, int trail) {}

    private static Style style(String id, int color) {
        return switch (id) {
            case "braise" -> new Style(Kind.EMBER, 0xFFE2A0, 0xFF5A10, 26, 5);
            case "givre" -> new Style(Kind.FROST, 0xF0FCFF, 0x3FB8FF, 20, 6);
            case "spectre" -> new Style(Kind.SPIRIT, 0xDDF2FF, 0x3C8CFF, 12, 12);
            case "neant" -> new Style(Kind.VOID, 0xF2E4FF, 0x8A3CFF, 22, 7);
            case "etoiles" -> new Style(Kind.STARS, 0xFFFFFF, 0xA8B8FF, 30, 1);
            case "coeurs" -> new Style(Kind.HEARTS, 0xFFE0EE, 0xFF4A96, 12, 4);
            default -> new Style(Kind.SPIRIT, 0xFFFFFF, color, 14, 8);
        };
    }

    private static float hash(int a, int b) {
        int h = a * 374761393 + b * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFFFF) / (float) 0xFFFFFF;
    }

    public static void renderWorld(PoseStack pose, MultiBufferSource consumers, Vec3 cam, float pt) {
        Minecraft mc = Minecraft.getInstance();
        if (consumers == null || mc.level == null || mc.player == null || !Cosmetics.anyWorn(Cosmetics.Type.AURA)) return;
        RenderType type = FxRender.additive(GLOW);
        VertexConsumer vc = null;
        Quaternionf rot = mc.gameRenderer.getMainCamera().rotation();
        Vector3f right = rot.transform(new Vector3f(1, 0, 0)), up = rot.transform(new Vector3f(0, 1, 0));
        Matrix4f m = pose.last().pose();
        float time = (float) ((System.currentTimeMillis() % 3_600_000L) / 1000.0);
        if (!gg.aurora.Settings.anim()) time = 30;
        gg.aurora.render.FxCull.begin();
        for (Player p : mc.level.players()) {
            if (p.isInvisible() || p.distanceToSqr(cam) > 48 * 48 || !gg.aurora.render.FxCull.visible(p, cam, 2)) continue;
            if (p == mc.player && mc.options.getCameraType().isFirstPerson()) continue;
            Cosmetics.Item aura = Cosmetics.worn(p, Cosmetics.Type.AURA);
            if (aura == null) continue;
            if (vc == null) vc = consumers.getBuffer(type);
            double x = Mth.lerp(pt, p.xo, p.getX()) - cam.x, y = Mth.lerp(pt, p.yo, p.getY()) - cam.y, z = Mth.lerp(pt, p.zo, p.getZ()) - cam.z;
            int q = gg.aurora.Settings.cosmeticQuality;
            boolean far = q == 2 || p.distanceToSqr(cam) > (q == 1 && p != mc.player ? 12 * 12 : 20 * 20);
            draw(vc, m, right, up, style(aura.id(), aura.color()), (float) x, (float) y, (float) z, time, p.getId(), far, p.isCrouching());
        }
        if (vc != null && consumers instanceof MultiBufferSource.BufferSource bs) bs.endBatch(type);
    }

    private static void draw(VertexConsumer vc, Matrix4f m, Vector3f right, Vector3f up, Style st, float px, float py, float pz,
                             float time, int seed, boolean far, boolean crouch) {
        float height = crouch ? 1.55f : 1.9f;
        // halo diffus autour du corps, qui respire
        float breathe = 0.75f + 0.25f * Mesh.sin(time * 1.3f + seed);
        sprite(vc, m, right, up, px, py + height * 0.5f, pz, 1.35f, st.edge(), 0.07f * breathe);

        int count = far ? st.count() / 2 : st.count();
        int trail = far ? Math.min(2, st.trail()) : st.trail();
        Vector3f pos = new Vector3f();
        for (int i = 0; i < count; i++) {
            float r1 = hash(i, seed), r2 = hash(i, seed + 101), r3 = hash(i, seed + 202), r4 = hash(i, seed + 303), r5 = hash(i, seed + 404);
            for (int k = trail - 1; k >= 0; k--) {
                float tt = time - k * (st.kind() == Kind.SPIRIT ? 0.06f : 0.04f);
                float life = path(st.kind(), tt, r1, r2, r3, r4, r5, height, pos);
                if (life <= 0) continue;
                float tk = 1 - k / (float) Math.max(1, trail);
                float a = life * (float) Math.pow(tk, 1.6);
                float size = core(st.kind()) * (0.45f + 0.55f * tk) * (0.8f + 0.4f * r5);
                int col = lerp(st.edge(), st.core(), tk * tk);
                // cœur lumineux + halo coloré
                sprite(vc, m, right, up, px + pos.x, py + pos.y, pz + pos.z, size, col, 0.95f * a);
                if (k == 0) sprite(vc, m, right, up, px + pos.x, py + pos.y, pz + pos.z, size * 3.2f, st.edge(), 0.22f * a);
            }
        }
    }

    private static float core(Kind k) {
        return switch (k) {
            case EMBER -> 0.07f;
            case STARS -> 0.06f;
            case HEARTS -> 0.12f;
            case SPIRIT -> 0.11f;
            default -> 0.085f;
        };
    }

    /** Position (relative aux pieds) d'un follet à l'instant t ; renvoie son intensité (0 = invisible). */
    private static float path(Kind kind, float t, float r1, float r2, float r3, float r4, float r5, float H, Vector3f out) {
        float dir = r2 < 0.5f ? -1 : 1;
        float wob = (float) (Mesh.sin(t * (1.7 + r3) + r1 * 20) * 0.08 + Mesh.sin(t * (2.9 + r4) + r2 * 30) * 0.05);
        switch (kind) {
            case EMBER -> {
                // monte vite en tremblant, s'éteint en haut
                float life = frac(r4 + t * (0.35f + 0.3f * r2));
                double a = r1 * Math.PI * 2 + t * 0.6 * dir + life * 2.5 * dir;
                float rad = 0.35f + 0.45f * r3 + life * 0.35f;
                out.set(Mesh.cos(a) * rad + wob, -0.05f + life * (H + 0.9f), Mesh.sin(a) * rad - wob);
                float flick = 0.6f + 0.4f * Mesh.sin(t * 25 + r5 * 40);
                return Mesh.sin(Math.PI * life) * flick;
            }
            case FROST -> {
                // flocons en hélice lente, montent et redescendent
                double a = r1 * Math.PI * 2 + t * (0.35 + 0.4 * r2) * dir;
                float rad = 0.6f + 0.35f * r3 + 0.1f * Mesh.sin(t * 0.8 + r4 * 6);
                float h = H * (0.5f + 0.48f * Mesh.sin(t * (0.25 + 0.3 * r4) + r5 * 6.28));
                out.set(Mesh.cos(a) * rad, h + wob, Mesh.sin(a) * rad);
                return 0.55f + 0.45f * (float) Math.max(0, Mesh.sin(t * 3 + r5 * 50));
            }
            case SPIRIT -> {
                // esprits qui serpentent autour du corps à différentes hauteurs, longues traînées
                double a = r1 * Math.PI * 2 + t * (0.9 + 0.7 * r2) * dir;
                float rad = 0.55f + 0.3f * r3 + 0.15f * Mesh.sin(t * (1.1 + r4) + r5 * 9);
                float h = H * (0.15f + 0.8f * (0.5f + 0.5f * Mesh.sin(t * (0.45 + 0.5 * r4) + r1 * 12)));
                out.set(Mesh.cos(a) * rad, h, Mesh.sin(a) * rad);
                return 0.9f;
            }
            case VOID -> {
                // aspiré en spirale vers le cœur du joueur, réapparaît au loin
                float life = frac(r4 + t * (0.22f + 0.18f * r2));
                double a = r1 * Math.PI * 2 + life * 5.5 * dir + t * 0.3;
                float rad = 0.12f + (1 - life) * (1.0f + 0.4f * r3);
                float h = H * 0.55f + (r5 - 0.5f) * H * (1 - life) * 1.1f;
                out.set(Mesh.cos(a) * rad, h, Mesh.sin(a) * rad);
                return Mesh.sin(Math.PI * Math.min(1, life * 1.2f)) * (life < 0.95f ? 1 : 0);
            }
            case STARS -> {
                // étoiles posées sur une coquille autour du joueur, qui scintillent et dérivent
                double a = r1 * Math.PI * 2 + t * 0.12 * dir;
                float rad = 0.75f + 0.5f * r3;
                float h = -0.1f + (H + 0.5f) * r4 + 0.1f * Mesh.sin(t * 0.7 + r5 * 6);
                out.set(Mesh.cos(a) * rad, h, Mesh.sin(a) * rad);
                float tw = Mesh.sin(t * (1.5 + r2 * 3) + r5 * 60);
                return Math.max(0, tw) * Math.max(0, tw);
            }
            default -> {
                // cœurs qui flottent vers le haut en se balançant
                float life = frac(r4 + t * (0.12f + 0.08f * r2));
                double a = r1 * Math.PI * 2 + t * 0.2 * dir;
                float rad = 0.5f + 0.4f * r3;
                out.set(Mesh.cos(a) * rad + Mesh.sin(t * 1.5 + r5 * 8) * 0.12f, 0.1f + life * (H + 0.6f),
                        Mesh.sin(a) * rad);
                return Mesh.sin(Math.PI * life);
            }
        }
    }

    private static float frac(float v) {
        return v - (float) Math.floor(v);
    }

    private static int lerp(int a, int b, float t) {
        return Mesh.lerp(a, b, t) & 0xFFFFFF;
    }

    /** Sprite carré face à la caméra. */
    private static void sprite(VertexConsumer vc, Matrix4f m, Vector3f r, Vector3f u, float x, float y, float z, float s, int rgb, float a) {
        if (a <= 0.004f) return;
        int col = (Math.min(255, Math.round(a * 255)) << 24) | (rgb & 0xFFFFFF);
        float rx = r.x * s, ry = r.y * s, rz = r.z * s, ux = u.x * s, uy = u.y * s, uz = u.z * s;
        vertex(vc, m, x - rx - ux, y - ry - uy, z - rz - uz, 0, 1, col);
        vertex(vc, m, x + rx - ux, y + ry - uy, z + rz - uz, 1, 1, col);
        vertex(vc, m, x + rx + ux, y + ry + uy, z + rz + uz, 1, 0, col);
        vertex(vc, m, x - rx + ux, y - ry + uy, z - rz + uz, 0, 0, col);
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, float x, float y, float z, float u, float v, int col) {
        vc.addVertex(m, x, y, z).setColor(col).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(0, 1, 0);
    }
}
