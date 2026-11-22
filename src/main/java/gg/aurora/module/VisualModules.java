package gg.aurora.module;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import gg.aurora.render.FxRender;
import gg.aurora.ui.Draw;
import gg.aurora.ui.Icons;
import gg.aurora.ui.Rows;
import gg.aurora.ui.Theme;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Visuels : Draw Crosshair, Hand Pose, Hand Aura, Jump Circles, Target ESP, Custom Glint,
 * Swing Animation, Custom FOV.
 */
public final class VisualModules {
    private VisualModules() {}

    private static String pct(double v) {
        return Math.round(v * 100) + " %";
    }

    private static String f2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** Couleur « thème » (accent du menu) ou couleur choisie. */
    public static int color(Module m, int def) {
        return m.flag("theme", true) ? Theme.accent() : (int) m.opt("color", def);
    }

    private static List<Rows.Row> colorRows(Module m, int def) {
        return List.of(
                new Rows.Toggle("Theme color", "Follows the menu accent", () -> m.flag("theme", true), v -> m.set("theme", v)),
                new Rows.ColorRow("Color", () -> (int) m.opt("color", def), v -> m.set("color", v & 0xFFFFFF)));
    }

    // ===================================================================== Draw Crosshair

    public static final String[] CROSS_STYLES = {"Cross", "Dot", "Cross + dot", "Circle", "Circle + dot", "T", "Chevron", "Custom drawing"};

    public static final Module CROSSHAIR = Modules.register(new Module("draw_crosshair", "Draw Crosshair", Category.VISUALS, Icons.TARGET, false))
            .withSettings(() -> List.of(
                    new Rows.Button("Open the crosshair editor", "Ready templates + 16 / 32 / 64 grid to draw", Icons.WAND, () -> {
                        var mc = Minecraft.getInstance();
                        mc.setScreen(new gg.aurora.ui.CrosshairScreen(mc.screen));
                    }),
                    new Rows.Choice("Shape", CROSS_STYLES, () -> (int) VisualModules.CROSSHAIR.opt("style", 2),
                            i -> VisualModules.CROSSHAIR.set("style", i)),
                    new Rows.ColorRow("Color", () -> (int) VisualModules.CROSSHAIR.opt("color", 0xFFFFFF),
                            v -> VisualModules.CROSSHAIR.set("color", v & 0xFFFFFF)),
                    new Rows.Toggle("Color on a target", "Changes color when aiming at an entity",
                            () -> VisualModules.CROSSHAIR.flag("onTarget", true), v -> VisualModules.CROSSHAIR.set("onTarget", v)),
                    new Rows.ColorRow("Color on target", () -> (int) VisualModules.CROSSHAIR.opt("targetColor", 0xFF4A5A),
                            v -> VisualModules.CROSSHAIR.set("targetColor", v & 0xFFFFFF)),
                    new Rows.Slider("Size", null, 1, 12, 0.5, () -> VisualModules.CROSSHAIR.opt("size", 4),
                            v -> VisualModules.CROSSHAIR.set("size", v), v -> String.format(Locale.ROOT, "%.1f", v)),
                    new Rows.Slider("Gap", null, 0, 8, 0.5, () -> VisualModules.CROSSHAIR.opt("gap", 2),
                            v -> VisualModules.CROSSHAIR.set("gap", v), v -> String.format(Locale.ROOT, "%.1f", v)),
                    new Rows.Slider("Thickness", null, 0.5, 3, 0.25, () -> VisualModules.CROSSHAIR.opt("thick", 1),
                            v -> VisualModules.CROSSHAIR.set("thick", v), v -> String.format(Locale.ROOT, "%.2f", v)),
                    new Rows.Slider("Drawing size", "Hand-drawn crosshair", 8, 64, 1, () -> VisualModules.CROSSHAIR.opt("drawSize", 16),
                            v -> VisualModules.CROSSHAIR.set("drawSize", v), v -> Math.round(v) + " px"),
                    new Rows.Toggle("Outline", "Dark edge to stay readable", () -> VisualModules.CROSSHAIR.flag("outline", true),
                            v -> VisualModules.CROSSHAIR.set("outline", v)),
                    new Rows.Toggle("Dynamic", "Spreads when running and hitting", () -> VisualModules.CROSSHAIR.flag("dynamic", false),
                            v -> VisualModules.CROSSHAIR.set("dynamic", v)),
                    new Rows.Toggle("Attack cooldown", "Small bar under the crosshair", () -> VisualModules.CROSSHAIR.flag("cooldown", true),
                            v -> VisualModules.CROSSHAIR.set("cooldown", v))));

    private static float crossSpread;

