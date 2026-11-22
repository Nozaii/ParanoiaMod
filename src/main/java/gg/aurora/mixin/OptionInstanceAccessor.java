package gg.aurora.mixin;

import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Permet de pousser le gamma au-delà de 100 % (Fullbright) sans passer par la validation. */
@Mixin(OptionInstance.class)
public interface OptionInstanceAccessor {
    @Accessor("value")
    void aurora$setValue(Object value);
}
