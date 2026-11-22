package gg.aurora.module;

import gg.aurora.mixin.MinecraftAccessor;
import gg.aurora.ui.Icons;
import gg.aurora.ui.Rows;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Optimiseurs PvP côté client : ils n'attendent plus la réponse du serveur pour afficher le
 * résultat d'une action (comme Marlow's Crystal Optimizer, Consumable Optimizer, Anchor Optimizer).
 * Le serveur reste maître : en cas de désaccord il corrige l'état au paquet suivant.
 */
public final class PvpOptimizers {
    private PvpOptimizers() {}

    public static final Module CRYSTAL = Modules.register(new Module("crystal_optimizer", "Crystal Optimizer", Category.PVP, Icons.GEM, false))
            .withSettings(() -> List.of(
                    new Rows.Toggle("Ignore with Weakness", "Keeps the crystal if you can't break it",
                            () -> PvpOptimizers.CRYSTAL.flag("weakness", true), v -> PvpOptimizers.CRYSTAL.set("weakness", v)),
                    new Rows.Toggle("Instant placement", "Shows the crystal on your side before the server confirms it",
                            () -> PvpOptimizers.CRYSTAL.flag("ghost", false), v -> PvpOptimizers.CRYSTAL.set("ghost", v))));

    /** Client side crystal waiting for the real one: entity and expiry tick. */
    private static final java.util.Map<EndCrystal, Integer> GHOSTS = new java.util.HashMap<>();
    private static int nextGhostId = -2_000_000;

