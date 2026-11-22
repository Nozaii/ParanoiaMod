package gg.aurora;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import gg.aurora.mixin.OptionInstanceAccessor;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.PrioritizeChunkUpdates;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.server.level.ParticleStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bouton Boost : applique d'un coup les réglages vidéo les plus performants, en sauvegardant les
 * valeurs d'avant pour tout restaurer à l'identique.
 *
 * Sans gel : les valeurs sont posées directement (sans les rappels de chaque option, qui
 * reconstruisaient la carte plusieurs fois), puis la carte est reconstruite une seule fois. Rien ne
 * touche aux textures ni aux shaders (mipmaps, filtrage, transparence avancée) : pas de rechargement.
 *
 * Trois niveaux (Léger, Fort, Extrême), mesure du gain de FPS, et Boost automatique quand les FPS
 * restent sous un seuil.
 */
public final class Boost {
    private Boost() {}

    public static final String[] LEVELS = {"Light", "Strong", "Extreme"};

    public static boolean active;
    /** Niveau appliqué (0 léger, 1 fort, 2 extrême). */
    public static int appliedLevel = 1;
    /** Valeurs d'origine, par nom d'option (sauvegardées dans la config pour survivre à un redémarrage). */
    private static final Map<String, Object> backup = new LinkedHashMap<>();

    private record Entry(String name, OptionInstance<?> option, Object value) {}

    /** Réglages du niveau ; chaque niveau reprend le précédent et va plus loin. */
    private static List<Entry> targets(Options o, int level) {
        List<Entry> e = new ArrayList<>();
        // léger : gratuit visuellement ou presque, gros gain
        e.add(new Entry("enableVsync", o.enableVsync(), false));
        e.add(new Entry("framerateLimit", o.framerateLimit(), 260));
        e.add(new Entry("inactivityFpsLimit", o.inactivityFpsLimit(), InactivityFpsLimit.AFK));
        e.add(new Entry("cloudStatus", o.cloudStatus(), CloudStatus.OFF));
        e.add(new Entry("entityShadows", o.entityShadows(), false));
        e.add(new Entry("menuBackgroundBlurriness", o.menuBackgroundBlurriness(), 0));
        e.add(new Entry("chunkSectionFadeInTime", o.chunkSectionFadeInTime(), 0.0));
        e.add(new Entry("biomeBlendRadius", o.biomeBlendRadius(), level >= 1 ? 0 : 1));
        // transparence améliorée (ex-« Fabulous ») : passes plein écran en plus, coûteuse partout
        e.add(new Entry("improvedTransparency", o.improvedTransparency(), false));
        if (level >= 1) {
            // fort : moins de géométrie et d'entités
            e.add(new Entry("renderDistance", o.renderDistance(), Math.min(o.renderDistance().get(), 8)));
            e.add(new Entry("simulationDistance", o.simulationDistance(), Math.min(o.simulationDistance().get(), 6)));
            e.add(new Entry("entityDistanceScaling", o.entityDistanceScaling(), 0.75));
            e.add(new Entry("cutoutLeaves", o.cutoutLeaves(), false));
            e.add(new Entry("ambientOcclusion", o.ambientOcclusion(), false));
            e.add(new Entry("particles", o.particles(), ParticleStatus.DECREASED));
            e.add(new Entry("prioritizeChunkUpdates", o.prioritizeChunkUpdates(), PrioritizeChunkUpdates.NONE));
            e.add(new Entry("vignette", o.vignette(), false));
            // pluie et neige : moins de colonnes de particules autour du joueur (3 à 10, 10 par défaut)
            e.add(new Entry("weatherRadius", o.weatherRadius(), Math.min(o.weatherRadius().get(), 5)));
        }
        if (level >= 2) {
            // extrême : tout pour les FPS
            e.add(new Entry("renderDistance", o.renderDistance(), Math.min(o.renderDistance().get(), 5)));
            e.add(new Entry("simulationDistance", o.simulationDistance(), 5));
            e.add(new Entry("entityDistanceScaling", o.entityDistanceScaling(), 0.5));
            e.add(new Entry("particles", o.particles(), ParticleStatus.MINIMAL));
            e.add(new Entry("weatherRadius", o.weatherRadius(), 3));
        }
        // garde seulement la dernière valeur de chaque option
        Map<String, Entry> last = new LinkedHashMap<>();
        for (Entry x : e) last.put(x.name(), x);
        return new ArrayList<>(last.values());
    }

