package gg.aurora;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/** Sons embarqués (assets/aurora/sounds.json) : coups de combat et chutes. */
public final class AuroraSounds {
    private AuroraSounds() {}

    public static final SoundEvent HIT = reg("combat.hit");
    public static final SoundEvent FALL_SMALL = reg("fall.small");
    public static final SoundEvent FALL_BIG = reg("fall.big");

    private static SoundEvent reg(String name) {
        Identifier id = Identifier.fromNamespaceAndPath("aurora", name);
        return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
    }

    /** Force le chargement de la classe pendant l'init du mod (registre encore ouvert). */
    public static void init() {}
}