    private static void placeGhost(Level level, BlockPos base) {
        if (!(level instanceof net.minecraft.client.multiplayer.ClientLevel cl)) return;
        BlockState st = level.getBlockState(base);
        BlockPos up = base.above();
        if (!(st.is(Blocks.OBSIDIAN) || st.is(Blocks.BEDROCK)) || !level.getBlockState(up).isAir()) return;
        var box = new net.minecraft.world.phys.AABB(up).expandTowards(0, 1, 0);
        if (!level.getEntities((Entity) null, box, e -> true).isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            EndCrystal c = new EndCrystal(cl, up.getX() + 0.5, up.getY(), up.getZ() + 0.5);
            c.setShowBottom(false);
            c.setId(nextGhostId--);
            cl.addEntity(c);
            GHOSTS.put(c, mc.player == null ? 0 : mc.player.tickCount + 12);
        });
    }

    /** Ghost goes away when the real crystal arrives, or after 12 ticks if the server refused it. */
    private static void tickGhosts(Minecraft mc) {
        if (GHOSTS.isEmpty()) return;
        var it = GHOSTS.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            EndCrystal c = e.getKey();
            boolean real = mc.level != null && !mc.level.getEntitiesOfClass(EndCrystal.class, c.getBoundingBox().inflate(0.3), x -> x.getId() > 0).isEmpty();
            if (mc.level == null || mc.player == null || real || mc.player.tickCount >= e.getValue() || c.level() != mc.level) {
                if (mc.level != null && c.level() == mc.level) mc.level.removeEntity(c.getId(), Entity.RemovalReason.DISCARDED);
                it.remove();
            }
        }
    }

    public static final Module GAP = Modules.register(new Module("gap_optimizer", "Gap Optimizer", Category.PVP, Icons.TOTEM, false))
            .withSettings(() -> List.of(
                    new Rows.Toggle("Potions too", "Also chains drinkable potions", () -> PvpOptimizers.GAP.flag("potions", true),
                            v -> PvpOptimizers.GAP.set("potions", v))));

    public static final Module CONSUMABLE = Modules.register(new Module("consumable_optimizer", "Consumable Optimizer", Category.PVP, Icons.TOTEM, false))
            .withSettings(() -> List.of(
                    new Rows.Toggle("Block server restarts", "Avoids eating again when the server lags",
                            () -> PvpOptimizers.CONSUMABLE.flag("recall", true), v -> PvpOptimizers.CONSUMABLE.set("recall", v))));

    /** Tick (joueur) de la dernière consommation terminée côté client, -1 = aucune en attente du serveur. */
    private static int localDoneTick = -1;

    public static boolean consumeLocally(net.minecraft.world.item.ItemStack st) {
        return CONSUMABLE.enabled && !st.isEmpty() && st.has(net.minecraft.core.component.DataComponents.CONSUMABLE) && !st.useOnRelease();
    }

    public static void onLocalConsume(LocalPlayer p) {
        localDoneTick = p.tickCount;
    }

    /** Fenêtre d'attente du serveur : ping + marge, en ticks. */
    private static int window(LocalPlayer p) {
        var con = Minecraft.getInstance().getConnection();
        var info = con == null ? null : con.getPlayerInfo(p.getUUID());
        int ping = info == null ? 100 : info.getLatency();
        return Math.min(40, 3 + ping / 50);
    }

    public static boolean suppressRecall(LocalPlayer p) {
        return CONSUMABLE.enabled && CONSUMABLE.flag("recall", true) && localDoneTick >= 0 && p.tickCount - localDoneTick <= window(p);
    }

    public static final Module ANCHOR = Modules.register(new Module("anchor_optimizer", "Anchor Optimizer", Category.PVP, Icons.SUN, false))
            .withSettings(() -> List.of(
                    new Rows.Toggle("Instant charge", "Shows the glowstone charge without waiting", () -> PvpOptimizers.ANCHOR.flag("charge", true),
                            v -> PvpOptimizers.ANCHOR.set("charge", v)),
                    new Rows.Toggle("Instant explosion", "Removes the anchor as soon as you trigger it", () -> PvpOptimizers.ANCHOR.flag("explode", true),
                            v -> PvpOptimizers.ANCHOR.set("explode", v))));

    public static final Module PROJECTILES = Modules.register(new Module("projectile_optimizer", "Projectile Optimizer", Category.PVP, Icons.SWORD, false))
            .withSettings(() -> List.of(
                    new Rows.Toggle("Hide arrows on the ground", null, () -> PvpOptimizers.PROJECTILES.flag("ground", true),
                            v -> PvpOptimizers.PROJECTILES.set("ground", v)),
                    new Rows.Toggle("Hide stuck arrows", "The ones stuck in players", () -> PvpOptimizers.PROJECTILES.flag("stuck", true),
                            v -> PvpOptimizers.PROJECTILES.set("stuck", v)),
                    new Rows.Toggle("Hide XP orbs", null, () -> PvpOptimizers.PROJECTILES.flag("xp", false),
                            v -> PvpOptimizers.PROJECTILES.set("xp", v))));

    private static boolean wasUsing;
    private static ItemUseAnimation lastAnim;

    public static void init() {
        ShieldModules.init();
        ExtraModules.init();
        CombatModules.init();
        VisualModules.init();
        MusicModule.touch();
        AmbientFx.init();
        ClientModules.init();
        ClarityModules.init();
        AutoGG.MODULE.toString();
        NewModules.init();
        ClientTools.init();
        Freelook.FREELOOK.toString();
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() && ANCHOR.enabled) anchor(player.getItemInHand(hand).is(Items.GLOWSTONE), level, hit.getBlockPos());
            if (level.isClientSide() && CRYSTAL.enabled && CRYSTAL.flag("ghost", false) && player.getItemInHand(hand).is(Items.END_CRYSTAL)) {
                placeGhost(level, hit.getBlockPos());
            }
            return InteractionResult.PASS;
        });
    }

    /** Coup sur un cristal : retiré tout de suite côté client. */
    public static void onAttack(LocalPlayer p, Entity e) {
        if (!CRYSTAL.enabled || !(e instanceof EndCrystal)) return;
        if (CRYSTAL.flag("weakness", true) && p.hasEffect(MobEffects.WEAKNESS) && !p.hasEffect(MobEffects.STRENGTH)) return;
        Minecraft mc = Minecraft.getInstance();
        int id = e.getId();
        mc.execute(() -> {
            if (mc.level != null) mc.level.removeEntity(id, Entity.RemovalReason.KILLED);
        });
    }

    private static void anchor(boolean glowstone, Level level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (!st.is(Blocks.RESPAWN_ANCHOR)) return;
        int charge = st.getValue(RespawnAnchorBlock.CHARGE);
        Minecraft mc = Minecraft.getInstance();
        if (glowstone && charge < RespawnAnchorBlock.MAX_CHARGES && ANCHOR.flag("charge", true)) {
            mc.execute(() -> level.setBlock(pos, st.setValue(RespawnAnchorBlock.CHARGE, charge + 1), 0));
        } else if (!glowstone && charge > 0 && ANCHOR.flag("explode", true) && level.dimension() != Level.NETHER) {
            mc.execute(() -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), 0));
        }
    }

    /** Gap Optimizer : supprime l'attente de 4 ticks entre deux consommations. */
    public static void tick(Minecraft mc) {
        tickGhosts(mc);
        ClientTools.tick();
        LocalPlayer p = mc.player;
        if (p == null) return;
        if (localDoneTick >= 0) {
            // consommation finie chez nous mais pas encore chez le serveur : pas de nouvelle tout de suite,
            // sinon le serveur la recommencerait au lieu de finir la première
            if (p.tickCount - localDoneTick > window(p)) localDoneTick = -1;
            else if (((MinecraftAccessor) mc).aurora$getRightClickDelay() < 1) ((MinecraftAccessor) mc).aurora$setRightClickDelay(1);
        }
        boolean using = p.isUsingItem();
        if (using) lastAnim = p.getUseItem().getUseAnimation();
        if (GAP.enabled && wasUsing && !using && mc.options.keyUse.isDown()
                && (lastAnim == ItemUseAnimation.EAT || lastAnim == ItemUseAnimation.DRINK && GAP.flag("potions", true))) {
            ((MinecraftAccessor) mc).aurora$setRightClickDelay(0);
        }
        wasUsing = using;
    }

    public static boolean hideStuckArrows() {
        return PROJECTILES.enabled && PROJECTILES.flag("stuck", true);
    }

    public static boolean hideGroundArrows() {
        return PROJECTILES.enabled && PROJECTILES.flag("ground", true);
    }

    public static boolean hideXp() {
        return PROJECTILES.enabled && PROJECTILES.flag("xp", false);
    }
}
