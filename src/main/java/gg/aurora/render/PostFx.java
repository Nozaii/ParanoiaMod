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
import gg.aurora.module.VisualModules;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.OptionalInt;

/**
 * Passes plein écran :
 * - Sol miroir : reflets dans l'écran sur les surfaces horizontales (avant le rendu de la main, quand la
 *   profondeur du monde est encore là) ;
 * - Motion blur : mélange de l'image avec l'historique des images précédentes (après les filtres).
 * Chaque passe se coupe d'elle-même après une erreur de rendu, sans planter le jeu.
 */
public final class PostFx {
    private PostFx() {}

    private static RenderPipeline ssrPipe, blurPipe;
    private static TextureTarget tmp;
    private static boolean ssrFailed, blurFailed;
    private static final boolean VULKAN = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("vulkanmod");
    private static long lastBlur;

    private static RenderPipeline pipeline(String name, String frag, String uniform, String... samplers) {
        RenderPipeline.Builder b = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("aurora", "pipeline/" + name))
                .withVertexShader("core/screenquad")
                .withFragmentShader(Identifier.fromNamespaceAndPath("aurora", "core/" + frag))
                .withUniform(uniform, UniformType.UNIFORM_BUFFER)
                .withoutBlend()
                .withDepthWrite(false)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES);
        for (String s : samplers) b = b.withSampler(s);
        return b.build();
    }

    private static TextureTarget fit(TextureTarget t, RenderTarget main, String label) {
        if (t == null) return new TextureTarget(label, main.width, main.height, false);
        if (t.width != main.width || t.height != main.height) t.resize(main.width, main.height);
        return t;
    }

    // ---------------------------------------------------------------- shockwaves

    private static final int WAVES = 4;
    private static final double[][] WAVE = new double[WAVES][3];
    private static final long[] WAVE_START = new long[WAVES];
    private static int waveNext;
    private static RenderPipeline shockPipe;
    private static boolean shockFailed;

    /** Starts a wave at this world position (oldest one is replaced). */
    public static void shock(net.minecraft.world.phys.Vec3 p) {
        WAVE[waveNext][0] = p.x;
        WAVE[waveNext][1] = p.y;
        WAVE[waveNext][2] = p.z;
        WAVE_START[waveNext] = System.nanoTime();
        waveNext = (waveNext + 1) % WAVES;
    }

    /** Costs nothing while no wave is running: the pass only exists during a wave. */
    public static void shockwaves(Minecraft mc) {
        var m = gg.aurora.module.ClarityModules.SHOCKWAVES;
        if (shockFailed || TexCopy.VULKAN || !m.enabled || mc.level == null) return;
        long now = System.nanoTime();
        final float life = 1.1f;
        boolean any = false;
        for (int i = 0; i < WAVES; i++) any |= WAVE_START[i] != 0 && (now - WAVE_START[i]) / 1e9f < life;
        if (!any) return;
        RenderTarget main = mc.getMainRenderTarget();
        if (main.getDepthTextureView() == null) return;
        try {
            if (shockPipe == null) shockPipe = pipeline("shockwave", "shockwave", "ShockInfo", "InSampler", "DepthSampler");
            tmp = fit(tmp, main, "Paranoia reflets");
            var cam = mc.gameRenderer.getMainCamera();
            float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
            float fov = ((gg.aurora.mixin.GameRendererAccessor) mc.gameRenderer).aurora$getFov(cam, pt, true);
            Matrix4f inv = new Matrix4f(mc.gameRenderer.getProjectionMatrix(fov)).invert();
            Matrix4f viewToWorld = new Matrix4f().rotation(cam.rotation());
            int rgb = (int) m.opt("color", 0xDDE6FF);
            float range = (float) m.opt("range", 16), power = (float) m.opt("strength", 0.6);
            var pos = cam.position();
            float[] str = new float[WAVES];
            float[][] w = new float[WAVES][4];
            for (int i = 0; i < WAVES; i++) {
                if (WAVE_START[i] == 0) continue;
                float t = (now - WAVE_START[i]) / 1e9f / life;
                if (t >= 1) continue;
                float e = 1 - (1 - t) * (1 - t);
                w[i][0] = (float) (WAVE[i][0] - pos.x);
                w[i][1] = (float) (WAVE[i][1] - pos.y);
                w[i][2] = (float) (WAVE[i][2] - pos.z);
                w[i][3] = e * range;
                str[i] = power * (1 - t) * (1 - t);
            }
            if (shockRing == null) shockRing = new UniformRing("Paranoia shockwave", 224);
            var b = shockRing.begin().putMat4f(inv).putMat4f(viewToWorld)
                    .putVec4((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f, VULKAN ? 1 : 0);
            for (float[] v : w) b = b.putVec4(v[0], v[1], v[2], v[3]);
            b.putVec4(str[0], str[1], str[2], str[3]);
            GpuBuffer buf = shockRing.commit();
            var enc = RenderSystem.getDevice().createCommandEncoder();
            try (RenderPass rp = enc.createRenderPass(() -> "Paranoia shockwave", tmp.getColorTextureView(), OptionalInt.empty())) {
                rp.setPipeline(shockPipe);
                rp.bindTexture("InSampler", main.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                rp.bindTexture("DepthSampler", main.getDepthTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                rp.setUniform("ShockInfo", buf);
                rp.draw(0, 3);
            }
            TexCopy.copy(tmp, main);
        } catch (RuntimeException e) {
            shockFailed = true;
            AuroraClient.LOGGER.error("Shockwaves off after a render error", e);
        }
    }

    // ---------------------------------------------------------------- sol miroir

    private static UniformRing ssrRing, blurRing, shockRing;
    private static final Matrix4f SSR_INV = new Matrix4f(), SSR_V2W = new Matrix4f();
    private static final Quaternionf SSR_Q = new Quaternionf();
    private static final Vector3f SSR_UP = new Vector3f();

    /** Juste avant le rendu de la main : couleur + profondeur du monde disponibles. */
    public static void ssr(Minecraft mc) {
        var m = VisualModules.MIRROR;
        if (ssrFailed || TexCopy.VULKAN || !m.enabled || !m.flag("ssr", true) || mc.level == null || mc.player == null) return;
        float strength = (float) m.opt("ssrStrength", 0.55);
        if (m.flag("rainOnly", false)) strength *= mc.level.getRainLevel(1f);
        if (strength <= 0.01f) return;
        RenderTarget main = mc.getMainRenderTarget();
        if (main.getDepthTextureView() == null) return;
        try {
            long t0 = gg.aurora.Prof.start();
            if (ssrPipe == null) ssrPipe = pipeline("ssr", "ssr", "SsrInfo", "InSampler", "DepthSampler");
            tmp = fit(tmp, main, "Paranoia reflets");
            var cam = mc.gameRenderer.getMainCamera();
            float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
            float fov = ((gg.aurora.mixin.GameRendererAccessor) mc.gameRenderer).aurora$getFov(cam, pt, true);
            Matrix4f proj = mc.gameRenderer.getProjectionMatrix(fov);
            Matrix4f inv = SSR_INV.set(proj).invert();
            Vector3f up = SSR_Q.set(cam.rotation()).conjugate().transform(SSR_UP.set(0, 1, 0));
            float time = (float) ((System.currentTimeMillis() % 100000L) / 1000.0);
            if (ssrRing == null) ssrRing = new UniformRing("Paranoia reflets", 272);
            {
                // flaques : mouillé = pluie (ou toujours), seuil = 1 - quantité
                float wet = 0, rain = mc.level.getRainLevel(pt);
                if (m.flag("puddles", true)) {
                    wet = m.flag("puddlesDry", false) ? 1f : mc.level.getRainLevel(pt);
                }
                float threshold = 0.75f - 0.45f * (float) m.opt("puddleAmount", 0.5);
                var pos = cam.position();
                Matrix4f viewToWorld = SSR_V2W.rotation(cam.rotation());
                ssrRing.begin()
                        .putMat4f(proj).putMat4f(inv)
                        .putVec4(up.x, up.y, up.z, 0)
                        .putVec4(strength, (float) m.opt("ssrRough", 0.3), (float) m.opt("ssrDist", 48), time)
                        .putVec4(0.3f, 0.7f, 0.9f, 0)
                        .putMat4f(viewToWorld)
                        .putVec4((float) (pos.x % 1024.0), (float) pos.y, (float) (pos.z % 1024.0), time)
                        .putVec4(threshold, wet, m.flag("ripples", true) ? rain : 0f, VULKAN ? 1f : 0f);
            }
            GpuBuffer buf = ssrRing.commit();
            gg.aurora.Prof.stop(gg.aurora.Prof.SSR_SETUP, t0);
            long t1 = gg.aurora.Prof.start();
            try (RenderPass rp = RenderSystem.getDevice().createCommandEncoder()
                    .createRenderPass(() -> "Paranoia reflets", tmp.getColorTextureView(), OptionalInt.empty())) {
                rp.setPipeline(ssrPipe);
                rp.bindTexture("InSampler", main.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                rp.bindTexture("DepthSampler", main.getDepthTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                rp.setUniform("SsrInfo", buf);
                rp.draw(0, 3);
            }
            gg.aurora.Prof.stop(gg.aurora.Prof.SSR_PASS, t1);
            long t2 = gg.aurora.Prof.start();
            TexCopy.copy(tmp, main);
            gg.aurora.Prof.stop(gg.aurora.Prof.SSR_COPY, t2);
        } catch (RuntimeException e) {
            ssrFailed = true;
            AuroraClient.LOGGER.error("Reflets du sol désactivés après une erreur de rendu", e);
        }
    }

    // ---------------------------------------------------------------- motion blur

    private static final Matrix4f prevProj = new Matrix4f();
    private static final Quaternionf prevRot = new Quaternionf();
    private static final Quaternionf prevRotBefore = new Quaternionf();
    private static double prevX, prevY, prevZ;
    private static boolean prevValid;
    /** Outil de dev : rotation continue (degrés / s) pour tester le flou. */
    public static float devSpin;

    /**
     * Avant la main (profondeur du monde disponible) : chaque pixel est reprojeté dans l'image précédente
     * et flouté le long de son mouvement. Obturateur constant : même flou à 60 ou 300 FPS.
     */
    public static void motionBlur(Minecraft mc) {
        if (TexCopy.VULKAN) return;
        var m = VisualModules.MOTION_BLUR;
        if (blurFailed || !m.enabled || mc.level == null || mc.player == null) {
            prevValid = false;
            lastBlur = 0;
            return;
        }
        RenderTarget main = mc.getMainRenderTarget();
        if (main.getDepthTextureView() == null) return;
        var cam = mc.gameRenderer.getMainCamera();
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        float fov = ((gg.aurora.mixin.GameRendererAccessor) mc.gameRenderer).aurora$getFov(cam, pt, true);
        Matrix4f proj = mc.gameRenderer.getProjectionMatrix(fov);
        Quaternionf rot = new Quaternionf(cam.rotation());
        var pos = cam.position();
        long now = System.nanoTime();
        float dt = lastBlur == 0 ? 1 / 60f : Math.max(1 / 1000f, Math.min(0.1f, (now - lastBlur) / 1e9f));
        lastBlur = now;
        double dx = pos.x - prevX, dy = pos.y - prevY, dz = pos.z - prevZ;
        boolean ok = prevValid && dx * dx + dy * dy + dz * dz < 16 && mc.screen == null;
        // camera still: nothing to blur, the whole pass and its copy are skipped
        boolean still = dx * dx + dy * dy + dz * dz < 1e-8 && Math.abs(rot.dot(prevRot)) > 0.9999999f;
        if (still) ok = false;
        // NDC courant -> clip précédent
        prevRotBefore.set(prevRot);
        Matrix4f reproj = new Matrix4f(prevProj).rotate(new Quaternionf(prevRot).conjugate())
                .translate((float) dx, (float) dy, (float) dz).rotate(rot).mul(new Matrix4f(proj).invert());
        prevProj.set(proj);
        prevRot.set(rot);
        prevX = pos.x;
        prevY = pos.y;
        prevZ = pos.z;
        prevValid = true;
        if (devSpin != 0) mc.player.setYRot(mc.player.getYRot() + devSpin * dt);
        if (!ok) return;
        try {
            if (blurPipe == null) blurPipe = pipeline("motionblur", "motionblur", "BlurInfo", "InSampler", "DepthSampler");
            tmp = fit(tmp, main, "Paranoia reflets");
            // obturateur : fraction (force) d'une image à 60 FPS, ramenée au vrai temps de frame
            float shutter = (float) m.opt("strength", 0.5) * (1 / 60f) / dt;
            // fewer samples for slow movement: the streak is short, the result is identical
            float move = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) + (1 - Math.abs(rot.dot(prevRotBefore))) * 200;
            int samples = Math.max(4, Math.min((int) m.opt("samples", 12), 4 + (int) (move * 40)));
            if (blurRing == null) blurRing = new UniformRing("Paranoia motion blur", 80);
            blurRing.begin().putMat4f(reproj).putVec4(shutter, 0.06f, samples, VULKAN ? 1f : 0f);
            GpuBuffer buf = blurRing.commit();
            var enc = RenderSystem.getDevice().createCommandEncoder();
            try (RenderPass rp = enc.createRenderPass(() -> "Paranoia motion blur", tmp.getColorTextureView(), OptionalInt.empty())) {
                rp.setPipeline(blurPipe);
                rp.bindTexture("InSampler", main.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                rp.bindTexture("DepthSampler", main.getDepthTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                rp.setUniform("BlurInfo", buf);
                rp.draw(0, 3);
            }
            TexCopy.copy(tmp, main);
        } catch (RuntimeException e) {
            blurFailed = true;
            AuroraClient.LOGGER.error("Motion blur désactivé après une erreur de rendu", e);
        }
    }
}
