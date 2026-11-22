package gg.aurora.mixin;

import gg.aurora.module.ChatPlus;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Chat amélioré : heure et tête devant chaque message, catégorie (onglets), filtre d'affichage. */
@Mixin(ChatComponent.class)
public class ChatComponentMixin {
    @ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Component aurora$decorate(Component msg) {
        return ChatPlus.decorate(msg);
    }

    @Inject(method = "addMessageToQueue", at = @At("HEAD"))
    private void aurora$track(GuiMessage msg, CallbackInfo ci) {
        ChatPlus.track(msg);
    }

    /** Onglet actif : les messages d'une autre catégorie restent dans l'historique mais ne s'affichent pas. */
    @Inject(method = "addMessageToDisplayQueue", at = @At("HEAD"), cancellable = true)
    private void aurora$filter(GuiMessage msg, CallbackInfo ci) {
        if (!ChatPlus.visible(msg)) ci.cancel();
    }
}
