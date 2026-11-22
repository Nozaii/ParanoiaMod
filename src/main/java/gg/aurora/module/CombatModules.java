package gg.aurora.module;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import gg.aurora.mixin.GameRendererAccessor;
import gg.aurora.render.Filters;
import gg.aurora.ui.Icons;
import gg.aurora.ui.Rows;
import gg.aurora.waypoint.Waypoints;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Locale;

/** Shield Size, Hitboxes, Hit Color, Adaptive Detail, Filters. */
public final class CombatModules {
    private CombatModules() {}

    // ===================================================================== PvP

    public static final Module SHIELD_SIZE = Modules.register(new Module("shield_size", "Shield Size", Category.PVP, Icons.SHIELD, false))
            .withSettings(() -> List.of(
                    new Rows.Slider("Size", "Shield in off hand", 0.3, 1.2, 0.05, () -> CombatModules.SHIELD_SIZE.opt("scale", 0.6),
                            v -> CombatModules.SHIELD_SIZE.set("scale", v), v -> Math.round(v * 100) + " %"),
                    new Rows.Slider("Height", "Lower = less intrusive", -0.4, 0.2, 0.02, () -> CombatModules.SHIELD_SIZE.opt("down", -0.16),
                            v -> CombatModules.SHIELD_SIZE.set("down", v), v -> String.format(Locale.ROOT, "%+.2f", v)),
                    new Rows.Toggle("Also in the main hand", null, () -> CombatModules.SHIELD_SIZE.flag("main", false),
                            v -> CombatModules.SHIELD_SIZE.set("main", v))));

    public static final Module HITBOXES = Modules.register(new Module("hitboxes", "Combat Hitbox", Category.PVP, Icons.TARGET, false))
            .withSettings(() -> List.of(
                    new Rows.Toggle("Players", null, () -> CombatModules.HITBOXES.flag("players", true), v -> CombatModules.HITBOXES.set("players", v)),
                    new Rows.Toggle("Mobs", null, () -> CombatModules.HITBOXES.flag("mobs", false), v -> CombatModules.HITBOXES.set("mobs", v)),
                    new Rows.Toggle("Ground items", null, () -> CombatModules.HITBOXES.flag("items", false), v -> CombatModules.HITBOXES.set("items", v)),
                    new Rows.ColorRow("Color", () -> (int) CombatModules.HITBOXES.opt("color", 0xFFFFFF), v -> CombatModules.HITBOXES.set("color", v & 0xFFFFFF)),
                    new Rows.ColorRow("Target color", () -> (int) CombatModules.HITBOXES.opt("target", 0xFF4A4A),
                            v -> CombatModules.HITBOXES.set("target", v & 0xFFFFFF)),
                    new Rows.Toggle("Fill", "Light tint inside the box", () -> CombatModules.HITBOXES.flag("fill", false),
                            v -> CombatModules.HITBOXES.set("fill", v)),
                    new Rows.Toggle("Facing direction", null, () -> CombatModules.HITBOXES.flag("look", true), v -> CombatModules.HITBOXES.set("look", v)),
                    new Rows.Slider("Distance", null, 4, 64, 2, () -> CombatModules.HITBOXES.opt("dist", 24), v -> CombatModules.HITBOXES.set("dist", v),
                            v -> Math.round(v) + " blocs")));

    public static final Module HIT_COLOR = Modules.register(new Module("hit_color", "Hit Color", Category.PVP, Icons.HEART, false))
            .withSettings(() -> List.of(
                    new Rows.ColorRow("Color when hit", () -> (int) CombatModules.HIT_COLOR.opt("color", 0x7A5CFF),
                            v -> CombatModules.HIT_COLOR.set("color", v & 0xFFFFFF)),
                    new Rows.Slider("Intensity", null, 0.1, 1, 0.05, () -> CombatModules.HIT_COLOR.opt("alpha", 0.55),
                            v -> CombatModules.HIT_COLOR.set("alpha", v), v -> Math.round(v * 100) + " %")));

    // ===================================================================== Visuals

