package gg.aurora.waypoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import gg.aurora.AuroraClient;
import gg.aurora.Settings;
import gg.aurora.mixin.GameRendererAccessor;
import gg.aurora.ui.Toasts;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Camera;
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
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
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

    private record Label(Waypoint w, float sx, float sy, double dist) {}

    private static final List<Label> LABELS = new ArrayList<>();
    private static final Map<String, ItemStack> ICONS = new HashMap<>();

    public static void renderHud(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!Settings.wpEnabled || hidden || mc.level == null || mc.player == null || (LIST.isEmpty() && !zonePicking) || mc.options.hideGui) return;
        Camera cam = mc.gameRenderer.getMainCamera();
        if (!cam.isInitialized()) return;
        float pt = delta.getGameTimeDeltaPartialTick(true);
        float fov = ((GameRendererAccessor) mc.gameRenderer).aurora$getFov(cam, pt, true);
        Matrix4f proj = mc.gameRenderer.getProjectionMatrix(fov);
        Quaternionf view = new Quaternionf(cam.rotation()).conjugate();
        Vec3 c = cam.position();
        String dim = mc.level.dimension().identifier().toString();
        int gw = g.guiWidth(), gh = g.guiHeight();
        double max = Settings.wpMaxDistance > 0 ? Settings.wpMaxDistance : Double.MAX_VALUE;
        LABELS.clear();
        Vector3f p = new Vector3f();
        Vector4f clip = new Vector4f();
        for (Waypoint w : LIST) {
            if (!shown(w) || !w.dim.equals(dim)) continue;
            double dx = (w.minX() + w.maxX() + 1) / 2.0 - c.x, dy = w.maxY() + 1.15 - c.y, dz = (w.minZ() + w.maxZ() + 1) / 2.0 - c.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > max) continue;
            if (Settings.wpHideNear && dist < 3.5) continue;
            p.set((float) dx, (float) dy, (float) dz);
            view.transform(p);
            clip.set(p, 1);
            proj.transform(clip);
            if (clip.w <= 0.05f) continue;
            float nx = clip.x / clip.w, ny = clip.y / clip.w;
            if (nx < -1.2f || nx > 1.2f || ny < -1.2f || ny > 1.2f) continue;
            LABELS.add(new Label(w, (nx * 0.5f + 0.5f) * gw, (1 - (ny * 0.5f + 0.5f)) * gh, dist));
        }
        LABELS.sort((a, b) -> Double.compare(b.dist(), a.dist()));
        float cxs = gw / 2f, cys = gh / 2f;
        for (Label l : LABELS) {
            // plus loin = plus petit : pleine taille jusqu'à 16 blocs, puis jusqu'à 45 %
            float k = Settings.wpDistScale ? (float) (0.45 + 0.55 * Math.min(1, 16 / Math.max(16, l.dist()))) : 1;
            // visée avec hystérésis : entre dans 14 px, sort au-delà de 26 px (plus de clignotement)
            double off = Math.hypot(l.sx() - cxs, l.sy() - cys);
            boolean was = AIMED.contains(l.w().id);
            boolean aimed = off < 14 || was && off < 26;
            if (aimed) AIMED.add(l.w().id);
            else AIMED.remove(l.w().id);
            boolean far = Settings.wpDotDistance > 0 && l.dist() > Settings.wpDotDistance;
            boolean name = Settings.wpNames == 0 || Settings.wpNames == 1 && aimed;
            if (far && !aimed) {
                drawDot(g, l.w(), l.sx(), l.sy());
            } else {
                drawLabel(g, mc.font, l.w(), l.sx(), l.sy(), l.dist(), Settings.wpLabelScale * k, name);
            }
        }
        if (zonePicking) {
            BlockPos a = zoneStart != null ? zoneStart : target(mc), b = target(mc);
            String size = (Math.abs(a.getX() - b.getX()) + 1) + " × " + (Math.abs(a.getY() - b.getY()) + 1) + " × " + (Math.abs(a.getZ() - b.getZ()) + 1);
            String key = zoneKey.getTranslatedKeyMessage().getString();
            String msg = zoneStart == null ? "Zone: aim at the 1st corner · " + key : "Zone " + size + " · aim at the 2nd corner · " + key;
            int tw = mc.font.width(msg);
            int x = gw / 2 - tw / 2, y = gh / 2 + 14;
            g.fill(x - 5, y - 3, x + tw + 5, y + 11, 0xB0101014);
            g.fill(x - 5, y - 3, x - 4, y + 11, 0xFF4DA3FF);
            g.drawString(mc.font, msg, x, y, 0xFFFFFFFF, true);
            String hint = "Sneak + left click: cancel";
            g.drawString(mc.font, hint, gw / 2 - mc.font.width(hint) / 2, y + 14, 0xFFA0A4AC, true);
        }
    }

    /** Waypoint lointain : simple point de couleur avec un liseré sombre et un léger halo. */
    private static void drawDot(GuiGraphics g, Waypoint w, float cx, float cy) {
        int col = 0xFF000000 | w.color;
        gg.aurora.ui.Draw.glow(g, cx - 4, cy - 4, 8, 8, 5, gg.aurora.ui.Draw.withAlpha(w.color, 0.35f));
        gg.aurora.ui.Draw.rrect(g, cx - 3.5f, cy - 3.5f, 7, 7, 3.5f, 0xC0000000);
        gg.aurora.ui.Draw.rrect(g, cx - 2.5f, cy - 2.5f, 5, 5, 2.5f, col);
    }

    /** Étiquette d'un waypoint centrée en (cx, cy). Aussi utilisée pour l'aperçu dans le menu. */
    public static void drawLabel(GuiGraphics g, Font font, Waypoint w, float cx, float cy, double dist, float scale) {
        drawLabel(g, font, w, cx, cy, dist, scale, true);
    }

    private static final java.util.Set<String> AIMED = new java.util.HashSet<>();

    /** name = false : pas de nom (losange + distance), quel que soit le style. */
    public static void drawLabel(GuiGraphics g, Font font, Waypoint w, float cx, float cy, double dist, float scale, boolean name) {
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().scale(scale, scale);
        String d = "[" + (dist < 1000 ? Math.round(dist) + "m" : String.format(java.util.Locale.ROOT, "%.1fkm", dist / 1000)) + "]";
        boolean showD = Settings.wpShowDistance;
        int dark = 0xA0101014, col = 0xFF000000 | w.color;
        int colored = 0xC0000000 | w.color;
        int preset = !name && (w.preset == 0 || w.preset == 3) ? 4 : w.preset;
        switch (preset) {
            case 1, 2 -> { // icône dans un carré, distance dessous
                int bg = w.preset == 2 ? colored : dark;
                g.fill(-11, -24, 11, -2, bg);
                if (w.preset == 1) g.fill(-11, -24, 11, -23, col);
                drawIcon(g, font, w, 0, -13);
                if (showD) pill(g, font, d, 2, 0xFFFFFFFF, dark);
            }
            case 3 -> { // texte coloré
                int tw = font.width(w.name);
                g.fill(-tw / 2 - 3, -14, tw / 2 + 3, -2, colored);
                g.drawString(font, w.name, -tw / 2, -12, 0xFFFFFFFF, true);
                if (showD) pill(g, font, d, 1, 0xFFFFFFFF, dark);
            }
            case 4 -> { // simple : losange + distance
                g.pose().pushMatrix();
                g.pose().rotate((float) Math.toRadians(45));
                g.fill(-3, -3, 3, 3, 0xFF000000);
                g.fill(-2, -2, 2, 2, col);
                g.pose().popMatrix();
                if (showD) {
                    int tw = font.width(d);
                    g.drawString(font, d, -tw / 2, 6, 0xFFFFFFFF, true);
                }
            }
            default -> { // texte (+ icône à gauche si elle existe)
                ItemStack icon = w.iconType == 1 ? icon(w) : ItemStack.EMPTY;
                int tw = font.width(w.name), iw = icon.isEmpty() ? 0 : 12;
                int x0 = -(tw + iw) / 2 - 3, x1 = (tw + iw) / 2 + 3;
                g.fill(x0, -14, x1, -2, dark);
                g.fill(x0, -14, x0 + 1, -2, col);
                if (!icon.isEmpty()) {
                    g.pose().pushMatrix();
                    g.pose().translate(x0 + 2, -13);
                    g.pose().scale(0.625f, 0.625f);
                    g.renderItem(icon, 0, 0);
                    g.pose().popMatrix();
                }
                g.drawString(font, w.name, x0 + 3 + iw, -12, 0xFFFFFFFF, true);
                if (showD) pill(g, font, d, 1, 0xFFE0E0E0, dark);
            }
        }
        g.pose().popMatrix();
    }

    private static void drawIcon(GuiGraphics g, Font font, Waypoint w, float cx, float cy) {
        if (w.iconType == 1) {
            ItemStack s = icon(w);
            if (!s.isEmpty()) {
                g.renderItem(s, Math.round(cx - 8), Math.round(cy - 8));
                return;
            }
        }
        String letter = w.iconType == 0 ? "•" : w.name.isEmpty() ? "?" : w.name.substring(0, 1).toUpperCase();
        int tw = font.width(letter);
        g.drawString(font, letter, Math.round(cx - tw / 2f), Math.round(cy - 4), 0xFFFFFFFF, true);
    }

    private static ItemStack icon(Waypoint w) {
        return ICONS.computeIfAbsent(w.icon, k -> iconStack(w));
    }

    private static void pill(GuiGraphics g, Font font, String s, int y, int color, int bg) {
        int tw = font.width(s);
        g.fill(-tw / 2 - 2, y, tw / 2 + 2, y + 11, bg);
        g.drawString(font, s, -tw / 2, y + 2, color, true);
    }
}
