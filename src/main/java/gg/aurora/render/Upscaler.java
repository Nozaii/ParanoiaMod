package gg.aurora.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import gg.aurora.AuroraClient;
import gg.aurora.Settings;
import gg.aurora.mixin.MinecraftAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Upscaling AMD FSR 1.0 : le monde est rendu dans une cible réduite, puis agrandi à la taille de
 * l'écran par EASU et affiné par RCAS. L'interface reste rendue en pleine résolution.
 */
public final class Upscaler {
    private Upscaler() {}

    public record Quality(String name, float scale) {}

    /** Modes de qualité officiels de FSR 1.0 (facteur d'échelle par axe). */
    public static final Quality[] QUALITIES = {
            new Quality("Ultra quality", 1.3f),
            new Quality("Quality", 1.5f),
            new Quality("Balanced", 1.7f),
            new Quality("Performance", 2.0f),
    };

    public static final String[] VERSIONS = {"Off", "FSR 1.0"};

    private static TextureTarget low, mid;
    private static RenderTarget saved;
    private static RenderPipeline easu;
    private static final Map<Integer, RenderPipeline> RCAS = new HashMap<>();
    private static boolean failed;

    public static Quality quality() {
        return QUALITIES[Math.max(0, Math.min(QUALITIES.length - 1, Settings.fsrQuality))];
    }

    /** Actif seulement en jeu ; incompatible avec la transparence améliorée (Fabulous). */
    public static boolean active(Minecraft mc) {
        return Settings.upscaler == 1 && !failed && !TexCopy.VULKAN && mc.level != null && !mc.options.improvedTransparency().get();
    }

    // ================================================================ GPU

    public static String gpu() {
        try {
            return RenderSystem.getDevice().getRenderer();
        } catch (RuntimeException e) {
            return "Unknown";
        }
    }

    private static String vendor() {
        try {
            return (RenderSystem.getDevice().getVendor() + " " + gpu()).toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return "";
        }
    }

    public static String gpuFamily() {
        String v = vendor();
        if (v.contains("nvidia") || v.contains("geforce")) return v.contains("rtx") ? "NVIDIA RTX" : "NVIDIA";
        if (v.contains("amd") || v.contains("radeon")) return "AMD";
        if (v.contains("intel")) return "Intel";
        return "Other";
    }

    public static String dlssStatus() {
        return gpuFamily().equals("NVIDIA RTX")
                ? "RTX detected, but impossible on Minecraft Java (NVIDIA SDK + motion vectors)"
                : "Requires an NVIDIA RTX, and impossible on Minecraft Java";
    }

    public static String fsrStatus() {
        if (failed) return "Render error: off for this session";
        Minecraft mc = Minecraft.getInstance();
        if (Settings.upscaler != 1) return "Compatible with your GPU (" + gpuFamily() + ")";
        if (mc.options.improvedTransparency().get()) return "Paused: turn off « Improved transparency »";
        return "Active: " + internalResolution(mc);
    }

    // ================================================================ rendu

    /** Avant le rendu du monde : la cible principale est remplacée par une cible réduite. */
    public static void begin(Minecraft mc) {
        if (!active(mc) || saved != null) return;
        RenderTarget main = mc.getMainRenderTarget();
        float s = quality().scale();
        int w = Math.max(1, Math.round(main.width / s)), h = Math.max(1, Math.round(main.height / s));
        try {
            if (low == null) low = new TextureTarget("Paranoia FSR (input)", w, h, true);
            else if (low.width != w || low.height != h) low.resize(w, h);
            RenderSystem.getDevice().createCommandEncoder()
                    .clearColorAndDepthTextures(low.getColorTexture(), 0, low.getDepthTexture(), 1.0);
        } catch (RuntimeException e) {
            fail(e);
            return;
        }
        saved = main;
        ((MinecraftAccessor) mc).aurora$setMainRenderTarget(low);
    }

    /** Après le rendu du monde : EASU (agrandissement) puis RCAS (netteté) vers la vraie cible. */
    public static void end(Minecraft mc) {
        if (saved == null) return;
        RenderTarget main = saved;
        saved = null;
        ((MinecraftAccessor) mc).aurora$setMainRenderTarget(main);
        try {
            if (mid == null) mid = new TextureTarget("Paranoia FSR (EASU)", main.width, main.height, false);
            else if (mid.width != main.width || mid.height != main.height) mid.resize(main.width, main.height);
            pass(easu(), low.getColorTextureView(), mid.getColorTextureView());
            pass(rcas(), mid.getColorTextureView(), main.getColorTextureView());
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    private static void fail(RuntimeException e) {
        failed = true;
        AuroraClient.LOGGER.error("FSR désactivé après une erreur de rendu", e);
    }

    private static void pass(RenderPipeline pipeline, GpuTextureView src, GpuTextureView dst) {
        try (RenderPass rp = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Paranoia FSR", dst, OptionalInt.empty())) {
            rp.setPipeline(pipeline);
            rp.bindTexture("InSampler", src, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            rp.draw(0, 3);
        }
    }

    private static RenderPipeline.Builder base(String name) {
        return RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("aurora", "pipeline/" + name))
                .withVertexShader("core/screenquad")
                .withFragmentShader(Identifier.fromNamespaceAndPath("aurora", "core/" + name))
                .withSampler("InSampler")
                .withoutBlend()
                .withDepthWrite(false)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES);
    }

    private static RenderPipeline easu() {
        if (easu == null) easu = base("fsr_easu").build();
        return easu;
    }

    /** Netteté 0..1 → 2..0 « stops » RCAS, par crans de 0.1 (un pipeline compilé par cran). */
    private static RenderPipeline rcas() {
        int step = Math.round(Math.max(0, Math.min(1, Settings.fsrSharpness)) * 10);
        return RCAS.computeIfAbsent(step, k -> base("fsr_rcas").withShaderDefine("SHARPNESS", 2f * (1f - k / 10f)).build());
    }

    /** Résolution interne → résolution d'affichage. */
    public static String internalResolution(Minecraft mc) {
        int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
        float s = quality().scale();
        return Math.round(w / s) + "×" + Math.round(h / s) + " → " + w + "×" + h;
    }
}
