package gg.aurora.waypoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import gg.aurora.AuroraClient;
import gg.aurora.Settings;
import gg.aurora.ui.Draw;
import gg.aurora.ui.Toasts;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Système de waypoints : un fichier par monde / serveur (config/aurora/waypoints), dossiers,
 * étiquettes projetées à l'écran (taille constante, toujours lisibles), surbrillance du bloc
 * qui épouse sa forme réelle (dalles, portes, échelles…), waypoint de mort automatique.
 */
public final class Waypoints {
    private Waypoints() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("aurora").resolve("waypoints");
    private static final int FULL_BRIGHT = 0xF000F0;

    public static final List<Waypoint> LIST = new ArrayList<>();
    public static final List<Waypoint.Folder> FOLDERS = new ArrayList<>();
    private static String key;
    private static boolean wasDead;

    public static KeyMapping openKey, addKey, toggleKey, zoneKey;
    /** Sélection de zone en cours : premier coin choisi (null = pas de sélection). */
    public static BlockPos zoneStart;
    private static boolean zonePicking;
    /** Masquage global rapide (touche). */
    public static boolean hidden;

    private static class Data {
        List<Waypoint.Folder> folders = new ArrayList<>();
        List<Waypoint> waypoints = new ArrayList<>();
    }

    public static void init(KeyMapping.Category category) {
        openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.aurora.waypoints", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, category));
        addKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.aurora.waypoint_add", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, category));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.aurora.waypoint_toggle", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), category));
        zoneKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.aurora.waypoint_zone", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U, category));
        Pings.init(category);
    }

    /** Bloc visé par la caméra (jusqu'à 160 blocs), ou la position du joueur. */
    public static BlockPos target(Minecraft mc) {
        HitResult hit = mc.player.pick(160, 0, false);
        if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) return bh.getBlockPos();
        return mc.player.blockPosition().below();
    }

    /** Démarre la sélection d'une zone à la caméra (depuis le menu ou la touche). */
    public static void startZone() {
        zonePicking = true;
        zoneStart = null;
        Toasts.push(gg.aurora.ui.Icons.PIN, "Zone", "Aim at the 1st corner and press " + zoneKey.getTranslatedKeyMessage().getString(), false);
    }

    public static void cancelZone() {
        zonePicking = false;
        zoneStart = null;
    }

    public static boolean picking() {
        return zonePicking;
    }

    private static void zoneKeyPressed(Minecraft mc) {
        if (!zonePicking) {
            startZone();
            zoneStart = target(mc);
            Toasts.push(gg.aurora.ui.Icons.PIN, "Zone", "1st corner set · aim at the 2nd corner then " + zoneKey.getTranslatedKeyMessage().getString(), false);
            return;
        }
        if (zoneStart == null) {
            zoneStart = target(mc);
            return;
        }
        BlockPos b = target(mc);
        Waypoint w = create(mc);
        w.area = true;
        w.x = zoneStart.getX();
        w.y = zoneStart.getY();
        w.z = zoneStart.getZ();
        w.x2 = b.getX();
        w.y2 = b.getY();
        w.z2 = b.getZ();
        w.name = "Zone " + (LIST.size() + 1);
        w.fill = Math.min(w.fill, 0.12f);
        zonePicking = false;
        zoneStart = null;
        mc.setScreen(new WaypointsScreen(null, w));
    }

    // ===================================================================== stockage

    /** Identifiant du monde courant : adresse du serveur ou nom de la sauvegarde solo. */
    private static Object keyLevel;
    private static String keyCached;

    /** Clé du monde/serveur : recalculée seulement au changement de monde (plus de regex à chaque tick). */
    private static String currentKey(Minecraft mc) {
        if (mc.level == null) {
            keyLevel = null;
            return null;
        }
        if (mc.level == keyLevel) return keyCached;
        keyLevel = mc.level;
        return keyCached = computeKey(mc);
    }

    private static String computeKey(Minecraft mc) {
        String k;
        if (mc.getSingleplayerServer() != null) k = "solo_" + mc.getSingleplayerServer().getWorldData().getLevelName();
        else if (mc.getCurrentServer() != null) k = "srv_" + mc.getCurrentServer().ip;
        else k = "autre";
        return k.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void load(String k) {
        LIST.clear();
        FOLDERS.clear();
        Path f = DIR.resolve(k + ".json");
        if (!Files.exists(f)) return;
        try (Reader r = Files.newBufferedReader(f)) {
            Data d = GSON.fromJson(r, Data.class);
            if (d != null) {
                if (d.folders != null) FOLDERS.addAll(d.folders);
                if (d.waypoints != null) LIST.addAll(d.waypoints);
            }
        } catch (Exception e) {
            AuroraClient.LOGGER.warn("Waypoints illisibles ({}): {}", f, e.toString());
        }
    }

    public static void save() {
        if (key == null) return;
        try {
            Files.createDirectories(DIR);
            Data d = new Data();
            d.folders = FOLDERS;
            for (Waypoint w : LIST) if (!w.temp || Settings.wpDeath) d.waypoints.add(w);
            try (Writer w = Files.newBufferedWriter(DIR.resolve(key + ".json"))) {
                GSON.toJson(d, w);
            }
        } catch (Exception e) {
            AuroraClient.LOGGER.warn("Sauvegarde des waypoints impossible: {}", e.toString());
        }
    }

    public static Waypoint.Folder folder(String id) {
        if (id == null || id.isEmpty()) return null;
        for (Waypoint.Folder f : FOLDERS) if (f.id.equals(id)) return f;
        return null;
    }

    public static boolean shown(Waypoint w) {
        if (!w.visible) return false;
        Waypoint.Folder f = folder(w.folder);
        return f == null || f.visible;
    }

    // ===================================================================== création

    private static final int[] PALETTE = {0xFFFFFF, 0xE8473E, 0xFF9A2E, 0xFFD43A, 0x34C759, 0x3AB7F0, 0x6B5CFF, 0xFF5FB0};

    /** Nouveau waypoint pré-rempli : bloc visé (ou position du joueur), réglages par défaut. */
    public static Waypoint create(Minecraft mc) {
        Waypoint w = new Waypoint();
        BlockPos pos = mc.player.blockPosition().below();
        HitResult hit = mc.player.pick(64, 0, false);
        if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) pos = bh.getBlockPos();
        w.x = pos.getX();
        w.y = pos.getY();
        w.z = pos.getZ();
        w.dim = mc.level.dimension().identifier().toString();
        w.preset = Settings.wpDefaultPreset;
        w.fill = Settings.wpFill;
        w.outline = Settings.wpOutline;
        w.walls = Settings.wpThroughWalls;
        w.color = PALETTE[(int) (Math.random() * PALETTE.length)];
        Item item = mc.level.getBlockState(pos).getBlock().asItem();
        w.icon = item == Items.AIR ? "minecraft:grass_block" : BuiltInRegistries.ITEM.getKey(item).toString();
        w.name = "Waypoint " + (LIST.size() + 1);
        return w;
    }

    public static ItemStack iconStack(Waypoint w) {
        try {
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(w.icon));
            return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    // ===================================================================== tick

    public static void tick(Minecraft mc) {
        Pings.tick(mc);
        String k = currentKey(mc);
        if (k == null) {
            if (key != null) save();
            key = null;
            return;
        }
        if (!k.equals(key)) {
            if (key != null) save();
            key = k;
            load(k);
        }
        while (openKey.consumeClick()) if (mc.screen == null) mc.setScreen(new WaypointsScreen(null, null));
        while (addKey.consumeClick()) if (mc.screen == null && mc.player != null) mc.setScreen(new WaypointsScreen(null, create(mc)));
        while (zoneKey.consumeClick()) if (mc.screen == null && mc.player != null) zoneKeyPressed(mc);
        if (zonePicking && mc.player != null && mc.player.isShiftKeyDown() && mc.options.keyAttack.isDown()) {
            zonePicking = false;
            zoneStart = null;
        }
        while (toggleKey.consumeClick()) {
            hidden = !hidden;
            Toasts.push(gg.aurora.ui.Icons.PIN, "Waypoints", hidden ? "Hidden" : "Shown", false);
        }
        if (mc.player == null) return;
        boolean dead = mc.player.isDeadOrDying();
        if (dead && !wasDead && Settings.wpEnabled && Settings.wpDeath) {
            LIST.removeIf(w -> w.temp);
            Waypoint w = new Waypoint();
            BlockPos p = mc.player.blockPosition();
            w.x = p.getX();
            w.y = p.getY();
            w.z = p.getZ();
            w.dim = mc.level.dimension().identifier().toString();
            w.name = "Death";
            w.color = 0xE8473E;
            w.icon = "minecraft:skeleton_skull";
            w.preset = 1;
            w.block = false;
            w.temp = true;
            LIST.add(w);
            save();
        }
        wasDead = dead;
        // waypoint de mort atteint : retiré
        if (!dead && mc.level.getGameTime() % 10 == 0) {
            String dim = mc.level.dimension().identifier().toString();
            boolean removed = LIST.removeIf(w -> w.temp && w.dim.equals(dim)
                    && mc.player.distanceToSqr(w.x + 0.5, w.y + 0.5, w.z + 0.5) < 9);
            if (removed) save();
        }
    }

    // ===================================================================== bloc en surbrillance (monde)

    private static RenderType fillType, wallType;

    public static void renderWorld(PoseStack pose, MultiBufferSource consumers, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        if (!Settings.wpEnabled || hidden || consumers == null || mc.level == null || (LIST.isEmpty() && !zonePicking)) return;
        if (fillType == null) {
            fillType = RenderTypes.debugQuads();
            wallType = RenderTypes.textBackgroundSeeThrough();
        }
        String dim = mc.level.dimension().identifier().toString();
        double max = Settings.wpMaxDistance > 0 ? Settings.wpMaxDistance : 512;
        Matrix4f mat = pose.last().pose();
        if (zonePicking && mc.player != null) {
            BlockPos a = zoneStart != null ? zoneStart : target(mc), b = target(mc);
            float pulse = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 180.0);
            VertexConsumer pv = consumers.getBuffer(wallType);
            box(pv, mat, (float) (Math.min(a.getX(), b.getX()) - cam.x) - 0.01f, (float) (Math.min(a.getY(), b.getY()) - cam.y) - 0.01f,
                    (float) (Math.min(a.getZ(), b.getZ()) - cam.z) - 0.01f, (float) (Math.max(a.getX(), b.getX()) + 1 - cam.x) + 0.01f,
                    (float) (Math.max(a.getY(), b.getY()) + 1 - cam.y) + 0.01f, (float) (Math.max(a.getZ(), b.getZ()) + 1 - cam.z) + 0.01f,
                    argbA(0x4DA3FF, 0.08f + 0.06f * pulse), argbA(0xFFFFFF, 0.9f));
        }
        for (int pass = 0; pass < 2; pass++) {
            VertexConsumer vc = null;
            for (Waypoint w : LIST) {
                if (!w.block || !shown(w) || !w.dim.equals(dim) || w.walls != (pass == 1)) continue;
                double dx = w.x + 0.5 - cam.x, dy = w.y + 0.5 - cam.y, dz = w.z + 0.5 - cam.z;
                if (!w.area && dx * dx + dy * dy + dz * dz > max * max) continue;
                if (vc == null) vc = consumers.getBuffer(pass == 1 ? wallType : fillType);
                if (w.area) {
                    box(vc, mat, (float) (w.minX() - cam.x) - 0.004f, (float) (w.minY() - cam.y) - 0.004f, (float) (w.minZ() - cam.z) - 0.004f,
                            (float) (w.maxX() + 1 - cam.x) + 0.004f, (float) (w.maxY() + 1 - cam.y) + 0.004f, (float) (w.maxZ() + 1 - cam.z) + 0.004f,
                            argbA(w.color, w.fill), argbA(w.color, w.outline));
                    continue;
                }
                BlockPos bp = new BlockPos(w.x, w.y, w.z);
                BlockState st = mc.level.getBlockState(bp);
                VoxelShape shape = st.getShape(mc.level, bp);
                List<AABB> boxes = shape.isEmpty() ? List.of(new AABB(0, 0, 0, 1, 1, 1)) : shape.toAabbs();
                for (AABB b : boxes) {
                    float x0 = (float) (w.x + b.minX - cam.x) - 0.002f, y0 = (float) (w.y + b.minY - cam.y) - 0.002f, z0 = (float) (w.z + b.minZ - cam.z) - 0.002f;
                    float x1 = (float) (w.x + b.maxX - cam.x) + 0.002f, y1 = (float) (w.y + b.maxY - cam.y) + 0.002f, z1 = (float) (w.z + b.maxZ - cam.z) + 0.002f;
                    box(vc, mat, x0, y0, z0, x1, y1, z1, argbA(w.color, w.fill), argbA(w.color, w.outline));
                }
            }
        }
        if (consumers instanceof MultiBufferSource.BufferSource bs) bs.endBatch();
    }

    private static int argbA(int rgb, float a) {
        return (Math.round(Math.max(0, Math.min(1, a)) * 255) << 24) | (rgb & 0xFFFFFF);
    }

    private static final Vector3f TA = new Vector3f(), TB = new Vector3f(), TC = new Vector3f(), TD = new Vector3f();

    /** Boîte : 6 faces orientées vers la caméra (aucune en double), arêtes en bandes face caméra. */
    private static final float[][] CORNERS = new float[8][3];
    private static final int[][] EDGES = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};

    public static void box(VertexConsumer vc, Matrix4f m, float x0, float y0, float z0, float x1, float y1, float z1, int fill, int line) {
        if ((fill >>> 24) > 0) {
            face(vc, m, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, fill);
            face(vc, m, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, fill);
            face(vc, m, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, fill);
            face(vc, m, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, fill);
            face(vc, m, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, fill);
            face(vc, m, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, fill);
        }
        if ((line >>> 24) > 0) {
            float[][] c = CORNERS; // render thread only
            for (int i = 0; i < 8; i++) {
                c[i][0] = i == 1 || i == 2 || i == 5 || i == 6 ? x1 : x0;
                c[i][1] = i < 4 ? y0 : y1;
                c[i][2] = i == 2 || i == 3 || i == 6 || i == 7 ? z1 : z0;
            }
            for (int[] k : EDGES) edge(vc, m, c[k[0]], c[k[1]], line);
        }
    }

    private static void face(VertexConsumer vc, Matrix4f m, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz, int col) {
        // normale · direction vers la caméra (à l'origine) : on retourne la face si elle s'éloigne
        TA.set(bx - ax, by - ay, bz - az);
        TB.set(dx - ax, dy - ay, dz - az);
        TA.cross(TB);
        boolean front = TA.dot(-ax, -ay, -az) > 0;
        if (front) quad(vc, m, ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, col);
        else quad(vc, m, dx, dy, dz, cx, cy, cz, bx, by, bz, ax, ay, az, col);
    }

    private static void edge(VertexConsumer vc, Matrix4f m, float[] a, float[] b, int col) {
        TA.set(b[0] - a[0], b[1] - a[1], b[2] - a[2]);
        TB.set(a[0] + b[0], a[1] + b[1], a[2] + b[2]).mul(0.5f);
        float dist = Math.max(0.5f, TB.length());
        TC.set(TA).cross(TB);
        if (TC.lengthSquared() < 1e-8f) return;
        // constant on screen up close, then fixed in the world: it thins out with distance, never thickens
        TC.normalize(Math.min(0.0012f * dist, 0.0045f));
        face(vc, m, a[0] - TC.x, a[1] - TC.y, a[2] - TC.z, a[0] + TC.x, a[1] + TC.y, a[2] + TC.z,
                b[0] + TC.x, b[1] + TC.y, b[2] + TC.z, b[0] - TC.x, b[1] - TC.y, b[2] - TC.z, col);
    }

    private static void quad(VertexConsumer vc, Matrix4f m, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz, int col) {
        vc.addVertex(m, ax, ay, az).setColor(col).setLight(FULL_BRIGHT);
        vc.addVertex(m, bx, by, bz).setColor(col).setLight(FULL_BRIGHT);
        vc.addVertex(m, cx, cy, cz).setColor(col).setLight(FULL_BRIGHT);
        vc.addVertex(m, dx, dy, dz).setColor(col).setLight(FULL_BRIGHT);
    }

    // ===================================================================== étiquettes (HUD)

    private record Label(Waypoint w, float sx, float sy, double dist, boolean edge, float angle) {}

    private static final List<Label> LABELS = new ArrayList<>();
    private static final Map<String, ItemStack> ICONS = new HashMap<>();
    private static final java.util.Set<String> AIMED = new java.util.HashSet<>();

    public static void renderHud(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!Settings.wpEnabled || hidden || mc.level == null || mc.player == null || (LIST.isEmpty() && !zonePicking) || mc.options.hideGui) return;
        int gw = g.guiWidth(), gh = g.guiHeight();
        if (!Projector.begin(delta, gw, gh)) return;
        String dim = mc.level.dimension().identifier().toString();
        double max = Settings.wpMaxDistance > 0 ? Settings.wpMaxDistance : Double.MAX_VALUE;
        LABELS.clear();
        float[] s = new float[3];
        for (Waypoint w : LIST) {
            if (!shown(w) || !w.dim.equals(dim)) continue;
            double x = (w.minX() + w.maxX() + 1) / 2.0, y = w.maxY() + 1.15, z = (w.minZ() + w.maxZ() + 1) / 2.0;
            double dx = x - Projector.camX(), dy = y - Projector.camY(), dz = z - Projector.camZ();
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > max) continue;
            if (Settings.wpHideNear && dist < 3) continue;
            boolean front = Projector.project(x, y, z, s);
            if (front && Projector.onScreen(s, 40)) {
                LABELS.add(new Label(w, s[0], s[1], dist, false, 0));
            } else if (Settings.wpEdge) {
                float ang = Projector.clampToEdge(s, front, 18);
                LABELS.add(new Label(w, s[0], s[1], dist, true, ang));
            }
        }
        // far first, the nearest on top; stable order for equal distances (no flicker)
        LABELS.sort((a, b) -> {
            int c = Double.compare(b.dist(), a.dist());
            return c != 0 ? c : a.w().id.compareTo(b.w().id);
        });
        float cxs = gw / 2f, cys = gh / 2f;
        for (Label l : LABELS) {
            if (l.edge()) {
                drawEdge(g, l.w(), l.sx(), l.sy(), l.angle(), l.dist());
                continue;
            }
            // aim with hysteresis: in under 14 px, out past 26 px
            double off = Math.hypot(l.sx() - cxs, l.sy() - cys);
            boolean was = AIMED.contains(l.w().id);
            boolean aimed = off < 14 || was && off < 26;
            if (aimed) AIMED.add(l.w().id);
            else AIMED.remove(l.w().id);
            // fades in when walking away from it instead of popping
            float alpha = Settings.wpHideNear ? Draw.clamp01((float) (l.dist() - 3) / 3f) : 1;
            drawAt(g, mc.font, l.w(), l.sx(), l.sy(), l.dist(), aimed, alpha);
        }
        if (zonePicking) {
            BlockPos a = zoneStart != null ? zoneStart : target(mc), b = target(mc);
            String size = (Math.abs(a.getX() - b.getX()) + 1) + " × " + (Math.abs(a.getY() - b.getY()) + 1) + " × " + (Math.abs(a.getZ() - b.getZ()) + 1);
            String key = zoneKey.getTranslatedKeyMessage().getString();
            String msg = zoneStart == null ? "Zone: aim at the 1st corner · " + key : "Zone " + size + " · aim at the 2nd corner · " + key;
            float tw = Draw.width(msg, 1f);
            float x = gw / 2f - tw / 2, y = gh / 2f + 16;
            Draw.rrect(g, x - 7, y - 4, tw + 14, 16, 8, 0xD0101014);
            Draw.text(g, msg, x, y, 1f, 0xFFFFFFFF);
            Draw.centered(g, "Sneak + left click: cancel", gw / 2f, y + 17, 0.8f, 0xFFA0A4AC);
        }
    }

    /** A label with the in-world rules (size by distance, dot when far, names). Also drives the settings preview. */
    public static void drawAt(GuiGraphics g, Font font, Waypoint w, float x, float y, double dist, boolean aimed, float alpha) {
        // smaller with distance (full size up to 16 blocks, down to 50 %), in steps so text never shimmers
        float k = 1;
        if (Settings.wpDistScale) {
            k = (float) (0.5 + 0.5 * Math.min(1, 16 / Math.max(16, dist)));
            k = Math.round(k * 20) / 20f;
        }
        boolean far = Settings.wpDotDistance > 0 && dist > Settings.wpDotDistance;
        boolean name = Settings.wpNames == 0 || Settings.wpNames == 1 && aimed;
        if (far && !aimed) drawDot(g, w, x, y, alpha);
        else drawLabel(g, font, w, x, y, dist, Settings.wpLabelScale * (aimed ? Math.max(k, 0.9f) : k), name, alpha);
    }

    /** Distant waypoint: a colored dot with a dark rim. */
    private static void drawDot(GuiGraphics g, Waypoint w, float cx, float cy, float a) {
        Draw.rrect(g, cx - 4, cy - 4, 8, 8, 4, Draw.withAlpha(0x000000, 0.7f * a));
        Draw.rrect(g, cx - 2.75f, cy - 2.75f, 5.5f, 5.5f, 2.75f, Draw.withAlpha(w.color, a));
    }

    /** Off-screen waypoint: dot pinned to the screen edge with an arrow toward it. */
    private static void drawEdge(GuiGraphics g, Waypoint w, float cx, float cy, float angle, double dist) {
        edgeArrow(g, cx, cy, angle, w.color, 0.9f);
        if (Settings.wpShowDistance) {
            String d = distText(dist);
            float tw = Draw.width(d, 0.7f);
            float tx = Math.max(4, Math.min(cx - tw / 2, Minecraft.getInstance().getWindow().getGuiScaledWidth() - tw - 4));
            float ty = cy + (Math.sin(angle) > 0.5 ? -17 : 10);
            shadowText(g, d, tx, ty, 0.7f, 0xE0FFFFFF);
        }
    }

    /** Small round marker with a chevron pointing at angle (radians, 0 = right). Shared with pings. */
    public static void edgeArrow(GuiGraphics g, float cx, float cy, float angle, int rgb, float a) {
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().rotate(angle - (float) (Math.PI / 2));
        Draw.icon(g, gg.aurora.ui.Icons.CARET_DOWN, 0.5f, 10.5f, 13, Draw.withAlpha(0x000000, 0.5f * a));
        Draw.icon(g, gg.aurora.ui.Icons.CARET_DOWN, 0, 10, 13, Draw.withAlpha(rgb, a));
        g.pose().popMatrix();
        Draw.rrect(g, cx - 6, cy - 6, 12, 12, 6, Draw.withAlpha(0x000000, 0.65f * a));
        Draw.rrect(g, cx - 4, cy - 4, 8, 8, 4, Draw.withAlpha(rgb, a));
    }

    public static String distText(double dist) {
        return dist < 1000 ? Math.round(dist) + "m" : String.format(java.util.Locale.ROOT, "%.1fkm", dist / 1000);
    }

    /** Waypoint label anchored at (cx, cy), content above the anchor. Also used by the previews in the menus. */
    public static void drawLabel(GuiGraphics g, Font font, Waypoint w, float cx, float cy, double dist, float scale) {
        drawLabel(g, font, w, cx, cy, dist, scale, true, 1);
    }

    public static void drawLabel(GuiGraphics g, Font font, Waypoint w, float cx, float cy, double dist, float scale, boolean name) {
        drawLabel(g, font, w, cx, cy, dist, scale, name, 1);
    }

    private static final int GLASS = 0x121419;

    /** name = false: no name (dot + distance), whatever the style. */
    public static void drawLabel(GuiGraphics g, Font font, Waypoint w, float cx, float cy, double dist, float scale, boolean name, float a) {
        if (a < 0.02f) return;
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().scale(scale, scale);
        String d = distText(dist);
        boolean showD = Settings.wpShowDistance;
        int preset = !name && (w.preset == 0 || w.preset == 3) ? 4 : w.preset;
        float ts = 0.9f, ds = 0.75f;
        switch (preset) {
            case 1, 2 -> { // icon in a rounded square, distance under it
                boolean filled = preset == 2;
                float s = 20, y0 = showD ? -31 : -22;
                Draw.rrect(g, -s / 2, y0, s, s, 5, Draw.withAlpha(filled ? w.color : GLASS, (filled ? 0.95f : 0.85f) * a));
                Draw.ring(g, -s / 2, y0, s, s, 5, Draw.withAlpha(filled ? 0xFFFFFF : w.color, (filled ? 0.25f : 0.9f) * a));
                drawIcon(g, font, w, 0, y0 + s / 2, filled ? contrast(w.color) : 0xFFFFFF, a);
                if (showD) shadowText(g, d, -Draw.width(d, ds) / 2, y0 + s + 3, ds, Draw.withAlpha(0xFFFFFF, a));
                anchor(g, w.color, a * 0.9f);
            }
            case 3 -> { // colored pill
                float tw = Draw.width(w.name, ts), dw = showD ? Draw.width(d, ds) + 6 : 0;
                float pw = tw + dw + 12, h = 13, y0 = -h - 5;
                int fg = contrast(w.color);
                Draw.rrect(g, -pw / 2, y0, pw, h, h / 2, Draw.withAlpha(w.color, 0.95f * a));
                Draw.text(g, w.name, -pw / 2 + 6, y0 + 3, ts, Draw.withAlpha(fg, a));
                if (showD) Draw.text(g, d, -pw / 2 + 6 + tw + 6, y0 + 3.8f, ds, Draw.withAlpha(fg, 0.7f * a));
                anchor(g, w.color, a);
            }
            case 4 -> { // simple: dot + distance
                Draw.rrect(g, -4, -8, 8, 8, 4, Draw.withAlpha(0x000000, 0.7f * a));
                Draw.rrect(g, -2.75f, -6.75f, 5.5f, 5.5f, 2.75f, Draw.withAlpha(w.color, a));
                if (showD) shadowText(g, d, -Draw.width(d, ds) / 2, 2, ds, Draw.withAlpha(0xFFFFFF, a));
            }
            default -> { // glass pill: [icon or dot] name · distance
                ItemStack icon = w.iconType == 1 && a > 0.6f ? icon(w) : ItemStack.EMPTY;
                float tw = Draw.width(w.name, ts), dw = showD ? Draw.width(d, ds) + 6 : 0;
                float lead = icon.isEmpty() ? 9 : 13;
                float pw = 6 + lead + tw + dw + 6, h = 14, y0 = -h - 5;
                float x0 = -pw / 2;
                Draw.rrect(g, x0, y0, pw, h, h / 2, Draw.withAlpha(GLASS, 0.82f * a));
                Draw.ring(g, x0, y0, pw, h, h / 2, Draw.withAlpha(0xFFFFFF, 0.08f * a));
                if (icon.isEmpty()) {
                    Draw.rrect(g, x0 + 6, y0 + h / 2 - 2.5f, 5, 5, 2.5f, Draw.withAlpha(w.color, a));
                } else {
                    g.pose().pushMatrix();
                    g.pose().translate(x0 + 5, y0 + 2);
                    g.pose().scale(0.625f, 0.625f);
                    g.renderItem(icon, 0, 0);
                    g.pose().popMatrix();
                }
                float tx = x0 + 6 + lead;
                Draw.text(g, w.name, tx, y0 + 3.5f, ts, Draw.withAlpha(0xFFFFFF, a));
                if (showD) Draw.text(g, d, tx + tw + 6, y0 + 4.3f, ds, Draw.withAlpha(0xFFFFFF, 0.6f * a));
                anchor(g, w.color, a);
            }
        }
        g.pose().popMatrix();
    }

    /** Little dot that marks the exact spot under a label. */
    private static void anchor(GuiGraphics g, int rgb, float a) {
        Draw.rrect(g, -2.5f, -2.5f, 5, 5, 2.5f, Draw.withAlpha(0x000000, 0.6f * a));
        Draw.rrect(g, -1.5f, -1.5f, 3, 3, 1.5f, Draw.withAlpha(rgb, a));
    }

    /** Black or white, whichever reads best on rgb. */
    public static int contrast(int rgb) {
        int r = rgb >> 16 & 255, gr = rgb >> 8 & 255, b = rgb & 255;
        return 0.299 * r + 0.587 * gr + 0.114 * b > 170 ? 0x15161A : 0xFFFFFF;
    }

    /** Text without background: soft shadow for legibility over the world. */
    public static void shadowText(GuiGraphics g, String s, float x, float y, float scale, int argb) {
        Draw.text(g, s, x + 0.6f, y + 0.6f, scale, Draw.withAlpha(0x000000, (argb >>> 24) / 255f * 0.75f));
        Draw.text(g, s, x, y, scale, argb);
    }

    private static void drawIcon(GuiGraphics g, Font font, Waypoint w, float cx, float cy, int fg, float a) {
        if (w.iconType == 1 && a > 0.6f) {
            ItemStack s = icon(w);
            if (!s.isEmpty()) {
                g.renderItem(s, Math.round(cx - 8), Math.round(cy - 8));
                return;
            }
        }
        if (w.iconType == 0) {
            Draw.rrect(g, cx - 3, cy - 3, 6, 6, 3, Draw.withAlpha(fg, a));
            return;
        }
        String letter = w.name.isEmpty() ? "?" : w.name.substring(0, 1).toUpperCase();
        Draw.centered(g, letter, cx, cy - 4, 1.05f, Draw.withAlpha(fg, a));
    }

    private static ItemStack icon(Waypoint w) {
        return ICONS.computeIfAbsent(w.icon, k -> iconStack(w));
    }
}
