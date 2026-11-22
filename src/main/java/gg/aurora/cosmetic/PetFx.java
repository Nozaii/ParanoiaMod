package gg.aurora.cosmetic;

import com.mojang.blaze3d.vertex.PoseStack;
import gg.aurora.render.FxRender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

import static gg.aurora.cosmetic.Mesh.argb;
import static gg.aurora.cosmetic.Mesh.lerp;
import static gg.aurora.cosmetic.Mesh.v;

/**
 * Familiers : méduse translucide en 3D qui flotte près de l'épaule. Cloche qui se contracte en
 * pulsant (nervures et bord lumineux), tentacules et bras oraux qui ondulent et traînent derrière
 * quand elle se déplace. Elle suit le joueur avec un léger retard, comme dans l'eau.
 */
public final class PetFx {
    private PetFx() {}

    private static final Identifier WHITE = Identifier.fromNamespaceAndPath("aurora", "textures/cosmetic/white.png");
    private static final Identifier GLOW = Identifier.fromNamespaceAndPath("aurora", "textures/ui/glow.png");
    private static final int FULL = 0xF000F0;
    /** Under a shader pack: pets take the light of their spot and glow less, or they burn out at night. */
    static float gain() {
        return gg.aurora.render.IrisCompat.shaderPackInUse() ? 0.3f : 1f;
    }

