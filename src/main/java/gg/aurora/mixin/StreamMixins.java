package gg.aurora.mixin;

import gg.aurora.module.ClientModules;
import net.minecraft.client.gui.components.debug.DebugEntryPosition;
import net.minecraft.client.gui.components.debug.DebugEntrySectionPosition;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mode stream : pas de coordonnées dans l'écran de debug (F3). */
public final class StreamMixins {
    private StreamMixins() {}

    @Mixin(DebugEntryPosition.class)
    public static class Position {
        @Inject(method = "display", at = @At("HEAD"), cancellable = true)
        private void aurora$hide(DebugScreenDisplayer d, Level level, LevelChunk a, LevelChunk b, CallbackInfo ci) {
            if (ClientModules.hideCoords()) ci.cancel();
        }
    }

    @Mixin(DebugEntrySectionPosition.class)
    public static class Section {
        @Inject(method = "display", at = @At("HEAD"), cancellable = true)
        private void aurora$hide(DebugScreenDisplayer d, Level level, LevelChunk a, LevelChunk b, CallbackInfo ci) {
            if (ClientModules.hideCoords()) ci.cancel();
        }
    }
}
