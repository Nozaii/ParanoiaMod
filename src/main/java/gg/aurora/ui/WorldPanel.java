package gg.aurora.ui;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import gg.aurora.AuroraClient;
import gg.aurora.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;

/**
 * Menu fixé dans le monde : l'écran du menu (ou la garde-robe, les amis…) est dessiné hors écran
 * dans une texture par un second GuiRenderer, puis affiché sur un panneau placé devant le joueur.
 * On tourne autour, on le vise avec le viseur pour cliquer, clic droit maintenu pour le déplacer,
 * et il se lit des deux côtés. Le HUD et la partie restent normaux.
 */
public final class WorldPanel {
    private WorldPanel() {}

    private static final Identifier TEX = Identifier.fromNamespaceAndPath("aurora", "dynamic/world_panel");
    private static final Identifier WHITE = Identifier.fromNamespaceAndPath("aurora", "dynamic/world_panel_white");
    /** VulkanMod : l'image hors écran est rangée de haut en bas (OpenGL : de bas en haut). */
    private static final boolean FLIP_V = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("vulkanmod");
    /** Épaisseur de la dalle et hauteur du relief du contenu (blocs). */
    private static final float SLAB = 0.05f, LIFT = 0.022f;
    private static boolean whiteReady;
    /** Rayon des coins (unités GUI) du rectangle affiché. */
    private static float cornerGui = 10;
    /** 1 = net, moins = trop près (transparent, plus de clic). */
    private static float fade = 1;

    /** Écran affiché dans le panneau (null = fermé). */
    public static Screen hosted;
    /** Vrai pendant un appel à l'écran hébergé (rendu, clic) : ses setScreen() changent le panneau. */
    public static boolean inHostedCall;
    /** Vrai pendant son rendu : pas de fond plein écran, pas d'animation d'écran. */
    public static boolean rendering;
    /** Cible de rendu hors écran utilisée par le second GuiRenderer (lue par le mixin de GuiRenderer). */
    public static TextureTarget redirect;
    /** Vrai pendant l'ouverture volontaire de l'écran vanilla des packs (pas de détour par l'écran par objet). */
    public static boolean bypassPacks;

    private static TextureTarget target;
    private static GuiRenderState state;
    private static GuiRenderer renderer;
    private static PanelTexture texture;
    private static boolean failed;

    // placement dans le monde
    private static Vec3 center = Vec3.ZERO;
    private static final Vector3f normal = new Vector3f(0, 0, 1);
    private static float worldW = 2.6f, worldH = 1.5f;
    private static boolean moving;
    private static float moveDist = 2.4f;

    // visée
    private static boolean aimed, front = true, leftDown;
    /** Poignée de redimensionnement (coin bas-droit vu par le joueur) : survolée / en cours. */
    private static boolean gripHover, resizing;
    private static Vec3 anchor = Vec3.ZERO;
    private static float resizeSide = 1;
    /** Zone de saisie du coin (blocs) : large pour ne pas le rater. */
    private static final float GRIP = 0.28f;
    private static double mouseX = -1000, mouseY = -1000, hitX, hitY, hitZ;

    public static boolean open() {
        return hosted != null;
    }

    public static boolean aimed() {
        return open() && aimed;
    }

    // ===================================================================== ouverture / fermeture

