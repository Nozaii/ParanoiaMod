package gg.aurora.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;

import java.lang.reflect.Method;

/**
 * Copies the color of one render target into another (same size).
 * OpenGL: copyTextureToTexture. VulkanMod 0.6.x checks copyTextureToTexture's arguments but copies nothing, so there
 * the copy goes through VulkanMod's own blit (ImageUtil.blitFramebuffer: ends the pass, transitions, vkCmdBlitImage).
 */
public final class TexCopy {
    private TexCopy() {}

    public static final boolean VULKAN = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("vulkanmod");
    private static Method vkImage, vkBlit;
    private static boolean vkFailed;

    static {
        if (VULKAN) {
            try {
                ClassLoader cl = TexCopy.class.getClassLoader();
                Class<?> tex = Class.forName("net.vulkanmod.render.engine.VkGpuTexture", false, cl);
                Class<?> img = Class.forName("net.vulkanmod.vulkan.texture.VulkanImage", false, cl);
                Class<?> util = Class.forName("net.vulkanmod.vulkan.texture.ImageUtil", false, cl);
                vkImage = tex.getMethod("getVulkanImage");
                vkBlit = util.getMethod("blitFramebuffer", img, img);
            } catch (ReflectiveOperationException | LinkageError e) {
                vkFailed = true;
                gg.aurora.AuroraClient.LOGGER.warn("VulkanMod blit not found: screen effects stay off under Vulkan", e);
            }
        }
    }

    /** True when screen effects can't run: VulkanMod without a usable blit. */
    public static boolean unsupported() {
        return VULKAN && vkFailed;
    }

    /** Module ids whose effect needs a screen copy. */
    public static final java.util.Set<String> NEEDS_COPY = java.util.Set.of("ground_mirror", "motion_blur", "shockwaves", "filters");


    /**
     * Effects that read the world depth. VulkanMod 0.6.x keeps its depth as D24S8 bound through a depth+stencil view,
     * which shaders can't sample (reads give 0): these stay off there.
     */
    public static final java.util.Set<String> NEEDS_DEPTH = java.util.Set.of("ground_mirror", "motion_blur", "shockwaves");

    /** True when this module's effect can't run with the current renderer. */
    public static boolean blocked(String moduleId) {
        if (!VULKAN) return false;
        return NEEDS_DEPTH.contains(moduleId) || vkFailed && NEEDS_COPY.contains(moduleId);
    }

    public static void copy(RenderTarget src, RenderTarget dst) {
        copy(src.getColorTexture(), dst.getColorTexture(), Math.min(src.width, dst.width), Math.min(src.height, dst.height));
    }

    public static void copy(GpuTexture src, GpuTexture dst, int w, int h) {
        if (!VULKAN) {
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(src, dst, 0, 0, 0, 0, 0, w, h);
            return;
        }
        if (vkFailed) throw new IllegalStateException("VulkanMod blit unavailable");
        try {
            vkBlit.invoke(null, vkImage.invoke(src), vkImage.invoke(dst));
        } catch (ReflectiveOperationException e) {
            vkFailed = true;
            throw new IllegalStateException("VulkanMod blit failed", e);
        }
    }
}
