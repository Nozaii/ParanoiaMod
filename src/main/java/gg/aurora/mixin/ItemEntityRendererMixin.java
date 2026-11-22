package gg.aurora.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import gg.aurora.module.ScaledItemState;
import gg.aurora.module.WorldModules;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionfc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Objets au sol : Item Size (taille par type d'objet) et Item Physic (posés à plat, sans
 * flottement ni rotation, orientation aléatoire propre à chaque objet).
 */
@Mixin(ItemEntityRenderer.class)
public class ItemEntityRendererMixin {
    private static final String SUBMIT = "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
            + "Lnet/minecraft/client/renderer/state/CameraRenderState;)V";
    private static final String EXTRACT = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;"
            + "Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V";

    @Inject(method = EXTRACT, at = @At("RETURN"))
    private void aurora$scale(ItemEntity entity, ItemEntityRenderState state, float partialTick, CallbackInfo ci) {
        ((ScaledItemState) state).aurora$setScale(WorldModules.groundScale(entity.getItem()));
        if (!WorldModules.ITEM_PHYSIC.enabled) return; // atterrissage : utile seulement à Item Physic
        boolean grounded = entity.onGround() || entity.isInWater();
        ((ScaledItemState) state).aurora$setGrounded(grounded);
        // landing progress 0..1 over ~6 ticks: the item settles instead of snapping flat
        float[] st = SETTLE.computeIfAbsent(entity, e -> new float[]{grounded ? 1 : 0, 0});
        st[0] = grounded ? Math.min(1, st[0] + 0.05f) : 0;
        if (!grounded) st[1] = entity.tickCount + partialTick;
        ((ScaledItemState) state).aurora$setSettle(st[0], st[1]);
    }

    private static final java.util.Map<ItemEntity, float[]> SETTLE = new java.util.WeakHashMap<>();

    private static boolean flat(ItemEntityRenderState state) {
        AABB box = state.item.getModelBoundingBox();
        return box.maxZ - box.minZ < 0.2;
    }

    /** Signed shortest turn from a to b, in degrees. */
    private static float shortest(float a, float b) {
        return ((b - a) % 360 + 540) % 360 - 180;
    }

    private static float scale(ItemEntityRenderState state) {
        return ((ScaledItemState) state).aurora$getScale();
    }

    // @WrapOperation (et non @Redirect) : plusieurs mods peuvent envelopper le même appel sans conflit
    @WrapOperation(method = SUBMIT, at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"))
    private void aurora$ground(PoseStack pose, float x, float y, float z, Operation<Void> original, ItemEntityRenderState state,
                               PoseStack p2, SubmitNodeCollector collector, CameraRenderState camera) {
        float s = scale(state);
        AABB box = state.item.getModelBoundingBox();
        if (!WorldModules.ITEM_PHYSIC.enabled) {
            // garde l'objet au-dessus du sol quand il est agrandi
            original.call(pose, x, y + (s - 1) * (float) -box.minY, z);
            return;
        }
        boolean grounded = ((ScaledItemState) state).aurora$grounded();
        original.call(pose, 0f, grounded ? (flat(state) ? 0.03f * s : (float) -box.minY * s) : 0.25f * s, 0f);
    }

    @WrapOperation(method = SUBMIT, at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionfc;)V"))
    private void aurora$rotation(PoseStack pose, Quaternionfc spin, Operation<Void> original, ItemEntityRenderState state, PoseStack p2,
                                 SubmitNodeCollector collector, CameraRenderState camera) {
        if (!WorldModules.ITEM_PHYSIC.enabled) {
            original.call(pose, spin);
        } else {
            float yaw = Math.floorMod(state.seed * 37, 360);
            var ss = (ScaledItemState) state;
            float k = ss.aurora$settle();
            k = k * k * (3 - 2 * k);
            float air = ss.aurora$airAge();
            boolean isFlat = flat(state);
            float tumble = (air * 14) % 360;
            if (isFlat) {
                // lands flat, tumble eased into 90 degrees; a small random tilt so piles don't look stamped
                float tilt = ((state.seed >>> 3) % 9 - 4) * k;
                pose.mulPose(Axis.YP.rotationDegrees(yaw));
                pose.mulPose(Axis.XP.rotationDegrees(tumble + shortest(tumble, 90 + tilt) * k));
            } else {
                // blocks keep their axis, turning a little in the air and resting on a quarter turn
                float rest = Math.round(yaw / 90f) * 90f;
                pose.mulPose(Axis.YP.rotationDegrees(yaw + air * 9 + shortest(yaw + air * 9, rest) * k));
            }
        }
        float s = scale(state);
        if (s != 1f) pose.scale(s, s, s);
    }
}
