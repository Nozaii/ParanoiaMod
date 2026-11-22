package gg.aurora.module;

import gg.aurora.render.PostFx;
import gg.aurora.ui.Icons;
import gg.aurora.ui.Rows;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import net.minecraft.client.renderer.fog.environment.BlindnessFogEnvironment;
import net.minecraft.client.renderer.fog.environment.DarknessFogEnvironment;
import net.minecraft.client.renderer.fog.environment.FogEnvironment;
import net.minecraft.client.renderer.fog.environment.LavaFogEnvironment;
import net.minecraft.client.renderer.fog.environment.PowderedSnowFogEnvironment;
import net.minecraft.client.renderer.fog.environment.WaterFogEnvironment;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/** Visual clarity and comfort: shockwaves, stretched resolution, fog controls, night vision cleanup, smooth scroll. */
public final class ClarityModules {
    private ClarityModules() {}

    // ===================================================================== Shockwaves

    public static final Module SHOCKWAVES = Modules.register(new Module("shockwaves", "Shockwaves", Category.VISUALS, Icons.SPARKLES, false))
            .withSettings(() -> List.of(
                    new Rows.Toggle("On hit", null, () -> ClarityModules.SHOCKWAVES.flag("hit", true), v -> ClarityModules.SHOCKWAVES.set("hit", v)),
                    new Rows.Toggle("On kill", null, () -> ClarityModules.SHOCKWAVES.flag("kill", true), v -> ClarityModules.SHOCKWAVES.set("kill", v)),
                    new Rows.Toggle("On landing", "After a fall of 4 blocks or more", () -> ClarityModules.SHOCKWAVES.flag("land", true),
                            v -> ClarityModules.SHOCKWAVES.set("land", v)),
                    new Rows.ColorRow("Color", () -> (int) ClarityModules.SHOCKWAVES.opt("color", 0xDDE6FF),
                            v -> ClarityModules.SHOCKWAVES.set("color", v & 0xFFFFFF)),
                    new Rows.Slider("Range", null, 4, 40, 1, () -> ClarityModules.SHOCKWAVES.opt("range", 16),
                            v -> ClarityModules.SHOCKWAVES.set("range", v), v -> Math.round(v) + " blocks"),
                    new Rows.Slider("Strength", null, 0.2, 1, 0.05, () -> ClarityModules.SHOCKWAVES.opt("strength", 0.6),
                            v -> ClarityModules.SHOCKWAVES.set("strength", v), v -> Math.round(v * 100) + " %")));

    private static double fallFrom = Double.NaN;

    public static void onHit(Entity e) {
        if (SHOCKWAVES.enabled && SHOCKWAVES.flag("hit", true)) PostFx.shock(e.position().add(0, 0.1, 0));
    }

    public static void onKill(Entity e) {
        if (SHOCKWAVES.enabled && SHOCKWAVES.flag("kill", true)) PostFx.shock(e.position().add(0, 0.1, 0));
    }

    public static void tick(Minecraft mc) {
        var p = mc.player;
        if (p == null || !SHOCKWAVES.enabled || !SHOCKWAVES.flag("land", true)) {
            fallFrom = Double.NaN;
            return;
        }
        if (p.onGround()) {
            if (!Double.isNaN(fallFrom) && fallFrom - p.getY() >= 4) PostFx.shock(p.position().add(0, 0.1, 0));
            fallFrom = Double.NaN;
        } else if (Double.isNaN(fallFrom) || p.getY() > fallFrom) {
            fallFrom = p.getY();
        }
    }

    // ===================================================================== Rescale resolution

    public static final String[] RATIOS = {"R_16_9", "R_16_10", "R_4_3", "R_5_4", "CUSTOM"};
    private static final double[] RATIO_VALUES = {16 / 9.0, 16 / 10.0, 4 / 3.0, 5 / 4.0, 0};

    public static final Module RESCALE = Modules.register(new Module("rescale_resolution", "Rescale Resolution", Category.VISUALS, Icons.MONITOR, false))
            .withSettings(() -> List.of(
                    new Rows.Choice("Ratio", RATIOS, () -> (int) ClarityModules.RESCALE.opt("ratio", 2), i -> ClarityModules.RESCALE.set("ratio", i)),
                    new Rows.Slider("Custom width", "Used with CUSTOM", 800, 2560, 10, () -> ClarityModules.RESCALE.opt("w", 1440),
                            v -> ClarityModules.RESCALE.set("w", v), v -> Math.round(v) + ""),
                    new Rows.Slider("Custom height", "Used with CUSTOM", 600, 1440, 10, () -> ClarityModules.RESCALE.opt("h", 1080),
                            v -> ClarityModules.RESCALE.set("h", v), v -> Math.round(v) + "")));

    /** Horizontal scale of the projection: the world is drawn in the chosen ratio and stretched over the window. */
    public static float stretch() {
        if (!RESCALE.enabled) return 1;
        var win = Minecraft.getInstance().getWindow();
        if (win.getHeight() <= 0) return 1;
        int i = Math.max(0, Math.min(RATIOS.length - 1, (int) RESCALE.opt("ratio", 2)));
        double target = i == 4 ? RESCALE.opt("w", 1440) / Math.max(1, RESCALE.opt("h", 1080)) : RATIO_VALUES[i];
        return (float) (win.getWidth() / (double) win.getHeight() / target);
    }

    // ===================================================================== Fog controls

    public static final Module FOG = Modules.register(new Module("fog_controls", "Fog Controls", Category.VISUALS, Icons.EYE, false))
            .withSettings(() -> List.of(
                    fogRow("Atmospheric fog", "atmo"), fogRow("Water fog", "water"), fogRow("Lava fog", "lava"), fogRow("Powder snow fog", "snow"),
                    fogRow("Blindness fog", "blind"), fogRow("Darkness fog", "dark")));

    private static Rows.Toggle fogRow(String label, String key) {
        return new Rows.Toggle("Remove " + label.toLowerCase(Locale.ROOT), null, () -> ClarityModules.FOG.flag(key, false), v -> ClarityModules.FOG.set(key, v));
    }

    public static boolean fogRemoved(FogEnvironment e) {
        if (!FOG.enabled) return false;
        if (e instanceof AtmosphericFogEnvironment) return FOG.flag("atmo", false);
        if (e instanceof WaterFogEnvironment) return FOG.flag("water", false);
        if (e instanceof LavaFogEnvironment) return FOG.flag("lava", false);
        if (e instanceof PowderedSnowFogEnvironment) return FOG.flag("snow", false);
        if (e instanceof BlindnessFogEnvironment) return FOG.flag("blind", false);
        if (e instanceof DarknessFogEnvironment) return FOG.flag("dark", false);
        return false;
    }

    // ===================================================================== Small comforts

    public static final Module NO_NIGHT_VISION = Modules.register(new Module("no_night_vision", "No Night Vision", Category.VISUALS, Icons.EYE, false));

    public static final Module SMOOTH_SCROLL = Modules.register(new Module("smooth_scroll", "Smooth Scroll", Category.MISC, Icons.LIST, true))
            .withSettings(() -> List.of(new Rows.Slider("Speed", "Higher = snappier", 6, 40, 1, () -> ClarityModules.SMOOTH_SCROLL.opt("speed", 18),
                    v -> ClarityModules.SMOOTH_SCROLL.set("speed", v), v -> Math.round(v) + "")));

    public static void init() {
    }

    /** Vec3 helper kept here so callers don't need the module classes at init. */
    public static Vec3 feet(Entity e) {
        return e.position();
    }
}
