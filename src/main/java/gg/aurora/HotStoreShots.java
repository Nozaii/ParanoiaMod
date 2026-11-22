package gg.aurora;

import gg.aurora.ui.ModsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Outil de développement : avec -Daurora.hotstore=true et un monde ouvert, le gestionnaire de mods de Paranoia passe
 * par le runtime sans que le joueur ouvre Hot : un jar arrive dans mods/ pendant la partie (comme après un
 * téléchargement du Store), « Load now » le démarre sans redémarrage, l'interrupteur l'éteint et le rallume, Remove le
 * retire. Rapport : screenshots/hotstore-report.txt, captures hotstore_*.png. Inactif en jeu normal.
 */
final class HotStoreShots {
    private HotStoreShots() {}

    static final boolean ENABLED = Boolean.getBoolean("aurora.hotstore");
    private static final String EX = "example_ticks";
    private static int tick = -1, failed;
    private static Path jar;
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

    private static ModManager.Installed mod() {
        for (ModManager.Installed i : ModManager.installed) if (EX.equals(i.modId)) return i;
        return null;
    }

    private static void step(Minecraft mc) throws Exception {
        switch (tick) {
            case 20 -> {
                check("HotLoader's check of Fabric's internals passes on this Fabric (live loading stays available)", HotLoader.UNAVAILABLE == null,
                        String.valueOf(HotLoader.UNAVAILABLE));
                if (!HotBridge.PRESENT) out.add("(runtime absent: the fallback path is checked)");
                // as if the Store had just downloaded it
                jar = mc.gameDirectory.toPath().resolve("mods/example-ticks-1.0.0.jar");
                Files.copy(Path.of(System.getProperty("aurora.hotjar")), jar, StandardCopyOption.REPLACE_EXISTING);
                ModManager.scan();
            }
            case 60 -> {
                ModManager.Installed i = mod();
                check("the new jar is listed, not running yet", i != null && !i.loaded, String.valueOf(i));
                if (i != null) ModManager.loadNow(i);
            }
            case 140 -> {
                ModManager.Installed i = mod();
                if (!HotBridge.PRESENT) {
                    // without the runtime: HotLoader alone, as before; this mod's mixin targets the loaded title screen
                    check("without the runtime, the fallback (HotLoader) runs and says why a restart is needed, nothing half-loaded",
                            i != null && !i.live && ModManager.refused.containsKey(i.path) && ModManager.restartNeeded(),
                            i == null ? "gone" : String.valueOf(ModManager.refused.get(i.path)));
                    Files.deleteIfExists(jar);
                    finish(mc);
                    return;
                }
                check("Paranoia's own mod manager started it without a restart (through the runtime, mixins applied)",
                        i != null && i.loaded && i.live && "ACTIVE".equals(HotBridge.state(EX)) && !ModManager.restartNeeded(), i == null ? "gone" : "loaded=" + i.loaded + " live=" + i.live);
                mc.setScreen(new ModsScreen(new PauseScreen(true)));
            }
            case 180 -> shot(mc, "hotstore_running");
            case 182 -> {
                mc.setScreen(null);
                ModManager.toggle(mod());
            }
            case 240 -> {
                ModManager.Installed i = mod();
                check("turned off now: runtime off, file renamed for the next launch, no restart asked",
                        "DISABLED".equals(HotBridge.state(EX)) && i != null && !i.enabled && i.pending == null && !ModManager.restartNeeded(), i == null ? "gone" : i.file + " pending=" + i.pending);
                ModManager.toggle(i);
            }
            case 300 -> {
                ModManager.Installed i = mod();
                check("turned on again now", "ACTIVE".equals(HotBridge.state(EX)) && i != null && i.enabled && i.pending == null, i == null ? "gone" : i.file);
                ModManager.remove(i);
            }
            case 380 -> {
                check("removed now: the runtime no longer runs it, the jar left mods/, no restart asked",
                        HotBridge.state(EX) == null && mod() == null && !Files.exists(jar) && !ModManager.restartNeeded(), String.valueOf(mod()));
                finish(mc);
            }
            default -> {}
        }
    }

    private static void check(String name, boolean ok, String detail) {
        if (!ok) failed++;
        String s = (ok ? "PASS " : "FAIL ") + name + (detail.isEmpty() ? "" : "  [" + detail + "]");
        AuroraClient.LOGGER.info("[hotstore] {}", s);
        out.add(s);
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), 1, msg -> {});
    }

    private static void finish(Minecraft mc) {
        out.add(failed == 0 ? "ALL PASSED (" + out.size() + " checks)" : failed + " FAILED");
        try {
            Path dir = Files.createDirectories(mc.gameDirectory.toPath().resolve("screenshots"));
            Files.write(dir.resolve("hotstore-report.txt"), out);
        } catch (Exception e) {
            AuroraClient.LOGGER.warn("hotstore report not written", e);
        }
        mc.stop();
    }
}
