package gg.aurora.waypoint;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import gg.aurora.Friends;
import gg.aurora.Settings;
import gg.aurora.ui.Draw;
import gg.aurora.ui.Icons;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Fortnite-style pings. Tap the key: ping what you aim at (an item on the ground is pinged as that
 * item, a monster as an enemy, a chest as loot). Tap twice quickly: danger. Hold: a wheel to pick the
 * ping. Tapping your own ping again removes it. Pings are shared with the Paranoia players around you
 * through the live channel (same one as emotes).
 */
public final class Pings {
    private Pings() {}

    public enum Kind {
        GO("go", "Go here", Icons.PIN, 0x4DA3FF),
        DANGER("danger", "Danger", "!", 0xFF3B3B),
        ENEMY("enemy", "Enemy", Icons.SWORD, 0xFF5C8A),
        LOOT("loot", "Loot", Icons.BAG, 0xFFD43A),
        DEFEND("defend", "Defend", Icons.SHIELD, 0x34C759),
        HELP("help", "Need help", Icons.HEART, 0xFF8A2E),
        ITEM("item", "Item", Icons.BAG, 0xFFD43A);

        public final String id, title, icon;
        public final int color;

        Kind(String id, String title, String icon, int color) {
            this.id = id;
            this.title = title;
            this.icon = icon;
            this.color = color;
        }

        static Kind of(String id) {
            for (Kind k : values()) if (k.id.equals(id)) return k;
            return null;
        }
    }

    /** Wheel order, clockwise from the top. */
    public static final Kind[] WHEEL = {Kind.GO, Kind.DANGER, Kind.ENEMY, Kind.LOOT, Kind.DEFEND, Kind.HELP};

    public static final class Ping {
        String id = UUID.randomUUID().toString().substring(0, 8);
        UUID owner;
        String ownerName = "";
        boolean mine;
        Kind kind;
        double x, y, z;
        String dim;
        int entity = -1;
        String item = "";
        long born, life;
        ItemStack stack = ItemStack.EMPTY;
        // last HUD position (to re-ping your own ping and remove it)
        float sx, sy;
        boolean seen;

        String title() {
            if (kind == Kind.ITEM || (kind == Kind.LOOT && !stack.isEmpty())) {
                if (stack.isEmpty()) return kind.title;
                String n = stack.getHoverName().getString();
                return stack.getCount() > 1 ? n + " ×" + stack.getCount() : n;
            }
            return kind.title;
        }
    }

    /** What the crosshair points at when the key goes down. */
    public record Target(Vec3 pos, int entity, String item, Kind auto, String what) {}

    public static final List<Ping> LIST = new ArrayList<>();
    private static final ConcurrentLinkedQueue<Object[]> INBOX = new ConcurrentLinkedQueue<>();
    private static final double RANGE = 256;
    private static final long HOLD_MS = 220, DOUBLE_MS = 450;

    public static KeyMapping key;
    private static long pressAt = -1, lastTapAt;
    private static Target pressTarget;
    private static Ping lastTap;
    private static boolean waitRelease;

    public static void init(KeyMapping.Category category) {
        key = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.aurora.ping", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, category));
    }

    // ===================================================================== input

    /** Real key / mouse button state (key mappings are released when a screen opens). */
    /** Dev captures only: keeps the wheel open without a key held. */
    public static boolean devHold;

    public static boolean physicallyDown() {
        if (devHold) return true;
        Minecraft mc = Minecraft.getInstance();
        InputConstants.Key k = InputConstants.getKey(key.saveString());
        if (k.getType() == InputConstants.Type.MOUSE) return GLFW.glfwGetMouseButton(mc.getWindow().handle(), k.getValue()) == GLFW.GLFW_PRESS;
        if (k.getType() == InputConstants.Type.KEYSYM && k.getValue() >= 0) return InputConstants.isKeyDown(mc.getWindow(), k.getValue());
        return false;
    }

