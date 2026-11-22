package gg.aurora.mixin;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Mod hot-loading: keys created by a mod loaded mid-game are added to the controls list. */
public final class KeyBindsAccessor {
    private KeyBindsAccessor() {}

    @Mixin(KeyMapping.class)
    public interface All {
        @Accessor("ALL")
        static Map<String, KeyMapping> aurora$all() {
            throw new AssertionError();
        }
    }

    @Mixin(Options.class)
    public interface Keys {
        @Mutable
        @Accessor("keyMappings")
        void aurora$setKeyMappings(KeyMapping[] keys);
    }
}
