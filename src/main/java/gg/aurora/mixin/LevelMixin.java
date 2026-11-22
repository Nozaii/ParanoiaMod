package gg.aurora.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import gg.aurora.module.WorldModules;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Heure fixe et météo forcées, uniquement pour le monde affiché côté client. */
@Mixin(Level.class)
public class LevelMixin {
    // appelés plusieurs fois par image (ciel, brouillard, pluie) : ModifyReturnValue, sans allocation
    @ModifyReturnValue(method = "getDayTime", at = @At("RETURN"))
    private long aurora$time(long original) {
        if (!((Object) this instanceof ClientLevel)) return original;
        long t = WorldModules.fixedTime();
        return t >= 0 ? t : original;
    }

    @ModifyReturnValue(method = "getRainLevel", at = @At("RETURN"))
    private float aurora$rain(float original) {
        if (!((Object) this instanceof ClientLevel)) return original;
        float r = WorldModules.rainLevel();
        return r >= 0 ? r : original;
    }

    @ModifyReturnValue(method = "getThunderLevel", at = @At("RETURN"))
    private float aurora$thunder(float original) {
        if (!((Object) this instanceof ClientLevel)) return original;
        float t = WorldModules.thunderLevel();
        return t >= 0 ? t : original;
    }
}
