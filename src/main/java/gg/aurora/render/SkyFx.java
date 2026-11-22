package gg.aurora.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import gg.aurora.mixin.RenderPipelinesAccessor;
import gg.aurora.mixin.RenderTypeInvoker;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/**
 * Ciel procédural : une sphère autour de la caméra dont le shader calcule nuages, aurores, éclairs
 * et étoiles à partir de la direction de vue. Le temps et l'ambiance passent par les UV du sommet.
 */
public final class SkyFx {
    private SkyFx() {}

    private static RenderType type;

    public static RenderType type() {
        if (type == null) {
            RenderPipeline pipeline = RenderPipeline.builder(RenderPipelinesAccessor.aurora$matricesFog())
                    .withLocation(Identifier.fromNamespaceAndPath("aurora", "pipeline/sky_fx"))
                    .withVertexShader(Identifier.fromNamespaceAndPath("aurora", "core/sky_fx"))
                    .withFragmentShader(Identifier.fromNamespaceAndPath("aurora", "core/sky_fx"))
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .withDepthWrite(false)
                    .withCull(false)
                    .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
                    .build();
            type = RenderTypeInvoker.aurora$create("aurora_sky_fx", RenderSetup.builder(pipeline).createRenderSetup());
        }
        return type;
    }

    // ------------------------------------------------------------------ packs de shaders (Iris)
    // Un pack dessine son propre ciel par-dessus tout shader « inconnu ». On calcule donc le ciel dans
    // une texture équirectangulaire (hors du rendu du monde), puis on la pose sur la sphère avec un
    // pipeline que le pack traite comme un ciel texturé (comme le soleil et la lune).

    private static final Identifier EQUI_TEX = Identifier.fromNamespaceAndPath("aurora", "dynamic/sky_equirect");
    private static com.mojang.blaze3d.pipeline.TextureTarget equi;
    private static RenderPipeline equiPipe, texPipe;
    private static RenderType texType;
    private static long lastEqui;
    private static boolean equiReady, equiFailed;

    /** Sky computed 20 times per second instead of on every pixel of every frame: the biggest cost of Custom Sky. */
    private static boolean cached() {
        return gg.aurora.module.WorldModules.CUSTOM_SKY.flag("cached", true);
    }

    /** Ciel par texture : cache activé, ou pack de shaders Iris actif. */
    public static boolean useTexture() {
        return equiReady && !equiFailed && (cached() || IrisCompat.shaderPackInUse());
    }

    /** Avant le rendu du monde : met à jour la texture du ciel (~30 fois par seconde). */
    public static void prepare(net.minecraft.client.Minecraft mc, int mode, float secs) {
        if (equiFailed || mode <= 0 || !cached() && !IrisCompat.shaderPackInUse()) return;
        long now = System.nanoTime();
        if (equiReady && now - lastEqui < 50_000_000L) return;
        lastEqui = now;
        try {
            if (equiPipe == null) {
                equiPipe = RenderPipeline.builder()
                        .withLocation(Identifier.fromNamespaceAndPath("aurora", "pipeline/sky_equirect"))
                        .withVertexShader("core/screenquad")
                        .withFragmentShader(Identifier.fromNamespaceAndPath("aurora", "core/sky_fx_equirect"))
                        .withUniform("SkyInfo", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
                        .withDepthWrite(false)
                        .withDepthTestFunction(com.mojang.blaze3d.platform.DepthTestFunction.NO_DEPTH_TEST)
                        .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                        .build();
            }
            if (equi == null) {
                equi = new com.mojang.blaze3d.pipeline.TextureTarget("Paranoia ciel", 2048, 1024, false);
                mc.getTextureManager().register(EQUI_TEX, new TargetTexture(equi));
            }
            com.mojang.blaze3d.buffers.GpuBuffer buf;
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                var bb = com.mojang.blaze3d.buffers.Std140Builder.onStack(stack, 16).putVec4(secs, mode, 0, 0).get();
                buf = com.mojang.blaze3d.systems.RenderSystem.getDevice().createBuffer(() -> "Paranoia ciel",
                        com.mojang.blaze3d.buffers.GpuBuffer.USAGE_UNIFORM, bb);
            }
            try (var rp = com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder()
                    .createRenderPass(() -> "Paranoia ciel", equi.getColorTextureView(), java.util.OptionalInt.empty())) {
                rp.setPipeline(equiPipe);
                rp.setUniform("SkyInfo", buf);
                rp.draw(0, 3);
            }
            buf.close();
            equiReady = true;
        } catch (RuntimeException e) {
            equiFailed = true;
            gg.aurora.AuroraClient.LOGGER.error("Ciel en texture (packs de shaders) désactivé", e);
        }
    }

