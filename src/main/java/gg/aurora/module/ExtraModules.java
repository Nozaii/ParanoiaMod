package gg.aurora.module;

import gg.aurora.Friends;
import gg.aurora.ui.Icons;
import gg.aurora.ui.Rows;
import gg.aurora.ui.Toasts;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Modules supplémentaires : PvP, HUD, Visuals, Player, Misc. */
public final class ExtraModules {
    private ExtraModules() {}

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    // ===================================================================== PvP

    public static final Module FRIEND_GLOW = Modules.register(new Module("friend_glow", "Friend Glow", Category.PVP, Icons.FRIENDS, false))
            .withSettings(() -> List.of(
                    new Rows.ColorRow("Outline color", () -> (int) ExtraModules.FRIEND_GLOW.opt("color", 0x8CFFA0),
                            v -> ExtraModules.FRIEND_GLOW.set("color", v & 0xFFFFFF))));

    public static final Module TARGET_INFO = Modules.register(new Module("target_info", "Target Info", Category.PVP, Icons.TARGET, false))
            .withSettings(() -> List.of(
                    new Rows.Toggle("Vie", null, () -> ExtraModules.TARGET_INFO.flag("hp", true), v -> ExtraModules.TARGET_INFO.set("hp", v)),
                    new Rows.Toggle("Distance", null, () -> ExtraModules.TARGET_INFO.flag("dist", true), v -> ExtraModules.TARGET_INFO.set("dist", v)),
                    new Rows.Toggle("Ping", null, () -> ExtraModules.TARGET_INFO.flag("ping", true), v -> ExtraModules.TARGET_INFO.set("ping", v))));

    public static final Module TOTEM_POPS = Modules.register(new Module("totem_pops", "Totem Pop Counter", Category.PVP, Icons.TOTEM, false));


    // ===================================================================== HUD

