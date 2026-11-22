package gg.aurora.mixin;

import gg.aurora.module.VisualModules;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Draw Crosshair, Custom Glint (vitesse et taille du motif), Swing Animation. */
public final class VisualMixins {
    private VisualMixins() {}

    @Mixin(Gui.class)
    public static class Crosshair {
        @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
        private void aurora$crosshair(GuiGraphics g, DeltaTracker delta, CallbackInfo ci) {
            if (VisualModules.CROSSHAIR.enabled && VisualModules.drawCrosshair(g)) ci.cancel();
        }
    }

    @Mixin(TextureTransform.class)
    public static class Glint {
        @Redirect(method = "setupGlintTexturing", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;"))
        private static Object aurora$glintSpeed(OptionInstance<?> option) {
            Object v = option.get();
            double k = VisualModules.glintSpeed();
            return k == 1 || !(v instanceof Double d) ? v : d * k;
        }

        @ModifyVariable(method = "setupGlintTexturing", at = @At("HEAD"), argsOnly = true)
        private static float aurora$glintPattern(float scale) {
            return scale / VisualModules.glintPattern();
        }
    }

    @Mixin(LivingEntity.class)
    public static class Swing {
        @Inject(method = "getCurrentSwingDuration", at = @At("RETURN"), cancellable = true)
        private void aurora$swing(CallbackInfoReturnable<Integer> cir) {
            int v = cir.getReturnValueI();
            int n = VisualModules.swingDuration((LivingEntity) (Object) this, v);
            if (n != v) cir.setReturnValue(n);
        }
    }
}
