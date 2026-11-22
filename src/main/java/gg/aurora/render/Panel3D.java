package gg.aurora.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import gg.aurora.AuroraClient;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.OptionalInt;

/**
 * Arrivée en 3D du menu : l'interface de Minecraft est en 2D, donc on capture l'image juste avant
 * le rendu de l'interface puis juste après, et un shader reprojette le rectangle du panneau sur un
 * plan incliné en perspective (homographie inverse). Ne coûte rien hors de l'animation (~0,5 s).
 */
public final class Panel3D {
    private Panel3D() {}

    private static boolean active, captured, failed, closing;
    private static float x, y, w, h, radius, progress;
    private static TextureTarget pre, tmp;
    private static RenderPipeline pipe;

    /** Rectangle du panneau en pixels (origine en haut à gauche), progression 0 → 1. */
    public static void set(float px, float py, float pw, float ph, float r, float p, boolean isClosing) {
        x = px;
        y = py;
        w = pw;
        h = ph;
        radius = r;
        progress = p;
        closing = isClosing;
        active = !failed && !TexCopy.unsupported();
    }

    /** Faux si la 3D a été coupée après une erreur (le menu s'anime alors en 2D). */
    public static boolean available() {
        return !failed && !TexCopy.unsupported();
    }

    public static void clear() {
        active = false;
    }

    private static TextureTarget fit(TextureTarget t, RenderTarget main, String label) {
        if (t == null) return new TextureTarget(label, main.width, main.height, false);
        if (t.width != main.width || t.height != main.height) t.resize(main.width, main.height);
        return t;
    }

    /** Juste avant le rendu de l'interface : image du monde seule. */
    public static void beforeGui() {
        if (!active || failed) return;
        try {
            RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
            pre = fit(pre, main, "Paranoia menu 3D (monde)");
            TexCopy.copy(main, pre);
            captured = true;
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    /** Juste après : le panneau dessiné à plat est remplacé par sa version en perspective. */
    public static void afterGui() {
        if (!captured) return;
        captured = false;
        if (!active || failed) return;
        try {
            RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
            tmp = fit(tmp, main, "Paranoia menu 3D (interface)");
            var enc = RenderSystem.getDevice().createCommandEncoder();
            TexCopy.copy(main, tmp);
            if (pipe == null) {
                pipe = RenderPipeline.builder()
                        .withLocation(Identifier.fromNamespaceAndPath("aurora", "pipeline/panel3d"))
                        .withVertexShader("core/screenquad")
                        .withFragmentShader(Identifier.fromNamespaceAndPath("aurora", "core/panel3d"))
                        .withUniform("Panel3dInfo", UniformType.UNIFORM_BUFFER)
                        .withSampler("InSampler")
                        .withSampler("PreSampler")
                        .withoutBlend()
                        .withDepthWrite(false)
                        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                        .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                        .build();
            }
            float H = main.height;
            // rectangle source en coordonnées du tampon (origine en bas à gauche)
            float x0 = x, x1 = x + w, y0 = H - (y + h), y1 = H - y;
            float cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
            float e = easeOutCubic(progress), k = 1 - e;
            // le panneau arrive de loin, tourné et incliné, puis se pose face à la caméra
            double ry = Math.toRadians(-38 * k), rx = Math.toRadians(16 * k);
            float depth = k * w * 1.5f, slide = k * w * 0.28f, lift = -k * h * 0.12f, f = H * 1.25f;
            float[] dst = new float[8], src = {x0, y0, x1, y0, x1, y1, x0, y1};
            for (int i = 0; i < 4; i++) {
                float lx = src[i * 2] - cx, ly = src[i * 2 + 1] - cy;
                // rotation X (inclinaison) puis Y (pivot), plan placé en profondeur
                double y2 = ly * Math.cos(rx), z2 = ly * Math.sin(rx);
                double x3 = lx * Math.cos(ry) + z2 * Math.sin(ry), z3 = -lx * Math.sin(ry) + z2 * Math.cos(ry) + depth;
                double s = f / Math.max(1, f + z3);
                dst[i * 2] = (float) (cx + slide + x3 * s);
                dst[i * 2 + 1] = (float) (cy + lift + y2 * s);
            }
            double[] m = homography(dst, src); // écran -> panneau
            Matrix4f hinv = new Matrix4f(
                    (float) m[0], (float) m[3], (float) m[6], 0,
                    (float) m[1], (float) m[4], (float) m[7], 0,
                    (float) m[2], (float) m[5], 1f, 0,
                    0, 0, 0, 1);
            GpuBuffer buf;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer bb = Std140Builder.onStack(stack, 96).putMat4f(hinv)
                        .putVec4(x0, y0, x1, y1)
                        .putVec4(e, 0.42f * e, radius, 0)
                        .get();
                buf = RenderSystem.getDevice().createBuffer(() -> "Paranoia menu 3D", GpuBuffer.USAGE_UNIFORM, bb);
            }
            try (RenderPass rp = enc.createRenderPass(() -> "Paranoia menu 3D", main.getColorTextureView(), OptionalInt.empty())) {
                rp.setPipeline(pipe);
                rp.bindTexture("InSampler", tmp.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                rp.bindTexture("PreSampler", pre.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                rp.setUniform("Panel3dInfo", buf);
                rp.draw(0, 3);
            }
            buf.close();
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    private static void fail(RuntimeException e) {
        failed = true;
        active = false;
        captured = false;
        AuroraClient.LOGGER.error("Menu 3D désactivé après une erreur de rendu (animation 2D à la place)", e);
    }

    private static float easeOutCubic(float t) {
        t = Math.max(0, Math.min(1, t));
        float u = 1 - t;
        return 1 - u * u * u;
    }

    /**
     * Homographie qui envoie les 4 points (u, v) de {@code from} sur les 4 points (x, y) de {@code to} :
     * x = (a u + b v + c) / (g u + h v + 1), y = (d u + e v + f) / (g u + h v + 1). Renvoie {a..h}.
     */
    static double[] homography(float[] from, float[] to) {
        double[][] A = new double[8][9];
        for (int i = 0; i < 4; i++) {
            double u = from[i * 2], v = from[i * 2 + 1], X = to[i * 2], Y = to[i * 2 + 1];
            A[i * 2] = new double[]{u, v, 1, 0, 0, 0, -u * X, -v * X, X};
            A[i * 2 + 1] = new double[]{0, 0, 0, u, v, 1, -u * Y, -v * Y, Y};
        }
        for (int c = 0; c < 8; c++) {
            int piv = c;
            for (int r = c + 1; r < 8; r++) if (Math.abs(A[r][c]) > Math.abs(A[piv][c])) piv = r;
            double[] t = A[c];
            A[c] = A[piv];
            A[piv] = t;
            double d = A[c][c];
            if (Math.abs(d) < 1e-12) d = 1e-12;
            for (int k = c; k < 9; k++) A[c][k] /= d;
            for (int r = 0; r < 8; r++) {
                if (r == c) continue;
                double fct = A[r][c];
                if (fct == 0) continue;
                for (int k = c; k < 9; k++) A[r][k] -= fct * A[c][k];
            }
        }
        double[] out = new double[8];
        for (int i = 0; i < 8; i++) out[i] = A[i][8];
        return out;
    }
}
