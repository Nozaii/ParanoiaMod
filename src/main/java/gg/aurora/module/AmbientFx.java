package gg.aurora.module;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import gg.aurora.render.FxRender;
import gg.aurora.ui.Icons;
import gg.aurora.ui.Rows;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Particules d'ambiance (poussières lumineuses qui flottent dans l'air autour de la caméra) et
 * flammes d'objet (flammes qui montent de l'objet tenu en main). Sprites additifs, sans particules
 * vanilla : quasi gratuit.
 */
public final class AmbientFx {
    private AmbientFx() {}

    private static final Identifier GLOW = Identifier.fromNamespaceAndPath("aurora", "textures/ui/glow.png");
    public static final String[] STYLES = {"Dust", "Petals", "Stars", "Snow", "Fireflies", "Embers"};

    public static final Module AMBIENT = Modules.register(new Module("ambient_particles", "Ambient particles", Category.VISUALS,
            Icons.SPARKLES, false))
            .withSettings(() -> List.of(
                    new Rows.Choice("Style", STYLES, () -> (int) AmbientFx.AMBIENT.opt("style", 0), i -> AmbientFx.AMBIENT.set("style", i)),
                    new Rows.Slider("Density", null, 20, 400, 10, () -> AmbientFx.AMBIENT.opt("count", 160), v -> AmbientFx.AMBIENT.set("count", v),
                            v -> Math.round(v) + ""),
                    new Rows.Slider("Size", null, 0.3, 2, 0.1, () -> AmbientFx.AMBIENT.opt("size", 1), v -> AmbientFx.AMBIENT.set("size", v),
                            v -> Math.round(v * 100) + " %"),
                    new Rows.ColorRow("Color", () -> (int) AmbientFx.AMBIENT.opt("color", 0xE8E6FF), v -> AmbientFx.AMBIENT.set("color", v & 0xFFFFFF)),
                    new Rows.Slider("Speed", null, 0, 3, 0.1, () -> AmbientFx.AMBIENT.opt("speed", 1), v -> AmbientFx.AMBIENT.set("speed", v),
                            v -> String.format(java.util.Locale.ROOT, "%.1f×", v)),
                    new Rows.Toggle("Twinkle", null, () -> AmbientFx.AMBIENT.flag("twinkle", true), v -> AmbientFx.AMBIENT.set("twinkle", v))));

    public static final Module ITEM_FLAMES = Modules.register(new Module("item_flames", "Item flames", Category.VISUALS, Icons.BOLT, false))
            .withSettings(() -> List.of(
                    new Rows.ColorRow("Color", () -> (int) AmbientFx.ITEM_FLAMES.opt("color", 0x9D3CFF),
                            v -> AmbientFx.ITEM_FLAMES.set("color", v & 0xFFFFFF)),
                    new Rows.Slider("Intensity", null, 0.2, 3, 0.1, () -> AmbientFx.ITEM_FLAMES.opt("rate", 1), v -> AmbientFx.ITEM_FLAMES.set("rate", v),
                            v -> Math.round(v * 100) + " %"),
                    new Rows.Slider("Height", null, 0.3, 2, 0.1, () -> AmbientFx.ITEM_FLAMES.opt("height", 1), v -> AmbientFx.ITEM_FLAMES.set("height", v),
                            v -> Math.round(v * 100) + " %"),
                    new Rows.Toggle("Other players", null, () -> AmbientFx.ITEM_FLAMES.flag("others", false), v -> AmbientFx.ITEM_FLAMES.set("others", v))));

    /** Une flamme : position monde, vitesse, âge (en secondes) et durée de vie. */
    private static final class Flame {
        double x, y, z;
        float vx, vy, vz, age, life, size;
    }

    private static final List<Flame> FLAMES = new ArrayList<>();
    private static long last = System.nanoTime();
    private static float spawnAcc;
    private static final java.util.Random RND = new java.util.Random();

    private static final Vector3f TRIGHT = new Vector3f(), TUP = new Vector3f(), TLOOK = new Vector3f();
    /** Four fixed random numbers per particle. */
    private static final float[] SEEDS = new float[4096 * 4];

