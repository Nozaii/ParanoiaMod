package gg.aurora.mixin;

import gg.aurora.module.Freelook;
import gg.aurora.module.Modules;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freelook (caméra détachée du regard du joueur) et molette du zoom. */
public final class CameraMixins {
    private CameraMixins() {}

    @Mixin(Entity.class)
    public static class Turn {
        @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
        private void aurora$freelook(double yaw, double pitch, CallbackInfo ci) {
            if ((Object) this == Minecraft.getInstance().player && Freelook.turn(yaw, pitch)) ci.cancel();
        }
    }

    @Mixin(Camera.class)
    public static class Rotation {
        @ModifyArg(method = "setup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"), index = 0)
        private float aurora$yaw(float yaw) {
            return yaw + Freelook.yawOffset();
        }

        @ModifyArg(method = "setup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"), index = 1)
        private float aurora$pitch(float pitch) {
            return Math.max(-90, Math.min(90, pitch + Freelook.pitchOffset()));
        }
    }

    @Mixin(MouseHandler.class)
    public static class Scroll {
        @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
        private void aurora$zoomScroll(long window, double x, double y, CallbackInfo ci) {
            if (Minecraft.getInstance().screen == null && Modules.zoomScroll(y)) ci.cancel();
        }
    }
}
