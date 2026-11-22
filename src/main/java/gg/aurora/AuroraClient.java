package gg.aurora;

import com.mojang.blaze3d.platform.InputConstants;
import gg.aurora.module.HudModule;
import gg.aurora.module.Modules;
import gg.aurora.ui.AuroraScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuroraClient implements ClientModInitializer {
    public static final String MOD_ID = "aurora";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static KeyMapping menuKey;
    public static KeyMapping zoomKey;
    public static KeyMapping emoteKey;
    private static boolean optionsSynced;

    private static net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement deferredScoreboard, deferredBossBar;
    private static net.minecraft.client.DeltaTracker deferredDelta;

    @Override
    public void onInitializeClient() {
        AuroraSounds.init();
        gg.aurora.module.WorldModules.init();
        gg.aurora.module.PvpOptimizers.init();
        gg.aurora.module.PerfModules.init();
        gg.aurora.module.ShulkerViewer.init();
        ModManager.init();
        // couche cosmétiques (ailes, halo, aura) sur le modèle des joueurs
        net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, ctx) -> {
            if (renderer instanceof net.minecraft.client.renderer.entity.player.AvatarRenderer<?> avatar) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                var parent = (net.minecraft.client.renderer.entity.RenderLayerParent) avatar;
                helper.register(new gg.aurora.cosmetic.CosmeticsLayer(parent));
            }
        });
        Config.load();
        gg.aurora.module.ModuleGroups.apply();
        if (Settings.migration < 17) {
            // 1.7 : le zoom était coupé par défaut, d'où « le zoom ne marche pas »
            Modules.ZOOM.enabled = true;
            Settings.migration = 17;
            Config.save();
        }
        if (Settings.hudStyleVersion < 2) {
            // nouveau style par défaut : widgets épurés
            Settings.hudStyle = Settings.HudStyle.CLEAN;
            Settings.hudStyleVersion = 2;
            Config.save();
        }
        // doublons avec d'autres mods (Entity Culling, Zoomify…) : module coupé une seule fois
        java.util.List<String> dup = gg.aurora.module.ModConflicts.apply();
        Config.save();
        if (!dup.isEmpty()) gg.aurora.ui.Toasts.push(gg.aurora.ui.Icons.INFO, "Duplicates turned off",
                String.join(", ", dup) + " : another mod already does this", true);
        Updater.checkAsync(false);

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        menuKey = KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.aurora.menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, category));
        zoomKey = KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.aurora.zoom", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, category));
        gg.aurora.waypoint.Waypoints.init(category);
        gg.aurora.module.MusicModule.init(category);
        gg.aurora.module.Freelook.init(category);
        emoteKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.aurora.emotes", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, category));
        // chat amélioré : catégorie de chaque message (onglets), têtes, MP masqués en mode stream
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.ALLOW_CHAT.register(
                (message, signed, sender, params, time) -> gg.aurora.module.ChatPlus.onChat(message, sender, params));
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.ALLOW_GAME.register(gg.aurora.module.ChatPlus::onGame);
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((msg, overlay) -> gg.aurora.module.AutoGG.onServerText(msg));

        // rendu dans le monde : effets de kill (géométrie) et surbrillance des blocs des waypoints
        // menu dans le monde : après l'eau et le verre (sinon ils passeraient devant le panneau)
        net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.END_MAIN.register(ctx -> {
            long t = Prof.start();
            gg.aurora.ui.WorldPanel.renderWorld(ctx.matrices(), ctx.consumers(), Minecraft.getInstance().gameRenderer.getMainCamera().position());
            Prof.stop(Prof.WORLD_PANEL, t);
        });
        net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.AFTER_ENTITIES.register(ctx -> {
            Minecraft mc = Minecraft.getInstance();
            var cam = mc.gameRenderer.getMainCamera().position();
            float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            // lumière additive de tous les effets : un seul appel de dessin par texture, à la fin
            var fx = gg.aurora.render.FxBatch.begin(ctx.consumers());
            { long t_ = gg.aurora.Prof.start(); gg.aurora.cosmetic.HitSlashes.renderWorld(ctx.matrices(), fx, cam, pt); gg.aurora.Prof.stop(gg.aurora.Prof.KILL, t_); }
            gg.aurora.waypoint.Waypoints.renderWorld(ctx.matrices(), fx, cam);
            gg.aurora.module.CombatModules.renderHitboxes(ctx.matrices(), fx, cam, pt);
            { long t_ = gg.aurora.Prof.start(); gg.aurora.module.VisualModules.renderWorld(ctx.matrices(), fx, cam, pt); gg.aurora.Prof.stop(gg.aurora.Prof.VISUAL_WORLD, t_); }
            { long t_ = gg.aurora.Prof.start(); gg.aurora.cosmetic.AuraFx.renderWorld(ctx.matrices(), fx, cam, pt); gg.aurora.Prof.stop(gg.aurora.Prof.AURA, t_); }
            { long t_ = gg.aurora.Prof.start(); gg.aurora.cosmetic.PetFx.renderWorld(ctx.matrices(), fx, cam, pt); gg.aurora.Prof.stop(gg.aurora.Prof.PET, t_); }
            { long t_ = gg.aurora.Prof.start(); gg.aurora.cosmetic.TrailFx.renderWorld(ctx.matrices(), fx, cam, pt); gg.aurora.Prof.stop(gg.aurora.Prof.TRAIL, t_); }
            gg.aurora.module.Live.renderWorld(ctx.matrices(), fx, cam, pt);
            { long t_ = gg.aurora.Prof.start(); gg.aurora.module.AmbientFx.renderWorld(ctx.matrices(), fx, cam, pt); gg.aurora.Prof.stop(gg.aurora.Prof.AMBIENT, t_); }
            { long t_ = gg.aurora.Prof.start(); gg.aurora.render.FxBatch.end(fx); gg.aurora.Prof.stop(gg.aurora.Prof.FX_FLUSH, t_); }
        });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            long tickT = Prof.start();
            if (!optionsSynced) {
                Modules.syncVanillaOptions();
                optionsSynced = true;
            }
            while (menuKey.consumeClick()) {
                if (gg.aurora.ui.WorldPanel.open()) gg.aurora.ui.WorldPanel.close();
                else if (mc.screen == null) mc.setScreen(new gg.aurora.ui.ClickGuiScreen());
            }
            while (emoteKey.consumeClick()) {
                if (mc.screen == null && mc.player != null && gg.aurora.module.ClientModules.EMOTES.enabled) {
                    mc.setScreen(new gg.aurora.ui.EmoteWheelScreen(emoteKey));
                }
            }
            gg.aurora.module.Live.tick(mc);
            Boost.tick(mc);
            gg.aurora.module.Freelook.tick(mc);
            Modules.tick(mc);
            gg.aurora.nametag.NametagSync.tick(mc);
            Friends.tick(mc);
            gg.aurora.cosmetic.Cosmetics.tick(mc);
            gg.aurora.cosmetic.HitSlashes.tick(mc);
            gg.aurora.waypoint.Waypoints.tick(mc);
            DevShots.tick(mc);
            ModShots.tick(mc);
            HotShots.tick(mc);
            HotStoreShots.tick(mc);
            StoreShots.tick(mc);
            DevWorld.tick(mc);
            if (Boolean.getBoolean("aurora.devprev")) DevPreviews.tick(mc);
            SelfTest.tick(mc);
            Bench.tick(mc);
            gg.aurora.module.AutoGG.tick(mc);
            gg.aurora.render.Vegetation.tick(mc);
            gg.aurora.cosmetic.MiniSkinPet.tick(mc);
            gg.aurora.module.ClarityModules.tick(mc);
            gg.aurora.module.NewModules.tick(mc);
            gg.aurora.ui.WorldPanel.tick();
            gg.aurora.module.PerfModules.tick(mc);
            Prof.stop(Prof.TICK, tickT);
        });

        // statistiques de la page de joueur : envoyées en quittant un monde ou un serveur
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PlayerStats.flush());
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            Modules.restoreGamma();
            gg.aurora.media.MediaBridge.stop();
            Config.save();
            Updater.applyOnExit();
            ModManager.applyOnExit();
        });

        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() && player instanceof LocalPlayer lp) {
                Modules.onAttack(lp, entity);
                gg.aurora.module.NewModules.onAttack(entity);
            }
            return InteractionResult.PASS;
        });

        // étiquettes des waypoints : sous tous les autres éléments du HUD
        HudElementRegistry.addFirst(Identifier.fromNamespaceAndPath(MOD_ID, "waypoints"), gg.aurora.waypoint.Waypoints::renderHud);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "modules"), (g, delta) -> {
            Minecraft mc = Minecraft.getInstance();
            Modules.sampleClicks();
            if (mc.options.hideGui || mc.player == null) return;
            Modules.renderLowHp(g);
            gg.aurora.module.ClientModules.renderStreamBadge(g);
            gg.aurora.module.ClientModules.renderTotem(g);
            gg.aurora.module.NewModules.renderOverlay(g, delta);
            if (mc.screen instanceof gg.aurora.ui.HudEditScreen) return;
            long hudT = Prof.start();
            var cache = gg.aurora.module.PerfModules.HUD_CACHE;
            gg.aurora.render.HudCache.draw(g, cache.enabled ? (float) cache.opt("hz", 30) : 0, gg2 -> {
                if (deferredScoreboard != null) deferredScoreboard.render(gg2, deferredDelta);
                if (deferredBossBar != null) deferredBossBar.render(gg2, deferredDelta);
                for (HudModule h : Modules.hudModules()) {
                    if (!h.enabled || h.live) continue;
                    h.draw(gg2, false);
                    gg2.nextStratum(); // vanilla tests each element against its whole stratum: one per widget keeps that short
                }
            });
            Prof.stop(Prof.HUD, hudT);
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "modules_live"), (g, delta) -> {
            if (Minecraft.getInstance().screen instanceof gg.aurora.ui.HudEditScreen) return;
            for (HudModule h : Modules.hudModules()) if (h.enabled && h.live) h.draw(g, false);
        });
        // notifications : sur le HUD en jeu, et par-dessus tous les écrans (menus, chat, inventaire…)
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "toasts"), (g, delta) -> {
            if (Minecraft.getInstance().screen == null) gg.aurora.ui.Toasts.render(g);
        });
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterRender(screen).register((sc, g, mx, my, delta) -> gg.aurora.ui.Toasts.render(g));
            if (screen instanceof net.minecraft.client.gui.screens.PauseScreen) gg.aurora.ui.PauseButton.attach(screen);
            else if (screen instanceof net.minecraft.client.gui.screens.TitleScreen) gg.aurora.ui.PauseButton.takeOverModMenu(screen);
            // Now Playing : les boutons du widget sont cliquables quand le chat est ouvert
            if (screen instanceof net.minecraft.client.gui.screens.ChatScreen) {
                net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseClick(screen)
                        .register((sc, event) -> !gg.aurora.module.ChatPlus.click(event.x(), event.y(), event.button())
                                && !(event.button() == 0 && gg.aurora.module.MusicModule.click(event.x(), event.y())));
                net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterRender(screen)
                        .register((sc, g, mx, my, delta) -> gg.aurora.module.ChatPlus.renderTabs(g, mx, my));
            }
        });

        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((d, ctx) -> Commands.register(d));

        // text heavy and nearly static: refreshed with the HUD cache instead of every frame
        HudElementRegistry.replaceElement(VanillaHudElements.SCOREBOARD, old -> (g, d) -> {
            boolean cached = gg.aurora.module.PerfModules.HUD_CACHE.enabled;
            deferredScoreboard = cached && !Modules.HIDE_SCOREBOARD.enabled ? old : null;
            deferredDelta = d;
            if (!cached && !Modules.HIDE_SCOREBOARD.enabled) old.render(g, d);
        });
        HudElementRegistry.replaceElement(VanillaHudElements.BOSS_BAR, old -> (g, d) -> {
            boolean cached = gg.aurora.module.PerfModules.HUD_CACHE.enabled;
            deferredBossBar = cached && !Modules.HIDE_BOSSBAR.enabled ? old : null;
            deferredDelta = d;
            if (!cached && !Modules.HIDE_BOSSBAR.enabled) old.render(g, d);
        });
    }
}
