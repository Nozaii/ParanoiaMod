package gg.aurora.module;

import gg.aurora.AuroraClient;
import gg.aurora.mixin.OptionInstanceAccessor;
import gg.aurora.ui.Draw;
import gg.aurora.ui.HudKit;
import gg.aurora.ui.Icons;
import gg.aurora.ui.Spring;
import gg.aurora.ui.Theme;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;

public final class Modules {
    private Modules() {}

    public static final List<Module> ALL = new ArrayList<>();

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private static LocalPlayer player() {
        return mc().player;
    }

    /** Enregistre un module déclaré ailleurs (ex. WorldModules). */
    public static <T extends Module> T register(T m) {
        return add(m);
    }

    private static <T extends Module> T add(T m) {
        ALL.add(m);
        return m;
    }

    // ---------- état partagé ----------
    private static final Deque<Long> leftClicks = new ArrayDeque<>();
    private static final Deque<Long> rightClicks = new ArrayDeque<>();
    private static boolean lastLeft, lastRight;
    private static int combo;
    private static long lastHitMs;
    private static double lastReach = -1;
    private static LivingEntity target;
    private static long targetMs;
    private static float zoomAnim = 1f;
    private static double savedGamma = -1;

    // ================= PvP =================
    public static final HudModule REACH = add(new HudModule("reach", "Reach Display", Category.PVP, Icons.RULER, false, 0.01f, 0.40f,
            () -> List.of("Reach|" + (lastReach < 0 ? "--" : String.format(Locale.ROOT, "%.2f", lastReach)))));

    public static final HudModule COMBO = add(new HudModule("combo", "Combo Counter", Category.PVP, Icons.SWORD, false, 0.01f, 0.46f,
            () -> List.of("Combo|" + combo)));

