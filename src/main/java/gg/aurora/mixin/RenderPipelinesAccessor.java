package gg.aurora.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accès aux briques de pipeline vanilla (matrices + brouillard) pour nos pipelines additifs. */
@Mixin(RenderPipelines.class)
public interface RenderPipelinesAccessor {
    @Accessor("MATRICES_FOG_SNIPPET")
    static RenderPipeline.Snippet aurora$matricesFog() {
        throw new AssertionError();
    }
}
