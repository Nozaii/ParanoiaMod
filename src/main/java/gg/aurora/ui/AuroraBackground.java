package gg.aurora.ui;

import gg.aurora.Settings;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;

import java.util.Random;

/**
 * Fond nuit violet : aurore qui dérive, étoiles, étoile filante et trois plans de montagnes.
 * À l'ouverture les montagnes montent depuis le bas (en décalé), à la fermeture elles redescendent.
 */
public final class AuroraBackground {
    private static final Identifier AURORA = Draw.tex("ui/aurora");
    private static final Identifier BACK = Draw.tex("ui/mountains_back");
    private static final Identifier MID = Draw.tex("ui/mountains_mid");
    private static final Identifier FRONT = Draw.tex("ui/mountains_front");
    private static final Identifier STAR = Draw.tex("ui/star");
    private static final float[] DELAYS = {0f, 0.11f, 0.22f};

    private static final int STARS = 90;
    private final float[] sx = new float[STARS], sy = new float[STARS], phase = new float[STARS], size = new float[STARS];
    private final Random rnd = new Random();
    private float shootT = -1, shootX, shootY, nextShoot = 3f;

    private final Spring[] rise = {new Spring(0, 70, 0.68f), new Spring(0, 70, 0.68f), new Spring(0, 70, 0.68f)};
    private final Spring sky = new Spring(0, 14, 1f);
    private final Spring veil = new Spring(0, 160, 1f);
    private float time, introClock, lastDt;
    private boolean sinking;

    public AuroraBackground() {
        Random r = new Random(1337);
        for (int i = 0; i < STARS; i++) {
            sx[i] = r.nextFloat();
            sy[i] = r.nextFloat() * 0.7f;
            phase[i] = r.nextFloat() * 6.28f;
            size[i] = 2.5f + r.nextFloat() * r.nextFloat() * 4f;
        }
    }

    /** Fait redescendre les montagnes (fermeture). */
    public void sink() {
        sinking = true;
    }

    /** true quand l'animation de fermeture est terminée. */
    public boolean sunk() {
        return sinking && rise[0].value < 0.02f && veil.value < 0.05f;
    }

    /** Avancement de la montée (0..1), sert à enchaîner l'apparition du contenu. */
    public float riseProgress() {
        return Draw.clamp01(rise[2].value);
    }

    /** Montagnes instantanément en place (retour d'un sous-écran). */
    public void skipIntro() {
        for (Spring s : rise) s.set(1);
        sky.set(1);
        veil.set(1);
        introClock = 10;
    }

    public void update(float dt) {
        time += dt;
        lastDt = dt;
        introClock += dt;
        for (int i = 0; i < rise.length; i++) {
            float target = sinking ? 0 : (introClock >= DELAYS[i] ? 1 : 0);
            rise[i].update(dt, target);
        }
        sky.update(dt, sinking ? 0 : 1);
        veil.update(dt, sinking ? (rise[0].value < 0.3f ? 0 : 1) : 1);
    }

