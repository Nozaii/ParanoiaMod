package gg.aurora.mixin;

import gg.aurora.ItemPacks;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.equipment.EquipmentAsset;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Aperçus de packs : les assets « aurora:pack/… » n'existent pas sur disque, on sert leurs couches directement. */
@Mixin(EquipmentAssetManager.class)
public class EquipmentAssetManagerMixin {
    @Inject(method = "get", at = @At("HEAD"), cancellable = true)
    private void aurora$preview(ResourceKey<EquipmentAsset> key, CallbackInfoReturnable<EquipmentClientInfo> cir) {
        if (!key.identifier().getNamespace().equals("aurora")) return;
        EquipmentClientInfo info = ItemPacks.previewAssetInfo(key);
        if (info != null) cir.setReturnValue(info);
    }
}
