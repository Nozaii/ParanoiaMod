package gg.aurora;

import gg.aurora.module.Module;
import gg.aurora.module.Modules;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * Outil de dev (-Daurora.devworld=true -Daurora.devprev=true) : capture un aperçu animé de chaque module Visuals.
 * Une scène propre est construite loin du reste du monde de test (« arène » : herbe, ciel dégagé, midi, un mannequin
 * zombie, une mare), le HUD est masqué sauf pour les effets de la main, et chaque module est filmé seul, 6 images.
 * tools/make_previews.py assemble ensuite les bandes animées dans le jar (assets/aurora/textures/preview/<id>.png).
 */
public final class DevPreviews {
    private DevPreviews() {}

    private static final int FIRST = 0, BACK = 1, FRONT = 2;
    /** Centre de l'arène (bloc du sol à Y - 1) : loin de tout, ciel dégagé. */
    private static final int AX = 5000, AY = 120, AZ = 5000;
    private static final String SPOT = (AX + 0.5) + " " + AY + " " + (AZ + 0.5);
    /** Modules montrés avec la main (le HUD reste visible pour garder la main en vue). */
    private static final Set<String> HAND = Set.of("hand_pose", "hand_aura", "old_animations", "swing_animation", "custom_glint", "low_fire",
            "totem_anim", "draw_crosshair", "zoom");

    private record Prev(String id, int cam, float yaw, float pitch, int shotAt, Runnable start, IntConsumer tick, Runnable end) {}

    private static final List<Prev> LIST = new ArrayList<>();
    private static int index = -1, t, setup;
    private static final int PERIOD = 100, FRAMES = 6;

