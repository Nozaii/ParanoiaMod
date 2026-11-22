package gg.aurora.module;

import gg.aurora.ui.Icons;
import gg.aurora.ui.Rows;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shield Fix : le bouclier n'apparaît levé (1re et 3e personne) que lorsqu'il bloque vraiment, après
 * le délai de levée — ce que tu vois correspond à ce que calcule le serveur.
 * Shield Status : état de ton bouclier (prêt, levée, bloque, cassé + temps restant) et de celui de ta cible.
 */
public final class ShieldModules {
    private ShieldModules() {}

    public static final Module FIX = Modules.register(new Module("shield_fix", "Shield Fix", Category.PVP, Icons.SHIELD, false))
            .withSettings(() -> List.of(
                    new Rows.Toggle("Real raise", "Shield raised only while it blocks", () -> ShieldModules.FIX.flag("real", true),
                            v -> ShieldModules.FIX.set("real", v)),
                    new Rows.Toggle("Other players too", "Shows when the opponent really blocks", () -> ShieldModules.FIX.flag("others", true),
                            v -> ShieldModules.FIX.set("others", v))));

    public static final HudModule STATUS = Modules.register(new HudModule("shield_status", "Shield Status", Category.PVP, Icons.SHIELD,
            false, 0.5f, 0.58f, ShieldModules::lines))
            .withSettings(() -> List.of(
                    new Rows.Toggle("Color the shield", "Green raised, red broken, yellow raising", () -> ShieldModules.STATUS.flag("tint", true),
                            v -> ShieldModules.STATUS.set("tint", v)),
                    new Rows.Toggle("Other players' shields", "Color on the opponent too", () -> ShieldModules.STATUS.flag("others", true),
                            v -> ShieldModules.STATUS.set("others", v)),
                    new Rows.ColorRow("Raised (blocking)", () -> (int) ShieldModules.STATUS.opt("up", 0x3DDC84), v -> ShieldModules.STATUS.set("up", v & 0xFFFFFF)),
                    new Rows.ColorRow("Raising", () -> (int) ShieldModules.STATUS.opt("raising", 0xFFC23D),
                            v -> ShieldModules.STATUS.set("raising", v & 0xFFFFFF)),
                    new Rows.ColorRow("Broken (axe)", () -> (int) ShieldModules.STATUS.opt("broken", 0xFF3B3B),
                            v -> ShieldModules.STATUS.set("broken", v & 0xFFFFFF)),
                    new Rows.Slider("Intensity", null, 0.2, 1, 0.05, () -> ShieldModules.STATUS.opt("strength", 0.75),
                            v -> ShieldModules.STATUS.set("strength", v), v -> Math.round(v * 100) + " %"),
                    new Rows.Toggle("Text on the HUD", "Status and time left", () -> ShieldModules.STATUS.flag("text", true),
                            v -> ShieldModules.STATUS.set("text", v))));

    /** Design du bouclier en cours de rendu (texture), null = vanilla. */
    public static net.minecraft.resources.Identifier renderDesign;

    /** Design exclusif porté par ce joueur (bouclier tenu), ou null. */
    public static net.minecraft.resources.Identifier designFor(LivingEntity e, ItemStack stack) {
        if (stack == null || !stack.has(DataComponents.BLOCKS_ATTACKS) || !(e instanceof Player p)) return null;
        var item = gg.aurora.cosmetic.Cosmetics.worn(p, gg.aurora.cosmetic.Cosmetics.Type.SHIELD);
        return item == null ? null : net.minecraft.resources.Identifier.fromNamespaceAndPath("aurora", "textures/cosmetic/shield_model_" + item.id() + ".png");
    }

    /** Teinte du bouclier en cours de rendu (ARGB), -1 = couleur normale. */
    public static int renderTint = -1;