    static {
        for (int i = 0; i < 4096; i++) for (int k = 0; k < 4; k++) SEEDS[i * 4 + k] = hash(i, k + 1);
    }

    private static float hash(int a, int b) {
        int h = a * 374761393 + b * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFFFF) / (float) 0xFFFFFF;
    }

    /** Main qui tient l'objet, en coordonnées monde (vue à la 1re personne : devant la caméra, en bas à droite). */
    private static Vec3 handPos(Minecraft mc, Player p, float pt) {
        if (p == mc.player && mc.options.getCameraType().isFirstPerson()) {
            var cam = mc.gameRenderer.getMainCamera();
            Quaternionf r = cam.rotation();
            Vector3f f = r.transform(new Vector3f(0, 0, -1)), right = r.transform(new Vector3f(1, 0, 0)), up = r.transform(new Vector3f(0, 1, 0));
            Vec3 c = cam.position();
            // l'objet tenu est dessiné avec son propre champ de vision : on vise sa position à l'écran
            // (68 % vers la droite, 30 % vers le bas : juste au-dessus de l objet) puis on la projette dans le champ de vision du monde
            float fov;
            try {
                fov = ((gg.aurora.mixin.GameRendererAccessor) mc.gameRenderer).aurora$getFov(cam, pt, true);
            } catch (RuntimeException e) {
                fov = mc.options.fov().get();
            }
            double tv = Math.tan(Math.toRadians(fov) / 2), th = tv * mc.getWindow().getWidth() / Math.max(1.0, mc.getWindow().getHeight());
            double d = 1.0, sx = 0.68 * d * th, sy = -0.3 * d * tv;
            return new Vec3(c.x + f.x * d + right.x * sx + up.x * sy, c.y + f.y * d + right.y * sx + up.y * sy,
                    c.z + f.z * d + right.z * sx + up.z * sy);
        }
        float yaw = (float) Math.toRadians(Mth.rotLerp(pt, p.yBodyRotO, p.yBodyRot));
        double fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        double x = Mth.lerp(pt, p.xo, p.getX()), y = Mth.lerp(pt, p.yo, p.getY()), z = Mth.lerp(pt, p.zo, p.getZ());
        return new Vec3(x + rx * 0.38 + fx * 0.35, y + (p.isCrouching() ? 0.55 : 0.75), z + rz * 0.38 + fz * 0.35);
    }

    public static void renderWorld(PoseStack pose, MultiBufferSource consumers, Vec3 cam, float pt) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - last) / 1e9f);
        last = now;
        if (consumers == null || mc.level == null || mc.player == null || (!AMBIENT.enabled && !ITEM_FLAMES.enabled && FLAMES.isEmpty())) return;
        RenderType type = FxRender.additive(GLOW);
        VertexConsumer vc = consumers.getBuffer(type);
        Quaternionf rot = mc.gameRenderer.getMainCamera().rotation();
        Vector3f right = rot.transform(TRIGHT.set(1, 0, 0)), up = rot.transform(TUP.set(0, 1, 0)), look = rot.transform(TLOOK.set(0, 0, -1));
        Matrix4f m = pose.last().pose();
        float time = (float) ((System.currentTimeMillis() % 3_600_000L) / 1000.0);

        if (AMBIENT.enabled) {
            // champ de poussières ancré au monde (pas à la caméra), replié dans un cube autour d'elle
            int count = (int) AMBIENT.opt("count", 160);
            float S = 22, size = 0.055f * (float) AMBIENT.opt("size", 1), speed = (float) AMBIENT.opt("speed", 1);
            int col = (int) AMBIENT.opt("color", 0xE8E6FF);
            boolean twinkle = AMBIENT.flag("twinkle", true);
            int style = Math.max(0, Math.min(STYLES.length - 1, (int) AMBIENT.opt("style", 0)));
            for (int i = 0; i < count; i++) {
                int k4 = (i & 4095) * 4;
                float r1 = SEEDS[k4], r2 = SEEDS[k4 + 1], r3 = SEEDS[k4 + 2], r4 = SEEDS[k4 + 3];
                double bx = r1 * S + Mth.sin(time * 0.13f * speed + r4 * 30) * 1.5 + time * 0.08 * speed * (r2 - 0.5);
                double by = r2 * S + Mth.sin(time * 0.17f * speed + r1 * 20) * 0.8 + time * RISE[style] * speed * (0.6 + r3 * 0.8);
                double bz = r3 * S + Mth.cos(time * 0.11f * speed + r2 * 40) * 1.5;
                double x = wrap(bx - cam.x, S), y = wrap(by - cam.y, S), z = wrap(bz - cam.z, S);
                if (x * look.x + y * look.y + z * look.z < -1) continue; // behind the camera
                double d = Math.sqrt(x * x + y * y + z * z);
                // s'estompent au bord du cube et tout près de la caméra
                float fade = (float) (Math.min(1, (S / 2 - Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)))) / 3) * Math.min(1, (d - 0.6) / 1.5));
                if (fade <= 0) continue;
                float tw = twinkle ? 0.45f + 0.55f * Math.max(0, Mth.sin(time * (1.2f + r3 * 2.5f) + r4 * 40)) : 0.8f;
                float s = size * (0.6f + 0.8f * r4);
                float fx = (float) x, fy = (float) y, fz = (float) z;
                switch (style) {
                    case 1 -> { // petal: thin flake that turns as it falls
                        float a = time * (0.8f + r3) + r4 * 6.28f, ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                        TR.set(right).mul(ca).fma(sa, up).mul(1.6f);
                        TU.set(up).mul(ca).fma(-sa, right).mul(0.45f + 0.55f * Math.abs((float) Math.sin(a * 1.3f)));
                        sprite(vc, m, TR, TU, fx, fy, fz, s * 1.3f, Mesh2.lerp(col, 0xFFD2E6, r2), 0.9f * fade);
                    }
                    case 2 -> { // star: four-point glint
                        float k = s * (0.6f + 1.2f * tw);
                        TR.set(right).mul(3.2f);
                        TU.set(up).mul(0.22f);
                        sprite(vc, m, TR, TU, fx, fy, fz, k, 0xFFFFFF, 0.9f * fade * tw);
                        TR.set(right).mul(0.22f);
                        TU.set(up).mul(3.2f);
                        sprite(vc, m, TR, TU, fx, fy, fz, k, 0xFFFFFF, 0.9f * fade * tw);
                        sprite(vc, m, right, up, fx, fy, fz, k * 2.2f, col, 0.3f * fade * tw);
                    }
                    case 3 -> sprite(vc, m, right, up, fx, fy, fz, s * 0.9f, 0xFFFFFF, 0.85f * fade);
                    case 4 -> { // firefly: slow blink
                        float b = (float) Math.pow(Math.max(0, Math.sin(time * (0.7 + r3) + r4 * 40)), 3);
                        sprite(vc, m, right, up, fx, fy, fz, s * 0.7f, 0xF4FFB0, fade * b);
                        sprite(vc, m, right, up, fx, fy, fz, s * 4f, 0xB8FF5A, 0.4f * fade * b);
                    }
                    case 5 -> { // ember: glows then dies as it rises
                        sprite(vc, m, right, up, fx, fy, fz, s * 0.6f, 0xFFE2A8, 0.95f * fade * tw);
                        sprite(vc, m, right, up, fx, fy, fz, s * 2.6f, 0xFF6A1E, 0.45f * fade * tw);
                    }
                    default -> {
                        sprite(vc, m, right, up, fx, fy, fz, s, 0xFFFFFF, 0.95f * fade * tw);
                        sprite(vc, m, right, up, fx, fy, fz, s * 4f, col, 0.3f * fade * tw);
                    }
                }
            }
        }

        if (ITEM_FLAMES.enabled) {
            boolean others = ITEM_FLAMES.flag("others", false);
            float rate = 38 * (float) ITEM_FLAMES.opt("rate", 1);
            float height = (float) ITEM_FLAMES.opt("height", 1);
            for (Player p : mc.level.players()) {
                if (p != mc.player && (!others || p.distanceToSqr(cam) > 32 * 32)) continue;
                if (p.getMainHandItem().isEmpty() || p.isInvisible()) continue;
                Vec3 h = handPos(mc, p, pt);
                spawnAcc += rate * dt;
                while (spawnAcc >= 1) {
                    spawnAcc -= 1;
                    Flame f = new Flame();
                    f.x = h.x + (RND.nextFloat() - 0.5f) * 0.12f;
                    f.y = h.y + (RND.nextFloat() - 0.2f) * 0.15f;
                    f.z = h.z + (RND.nextFloat() - 0.5f) * 0.12f;
                    f.vx = (RND.nextFloat() - 0.5f) * 0.12f;
                    f.vz = (RND.nextFloat() - 0.5f) * 0.12f;
                    f.vy = (0.45f + RND.nextFloat() * 0.5f) * height;
                    f.life = 0.55f + RND.nextFloat() * 0.5f;
                    f.size = 0.05f + RND.nextFloat() * 0.06f;
                    FLAMES.add(f);
                }
            }
            if (FLAMES.size() > 600) FLAMES.subList(0, FLAMES.size() - 600).clear();
        }
        int fc = (int) ITEM_FLAMES.opt("color", 0x9D3CFF);
        int hot = Mesh2.lerp(fc, 0xFFFFFF, 0.55f);
        for (int i = FLAMES.size() - 1; i >= 0; i--) {
            Flame f = FLAMES.get(i);
            f.age += dt;
            if (f.age >= f.life) {
                FLAMES.remove(i);
                continue;
            }
            float u = f.age / f.life;
            // monte en ondulant, ralentit, se resserre
            f.x += (f.vx + Mth.sin(time * 9 + i) * 0.08) * dt;
            f.y += f.vy * dt;
            f.z += (f.vz + Mth.cos(time * 7 + i) * 0.08) * dt;
            f.vy *= 1 - dt * 0.6f;
            float a = Mth.sin(Mth.PI * Math.min(1, u * 1.4f)) * (1 - u);
            float s = f.size * (1 - u * 0.65f);
            float x = (float) (f.x - cam.x), y = (float) (f.y - cam.y), z = (float) (f.z - cam.z);
            sprite(vc, m, right, up, x, y, z, s, Mesh2.lerp(hot, fc, u), 0.9f * a);
            sprite(vc, m, right, up, x, y, z, s * 2.6f, fc, 0.25f * a);
        }
        if (consumers instanceof MultiBufferSource.BufferSource bs) bs.endBatch(type);
    }

    private static final float[] RISE = {0.05f, -0.55f, 0.02f, -0.9f, 0.04f, 0.6f};
    private static final Vector3f TR = new Vector3f(), TU = new Vector3f();

    private static double wrap(double v, double s) {
        double h = s / 2;
        return v - s * Math.floor((v + h) / s); // fmod on doubles is slow, and this runs per particle
    }

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

    /** Mélange de couleurs RVB. */
    private static final class Mesh2 {
        static int lerp(int a, int b, float t) {
            int r = 0;
            for (int sh = 0; sh <= 16; sh += 8) {
                int ca = (a >>> sh) & 255, cb = (b >>> sh) & 255;
                r |= Math.round(ca + (cb - ca) * t) << sh;
            }
            return r;
        }
    }

    // ===================================================================== style « Cursed »

    /** Applique d'un coup l'ambiance de la vidéo : ciel de nuit d'orage, filtre sombre désaturé, poussières, flammes. */
    public static void applyCursedStyle() {
        WorldModules.CUSTOM_SKY.enabled = true;
        WorldModules.CUSTOM_SKY.set("ambience", 7);
        WorldModules.CUSTOM_SKY.set("ambienceAlpha", 1.0);
        CombatModules.FILTERS.enabled = true;
        CombatModules.FILTERS.set("preset", 7);
        CombatModules.FILTERS.set("strength", 0.85);
        CombatModules.FILTERS.set("vignette", 0.6);
        CombatModules.FILTERS.set("bloom", 0.35);
        AMBIENT.enabled = true;
        ITEM_FLAMES.enabled = true;
        gg.aurora.Settings.pet = "meduse";
        gg.aurora.Settings.wings = "maudites";
        gg.aurora.Settings.halo = "hat_lunettes";
        gg.aurora.Config.save();
        gg.aurora.nametag.NametagSync.markDirty();
    }

    /** Force le chargement (enregistrement des modules). */
    public static void init() {}
}
