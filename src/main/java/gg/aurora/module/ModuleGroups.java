package gg.aurora.module;

/**
 * Rangement des modules : chaque catégorie est découpée en groupes nommés (affichés comme
 * en-têtes dans le menu), dans un ordre fixe. Un module absent de la liste va dans « Autres ».
 */
public final class ModuleGroups {
    private ModuleGroups() {}

    private record Group(Category category, String name, String... ids) {}

    private static final Group[] GROUPS = {
            // PvP
            new Group(Category.PVP, "Combat", "combat_sounds", "hitboxes", "hit_color", "hit_effects", "target_info", "low_hp", "totem_pops", "hitmarker", "damage_indicator"),
            new Group(Category.PVP, "Shield", "shield_fix", "shield_status", "shield_size"),
            new Group(Category.PVP, "Optimizers", "crystal_optimizer", "gap_optimizer", "consumable_optimizer", "anchor_optimizer", "projectile_optimizer"),
            new Group(Category.PVP, "Combat displays", "reach", "combo", "target_hud", "pops_hud", "item_cooldowns", "item_counter"),
            // HUD
            new Group(Category.HUD, "Essentials", "fps", "cps", "ping", "keystrokes", "coords", "frametime_graph", "ping_graph"),
            new Group(Category.HUD, "Equipment", "armor", "arrows", "totems", "potions", "held_item_info", "saturation"),
            new Group(Category.HUD, "Movement", "sprint_hud", "speed", "compass"),
            new Group(Category.HUD, "World and session", "biome", "light_level", "day", "clock", "session", "server", "memory", "watermark", "stopwatch"),
            new Group(Category.HUD, "Music", "now_playing"),
            // Visuels
            new Group(Category.VISUALS, "Camera and crosshair", "draw_crosshair", "custom_fov", "zoom", "no_hurt_cam", "no_view_bobbing", "rescale_resolution"),
            new Group(Category.VISUALS, "Hand and items", "hand_pose", "hand_aura", "old_animations", "swing_animation", "custom_glint", "low_fire", "totem_anim", "no_totem_anim"),
            new Group(Category.VISUALS, "Sky and world", "custom_sky", "weather", "time_changer", "clear_water", "fog_controls", "fullbright", "no_night_vision", "block_outline"),
            new Group(Category.VISUALS, "Effects", "shockwaves", "ground_mirror", "motion_blur", "filters", "ambient_particles", "item_flames", "trail", "jump_circles", "target_esp", "friend_glow", "hologram", "bats"),
            new Group(Category.VISUALS, "Interface", "hide_scoreboard", "hide_bossbar", "overlay_editor", "item_tooltips"),
            // Joueur
            new Group(Category.PLAYER, "Movement", "freelook", "toggle_sprint", "toggle_sneak", "fly_speed"),
            new Group(Category.PLAYER, "Survival", "auto_respawn", "drop_protect", "death_coords"),
            new Group(Category.PLAYER, "Expression", "emotes"),
            // Divers
            new Group(Category.MISC, "Performance", "hud_cache", "smooth_scroll", "adaptive_mode", "screen_limit", "optimizer", "smart_culling", "adaptive_detail", "menu_fps", "item_size", "item_physic"),
            new Group(Category.MISC, "Chat and stream", "chat_plus", "stream_mode", "auto_gg"),
            new Group(Category.MISC, "Tools", "mod_manager", "sound_mixer", "screenshot_tools"),
    };

    private static boolean applied;

    /** Range chaque module (catégorie + groupe). Appelé une fois, après l'enregistrement de tous les modules. */
    public static void apply() {
        if (applied) return;
        applied = true;
        for (Module m : Modules.ALL) {
            m.groupOrder = 999;
            m.group = "Others";
            for (int gi = 0; gi < GROUPS.length; gi++) {
                Group g = GROUPS[gi];
                for (int k = 0; k < g.ids().length; k++) {
                    if (g.ids()[k].equals(m.id)) {
                        m.category = g.category();
                        m.group = g.name();
                        m.groupOrder = gi * 100 + k;
                    }
                }
            }
        }
    }
}