    public static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            LIST.clear();
            INBOX.clear();
            pressAt = -1;
            return;
        }
        while (key.consumeClick()) {
            // state is read below; just drain the queue
        }
        long now = System.currentTimeMillis();
        boolean down = Settings.pingEnabled && mc.screen == null && physicallyDown();
        // after the wheel: wait for the key to be let go before a new press counts
        if (waitRelease) {
            if (!down && mc.screen == null) waitRelease = false;
            down = false;
        }
        if (down && pressAt < 0) {
            pressAt = now;
            pressTarget = aim(mc);
        } else if (down && now - pressAt >= HOLD_MS) {
            pressAt = -1;
            waitRelease = true;
            mc.setScreen(new gg.aurora.ui.PingWheelScreen(pressTarget));
        } else if (!down && pressAt >= 0) {
            pressAt = -1;
            tap(mc, pressTarget, now);
        }
        receive(mc, now);
        update(mc, now);
    }

    private static void tap(Minecraft mc, Target t, long now) {
        // second tap right after the first: the ping becomes a danger ping
        if (lastTap != null && now - lastTapAt < DOUBLE_MS && LIST.contains(lastTap)) {
            Ping d = lastTap;
            lastTap.kind = Kind.DANGER;
            lastTap.stack = ItemStack.EMPTY;
            lastTap.item = "";
            lastTap.entity = -1;
            lastTap.born = now;
            lastTap.life = life(Kind.DANGER);
            lastTap = null;
            sound(Kind.DANGER, true);
            send(d);
            return;
        }
        // tapping your own ping again removes it
        Ping own = aimedOwn(mc);
        if (own != null) {
            LIST.remove(own);
            sendClear(own);
            sound(null, true);
            lastTap = null;
            return;
        }
        if (t == null) return;
        lastTap = place(mc, t, t.auto());
        lastTapAt = now;
    }

    /** From the wheel. */
    public static void choose(Target t, Kind kind) {
        Minecraft mc = Minecraft.getInstance();
        if (t == null || mc.player == null) return;
        place(mc, t, kind);
        lastTap = null;
    }

    private static Ping place(Minecraft mc, Target t, Kind kind) {
        Ping p = new Ping();
        p.mine = true;
        p.owner = mc.player.getUUID();
        p.ownerName = mc.player.getGameProfile().name();
        p.kind = kind;
        p.x = t.pos().x;
        p.y = t.pos().y;
        p.z = t.pos().z;
        p.dim = mc.level.dimension().identifier().toString();
        // the item / enemy is followed while it moves; other kinds stay where they were placed
        boolean follow = kind == Kind.ITEM || kind == Kind.ENEMY || (kind == Kind.LOOT && !t.item().isEmpty());
        p.entity = follow ? t.entity() : -1;
        p.item = kind == Kind.ITEM || kind == Kind.LOOT ? t.item() : "";
        p.stack = stack(p.item);
        p.born = System.currentTimeMillis();
        p.life = life(kind);
        resolve(mc, p);
        // at most 3 of my pings at once: the oldest goes
        int mine = 0;
        for (Ping q : LIST) if (q.mine) mine++;
        if (mine >= 3) for (int i = 0; i < LIST.size(); i++) if (LIST.get(i).mine) {
            LIST.remove(i);
            break;
        }
        LIST.add(p);
        sound(kind, true);
        send(p);
        return p;
    }

    private static long life(Kind k) {
        long base = Math.max(3, Settings.pingSeconds) * 1000L;
        return k == Kind.ITEM || k == Kind.LOOT ? base + base / 2 : base;
    }

    /** Own ping under the crosshair (from the last HUD frame). */
    private static Ping aimedOwn(Minecraft mc) {
        float cx = mc.getWindow().getGuiScaledWidth() / 2f, cy = mc.getWindow().getGuiScaledHeight() / 2f;
        Ping best = null;
        double bd = 14;
        for (Ping p : LIST) {
            if (!p.mine || !p.seen) continue;
            double d = Math.hypot(p.sx - cx, p.sy - 12 - cy);
            double d2 = Math.hypot(p.sx - cx, p.sy - cy);
            d = Math.min(d, d2);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    // ===================================================================== aiming

    public static Target aim(Minecraft mc) {
        Entity cam = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Vec3 eye = cam.getEyePosition(pt), look = cam.getViewVector(pt), end = eye.add(look.scale(RANGE));
        BlockHitResult bh = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, cam));
        boolean block = bh.getType() == HitResult.Type.BLOCK;
        double best = block ? eye.distanceToSqr(bh.getLocation()) : RANGE * RANGE;
        Vec3 hit = block ? bh.getLocation() : null;
        Entity hitE = null;
        ItemEntity item = null;
        double itemD = best;
        Vec3 itemHit = null;
        Vec3 reach = eye.add(look.scale(Math.sqrt(best)));
        for (Entity e : mc.level.getEntities(cam, new AABB(eye, reach).inflate(1.5),
                e -> !e.isSpectator() && e != mc.player && (e instanceof ItemEntity || e instanceof LivingEntity))) {
            // items are tiny: a bigger target makes them easy to ping
            double grow = e instanceof ItemEntity ? 0.5 : 0.2;
            Optional<Vec3> o = e.getBoundingBox().inflate(grow).clip(eye, reach);
            if (o.isEmpty()) continue;
            double d = eye.distanceToSqr(o.get());
            if (e instanceof ItemEntity it) {
                if (d < itemD) {
                    itemD = d;
                    item = it;
                    itemHit = o.get();
                }
            } else if (d < best) {
                best = d;
                hitE = e;
                hit = o.get();
            }
        }
        // an item right behind a mob or a player (feet, legs) is what you meant to ping
        if (item != null && (hitE == null || !(hitE instanceof Enemy) && Math.sqrt(itemD) - Math.sqrt(best) < 2.5)) {
            hitE = item;
            hit = itemHit;
        }
        // aimed at the ground next to an item: take the item
        if (hitE == null && hit != null) {
            double bd = 1.6 * 1.6;
            for (ItemEntity it : mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(hit, hit).inflate(1.6))) {
                double d = it.position().distanceToSqr(hit);
                if (d < bd) {
                    bd = d;
                    hitE = it;
                }
            }
        }
        if (hitE instanceof ItemEntity it) {
            ItemStack s = it.getItem();
            return new Target(top(hitE), it.getId(), BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), Kind.ITEM, s.getHoverName().getString());
        }
        if (hitE instanceof Enemy) return new Target(top(hitE), hitE.getId(), "", Kind.ENEMY, hitE.getName().getString());
        if (hitE != null) return new Target(top(hitE), -1, "", Kind.GO, hitE.getName().getString());
        if (hit == null) return null;
        BlockPos bp = bh.getBlockPos();
        if (mc.level.getBlockEntity(bp) instanceof Container) {
            Item blockItem = mc.level.getBlockState(bp).getBlock().asItem();
            String id = blockItem == Items.AIR ? "" : BuiltInRegistries.ITEM.getKey(blockItem).toString();
            return new Target(Vec3.atBottomCenterOf(bp.above()), -1, id, Kind.LOOT, mc.level.getBlockState(bp).getBlock().getName().getString());
        }
        return new Target(hit, -1, "", Kind.GO, null);
    }

    private static Vec3 top(Entity e) {
        return new Vec3(e.getX(), e.getBoundingBox().maxY + 0.15, e.getZ());
    }

    private static ItemStack stack(String id) {
        if (id == null || id.isEmpty()) return ItemStack.EMPTY;
        try {
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
            return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    // ===================================================================== lifetime

    private static void update(Minecraft mc, long now) {
        LIST.removeIf(p -> now - p.born > p.life);
        for (Ping p : LIST) resolve(mc, p);
    }

    /** Follows the pinged entity; a picked-up item makes its ping fade out. */
    private static void resolve(Minecraft mc, Ping p) {
        if (p.entity < 0) return;
        Entity e = mc.level.getEntity(p.entity);
        if (e == null || e.isRemoved() || (e instanceof LivingEntity le && le.isDeadOrDying())) {
            p.entity = -1;
            p.life = Math.min(p.life, System.currentTimeMillis() - p.born + 900);
            return;
        }
        if (e instanceof ItemEntity it) {
            ItemStack s = it.getItem();
            if (!s.isEmpty() && (p.stack.isEmpty() || p.stack.getItem() == s.getItem())) p.stack = s.copy();
        }
    }

    /** Smooth position this frame. */
    private static Vec3 pos(Minecraft mc, Ping p, float pt) {
        if (p.entity >= 0) {
            Entity e = mc.level.getEntity(p.entity);
            if (e != null) {
                double h = e.getBoundingBox().getYsize();
                return new Vec3(Mth.lerp(pt, e.xo, e.getX()), Mth.lerp(pt, e.yo, e.getY()) + h + 0.15, Mth.lerp(pt, e.zo, e.getZ()));
            }
        }
        return new Vec3(p.x, p.y, p.z);
    }

    // ===================================================================== sharing

    private static JsonObject json(Ping p) {
        JsonObject o = new JsonObject();
        o.addProperty("id", p.id);
        o.addProperty("kind", p.kind.id);
        o.addProperty("x", Math.round(p.x * 10) / 10.0);
        o.addProperty("y", Math.round(p.y * 10) / 10.0);
        o.addProperty("z", Math.round(p.z * 10) / 10.0);
        o.addProperty("dim", p.dim);
        if (p.entity >= 0) o.addProperty("eid", p.entity);
        if (!p.item.isEmpty()) o.addProperty("item", p.item);
        return o;
    }

    private static void send(Ping p) {
        if (Settings.pingShare) Friends.sendPing(json(p));
    }

    private static void sendClear(Ping p) {
        if (!Settings.pingShare) return;
        JsonObject o = new JsonObject();
        o.addProperty("id", p.id);
        o.addProperty("kind", "none");
        Friends.sendPing(o);
    }

    /** Called from the live poll thread: queued, applied on the client thread. */
    public static void received(UUID owner, JsonObject ping, long ageMs) {
        INBOX.add(new Object[]{owner, ping, ageMs, System.currentTimeMillis()});
    }

    private static void receive(Minecraft mc, long now) {
        Object[] m;
        while ((m = INBOX.poll()) != null) {
            UUID owner = (UUID) m[0];
            JsonObject o = (JsonObject) m[1];
            long born = (long) m[3] - (long) m[2];
            if (Settings.pingFrom == 2 || !Settings.pingEnabled) continue;
            if (Settings.pingFrom == 1 && !Friends.isFriend(owner)) continue;
            try {
                String id = o.get("id").getAsString();
                Kind kind = Kind.of(o.get("kind").getAsString());
                Ping old = null;
                for (Ping p : LIST) if (!p.mine && owner.equals(p.owner)) old = p;
                if (kind == null) { // cleared
                    if (old != null && old.id.equals(id)) LIST.remove(old);
                    continue;
                }
                if (old != null && old.id.equals(id) && old.kind == kind) continue;
                long life = life(kind);
                if (now - born > life) continue;
                if (old != null) LIST.remove(old);
                Ping p = new Ping();
                p.id = id;
                p.owner = owner;
                Player pl = mc.level.getPlayerByUUID(owner);
                p.ownerName = pl != null ? pl.getGameProfile().name() : "";
                p.kind = kind;
                p.x = o.get("x").getAsDouble();
                p.y = o.get("y").getAsDouble();
                p.z = o.get("z").getAsDouble();
                p.dim = o.get("dim").getAsString();
                p.entity = o.has("eid") ? o.get("eid").getAsInt() : -1;
                p.item = o.has("item") ? o.get("item").getAsString() : "";
                p.stack = stack(p.item);
                p.born = born;
                p.life = life;
                resolve(mc, p);
                LIST.add(p);
                if (old == null || old.kind != kind) sound(kind, false);
            } catch (RuntimeException ignored) {
                // malformed ping: skipped
            }
        }
    }

    private static void sound(Kind k, boolean mine) {
        if (!Settings.pingSound) return;
        var mgr = Minecraft.getInstance().getSoundManager();
        float vol = mine ? 0.55f : 0.45f;
        if (k == null) mgr.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), 0.8f, vol));
        else if (k == Kind.DANGER) {
            mgr.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 0.75f, vol));
            mgr.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS.value(), 0.7f, vol));
        } else if (k == Kind.HELP || k == Kind.ENEMY) mgr.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 1.1f, vol));
        else mgr.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.6f, vol));
    }

    // ===================================================================== world: light beam

    private static RenderType beamType;

    public static void renderWorld(PoseStack pose, MultiBufferSource consumers, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        if (!Settings.pingEnabled || LIST.isEmpty() || consumers == null || mc.level == null) return;
        if (beamType == null) beamType = RenderTypes.textBackgroundSeeThrough();
        String dim = mc.level.dimension().identifier().toString();
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        long now = System.currentTimeMillis();
        Matrix4f m = pose.last().pose();
        VertexConsumer vc = null;
        for (Ping p : LIST) {
            if (!p.dim.equals(dim)) continue;
            Vec3 v = pos(mc, p, pt);
            float a = alpha(p, now);
            if (a < 0.02f) continue;
            if (vc == null) vc = consumers.getBuffer(beamType);
            float x = (float) (v.x - cam.x), y = (float) (v.y - cam.y), z = (float) (v.z - cam.z);
            float dist = (float) Math.sqrt(x * x + y * y + z * z);
            // vertical beam facing the camera, fading toward the top; thin but never invisible
            float sx = -z, sz = x, len = (float) Math.sqrt(sx * sx + sz * sz);
            if (len < 1e-4f) continue;
            float w = Math.max(0.03f, dist * 0.0022f);
            sx = sx / len * w;
            sz = sz / len * w;
            float h = Math.min(40, 2.5f + dist * 0.08f);
            int bottom = Draw.withAlpha(p.kind.color, 0.75f * a), top = Draw.withAlpha(p.kind.color, 0);
            vc.addVertex(m, x - sx, y, z - sz).setColor(bottom).setLight(0xF000F0);
            vc.addVertex(m, x + sx, y, z + sz).setColor(bottom).setLight(0xF000F0);
            vc.addVertex(m, x + sx, y + h, z + sz).setColor(top).setLight(0xF000F0);
            vc.addVertex(m, x - sx, y + h, z - sz).setColor(top).setLight(0xF000F0);
            // ring that opens up when the ping lands (danger keeps pulsing)
            long age = now - p.born;
            float t = p.kind == Kind.DANGER ? (age % 1100) / 1100f : Math.min(1, age / 700f);
            if (t < 1) ring(vc, m, x, y + 0.02f, z, 0.25f + t * 1.6f, Math.max(0.04f, dist * 0.003f),
                    Draw.withAlpha(p.kind.color, (1 - t) * 0.8f * a));
        }
        if (vc != null && consumers instanceof MultiBufferSource.BufferSource bs) bs.endBatch();
    }

    private static void ring(VertexConsumer vc, Matrix4f m, float x, float y, float z, float r, float th, int col) {
        int n = 32;
        for (int i = 0; i < n; i++) {
            double a0 = i * Math.PI * 2 / n, a1 = (i + 1) * Math.PI * 2 / n;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            float ri = r - th, ro = r + th;
            // both windings so the ring shows from above and below
            vc.addVertex(m, x + c0 * ri, y, z + s0 * ri).setColor(col).setLight(0xF000F0);
            vc.addVertex(m, x + c1 * ri, y, z + s1 * ri).setColor(col).setLight(0xF000F0);
            vc.addVertex(m, x + c1 * ro, y, z + s1 * ro).setColor(col).setLight(0xF000F0);
            vc.addVertex(m, x + c0 * ro, y, z + s0 * ro).setColor(col).setLight(0xF000F0);
            vc.addVertex(m, x + c0 * ro, y, z + s0 * ro).setColor(col).setLight(0xF000F0);
            vc.addVertex(m, x + c1 * ro, y, z + s1 * ro).setColor(col).setLight(0xF000F0);
            vc.addVertex(m, x + c1 * ri, y, z + s1 * ri).setColor(col).setLight(0xF000F0);
            vc.addVertex(m, x + c0 * ri, y, z + s0 * ri).setColor(col).setLight(0xF000F0);
        }
    }

    private static float alpha(Ping p, long now) {
        long age = now - p.born, left = p.life - age;
        return Draw.clamp01(age / 120f) * Draw.clamp01(left / 600f);
    }

    // ===================================================================== HUD marker

    public static void renderHud(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        for (Ping p : LIST) p.seen = false;
        if (!Settings.pingEnabled || LIST.isEmpty() || mc.level == null || mc.player == null || mc.options.hideGui) return;
        int gw = g.guiWidth(), gh = g.guiHeight();
        if (!Projector.begin(delta, gw, gh)) return;
        String dim = mc.level.dimension().identifier().toString();
        float pt = delta.getGameTimeDeltaPartialTick(true);
        long now = System.currentTimeMillis();
        float[] s = new float[3];
        for (Ping p : LIST) {
            if (!p.dim.equals(dim)) continue;
            float a = alpha(p, now);
            if (a < 0.02f) continue;
            Vec3 v = pos(mc, p, pt);
            double dist = Math.sqrt(v.distanceToSqr(Projector.camX(), Projector.camY(), Projector.camZ()));
            boolean front = Projector.project(v.x, v.y, v.z, s);
            if (front && Projector.onScreen(s, -10)) {
                p.sx = s[0];
                p.sy = s[1];
                p.seen = true;
                long age = now - p.born;
                float pop = age < 260 ? 1 + 0.4f * (1 - Draw.easeOutCubic(age / 260f)) : 1;
                marker(g, p, s[0], s[1], dist, pop, a, now);
            } else {
                float ang = Projector.clampToEdge(s, front, 20);
                Waypoints.edgeArrow(g, s[0], s[1], ang, p.kind.color, a);
                String d = Waypoints.distText(dist);
                float ty = s[1] + (Math.sin(ang) > 0.5 ? -16 : 10);
                Waypoints.shadowText(g, d, s[0] - Draw.width(d, 0.7f) / 2, ty, 0.7f, Draw.withAlpha(0xFFFFFF, a));
            }
        }
    }

    private static void marker(GuiGraphics g, Ping p, float x, float y, double dist, float scale, float a, long now) {
        int col = p.kind.color, fg = Waypoints.contrast(col);
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        float r = 9, cy = -r - 6;
        // pulse when it lands, and keeps pulsing for danger
        long age = now - p.born;
        float t = p.kind == Kind.DANGER ? (age % 1100) / 1100f : Math.min(1, age / 600f);
        if (t < 1) {
            float rr = r + 2 + t * 10;
            Draw.ring(g, -rr, cy - rr, 2 * rr, 2 * rr, rr, Draw.withAlpha(col, (1 - t) * 0.9f * a));
        }
        Draw.rrect(g, -r - 1.5f, cy - r - 1.5f, 2 * r + 3, 2 * r + 3, r + 1.5f, Draw.withAlpha(0x000000, 0.45f * a));
        Draw.rrect(g, -r, cy - r, 2 * r, 2 * r, r, Draw.withAlpha(col, 0.95f * a));
        Draw.ring(g, -r, cy - r, 2 * r, 2 * r, r, Draw.withAlpha(0xFFFFFF, 0.35f * a));
        if (!p.stack.isEmpty() && a > 0.6f) {
            g.pose().pushMatrix();
            g.pose().translate(-6, cy - 6);
            g.pose().scale(0.75f, 0.75f);
            g.renderItem(p.stack, 0, 0);
            g.pose().popMatrix();
        } else if ("!".equals(p.kind.icon)) {
            Draw.centered(g, "!", 0, cy - 5, 1.3f, Draw.withAlpha(fg, a));
        } else {
            Draw.icon(g, p.kind.icon, 0, cy, 11, Draw.withAlpha(fg, a));
        }
        // stem to the exact spot
        Draw.rrect(g, -0.75f, cy + r, 1.5f, 4, 0.75f, Draw.withAlpha(col, 0.9f * a));
        Draw.rrect(g, -2, -2, 4, 4, 2, Draw.withAlpha(col, a));
        // text under the spot
        String title = p.title();
        String sub = Waypoints.distText(dist) + (p.mine || p.ownerName.isEmpty() ? "" : "  ·  " + p.ownerName);
        Waypoints.shadowText(g, title, -Draw.width(title, 0.85f) / 2, 5, 0.85f, Draw.withAlpha(0xFFFFFF, a));
        Waypoints.shadowText(g, sub, -Draw.width(sub, 0.7f) / 2, 14, 0.7f, Draw.withAlpha(0xFFFFFF, 0.7f * a));
        g.pose().popMatrix();
    }
}
