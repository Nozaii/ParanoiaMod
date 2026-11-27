package gg.aurora;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;

/**
 * Plein écran fenêtré sans bordure : la fenêtre perd sa barre de titre et couvre tout l'écran
 * (barre des tâches comprise). Contrairement au plein écran exclusif, Alt+Tab et la touche Windows
 * ne réduisent pas le jeu. Quand le réglage est actif, F11 bascule entre ce mode et le fenêtré.
 */
public final class Borderless {
    private Borderless() {}

    /** Le mode sans bordure est-il appliqué en ce moment ? */
    private static boolean active;
    /** Position et taille de la fenêtre avant passage en sans bordure. */
    private static int savedX, savedY, savedW = 854, savedH = 480;
    /** Laisse passer l'appel vanilla à toggleFullScreen (sortie du plein écran exclusif). */
    public static boolean vanilla;

    public static boolean active() {
        return active;
    }

    /** Premier tick : applique le réglage sauvegardé. */
    public static void init(Minecraft mc) {
        if (Settings.borderless) enter(mc);
    }

    /** Réglage changé depuis le menu. */
    public static void setEnabled(boolean on) {
        Settings.borderless = on;
        Minecraft mc = Minecraft.getInstance();
        if (on) enter(mc);
        else exit(mc);
    }

    /** F11 (ou la case Plein écran vanilla) quand le réglage est actif. */
    public static void toggle(Minecraft mc) {
        if (active) exit(mc);
        else enter(mc);
    }

    private static void enter(Minecraft mc) {
        if (active) return;
        Window w = mc.getWindow();
        // le plein écran exclusif doit d'abord être quitté, sinon GLFW garde le moniteur
        if (w.isFullscreen()) {
            vanilla = true;
            try {
                mc.options.fullscreen().set(false);
            } finally {
                vanilla = false;
            }
        }
        long h = w.handle();
        int[] x = new int[1], y = new int[1], ww = new int[1], hh = new int[1];
        GLFW.glfwGetWindowPos(h, x, y);
        GLFW.glfwGetWindowSize(h, ww, hh);
        savedX = x[0];
        savedY = y[0];
        savedW = Math.max(320, ww[0]);
        savedH = Math.max(240, hh[0]);

        long monitor = monitorOf(h);
        if (monitor == 0L) return;
        GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
        if (mode == null) return;
        int[] mx = new int[1], my = new int[1];
        GLFW.glfwGetMonitorPos(monitor, mx, my);

        GLFW.glfwSetWindowAttrib(h, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
        GLFW.glfwSetWindowAttrib(h, GLFW.GLFW_AUTO_ICONIFY, GLFW.GLFW_FALSE);
        // fenêtre normale (monitor = 0) à la taille exacte de l'écran : Windows masque la barre des tâches
        GLFW.glfwSetWindowMonitor(h, 0L, mx[0], my[0], mode.width(), mode.height(), GLFW.GLFW_DONT_CARE);
        active = true;
    }

    private static void exit(Minecraft mc) {
        if (!active) return;
        active = false;
        long h = mc.getWindow().handle();
        GLFW.glfwSetWindowAttrib(h, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
        GLFW.glfwSetWindowAttrib(h, GLFW.GLFW_AUTO_ICONIFY, GLFW.GLFW_TRUE);
        GLFW.glfwSetWindowMonitor(h, 0L, savedX, savedY, savedW, savedH, GLFW.GLFW_DONT_CARE);
    }

    /** Moniteur qui contient le centre de la fenêtre (sinon le principal). */
    private static long monitorOf(long h) {
        int[] x = new int[1], y = new int[1], ww = new int[1], hh = new int[1];
        GLFW.glfwGetWindowPos(h, x, y);
        GLFW.glfwGetWindowSize(h, ww, hh);
        int cx = x[0] + ww[0] / 2, cy = y[0] + hh[0] / 2;
        PointerBuffer monitors = GLFW.glfwGetMonitors();
        if (monitors != null) {
            for (int i = 0; i < monitors.limit(); i++) {
                long m = monitors.get(i);
                GLFWVidMode mode = GLFW.glfwGetVideoMode(m);
                if (mode == null) continue;
                int[] mx = new int[1], my = new int[1];
                GLFW.glfwGetMonitorPos(m, mx, my);
                if (cx >= mx[0] && cx < mx[0] + mode.width() && cy >= my[0] && cy < my[0] + mode.height()) return m;
            }
        }
        return GLFW.glfwGetPrimaryMonitor();
    }
}
