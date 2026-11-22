package gg.aurora.mixin;

import gg.aurora.module.ScaledItemState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Ajoute l'échelle Item Size à l'état de rendu des objets au sol. */
@Mixin(ItemEntityRenderState.class)
public class ItemEntityRenderStateMixin implements ScaledItemState {
    @Unique
    private float aurora$scale = 1f;

    @Unique
    private boolean aurora$grounded = true;

    @Override
    public boolean aurora$grounded() {
        return aurora$grounded;
    }

    @Override
    public void aurora$setGrounded(boolean g) {
        aurora$grounded = g;
    }

    @Unique
    private float aurora$settle = 1, aurora$airAge;

    @Override
    public void aurora$setSettle(float settle, float airAge) {
        aurora$settle = settle;
        aurora$airAge = airAge;
    }

    @Override
    public float aurora$settle() {
        return aurora$settle;
    }

    @Override
    public float aurora$airAge() {
        return aurora$airAge;
    }

    @Override
    public float aurora$getScale() {
        return aurora$scale;
    }

    @Override
    public void aurora$setScale(float s) {
        aurora$scale = s;
    }
}