    private static void cmd(Minecraft mc, String c) {
        MinecraftServer s = mc.getSingleplayerServer();
        if (s != null) s.execute(() -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), c));
    }

    private static Prev p(String id, int cam, float yaw, float pitch) {
        return new Prev(id, cam, yaw, pitch, 50, null, null, null);
    }

    private static Prev p(String id, int cam, float yaw, float pitch, int shotAt, Runnable start, IntConsumer tick, Runnable end) {
        return new Prev(id, cam, yaw, pitch, shotAt, start, tick, end);
    }

    /** Arène : forceload, sol d'herbe 41 × 41, air au-dessus, bordure de pierre taillée, quelques blocs pour la profondeur. */
    private static void arena(Minecraft mc) {
        int x0 = AX - 20, x1 = AX + 20, z0 = AZ - 20, z1 = AZ + 20;
        cmd(mc, "forceload add " + x0 + " " + z0 + " " + x1 + " " + z1);
        cmd(mc, "fill " + x0 + " " + (AY - 4) + " " + z0 + " " + x1 + " " + (AY - 2) + " " + z1 + " dirt");
        cmd(mc, "fill " + x0 + " " + (AY - 1) + " " + z0 + " " + x1 + " " + (AY - 1) + " " + z1 + " grass_block");
        cmd(mc, "fill " + x0 + " " + AY + " " + z0 + " " + x1 + " " + (AY + 9) + " " + z1 + " air");
        cmd(mc, "fill " + x0 + " " + (AY + 10) + " " + z0 + " " + x1 + " " + (AY + 24) + " " + z1 + " air");
        // repères au loin (au sud, face à la caméra) : un muret et deux arbres de blocs
        cmd(mc, "fill " + (AX - 6) + " " + AY + " " + (AZ + 14) + " " + (AX + 6) + " " + (AY + 1) + " " + (AZ + 14) + " stone_bricks");
        cmd(mc, "fill " + (AX - 9) + " " + AY + " " + (AZ + 11) + " " + (AX - 9) + " " + (AY + 3) + " " + (AZ + 11) + " oak_log");
        cmd(mc, "fill " + (AX - 10) + " " + (AY + 4) + " " + (AZ + 10) + " " + (AX - 8) + " " + (AY + 5) + " " + (AZ + 12) + " oak_leaves[persistent=true]");
        cmd(mc, "fill " + (AX + 9) + " " + AY + " " + (AZ + 12) + " " + (AX + 9) + " " + (AY + 3) + " " + (AZ + 12) + " birch_log");
        cmd(mc, "fill " + (AX + 8) + " " + (AY + 4) + " " + (AZ + 11) + " " + (AX + 10) + " " + (AY + 5) + " " + (AZ + 13) + " birch_leaves[persistent=true]");
    }

    private static void zombie(Minecraft mc, int dz) {
        cmd(mc, "summon zombie " + (AX + 0.5) + " " + AY + " " + (AZ + dz + 0.5)
                + " {NoAI:1b,PersistenceRequired:1b,Silent:1b,Rotation:[180f,0f],equipment:{head:{id:\"minecraft:iron_helmet\",count:1}}}");
    }

    private static void build(Minecraft mc) {
        Runnable sword = () -> cmd(mc, "item replace entity @p weapon.mainhand with diamond_sword[enchantments={sharpness:1}]");
        Runnable killMobs = () -> cmd(mc, "kill @e[type=!player]");
        Runnable zombieNear = () -> zombie(mc, 3);
        IntConsumer attack = tt -> {
            if (tt == 44 || tt == 52) {
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (e instanceof Zombie z) {
                        mc.gameMode.attack(mc.player, z);
                        mc.player.swing(InteractionHand.MAIN_HAND);
                        Modules.onAttack(mc.player, z);
                        break;
                    }
                }
            }
        };
        Runnable items = () -> {
            for (int i = -1; i <= 1; i++) {
                cmd(mc, "summon item " + (AX + 0.5 + i * 0.8) + " " + AY + " " + (AZ + 2.5) + " {Item:{id:\"" + (i == 0 ? "diamond_sword" : i < 0 ? "golden_apple" : "ender_pearl") + "\",count:1},PickupDelay:32767}");
            }
        };
        // caméra et viseur
        LIST.add(p("draw_crosshair", FIRST, 0, 5));
        LIST.add(p("custom_fov", FIRST, 0, 5));
        LIST.add(p("zoom", FIRST, 0, 2, 50, () -> zombie(mc, 12), tt -> {
            if (tt >= 20 && AuroraClient.zoomKey != null) AuroraClient.zoomKey.setDown(true);
        }, () -> {
            if (AuroraClient.zoomKey != null) AuroraClient.zoomKey.setDown(false);
            killMobs.run();
        }));
        // main et objets
        LIST.add(p("hand_pose", FIRST, 0, 5, 50, sword, null, null));
        LIST.add(p("hand_aura", FIRST, 0, 5, 50, sword, null, null));
        LIST.add(p("old_animations", FIRST, 0, 5, 50, sword, tt -> {
            if (tt >= 20) mc.options.keyUse.setDown(true);
        }, () -> mc.options.keyUse.setDown(false)));
        LIST.add(p("swing_animation", FIRST, 0, 5, 50, sword, tt -> {
            if (tt % 8 == 0 && tt >= 48) mc.player.swing(InteractionHand.MAIN_HAND);
        }, null));
        LIST.add(p("custom_glint", FIRST, 0, 5, 50, sword, null, null));
        LIST.add(p("low_fire", FIRST, 0, 5, 50, () -> {
            cmd(mc, "gamemode survival @p");
            cmd(mc, "effect give @p fire_resistance 1000 0 true");
            cmd(mc, "setblock " + AX + " " + AY + " " + AZ + " fire");
        }, null, () -> {
            cmd(mc, "setblock " + AX + " " + AY + " " + AZ + " air");
            cmd(mc, "extinguish @p");
            cmd(mc, "gamemode creative @p");
        }));
        LIST.add(p("totem_anim", FIRST, 0, 5, 30, null, tt -> {
            if (tt == 28) mc.gameRenderer.displayItemActivation(new ItemStack(Items.TOTEM_OF_UNDYING));
        }, null));
        // ciel et monde
        LIST.add(p("custom_sky", FIRST, 0, -35));
        LIST.add(p("time_changer", FIRST, 0, -20));
        LIST.add(p("weather", FIRST, 0, -15));
        LIST.add(p("clear_water", FIRST, 0, 40, 50, () -> cmd(mc, "fill " + (AX - 3) + " " + (AY - 1) + " " + (AZ + 2) + " " + (AX + 3) + " " + (AY - 2) + " " + (AZ + 7) + " water"),
                null, () -> {
                    cmd(mc, "fill " + (AX - 3) + " " + (AY - 2) + " " + (AZ + 2) + " " + (AX + 3) + " " + (AY - 2) + " " + (AZ + 7) + " dirt");
                    cmd(mc, "fill " + (AX - 3) + " " + (AY - 1) + " " + (AZ + 2) + " " + (AX + 3) + " " + (AY - 1) + " " + (AZ + 7) + " grass_block");
                }));
        LIST.add(p("fullbright", FIRST, 0, 5, 50, () -> cmd(mc, "time set 18000"), null, () -> cmd(mc, "time set 6000")));
        LIST.add(p("no_night_vision", FIRST, 0, 5, 50, () -> {
            cmd(mc, "time set 18000");
            cmd(mc, "effect give @p night_vision 1000 0 true");
        }, null, () -> {
            cmd(mc, "time set 6000");
            cmd(mc, "effect clear @p night_vision");
        }));
        LIST.add(p("block_outline", FIRST, 0, 55, 50, () -> cmd(mc, "setblock " + AX + " " + AY + " " + (AZ + 2) + " gold_block"), null,
                () -> cmd(mc, "setblock " + AX + " " + AY + " " + (AZ + 2) + " air")));
        // effets
        LIST.add(p("shockwaves", BACK, 0, 25, 34, null, tt -> {
            if (tt == 20) cmd(mc, "tp @p " + (AX + 0.5) + " " + (AY + 5) + " " + (AZ + 0.5));
        }, null));
        LIST.add(p("ground_mirror", FRONT, 0, 10));
        LIST.add(p("motion_blur", FIRST, 0, 5, 50, null, tt -> {
            if (tt > 44 && tt < 76) mc.player.setYRot(mc.player.getYRot() + 2.5f);
        }, null));
        LIST.add(p("filters", FIRST, 0, 5));
        LIST.add(p("ambient_particles", FIRST, 0, 0));
        LIST.add(p("item_flames", FIRST, 0, 40, 50, items, null, killMobs));
        LIST.add(p("item_size", FIRST, 0, 40, 50, items, null, killMobs));
        LIST.add(p("item_physic", FIRST, 0, 40, 50, items, null, killMobs));
        LIST.add(p("trail", BACK, 0, 15, 50, null, tt -> {
            if (tt >= 25) mc.options.keyUp.setDown(true);
        }, () -> mc.options.keyUp.setDown(false)));
        LIST.add(p("jump_circles", BACK, 0, 25, 40, null, tt -> mc.options.keyJump.setDown(tt >= 36 && tt <= 38), () -> mc.options.keyJump.setDown(false)));
        LIST.add(p("target_esp", FIRST, 0, 8, 50, zombieNear, attack, killMobs));
        LIST.add(p("hit_effects", FIRST, 0, 8, 44, () -> {
            sword.run();
            zombieNear.run();
        }, attack, killMobs));
        LIST.add(p("hit_color", FIRST, 0, 8, 44, () -> {
            sword.run();
            zombieNear.run();
        }, attack, killMobs));
        LIST.add(p("hitboxes", FIRST, 0, 8, 50, zombieNear, null, killMobs));
        LIST.add(p("hologram", FRONT, 0, 10));
        LIST.add(p("bats", FIRST, 0, -10));
    }

    static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        if (index < 0) {
            if (mc.screen != null) return;
            // préparation en plusieurs ticks : arène, réglages, puis attente du chargement
            setup++;
            if (setup == 1) {
                build(mc);
                cmd(mc, "gamerule doMobSpawning false");
                cmd(mc, "gamerule doMobLoot false");
                cmd(mc, "gamerule doDaylightCycle false");
                cmd(mc, "gamerule doWeatherCycle false");
                cmd(mc, "gamerule sendCommandFeedback false");
                cmd(mc, "gamemode creative @p");
                cmd(mc, "time set 6000");
                cmd(mc, "weather clear");
                cmd(mc, "effect clear @p");
                cmd(mc, "tp @p " + SPOT + " 0 5");
            }
            if (setup == 20) arena(mc);
            if (setup == 25) {
                cmd(mc, "kill @e[type=!player]");
                cmd(mc, "tp @p " + SPOT + " 0 5");
                gg.aurora.DevWorld.setCosmetics(false);
                Settings.cape = "";
            }
            if (setup < 80) return;
            index = 0;
            t = -1;
        }
        t++;
        if (index >= LIST.size()) {
            mc.options.hideGui = false;
            mc.stop();
            return;
        }
        Prev pv = LIST.get(index);
        if (t == 0) {
            for (Module m : Modules.ALL) m.enabled = false;
            gg.aurora.module.PerfModules.HUD_CACHE.enabled = true;
            Module m = Modules.byId(pv.id());
            if (m != null) m.enabled = true;
            else AuroraClient.LOGGER.warn("devprev: module inconnu {}", pv.id());
            mc.options.setCameraType(pv.cam() == FIRST ? CameraType.FIRST_PERSON : pv.cam() == BACK ? CameraType.THIRD_PERSON_BACK : CameraType.THIRD_PERSON_FRONT);
            // image propre : pas de HUD ni de chat, sauf pour les effets de la main (la main disparaît avec le HUD)
            mc.options.hideGui = !HAND.contains(pv.id());
            cmd(mc, "kill @e[type=item]");
            cmd(mc, "tp @p " + SPOT + " " + pv.yaw() + " " + pv.pitch());
            cmd(mc, "item replace entity @p weapon.mainhand with air");
            cmd(mc, "item replace entity @p weapon.offhand with air");
            if (pv.start() != null) pv.start().run();
        }
        if (t == pv.shotAt() - 2) mc.gui.getChat().clearMessages(false);
        // actions seulement avant la fin du créneau : sinon une touche resterait enfoncée pour le module suivant
        if (pv.tick() != null && t < PERIOD - 12) pv.tick().accept(t);
        // 6 images espacées de 4 ticks (0,8 s en tout) : l'aperçu est animé dans le menu
        int rel = t - pv.shotAt();
        if (rel >= 0 && rel < FRAMES * 4 && rel % 4 == 0) {
            Screenshot.grab(mc.gameDirectory, "prev_" + pv.id() + "_" + (rel / 4) + ".png", mc.getMainRenderTarget(), 1, msg -> {});
        }
        if (t == PERIOD - 10 && pv.end() != null) pv.end().run();
        if (t >= PERIOD) {
            index++;
            t = -1;
        }
    }
}
