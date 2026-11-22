package gg.aurora.module;

import gg.aurora.ui.ColorPicker;
import gg.aurora.ui.Icons;
import gg.aurora.ui.Rows;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Locale;

/**
 * Modules visuels / monde : taille des objets, Item Physic, contour de bloc, ciel, météo,
 * vitesse de vol et Optimizer +++. Les mixins appellent les méthodes statiques d'ici.
 */
public final class WorldModules {
    private WorldModules() {}

    private static String pct(double v) {
        return Math.round(v * 100) + " %";
    }

    private static Rows.Slider scaleRow(Module m, String label, String key) {
        return new Rows.Slider(label, null, 0.5, 3, 0.05, () -> m.opt(key, 1), v -> m.set(key, v), WorldModules::pct);
    }

    // ===================================================== Taille des objets au sol
    public static final Module ITEM_SIZE = Modules.register(new Module("item_size", "Item Size (ground)", Category.VISUALS, Icons.TOTEM, false))
            .withSettings(WorldModules::itemSizeRows);

    private static final String CUSTOM = "item:";

    /** Lignes de base, puis un curseur par objet ajouté depuis le sélecteur (tous les blocs et objets). */
    private static List<gg.aurora.ui.Rows.Row> itemSizeRows() {
        var m = WorldModules.ITEM_SIZE;
        List<gg.aurora.ui.Rows.Row> rows = new java.util.ArrayList<>(List.of(
                scaleRow(m, "All items", "all"),
                scaleRow(m, "Swords", "sword"),
                scaleRow(m, "Golden apples", "gapple"),
                scaleRow(m, "Potions", "potion"),
                scaleRow(m, "Totems", "totem"),
                scaleRow(m, "Ender pearls", "pearl")));
        for (String key : new java.util.ArrayList<>(m.opts.keySet())) {
            if (!key.startsWith(CUSTOM)) continue;
            String id = key.substring(CUSTOM.length());
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.Identifier.tryParse(id));
            String name = item.map(i -> new ItemStack(i).getHoverName().getString()).orElse(id);
            rows.add(scaleRow(m, name, key));
            rows.add(new gg.aurora.ui.Rows.Button("Remove: " + name, null, gg.aurora.ui.Icons.ARROW_DOWN, () -> {
                m.opts.remove(key);
                gg.aurora.Config.save();
            }));
        }
        rows.add(new gg.aurora.ui.Rows.Button("Add an item…", "Pick any block or item", gg.aurora.ui.Icons.SEARCH, () -> {
            var mc = net.minecraft.client.Minecraft.getInstance();
            mc.setScreen(new gg.aurora.ui.ItemPickerScreen(mc.screen, "", id -> {
                if (id != null && !id.isEmpty() && !m.opts.containsKey(CUSTOM + id)) m.set(CUSTOM + id, 1.5);
            }));
        }));
        return rows;
    }

    /** Échelle d'un objet posé au sol. */
    public static float groundScale(ItemStack st) {
        if (!WorldModules.ITEM_SIZE.enabled || st.isEmpty()) return 1f;
        double s = WorldModules.ITEM_SIZE.opt("all", 1);
        Double custom = WorldModules.ITEM_SIZE.opts.get(CUSTOM + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()));
        if (custom != null) return (float) (s * custom);
        if (st.is(Items.GOLDEN_APPLE) || st.is(Items.ENCHANTED_GOLDEN_APPLE)) s *= WorldModules.ITEM_SIZE.opt("gapple", 1);
        else if (st.is(Items.TOTEM_OF_UNDYING)) s *= WorldModules.ITEM_SIZE.opt("totem", 1);
        else if (st.is(Items.POTION) || st.is(Items.SPLASH_POTION) || st.is(Items.LINGERING_POTION)) s *= WorldModules.ITEM_SIZE.opt("potion", 1);
        else if (st.is(Items.ENDER_PEARL)) s *= WorldModules.ITEM_SIZE.opt("pearl", 1);
        else if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath().endsWith("_sword")) s *= WorldModules.ITEM_SIZE.opt("sword", 1);
        return (float) s;
    }

    // ===================================================== Item Physic
    public static final Module ITEM_PHYSIC = Modules.register(new Module("item_physic", "Item Physic", Category.VISUALS, Icons.ARROW_DOWN, false));

    // ===================================================== Contour de bloc
    public static final Module BLOCK_OUTLINE = Modules.register(new Module("block_outline", "Block Outline", Category.VISUALS, Icons.HUD, false))
            .withSettings(() -> List.of(
                    new Rows.ColorRow("Color", () -> (int) WorldModules.BLOCK_OUTLINE.opt("color", 0x9D7CFF), v -> WorldModules.BLOCK_OUTLINE.set("color", v)),
                    new Rows.Slider("Opacity", null, 0.1, 1, 0.05, () -> WorldModules.BLOCK_OUTLINE.opt("alpha", 0.9), v -> WorldModules.BLOCK_OUTLINE.set("alpha", v), WorldModules::pct),
                    new Rows.Slider("Thickness", null, 1, 8, 0.5, () -> WorldModules.BLOCK_OUTLINE.opt("width", 3),
                            v -> WorldModules.BLOCK_OUTLINE.set("width", v), v -> String.format(Locale.ROOT, "%.1f px", v)),
                    new Rows.Toggle("Animated rainbow", null, () -> WorldModules.BLOCK_OUTLINE.flag("rainbow", false), v -> WorldModules.BLOCK_OUTLINE.set("rainbow", v))));

    public static int outlineColor(int vanilla) {
        if (!WorldModules.BLOCK_OUTLINE.enabled) return vanilla;
        int rgb = WorldModules.BLOCK_OUTLINE.flag("rainbow", false)
                ? ColorPicker.hsv((System.currentTimeMillis() % 4000) / 4000f, 0.75f, 1f)
                : (int) WorldModules.BLOCK_OUTLINE.opt("color", 0x9D7CFF);
        int a = (int) Math.round(255 * WorldModules.BLOCK_OUTLINE.opt("alpha", 0.9));
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    public static float outlineWidth(float vanilla) {
        return WorldModules.BLOCK_OUTLINE.enabled ? (float) WorldModules.BLOCK_OUTLINE.opt("width", 3) : vanilla;
    }

    // ===================================================== Ciel
    public static final Module CUSTOM_SKY = Modules.register(new Module("custom_sky", "Custom Sky", Category.VISUALS, Icons.SUN, false))
            .withSettings(() -> List.of(
                    new Rows.Choice("Ambience", new String[]{"None", "Storm", "Inferno", "Cosmic aurora", "Lightning", "Nebula", "Thunderstorm", "Cursed night", "My image"}, () -> (int) WorldModules.CUSTOM_SKY.opt("ambience", 0),
                            i -> WorldModules.CUSTOM_SKY.set("ambience", i)),
                    new Rows.Button("Next sky image", "PNG panoramas (2:1) from config/aurora/skies", Icons.SUN, gg.aurora.render.SkyFx::nextImage),
                    new Rows.Info("Current image", gg.aurora.render.SkyFx::imageName),
                    new Rows.Button("Open skies folder", null, Icons.ARROW_DOWN, gg.aurora.render.SkyFx::openFolder),
                    new Rows.Slider("Ambience intensity", null, 0.2, 1, 0.05, () -> WorldModules.CUSTOM_SKY.opt("ambienceAlpha", 0.9),
                            v -> WorldModules.CUSTOM_SKY.set("ambienceAlpha", v), v -> Math.round(v * 100) + " %"),
                    new Rows.Toggle("Fast sky", "Computed 20 times per second: far fewer GPU costs", () -> WorldModules.CUSTOM_SKY.flag("cached", true),
                            v -> WorldModules.CUSTOM_SKY.set("cached", v)),
                    new Rows.Toggle("Custom color", null, () -> WorldModules.CUSTOM_SKY.flag("tint", true), v -> WorldModules.CUSTOM_SKY.set("tint", v)),
                    new Rows.ColorRow("Sky color", () -> (int) WorldModules.CUSTOM_SKY.opt("color", 0x6A4FD8), v -> WorldModules.CUSTOM_SKY.set("color", v)),
                    new Rows.Toggle("Animated rainbow", null, () -> WorldModules.CUSTOM_SKY.flag("rainbow", false), v -> WorldModules.CUSTOM_SKY.set("rainbow", v)),
                    new Rows.Toggle("Follow day and night", "Darkens the color at night", () -> WorldModules.CUSTOM_SKY.flag("cycle", true), v -> WorldModules.CUSTOM_SKY.set("cycle", v)),
                    new Rows.Toggle("Fixed time", "Only changes what you see", () -> WorldModules.CUSTOM_SKY.flag("fixed", false), v -> WorldModules.CUSTOM_SKY.set("fixed", v)),
                    new Rows.Slider("Time", null, 0, 23.75, 0.25, () -> WorldModules.CUSTOM_SKY.opt("hour", 22),
                            v -> WorldModules.CUSTOM_SKY.set("hour", v), v -> String.format(Locale.ROOT, "%02d:%02d", (int) v, (int) Math.round((v % 1) * 60))),
                    new Rows.Toggle("Stars always visible", null, () -> WorldModules.CUSTOM_SKY.flag("stars", false), v -> WorldModules.CUSTOM_SKY.set("stars", v))));

    /** Couleur du ciel (ARGB) ou la valeur vanilla. */
    public static int skyColor(int vanilla) {
        if (!WorldModules.CUSTOM_SKY.enabled || !WorldModules.CUSTOM_SKY.flag("tint", true)) return vanilla;
        int rgb = WorldModules.CUSTOM_SKY.flag("rainbow", false)
                ? ColorPicker.hsv((System.currentTimeMillis() % 20000) / 20000f, 0.55f, 0.85f)
                : (int) WorldModules.CUSTOM_SKY.opt("color", 0x6A4FD8);
        if (WorldModules.CUSTOM_SKY.flag("cycle", true)) {
            // luminosité du ciel vanilla (jour ~0.7, nuit ~0) appliquée à la couleur choisie
            float lum = ((vanilla >> 16 & 255) * 0.3f + (vanilla >> 8 & 255) * 0.59f + (vanilla & 255) * 0.11f) / 255f;
            float k = Math.min(1f, Math.max(0.06f, lum / 0.62f));
            rgb = (Math.round((rgb >> 16 & 255) * k) << 16) | (Math.round((rgb >> 8 & 255) * k) << 8) | Math.round((rgb & 255) * k);
        }
        return (vanilla & 0xFF000000) | (rgb & 0xFFFFFF);
    }

    /** Ambiance de ciel : 0 = aucune, 1 tempête, 2 enfer, 3 aurore cosmique, 4 éclairs, 5 nébuleuse, 6 orage. */
    public static int ambience() {
        return WorldModules.CUSTOM_SKY.enabled ? (int) WorldModules.CUSTOM_SKY.opt("ambience", 0) : 0;
    }

    public static float ambienceAlpha() {
        return (float) WorldModules.CUSTOM_SKY.opt("ambienceAlpha", 0.9);
    }

    public static boolean starsAlways() {
        return WorldModules.CUSTOM_SKY.enabled && WorldModules.CUSTOM_SKY.flag("stars", false);
    }

    /** Heure du jour imposée en ticks (0 = 6h00), ou -1. */
    public static long fixedTime() {
        long changer = NewModules.fixedTime();
        if (changer >= 0) return changer;
        if (!WorldModules.CUSTOM_SKY.enabled || !WorldModules.CUSTOM_SKY.flag("fixed", false)) return -1;
        double hour = WorldModules.CUSTOM_SKY.opt("hour", 22);
        return (long) (((hour - 6 + 24) % 24) * 1000);
    }

    // ===================================================== Météo
    public static final Module WEATHER = Modules.register(new Module("weather", "Weather Control", Category.VISUALS, Icons.WIFI, false))
            .withSettings(() -> List.of(
                    new Rows.Choice("Weather", new String[]{"Light", "Rain", "Thunderstorm"},
                            () -> (int) WorldModules.WEATHER.opt("mode", 0), i -> WorldModules.WEATHER.set("mode", i))));

    /** -1 = vanilla, sinon niveau de pluie imposé. */
    public static float rainLevel() {
        if (!WorldModules.WEATHER.enabled) return -1;
        return WorldModules.WEATHER.opt("mode", 0) == 0 ? 0f : 1f;
    }

    public static float thunderLevel() {
        if (!WorldModules.WEATHER.enabled) return -1;
        return WorldModules.WEATHER.opt("mode", 0) == 2 ? 1f : 0f;
    }

    // ===================================================== Eau claire
    public static final Module CLEAR_WATER = Modules.register(new Module("clear_water", "Clear Water", Category.VISUALS, Icons.EYE, false))
            .withSettings(() -> List.of(
                    new Rows.Slider("Clarity", null, 0.2, 1, 0.05, () -> WorldModules.CLEAR_WATER.opt("clarity", 1), v -> WorldModules.CLEAR_WATER.set("clarity", v), WorldModules::pct),
                    new Rows.Toggle("Hide the underwater filter", null, () -> WorldModules.CLEAR_WATER.flag("overlay", true), v -> WorldModules.CLEAR_WATER.set("overlay", v))));

    /** Part de brouillard aquatique retirée (0 = vanilla, 1 = aucun brouillard). */
    public static float waterClarity() {
        return CLEAR_WATER.enabled ? (float) CLEAR_WATER.opt("clarity", 1) : 0f;
    }

    public static boolean hideWaterOverlay() {
        return CLEAR_WATER.enabled && CLEAR_WATER.flag("overlay", true);
    }

    // ===================================================== Vitesse de vol
    public static final Module FLY_SPEED = Modules.register(new Module("fly_speed", "Fly Speed", Category.PLAYER, Icons.WAND, false) {
        @Override
        protected void onToggle() {
            LocalPlayer p = Minecraft.getInstance().player;
            if (!enabled && p != null) p.getAbilities().setFlyingSpeed(0.05f);
        }
    }).withSettings(() -> List.of(
            new Rows.Slider("Speed (creative)", null, 1, 10, 0.25, () -> WorldModules.FLY_SPEED.opt("mult", 2.5),
                    v -> WorldModules.FLY_SPEED.set("mult", v), v -> String.format(Locale.ROOT, "x%.2f", v))));

    // ===================================================== Optimizer +++
    public static final Module OPTIMIZER = Modules.register(new Module("optimizer", "Optimizer +++", Category.MISC, Icons.GAUGE, false))
            .withSettings(() -> List.of(
                    new Rows.Info("Principle", "Cuts what's expensive without touching textures"),
                    new Rows.Choice("Particles", new String[]{"None", "Reduced", "All"},
                            () -> (int) WorldModules.OPTIMIZER.opt("particles", 1), i -> WorldModules.OPTIMIZER.set("particles", i)),
                    new Rows.Slider("Max particles per tick", "Avoids FPS drops in combat (explosions, potions…)", 10, 300, 10,
                            () -> WorldModules.OPTIMIZER.opt("particleCap", 80), v -> WorldModules.OPTIMIZER.set("particleCap", v), v -> Math.round(v) + ""),
                    new Rows.Toggle("Hide potion particles", "Effect swirls around players",
                            () -> WorldModules.OPTIMIZER.flag("potionParticles", true), v -> WorldModules.OPTIMIZER.set("potionParticles", v)),
                    new Rows.Slider("Entity distance", null, 16, 160, 8, () -> WorldModules.OPTIMIZER.opt("entityDist", 64),
                            v -> WorldModules.OPTIMIZER.set("entityDist", v), v -> Math.round(v) + " blocs"),
                    new Rows.Slider("Ground item distance", null, 8, 64, 4, () -> WorldModules.OPTIMIZER.opt("itemDist", 24),
                            v -> WorldModules.OPTIMIZER.set("itemDist", v), v -> Math.round(v) + " blocs"),
                    new Rows.Slider("Block entity distance", "Chests, signs, banners, heads…", 16, 128, 8,
                            () -> WorldModules.OPTIMIZER.opt("blockEntityDist", 48), v -> WorldModules.OPTIMIZER.set("blockEntityDist", v),
                            v -> Math.round(v) + " blocs"),
                    new Rows.Toggle("Dynamic distances", "Shortens distances when FPS drops, restores them after",
                            () -> WorldModules.OPTIMIZER.flag("dynamic", true), v -> WorldModules.OPTIMIZER.set("dynamic", v)),
                    new Rows.Slider("Target FPS", "For dynamic distances", 30, 240, 10, () -> WorldModules.OPTIMIZER.opt("target", 90),
                            v -> WorldModules.OPTIMIZER.set("target", v), v -> Math.round(v) + " FPS"),
                    new Rows.Slider("Unfocused FPS", "When the window isn't active (0 = no limit)", 0, 60, 5,
                            () -> WorldModules.OPTIMIZER.opt("bgFps", 15), v -> WorldModules.OPTIMIZER.set("bgFps", v),
                            v -> v < 1 ? "None" : Math.round(v) + " FPS"),
                    new Rows.Toggle("Hide armor stands", null, () -> WorldModules.OPTIMIZER.flag("hideStands", false), v -> WorldModules.OPTIMIZER.set("hideStands", v)),
                    new Rows.Info("Status", WorldModules::optimizerStatus)));

    /** Culling intelligent (inspiré d'Entity Culling) : ce qui est caché derrière des murs n'est pas dessiné. */
    public static final Module CULLING = Modules.register(new Module("smart_culling", "Smart culling", Category.MISC, Icons.EYE, true))
            .withSettings(() -> List.of(
                    new Rows.Info("Principle", "Skips what's hidden behind opaque blocks"),
                    new Rows.Toggle("Entities", "Mobs, items, frames… behind walls", () -> WorldModules.CULLING.flag("entities", true),
                            v -> WorldModules.CULLING.set("entities", v)),
                    new Rows.Toggle("Block entities", "Chests, signs, banners inside buildings", () -> WorldModules.CULLING.flag("blockEntities", true),
                            v -> WorldModules.CULLING.set("blockEntities", v)),
                    new Rows.Toggle("Players too", "Also hides their name through walls", () -> WorldModules.CULLING.flag("players", false),
                            v -> WorldModules.CULLING.set("players", v)),
                    new Rows.Toggle("Off-screen particles", "Ignores the ones behind you", () -> WorldModules.CULLING.flag("particles", true),
                            v -> WorldModules.CULLING.set("particles", v))));

    /** Entité cachée derrière des blocs (culling intelligent). */
    public static boolean occluded(Entity e) {
        if (!CULLING.enabled || !CULLING.flag("entities", true) || e == Minecraft.getInstance().player || e.isCurrentlyGlowing()) return false;
        if (e instanceof net.minecraft.world.entity.player.Player && !CULLING.flag("players", false)) return false;
        var b = e.getBoundingBox();
        if (b.getXsize() > 4 || b.getYsize() > 4 || b.getZsize() > 4) return false; // gros mobs (dragon…) : jamais
        return !gg.aurora.render.Occlusion.visible(((long) e.getId() << 1) | 1, b, 0.25);
    }

    /** Bloc-entité caché derrière des blocs (culling intelligent). */
    public static boolean occluded(net.minecraft.world.level.block.entity.BlockEntity be) {
        if (!CULLING.enabled || !CULLING.flag("blockEntities", true)) return false;
        // faisceaux de balise et portails de l'End : visibles de loin, jamais cachés
        if (be instanceof net.minecraft.world.level.block.entity.BeaconBlockEntity
                || be instanceof net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity) return false;
        var p = be.getBlockPos();
        int x = p.getX(), y = p.getY(), z = p.getZ();
        return !gg.aurora.render.Occlusion.visible(p.asLong() << 1, x - 0.1, y - 0.1, z - 0.1, x + 1.1, y + 1.1, z + 1.1);
    }

    private static final org.joml.Vector3f FWD = new org.joml.Vector3f();

    /** Particule qui apparaît hors du champ de vision, loin de la caméra. */
    public static boolean offscreenParticle(net.minecraft.client.particle.Particle particle) {
        if (!CULLING.enabled || !CULLING.flag("particles", true)) return false;
        var mc = Minecraft.getInstance();
        var b = particle.getBoundingBox();
        var cam = mc.gameRenderer.getMainCamera();
        var pos = cam.position();
        double dx = (b.minX + b.maxX) / 2 - pos.x, dy = (b.minY + b.maxY) / 2 - pos.y, dz = (b.minZ + b.maxZ) / 2 - pos.z;
        double d2 = dx * dx + dy * dy + dz * dz;
        if (d2 < 8 * 8) return false;
        org.joml.Vector3f f = cam.rotation().transform(FWD.set(0, 0, -1));
        double cos = (dx * f.x + dy * f.y + dz * f.z) / Math.sqrt(d2);
        double half = Math.toRadians(mc.options.fov().get() * 1.6) * 0.5 + 0.3;
        return cos < Math.cos(Math.min(Math.PI, half));
    }

    /** Optimizer actif : le module, ou le Boost en niveau Extrême. */
    public static boolean optimizing() {
        return OPTIMIZER.enabled || gg.aurora.Boost.extreme();
    }

    /** Facteur des distances dynamiques (1 = normal, 0,4 = réduit au maximum). */
    private static float dynFactor = 1;
    private static long lastDyn;
    private static int particlesThisTick;

    private static String optimizerStatus() {
        if (!optimizing()) return "Off";
        String d = OPTIMIZER.flag("dynamic", true) ? " · distances " + Math.round(dynFactor * 100) + " %" : "";
        return Minecraft.getInstance().getFps() + " FPS" + d;
    }

    private static void optimizerTick(Minecraft mc) {
        particlesThisTick = 0;
        long now = System.currentTimeMillis();
        if (!optimizing() || !OPTIMIZER.flag("dynamic", true)) {
            dynFactor = 1;
            return;
        }
        if (now - lastDyn < 1000) return;
        lastDyn = now;
        int fps = mc.getFps(), target = (int) OPTIMIZER.opt("target", 90);
        // descend vite quand ça rame, remonte doucement quand il y a de la marge
        if (fps < target * 0.9f) dynFactor = Math.max(0.4f, dynFactor - 0.1f);
        else if (fps > target * 1.25f) dynFactor = Math.min(1f, dynFactor + 0.05f);
    }

    /** Facteur appliqué aux distances (dynamique, et réduit en Boost Extrême). */
    private static double distScale() {
        return dynFactor * (gg.aurora.Boost.extreme() && !OPTIMIZER.enabled ? 0.75 : 1);
    }

    /** Distance max des blocs-entités, ou -1 si pas de limite. */
    public static double blockEntityDistance() {
        return optimizing() ? OPTIMIZER.opt("blockEntityDist", 48) * distScale() : -1;
    }

    /** FPS max quand la fenêtre n'a pas le focus (0 = pas de limite). */
    public static int backgroundFps() {
        if (!optimizing() || ModConflicts.dynamicFps) return 0;
        return (int) OPTIMIZER.opt("bgFps", 15);
    }

    private static int particleCounter;

    /** false = la particule est ignorée. */
    public static boolean allowParticle(net.minecraft.client.particle.Particle particle) {
        if (!optimizing()) return true;
        int mode = gg.aurora.Boost.extreme() && !OPTIMIZER.enabled ? 1 : (int) WorldModules.OPTIMIZER.opt("particles", 1);
        if (mode == 0) return false;
        if (particle instanceof net.minecraft.client.particle.SpellParticle && OPTIMIZER.flag("potionParticles", true)) return false;
        // plafond par tick : pas de pic quand une explosion ou un combat en crée des centaines d'un coup
        if (++particlesThisTick > OPTIMIZER.opt("particleCap", 80)) return false;
        return mode == 2 || (particleCounter++ & 1) == 0;
    }

    /** false = l'entité n'est pas dessinée. */
    public static boolean allowEntity(Entity e, double camX, double camY, double camZ) {
        if (e instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow arrow && PvpOptimizers.hideGroundArrows()
                && ((gg.aurora.mixin.AbstractArrowAccessor) arrow).aurora$isInGround()) return false;
        if (e instanceof net.minecraft.world.entity.ExperienceOrb && PvpOptimizers.hideXp()) return false;
        // Adaptive Detail : entités lointaines masquées (sauf joueurs), distance allongée au zoom
        if (CombatModules.DETAIL.enabled && !(e instanceof net.minecraft.world.entity.player.Player)
                && !CombatModules.allowDetail(e.distanceToSqr(camX, camY, camZ))) return false;
        if (!optimizing() || e == Minecraft.getInstance().player) return true;
        if (e instanceof ArmorStand && WorldModules.OPTIMIZER.flag("hideStands", false) && !e.hasCustomName()) return false;
        // les joueurs restent visibles plus loin que le reste (PvP)
        double max = e instanceof ItemEntity ? WorldModules.OPTIMIZER.opt("itemDist", 24)
                : WorldModules.OPTIMIZER.opt("entityDist", 64) * (e instanceof net.minecraft.world.entity.player.Player ? 1.5 : 1);
        max *= distScale();
        return e.distanceToSqr(camX, camY, camZ) <= max * max;
    }

    private static Object lastLevel;

    public static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p != null && WorldModules.FLY_SPEED.enabled && p.getAbilities().mayfly) {
            p.getAbilities().setFlyingSpeed((float) (0.05 * WorldModules.FLY_SPEED.opt("mult", 2.5)));
        }
        optimizerTick(mc);
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            // (plus de System.gc() : un GC complet forcé fige le jeu ; G1 libère la mémoire tout seul)
        }
    }

    /** Force le chargement de la classe (et donc l'enregistrement des modules). */
    public static void init() {}
}