    public static final Module DETAIL = Modules.register(new Module("adaptive_detail", "Adaptive Detail", Category.VISUALS, Icons.EYE, false))
            .withSettings(() -> List.of(
                    new Rows.Info("Principle", "Far = less detail · near / zoom = more"),
                    new Rows.Slider("Detail distance", "Chests, signs, entities…", 16, 128, 4, () -> CombatModules.DETAIL.opt("dist", 48),
                            v -> CombatModules.DETAIL.set("dist", v), v -> Math.round(v) + " blocs"),
                    new Rows.Toggle("Follow the zoom", "Zoom brings far details back", () -> CombatModules.DETAIL.flag("zoom", true),
                            v -> CombatModules.DETAIL.set("zoom", v))));

    public static final Module FILTERS = Modules.register(new Module("filters", "Graphic filters", Category.VISUALS, Icons.PALETTE, false))
            .withSettings(() -> List.of(
                    new Rows.Button("Apply the Cursed style", "Storm sky, dark filter, dust, flames, jellyfish, wings, glasses",
                            Icons.SPARKLES, AmbientFx::applyCursedStyle),
                    new Rows.Slider("Filter", null, 0, Filters.PRESETS.length - 1, 1, () -> CombatModules.FILTERS.opt("preset", 1),
                            v -> CombatModules.FILTERS.set("preset", Math.round(v)), v -> Filters.PRESETS[(int) Math.round(v)]),
                    new Rows.Slider("Intensity", null, 0, 1, 0.1, () -> CombatModules.FILTERS.opt("strength", 0.7),
                            v -> CombatModules.FILTERS.set("strength", v), v -> Math.round(v * 100) + " %"),
                    new Rows.Slider("Glow (bloom)", null, 0, 1, 0.1, () -> CombatModules.FILTERS.opt("bloom", 0.4),
                            v -> CombatModules.FILTERS.set("bloom", v), v -> Math.round(v * 100) + " %"),
                    new Rows.Slider("Vignette", null, 0, 1, 0.1, () -> CombatModules.FILTERS.opt("vignette", 0.4),
                            v -> CombatModules.FILTERS.set("vignette", v), v -> Math.round(v * 100) + " %"),
                    new Rows.Slider("Sharpness", null, 0, 1, 0.1, () -> CombatModules.FILTERS.opt("sharpen", 0.2),
                            v -> CombatModules.FILTERS.set("sharpen", v), v -> Math.round(v * 100) + " %")));

    // ===================================================================== Adaptive Detail

    /** Force le chargement (enregistrement des modules avant la lecture de la config). */
    public static void init() {}

    /** Facteur de zoom courant (1 = champ de vision normal, 4 = zoom ×4). */
    private static float zoomFactor = 1;

    public static void tick(Minecraft mc) {
        if (DETAIL.enabled && DETAIL.flag("zoom", true) && mc.level != null) {
            try {
                var cam = mc.gameRenderer.getMainCamera();
                float fov = ((GameRendererAccessor) mc.gameRenderer).aurora$getFov(cam, 1f, true);
                zoomFactor = Math.max(1f, mc.options.fov().get() / Math.max(1f, fov));
            } catch (RuntimeException e) {
                zoomFactor = 1;
            }
        } else {
            zoomFactor = 1;
        }
        applyHitColor(mc);
    }

    /** Distance au-delà de laquelle les détails (entités, blocs-entités) ne sont plus dessinés. */
    public static double detailDistance() {
        return DETAIL.opt("dist", 48) * zoomFactor;
    }

    public static boolean allowDetail(double distSq) {
        if (!DETAIL.enabled) return true;
        double d = detailDistance();
        return distSq <= d * d;
    }

    // ===================================================================== Hit Color

    private static int appliedHit = 0xB2FF0000;

    private static void applyHitColor(Minecraft mc) {
        int want = HIT_COLOR.enabled
                ? (Math.round((float) HIT_COLOR.opt("alpha", 0.55) * 255) << 24) | ((int) HIT_COLOR.opt("color", 0x7A5CFF) & 0xFFFFFF)
                : 0xB2FF0000;
        if (want == appliedHit) return;
        try {
            var tex = ((gg.aurora.mixin.OverlayTextureAccessor) mc.gameRenderer.overlayTexture()).aurora$texture();
            var img = tex.getPixels();
            if (img == null) return;
            for (int y = 0; y < 8; y++) for (int x = 0; x < 16; x++) img.setPixel(x, y, want);
            tex.upload();
            appliedHit = want;
        } catch (RuntimeException e) {
            gg.aurora.AuroraClient.LOGGER.warn("Hit Color: {}", e.toString());
            appliedHit = want;
        }
    }

