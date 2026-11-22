package gg.aurora.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import gg.aurora.ui.WorldPanel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Menu fixé dans le monde : rendu hors écran, clics et molette, navigation entre écrans. */
public final class WorldPanelMixins {
    private WorldPanelMixins() {}

    /** Le second GuiRenderer dessine dans la texture du panneau au lieu de l'écran. */
    @Mixin(GuiRenderer.class)
    public static class Target {
        @WrapOperation(method = "draw", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/client/Minecraft;getMainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
        private RenderTarget aurora$target(Minecraft mc, Operation<RenderTarget> original) {
            RenderTarget r = WorldPanel.redirect;
            return r != null ? r : original.call(mc);
        }
    }

    @Mixin(MouseHandler.class)
    public static class Mouse {
        @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
        private void aurora$button(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
            if (WorldPanel.button(info, action)) ci.cancel();
        }

        @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
        private void aurora$scroll(long window, double x, double y, CallbackInfo ci) {
            if (WorldPanel.scroll(y)) ci.cancel();
        }
    }

    @Mixin(Minecraft.class)
    public static class SetScreen {
        @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
        private void aurora$setScreen(Screen screen, CallbackInfo ci) {
            if (WorldPanel.intercept(screen)) ci.cancel();
        }
    }
}
