package gg.aurora.mixin;

import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Message de kick et écran de retour (mods interdits par le serveur). */
@Mixin(DisconnectedScreen.class)
public interface DisconnectedScreenAccessor {
    @Accessor("parent")
    Screen aurora$parent();

    @Accessor("details")
    DisconnectionDetails aurora$details();
}
