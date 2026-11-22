package gg.aurora.mixin;

import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Rendus « image dans l'image » (modèle du joueur dans la garde-robe…), partagés avec le menu dans le monde. */
@Mixin(GuiRenderer.class)
public interface GuiRendererAccessor {
    @Accessor("pictureInPictureRenderers")
    Map<Class<? extends PictureInPictureRenderState>, PictureInPictureRenderer<?>> aurora$pictureInPicture();
}
