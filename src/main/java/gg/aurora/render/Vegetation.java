package gg.aurora.render;

import gg.aurora.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.TallFlowerBlock;
import net.minecraft.world.level.block.TallGrassBlock;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Réglages graphiques « végétation » : herbes, fleurs et débris au sol peuvent ne pas être dessinés.
 * Les blocs restent là (collisions, clics, sons) : seul leur modèle est retiré des chunks. La liste des
 * blocs cachés est calculée une fois par changement de réglage ; pendant la construction des chunks
 * (plusieurs threads) on ne fait que la lire.
 */
public final class Vegetation {
    private Vegetation() {}

    /** Blocs à ne pas dessiner, ou null quand tout est affiché (cas normal : coût nul). */
    public static volatile Set<Block> hidden;

    private static final Set<String> GRASS = Set.of("short_grass", "fern", "tall_grass", "large_fern", "short_dry_grass", "tall_dry_grass",
            "seagrass", "tall_seagrass");
    private static final Set<String> FLOWER_BEDS = Set.of("pink_petals", "wildflowers", "cactus_flower", "spore_blossom");
    private static final Set<String> LITTER = Set.of("leaf_litter", "dead_bush", "bush", "firefly_bush", "hanging_roots", "glow_lichen",
            "vine", "moss_carpet", "pale_moss_carpet", "pale_hanging_moss");

    /** À appeler au lancement, à l'entrée dans un monde et après chaque changement de réglage. */
    public static void refresh(boolean rebuildChunks) {
        Set<Block> out = null;
        if (!Settings.showGrass || !Settings.showFlowers || !Settings.showLitter) {
            out = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Block b : BuiltInRegistries.BLOCK) {
                String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
                boolean grass = b instanceof TallGrassBlock || GRASS.contains(id);
                boolean flower = b instanceof FlowerBlock || b instanceof TallFlowerBlock || FLOWER_BEDS.contains(id) || flowerTag(b);
                if (!Settings.showGrass && grass || !Settings.showFlowers && flower && !grass || !Settings.showLitter && LITTER.contains(id)) out.add(b);
            }
            if (out.isEmpty()) out = null;
        }
        hidden = out;
        Minecraft mc = Minecraft.getInstance();
        if (rebuildChunks && mc.levelRenderer != null && mc.level != null) mc.levelRenderer.allChanged();
    }

    private static Object lastLevel;

    /** Chaque tick : à l'entrée dans un monde, la liste est recalculée (les tags de blocs viennent d'arriver). */
    public static void tick(Minecraft mc) {
        if (mc.level == lastLevel) return;
        lastLevel = mc.level;
        if (mc.level != null) refresh(false);
    }

    private static boolean flowerTag(Block b) {
        try {
            return b.defaultBlockState().is(BlockTags.FLOWERS);
        } catch (RuntimeException e) {
            return false; // tags pas encore chargés (hors d'un monde)
        }
    }
}
