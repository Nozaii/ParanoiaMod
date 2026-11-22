package gg.aurora.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.renderer.texture.DynamicTexture;

import java.util.function.Supplier;

/**
 * Texture dynamique filtrée en linéaire : photos, bannières et GIF restent nets et sans escalier
 * quand l'interface les agrandit ou les réduit (les textures du jeu restent en « nearest »).
 */
public class SmoothTexture extends DynamicTexture {
    public SmoothTexture(Supplier<String> label, NativeImage image) {
        super(label, image);
    }

    @Override
    public GpuSampler getSampler() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }
}
