package gg.aurora.ui;

import gg.aurora.Settings;
import gg.aurora.nametag.NametagSync;
import gg.aurora.render.Upscaler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sections des paramètres (Interface, Rendering, …) : la même liste sert à la vue Réglages du menu
 * (RShift) et à l'écran Réglages du menu titre. {@code owner} est l'écran auquel les sous-pages reviennent.
 */
public final class SettingsSections {
    private SettingsSections() {}

    public record Section(String name, String icon, List<Rows.Row> rows) {}

    /** L'adresse du serveur a changé : la synchro est relancée à la fermeture (voir SettingsScreen, ClickGuiScreen). */
    public static boolean urlChanged;

    public static List<Section> build(Screen owner) {
        Minecraft mc = Minecraft.getInstance();
        String[] importCode = {""};
        List<Section> sections = new ArrayList<>();
        sections.add(new Section("Interface", Icons.MONITOR, List.of(
                new Rows.Toggle("Paranoia main menu", "Replaces the Minecraft title screen", () -> Settings.customTitle, v -> Settings.customTitle = v),
                new Rows.Toggle("Per-item packs", "The Resource Packs button opens the per-item picker (swords, axes, cobweb…)",
                        () -> Settings.itemPackMenu, v -> Settings.itemPackMenu = v),
                new Rows.Toggle("Elytra with cape pattern", "Off: normal elytra texture", () -> Settings.elytraCape, v -> Settings.elytraCape = v),
                new Rows.Toggle("Menu fixed in the world", "RShift places the menu in front of you: walk around it, aim to click, right click to move it",
                        () -> Settings.worldMenu, v -> Settings.worldMenu = v),
                new Rows.Slider("World menu size", null, 0.6, 2.0, 0.1, () -> Settings.worldMenuScale, v -> Settings.worldMenuScale = v,
                        v -> Math.round(v * 100) + " %"),
                new Rows.Choice("Menu animations", Settings.MENU_ANIMS, () -> Settings.menuAnim, i -> Settings.menuAnim = i),
                new Rows.Toggle("Animations", "Transitions, springs and reveals", () -> Settings.animations, v -> Settings.animations = v),
                new Rows.Slider("Animation speed", null, 0.5, 2.0, 0.1, () -> Settings.animSpeed, v -> Settings.animSpeed = (float) v,
                        v -> String.format(Locale.ROOT, "%.1fx", v)),
                new Rows.Toggle("Background blur", "Blurs the world behind the menu", () -> Settings.blur, v -> Settings.blur = v),
                new Rows.Toggle("Stars", null, () -> Settings.stars, v -> Settings.stars = v),
                new Rows.Toggle("Shooting stars", null, () -> Settings.shootingStars, v -> Settings.shootingStars = v),
                new Rows.Toggle("Menu sounds", "Small click on buttons", () -> Settings.sounds, v -> Settings.sounds = v))));
        sections.add(new Section("Rendering", Icons.GAUGE, List.of(
                new Rows.Choice("Performance profile", gg.aurora.render.PerfProfiles.NAMES, () -> Settings.perfProfile, i -> {
                    Settings.perfProfile = i;
                    gg.aurora.render.PerfProfiles.apply(i);
                }),
                new Rows.Choice("Boost level", gg.aurora.Boost.LEVELS, () -> Settings.boostLevel, i -> Settings.boostLevel = i),
                new Rows.Toggle("Automatic Boost", "Turns on when FPS stays under the threshold for 8 s", () -> Settings.boostAuto, v -> Settings.boostAuto = v),
                new Rows.Slider("Auto Boost threshold", null, 20, 144, 2, () -> Settings.boostThreshold, v -> Settings.boostThreshold = (int) Math.round(v),
                        v -> Math.round(v) + " FPS"),
                new Rows.Toggle("Grass", "Short and tall grass, ferns, seagrass", () -> Settings.showGrass, v -> {
                    Settings.showGrass = v;
                    gg.aurora.render.Vegetation.refresh(true);
                }),
                new Rows.Toggle("Flowers", "Flowers, petals, tall flowers", () -> Settings.showFlowers, v -> {
                    Settings.showFlowers = v;
                    gg.aurora.render.Vegetation.refresh(true);
                }),
                new Rows.Toggle("Ground litter", "Leaf litter, bushes, vines, moss carpets", () -> Settings.showLitter, v -> {
                    Settings.showLitter = v;
                    gg.aurora.render.Vegetation.refresh(true);
                }),
                new Rows.Info("Hidden plants", "Still there: you can walk through and break them"),
                new Rows.Info("Graphics card", Upscaler::gpu),
                new Rows.Slider("Upscaling", "Version compatible with your GPU", 0, Upscaler.VERSIONS.length - 1, 1,
                        () -> Settings.upscaler, v -> Settings.upscaler = (int) Math.round(v), v -> Upscaler.VERSIONS[(int) Math.round(v)]),
                new Rows.Slider("FSR quality", "Internal resolution before upscaling", 0, Upscaler.QUALITIES.length - 1, 1,
                        () -> Settings.fsrQuality, v -> Settings.fsrQuality = (int) Math.round(v),
                        v -> Upscaler.QUALITIES[(int) Math.round(v)].name()),
                new Rows.Slider("Sharpness (RCAS)", null, 0, 1, 0.1, () -> Settings.fsrSharpness, v -> Settings.fsrSharpness = (float) v,
                        v -> Math.round(v * 100) + " %"),
                new Rows.Info("FSR 1.0", Upscaler::fsrStatus),
                new Rows.Info("DLSS", Upscaler::dlssStatus),
                new Rows.Info("FSR 2 / 3", "Impossible on Minecraft Java (motion vectors required)"),
                // réglages JVM repris de OneLauncher (voir JvmTuning)
                new Rows.Info("Java", gg.aurora.JvmTuning::summary),
                new Rows.Info("JVM options", gg.aurora.JvmTuning::status),
                new Rows.Button("Copy optimized JVM arguments", "Paste them in your launcher's JVM arguments: kept on every launch",
                        Icons.GAUGE, () -> {
                            mc.keyboardHandler.setClipboard(gg.aurora.JvmTuning.recommendedLine());
                            gg.aurora.ui.Toasts.push(Icons.GAUGE, "JVM arguments copied", "Paste them in your launcher", false);
                        }),
                new Rows.Button("Restart with optimized JVM", "Adds the missing options for this session only (garbage collector, heap)",
                        Icons.GAUGE, gg.aurora.JvmTuning::restartOptimized),
                new Rows.Toggle("Dedicated GPU (Windows)", "Runs Java on the high performance graphics card, from the next launch. For laptops",
                        () -> Settings.dedicatedGpu, v -> {
                            Settings.dedicatedGpu = v;
                            gg.aurora.JvmTuning.applyGpuPreference(v);
                        }))));
        sections.add(new Section("Cosmetics", Icons.SPARKLES, List.of(
                new Rows.Info("Shown cosmetics", "Yours and other players', by type"),
                new Rows.Toggle("Capes", "Paranoia capes (vanilla capes stay)", () -> Settings.showCapes, v -> Settings.showCapes = v),
                new Rows.Toggle("Wings", null, () -> Settings.showWings, v -> Settings.showWings = v),
                new Rows.Toggle("Auras", null, () -> Settings.showAuras, v -> Settings.showAuras = v),
                new Rows.Toggle("Head", "Halos and hats", () -> Settings.showHead, v -> Settings.showHead = v),
                new Rows.Toggle("Back", "Guitar, katana, AK-47, mace", () -> Settings.showBack, v -> Settings.showBack = v),
                new Rows.Toggle("Pets", null, () -> Settings.showPets, v -> Settings.showPets = v),
                new Rows.Toggle("Shields", "Shield designs", () -> Settings.showShields, v -> Settings.showShields = v),
                new Rows.Toggle("Trails", null, () -> Settings.showTrails, v -> Settings.showTrails = v),
                new Rows.Choice("Cosmetics quality", new String[]{"High", "Balanced", "Performance"},
                        () -> Settings.cosmeticQuality, i -> Settings.cosmeticQuality = i),
                new Rows.Info("Players with 3D cosmetics", () -> gg.aurora.Settings.cosmeticQuality == 0 ? "All" : gg.aurora.Settings.cosmeticQuality == 2 ? "3 nearest" : "6 nearest"),
                new Rows.Toggle("Aura particles", "Particles around players with an aura", () -> Settings.auraParticles, v -> Settings.auraParticles = v))));
        sections.add(new Section("Waypoints", Icons.PIN, List.of(
                new Rows.Toggle("Waypoints", "Labels and highlighted blocks in the world", () -> Settings.wpEnabled, v -> Settings.wpEnabled = v),
                new Rows.Button("Manage waypoints", "Create, edit, share · " + keyName(gg.aurora.waypoint.Waypoints.openKey), Icons.PIN,
                        () -> mc.setScreen(new gg.aurora.waypoint.WaypointsScreen(owner, null))),
                new WaypointRows.LabelPreview(),
                new WaypointRows.Header("Label", Icons.PENCIL),
                new WaypointRows.DefaultStyle(),
                new Rows.Button("Use this style everywhere", "Applies it to the waypoints you already have", Icons.CHECK, () -> {
                    for (gg.aurora.waypoint.Waypoint w : gg.aurora.waypoint.Waypoints.LIST) w.preset = Settings.wpDefaultPreset;
                    gg.aurora.waypoint.Waypoints.save();
                    Toasts.push(Icons.PIN, "Waypoints", "Style applied to " + gg.aurora.waypoint.Waypoints.LIST.size() + " waypoints", false);
                }),
                new Rows.Slider("Size", null, 0.5, 2, 0.1, () -> Settings.wpLabelScale, v -> Settings.wpLabelScale = (float) v,
                        v -> String.format(Locale.ROOT, "%.1fx", v)),
                new Rows.Choice("Show names", new String[]{"Always", "When aimed at", "Never"}, () -> Settings.wpNames, i -> Settings.wpNames = i),
                new Rows.Toggle("Show distance", "12m next to the name", () -> Settings.wpShowDistance, v -> Settings.wpShowDistance = v),
                new WaypointRows.Header("Distance", Icons.RULER),
                new Rows.Toggle("Shrink when far", "Full size up to 16 blocks, then smaller", () -> Settings.wpDistScale,
                        v -> Settings.wpDistScale = v),
                new Rows.Slider("Turn into a dot beyond", "Aim at the dot to see the full label", 0, 1000, 10,
                        () -> Settings.wpDotDistance, v -> Settings.wpDotDistance = (int) Math.round(v), v -> v < 1 ? "Never" : Math.round(v) + " blocks"),
                new Rows.Slider("Hide beyond", null, 0, 2000, 50, () -> Settings.wpMaxDistance, v -> Settings.wpMaxDistance = (int) Math.round(v),
                        v -> v < 1 ? "Never" : Math.round(v) + " blocks"),
                new Rows.Toggle("Fade out up close", "Gets out of the way when you reach it", () -> Settings.wpHideNear, v -> Settings.wpHideNear = v),
                new Rows.Toggle("Off-screen arrows", "Waypoints behind you stay on the screen edge", () -> Settings.wpEdge, v -> Settings.wpEdge = v),
                new WaypointRows.Header("Highlighted block · new waypoints", Icons.LAYERS),
                new Rows.Slider("Fill", null, 0, 1, 0.05, () -> Settings.wpFill, v -> Settings.wpFill = (float) v,
                        v -> v < 0.01 ? "None" : Math.round(v * 100) + " %"),
                new Rows.Slider("Outline", null, 0, 1, 0.05, () -> Settings.wpOutline, v -> Settings.wpOutline = (float) v,
                        v -> v < 0.01 ? "None" : Math.round(v * 100) + " %"),
                new Rows.Toggle("Visible through walls", null, () -> Settings.wpThroughWalls, v -> Settings.wpThroughWalls = v),
                new WaypointRows.Header("Automatic", Icons.SKULL),
                new Rows.Toggle("Death waypoint", "Marks where you die, removed when you get there", () -> Settings.wpDeath, v -> Settings.wpDeath = v))));
        sections.add(new Section("Pings", Icons.TARGET, List.of(
                new Rows.Toggle("Pings", "Mark a spot or an item for you and the players around you", () -> Settings.pingEnabled, v -> Settings.pingEnabled = v),
                new Rows.Info("Tap " + keyName(gg.aurora.waypoint.Pings.key), "ping · twice: danger · hold: wheel"),
                new Rows.KeyBind("Ping key", null, () -> code(gg.aurora.waypoint.Pings.key), v -> bind(gg.aurora.waypoint.Pings.key, v)),
                new Rows.Choice("Show pings from", new String[]{"Everyone", "Friends", "Nobody"}, () -> Settings.pingFrom, i -> Settings.pingFrom = i),
                new Rows.Toggle("Share my pings", "Paranoia players around you see them", () -> Settings.pingShare, v -> Settings.pingShare = v),
                new Rows.Slider("Duration", null, 3, 20, 1, () -> Settings.pingSeconds, v -> Settings.pingSeconds = (int) Math.round(v),
                        v -> Math.round(v) + " s"),
                new Rows.Toggle("Sound", null, () -> Settings.pingSound, v -> Settings.pingSound = v))));
        sections.add(new Section("Theme", Icons.PALETTE, List.of(
                new Rows.Swatches(),
                new Rows.ThemePreview())));
        sections.add(new Section("HUD", Icons.HUD, List.of(
                new Rows.Choice("Widget style", new String[]{"Glass", "Full", "Minimal", "Clean"},
                        () -> Settings.hudStyle.ordinal(), i -> Settings.hudStyle = Settings.HudStyle.values()[i]),
                new Rows.Slider("Background opacity", null, 0.2, 1.0, 0.05, () -> Settings.hudOpacity, v -> Settings.hudOpacity = (float) v,
                        v -> Math.round(v * 100) + " %"),
                new Rows.Toggle("Drop shadow", null, () -> Settings.hudShadow, v -> Settings.hudShadow = v),
                new Rows.Toggle("Colored icons", "HUD icons in the accent color", () -> Settings.hudAccentIcons, v -> Settings.hudAccentIcons = v),
                new Rows.Button("Customize the HUD", "Position, size, color of each widget", Icons.MOVE,
                        () -> mc.setScreen(new HudEditScreen(owner))),
                new Rows.Button("Export layout", "Short code copied to clipboard", Icons.BAG, HudLayout::export),
                new Rows.TextField("Code to import", "Paste a PHUD1:… code", 4000, () -> importCode[0], v -> importCode[0] = v),
                new Rows.Button("Import layout", "Applies the code pasted above", Icons.ARROW_DOWN, () -> HudLayout.importCode(importCode[0])),
                new Rows.Info("Sharing", () -> HudLayout.status.isEmpty() ? "Export or import a layout" : HudLayout.status))));
        sections.add(new Section("Profiles", Icons.LAYERS, gg.aurora.module.ClientTools.profileRows()));
        sections.add(new Section("Account", Icons.PERSON, List.of(
                new Rows.Info("Discord", () -> gg.aurora.Friends.myDiscord.isEmpty() ? "Not linked" : "Linked: " + gg.aurora.Friends.myDiscord),
                new Rows.Button("Link my Discord account", "Opens Discord in the browser", Icons.FRIENDS, gg.aurora.Friends::linkDiscord),
                new Rows.Button("Unlink Discord", null, Icons.LOCK, gg.aurora.Friends::unlinkDiscord),
                new Rows.Info("Status", () -> gg.aurora.Friends.discordStatus.isEmpty() ? "—" : gg.aurora.Friends.discordStatus))));
        sections.add(new Section("Social", Icons.FRIENDS, List.of(
                new Rows.Toggle("Other players' nametags", "Show gradients of mod players", () -> Settings.showOthers, v -> Settings.showOthers = v),
                new Rows.Choice("Imported capes of others", new String[]{"Everyone", "Friends", "Nobody"}, () -> Settings.importedCapes, i -> Settings.importedCapes = i),
                new Rows.Toggle("Share my imported cape", "Sent to the Paranoia server (256 px, 24 frames, 512 KB at most) so others see it",
                        () -> Settings.shareImportedCape, v -> {
                            Settings.shareImportedCape = v;
                            NametagSync.markDirty();
                        }),
                new Rows.Toggle("Logo Paranoia", "Logo before the name of mod players", () -> Settings.showLogos, v -> Settings.showLogos = v),
                new Rows.TextField("Nametag server", "Official server (leave empty)", 120,
                        () -> Settings.apiUrl, v -> {
                            Settings.apiUrl = v;
                            urlChanged = true;
                        }),
                new Rows.Info("Status", () -> NametagSync.status),
                new Rows.Button("Sync now", "Publishes your nametag and reloads the others", Icons.GLOBE, () -> {
                    NametagSync.reset();
                    NametagSync.publish();
                }))));
        sections.add(new Section("Keys", Icons.KEYBOARD, keyRows()));
        sections.add(new Section("About", Icons.INFO, List.of(
                new Rows.Info(Theme.NAME + " Client", () -> "Version " + gg.aurora.Updater.currentVersion()),
                new Rows.Toggle("Automatic updates", "Installed when the game closes", () -> Settings.autoUpdate, v -> Settings.autoUpdate = v),
                new Rows.Info("Update", () -> gg.aurora.Updater.status),
                new Rows.Button("Check now", null, Icons.GLOBE, () -> gg.aurora.Updater.checkAsync(true)),
                new Rows.Info("Font", "Poppins (SIL OFL license)"),
                new Rows.Info("Configuration", "config/aurora.json"))));
        return sections;
    }

