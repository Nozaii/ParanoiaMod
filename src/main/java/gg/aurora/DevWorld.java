package gg.aurora;

import gg.aurora.module.WorldModules;
import gg.aurora.ui.AuroraScreen;
import gg.aurora.ui.FriendsScreen;
import gg.aurora.ui.WardrobeScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.server.MinecraftServer;

/**
 * Outil de développement : avec -Daurora.devworld=true (et --quickPlaySingleplayer), teste les
 * modules dans un monde et enregistre des captures. Inactif en jeu normal.
 */
public final class DevWorld {
    private DevWorld() {}

    public static final boolean ENABLED = Boolean.getBoolean("aurora.devworld");
    private static int tick = -1;

    private static void cmd(Minecraft mc, String c) {
        MinecraftServer s = mc.getSingleplayerServer();
        if (s != null) s.execute(() -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), c));
    }

    public static final boolean UNCAPPED = Boolean.getBoolean("aurora.devab") || Boolean.getBoolean("aurora.devfresh") || Boolean.getBoolean("aurora.devhud") || Boolean.getBoolean("aurora.devall") || Boolean.getBoolean("aurora.devsweep") || Boolean.getBoolean("aurora.devcos") || Boolean.getBoolean("aurora.devmax") || Boolean.getBoolean("aurora.devset") || System.getProperty("aurora.devprof") != null || System.getProperty("aurora.devabimpl") != null || Boolean.getBoolean("aurora.devvkfx");
    public static long sweepFrames;
    private static long abFrames, abStart;
    private static double sweepBase;
    private static java.util.List<gg.aurora.module.Module> SWEEP;
    private static final java.util.List<String> SWEEP_OUT = new java.util.ArrayList<>();
    private static String AB = "";
    private static long abSum;
    private static int abN;

    static void tick(Minecraft mc) {
        if (!ENABLED || Boolean.getBoolean("aurora.devprev")) return;
        if (tick < 0) {
            if (mc.player == null || mc.level == null || mc.screen != null) return;
            tick = 0;
        }
        tick++;
        var p = mc.player;
        mc.getFramerateLimitTracker().onInputReceived();
        if (Boolean.getBoolean("aurora.devwpui")) { // waypoints menu: folders, tabs, square look
            if (tick == 20) { cmd(mc, "time set 6000"); cmd(mc, "weather clear"); mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                p.setYRot(0); p.setXRot(10);
                gg.aurora.waypoint.Waypoints.LIST.removeIf(w -> w.name.startsWith("Dev "));
                gg.aurora.waypoint.Waypoints.FOLDERS.removeIf(f -> f.name.startsWith("Dev "));
                gg.aurora.waypoint.Waypoint.Folder base = new gg.aurora.waypoint.Waypoint.Folder(); base.name = "Dev Base"; base.color = 0x3AB7F0;
                gg.aurora.waypoint.Waypoint.Folder mines = new gg.aurora.waypoint.Waypoint.Folder(); mines.name = "Dev Mines"; mines.color = 0xFFD43A;
                gg.aurora.waypoint.Waypoints.FOLDERS.add(base); gg.aurora.waypoint.Waypoints.FOLDERS.add(mines);
                String[][] wps = {{"Dev Home", "minecraft:red_bed", base.id}, {"Dev Farm", "minecraft:wheat", base.id}, {"Dev Diamonds", "minecraft:diamond_ore", mines.id},
                        {"Dev Portal", "minecraft:obsidian", ""}, {"Dev Village", "minecraft:bell", ""}};
                int[] cols = {0x3AB7F0, 0x34C759, 0xFFD43A, 0x6B5CFF, 0xFF9A2E};
                for (int i = 0; i < wps.length; i++) {
                    gg.aurora.waypoint.Waypoint w = new gg.aurora.waypoint.Waypoint();
                    w.name = wps[i][0]; w.icon = wps[i][1]; w.folder = wps[i][2]; w.color = cols[i]; w.block = false; w.preset = i % 5;
                    w.x = (int) p.getX() - 12 + i * 6; w.y = (int) p.getY() + 1; w.z = (int) p.getZ() + 14 + i * 8;
                    w.dim = mc.level.dimension().identifier().toString();
                    gg.aurora.waypoint.Waypoints.LIST.add(w);
                }
            }
            if (tick == 40) shot(mc, "wpui_world");
            if (tick == 44) { var sc = new gg.aurora.waypoint.WaypointsScreen(null, null); mc.setScreen(sc);
                sc.debugSelect(gg.aurora.waypoint.Waypoints.LIST.stream().filter(w -> w.name.equals("Dev Home")).findFirst().orElse(null)); sc.debugTab(0); }
            if (tick == 60) shot(mc, "wpui_general");
            if (tick == 62) ((gg.aurora.waypoint.WaypointsScreen) mc.screen).debugTab(1);
            if (tick == 76) shot(mc, "wpui_look");
            if (tick == 78) ((gg.aurora.waypoint.WaypointsScreen) mc.screen).debugTab(2);
            if (tick == 90) shot(mc, "wpui_highlight");
            if (tick == 92) ((gg.aurora.waypoint.WaypointsScreen) mc.screen).debugSelect(gg.aurora.waypoint.Waypoints.FOLDERS.stream().filter(f -> f.name.equals("Dev Base")).findFirst().orElse(null));
            if (tick == 106) shot(mc, "wpui_folder");
            if (tick == 110) { mc.setScreen(null); gg.aurora.waypoint.Waypoints.LIST.removeIf(w -> w.name.startsWith("Dev "));
                gg.aurora.waypoint.Waypoints.FOLDERS.removeIf(f -> f.name.startsWith("Dev ")); gg.aurora.waypoint.Waypoints.save(); mc.stop(); }
            return;
        }
        if (Boolean.getBoolean("aurora.devping")) { // pings (item, danger, remote, off-screen), wheel, waypoint labels, settings
            if (tick == 20) { cmd(mc, "time set 6000"); cmd(mc, "weather clear"); cmd(mc, "gamemode creative @p"); cmd(mc, "kill @e[type=item]");
                setCosmetics(false); mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                p.setYRot(0); p.setXRot(18); }
            if (tick == 30) {
                net.minecraft.world.phys.Vec3 at = p.position().add(0, 0, 5);
                cmd(mc, String.format(java.util.Locale.ROOT, "summon item %.1f %.1f %.1f {Item:{id:\"minecraft:diamond\",count:3},PickupDelay:32767,Age:-32768}", at.x, at.y + 1, at.z));
                gg.aurora.waypoint.Waypoints.LIST.removeIf(w -> w.name.startsWith("Dev "));
                String[] names = {"Dev Base", "Dev Mine", "Dev Farm", "Dev Portal", "Dev Tower"};
                int[] cols = {0x4DA3FF, 0xFFD43A, 0x34C759, 0x6B5CFF, 0xE8473E};
                for (int i = 0; i < 5; i++) {
                    gg.aurora.waypoint.Waypoint w = new gg.aurora.waypoint.Waypoint();
                    w.name = names[i]; w.preset = i; w.color = cols[i]; w.block = false;
                    w.x = (int) p.getX() - 16 + i * 8; w.y = (int) p.getY() + 2; w.z = (int) p.getZ() + 22 + i * 14;
                    w.dim = mc.level.dimension().identifier().toString();
                    w.icon = new String[]{"minecraft:red_bed", "minecraft:iron_pickaxe", "minecraft:wheat", "minecraft:obsidian", "minecraft:stone_bricks"}[i];
                    gg.aurora.waypoint.Waypoints.LIST.add(w);
                }
                gg.aurora.waypoint.Waypoint behind = new gg.aurora.waypoint.Waypoint();
                behind.name = "Dev Behind"; behind.block = false; behind.x = (int) p.getX() + 30; behind.y = (int) p.getY(); behind.z = (int) p.getZ() - 40;
                behind.dim = mc.level.dimension().identifier().toString();
                gg.aurora.waypoint.Waypoints.LIST.add(behind);
                Settings.wpEdge = true;
            }
            if (tick == 50) { // item under the crosshair
                p.setXRot(32);
                for (var it : mc.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, p.getBoundingBox().inflate(12))) {
                    net.minecraft.world.phys.Vec3 d = it.position().add(0, 0.15, 0).subtract(p.getEyePosition());
                    p.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
                    p.setXRot((float) Math.toDegrees(-Math.atan2(d.y, Math.hypot(d.x, d.z))));
                }
                gg.aurora.waypoint.Pings.Target t = gg.aurora.waypoint.Pings.aim(mc);
                AuroraClient.LOGGER.info("devping aim: {}", t);
                gg.aurora.waypoint.Pings.choose(t, t != null ? t.auto() : gg.aurora.waypoint.Pings.Kind.GO);
                p.setXRot(12); p.setYRot(-25);
                gg.aurora.waypoint.Pings.Target t2 = gg.aurora.waypoint.Pings.aim(mc);
                if (t2 != null) gg.aurora.waypoint.Pings.choose(t2, gg.aurora.waypoint.Pings.Kind.DANGER);
                p.setYRot(0); p.setXRot(18);
                com.google.gson.JsonObject o = new com.google.gson.JsonObject();
                o.addProperty("id", "remote1"); o.addProperty("kind", "help");
                o.addProperty("x", p.getX() + 9); o.addProperty("y", p.getY() + 1); o.addProperty("z", p.getZ() + 14);
                o.addProperty("dim", mc.level.dimension().identifier().toString());
                gg.aurora.waypoint.Pings.received(java.util.UUID.randomUUID(), o, 0);
                com.google.gson.JsonObject o2 = o.deepCopy(); o2.addProperty("id", "remote2"); o2.addProperty("kind", "enemy");
                o2.addProperty("x", p.getX() - 30); o2.addProperty("z", p.getZ() - 20);
                gg.aurora.waypoint.Pings.received(java.util.UUID.randomUUID(), o2, 0);
            }
            if (tick == 62) shot(mc, "ping_world");
            if (tick == 70) { p.setYRot(140); p.setXRot(18); }
            if (tick == 74) shot(mc, "ping_edge");
            if (tick == 80) { p.setYRot(0); p.setXRot(32); gg.aurora.waypoint.Pings.devHold = true;
                mc.setScreen(new gg.aurora.ui.PingWheelScreen(gg.aurora.waypoint.Pings.aim(mc))); }
            if (tick == 84) { double sc = mc.getWindow().getGuiScale();
                org.lwjgl.glfw.GLFW.glfwSetCursorPos(mc.getWindow().handle(), mc.getWindow().getScreenWidth() / 2.0 + 60 * sc, mc.getWindow().getScreenHeight() / 2.0 - 36 * sc); }
            if (tick == 92) shot(mc, "ping_wheel");
            if (tick == 96) { gg.aurora.waypoint.Pings.devHold = false; mc.setScreen(null); }
            if (tick == 104) { gg.aurora.ui.SettingsScreen st = new gg.aurora.ui.SettingsScreen(null); mc.setScreen(st);
                for (int i = 0; i < st.sectionCount(); i++) if (gg.aurora.ui.SettingsSections.build(st).get(i).name().equals("Waypoints")) st.debugSection(i); }
            if (tick == 130) shot(mc, "ping_settings_wp");
            if (tick == 134) { gg.aurora.ui.SettingsScreen st = (gg.aurora.ui.SettingsScreen) mc.screen;
                for (int i = 0; i < st.sectionCount(); i++) if (gg.aurora.ui.SettingsSections.build(st).get(i).name().equals("Pings")) st.debugSection(i); }
            if (tick == 150) shot(mc, "ping_settings_ping");
            if (tick == 156) { gg.aurora.waypoint.Waypoints.LIST.removeIf(w -> w.name.startsWith("Dev ")); Settings.wpEdge = false; mc.stop(); }
            return;
        }
        if (Boolean.getBoolean("aurora.devsat")) { // saturation (contours dorés, aperçu) puis élytres d'une cape importée
            if (tick == 20) { cmd(mc, "time set 6000"); cmd(mc, "weather clear"); cmd(mc, "spreadplayers 60 60 0 1 false @p"); cmd(mc, "gamemode survival @p");
                setCosmetics(false); Settings.back = ""; Settings.pet = ""; gg.aurora.module.NewModules.SATURATION.enabled = true;
                cmd(mc, "effect clear @p"); cmd(mc, "effect give @p hunger 6 255 true"); cmd(mc, "item replace entity @p weapon.mainhand with golden_apple 5");
                mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON); }
            if (tick == 150) cmd(mc, "effect give @p saturation 1 0 true");
            if (tick == 165) cmd(mc, "effect give @p hunger 2 255 true");
            if (tick == 215) shot(mc, "sat_a");
            if (tick == 222) shot(mc, "sat_b");
            if (tick == 224) {
                try {
                    var src = mc.gameDirectory.toPath().resolve("devcape.png");
                    var f = CapeImport.decode(src);
                    CapeImport.save(Capes.folder(), "devtest", CapeImport.build(f, 0, 0, f.w(), f.h()));
                    Capes.scanImported(); Settings.cape = "imported_devtest";
                } catch (Exception e) { AuroraClient.LOGGER.error("devcape", e); }
                cmd(mc, "gamemode creative @p"); cmd(mc, "item replace entity @p armor.chest with elytra"); cmd(mc, "kill @e[type=!player]");
                Settings.elytraCape = true;
                mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK); cmd(mc, "execute as @p at @s run tp @s ~ ~ ~ 20 15"); }
            if (tick == 270) shot(mc, "elytra_imported");
            if (tick == 272) { cmd(mc, "item replace entity @p armor.chest with air"); }
            if (tick == 300) shot(mc, "cape_imported");
            if (tick == 302) { Settings.elytraCape = false; Settings.cape = ""; mc.stop(); }
            return;
        }
        if (Boolean.getBoolean("aurora.devshulker")) { // tooltip de shulker : vue regroupée puis boîte entière
            if (tick == 20) { cmd(mc, "time set 6000"); setCosmetics(false); gg.aurora.module.ShulkerViewer.MODULE.enabled = true; }
            // -Daurora.devshulkers=black,blue,... : one compact capture per box colour (« none » = plain box)
            String[] colors = System.getProperty("aurora.devshulkers", "").isBlank() ? null : System.getProperty("aurora.devshulkers").split(",");
            if (colors != null) {
                int i = (tick - 30) / 12, step = (tick - 30) % 12;
                if (tick >= 30 && i < colors.length) {
                    if (step == 0) {
                        var items = new java.util.ArrayList<net.minecraft.world.item.ItemStack>();
                        items.add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.END_CRYSTAL, 64));
                        items.add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OBSIDIAN, 64));
                        items.add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TOTEM_OF_UNDYING, 1));
                        items.add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLDEN_APPLE, 32));
                        var color = "none".equals(colors[i]) ? null : net.minecraft.world.item.DyeColor.byName(colors[i], null);
                        var box = new net.minecraft.world.item.ItemStack(net.minecraft.world.level.block.ShulkerBoxBlock.getBlockByColor(color).asItem());
                        box.set(net.minecraft.core.component.DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(items));
                        gg.aurora.module.ShulkerViewer.devFull = (i % 2) == 1;
                        mc.setScreen(new TooltipProbe(box));
                    }
                    if (step == 10) shot(mc, "s_color_" + colors[i]);
                }
                if (tick == 30 + colors.length * 12 + 2) { gg.aurora.module.ShulkerViewer.devFull = false; mc.stop(); }
                return;
            }
            if (tick == 30) {
                var items = new java.util.ArrayList<net.minecraft.world.item.ItemStack>();
                for (int i = 0; i < 27; i++) {
                    var it = i < 9 ? net.minecraft.world.item.Items.END_CRYSTAL : i < 15 ? net.minecraft.world.item.Items.OBSIDIAN
                            : i < 19 ? net.minecraft.world.item.Items.TOTEM_OF_UNDYING : i < 22 ? net.minecraft.world.item.Items.GOLDEN_APPLE
                            : i < 24 ? net.minecraft.world.item.Items.ENDER_PEARL : i == 24 ? net.minecraft.world.item.Items.NETHERITE_SWORD : net.minecraft.world.item.Items.AIR;
                    var st = new net.minecraft.world.item.ItemStack(it, it == net.minecraft.world.item.Items.TOTEM_OF_UNDYING || it == net.minecraft.world.item.Items.NETHERITE_SWORD ? 1 : it == net.minecraft.world.item.Items.ENDER_PEARL ? 16 : 64);
                    items.add(it == net.minecraft.world.item.Items.AIR ? net.minecraft.world.item.ItemStack.EMPTY : st);
                }
                var box = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PURPLE_SHULKER_BOX);
                box.set(net.minecraft.core.component.DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(items));
                mc.setScreen(new TooltipProbe(box));
            }
            if (tick == 50) shot(mc, "s_compact");
            if (tick == 52) gg.aurora.module.ShulkerViewer.devFull = true;
            if (tick == 70) shot(mc, "s_full");
            if (tick == 72) { gg.aurora.module.ShulkerViewer.devFull = false; mc.stop(); }
            return;
        }
        if (Boolean.getBoolean("aurora.devpage")) { // page de joueur : lecture, édition, Discord, contre un serveur local (-Daurora.api)
            if (tick == 20) { cmd(mc, "time set 6000"); cmd(mc, "weather clear"); setCosmetics(false);
                String api = System.getProperty("aurora.api", ""); if (!api.isEmpty()) Settings.apiUrl = api;
                mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON); gg.aurora.Friends.sync(true); }
            if (tick == 30) cmd(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 10");
            if (tick == 80) mc.setScreen(new gg.aurora.ui.ProfileScreen(null));
            if (tick == 150) shot(mc, "p_mine");
            if (tick == 152 && mc.screen instanceof gg.aurora.ui.ProfileScreen ps) ps.debugEdit();
            if (tick == 190) shot(mc, "p_edit");
            if (tick == 192 && mc.screen instanceof gg.aurora.ui.ProfileScreen ps) { ps.debugPose(1); ps.debugSetBio("Bob here. Testing the page editor."); ps.debugSave(); }
            if (tick == 240) shot(mc, "p_mine_saved");
            if (tick == 242) mc.setScreen(new gg.aurora.ui.ProfileScreen(null));
            if (tick == 300) shot(mc, "p_mine_reopened");
            if (tick == 302) { String other = System.getProperty("aurora.devother", ""); if (!other.isEmpty()) mc.setScreen(new gg.aurora.ui.ProfileScreen(null, other)); }
            if (tick == 370) shot(mc, "p_other");
            if (tick == 372 && mc.screen instanceof gg.aurora.ui.ProfileScreen ps) ps.debugTab(1);
            if (tick == 400) shot(mc, "p_discord");
            if (tick == 402) { var gui = new gg.aurora.ui.ClickGuiScreen(); gui.debugSelect("ground_mirror"); mc.setScreen(gui); }
            if (tick == 440) shot(mc, "p_preview_row");
            if (tick == 442) mc.setScreen(new gg.aurora.ui.KeyboardScreen(null));
            if (tick == 470) shot(mc, "p_keyboard");
            if (tick == 472 && mc.screen instanceof gg.aurora.ui.KeyboardScreen ks) ks.debugSelect(org.lwjgl.glfw.GLFW.GLFW_KEY_C);
            if (tick == 500) shot(mc, "p_keyboard_key");
            if (tick == 502) { gg.aurora.ui.ClickGuiScreen.devProgress = 0.45f; mc.setScreen(new gg.aurora.ui.ClickGuiScreen()); }
            if (tick == 520) shot(mc, "p_menu_opening");
            if (tick == 522) { gg.aurora.ui.ClickGuiScreen.devProgress = -1; mc.stop(); }
            return;
        }
        if (Boolean.getBoolean("aurora.devnew")) { // bouton pause vanilla, HUD sans barres, éditeur HUD, Pictures, pages intégrées
            if (tick == 20) { cmd(mc, "time set 6000"); cmd(mc, "weather clear"); setCosmetics(false);
                mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                int n = 0; for (var h : gg.aurora.module.Modules.hudModules()) { if (n++ < 5) h.enabled = true; } }
            if (tick == 30) cmd(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 10");
            if (tick == 50) shot(mc, "n_hud");
            if (tick == 52) mc.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true));
            if (tick == 70) shot(mc, "n_pause");
            if (tick == 72) { var e = new gg.aurora.ui.HudEditScreen(null); mc.setScreen(e); e.debugSelect(gg.aurora.module.Modules.hudModules().get(1)); }
            if (tick == 110) shot(mc, "n_hud_editor");
            if (tick == 112) { var gui = new gg.aurora.ui.ClickGuiScreen(); gui.debugTab(5); mc.setScreen(gui); }
            if (tick == 150) shot(mc, "n_wardrobe");
            if (tick == 152) { var gui = new gg.aurora.ui.ClickGuiScreen(); gui.debugTab(6); mc.setScreen(gui); }
            if (tick == 190) shot(mc, "n_friends");
            if (tick == 192) { var gui = new gg.aurora.ui.ClickGuiScreen(); gui.debugTab(7); mc.setScreen(gui); }
            if (tick == 240) shot(mc, "n_pics_gallery");
            if (tick == 242 && mc.screen instanceof gg.aurora.ui.ClickGuiScreen gui && gui.page() instanceof gg.aurora.ui.PicturesScreen ps) { ps.debugSize(2); }
            if (tick == 262) shot(mc, "n_pics_gallery_large");
            if (tick == 264 && mc.screen instanceof gg.aurora.ui.ClickGuiScreen gui && gui.page() instanceof gg.aurora.ui.PicturesScreen ps) { ps.debugSize(1); ps.debugOpen(0); }
            if (tick == 300) shot(mc, "n_pics_viewer");
            if (tick == 302 && mc.screen instanceof gg.aurora.ui.ClickGuiScreen gui && gui.page() instanceof gg.aurora.ui.PicturesScreen ps) { ps.debugEdit(); ps.debugStroke(0.2f, 0.3f, 0.7f, 0.5f, 2, 2); ps.debugStroke(0.3f, 0.7f, 0.8f, 0.75f, 4, 3); }
            if (tick == 330) shot(mc, "n_pics_editor");
            if (tick == 332 && mc.screen instanceof gg.aurora.ui.ClickGuiScreen gui && gui.page() instanceof gg.aurora.ui.PicturesScreen ps) { ps.debugCrop(0.15f, 0.2f, 0.8f, 0.85f); }
            if (tick == 350) shot(mc, "n_pics_crop");
            if (tick == 352) { var gui = new gg.aurora.ui.ClickGuiScreen(); gui.debugTab(7); mc.setScreen(gui);
                if (gui.page() instanceof gg.aurora.ui.PicturesScreen ps) { ps.debugOpen(1); ps.debugShare(); } }
            if (tick == 390) shot(mc, "n_pics_share");
            if (tick == 392) mc.stop();
            return;
        }
        if (Boolean.getBoolean("aurora.devgui")) { // nouveau menu : grille, tiroir, filtres, vue réglages
            if (tick == 20) { cmd(mc, "time set 6000"); cmd(mc, "weather clear"); setCosmetics(false);
                mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON); }
            if (tick == 30) cmd(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 10");
            if (tick == 40) { var gui = new gg.aurora.ui.ClickGuiScreen(); gui.debugTab(0); mc.setScreen(gui); }
            if (tick == 75) shot(mc, "g_combat");
            if (tick == 77) { var gui = new gg.aurora.ui.ClickGuiScreen(); gui.debugSelect("low_hp"); mc.setScreen(gui); }
            if (tick == 112) shot(mc, "g_drawer");
            if (tick == 114) { var gui = new gg.aurora.ui.ClickGuiScreen(); gui.debugTab(1); gui.debugFilter(1); mc.setScreen(gui); }
            if (tick == 150) shot(mc, "g_visuals_enabled");
            if (tick == 152) { var gui = new gg.aurora.ui.ClickGuiScreen(); gui.debugTab(3); mc.setScreen(gui); }
            if (tick == 185) shot(mc, "g_perf");
            if (tick == 187) { var gui = new gg.aurora.ui.ClickGuiScreen(); gui.debugSettings(0); mc.setScreen(gui); }
            if (tick == 222) shot(mc, "g_settings");
            if (tick == 224) { var gui = new gg.aurora.ui.ClickGuiScreen(); gui.debugSettings(4); mc.setScreen(gui); }
            if (tick == 258) shot(mc, "g_settings_theme");
            if (tick == 260) { mc.setScreen(null); Settings.worldMenu = true; mc.setScreen(new gg.aurora.ui.ClickGuiScreen()); }
            if (tick == 300) shot(mc, "g_world");
            if (tick == 302) { Settings.worldMenu = false; mc.stop(); }
            return;
        }
        if (Boolean.getBoolean("aurora.devmini")) { // végétation masquée, interrupteurs de cosmétiques, mini skin équipé
            if (tick == 20) { cmd(mc, "time set 6000"); cmd(mc, "weather clear"); cmd(mc, "spreadplayers 60 60 0 1 false @p");
                setCosmetics(false); Settings.pet = ""; Settings.wings = "angel"; Settings.back = "katana";
                mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON); }
            if (tick == 30) { cmd(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 25");
                cmd(mc, "execute at @p run fill ~-5 ~ ~2 ~5 ~ ~9 short_grass replace air");
                cmd(mc, "execute at @p run fill ~-2 ~ ~3 ~2 ~ ~4 poppy replace short_grass");
                cmd(mc, "execute at @p run fill ~-4 ~ ~6 ~-3 ~ ~7 fern replace short_grass");
                cmd(mc, "execute at @p run fill ~3 ~ ~6 ~4 ~ ~7 pink_petals replace short_grass"); }
            if (tick == 70) shot(mc, "veg_on");
            if (tick == 72) { Settings.showGrass = false; gg.aurora.render.Vegetation.refresh(true); }
            if (tick == 110) shot(mc, "veg_no_grass");
            if (tick == 112) { Settings.showFlowers = false; gg.aurora.render.Vegetation.refresh(true); }
            if (tick == 150) shot(mc, "veg_none");
            if (tick == 152) { Settings.showGrass = Settings.showFlowers = true; gg.aurora.render.Vegetation.refresh(true);
                mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
                cmd(mc, "execute as @p at @s run tp @s ~ ~ ~ 20 15"); }
            if (tick == 190) shot(mc, "cos_all");
            if (tick == 192) { Settings.showWings = false; Settings.showBack = false; }
            if (tick == 205) shot(mc, "cos_hidden");
            if (tick == 207) { Settings.showWings = Settings.showBack = true; Settings.wings = ""; Settings.back = ""; Settings.pet = "mini_skin";
                Settings.petSkin = "Dream"; Settings.miniHelmet = "minecraft:diamond_helmet"; Settings.miniChest = "minecraft:netherite_chestplate";
                Settings.miniLegs = "minecraft:golden_leggings"; Settings.miniBoots = "minecraft:iron_boots"; Settings.miniMain = "minecraft:diamond_sword";
                Settings.miniOff = "minecraft:shield"; Settings.miniWings = "demon"; Settings.miniHalo = "or";
                mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT); }
            if (tick == 330) shot(mc, "mini_geared");
            if (tick == 332) { Settings.miniHelmet = Settings.miniChest = Settings.miniLegs = Settings.miniBoots = ""; Settings.miniWings = ""; Settings.miniHalo = "";
                Settings.miniBack = "guitare"; Settings.miniHead = 1.6; }
            if (tick == 350) shot(mc, "mini_bighead");
            if (tick == 352) { Settings.miniHead = 1.3; Settings.miniHelmet = "minecraft:diamond_helmet"; Settings.miniWings = "angel";
                mc.setScreen(new gg.aurora.ui.MiniSkinScreen(null)); }
            if (tick == 390) shot(mc, "mini_screen");
            if (tick == 392) mc.stop();
            return;
        }
        if (Boolean.getBoolean("aurora.devback")) { // objets de dos, dragon, mini skin
            if (tick == 20) { cmd(mc, "time set 6000"); cmd(mc, "weather clear"); cmd(mc, "spreadplayers 60 60 0 1 false @p");
                setCosmetics(false); Settings.back = "guitare"; Settings.pet = "";
                mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK); }
            if (tick == 30) cmd(mc, "execute as @p at @s run tp @s ~ ~ ~ 20 20");
            if (tick == 60) shot(mc, "back_guitare");
            if (tick == 62) Settings.back = "katana";
            if (tick == 72) shot(mc, "back_katana");
            if (tick == 74) Settings.back = "ak47";
            if (tick == 84) shot(mc, "back_ak47");
            if (tick == 86) Settings.back = "masse";
            if (tick == 96) shot(mc, "back_masse");
            if (tick == 98) { Settings.back = ""; Settings.pet = "dragon"; Settings.petSkin = "Dream";
                gg.aurora.cosmetic.MiniSkinPet.prefetch("Dream"); cmd(mc, "execute as @p at @s run tp @s ~ ~ ~ 20 -12"); }
            if (tick == 150) shot(mc, "pet_dragon");
            if (tick == 152) { Settings.pet = "mini_skin"; mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT); }
            if (tick == 300) shot(mc, "pet_miniskin");
            if (tick == 302) mc.stop();
            return;
        }
        if (Boolean.getBoolean("aurora.devhand")) { // Hand Aura : ancien chemin (36 copies CPU) puis instancié
            if (tick == 20) { cmd(mc, "time set 18000"); cmd(mc, "weather clear"); cmd(mc, "spreadplayers 60 60 0 1 false @p");
                setCosmetics(false); gg.aurora.module.VisualModules.HAND_AURA.enabled = true; gg.aurora.module.VisualModules.HAND_AURA.set("breathe", false);
                cmd(mc, "item replace entity @p weapon.mainhand with diamond_sword"); Settings.animations = false;
                mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON); }
            if (tick == 30) cmd(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 0");
            if (tick == 60) gg.aurora.render.HandGlow.legacy = true;
            if (tick == 70) shot(mc, "hand_old");
            if (tick == 72) gg.aurora.render.HandGlow.legacy = false;
            if (tick == 82) shot(mc, "hand_new");
            if (tick == 84) mc.stop();
            return;
        }
        if (Boolean.getBoolean("aurora.devpet")) { // comparaison visuelle du familier : ancien rendu puis nouveau
            if (tick == 20) { cmd(mc, "time set 18000"); cmd(mc, "weather clear"); cmd(mc, "spreadplayers 60 60 0 1 false @p");
                setCosmetics(false); Settings.pet = "meduse"; gg.aurora.module.WorldModules.CUSTOM_SKY.enabled = false;
                mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK); }
            if (tick == 30) cmd(mc, "execute as @p at @s run tp @s ~ ~ ~ 160 25");
            if (tick == 60) { gg.aurora.cosmetic.PetFx.legacyRibbons = true; gg.aurora.cosmetic.PetFx.freezeTime = 1234.5f; }
            if (tick == 70) { shotFrozen(mc, "pet_old"); }
            if (tick == 72) gg.aurora.cosmetic.PetFx.legacyRibbons = false;
            if (tick == 82) shotFrozen(mc, "pet_new");
            if (tick == 84) mc.stop();
            return;
        }
        if (Boolean.getBoolean("aurora.devboost")) { // Boost : options vanilla + Sodium appliquées puis restaurées
            if (tick == 40) { if (Boost.active) Boost.toggle(); devBoostLog(mc, "avant"); Boost.toggle(); }
            if (tick == 100) { devBoostLog(mc, "boost"); Boost.toggle(); }
            if (tick == 160) { devBoostLog(mc, "restauré"); mc.stop(); }
            return;
        }
        if (Boolean.getBoolean("aurora.devjelly")) { // méduse : ancien chemin, ancien (témoin), puis sommets transformés une fois
            if (tick == 20) { cmd(mc, "time set 18000"); cmd(mc, "weather clear"); cmd(mc, "spreadplayers 60 60 0 1 false @p");
                setCosmetics(false); Settings.pet = "meduse"; gg.aurora.module.WorldModules.CUSTOM_SKY.enabled = false;
                mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK); mc.options.hideGui = true; }
            if (tick == 30) { cmd(mc, "execute as @p at @s run tp @s ~ ~ ~ 160 25"); cmd(mc, "tick freeze"); }
            if (tick == 60) { gg.aurora.cosmetic.PetFx.legacyVerts = true; gg.aurora.cosmetic.PetFx.freezeTime = 1234.5f; }
            if (tick == 70) shotFrozen(mc, "jelly_old_a");
            if (tick == 72) shotFrozen(mc, "jelly_old_b");
            if (tick == 73) gg.aurora.cosmetic.PetFx.legacyVerts = false;
            if (tick == 74) shotFrozen(mc, "jelly_new");
            if (tick == 76) mc.stop();
            return;
        }
        if (Boolean.getBoolean("aurora.devjvm")) { // « Redémarrer optimisé » : 1er lancement relance, le 2e vérifie ses options et s'arrête
            if (tick == 40) {
                if (!JvmTuning.currentArgs().contains("-XX:+ParallelRefProcEnabled")) {
                    AuroraClient.LOGGER.info("devjvm: before {} | missing {}", JvmTuning.summary(), JvmTuning.missing());
                    JvmTuning.restartOptimized();
                } else {
                    try {
                        java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("devjvm.txt"),
                                JvmTuning.summary() + "\n" + JvmTuning.currentArgs() + "\nmissing " + JvmTuning.missing());
                    } catch (java.io.IOException e) { AuroraClient.LOGGER.error("devjvm", e); }
                    mc.stop();
                }
            }
            return;
        }
        if (Boolean.getBoolean("aurora.devfxcmp")) { // lumière des effets : ancien, ancien (témoin du bruit), puis nouveau
            if (tick == 20) { scene(mc, true); Settings.animations = false; gg.aurora.module.WorldModules.CUSTOM_SKY.enabled = false;
                mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK); mc.options.hideGui = true; }
            if (tick == 30) { cmd(mc, "execute as @p at @s run tp @s ~ ~ ~ 160 25"); cmd(mc, "tick freeze"); }
            if (tick == 60) { gg.aurora.render.FxBatch.legacy = true; gg.aurora.cosmetic.PetFx.freezeTime = 1234.5f; }
            if (tick == 70) shotFrozen(mc, "fx_old_a");
            if (tick == 72) shotFrozen(mc, "fx_old_b");
            if (tick == 73) { gg.aurora.render.FxBatch.legacy = false; }
            if (tick == 74) shotFrozen(mc, "fx_new");
            if (tick == 76) mc.stop();
            return;
        }
        String abImpl = System.getProperty("aurora.devabimpl");
        if (abImpl != null) { // A/B d'implémentations dans la même session (scène « user »)
            if (tick == 20) { scene(mc, !abImpl.equals("real")); if (abImpl.equals("real")) Settings.cosmeticQuality = 2; }
            if (tick == 30) animals(mc);
            if (tick == 300) {
                switch (abImpl) {
                    case "ubo" -> AbTest.start(new String[]{"buffer persistant", "anneau mappé", "recréé à chaque image"},
                            i -> gg.aurora.render.UniformRing.mode = i, 160);
                    case "sky" -> AbTest.start(new String[]{"cube", "sphère (ancien)"}, i -> gg.aurora.render.SkyFx.legacySphere = i == 1, 160);
                    case "pet" -> AbTest.start(new String[]{"rubans face caméra", "quads croisés (ancien)"},
                            i -> gg.aurora.cosmetic.PetFx.legacyRibbons = i == 1, 160);
                    case "features" -> AbTest.start(FEATURES, DevWorld::feature, 180);
                    case "features4" -> AbTest.start(new String[]{"tout actif", "sans familier", "sans Hand Aura", "sans sol miroir"},
                            i -> DevWorld.feature(i == 0 ? 0 : i == 1 ? 3 : i == 2 ? 4 : 2), 200);
                    case "legacy" -> AbTest.start(new String[]{"nouveau code", "anciens chemins (familier, Hand Aura, ciel)"}, i -> {
                        gg.aurora.cosmetic.PetFx.legacyRibbons = i == 1;
                        gg.aurora.render.HandGlow.legacy = i == 1;
                        gg.aurora.render.SkyFx.legacySphere = i == 1;
                    }, 200);
                    case "real" -> AbTest.start(new String[]{"mod coupé", "config réelle", "sans ciel", "sans HUD", "sans optimiseurs", "sans visuels"}, DevWorld::real, 240);
                    case "fxbatch" -> {
                        // tous les systèmes additifs actifs, pour que le regroupement ait du travail
                        gg.aurora.module.AmbientFx.AMBIENT.enabled = true;
                        gg.aurora.module.AmbientFx.ITEM_FLAMES.enabled = true;
                        AbTest.start(new String[]{"lot additif commun", "un lot par système (ancien)"}, i -> gg.aurora.render.FxBatch.legacy = i == 1, 200);
                    }
                    case "jelly" -> AbTest.start(new String[]{"points transformés une fois", "chaque coin transformé (ancien)"},
                            i -> gg.aurora.cosmetic.PetFx.legacyVerts = i == 1, 200);
                    case "aura" -> AbTest.start(new String[]{"Hand Aura instanciée", "36 copies CPU (ancien)"}, i -> gg.aurora.render.HandGlow.legacy = i == 1, 160);
                    default -> AbTest.start(new String[]{"A", "A (témoin)"}, i -> {}, 160);
                }
            }
            if (tick > 305 && AbTest.done()) mc.stop();
            return;
        }
        String prof = System.getProperty("aurora.devprof");
        if (prof != null) { // scène fixe et reproductible pour mesurer avant / après
            if (tick == 20) scene(mc, prof.equals("user"));
            if (tick == 30) animals(mc);
            if (tick == 60) Bench.start(20);
            if (tick > 80 && !Bench.running()) mc.stop();
            return;
        }
        if (Boolean.getBoolean("aurora.devsky")) {
            if (tick == 20) { cmd(mc, "time set 18000"); cmd(mc, "weather clear"); cmd(mc, "spreadplayers 60 60 0 1 false @p");
                gg.aurora.module.WorldModules.WEATHER.enabled = false;
                gg.aurora.module.WorldModules.CUSTOM_SKY.enabled = true; gg.aurora.module.WorldModules.CUSTOM_SKY.set("ambience", 3); }
            if (tick == 30) cmd(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 -30");
            if (tick == 50) { Settings.animations = false; gg.aurora.render.SkyFx.legacySphere = true; }
            if (tick == 58) shot(mc, "sky_old");
            if (tick == 59) gg.aurora.render.SkyFx.legacySphere = false;
            if (tick == 60) shot(mc, "sky_a");
            if (tick == 61) cmd(mc, "execute as @p at @s run tp @s ~ ~ ~ 120 -10");
            if (tick == 80) shot(mc, "sky_b");
            if (tick == 82) mc.stop();
            return;
        }
        if (Boolean.getBoolean("aurora.devshader")) {
            if (tick == 20) { cmd(mc, "time set 6000"); cmd(mc, "weather clear"); cmd(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 5");
                gg.aurora.module.WorldModules.WEATHER.enabled = false; }
            if (tick == 40) { Settings.worldMenu = true; mc.setScreen(new gg.aurora.ui.ClickGuiScreen()); }
            if (tick == 70) shot(mc, "sh_panel");
            if (tick == 72) { gg.aurora.ui.WorldPanel.close(); setCosmetics(true); Settings.pet = "ovni";
                mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK); }
            if (tick == 100) shot(mc, "sh_cosmetics");
            if (tick == 102) { Settings.pet = "verity"; cmd(mc, "item replace entity @p armor.chest with elytra");
                mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT); }
            if (tick == 104) cmd(mc, "spreadplayers 60 60 0 1 false @p");
            if (tick == 112) cmd(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 10");
            if (tick == 113) mc.player.setDeltaMovement(0.35, 0, 0.2);
            if (tick == 140) shot(mc, "sh_verity");
            if (tick == 142) { mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON); cmd(mc, "time set 18000");
                gg.aurora.module.WorldModules.CUSTOM_SKY.enabled = true; gg.aurora.module.WorldModules.CUSTOM_SKY.set("ambience", 3);
                cmd(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 -25"); }
            if (tick == 190) shot(mc, "sh_sky");
            if (tick == 192) { cmd(mc, "weather rain"); gg.aurora.module.VisualModules.MIRROR.enabled = true;
                gg.aurora.module.VisualModules.MIRROR.set("puddles", true); cmd(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 20"); }
            if (tick == 260) shot(mc, "sh_rain");
            if (tick == 262) mc.stop();
            return;
        }
        if (Boolean.getBoolean("aurora.devfx")) {
            if (tick == 20) { Settings.worldMenu = false; Settings.wings = ""; Settings.pet = "verity"; cmd(mc, "time set 6000"); cmd(mc, "weather clear");
                cmd(mc, "spreadplayers 60 60 0 1 false @p"); gg.aurora.module.ClarityModules.SHOCKWAVES.enabled = true;
                gg.aurora.module.ExtraModules.HIT_EFFECTS.enabled = true; gg.aurora.module.WorldModules.WEATHER.enabled = false;
                gg.aurora.module.VisualModules.MIRROR.enabled = false; }
            if (tick == 40) cmd(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 25");
            if (tick == 50) { var pos = p.position().add(p.getLookAngle().multiply(4, 0, 4)); gg.aurora.render.PostFx.shock(pos); gg.aurora.cosmetic.HitSlashes.devHit(p.getEyePosition().add(p.getLookAngle().scale(3.5))); }
            if (tick == 53) shot(mc, "f_shock");
            if (tick == 60) { gg.aurora.module.AmbientFx.AMBIENT.enabled = true; gg.aurora.module.AmbientFx.AMBIENT.set("style", 1); gg.aurora.module.AmbientFx.AMBIENT.set("count", 300); }
            if (tick == 75) shot(mc, "f_petals");
            if (tick == 76) { gg.aurora.module.AmbientFx.AMBIENT.enabled = false; gg.aurora.module.ClarityModules.RESCALE.enabled = true; }
            if (tick == 85) shot(mc, "f_rescale");
            if (tick == 86) { gg.aurora.module.ClarityModules.RESCALE.enabled = false; mc.setScreen(new gg.aurora.ui.ItemPacksScreen(new net.minecraft.client.gui.screens.TitleScreen(), null)); }
            if (tick == 115) shot(mc, "f_catalog");
            if (tick == 116) mc.setScreen(new gg.aurora.ui.KeyboardScreen(null, new net.minecraft.client.gui.screens.TitleScreen()));
            if (tick == 135) shot(mc, "f_keys");
            if (tick == 137) mc.stop();
            return;
        }
        if (Boolean.getBoolean("aurora.devcheck")) {
            if (tick == 20) { Settings.worldMenu = false; Settings.pet = "verity"; Settings.wings = ""; Settings.cape = "paranoia"; Settings.aura = ""; Settings.halo = "";
                cmd(mc, "time set 6000"); cmd(mc, "weather clear"); cmd(mc, "spreadplayers 60 60 0 1 false @p"); cmd(mc, "gamemode creative @p");
                cmd(mc, "effect give @p speed 300 1"); cmd(mc, "effect give @p strength 200 0");
                cmd(mc, "item replace entity @p armor.chest with elytra"); cmd(mc, "item replace entity @p armor.head with diamond_helmet");
                cmd(mc, "item replace entity @p weapon.mainhand with golden_apple 5"); cmd(mc, "give @p golden_apple 12");
                gg.aurora.module.Modules.POTIONS.enabled = true; gg.aurora.module.Modules.ARMOR.enabled = true; }
            if (tick == 40) mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
            if (tick == 75) shot(mc, "c_front");
            if (tick == 76) mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
            if (tick == 95) shot(mc, "c_back");
            if (tick == 96) { mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON); mc.setScreen(new gg.aurora.ui.ClickGuiScreen()); }
            if (tick == 125) shot(mc, "c_menu");
            if (tick == 126) mc.setScreen(new gg.aurora.ui.WardrobeScreen(new gg.aurora.ui.ClickGuiScreen()));
            if (tick == 130 && mc.screen instanceof gg.aurora.ui.WardrobeScreen w) w.debugCategory(6);
            if (tick == 150) shot(mc, "c_pets");
            if (tick == 151) mc.setScreen(null);
            if (tick == 152) { cmd(mc, "time set 18000"); cmd(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 25"); gg.aurora.module.Modules.FULLBRIGHT.enabled = false; }
            if (tick == 170) shot(mc, "c_dark");
            if (tick == 171) gg.aurora.module.Modules.FULLBRIGHT.enabled = true;
            if (tick == 235) shot(mc, "c_bright");
            if (tick == 237) mc.stop();
            return;
        }
        if (Boolean.getBoolean("aurora.devnew")) {
            if (tick == 20) { Settings.worldMenu = false; Settings.wings = ""; Settings.aura = ""; Settings.halo = ""; Settings.pet = ""; Settings.cape = "paranoia"; Settings.elytraCape = false;
                cmd(mc, "time set 6000"); cmd(mc, "weather clear"); cmd(mc, "clear @p");
                cmd(mc, "item replace entity @p armor.head with diamond_helmet[damage=340]"); cmd(mc, "item replace entity @p armor.chest with netherite_chestplate");
                gg.aurora.module.Modules.ARMOR.enabled = true; gg.aurora.module.WorldModules.WEATHER.enabled = false; }
            if (tick == 60) shot(mc, "n_hud");
            if (tick == 62) mc.setScreen(new gg.aurora.ui.ClickGuiScreen());
            if (tick == 80) shot(mc, "n_menu");
            if (tick == 82) { var gui = new gg.aurora.ui.ClickGuiScreen(); gui.debugTab(1); mc.setScreen(gui); }
            if (tick == 100) shot(mc, "n_menu_visuals");
            if (tick == 102) { Settings.elytraCape = true; mc.setScreen(new gg.aurora.ui.WardrobeScreen(null)); }
            if (tick == 125) shot(mc, "n_elytra");
            if (tick == 127) { Settings.elytraCape = false; mc.setScreen(new gg.aurora.ui.ItemPacksScreen(new net.minecraft.client.gui.screens.TitleScreen(), null)); }
            if (tick == 160) shot(mc, "n_catalog");
            if (tick == 161) { gg.aurora.CommunityPacks.devSample("[{id:'a1b2c3d4e5f60708',name:'Faithful PvP 32x',author:'Lelooser',size:900000,likes:128,views:2400,downloads:640,liked:true,mine:false},{id:'b1b2c3d4e5f60708',name:'Crystal Clear',author:'Nova',size:900000,likes:42,views:510,downloads:97,liked:false,mine:true},{id:'c1b2c3d4e5f60708',name:'A very long texture pack name for testing it',author:'Kira',size:900000,likes:3,views:40,downloads:9,liked:false,mine:false}]");
                var cat = new gg.aurora.ui.ItemPacksScreen(new net.minecraft.client.gui.screens.TitleScreen(), null); cat.debugCommunity(); mc.setScreen(cat); }
            if (tick == 185) shot(mc, "n_community");
            if (tick == 187) { mc.setScreen(null); cmd(mc, "execute at @p run fill ~-12 ~-4 ~-12 ~12 ~6 ~12 water"); cmd(mc, "execute as @p at @s run tp @s ~ ~1 ~ 0 10");
                gg.aurora.module.WorldModules.CLEAR_WATER.enabled = false; }
            if (tick == 215) shot(mc, "n_water_off");
            if (tick == 217) gg.aurora.module.WorldModules.CLEAR_WATER.enabled = true;
            if (tick == 240) shot(mc, "n_water_on");
            if (tick == 242) { gg.aurora.module.WorldModules.CLEAR_WATER.enabled = false; cmd(mc, "execute at @p run fill ~-14 ~-6 ~-14 ~14 ~8 ~14 air replace water"); cmd(mc, "time set 18000");
                cmd(mc, "execute as @p at @s run tp @s ~ ~ ~ 180 10"); Settings.pet = "ovni"; mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK); }
            if (tick == 295) shot(mc, "n_pet_ufo");
            if (tick == 297) Settings.pet = "meduse";
            if (tick == 335) shot(mc, "n_pet_other");
            if (tick == 337) { Settings.pet = ""; mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON); mc.stop(); }
            return;
        }
        if (Boolean.getBoolean("aurora.devprof")) { // a real session for the profiler: HUD, cosmetics, sky and world effects on
            if (tick == 20) { Settings.worldMenu = false; Settings.cape = "paranoia"; Settings.pet = "ovni"; Settings.elytraCape = false;
                cmd(mc, "time set 6000"); cmd(mc, "weather clear"); cmd(mc, "tp @p 60 90 60 45 15");
                cmd(mc, "item replace entity @p armor.head with diamond_helmet"); cmd(mc, "item replace entity @p armor.chest with netherite_chestplate");
                for (var m : gg.aurora.module.Modules.ALL) if (m.category == gg.aurora.module.Category.HUD) m.enabled = true;
                gg.aurora.module.WorldModules.CUSTOM_SKY.enabled = true; gg.aurora.module.ExtraModules.HIT_EFFECTS.enabled = true;
                gg.aurora.module.PerfModules.HUD_CACHE.enabled = true; mc.options.framerateLimit().set(260); }
            if (tick == 1500) mc.stop();
            return;
        }
        if (Boolean.getBoolean("aurora.devfresh")) { // nouveau joueur : modules par défaut contre tout coupé, même scène, FPS non bridés
            if (tick == 20) { Settings.worldMenu = false; Settings.pet = ""; Settings.wings = ""; Settings.aura = ""; Settings.halo = ""; Settings.cape = "";
                cmd(mc, "time set 6000"); cmd(mc, "weather clear"); cmd(mc, "tp @p 60 90 60 45 15"); cmd(mc, "gamerule doMobSpawning false");
                mc.options.framerateLimit().set(260); mc.options.enableVsync().set(false); mc.options.renderDistance().set(8); mc.levelRenderer.allChanged();
                mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK); }
            if (tick == 60) { for (int i = 0; i < 8; i++) cmd(mc, "execute at @p run summon armor_stand ~" + (i % 4 * 2 - 3) + " ~ ~" + (i / 4 * 2 + 4)); for (int i = 0; i < 4; i++) cmd(mc, "execute at @p run summon zombie ~" + (i * 2 - 4) + " ~ ~9 {NoAI:1b}"); }
            String[] names = {"off", "defaults", "cosmetics", "off", "defaults", "cosmetics", "off", "defaults", "cosmetics"};
            int at = tick - 600, phase = at / 300, in = at % 300;
            if (at >= 0 && in == 0) {
                if (phase >= names.length) { try { java.nio.file.Files.createDirectories(mc.gameDirectory.toPath().resolve("paranoia-bench")); java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("paranoia-bench/fresh.txt"), AB); } catch (Exception ignored) {} mc.stop(); return; }
                boolean def = !names[phase].equals("off"), cos = names[phase].equals("cosmetics");
                for (var m : gg.aurora.module.Modules.ALL) m.enabled = def && m.def;
                Settings.cape = cos ? "paranoia" : ""; Settings.pet = cos ? "ovni" : ""; Settings.wings = cos ? "demon" : "";
                Settings.aura = cos ? gg.aurora.cosmetic.Cosmetics.of(gg.aurora.cosmetic.Cosmetics.Type.AURA).get(0).id() : "";
                Settings.halo = cos ? gg.aurora.cosmetic.Cosmetics.of(gg.aurora.cosmetic.Cosmetics.Type.HALO).get(0).id() : "";
                abSum = 0; abN = 0;
            }
            if (at >= 0 && in >= 80) { abSum += mc.getFps(); abN++; }
            if (at >= 0 && in == 299) AB += names[phase] + " " + Math.round(abSum / (double) abN) + " fps" + System.lineSeparator();
            return;
        }
        if (Boolean.getBoolean("aurora.devab")) { // same session, alternating setups: fixed cost of each one, uncapped
            if (tick == 20) { Settings.worldMenu = false; Settings.pet = ""; Settings.wings = ""; Settings.aura = ""; Settings.halo = ""; Settings.cape = "";
                cmd(mc, "time set 6000"); cmd(mc, "weather clear"); cmd(mc, "tp @p 60 90 60 45 15");
                for (var m : gg.aurora.module.Modules.ALL) m.enabled = false;
                gg.aurora.module.Modules.byId("fps").enabled = true; gg.aurora.module.WorldModules.OPTIMIZER.enabled = true;
                mc.options.framerateLimit().set(260); mc.options.enableVsync().set(false); mc.options.renderDistance().set(5); mc.levelRenderer.allChanged(); }
            String[] names = {"base", "hud_cache", "adaptive", "cape", "base", "hud_cache", "adaptive", "cape", "base", "hud_cache"};
            int at = tick - 200, phase = at / 160, in = at % 160;
            if (at >= 0 && in == 0) {
                if (phase >= names.length) { try { java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("paranoia-bench/ab.txt"), AB); } catch (Exception ignored) {} mc.stop(); return; }
                String n = names[phase];
                gg.aurora.module.PerfModules.HUD_CACHE.enabled = n.equals("hud_cache");
                gg.aurora.module.Modules.byId("adaptive_mode").enabled = n.equals("adaptive");
                Settings.cape = n.equals("cape") ? "hypergamy" : ""; Settings.elytraCape = n.equals("cape");
                abSum = 0; abN = 0;
            }
            if (at >= 0 && in >= 50) { abSum += mc.getFps(); abN++; }
            if (at >= 0 && in == 159) AB += names[phase] + " " + Math.round(abSum / (double) abN) + " fps" + System.lineSeparator();
            return;
        }
        if (Boolean.getBoolean("aurora.devhud")) {
            if (tick == 20) { Settings.worldMenu = false; Settings.cape = ""; Settings.pet = ""; cmd(mc, "time set 6000"); cmd(mc, "weather clear");
                cmd(mc, "scoreboard objectives add t dummy \"Test board\""); cmd(mc, "scoreboard objectives setdisplay sidebar t");
                for (int i = 0; i < 12; i++) cmd(mc, "scoreboard players set line" + i + " t " + i);
                cmd(mc, "bossbar add b \"Boss test\""); cmd(mc, "bossbar set b players @a");
                cmd(mc, "item replace entity @p weapon.offhand with shield");
                for (var m : gg.aurora.module.Modules.ALL) m.enabled = false;
                gg.aurora.module.Modules.byId("fps").enabled = true; gg.aurora.module.Modules.POTIONS.enabled = true; gg.aurora.module.ShieldModules.STATUS.enabled = true;
                gg.aurora.module.PerfModules.HUD_CACHE.enabled = true; mc.options.framerateLimit().set(260); }
            if (tick == 70) shot(mc, "h_board");
            if (tick == 72) { var e = new gg.aurora.ui.HudEditScreen(null); mc.setScreen(e); e.debugSelect(gg.aurora.module.Modules.POTIONS); }
            if (tick == 95) shot(mc, "h_edit");
            if (tick == 97) mc.setScreen(null);
            int at = tick - 110, phase = at / 160, in = at % 160;
            String[] names = {"direct", "cached", "direct", "cached", "direct", "cached"};
            if (at >= 0 && in == 0) {
                if (phase >= names.length) { try { java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("paranoia-bench/ab.txt"), AB); } catch (Exception ignored) {} mc.stop(); return; }
                gg.aurora.module.PerfModules.HUD_CACHE.enabled = names[phase].equals("cached"); abSum = 0; abN = 0;
            }
            if (at >= 0 && in >= 50) { abSum += mc.getFps(); abN++; }
            if (at >= 0 && in == 159) AB += names[phase] + " " + Math.round(abSum / (double) abN) + " fps" + System.lineSeparator();
            return;
        }
        if (Boolean.getBoolean("aurora.devall")) { // whole mod at once: every module and cosmetic, entities around
            if (tick == 20) { Settings.worldMenu = false; Settings.cape = "paranoia"; Settings.pet = "ovni"; Settings.wings = "demon"; Settings.aura = gg.aurora.cosmetic.Cosmetics.of(gg.aurora.cosmetic.Cosmetics.Type.AURA).get(0).id();
                Settings.halo = gg.aurora.cosmetic.Cosmetics.of(gg.aurora.cosmetic.Cosmetics.Type.HALO).get(0).id();
                cmd(mc, "time set 6000"); cmd(mc, "weather clear"); cmd(mc, "tp @p 60 90 60 45 15"); cmd(mc, "gamerule doMobSpawning false");
                cmd(mc, "item replace entity @p armor.head with diamond_helmet"); cmd(mc, "item replace entity @p armor.chest with netherite_chestplate"); cmd(mc, "item replace entity @p weapon.offhand with shield");
                for (var m : gg.aurora.module.Modules.ALL) m.enabled = !m.id.equals("adaptive_mode") && !m.id.equals("stream_mode") && !m.id.equals("rescale_resolution") && !m.id.equals("screen_limit") && !m.id.equals("menu_fps");
                mc.options.framerateLimit().set(260); mc.options.enableVsync().set(false); }
            if (tick == 60) for (int i = 0; i < 16; i++) cmd(mc, "execute at @p run summon armor_stand ~" + (i % 4 * 2 - 3) + " ~ ~" + (i / 4 * 2 + 3));
            if (tick == 60) for (int i = 0; i < 8; i++) cmd(mc, "execute at @p run summon zombie ~" + (i * 2 - 8) + " ~ ~9 {NoAI:1b}");
            if (tick == 100) shot(mc, "all_on");
            if (tick == 1300) mc.stop();
            return;
        }
        if (Boolean.getBoolean("aurora.devsweep")) { // each module against a nearby baseline, whole frame time (CPU and GPU)
            if (tick == 20) { Settings.worldMenu = false; Settings.cape = ""; Settings.pet = ""; Settings.wings = ""; Settings.aura = ""; Settings.halo = "";
                cmd(mc, "time set 6000"); cmd(mc, "weather clear"); cmd(mc, "tp @p 60 90 60 45 15"); cmd(mc, "gamerule doMobSpawning false");
                cmd(mc, "item replace entity @p armor.chest with netherite_chestplate"); cmd(mc, "item replace entity @p weapon.offhand with shield");
                for (var m : gg.aurora.module.Modules.ALL) m.enabled = false;
                gg.aurora.module.PerfModules.HUD_CACHE.enabled = true;
                mc.options.framerateLimit().set(260); mc.options.enableVsync().set(false); mc.options.renderDistance().set(5); }
            if (tick == 60) { for (int i = 0; i < 12; i++) cmd(mc, "execute at @p run summon armor_stand ~" + (i % 4 * 2 - 3) + " ~ ~" + (i / 4 * 2 + 3)); for (int i = 0; i < 6; i++) cmd(mc, "execute at @p run summon zombie ~" + (i * 2 - 6) + " ~ ~9 {NoAI:1b}"); }
            if (tick < 160) return;
            if (SWEEP == null && System.getProperty("aurora.sweepids") != null) { SWEEP = new java.util.ArrayList<>(); for (int rep = 0; rep < 5; rep++) for (String id : System.getProperty("aurora.sweepids").split(",")) SWEEP.add(gg.aurora.module.Modules.byId(id)); }
            if (SWEEP == null) { SWEEP = new java.util.ArrayList<>(); for (var m : gg.aurora.module.Modules.ALL) if (!(m instanceof gg.aurora.module.HudModule) && m != gg.aurora.module.PerfModules.HUD_CACHE && !m.id.equals("adaptive_mode") && !m.id.equals("screen_limit") && !m.id.equals("menu_fps") && !m.id.equals("rescale_resolution")) SWEEP.add(m); }
            int at = tick - 160, slot = at / 50, in = at % 50, group = slot / 5, pos = slot % 5; // baseline, then four modules
            int idx = group * 4 + pos - 1;
            if (pos != 0 && idx >= SWEEP.size() || group * 4 >= SWEEP.size() && pos == 0) {
                java.util.List<String> out = new java.util.ArrayList<>(SWEEP_OUT); java.util.Collections.sort(out, java.util.Collections.reverseOrder());
                try { java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("paranoia-bench/sweep.txt"), String.join(System.lineSeparator(), out)); } catch (Exception ignored) {}
                mc.stop(); return;
            }
            var cur = pos == 0 ? null : SWEEP.get(idx);
            if (in == 0) { for (var m : SWEEP) m.enabled = false; if (cur != null) cur.enabled = true; }
            if (in == 15) { abFrames = sweepFrames; abStart = System.nanoTime(); }
            if (in == 49) {
                double ms = (System.nanoTime() - abStart) / 1e6 / Math.max(1, sweepFrames - abFrames);
                if (cur == null) sweepBase = ms; else SWEEP_OUT.add(String.format(java.util.Locale.ROOT, "%+07.3f ms  %-26s (base %.3f)", ms - sweepBase, cur.id, sweepBase));
            }
            return;
        }
        if (Boolean.getBoolean("aurora.devcos")) { // moving camera: post effects and cosmetics against the baseline
            if (tick == 20) { Settings.worldMenu = false; Settings.cape = ""; Settings.pet = ""; Settings.wings = ""; Settings.aura = ""; Settings.halo = "";
                cmd(mc, "time set 6000"); cmd(mc, "weather clear"); cmd(mc, "tp @p 60 90 60 45 15"); cmd(mc, "gamerule doMobSpawning false");
                cmd(mc, "item replace entity @p weapon.offhand with shield");
                for (var m : gg.aurora.module.Modules.ALL) m.enabled = false;
                gg.aurora.module.PerfModules.HUD_CACHE.enabled = true; mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
                mc.options.framerateLimit().set(260); mc.options.enableVsync().set(false); mc.options.renderDistance().set(5); }
            if (tick < 120) return;
            p.setYRot(p.getYRot() + 4);
            String[] set = {"base", "pet:ovni", "pet:meduse", "pet:verity", "wings:demon", "aura", "halo", "cape:paranoia", "m:motion_blur", "m:ground_mirror", "m:filters", "m:custom_sky", "m:weather", "m:ambient_particles", "m:hand_aura", "m:hitboxes", "m:friend_glow", "m:hologram", "m:jump_circles", "m:shockwaves"};
            int at = tick - 120, slot = at / 45, in = at % 45, round = slot / (set.length * 2), pos = slot % (set.length * 2);
            if (round >= 3) {
                try { java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("paranoia-bench/cos.txt"), AB); } catch (Exception ignored) {}
                mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON); mc.stop(); return;
            }
            String cur = pos % 2 == 0 ? "base" : set[pos / 2];
            if (in == 0) {
                for (var m : gg.aurora.module.Modules.ALL) if (m != gg.aurora.module.PerfModules.HUD_CACHE) m.enabled = false;
                Settings.pet = ""; Settings.wings = ""; Settings.aura = ""; Settings.halo = ""; Settings.cape = "";
                if (cur.startsWith("pet:")) Settings.pet = cur.substring(4);
                if (cur.startsWith("wings:")) Settings.wings = cur.substring(6);
                if (cur.startsWith("cape:")) Settings.cape = cur.substring(5);
                if (cur.equals("aura")) Settings.aura = gg.aurora.cosmetic.Cosmetics.of(gg.aurora.cosmetic.Cosmetics.Type.AURA).get(0).id();
                if (cur.equals("halo")) Settings.halo = gg.aurora.cosmetic.Cosmetics.of(gg.aurora.cosmetic.Cosmetics.Type.HALO).get(0).id();
                if (cur.startsWith("m:")) gg.aurora.module.Modules.byId(cur.substring(2)).enabled = true;
            }
            if (in == 12) { abFrames = sweepFrames; abStart = System.nanoTime(); }
            if (in == 30 && round == 0 && !cur.startsWith("m:") && !cur.equals("base")) shot(mc, "c_" + cur.replace(":", "_"));
            if (in == 44) {
                double ms = (System.nanoTime() - abStart) / 1e6 / Math.max(1, sweepFrames - abFrames);
                if (cur.equals("base")) { sweepBase = ms; AB += "BASE " + String.format(java.util.Locale.ROOT, "%.3f", ms) + System.lineSeparator(); } else AB += cur + " " + String.format(java.util.Locale.ROOT, "%.3f", ms - sweepBase) + System.lineSeparator();
            }
            return;
        }
        if (Boolean.getBoolean("aurora.devclean")) { // removes what the other scripts left in the test world
            if (tick == 20) { cmd(mc, "kill @e[type=!player]"); cmd(mc, "scoreboard objectives remove t"); cmd(mc, "bossbar remove b"); cmd(mc, "clear @p"); cmd(mc, "effect clear @p"); }
            if (tick == 60) mc.stop();
            return;
        }
        if (Boolean.getBoolean("aurora.devmax")) { // highest possible FPS: empty sky, tiny distance, fixed cost of the mod
            if (tick == 20) { Settings.worldMenu = false; Settings.cape = ""; Settings.pet = ""; Settings.wings = ""; Settings.aura = ""; Settings.halo = "";
                cmd(mc, "time set 6000"); cmd(mc, "weather clear"); cmd(mc, "tp @p 60 310 60 0 -90");
                for (var m : gg.aurora.module.Modules.ALL) m.enabled = false;
                mc.options.framerateLimit().set(260); mc.options.enableVsync().set(false); mc.options.renderDistance().set(2); mc.options.hideGui = false; }
            if (tick < 120) return;
            if (Boolean.getBoolean("aurora.maxseries")) { // nothing changes: does the frame rate drift by itself?
                int t2 = tick - 120;
                if (t2 % 100 == 0) { abFrames = sweepFrames; abStart = System.nanoTime(); }
                if (t2 % 100 == 99) { var rt = Runtime.getRuntime(); AB += (t2 / 20) + "s " + Math.round((sweepFrames - abFrames) / ((System.nanoTime() - abStart) / 1e9)) + " fps heap " + (rt.totalMemory() - rt.freeMemory() >> 20) + " MB" + System.lineSeparator(); }
                if (t2 >= 1100) { try { java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("paranoia-bench/max.txt"), AB); } catch (Exception ignored) {} mc.stop(); }
                return;
            }
            String[] set = {"nothing", "hud_cache", "fps_widget", "fps_widget+cache", "nothing", "hud_cache", "fps_widget", "fps_widget+cache", "nothing", "hud_cache", "fps_widget", "fps_widget+cache"};
            int at = tick - 120, slot = at / 80, in = at % 80;
            if (slot >= set.length) {
                try { java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("paranoia-bench/max.txt"), AB); } catch (Exception ignored) {}
                mc.stop(); return;
            }
            String cur = set[slot];
            if (in == 0) { gg.aurora.module.PerfModules.HUD_CACHE.enabled = cur.contains("cache"); gg.aurora.module.Modules.byId("fps").enabled = cur.contains("fps"); }
            if (in == 15) { abFrames = sweepFrames; abStart = System.nanoTime(); }
            if (in == 79) { double ms = (System.nanoTime() - abStart) / 1e6 / Math.max(1, sweepFrames - abFrames); AB += cur + " " + Math.round(1000 / ms) + " fps " + String.format(java.util.Locale.ROOT, "%.3f", ms) + " ms" + System.lineSeparator(); }
            return;
        }
        if (Boolean.getBoolean("aurora.devset")) { // the whole module set of an instance, in one session
            String[] ids = {"totems", "fps", "potions", "armor", "fullbright", "item_size", "custom_sky", "weather", "optimizer", "crystal_optimizer", "gap_optimizer", "anchor_optimizer", "friend_glow", "low_fire", "hologram", "shield_size", "hand_aura", "jump_circles"};
            if (tick == 20) { Settings.worldMenu = false; Settings.cape = ""; Settings.pet = ""; Settings.wings = ""; Settings.aura = ""; Settings.halo = "";
                cmd(mc, "time set 6000"); cmd(mc, "weather clear"); cmd(mc, "tp @p 60 90 60 45 15"); cmd(mc, "effect give @p speed 900 1");
                for (var m : gg.aurora.module.Modules.ALL) m.enabled = false;
                gg.aurora.module.PerfModules.HUD_CACHE.enabled = true;
                mc.options.framerateLimit().set(260); mc.options.enableVsync().set(false); mc.options.renderDistance().set(5); }
            if (tick < 160) return;
            String[] set = {"none", "all", "all-hand_aura", "all-nocache", "none", "all", "all-hand_aura", "all-nocache", "none", "all", "all-hand_aura", "all-nocache", "none", "all", "all-hand_aura", "all-nocache"};
            int at = tick - 160, slot = at / 100, in = at % 100;
            if (slot >= set.length) { try { java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("paranoia-bench/set.txt"), AB); } catch (Exception ignored) {} mc.stop(); return; }
            String cur = set[slot];
            if (in == 0) {
                for (String id : ids) gg.aurora.module.Modules.byId(id).enabled = !cur.equals("none");
                if (cur.equals("all-hand_aura")) gg.aurora.module.Modules.byId("hand_aura").enabled = false;
                gg.aurora.module.PerfModules.HUD_CACHE.enabled = !cur.equals("all-nocache");
            }
            if (slot == 1 && in == 60) shot(mc, "aura_one_draw");
            if (in == 30) { abFrames = sweepFrames; abStart = System.nanoTime(); }
            if (in == 99) AB += cur + " " + Math.round((sweepFrames - abFrames) / ((System.nanoTime() - abStart) / 1e9)) + System.lineSeparator();
            return;
        }
        if (Boolean.getBoolean("aurora.devvkfx")) { // effets d'écran un par un (capture externe, voir tools/vk_capture.ps1)
            final int P = 140;
            if (tick == 20) { scene(mc, false); animals(mc); cmd(mc, "weather rain"); }
            int stage = tick < 40 ? -1 : (tick - 40) / P, at = (tick - 40) % P;
            String[] names = {"fx_mirror", "fx_blur", "fx_shock", "fx_filters", "fx_fsr", "fx_menu3d", "fx_after"};
            if (stage >= names.length) { if (at == 0) mc.stop(); return; }
            if (stage >= 0 && at == 0) {
                if (mc.screen != null) mc.setScreen(null);
                gg.aurora.module.VisualModules.MIRROR.enabled = false; gg.aurora.module.VisualModules.MOTION_BLUR.enabled = false;
                gg.aurora.module.ClarityModules.SHOCKWAVES.enabled = false; gg.aurora.module.CombatModules.FILTERS.enabled = false;
                Settings.upscaler = 0; gg.aurora.module.WorldModules.WEATHER.enabled = false;
                switch (names[stage]) {
                    case "fx_mirror" -> { gg.aurora.module.VisualModules.MIRROR.enabled = true; gg.aurora.module.VisualModules.MIRROR.set("puddles", true);
                        cmd(mc, "execute as @p at @s run tp @s ~ ~ ~ 45 22"); }
                    case "fx_blur" -> gg.aurora.module.VisualModules.MOTION_BLUR.enabled = true;
                    case "fx_shock" -> gg.aurora.module.ClarityModules.SHOCKWAVES.enabled = true;
                    case "fx_filters" -> gg.aurora.module.CombatModules.FILTERS.enabled = true;
                    case "fx_fsr" -> Settings.upscaler = 1;
                    case "fx_menu3d" -> mc.setScreen(new gg.aurora.ui.ClickGuiScreen());
                    default -> {}
                }
            }
            if (stage == 1 && at > 5 && mc.player != null) mc.player.setYRot(mc.player.getYRot() + 7);
            if (stage == 2 && at % 12 == 0 && mc.player != null) gg.aurora.render.PostFx.shock(mc.player.position().add(mc.player.getLookAngle().scale(5)));
            int shootAt = stage == 5 ? 3 : 50;
            if (stage >= 0 && at == shootAt) {
                try { java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("vk-stage.txt"), names[stage]); } catch (Exception ignored) {}
            }
            return;
        }
        if (Boolean.getBoolean("aurora.devvk")) { // tour visuel pour VulkanMod : capture externe (tools/vk_capture.ps1) via run/vk-stage.txt
            final int P = 70; // ticks par pose
            if (tick == 20) { scene(mc, true); animals(mc); Settings.cosmeticQuality = 0;
                gg.aurora.module.Modules.POTIONS.enabled = true; gg.aurora.module.Modules.ARMOR.enabled = true; gg.aurora.module.Modules.FPS.enabled = true;
                cmd(mc, "effect give @p speed 600 1"); cmd(mc, "item replace entity @p armor.head with diamond_helmet");
                cmd(mc, "item replace entity @p armor.chest with netherite_chestplate"); cmd(mc, "item replace entity @p weapon.mainhand with diamond_sword"); }
            int stage = tick < 40 ? -1 : (tick - 40) / P, at = (tick - 40) % P;
            String[] names = {"hud_sky", "third_person", "front_cosmetics", "menu", "wardrobe", "hudedit", "keyboard", "world_menu", "shulker", "motion_blur"};
            if (stage >= Integer.getInteger("aurora.devvkstages", names.length)) { if (at == 0) mc.stop(); return; }
            if (stage >= 0 && at == 0) {
                if (mc.screen != null && stage != 8) mc.setScreen(null);
                gg.aurora.ui.WorldPanel.close();
                mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                switch (names[stage]) {
                    case "third_person" -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
                    case "front_cosmetics" -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
                    case "menu" -> mc.setScreen(new gg.aurora.ui.ClickGuiScreen());
                    case "wardrobe" -> mc.setScreen(new gg.aurora.ui.WardrobeScreen(null));
                    case "hudedit" -> mc.setScreen(new gg.aurora.ui.HudEditScreen(null));
                    case "keyboard" -> mc.setScreen(new gg.aurora.ui.KeyboardScreen(null));
                    case "world_menu" -> { Settings.worldMenu = true; mc.setScreen(new gg.aurora.ui.ClickGuiScreen()); }
                    case "shulker" -> { Settings.worldMenu = false; gg.aurora.module.ShulkerViewer.MODULE.enabled = true;
                        var items = new java.util.ArrayList<net.minecraft.world.item.ItemStack>();
                        for (int i = 0; i < 27; i++) items.add(new net.minecraft.world.item.ItemStack(i % 3 == 0 ? net.minecraft.world.item.Items.END_CRYSTAL
                                : i % 3 == 1 ? net.minecraft.world.item.Items.OBSIDIAN : net.minecraft.world.item.Items.TOTEM_OF_UNDYING, i % 3 == 2 ? 1 : 64));
                        var box = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PURPLE_SHULKER_BOX);
                        box.set(net.minecraft.core.component.DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(items));
                        mc.setScreen(new TooltipProbe(box)); }
                    case "motion_blur" -> { gg.aurora.module.VisualModules.MOTION_BLUR.enabled = true;
                        cmd(mc, "execute as @p at @s run tp @s ~ ~ ~ ~90 8"); }
                    default -> {}
                }
            }
            if (stage == 9 && at > 5 && mc.player != null) mc.player.setYRot(mc.player.getYRot() + 6); // rotation continue pour le flou
            if (stage >= 0 && at == P - 15) {
                try { java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("vk-stage.txt"), names[stage]); } catch (Exception ignored) {}
            }
            return;
        }
        if (Boolean.getBoolean("aurora.devscroll")) { // smooth scroll: the list entries must follow the scrollbar
            if (tick == 40) { gg.aurora.module.ClarityModules.SMOOTH_SCROLL.enabled = true;
                mc.setScreen(new net.minecraft.client.gui.screens.options.VideoSettingsScreen(null, mc, mc.options)); }
            if (tick == 60) shot(mc, "scroll_0");
            if (tick == 62 && mc.screen != null) { int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
                for (int i = 0; i < 6; i++) mc.screen.mouseScrolled(w / 2.0, h / 2.0, 0, -1); }
            if (tick == 90) shot(mc, "scroll_1");
            if (tick == 92) mc.stop();
            return;
        }
        if (Boolean.getBoolean("aurora.devui")) {
            if (tick == 20) { cmd(mc, "time set 6000"); cmd(mc, "weather clear");
                cmd(mc, "effect give @p speed 300 1"); cmd(mc, "effect give @p strength 200 0"); cmd(mc, "effect give @p fire_resistance 90 0");
                cmd(mc, "item replace entity @p armor.head with diamond_helmet"); cmd(mc, "item replace entity @p armor.chest with netherite_chestplate");
                cmd(mc, "item replace entity @p armor.feet with golden_boots"); cmd(mc, "give @p golden_apple 12"); cmd(mc, "give @p ender_pearl 16");
                cmd(mc, "item replace entity @p weapon.mainhand with golden_apple 5");
                gg.aurora.module.Modules.POTIONS.enabled = true; gg.aurora.module.Modules.ARMOR.enabled = true; }
            if (tick == 60) shot(mc, "ui_hud");
            if (tick == 62) mc.setScreen(new gg.aurora.ui.ClickGuiScreen());
            if (tick == 80) shot(mc, "ui_menu");
            if (tick == 82) mc.setScreen(new gg.aurora.ui.KeyboardScreen(null));
            if (tick == 100) shot(mc, "ui_keyboard");
            if (tick == 102) mc.setScreen(new gg.aurora.ui.WardrobeScreen(new gg.aurora.ui.ClickGuiScreen()));
            if (tick == 125) shot(mc, "ui_wardrobe");
            if (tick == 127) mc.setScreen(new gg.aurora.ui.HudEditScreen(null));
            if (tick == 145) shot(mc, "ui_hudedit");
            if (tick == 147) mc.setScreen(new gg.aurora.ui.ItemPacksScreen(new net.minecraft.client.gui.screens.TitleScreen(), null));
            if (tick == 175) shot(mc, "ui_catalog");
            if (tick == 177) mc.stop();
            return;
        }
        if (Boolean.getBoolean("aurora.devpacks")) {
            if (tick == 20) { cmd(mc, "time set 6000"); cmd(mc, "weather clear"); cmd(mc, "item replace entity @p weapon.mainhand with diamond_sword");
                Settings.itemPacks = "cat:swords=TestRouge.zip"; mc.reloadResourcePacks(); }
            if (tick == 120) shot(mc, "pack_sword");
            if (tick == 125) mc.setScreen(new gg.aurora.ui.ItemPacksScreen(new net.minecraft.client.gui.screens.TitleScreen(), null));
            if (tick == 150) shot(mc, "pack_screen");
            if (tick == 152) { Settings.itemPacks = ""; mc.stop(); }
            return;
        }
        if (Boolean.getBoolean("aurora.devbench")) { // tout coupé, en l'air : coût fixe du mod
            if (tick == 20) { cmd(mc, "time set 6000"); cmd(mc, "weather clear"); cmd(mc, "execute as @p at @s run tp @s ~ 300 ~ 0 -30");
                for (var m : gg.aurora.module.Modules.ALL) if (!(m instanceof gg.aurora.module.HudModule)) m.enabled = false;
                setCosmetics(false); Settings.worldMenu = false; }
            if (tick == 70) shot(mc, "hud_cache");
            if (tick == 80) Bench.start(20);
            if (tick > 100 && !Bench.running()) mc.stop();
            return;
        }
        if (Boolean.getBoolean("aurora.devhold")) { // longues poses pour captures d'écran externes (VulkanMod)
            if (tick == 20) { cmd(mc, "time set 6000"); cmd(mc, "weather clear"); cmd(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 0"); }
            if (tick == 30) { Settings.worldMenu = true; mc.setScreen(new gg.aurora.ui.ClickGuiScreen()); }
            if (tick == 330) { gg.aurora.ui.WorldPanel.close(); cmd(mc, "weather rain"); gg.aurora.module.WorldModules.WEATHER.enabled = false;
                gg.aurora.module.VisualModules.MIRROR.enabled = true; gg.aurora.module.VisualModules.MIRROR.set("puddles", true);
                cmd(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 18"); }
            if (tick == 700) mc.stop();
            return;
        }
        if (tick == 20) { cmd(mc, "time set 18000"); cmd(mc, "weather clear"); cmd(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 0"); }
        if (tick == 30) { Settings.worldMenu = true; mc.setScreen(new gg.aurora.ui.ClickGuiScreen()); }
        if (tick == 45) shot(mc, "wp_front");
        if (tick == 46) cmd(mc, "execute as @p at @s run tp @s ~-1.6 ~ ~0.9 -55 8");
        if (tick == 60) shot(mc, "wp_side");
        if (tick == 61) cmd(mc, "execute as @p at @s run tp @s ~1.6 ~ ~-2.2 0 0");
        if (tick == 75) shot(mc, "wp_near");
        if (tick == 76) cmd(mc, "execute as @p at @s run tp @s ~ ~ ~4.2 180 0");
        if (tick == 90) shot(mc, "wp_back");
        if (tick == 91) { gg.aurora.ui.WorldPanel.close(); cmd(mc, "execute as @p at @s run tp @s ~ ~ ~-1 0 0"); cmd(mc, "weather rain"); gg.aurora.module.WorldModules.WEATHER.enabled = false;
            gg.aurora.module.VisualModules.MIRROR.enabled = true; gg.aurora.module.VisualModules.MIRROR.set("puddles", true);
            cmd(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 18"); }
        if (tick == 200) shot(mc, "rain_puddles");
        if (tick == 201) cmd(mc, "weather clear");
        if (tick == 340) shot(mc, "dry_after");
        if (tick == 341) { cmd(mc, "execute as @p at @s run fill ~-3 ~-1 ~1 ~3 ~-1 ~6 air"); cmd(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 62"); }
        if (tick == 360) shot(mc, "ssr_down");
        if (tick == 362) mc.stop();
        switch (tick) {
            default -> {}
        }
    }

    static final String[] FEATURES = {"tout actif", "sans ciel custom", "sans sol miroir", "sans familier", "sans Hand Aura",
            "sans traînée", "sans ailes/aura/halo/cape", "sans HUD Paranoia", "sans Block Outline"};

    /** Variante i : tout actif sauf une fonctionnalité (coût total réel de celle-ci, CPU + GPU). */
    private static void feature(int i) {
        gg.aurora.module.WorldModules.CUSTOM_SKY.enabled = i != 1;
        gg.aurora.module.VisualModules.MIRROR.enabled = i != 2;
        Settings.pet = i == 3 ? "" : "meduse";
        gg.aurora.module.VisualModules.HAND_AURA.enabled = i != 4;
        gg.aurora.module.ClientModules.TRAIL.enabled = i != 5;
        Settings.wings = i == 6 ? "" : "paranoia";
        Settings.aura = i == 6 ? "" : "braise";
        Settings.halo = i == 6 ? "" : "paranoia";
        Settings.cape = i == 6 ? "" : "johnpork";
        for (var h : gg.aurora.module.Modules.hudModules()) if (h.def) h.enabled = i != 7;
        gg.aurora.module.Modules.CPS.enabled = i != 7;
        gg.aurora.module.WorldModules.BLOCK_OUTLINE.enabled = i != 8;
    }

    /** Scène de mesure : « user » = réglages par défaut + cosmétiques, ciel, sol miroir, Hand Aura ; sinon tout coupé. */
    /** Modules actifs dans la vraie config du joueur (instance du launcher, 13/11). */
    private static final String[] REAL = {"fps", "cps", "potions", "armor", "fullbright", "item_size", "item_physic", "custom_sky", "weather",
            "optimizer", "smart_culling", "low_fire", "auto_respawn", "death_coords", "shield_size", "adaptive_detail", "target_esp", "chat_plus",
            "combat_sounds", "fog_controls", "auto_gg", "saturation", "no_view_bobbing", "adaptive_mode", "hud_cache", "shulker_viewer"};

    private static void real(int variant) {
        java.util.Set<String> skip = switch (variant) {
            case 2 -> java.util.Set.of("custom_sky");
            case 3 -> java.util.Set.of("fps", "cps", "potions", "armor", "saturation", "hud_cache");
            case 4 -> java.util.Set.of("optimizer", "smart_culling", "adaptive_detail", "adaptive_mode");
            case 5 -> java.util.Set.of("item_physic", "target_esp", "fullbright", "fog_controls", "item_size", "low_fire", "shield_size", "weather");
            default -> java.util.Set.of();
        };
        for (var m : gg.aurora.module.Modules.ALL) m.enabled = false;
        if (variant == 0) return;
        for (String id : REAL) {
            var m = gg.aurora.module.Modules.byId(id);
            if (m != null && !skip.contains(id)) m.enabled = true;
        }
        gg.aurora.module.WorldModules.CUSTOM_SKY.set("ambience", 4);
        gg.aurora.module.WorldModules.CUSTOM_SKY.set("cached", true);
    }

    private static void scene(Minecraft mc, boolean user) {
        cmd(mc, "gamerule doDaylightCycle false"); cmd(mc, "gamerule doWeatherCycle false"); cmd(mc, "gamerule doMobSpawning false");
        cmd(mc, "time set 6000"); cmd(mc, "weather clear"); cmd(mc, "kill @e[type=!player]");
        cmd(mc, "spreadplayers 60 60 0 1 false @p");
        for (var m : gg.aurora.module.Modules.ALL) m.enabled = user && m.def;
        setCosmetics(user);
        Settings.worldMenu = false;
        gg.aurora.module.WorldModules.WEATHER.enabled = false;
        if (user) {
            gg.aurora.module.WorldModules.CUSTOM_SKY.enabled = true;
            gg.aurora.module.WorldModules.CUSTOM_SKY.set("ambience", 3);
            gg.aurora.module.VisualModules.MIRROR.enabled = true;
            gg.aurora.module.VisualModules.HAND_AURA.enabled = true;
            gg.aurora.module.Modules.CPS.enabled = true;
            gg.aurora.module.WorldModules.BLOCK_OUTLINE.enabled = true;
        }
    }

    /** 24 animaux immobiles autour du joueur, caméra fixe. */
    private static void animals(Minecraft mc) {
        cmd(mc, "execute as @p at @s run tp @s ~ ~ ~ 45 8");
        for (int i = 0; i < 24; i++) {
            double a = i * Math.PI * 2 / 24, r = 4 + (i % 3) * 3;
            cmd(mc, String.format(java.util.Locale.ROOT, "execute as @p at @s run summon %s ~%.1f ~ ~%.1f {NoAI:1b,Silent:1b}",
                    i % 3 == 0 ? "villager" : "cow", Math.cos(a) * r, Math.sin(a) * r));
        }
    }

    static void setCosmetics(boolean on) {
        Settings.wings = on ? "paranoia" : "";
        Settings.aura = on ? "braise" : "";
        Settings.halo = on ? "paranoia" : "";
        Settings.pet = on ? "meduse" : "";
        Settings.cape = on ? "johnpork" : "";
        gg.aurora.module.ClientModules.TRAIL.enabled = on;
    }

    // ---- frametimes (appelé à chaque image par le rendu)
    private static final long[] FRAMES = new long[200_000];
    private static int frameCount = -1;
    private static long lastFrame;

    public static void frame() {
        AbTest.frame();
        if (!ENABLED || frameCount < 0) return;
        long now = System.nanoTime();
        if (lastFrame != 0 && frameCount < FRAMES.length) FRAMES[frameCount++] = now - lastFrame;
        lastFrame = now;
    }

    private static void startFrames() {
        frameCount = 0;
        lastFrame = 0;
    }

    private static void report(String label) {
        int n = frameCount;
        frameCount = -1;
        long[] f = java.util.Arrays.copyOf(FRAMES, n);
        java.util.Arrays.sort(f);
        double sum = 0;
        for (long v : f) sum += v;
        double avgMs = sum / n / 1e6;
        // 1 % low = FPS moyen des 1 % d'images les plus lentes
        double low1 = 0, low01 = 0;
        int k1 = Math.max(1, n / 100), k01 = Math.max(1, n / 1000);
        for (int i = n - k1; i < n; i++) low1 += f[i];
        for (int i = n - k01; i < n; i++) low01 += f[i];
        low1 /= k1;
        low01 /= k01;
        AuroraClient.LOGGER.info(String.format("FRAMES %s n=%d avg=%.3fms (%.0f fps) p50=%.3fms p99=%.3fms 1%%low=%.0f fps 0.1%%low=%.0f fps max=%.2fms",
                label, n, avgMs, 1000 / avgMs, f[n / 2] / 1e6, f[(int) (n * 0.99)] / 1e6, 1e9 / low1, 1e9 / low01, f[n - 1] / 1e6));
        var rt = Runtime.getRuntime();
        AuroraClient.LOGGER.info("HEAP " + label + " used=" + (rt.totalMemory() - rt.freeMemory()) / 1048576 + "MB total=" + rt.totalMemory() / 1048576 + "MB");
    }

    // ---- JFR (profil CPU / allocations / GC)
    private static jdk.jfr.Recording rec;

    private static void startJfr() {
        try {
            rec = new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"));
            rec.start();
        } catch (Exception e) {
            AuroraClient.LOGGER.warn("JFR: {}", e.toString());
        }
    }

    private static void stopJfr(Minecraft mc) {
        if (rec == null) return;
        try {
            rec.dump(mc.gameDirectory.toPath().resolve("aurora_profile.jfr"));
            rec.stop();
        } catch (Exception e) {
            AuroraClient.LOGGER.warn("JFR: {}", e.toString());
        }
    }

    private static FriendsScreen screen;

    /** Capture avec le temps figé (même pose du familier pour comparer deux rendus). */
    private static void shotFrozen(Minecraft mc, String name) {
        gg.aurora.cosmetic.PetFx.freezeTime = 1234.5f;
        shot(mc, name);
    }

    /** Écran de test : affiche le tooltip d'un objet comme au survol dans l'inventaire. */
    private static final class TooltipProbe extends net.minecraft.client.gui.screens.Screen {
        private final net.minecraft.world.item.ItemStack stack;

        TooltipProbe(net.minecraft.world.item.ItemStack stack) {
            super(net.minecraft.network.chat.Component.literal("probe"));
            this.stack = stack;
        }

        @Override
        public void render(net.minecraft.client.gui.GuiGraphics g, int mx, int my, float pt) {
            super.render(g, mx, my, pt);
            g.renderItem(stack, width / 2 - 8, height / 2 - 30);
            g.setTooltipForNextFrame(font, stack, width / 2 - 8, height / 2 - 20);
        }
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), 1, msg -> {});
    }

    private static void devBoostLog(Minecraft mc, String step) {
        try {
            var o = mc.options;
            String line = step + " | boost=" + Boost.active + " transparence=" + o.improvedTransparency().get() + " météo=" + o.weatherRadius().get()
                    + " rendu=" + o.renderDistance().get() + " | sodium " + java.nio.file.Files.readString(mc.gameDirectory.toPath().resolve("config/sodium-options.json"))
                    .replaceAll("\\s+", " ").replaceAll(".*\"performance\"", "\"performance\"").replaceAll("\"debug\".*", "") + "\n";
            java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("devboost.txt"), line,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) { AuroraClient.LOGGER.error("devboost", e); }
    }
}
