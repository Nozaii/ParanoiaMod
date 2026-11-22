package gg.aurora.mixin;

import gg.aurora.ItemPacks;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.ArrayList;
import java.util.List;

/** Packs par objet : la couche choisie passe au-dessus de tous les packs au rechargement des ressources. */
@Mixin(ReloadableResourceManager.class)
public class ItemPackMixin {
    @Shadow
    @Final
    private PackType type;

    @ModifyVariable(method = "createReload", at = @At("HEAD"), argsOnly = true)
    private List<PackResources> aurora$itemPacks(List<PackResources> packs) {
        if (type != PackType.CLIENT_RESOURCES) return packs;
        try {
            PackResources layer = ItemPacks.layer();
            if (layer == null) return packs;
            List<PackResources> out = new ArrayList<>(packs);
            out.add(layer);
            return out;
        } catch (RuntimeException e) {
            gg.aurora.AuroraClient.LOGGER.error("Packs par objet ignorés", e);
            return packs;
        }
    }
}
