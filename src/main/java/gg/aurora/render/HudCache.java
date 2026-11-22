package gg.aurora.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import gg.aurora.AuroraClient;
import gg.aurora.ui.WorldPanel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.function.Consumer;

/**
 * HUD en cache : les modules HUD (fonds arrondis, halos, textes) sont redessinés quelques dizaines de
 * fois par seconde dans une texture, puis affichés en un seul élément à chaque image. Le tri des
 * éléments de l'interface vanilla coûte n² : avec ~30 éléments par module, c'était la plus grosse
 * dépense fixe du mod à haut FPS.
 */
public final class HudCache {
    private HudCache() {}

    private static final Identifier TEX = Identifier.fromNamespaceAndPath("aurora", "dynamic/hud_cache");
    private static final boolean FLIP_V = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("vulkanmod");

    private static TextureTarget target;
    private static GuiRenderState state;
    private static GuiRenderer renderer;
    private static CacheTexture texture;
    private static long last;
    private static boolean failed, ready;
    private static int lastW, lastH;

    /** Dessine le HUD via le cache (ou directement si le cache est coupé ou a échoué). */
    public static void draw(GuiGraphics g, float hz, Consumer<GuiGraphics> paint) {
        if (failed || hz <= 0) {
            paint.accept(g);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        var win = mc.getWindow();
        int fw = win.getWidth(), fh = win.getHeight();
        if (fw <= 0 || fh <= 0) return;
        long now = System.nanoTime();
        boolean resized = fw != lastW || fh != lastH;
        if (!ready || resized || now - last >= (long) (1e9 / hz)) {
            try {
                refresh(mc, fw, fh, paint);
                last = now;
                lastW = fw;
                lastH = fh;
                ready = true;
            } catch (RuntimeException e) {
                failed = true;
                WorldPanel.redirect = null;
                AuroraClient.LOGGER.error("HUD en cache désactivé après une erreur (rendu direct)", e);
                paint.accept(g);
                return;
            }
        }
        int gw = win.getGuiScaledWidth(), gh = win.getGuiScaledHeight();
        // image hors écran rangée de bas en haut en OpenGL : on la retourne
        if (FLIP_V) g.blit(RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, TEX, 0, 0, 0, 0, gw, gh, fw, fh, fw, fh);
        else g.blit(RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, TEX, 0, 0, 0, fh, gw, gh, fw, -fh, fw, fh);
    }

    private static void refresh(Minecraft mc, int fw, int fh, Consumer<GuiGraphics> paint) {
        if (target == null) target = new TextureTarget("Paranoia HUD", fw, fh, true);
        else if (target.width != fw || target.height != fh) target.resize(fw, fh);
        if (renderer == null) {
            state = new GuiRenderState();
            renderer = new GuiRenderer(state, mc.renderBuffers().bufferSource(), mc.gameRenderer.getSubmitNodeStorage(),
                    mc.gameRenderer.getFeatureRenderDispatcher(), List.of());
        }
        state.reset();
        paint.accept(new GuiGraphics(mc, state, -1000, -1000));
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(), 0, target.getDepthTexture(), 1.0);
        WorldPanel.redirect = target;
        try {
            renderer.render(((gg.aurora.mixin.GameRendererAccessor) mc.gameRenderer).aurora$fogRenderer().getBuffer(FogRenderer.FogMode.NONE));
            renderer.incrementFrameNumber();
        } finally {
            WorldPanel.redirect = null;
        }
        if (texture == null) {
            texture = new CacheTexture();
            mc.getTextureManager().register(TEX, texture);
        }
        texture.set(target);
    }

    private static final class CacheTexture extends AbstractTexture {
        void set(TextureTarget t) {
            texture = t.getColorTexture();
            textureView = t.getColorTextureView();
            sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        }

        @Override
        public void close() {
        }
    }
}
