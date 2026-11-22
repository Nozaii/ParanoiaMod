package gg.aurora.ui;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import gg.aurora.Settings;
import gg.aurora.cosmetic.CosmeticsLayer;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.SafetyScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Random;
import java.util.function.Supplier;

/**
 * Menu principal Paranoia : écran « Press to enter », puis logo, boutons texte, rideaux de lumière,
 * étincelles et le skin du joueur debout dont la tête suit la souris.
 */
public class ParanoiaTitleScreen extends Screen {
    private static final Identifier LOGO = Draw.tex("ui/title_logo"), RAY = Draw.tex("ui/light_ray"),
            SPARKLE = Draw.tex("ui/sparkle"), STAR = Draw.tex("ui/star_outline"),
            CURTAIN_A = Draw.tex("ui/curtain_a"), CURTAIN_B = Draw.tex("ui/curtain_b"), GLOW = Draw.tex("ui/glow"), VIGNETTE = Draw.tex("ui/vignette");

    /** « Press to enter » n'apparaît qu'au premier affichage de la session. */
    private static boolean entered;

    private record Entry(String label, Runnable action) {}

    private record Spark(float x, float y, float size, float speed, float phase) {}

    private final Entry[] entries = {
            new Entry("Singleplayer", () -> minecraft.setScreen(new SelectWorldScreen(this))),
            new Entry("Multiplayer", () -> minecraft.setScreen(minecraft.options.skipMultiplayerWarning
                    ? new JoinMultiplayerScreen(this) : new SafetyScreen(this))),
            new Entry("Minecraft Settings", () -> minecraft.setScreen(new OptionsScreen(this, minecraft.options))),
            new Entry("Client", () -> minecraft.setScreen(new ClickGuiScreen(this))),
            new Entry("Quit Game", () -> minecraft.stop()),
    };
    private final float[] hover = new float[entries.length];
    private final Spark[] sparks = new Spark[34];
    private final float[] btnX0 = new float[5], btnX1 = new float[5], btnY = new float[5], btnY1 = new float[5];
    /** Centre vertical de chaque bouton (fraction de la hauteur), d'après la maquette de référence. */
    private static final float[] BTN_Y = {0.456f, 0.519f, 0.599f, 0.662f, 0.731f};

    /** Taille du texte des boutons : même largeur relative que « Singleplayer » sur la maquette. */
    private float btnScale() {
        return height * 0.129f / Math.max(1, Draw.width("Singleplayer", 1f));
    }

    private long start, last;
    private float time, enterT, fadeIn;
    private boolean entering;
    private float headYaw, headPitch, frameDt = 1f / 60;
    private Supplier<PlayerSkin> skin;
    /** Vrai tant que le skin vient d'un profil sans textures (profil pas encore chargé au lancement). */
    private boolean skinPending;

    /** Au lancement, le profil complet (avec le skin) arrive après l'écran titre : on refait la recherche dès qu'il est là. */
    private void refreshSkin() {
        if (skin != null && !skinPending) return;
        var gp = minecraft.getGameProfile();
        boolean has = gp.properties().containsKey("textures");
        if (skin == null || has) {
            skin = minecraft.getSkinManager().createLookup(gp, false);
            skinPending = !has;
        }
    }
    private final AuroraBackground background = new AuroraBackground();
    private final Spring parX = new Spring(0, 30, 1f), parY = new Spring(0, 30, 1f);
    /** Arrivée du joueur : temps écoulé depuis l'entrée, position de marche (animation des jambes). */
    private float walkClock = -1, walkPos, bodyYaw = 270;
    /** Marche physique : distance parcourue (blocs), vitesse (blocs/s), amplitude des jambes, distance totale. */
    private float walkDist, walkVel, walkAmp, walkTotal = 1;
    private boolean arrived;
    private final AvatarRenderState avatar = new AvatarRenderState();

    public ParanoiaTitleScreen() {
        super(Component.literal(Theme.NAME));
        Random r = new Random(7);
        for (int i = 0; i < sparks.length; i++) {
            // concentrées à droite, autour des rideaux de lumière, quelques-unes ailleurs
            float x = i < 13 ? r.nextFloat() * 0.16f : i < 26 ? 0.84f + r.nextFloat() * 0.16f : 0.55f + r.nextFloat() * 0.3f;
            sparks[i] = new Spark(x, r.nextFloat(), i % 4 == 0 ? 7 + r.nextFloat() * 4 : 2 + r.nextFloat() * 2.5f,
                    0.5f + r.nextFloat() * 1.2f, r.nextFloat() * 6.28f);
        }
        if (entered) {
            enterT = 1;
            walkClock = 99;
            arrived = true;
            bodyYaw = FACING;
            background.skipIntro();
        }
    }