    public static final HudModule TARGET = add(new HudModule("target_hud", "Target HUD", Category.PVP, Icons.TARGET, false, 0.55f, 0.55f, () -> null) {
        private final Spring hpAnim = new Spring(1, 120, 1f), ghost = new Spring(1, 18, 1f);
        private long last = System.nanoTime();

        @Override
        public void render(GuiGraphics g, boolean preview) {
            LivingEntity t = target;
            boolean fresh = t != null && t.isAlive() && System.currentTimeMillis() - targetMs < 5000;
            if (!fresh && !preview) return;
            long now = System.nanoTime();
            float dt = Math.min(0.1f, (now - last) / 1e9f);
            last = now;
            String name = fresh ? t.getDisplayName().getString() : "Target";
            float hp = fresh ? t.getHealth() : 14f, max = fresh ? t.getMaxHealth() : 20f;
            float frac = Math.max(0f, Math.min(1f, hp / max));
            hpAnim.update(dt, frac);
            ghost.update(dt, frac);
            if (ghost.value < hpAnim.value) ghost.set(hpAnim.value);

            lastW = (int) Math.max(110, Draw.width(name, 1f) + 50);
            lastH = 32;
            float x0 = px(g), y0 = py(g);
            HudKit.background(g, x0, y0, lastW, lastH, 7);
            Draw.rrect(g, x0 + 6, y0 + 6, 20, 20, 6, Draw.withAlpha(Theme.accent(), 0.25f));
            Draw.centered(g, name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT), x0 + 16, y0 + 10.5f, 1.1f, HudKit.textColor());
            HudKit.text(g, name, x0 + 32, y0 + 6, 1f, HudKit.textColor());
            String hpText = String.format(Locale.ROOT, "%.1f", hp / 2f) + " ❤";
            HudKit.text(g, hpText, x0 + lastW - 7 - Draw.width(hpText, 0.85f), y0 + 6.5f, 0.85f, HudKit.dimColor());
            float bx = x0 + 32, bw = lastW - 39, by = y0 + 19;
            Draw.rrect(g, bx, by, bw, 5, 2.5f, Draw.withAlpha(0xFFFFFF, 0.08f));
            if (ghost.value > 0.01f) Draw.rrect(g, bx, by, bw * ghost.value, 5, 2.5f, Draw.withAlpha(0xFFFFFF, 0.35f));
            int col = Draw.lerpColor(0xF2545B, Theme.accent(), Math.min(1f, frac * 2f));
            if (hpAnim.value > 0.01f) Draw.rrect(g, bx, by, Math.max(5, bw * hpAnim.value), 5, 2.5f, 0xFF000000 | col);
        }
    });

    public static final Module LOW_HP = add(new Module("low_hp", "Low HP Warning", Category.PVP, Icons.HEART, true))
            .withSettings(() -> List.of(
                    new gg.aurora.ui.Rows.Slider("Threshold", "Alert below", 1, 10, 0.5, () -> Modules.LOW_HP.opt("hearts", 3),
                            v -> Modules.LOW_HP.set("hearts", v), v -> String.format(Locale.ROOT, "%.1f ❤", v)),
                    new gg.aurora.ui.Rows.ColorRow("Color", () -> (int) Modules.LOW_HP.opt("color", 0xC01818), v -> Modules.LOW_HP.set("color", v & 0xFFFFFF)),
                    new gg.aurora.ui.Rows.Slider("Intensity", null, 0.2, 1, 0.05, () -> Modules.LOW_HP.opt("alpha", 0.5),
                            v -> Modules.LOW_HP.set("alpha", v), v -> Math.round(v * 100) + " %"),
                    new gg.aurora.ui.Rows.Toggle("Pulse", "Beats like a heart", () -> Modules.LOW_HP.flag("pulse", true), v -> Modules.LOW_HP.set("pulse", v))));

    // ================= HUD =================
    public static final HudModule KEYSTROKES = add(new HudModule("keystrokes", "Keystrokes", Icons.KEYBOARD, false, 0.01f, 0.70f, () -> null) {
        private final Map<String, Spring> press = new HashMap<>();
        private long last = System.nanoTime();
        private float dt;

        @Override
        public void render(GuiGraphics g, boolean preview) {
            long now = System.nanoTime();
            dt = Math.min(0.1f, (now - last) / 1e9f);
            last = now;
            var o = mc().options;
            float k = 19, gap = 2.5f;
            boolean mouse = flag("mouse", true), space = flag("space", false), showCps = flag("cps", true);
            lastW = Math.round(k * 3 + gap * 2);
            lastH = Math.round(k * 2 + gap + (mouse ? gap + 15 : 0) + (space ? gap + 10 : 0));
            float x0 = px(g), y0 = py(g);
            key(g, x0 + k + gap, y0, k, k, keyLabel(o.keyUp, "W"), o.keyUp, null);
            key(g, x0, y0 + k + gap, k, k, keyLabel(o.keyLeft, "A"), o.keyLeft, null);
            key(g, x0 + k + gap, y0 + k + gap, k, k, keyLabel(o.keyDown, "S"), o.keyDown, null);
            key(g, x0 + (k + gap) * 2, y0 + k + gap, k, k, keyLabel(o.keyRight, "D"), o.keyRight, null);
            float by = y0 + (k + gap) * 2;
            if (mouse) {
                float half = (lastW - gap) / 2;
                key(g, x0, by, half, 15, "LMB", o.keyAttack, showCps ? cps(leftClicks) + " CPS" : null);
                key(g, x0 + half + gap, by, lastW - half - gap, 15, "RMB", o.keyUse, showCps ? cps(rightClicks) + " CPS" : null);
                by += 15 + gap;
            }
            if (space) key(g, x0, by, lastW, 10, "—", o.keyJump, null);
        }

        /** Lettre réelle de la touche (AZERTY : Z Q S D) si l'option est active. */
        private String keyLabel(KeyMapping km, String def) {
            if (!flag("real", true)) return def;
            String s = km.getTranslatedKeyMessage().getString();
            return s.length() <= 3 ? s.toUpperCase(Locale.ROOT) : def;
        }

        private void key(GuiGraphics g, float x, float y, float w, float h, String label, KeyMapping km, String sub) {
            Spring sp = press.computeIfAbsent(label, l -> new Spring(0, 600, 0.75f));
            sp.update(dt, km.isDown() ? 1 : 0);
            float p = Math.max(0f, Math.min(1f, sp.value));
            float s = 1f - 0.06f * sp.value;
            float cx = x + w / 2, cy = y + h / 2;
            float ww = w * s, hh = h * s;
            HudKit.background(g, cx - ww / 2, cy - hh / 2, ww, hh, 5);
            if (p > 0.01f) {
                Draw.glow(g, cx - ww / 2, cy - hh / 2, ww, hh, 6, Draw.withAlpha(Theme.accent(), 0.35f * p));
                Draw.rrect(g, cx - ww / 2, cy - hh / 2, ww, hh, 5, Draw.withAlpha(Theme.accent(), 0.85f * p));
            }
            int col = 0xFF000000 | Draw.lerpColor(Theme.TEXT, 0xFFFFFF, p);
            String t = sub != null ? sub : label;
            float sc = sub != null ? 0.8f : h < 12 ? 0.7f : 1f;
            Draw.centered(g, t, cx, cy - 4.2f * sc, sc, col);
        }
    }).withSettings(() -> List.of(
            new gg.aurora.ui.Rows.Toggle("Mouse buttons", null, () -> Modules.KEYSTROKES.flag("mouse", true), v -> Modules.KEYSTROKES.set("mouse", v)),
            new gg.aurora.ui.Rows.Toggle("CPS on the buttons", null, () -> Modules.KEYSTROKES.flag("cps", true), v -> Modules.KEYSTROKES.set("cps", v)),
            new gg.aurora.ui.Rows.Toggle("Space bar", null, () -> Modules.KEYSTROKES.flag("space", false), v -> Modules.KEYSTROKES.set("space", v)),
            new gg.aurora.ui.Rows.Toggle("Real keys", "Shows Z Q S D on AZERTY", () -> Modules.KEYSTROKES.flag("real", true),
                    v -> Modules.KEYSTROKES.set("real", v))));

    public static final HudModule TOTEMS = add(new HudModule("totems", "Totem Counter", Icons.TOTEM, true, 0.99f, 0.62f, () -> null) {
        @Override
        public void render(GuiGraphics g, boolean preview) {
            LocalPlayer p = player();
            int count = p == null ? 0 : countItem(p, Items.TOTEM_OF_UNDYING);
            if (count == 0 && !preview) return;
            String s = "x" + count;
            lastW = (int) Math.ceil(6 + 16 + 4 + Draw.width(s, 1f) + 8);
            lastH = 20;
            int x0 = px(g), y0 = py(g);
            HudKit.background(g, x0, y0, lastW, lastH, 10);
            g.renderItem(new ItemStack(Items.TOTEM_OF_UNDYING), x0 + 5, y0 + 2);
            HudKit.text(g, s, x0 + 25, y0 + 6.4f, 1f, count == 0 ? 0xFFF2545B : HudKit.textColor());
        }
    });

    public static final HudModule FPS = add(new HudModule("fps", "FPS", Icons.GAUGE, true, 0.01f, 0.02f,
            () -> List.of(Modules.FPS.flag("low", true) && gg.aurora.render.FrameStats.low1() > 0
                    ? "FPS|" + mc().getFps() + "  ·  1% " + gg.aurora.render.FrameStats.low1()
                    : "FPS|" + mc().getFps())))
            .withSettings(() -> List.of(new gg.aurora.ui.Rows.Toggle("1 % low", "FPS of the slowest frames (stutter)",
                    () -> Modules.FPS.flag("low", true), v -> Modules.FPS.set("low", v))));

    public static final HudModule CPS = add(new HudModule("cps", "CPS", Icons.MOUSE, false, 0.01f, 0.08f,
            () -> List.of("CPS|" + cps(leftClicks) + (Modules.CPS.flag("right", true) ? " · " + cps(rightClicks) : ""))))
            .withSettings(() -> List.of(new gg.aurora.ui.Rows.Toggle("Right click", "Also shows right clicks", () -> Modules.CPS.flag("right", true),
                    v -> Modules.CPS.set("right", v))));

    public static final HudModule SPRINT_HUD = add(new HudModule("sprint_hud", "Sprint", Icons.CHEVRONS, false, 0.01f, 0.14f,
            () -> {
                LocalPlayer p = player();
                if (p == null) return null;
                return List.of(p.isSprinting() ? "Sprint" : p.isShiftKeyDown() ? "Sneak" : "Walk");
            }));

    public static final HudModule COORDS = add(new HudModule("coords", "Coordinates", Icons.PIN, false, 0.01f, 0.20f,
            () -> {
                LocalPlayer p = player();
                if (p == null) return null;
                if (ClientModules.hideCoords()) return List.of("Coordinates|hidden");
                String f = Modules.COORDS.flag("decimals", true) ? "%.1f" : "%.0f";
                List<String> out = new ArrayList<>();
                out.add(String.format(Locale.ROOT, "X|" + f, p.getX()));
                out.add(String.format(Locale.ROOT, "Y|" + f, p.getY()));
                out.add(String.format(Locale.ROOT, "Z|" + f, p.getZ()));
                if (Modules.COORDS.flag("other", false) && mc().level != null) {
                    boolean nether = mc().level.dimension() == net.minecraft.world.level.Level.NETHER;
                    double k = nether ? 8 : 0.125;
                    out.add(String.format(Locale.ROOT, (nether ? "World|" : "Nether|") + "%.0f, %.0f", p.getX() * k, p.getZ() * k));
                }
                if (Modules.COORDS.flag("dir", true)) out.add("Dir|" + capitalize(p.getDirection().getName()));
                return out;
            }))
            .withSettings(() -> List.of(
                    new gg.aurora.ui.Rows.Toggle("Direction", null, () -> Modules.COORDS.flag("dir", true), v -> Modules.COORDS.set("dir", v)),
                    new gg.aurora.ui.Rows.Toggle("Decimals", null, () -> Modules.COORDS.flag("decimals", true), v -> Modules.COORDS.set("decimals", v)),
                    new gg.aurora.ui.Rows.Toggle("Nether ↔ Overworld", "Matching coordinates in the other dimension",
                            () -> Modules.COORDS.flag("other", false), v -> Modules.COORDS.set("other", v))));

    public static final HudModule PING = add(new HudModule("ping", "Ping", Icons.WIFI, false, 0.01f, 0.32f,
            () -> {
                LocalPlayer p = player();
                if (p == null || mc().getConnection() == null || mc().isLocalServer()) return null;
                PlayerInfo info = mc().getConnection().getPlayerInfo(p.getUUID());
                return info == null ? null : List.of("Ping|" + info.getLatency() + " ms");
            }));

    public static final HudModule POTIONS = add(new HudWidgets.Potions());

    /** Armure + compteur d'objets (fusion de l'ancien Armor HUD et du compteur de durabilité). */
    public static final HudModule ARMOR = add(new HudWidgets.Armor());

    public static final HudModule CLOCK = add(new HudModule("clock", "Clock", Icons.CLOCK, false, 0.50f, 0.01f,
            () -> List.of(LocalTime.now().format(DateTimeFormatter.ofPattern(
                    (Modules.CLOCK.flag("h12", false) ? "h:mm" : "HH:mm") + (Modules.CLOCK.flag("seconds", false) ? ":ss" : "")
                            + (Modules.CLOCK.flag("h12", false) ? " a" : ""), Locale.ENGLISH)))))
            .withSettings(() -> List.of(
                    new gg.aurora.ui.Rows.Toggle("12 h format", "AM / PM", () -> Modules.CLOCK.flag("h12", false), v -> Modules.CLOCK.set("h12", v)),
                    new gg.aurora.ui.Rows.Toggle("Seconds", null, () -> Modules.CLOCK.flag("seconds", false), v -> Modules.CLOCK.set("seconds", v))));

    public static final HudModule SERVER = add(new HudModule("server", "Server Address", Icons.GLOBE, false, 0.50f, 0.07f,
            () -> {
                ServerData s = mc().getCurrentServer();
                return s == null ? null : List.of(ClientModules.hideIp() ? "Server hidden" : s.ip);
            }));

    // ================= Visuals =================
    public static final Module FULLBRIGHT = add(new Module("fullbright", "Fullbright", Category.VISUALS, Icons.SUN, false) {
        @Override
        protected void onToggle() {
            applyFullbright();
        }
    }).withSettings(() -> List.of(new gg.aurora.ui.Rows.Slider("Brightness", null, 1, 15, 0.5, () -> Modules.FULLBRIGHT.opt("level", 15), v -> {
        Modules.FULLBRIGHT.set("level", v);
    }, v -> Math.round(v / 15 * 100) + " %")));

    public static final Module ZOOM = add(new Module("zoom", "Zoom (C)", Category.VISUALS, Icons.SEARCH, true))
            .withSettings(() -> List.of(
                    new gg.aurora.ui.Rows.Slider("Magnification", null, 1.5, 10, 0.5, () -> Modules.ZOOM.opt("factor", 4), v -> Modules.ZOOM.set("factor", v),
                            v -> String.format(Locale.ROOT, "×%.1f", v)),
                    new gg.aurora.ui.Rows.Toggle("Smooth animation", null, () -> Modules.ZOOM.flag("smooth", true), v -> Modules.ZOOM.set("smooth", v)),
                    new gg.aurora.ui.Rows.Toggle("Scroll wheel", "Zoom in or out while holding", () -> Modules.ZOOM.flag("scroll", true), v -> Modules.ZOOM.set("scroll", v)),
                    new gg.aurora.ui.Rows.Toggle("Cinematic camera", "Smoothed mouse movement while zooming",
                            () -> Modules.ZOOM.flag("cinematic", false), v -> Modules.ZOOM.set("cinematic", v))));

    private static boolean zoomCinematic;
    /** Multiplicateur réglé à la molette pendant le zoom. */
    private static float zoomScroll = 1;

    /** Molette pendant le zoom : zoome plus ou moins. true = molette consommée. */
    public static boolean zoomScroll(double dy) {
        boolean on = ZOOM.enabled && AuroraClient.zoomKey != null && AuroraClient.zoomKey.isDown() && mc().screen == null;
        if (!on || dy == 0 || !ZOOM.flag("scroll", true)) return false;
        zoomScroll = (float) Math.max(0.35, Math.min(4, zoomScroll * (dy > 0 ? 1.15 : 1 / 1.15)));
        return true;
    }

    public static final Module NO_HURT_CAM = add(new Module("no_hurt_cam", "No Hurt Cam", Category.VISUALS, Icons.EYE, false));

    public static final Module HIDE_SCOREBOARD = add(new Module("hide_scoreboard", "Hide Scoreboard", Category.VISUALS, Icons.LIST, false));

    public static final Module HIDE_BOSSBAR = add(new Module("hide_bossbar", "Hide Boss Bar", Category.VISUALS, Icons.SKULL, false));

    // ================= Player =================
    public static final Module TOGGLE_SPRINT = add(new Module("toggle_sprint", "Toggle Sprint", Category.PLAYER, Icons.CHEVRONS, false) {
        @Override
        protected void onToggle() {
            if (!enabled) mc().options.keySprint.setDown(false);
        }
    });

    public static final Module TOGGLE_SNEAK = add(new Module("toggle_sneak", "Toggle Sneak", Category.PLAYER, Icons.ARROW_DOWN, false) {
        @Override
        protected void onToggle() {
            mc().options.toggleCrouch().set(enabled);
        }
    });

    // ================= Misc =================
    public static final HudModule SPEED = add(new HudModule("speed", "Speed", Category.MISC, Icons.CHEVRONS, false, 0.01f, 0.52f,
            () -> {
                LocalPlayer p = player();
                if (p == null) return null;
                double dy = Modules.SPEED.flag("vertical", false) ? p.getY() - p.yo : 0;
                double bps = Math.sqrt(Math.pow(p.getX() - p.xo, 2) + Math.pow(p.getZ() - p.zo, 2) + dy * dy) * 20;
                boolean kmh = Modules.SPEED.opt("unit", 0) == 1;
                return List.of(String.format(Locale.ROOT, kmh ? "Speed|%.1f km/h" : "Speed|%.1f b/s", kmh ? bps * 3.6 : bps));
            }))
            .withSettings(() -> List.of(
                    new gg.aurora.ui.Rows.Choice("Unit", new String[]{"Blocks/s", "km/h"}, () -> (int) Modules.SPEED.opt("unit", 0), i -> Modules.SPEED.set("unit", i)),
                    new gg.aurora.ui.Rows.Toggle("Vertical speed", "Also counts falls and jumps", () -> Modules.SPEED.flag("vertical", false),
                            v -> Modules.SPEED.set("vertical", v))));

    public static final HudModule DAY = add(new HudModule("day", "Day Counter", Category.MISC, Icons.SUN, false, 0.50f, 0.13f,
            () -> mc().level == null ? null : List.of("Day|" + (mc().level.getDayTime() / 24000L + 1))));

    public static final HudModule MEMORY = add(new HudModule("memory", "Memory", Category.MISC, Icons.MONITOR, false, 0.99f, 0.02f,
            () -> {
                Runtime r = Runtime.getRuntime();
                long used = (r.totalMemory() - r.freeMemory()) >> 20, max = r.maxMemory() >> 20;
                return List.of("RAM|" + (used * 100 / Math.max(1, max)) + "% · " + used + " Mo");
            }));

    // ---------- logique ----------

    private static final java.util.Set<Integer> HELD = new java.util.HashSet<>();

    /** Raccourcis des modules : un appui (front montant) bascule le module, seulement en jeu. */
    private static void moduleKeys(Minecraft mc) {
        if (mc.screen != null || mc.player == null) {
            HELD.clear();
            return;
        }
        var window = mc.getWindow();
        for (Module m : ALL) {
            if (m.key < 0) continue;
            boolean down = com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, m.key);
            if (down && HELD.add(m.key)) {
                if (!m.enabled && gg.aurora.FairPlay.blocked(m)) {
                    gg.aurora.FairPlay.notice(m);
                    continue;
                }
                m.enabled = !m.enabled;
                gg.aurora.ui.Toasts.push(m.icon, m.name, m.enabled ? "On" : "Off", false);
                gg.aurora.Config.save();
            } else if (!down) {
                HELD.remove(m.key);
            }
        }
    }

    public static Module byId(String id) {
        for (Module m : ALL) if (m.id.equals(id)) return m;
        return null;
    }

    public static void tick(Minecraft mc) {
        moduleKeys(mc);
        WorldModules.tick(mc);
        PvpOptimizers.tick(mc);
        ShieldModules.tick(mc);
        ExtraModules.tick(mc);
        ClientModules.tickFall(mc.player);
        gg.aurora.FairPlay.tick(mc);
        gg.aurora.PlayerStats.tick(mc);
        CombatModules.tick(mc);
        VisualModules.tick(mc);
        MusicModule.tick(mc);
        if (mc.player == null) return;
        if (TOGGLE_SPRINT.enabled) mc.options.keySprint.setDown(true);
        if (System.currentTimeMillis() - lastHitMs > 2000 || mc.player.hurtTime > 0) combo = 0;
    }

    /** Appelé à chaque frame du HUD pour compter les clics. */
    public static void sampleClicks() {
        var o = mc().options;
        long now = System.currentTimeMillis();
        boolean l = o.keyAttack.isDown(), r = o.keyUse.isDown();
        if (l && !lastLeft) leftClicks.addLast(now);
        if (r && !lastRight) rightClicks.addLast(now);
        lastLeft = l;
        lastRight = r;
    }

    public static void onAttack(LocalPlayer p, net.minecraft.world.entity.Entity e) {
        PvpOptimizers.onAttack(p, e);
        ShieldModules.onAttack(p, e);
        VisualModules.onAttack(e);
        ClientModules.onHit(e);
        gg.aurora.PlayerStats.onAttack(e);
        gg.aurora.cosmetic.HitSlashes.onHit(e);
        ClarityModules.onHit(e);
        lastReach = Math.sqrt(e.getBoundingBox().distanceToSqr(p.getEyePosition()));
        long now = System.currentTimeMillis();
        combo = now - lastHitMs <= 2000 ? combo + 1 : 1;
        lastHitMs = now;
        if (e instanceof LivingEntity le) {
            target = le;
            targetMs = now;
        }
    }

    public static float zoomFactor() {
        boolean on = ZOOM.enabled && AuroraClient.zoomKey != null && AuroraClient.zoomKey.isDown() && mc().screen == null;
        if (!on) zoomScroll = 1;
        float targetZoom = on ? 1f / ((float) ZOOM.opt("factor", 4) * zoomScroll) : 1f;
        boolean cine = on && ZOOM.flag("cinematic", false);
        if (cine != zoomCinematic) {
            zoomCinematic = cine;
            mc().options.smoothCamera = cine;
        }
        zoomAnim += (targetZoom - zoomAnim) * (ZOOM.flag("smooth", true) ? 0.25f : 1f);
        if (Math.abs(zoomAnim - targetZoom) < 0.002f) zoomAnim = targetZoom;
        return zoomAnim;
    }

    /** Fullbright lives in the lightmap (see FullbrightMixin): the gamma option stays untouched. */
    public static void applyFullbright() {
    }

    /** 0..1 strength applied to the lightmap, 0 when off. */
    public static float fullbright() {
        return FULLBRIGHT.enabled ? (float) Math.min(1, FULLBRIGHT.opt("level", 15) / 15.0) : 0;
    }

    /** Remet le gamma normal avant que options.txt soit sauvegardé à la fermeture. */
    public static void restoreGamma() {
        if (savedGamma >= 0) mc().options.gamma().set(savedGamma);
    }

    public static void syncVanillaOptions() {
        if (TOGGLE_SNEAK.enabled) mc().options.toggleCrouch().set(true);
    }

    /** Vignette rouge quand la vie est basse. */
    public static void renderLowHp(GuiGraphics g) {
        LocalPlayer p = player();
        if (!LOW_HP.enabled || p == null || p.isCreative() || p.isSpectator() || p.getHealth() > LOW_HP.opt("hearts", 3) * 2) return;
        float pulse = LOW_HP.flag("pulse", true) ? 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 180.0) : 0.7f;
        int a = Math.min(255, (int) ((60 + 70 * pulse) * LOW_HP.opt("alpha", 0.5) * 2));
        int rgb = (int) LOW_HP.opt("color", 0xC01818);
        int edge = (a << 24) | rgb;
        int w = g.guiWidth(), h = g.guiHeight(), d = Math.max(20, h / 6);
        g.fillGradient(0, 0, w, d, edge, rgb);
        g.fillGradient(0, h - d, w, h, rgb, edge);
    }

    // ---------- utilitaires ----------

    private static int cps(Deque<Long> clicks) {
        long limit = System.currentTimeMillis() - 1000;
        while (!clicks.isEmpty() && clicks.peekFirst() < limit) clicks.removeFirst();
        return clicks.size();
    }

    private static int countItem(LocalPlayer p, net.minecraft.world.item.Item item) {
        int n = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.is(item)) n += st.getCount();
        }
        return n;
    }

    private static String formatTicks(int ticks) {
        int s = ticks / 20;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static List<HudModule> hudCache;
    private static int hudCacheSize = -1;

    /** Widgets du HUD (liste mise en cache : appelée à chaque image). */
    public static List<HudModule> hudModules() {
        if (hudCache == null || hudCacheSize != ALL.size()) {
            List<HudModule> out = new ArrayList<>();
            for (Module m : ALL) if (m instanceof HudModule h) out.add(h);
            hudCache = java.util.Collections.unmodifiableList(out);
            hudCacheSize = ALL.size();
        }
        return hudCache;
    }

    public static List<Module> of(Category c) {
        ModuleGroups.apply();
        List<Module> out = new ArrayList<>();
        for (Module m : ALL) if (m.category == c) out.add(m);
        // rangés par groupe (ordre défini), puis dans l'ordre du groupe
        out.sort(java.util.Comparator.<Module>comparingInt(m -> m.groupOrder).thenComparing(m -> m.name.toLowerCase(Locale.ROOT)));
        return out;
    }
}
