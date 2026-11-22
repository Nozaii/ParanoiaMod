package gg.aurora.mixin;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/** Accès à l'état du chat (copie d'un message au clic droit). */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {
    @Accessor("trimmedMessages")
    List<GuiMessage.Line> aurora$trimmed();

    @Accessor("allMessages")
    List<GuiMessage> aurora$all();

    @Accessor("chatScrollbarPos")
    int aurora$scroll();

    @Invoker("getScale")
    double aurora$scale();

    @Invoker("getLineHeight")
    int aurora$lineHeight();

    @Invoker("getWidth")
    int aurora$width();
}
