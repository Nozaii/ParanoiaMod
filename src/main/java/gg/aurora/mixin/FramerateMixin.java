package gg.aurora.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import gg.aurora.module.ExtraModules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Menu FPS Cap : limite les FPS dans les menus. */
@Mixin(FramerateLimitTracker.class)
public class FramerateMixin {
    private static long aurora$focusedAt = System.currentTimeMillis();

    @Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
    private void aurora$menu(CallbackInfoReturnable<Integer> cir) {
        gg.aurora.DevWorld.sweepFrames++;
        if (gg.aurora.SelfTest.ENABLED || gg.aurora.DevWorld.UNCAPPED) { // measure real costs, even when the window loses focus
            cir.setReturnValue(260);
            return;
        }
        int v = ExtraModules.menuFps(cir.getReturnValueI());
        // Optimizer +++ : fenêtre en arrière-plan -> FPS limités (le GPU et le CPU se reposent)
        int bg = gg.aurora.module.WorldModules.backgroundFps();
        // only after 2 s without focus: overlays (Discord, Medal) steal focus for an instant, that must not drop FPS
        boolean active = net.minecraft.client.Minecraft.getInstance().isWindowActive();
        long now = System.currentTimeMillis();
        if (active) aurora$focusedAt = now;
        if (bg > 0 && !active && now - aurora$focusedAt > 2000) v = Math.min(v, bg);
        // Limiteur écran : juste sous la fréquence de l'écran (260 = illimité dans Minecraft)
        int screen = gg.aurora.module.PerfModules.screenLimit();
        if (screen > 0 && (v >= 260 || screen < v)) v = screen;
        if (v != cir.getReturnValueI()) cir.setReturnValue(v);
    }
}
