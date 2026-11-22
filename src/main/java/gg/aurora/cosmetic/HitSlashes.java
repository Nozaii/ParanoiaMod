package gg.aurora.cosmetic;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import static gg.aurora.cosmetic.Mesh.argb;
import static gg.aurora.cosmetic.Mesh.v;

/** Hit Effects : entaille lumineuse, anneau et étincelles sur la cible à chaque coup. */
public final class HitSlashes {
    private HitSlashes() {}

    private static final int FULL_BRIGHT = 0xF000F0;
    private static final Identifier WHITE = Identifier.fromNamespaceAndPath("aurora", "textures/cosmetic/white.png");
    private static final Identifier GLOW_TEX = Identifier.fromNamespaceAndPath("aurora", "textures/ui/glow.png");
    private static RenderType solidType, bloomType;

    private record Slash(Vec3 pos, float tilt, int age) {}

    private static final List<Slash> SLASHES = new ArrayList<>();
    private static final int SLASH_TIME = 11;

    public static void onHit(Entity target) {
        if (!gg.aurora.module.ExtraModules.HIT_EFFECTS.enabled) return;
        Vec3 p = target.position().add(0, target.getBbHeight() * 0.6, 0);
        SLASHES.add(new Slash(p, (float) (Math.random() * 70 - 35), 0));
        if (SLASHES.size() > 8) SLASHES.remove(0);
    }

    /** Dev only: impact at a position. */
    public static void devHit(Vec3 p) {
        SLASHES.add(new Slash(p, 20, 0));
    }

    public static void tick(Minecraft mc) {
        if (SLASHES.isEmpty()) return;
        if (mc.level == null) {
            SLASHES.clear();
            return;
        }
        SLASHES.replaceAll(s -> new Slash(s.pos(), s.tilt(), s.age() + 1));
        SLASHES.removeIf(s -> s.age() > SLASH_TIME);
    }

    public static void renderWorld(PoseStack pose, MultiBufferSource consumers, Vec3 cam, float partial) {
        if (consumers == null || SLASHES.isEmpty()) return;
        if (solidType == null) {
            solidType = gg.aurora.render.FxRender.additive(WHITE);
            bloomType = gg.aurora.render.FxRender.additive(GLOW_TEX);
        }
        renderSlashes(pose, consumers, cam, partial);
    }

