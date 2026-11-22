package gg.aurora;

import gg.aurora.module.Module;
import gg.aurora.module.Modules;
import gg.aurora.render.FrameStats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * In-game self test (dev only, -Daurora.selftest=true): logic checks, then the frame cost of every
 * screen and of every module switched on alone. Report: paranoia-bench/selftest.txt.
 */
public final class SelfTest {
    private SelfTest() {}

    public static final boolean ENABLED = Boolean.getBoolean("aurora.selftest");
    private static final int SETTLE = 12, MEASURE = 24;
    private static final StringBuilder OUT = new StringBuilder();
    private static final float[] FRAMES = new float[128];
    private static int step = -1, wait;
    private static float baseline;
    private static final List<Module> MODS = new ArrayList<>();
    private static final List<float[]> COST = new ArrayList<>();
    private static final List<String> NAMES = new ArrayList<>();

    private record Case(String name, Supplier<Screen> screen) {}

    private static final List<Case> SCREENS = List.of(
            new Case("Module menu", gg.aurora.ui.ClickGuiScreen::new),
            new Case("Keyboard", () -> new gg.aurora.ui.KeyboardScreen(null)),
            new Case("Wardrobe", () -> new gg.aurora.ui.WardrobeScreen(null)),
            new Case("Friends", () -> new gg.aurora.ui.FriendsScreen(null)),
            new Case("Settings", () -> new gg.aurora.ui.SettingsScreen(null)),
            new Case("HUD editor", () -> new gg.aurora.ui.HudEditScreen(null)),
            new Case("Texture catalog", () -> new gg.aurora.ui.ItemPacksScreen(null, null)),
            new Case("Screenshots", () -> new gg.aurora.ui.PicturesScreen(null)),
            new Case("Kill messages", () -> new gg.aurora.ui.DeathMessagesScreen(null)),
            new Case("Mod manager", () -> new gg.aurora.ui.ModsScreen(null)));

    private static void check(String name, boolean ok) {
        OUT.append(ok ? "PASS  " : "FAIL  ").append(name).append('\n');
    }

    private static void logic() {
        OUT.append("== Logic\n");
        String saved = Settings.itemPacks;
        Settings.itemPacks = "";
        ItemPacks.set("cat:swords", "A.zip");
        ItemPacks.set("item:minecraft:cobweb", "B b.zip");
        check("item packs: two entries round trip", ItemPacks.mapping().size() == 2 && "B b.zip".equals(ItemPacks.mapping().get("item:minecraft:cobweb")));
        ItemPacks.set("cat:swords", null);
        check("item packs: removing an entry", !ItemPacks.mapping().containsKey("cat:swords"));
        Settings.itemPacks = saved;
        check("item packs: catalog tiles all point to real items", tilesValid());
        check("item packs: mode tiles all exist", modesValid());
        check("pets: every family member exists", petsValid());
        gg.aurora.module.AutoGG.setDeath("death.attack.player", false);
        check("auto gg: kill message off", !gg.aurora.module.AutoGG.deathEnabled("death.attack.player"));
        gg.aurora.module.AutoGG.setDeath("death.attack.player", true);
        check("auto gg: kill message back on", gg.aurora.module.AutoGG.deathEnabled("death.attack.player"));
        java.util.Set<String> ids = new java.util.HashSet<>();
        boolean unique = true;
        for (Module m : Modules.ALL) unique &= ids.add(m.id);
        check("modules: ids are unique (" + ids.size() + ")", unique);
    }

    private static boolean tilesValid() {
        for (ItemPacks.Category c : ItemPacks.CATEGORIES) {
            for (var id : ItemPacks.itemsOf(c.key())) {
                if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id).isEmpty()) {
                    OUT.append("      unknown item ").append(id).append('\n');
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean modesValid() {
        for (ItemPacks.Mode m : ItemPacks.MODES) for (String t : m.tiles()) if (ItemPacks.tile(t) == null) return false;
        return true;
    }

    private static boolean petsValid() {
        var all = gg.aurora.cosmetic.Cosmetics.PETS;
        for (var it : all) for (String v : gg.aurora.cosmetic.Cosmetics.family(it.id())) if (all.stream().noneMatch(p -> p.id().equals(v))) return false;
        return true;
    }

    private static float[] frames() {
        int n = FrameStats.recent(FRAMES);
        float sum = 0, max = 0;
        for (int i = 0; i < n; i++) {
            sum += FRAMES[i];
            max = Math.max(max, FRAMES[i]);
        }
        return new float[]{n == 0 ? 0 : sum / n, max};
    }

    /** Every client tick once in a world. Returns true while running. */
    public static boolean tick(Minecraft mc) {
        if (!ENABLED || mc.level == null || mc.player == null) return false;
        mc.getFramerateLimitTracker().onInputReceived();
        if (--wait > 0) return true;
        int nScreens = SCREENS.size();
        if (step == -1) {
            logic();
            for (Module m : Modules.ALL) {
                m.enabled = false;
                if (!m.id.equals("auto_respawn")) MODS.add(m);
            }
            OUT.append("\n== Screens (ms per frame: average / worst)\n");
            wait = 130; // FrameStats ignores the first 4 s in a world
            step = 0;
            return true;
        }
        if (step == 0) baseline = frames()[0];
        int s = step - 1; // result of the previous case
        if (s >= 0 && s < nScreens) {
            float[] f = frames();
            OUT.append(String.format(Locale.ROOT, "%-18s %7.2f / %7.2f%n", SCREENS.get(s).name(), f[0], f[1]));
        }
        if (step < nScreens) {
            try {
                mc.setScreen(SCREENS.get(step).screen().get());
            } catch (RuntimeException e) {
                OUT.append("FAIL  screen ").append(SCREENS.get(step).name()).append(": ").append(e).append('\n');
            }
            wait = MEASURE;
            step++;
            return true;
        }
        if (step == nScreens) {
            mc.setScreen(null);
            wait = SETTLE + MEASURE;
            step++;
            return true;
        }
        int k = step - nScreens - 1;
        if (k == 0) {
            baseline = frames()[0];
            OUT.append(String.format(Locale.ROOT, "%n== Modules, one at a time (baseline %.2f ms)%n", baseline));
        } else if (k <= MODS.size()) {
            Module prev = MODS.get(k - 1);
            float[] f = frames();
            COST.add(f);
            NAMES.add(prev.id);
            prev.enabled = false;
        }
        if (k < MODS.size()) {
            try {
                MODS.get(k).enabled = true;
            } catch (RuntimeException e) {
                OUT.append("FAIL  module ").append(MODS.get(k).id).append(": ").append(e).append('\n');
            }
            wait = SETTLE + MEASURE;
            step++;
            return true;
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < COST.size(); i++) order.add(i);
        order.sort((a, b) -> Float.compare(COST.get(b)[0], COST.get(a)[0]));
        for (int i : order) {
            OUT.append(String.format(Locale.ROOT, "%-24s %+7.2f ms avg, worst %7.2f%n", NAMES.get(i), COST.get(i)[0] - baseline, COST.get(i)[1]));
        }
        try {
            var dir = mc.gameDirectory.toPath().resolve("paranoia-bench");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("selftest.txt"), OUT.toString());
        } catch (java.io.IOException e) {
            AuroraClient.LOGGER.error("selftest report", e);
        }
        mc.stop();
        return false;
    }
}
