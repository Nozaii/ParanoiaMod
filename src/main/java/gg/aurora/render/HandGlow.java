package gg.aurora.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import gg.aurora.mixin.RenderPipelinesAccessor;
import gg.aurora.mixin.RenderSetupAccessor;
import gg.aurora.mixin.RenderTypeAccessor;
import gg.aurora.mixin.RenderTypeInvoker;
import gg.aurora.mixin.TextureBindingAccessor;
import gg.aurora.module.VisualModules;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand Aura : contour lumineux autour de la main et de l'objet tenu (1re personne).
 * La main est soumise une seconde fois à un collecteur intermédiaire qui remplace chaque géométrie
 * par sa silhouette (alpha de la texture, couleur unie) translucide, décalée dans 16
 * directions de l'écran et dessinée avant l'objet, qui la recouvre : seul le bord dépasse, comme un néon.
 */
public final class HandGlow {
    private HandGlow() {}

    private static RenderPipeline pipeline;
    private static final Map<Identifier, RenderType> TYPES = new HashMap<>();
    private static final Identifier FALLBACK = Identifier.fromNamespaceAndPath("aurora", "textures/cosmetic/white.png");

    /** Décalages (x, y, alpha relatif) de la passe courante, en unités de vue. */
    private static float[] offsets = new float[0], lastOffsets = new float[0];
    private static int color;
    private static SubmitNodeCollector root;