    /** À la fermeture de la vue : relance la synchro si l'adresse du serveur a changé. */
    public static void closed() {
        if (urlChanged) {
            NametagSync.reset();
            urlChanged = false;
        }
    }

    /** Raccourcis : touches du client (modifiables ici, comme dans Contrôles) puis un raccourci par module. */
    private static List<Rows.Row> keyRows() {
        List<Rows.Row> r = new ArrayList<>();
        r.add(new Rows.Toggle("Visual keyboard", "Options › Controls › Key Binds opens the keyboard", () -> Settings.visualKeyboard, v -> Settings.visualKeyboard = v));
        r.add(new Rows.Info("Tip", "Click then press a key · Backspace = none"));
        Object[][] keys = {
                {"Open the Paranoia menu", gg.aurora.AuroraClient.menuKey},
                {"Zoom", gg.aurora.AuroraClient.zoomKey},
                {"Waypoints menu", gg.aurora.waypoint.Waypoints.openKey},
                {"New waypoint", gg.aurora.waypoint.Waypoints.addKey},
                {"Zone waypoint", gg.aurora.waypoint.Waypoints.zoneKey},
                {"Show / hide waypoints", gg.aurora.waypoint.Waypoints.toggleKey},
                {"Ping", gg.aurora.waypoint.Pings.key},
        };
        for (Object[] k : keys) {
            net.minecraft.client.KeyMapping km = (net.minecraft.client.KeyMapping) k[1];
            if (km == null) continue;
            r.add(new Rows.KeyBind((String) k[0], null, () -> code(km), v -> bind(km, v)));
        }
        r.add(new Rows.Info("Modules", "Toggle a module with one key"));
        for (gg.aurora.module.Module m : gg.aurora.module.Modules.ALL) {
            r.add(new Rows.KeyBind(m.name, null, () -> m.key, v -> m.key = v));
        }
        return r;
    }

    private static void bind(net.minecraft.client.KeyMapping km, int v) {
        km.setKey(v < 0 ? com.mojang.blaze3d.platform.InputConstants.UNKNOWN
                : com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(v));
        net.minecraft.client.KeyMapping.resetMapping();
        Minecraft.getInstance().options.save();
    }

    private static String keyName(net.minecraft.client.KeyMapping km) {
        return km == null || km.isUnbound() ? "(no key)" : km.getTranslatedKeyMessage().getString();
    }

    private static int code(net.minecraft.client.KeyMapping km) {
        if (km.isUnbound()) return -1;
        try {
            return com.mojang.blaze3d.platform.InputConstants.getKey(km.saveString()).getValue();
        } catch (Exception e) {
            return -1;
        }
    }
}
