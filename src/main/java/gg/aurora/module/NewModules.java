package gg.aurora.module;

import gg.aurora.Settings;
import gg.aurora.mixin.GameRendererAccessor;
import gg.aurora.render.FrameStats;
import gg.aurora.ui.Draw;
import gg.aurora.ui.HudKit;
import gg.aurora.ui.Icons;
import gg.aurora.ui.Rows;
import gg.aurora.ui.Theme;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** PvP, HUD and comfort modules added in 1.8.4. */
public final class NewModules {
    private NewModules() {}

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private static String pct(double v) {
        return Math.round(v * 100) + " %";
    }

    // =========================================================================== Item Cooldowns

    private static final Item[] COOLDOWN_ITEMS = {Items.ENDER_PEARL, Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE, Items.CHORUS_FRUIT, Items.SHIELD,
            Items.WIND_CHARGE, Items.GOAT_HORN};

    public static final HudModule COOLDOWNS = Modules.register(new HudModule("item_cooldowns", "Item Cooldowns", Category.PVP, Icons.CLOCK, false, 0.5f, 0.68f, () -> null) {
        @Override
        public void render(GuiGraphics g, boolean preview) {
            LocalPlayer p = mc().player;
            List<ItemStack> on = new ArrayList<>();
            List<Float> left = new ArrayList<>();
            if (p != null) {
                for (Item it : COOLDOWN_ITEMS) {
                    ItemStack st = new ItemStack(it);
                    float f = p.getCooldowns().getCooldownPercent(st, 0);
                    if (f > 0) {
                        on.add(st);
                        left.add(f);
                    }
                }
            }
            if (on.isEmpty() && preview) {
                on.add(new ItemStack(Items.ENDER_PEARL));
                left.add(0.6f);
                on.add(new ItemStack(Items.SHIELD));
                left.add(0.3f);
            }
            if (on.isEmpty()) return;
            lastW = on.size() * 22 + 4;
            lastH = 26;
            int x0 = px(g), y0 = py(g);
            HudKit.background(g, x0, y0, lastW, lastH, 4);
            for (int i = 0; i < on.size(); i++) {
                int x = x0 + 5 + i * 22;
                g.renderItem(on.get(i), x, y0 + 3);
                Draw.rrect(g, x, y0 + 21, 16, 2, 1, 0x50000000);
                Draw.rrect(g, x, y0 + 21, Math.max(1, 16 * left.get(i)), 2, 1, 0xFF000000 | Theme.accent());
            }
        }
    });

    // =========================================================================== Item Counter

    private record Counted(String key, String label, Item item) {}

    private static final Counted[] COUNTED = {new Counted("totem", "Totems", Items.TOTEM_OF_UNDYING), new Counted("pearl", "Ender pearls", Items.ENDER_PEARL),
            new Counted("gapple", "Golden apples", Items.GOLDEN_APPLE), new Counted("crystal", "End crystals", Items.END_CRYSTAL),
            new Counted("xp", "XP bottles", Items.EXPERIENCE_BOTTLE), new Counted("obsidian", "Obsidian", Items.OBSIDIAN)};

    public static final HudModule ITEM_COUNTER = Modules.register(new HudModule("item_counter", "Item Counter", Category.PVP, Icons.BAG, false, 0.99f, 0.56f, () -> null) {
        @Override
        public void render(GuiGraphics g, boolean preview) {
            LocalPlayer p = mc().player;
            List<ItemStack> items = new ArrayList<>();
            List<Integer> counts = new ArrayList<>();
            for (Counted c : COUNTED) {
                if (!flag(c.key(), !c.key().equals("xp") && !c.key().equals("obsidian"))) continue;
                ItemStack st = new ItemStack(c.item());
                int n = p == null ? 12 : HudWidgets.Armor.total(p, st);
                if (n == 0 && flag("hideZero", false) && !preview) continue;
                items.add(st);
                counts.add(n);
            }
            if (items.isEmpty()) return;
            int cell = 28;
            lastW = items.size() * cell + 4;
            lastH = 20;
            int x0 = px(g), y0 = py(g);
            HudKit.background(g, x0, y0, lastW, lastH, 4);
            for (int i = 0; i < items.size(); i++) {
                int x = x0 + 3 + i * cell;
                g.renderItem(items.get(i), x, y0 + 2);
                HudKit.text(g, String.valueOf(counts.get(i)), x + 17, y0 + 7, 0.75f, counts.get(i) == 0 ? 0xFFF2545B : HudKit.textColor());
            }
        }
    }).withSettings(() -> {
        List<Rows.Row> rows = new ArrayList<>();
        for (Counted c : COUNTED) {
            boolean def = !c.key().equals("xp") && !c.key().equals("obsidian");
            rows.add(new Rows.Toggle(c.label(), null, () -> NewModules.ITEM_COUNTER.flag(c.key(), def), v -> NewModules.ITEM_COUNTER.set(c.key(), v)));
        }
        rows.add(new Rows.Toggle("Hide empty", "Skip items you have none of", () -> NewModules.ITEM_COUNTER.flag("hideZero", false), v -> NewModules.ITEM_COUNTER.set("hideZero", v)));
        return rows;
    });