    /** Dessine le viseur. Retourne false si le viseur vanilla doit être gardé (écran de debug). */
    public static boolean drawCrosshair(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getDebugOverlay().showDebugScreen()) return false;
        if (!mc.options.getCameraType().isFirstPerson() || mc.player == null) return true;
        if (mc.gameMode != null && mc.gameMode.getPlayerMode() == net.minecraft.world.level.GameType.SPECTATOR
                && !(mc.hitResult instanceof EntityHitResult)) return true;
        Module m = CROSSHAIR;
        int style = (int) m.opt("style", 2);
        boolean target = m.flag("onTarget", true) && mc.hitResult instanceof EntityHitResult;
        int col = 0xFF000000 | (target ? (int) m.opt("targetColor", 0xFF4A5A) : (int) m.opt("color", 0xFFFFFF));
        float size = (float) m.opt("size", 4), gap = (float) m.opt("gap", 2), th = (float) m.opt("thick", 1);
        if (m.flag("dynamic", false)) {
            LocalPlayer p = mc.player;
            float want = (p.isSprinting() ? 2.5f : 0) + (p.getDeltaMovement().horizontalDistanceSqr() > 0.003 ? 1.2f : 0)
                    + (p.swinging ? 2f : 0) + (!p.onGround() ? 1.5f : 0);
            crossSpread += (want - crossSpread) * 0.25f;
            gap += crossSpread;
        } else {
            crossSpread = 0;
        }
        // centre exact de l'écran en vrais pixels (le rayon de visée), aligné sur la grille de pixels pour
        // une épaisseur nette : guiWidth()/2 se décale dès que la fenêtre n'est pas un multiple de l'échelle
        var win = mc.getWindow();
        float scale = (float) win.getGuiScale();
        int thPx = Math.max(1, Math.round(th * scale));
        float cx = (float) (Math.round(win.getWidth() / 2.0 - thPx / 2.0) + thPx / 2.0) / scale;
        float cy = (float) (Math.round(win.getHeight() / 2.0 - thPx / 2.0) + thPx / 2.0) / scale;
        th = thPx / scale;
        gap = Math.round(gap * scale) / scale;
        size = Math.max(1, Math.round(size * scale)) / scale;
        if (style == CROSS_STYLES.length - 1) {
            // dessin perso : l'image telle que dessinée, centrée
            if (gg.aurora.ui.CrosshairArt.ready()) {
                int n = gg.aurora.ui.CrosshairArt.size;
                float ds = (float) m.opt("drawSize", 16) + (m.flag("dynamic", false) ? crossSpread * 2 : 0);
                int tintCol = target ? col : 0xFFFFFFFF;
                // whole pixels, same parity as the window: identical margins on both sides
                int p = Math.max(1, Math.round(ds * scale));
                if (((win.getWidth() - p) & 1) != 0) p++;
                ds = p / scale;
                float ix = ((win.getWidth() - p) / 2) / scale, iy = ((win.getHeight() - p) / 2) / scale;
                Draw.image(g, gg.aurora.ui.CrosshairArt.TEX, ix, iy, ds, ds, n, n, 0, 0, n, n, tintCol);
                size = ds / 2 - gap;
            }
        } else {
            shape(g, cx, cy, style, col, size, gap, th, m.flag("outline", true), 1 / scale);
        }
        if (m.flag("cooldown", true) && mc.options.attackIndicator().get() != AttackIndicatorStatus.OFF) {
            float k = mc.player.getAttackStrengthScale(0);
            if (k < 1f) {
                float w = 14, y = cy + gap + size + 4;
                Draw.rrect(g, cx - w / 2, y, w, 2, 1, 0x80000000);
                Draw.rrect(g, cx - w / 2, y, w * k, 2, 1, col);
            }
        }
        return true;
    }

    /** Aperçu d'une forme (éditeur de viseur), à l'échelle donnée. */
    public static void drawCrosshairShape(GuiGraphics g, float cx, float cy, int style, int col, float scale) {
        shape(g, cx, cy, style, col, 5 * scale, 2.5f * scale, 1.2f * scale, true, 0.5f);
    }

    /** px = one real pixel in GUI units: every edge lands on the pixel grid, arms mirror each other exactly. */
    private static void shape(GuiGraphics g, float cx, float cy, int style, int col, float size, float gap, float th, boolean outline, float px) {
        for (int pass = outline ? 0 : 1; pass < 2; pass++) {
            float o = pass == 0 ? px : 0;
            int c = pass == 0 ? 0xB0000000 : col;
            boolean lines = style == 0 || style == 2 || style == 5;
            if (lines) {
                float hx = th / 2, hy = th / 2;
                bar(g, cx - hx - o, cy - hy - gap - size - o, th + 2 * o, size + 2 * o, c, style == 5);
                bar(g, cx - hx - o, cy + hy + gap - o, th + 2 * o, size + 2 * o, c, false);
                bar(g, cx - hx - gap - size - o, cy - hy - o, size + 2 * o, th + 2 * o, c, false);
                bar(g, cx + hx + gap - o, cy - hy - o, size + 2 * o, th + 2 * o, c, false);
            }
            if (style == 1 || style == 2 || style == 4) {
                float d = th + 2 * px * Math.round(0.2f * th / px) + 2 * o;
                Draw.rrect(g, cx - d / 2, cy - d / 2, d, d, d / 2, c);
            }
            if (style == 3 || style == 4) {
                float r = gap + size * 0.6f + o;
                for (float k = 0; k < th + 2 * o; k += 0.5f)
                    Draw.ring(g, cx - r + k, cy - r + k, 2 * (r - k), 2 * (r - k), r - k, c);
            }
            if (style == 6) {
                for (int sd = -1; sd <= 1; sd += 2) {
                    for (float k = 0; k < size; k += 0.5f) {
                        Draw.rrect(g, cx + sd * (gap * 0.5f + k) - th / 2 - o, cy + gap * 0.5f + k - th / 2 - o, th + 2 * o, th + 2 * o, th / 2, c);
                    }
                }
            }
        }
    }

    private static void bar(GuiGraphics g, float x, float y, float w, float h, int c, boolean skip) {
        if (!skip) Draw.rect(g, x, y, w, h, c);
    }

    // ===================================================================== Hand Pose

    public static final Module HAND_POSE = Modules.register(new Module("hand_pose", "Hand Pose", Category.VISUALS, Icons.PERSON, false))
            .withSettings(VisualModules::handPoseRows);

    private static String handKey(String k) {
        boolean off = !HAND_POSE.flag("link", false) && HAND_POSE.opt("editing", 0) == 1;
        return (off ? "o" : "m") + k;
    }

    private static Rows.Slider poseSlider(String label, String k, double min, double max, double step, double def,
                                          java.util.function.DoubleFunction<String> fmt) {
        return new Rows.Slider(label, null, min, max, step, () -> HAND_POSE.opt(handKey(k), def), v -> HAND_POSE.set(handKey(k), v), fmt);
    }

    private static List<Rows.Row> handPoseRows() {
        List<Rows.Row> rows = new ArrayList<>();
        rows.add(new Rows.Toggle("Link hands", "Same settings for both hands", () -> HAND_POSE.flag("link", false),
                v -> HAND_POSE.set("link", v)));
        rows.add(new Rows.Choice("Edited hand", new String[]{"Main hand", "Off hand"}, () -> (int) HAND_POSE.opt("editing", 0),
                i -> HAND_POSE.set("editing", i)));
        rows.add(poseSlider("Offset X", "x", -0.6, 0.6, 0.01, 0, VisualModules::f2));
        rows.add(poseSlider("Offset Y", "y", -0.6, 0.6, 0.01, 0, VisualModules::f2));
        rows.add(poseSlider("Offset Z", "z", -0.6, 0.6, 0.01, 0, VisualModules::f2));
        rows.add(poseSlider("Pitch", "pitch", -90, 90, 1, 0, v -> Math.round(v) + "°"));
        rows.add(poseSlider("Rotation", "yaw", -90, 90, 1, 0, v -> Math.round(v) + "°"));
        rows.add(poseSlider("Roll", "roll", -90, 90, 1, 0, v -> Math.round(v) + "°"));
        rows.add(poseSlider("Size", "scale", 0.3, 1.5, 0.05, 1, VisualModules::pct));
        rows.add(new Rows.Button("Reset hand", null, Icons.ARROW_LEFT, () -> {
            for (String k : new String[]{"x", "y", "z", "pitch", "yaw", "roll", "scale"}) HAND_POSE.opts.remove(handKey(k));
        }));
        return rows;
    }

    /** Applique Hand Pose autour du point où la main est dessinée. */
    public static boolean applyHandPose(PoseStack pose, InteractionHand hand) {
        if (!HAND_POSE.enabled) return false;
        String p = HAND_POSE.flag("link", false) || hand == InteractionHand.MAIN_HAND ? "m" : "o";
        float x = (float) HAND_POSE.opt(p + "x", 0), y = (float) HAND_POSE.opt(p + "y", 0), z = (float) HAND_POSE.opt(p + "z", 0);
        float pitch = (float) HAND_POSE.opt(p + "pitch", 0), yaw = (float) HAND_POSE.opt(p + "yaw", 0), roll = (float) HAND_POSE.opt(p + "roll", 0);
        float s = (float) HAND_POSE.opt(p + "scale", 1);
        boolean right = (hand == InteractionHand.MAIN_HAND) == (Minecraft.getInstance().options.mainHand().get() == net.minecraft.world.entity.HumanoidArm.RIGHT);
        float side = right ? 0.56f : -0.56f;
        pose.pushPose();
        // la main gauche est le miroir de la droite : X, rotation et roulis inversés
        float mx = right ? 1 : -1;
        pose.translate(side + x * mx, -0.52f + y, -0.72f + z);
        pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(pitch));
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(yaw * mx));
        pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(roll * mx));
        pose.scale(s, s, s);
        pose.translate(-side, 0.52f, 0.72f);
        return true;
    }

    // ===================================================================== Hand Aura

    public static final Module HAND_AURA = Modules.register(new Module("hand_aura", "Hand Aura", Category.VISUALS, Icons.SPARKLES, false))
            .withSettings(() -> {
                List<Rows.Row> r = new ArrayList<>(colorRows(VisualModules.HAND_AURA, 0x9D7CFF));
                r.add(new Rows.Slider("Aura", "Glow intensity", 0, 100, 5, () -> VisualModules.HAND_AURA.opt("aura", 70),
                        v -> VisualModules.HAND_AURA.set("aura", v), v -> Math.round(v) + ""));
                r.add(new Rows.Slider("Reach", "Glow size", 1, 20, 1, () -> VisualModules.HAND_AURA.opt("reach", 10),
                        v -> VisualModules.HAND_AURA.set("reach", v), v -> Math.round(v) + ""));
                r.add(new Rows.Slider("Outline", "Edge sharpness", 0, 100, 5, () -> VisualModules.HAND_AURA.opt("rim", 60),
                        v -> VisualModules.HAND_AURA.set("rim", v), v -> Math.round(v) + ""));
                r.add(new Rows.Toggle("Breathing", "The glow pulses gently", () -> VisualModules.HAND_AURA.flag("breathe", true),
                        v -> VisualModules.HAND_AURA.set("breathe", v)));
                r.add(new Rows.Toggle("Empty hand too", null, () -> VisualModules.HAND_AURA.flag("arm", true),
                        v -> VisualModules.HAND_AURA.set("arm", v)));
                return r;
            });

    // ===================================================================== Jump Circles / Target ESP

    public static final Module JUMP_CIRCLES = Modules.register(new Module("jump_circles", "Jump Circles", Category.VISUALS, Icons.SPARKLES, false))
            .withSettings(() -> {
                List<Rows.Row> r = new ArrayList<>(colorRows(VisualModules.JUMP_CIRCLES, 0x9D7CFF));
                r.add(new Rows.Slider("Size", null, 0.4, 2.5, 0.1, () -> VisualModules.JUMP_CIRCLES.opt("size", 1.2),
                        v -> VisualModules.JUMP_CIRCLES.set("size", v), v -> String.format(Locale.ROOT, "%.1f", v)));
                r.add(new Rows.Slider("Duration", null, 0.4, 3, 0.1, () -> VisualModules.JUMP_CIRCLES.opt("duration", 1),
                        v -> VisualModules.JUMP_CIRCLES.set("duration", v), v -> String.format(Locale.ROOT, "%.1f s", v)));
                r.add(new Rows.Toggle("Double ring", null, () -> VisualModules.JUMP_CIRCLES.flag("double", true),
                        v -> VisualModules.JUMP_CIRCLES.set("double", v)));
                r.add(new Rows.Toggle("Other players", null, () -> VisualModules.JUMP_CIRCLES.flag("others", false),
                        v -> VisualModules.JUMP_CIRCLES.set("others", v)));
                return r;
            });

    public static final Module TARGET_ESP = Modules.register(new Module("target_esp", "Target ESP", Category.VISUALS, Icons.TARGET, false))
            .withSettings(() -> {
                List<Rows.Row> r = new ArrayList<>(colorRows(VisualModules.TARGET_ESP, 0x9D7CFF));
                r.add(new Rows.Toggle("Red when hit", null, () -> VisualModules.TARGET_ESP.flag("hurt", true),
                        v -> VisualModules.TARGET_ESP.set("hurt", v)));
                r.add(new Rows.Slider("Speed", null, 0.2, 3, 0.1, () -> VisualModules.TARGET_ESP.opt("speed", 1),
                        v -> VisualModules.TARGET_ESP.set("speed", v), v -> String.format(Locale.ROOT, "%.1f×", v)));
                r.add(new Rows.Slider("Duration after a hit", null, 1, 10, 0.5, () -> VisualModules.TARGET_ESP.opt("keep", 3),
                        v -> VisualModules.TARGET_ESP.set("keep", v), v -> String.format(Locale.ROOT, "%.1f s", v)));
                return r;
            });

    private record Circle(double x, double y, double z, long start) {}

    private static final List<Circle> CIRCLES = new ArrayList<>();
    private static final Map<Integer, Boolean> GROUND = new HashMap<>();
    private static int targetId = -1;
    private static long targetUntil;

    public static void onAttack(Entity e) {
        if (e instanceof LivingEntity) {
            targetId = e.getId();
            targetUntil = System.currentTimeMillis() + Math.round(TARGET_ESP.opt("keep", 3) * 1000);
        }
    }

    public static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            CIRCLES.clear();
            GROUND.clear();
            return;
        }
        if (JUMP_CIRCLES.enabled) {
            boolean others = JUMP_CIRCLES.flag("others", false);
            for (Player p : mc.level.players()) {
                if (p != mc.player && (!others || p.distanceToSqr(mc.player) > 48 * 48)) continue;
                boolean ground = p.onGround();
                Boolean was = GROUND.put(p.getId(), ground);
                if (was != null && was && !ground && p.getDeltaMovement().y > 0.2) {
                    CIRCLES.add(new Circle(p.getX(), p.getY() + 0.02, p.getZ(), System.currentTimeMillis()));
                }
            }
            if (CIRCLES.size() > 40) CIRCLES.remove(0);
        }
        applyGlint(mc);
    }

    public static void renderWorld(PoseStack pose, MultiBufferSource consumers, Vec3 cam, float pt) {
        if (consumers == null || (!JUMP_CIRCLES.enabled && !TARGET_ESP.enabled)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var type = FxRender.additive(WHITE);
        VertexConsumer vc = consumers.getBuffer(type);
        Matrix4f m = pose.last().pose();
        long now = System.currentTimeMillis();
        boolean drew = false;
        if (JUMP_CIRCLES.enabled && !CIRCLES.isEmpty()) {
            int col = color(JUMP_CIRCLES, 0x9D7CFF);
            float size = (float) JUMP_CIRCLES.opt("size", 1.2), dur = (float) JUMP_CIRCLES.opt("duration", 1) * 1000;
            boolean dbl = JUMP_CIRCLES.flag("double", true);
            CIRCLES.removeIf(c -> now - c.start() > dur * 1.3f);
            for (Circle c : CIRCLES) {
                for (int k = 0; k < (dbl ? 2 : 1); k++) {
                    float t = (now - c.start() - k * dur * 0.22f) / dur;
                    if (t < 0 || t > 1) continue;
                    float e = 1 - (1 - t) * (1 - t) * (1 - t);
                    float r = size * (0.15f + 0.85f * e) * (k == 0 ? 1 : 0.7f);
                    float a = (1 - t) * (k == 0 ? 0.9f : 0.6f);
                    ring(vc, m, (float) (c.x() - cam.x), (float) (c.y() - cam.y), (float) (c.z() - cam.z), r, 0.09f + 0.05f * (1 - t), 0,
                            col, a, 48);
                    // halo doux au sol, plus large et plus faible
                    ring(vc, m, (float) (c.x() - cam.x), (float) (c.y() - cam.y), (float) (c.z() - cam.z), r * 0.92f, r * 0.5f, 0,
                            col, a * 0.18f, 48);
                    drew = true;
                }
            }
        }
        if (TARGET_ESP.enabled && targetId >= 0 && now < targetUntil) {
            Entity e = mc.level.getEntity(targetId);
            if (e instanceof LivingEntity le && le.isAlive() && !le.isInvisible()) {
                int col = color(TARGET_ESP, 0x9D7CFF);
                if (TARGET_ESP.flag("hurt", true) && le.hurtTime > 0) col = Draw.lerpColor(col, 0xFF3B3B, le.hurtTime / 10f);
                double x = net.minecraft.util.Mth.lerp(pt, e.xo, e.getX()) - cam.x, y = net.minecraft.util.Mth.lerp(pt, e.yo, e.getY()) - cam.y,
                        z = net.minecraft.util.Mth.lerp(pt, e.zo, e.getZ()) - cam.z;
                float h = e.getBbHeight(), w = e.getBbWidth() * 0.85f + 0.12f;
                float time = (mc.level.getGameTime() + pt) * 0.06f * (float) TARGET_ESP.opt("speed", 1);
                float fade = Math.min(1f, (targetUntil - now) / 400f);
                float s = 0.5f + 0.5f * (float) Math.sin(time);
                float ly = (float) y + 0.05f + s * (h - 0.1f);
                // anneau + rideau dégradé qui traîne derrière le mouvement
                float dir = (float) Math.cos(time) >= 0 ? -1 : 1;
                ring(vc, m, (float) x, ly, (float) z, w, 0.035f, 0, col, 0.95f * fade, 40);
                curtain(vc, m, (float) x, ly, (float) z, w, 0.35f * dir, col, 0.45f * fade, 40);
                ring(vc, m, (float) x, ly, (float) z, w, 0.18f, 0, col, 0.12f * fade, 40);
                drew = true;
            }
        }
        if (drew && consumers instanceof MultiBufferSource.BufferSource bs) bs.endBatch(type);
    }

    private static final Identifier WHITE = Identifier.fromNamespaceAndPath("aurora", "textures/cosmetic/white.png");

    private static void vtx(VertexConsumer vc, Matrix4f m, float x, float y, float z, int rgb, float a) {
        vc.addVertex(m, x, y, z).setColor((Math.round(Math.max(0, Math.min(1, a)) * 255) << 24) | (rgb & 0xFFFFFF)).setUv(0.5f, 0.5f)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(0, 1, 0);
    }

    /** Anneau horizontal lumineux : bord net au rayon r, s'estompant sur la largeur w de chaque côté. */
    private static void ring(VertexConsumer vc, Matrix4f m, float x, float y, float z, float r, float w, float dy, int rgb, float a, int seg) {
        for (int i = 0; i < seg; i++) {
            double a0 = Math.PI * 2 * i / seg, a1 = Math.PI * 2 * (i + 1) / seg;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            float ri = Math.max(0, r - w), ro = r + w;
            vtx(vc, m, x + c0 * ri, y, z + s0 * ri, rgb, 0);
            vtx(vc, m, x + c1 * ri, y, z + s1 * ri, rgb, 0);
            vtx(vc, m, x + c1 * r, y + dy, z + s1 * r, rgb, a);
            vtx(vc, m, x + c0 * r, y + dy, z + s0 * r, rgb, a);
            vtx(vc, m, x + c0 * r, y + dy, z + s0 * r, rgb, a);
            vtx(vc, m, x + c1 * r, y + dy, z + s1 * r, rgb, a);
            vtx(vc, m, x + c1 * ro, y, z + s1 * ro, rgb, 0);
            vtx(vc, m, x + c0 * ro, y, z + s0 * ro, rgb, 0);
        }
    }

    /** Rideau vertical dégradé sous / sur un anneau (traînée). */
    private static void curtain(VertexConsumer vc, Matrix4f m, float x, float y, float z, float r, float h, int rgb, float a, int seg) {
        for (int i = 0; i < seg; i++) {
            double a0 = Math.PI * 2 * i / seg, a1 = Math.PI * 2 * (i + 1) / seg;
            float c0 = (float) Math.cos(a0) * r, s0 = (float) Math.sin(a0) * r, c1 = (float) Math.cos(a1) * r, s1 = (float) Math.sin(a1) * r;
            vtx(vc, m, x + c0, y, z + s0, rgb, a);
            vtx(vc, m, x + c1, y, z + s1, rgb, a);
            vtx(vc, m, x + c1, y + h, z + s1, rgb, 0);
            vtx(vc, m, x + c0, y + h, z + s0, rgb, 0);
        }
    }

    // ===================================================================== Custom Glint

    public static final Module CUSTOM_GLINT = Modules.register(new Module("custom_glint", "Custom Glint", Category.VISUALS, Icons.GEM, false))
            .withSettings(() -> {
                List<Rows.Row> r = new ArrayList<>(colorRows(VisualModules.CUSTOM_GLINT, 0x6FA8FF));
                r.add(new Rows.Slider("Intensity", null, 0.1, 1.5, 0.05, () -> VisualModules.CUSTOM_GLINT.opt("intensity", 1),
                        v -> VisualModules.CUSTOM_GLINT.set("intensity", v), VisualModules::pct));
                r.add(new Rows.Slider("Speed", null, 0.1, 4, 0.1, () -> VisualModules.CUSTOM_GLINT.opt("speed", 1),
                        v -> VisualModules.CUSTOM_GLINT.set("speed", v), v -> String.format(Locale.ROOT, "%.1f×", v)));
                r.add(new Rows.Slider("Pattern size", null, 0.25, 3, 0.05, () -> VisualModules.CUSTOM_GLINT.opt("pattern", 1),
                        v -> VisualModules.CUSTOM_GLINT.set("pattern", v), v -> String.format(Locale.ROOT, "%.2f", v)));
                return r;
            });

    private static final Identifier[] GLINTS = {
            Identifier.withDefaultNamespace("textures/misc/enchanted_glint_item.png"),
            Identifier.withDefaultNamespace("textures/misc/enchanted_glint_armor.png")};
    private static long glintKey = 0;

    public static double glintSpeed() {
        return CUSTOM_GLINT.enabled ? CUSTOM_GLINT.opt("speed", 1) : 1;
    }

    public static float glintPattern() {
        return CUSTOM_GLINT.enabled ? (float) CUSTOM_GLINT.opt("pattern", 1) : 1;
    }

    /** Recolore les textures de reflet d'enchantement quand la couleur / l'intensité changent. */
    private static void applyGlint(Minecraft mc) {
        long key = CUSTOM_GLINT.enabled ? (((long) color(CUSTOM_GLINT, 0x6FA8FF)) << 16) ^ Math.round(CUSTOM_GLINT.opt("intensity", 1) * 1000) ^ 1L << 40 : 0;
        if (key == glintKey) return;
        glintKey = key;
        var tm = mc.getTextureManager();
        for (Identifier id : GLINTS) {
            if (key == 0) {
                tm.release(id);
                continue;
            }
            try (InputStream in = mc.getResourceManager().open(id)) {
                NativeImage img = NativeImage.read(in);
                int col = color(CUSTOM_GLINT, 0x6FA8FF);
                float k = (float) CUSTOM_GLINT.opt("intensity", 1);
                float cr = ((col >> 16) & 255) / 255f, cg = ((col >> 8) & 255) / 255f, cb = (col & 255) / 255f;
                for (int y = 0; y < img.getHeight(); y++) {
                    for (int x = 0; x < img.getWidth(); x++) {
                        int p = img.getPixel(x, y);
                        float l = Math.max((p >> 16) & 255, Math.max((p >> 8) & 255, p & 255)) / 255f * k;
                        int r = Math.min(255, Math.round(cr * l * 255)), gg = Math.min(255, Math.round(cg * l * 255)),
                                b = Math.min(255, Math.round(cb * l * 255));
                        img.setPixel(x, y, (p & 0xFF000000) | (r << 16) | (gg << 8) | b);
                    }
                }
                tm.register(id, new DynamicTexture(() -> "aurora_glint", img));
            } catch (Exception e) {
                gg.aurora.AuroraClient.LOGGER.warn("Custom Glint : {}", e.toString());
            }
        }
    }

    // ===================================================================== Swing / FOV

    public static final Module SWING = Modules.register(new Module("swing_animation", "Swing Animation", Category.VISUALS, Icons.SWORD, false))
            .withSettings(() -> List.of(
                    new Rows.Slider("Hit duration", "Higher = slower animation", 0.4, 3, 0.1,
                            () -> VisualModules.SWING.opt("duration", 1.6), v -> VisualModules.SWING.set("duration", v),
                            v -> String.format(Locale.ROOT, "%.1f×", v))));

    public static int swingDuration(LivingEntity e, int vanilla) {
        if (!SWING.enabled || e != Minecraft.getInstance().player) return vanilla;
        return Math.max(1, Math.round(vanilla * (float) SWING.opt("duration", 1.6)));
    }

    public static final Module CUSTOM_FOV = Modules.register(new Module("custom_fov", "Custom FOV", Category.VISUALS, Icons.EYE, false))
            .withSettings(() -> List.of(
                    new Rows.Slider("Field of view", "Beyond the vanilla limit", 30, 150, 1, () -> VisualModules.CUSTOM_FOV.opt("fov", 100),
                            v -> VisualModules.CUSTOM_FOV.set("fov", v), v -> Math.round(v) + "°"),
                    new Rows.Toggle("Fixed FOV", "Ignores sprint and effects", () -> VisualModules.CUSTOM_FOV.flag("static", true),
                            v -> VisualModules.CUSTOM_FOV.set("static", v))));

    public static float fov(float vanilla) {
        if (!CUSTOM_FOV.enabled) return vanilla;
        float want = (float) CUSTOM_FOV.opt("fov", 100);
        if (CUSTOM_FOV.flag("static", true)) return want;
        return vanilla * want / Math.max(1f, Minecraft.getInstance().options.fov().get());
    }

    // ===================================================================== Sol miroir

    public static final Module MIRROR = Modules.register(new Module("ground_mirror", "Mirror floor", Category.VISUALS, Icons.LAYERS, false))
            .withSettings(() -> List.of(
                    new Rows.Info("Effect", "Reflections on the floor, like wet tiles"),
                    new Rows.Toggle("Floor reflections (shader)", "Sky, blocks and players reflect on the floor", () -> VisualModules.MIRROR.flag("ssr", true),
                            v -> VisualModules.MIRROR.set("ssr", v)),
                    new Rows.Slider("Intensity", null, 0.1, 1, 0.05, () -> VisualModules.MIRROR.opt("ssrStrength", 0.55),
                            v -> VisualModules.MIRROR.set("ssrStrength", v), VisualModules::pct),
                    new Rows.Slider("Roughness", "0 = perfect mirror, higher = wet floor", 0, 1, 0.05, () -> VisualModules.MIRROR.opt("ssrRough", 0.3),
                            v -> VisualModules.MIRROR.set("ssrRough", v), VisualModules::pct),
                    new Rows.Slider("Reflection distance", null, 8, 96, 4, () -> VisualModules.MIRROR.opt("ssrDist", 48),
                            v -> VisualModules.MIRROR.set("ssrDist", v), v -> Math.round(v) + " blocs"),
                    new Rows.Toggle("Only in the rain", "The floor shines when it rains", () -> VisualModules.MIRROR.flag("rainOnly", false),
                            v -> VisualModules.MIRROR.set("rainOnly", v)),
                    new Rows.Toggle("Rain: puddles", "Puddles reflecting the sky when it rains", () -> VisualModules.MIRROR.flag("puddles", true),
                            v -> VisualModules.MIRROR.set("puddles", v)),
                    new Rows.Slider("Puddle amount", null, 0, 1, 0.05, () -> VisualModules.MIRROR.opt("puddleAmount", 0.5),
                            v -> VisualModules.MIRROR.set("puddleAmount", v), VisualModules::pct),
                    new Rows.Toggle("Raindrop ripples", "Small rings in the puddles", () -> VisualModules.MIRROR.flag("ripples", true),
                            v -> VisualModules.MIRROR.set("ripples", v)),
                    new Rows.Toggle("Styled rain", "Thin clear drops, no vanilla splashes",
                            () -> VisualModules.MIRROR.flag("rainStyle", true), v -> VisualModules.MIRROR.set("rainStyle", v)),
                    new Rows.Toggle("Puddles without rain", "Floor always wet", () -> VisualModules.MIRROR.flag("puddlesDry", false),
                            v -> VisualModules.MIRROR.set("puddlesDry", v)),
                    new Rows.Toggle("Sharp reflection of players", "Mirrored copy of players under their feet", () -> VisualModules.MIRROR.flag("players", false),
                            v -> VisualModules.MIRROR.set("players", v)),
                    new Rows.Slider("Reflection opacity", null, 0.1, 0.8, 0.05, () -> VisualModules.MIRROR.opt("alpha", 0.38),
                            v -> VisualModules.MIRROR.set("alpha", v), VisualModules::pct),
                    new Rows.ColorRow("Glass tint", () -> (int) VisualModules.MIRROR.opt("tint", 0xC8D4FF),
                            v -> VisualModules.MIRROR.set("tint", v & 0xFFFFFF)),
                    new Rows.Toggle("Other players", null, () -> VisualModules.MIRROR.flag("others", true), v -> VisualModules.MIRROR.set("others", v)),
                    new Rows.Slider("Distance", null, 8, 64, 4, () -> VisualModules.MIRROR.opt("dist", 32), v -> VisualModules.MIRROR.set("dist", v),
                            v -> Math.round(v) + " blocs")));

    public static final Module MOTION_BLUR = Modules.register(new Module("motion_blur", "Motion Blur", Category.VISUALS, Icons.CHEVRONS, false))
            .withSettings(() -> List.of(
                    new Rows.Slider("Force", "Blur length", 0.1, 1, 0.05, () -> VisualModules.MOTION_BLUR.opt("strength", 0.5),
                            v -> VisualModules.MOTION_BLUR.set("strength", v), VisualModules::pct),
                    new Rows.Slider("Quality", "Blur samples", 6, 16, 1, () -> VisualModules.MOTION_BLUR.opt("samples", 12),
                            v -> VisualModules.MOTION_BLUR.set("samples", v), v -> String.valueOf(Math.round(v))),
                    new Rows.Info("Camera blur", "Follows real movement, the hand stays sharp, same result at 60 or 300 FPS")));

    /**
     * Hauteur du sol sous le joueur (dessus du premier bloc solide dans les 4 blocs en dessous), ou NaN
     * s'il n'y a rien : le reflet suit le vrai sol pendant les sauts.
     */
    public static double groundBelow(Player p) {
        var level = p.level();
        var pos = net.minecraft.core.BlockPos.containing(p.getX(), p.getY() + 0.01, p.getZ()).mutable();
        for (int i = 0; i < 5; i++) {
            var state = level.getBlockState(pos);
            var shape = state.getCollisionShape(level, pos);
            if (!shape.isEmpty()) {
                double top = pos.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
                if (top <= p.getY() + 0.01) return top;
            }
            pos.move(net.minecraft.core.Direction.DOWN);
        }
        return Double.NaN;
    }

    /** Force le chargement (enregistrement des modules avant la lecture de la config). */
    public static void init() {}
}
