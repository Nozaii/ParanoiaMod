package gg.aurora.module;

import gg.aurora.Settings;
import gg.aurora.ui.Icons;
import gg.aurora.ui.Rows;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * Optimisation intégrée au menu :
 * - Mode adaptatif : FPS sous la cible -> cosmétiques en qualité Performance, puis effets lourds mis en
 *   pause ; tout revient quand les FPS remontent (hystérésis pour éviter le va-et-vient).
 * - Limiteur écran : FPS plafonnés juste sous la fréquence de l'écran (images régulières, moins de chauffe).
 */
public final class PerfModules {
    private PerfModules() {}

    public static final Module ADAPTIVE = Modules.register(new Module("adaptive_mode", "Adaptive mode", Category.MISC, Icons.GAUGE, false) {
        @Override
        protected void onToggle() {
            if (!enabled) restoreAll();
        }
    }).withSettings(() -> List.of(
            new Rows.Info("Principle", "Lightens when FPS drops"),
            new Rows.Slider("Target FPS", null, 60, 400, 10, () -> PerfModules.ADAPTIVE.opt("target", 144),
                    v -> PerfModules.ADAPTIVE.set("target", v), v -> Math.round(v) + " FPS"),
            new Rows.Toggle("Pause heavy effects", "Mirror floor, motion blur, sky, filters, hologram",
                    () -> PerfModules.ADAPTIVE.flag("heavy", true), v -> PerfModules.ADAPTIVE.set("heavy", v)),
            new Rows.Info("Status", PerfModules::adaptiveStatus)));

    public static final Module HUD_CACHE = Modules.register(new Module("hud_cache", "HUD cache", Category.MISC, Icons.GAUGE, true))
            .withSettings(() -> List.of(
                    new Rows.Slider("Refresh rate", "Lower = more FPS", 10, 60, 5, () -> PerfModules.HUD_CACHE.opt("hz", 30),
                            v -> PerfModules.HUD_CACHE.set("hz", v), v -> Math.round(v) + " /s")));

    public static final Module SCREEN_LIMIT = Modules.register(new Module("screen_limit", "Screen limiter", Category.MISC, Icons.MONITOR, false))
            .withSettings(() -> List.of(
                    new Rows.Info("Screen", () -> refresh() + " Hz → limit " + screenLimit() + " FPS"),
                    new Rows.Slider("Margin under the refresh rate", "A few FPS under the refresh rate: smoother frames", 0, 10, 1,
                            () -> PerfModules.SCREEN_LIMIT.opt("margin", 3), v -> PerfModules.SCREEN_LIMIT.set("margin", v),
                            v -> Math.round(v) + " FPS"),
                    new Rows.Toggle("Double the screen", "Limit at 2× the refresh rate (lower latency)",
                            () -> PerfModules.SCREEN_LIMIT.flag("double", false), v -> PerfModules.SCREEN_LIMIT.set("double", v))));

    private static final String[] HEAVY = {"ground_mirror", "motion_blur", "custom_sky", "filters", "hologram"};
    private static int step;
    private static int savedQuality = -1;
    private static final List<Module> paused = new ArrayList<>();
    private static long lastCheck, lowSince, highSince;

    public static void init() {}

    private static int refresh() {
        try {
            int r = Minecraft.getInstance().getWindow().getRefreshRate();
            return r > 0 ? r : 60;
        } catch (RuntimeException e) {
            return 60;
        }
    }

    /** Limite du limiteur écran (0 = inactif). */
    public static int screenLimit() {
        if (!SCREEN_LIMIT.enabled) return 0;
        int r = refresh() * (SCREEN_LIMIT.flag("double", false) ? 2 : 1);
        return Math.max(30, r - (int) SCREEN_LIMIT.opt("margin", 3));
    }

    /** Le module est en pause par le mode adaptatif (sa valeur réelle pour la config est « activé »). */
    public static boolean pausedByAdaptive(Module m) {
        return paused.contains(m);
    }

    private static String adaptiveStatus() {
        if (!ADAPTIVE.enabled) return "Off";
        return switch (step) {
            case 0 -> "Everything is on";
            case 1 -> "Light cosmetics";
            default -> "Light cosmetics + " + paused.size() + " effect(s) paused";
        };
    }

    public static void tick(Minecraft mc) {
        if (!ADAPTIVE.enabled || mc.level == null) {
            if (step != 0) restoreAll();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCheck < 500) return;
        lastCheck = now;
        int fps = mc.getFps(), target = (int) ADAPTIVE.opt("target", 144);
        if (mc.screen != null) return; // les menus faussent la mesure
        if (fps < target * 0.9f) {
            highSince = 0;
            if (lowSince == 0) lowSince = now;
            else if (now - lowSince > 2500 && step < (ADAPTIVE.flag("heavy", true) ? 2 : 1)) {
                lowSince = now;
                stepUp();
            }
        } else if (fps > target * 1.3f) {
            lowSince = 0;
            if (highSince == 0) highSince = now;
            else if (now - highSince > 6000 && step > 0) {
                highSince = now;
                stepDown();
            }
        } else {
            lowSince = 0;
            highSince = 0;
        }
    }

    /** Qualité réglée par le joueur pendant que le mode adaptatif l'a baissée, sinon -1. */
    public static int userQuality() {
        return step >= 1 ? savedQuality : -1;
    }

    private static void stepUp() {
        step++;
        if (step == 1) {
            savedQuality = Settings.cosmeticQuality;
            Settings.cosmeticQuality = 2;
        } else if (step == 2) {
            for (String id : HEAVY) {
                Module m = Modules.byId(id);
                if (m != null && m.enabled) {
                    m.enabled = false;
                    paused.add(m);
                }
            }
        }
    }

    private static void stepDown() {
        if (step == 2) {
            for (Module m : paused) m.enabled = true;
            paused.clear();
        } else if (step == 1 && savedQuality >= 0) {
            Settings.cosmeticQuality = savedQuality;
            savedQuality = -1;
        }
        step--;
    }

    private static void restoreAll() {
        while (step > 0) stepDown();
        lowSince = highSince = 0;
    }
}
