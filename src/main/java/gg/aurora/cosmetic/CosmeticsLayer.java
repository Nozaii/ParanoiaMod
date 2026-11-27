package gg.aurora.cosmetic;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.joml.Vector3f;

import static gg.aurora.cosmetic.Mesh.argb;
import static gg.aurora.cosmetic.Mesh.lerp;
import static gg.aurora.cosmetic.Mesh.shade;
import static gg.aurora.cosmetic.Mesh.v;

/**
 * Cosmétiques en 3D procédurale : ailes à plumes / membranes / papillon articulées et animées,
 * halos en tore avec cristaux, auras d'énergie (champ, rubans hélicoïdaux, éclats lumineux).
 */
public class CosmeticsLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    /** Identifiant du joueur affiché dans le menu principal : rendu sans cosmétiques. */
    public static final int PREVIEW_ID = -1729;
    private static final int FULL_BRIGHT = 0xF000F0;
    private static final Identifier WHITE = Identifier.fromNamespaceAndPath("aurora", "textures/cosmetic/white.png");
    private static final Identifier GLOW_TEX = Identifier.fromNamespaceAndPath("aurora", "textures/ui/glow.png");
    private static final Identifier BUTTERFLY = Identifier.fromNamespaceAndPath("aurora", "textures/cosmetic/butterfly_wing.png");

    /**
     * Ailes de monarque : une texture d'aile par côté (antérieure + postérieure), qui bat en tournant
     * autour de la colonne, avec un léger planement entre deux battements.
     */
    private static void butterfly(PoseStack.Pose base, VertexConsumer vc, int light, float t, float crouch) {
        PoseStack ps = new PoseStack();
        ps.last().set(base);
        float cycle = (t * 0.18f) % 6.2832f;
        // battements rapides puis planement
        float beat = Mesh.sin(t * 0.42f); // t ralenti en sneak (temps d'animation)
        float glide = 0.5f + 0.5f * Mesh.sin(t * 0.05f);
        float open = 18 + 52 * (0.5f + 0.5f * beat) * (0.55f + 0.45f * glide);
        for (int side = -1; side <= 1; side += 2) {
            ps.pushPose();
            ps.translate(side * 0.03f, 0.22f, 0.15f);
            ps.scale(side, 1, 1);
            ps.mulPose(Axis.YP.rotationDegrees(-open));
            ps.mulPose(Axis.ZP.rotationDegrees(-12));
            PoseStack.Pose pp = ps.last();
            float w = 0.95f, top = -0.55f, bot = 0.42f;
            int col = 0xFFFFFFFF;
            // la texture est l'aile droite : le côté gauche la lit en miroir via l'échelle négative
            wingVertex(vc, pp, 0, top, 0, 0.02f, 0.0f, col, light);
            wingVertex(vc, pp, 0, bot, 0, 0.02f, 1.0f, col, light);
            wingVertex(vc, pp, w, bot, 0, 1.0f, 1.0f, col, light);
            wingVertex(vc, pp, w, top, 0, 1.0f, 0.0f, col, light);
            ps.popPose();
        }
        // corps : thorax et abdomen fins
        ps.pushPose();
        ps.translate(0, 0.0f, 0.16f);
        PoseStack.Pose pp = ps.last();
        int body = 0xFF15100C;
        wingVertex(vc, pp, -0.025f, -0.12f, 0, 0.0f, 0.47f, body, light);
        wingVertex(vc, pp, -0.025f, 0.38f, 0, 0.0f, 0.47f, body, light);
        wingVertex(vc, pp, 0.025f, 0.38f, 0, 0.0f, 0.47f, body, light);
        wingVertex(vc, pp, 0.025f, -0.12f, 0, 0.0f, 0.47f, body, light);
        ps.popPose();
    }

    private static void wingVertex(VertexConsumer vc, PoseStack.Pose p, float x, float y, float z, float u, float v, int col, int light) {
        vc.addVertex(p, x, y, z).setColor(col).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0, 0, -1);
    }

    private enum Pass { SOLID, MEMBRANE, GLOW, BLOOM }

    private enum Kind { FEATHER, MEMBRANE, BUTTERFLY, BLADE }

    private static final Pass[] WING_SOLID = {Pass.SOLID}, WING_GLOW = {Pass.SOLID, Pass.GLOW},
            WING_MEMBRANE = {Pass.SOLID, Pass.MEMBRANE}, WING_MEMBRANE_GLOW = {Pass.SOLID, Pass.MEMBRANE, Pass.GLOW},
            HALO_ALL = {Pass.SOLID, Pass.MEMBRANE, Pass.GLOW, Pass.BLOOM}, HALO_SOLID = {Pass.SOLID, Pass.MEMBRANE},
            AURA_FX = {Pass.GLOW, Pass.BLOOM};
    private static final RenderType[] TYPES = new RenderType[Pass.values().length];

    private record WingStyle(Kind kind, int bone, int base, int tip, int covert, int glow, float len) {}

    public CosmeticsLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
        super(parent);
    }

    private static RenderType type(Pass p) {
        RenderType r = TYPES[p.ordinal()];
        if (r == null) TYPES[p.ordinal()] = r = createType(p);
        return r;
    }

    private static RenderType createType(Pass p) {
        return switch (p) {
            case SOLID -> RenderTypes.entityCutoutNoCull(WHITE);
            case MEMBRANE -> RenderTypes.entityTranslucent(WHITE);
            // lumière additive : les effets s'ajoutent à la scène comme une vraie source lumineuse
            case GLOW -> gg.aurora.render.FxRender.additive(WHITE);
            case BLOOM -> gg.aurora.render.FxRender.additive(GLOW_TEX);
        };
    }

    /** Niveau de détail courant (0 = proche, 1 = moyen : sans passes lumineuses ni petites plumes). */
    private static int lod;
    private static final double LOD1 = 24 * 24, CULL = 72 * 72;

    private interface Builder {
        void build(Mesh m, Pass pass);
    }

    private static void submit(PoseStack pose, SubmitNodeCollector collector, int light, Builder b, Pass... passes) {
        for (Pass pass : passes) {
            int l = pass == Pass.GLOW || pass == Pass.BLOOM ? FULL_BRIGHT : light;
            boolean add = pass == Pass.GLOW || pass == Pass.BLOOM;
            collector.submitCustomGeometry(pose, type(pass), (p, vc) -> {
                Mesh m = Mesh.shared(p, vc, l);
                if (add) m.additive();
                b.build(m, pass);
            });
        }
    }

    @Override
    public void submit(PoseStack pose, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot) {
        long profT = gg.aurora.Prof.start();
        try {
            submit0(pose, collector, light, state, yRot, xRot);
        } finally {
            gg.aurora.Prof.stop(gg.aurora.Prof.PLAYER_COSMETICS, profT);
        }
    }

    private void submit0(PoseStack pose, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot) {
        // passe de silhouette du Friend Glow : seuls le joueur et son équipement comptent, pas les cosmétiques
        if (state.isInvisible || gg.aurora.render.FriendGlow.active) return;
        Cosmetics.Item wings, aura, halo;
        if (state.id == PREVIEW_ID) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        // familier « Mini skin » : ses propres cosmétiques, aucun effet de module
        MiniSkinPet.Mini mini = MiniSkinPet.byId(state.id);
        Entity e = mini != null ? mini.owner() : mc.level.getEntity(state.id);
        if (!(e instanceof Player player)) return;
        wings = mini != null ? mini.cosmetic(Cosmetics.Type.WINGS) : Cosmetics.worn(player, Cosmetics.Type.WINGS);
        aura = mini != null ? null : Cosmetics.worn(player, Cosmetics.Type.AURA);
        halo = mini != null ? mini.cosmetic(Cosmetics.Type.HALO) : Cosmetics.worn(player, Cosmetics.Type.HALO);
        double d2 = state.distanceToCameraSq;
        boolean self = mini == null && player == mc.player;
        // qualité : Haute = tout ; Équilibrée = détails réduits pour les autres au-delà de 12 blocs ;
        // Performance = détails réduits partout, effets coupés plus tôt
        int q = gg.aurora.Settings.cosmeticQuality;
        if (d2 > (q == 2 ? 40 * 40 : q == 1 ? 64 * 64 : CULL)) return;
        int level = q == 2 ? 1 : d2 > (q == 1 && !self ? 12 * 12 : LOD1) ? 1 : 0;
        float t = state.ageInTicks;
        // sneak : transition douce entre les poses, et battements ralentis sans saut de phase
        float[] anim = anim(mini != null ? state.id : player.getId(), state.isCrouching, t);
        float crouch = anim[0], wt = anim[1];

        // Sol miroir : copie du modèle retournée sous le plan du sol, translucide, dessinée avant le joueur
        var mirrorMod = gg.aurora.module.VisualModules.MIRROR;
        if (mini == null && mirrorMod.enabled && mirrorMod.flag("players", false) && state.skin != null && (self || mirrorMod.flag("others", true))
                && d2 < mirrorMod.opt("dist", 32) * mirrorMod.opt("dist", 32)) {
            double ground = gg.aurora.module.VisualModules.groundBelow(player);
            var cam = mc.gameRenderer.getMainCamera().position();
            if (!Double.isNaN(ground) && cam.y > ground + 0.05) {
                float lift = (float) (player.getY() - ground);
                int tint = (int) mirrorMod.opt("tint", 0xC8D4FF);
                float fade = Math.max(0, 1 - lift / 2.5f);
                int a = Math.round(255 * (float) mirrorMod.opt("alpha", 0.38) * fade);
                if (a > 4) {
                    pose.pushPose();
                    // espace du modèle : Y vers le bas, pieds à 1,501, échelle du joueur 0,9375
                    pose.translate(0, 2 * (1.501f + lift / 0.9375f), 0);
                    pose.scale(1, -1, 1);
                    collector.order(-1).submitModel(getParentModel(), state, pose, gg.aurora.render.FxRender.mirror(state.skin.body().texturePath()),
                            light, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, (a << 24) | tint, null, 0, null);
                    pose.popPose();
                }
            }
        }
        // Hologram Skin (ou aura Spectre) : copie émissive teintée du modèle par-dessus le skin
        boolean holo = mini == null && (gg.aurora.module.ExtraModules.HOLOGRAM.enabled && (self || gg.aurora.module.ExtraModules.HOLOGRAM.flag("others", false))
                || aura != null && aura.id().equals("spectre"));
        if (holo && state.skin != null) {
            int hc = (int) gg.aurora.module.ExtraModules.HOLOGRAM.opt("color", 0x4FA8FF);
            float flick = 0.8f + 0.2f * Mesh.sin(t * 0.7f) * Mesh.sin(t * 0.23f);
            // teinte claire et peu opaque : le skin reste lisible dessous, effet hologramme
            int col = (Math.round(185 * flick) << 24) | (hc & 0xFFFFFF);
            collector.submitModel(getParentModel(), state, pose, RenderTypes.entityTranslucentEmissive(state.skin.body().texturePath()),
                    FULL_BRIGHT, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, col, null, 0, null);
        }
        if (self && gg.aurora.module.ExtraModules.BATS.enabled && level == 0) {
            pose.pushPose();
            submit(pose, collector, light, (m, pass) -> bats(m, pass, t), WING_GLOW);
            pose.popPose();
        }

        if (wings != null && style(wings.id()).kind() == Kind.BUTTERFLY) {
            pose.pushPose();
            getParentModel().body.translateAndRotate(pose);
            collector.submitCustomGeometry(pose, RenderTypes.entityCutoutNoCull(BUTTERFLY), (p, vc) -> butterfly(p, vc, light, wt, crouch));
            pose.popPose();
        } else if (wings != null) {
            pose.pushPose();
            getParentModel().body.translateAndRotate(pose);
            WingStyle st = style(wings.id());
            boolean glow = level == 0 && (st.kind() != Kind.FEATHER || st.glow() != 0);
            Pass[] passes = st.kind() == Kind.MEMBRANE ? (glow ? WING_MEMBRANE_GLOW : WING_MEMBRANE)
                    : (glow ? WING_GLOW : WING_SOLID);
            submit(pose, collector, light, (m, pass) -> {
                lod = level;
                wings(m, pass, st, wt, crouch);
            }, passes);
            pose.popPose();
        }

        // objet dans le dos (guitare, katana, AK-47, masse) : en diagonale, au-dessus de la cape
        Cosmetics.Item back = mini != null ? mini.cosmetic(Cosmetics.Type.BACK) : Cosmetics.worn(player, Cosmetics.Type.BACK);
        if (back != null) {
            String backId = back.id();
            pose.pushPose();
            getParentModel().body.translateAndRotate(pose);
            // repère du corps : Y vers le bas, Z vers l'arrière ; l'objet est modélisé Y vers le haut, Z vers l'extérieur
            pose.translate(0, 0.40f, 0.19f);
            pose.mulPose(Axis.ZP.rotationDegrees(180 + BackItems.tilt(backId)));
            float bs = BackItems.scale(backId);
            pose.scale(bs, bs, bs);
            submit(pose, collector, light, (m, pass) -> BackItems.draw(m, backId), WING_SOLID);
            pose.popPose();
        }

        if (halo != null && Cosmetics.isHat(halo.id())) {
            pose.pushPose();
            getParentModel().head.translateAndRotate(pose);
            Cosmetics.Item h = halo;
            submit(pose, collector, light, (m, pass) -> {
                lod = level;
                Hats.hat(m, pass == Pass.GLOW, h.id(), t);
            }, level == 0 ? WING_GLOW : WING_SOLID);
            pose.popPose();
        } else if (halo != null) {
            pose.pushPose();
            getParentModel().head.translateAndRotate(pose);
            pose.translate(0, -0.66 - 0.025 * Mesh.sin(t * 0.1), 0);
            pose.mulPose(Axis.XP.rotationDegrees(-8));
            Cosmetics.Item h = halo;
            submit(pose, collector, FULL_BRIGHT, (m, pass) -> {
                lod = level;
                halo(m, pass, h, t);
            }, level == 0 ? HALO_ALL : HALO_SOLID);
            pose.popPose();
        }
    }

    // ===================================================================== ailes

    /** Par joueur : {sneak lissé 0..1, temps d'animation des ailes, dernier âge vu}. */
    private static final java.util.Map<Integer, float[]> ANIM = new java.util.HashMap<>();

    private static float[] anim(int key, boolean crouching, float age) {
        if (ANIM.size() > 256) ANIM.clear();
        float[] a = ANIM.computeIfAbsent(key, k -> new float[]{crouching ? 1 : 0, age, age});
        float dt = Mth.clamp(age - a[2], 0, 5);
        a[2] = age;
        // approche exponentielle (~0,3 s), indépendante des FPS
        a[0] += ((crouching ? 1 : 0) - a[0]) * (1 - (float) Math.exp(-dt * 0.35f));
        // battements 40 % plus lents en sneak, sans saut de phase
        a[1] += dt * (1 - 0.4f * a[0]);
        float k = a[0];
        return new float[]{k * k * (3 - 2 * k), a[1]};
    }

    private static WingStyle style(String id) {
        return switch (id) {
            case "paranoia" -> new WingStyle(Kind.FEATHER, 0x1A1030, 0x1E1236, 0x5B35B8, 0x2A1A4A, 0xB98CFF, 1.05f);
            case "corbeau" -> new WingStyle(Kind.FEATHER, 0x15151A, 0x141419, 0x2E3956, 0x1E1E28, 0, 0.95f);
            case "demon" -> new WingStyle(Kind.MEMBRANE, 0x2A0A0E, 0x3E040C, 0xA3111F, 0, 0xFF4A1A, 1f);
            case "maudites" -> new WingStyle(Kind.BLADE, 0xF4F2FF, 0xFFFFFF, 0xC9B8FF, 0xE6DEFF, 0xD8C8FF, 1f);
            case "papillon" -> new WingStyle(Kind.BUTTERFLY, 0x1A0E1A, 0xFFC6EE, 0xD9369F, 0x2A0B24, 0xFFFFFF, 1f);
            default -> new WingStyle(Kind.FEATHER, 0xD9D2C4, 0xD8D2C6, 0xFFFFFF, 0xF2EEE6, 0xFFE7A8, 1f);
        };
    }

    private static void wings(Mesh m, Pass pass, WingStyle st, float t, float crouch) {
        boolean bfly = st.kind() == Kind.BUTTERFLY;
        float speed = bfly ? 0.3f : 0.11f;
        float ph = Mesh.sin(t * speed);
        for (int side = -1; side <= 1; side += 2) {
            m.ps.pushPose();
            m.ps.translate(side * 0.07f, 0.30f, 0.14f);
            m.ps.scale(side, 1, 1);
            float sweep, lift;
            if (bfly) {
                sweep = 8 + 40 * (0.5f + 0.5f * ph);
                lift = 4;
            } else if (st.kind() == Kind.BLADE) {
                // lames en V : presque plates dans le dos, flottent doucement
                sweep = 18 + ph * 6;
                lift = 0;
            } else if (st.kind() == Kind.FEATHER) {
                // ailes d'ange dressées vers le haut
                sweep = 33 + 25 * crouch + ph * 12;
                lift = 10 - 8 * crouch + ph * 7;
            } else {
                sweep = 34 + 24 * crouch + ph * 13;
                lift = 7 - 7 * crouch + ph * 8;
            }
            m.ps.mulPose(Axis.YP.rotationDegrees(-sweep));
            m.ps.mulPose(Axis.ZP.rotationDegrees(-lift));
            // ailes plus hautes que larges : envergure réduite, hauteur allongée
            if (st.kind() == Kind.FEATHER) m.ps.scale(0.85f, st.base() == 0xD8D2C6 ? 1.5f : 1.1f, 1f);
            else m.ps.scale(0.8f, 1.12f, 1f);
            switch (st.kind()) {
                case FEATHER -> featherWing(m, pass, st, t, ph, crouch);
                case MEMBRANE -> membraneWing(m, pass, st, t, ph, crouch);
                case BUTTERFLY -> butterflyWing(m, pass, st, t);
                case BLADE -> bladeWing(m, pass, st, t, ph);
            }
            m.ps.popPose();
        }
    }

    /**
     * Ailes maudites : trois lames lumineuses en éventail qui forment un V dans le dos (comme le logo
     * Cursed). Effilées, légèrement recourbées vers l'extérieur, blanches avec un liseré lavande.
     */
    private static void bladeWing(Mesh m, Pass pass, WingStyle st, float t, float ph) {
        float[] ang = {28, 42, 57}, len = {1.0f, 0.78f, 0.56f}, wid = {0.11f, 0.09f, 0.075f};
        float hover = 0.03f * Mesh.sin(t * 0.09f);
        for (int k = 0; k < 3; k++) {
            float a = (float) Math.toRadians(ang[k] + ph * 3 * (k + 1) * 0.5f);
            float dx = Mesh.sin(a), dy = -Mesh.cos(a);
            // perpendiculaire dans le plan (vers l'extérieur et le bas)
            float nx = -dy, ny = dx;
            final int N = 10;
            for (int i = 0; i < N; i++) {
                float u0 = i / (float) N, u1 = (i + 1) / (float) N;
                float w0 = wid[k] * shape(u0), w1 = wid[k] * shape(u1);
                float b0 = 0.10f * u0 * u0, b1 = 0.10f * u1 * u1;
                float l0 = len[k] * u0, l1 = len[k] * u1;
                float x0 = 0.02f + dx * l0 + nx * b0, y0 = hover + dy * l0 + ny * b0;
                float x1 = 0.02f + dx * l1 + nx * b1, y1 = hover + dy * l1 + ny * b1;
                float z = 0.01f * k;
                if (pass == Pass.SOLID) {
                    int c0 = argb(255, lerp(st.tip(), st.base(), u0)), c1 = argb(255, lerp(st.tip(), st.base(), u1));
                    m.quad(v(x0 - nx * w0, y0 - ny * w0, z), v(x0 + nx * w0 * 0.4f, y0 + ny * w0 * 0.4f, z),
                            v(x1 + nx * w1 * 0.4f, y1 + ny * w1 * 0.4f, z), v(x1 - nx * w1, y1 - ny * w1, z), c0, c0, c1, c1);
                } else if (pass == Pass.GLOW) {
                    float g0 = w0 * 2.4f + 0.02f, g1 = w1 * 2.4f + 0.02f;
                    float flick = 0.8f + 0.2f * Mesh.sin(t * 0.3f + k);
                    int c0 = argb(Math.round(90 * flick * (1 - u0 * 0.5f)), st.glow()), c1 = argb(Math.round(90 * flick * (1 - u1 * 0.5f)), st.glow());
                    m.quad(v(x0 - nx * g0, y0 - ny * g0, z - 0.005f), v(x0 + nx * g0, y0 + ny * g0, z - 0.005f),
                            v(x1 + nx * g1, y1 + ny * g1, z - 0.005f), v(x1 - nx * g1, y1 - ny * g1, z - 0.005f), c0, c0, c1, c1);
                }
            }
        }
    }

    /** Profil d'une lame : s'élargit vite puis s'effile jusqu'à la pointe. */
    private static float shape(float u) {
        return Mesh.sin(Math.PI * Math.pow(u, 0.55)) * (1 - u * 0.2f) + 0.05f;
    }

    private static Vector3f dir(Vector3f from, float angDeg, float len) {
        double a = Math.toRadians(angDeg);
        return v(from.x + Mesh.cos(a) * len, from.y + Mesh.sin(a) * len, from.z);
    }

    /** Tampon de travail des boucles de plumes (lu immédiatement, jamais conservé). */
    private static final Vector3f MIXB = new Vector3f();
    private static final org.joml.Quaternionf ROTQ = new org.joml.Quaternionf();

    private static Vector3f mixTo(Vector3f out, Vector3f a, Vector3f b, float s) {
        return out.set(a.x + (b.x - a.x) * s, a.y + (b.y - a.y) * s, a.z + (b.z - a.z) * s);
    }

    private static Vector3f mix(Vector3f a, Vector3f b, float s) {
        return v(a.x + (b.x - a.x) * s, a.y + (b.y - a.y) * s, a.z + (b.z - a.z) * s);
    }

    /** Aile d'oiseau : humérus / avant-bras / main, rémiges primaires et secondaires, tertiaires, couvertures. */
    private static void featherWing(Mesh m, Pass pass, WingStyle st, float t, float ph, float crouch) {
        float fold = Mth.lerp(crouch, 0.1f + 0.08f * ph, 0.7f);
        float a1 = -47 + 34 * fold + ph * 7, a2 = a1 + 32 + 45 * fold, a3 = a2 + 27 + 35 * fold;
        float L = st.len();
        Vector3f j0 = v(0, 0, 0), j1 = dir(j0, a1, 0.30f * L), j2 = dir(j1, a2, 0.40f * L), j3 = dir(j2, a3, 0.33f * L);
        int base = argb(255, st.base()), tip = argb(255, st.tip()), covert = argb(255, st.covert());
        float flick = 0.75f + 0.25f * Mesh.sin(t * 0.25f);

        // rémiges primaires (main) : en éventail jusqu'à la pointe
        for (int k = 0; k < 9; k++) {
            float s = k / 8f;
            Vector3f b = mixTo(MIXB, j2, j3, s);
            feather(m, pass, b, 100 - (100 - (a3 - 6)) * s, (0.50f + 0.26f * s) * L, 0.075f, 0.004f * k,
                    base, tip, st.glow(), flick);
        }
        // secondaires (avant-bras)
        for (int k = 0; k < 9; k++) {
            float s = k / 8f;
            Vector3f b = mixTo(MIXB, j1, j2, s);
            feather(m, pass, b, 102 - 3 * s, (0.46f + 0.03f * (1 - s)) * L, 0.07f, 0.036f + 0.0015f * k,
                    lerp(base, tip, 0.15f), lerp(base, tip, 0.85f), st.glow(), flick * 0.6f);
        }
        // tertiaires (humérus)
        for (int k = 0; k < 4; k++) {
            float s = k / 3f;
            Vector3f b = mixTo(MIXB, j0, j1, 0.2f + 0.8f * s);
            feather(m, pass, b, 108 - 6 * s, (0.34f + 0.08f * s) * L, 0.065f, 0.05f, lerp(base, covert, 0.5f), tip, 0, 0);
        }
        // grandes puis petites couvertures, superposées
        Vector3f[] path = {j0, j1, j2, j3};
        for (int row = 0; row < (lod == 0 ? 2 : 1); row++) {
            int count = row == 0 ? 12 : 10;
            for (int k = 0; k < count; k++) {
                float s = k / (float) (count - 1) * (row == 0 ? 2.6f : 2.2f);
                int seg = Math.min(2, (int) s);
                Vector3f b = mixTo(MIXB, path[seg], path[seg + 1], s - seg);
                b.y += row == 0 ? 0.03f : 0.01f;
                feather(m, pass, b, 92 - 8 * s, (row == 0 ? 0.22f : 0.12f) * L, row == 0 ? 0.06f : 0.05f,
                        0.064f + row * 0.012f + 0.001f * k, covert, lerp(covert, tip, 0.5f), 0, 0);
            }
        }
        if (pass == Pass.SOLID) {
            int bone = argb(255, st.bone());
            m.bone(v(j0.x, j0.y, 0.09f), v(j1.x, j1.y, 0.09f), 0.07f, 0.055f, 0.06f, bone);
            m.bone(v(j1.x, j1.y, 0.09f), v(j2.x, j2.y, 0.09f), 0.055f, 0.045f, 0.05f, bone);
            m.bone(v(j2.x, j2.y, 0.09f), v(j3.x, j3.y, 0.09f), 0.045f, 0.02f, 0.04f, bone);
        }
    }

    /**
     * Une plume : vane effilée avec arête centrale en relief et pointe recourbée vers l'arrière.
     * Passe GLOW : liseré lumineux sur la moitié extérieure (si glow != 0).
     */
    private static final int FN = 5;
    /** Profil d'une plume précalculé : largeur, courbure, dégradé de couleur, intensité du liseré. */
    private static final float[] F_W = new float[FN + 1], F_CURL = new float[FN + 1], F_COL = new float[FN + 1], F_GLOW = new float[FN + 1];
    /** Tampons réutilisés (le rendu se fait sur un seul thread) : aucune allocation par plume. */
    private static final Vector3f[] FC = new Vector3f[FN + 1], FL = new Vector3f[FN + 1], FR = new Vector3f[FN + 1];
    private static final int[] FCC = new int[FN + 1], FCE = new int[FN + 1];

    static {
        for (int i = 0; i <= FN; i++) {
            float s = i / (float) FN;
            F_W[i] = Mesh.sin(Math.PI * (0.1 + 0.9 * Math.pow(s, 0.8)));
            F_CURL[i] = 0.07f * s * s;
            F_COL[i] = (float) Math.pow(s, 0.9);
            F_GLOW[i] = Math.max(0, (s - 0.55f) / 0.45f);
            FC[i] = new Vector3f();
            FL[i] = new Vector3f();
            FR[i] = new Vector3f();
        }
    }

    /**
     * Une plume : vane effilée avec arête centrale en relief et pointe recourbée vers l'arrière.
     * Passe GLOW : liseré lumineux sur la moitié extérieure (si glow != 0), segments invisibles ignorés.
     */
    private static void feather(Mesh m, Pass pass, Vector3f b, float ang, float len, float width, float z,
                                int base, int tip, int glow, float glowK) {
        boolean glowPass = pass == Pass.GLOW;
        if (glowPass && (glow == 0 || glowK <= 0)) return;
        if (pass != Pass.SOLID && !glowPass) return;
        m.ps.pushPose();
        m.ps.translate(b.x, b.y, b.z + z);
        m.ps.mulPose(ROTQ.rotationZ((float) Math.toRadians(ang)));
        float lift = glowPass ? 0.003f : 0;
        int first = 0;
        if (glowPass) while (first < FN && F_GLOW[first + 1] <= 0) first++;
        for (int i = first; i <= FN; i++) {
            float s = i / (float) FN, w = width * F_W[i], curl = F_CURL[i] + lift, x = s * len;
            FC[i].set(x, 0, curl + 0.008f * (1 - s * 0.6f));
            FL[i].set(x, -w, curl);
            FR[i].set(x, w * 0.8f, curl + 0.002f);
            if (!glowPass) {
                int col = lerp(base, tip, F_COL[i]);
                FCC[i] = shade(col, 1.07f);
                FCE[i] = shade(col, 0.8f);
            } else {
                FCC[i] = argb(Math.round(110 * F_GLOW[i] * glowK), glow);
                FCE[i] = argb(Math.round(230 * F_GLOW[i] * glowK), glow);
            }
        }
        for (int i = first; i < FN; i++) {
            m.quad(FC[i], FL[i], FL[i + 1], FC[i + 1], FCC[i], FCE[i], FCE[i + 1], FCC[i + 1]);
            m.quad(FC[i], FC[i + 1], FR[i + 1], FR[i], FCC[i], FCC[i + 1], FCE[i + 1], FCE[i]);
        }
        m.ps.popPose();
    }

    /** Aile de démon : os et doigts, membrane translucide festonnée, braises sur le bord de fuite. */
    private static void membraneWing(Mesh m, Pass pass, WingStyle st, float t, float ph, float crouch) {
        float fold = Mth.lerp(crouch, 0.08f + 0.06f * ph, 0.65f);
        float L = st.len();
        float a1 = -42 + 30 * fold + ph * 8, a2 = a1 + 34 + 40 * fold;
        Vector3f j0 = v(0, 0, 0), j1 = dir(j0, a1, 0.32f * L), j2 = dir(j1, a2, 0.42f * L);
        float spread = 1 - 0.55f * fold;
        float[] fa = {a2 - 12, a2 + 22 * spread, a2 + 52 * spread, a2 + 82 * spread};
        float[] fl = {0.80f, 0.74f, 0.64f, 0.52f};
        Vector3f[] f = new Vector3f[4];
        for (int i = 0; i < 4; i++) f[i] = dir(j2, fa[i], fl[i] * L);
        Vector3f body = v(0.02f, 0.62f, 0);

        int bone = argb(255, st.bone());
        if (pass == Pass.SOLID) {
            m.bone(j0, j1, 0.07f, 0.05f, 0.06f, bone);
            m.bone(j1, j2, 0.05f, 0.04f, 0.05f, bone);
            for (int i = 0; i < 4; i++) m.bone(j2, f[i], 0.035f, 0.01f, 0.03f, bone);
            // griffe au poignet
            m.ps.pushPose();
            m.ps.translate(j2.x, j2.y, 0);
            m.ps.mulPose(Axis.ZP.rotationDegrees(a2 - 75 + 90));
            m.spike(0, 0, 0, 0.022f, 0.10f, argb(255, 0x6E625A), argb(255, 0xEDE4D6));
            m.ps.popPose();
        } else if (pass == Pass.MEMBRANE) {
            int dark = argb(235, st.base()), edge = argb(205, st.tip());
            Vector3f[] e = {f[0], f[1], f[2], f[3], body};
            for (int i = 0; i < e.length - 1; i++) {
                Vector3f a = e[i], b = e[i + 1];
                Vector3f mid = mix(mix(a, b, 0.5f), j2, i == e.length - 2 ? 0.12f : 0.24f);
                m.tri(j2, a, mid, dark, edge, edge);
                m.tri(j2, mid, b, dark, edge, edge);
            }
            m.tri(j2, body, j1, dark, edge, dark);
            m.tri(j1, body, j0, dark, edge, dark);
            m.tri(j0, j1, j2, dark, dark, dark);
        } else if (pass == Pass.GLOW) {
            float flick = 0.6f + 0.4f * Mesh.sin(t * 0.33f);
            Vector3f[] e = {f[0], f[1], f[2], f[3], body};
            for (int i = 0; i < e.length - 1; i++) {
                Vector3f a = e[i], b = e[i + 1];
                Vector3f mid = mix(mix(a, b, 0.5f), j2, i == e.length - 2 ? 0.12f : 0.24f);
                strip(m, a, mid, 0.025f, argb(Math.round(170 * flick), st.glow()));
                strip(m, mid, b, 0.025f, argb(Math.round(170 * flick), st.glow()));
            }
        }
    }

    /** Bande lumineuse le long d'un segment (s'estompe vers l'intérieur). */
    private static void strip(Mesh m, Vector3f a, Vector3f b, float w, int color) {
        float dx = b.x - a.x, dy = b.y - a.y, len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-5f) return;
        float px = -dy / len * w, py = dx / len * w;
        int clear = color & 0x00FFFFFF;
        Vector3f a2 = v(a.x - px, a.y - py, a.z + 0.004f), b2 = v(b.x - px, b.y - py, b.z + 0.004f);
        Vector3f a1 = v(a.x + px * 0.3f, a.y + py * 0.3f, a.z + 0.004f), b1 = v(b.x + px * 0.3f, b.y + py * 0.3f, b.z + 0.004f);
        m.quad(a2, a1, b1, b2, clear, color, color, clear);
    }

    /** Aile de papillon : deux lobes dégradés, bordure sombre, nervures, ocelles lumineuses. */
    private static void butterflyWing(Mesh m, Pass pass, WingStyle st, float t) {
        drawLobe(m, pass, st, -78, 8, 0.95f, t, 0);
        drawLobe(m, pass, st, 18, 88, 0.62f, t, 1);
    }

    private static void drawLobe(Mesh m, Pass pass, WingStyle st, float from, float to, float R, float t, int lobe) {
        final int n = 14;
        Vector3f o = v(0, 0, 0);
        int center = argb(255, st.base()), outer = argb(255, st.tip()), edge = argb(255, st.covert());
        Vector3f[] in = new Vector3f[n + 1], out = new Vector3f[n + 1];
        for (int i = 0; i <= n; i++) {
            float s = i / (float) n;
            float ang = from + (to - from) * s;
            float r = R * (0.62f + 0.38f * Mesh.sin(Math.PI * Math.pow(s, lobe == 0 ? 0.75 : 1.1)));
            float z = 0.03f * s * (1 - s) * 4;
            Vector3f p = dir(o, ang, r);
            p.z = z;
            out[i] = p;
            in[i] = v(p.x * 0.84f, p.y * 0.84f, z * 0.84f);
        }
        if (pass == Pass.SOLID) {
            for (int i = 0; i < n; i++) {
                m.tri(o, in[i], in[i + 1], shade(center, 1.05f), outer, outer);
                m.quad(in[i], out[i], out[i + 1], in[i + 1], edge, shade(edge, 0.8f), shade(edge, 0.8f), edge);
            }
            // nervures
            for (int i = 1; i < n; i += 3) {
                Vector3f a = v(0, 0, 0.003f), b = v(in[i].x, in[i].y, in[i].z + 0.003f);
                float dx = b.x, dy = b.y, len = (float) Math.sqrt(dx * dx + dy * dy);
                float px = -dy / len * 0.008f, py = dx / len * 0.008f;
                m.quad(v(a.x - px, a.y - py, a.z), v(a.x + px, a.y + py, a.z), v(b.x + px * 0.3f, b.y + py * 0.3f, b.z),
                        v(b.x - px * 0.3f, b.y - py * 0.3f, b.z), shade(edge, 1.2f));
            }
        } else if (pass == Pass.GLOW) {
            float pulse = 0.65f + 0.35f * Mesh.sin(t * 0.2f + lobe);
            // ocelle : disque lumineux dégradé
            float mid = (from + to) / 2 + (lobe == 0 ? 10 : -5);
            Vector3f c = dir(o, mid, R * (lobe == 0 ? 0.55f : 0.5f));
            c.z = 0.03f;
            float rad = lobe == 0 ? 0.11f : 0.08f;
            int core = argb(Math.round(240 * pulse), st.glow()), rim = argb(0, st.tip());
            for (int i = 0; i < 16; i++) {
                double a0 = Math.PI * 2 * i / 16, a1 = Math.PI * 2 * (i + 1) / 16;
                Vector3f p0 = v(c.x + Mesh.cos(a0) * rad, c.y + Mesh.sin(a0) * rad, c.z);
                Vector3f p1 = v(c.x + Mesh.cos(a1) * rad, c.y + Mesh.sin(a1) * rad, c.z);
                m.tri(c, p0, p1, core, rim, rim);
            }
            // reflet irisé qui glisse sur l'aile
            float sweep = (float) ((t * 0.01f + lobe * 0.5f) % 1.0);
            int idx = Math.min(n - 1, (int) (sweep * n));
            m.tri(v(0, 0, 0.004f), v(in[idx].x, in[idx].y, in[idx].z + 0.004f), v(in[idx + 1].x, in[idx + 1].y, in[idx + 1].z + 0.004f),
                    argb(0, 0xFFFFFF), argb(70, 0xFFFFFF), argb(70, 0xFFFFFF));
        }
    }

    // ===================================================================== halos

    private static void halo(Mesh m, Pass pass, Cosmetics.Item item, float t) {
        int col = item.color();
        m.ps.mulPose(Axis.YP.rotationDegrees(t * 1.2f));
        float R = 0.27f;
        switch (pass) {
            case SOLID -> {
                int bright = argb(255, lerp(col, 0xFFFFFF, 0.35f)), deep = argb(255, shade(col, 0.55f));
                m.torus(R, 0.03f, lod == 0 ? 36 : 18, lod == 0 ? 8 : 5, bright, deep);
                if (item.id().equals("paranoia")) {
                    for (int k = 0; k < 5; k++) {
                        double a = Math.toRadians(k * 72 + t * 2.5);
                        float y = -0.12f - 0.03f * Mesh.sin(t * 0.15f + k);
                        m.crystal(R * Mesh.cos(a), y, R * Mesh.sin(a), 0.035f, 0.075f, argb(255, 0x2A1752));
                    }
                }
            }
            case MEMBRANE -> {
                if (item.id().equals("givre")) {
                    for (int k = 0; k < 12; k++) {
                        double a = Math.toRadians(k * 30);
                        float h = k % 2 == 0 ? 0.15f : 0.08f;
                        m.spike(R * Mesh.cos(a), -0.01f, R * Mesh.sin(a), 0.025f, h,
                                argb(200, 0xBFEFFF), argb(150, 0xFFFFFF));
                    }
                }
            }
            case GLOW -> {
                float pulse = 0.8f + 0.2f * Mesh.sin(t * 0.15f);
                m.torus(R, 0.07f, 28, 6, argb(Math.round(55 * pulse), col), argb(Math.round(25 * pulse), col));
                m.torus(R, 0.13f, 24, 5, argb(Math.round(18 * pulse), col), argb(Math.round(8 * pulse), col));
                if (item.id().equals("paranoia")) {
                    for (int k = 0; k < 5; k++) {
                        double a = Math.toRadians(k * 72 + t * 2.5);
                        float y = -0.12f - 0.03f * Mesh.sin(t * 0.15f + k);
                        m.crystal(R * Mesh.cos(a), y, R * Mesh.sin(a), 0.055f, 0.11f, argb(Math.round(110 * pulse), 0xB98CFF));
                    }
                }
            }
            case BLOOM -> {
                float pulse = 0.7f + 0.3f * Mesh.sin(t * 0.15f);
                for (int k = 0; k < 6; k++) {
                    double a = Math.toRadians(k * 60 + t * 3);
                    m.bloom(R * Mesh.cos(a), 0, R * Mesh.sin(a), 0.12f, argb(Math.round(60 * pulse), col));
                }
            }
        }
    }

    // ===================================================================== auras

    /**
     * Aura en anneaux tourbillonnants au sol (comme les clients PvP) : arcs lumineux qui tournent à
     * différentes vitesses et hauteurs, queue qui s'estompe, halo au sol, langues de feu / éclats.
     */
    private static void aura(PoseStack pose, SubmitNodeCollector collector, Cosmetics.Item item, float t, int level) {
        AuraStyle st = auraStyle(item.id(), item.color());
        submit(pose, collector, FULL_BRIGHT, (m, pass) -> {
            if (pass == Pass.GLOW) swirls(m, st, t, level);
            else auraGlow(m, st, t, level);
        }, AURA_FX);
    }

    private record AuraStyle(int core, int edge, float speed, boolean flames, boolean sparks, int arcs) {}

    private static AuraStyle auraStyle(String id, int color) {
        return switch (id) {
            case "braise" -> new AuraStyle(0xFFF2C0, 0xFF8A1E, 1.25f, true, true, 3);
            case "givre" -> new AuraStyle(0xE6FAFF, 0x3FB8FF, 1.0f, false, true, 3);
            case "spectre" -> new AuraStyle(0xD8F0FF, 0x3C8CFF, 0.85f, false, true, 3);
            case "neant" -> new AuraStyle(0xF0E0FF, 0x8A4CFF, -0.9f, false, true, 3);
            case "etoiles" -> new AuraStyle(0xFFFFFF, 0xB8C4FF, 0.7f, false, true, 2);
            case "coeurs" -> new AuraStyle(0xFFE6F2, 0xFF5AA0, 0.8f, false, false, 2);
            default -> new AuraStyle(lerp(color, 0xFFFFFF, 0.6f), color, 1f, false, true, 3);
        };
    }

    /** Arcs plats autour des pieds (y = 1.5 dans l'espace du modèle). */
    private static void swirls(Mesh m, AuraStyle st, float t, int level) {
        int arcs = level > 0 ? 2 : st.arcs();
        for (int k = 0; k < arcs; k++) {
            // anneaux qui flottent autour des jambes (au-dessus des herbes), inclinés pour se croiser
            float R = 0.7f + 0.17f * k, y = 1.32f - 0.16f * k;
            double head = t * 0.11f * st.speed() * (1 + 0.35f * k) + k * 2.1;
            float tilt = (k - 1) * 15f;
            m.ps.pushPose();
            m.ps.translate(0, y, 0);
            m.ps.mulPose(Axis.XP.rotationDegrees(tilt));
            m.ps.mulPose(Axis.ZP.rotationDegrees(tilt * 0.6f));
            final int n = level > 0 ? 14 : 30;
            float span = 3.4f - 0.5f * k;
            for (int i = 0; i < n; i++) {
                float u0 = i / (float) n, u1 = (i + 1) / (float) n;
                double a0 = head - u0 * span * Math.signum(st.speed()), a1 = head - u1 * span * Math.signum(st.speed());
                float w0 = 0.14f * (float) Math.pow(1 - u0, 0.8) + 0.01f, w1 = 0.14f * (float) Math.pow(1 - u1, 0.8) + 0.01f;
                float r0 = R * (1 + 0.06f * Mesh.sin(u0 * 9 + t * 0.2f)), r1 = R * (1 + 0.06f * Mesh.sin(u1 * 9 + t * 0.2f));
                int c0 = argb(Math.round(255 * (float) Math.pow(1 - u0, 1.1)), lerp(st.core(), st.edge(), u0 * 0.8f));
                int c1 = argb(Math.round(255 * (float) Math.pow(1 - u1, 1.1)), lerp(st.core(), st.edge(), u1 * 0.8f));
                float x0 = Mesh.cos(a0), z0 = Mesh.sin(a0), x1 = Mesh.cos(a1), z1 = Mesh.sin(a1);
                m.quad(v(x0 * (r0 - w0), 0, z0 * (r0 - w0)), v(x0 * (r0 + w0), 0, z0 * (r0 + w0)),
                        v(x1 * (r1 + w1), 0, z1 * (r1 + w1)), v(x1 * (r1 - w1), 0, z1 * (r1 - w1)), c0, c0, c1, c1);
                // langues de feu qui montent depuis l'avant de l'arc
                if (st.flames() && level == 0 && i % 3 == 0 && u0 < 0.55f) {
                    float hgt = 0.22f * (1 - u0) * (0.7f + 0.3f * Mesh.sin(t * 0.9f + i));
                    int fc = argb(Math.round(200 * (1 - u0)), lerp(st.core(), st.edge(), 0.4f));
                    m.quad(v(x0 * r0, 0, z0 * r0), v(x1 * r1, 0, z1 * r1), v(x1 * r1, -hgt, z1 * r1), v(x0 * r0, -hgt, z0 * r0),
                            fc, fc, fc & 0x00FFFFFF, fc & 0x00FFFFFF);
                }
            }
            m.ps.popPose();
        }
    }

    /** Halo doux au sol + éclats qui s'élèvent. */
    private static void auraGlow(Mesh m, AuraStyle st, float t, int level) {
        float pulse = 0.75f + 0.25f * Mesh.sin(t * 0.12f);
        float s = 1.05f;
        int c = argb(Math.round(80 * pulse), st.edge());
        m.sprite(v(-s, 1.49f, -s), v(-s, 1.49f, s), v(s, 1.49f, s), v(s, 1.49f, -s), c);
        if (!st.sparks() || level > 0) return;
        for (int k = 0; k < 8; k++) {
            float life = (float) ((t * 0.016 + k * 0.125) % 1.0);
            double a = k * 2.39996 + t * 0.04 * Math.signum(st.speed());
            float r = 0.5f + 0.3f * Mesh.sin(k * 1.7);
            float alpha = Mesh.sin(Math.PI * life);
            m.bloom(r * Mesh.cos(a), 1.45f - life * 1.6f, r * Mesh.sin(a), 0.05f + 0.03f * alpha,
                    argb(Math.round(220 * alpha), st.core()));
        }
    }

    // ===================================================================== hologramme et chauves-souris

    /** Chauves-souris qui volent autour de la tête (Halloween Bats). */
    private static void bats(Mesh m, Pass pass, float t) {
        for (int k = 0; k < 5; k++) {
            double a = t * 0.05 * (k % 2 == 0 ? 1 : -1) + k * 1.2566;
            float r = 0.9f + 0.25f * Mesh.sin(t * 0.03 + k);
            float y = -0.75f - 0.25f * Mesh.sin(t * 0.07 + k * 2);
            m.ps.pushPose();
            m.ps.translate(r * Mesh.cos(a), y, r * Mesh.sin(a));
            m.ps.mulPose(Axis.YP.rotation((float) (-a + (k % 2 == 0 ? 0 : Math.PI))));
            float flap = Mesh.sin(t * 1.1 + k) * 50;
            if (pass == Pass.SOLID) {
                int body = argb(255, 0x231A2E), wing = argb(255, 0x3A2A4E);
                m.quad(v(-0.035f, -0.04f, 0), v(0.035f, -0.04f, 0), v(0.035f, 0.05f, 0), v(-0.035f, 0.05f, 0), body);
                for (int side = -1; side <= 1; side += 2) {
                    m.ps.pushPose();
                    m.ps.scale(side, 1, 1);
                    m.ps.translate(0.03f, 0, 0);
                    m.ps.mulPose(Axis.YP.rotationDegrees(flap));
                    m.tri(v(0, -0.03f, 0), v(0.16f, -0.06f, 0), v(0.12f, 0.05f, 0), wing, wing, argb(255, 0x1A1222));
                    m.tri(v(0, -0.03f, 0), v(0.12f, 0.05f, 0), v(0, 0.04f, 0), wing, argb(255, 0x1A1222), wing);
                    m.ps.popPose();
                }
            } else {
                int eye = argb(255, 0xFF3A3A);
                m.quad(v(-0.02f, -0.025f, 0.004f), v(-0.008f, -0.025f, 0.004f), v(-0.008f, -0.013f, 0.004f), v(-0.02f, -0.013f, 0.004f), eye);
                m.quad(v(0.008f, -0.025f, 0.004f), v(0.02f, -0.025f, 0.004f), v(0.02f, -0.013f, 0.004f), v(0.008f, -0.013f, 0.004f), eye);
            }
            m.ps.popPose();
        }
    }

    /** Quad horizontal texturé (rayon r), lumineux. */
    private static void flat(PoseStack pose, SubmitNodeCollector collector, Cosmetics.Item item, float r, float alpha) {
        int a = Math.round(255 * alpha);
        collector.submitCustomGeometry(pose, RenderTypes.entityTranslucentEmissive(item.texture()), (p, vc) -> {
            vertex(vc, p, -r, 0, -r, 0, 0, a);
            vertex(vc, p, -r, 0, r, 0, 1, a);
            vertex(vc, p, r, 0, r, 1, 1, a);
            vertex(vc, p, r, 0, -r, 1, 0, a);
        });
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose p, float x, float y, float z, float u, float v, int a) {
        vc.addVertex(p, x, y, z).setColor(255, 255, 255, a).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT).setNormal(p, 0, -1, 0);
    }
}
