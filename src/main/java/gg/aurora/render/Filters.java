package gg.aurora.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import gg.aurora.AuroraClient;
import gg.aurora.module.CombatModules;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Filtres graphiques (post-traitement plein écran, une passe) appliqués au monde avant l'interface :
 * étalonnage couleur, lueur douce, vignette, netteté. Pipelines compilés à la demande par réglage.
 */
public final class Filters {
    private Filters() {}

    public static final String[] PRESETS = {"Vivid", "Cinema", "Warm", "Cold", "Black & white", "Dream", "Purple night", "Cursed"};

    private static TextureTarget tmp;
    private static final Map<Long, RenderPipeline> PIPELINES = new HashMap<>();
    private static boolean failed;

    private static int step(double v) {
        return (int) Math.round(Math.max(0, Math.min(1, v)) * 10);
    }

    private static RenderPipeline pipeline(int preset, int strength, int bloom, int vignette, int sharpen) {
        long key = preset | (long) strength << 8 | (long) bloom << 16 | (long) vignette << 24 | (long) sharpen << 32;
        return PIPELINES.computeIfAbsent(key, k -> RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("aurora", "pipeline/grade"))
                .withVertexShader("core/screenquad")
                .withFragmentShader(Identifier.fromNamespaceAndPath("aurora", "core/grade"))
                .withSampler("InSampler")
                .withShaderDefine("PRESET", preset)
                .withShaderDefine("STRENGTH", strength / 10f)
                .withShaderDefine("BLOOM_ON", bloom > 0 ? 1 : 0)
                .withShaderDefine("BLOOM", bloom / 10f)
                .withShaderDefine("VIGNETTE_ON", vignette > 0 ? 1 : 0)
                .withShaderDefine("VIGNETTE", vignette / 10f)
                .withShaderDefine("SHARPEN_ON", sharpen > 0 ? 1 : 0)
                .withShaderDefine("SHARPEN", sharpen / 10f)
                .withoutBlend()
                .withDepthWrite(false)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                .build());
    }

    /** Après le rendu du monde (et l'upscaling) : filtre → cible temporaire → recopie dans l'écran. */
    public static void apply(Minecraft mc) {
        if (failed || TexCopy.unsupported() || !CombatModules.FILTERS.enabled || mc.level == null) return;
        RenderTarget main = mc.getMainRenderTarget();
        try {
            if (tmp == null) tmp = new TextureTarget("Paranoia filtres", main.width, main.height, false);
            else if (tmp.width != main.width || tmp.height != main.height) tmp.resize(main.width, main.height);
            var m = CombatModules.FILTERS;
            RenderPipeline p = pipeline((int) m.opt("preset", 1), step(m.opt("strength", 0.7)), step(m.opt("bloom", 0.4)),
                    step(m.opt("vignette", 0.4)), step(m.opt("sharpen", 0.2)));
            try (RenderPass rp = RenderSystem.getDevice().createCommandEncoder()
                    .createRenderPass(() -> "Paranoia filtres", tmp.getColorTextureView(), OptionalInt.empty())) {
                rp.setPipeline(p);
                rp.bindTexture("InSampler", main.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                rp.draw(0, 3);
            }
            TexCopy.copy(tmp, main);
        } catch (RuntimeException e) {
            failed = true;
            AuroraClient.LOGGER.error("Filtres désactivés après une erreur de rendu", e);
        }
    }
}
