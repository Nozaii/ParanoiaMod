package gg.aurora.module;

import gg.aurora.Settings;
import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Doublons avec d'autres mods : quand un mod installé fait déjà le même travail qu'un module, le
 * module est coupé une seule fois (au premier lancement où le doublon est détecté). Si le joueur le
 * réactive ensuite, on respecte son choix. La raison s'affiche dans les réglages du module.
 */
public final class ModConflicts {
    private ModConflicts() {}

    private record Rule(String moduleId, String label, String... modIds) {}

    private static final List<Rule> RULES = List.of(
            new Rule("smart_culling", "Entity Culling", "entityculling"),
            new Rule("zoom", "Zoomify / Ok Zoomer", "zoomify", "ok_zoomer", "logical_zoom"),
            new Rule("freelook", "Freelook", "freelook", "perspectivemod"),
            new Rule("motion_blur", "Natural Motion Blur", "naturalmotionblur", "motionblur"),
            new Rule("crystal_optimizer", "Marlow's Crystal Optimizer", "marlowcrystal"),
            new Rule("anchor_optimizer", "Hero's Anchor Optimizer", "herosanchoroptimizer"),
            new Rule("gap_optimizer", "Consumable Optimizer", "consumableoptimizer"),
            new Rule("shield_fix", "Shield Fixes", "shieldfixes"));

    /** Dynamic FPS gère déjà les FPS en arrière-plan : on ne limite pas une deuxième fois. */
    public static boolean dynamicFps;

    /** À appeler après le chargement de la config. Renvoie les modules coupés à ce lancement. */
    public static List<String> apply() {
        FabricLoader fl = FabricLoader.getInstance();
        dynamicFps = fl.isModLoaded("dynamic_fps");
        Set<String> done = new LinkedHashSet<>(Arrays.asList(Settings.autoDisabled.split(",")));
        done.remove("");
        List<String> now = new ArrayList<>();
        for (Rule r : RULES) {
            String found = null;
            for (String id : r.modIds()) if (fl.isModLoaded(id)) found = id;
            Module m = Modules.byId(r.moduleId());
            if (m == null) continue;
            if (found == null) {
                m.conflict = null;
                done.remove(r.moduleId()); // mod retiré : on pourra re-signaler s'il revient
                continue;
            }
            m.conflict = r.label();
            if (done.add(r.moduleId()) && m.enabled) {
                m.enabled = false;
                m.toggleAnim = 0;
                now.add(m.name);
            }
        }
        Settings.autoDisabled = String.join(",", done);
        return now;
    }
}
