package gg.aurora.render;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.server.level.ParticleStatus;

/**
 * Profils de performance en un clic : règlent les options du jeu les plus coûteuses
 * (nuages, ombres d'entités, fondu des biomes, particules, transparence, feuilles…).
 * Ne touche ni aux textures (pas de rechargement) ni à la distance d'affichage.
 */
public final class PerfProfiles {
    private PerfProfiles() {}

    public static final String[] NAMES = {"Quality", "Balanced", "Max perf"};

    public static void apply(int profile) {
        Minecraft mc = Minecraft.getInstance();
        Options o = mc.options;
        try {
            switch (profile) {
                case 0 -> {
                    o.cloudStatus().set(CloudStatus.FANCY);
                    o.entityShadows().set(true);
                    o.biomeBlendRadius().set(5);
                    o.particles().set(ParticleStatus.ALL);
                    o.cutoutLeaves().set(true);
                    o.entityDistanceScaling().set(1.0);
                    o.chunkSectionFadeInTime().set(0.75);
                }
                case 1 -> {
                    o.cloudStatus().set(CloudStatus.FAST);
                    o.entityShadows().set(true);
                    o.biomeBlendRadius().set(2);
                    o.particles().set(ParticleStatus.ALL);
                    o.cutoutLeaves().set(true);
                    o.improvedTransparency().set(false);
                    o.entityDistanceScaling().set(1.0);
                    o.chunkSectionFadeInTime().set(0.5);
                }
                default -> {
                    o.cloudStatus().set(CloudStatus.OFF);
                    o.entityShadows().set(false);
                    o.biomeBlendRadius().set(0);
                    o.particles().set(ParticleStatus.DECREASED);
                    o.cutoutLeaves().set(false);
                    o.improvedTransparency().set(false);
                    o.vignette().set(false);
                    o.entityDistanceScaling().set(0.75);
                    o.chunkSectionFadeInTime().set(0.0);
                    o.enableVsync().set(false);
                    if (o.simulationDistance().get() > 8) o.simulationDistance().set(8);
                }
            }
            o.save();
        } catch (Exception e) {
            gg.aurora.AuroraClient.LOGGER.warn("Profil de performance: {}", e.toString());
        }
    }
}
