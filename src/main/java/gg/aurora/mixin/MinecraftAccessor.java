package gg.aurora.mixin;

import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Gap Optimizer (délai d'utilisation) et upscaling FSR (cible de rendu principale échangée). */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Accessor("rightClickDelay")
    void aurora$setRightClickDelay(int delay);

    @Accessor("rightClickDelay")
    int aurora$getRightClickDelay();

    @Mutable
    @Accessor("mainRenderTarget")
    void aurora$setMainRenderTarget(RenderTarget target);
}