    // =========================================================================== Hitmarker

    public static final Module HITMARKER = Modules.register(new Module("hitmarker", "Hitmarker", Category.PVP, Icons.TARGET, false))
            .withSettings(() -> List.of(
                    new Rows.ColorRow("Color", () -> (int) NewModules.HITMARKER.opt("color", 0xFFFFFF), v -> NewModules.HITMARKER.set("color", v & 0xFFFFFF)),
                    new Rows.Slider("Size", null, 3, 12, 0.5, () -> NewModules.HITMARKER.opt("size", 6), v -> NewModules.HITMARKER.set("size", v), v -> String.format(Locale.ROOT, "%.1f", v)),
                    new Rows.Toggle("Sound", "Short tick on every hit", () -> NewModules.HITMARKER.flag("sound", true), v -> NewModules.HITMARKER.set("sound", v))));

    private static long hitAt;

    /** Your attack landed on an entity. */
    public static void onAttack(Entity e) {
        if (!HITMARKER.enabled || !(e instanceof LivingEntity)) return;
        hitAt = System.currentTimeMillis();
        if (HITMARKER.flag("sound", true)) {
            mc().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), 1.9f, 0.45f));
        }
    }

    private static void renderHitmarker(GuiGraphics g) {
        long age = System.currentTimeMillis() - hitAt;
        if (!HITMARKER.enabled || age > 220) return;
        float a = 1 - age / 220f, s = (float) HITMARKER.opt("size", 6);
        int col = Draw.withAlpha((int) HITMARKER.opt("color", 0xFFFFFF), a);
        float cx = g.guiWidth() / 2f, cy = g.guiHeight() / 2f;
        for (int q = 0; q < 4; q++) {
            float dx = q % 2 == 0 ? 1 : -1, dy = q < 2 ? 1 : -1;
            for (float k = 2; k < s; k += 0.5f) Draw.rrect(g, cx + dx * k - 0.5f, cy + dy * k - 0.5f, 1, 1, 0.5f, col);
        }
    }

    // =========================================================================== Damage Indicator

    public static final Module DAMAGE = Modules.register(new Module("damage_indicator", "Damage Indicator", Category.PVP, Icons.HEART, false))
            .withSettings(() -> List.of(
                    new Rows.Toggle("Damage you deal", null, () -> NewModules.DAMAGE.flag("dealt", true), v -> NewModules.DAMAGE.set("dealt", v)),
                    new Rows.Toggle("Damage you take", null, () -> NewModules.DAMAGE.flag("taken", true), v -> NewModules.DAMAGE.set("taken", v)),
                    new Rows.Slider("Text size", null, 0.6, 2, 0.1, () -> NewModules.DAMAGE.opt("scale", 1), v -> NewModules.DAMAGE.set("scale", v), NewModules::pct)));

    private static final class Floater {
        Vec3 pos;
        float amount;
        long born;
        boolean mine;
    }

    private static final List<Floater> FLOATERS = new ArrayList<>();
    private static final Map<Integer, Float> LAST_HEALTH = new HashMap<>();

    private static void tickDamage(Minecraft mc) {
        if (!DAMAGE.enabled || mc.level == null || mc.player == null) {
            LAST_HEALTH.clear();
            return;
        }
        Map<Integer, Float> seen = new HashMap<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le) || e.distanceToSqr(mc.player) > 32 * 32) continue;
            float hp = le.getHealth() + le.getAbsorptionAmount();
            seen.put(e.getId(), hp);
            Float was = LAST_HEALTH.get(e.getId());
            if (was == null || hp >= was - 0.01f) continue;
            boolean me = e == mc.player;
            if (me ? !DAMAGE.flag("taken", true) : !DAMAGE.flag("dealt", true)) continue;
            Floater f = new Floater();
            f.pos = e.position().add(0, e.getBbHeight() + 0.2, 0);
            f.amount = was - hp;
            f.born = System.currentTimeMillis();
            f.mine = me;
            FLOATERS.add(f);
        }
        LAST_HEALTH.clear();
        LAST_HEALTH.putAll(seen);
    }

    private static void renderDamage(GuiGraphics g, DeltaTracker delta) {
        if (FLOATERS.isEmpty()) return;
        Minecraft mc = mc();
        var cam = mc.gameRenderer.getMainCamera();
        float pt = delta.getGameTimeDeltaPartialTick(true);
        Matrix4f proj = mc.gameRenderer.getProjectionMatrix(((GameRendererAccessor) mc.gameRenderer).aurora$getFov(cam, pt, true));
        Quaternionf view = new Quaternionf(cam.rotation()).conjugate();
        Vec3 c = cam.position();
        long now = System.currentTimeMillis();
        float size = (float) DAMAGE.opt("scale", 1);
        for (Iterator<Floater> it = FLOATERS.iterator(); it.hasNext(); ) {
            Floater f = it.next();
            float age = (now - f.born) / 1000f;
            if (age > 1.2f) {
                it.remove();
                continue;
            }
            Vector3f p = new Vector3f((float) (f.pos.x - c.x), (float) (f.pos.y + age * 0.6 - c.y), (float) (f.pos.z - c.z));
            view.transform(p);
            Vector4f clip = new Vector4f(p, 1);
            proj.transform(clip);
            if (clip.w <= 0.05f) continue;
            float sx = (clip.x / clip.w * 0.5f + 0.5f) * g.guiWidth(), sy = (1 - (clip.y / clip.w * 0.5f + 0.5f)) * g.guiHeight();
            String t = String.format(Locale.ROOT, "%.1f", f.amount);
            Draw.centered(g, t, sx, sy, size, Draw.withAlpha(f.mine ? 0xF2545B : 0xFFD65E, Math.min(1, (1.2f - age) * 2)));
        }
    }

    // =========================================================================== Graphs

    private static void graph(GuiGraphics g, HudModule m, float[] values, int count, float max, String label, int color) {
        lastSize(m, 104, 34);
        int x0 = m.px(g), y0 = m.py(g);
        HudKit.background(g, x0, y0, m.lastW, m.lastH, 4);
        HudKit.text(g, label, x0 + 4, y0 + 3, 0.7f, HudKit.textColor());
        int gx = x0 + 3, gy = y0 + 31, gw = m.lastW - 6, gh = 18;
        for (int i = 0; i < gw && i < count; i++) {
            float v = values[(count - 1 - i)] / Math.max(0.001f, max);
            int h = Math.max(1, Math.round(Math.min(1, v) * gh));
            g.fill(gx + gw - 1 - i, gy - h, gx + gw - i, gy, 0xD0000000 | color);
        }
    }

    private static void lastSize(HudModule m, int w, int h) {
        m.lastW = w;
        m.lastH = h;
    }

    private static final float[] FRAME_VALUES = new float[128];

    public static final HudModule FRAME_GRAPH = Modules.register(new HudModule("frametime_graph", "Frametime Graph", Icons.GAUGE, false, 0.01f, 0.2f, () -> null) {
        @Override
        public void render(GuiGraphics g, boolean preview) {
            int n = FrameStats.recent(FRAME_VALUES);
            float max = 1;
            for (int i = 0; i < n; i++) max = Math.max(max, FRAME_VALUES[i]);
            int low = FrameStats.low1();
            graph(g, this, FRAME_VALUES, n, Math.max(max, 8), String.format(Locale.ROOT, "%.1f ms max · 1%% low %d", max, low), 0x5EE6A8);
        }
    });

    private static final float[] PING = new float[120];
    private static int pingCount;
    private static long pingAt;

    private static void tickPing(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (now - pingAt < 1000 || mc.player == null || mc.getConnection() == null) return;
        pingAt = now;
        PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        if (info == null) return;
        System.arraycopy(PING, 0, PING, 1, PING.length - 1); // newest first, shifted once a second
        PING[0] = info.getLatency();
        pingCount = Math.min(PING.length, pingCount + 1);
    }

    private static final float[] PING_VIEW = new float[120];

    public static final HudModule PING_GRAPH = Modules.register(new HudModule("ping_graph", "Ping Graph", Icons.WIFI, false, 0.01f, 0.28f, () -> null) {
        @Override
        public void render(GuiGraphics g, boolean preview) {
            float max = 50;
            for (int i = 0; i < pingCount; i++) {
                PING_VIEW[pingCount - 1 - i] = PING[i];
                max = Math.max(max, PING[i]);
            }
            graph(g, this, PING_VIEW, pingCount, max, Math.round(PING[0]) + " ms", 0x5FA8F3);
        }
    });

    // =========================================================================== Saturation

    private static final Identifier FOOD_FULL = Identifier.withDefaultNamespace("hud/food_full"), FOOD_HALF = Identifier.withDefaultNamespace("hud/food_half");

    public static final Module SATURATION = Modules.register(new Module("saturation", "Saturation", Category.HUD, Icons.HEART, false))
            .withSettings(() -> List.of(
                    new Rows.Toggle("Saturation bar", "Gold outline around the hunger shanks", () -> NewModules.SATURATION.flag("bar", true), v -> NewModules.SATURATION.set("bar", v)),
                    new Rows.Toggle("Food preview", "Shows what the food in your hand will restore, when you can eat it", () -> NewModules.SATURATION.flag("preview", true), v -> NewModules.SATURATION.set("preview", v)),
                    new Rows.Toggle("Value", "Saturation number next to the bar", () -> NewModules.SATURATION.flag("text", false), v -> NewModules.SATURATION.set("text", v))));

    private static int[] outline;
    private static Object outlineSource;
    private static long outlineChecked;

    /**
     * One bit per pixel of the 9x9 shank: the opaque pixels that touch a transparent one, i.e. its outline.
     * Read from the active resource packs, so a pack with another shank shape gets its own outline.
     */
    /** Gold over the dark outline pixels of one shank, columns [from, to) of its 9 (filled from the right like vanilla). */
    private static void outline(GuiGraphics g, int[] mask, int x, int y, int from, int to, int argb) {
        for (int py = 0; py < 9; py++) {
            int row = mask[py];
            if (row == 0) continue;
            for (int px = Math.max(0, from); px < Math.min(9, to); px++) {
                if ((row >> px & 1) != 0) g.fill(x + px, y + py, x + px + 1, y + py + 1, argb);
            }
        }
    }

    private static int[] outlineMask() {
        long now = System.currentTimeMillis();
        if (outline != null && now - outlineChecked < 1000) return outline;
        outlineChecked = now;
        var res = mc().getResourceManager().getResource(Identifier.withDefaultNamespace("textures/gui/sprites/hud/food_empty.png"));
        Object src = res.map(r -> r.sourcePackId()).orElse(null);
        if (outline != null && java.util.Objects.equals(src, outlineSource)) return outline;
        int[] mask = new int[9];
        if (res.isPresent()) {
            try (var in = res.get().open(); var img = com.mojang.blaze3d.platform.NativeImage.read(in)) {
                int w = img.getWidth(), h = img.getHeight();
                java.util.function.BiPredicate<Integer, Integer> solid = (px, py) -> px >= 0 && py >= 0 && px < 9 && py < 9
                        && (img.getPixel(px * w / 9, py * h / 9) >>> 24) > 16;
                for (int py = 0; py < 9; py++) {
                    for (int px = 0; px < 9; px++) {
                        if (solid.test(px, py) && (!solid.test(px - 1, py) || !solid.test(px + 1, py) || !solid.test(px, py - 1) || !solid.test(px, py + 1))) {
                            mask[py] |= 1 << px;
                        }
                    }
                }
            } catch (Exception ignored) {
                mask = null;
            }
        }
        if (mask == null || java.util.Arrays.stream(mask).allMatch(r -> r == 0)) {
            // fallback: border of the cell
            mask = new int[9];
            for (int i = 0; i < 9; i++) mask[i] = i == 0 || i == 8 ? 0x1FF : 0x101;
        }
        outline = mask;
        outlineSource = src;
        return mask;
    }

    private static void renderSaturation(GuiGraphics g) {
        Minecraft mc = mc();
        LocalPlayer p = mc.player;
        if (!SATURATION.enabled || p == null || mc.gameMode == null || !mc.gameMode.canHurtPlayer() || p.getVehicle() instanceof LivingEntity) return;
        int right = g.guiWidth() / 2 + 91, y = g.guiHeight() - 39;
        var food = p.getFoodData();
        float sat = food.getSaturationLevel();
        // inventaire ou autre écran ouvert : le HUD vanilla est assombri, le contour doré suit (sinon il ressort trop)
        boolean dimmed = mc.screen != null;
        int gold = dimmed ? 0x55FFC93C : 0xFFFFC93C;
        float dim = dimmed ? 0.33f : 1f;
        if (SATURATION.flag("bar", true)) {
            int[] mask = outlineMask();
            for (int i = 0; i < 10; i++) {
                float v = Math.max(0, Math.min(2, sat - i * 2)) / 2f;
                if (v <= 0) break;
                int x = right - i * 8 - 9;
                // gold outline drawn from the right, like the vanilla half shank
                outline(g, mask, x, y, 9 - Math.round(9 * v), 9, gold);
            }
        }
        if (SATURATION.flag("preview", true)) {
            ItemStack hand = p.getMainHandItem();
            var props = hand.get(DataComponents.FOOD);
            if (props == null) {
                hand = p.getOffhandItem();
                props = hand.get(DataComponents.FOOD);
            }
            // nothing to preview when the food cannot be eaten now (full bar), except always-edible food like chorus fruit
            if (props != null && !p.canEat(props.canAlwaysEat())) props = null;
            if (props != null) {
                float pulse = (0.35f + 0.45f * (float) Math.abs(Math.sin(System.currentTimeMillis() / 260.0))) * dim;
                int color = (Math.round(255 * pulse) << 24) | 0xFFFFFF;
                int now = food.getFoodLevel(), after = Math.min(20, now + props.nutrition());
                for (int v = now + 1; v <= after; v++) {
                    if (v % 2 == 0 || v == after) {
                        int i = (v - 1) / 2;
                        g.blitSprite(RenderPipelines.GUI_TEXTURED, v % 2 == 0 ? FOOD_FULL : FOOD_HALF, right - i * 8 - 9, y, 9, 9, color);
                    }
                }
                float satAfter = Math.min(after, sat + props.saturation());
                for (int i = 0; i < 10; i++) {
                    float a = Math.max(0, Math.min(2, satAfter - i * 2)) / 2f, b = Math.max(0, Math.min(2, sat - i * 2)) / 2f;
                    if (a <= b) continue;
                    int x = right - i * 8 - 9;
                    // the part this food would add: the same gold outline, blinking
                    outline(g, outlineMask(), x, y, 9 - Math.round(9 * a), 9 - Math.round(9 * b), (Math.round(255 * pulse) << 24) | 0xFFC93C);
                }
            }
        }
        if (SATURATION.flag("text", false)) {
            Draw.text(g, String.format(Locale.ROOT, "%.1f", sat), right + 3, y + 1, 0.75f, gold);
        }
    }

    // =========================================================================== Stopwatch

    private static long watchStart, watchSaved;
    private static boolean watchRunning, watchKeyDown;

    private static void watchToggle() {
        if (watchRunning) watchSaved += System.currentTimeMillis() - watchStart;
        else watchStart = System.currentTimeMillis();
        watchRunning = !watchRunning;
    }

    private static long watchTime() {
        return watchSaved + (watchRunning ? System.currentTimeMillis() - watchStart : 0);
    }

    public static final HudModule STOPWATCH = Modules.register(new HudModule("stopwatch", "Stopwatch", Icons.CLOCK, false, 0.5f, 0.2f, () -> {
        long ms = watchTime();
        return List.of(String.format(Locale.ROOT, "Timer|%d:%02d.%d", ms / 60000, ms / 1000 % 60, ms / 100 % 10));
    })).withSettings(() -> List.of(
            new Rows.Button("Start / stop", null, Icons.CLOCK, NewModules::watchToggle),
            new Rows.Button("Reset", null, Icons.ARROW_LEFT, () -> {
                watchSaved = 0;
                watchRunning = false;
            }),
            new Rows.KeyBind("Start / stop key", null, () -> (int) NewModules.STOPWATCH.opt("key", -1), v -> NewModules.STOPWATCH.set("key", v))));

    // =========================================================================== Held Item Info

    public static final HudModule HELD_INFO = Modules.register(new HudModule("held_item_info", "Held Item Info", Icons.SWORD, false, 0.5f, 0.75f, () -> {
        LocalPlayer p = mc().player;
        if (p == null) return null;
        ItemStack s = p.getMainHandItem();
        if (s.isEmpty()) return null;
        List<String> out = new ArrayList<>();
        out.add(s.getHoverName().getString() + (s.isDamageableItem() ? "|" + (s.getMaxDamage() - s.getDamageValue()) + " / " + s.getMaxDamage() : "|x" + HudWidgets.Armor.total(p, s)));
        return out;
    }));

    // =========================================================================== Overlay Editor

    public static final Module OVERLAYS = Modules.register(new Module("overlay_editor", "Overlay Editor", Category.VISUALS, Icons.EYE, false))
            .withSettings(() -> List.of(
                    new Rows.Toggle("Hide pumpkin", "Carved pumpkin on your head", () -> NewModules.OVERLAYS.flag("pumpkin", true), v -> NewModules.OVERLAYS.set("pumpkin", v)),
                    new Rows.Toggle("Hide powder snow", null, () -> NewModules.OVERLAYS.flag("snow", true), v -> NewModules.OVERLAYS.set("snow", v)),
                    new Rows.Toggle("Hide water", "Underwater screen texture", () -> NewModules.OVERLAYS.flag("water", true), v -> NewModules.OVERLAYS.set("water", v)),
                    new Rows.Toggle("Hide vignette", "Dark screen corners", () -> NewModules.OVERLAYS.flag("vignette", false), v -> NewModules.OVERLAYS.set("vignette", v)),
                    new Rows.Info("Fire", "Use the Low Fire module")));

    public static boolean hideTexture(Identifier id) {
        if (!OVERLAYS.enabled) return false;
        String p = id.getPath();
        return p.contains("pumpkinblur") && OVERLAYS.flag("pumpkin", true) || p.contains("powder_snow") && OVERLAYS.flag("snow", true);
    }

    public static boolean hideWater() {
        return OVERLAYS.enabled && OVERLAYS.flag("water", true);
    }

    public static boolean hideVignette() {
        return OVERLAYS.enabled && OVERLAYS.flag("vignette", false);
    }

    // =========================================================================== Time Changer

    public static final Module TIME = Modules.register(new Module("time_changer", "Time Changer", Category.VISUALS, Icons.CLOCK, false))
            .withSettings(() -> List.of(
                    new Rows.Slider("Time of day", "Only changes what you see", 0, 24000, 250, () -> NewModules.TIME.opt("time", 6000), v -> NewModules.TIME.set("time", v),
                            v -> String.format(Locale.ROOT, "%02d:%02d", ((int) (v / 1000) + 6) % 24, (int) (v % 1000 * 60 / 1000)))));

    // =========================================================================== Sound Mixer

    private static final SoundSource[] MIXER = {SoundSource.PLAYERS, SoundSource.HOSTILE, SoundSource.NEUTRAL, SoundSource.BLOCKS, SoundSource.AMBIENT,
            SoundSource.WEATHER, SoundSource.MUSIC, SoundSource.RECORDS, SoundSource.VOICE};
    private static final String[] MIXER_NAMES = {"Combat (players)", "Hostile mobs", "Friendly mobs", "Blocks", "Ambience", "Weather", "Music", "Jukebox", "Voice"};

    public static final Module SOUNDS = Modules.register(new Module("sound_mixer", "Sound Mixer", Category.MISC, Icons.SLIDERS, false))
            .withSettings(() -> {
                List<Rows.Row> rows = new ArrayList<>();
                for (int i = 0; i < MIXER.length; i++) {
                    SoundSource s = MIXER[i];
                    rows.add(new Rows.Slider(MIXER_NAMES[i], null, 0, 1, 0.05, () -> mc().options.getSoundSourceOptionInstance(s).get(), v -> {
                        mc().options.getSoundSourceOptionInstance(s).set(v);
                        mc().options.save();
                    }, NewModules::pct));
                }
                return rows;
            });

    // =========================================================================== View Bobbing

    public static final Module NO_BOB = Modules.register(new Module("no_view_bobbing", "No View Bobbing", Category.VISUALS, Icons.ARROW_DOWN, false));

    // =========================================================================== Tooltips

    public static final Module TOOLTIPS = Modules.register(new Module("item_tooltips", "Item Tooltips+", Category.MISC, Icons.INFO, false))
            .withSettings(() -> List.of(
                    new Rows.Toggle("Durability", "Remaining uses and percent", () -> NewModules.TOOLTIPS.flag("dura", true), v -> NewModules.TOOLTIPS.set("dura", v)),
                    new Rows.Toggle("Food", "Hunger and saturation restored", () -> NewModules.TOOLTIPS.flag("food", true), v -> NewModules.TOOLTIPS.set("food", v))));

    private static void tooltip(ItemStack s, List<net.minecraft.network.chat.Component> lines) {
        if (!TOOLTIPS.enabled) return;
        if (s.isDamageableItem() && TOOLTIPS.flag("dura", true)) {
            int left = s.getMaxDamage() - s.getDamageValue();
            lines.add(net.minecraft.network.chat.Component.literal("Durability: " + left + " / " + s.getMaxDamage() + "  (" + Math.round(100f * left / s.getMaxDamage()) + "%)")
                    .withColor(s.getBarColor()));
        }
        var f = s.get(DataComponents.FOOD);
        if (f != null && TOOLTIPS.flag("food", true)) {
            lines.add(net.minecraft.network.chat.Component.literal(String.format(Locale.ROOT, "Food: +%d  Saturation: +%.1f", f.nutrition(), f.saturation())).withColor(0xFFC93C));
        }
    }

    // =========================================================================== wiring

    public static void init() {
        net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback.EVENT.register((stack, ctx, type, lines) -> tooltip(stack, lines));
    }

    private static Boolean savedBob;

    public static void tick(Minecraft mc) {
        tickDamage(mc);
        tickPing(mc);
        // view bobbing follows the module, the player's option comes back when it is turned off
        var bob = mc.options.bobView();
        if (NO_BOB.enabled) {
            if (savedBob == null) savedBob = bob.get();
            if (bob.get()) bob.set(false);
        } else if (savedBob != null) {
            bob.set(savedBob);
            savedBob = null;
        }
        int key = (int) STOPWATCH.opt("key", -1);
        if (STOPWATCH.enabled && key >= 0 && mc.screen == null) {
            boolean down = com.mojang.blaze3d.platform.InputConstants.isKeyDown(mc.getWindow(), key);
            if (down && !watchKeyDown) watchToggle();
            watchKeyDown = down;
        }
    }

    /** Every frame, outside the HUD cache: things that must follow the crosshair or the world. */
    public static void renderOverlay(GuiGraphics g, DeltaTracker delta) {
        renderHitmarker(g);
        renderDamage(g, delta);
        renderSaturation(g);
    }

    /** Client time from the Time Changer (or -1). */
    public static long fixedTime() {
        return TIME.enabled ? (long) TIME.opt("time", 6000) : -1;
    }

    /** Unused settings guard: keeps the import used if settings move. */
    static boolean anim() {
        return Settings.anim();
    }

    static final ArrayDeque<Long> UNUSED = new ArrayDeque<>();
}