    @Override
    protected void init() {
        if (start == 0) start = last = System.nanoTime();
        // style « Aucune » : pas d'écran d'accueil, tout est déjà en place
        if (Settings.menuAnim == 0 && !entered) {
            entered = true;
            enterT = 1;
            fadeIn = 1;
            walkClock = 99;
            arrived = true;
            bodyYaw = FACING;
            background.skipIntro();
        }
        refreshSkin();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    // ===================================================================== rendu

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        long now = System.nanoTime();
        float dt = Math.min(0.05f, (now - last) / 1e9f);
        last = now;
        int style = Settings.menuAnim;
        // minimaliste : décor calme ; aucune : décor figé
        float decor = style == 0 ? 0 : style == 1 ? 0.45f : 1f;
        frameDt = dt;
        time += dt * decor;
        fadeIn = Math.min(1, fadeIn + dt * (entered ? 4f : 0.9f));
        if (entering) {
            enterT = Math.min(1, enterT + dt / (style == 1 ? 0.55f : style == 3 ? 1.7f : 1.1f));
            if (enterT >= 1) entering = false;
        }

        if (walkClock >= 0) walkClock += dt;
        background.update(dt * decor);
        float par = style == 3 ? -3.5f : style == 2 ? -2f : 0;
        parX.update(dt, (mx / (float) Math.max(1, width) - 0.5f) * par);
        parY.update(dt, (my / (float) Math.max(1, height) - 0.5f) * par);
        g.fill(0, 0, width, height, 0xFF000000 | Theme.BG);
        background.render(g, width, height, 1f, parX.value, parY.value);
        if (style >= 2) drawCurtains(g);
        if (style != 1) drawSparks(g);
        Draw.image(g, VIGNETTE, -width * 0.15f, -height * 0.25f, width * 1.3f, height * 1.5f, 256, 256, 0, 0, 256, 256,
                Draw.withAlpha(0x000000, 0.35f));
    }

    /** Rideaux de lumière blanche le long du bord droit : textures de brins en S qui défilent et respirent. */
    private void drawCurtains(GuiGraphics g) {
        float w = width, h = height;
        Draw.image(g, GLOW, w * 0.72f, -h * 0.3f, w * 0.5f, h * 1.6f, 96, 96, 0, 0, 96, 96, Draw.withAlpha(Theme.accent(), 0.10f * fadeIn));
        Draw.image(g, GLOW, -w * 0.22f, -h * 0.3f, w * 0.5f, h * 1.6f, 96, 96, 0, 0, 96, 96, Draw.withAlpha(Theme.accent(), 0.08f * fadeIn));
        curtain(g, CURTAIN_A, w * 0.79f, w * 0.17f, 0.030f, 0.0f, 0.5f, false);
        curtain(g, CURTAIN_B, w * 0.86f, w * 0.15f, -0.022f, 1.7f, 0.45f, false);
        curtain(g, CURTAIN_A, w * 0.92f, w * 0.10f, 0.017f, 3.1f, 0.35f, false);
        curtain(g, CURTAIN_B, w * 0.04f, w * 0.17f, -0.026f, 0.8f, 0.5f, true);
        curtain(g, CURTAIN_A, -w * 0.02f, w * 0.15f, 0.020f, 2.4f, 0.45f, true);
        curtain(g, CURTAIN_B, w * 0.12f, w * 0.10f, 0.015f, 4.2f, 0.3f, true);
    }

    /** Un rideau : texture répétée verticalement, défilement + léger balancement horizontal. */
    private void curtain(GuiGraphics g, Identifier tex, float x, float cw, float speed, float phase, float alpha, boolean mirror) {
        float h = height, th = h * 1.25f;
        float scroll = ((time * speed + phase) % 1f + 1f) % 1f * th;
        float sway = (float) Math.sin(time * 0.35f + phase) * width * 0.008f;
        float a = alpha * (0.75f + 0.25f * (float) Math.sin(time * 0.5f + phase * 2)) * fadeIn * (Settings.menuAnim == 3 ? 1.3f : 1f);
        for (int k = -1; k <= 1; k++) {
            float y = scroll + k * th;
            if (y > h || y + th < 0) continue;
            Draw.image(g, tex, x + sway, y, cw, th, 128, 512, mirror ? 128 : 0, 0, mirror ? -128 : 128, 512, Draw.withAlpha(Draw.lerpColor(Theme.aurora(), 0xFFFFFF, 0.35f), a));
        }
    }

