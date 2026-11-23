package gg.aurora.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retient le serveur rejoint : un kick pendant la connexion arrive avant que la partie n'existe. */
@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {
    @Inject(method = "startConnecting", at = @At("HEAD"))
    private static void aurora$connecting(Screen parent, Minecraft mc, ServerAddress address, ServerData server, boolean quickPlay, TransferState transfer, CallbackInfo ci) {
        gg.aurora.ForbiddenMods.connecting(server);
    }
}
