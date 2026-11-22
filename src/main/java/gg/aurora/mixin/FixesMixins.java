package gg.aurora.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import gg.aurora.Settings;
import gg.aurora.module.Modules;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.entity.layers.WingsLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Small targeted fixes: Fullbright at the lightmap, vanilla elytra texture, vanilla effect icons. */
public final class FixesMixins {
    private FixesMixins() {}

    /** Elytra keep their own texture unless the player wants the cape pattern on them. */
    @Mixin(WingsLayer.class)
    public static class Wings {
        @Inject(method = "getPlayerElytraTexture", at = @At("HEAD"), cancellable = true)
        private static void aurora$elytra(HumanoidRenderState state, CallbackInfoReturnable<Identifier> cir) {
            if (!Settings.elytraCape) cir.setReturnValue(null);
        }
    }

    /** Overlay Editor: pumpkin, powder snow, vignette. */
    @Mixin(Gui.class)
    public static class Overlays {
        @Inject(method = "renderTextureOverlay", at = @At("HEAD"), cancellable = true)
        private void aurora$texture(GuiGraphics g, Identifier id, float alpha, CallbackInfo ci) {
            if (gg.aurora.module.NewModules.hideTexture(id)) ci.cancel();
        }

        @Inject(method = "renderVignette", at = @At("HEAD"), cancellable = true)
        private void aurora$vignette(CallbackInfo ci) {
            if (gg.aurora.module.NewModules.hideVignette()) ci.cancel();
        }
    }

    /** Overlay Editor: underwater texture. */
    @Mixin(net.minecraft.client.renderer.ScreenEffectRenderer.class)
    public static class Water {
        @Inject(method = "renderWater", at = @At("HEAD"), cancellable = true)
        private static void aurora$water(CallbackInfo ci) {
            if (gg.aurora.module.NewModules.hideWater()) ci.cancel();
        }
    }

    /** Potion HUD on: no vanilla effect icons in the corner. */
    @Mixin(Gui.class)
    public static class Effects {
        @Inject(method = "renderEffects", at = @At("HEAD"), cancellable = true)
        private void aurora$effects(GuiGraphics g, DeltaTracker delta, CallbackInfo ci) {
            if (Modules.POTIONS.enabled && Modules.POTIONS.flag("hideVanilla", true)) ci.cancel();
        }
    }
}