    private void drawSparks(GuiGraphics g) {
        for (Spark s : sparks) {
            float tw = (float) Math.sin(time * s.speed() + s.phase());
            float a = (float) Math.pow(Math.max(0, tw), 2.5) * fadeIn;
            if (a < 0.02f && s.size() <= 6) continue;
            float size = s.size() * (0.6f + 0.4f * a);
            float x = s.x() * width, y = s.y() * height;
            boolean big = s.size() > 6;
            Draw.image(g, big ? STAR : SPARKLE, x - size / 2, y - size / 2, size, size, 64, 64, 0, 0, 64, 64,
                    Draw.withAlpha(Draw.lerpColor(Theme.accent(), 0xFFFFFF, 0.6f), big ? (0.35f + 0.65f * a) * fadeIn : a));
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        if (debugMx >= 0) {
            mx = Math.round(debugMx * width);
            my = Math.round(debugMy * height);
        }
        float w = width, h = height;
        float e = Draw.easeInOut(enterT);
        boolean pointer = false;

        // ---- logo + titre : centrés sur l'écran d'accueil, remontent au-dessus des boutons après l'entrée
        // proportions de la maquette de référence (fractions de la hauteur d'écran) : logo centré à 29 %,
        // titre à 37 %, boutons de 46 % à 73 %, colonne au centre, joueur à 75 % de la largeur
        // entrée en deux temps : le logo part le premier avec un léger rebond, le titre suit avec un
        // petit retard ; une onde lumineuse part du logo au moment du clic
        float eLogo = entered ? backOut(Draw.clamp01(enterT * 1.3f)) : 0;
        float eTitle = entered ? backOut(Draw.clamp01((enterT - 0.1f) * 1.35f)) : 0;
        float pop = entered ? (float) Math.sin(Math.PI * Draw.clamp01(enterT * 2.2f)) : 0;
        float baseLogo = Draw.lerp(h * 0.13f, h * 0.107f, eLogo);
        float logoSize = baseLogo * (1 + 0.12f * pop);
        float titleScale = h * 0.170f / Math.max(1, Draw.width(Draw.title("Larp Client"), 1f));
        float splashY = h * 0.5f - h * 0.13f * 0.85f, finalY = h * 0.287f - h * 0.107f / 2;
        float blockY = Draw.lerp(splashY, finalY, eLogo) - (logoSize - baseLogo) / 2;
        float breathe = 0.5f + 0.5f * (float) Math.sin(time * 1.6f);
        float cx = w / 2;
        float lcy = blockY + logoSize / 2;
        // onde de choc + éclat derrière le logo
        if (entered && enterT < 0.7f) {
            float k = enterT / 0.7f, r = logoSize * (0.6f + 2.6f * Draw.easeOutCubic(k));
            float ra = (1 - k) * (1 - k) * fadeIn;
            for (int i = 0; i < 3; i++) {
                Draw.ring(g, cx - r - i, lcy - r - i, 2 * (r + i), 2 * (r + i), r + i, Draw.withAlpha(Theme.accent(), 0.35f * ra / (i + 1)));
            }
            Draw.image(g, GLOW, cx - logoSize * 2, lcy - logoSize * 2, logoSize * 4, logoSize * 4, 96, 96, 0, 0, 96, 96,
                    Draw.withAlpha(Draw.lerpColor(Theme.accent(), 0xFFFFFF, 0.3f), 0.45f * ra));
        }
        Draw.image(g, GLOW, cx - logoSize, blockY - logoSize * 0.5f, logoSize * 2, logoSize * 2, 96, 96, 0, 0, 96, 96,
                Draw.withAlpha(Theme.accent(), Math.min(1f, (0.16f + 0.10f * breathe + 0.25f * pop) * fadeIn)));
        g.pose().pushMatrix();
        g.pose().translate(cx, lcy);
        // petit basculement pendant le déplacement, qui se redresse à l'arrivée
        g.pose().rotate((float) Math.toRadians(-8 * pop));
        Draw.image(g, LOGO, -logoSize / 2, -logoSize / 2, logoSize, logoSize, 256, 256, 0, 0, 256, 256, Draw.withAlpha(0xFFFFFF, fadeIn));
        g.pose().popMatrix();
        Component title = Draw.title(Theme.NAME + " Client");
        float tySplash = splashY + h * 0.13f + h * 0.0175f, tyFinal = finalY + h * 0.107f + h * 0.0175f;
        float ty = Draw.lerp(tySplash, tyFinal, eTitle);
        // le titre s'élargit un instant pendant le trajet puis se pose
        float tScale = titleScale * (1 + 0.06f * (entered ? (float) Math.sin(Math.PI * Draw.clamp01((enterT - 0.1f) * 1.6f)) : 0));
        float tAlpha = fadeIn * (entered ? 0.55f + 0.45f * Draw.clamp01(Math.abs(eTitle - 0.5f) * 2) : 1);
        Draw.centered(g, title, cx, ty + 1.5f, tScale, Draw.withAlpha(0x000000, 0.4f * tAlpha));
        Draw.centered(g, title, cx, ty, tScale, Draw.withAlpha(0xFFFFFF, tAlpha));

        // ---- « Press to enter »
        float splashA = (1 - Draw.clamp01(enterT * 3)) * fadeIn;
        if (splashA > 0.01f) {
            float pulse = 0.55f + 0.45f * (0.5f + 0.5f * (float) Math.sin(time * 2.2f));
            Draw.centered(g, "Press to enter", w / 2, ty + h * 0.07f, btnScale(), Draw.withAlpha(Theme.TEXT_DIM, pulse * splashA));
        }

        // ---- boutons texte
        if (enterT > 0.3f) {
            float scale = btnScale(), lineH = 8 * scale;
            for (int i = 0; i < entries.length; i++) {
                float k = Draw.easeOutCubic(Draw.clamp01((enterT - 0.35f - i * 0.08f) / 0.4f));
                if (k <= 0) continue;
                String label = entries[i].label();
                float tw = Draw.width(label, scale);
                float y0 = h * BTN_Y[i] - lineH / 2;
                float y = y0 + (1 - k) * 8;
                float bw = Math.max(tw + 24, h * 0.30f);
                btnX0[i] = cx - bw / 2;
                btnX1[i] = cx + bw / 2;
                btnY[i] = y0 - lineH * 0.45f;
                btnY1[i] = y0 + lineH * 1.45f;
                boolean hv = entered && enterT >= 1 && mx >= btnX0[i] && mx <= btnX1[i] && my >= btnY[i] && my <= btnY1[i];
                hover[i] = Settings.menuAnim == 0 ? (hv ? 1 : 0) : Draw.smooth(hover[i], hv ? 1 : 0, Settings.menuAnim == 1 ? 24 : 14, frameDt);
                pointer |= hv;
                float hk = hover[i];
                int col = Draw.lerpColor(Theme.TEXT_DIM, Theme.TEXT, 0.55f + 0.45f * hk);
                if (hk > 0.01f) {
                    // barre sombre derrière le bouton survolé (comme sur Larp Client) + liseré d'accent
                    float bw2 = Draw.lerp(tw + 10, bw, hk), bh = lineH * 1.9f, bt = y - lineH * 0.45f;
                    Draw.rrect(g, cx - bw2 / 2, bt, bw2, bh, 4, Draw.withAlpha(0x000000, 0.32f * hk * k));
                    Draw.ring(g, cx - bw2 / 2, bt, bw2, bh, 4, Draw.withAlpha(0xFFFFFF, 0.08f * hk * k));
                    Draw.rrect(g, cx - bw2 / 2 + 2, bt + bh * 0.25f, 1.2f, bh * 0.5f, 0.6f, Draw.withAlpha(Theme.accent(), hk * k));
                }
                Draw.centered(g, label, cx, y, scale, Draw.withAlpha(col, k * (0.85f + 0.15f * hk)));
            }
        }

        // ---- skin du joueur
        if (walkClock >= 0) {
            // marche depuis le bord gauche à vitesse de marche réelle, freine, puis se tourne vers l'écran
            float scale = h * 0.27f, startX = w * 1.10f, endX = w * 0.755f;
            walkTotal = Math.max(0.1f, (startX - endX) / scale);
            if (!arrived) stepWalk(frameDt);
            float px = arrived ? endX : startX - walkDist * scale;
            float feet = h * 0.768f;
            renderAvatar(g, mx, my, px, feet, scale, arrived ? 1 : walkDist / walkTotal);
            // pseudo du compte au-dessus de la tête (comme le sélecteur de compte de la vidéo)
            if (arrived) {
                String name = minecraft.getGameProfile().name();
                float na = Draw.clamp01((walkClock - 0.2f) * 2) * fadeIn;
                // pseudo + flèche, souligné, au-dessus de la tête (comme la maquette)
                float ns = btnScale(), nameW = Draw.width(name, ns), nw = nameW + 12 * ns, nx = px - nw / 2, ny = feet - scale * 2.22f;
                Draw.text(g, name, nx, ny, ns, Draw.withAlpha(Theme.TEXT, na));
                Draw.icon(g, Icons.CARET_DOWN, nx + nameW + 7 * ns, ny + 4 * ns, 6 * ns, Draw.withAlpha(Theme.TEXT_DIM, na));
                Draw.rrect(g, px - nw * 0.4f, ny + 11 * ns, nw * 0.8f, 0.8f, 0.4f, Draw.withAlpha(0xFFFFFF, 0.35f * na));
            }
        }

        // ---- pied de page
        float fa = e * fadeIn;
        Draw.text(g, "Minecraft " + SharedConstants.getCurrentVersion().name(), 6, h - 12, 0.85f, Draw.withAlpha(Theme.TEXT_MUTED, fa));
        String right = Theme.NAME + " Client 1.3";
        Draw.text(g, right, w * 0.8f - Draw.width(right, 0.85f), h - 12, 0.85f, Draw.withAlpha(Theme.TEXT_MUTED, fa));

        if (pointer) g.requestCursor(CursorTypes.POINTING_HAND);
    }

    /** Courbe « back out » : dépasse légèrement la cible puis revient (mouvement vivant, pas linéaire). */
    private static float backOut(float t) {
        float c1 = 1.4f, c3 = c1 + 1, u = t - 1;
        return 1 + c3 * u * u * u + c1 * u * u;
    }

    /** Orientation du corps une fois arrivé : de trois quarts, tourné vers les boutons. */
    private static final float FACING = 208;

    /** Vitesse de marche (blocs/s, la marche vanilla est à 4,3), accélération et décélération (blocs/s²). */
    private static final float WALK_SPEED = 3.6f, WALK_ACC = 9f, WALK_DEC = 7f;

    private void stepWalk(float dt) {
        float remaining = walkTotal - walkDist;
        // vitesse max permettant de s'arrêter pile à l'arrivée (v² = 2·a·d)
        float brake = (float) Math.sqrt(2 * WALK_DEC * Math.max(0, remaining));
        float top = Settings.menuAnim == 3 ? WALK_SPEED * 0.72f : WALK_SPEED;
        walkVel = Math.min(Math.min(top, brake), walkVel + WALK_ACC * dt);
        walkDist = Math.min(walkTotal, walkDist + walkVel * dt);
        // comme le jeu : la phase des jambes avance de ~4 unités par bloc parcouru, l'amplitude suit la vitesse
        walkPos += walkVel * dt * 4f;
        // amplitude sans glissement des pieds : jambe ≈ 0,75 bloc, un pas ≈ 1,18 bloc → ~43° (le jeu exagère à ~69°)
        float targetAmp = Math.min(0.6f, walkVel * 4f / 20f * 0.75f);
        walkAmp += (targetAmp - walkAmp) * (1 - (float) Math.exp(-dt * 12));
        if (walkTotal - walkDist < 0.003f && walkVel < 0.4f) {
            arrived = true;
            walkVel = 0;
        }
    }

    /** Le joueur (feetY = position des pieds), tête orientée vers la souris une fois arrivé. */
    private void renderAvatar(GuiGraphics g, int mx, int my, float cx, float feetY, float scale, float walk) {
        AvatarRenderState s = avatar;
        refreshSkin();
        s.skin = skin.get();
        s.entityType = EntityType.PLAYER;
        s.id = CosmeticsLayer.PREVIEW_ID;
        s.showHat = s.showJacket = s.showLeftPants = s.showRightPants = s.showLeftSleeve = s.showRightSleeve = true;
        s.showCape = true;
        s.capeFlap = 0.15f + 0.1f * (float) Math.sin(time * 1.3f);
        s.capeLean = 4f;
        s.ageInTicks = time * 20;
        s.scale = 1;
        s.ageScale = 1;
        s.boundingBoxWidth = 0.6f;
        s.boundingBoxHeight = 1.8f;
        s.eyeHeight = 1.62f;
        s.lightCoords = 15728880;
        s.outlineColor = 0;
        if (arrived) walkAmp += (0 - walkAmp) * (1 - (float) Math.exp(-frameDt * 12));
        s.walkAnimationPos = walkPos;
        s.walkAnimationSpeed = walkAmp;
        // la cape suit le mouvement
        s.capeFlap += walkAmp * 0.5f;
        s.capeLean += walkAmp * 22f;
        s.nameTag = null;

        float eyeY = feetY - 1.62f * scale;
        float dx = (cx - mx) / (scale * 1.6f), dy = (eyeY - my) / (scale * 1.6f);
        float targetYaw = Math.max(-45, Math.min(45, (float) Math.toDegrees(Math.atan(dx)) * 0.8f));
        float targetPitch = Math.max(-28, Math.min(28, (float) -Math.toDegrees(Math.atan(dy)) * 0.6f));
        if (!entered || !arrived) targetYaw = targetPitch = 0;
        // de profil pendant la marche (vers la droite), puis face à l'écran
        bodyYaw = Draw.smooth(bodyYaw, arrived ? FACING : 270, 6, frameDt);
        headYaw = Draw.smooth(headYaw, targetYaw, 10, frameDt);
        headPitch = Draw.smooth(headPitch, targetPitch, 10, frameDt);
        s.bodyRot = bodyYaw;
        s.yRot = headYaw;
        s.xRot = headPitch;

        // ombre au sol
        Draw.image(g, GLOW, cx - scale * 0.7f, feetY - scale * 0.12f, scale * 1.4f, scale * 0.24f, 96, 96, 0, 0, 96, 96,
                Draw.withAlpha(0x000000, 0.6f));
        Draw.image(g, GLOW, cx - scale * 0.9f, feetY - scale * 1.3f, scale * 1.8f, scale * 1.6f, 96, 96, 0, 0, 96, 96,
                Draw.withAlpha(Theme.accent(), 0.10f));

        float half = scale * 1.4f;
        float centerY = feetY - 0.9f * scale;
        int x0 = Math.round(cx - half), x1 = Math.round(cx + half);
        int y0 = Math.round(centerY - half), y1 = Math.round(centerY + half);
        Quaternionf rot = new Quaternionf().rotateZ((float) Math.PI);
        Quaternionf cam = new Quaternionf().rotateX((float) Math.toRadians(4));
        rot.mul(cam);
        g.submitEntityRenderState(s, scale, new Vector3f(0, 0.9f + 0.0625f, 0), rot, cam, x0, y0, x1, y1);
    }

    // ===================================================================== entrées

    private void enter() {
        if (entered) return;
        entered = true;
        entering = true;
        walkClock = 0;
        walkDist = walkVel = walkAmp = 0;
        // minimaliste : le joueur est déjà en place, pas de marche
        arrived = Settings.menuAnim <= 1;
        if (arrived) bodyYaw = FACING;
        if (Settings.sounds) minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, 1.2f, 0.9f));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean dbl) {
        if (!entered) {
            enter();
            return true;
        }
        if (enterT < 1) return true;
        for (int i = 0; i < entries.length; i++) {
            if (event.x() >= btnX0[i] && event.x() <= btnX1[i] && event.y() >= btnY[i] && event.y() <= btnY1[i]) {
                if (Settings.sounds)
                    minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1f, 0.3f));
                entries[i].action().run();
                return true;
            }
        }
        return super.mouseClicked(event, dbl);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!entered) {
            enter();
            return true;
        }
        return super.keyPressed(event);
    }

    private float debugMx = -1, debugMy;

    /** Dev : lance l'animation d'entrée. */
    public void debugEnter() {
        enter();
    }

    /** Dev : avancement de l'arrivée du joueur (0..1). */
    public float walkProgress() {
        return walkClock < 0 ? 0 : arrived ? 1 : walkDist / walkTotal;
    }

    /** Dev : position de souris simulée (fraction de l'écran). */
    public void debugMouse(float x, float y) {
        debugMx = x;
        debugMy = y;
    }
}
