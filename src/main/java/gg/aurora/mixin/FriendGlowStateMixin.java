package gg.aurora.mixin;

import gg.aurora.render.FriendGlow;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Friend Glow : marque « à faire briller » posée sur l'état de rendu à l'extraction. */
@Mixin(EntityRenderState.class)
public class FriendGlowStateMixin implements FriendGlow.Marked {
    @Unique
    private boolean aurora$glow;

    @Override
    public boolean aurora$glow() {
        return aurora$glow;
    }

    @Override
    public void aurora$setGlow(boolean glow) {
        aurora$glow = glow;
    }
}
