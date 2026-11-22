package gg.aurora.waypoint;

import gg.aurora.mixin.GameRendererAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Projects world positions onto the HUD with the exact matrix the world was drawn with this frame
 * (view bobbing, hurt tilt, zoom and nausea included), so labels stay glued to their block instead of
 * swaying when walking. Falls back to a plain perspective if the capture mixin did not apply.
 */
public final class Projector {
    private Projector() {}

    /** Final world projection of the last frame, captured in GameRenderer.renderLevel. */
    public static final Matrix4f CAPTURED = new Matrix4f();
    public static volatile boolean captured;

    private static final Matrix4f PROJ = new Matrix4f();
    private static final Quaternionf VIEW = new Quaternionf();
    private static final Vector3f P = new Vector3f();
    private static final Vector4f CLIP = new Vector4f();
    private static double cx, cy, cz;
    private static float gw, gh;

    public static void capture(Matrix4f m) {
        CAPTURED.set(m);
        captured = true;
    }

    /** Prepares the projection for this HUD frame. Returns false when the camera is not ready. */
    public static boolean begin(DeltaTracker delta, float guiW, float guiH) {
        Minecraft mc = Minecraft.getInstance();
        Camera cam = mc.gameRenderer.getMainCamera();
        if (!cam.isInitialized()) return false;
        if (captured) {
            PROJ.set(CAPTURED);
        } else {
            float fov = ((GameRendererAccessor) mc.gameRenderer).aurora$getFov(cam, delta.getGameTimeDeltaPartialTick(true), true);
            PROJ.set(mc.gameRenderer.getProjectionMatrix(fov));
        }
        VIEW.set(cam.rotation()).conjugate();
        Vec3 c = cam.position();
        cx = c.x;
        cy = c.y;
        cz = c.z;
        gw = guiW;
        gh = guiH;
        return true;
    }

    public static double camX() { return cx; }
    public static double camY() { return cy; }
    public static double camZ() { return cz; }

    /**
     * Projects (x, y, z). out = {screenX, screenY, depth}. Returns false when the point is behind the
     * camera; out then holds the mirrored direction so callers can still point an edge arrow at it.
     */
    public static boolean project(double x, double y, double z, float[] out) {
        P.set((float) (x - cx), (float) (y - cy), (float) (z - cz));
        VIEW.transform(P);
        CLIP.set(P, 1);
        PROJ.transform(CLIP);
        float w = CLIP.w;
        boolean front = w > 0.05f;
        if (!front) w = Math.min(-0.05f, w);
        float nx = CLIP.x / w, ny = CLIP.y / w;
        if (!front) {
            nx = -nx;
            ny = -ny;
        }
        out[0] = (nx * 0.5f + 0.5f) * gw;
        out[1] = (1 - (ny * 0.5f + 0.5f)) * gh;
        out[2] = w;
        return front;
    }

    private static float hh(float inset) {
        return gh / 2f - inset;
    }

    /** On-screen with a margin (in GUI units). */
    public static boolean onScreen(float[] s, float margin) {
        return s[0] >= -margin && s[0] <= gw + margin && s[1] >= -margin && s[1] <= gh + margin;
    }

    /**
     * Clamps a point to an inset rectangle along the ray from the screen center. Returns the angle
     * (radians, 0 = right) the arrow should point to.
     */
    public static float clampToEdge(float[] s, boolean front, float inset) {
        float mx = gw / 2f, my = gh / 2f;
        float dx = s[0] - mx, dy = s[1] - my;
        if (!front && Math.abs(dx) < 1e-3f && Math.abs(dy) < 1e-3f) dy = 1;
        if (!front) {
            // behind: on the left or right edge (never the bottom, where the hotbar is)
            float len = (float) Math.hypot(dx, dy);
            dy = Math.max(-0.5f, Math.min(0.5f, dy / len)) * hh(inset) / Math.max(1, mx - inset);
            dx = dx < 0 ? -1 : 1;
        }
        float hw = mx - inset, hh = my - inset;
        float k = Math.min(hw / Math.max(1e-4f, Math.abs(dx)), hh / Math.max(1e-4f, Math.abs(dy)));
        if (front && k >= 1) return (float) Math.atan2(dy, dx);
        s[0] = mx + dx * k;
        s[1] = my + dy * k;
        return (float) Math.atan2(dy, dx);
    }
}