    public static void toggle() {
        ensureLoaded();
        if (active) restore();
        else apply(Settings.boostLevel);
    }

    /** Niveau Extrême actif : les optimisations d'Optimizer +++ s'appliquent même si le module est coupé. */
    public static boolean extreme() {
        return active && appliedLevel >= 2;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void apply(int level) {
        Minecraft mc = Minecraft.getInstance();
        Options o = mc.options;
        backup.clear();
        backup.put("graphicsPreset", o.graphicsPreset().get());
        List<Entry> entries = targets(o, level);
        boolean rebuild = false;
        for (Entry e : entries) {
            Object before = e.option().get();
            backup.put(e.name(), before);
            if (!before.equals(e.value())) {
                if (withCallback(e.name())) set((OptionInstance) e.option(), e.value());
                else raw(e.option(), e.value());
                rebuild |= needsRebuild(e.name());
            }
        }
        rebuild |= SodiumBoost.apply(backup);
        // étiquette « personnalisé » (les valeurs ne correspondent plus à un préréglage)
        raw(o.graphicsPreset(), GraphicsPreset.CUSTOM);
        appliedLevel = level;
        active = true;
        finish(mc, rebuild);
        measureFrom(mc);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void restore() {
        Minecraft mc = Minecraft.getInstance();
        Options o = mc.options;
        boolean rebuild = false;
        for (Entry e : targets(o, 2)) {
            if (!backup.containsKey(e.name())) continue;
            Object v = backup.get(e.name());
            if (!e.option().get().equals(v)) {
                if (withCallback(e.name())) set((OptionInstance) e.option(), v);
                else raw(e.option(), v);
                rebuild |= needsRebuild(e.name());
            }
        }
        rebuild |= SodiumBoost.restore(backup);
        // anciennes versions du Boost : mipmaps et filtrage étaient aussi modifiés
        if (backup.get("mipmapLevels") instanceof Integer mip) set((OptionInstance) o.mipmapLevels(), mip);
        if (backup.get("textureFiltering") instanceof TextureFilteringMethod tf) set((OptionInstance) o.textureFiltering(), tf);
        Object preset = backup.get("graphicsPreset");
        if (preset != null) raw(o.graphicsPreset(), preset);
        backup.clear();
        active = false;
        finish(mc, rebuild);
    }

    /** Options posées avec leur rappel vanilla (il reconfigure le rendu au-delà d'une reconstruction de carte). */
    private static boolean withCallback(String name) {
        return name.equals("improvedTransparency");
    }

    /** Options dont le changement demande de reconstruire la carte (une seule fois à la fin). */
    private static boolean needsRebuild(String name) {
        return switch (name) {
            case "renderDistance", "biomeBlendRadius", "cutoutLeaves", "ambientOcclusion", "prioritizeChunkUpdates" -> true;
            default -> false;
        };
    }

    /** Pose la valeur sans déclencher le rappel de l'option (qui reconstruirait la carte à chaque fois). */
    private static void raw(OptionInstance<?> option, Object value) {
        try {
            ((OptionInstanceAccessor) (Object) option).aurora$setValue(value);
        } catch (Exception ex) {
            AuroraClient.LOGGER.warn("Boost : option non appliquée ({})", value);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void set(OptionInstance option, Object value) {
        try {
            option.set(value);
        } catch (Exception ex) {
            AuroraClient.LOGGER.warn("Boost : option non appliquée ({})", value);
        }
    }

    private static void finish(Minecraft mc, boolean rebuild) {
        // effets que les rappels auraient faits, une seule fois
        mc.getWindow().updateVsync(mc.options.enableVsync().get());
        if (mc.level != null && rebuild) mc.levelRenderer.allChanged();
        // le serveur doit connaître la nouvelle distance d'affichage (sinon il envoie encore les chunks lointains)
        if (mc.player != null) mc.options.broadcastOptions();
        mc.options.save();
        Config.save();
    }

    // ---------- gain de FPS et Boost automatique ----------

    private static final float[] fpsHistory = new float[8];
    private static int histPos;
    private static long lastSample, measureAt, lowSince;
    private static float fpsBefore;

    /** Appelé à chaque tick : historique des FPS, mesure du gain, Boost automatique. */
    public static void tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (now - lastSample < 500) return;
        lastSample = now;
        int fps = mc.getFps();
        fpsHistory[histPos++ % fpsHistory.length] = fps;
        if (measureAt > 0 && now >= measureAt) {
            measureAt = 0;
            int after = fps;
            int gain = fpsBefore > 0 ? Math.round((after - fpsBefore) * 100 / fpsBefore) : 0;
            gg.aurora.ui.Toasts.push(gg.aurora.ui.Icons.BOLT, "Boost " + LEVELS[appliedLevel],
                    Math.round(fpsBefore) + " → " + after + " FPS" + (gain > 0 ? " (+" + gain + " %)" : ""), false);
        }
        // Boost automatique : FPS sous le seuil pendant 8 s en jeu
        if (Settings.boostAuto && !active && mc.level != null && mc.screen == null) {
            if (fps < Settings.boostThreshold) {
                if (lowSince == 0) lowSince = now;
                else if (now - lowSince > 8000) {
                    lowSince = 0;
                    ensureLoaded();
                    apply(Settings.boostLevel);
                    gg.aurora.ui.Toasts.push(gg.aurora.ui.Icons.BOLT, "Automatic Boost", "FPS under " + Settings.boostThreshold + " : Boost on", false);
                }
            } else {
                lowSince = 0;
            }
        }
    }

    private static void measureFrom(Minecraft mc) {
        float sum = 0;
        int n = 0;
        for (float f : fpsHistory) {
            if (f > 0) {
                sum += f;
                n++;
            }
        }
        fpsBefore = n > 0 ? sum / n : mc.getFps();
        // laisse la carte se reconstruire avant de mesurer
        measureAt = System.currentTimeMillis() + 4500;
    }

    // ---------- sauvegarde de l'état dans la config ----------

    static JsonObject write() {
        if (pending != null) return pending;
        JsonObject o = new JsonObject();
        o.addProperty("active", active);
        o.addProperty("level", appliedLevel);
        JsonObject b = new JsonObject();
        backup.forEach((k, v) -> {
            if (v instanceof Boolean bo) b.add(k, new JsonPrimitive(bo));
            else if (v instanceof Number n) b.add(k, new JsonPrimitive(n));
            else if (v instanceof Enum<?> en) b.add(k, new JsonPrimitive(en.name()));
        });
        o.add("backup", b);
        return o;
    }

    /** La config est lue avant que les options du jeu existent : on décode la sauvegarde plus tard. */
    private static JsonObject pending;

    static void read(JsonObject o) {
        active = o.has("active") && o.get("active").getAsBoolean();
        appliedLevel = o.has("level") ? o.get("level").getAsInt() : 1;
        pending = o;
    }

    private static void ensureLoaded() {
        if (pending == null) return;
        JsonObject o = pending;
        pending = null;
        decode(o);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void decode(JsonObject o) {
        backup.clear();
        if (!o.has("backup")) return;
        JsonObject b = o.getAsJsonObject("backup");
        // le type de chaque valeur est déduit de la valeur actuelle de l'option
        Options opts = Minecraft.getInstance().options;
        Map<String, Object> current = new LinkedHashMap<>();
        if (opts != null) {
            current.put("graphicsPreset", opts.graphicsPreset().get());
            for (Entry e : targets(opts, 2)) current.put(e.name(), e.option().get());
            current.put("mipmapLevels", opts.mipmapLevels().get());
            current.put("textureFiltering", opts.textureFiltering().get());
        }
        for (String k : b.keySet()) {
            if (k.startsWith(SodiumBoost.PREFIX)) {
                try {
                    backup.put(k, b.get(k).getAsBoolean());
                } catch (Exception ignored) {
                }
                continue;
            }
            Object cur = current.get(k);
            var v = b.get(k);
            try {
                if (cur instanceof Boolean) backup.put(k, v.getAsBoolean());
                else if (cur instanceof Integer) backup.put(k, v.getAsInt());
                else if (cur instanceof Double) backup.put(k, v.getAsDouble());
                else if (cur instanceof Enum<?> en) backup.put(k, Enum.valueOf((Class) en.getDeclaringClass(), v.getAsString()));
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Optimisations de Sodium (si installé) remises à leur valeur par défaut, quand elles ont été
     * coupées : occlusion par le brouillard (chunks cachés dans le brouillard non dessinés), tampons
     * d'envoi avancés, élimination des entités et des faces cachées, animation des seules textures
     * visibles. Champs publics de SodiumOptions, lus par réflexion : aucune dépendance de compilation,
     * rien ne se passe si une version de Sodium les renomme.
     */
    static final class SodiumBoost {
        private SodiumBoost() {}

        static final String PREFIX = "sodium.";
        /** groupe.champ -> valeur visée */
        private static final String[][] TARGETS = {
                {"performance", "useFogOcclusion"},
                {"performance", "useEntityCulling"},
                {"performance", "useBlockFaceCulling"},
                {"performance", "animateOnlyVisibleTextures"},
                {"advanced", "useAdvancedStagingBuffers"}};

        private static Object options() {
            if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium")) return null;
            try {
                Object opts = Class.forName("net.caffeinemc.mods.sodium.client.SodiumClientMod").getMethod("options").invoke(null);
                if (opts == null || (boolean) opts.getClass().getMethod("isReadOnly").invoke(opts)) return null;
                return opts;
            } catch (Throwable t) {
                return null;
            }
        }

        private static java.lang.reflect.Field field(Object opts, String group, String name, Object[] owner) throws Exception {
            Object g = opts.getClass().getField(group).get(opts);
            owner[0] = g;
            return g.getClass().getField(name);
        }

        /** Active les optimisations coupées ; note les anciennes valeurs. Vrai si le rendu doit être rechargé. */
        static boolean apply(Map<String, Object> backup) {
            Object opts = options();
            if (opts == null) return false;
            boolean changed = false, reload = false;
            for (String[] t : TARGETS) {
                try {
                    Object[] owner = new Object[1];
                    var f = field(opts, t[0], t[1], owner);
                    boolean before = f.getBoolean(owner[0]);
                    backup.put(PREFIX + t[0] + "." + t[1], before);
                    if (!before) {
                        f.setBoolean(owner[0], true);
                        changed = true;
                        reload |= t[1].equals("useAdvancedStagingBuffers") || t[1].equals("useBlockFaceCulling");
                    }
                } catch (Throwable ignored) {
                    // champ absent dans cette version de Sodium
                }
            }
            if (changed) save(opts);
            return reload;
        }

        static boolean restore(Map<String, Object> backup) {
            Object opts = options();
            if (opts == null) return false;
            boolean changed = false, reload = false;
            for (String[] t : TARGETS) {
                if (!(backup.get(PREFIX + t[0] + "." + t[1]) instanceof Boolean v)) continue;
                try {
                    Object[] owner = new Object[1];
                    var f = field(opts, t[0], t[1], owner);
                    if (f.getBoolean(owner[0]) != v) {
                        f.setBoolean(owner[0], v);
                        changed = true;
                        reload |= t[1].equals("useAdvancedStagingBuffers") || t[1].equals("useBlockFaceCulling");
                    }
                } catch (Throwable ignored) {
                }
            }
            if (changed) save(opts);
            return reload;
        }

        private static void save(Object opts) {
            try {
                opts.getClass().getMethod("writeToDisk", opts.getClass()).invoke(null, opts);
            } catch (Throwable t) {
                AuroraClient.LOGGER.warn("Boost : options Sodium non enregistrées ({})", t.toString());
            }
        }
    }
}