    /** Couleur du bouclier tenu par cette entité selon son état, -1 si aucune. */
    public static int tintFor(LivingEntity e, ItemStack stack) {
        if (!STATUS.enabled || !STATUS.flag("tint", true) || stack == null || !stack.has(DataComponents.BLOCKS_ATTACKS)) return -1;
        Minecraft mc = Minecraft.getInstance();
        int rgb;
        if (e == mc.player) {
            LocalPlayer p = mc.player;
            if (p.getCooldowns().isOnCooldown(stack)) rgb = (int) STATUS.opt("broken", 0xFF3B3B);
            else if (p.isBlocking()) rgb = (int) STATUS.opt("up", 0x3DDC84);
            else if (p.isUsingItem() && p.getUseItem() == stack) rgb = (int) STATUS.opt("raising", 0xFFC23D);
            else return -1;
        } else {
            if (!STATUS.flag("others", true)) return -1;
            if (ENEMY_DISABLED.containsKey(e.getId())) rgb = (int) STATUS.opt("broken", 0xFF3B3B);
            else if (e.isBlocking()) rgb = (int) STATUS.opt("up", 0x3DDC84);
            else if (e.isUsingItem()) rgb = (int) STATUS.opt("raising", 0xFFC23D);
            else return -1;
        }
        float k = (float) STATUS.opt("strength", 0.75);
        int r = Math.round(255 + (((rgb >> 16) & 255) - 255) * k), g = Math.round(255 + (((rgb >> 8) & 255) - 255) * k),
                b = Math.round(255 + ((rgb & 255) - 255) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Durée réelle des cooldowns d'objets (ticks), capturée à leur création. */
    private static final Map<Identifier, int[]> COOLDOWNS = new java.util.concurrent.ConcurrentHashMap<>();
    /** Boucliers adverses désactivés : id d'entité → tick de fin. */
    private static final Map<Integer, Long> ENEMY_DISABLED = new HashMap<>();
    private static int pendingId = -1;
    private static long pendingAt, clock;
    private static float pendingSeconds;

    /** Force le chargement (enregistrement des modules). */
    public static void init() {
        STATUS.live = true;
    }

    public static void onCooldown(Identifier group, int ticks) {
        COOLDOWNS.put(group, new int[]{ticks, (int) clock});
    }

    /** Coup porté : un adversaire qui bloque frappé à la hache aura son bouclier désactivé. */
    /** The server says this shield just broke: no need to wait and guess from the lowered shield. */
    public static void onShieldBroken(Entity e) {
        float secs = pendingId == e.getId() ? pendingSeconds : 5;
        ENEMY_DISABLED.put(e.getId(), clock + Math.round(secs * 20));
        if (pendingId == e.getId()) pendingId = -1;
    }

    public static void onAttack(LocalPlayer p, Entity e) {
        if (!(e instanceof LivingEntity le) || !le.isBlocking()) return;
        float secs = p.getSecondsToDisableBlocking();
        if (secs <= 0) return;
        pendingId = e.getId();
        pendingAt = clock;
        pendingSeconds = secs;
    }

    public static void tick(Minecraft mc) {
        clock++;
        if (mc.level == null) {
            ENEMY_DISABLED.clear();
            return;
        }
        if (pendingId >= 0 && clock - pendingAt >= 1) {
            Entity e = mc.level.getEntity(pendingId);
            // bouclier baissé juste après le coup : il a été désactivé
            if (e instanceof LivingEntity le && !le.isBlocking()) ENEMY_DISABLED.put(pendingId, clock + Math.round(pendingSeconds * 20));
            pendingId = -1;
        }
        ENEMY_DISABLED.values().removeIf(end -> end < clock);
    }

    /** Shield Fix : ce bouclier doit-il être affiché levé ? */
    public static boolean showRaised(LivingEntity e) {
        if (!FIX.enabled || !FIX.flag("real", true)) return true;
        if (!(e instanceof LocalPlayer) && !FIX.flag("others", true)) return true;
        return e.isBlocking();
    }

    private static ItemStack shield(Player p) {
        if (p.getOffhandItem().has(DataComponents.BLOCKS_ATTACKS)) return p.getOffhandItem();
        if (p.getMainHandItem().has(DataComponents.BLOCKS_ATTACKS)) return p.getMainHandItem();
        return ItemStack.EMPTY;
    }

    private static List<String> lines() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || !STATUS.flag("text", true)) return null;
        List<String> out = new ArrayList<>(2);
        ItemStack s = shield(p);
        if (!s.isEmpty()) {
            String state;
            if (p.getCooldowns().isOnCooldown(s)) {
                float pct = p.getCooldowns().getCooldownPercent(s, 0);
                int[] cd = COOLDOWNS.get(p.getCooldowns().getCooldownGroup(s));
                float total = cd != null ? cd[0] / 20f : 5f;
                state = String.format(Locale.ROOT, "Broken %.1fs", pct * total);
            } else if (p.isBlocking()) {
                state = "Blocking";
            } else if (p.isUsingItem() && p.getUseItem() == s) {
                state = "Raising…";
            } else {
                state = "Ready";
            }
            out.add("Shield|" + state);
        }
        if (mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof LivingEntity t) {
            Long end = ENEMY_DISABLED.get(t.getId());
            if (end != null) out.add(String.format(Locale.ROOT, "Target|Broken %.1fs", (end - clock) / 20f));
            else if (t.isBlocking()) out.add("Target|Blocking");
        }
        return out.isEmpty() ? null : out;
    }
}