    public static final HudModule WATERMARK = Modules.register(new HudModule("watermark", "Watermark", Icons.EYE_LOGO, false, 0.5f, 0.01f,
            () -> {
                Minecraft mc = mc();
                String server = mc.getCurrentServer() != null ? mc.getCurrentServer().ip : mc.hasSingleplayerServer() ? "Singleplayer" : "Menu";
                return List.of("Paranoia|" + server + "  ·  " + mc.getFps() + " fps  ·  " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));
            }));

    private static final String[] DIRS = {"S", "SO", "O", "NO", "N", "NE", "E", "SE"};

    public static final HudModule COMPASS = Modules.register(new HudModule("compass", "Direction", Icons.GLOBE, false, 0.5f, 0.06f,
            () -> {
                LocalPlayer p = mc().player;
                if (p == null) return null;
                float yaw = ((p.getYRot() % 360) + 360) % 360;
                return List.of("Cap|" + DIRS[Math.round(yaw / 45f) % 8] + "  " + Math.round(yaw) + "°");
            }));

    public static final HudModule BIOME = Modules.register(new HudModule("biome", "Biome", Icons.SUN, false, 0.01f, 0.26f,
            () -> {
                Minecraft mc = mc();
                if (mc.player == null || mc.level == null) return null;
                return mc.level.getBiome(mc.player.blockPosition()).unwrapKey()
                        .map(k -> List.of("Biome|" + pretty(k.identifier().getPath()))).orElse(null);
            }));

    public static final HudModule ARROWS = Modules.register(new HudModule("arrows", "Arrows", Icons.SWORD, false, 0.99f, 0.68f,
            () -> {
                LocalPlayer p = mc().player;
                if (p == null) return null;
                int n = 0;
                var inv = p.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack st = inv.getItem(i);
                    if (st.is(Items.ARROW) || st.is(Items.SPECTRAL_ARROW) || st.is(Items.TIPPED_ARROW)) n += st.getCount();
                }
                return n == 0 ? null : List.of("Arrows|" + n);
            }));

    private static final long START = System.currentTimeMillis();

    public static final HudModule SESSION = Modules.register(new HudModule("session", "Session", Icons.CLOCK, false, 0.01f, 0.58f,
            () -> {
                long s = (System.currentTimeMillis() - START) / 1000;
                return List.of(String.format(Locale.ROOT, "Session|%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60));
            }));

    public static final HudModule LIGHT = Modules.register(new HudModule("light_level", "Light", Icons.SUN, false, 0.01f, 0.64f,
            () -> {
                Minecraft mc = mc();
                if (mc.player == null || mc.level == null) return null;
                BlockPos pos = mc.player.blockPosition();
                int block = mc.level.getBrightness(LightLayer.BLOCK, pos);
                return List.of("Light|" + block + (block < 1 ? "  (spawn possible)" : ""));
            }));

    public static final HudModule POPS_HUD = Modules.register(new HudModule("pops_hud", "Totem Pops", Category.PVP, Icons.TOTEM, false, 0.99f, 0.50f,
            () -> {
                if (ExtraModules.POPS.isEmpty()) return null;
                Minecraft mc = mc();
                List<String> out = new java.util.ArrayList<>();
                ExtraModules.POPS.forEach((id, n) -> {
                    Player p = mc.level == null ? null : mc.level.getPlayerByUUID(id);
                    if (p != null) out.add(p.getGameProfile().name() + "|" + n + " pop" + (n > 1 ? "s" : ""));
                });
                return out.isEmpty() ? null : out;
            }));

    // ===================================================================== Visuals

    public static final Module LOW_FIRE = Modules.register(new Module("low_fire", "Low Fire", Category.VISUALS, Icons.SUN, false))
            .withSettings(() -> List.of(
                    new Rows.Choice("Fire on screen", new String[]{"Low", "Hidden"}, () -> (int) ExtraModules.LOW_FIRE.opt("mode", 0),
                            i -> ExtraModules.LOW_FIRE.set("mode", i))));

    public static final Module NO_TOTEM_ANIM = Modules.register(new Module("no_totem_anim", "No Totem Animation", Category.VISUALS, Icons.TOTEM, false));

    public static final Module HIT_EFFECTS = Modules.register(new Module("hit_effects", "Hit Effects", Category.VISUALS, Icons.SPARKLES, false))
            .withSettings(() -> List.of(
                    new Rows.ColorRow("Color", () -> (int) ExtraModules.HIT_EFFECTS.opt("color", 0x8CD8FF),
                            v -> ExtraModules.HIT_EFFECTS.set("color", v & 0xFFFFFF))));

    public static final Module HOLOGRAM = Modules.register(new Module("hologram", "Hologram Skin", Category.VISUALS, Icons.PERSON, false))
            .withSettings(() -> List.of(
                    new Rows.ColorRow("Tint", () -> (int) ExtraModules.HOLOGRAM.opt("color", 0x4FA8FF),
                            v -> ExtraModules.HOLOGRAM.set("color", v & 0xFFFFFF)),
                    new Rows.Toggle("Other players too", null, () -> ExtraModules.HOLOGRAM.flag("others", false),
                            v -> ExtraModules.HOLOGRAM.set("others", v))));

    public static final Module BATS = Modules.register(new Module("bats", "Halloween Bats", Category.VISUALS, Icons.SKULL, false));

    // ===================================================================== Player

    public static final Module AUTO_RESPAWN = Modules.register(new Module("auto_respawn", "Auto Respawn", Category.PLAYER, Icons.HEART, false));

    public static final Module DROP_PROTECT = Modules.register(new Module("drop_protect", "Drop Protection", Category.PLAYER, Icons.LOCK, true))
            .withSettings(() -> List.of(
                    new Rows.Choice("Protected items", new String[]{"Rare", "Rare + enchanted", "All but blocks"},
                            () -> (int) ExtraModules.DROP_PROTECT.opt("level", 0), i -> ExtraModules.DROP_PROTECT.set("level", i)),
                    new Rows.Info("Rare", "Totem, elytra, Notch apple, mace, trident, netherite"),
                    new Rows.Choice("To drop", new String[]{"Press twice", "Sneaking", "Both"},
                            () -> (int) ExtraModules.DROP_PROTECT.opt("unlock", 0), i -> ExtraModules.DROP_PROTECT.set("unlock", i)),
                    new Rows.Slider("Double press delay", null, 0.2, 1.0, 0.05, () -> ExtraModules.DROP_PROTECT.opt("window", 0.45),
                            v -> ExtraModules.DROP_PROTECT.set("window", v), v -> Math.round(v * 1000) + " ms"),
                    new Rows.Toggle("Full stack only", "Q drops one item normally, only Ctrl+Q is protected",
                            () -> ExtraModules.DROP_PROTECT.flag("stackOnly", false), v -> ExtraModules.DROP_PROTECT.set("stackOnly", v)),
                    new Rows.Toggle("Quiet message", "Small text above the hotbar instead of a notification",
                            () -> ExtraModules.DROP_PROTECT.flag("quiet", true), v -> ExtraModules.DROP_PROTECT.set("quiet", v))));

    private static long lastDropTry;
    private static int lastDropSlot = -1;

    /**
     * Drop Protection : vrai si le lâcher doit être bloqué. Premier appui bloqué (message court),
     * second appui rapide sur le même objet = lâché. Ou accroupi, selon le réglage.
     */
    public static boolean blockDrop(net.minecraft.client.player.LocalPlayer p, boolean fullStack) {
        ItemStack s = p.getMainHandItem();
        if (!protectedItem(s)) return false;
        if (DROP_PROTECT.flag("stackOnly", false) && !fullStack) return false;
        int unlock = (int) DROP_PROTECT.opt("unlock", 0);
        if ((unlock == 1 || unlock == 2) && p.isShiftKeyDown()) return false;
        long now = System.currentTimeMillis();
        int slot = p.getInventory().getSelectedSlot();
        if ((unlock == 0 || unlock == 2) && slot == lastDropSlot && now - lastDropTry < DROP_PROTECT.opt("window", 0.45) * 1000) {
            lastDropTry = 0;
            return false;
        }
        boolean repeat = now - lastDropTry < 1500 && slot == lastDropSlot;
        lastDropTry = now;
        lastDropSlot = slot;
        if (!repeat) {
            String how = unlock == 1 ? "Sneak to drop" : "Press again to drop";
            if (DROP_PROTECT.flag("quiet", true)) {
                mc().gui.setOverlayMessage(Component.literal(how + " : " + s.getHoverName().getString()).withStyle(ChatFormatting.GRAY), false);
            } else {
                gg.aurora.ui.Toasts.push(Icons.LOCK, "Drop Protection", how, false);
            }
        }
        return true;
    }

    // ===================================================================== Misc

    public static final Module DEATH_COORDS = Modules.register(new Module("death_coords", "Death Coords", Category.MISC, Icons.SKULL, true));


    public static final Module FPS_CAP_MENU = Modules.register(new Module("menu_fps", "Menu FPS Cap", Category.MISC, Icons.GAUGE, false))
            .withSettings(() -> List.of(
                    new Rows.Slider("FPS in menus", "Saves the GPU outside the game", 30, 144, 1, () -> ExtraModules.FPS_CAP_MENU.opt("fps", 60),
                            v -> ExtraModules.FPS_CAP_MENU.set("fps", v), v -> Math.round(v) + " fps")));

    // ===================================================================== logique

    private static final Map<UUID, Integer> POPS = new HashMap<>();
    /** Dev uniquement (-Daurora.devworld) : se traiter soi-même comme un ami pour voir le halo. */
    public static boolean DEV_SELF_FRIEND;
    private static final Set<String> ALERTED = new HashSet<>();
    private static boolean wasDead;
    private static int deadTicks;

    public static void init() {
    }

    private static String pretty(String path) {
        String[] w = path.split("_");
        StringBuilder b = new StringBuilder();
        for (String s : w) if (!s.isEmpty()) b.append(Character.toUpperCase(s.charAt(0))).append(s.substring(1)).append(' ');
        return b.toString().trim();
    }

    /** Objet précieux à ne pas lâcher par erreur. */
    public static boolean protectedItem(ItemStack s) {
        if (!DROP_PROTECT.enabled || s.isEmpty()) return false;
        int level = (int) DROP_PROTECT.opt("level", 0);
        if (level == 2) return !(s.getItem() instanceof net.minecraft.world.item.BlockItem);
        boolean rare = s.is(Items.TOTEM_OF_UNDYING) || s.is(Items.ELYTRA) || s.is(Items.ENCHANTED_GOLDEN_APPLE) || s.is(Items.MACE)
                || s.is(Items.TRIDENT) || s.isDamageableItem() && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().startsWith("netherite_");
        return rare || level == 1 && s.isEnchanted();
    }

    /** Événement d'entité 35 = totem utilisé. */
    public static void onTotemPop(Entity e) {
        if (!(e instanceof Player p) || !TOTEM_POPS.enabled) return;
        int n = POPS.merge(p.getUUID(), 1, Integer::sum);
        Minecraft mc = mc();
        String who = p == mc.player ? "Tu as" : p.getGameProfile().name() + " a";
        mc.gui.getChat().addMessage(Component.literal("[Paranoia] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal(who + " used a totem (" + n + ")").withStyle(ChatFormatting.GRAY)));
    }

    /** Friend Glow : contour vert clair autour des amis. */
    public static boolean friendGlow(Entity e) {
        return FRIEND_GLOW.enabled && e instanceof Player p && (p != mc().player && Friends.isFriend(p.getUUID()) || DEV_SELF_FRIEND && p == mc().player);
    }

    /** Contour vanilla à travers les murs : retiré (fair-play), l'enveloppe reste cachée par les blocs. */
    public static boolean friendOutline(Entity e) {
        return false;
    }

    public static int friendGlowColor() {
        return (int) FRIEND_GLOW.opt("color", 0x8CFFA0);
    }

    /** Target Info : « 20 HP · 3m · 12ms » ajouté au nametag des joueurs. */
    public static Component targetInfo(Entity e, Component name) {
        if (!TARGET_INFO.enabled || !(e instanceof Player p) || p == mc().player || name == null) return name;
        StringBuilder b = new StringBuilder();
        if (TARGET_INFO.flag("hp", true)) b.append(Math.round(p.getHealth() + p.getAbsorptionAmount())).append(" HP");
        if (TARGET_INFO.flag("dist", true) && mc().player != null)
            b.append(b.isEmpty() ? "" : " · ").append(Math.round(mc().player.distanceTo(p))).append("m");
        if (TARGET_INFO.flag("ping", true) && mc().getConnection() != null) {
            var info = mc().getConnection().getPlayerInfo(p.getUUID());
            if (info != null) b.append(b.isEmpty() ? "" : " · ").append(info.getLatency()).append("ms");
        }
        if (b.isEmpty()) return name;
        float hp = p.getHealth() / Math.max(1, p.getMaxHealth());
        ChatFormatting c = hp > 0.6f ? ChatFormatting.GREEN : hp > 0.3f ? ChatFormatting.YELLOW : ChatFormatting.RED;
        return Component.empty().append(name).append(Component.literal("  " + b).withStyle(c));
    }

    public static int menuFps(int normal) {
        Minecraft mc = mc();
        boolean menu = mc.level == null || mc.screen != null && !(mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen);
        if (!FPS_CAP_MENU.enabled || !menu) return normal;
        return Math.min(normal, (int) FPS_CAP_MENU.opt("fps", 60));
    }

    public static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            POPS.clear();
            return;
        }
        boolean dead = p.isDeadOrDying();
        if (dead && !wasDead) {
            POPS.remove(p.getUUID());
            if (DEATH_COORDS.enabled && !ClientModules.hideCoords()) {
                BlockPos b = p.blockPosition();
                mc.gui.getChat().addMessage(Component.literal("[Paranoia] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                        .append(Component.literal("Death at " + b.getX() + " " + b.getY() + " " + b.getZ() + " ("
                                + mc.level.dimension().identifier().getPath() + ")").withStyle(ChatFormatting.GRAY)));
            }
        }
        wasDead = dead;
        // retire les compteurs des joueurs morts
        POPS.keySet().removeIf(id -> {
            Player o = mc.level.getPlayerByUUID(id);
            return o != null && o.isDeadOrDying();
        });
        if (AUTO_RESPAWN.enabled && mc.screen instanceof DeathScreen) {
            if (++deadTicks > 10) {
                p.respawn();
                mc.setScreen(null);
                deadTicks = 0;
            }
        } else {
            deadTicks = 0;
        }
        // Armor Alert : notification quand une piece passe sous le seuil du HUD Armor (reglage dans ce HUD)
        if (Modules.ARMOR.flag("toast", true) && Modules.ARMOR.opt("alert", 15) >= 1 && mc.level.getGameTime() % 20 == 0) {
            double pct = Modules.ARMOR.opt("alert", 15) / 100.0;
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                ItemStack s = p.getItemBySlot(slot);
                String key = slot.getName();
                if (!s.isDamageableItem()) {
                    ALERTED.remove(key);
                    continue;
                }
                double left = (s.getMaxDamage() - s.getDamageValue()) / (double) s.getMaxDamage();
                if (left < pct && ALERTED.add(key)) {
                    Toasts.push(Icons.ARMOR, "Armor almost broken", s.getHoverName().getString() + " : " + Math.round(left * 100) + " %", true);
                } else if (left >= pct) {
                    ALERTED.remove(key);
                }
            }
        }
    }
}
