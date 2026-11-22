package gg.aurora.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import gg.aurora.module.CombatModules;
import gg.aurora.module.ShieldModules;
import gg.aurora.module.VisualModules;
import gg.aurora.render.HandGlow;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Main à la 1re personne : Hand Pose (position / rotation / taille), Shield Size (bouclier plus petit),
 * couleur du bouclier (Shield Status) et Hand Aura (contour lumineux, seconde passe).
 */
@Mixin(ItemInHandRenderer.class)
public abstract class HandMixin {
    @Unique
    private int aurora$depth;
    @Unique
    private boolean aurora$glowing;
    @Unique
    private boolean aurora$glowFailed;
    /** Nombre de poses empilées par appel (récursion de la passe de lueur incluse). */
    @Unique
    private final int[] aurora$pushed = new int[4];

    @Shadow
    protected abstract void renderArmWithItem(AbstractClientPlayer player, float pt, float pitch, InteractionHand hand, float swing, ItemStack stack,
                                              float equip, PoseStack pose, SubmitNodeCollector collector, int light);

    @Inject(method = "renderArmWithItem", at = @At("HEAD"))
    private void aurora$handHead(AbstractClientPlayer player, float pt, float pitch, InteractionHand hand, float swing, ItemStack stack,
                                 float equip, PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        int d = Math.min(3, aurora$depth++);
        int n = VisualModules.applyHandPose(pose, hand) ? 1 : 0;
        if (aurora$shieldSize(pose, hand, stack)) n++;
        aurora$pushed[d] = n;
        ShieldModules.renderTint = ShieldModules.tintFor(player, stack);
        ShieldModules.renderDesign = ShieldModules.designFor(player, stack);
        // animations 1.7 : pose de blocage à l'épée, consommation plus proche
        if (hand == InteractionHand.MAIN_HAND && gg.aurora.module.ClientModules.swordBlocking(player, stack)) {
            pose.pushPose();
            float side = player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT ? 1 : -1;
            // séquence de la 1.7 (Y 30°, X -80°, Y 60°) autour de la main, épée en travers devant soi
            pose.translate(side * 0.56f - side * 0.2f, -0.52f + 0.1f, -0.72f);
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(side * 30));
            pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-50));
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(side * 40));
            pose.translate(-side * 0.56f, 0.52f, 0.72f);
            aurora$pushed[d]++;
        } else if (gg.aurora.module.ClientModules.OLD_ANIM.enabled && gg.aurora.module.ClientModules.OLD_ANIM.flag("eat", true)
                && player.isUsingItem() && player.getUsedItemHand() == hand
                && (stack.has(net.minecraft.core.component.DataComponents.FOOD) || stack.has(net.minecraft.core.component.DataComponents.CONSUMABLE))) {
            pose.pushPose();
            float side = (hand == InteractionHand.MAIN_HAND) == (player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT) ? 1 : -1;
            float bob = (float) Math.abs(Math.sin((player.tickCount + pt) * 0.9f)) * 0.06f;
            pose.translate(-side * 0.12f, 0.06f + bob, 0.08f);
            aurora$pushed[d]++;
        }
    }

    @org.spongepowered.asm.mixin.Shadow
    private float mainHandHeight;
    @org.spongepowered.asm.mixin.Shadow
    private float oMainHandHeight;
    @org.spongepowered.asm.mixin.Shadow
    private ItemStack mainHandItem;

    /** Animations 1.7 : la main ne baisse pas après un coup ni au changement d'objet. */
    @Inject(method = "tick", at = @At("TAIL"))
    private void aurora$noDip(CallbackInfo ci) {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        // seulement quand l'objet affiché est déjà le bon : sinon l'échange d'objet (main baissée) ne se ferait jamais
        if (player != null && gg.aurora.module.ClientModules.OLD_ANIM.enabled && gg.aurora.module.ClientModules.OLD_ANIM.flag("noDip", true)
                && ItemStack.matches(mainHandItem, player.getMainHandItem())) {
            mainHandHeight = 1;
            oMainHandHeight = 1;
        }
    }

    @Unique
    private static PoseStack aurora$glowStack = new PoseStack();

    @Inject(method = "renderArmWithItem", at = @At("TAIL"))
    private void aurora$handTail(AbstractClientPlayer player, float pt, float pitch, InteractionHand hand, float swing, ItemStack stack,
                                 float equip, PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        int d = Math.min(3, --aurora$depth);
        for (int i = 0; i < aurora$pushed[d]; i++) pose.popPose();
        aurora$pushed[d] = 0;
        if (!aurora$glowing && !aurora$glowFailed) {
            SubmitNodeCollector glow = HandGlow.begin(collector, player.tickCount + pt);
            if (glow != null) {
                aurora$glowing = true;
                int savedDepth = aurora$depth;
                // pile séparée : une erreur au milieu (autre mod) ne déséquilibre pas la vraie pile
                // réutilisée (une seule pose de base) ; recréée seulement si une erreur l'a laissée déséquilibrée
                PoseStack copy = aurora$glowStack;
                copy.last().set(pose.last());
                try {
                    // seconde passe : même main, même pose, géométrie remplacée par sa silhouette lumineuse
                    renderArmWithItem(player, pt, pitch, hand, swing, stack, equip, copy, glow, light);
                } catch (RuntimeException e) {
                    aurora$glowStack = new PoseStack();
                    aurora$glowFailed = true;
                    gg.aurora.AuroraClient.LOGGER.warn("Hand Aura désactivée pour cette session (conflit avec un autre mod) : {}", e.toString());
                } finally {
                    if (!copy.isEmpty()) aurora$glowStack = new PoseStack(); // pile restée ouverte : on repart d'une neuve
                    aurora$depth = savedDepth;
                    aurora$glowing = false;
                }
            }
        }
        ShieldModules.renderTint = -1;
        ShieldModules.renderDesign = null;
    }

    /** Shield Size : réduit (et abaisse) le bouclier autour d'un point devant la caméra. */
    @Unique
    private static boolean aurora$shieldSize(PoseStack pose, InteractionHand hand, ItemStack stack) {
        if (!CombatModules.SHIELD_SIZE.enabled || !stack.has(DataComponents.BLOCKS_ATTACKS)) return false;
        if (hand == InteractionHand.MAIN_HAND && !CombatModules.SHIELD_SIZE.flag("main", false)) return false;
        float s = (float) CombatModules.SHIELD_SIZE.opt("scale", 0.6);
        pose.pushPose();
        float side = hand == InteractionHand.OFF_HAND ? -0.56f : 0.56f;
        pose.translate(side, -0.52f + (float) CombatModules.SHIELD_SIZE.opt("down", -0.16), -0.72f);
        pose.scale(s, s, s);
        pose.translate(-side, 0.52f, 0.72f);
        return true;
    }
}
