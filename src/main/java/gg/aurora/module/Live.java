package gg.aurora.module;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import gg.aurora.Friends;
import gg.aurora.nametag.NametagSync;
import gg.aurora.render.FxRender;
import net.minecraft.client.CameraType;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * État « en direct » partagé entre joueurs du client : trois points au-dessus de la tête quand un
 * joueur écrit un message privé, et emotes (salut, danse…) visibles par les autres.
 */
public final class Live {
    private Live() {}

    public static final String[] EMOTE_IDS = {"wave", "dance", "point", "clap", "salute", "facepalm"};
    public static final String[] EMOTE_NAMES = {"Wave", "Dance", "Point", "Clap", "Attention", "Facepalm"};
    private static final float[] EMOTE_SECONDS = {2.6f, 6f, 3f, 3f, 2.5f, 2.5f};

    private record State(boolean typing, String emote, long emoteStart) {}

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Paranoia-Live");
        t.setDaemon(true);
        return t;
    });
    private static long nextPoll, lastTypingSent;
    private static volatile boolean polling;

    // emote locale
    private static String selfEmote = "";
    private static long selfStart;
    private static CameraType savedCamera;

    public static float duration(String id) {
        for (int i = 0; i < EMOTE_IDS.length; i++) if (EMOTE_IDS[i].equals(id)) return EMOTE_SECONDS[i];
        return 3;
    }

    /** Lance une emote (moi), visible des autres joueurs du client. */
    public static void playEmote(String id) {
        if (!ClientModules.EMOTES.enabled) return;
        Minecraft mc = Minecraft.getInstance();
        selfEmote = id;
        selfStart = System.currentTimeMillis();
        Friends.sendLive(false, id);
        if (ClientModules.EMOTES.flag("camera", true) && mc.options.getCameraType().isFirstPerson()) {
            savedCamera = mc.options.getCameraType();
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        }
    }

    /** J'écris dans les messages privés (appelé à chaque touche ; envoyé au plus toutes les 2 s). */
    public static void typed() {
        long now = System.currentTimeMillis();
        if (now - lastTypingSent > 2000) {
            lastTypingSent = now;
            Friends.sendLive(true, "");
        }
    }

    public static void stoppedTyping() {
        if (lastTypingSent != 0) {
            lastTypingSent = 0;
            Friends.sendLive(false, "");
        }
    }

    /** Emote en cours pour ce joueur : {id, secondes écoulées}, ou null. */
    public static Object[] emote(Player p) {
        Minecraft mc = Minecraft.getInstance();
        if (p == mc.player || p.getUUID().equals(mc.getUser().getProfileId())) {
            if (selfEmote.isEmpty()) return null;
            float t = (System.currentTimeMillis() - selfStart) / 1000f;
            return t < duration(selfEmote) ? new Object[]{selfEmote, t} : null;
        }
        State s = STATES.get(p.getUUID());
        if (s == null || s.emote() == null) return null;
        float t = (System.currentTimeMillis() - s.emoteStart()) / 1000f;
        return t >= 0 && t < duration(s.emote()) ? new Object[]{s.emote(), t} : null;
    }

    public static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            STATES.clear();
            return;
        }
        // fin de l'emote locale : on remet la caméra
        if (!selfEmote.isEmpty() && (System.currentTimeMillis() - selfStart) / 1000f >= duration(selfEmote)) {
            selfEmote = "";
            if (savedCamera != null) {
                mc.options.setCameraType(savedCamera);
                savedCamera = null;
            }
        }
        // bouger annule l'emote
        if (!selfEmote.isEmpty() && mc.player.getDeltaMovement().horizontalDistanceSqr() > 0.01 && System.currentTimeMillis() - selfStart > 400) {
            selfStart = 0;
        }
        long now = System.currentTimeMillis();
        if (now < nextPoll || polling) return;
        nextPoll = now + 1500;
        List<UUID> ids = new ArrayList<>();
        for (Player p : mc.level.players()) {
            // 160 blocks: pings are useful far away (typing bubbles and emotes only show up close anyway)
            if (p == mc.player || p.distanceToSqr(mc.player) > 160 * 160) continue;
            if (NametagSync.profile(p.getUUID()) != null) ids.add(p.getUUID());
        }
        if (ids.isEmpty()) {
            STATES.clear();
            return;
        }
        polling = true;
        EXEC.execute(() -> {
            try {
                JsonObject o = Friends.fetchLive(ids);
                if (o == null) return;
                long serverNow = o.has("now") ? o.get("now").getAsLong() : System.currentTimeMillis();
                long local = System.currentTimeMillis();
                JsonObject live = o.has("live") ? o.getAsJsonObject("live") : new JsonObject();
                for (UUID id : ids) {
                    String key = id.toString().replace("-", "");
                    if (!live.has(key)) {
                        STATES.remove(id);
                        continue;
                    }
                    JsonObject s = live.getAsJsonObject(key);
                    boolean typing = s.has("typing") && s.get("typing").getAsBoolean();
                    String emote = null;
                    long start = 0;
                    if (s.has("emote") && s.get("emote").isJsonObject()) {
                        JsonObject e = s.getAsJsonObject("emote");
                        emote = e.get("id").getAsString();
                        start = local - (serverNow - e.get("at").getAsLong());
                    }
                    STATES.put(id, new State(typing, emote, start));
                    if (s.has("ping") && s.get("ping").isJsonObject()) {
                        JsonObject pg = s.getAsJsonObject("ping");
                        long at = pg.has("at") ? pg.get("at").getAsLong() : serverNow;
                        gg.aurora.waypoint.Pings.received(id, pg, Math.max(0, serverNow - at));
                    }
                }
            } catch (Exception ignored) {
                // hors ligne : pas d'état
            } finally {
                polling = false;
            }
        });
    }

    // ---------------------------------------------------------------- bulle « en train d'écrire »

    private static final Identifier WHITE = Identifier.fromNamespaceAndPath("aurora", "textures/cosmetic/white.png");
    private static final Identifier GLOW = Identifier.fromNamespaceAndPath("aurora", "textures/ui/glow.png");

    public static void renderWorld(PoseStack pose, MultiBufferSource consumers, Vec3 cam, float pt) {
        Minecraft mc = Minecraft.getInstance();
        if (consumers == null || mc.level == null || STATES.isEmpty()) return;
        Quaternionf rot = mc.gameRenderer.getMainCamera().rotation();
        Vector3f right = rot.transform(new Vector3f(1, 0, 0)), up = rot.transform(new Vector3f(0, 1, 0));
        Matrix4f m = pose.last().pose();
        RenderType bg = net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucent(WHITE), dots = FxRender.additive(GLOW);
        boolean any = false;
        float time = (System.currentTimeMillis() % 100000) / 1000f;
        for (Player p : mc.level.players()) {
            State s = STATES.get(p.getUUID());
            if (s == null || !s.typing() || p.isInvisible()) continue;
            any = true;
            double x = Mth.lerp(pt, p.xo, p.getX()) - cam.x, y = Mth.lerp(pt, p.yo, p.getY()) + p.getBbHeight() + 0.75 - cam.y,
                    z = Mth.lerp(pt, p.zo, p.getZ()) - cam.z;
            VertexConsumer b = consumers.getBuffer(bg);
            quad(b, m, right, up, (float) x, (float) y, (float) z, 0.32f, 0.14f, 0xB0101018);
            VertexConsumer d = consumers.getBuffer(dots);
            for (int i = 0; i < 3; i++) {
                float bounce = (float) Math.max(0, Math.sin(time * 6 - i * 0.9)) * 0.04f;
                float ox = (i - 1) * 0.18f;
                float px = (float) x + right.x * ox + up.x * bounce, py = (float) y + right.y * ox + up.y * bounce, pz = (float) z + right.z * ox + up.z * bounce;
                quad(d, m, right, up, px, py, pz, 0.07f, 0.07f, 0xF0FFFFFF);
            }
        }
        if (any && consumers instanceof MultiBufferSource.BufferSource bs) {
            bs.endBatch(bg);
            bs.endBatch(dots);
        }
    }

    private static void quad(VertexConsumer vc, Matrix4f m, Vector3f r, Vector3f u, float x, float y, float z, float w, float h, int col) {
        float rx = r.x * w, ry = r.y * w, rz = r.z * w, ux = u.x * h, uy = u.y * h, uz = u.z * h;
        v(vc, m, x - rx - ux, y - ry - uy, z - rz - uz, 0, 1, col);
        v(vc, m, x + rx - ux, y + ry - uy, z + rz - uz, 1, 1, col);
        v(vc, m, x + rx + ux, y + ry + uy, z + rz + uz, 1, 0, col);
        v(vc, m, x - rx + ux, y - ry + uy, z - rz + uz, 0, 0, col);
    }

    private static void v(VertexConsumer vc, Matrix4f m, float x, float y, float z, float u, float vv, int col) {
        vc.addVertex(m, x, y, z).setColor(col).setUv(u, vv).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(0, 1, 0);
    }
}
