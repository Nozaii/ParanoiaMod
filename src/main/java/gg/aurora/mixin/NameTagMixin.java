package gg.aurora.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import gg.aurora.module.ExtraModules;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Target Info : vie, distance et ping ajoutés au nametag des joueurs. */
@Mixin(EntityRenderer.class)
public class NameTagMixin {
    @ModifyReturnValue(method = "getNameTag", at = @At("RETURN"))
    private Component aurora$info(Component original, Entity entity) {
        return ExtraModules.targetInfo(entity, original);
    }
}
