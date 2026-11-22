package gg.aurora;

import gg.aurora.ui.ModsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Outil de développement : avec -Daurora.hotshot=true et un monde ouvert, vérifie l'entrée « Hot » de l'écran Mods
 * (Paranoia Dynamic Runtime) comme un joueur : menu Échap → Mods → Hot → un jar déposé → plan → confirmation → le mod
 * tourne → désinstallé → retour à l'écran Mods. Sans le runtime : l'entrée n'existe pas. Captures dans screenshots/
 * (hot_*.png), rapport dans screenshots/hot-report.txt. Inactif en jeu normal.
 */
final class HotShots {
    private HotShots() {}

    static final boolean ENABLED = Boolean.getBoolean("aurora.hotshot");
    private static final String EX = "example_ticks";
    private static int tick = -1, failed;
    private static ModsScreen mods;
    private static Screen hot;
    private static final List<String> out = new ArrayList<>();

    static void tick(Minecraft mc) {
        if (!ENABLED) return;
        if (tick < 0) {
            if (mc.player == null || mc.level == null || mc.screen != null) return;
            tick = 0;
        }
        tick++;
        mc.getFramerateLimitTracker().onInputReceived();
        try {
            step(mc);
        } catch (Throwable t) {
            check("no exception", false, t.toString());
            finish(mc);
        }
    }

    private static void step(Minecraft mc) throws Exception {
        switch (tick) {
            case 40 -> mc.setScreen(new PauseScreen(true));
            case 60 -> {
                mods = new ModsScreen(mc.screen);
                mc.setScreen(mods);
            }
            case 100 -> {
                shot(mc, "hot_rail");
                check(ModsScreen.HOT ? "runtime present: its own manager stays reachable (F8), the rail shows « Add »" : "runtime absent: no hot mods manager, nothing else changes",
                        ModsScreen.HOT == net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("paranoia_dynamic"), "");
                if (!ModsScreen.HOT) finish(mc);
            }
            case 102 -> {
                mods.openHot();
                hot = mc.screen;
                check("the hot mods manager opens over the Mods screen", hot != null && hot.getClass().getName().endsWith("DynamicModsScreen"), String.valueOf(hot));
            }
            case 140 -> shot(mc, "hot_manager");
            case 142 -> hot.onFilesDrop(List.of(Path.of(System.getProperty("aurora.hotjar"))));
            case 160 -> {
                Object plan = call(hot, "plan");
                check("a dropped jar shows its plan first", plan != null && String.valueOf(call(plan, "title")).startsWith("Install"), String.valueOf(plan));
                shot(mc, "hot_plan");
            }
            case 162 -> check("confirmed", Boolean.TRUE.equals(call(hot, "confirm")), "");
            case 260 -> {
                check("the mod runs, installed from the Paranoia Mods screen without a restart", shows(EX, "ACTIVE"), "");
                hot.getClass().getMethod("select", String.class).invoke(hot, EX);
            }
            case 270 -> shot(mc, "hot_installed");
            case 272 -> {
                Object c = call(hot, "controller");
                Object plan = c.getClass().getMethod("planUninstall", String.class).invoke(c, EX);
                hot.getClass().getMethod("show", plan.getClass()).invoke(hot, plan);
                call(hot, "confirm");
            }
            case 340 -> {
                Object c = call(hot, "controller");
                boolean gone = ((java.util.Optional<?>) c.getClass().getMethod("row", String.class).invoke(c, EX)).isEmpty();
                check("uninstalled from the same screen", gone, "");
                hot.onClose();
            }
            case 370 -> {
                check("closing the manager comes back to the Paranoia Mods screen", mc.screen == mods, String.valueOf(mc.screen));
                shot(mc, "hot_back");
            }
            case 372 -> finish(mc);
            default -> {}
        }
    }

    private static boolean shows(String id, String state) throws Exception {
        Class<?> st = Class.forName("gg.paranoia.dynamic.api.ModState");
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object v = Enum.valueOf((Class) st, state);
        return (Boolean) hot.getClass().getMethod("shows", String.class, st).invoke(hot, id, v);
    }

    private static Object call(Object o, String m) throws Exception {
        return o.getClass().getMethod(m).invoke(o);
    }

    private static void check(String name, boolean ok, String detail) {
        if (!ok) failed++;
        String s = (ok ? "PASS " : "FAIL ") + name + (detail.isEmpty() ? "" : "  [" + detail + "]");
        AuroraClient.LOGGER.info("[hotshot] {}", s);
        out.add(s);
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), 1, msg -> {});
    }

    private static void finish(Minecraft mc) {
        out.add(failed == 0 ? "ALL PASSED (" + out.size() + " checks)" : failed + " FAILED");
        try {
            Path dir = Files.createDirectories(mc.gameDirectory.toPath().resolve("screenshots"));
            Files.write(dir.resolve("hot-report.txt"), out);
        } catch (Exception e) {
            AuroraClient.LOGGER.warn("hot report not written", e);
        }
        mc.stop();
    }
}
