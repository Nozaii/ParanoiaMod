package gg.aurora.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Champ de vision réel (effets, zoom) : sert à projeter les waypoints à l'écran. */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Invoker("getFov")
    float aurora$getFov(Camera camera, float partialTick, boolean useFovSetting);

    @org.spongepowered.asm.mixin.gen.Accessor("fogRenderer")
    net.minecraft.client.renderer.fog.FogRenderer aurora$fogRenderer();

    @org.spongepowered.asm.mixin.gen.Accessor("guiRenderer")
    net.minecraft.client.gui.render.GuiRenderer aurora$guiRenderer();
}