    static int lightAt(double x, double y, double z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !gg.aurora.render.IrisCompat.shaderPackInUse()) return FULL;
        int l = net.minecraft.client.renderer.LevelRenderer.getLightColor(mc.level, net.minecraft.core.BlockPos.containing(x, y, z));
        // a floor keeps them readable in the dark without the full-bright burn
        return (l >> 4 & 15) < 9 ? l & ~0xF0 | 9 << 4 : l;
    }

    static int petLight = FULL;

    /** Position lissée (x, y, z) et vitesse (vx, vy, vz) par joueur. */
    private static final Map<Integer, float[]> STATE = new HashMap<>();
    private static long last = System.nanoTime();

    private static final boolean VULKAN = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("vulkanmod");

    public static void renderWorld(PoseStack pose, MultiBufferSource consumers, Vec3 cam, float pt) {
        Minecraft mc = Minecraft.getInstance();
        long preT = gg.aurora.Prof.start();
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - last) / 1e9f);
        last = now;
        if (consumers == null || mc.level == null || mc.player == null) return;
        if (!Cosmetics.anyWorn(Cosmetics.Type.PET)) { // personne n'a de familier : rien à faire
            if (!STATE.isEmpty()) STATE.clear();
            return;
        }
        float time = freezeTime > 0 ? freezeTime : (float) ((System.currentTimeMillis() % 3_600_000L) / 1000.0);
        boolean any = false;
        // lumière et halo : même type additif (texture glow, centre blanc pur) -> un seul appel de dessin
        RenderType body = RenderTypes.entityTranslucent(WHITE), glow = FxRender.additive(GLOW), bloom = glow;
        gg.aurora.render.FxCull.begin();
        for (Player p : mc.level.players()) {
            Cosmetics.Item pet = Cosmetics.worn(p, Cosmetics.Type.PET);
            if (pet == null || p.isInvisible() || p.distanceToSqr(cam) > 64 * 64) {
                STATE.remove(p.getId());
                PetModels.forget(p.getId());
                DragonPet.forget(p.getId());
                continue;
            }
            if (!gg.aurora.render.FxCull.visible(p, cam, 3)) continue;
            float yaw = (float) Math.toRadians(Mth.rotLerp(pt, p.yBodyRotO, p.yBodyRot));
            float fx = -Mesh.sin(yaw), fz = Mesh.cos(yaw);
            float rx = -Mesh.cos(yaw), rz = -Mesh.sin(yaw);
            double px = Mth.lerp(pt, p.xo, p.getX()), py = Mth.lerp(pt, p.yo, p.getY()), pz = Mth.lerp(pt, p.zo, p.getZ());
            if (pet.id().equals("mini_skin")) continue; // dessiné par le jeu comme un joueur (voir MiniSkinPet.extract)
            if (pet.id().equals("dragon")) { // dragon serpent en orbite autour du joueur
                int dl = net.minecraft.client.renderer.LevelRenderer.getLightColor(mc.level, net.minecraft.core.BlockPos.containing(px, py + 2.3, pz));
                DragonPet.render(pose, consumers, RenderTypes.entityCutoutNoCull(WHITE), glow, p, cam, px, py, pz, dt, time, dl);
                continue;
            }
            if (Cosmetics.isVerity(pet.id())) { // rolls on the ground next to the player
                PetModels.verity(pose, consumers, p, cam, px, py, pz, yaw, dt, time, pet.id());
                continue;
            }
            // derrière l'épaule droite, un peu au-dessus de la tête, avec une dérive lente
            float drift = Mesh.sin(time * 0.37f) * 0.12f;
            float tx = (float) px + rx * (0.85f + drift) - fx * 0.35f;
            float ty = (float) py + 1.95f + 0.09f * Mesh.sin(time * 1.1f);
            float tz = (float) pz + rz * (0.85f + drift) - fz * 0.35f;
            float[] s = STATE.computeIfAbsent(p.getId(), k -> new float[]{tx, ty, tz, 0, 0, 0});
            float k = 1 - (float) Math.exp(-dt * 2.6f);
            float nx = s[0] + (tx - s[0]) * k, ny = s[1] + (ty - s[1]) * k, nz = s[2] + (tz - s[2]) * k;
            if (dt > 0) {
                s[3] = s[3] + ((nx - s[0]) / dt - s[3]) * 0.2f;
                s[4] = s[4] + ((ny - s[1]) / dt - s[4]) * 0.2f;
                s[5] = s[5] + ((nz - s[2]) / dt - s[5]) * 0.2f;
            }
            s[0] = nx;
            s[1] = ny;
            s[2] = nz;
            if (Math.abs(nx - tx) > 8 || Math.abs(ny - ty) > 8 || Math.abs(nz - tz) > 8) {
                s[0] = tx;
                s[1] = ty;
                s[2] = tz;
            }
            any = true;
            float seed = p.getId() * 1.37f;
            int col = pet.color();
            float ox = (float) (s[0] - cam.x), oy = (float) (s[1] - cam.y), oz = (float) (s[2] - cam.z);
            // VulkanMod clamps depth instead of clipping at the near plane: a pet beside the first-person camera
            // turns into streaks across the screen. Own pet, first person: drawn only once clearly in front.
            if (VULKAN && p == mc.player && !mc.gameRenderer.getMainCamera().isDetached()) {
                var f = mc.gameRenderer.getMainCamera().forwardVector();
                if (ox * f.x() + oy * f.y() + oz * f.z() < 1.4f) continue;
            }
            petLight = lightAt(s[0], s[1], s[2]);
            if (Cosmetics.isUfo(pet.id())) {
                PetModels.ufo(pose, consumers, RenderTypes.entityCutoutNoCull(WHITE), body, glow, ox, oy + 0.15f, oz, s[3], s[5], time + seed, col);
                continue;
            }
            gg.aurora.Prof.stop(gg.aurora.Prof.PET_PRE, preT);
            draw(pose, consumers, body, glow, bloom, ox, oy, oz, s[3], s[4], s[5], time + seed, col);
        }
        if (any && consumers instanceof MultiBufferSource.BufferSource bs) {
            long flushT = gg.aurora.Prof.start();
            flushTimer = flushT;
            bs.endBatch(body);
            bs.endBatch(glow);
            gg.aurora.Prof.stop(gg.aurora.Prof.PET_FLUSH, flushTimer);
        }
    }

    private static void draw(PoseStack pose, MultiBufferSource consumers, RenderType bodyT, RenderType glowT, RenderType bloomT,
                             float x, float y, float z, float vx, float vy, float vz, float t, int col) {
        // contraction de la cloche : rapide, puis relâchement lent
        float ph = (t * 0.9f) % 1f;
        float pulse = ph < 0.3f ? Mesh.sin(ph / 0.3f * Math.PI / 2) : 1 - smooth((ph - 0.3f) / 0.7f);
        float R = 0.34f * (1 - 0.14f * pulse), H = 0.27f * (1 + 0.12f * pulse);
        // inclinaison dans le sens du déplacement
        float tilt = Math.max(-0.5f, Math.min(0.5f, (float) Math.sqrt(vx * vx + vz * vz) * 0.25f));
        float dirA = (float) Math.atan2(vz, vx);

        pose.pushPose();
        pose.translate(x, y, z);
        pose.mulPose(com.mojang.math.Axis.YP.rotation(-dirA));
        pose.mulPose(com.mojang.math.Axis.ZP.rotation(-tilt));
        pose.mulPose(com.mojang.math.Axis.YP.rotation(dirA));
        PoseStack.Pose base = pose.last();
        // caméra dans le repère du familier : les rubans lumineux lui font face
        INV.set(base.pose()).invert().transformPosition(0, 0, 0, CAM_L);

        int light = lerp(col, 0xFFFFFF, 0.55f) & 0xFFFFFF, deep = Mesh.shade(argb(255, col), 0.55f) & 0xFFFFFF;
        boolean low = gg.aurora.Settings.cosmeticQuality == 2;
        final int S = low ? 16 : 24, L = low ? 6 : 9;
        long bellT = gg.aurora.Prof.start();
        // grille de la cloche calculée une seule fois (au lieu de 4 points recalculés par quad)
        if (GRID.length < (L + 1) * (S + 1)) {
            GRID = new Vector3f[(L + 1) * (S + 1)];
            for (int i = 0; i < GRID.length; i++) GRID[i] = new Vector3f();
        }
        for (int j = 0; j <= L; j++) {
            float a = j / (float) L;
            double ang = a * Math.PI / 2 * 1.08;
            float r = R * Mesh.sin(ang) + (a > 0.85f ? (a - 0.85f) * 0.25f : 0), yy = H * Mesh.cos(ang);
            for (int i = 0; i <= S; i++) {
                double lon = Math.PI * 2 * i / S;
                GRID[j * (S + 1) + i].set(r * Mesh.cos(lon), yy, r * Mesh.sin(lon));
            }
        }
        org.joml.Matrix4f M = base.pose();
        boolean pre = !legacyVerts;
        if (pre) {
            if (GT.length < GRID.length) {
                GT = new Vector3f[GRID.length];
                for (int i = 0; i < GT.length; i++) GT[i] = new Vector3f();
            }
            // chaque point de la grille est partagé par quatre quads : transformé une seule fois
            for (int k = 0; k < (L + 1) * (S + 1); k++) M.transformPosition(GRID[k], GT[k]);
        }
        // ---- cloche translucide (double face)
        Mesh mb = Mesh.shared(base, consumers.getBuffer(bodyT), petLight);
        for (int j = 0; j < L; j++) {
            float a0 = j / (float) L, a1 = (j + 1) / (float) L;
            int c0 = argb(Math.round(40 + 80 * a0), lerp(light, deep, a0 * 0.6f) & 0xFFFFFF);
            int c1 = argb(Math.round(40 + 80 * a1), lerp(light, deep, a1 * 0.6f) & 0xFFFFFF);
            for (int i = 0; i < S; i++) {
                int i00 = j * (S + 1) + i, i10 = (j + 1) * (S + 1) + i;
                if (pre) mb.quadPre(GRID[i00], GRID[i10], GRID[i10 + 1], GRID[i00 + 1], GT[i00], GT[i10], GT[i10 + 1], GT[i00 + 1], c0, c1, c1, c0);
                else mb.quad(GRID[i00], GRID[i10], GRID[i10 + 1], GRID[i00 + 1], c0, c1, c1, c0);
            }
        }
        gg.aurora.Prof.stop(gg.aurora.Prof.PET_BELL, bellT);
        long glowPT = gg.aurora.Prof.start();
        // ---- lumière : nervures, bord, tentacules, bras oraux
        Mesh mg = Mesh.shared(base, consumers.getBuffer(glowT), FULL).additive().gain(gain());
        for (int r = 0; r < 8; r++) {
            double lon = Math.PI * 2 * r / 8 + 0.2;
            for (int j = 1; j < L; j++) {
                float a0 = j / (float) L, a1 = (j + 1) / (float) L;
                ribbon(mg, M, bellTo(TA, a0, lon, R * 1.01f, H * 1.01f), bellTo(TB, a1, lon, R * 1.01f, H * 1.01f), 0.011f, 0.011f,
                        argb(Math.round(150 * a0), 0xFFFFFF), argb(Math.round(150 * a1), light));
            }
        }
        for (int i = 0; i < S; i++) {
            double l0 = Math.PI * 2 * i / S, l1 = Math.PI * 2 * (i + 1) / S;
            ribbon(mg, M, bellTo(TA, 1, l0, R * 1.02f, H), bellTo(TB, 1, l1, R * 1.02f, H), 0.022f, 0.022f, argb(200, light), argb(200, light));
        }
        // tentacules fins depuis le bord : ondulent et traînent derrière le mouvement
        final int T = low ? 8 : 12, N = low ? 9 : 14;
        for (int i = 0; i < T; i++) {
            double lon = Math.PI * 2 * i / T + 0.13;
            Vector3f prev = bellTo(TA, 0.97f, lon, R * 0.96f, H), next = TB;
            Vector3f tPrev = M.transformPosition(prev, TTA), tNext = TTB;
            float len = 1.0f + 0.35f * Mesh.sin(i * 2.3);
            for (int j = 1; j <= N; j++) {
                float u = j / (float) N;
                Vector3f p = tentacle(next, prev, u, len / N, t, i, vx, vy, vz, 0.06f);
                Vector3f tp = M.transformPosition(p, tNext); // extrémité partagée avec le segment suivant
                ribbonT(mg, M, prev, p, tPrev, tp, 0.009f * (1 - u * 0.7f), 0.009f * (1 - u * 0.6f),
                        argb(Math.round(170 * (1 - u * 0.85f)), light), argb(Math.round(170 * (1 - u)), light));
                next = prev;
                prev = p;
                tNext = tPrev;
                tPrev = tp;
            }
        }
        // bras oraux : rubans plus larges et frisés au centre
        for (int i = 0; i < 4; i++) {
            double lon = Math.PI / 2 * i + t * 0.2;
            Vector3f prev = TA.set(Mesh.cos(lon) * 0.04f, H * 0.05f, Mesh.sin(lon) * 0.04f), next = TB;
            Vector3f tPrev = M.transformPosition(prev, TTA), tNext = TTB;
            for (int j = 1; j <= 10; j++) {
                float u = j / 10f;
                Vector3f p = tentacle(next, prev, u, 0.07f, t * 0.8f, i + 20, vx, vy, vz, 0.04f);
                p.x += Mesh.cos(lon + u * 4) * 0.012f;
                p.z += Mesh.sin(lon + u * 4) * 0.012f;
                Vector3f tp = M.transformPosition(p, tNext);
                ribbonT(mg, M, prev, p, tPrev, tp, 0.035f * (1 - u * 0.5f), 0.035f * (1 - u * 0.5f), argb(Math.round(90 * (1 - u)), light),
                        argb(Math.round(90 * (1 - u)), light));
                next = prev;
                prev = p;
                tNext = tPrev;
                tPrev = tp;
            }
        }
        gg.aurora.Prof.stop(gg.aurora.Prof.PET_GLOW, glowPT);
        // ---- halo et cœur lumineux
        Mesh ml = Mesh.shared(base, consumers.getBuffer(bloomT), FULL).additive().gain(gain());
        ml.bloom(0, H * 0.45f, 0, 0.55f + 0.08f * pulse, argb(Math.round(70 + 50 * pulse), col));
        ml.bloom(0, H * 0.4f, 0, 0.22f, argb(160, 0xFFFFFF));
        pose.popPose();
    }

    private static float smooth(float t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    /** Point de la cloche : a = 0 au sommet, 1 au bord ; lon = longitude. Le bord s'évase légèrement. */
    private static Vector3f[] GRID = new Vector3f[0];

    /** Deux points de travail alternés (tentacules : précédent / suivant). */
    private static final Vector3f TA = new Vector3f(), TB = new Vector3f();

    private static Vector3f bellTo(Vector3f out, float a, double lon, float R, float H) {
        double ang = a * Math.PI / 2 * 1.08;
        float r = R * Mesh.sin(ang) + (a > 0.85f ? (a - 0.85f) * 0.25f : 0);
        float y = H * Mesh.cos(ang);
        return out.set(r * Mesh.cos(lon), y, r * Mesh.sin(lon));
    }

    /** Segment suivant d'un tentacule : descend, ondule, et traîne à l'opposé de la vitesse. */
    private static Vector3f tentacle(Vector3f out, Vector3f prev, float u, float seg, float t, int i, float vx, float vy, float vz, float amp) {
        float sway = Mesh.sin(t * 1.6f + u * 5.5f + i * 1.7f) * amp * u;
        float sway2 = Mesh.cos(t * 1.2f + u * 4.0f + i * 2.9f) * amp * u;
        float drag = 0.06f * u;
        return out.set(prev.x + sway * seg * 8 - vx * drag * seg * 6, prev.y - seg - Math.max(-0.02f, vy) * drag * seg * 4,
                prev.z + sway2 * seg * 8 - vz * drag * seg * 6);
    }

    /**
     * Ruban lumineux toujours tourné vers la caméra : un quad au lieu de deux quads croisés (moitié moins
     * de sommets), avec la même largeur apparente et la même luminosité que les deux quads croisés d'avant.
     */
    private static final Vector3f R0 = new Vector3f(), R1 = new Vector3f(), R2 = new Vector3f(), R3 = new Vector3f(), SIDE = new Vector3f(),
            CAM_L = new Vector3f();
    private static final org.joml.Matrix4f INV = new org.joml.Matrix4f();

    /** Dev : ancien rendu (deux quads croisés) pour comparer à l'image près. */
    public static boolean legacyRibbons;
    private static long flushTimer;
    /** Dev : temps figé (> 0) pour des captures identiques. */
    public static float freezeTime;

    /** Dev : ancien chemin (chaque coin de quad retransformé par la matrice) pour l'A/B. */
    public static boolean legacyVerts;
    private static Vector3f[] GT = new Vector3f[0];
    private static final Vector3f TTA = new Vector3f(), TTB = new Vector3f(), TR0 = new Vector3f(), TR1 = new Vector3f(), SIDE_T = new Vector3f();

    /** Ruban entre deux points locaux, transformés ici (deux transformations au lieu de quatre coins). */
    private static void ribbon(Mesh m, org.joml.Matrix4f M, Vector3f a, Vector3f b, float wa, float wb, int ca, int cb) {
        if (legacyRibbons || legacyVerts) {
            ribbon(m, a, b, wa, wb, ca, cb);
            return;
        }
        ribbonT(m, M, a, b, M.transformPosition(a, TR0), M.transformPosition(b, TR1), wa, wb, ca, cb);
    }

    /**
     * Ruban aux extrémités déjà transformées (ta, tb) : côté calculé en local comme ribbon(), puis tourné
     * par la matrice ; coins = extrémité ± largeur × côté (transformation affine : mêmes coins).
     */
    private static void ribbonT(Mesh m, org.joml.Matrix4f M, Vector3f a, Vector3f b, Vector3f ta, Vector3f tb, float wa, float wb, int ca, int cb) {
        if (legacyRibbons || legacyVerts) {
            ribbon(m, a, b, wa, wb, ca, cb);
            return;
        }
        float dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
        float vx = (a.x + b.x) * 0.5f - CAM_L.x, vy = (a.y + b.y) * 0.5f - CAM_L.y, vz = (a.z + b.z) * 0.5f - CAM_L.z;
        SIDE.set(dy * vz - dz * vy, dz * vx - dx * vz, dx * vy - dy * vx);
        float l = SIDE.length();
        if (l < 1e-9f) SIDE.set(1, 0, 0);
        else SIDE.div(l);
        float px = Math.abs(SIDE.x), pz = Math.abs(SIDE.z), big = Math.max(px, pz);
        if (big < 1e-4f) return;
        float k = Math.min(2f, (px + pz) / big);
        if (k > 1.001f) {
            ca = Mesh.withAlpha(ca, k);
            cb = Mesh.withAlpha(cb, k);
        }
        wa *= big;
        wb *= big;
        M.transformDirection(SIDE, SIDE_T);
        m.quadT(R0.set(ta).fma(-wa, SIDE_T), R1.set(ta).fma(wa, SIDE_T), R2.set(tb).fma(wb, SIDE_T), R3.set(tb).fma(-wb, SIDE_T), ca, ca, cb, cb);
    }

    private static void ribbon(Mesh m, Vector3f a, Vector3f b, float wa, float wb, int ca, int cb) {
        if (legacyRibbons) {
            m.quad(R0.set(a.x - wa, a.y, a.z), R1.set(a.x + wa, a.y, a.z), R2.set(b.x + wb, b.y, b.z), R3.set(b.x - wb, b.y, b.z), ca, ca, cb, cb);
            m.quad(R0.set(a.x, a.y, a.z - wa), R1.set(a.x, a.y, a.z + wa), R2.set(b.x, b.y, b.z + wb), R3.set(b.x, b.y, b.z - wb), ca, ca, cb, cb);
            return;
        }
        float dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
        float vx = (a.x + b.x) * 0.5f - CAM_L.x, vy = (a.y + b.y) * 0.5f - CAM_L.y, vz = (a.z + b.z) * 0.5f - CAM_L.z;
        SIDE.set(dy * vz - dz * vy, dz * vx - dx * vz, dx * vy - dy * vx);
        float l = SIDE.length();
        if (l < 1e-9f) SIDE.set(1, 0, 0);
        else SIDE.div(l);
        // même rendu que les deux quads croisés (décalés en x et en z) : largeur apparente = leur plus grande
        // projection sur la direction vue, luminosité = somme des deux couches
        float px = Math.abs(SIDE.x), pz = Math.abs(SIDE.z), big = Math.max(px, pz);
        if (big < 1e-4f) return; // vus par la tranche, les deux quads croisés étaient invisibles aussi
        float k = Math.min(2f, (px + pz) / big);
        if (k > 1.001f) {
            ca = Mesh.withAlpha(ca, k);
            cb = Mesh.withAlpha(cb, k);
        }
        wa *= big;
        wb *= big;
        m.quad(R0.set(a).fma(-wa, SIDE), R1.set(a).fma(wa, SIDE), R2.set(b).fma(wb, SIDE), R3.set(b).fma(-wb, SIDE), ca, ca, cb, cb);
    }
}