    public static RenderType texType() {
        if (texType == null) {
            texPipe = RenderPipeline.builder(RenderPipelinesAccessor.aurora$matricesFog())
                    .withLocation(Identifier.fromNamespaceAndPath("aurora", "pipeline/sky_tex"))
                    .withVertexShader("core/position_tex_color")
                    .withFragmentShader("core/position_tex_color")
                    .withSampler("Sampler0")
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .withDepthWrite(false)
                    .withCull(false)
                    .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
                    .build();
            IrisCompat.assign(texPipe, "SKY_TEXTURED");
            texType = RenderTypeInvoker.aurora$create("aurora_sky_tex",
                    RenderSetup.builder(texPipe).withTexture("Sampler0", EQUI_TEX).createRenderSetup());
        }
        return texType;
    }

    // ------------------------------------------------------------------ user sky image

    private static final java.nio.file.Path SKIES = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("aurora").resolve("skies");
    private static final Identifier USER_TEX = Identifier.fromNamespaceAndPath("aurora", "dynamic/sky_user");
    private static RenderType userType;
    private static String loaded = "";
    private static boolean userReady;

    private static java.util.List<String> images() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            java.nio.file.Files.createDirectories(SKIES);
            try (var s = java.nio.file.Files.list(SKIES)) {
                s.forEach(p -> {
                    String n = p.getFileName().toString();
                    if (n.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) out.add(n);
                });
            }
        } catch (java.io.IOException ignored) {
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public static void nextImage() {
        var all = images();
        if (all.isEmpty()) {
            gg.aurora.ui.Toasts.push(gg.aurora.ui.Icons.INFO, "Custom Sky", "No PNG in config/aurora/skies yet", false);
            return;
        }
        gg.aurora.Settings.skyImage = all.get((all.indexOf(gg.aurora.Settings.skyImage) + 1) % all.size());
        loaded = "";
        gg.aurora.module.WorldModules.CUSTOM_SKY.set("ambience", 8);
    }

    public static String imageName() {
        return gg.aurora.Settings.skyImage.isEmpty() ? "None" : gg.aurora.Settings.skyImage;
    }

    public static void openFolder() {
        images();
        net.minecraft.util.Util.getPlatform().openPath(SKIES);
    }

    /** Sky from the user's panorama, or null if there is none to draw. */
    public static RenderType userType(net.minecraft.client.Minecraft mc) {
        String name = gg.aurora.Settings.skyImage;
        if (name.isEmpty()) return null;
        if (!name.equals(loaded)) {
            loaded = name;
            userReady = false;
            java.nio.file.Path p = SKIES.resolve(name);
            if (p.normalize().startsWith(SKIES.normalize()) && java.nio.file.Files.exists(p)) {
                try (var in = java.nio.file.Files.newInputStream(p)) {
                    var img = com.mojang.blaze3d.platform.NativeImage.read(in);
                    mc.getTextureManager().register(USER_TEX, new net.minecraft.client.renderer.texture.DynamicTexture(() -> "Paranoia sky", img));
                    userReady = true;
                } catch (java.io.IOException | RuntimeException e) {
                    gg.aurora.AuroraClient.LOGGER.warn("Sky image unreadable: {}", name);
                }
            }
        }
        if (!userReady) return null;
        if (userType == null) {
            texType();
            userType = RenderTypeInvoker.aurora$create("aurora_sky_user", RenderSetup.builder(texPipe).withTexture("Sampler0", USER_TEX).createRenderSetup());
        }
        return userType;
    }

    /** Sphère texturée par le ciel équirectangulaire (mêmes directions que le shader). */
    public static void sphereTex(VertexConsumer vc, float r, int alpha) {
        float[] u = unit();
        float[] uv = UNIT_UV;
        for (int i = 0, j = 0; i < u.length; i += 3, j += 2) {
            vc.addVertex(u[i] * r, u[i + 1] * r, u[i + 2] * r).setUv(uv[j], uv[j + 1]).setColor(255, 255, 255, alpha);
        }
    }

    private static final class TargetTexture extends net.minecraft.client.renderer.texture.AbstractTexture {
        TargetTexture(com.mojang.blaze3d.pipeline.TextureTarget t) {
            texture = t.getColorTexture();
            textureView = t.getColorTextureView();
            sampler = com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getClampToEdge(com.mojang.blaze3d.textures.FilterMode.LINEAR);
        }

        @Override
        public void close() {
        }
    }

    /** Sphère de rayon r (32 × 16 quads) ; le détail vient du shader, pas de la géométrie. */
    public static void sphere(VertexConsumer vc, float r, float time, int mode, int alpha) {
        if (legacySphere) {
            float[] u = unit();
            for (int i = 0; i < u.length; i += 3) vc.addVertex(u[i] * r, u[i + 1] * r, u[i + 2] * r).setUv(time, mode).setColor(255, 255, 255, alpha);
            return;
        }
        // Le shader normalise la position pour obtenir la direction de vue, pixel par pixel : un cube autour
        // de la caméra donne exactement le même ciel qu'une sphère, avec 24 sommets au lieu de 2 048.
        for (int i = 0; i < CUBE.length; i += 3) vc.addVertex(CUBE[i] * r, CUBE[i + 1] * r, CUBE[i + 2] * r).setUv(time, mode).setColor(255, 255, 255, alpha);
    }

    /** Dev : ancienne sphère, pour comparer les deux rendus. */
    public static boolean legacySphere;

    /** 6 faces (quads) d'un cube unité centré sur la caméra. */
    private static final float[] CUBE = {
            -1, -1, -1, 1, -1, -1, 1, -1, 1, -1, -1, 1,
            -1, 1, -1, -1, 1, 1, 1, 1, 1, 1, 1, -1,
            -1, -1, -1, -1, 1, -1, 1, 1, -1, 1, -1, -1,
            -1, -1, 1, 1, -1, 1, 1, 1, 1, -1, 1, 1,
            -1, -1, -1, -1, -1, 1, -1, 1, 1, -1, 1, -1,
            1, -1, -1, 1, 1, -1, 1, 1, 1, 1, -1, 1};

    private static float[] UNIT, UNIT_UV;

    private static float[] unit() {
        if (UNIT != null) return UNIT;
        final int seg = 24, rings = 12; // texture at infinity: 1152 vertices are enough (was 2048)
        float[] out = new float[seg * rings * 4 * 3];
        UNIT_UV = new float[seg * rings * 4 * 2];
        int k = 0, t = 0;
        for (int i = 0; i < seg; i++) {
            for (int j = 0; j < rings; j++) {
                float u0 = i / (float) seg, u1 = (i + 1) / (float) seg, v0 = j / (float) rings, v1 = (j + 1) / (float) rings;
                float[][] q = {{u0, v0}, {u0, v1}, {u1, v1}, {u1, v0}};
                for (float[] p : q) {
                    UNIT_UV[t++] = p[0];
                    UNIT_UV[t++] = p[1];
                    double th = p[0] * Math.PI * 2, ph = p[1] * Math.PI;
                    out[k++] = (float) (Math.sin(ph) * Math.cos(th));
                    out[k++] = (float) Math.cos(ph);
                    out[k++] = (float) (Math.sin(ph) * Math.sin(th));
                }
            }
        }
        return UNIT = out;
    }
}
