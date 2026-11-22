package gg.aurora.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import gg.aurora.Capes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cape Paranoia : la nôtre (wardrobe) et celle des autres joueurs du mod (profil partagé). */
@Mixin(AbstractClientPlayer.class)
public class AbstractClientPlayerMixin {
    /** Skin + cape déjà combinés (évite un nouvel objet à chaque appel : plusieurs fois par joueur et par image). */
    private static final java.util.Map<PlayerSkin, Object[]> AURORA$CACHE = new java.util.IdentityHashMap<>();

    private static PlayerSkin aurora$with(PlayerSkin s, net.minecraft.core.ClientAsset.Texture cape) {
        Object[] c = AURORA$CACHE.get(s);
        if (c != null && c[0] == cape) return (PlayerSkin) c[1];
        if (AURORA$CACHE.size() > 256) AURORA$CACHE.clear();
        PlayerSkin out = new PlayerSkin(s.body(), cape, s.elytra(), s.model(), s.secure());
        AURORA$CACHE.put(s, new Object[]{cape, out});
        return out;
    }

    @ModifyReturnValue(method = "getSkin", at = @At("RETURN"))
    private PlayerSkin aurora$cape(PlayerSkin s) {
        AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;
        if (!gg.aurora.Settings.showCapes || s == null) return s; // capes Paranoia masquées : la cape vanilla du joueur reste
        Capes.Cape cape;
        if (self == Minecraft.getInstance().player) {
            cape = Capes.selected();
        } else {
            var p = gg.aurora.nametag.NametagSync.profile(self.getUUID());
            if (p != null && p.cape() != null && p.cape().startsWith("u_")) {
                // cape importée par ce joueur : téléchargée du serveur Paranoia, selon mon réglage (tous / amis / personne)
                var remote = gg.aurora.Media.remoteCape(self.getUUID(), p.cape());
                return remote == null ? s : aurora$with(s, remote);
            }
            cape = p == null ? null : Capes.byId(p.cape());
        }
        if (cape == null || !cape.ready()) return s;
        return aurora$with(s, cape.asset());
    }
}
