package gg.aurora.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import gg.aurora.module.ClarityModules;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.fog.environment.FogEnvironment;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Rescale Resolution, Fog Controls and Smooth Scroll hooks. */
public final class ClarityMixins {
    private ClarityMixins() {}

    @Mixin(GameRenderer.class)
    public static class Projection {
        @ModifyReturnValue(method = "getProjectionMatrix", at = @At("RETURN"))
        private Matrix4f aurora$stretch(Matrix4f m) {
            float k = ClarityModules.stretch();
            return k == 1 ? m : m.m00(m.m00() * k);
        }
    }

    @Mixin(FogRenderer.class)
    public static class Fog {
        @WrapOperation(method = "setupFog", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/client/renderer/fog/environment/FogEnvironment;setupFog(Lnet/minecraft/client/renderer/fog/FogData;Lnet/minecraft/client/Camera;Lnet/minecraft/client/multiplayer/ClientLevel;FLnet/minecraft/client/DeltaTracker;)V"))
        private void aurora$fog(FogEnvironment env, FogData data, Camera cam, ClientLevel level, float dist, DeltaTracker delta, Operation<Void> original) {
            original.call(env, data, cam, level, dist, delta);
            if (ClarityModules.fogRemoved(env)) {
                data.environmentalStart = dist * 4;
                data.environmentalEnd = dist * 4 + 1;
                data.skyEnd = dist * 4;
                data.cloudEnd = dist * 4;
            }
        }
    }

    /** Lists glide to the wheel target instead of jumping. */
    @Mixin(AbstractScrollArea.class)
    public abstract static class Scroll {
        @Shadow
        private double scrollAmount;

        @Shadow
        public abstract int maxScrollAmount();

        @Unique
        private double aurora$target;
        @Unique
        private boolean aurora$gliding;
        @Unique
        private long aurora$last;

        @WrapOperation(method = "mouseScrolled", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/client/gui/components/AbstractScrollArea;setScrollAmount(D)V"))
        private void aurora$wheel(AbstractScrollArea self, double value, Operation<Void> original) {
            if (!ClarityModules.SMOOTH_SCROLL.enabled) {
                original.call(self, value);
                return;
            }
            double from = aurora$gliding ? aurora$target : scrollAmount;
            aurora$target = Mth.clamp(from + value - scrollAmount, 0, maxScrollAmount());
            aurora$gliding = true;
        }

        @Inject(method = "renderScrollbar", at = @At("HEAD"))
        private void aurora$glide(GuiGraphics g, int mx, int my, CallbackInfo ci) {
            long now = System.nanoTime();
            float dt = Math.min(0.05f, (now - aurora$last) / 1e9f);
            aurora$last = now;
            if (!aurora$gliding) return;
            double d = aurora$target - scrollAmount;
            // through setScrollAmount, not the field: lists (options, servers) move their entries there
            AbstractScrollArea self = (AbstractScrollArea) (Object) this;
            if (Math.abs(d) < 0.4) {
                aurora$gliding = false;
                self.setScrollAmount(aurora$target);
            } else {
                self.setScrollAmount(scrollAmount + d * (1 - Math.exp(-dt * ClarityModules.SMOOTH_SCROLL.opt("speed", 18))));
            }
        }
    }
}