    /** opacity : opacité du voile (plus faible en jeu pour laisser voir le monde flouté). */
    public void render(GuiGraphics g, float w, float h, float opacity, float parallaxX, float parallaxY) {
        float v = Draw.clamp01(veil.value) * opacity;
        float skyA = Draw.clamp01(sky.value) * v;
        int W = (int) Math.ceil(w), H = (int) Math.ceil(h);
        g.fill(0, 0, W, H, Draw.withAlpha(Theme.BG, v));
        g.fillGradient(0, 0, W, (int) (h * 0.6f), Draw.withAlpha(0x261D52, 0.55f * skyA), 0x00261D52);

        // aurore : deux couches qui dérivent en sens inverse et respirent
        float breathe = 0.75f + 0.25f * (float) Math.sin(time * 0.35);
        float ah = h * 0.78f;
        int tint = Theme.aurora();
        Draw.image(g, AURORA, -8 + parallaxX * 4, -6 + parallaxY * 3, w + 16, ah, 1024, 512,
                (time * 14f) % 1024, 0, 1024 * 0.9f, 512, Draw.withAlpha(tint, 0.42f * breathe * skyA));
        Draw.image(g, AURORA, -8 + parallaxX * 7, h * 0.04f + parallaxY * 5, w + 16, ah * 0.9f, 1024, 512,
                1024 - (time * 9f) % 1024, 0, 1024 * 0.6f, 512,
                Draw.withAlpha(Draw.lerpColor(tint, 0x6FD8FF, 0.35f), 0.26f * (1.6f - breathe) * skyA));

        if (Settings.stars) {
            for (int i = 0; i < STARS; i++) {
                float tw = 0.3f + 0.7f * (0.5f + 0.5f * (float) Math.sin(time * (0.8 + (i % 5) * 0.25) + phase[i]));
                float s = size[i];
                float x = sx[i] * w + parallaxX * 2, y = sy[i] * h + parallaxY * 1.5f;
                Draw.image(g, STAR, x - s / 2, y - s / 2, s, s, 32, 32, 0, 0, 32, 32,
                        Draw.withAlpha(Draw.lerpColor(0xFFFFFF, tint, 0.2f), tw * 0.85f * skyA));
            }
        }
        if (Settings.shootingStars) shootingStar(g, w, h, skyA);

        // montagnes : chaque plan monte depuis le bas avec un léger rebond
        float bh = h * 0.30f, mh = h * 0.24f, fh = h * 0.19f;
        float m = Math.min(1f, opacity + 0.2f);
        layer(g, BACK, 160, w, h, bh, 6, 4, 2f, 0.55f, rise[0].value, parallaxX * 5, parallaxY * 2, Theme.MOUNTAIN_BACK, m);
        layer(g, MID, 144, w, h, mh, 9, 5, 3f, 0.62f, rise[1].value, parallaxX * 8, parallaxY * 3, Theme.MOUNTAIN_MID, m);
        layer(g, FRONT, 128, w, h, fh, 14, 6, 4.5f, 0.7f, rise[2].value, parallaxX * 12, parallaxY * 4, Theme.MOUNTAIN_FRONT, m);
        g.fillGradient(0, (int) (h * 0.8f), W, H, 0x00000000, Draw.withAlpha(0x000000, 0.35f * v));
    }

    private void layer(GuiGraphics g, Identifier tex, int texH, float w, float h, float lh, float pad, float sink,
                       float drift, float span, float rise, float px, float py, int color, float alpha) {
        float offset = (1 - rise) * (lh + 24);
        float y = h - lh + sink + py + offset;
        Draw.image(g, tex, -pad + px, y, w + pad * 2, lh, 1024, texH, (time * drift) % 1024, 0, 1024 * span, texH,
                Draw.withAlpha(color, alpha));
        // comble le bas si la couche rebondit au-dessus de sa position
        if (y + lh < h) g.fill(0, (int) (y + lh - 1), (int) Math.ceil(w), (int) Math.ceil(h), Draw.withAlpha(color, alpha));
    }

    private void shootingStar(GuiGraphics g, float w, float h, float alpha) {
        if (shootT < 0) {
            nextShoot -= lastDt;
            if (nextShoot <= 0) {
                shootT = 0;
                shootX = w * (0.2f + rnd.nextFloat() * 0.7f);
                shootY = h * (0.05f + rnd.nextFloat() * 0.25f);
                nextShoot = 6f + rnd.nextFloat() * 8f;
            }
            return;
        }
        shootT += lastDt;
        float t = shootT / 0.9f;
        if (t >= 1) {
            shootT = -1;
            return;
        }
        float a = (float) Math.sin(t * Math.PI) * alpha;
        for (int k = 0; k < 40; k++) {
            float tt = t - k * 0.0035f;
            if (tt < 0) break;
            float x = shootX - tt * w * 0.25f, y = shootY + tt * h * 0.12f;
            float s = 3.4f - k * 0.06f;
            Draw.image(g, STAR, x - s / 2, y - s / 2, s, s, 32, 32, 0, 0, 32, 32, Draw.withAlpha(0xFFFFFF, a * 0.7f * (1 - k / 40f)));
        }
    }
}
