package gg.aurora.module;

/** Implémentée par ItemEntityRenderState (mixin) : échelle d'affichage de l'objet au sol. */
public interface ScaledItemState {
    float aurora$getScale();

    void aurora$setScale(float s);

    void aurora$setSettle(float settle, float airAge);

    float aurora$settle();

    float aurora$airAge();

    boolean aurora$grounded();

    void aurora$setGrounded(boolean g);
}
