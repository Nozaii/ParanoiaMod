package gg.aurora.render;

import com.mojang.blaze3d.vertex.PoseStack;
import gg.aurora.module.ExtraModules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * Friend Glow : l'entité ciblée (et sa monture) est soumise une seconde fois à un collecteur intermédiaire
 * qui remplace chaque modèle (skin, armure, objets tenus, cheval…) par sa silhouette translucide, décalée
 * tout autour face à la caméra et dessinée avant l'entité, qui la recouvre : seul le bord dépasse.
 * Test de profondeur conservé : les murs cachent la lueur (fair-play, pas d'ESP).
 */
public final class FriendGlow {
    private FriendGlow() {}

    /** Marque posée sur l'état de rendu à l'extraction (mixin sur EntityRenderState). */
    public interface Marked {
        boolean aurora$glow();

        void aurora$setGlow(boolean glow);
    }

    public static final String[] STYLES = {"Outline", "Soft", "Pulse", "Rainbow"};
    private static final int FULL_BRIGHT = 0xF000F0;

    /** Vrai pendant la passe de silhouette (les cosmétiques s'en excluent, pas de réentrance). */
    public static boolean active;
    private static boolean warned;

    /** Copies d'une entité : translation (x, y, z) dans l'espace caméra-monde, couleur ARGB. */
    private record Copies(float[] tx, float[] ty, float[] tz, int[] col) {
        int n() {
            return col.length;
        }

        /** Pose déplacée par la copie i (translation appliquée avant la pose). */
        void shift(Matrix4f m, int i) {
            new Matrix4f().translation(tx[i], ty[i], tz[i]).mul(m, m);
        }
    }

    /** Copies de l'entité en cours ; capturées par les rendus différés (exécutés après toutes les soumissions). */
    private static Copies cur;
    private static SubmitNodeCollector root;
    private static final PoseStack SCRATCH = new PoseStack();

    /** Appelé à l'extraction : l'entité (ou un de ses passagers) doit-elle briller ? */
    public static void mark(Entity e, EntityRenderState s) {
        if (!(s instanceof Marked m)) return;
        boolean glow = false;
        if (ExtraModules.FRIEND_GLOW.enabled) {
            double range = ExtraModules.FRIEND_GLOW.opt("range", 64);
            if (s.distanceToCameraSq < range * range) {
                glow = ExtraModules.friendGlow(e);
                if (!glow && ExtraModules.FRIEND_GLOW.flag("mount", true)) {
                    for (Entity p : e.getPassengers()) {
                        if (ExtraModules.friendGlow(p)) {
                            glow = true;
                            break;
                        }
                    }
                }
            }
        }
        m.aurora$setGlow(glow);
    }

    /** Appelé en tête de EntityRenderDispatcher.submit : soumet la silhouette avant l'entité elle-même. */
    public static <S extends EntityRenderState> void submit(EntityRenderDispatcher d, S state, CameraRenderState cam,
                                                             double x, double y, double z, PoseStack pose, SubmitNodeCollector c) {
        if (active || !(state instanceof Marked m) || !m.aurora$glow() || state.isInvisible) return;
        cur = prepare(state.distanceToCameraSq);
        active = true;
        root = c;
        try {
            d.submit(state, cam, x, y, z, pose, wrap(c, SubmitNodeCollector.class));
        } catch (RuntimeException ex) {
            // une lueur ratée ne doit jamais faire planter le jeu
            if (!warned) {
                warned = true;
                gg.aurora.AuroraClient.LOGGER.warn("Friend Glow : {}", ex.toString());
            }
        } finally {
            active = false;
            root = null;
        }
    }

    /** Calcule les copies décalées selon l'effet choisi ; épaisseur constante à l'écran. */
    private static Copies prepare(double d2) {
        var mod = ExtraModules.FRIEND_GLOW;
        int style = (int) mod.opt("style", 0);
        int base = ExtraModules.friendGlowColor() & 0xFFFFFF;
        float t = (System.currentTimeMillis() % 3_600_000L) / 50f; // en ticks
        float r = 0.006f * (float) mod.opt("width", 2) * (float) Math.max(2, Math.sqrt(d2));
        Quaternionf q = Minecraft.getInstance().gameRenderer.getMainCamera().rotation();
        Vector3f right = q.transform(new Vector3f(1, 0, 0)), up = q.transform(new Vector3f(0, 1, 0));
        int rings = style == 1 ? 2 : 1, dirs = 6;
        int n = rings * dirs;
        float[] tx = new float[n], ty = new float[n], tz = new float[n];
        int[] col = new int[n];
        float wave = 0.5f + 0.5f * Mth.sin(t * 0.18f);
        int k = 0;
        for (int ring = 0; ring < rings; ring++) {
            float rr;
            int alpha;
            switch (style) {
                case 1 -> { // halo doux : anneau proche + anneau large très léger
                    rr = ring == 0 ? r * 0.8f : r * 2f;
                    alpha = ring == 0 ? 70 : 32;
                }
                case 2 -> { // pulsation : épaisseur et opacité respirent
                    rr = r * (0.7f + 0.8f * wave);
                    alpha = Math.round(70 + 120 * wave);
                }
                default -> { // contour net (et arc-en-ciel)
                    rr = r;
                    alpha = 150;
                }
            }
            for (int i = 0; i < dirs; i++) {
                double ang = Math.PI * 2 * (i + ring * 0.5) / dirs;
                float ox = (float) Math.cos(ang) * rr, oy = (float) Math.sin(ang) * rr;
                tx[k] = right.x * ox + up.x * oy;
                ty[k] = right.y * ox + up.y * oy;
                tz[k] = right.z * ox + up.z * oy;
                int rgb = style == 3 ? Mth.hsvToRgb(((t * 0.01f) + i / (float) dirs) % 1f, 0.75f, 1f) & 0xFFFFFF : base;
                col[k++] = (alpha << 24) | rgb;
            }
        }
        return new Copies(tx, ty, tz, col);
    }

    private static Identifier texture(RenderType type) {
        return HandGlow.textureOf(type);
    }

    /** Reflets d'enchantement, ombres… : pas de silhouette (ils recouvrent le modèle déjà traité). */
    private static boolean skip(Identifier tex) {
        String p = tex.getPath();
        return p.contains("glint") || p.contains("shadow");
    }

    @SuppressWarnings("unchecked")
    private static <T> T wrap(Object target, Class<T> iface) {
        return (T) Proxy.newProxyInstance(FriendGlow.class.getClassLoader(), new Class<?>[]{iface}, new Handler(target));
    }

    private record Handler(Object target) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) return method.invoke(target, args);
            // méthodes reconnues par leurs paramètres (noms « intermediary » en jeu)
            Class<?>[] pt = method.getParameterTypes();
            if (pt.length == 1 && pt[0] == int.class && OrderedSubmitNodeCollector.class.isAssignableFrom(method.getReturnType())) {
                return wrap(((SubmitNodeCollector) target).order((Integer) args[0]), OrderedSubmitNodeCollector.class);
            }
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
            if (method.getReturnType() != void.class) return method.invoke(target, args);
            glow(pt, args);
            return null; // ombres, textes, flammes, laisses : rien
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static void glow(Class<?>[] pt, Object[] args) {
            // la lueur passe avant tout le reste : l'entité la recouvre, seul le bord dépasse
            OrderedSubmitNodeCollector c = root.order(-1);
            Copies cp = cur;
            if (pt.length == 10 && pt[0] == Model.class && pt[2] == PoseStack.class && pt[3] == RenderType.class) {
                Identifier tex = texture((RenderType) args[3]);
                if (skip(tex)) return;
                RenderType sil = HandGlow.silhouetteOf(tex);
                PoseStack pose = (PoseStack) args[2];
                for (int i = 0; i < cp.n(); i++) {
                    pose.pushPose();
                    cp.shift(pose.last().pose(), i);
                    c.submitModel((Model) args[0], args[1], pose, sil, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, cp.col()[i],
                            (TextureAtlasSprite) args[7], 0, null);
                    pose.popPose();
                }
            } else if (pt.length == 9 && pt[0] == PoseStack.class && pt[6] == List.class && pt[7] == RenderType.class) {
                Identifier tex = texture((RenderType) args[7]);
                if (skip(tex)) return;
                List<BakedQuad> quads = (List<BakedQuad>) args[6];
                BakedQuad[] qa = quads.toArray(new BakedQuad[0]);
                c.submitCustomGeometry((PoseStack) args[0], HandGlow.silhouetteOf(tex), (p, vc) -> {
                    PoseStack.Pose moved = SCRATCH.last();
                    for (int i = 0; i < cp.n(); i++) {
                        moved.set(p);
                        cp.shift(moved.pose(), i);
                        int cc = cp.col()[i];
                        float a = (cc >>> 24) / 255f, r = ((cc >> 16) & 255) / 255f, g = ((cc >> 8) & 255) / 255f, b = (cc & 255) / 255f;
                        for (BakedQuad quad : qa) vc.putBulkData(moved, quad, r, g, b, a, FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                    }
                });
            } else if (pt.length == 11 && pt[0] == ModelPart.class && pt[1] == PoseStack.class && pt[2] == RenderType.class) {
                Identifier tex = texture((RenderType) args[2]);
                if (skip(tex)) return;
                ModelPart part = (ModelPart) args[0];
                TextureAtlasSprite sprite = (TextureAtlasSprite) args[5];
                c.submitCustomGeometry((PoseStack) args[1], HandGlow.silhouetteOf(tex), (p, vc) -> {
                    var out = sprite == null ? vc : sprite.wrap(vc);
                    for (int i = 0; i < cp.n(); i++) {
                        SCRATCH.last().set(p);
                        cp.shift(SCRATCH.last().pose(), i);
                        part.render(SCRATCH, out, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, cp.col()[i]);
                    }
                });
            }
        }
    }
}