    private static void renderSlashes(PoseStack pose, MultiBufferSource consumers, Vec3 cam, float partial) {
        org.joml.Quaternionf rot = Minecraft.getInstance().gameRenderer.getMainCamera().rotation();
        int col = (int) gg.aurora.module.ExtraModules.HIT_EFFECTS.opt("color", 0x8CD8FF);
        for (int pass = 0; pass < 2; pass++) {
            Mesh m = new Mesh(pose.last(), consumers.getBuffer(pass == 0 ? solidType : bloomType), FULL_BRIGHT).additive();
            for (Slash s : SLASHES) {
                float t = Math.min(1, (s.age() + partial) / SLASH_TIME);
                m.ps.pushPose();
                // décalée vers la caméra pour passer devant le corps de la cible
                Vec3 toCam = cam.subtract(s.pos()).normalize().scale(0.75);
                m.ps.translate((float) (s.pos().x + toCam.x - cam.x), (float) (s.pos().y + toCam.y - cam.y), (float) (s.pos().z + toCam.z - cam.z));
                m.ps.mulPose(rot);
                m.ps.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(s.tilt()));
                float fade = 1 - t;
                if (pass == 0) {
                    // 1) impact: sharp crescent sweeping through the target, plus a thinner echo
                    final int n = 20;
                    float head = Math.min(1, t * 2.6f), tail = Math.max(0, t * 2.6f - 0.9f);
                    for (int layer = 0; layer < 2; layer++) {
                        float R = layer == 0 ? 0.72f : 0.56f, wid = layer == 0 ? 0.085f : 0.04f;
                        float span = layer == 0 ? 165 : 120, start = layer == 0 ? -172 : -150;
                        for (int i = 0; i < n; i++) {
                            float u0 = i / (float) n, u1 = (i + 1) / (float) n;
                            if (u1 > head || u0 < tail) continue;
                            double a0 = Math.toRadians(start + span * u0), a1 = Math.toRadians(start + span * u1);
                            float w0 = wid * (float) Math.pow(Mesh.sin(Math.PI * u0), 1.6) + 0.002f;
                            float w1 = wid * (float) Math.pow(Mesh.sin(Math.PI * u1), 1.6) + 0.002f;
                            int c0 = argb(Math.round(255 * fade), Mesh.lerp(col, 0xFFFFFF, 0.25f + u0 * 0.75f));
                            int c1 = argb(Math.round(255 * fade), Mesh.lerp(col, 0xFFFFFF, 0.25f + u1 * 0.75f));
                            float x0 = Mesh.cos(a0), y0 = Mesh.sin(a0), x1 = Mesh.cos(a1), y1 = Mesh.sin(a1);
                            m.quad(v(x0 * (R - w0), y0 * (R - w0) + 0.35f, 0), v(x0 * (R + w0), y0 * (R + w0) + 0.35f, 0),
                                    v(x1 * (R + w1), y1 * (R + w1) + 0.35f, 0), v(x1 * (R - w1), y1 * (R - w1) + 0.35f, 0), c0, c0, c1, c1);
                        }
                    }
                    // 2) ring that opens from the impact, thinning out
                    float e = 1 - (1 - t) * (1 - t), rr = 0.15f + 0.95f * e, rw = 0.05f * (1 - t) + 0.004f;
                    int rc = argb(Math.round(190 * fade), col);
                    final int seg = 28;
                    for (int i = 0; i < seg; i++) {
                        double a0 = Math.PI * 2 * i / seg, a1 = Math.PI * 2 * (i + 1) / seg;
                        float x0 = Mesh.cos(a0), y0 = Mesh.sin(a0), x1 = Mesh.cos(a1), y1 = Mesh.sin(a1);
                        m.quad(v(x0 * (rr - rw), y0 * (rr - rw), 0), v(x0 * (rr + rw), y0 * (rr + rw), 0),
                                v(x1 * (rr + rw), y1 * (rr + rw), 0), v(x1 * (rr - rw), y1 * (rr - rw), 0), rc);
                    }
                    // 3) star burst: thin spikes that shoot out and shrink
                    int seed = Float.floatToIntBits(s.tilt());
                    for (int i = 0; i < 9; i++) {
                        float h = ((seed * 31 + i * 97) & 1023) / 1023f;
                        double a = Math.PI * 2 * (i + h * 0.7) / 9;
                        float ca = Mesh.cos(a), sa = Mesh.sin(a);
                        float in = 0.1f + 0.7f * e, len = (0.55f + 0.6f * h) * (1 - t) * (1 - t), wdt = 0.03f * (1 - t);
                        int sc = argb(Math.round(255 * fade), Mesh.lerp(col, 0xFFFFFF, 0.6f));
                        m.quad(v(ca * in - sa * wdt, sa * in + ca * wdt, 0), v(ca * in + sa * wdt, sa * in - ca * wdt, 0),
                                v(ca * (in + len), sa * (in + len), 0), v(ca * (in + len), sa * (in + len), 0), sc, sc, argb(0, col), argb(0, col));
                        // 4) spark carried further along each spike
                        float d = in + len * 1.2f + 0.3f * e, q = 0.022f * (1 - t) + 0.004f;
                        m.quad(v(ca * d - q, sa * d - q, 0), v(ca * d + q, sa * d - q, 0), v(ca * d + q, sa * d + q, 0), v(ca * d - q, sa * d + q, 0), sc);
                    }
                } else {
                    // flash at the instant of impact, then a soft halo
                    if (t < 0.3f) m.bloom(0, 0, 0, 0.32f, argb(Math.round(255 * (1 - t / 0.3f)), 0xFFFFFF));
                    if (t < 0.7f) m.bloom(0, 0, 0, 0.75f * (0.5f + t), argb(Math.round(150 * (1 - t / 0.7f)), col));
                }
                m.ps.popPose();
            }
        }
        if (consumers instanceof MultiBufferSource.BufferSource bs) bs.endBatch();
    }

}