    private static RenderPipeline pipeline() {
        if (pipeline == null) {
            pipeline = RenderPipeline.builder(RenderPipelinesAccessor.aurora$matricesFog())
                    .withLocation(Identifier.fromNamespaceAndPath("aurora", "pipeline/silhouette"))
                    .withVertexShader("core/entity")
                    .withFragmentShader(Identifier.fromNamespaceAndPath("aurora", "core/silhouette"))
                    .withShaderDefine("EMISSIVE")
                    .withShaderDefine("NO_OVERLAY")
                    .withShaderDefine("NO_CARDINAL_LIGHTING")
                    .withSampler("Sampler0")
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .withDepthWrite(false)
                    .withCull(false)
                    .withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS)
                    .build();
        }
        return pipeline;
    }

    // ------------------------------------------------------------------ version instanciée
    // Les 36 copies décalées (3 anneaux x 12 directions) ne sont plus construites sur le CPU : une seule
    // silhouette est envoyée et le GPU la dessine 36 fois (instances), le vertex shader ajoutant le décalage
    // et l'alpha de chaque copie. Même calcul qu'avant (translation de la pose = position + décalage).
    // Seulement en OpenGL vanilla : VulkanMod (tableaux d'uniforms) et les packs Iris gardent l'ancien chemin.

    /** Dev (A/B) : forcer l'ancien chemin (36 copies sur le CPU). */
    public static boolean legacy;
    private static final boolean VULKAN = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("vulkanmod");
    private static RenderPipeline instPipeline;
    private static boolean instFailed;
    private static final Map<Identifier, RenderType> INST_TYPES = new HashMap<>();
    private static final java.util.Set<RenderType> INST_SET = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private static com.mojang.blaze3d.buffers.GpuBuffer offsetBuffer;
    private static java.nio.ByteBuffer offsetStaging;
    private static boolean offsetsDirty = true;

    private static boolean instanced() {
        return !legacy && !instFailed && !VULKAN && !IrisCompat.shaderPackInUse();
    }

    private static RenderPipeline instPipeline() {
        if (instPipeline == null) {
            instPipeline = RenderPipeline.builder(RenderPipelinesAccessor.aurora$matricesFog())
                    .withLocation(Identifier.fromNamespaceAndPath("aurora", "pipeline/silhouette_inst"))
                    .withVertexShader(Identifier.fromNamespaceAndPath("aurora", "core/silhouette_inst"))
                    .withFragmentShader(Identifier.fromNamespaceAndPath("aurora", "core/silhouette"))
                    .withUniform("AuraOffsets", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
                    .withSampler("Sampler0")
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .withDepthWrite(false)
                    .withCull(false)
                    .withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS)
                    .build();
        }
        return instPipeline;
    }

    private static RenderType instType(Identifier tex) {
        return INST_TYPES.computeIfAbsent(tex, t -> {
            RenderType rt = RenderTypeInvoker.aurora$create("aurora_silhouette_inst",
                    RenderSetup.builder(instPipeline()).withTexture("Sampler0", t).createRenderSetup());
            INST_SET.add(rt);
            return rt;
        });
    }

    /** Appelé par le mixin de RenderType.draw : ce type est-il dessiné en 36 instances ? */
    public static boolean isInstanced(RenderType t) {
        return !INST_SET.isEmpty() && INST_SET.contains(t);
    }

    /** Buffer des décalages (36 vec4), réécrit seulement quand ils changent (respiration, réglages). */
    public static com.mojang.blaze3d.buffers.GpuBuffer offsets() {
        if (offsetsDirty || offsetBuffer == null) {
            if (offsetStaging == null) offsetStaging = org.lwjgl.system.MemoryUtil.memAlloc(36 * 16);
            offsetStaging.clear();
            var b = com.mojang.blaze3d.buffers.Std140Builder.intoBuffer(offsetStaging);
            for (int i = 0; i < 36; i++) {
                int k = i * 3;
                if (k + 2 < offsets.length) b.putVec4(offsets[k], offsets[k + 1], offsets[k + 2], 0);
                else b.putVec4(0, 0, 0, 0);
            }
            offsetStaging.position(0).limit(36 * 16);
            if (offsetBuffer != null) offsetBuffer.close();
            offsetBuffer = com.mojang.blaze3d.systems.RenderSystem.getDevice().createBuffer(() -> "Paranoia Hand Aura", com.mojang.blaze3d.buffers.GpuBuffer.USAGE_UNIFORM, offsetStaging);
            offsetsDirty = false;
        }
        return offsetBuffer;
    }

    public static int instances() {
        return Math.min(36, offsets.length / 3);
    }

    /** Erreur GPU sur le chemin instancié : retour définitif à l'ancien chemin. */
    public static void instancingFailed(RuntimeException e) {
        if (!instFailed) gg.aurora.AuroraClient.LOGGER.warn("Hand Aura instanciée désactivée : {}", e.toString());
        instFailed = true;
    }

    private static Identifier textureOf(RenderType source) {
        try {
            RenderSetup setup = ((RenderTypeAccessor) source).aurora$state();
            Object binding = ((RenderSetupAccessor) (Object) setup).aurora$textures().get("Sampler0");
            if (binding != null) return ((TextureBindingAccessor) binding).aurora$location();
        } catch (RuntimeException ignored) {
        }
        return FALLBACK;
    }

    private static RenderType silhouette(RenderType source) {
        Identifier tex = FALLBACK;
        try {
            RenderSetup setup = ((RenderTypeAccessor) source).aurora$state();
            Object binding = ((RenderSetupAccessor) (Object) setup).aurora$textures().get("Sampler0");
            if (binding != null) tex = ((TextureBindingAccessor) binding).aurora$location();
        } catch (RuntimeException ignored) {
            // texture inconnue : silhouette pleine
        }
        return TYPES.computeIfAbsent(tex, t -> RenderTypeInvoker.aurora$create("aurora_silhouette",
                RenderSetup.builder(pipeline()).withTexture("Sampler0", t).createRenderSetup())); // couleur unique : mélange commutatif, pas de tri
    }

    /** Silhouette translucide d'une texture (contour lumineux du Friend Glow). */
    public static RenderType silhouetteOf(Identifier texture) {
        return TYPES.computeIfAbsent(texture, t -> RenderTypeInvoker.aurora$create("aurora_silhouette",
                RenderSetup.builder(pipeline()).withTexture("Sampler0", t).createRenderSetup())); // couleur unique : mélange commutatif, pas de tri
    }

    /** Prépare la passe de lueur ; null si le module est coupé. */
    public static SubmitNodeCollector begin(SubmitNodeCollector delegate, float time) {
        if (!VisualModules.HAND_AURA.enabled) return null;
        var m = VisualModules.HAND_AURA;
        float aura = (float) m.opt("aura", 70) / 100f, rim = (float) m.opt("rim", 60) / 100f;
        float reach = (float) m.opt("reach", 10) / 20f * 0.02f + 0.002f;
        float breathe = m.flag("breathe", true) ? 0.78f + 0.22f * (float) Math.sin(time * 0.12f) : 1f;
        color = VisualModules.color(m, 0x9D7CFF);
        // trois anneaux de 16 directions (décalés d'un demi-pas) : contour continu, sans marches
        final int dirs = 12;
        float[] radius = {0.28f, 0.62f, 1f};
        float[] alpha = {(0.05f + 0.16f * rim) * 1.33f, (0.045f + 0.07f * aura) * 1.33f, 0.06f * aura * 1.33f}; // 12 passes instead of 16 per ring
        float[] o = offsets.length == 3 * dirs * 3 ? offsets : new float[3 * dirs * 3];
        int n = 0;
        for (int ring = 0; ring < 3; ring++) {
            for (int i = 0; i < dirs; i++) {
                double a = Math.PI * 2 * (i + (ring % 2) * 0.5) / dirs;
                o[n++] = (float) Math.cos(a) * reach * radius[ring];
                o[n++] = (float) Math.sin(a) * reach * radius[ring];
                o[n++] = alpha[ring] * breathe;
            }
        }
        if (o != offsets || !java.util.Arrays.equals(o, lastOffsets)) {
            offsetsDirty = true;
            lastOffsets = o.clone();
        }
        offsets = o;
        root = delegate;
        return wrap(delegate, SubmitNodeCollector.class);
    }

    @SuppressWarnings("unchecked")
    private static <T> T wrap(Object target, Class<T> iface) {
        return (T) Proxy.newProxyInstance(HandGlow.class.getClassLoader(), new Class<?>[]{iface}, new Handler(target));
    }

    private record Handler(Object target) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) return method.invoke(target, args);
            // Les noms des méthodes de Minecraft changent en jeu (noms « intermediary ») : on reconnaît
            // donc chaque méthode par ses paramètres, jamais par son nom.
            Class<?>[] pt = method.getParameterTypes();
            if (pt.length == 1 && pt[0] == int.class && OrderedSubmitNodeCollector.class.isAssignableFrom(method.getReturnType())) {
                return wrap(((SubmitNodeCollector) target).order((Integer) args[0]), OrderedSubmitNodeCollector.class);
            }
            // les variantes par défaut rappellent la méthode principale sur ce même collecteur
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
            // méthode inconnue qui renvoie quelque chose (ajoutée par un autre mod) : on la laisse passer
            if (method.getReturnType() != void.class) return method.invoke(target, args);
            try {
                glow(pt, args);
            } catch (RuntimeException e) {
                // un rendu de lueur raté ne doit jamais faire planter le jeu
                if (!warned) {
                    warned = true;
                    gg.aurora.AuroraClient.LOGGER.warn("Hand Aura : {}", e.toString());
                }
            }
            return null;
        }

        private static void glow(Class<?>[] pt, Object[] args) {
            boolean item = pt.length == 9 && pt[0] == PoseStack.class && pt[6] == List.class && pt[7] == RenderType.class;
            boolean part = pt.length == 11 && pt[0] == net.minecraft.client.model.geom.ModelPart.class && pt[1] == PoseStack.class
                    && pt[2] == RenderType.class && pt[8] == int.class;
            if (!item && !part) return; // ombres, textes, effets : pas de lueur
            // ordre de rendu antérieur : la lueur est dessinée avant la main, qui la recouvre ensuite
            OrderedSubmitNodeCollector c = root.order(-1);
            switch (item ? "submitItem" : "submitModelPart") {
                case "submitItem" -> {
                    PoseStack pose = (PoseStack) args[0];
                    @SuppressWarnings("unchecked") List<BakedQuad> quads = (List<BakedQuad>) args[6];
                    if (instanced()) {
                        RenderType it = instType(textureOf((RenderType) args[7]));
                        float r1 = ((color >> 16) & 255) / 255f, g1 = ((color >> 8) & 255) / 255f, b1 = (color & 255) / 255f;
                        c.submitCustomGeometry(pose, it, (p, vc) -> {
                            for (BakedQuad q : quads) vc.putBulkData(p, q, r1, g1, b1, 1f, 0xF000F0, OverlayTexture.NO_OVERLAY);
                        });
                        return;
                    }
                    RenderType type = silhouette((RenderType) args[7]);
                    float r = ((color >> 16) & 255) / 255f, g = ((color >> 8) & 255) / 255f, b = (color & 255) / 255f;
                    // every offset goes in one draw: a draw per offset is what costs, above all under Vulkan
                    c.submitCustomGeometry(pose, type, (p, vc) -> {
                        // la liste vient souvent d'une map (pas d'accès indexé) : un tableau une fois, pas un itérateur
                        // par décalage (36 par objet et par image = ~280 Mo/s alloués avant)
                        BakedQuad[] qa = quads instanceof java.util.RandomAccess ? null : quads.toArray(new BakedQuad[0]);
                        int nq = qa == null ? quads.size() : qa.length;
                        for (int i = 0; i < offsets.length; i += 3) {
                            float a = offsets[i + 2];
                            if (a <= 0.004f) continue;
                            PoseStack.Pose moved = shifted(p, i);
                            for (int k = 0; k < nq; k++) {
                                vc.putBulkData(moved, qa == null ? quads.get(k) : qa[k], r, g, b, a, 0xF000F0, OverlayTexture.NO_OVERLAY);
                            }
                        }
                    });
                }
                case "submitModelPart" -> {
                    if (!VisualModules.HAND_AURA.flag("arm", true) && armPart(args)) return;
                    PoseStack pose = (PoseStack) args[1];
                    var model = (net.minecraft.client.model.geom.ModelPart) args[0];
                    var sprite = (net.minecraft.client.renderer.texture.TextureAtlasSprite) args[5];
                    if (instanced()) {
                        RenderType it = instType(textureOf((RenderType) args[2]));
                        c.submitCustomGeometry(pose, it, (p, vc) -> {
                            var out = sprite == null ? vc : sprite.wrap(vc);
                            SCRATCH.last().set(p);
                            model.render(SCRATCH, out, 0xF000F0, OverlayTexture.NO_OVERLAY, 0xFF000000 | (color & 0xFFFFFF));
                        });
                        return;
                    }
                    RenderType type = silhouette((RenderType) args[2]);
                    c.submitCustomGeometry(pose, type, (p, vc) -> {
                        var out = sprite == null ? vc : sprite.wrap(vc);
                        for (int i = 0; i < offsets.length; i += 3) {
                            float a = offsets[i + 2];
                            if (a <= 0.004f) continue;
                            shifted(p, i);
                            model.render(SCRATCH, out, 0xF000F0, OverlayTexture.NO_OVERLAY, (Math.round(Math.min(1f, a) * 255) << 24) | (color & 0xFFFFFF));
                        }
                    });
                }
                default -> {
                }
            }
        }

        private static boolean armPart(Object[] args) {
            return args[5] == null;
        }
    }

    private static boolean warned;
    private static final Matrix4f TMP = new Matrix4f();
    private static final PoseStack SCRATCH = new PoseStack();

    /** The pose moved by offset i in view space, slightly off the object. */
    private static PoseStack.Pose shifted(PoseStack.Pose from, int i) {
        PoseStack.Pose out = SCRATCH.last();
        out.set(from);
        Matrix4f m = out.pose();
        TMP.translation(offsets[i], offsets[i + 1], 0).mul(m, m);
        return out;
    }
}