    // ===================================================================== Hitboxes

    public static void renderHitboxes(PoseStack pose, MultiBufferSource consumers, Vec3 cam, float pt) {
        Minecraft mc = Minecraft.getInstance();
        if (!HITBOXES.enabled || consumers == null || mc.level == null || mc.player == null) return;
        double max = HITBOXES.opt("dist", 24);
        Entity target = mc.hitResult instanceof EntityHitResult eh ? eh.getEntity() : null;
        int base = (int) HITBOXES.opt("color", 0xFFFFFF), tc = (int) HITBOXES.opt("target", 0xFF4A4A);
        boolean fill = HITBOXES.flag("fill", false), look = HITBOXES.flag("look", true);
        boolean players = HITBOXES.flag("players", true), mobs = HITBOXES.flag("mobs", false), items = HITBOXES.flag("items", false);
        VertexConsumer vc = consumers.getBuffer(RenderTypes.debugQuads());
        Matrix4f m = pose.last().pose();
        gg.aurora.render.FxCull.begin();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player && mc.options.getCameraType().isFirstPerson()) continue;
            boolean ok = e instanceof Player ? players : e instanceof ItemEntity ? items : e instanceof LivingEntity && mobs;
            if (!ok || e.isInvisible() || e.distanceToSqr(cam) > max * max) continue;
            if (!gg.aurora.render.FxCull.visible(e, cam, 2)) continue; // off screen: nothing to draw
            double ix = Mathx(e.xo, e.getX(), pt) - e.getX(), iy = Mathx(e.yo, e.getY(), pt) - e.getY(), iz = Mathx(e.zo, e.getZ(), pt) - e.getZ();
            AABB b = e.getBoundingBox().move(ix - cam.x, iy - cam.y, iz - cam.z);
            int col = e == target ? tc : base;
            int hurt = e instanceof LivingEntity le && le.hurtTime > 0 ? 1 : 0;
            if (hurt == 1) col = 0xFF4A4A;
            Waypoints.box(vc, m, (float) b.minX, (float) b.minY, (float) b.minZ, (float) b.maxX, (float) b.maxY, (float) b.maxZ,
                    fill ? (0x22 << 24) | col : 0, 0xE0000000 | col);
            if (look && e instanceof LivingEntity le) {
                Vec3 eye = new Vec3(b.getCenter().x, b.minY + le.getEyeHeight(), b.getCenter().z);
                Vec3 dir = le.getViewVector(pt).scale(1.6);
                line(vc, m, eye, eye.add(dir), 0xFF3A7BFF);
            }
        }
        if (consumers instanceof MultiBufferSource.BufferSource bs) bs.endBatch(RenderTypes.debugQuads());
    }

    private static double Mathx(double a, double b, float t) {
        return a + (b - a) * t;
    }

    /** Segment fin face caméra (la caméra est à l'origine). */
    private static void line(VertexConsumer vc, Matrix4f m, Vec3 a, Vec3 b, int col) {
        Vec3 d = b.subtract(a), mid = a.add(b).scale(0.5);
        Vec3 side = d.cross(mid);
        if (side.lengthSqr() < 1e-8) return;
        side = side.normalize().scale(0.0025 * Math.max(0.5, mid.length()));
        vc.addVertex(m, (float) (a.x - side.x), (float) (a.y - side.y), (float) (a.z - side.z)).setColor(col).setLight(0xF000F0);
        vc.addVertex(m, (float) (a.x + side.x), (float) (a.y + side.y), (float) (a.z + side.z)).setColor(col).setLight(0xF000F0);
        vc.addVertex(m, (float) (b.x + side.x), (float) (b.y + side.y), (float) (b.z + side.z)).setColor(col).setLight(0xF000F0);
        vc.addVertex(m, (float) (b.x - side.x), (float) (b.y - side.y), (float) (b.z - side.z)).setColor(col).setLight(0xF000F0);
    }
}
