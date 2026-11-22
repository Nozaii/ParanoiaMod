package gg.aurora.mixin;

import gg.aurora.module.Live;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Emotes : pose les bras, la tête et le corps du modèle pendant l'animation (salut, danse…). */
@Mixin(PlayerModel.class)
public class EmoteModelMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
    private void aurora$emote(AvatarRenderState state, CallbackInfo ci) {
        var level = Minecraft.getInstance().level;
        if (level == null || !(level.getEntity(state.id) instanceof Player p)) return;
        Object[] e = Live.emote(p);
        if (e == null) return;
        PlayerModel m = (PlayerModel) (Object) this;
        String id = (String) e[0];
        float t = (Float) e[1];
        // entrée et sortie en douceur
        float dur = Live.duration(id);
        float k = Math.min(1, Math.min(t / 0.25f, (dur - t) / 0.3f));
        k = k * k * (3 - 2 * k);
        float pi = (float) Math.PI;
        switch (id) {
            case "wave" -> {
                lerp(m.rightArm, 0, 0, 2.7f + 0.35f * (float) Math.sin(t * 9), k);
            }
            case "point" -> {
                lerp(m.rightArm, -pi / 2 - 0.1f, -0.15f, 0, k);
                lerp(m.head, -0.1f, m.head.yRot, 0, k);
            }
            case "clap" -> {
                float c = 0.35f + 0.3f * (float) Math.abs(Math.sin(t * 8));
                lerp(m.rightArm, -1.25f, -c, 0, k);
                lerp(m.leftArm, -1.25f, c, 0, k);
            }
            case "salute" -> {
                lerp(m.rightArm, -2.35f, -0.65f, 0.2f, k);
            }
            case "facepalm" -> {
                lerp(m.rightArm, -2.05f, -0.45f, 0, k);
                lerp(m.head, 0.45f, 0, 0, k);
            }
            default -> {
                // danse : bras qui alternent en l'air, buste qui balance, jambes qui piétinent
                float b = (float) Math.sin(t * 6.5f);
                lerp(m.rightArm, 0, 0, 2.3f + 0.5f * b, k);
                lerp(m.leftArm, 0, 0, -2.3f + 0.5f * b, k);
                lerp(m.body, 0, 0.35f * b, 0, k);
                lerp(m.head, 0.1f * (float) Math.sin(t * 13), 0.3f * b, 0.15f * b, k);
                lerp(m.rightLeg, 0.35f * b, 0, 0.05f, k);
                lerp(m.leftLeg, -0.35f * b, 0, -0.05f, k);
            }
        }
    }

    private static void lerp(net.minecraft.client.model.geom.ModelPart part, float x, float y, float z, float k) {
        part.xRot += (x - part.xRot) * k;
        part.yRot += (y - part.yRot) * k;
        part.zRot += (z - part.zRot) * k;
    }
}
