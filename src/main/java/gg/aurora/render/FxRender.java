package gg.aurora.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import gg.aurora.mixin.RenderPipelinesAccessor;
import gg.aurora.mixin.RenderTypeInvoker;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Rendu « lumière » des effets : shader d'entité émissif avec mélange additif (SRC_ALPHA, ONE), sans
 * écriture de profondeur ni élimination des faces. Les couleurs s'ajoutent à la scène comme une
 * vraie source lumineuse (bloom naturel là où les effets se superposent), tout en restant cachées
 * derrière les blocs. L'alpha (couleur et texture) règle l'intensité ajoutée.
 */
public final class FxRender {
    private FxRender() {}

    private static RenderPipeline pipeline;
    private static final Map<Identifier, RenderType> TYPES = new HashMap<>();

    private static RenderPipeline pipeline() {
        if (pipeline == null) {
            pipeline = RenderPipeline.builder(RenderPipelinesAccessor.aurora$matricesFog())
                    .withLocation(Identifier.fromNamespaceAndPath("aurora", "pipeline/fx_additive"))
                    .withVertexShader("core/entity")
                    .withFragmentShader("core/entity")
                    .withShaderDefine("EMISSIVE")
                    .withShaderDefine("NO_OVERLAY")
                    .withShaderDefine("NO_CARDINAL_LIGHTING")
                    .withSampler("Sampler0")
                    .withBlend(BlendFunction.LIGHTNING)
                    .withDepthWrite(false)
                    .withCull(false)
                    .withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS)
                    .build();
            IrisCompat.assign(pipeline, "EMISSIVE_ENTITIES");
        }
        return pipeline;
    }

    private static RenderPipeline mirror;
    private static final Map<Identifier, RenderType> MIRROR = new HashMap<>();

    /**
     * Sol miroir : modèle translucide sans test de profondeur (le reflet est sous le sol, il doit
     * apparaître « dans » sa surface). Dessiné avant le joueur, qui le recouvre ensuite.
     */
    public static RenderType mirror(Identifier texture) {
        if (mirror == null) {
            mirror = RenderPipeline.builder(RenderPipelinesAccessor.aurora$matricesFog())
                    .withLocation(Identifier.fromNamespaceAndPath("aurora", "pipeline/mirror"))
                    .withVertexShader("core/entity")
                    .withFragmentShader("core/entity")
                    .withShaderDefine("EMISSIVE")
                    .withShaderDefine("NO_OVERLAY")
                    .withShaderDefine("NO_CARDINAL_LIGHTING")
                    .withShaderDefine("ALPHA_CUTOUT", 0.1f)
                    .withSampler("Sampler0")
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .withDepthWrite(false)
                    .withDepthTestFunction(com.mojang.blaze3d.platform.DepthTestFunction.NO_DEPTH_TEST)
                    .withCull(false)
                    .withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS)
                    .build();
            IrisCompat.assign(mirror, "ENTITIES_TRANSLUCENT");
        }
        return MIRROR.computeIfAbsent(texture, t -> RenderTypeInvoker.aurora$create("aurora_mirror",
                RenderSetup.builder(mirror).withTexture("Sampler0", t).sortOnUpload().createRenderSetup()));
    }

    /** Type de rendu additif pour une texture (blanche pour la géométrie, halo doux pour le bloom). */
    public static RenderType additive(Identifier texture) {
        return TYPES.computeIfAbsent(texture, t -> RenderTypeInvoker.aurora$create("aurora_fx_additive",
                // mélange additif : l'ordre ne change pas le résultat, inutile de trier les sommets à chaque image
                RenderSetup.builder(pipeline()).withTexture("Sampler0", t).createRenderSetup()));
    }
}
