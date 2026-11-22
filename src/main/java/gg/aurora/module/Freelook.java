package gg.aurora.module;

import gg.aurora.ui.Icons;
import gg.aurora.ui.Rows;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * Freelook : regarder autour de soi sans tourner le personnage. Pendant l'appui (ou en mode bascule),
 * la souris fait tourner la caméra seule ; au relâchement elle revient en douceur derrière le joueur.
 */
public final class Freelook {
    private Freelook() {}

    public static final Module FREELOOK = Modules.register(new Module("freelook", "Freelook", Category.PLAYER, Icons.EYE, true))
            .withSettings(() -> List.of(
                    new Rows.Info("Key", "Left Alt by default (change it in the visual keyboard)"),
                    new Rows.Toggle("Toggle mode", "One press turns on, another turns off", () -> Freelook.FREELOOK.flag("toggle", false),
                            v -> Freelook.FREELOOK.set("toggle", v)),
                    new Rows.Toggle("Switch to third person", "Back view during freelook", () -> Freelook.FREELOOK.flag("third", true),
                            v -> Freelook.FREELOOK.set("third", v))));

    public static KeyMapping key;
    private static boolean active, toggled, wasDown;
    private static CameraType saved;
    /** Décalage de la caméra par rapport au regard du joueur (degrés). */
    private static float dYaw, dPitch;

    public static void init(KeyMapping.Category category) {
        key = net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.aurora.freelook", com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT, category));
    }

    public static boolean active() {
        return active;
    }

    public static void tick(Minecraft mc) {
        if (key == null) return;
        boolean down = FREELOOK.enabled && mc.screen == null && mc.player != null && key.isDown();
        boolean want;
        if (FREELOOK.flag("toggle", false)) {
            if (down && !wasDown) toggled = !toggled;
            want = toggled && FREELOOK.enabled && mc.player != null;
        } else {
            want = down;
        }
        wasDown = down;
        if (want && !active) {
            active = true;
            if (FREELOOK.flag("third", true) && mc.options.getCameraType().isFirstPerson()) {
                saved = mc.options.getCameraType();
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            }
        } else if (!want && active) {
            active = false;
            // instant snap back to the player's view: no easing, no disorientation
            dYaw = 0;
            dPitch = 0;
            if (saved != null) {
                mc.options.setCameraType(saved);
                saved = null;
            }
        }
    }

    /** Mouvement de souris pendant le freelook : tourne la caméra, pas le joueur. true = consommé. */
    public static boolean turn(double yaw, double pitch) {
        if (!active) return false;
        dYaw += (float) yaw * 0.15f;
        Minecraft mc = Minecraft.getInstance();
        float playerPitch = mc.player != null ? mc.player.getXRot() : 0;
        dPitch = Math.max(-90 - playerPitch, Math.min(90 - playerPitch, dPitch + (float) pitch * 0.15f));
        return true;
    }

    /** Décalage appliqué à la caméra ; revient vers 0 en douceur hors freelook. */
    public static float yawOffset() {
        return dYaw;
    }

    public static float pitchOffset() {
        return dPitch;
    }
}