    /** Ouvre l'écran dans un panneau devant le joueur. */
    public static void show(Screen s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || failed) {
            mc.setScreen(s); // pas de monde : écran normal
            return;
        }
        boolean wasOpen = open();
        host(s);
        if (!wasOpen) placeInFront(mc);
    }

    /** Change l'écran affiché (navigation dans le menu) sans bouger le panneau. */
    public static void host(Screen s) {
        Minecraft mc = Minecraft.getInstance();
        if (hosted != null && hosted != s) callHosted(hosted::removed);
        hosted = s;
        callHosted(() -> s.init(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()));
    }

    public static void close() {
        resizing = false;
        gripHover = false;
        if (hosted != null) callHosted(hosted::removed);
        hosted = null;
        moving = false;
        aimed = false;
        leftDown = false;
    }

    private static void placeInFront(Minecraft mc) {
        var cam = mc.gameRenderer.getMainCamera();
        Vector3f look = cam.rotation().transform(new Vector3f(0, 0, -1));
        Vector3f flat = new Vector3f(look.x, 0, look.z);
        if (flat.lengthSquared() < 1e-4) flat.set(0, 0, -1);
        flat.normalize();
        Vec3 eye = cam.position();
        normal.set(-flat.x, 0, -flat.z);
        center = eye.add(flat.x * 2.4, -0.15, flat.z * 2.4);
        for (double dist = 2.4; dist >= 0.9; dist -= 0.1) {
            Vec3 c = eye.add(flat.x * dist, -0.15, flat.z * dist);
            if (fits(mc, c)) {
                center = c;
                break;
            }
        }
    }

    /** Le panneau ne traverse pas les blocs pleins (dalles, fleurs, vitres… ne gênent pas). */
    private static boolean fits(Minecraft mc, Vec3 c) {
        if (mc.level == null) return true;
        Vector3f r = right();
        var mp = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int i = 0; i <= 6; i++) {
            for (int j = 0; j <= 4; j++) {
                double a = (i / 6.0 - 0.5) * worldW * 0.96, b = (j / 4.0 - 0.5) * worldH * 0.96;
                mp.set(Math.floor(c.x + r.x * a), Math.floor(c.y + b), Math.floor(c.z + r.z * a));
                var st = mc.level.getBlockState(mp);
                if (!st.isAir() && st.isCollisionShapeFullBlock(mc.level, mp)) return false;
            }
        }
        return true;
    }

    private static void callHosted(Runnable r) {
        boolean was = inHostedCall;
        inHostedCall = true;
        try {
            r.run();
        } finally {
            inHostedCall = was;
        }
    }

    // ===================================================================== rendu hors écran

    /** Rectangle utile de l'écran (unités GUI) : le panneau du menu, la fenêtre des sous-pages. */
    private static float[] crop(Screen s, int gw, int gh) {
        if (s instanceof ClickGuiScreen c) return c.panelRect();
        if (s instanceof SubScreen sub) return sub.panelRect();
        return new float[]{0, 0, gw, gh};
    }

    /** Avant le rendu du monde : met à jour la visée et dessine l'écran dans la texture du panneau. */
    public static void renderOffscreen() {
        Minecraft mc = Minecraft.getInstance();
        if (hosted == null || failed) return;
        if (mc.level == null || mc.player == null) {
            close();
            return;
        }
        try {
            var win = mc.getWindow();
            int fw = win.getWidth(), fh = win.getHeight(), gw = win.getGuiScaledWidth(), gh = win.getGuiScaledHeight();
            if (hosted.width != gw || hosted.height != gh) callHosted(() -> hosted.init(gw, gh));
            float[] rect = crop(hosted, gw, gh);
            updateAim(mc, rect);
            if (target == null) target = new TextureTarget("Paranoia menu monde", fw, fh, true);
            else if (target.width != fw || target.height != fh) target.resize(fw, fh);
            if (renderer == null) {
                state = new GuiRenderState();
                var pip = ((gg.aurora.mixin.GuiRendererAccessor) ((gg.aurora.mixin.GameRendererAccessor) mc.gameRenderer).aurora$guiRenderer())
                        .aurora$pictureInPicture();
                renderer = new GuiRenderer(state, mc.renderBuffers().bufferSource(), mc.gameRenderer.getSubmitNodeStorage(),
                        mc.gameRenderer.getFeatureRenderDispatcher(), new ArrayList<>(pip.values()));
            }
            state.reset();
            int mx = (int) Math.floor(mouseX), my = (int) Math.floor(mouseY);
            GuiGraphics g = new GuiGraphics(mc, state, mx, my);
            float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
            rendering = true;
            Screen s = hosted;
            callHosted(() -> s.renderWithTooltipAndSubtitles(g, mx, my, pt));
            rendering = false;
            if (hosted == null) return; // fermé pendant son rendu
            // curseur : point lumineux là où vise le viseur
            if (aimed) {
                Draw.rrect(g, (float) mouseX - 2.5f, (float) mouseY - 2.5f, 5, 5, 2.5f, 0xFFFFFFFF);
                Draw.ring(g, (float) mouseX - 3.5f, (float) mouseY - 3.5f, 7, 7, 3.5f, Draw.withAlpha(Theme.accent(), 0.9f));
            }
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(), 0, target.getDepthTexture(), 1.0);
            redirect = target;
            try {
                renderer.render(((gg.aurora.mixin.GameRendererAccessor) mc.gameRenderer).aurora$fogRenderer().getBuffer(FogRenderer.FogMode.NONE));
                renderer.incrementFrameNumber();
            } finally {
                redirect = null;
            }
            if (texture == null) {
                texture = new PanelTexture();
                mc.getTextureManager().register(TEX, texture);
            }
            texture.set(target.getColorTexture(), target.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            float scale = (float) win.getGuiScale();
            u0 = rect[0] * scale / fw;
            u1 = (rect[0] + rect[2]) * scale / fw;
            vTop = 1 - rect[1] * scale / fh;
            vBottom = 1 - (rect[1] + rect[3]) * scale / fh;
            if (FLIP_V) {
                vTop = 1 - vTop;
                vBottom = 1 - vBottom;
            }
            cornerGui = rect.length > 4 ? rect[4] : 10;
            guiW = rect[2];
            worldW = (float) (2.6 * Settings.worldMenuScale);
            worldH = worldW * rect[3] / Math.max(1, rect[2]);
        } catch (RuntimeException e) {
            rendering = false;
            redirect = null;
            failed = true;
            AuroraClient.LOGGER.error("Menu dans le monde désactivé après une erreur (menu écran à la place)", e);
            Screen s = hosted;
            hosted = null;
            mc.setScreen(s);
        }
    }

    private static float u0, u1 = 1, vTop = 1, vBottom, guiW = 560;

    private static Vector3f right() {
        // droite du panneau vue de face : look × haut, avec look = -normale
        return new Vector3f(-normal.x, 0, -normal.z).cross(0, 1, 0).normalize();
    }

    private static void updateAim(Minecraft mc, float[] rect) {
        var cam = mc.gameRenderer.getMainCamera();
        Vec3 e = cam.position();
        Vector3f d = cam.rotation().transform(new Vector3f(0, 0, -1));
        if (moving) {
            Vector3f flat = new Vector3f(d.x, 0, d.z);
            if (flat.lengthSquared() > 1e-4) {
                flat.normalize();
                normal.set(-flat.x, 0, -flat.z);
            }
            for (double dist = moveDist; dist >= 0.6; dist -= 0.1) {
                Vec3 c = e.add(d.x * dist, d.y * dist, d.z * dist);
                if (fits(mc, c)) {
                    center = c;
                    break;
                }
            }
        }
        fade = nearFade(e);
        aimed = false;
        gripHover = false;
        mouseX = mouseY = -1000;
        Vector3f r = right();
        double cx = center.x - e.x, cy = center.y - e.y, cz = center.z - e.z;
        front = -(cx * normal.x + cy * normal.y + cz * normal.z) > 0; // caméra du côté de la normale
        double denom = d.x * normal.x + d.y * normal.y + d.z * normal.z;
        if (Math.abs(denom) < 1e-5) return;
        double t = (cx * normal.x + cy * normal.y + cz * normal.z) / denom;
        if (t <= 0 || t > 12) return;
        hitX = e.x + d.x * t;
        hitY = e.y + d.y * t;
        hitZ = e.z + d.z * t;
        double lx = hitX - center.x, ly = hitY - center.y, lz = hitZ - center.z;
        double a = lx * r.x + ly * r.y + lz * r.z, b = ly;
        if (resizing) {
            // coin opposé fixe : la largeur suit le viseur dans le plan figé du panneau, sans glisser
            double ax = hitX - anchor.x, az = hitZ - anchor.z;
            double w = (ax * r.x + az * r.z) * resizeSide;
            double h = anchor.y - hitY;
            double aspect = worldH / Math.max(0.01, worldW);
            double nw = Math.max(1.0, Math.min(6.0, Math.max(w, h / Math.max(0.05, aspect))));
            Settings.worldMenuScale = nw / 2.6;
            worldW = (float) nw;
            worldH = (float) (nw * aspect);
            center = anchor.add(r.x * resizeSide * worldW / 2, -worldH / 2, r.z * resizeSide * worldW / 2);
            gripHover = true;
            return;
        }
        // coin bas-droit tel que vu par le joueur (de dos, l'axe horizontal est inversé)
        float side = front ? 1 : -1;
        double ga = a * side;
        if (fade >= 0.98f && !moving && ga > worldW / 2 - GRIP && ga < worldW / 2 + GRIP * 0.5
                && b < -worldH / 2 + GRIP && b > -worldH / 2 - GRIP * 0.5) {
            gripHover = true;
            return;
        }
        if (Math.abs(a) > worldW / 2 || Math.abs(b) > worldH / 2) return;
        if (fade < 0.98f && !moving) return; // trop près : on ne clique plus
        double u = front ? 0.5 + a / worldW : 0.5 - a / worldW, v = 0.5 - b / worldH;
        aimed = true;
        mouseX = rect[0] + u * rect[2];
        mouseY = rect[1] + v * rect[3];
        if (leftDown) {
            MouseButtonEvent ev = new MouseButtonEvent(mouseX, mouseY, new MouseButtonInfo(0, 0));
            callHosted(() -> hosted.mouseDragged(ev, 0, 0));
        }
    }

    // ===================================================================== rendu dans le monde

    /** Distance de l'oeil au point le plus proche du panneau : 1 net, jusqu'à 0,2 tout près. */
    private static float nearFade(Vec3 e) {
        Vector3f r = right();
        double cx = e.x - center.x, cy = e.y - center.y, cz = e.z - center.z;
        double a = Math.max(-worldW / 2, Math.min(worldW / 2, cx * r.x + cz * r.z));
        double b = Math.max(-worldH / 2, Math.min(worldH / 2, cy));
        double px = center.x + r.x * a - e.x, py = center.y + b - e.y, pz = center.z + r.z * a - e.z;
        double dist = Math.sqrt(px * px + py * py + pz * pz);
        double t = Math.max(0, Math.min(1, (dist - 0.45) / 0.45));
        return (float) (0.2 + 0.8 * t * t * (3 - 2 * t));
    }

    private static void ensureWhite(Minecraft mc) {
        if (whiteReady) return;
        whiteReady = true;
        var img = new com.mojang.blaze3d.platform.NativeImage(4, 4, false);
        img.fillRect(0, 0, 4, 4, 0xFFFFFFFF);
        mc.getTextureManager().register(WHITE, new net.minecraft.client.renderer.texture.DynamicTexture(() -> "Paranoia panneau", img));
    }

    /**
     * Dalle opaque (avec épaisseur et tranche colorée), ombre du contenu posée dessus, puis le contenu
     * légèrement surélevé : en tournant autour, les boutons gardent du relief. La dalle est opaque,
     * donc rien (flaques, eau, nuages) ne se voit à travers, même avec un pack de shaders.
     */
    public static void renderWorld(PoseStack pose, MultiBufferSource consumers, Vec3 cam) {
        if (hosted == null || texture == null || consumers == null) return;
        Minecraft mc = Minecraft.getInstance();
        ensureWhite(mc);
        var p = pose.last();
        Vector3f r = right();
        float sx = front ? 1 : -1; // vu de dos : axe horizontal retourné pour lire le texte à l'endroit
        float nx = front ? normal.x : -normal.x, nz = front ? normal.z : -normal.z; // vers la caméra
        float hw = worldW / 2, hh = worldH / 2;
        float ox = (float) (center.x - cam.x), oy = (float) (center.y - cam.y), oz = (float) (center.z - cam.z);
        float rad = Math.min(Math.min(hw, hh) * 0.5f, cornerGui / Math.max(1f, guiW) * worldW);
        int alpha = Math.round(255 * fade);
        boolean solid = fade >= 0.999f;

        // ---- dalle
        var slabType = solid ? RenderTypes.entityCutoutNoCull(WHITE) : RenderTypes.entityTranslucent(WHITE);
        VertexConsumer vc = consumers.getBuffer(slabType);
        int face = (alpha << 24) | (Theme.PANEL & 0xFFFFFF);
        int rim = (alpha << 24) | (Draw.lerpColor(Theme.PANEL, Theme.accent(), 0.45f) & 0xFFFFFF);
        float[] ring = outline(hw, hh, rad);
        int n = ring.length / 2;
        float fT = SLAB / 2, bT = -SLAB / 2;
        for (int i = 0; i < n; i++) {
            int k = (i + 1) % n;
            float a0 = ring[i * 2], b0 = ring[i * 2 + 1], a1 = ring[k * 2], b1 = ring[k * 2 + 1];
            // faces avant / arrière (éventail depuis le centre, triangle répété en quad)
            v(vc, p, ox, oy, oz, r, nx, nz, 0, 0, fT, face);
            v(vc, p, ox, oy, oz, r, nx, nz, a0, b0, fT, face);
            v(vc, p, ox, oy, oz, r, nx, nz, a1, b1, fT, face);
            v(vc, p, ox, oy, oz, r, nx, nz, a1, b1, fT, face);
            v(vc, p, ox, oy, oz, r, nx, nz, 0, 0, bT, face);
            v(vc, p, ox, oy, oz, r, nx, nz, a1, b1, bT, face);
            v(vc, p, ox, oy, oz, r, nx, nz, a0, b0, bT, face);
            v(vc, p, ox, oy, oz, r, nx, nz, a0, b0, bT, face);
            // tranche
            v(vc, p, ox, oy, oz, r, nx, nz, a0, b0, fT, rim);
            v(vc, p, ox, oy, oz, r, nx, nz, a0, b0, bT, rim);
            v(vc, p, ox, oy, oz, r, nx, nz, a1, b1, bT, rim);
            v(vc, p, ox, oy, oz, r, nx, nz, a1, b1, fT, rim);
        }
        if (consumers instanceof MultiBufferSource.BufferSource bs) bs.endBatch(slabType);

        // ---- ombre du contenu, posée sur la dalle (décalée vers le bas)
        var shadowType = RenderTypes.entityTranslucent(TEX);
        vc = consumers.getBuffer(shadowType);
        int shadow = Math.round(110 * fade) << 24;
        texQuad(vc, p, ox, oy, oz, r, nx, nz, sx, hw, hh, fT + 0.003f, -worldH * 0.008f, shadow);
        if (consumers instanceof MultiBufferSource.BufferSource bs) bs.endBatch(shadowType);

        // ---- poignée de redimensionnement : coin bas-droit (vu par le joueur), en L, plus vive au survol
        int gc = (alpha << 24) | ((gripHover ? Theme.accent() : Draw.lerpColor(Theme.PANEL, 0xFFFFFF, 0.35f)) & 0xFFFFFF);
        var gripType = RenderTypes.entityCutoutNoCull(WHITE);
        vc = consumers.getBuffer(gripType);
        float gs = gripHover ? 0.22f : 0.16f, gt = 0.025f, gz = fT + 0.004f;
        float ex = (hw - 0.06f) * sx, ey = -hh + 0.06f; // coin, en retrait de l'arrondi
        float dx = -sx; // vers l'intérieur
        v(vc, p, ox, oy, oz, r, nx, nz, ex + dx * gs, ey + gt, gz, gc);
        v(vc, p, ox, oy, oz, r, nx, nz, ex, ey + gt, gz, gc);
        v(vc, p, ox, oy, oz, r, nx, nz, ex, ey, gz, gc);
        v(vc, p, ox, oy, oz, r, nx, nz, ex + dx * gs, ey, gz, gc);
        v(vc, p, ox, oy, oz, r, nx, nz, ex + dx * gt, ey + gs, gz, gc);
        v(vc, p, ox, oy, oz, r, nx, nz, ex, ey + gs, gz, gc);
        v(vc, p, ox, oy, oz, r, nx, nz, ex, ey, gz, gc);
        v(vc, p, ox, oy, oz, r, nx, nz, ex + dx * gt, ey, gz, gc);
        if (consumers instanceof MultiBufferSource.BufferSource bs) bs.endBatch(gripType);

        // ---- contenu surélevé
        var type = RenderTypes.entityTranslucentEmissive(TEX);
        vc = consumers.getBuffer(type);
        texQuad(vc, p, ox, oy, oz, r, nx, nz, sx, hw, hh, fT + LIFT, 0, (alpha << 24) | 0xFFFFFF);
        if (consumers instanceof MultiBufferSource.BufferSource bs) bs.endBatch(type);
    }

    /** Contour en rectangle arrondi (coordonnées du panneau : a horizontal, b vertical). */
    private static float[] outline(float hw, float hh, float rad) {
        final int seg = 5;
        float[] out = new float[4 * (seg + 1) * 2];
        int o = 0;
        float[][] cs = {{hw - rad, hh - rad, 0}, {-hw + rad, hh - rad, 90}, {-hw + rad, -hh + rad, 180}, {hw - rad, -hh + rad, 270}};
        for (float[] c : cs) {
            for (int s = 0; s <= seg; s++) {
                double ang = Math.toRadians(c[2] + 90.0 * s / seg);
                out[o++] = c[0] + (float) Math.cos(ang) * rad;
                out[o++] = c[1] + (float) Math.sin(ang) * rad;
            }
        }
        return out;
    }

    /** Sommet de la dalle : a/b dans le plan, depth le long de la normale tournée vers la caméra. */
    private static void v(VertexConsumer vc, PoseStack.Pose p, float ox, float oy, float oz, Vector3f r, float nx, float nz,
                          float a, float b, float depth, int argb) {
        vc.addVertex(p, ox + r.x * a + nx * depth, oy + b, oz + r.z * a + nz * depth).setColor(argb).setUv(0.5f, 0.5f)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(p, 0, 1, 0);
    }

    private static void texQuad(VertexConsumer vc, PoseStack.Pose p, float ox, float oy, float oz, Vector3f r, float nx, float nz,
                                float sx, float hw, float hh, float depth, float dy, int argb) {
        float x = ox + nx * depth, y = oy + dy, z = oz + nz * depth, w = hw * sx;
        corner(vc, p, x, y, z, r, -w, hh, u0, vTop, nx, nz, argb);
        corner(vc, p, x, y, z, r, -w, -hh, u0, vBottom, nx, nz, argb);
        corner(vc, p, x, y, z, r, w, -hh, u1, vBottom, nx, nz, argb);
        corner(vc, p, x, y, z, r, w, hh, u1, vTop, nx, nz, argb);
    }

    private static void corner(VertexConsumer vc, PoseStack.Pose p, float ox, float oy, float oz, Vector3f r, float a, float b,
                               float u, float v, float nx, float nz, int argb) {
        vc.addVertex(p, ox + r.x * a, oy + b, oz + r.z * a).setColor(argb).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(0xF000F0).setNormal(p, nx, 0, nz);
    }

    // ===================================================================== entrées

    /** Clic de souris en jeu : vrai si le panneau l'a pris. */
    public static boolean button(MouseButtonInfo info, int action) {
        Minecraft mc = Minecraft.getInstance();
        if (hosted == null || mc.screen != null) return false;
        int b = info.button();
        if (b == 1) {
            if (action == 1 && aimed) {
                moving = true;
                var cam = mc.gameRenderer.getMainCamera().position();
                moveDist = (float) Math.max(1.2, Math.min(6, Math.sqrt((hitX - cam.x) * (hitX - cam.x) + (hitY - cam.y) * (hitY - cam.y)
                        + (hitZ - cam.z) * (hitZ - cam.z))));
                return true;
            }
            if (action == 0 && moving) {
                moving = false;
                return true;
            }
            return false;
        }
        if (b != 0) return false;
        if (action == 1 && gripHover && !resizing) {
            // ancre = coin haut-gauche vu par le joueur
            resizing = true;
            resizeSide = front ? 1 : -1;
            Vector3f r = right();
            anchor = center.add(-r.x * resizeSide * worldW / 2, worldH / 2, -r.z * resizeSide * worldW / 2);
            return true;
        }
        if (action == 0 && resizing) {
            resizing = false;
            gg.aurora.Config.save();
            return true;
        }
        if (action == 1) {
            if (!aimed) return false;
            leftDown = true;
            MouseButtonEvent ev = new MouseButtonEvent(mouseX, mouseY, info);
            callHosted(() -> hosted.mouseClicked(ev, false));
            if (hosted instanceof ClickGuiScreen c && c.wantsKeyboard() && mc.screen == null) mc.setScreen(new WorldTypingScreen());
            return true;
        }
        if (action == 0 && leftDown) {
            leftDown = false;
            MouseButtonEvent ev = new MouseButtonEvent(mouseX, mouseY, info);
            if (hosted != null) callHosted(() -> hosted.mouseReleased(ev));
            return true;
        }
        return false;
    }

    /** Molette : défile dans le panneau visé, ou règle la distance pendant le déplacement. */
    public static boolean scroll(double dy) {
        Minecraft mc = Minecraft.getInstance();
        if (hosted == null || mc.screen != null) return false;
        if (moving) {
            moveDist = (float) Math.max(1.2, Math.min(6, moveDist + dy * 0.25));
            return true;
        }
        if (!aimed) return false;
        callHosted(() -> hosted.mouseScrolled(mouseX, mouseY, 0, dy));
        return true;
    }

    public static void tick() {
        if (hosted != null) callHosted(hosted::tick);
    }

    /**
     * setScreen() : en mode « menu dans le monde », les écrans du menu vont dans le panneau.
     * Renvoie vrai si l'appel est pris en charge (à annuler).
     */
    public static boolean intercept(Screen s) {
        Minecraft mc = Minecraft.getInstance();
        // Options › Controls › Key Binds: visual keyboard first (classic list one click away)
        if (!bypassPacks && Settings.visualKeyboard && s instanceof net.minecraft.client.gui.screens.options.controls.KeyBindsScreen
                && !(mc.screen instanceof KeyboardScreen)) {
            bypassPacks = true;
            try {
                mc.setScreen(new KeyboardScreen(mc.screen, s));
            } finally {
                bypassPacks = false;
            }
            return true;
        }
        // Options › Packs de ressources : écran par objet d'abord
        if (!bypassPacks && Settings.itemPackMenu && s instanceof net.minecraft.client.gui.screens.packs.PackSelectionScreen
                && mc.screen instanceof net.minecraft.client.gui.screens.options.OptionsScreen opts) {
            bypassPacks = true;
            try {
                mc.setScreen(new ItemPacksScreen(opts, s));
            } finally {
                bypassPacks = false;
            }
            return true;
        }
        if (inHostedCall) {
            if (s == null) {
                close();
                return true;
            }
            if (s instanceof ClickGuiScreen || s instanceof SubScreen) {
                host(s);
                return true;
            }
            if (s instanceof HudEditScreen) close(); // l'éditeur de HUD reste un écran normal
            return false;
        }
        if (!Settings.worldMenu || failed || mc.level == null) return false;
        if (s instanceof ClickGuiScreen && hosted == null && mc.screen == null) {
            show(s);
            return true;
        }
        if (hosted != null && s instanceof net.minecraft.client.gui.screens.PauseScreen && mc.screen == null) {
            close(); // Échap ferme le panneau
            return true;
        }
        return false;
    }

    /** Texture du panneau : pointe sur la cible de rendu hors écran (rien à charger ni libérer). */
    private static final class PanelTexture extends AbstractTexture {
        void set(GpuTexture t, GpuTextureView v, GpuSampler s) {
            texture = t;
            textureView = v;
            sampler = s;
        }

        @Override
        public void close() {
            // la cible appartient au panneau
        }
    }
}
