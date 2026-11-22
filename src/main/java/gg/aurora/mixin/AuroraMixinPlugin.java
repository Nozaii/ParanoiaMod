package gg.aurora.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Skips the mixins that target code another renderer replaces (VulkanMod rewrites RenderType.draw). */
public final class AuroraMixinPlugin implements IMixinConfigPlugin {
    private static final boolean VULKAN = FabricLoader.getInstance().isModLoaded("vulkanmod");
    /** Not applied under VulkanMod: the feature falls back to its plain path (HandGlow checks the same flag). */
    private static final Set<String> NOT_ON_VULKAN = Set.of("gg.aurora.mixin.AuraInstanceMixin");

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return !(VULKAN && NOT_ON_VULKAN.contains(mixinClassName));
    }

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
