package gg.aurora.ui;

import gg.aurora.Config;
import gg.aurora.PlayerPages;
import gg.aurora.PlayerPages.Anim;
import gg.aurora.PlayerPages.Badge;
import gg.aurora.PlayerPages.Look;
import gg.aurora.PlayerPages.Page;
import gg.aurora.cosmetic.Cosmetics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.ClientMannequin;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.PlayerSkin;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Page de joueur : fond choisi (couleur, dégradé ou image) sur toute la fenêtre, en-tête avec avatar encadré,
 * nom, titre, statut, liens ; colonne gauche (vitrine, bio, cosmétiques, skins), colonne droite (skin 3D et
 * poses, statistiques, badges, Discord). Sa propre page s'édite dans un tiroir avec aperçu en direct.
 */
public class ProfileScreen extends SubScreen {
    private static final float HEAD_Y = 30, DRAWER_W = 214;
    private static final DateTimeFormatter SINCE = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    private final String key;
    private Page page;
    private final Spring yaw = new Spring(200, 90, 0.9f);
    private final Spring scroll = new Spring(0, 260, 1f);
    private final Spring drawer = new Spring(0, 240, 0.9f);
    private float scrollTarget, bodyY, bodyH, skinX, skinY, skinW, skinH;
    private boolean draggingSkin;
    private int pose = -1;
    private PlayerSkin shownSkin;
    private PlayerPages.Skin historySkin;
    private final List<Object[]> hits = new ArrayList<>();
    private final long openedAt = System.currentTimeMillis();
    private String flash, tip;
    private boolean flashError;
    private float flashAt = -10;

    // ---- édition (brouillon affiché en direct)
    private boolean editing;
    private String bio = "", caption = "", status = "";
    private boolean showDiscord, privateMode;
    private int bgType = 1, bg1 = -1, bg2 = -1, frame = 1, titleIdx, showcase = 15, accent = -1, cardStyle, cardAlpha = 55, radius = 1;
    /** Apparence affichée à cette image (page ou brouillon d'édition). */
    private Look cur = PlayerPages.defaultLook();

    /** Accent de la page (choisi par le joueur), sinon celui du thème du client. */
    private int acc() {
        return cur != null && cur.accent() >= 0 ? cur.accent() : Theme.accent();
    }
    private final Map<String, String> links = new LinkedHashMap<>();
    private final RowPanel editPanel;
    private Poser poser;

    public ProfileScreen(Screen parent, String userKey) {
        super(parent, "Profile");
        this.key = userKey;
        editPanel = new RowPanel(() -> dt, new RowPanel.Callbacks() {
            public void pointer() {
                pointer = true;
            }

            public void sound(float pitch) {
                click(pitch);
            }

            public void changed() {}
        });
    }

    /** Ma page. */
    public ProfileScreen(Screen parent) {
        this(parent, "me");
    }

    public static ProfileScreen open(Screen parent, UUID id) {
        return new ProfileScreen(parent, id.toString());
    }

    @Override
    protected float maxW() {
        return 660;
    }

    @Override
    protected float maxH() {
        return 370;
    }

    @Override
    public boolean typing() {
        return editing && editPanel.focused() != null;
    }

    @Override
    public boolean consumesEscape() {
        return editing;
    }

    private void hit(float x, float y, float w, float h, Runnable r) {
        hits.add(new Object[]{x, y, w, h, r});
    }

    private void say(String t, boolean err) {
        flash = t;
        flashError = err;
        flashAt = time();
    }

    private boolean mine() {
        return PlayerPages.mine(page) || "me".equals(key) && page != null && !page.failed();
    }

    // ======================================================================= apparence (page ou brouillon)

    private Look look() {
        if (!editing || page == null) return page == null || page.look() == null ? PlayerPages.defaultLook() : page.look();
        String title = titleIdx <= 0 || titleIdx > page.badges().size() ? null : page.badges().get(titleIdx - 1).id();
        return new Look(PlayerPages.BG_TYPES[bgType], bg1, bg2, page.look() == null ? null : page.look().background(),
                PlayerPages.FRAMES[frame], title, status, new LinkedHashMap<>(links), privateMode, showcase, accent, cardStyle, cardAlpha, radius);
    }

    private int color1(Look l) {
        return l.bg1() >= 0 ? l.bg1() : Draw.lerpColor(acc(), 0x0A0912, 0.55f);
    }

    private int color2(Look l) {
        return l.bg2() >= 0 ? l.bg2() : 0x0B0A14;
    }

    // ======================================================================= skin 3D

    /** Modèle joueur hors du monde, avec le skin voulu et une pose figée. */
    private static final class Poser extends ClientMannequin {
        PlayerSkin skin;

        Poser(net.minecraft.world.level.Level level) {
            super(level, Minecraft.getInstance().playerSkinRenderCache());
        }

        @Override
        public PlayerSkin getSkin() {
            return skin;
        }

        @Override
        public Component belowNameDisplay() {
            return null;
        }

        @Override
        public boolean isPickable() {
            return false;
        }
    }

    private void renderSkin(GuiGraphics g, float x0, float y0, float x1, float y1) {
        if (shownSkin == null) return;
        if (minecraft.level == null) {
            float s = Math.min(x1 - x0, y1 - y0) * 0.55f;
            PlayerFaceRenderer.draw(g, shownSkin, Math.round(x0 + (x1 - x0 - s) / 2), Math.round(y0 + (y1 - y0 - s) / 2), Math.round(s));
            return;
        }
        if (poser == null || poser.level() != minecraft.level) poser = new Poser(minecraft.level);
        poser.skin = shownSkin;
        poser.tickCount++;
        poser.avatarState().tick(poser.position(), net.minecraft.world.phys.Vec3.ZERO);
        var renderer = minecraft.getEntityRenderDispatcher().getRenderer(poser);
        EntityRenderState state = renderer.createRenderState(poser, 1f);
        state.lightCoords = 15728880;
        state.shadowPieces.clear();
        state.outlineColor = 0;
        state.nameTag = null;
        state.distanceToCameraSq = 0;
        float t = time() * 20;
        if (state instanceof AvatarRenderState as) {
            as.bodyRot = 180f + yaw.value;
            as.yRot = 0;
            as.xRot = 0;
            as.scoreText = null;
            as.ageInTicks = t;
            as.walkAnimationSpeed = 0;
            as.skin = shownSkin;
            as.showCape = false;
            switch (pose) {
                case 1 -> {
                    as.walkAnimationSpeed = 1f;
                    as.walkAnimationPos = t * 0.6f;
                }
                case 2 -> as.isCrouching = true;
                case 3 -> {
                    as.attackArm = HumanoidArm.RIGHT;
                    as.attackTime = (t / 8f) % 1f;
                }
                case 4 -> {
                    as.isFallFlying = true;
                    as.fallFlyingTimeInTicks = 20;
                    as.xRot = -70;
                }
                default -> {
                }
            }
        }
        Quaternionf rot = new Quaternionf().rotateZ((float) Math.PI);
        Quaternionf cam = new Quaternionf().rotateX((float) Math.toRadians(6));
        rot.mul(cam);
        float tall = pose == 2 ? 1.5f : pose == 4 ? 1.2f : 1.85f;
        float scale = (y1 - y0) / (tall * 1.35f);
        g.submitEntityRenderState(state, scale, new Vector3f(0, tall / 2f + 0.0625f, 0), rot, cam, Math.round(x0), Math.round(y0), Math.round(x1), Math.round(y1));
    }

    // ======================================================================= rendu

    @Override
    protected void renderPage(GuiGraphics g, int mx, int my, float a) {
        hits.clear();
        tip = null;
        if (page == null) page = PlayerPages.get(key);
        if (page == null || page.failed()) {
            Draw.text(g, "Profile", wx + 8 + Draw.width("Back", 1f) + 36, wy + 11, 1.05f, Draw.withAlpha(Theme.TEXT, a));
            String msg = page == null ? "Loading…" : page.error();
            Draw.centered(g, msg, wx + ww / 2, wy + wh / 2 - 4, 0.95f, Draw.withAlpha(page == null ? Theme.TEXT_DIM : 0xF07878, a));
            if (page != null) {
                float bw = Draw.width("Retry", 0.85f) + 20, bx = wx + (ww - bw) / 2, by = wy + wh / 2 + 12;
                button(g, bx, by, "Retry", null, false, mx, my, a, () -> {
                    PlayerPages.refresh(key);
                    page = null;
                });
            }
            return;
        }
        if (pose < 0) pose = page.poseIndex();
        if (shownSkin == null) {
            if (historySkin != null) shownSkin = PlayerPages.skinOf(historySkin);
            if (shownSkin == null) shownSkin = gg.aurora.Friends.skin(page.uuid(), page.name());
            if (shownSkin == null) shownSkin = net.minecraft.client.resources.DefaultPlayerSkin.get(page.uuid());
        }
        if (!draggingSkin && animated()) yaw.target += dt * 10;
        yaw.update(dt);
        drawer.update(dt, editing ? 1 : 0);
        Look l = look();
        cur = l;

        background(g, l, a);
        backButton(g, mx, my, a);
        if (page.hidden()) {
            Draw.icon(g, Icons.LOCK, wx + ww / 2, wy + wh / 2 - 16, 16, Draw.withAlpha(Theme.TEXT_DIM, a));
            Draw.centered(g, page.name(), wx + ww / 2, wy + wh / 2 + 2, 1.2f, Draw.withAlpha(Theme.TEXT, a));
            Draw.centered(g, "This profile is visible to friends only", wx + ww / 2, wy + wh / 2 + 18, 0.85f, Draw.withAlpha(Theme.TEXT_DIM, a));
            return;
        }
        topActions(g, mx, my, a);
        header(g, l, mx, my, a);

        // ---- corps défilant, deux colonnes
        float drawerW = DRAWER_W * Draw.clamp01(drawer.value);
        bodyY = wy + HEAD_Y + 62;
        bodyH = wy + wh - 8 - bodyY;
        float x0 = wx + 12, full = ww - 24 - drawerW;
        float rw = Math.max(150, full * 0.36f), lw = full - rw - GAP, rx = x0 + lw + GAP;
        scroll.update(dt, scrollTarget);
        g.enableScissor((int) x0 - 2, (int) bodyY, (int) (x0 + full + 2), (int) (bodyY + bodyH));
        float y0 = bodyY - scroll.value;
        float lh = leftColumn(g, l, x0, y0, lw, mx, my, a);
        float rh = rightColumn(g, l, rx, y0, rw, mx, my, a);
        g.disableScissor();
        float content = Math.max(lh, rh);
        scrollTarget = Math.max(0, Math.min(Math.max(0, content - bodyH), scrollTarget));
        if (content > bodyH) {
            float barH = Math.max(14, bodyH * bodyH / content), by = bodyY + (bodyH - barH) * (scroll.value / Math.max(1, content - bodyH));
            Draw.rrect(g, x0 + full + 3, by, 2, barH, 1, Draw.withAlpha(0xFFFFFF, 0.2f * a));
        }

        if (drawer.value > 0.01f) editDrawer(g, mx, my, a);

        if (tip != null) {
            float tw = Draw.width(tip, 0.75f) + 12, tx = Math.min(mx + 10, width - tw - 4), ty = my + 12;
            Draw.rrect(g, tx, ty, tw, 13, 4, Draw.withAlpha(0x0A0912, 0.96f));
            Draw.text(g, tip, tx + 6, ty + 3, 0.75f, Theme.TEXT | 0xFF000000);
        }
        if (flash != null && time() - flashAt < 2.4f) {
            float e = Draw.clamp01(Math.min((time() - flashAt) * 6, (2.4f - (time() - flashAt)) * 4));
            float fw = Draw.width(flash, 0.9f) + 20, fx = wx + (ww - fw) / 2, fy = wy + wh - 24 + (1 - e) * 6;
            Draw.rrect(g, fx, fy, fw, 15, 7.5f, Draw.withAlpha(flashError ? 0x3A1C22 : Theme.ROW_HOVER, 0.97f * e));
            Draw.text(g, flash, fx + 10, fy + 4, 0.9f, Draw.withAlpha(flashError ? 0xF07878 : Theme.TEXT, e));
        }
    }

    /** Fond de toute la fenêtre : couleur, dégradé vertical ou image (couvrante), puis un voile pour la lisibilité. */
    private void background(GuiGraphics g, Look l, float a) {
        int c1 = color1(l), c2 = color2(l);
        String type = l.bgType();
        Anim img = "image".equals(type) ? PlayerPages.pageImage(page, "background") : null;
        if (img != null && !img.failed() && !img.frames().isEmpty()) {
            g.enableScissor((int) wx, (int) wy, (int) (wx + ww), (int) (wy + wh));
            float k = Math.max(ww / img.w(), wh / img.h()), iw = img.w() * k, ih = img.h() * k;
            Draw.image(g, img.at(System.currentTimeMillis() - openedAt), wx + (ww - iw) / 2, wy + (wh - ih) / 2, iw, ih, img.w(), img.h(), 0, 0, img.w(), img.h(),
                    Draw.withAlpha(0xFFFFFF, a));
            g.disableScissor();
            Draw.rrect(g, wx, wy, ww, wh, 10, Draw.withAlpha(0x07060D, 0.5f * a));
        } else if ("color".equals(type)) {
            Draw.rrect(g, wx, wy, ww, wh, 10, Draw.withAlpha(c1, a));
            Draw.rrect(g, wx, wy, ww, wh, 10, Draw.withAlpha(0x07060D, 0.35f * a));
        } else {
            // dégradé : bandes arrondies en haut et en bas, fondu au milieu
            Draw.rrect(g, wx, wy, ww, 20, 10, Draw.withAlpha(c1, a));
            Draw.rrect(g, wx, wy + wh - 20, ww, 20, 10, Draw.withAlpha(c2, a));
            g.fillGradient(Math.round(wx), Math.round(wy + 10), Math.round(wx + ww), Math.round(wy + wh - 10),
                    Draw.withAlpha(c1, a), Draw.withAlpha(c2, a));
            Draw.rrect(g, wx, wy, ww, wh, 10, Draw.withAlpha(0x07060D, 0.2f * a));
        }
        Draw.ring(g, wx, wy, ww, wh, 10, Draw.withAlpha(0xFFFFFF, 0.08f * a));
    }

    private void backButton(GuiGraphics g, int mx, int my, float a) {
        float bx = wx + 8, by = wy + 8, bw = Draw.width("Back", 1f) + 26;
        boolean hv = inside(mx, my, bx, by, bw, 16);
        Draw.rrect(g, bx, by, bw, 16, 8, Draw.withAlpha(hv ? Theme.ROW_HOVER : 0x000000, (hv ? 0.95f : 0.35f) * a));
        Draw.icon(g, Icons.ARROW_LEFT, bx + 10, by + 8, 9, Draw.withAlpha(Theme.TEXT, a));
        Draw.text(g, "Back", bx + 18, by + 4.2f, 1f, Draw.withAlpha(Theme.TEXT, a));
        pointer |= hv;
    }

    private float button(GuiGraphics g, float x, float y, String label, String icon, boolean primary, int mx, int my, float a, Runnable r) {
        float w = Draw.width(label, 0.85f) + (icon == null ? 16 : 26), h = 16;
        boolean hv = inside(mx, my, x, y, w, h);
        int bg = primary ? Draw.lerpColor(acc(), 0xFFFFFF, hv ? 0.1f : 0) : hv ? Theme.ROW_HOVER : 0x000000;
        Draw.rrect(g, x, y, w, h, 5, Draw.withAlpha(bg, (primary || hv ? 0.95f : 0.4f) * a));
        float tx = x + 8;
        if (icon != null) {
            Draw.icon(g, icon, x + 10, y + 8, 8, Draw.withAlpha(primary ? 0xFFFFFF : Theme.TEXT, a));
            tx = x + 18;
        }
        Draw.text(g, label, tx, y + 4, 0.85f, Draw.withAlpha(primary ? 0xFFFFFF : Theme.TEXT, a));
        hit(x, y, w, h, r);
        pointer |= hv;
        return w;
    }

    private void topActions(GuiGraphics g, int mx, int my, float a) {
        float x = wx + ww - 10, y = wy + 8;
        if (mine()) {
            if (editing) {
                float sw = Draw.width("Save", 0.85f) + 26;
                x -= sw;
                button(g, x, y, "Save", Icons.CHECK, true, mx, my, a, this::save);
                float cw = Draw.width("Cancel", 0.85f) + 16;
                x -= cw + 6;
                button(g, x, y, "Cancel", null, false, mx, my, a, () -> {
                    editing = false;
                    editPanel.focus(null);
                    click(0.9f);
                });
            } else {
                float ew = Draw.width("Edit profile", 0.85f) + 26;
                x -= ew;
                button(g, x, y, "Edit profile", Icons.PENCIL, false, mx, my, a, this::startEdit);
            }
        } else {
            String l = String.valueOf(page.likes());
            float lw = Draw.width(l, 0.85f) + 28;
            x -= lw;
            boolean hv = inside(mx, my, x, y, lw, 16);
            Draw.rrect(g, x, y, lw, 16, 5, Draw.withAlpha(hv ? Theme.ROW_HOVER : 0x000000, (hv ? 0.95f : 0.4f) * a));
            Draw.icon(g, page.liked() ? Icons.HEART_FILL : Icons.HEART, x + 10, y + 8, 9, Draw.withAlpha(page.liked() ? 0xF06A8A : Theme.TEXT, a));
            Draw.text(g, l, x + 19, y + 4, 0.85f, Draw.withAlpha(Theme.TEXT, a));
            Page p = page;
            hit(x, y, lw, 16, () -> {
                click(p.liked() ? 0.9f : 1.3f);
                PlayerPages.like(p.uuid(), err -> {
                    if (err != null) say(err, true);
                    page = null;
                });
            });
            pointer |= hv;
        }
    }

    /** Avatar encadré, nom, titre, statut, infos et liens. */
    private void header(GuiGraphics g, Look l, int mx, int my, float a) {
        float ax = wx + 14, ay = wy + HEAD_Y, as = 52;
        avatarFrame(g, l.frame(), ax, ay, as, a);
        Draw.rrect(g, ax + 3, ay + 3, as - 6, as - 6, 4, Draw.withAlpha(0x0A0912, a));
        if (shownSkin != null) PlayerFaceRenderer.draw(g, shownSkin, Math.round(ax + 6), Math.round(ay + 6), Math.round(as - 12));
        float tx = ax + as + 12, ty = ay + 2;
        Draw.text(g, page.name(), tx, ty, 1.5f, Draw.withAlpha(Theme.TEXT, a));
        float nx = tx + Draw.width(page.name(), 1.5f) + 8;
        Badge title = null;
        if (l.title() != null) for (Badge b : page.badges()) if (b.id().equals(l.title())) title = b;
        if (title != null) {
            float cw = Draw.width(title.name(), 0.75f) + 16;
            Draw.rrect(g, nx, ty + 2, cw, 12, 6, Draw.withAlpha(title.color(), 0.18f * a));
            Draw.rrect(g, nx + 5, ty + 6.5f, 3, 3, 1.5f, Draw.withAlpha(title.color(), a));
            Draw.text(g, title.name(), nx + 11, ty + 4.5f, 0.75f, Draw.withAlpha(title.color(), a));
        }
        String st = l.status() == null || l.status().isBlank() ? (mine() ? "Set a status in Edit profile" : "") : l.status();
        if (!st.isEmpty()) Draw.text(g, fit(st, 0.9f, ww - (tx - wx) - 160), tx, ty + 17, 0.9f,
                Draw.withAlpha(l.status() == null || l.status().isBlank() ? Theme.TEXT_MUTED : Theme.TEXT_DIM, a));
        // infos : présence, ancienneté, vues
        String presence = page.online() ? "Online" : page.lastSeen() > 0 ? "Seen " + ago(page.lastSeen()) : "Offline";
        String since = page.created() > 0 ? "Member since " + SINCE.format(Instant.ofEpochMilli(page.created()).atZone(ZoneId.systemDefault())) : "";
        float ix = tx;
        Draw.rrect(g, ix, ty + 31.5f, 5, 5, 2.5f, Draw.withAlpha(page.online() ? 0x5EE6A8 : Theme.TEXT_MUTED, a));
        ix += 9;
        String info = presence + (since.isEmpty() ? "" : "  ·  " + since) + "  ·  " + page.views() + (page.views() == 1 ? " view" : " views")
                + "  ·  " + page.likes() + (page.likes() == 1 ? " like" : " likes");
        Draw.text(g, info, ix, ty + 30, 0.75f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        // liens
        float lx = tx, ly = ty + 43;
        for (int i = 0; i < PlayerPages.LINK_SITES.length; i++) {
            String site = PlayerPages.LINK_SITES[i], handle = l.links().get(site);
            if (handle == null || handle.isBlank()) continue;
            String label = PlayerPages.LINK_NAMES[i];
            float cw = Draw.width(label, 0.72f) + 12;
            if (lx + cw > wx + ww - 12) break;
            boolean hv = inside(mx, my, lx, ly, cw, 12);
            Draw.rrect(g, lx, ly, cw, 12, 4, Draw.withAlpha(hv ? Theme.ROW_HOVER : 0x000000, (hv ? 0.95f : 0.35f) * a));
            Draw.text(g, label, lx + 6, ly + 2.6f, 0.72f, Draw.withAlpha(Theme.TEXT_DIM, a));
            if (hv) tip = linkUrl(site, handle);
            String url = linkUrl(site, handle);
            hit(lx, ly, cw, 12, () -> minecraft.setScreen(new net.minecraft.client.gui.screens.ConfirmLinkScreen(ok -> {
                if (ok) net.minecraft.util.Util.getPlatform().openUri(url);
                minecraft.setScreen(this);
            }, url, true)));
            pointer |= hv;
            lx += cw + 4;
        }
    }

    private static String linkUrl(String site, String h) {
        return switch (site) {
            case "youtube" -> "https://www.youtube.com/@" + h;
            case "twitch" -> "https://www.twitch.tv/" + h;
            case "tiktok" -> "https://www.tiktok.com/@" + h;
            case "x" -> "https://x.com/" + h;
            case "instagram" -> "https://www.instagram.com/" + h;
            default -> "https://github.com/" + h;
        };
    }

    /** Cadres d'avatar : traits nets, deux seulement s'animent doucement (Neon, Flame). */
    private void avatarFrame(GuiGraphics g, String f, float x, float y, float s, float a) {
        float t = (System.currentTimeMillis() - openedAt) / 1000f;
        float pulse = 0.75f + 0.25f * (float) Math.sin(t * 2.4f);
        switch (f == null ? "ring" : f) {
            case "none" -> {
            }
            case "double" -> {
                Draw.ring(g, x, y, s, s, 8, Draw.withAlpha(acc(), a));
                Draw.ring(g, x - 3, y - 3, s + 6, s + 6, 10, Draw.withAlpha(0xFFFFFF, 0.45f * a));
            }
            case "gold" -> {
                Draw.rrect(g, x - 2, y - 2, s + 4, s + 4, 9, Draw.withAlpha(0xC8963E, a));
                Draw.rrect(g, x - 1, y - 1, s + 2, s + 2, 8.5f, Draw.withAlpha(0xF5D27A, a));
                Draw.rrect(g, x + 1, y + 1, s - 2, s - 2, 7, Draw.withAlpha(0x0A0912, a));
            }
            case "neon" -> {
                Draw.glow(g, x, y, s, s, 8, Draw.withAlpha(acc(), 0.35f * pulse * a));
                Draw.ring(g, x - 1, y - 1, s + 2, s + 2, 9, Draw.withAlpha(Draw.lerpColor(acc(), 0xFFFFFF, 0.3f), a));
            }
            case "frost" -> {
                Draw.ring(g, x - 1, y - 1, s + 2, s + 2, 9, Draw.withAlpha(0xBFE6FF, a));
                for (int i = 0; i < 4; i++) {
                    float cx = i % 2 == 0 ? x - 2 : x + s - 4, cy = i < 2 ? y - 2 : y + s - 4;
                    Draw.rrect(g, cx, cy, 6, 6, 1.5f, Draw.withAlpha(0xE8F6FF, a));
                }
            }
            case "flame" -> {
                Draw.glow(g, x, y + 2, s, s, 7, Draw.withAlpha(0xFF6A2B, 0.3f * pulse * a));
                Draw.ring(g, x - 1, y - 1, s + 2, s + 2, 9, Draw.withAlpha(Draw.lerpColor(0xFF6A2B, 0xFFC24A, pulse), a));
            }
            case "royal" -> {
                Draw.ring(g, x - 1, y - 1, s + 2, s + 2, 9, Draw.withAlpha(0xF5C451, a));
                Draw.ring(g, x - 4, y - 4, s + 8, s + 8, 11, Draw.withAlpha(0x8A5CFF, 0.8f * a));
            }
            default -> Draw.ring(g, x - 1, y - 1, s + 2, s + 2, 9, Draw.withAlpha(acc(), 0.9f * a));
        }
    }

    // ---- cartes

    /** Écart entre les cartes (compact). */
    private static final float GAP = 5;

    private float radius() {
        return cur == null ? 6 : new float[]{2, 6, 10}[Math.max(0, Math.min(2, cur.radius()))];
    }

    /** Fond d'une carte selon le style de la page : verre, plein, contour ou transparent. */
    private void cardBg(GuiGraphics g, float x, float y, float w, float h, float a) {
        float r = radius(), op = cur == null ? 0.55f : cur.cardAlpha() / 100f;
        int style = cur == null ? 0 : cur.cardStyle();
        switch (style) {
            case 1 -> {
                Draw.rrect(g, x, y, w, h, r, Draw.withAlpha(Draw.lerpColor(Theme.PANEL, acc(), 0.06f), (0.35f + 0.65f * op) * a));
                Draw.ring(g, x, y, w, h, r, Draw.withAlpha(0xFFFFFF, 0.05f * a));
            }
            case 2 -> {
                if (op > 0) Draw.rrect(g, x, y, w, h, r, Draw.withAlpha(0x000000, 0.3f * op * a));
                Draw.ring(g, x, y, w, h, r, Draw.withAlpha(acc(), (0.25f + 0.5f * op) * a));
            }
            case 3 -> {
            }
            default -> {
                Draw.rrect(g, x, y, w, h, r, Draw.withAlpha(0x000000, 0.6f * op * a));
                Draw.ring(g, x, y, w, h, r, Draw.withAlpha(0xFFFFFF, 0.07f * a));
            }
        }
    }

    private float card(GuiGraphics g, float x, float y, float w, float h, String title, String meta, float a) {
        cardBg(g, x, y, w, h, a);
        if (title != null) {
            Draw.rrect(g, x + 8, y + 7.5f, 2, 7, 1, Draw.withAlpha(acc(), a));
            Draw.text(g, title, x + 13, y + 7, 0.82f, Draw.withAlpha(Theme.TEXT, a));
            if (meta != null) Draw.text(g, meta, x + w - 8 - Draw.width(meta, 0.68f), y + 8, 0.68f, Draw.withAlpha(Theme.TEXT_MUTED, a));
            return y + 19;
        }
        return y + 7;
    }

    /** Colonne gauche : vitrine (bannière ou visuel), bio, cosmétiques, historique des skins. Retourne la hauteur. */
    private float leftColumn(GuiGraphics g, Look l, float x, float y0, float w, int mx, int my, float a) {
        float y = y0;
        long now = System.currentTimeMillis() - openedAt;
        // vitrine
        Anim banner = PlayerPages.pageImage(page, "banner");
        Anim visual = PlayerPages.pageImage(page, "visual");
        Anim show = banner != null && !banner.failed() && !banner.frames().isEmpty() ? banner
                : visual != null && !visual.failed() && !visual.frames().isEmpty() ? visual : null;
        boolean loading = banner == null || visual == null;
        if (show != null || loading || mine()) {
            // sans image : une simple ligne d'invitation, pas un grand cadre vide
            float h = show != null ? Math.min(120, w * 0.38f) : 30;
            cardBg(g, x, y, w, h, a);
            if (show != null) {
                float k = Math.max(w / show.w(), h / show.h()), iw = show.w() * k, ih = show.h() * k;
                // ciseaux imbriqués : la pile intersecte avec la zone du corps, on referme seulement le nôtre
                g.enableScissor((int) x, (int) y, (int) (x + w), (int) (y + h));
                Draw.image(g, show.at(now), x + (w - iw) / 2, y + (h - ih) / 2, iw, ih, show.w(), show.h(), 0, 0, show.w(), show.h(), Draw.withAlpha(0xFFFFFF, a));
                g.disableScissor();
            } else {
                Draw.centered(g, loading ? "Loading…" : "Add a banner in Edit profile", x + w / 2, y + h / 2 - 4, 0.85f, Draw.withAlpha(Theme.TEXT_MUTED, a));
            }
            Draw.ring(g, x, y, w, h, 8, Draw.withAlpha(0xFFFFFF, 0.08f * a));
            y += h + 3;
            if (show == visual && show != null && page.caption() != null && !page.caption().isBlank()) {
                Draw.centered(g, fit(page.caption(), 0.75f, w), x + w / 2, y, 0.75f, Draw.withAlpha(Theme.TEXT_DIM, a));
                y += 11;
            }
            y += 6;
        }
        // bio
        String bioText = page.bio() == null || page.bio().isBlank() ? (mine() ? "Write a few words about you in Edit profile." : "No bio yet.") : page.bio();
        List<String> lines = wrap(editing ? (bio.isBlank() ? bioText : bio) : bioText, w - 20, 0.9f, 6);
        float h = 22 + lines.size() * 11 + 6;
        float cy = card(g, x, y, w, h, "About", null, a);
        boolean empty = (editing ? bio : page.bio()) == null || (editing ? bio : page.bio()).isBlank();
        for (String line : lines) {
            Draw.text(g, line, x + 10, cy, 0.9f, Draw.withAlpha(empty ? Theme.TEXT_MUTED : Theme.TEXT, a));
            cy += 11;
        }
        y += h + GAP;
        // cosmétiques portés
        if (l.shows(Look.SHOW_COSMETICS) && !page.cosmetics().isEmpty()) {
            float ch = 22 + chipsHeight(page.cosmetics().size(), w) + 6;
            cy = card(g, x, y, w, ch, "Cosmetics", page.cosmetics().size() + " equipped", a);
            float cx = x + 10;
            for (var e : page.cosmetics().entrySet()) {
                String[] info = cosmetic(e.getKey(), e.getValue());
                float cw = Draw.width(info[0], 0.78f) + 22;
                if (cx + cw > x + w - 10) {
                    cx = x + 10;
                    cy += 16;
                }
                int col = Integer.parseInt(info[1]);
                Draw.rrect(g, cx, cy, cw, 13, 4, Draw.withAlpha(Theme.ROW, 0.9f * a));
                Draw.rrect(g, cx + 6, cy + 5, 4, 4, 2, Draw.withAlpha(col, a));
                Draw.text(g, info[0], cx + 14, cy + 3, 0.78f, Draw.withAlpha(Theme.TEXT, a));
                if (inside(mx, my, cx, cy, cw, 13) && my >= bodyY && my < bodyY + bodyH) tip = info[2];
                cx += cw + 4;
            }
            y += ch + GAP;
        }
        // historique des skins
        if (l.shows(Look.SHOW_SKINS) && !page.skins().isEmpty()) {
            float fs = 24, gap = 5;
            int per = Math.max(1, (int) ((w - 20 + gap) / (fs + gap)));
            int rows = Math.min(2, (page.skins().size() + per - 1) / per);
            float sh = 22 + rows * (fs + gap) + 4;
            cy = card(g, x, y, w, sh, "Skin history", page.skins().size() + (page.skins().size() == 1 ? " skin" : " skins"), a);
            for (int i = 0; i < Math.min(per * 2, page.skins().size()); i++) {
                PlayerPages.Skin s = page.skins().get(i);
                float fx = x + 10 + (i % per) * (fs + gap), fy = cy + (i / per) * (fs + gap);
                boolean on = s == historySkin || historySkin == null && i == 0;
                boolean hv = inside(mx, my, fx, fy, fs, fs) && my >= bodyY && my < bodyY + bodyH;
                Draw.rrect(g, fx, fy, fs, fs, 4, Draw.withAlpha(Theme.ROW, a));
                PlayerSkin sk = PlayerPages.skinOf(s);
                if (sk != null) PlayerFaceRenderer.draw(g, sk, Math.round(fx + 3), Math.round(fy + 3), (int) fs - 6);
                if (on) Draw.ring(g, fx, fy, fs, fs, 4, Draw.withAlpha(acc(), 0.9f * a));
                else if (hv) Draw.ring(g, fx, fy, fs, fs, 4, Draw.withAlpha(0xFFFFFF, 0.3f * a));
                if (hv) tip = "Since " + SINCE.format(Instant.ofEpochMilli(s.first()).atZone(ZoneId.systemDefault()));
                hit(fx, Math.max(bodyY, fy), fs, fs, () -> {
                    historySkin = s;
                    shownSkin = null;
                    click(1.05f);
                });
                pointer |= hv;
            }
            y += sh + GAP;
        }
        return y - y0;
    }

    private float chipsHeight(int n, float w) {
        int per = Math.max(1, (int) ((w - 20) / 92));
        return ((n + per - 1) / per) * 16f;
    }

    /** Nom affiché, couleur et infobulle d'un cosmétique du profil. */
    private static String[] cosmetic(String slot, String id) {
        String label = switch (slot) {
            case "cape" -> "Cape";
            case "wings" -> "Wings";
            case "aura" -> "Aura";
            case "halo" -> "Head";
            case "pet" -> "Pet";
            case "back" -> "Back";
            case "shield" -> "Shield";
            default -> "Trail";
        };
        Cosmetics.Type t = switch (slot) {
            case "wings" -> Cosmetics.Type.WINGS;
            case "aura" -> Cosmetics.Type.AURA;
            case "halo" -> Cosmetics.Type.HALO;
            case "pet" -> Cosmetics.Type.PET;
            case "back" -> Cosmetics.Type.BACK;
            case "shield" -> Cosmetics.Type.SHIELD;
            default -> null;
        };
        if (t != null) {
            Cosmetics.Item it = Cosmetics.find(t, id);
            if (it != null) return new String[]{it.name(), String.valueOf(it.rarity().color), label + "  ·  " + it.rarity().label};
        }
        if (slot.equals("cape")) {
            var c = gg.aurora.Capes.byId(id);
            if (c != null) return new String[]{c.name(), String.valueOf(c.rarity().color), "Cape  ·  " + c.rarity().label};
            return new String[]{"Imported cape", String.valueOf(0xD7DCE5), "Cape  ·  Imported"};
        }
        return new String[]{label, String.valueOf(Theme.TEXT_DIM), label};
    }

    /** Colonne droite : skin 3D et poses, statistiques, badges, Discord. Retourne la hauteur. */
    private float rightColumn(GuiGraphics g, Look l, float x, float y0, float w, int mx, int my, float a) {
        float y = y0;
        // skin
        float sh = 128;
        card(g, x, y, w, sh, null, null, a);
        skinX = x;
        skinY = y;
        skinW = w;
        skinH = sh - 26;
        g.enableScissor((int) x, (int) y, (int) (x + w), (int) (y + sh - 26));
        renderSkin(g, x + 8, y + 6, x + w - 8, y + sh - 26);
        g.disableScissor();
        pointer |= inside(mx, my, x, y, w, sh - 26) && my >= bodyY && my < bodyY + bodyH;
        float py = y + sh - 20;
        Draw.text(g, PlayerPages.POSE_NAMES[pose], x + 10, py + 1, 0.75f, Draw.withAlpha(Theme.TEXT_DIM, a));
        for (int i = PlayerPages.POSES.length - 1; i >= 0; i--) {
            int idx = i;
            float dx = x + w - 14 - (PlayerPages.POSES.length - 1 - i) * 12, dy = py + 4;
            boolean on = i == pose, hv = inside(mx, my, dx - 5, dy - 5, 10, 10) && my >= bodyY && my < bodyY + bodyH;
            Draw.rrect(g, dx - (on ? 3 : 2), dy - (on ? 3 : 2), on ? 6 : 4, on ? 6 : 4, on ? 3 : 2,
                    Draw.withAlpha(on ? acc() : hv ? Theme.TEXT_DIM : Theme.TEXT_MUTED, a));
            if (hv) tip = PlayerPages.POSE_NAMES[i];
            hit(dx - 5, dy - 5, 10, 10, () -> {
                pose = idx;
                click(1.05f);
            });
            pointer |= hv;
        }
        y += sh + GAP;
        // statistiques
        if (l.shows(Look.SHOW_STATS)) {
            var s = page.stats();
            String[][] cells = {{"Playtime", playtime(s.playtime())}, {"K/D", s.kd()}, {"Kills", String.valueOf(s.kills())},
                    {"Deaths", String.valueOf(s.deaths())}, {"Best streak", String.valueOf(s.bestStreak())}};
            float ch = 22 + 3 * 26 + 2;
            float cy = card(g, x, y, w, ch, "Stats", "Counted by Paranoia", a);
            float cw = (w - 20 - 6) / 2;
            for (int i = 0; i < cells.length; i++) {
                float cx = x + 10 + (i % 2) * (cw + 6), yy = cy + (i / 2) * 26;
                Draw.rrect(g, cx, yy, cw, 22, 5, Draw.withAlpha(Theme.ROW, 0.85f * a));
                Draw.text(g, cells[i][1], cx + 7, yy + 3, 1f, Draw.withAlpha(Theme.TEXT, a));
                Draw.text(g, cells[i][0], cx + 7, yy + 13, 0.65f, Draw.withAlpha(Theme.TEXT_MUTED, a));
            }
            y += ch + GAP;
        }
        // badges
        if (l.shows(Look.SHOW_BADGES)) {
            List<Badge> bs = page.badges();
            float rows = bs.isEmpty() ? 1 : bs.size();
            float ch = 22 + Math.min(8, rows) * 15 + 4;
            float cy = card(g, x, y, w, ch, "Badges", String.valueOf(bs.size()), a);
            if (bs.isEmpty()) Draw.text(g, "None yet", x + 10, cy + 2, 0.78f, Draw.withAlpha(Theme.TEXT_MUTED, a));
            for (int i = 0; i < Math.min(8, bs.size()); i++) {
                Badge b = bs.get(i);
                float by = cy + i * 15;
                boolean hv = inside(mx, my, x + 6, by - 1, w - 12, 14) && my >= bodyY && my < bodyY + bodyH;
                if (hv) Draw.rrect(g, x + 6, by - 1, w - 12, 14, 4, Draw.withAlpha(Theme.ROW, a));
                Draw.rrect(g, x + 11, by + 3, 6, 6, 3, Draw.withAlpha(b.color(), a));
                Draw.text(g, b.name(), x + 22, by + 2, 0.8f, Draw.withAlpha(Theme.TEXT, a));
                String r = Badge.RARITY[Math.max(0, Math.min(3, b.rarity()))];
                Draw.text(g, r, x + w - 10 - Draw.width(r, 0.68f), by + 2.6f, 0.68f, Draw.withAlpha(b.color(), 0.85f * a));
                if (hv) tip = b.desc();
            }
            y += ch + GAP;
        }
        // Discord
        PlayerPages.Discord d = page.discord();
        if (d != null && (page.showDiscord() || mine())) {
            float ch = 22 + 34 + (d.badges() == null || d.badges().isEmpty() ? 0 : 16) + 4;
            float cy = card(g, x, y, w, ch, "Discord", mine() && !page.showDiscord() ? "Only you" : null, a);
            float as = 28;
            Anim av = d.avatar() == null ? null : PlayerPages.remoteImage(d.avatar());
            Draw.rrect(g, x + 10, cy, as, as, as / 2, Draw.withAlpha(Theme.ROW, a));
            if (av != null && !av.failed() && !av.frames().isEmpty())
                Draw.image(g, av.at(0), x + 10, cy, as, as, av.w(), av.h(), 0, 0, av.w(), av.h(), Draw.withAlpha(0xFFFFFF, a));
            Anim deco = d.decoration() == null ? null : PlayerPages.remoteImage(d.decoration());
            if (deco != null && !deco.failed() && !deco.frames().isEmpty()) {
                float ds = as * 1.2f;
                Draw.image(g, deco.at(now()), x + 10 - (ds - as) / 2, cy - (ds - as) / 2, ds, ds, deco.w(), deco.h(), 0, 0, deco.w(), deco.h(), Draw.withAlpha(0xFFFFFF, a));
            }
            Draw.text(g, fit(d.name() == null ? d.username() : d.name(), 1f, w - as - 30), x + 10 + as + 8, cy + 4, 1f, Draw.withAlpha(Theme.TEXT, a));
            Draw.text(g, "@" + d.username(), x + 10 + as + 8, cy + 16, 0.75f, Draw.withAlpha(Theme.TEXT_DIM, a));
            if (d.badges() != null && !d.badges().isEmpty()) {
                float bx = x + 10, by = cy + as + 6;
                for (String b : d.badges()) {
                    float bw = Draw.width(b, 0.68f) + 10;
                    if (bx + bw > x + w - 10) break;
                    Draw.rrect(g, bx, by, bw, 12, 4, Draw.withAlpha(Theme.ROW, a));
                    Draw.text(g, b, bx + 5, by + 2.8f, 0.68f, Draw.withAlpha(Theme.TEXT_DIM, a));
                    bx += bw + 4;
                }
            }
            y += ch + GAP;
        }
        return y - y0;
    }

    private long now() {
        return System.currentTimeMillis() - openedAt;
    }

    // ======================================================================= édition

    /** Ligne de section du tiroir d'édition. */
    private static final class Section extends Rows.Row {
        Section(String label) {
            super(label, null);
        }

        @Override
        public float height() {
            return 16;
        }

        @Override
        public void draw(Rows.Host h, GuiGraphics g, float x, float y, float w, int mx, int my, float a) {
            hover.update(h.dt(), 0);
            Draw.text(g, label, x + 4, y + 6, 0.78f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        }
    }

    private void startEdit() {
        if (page == null || page.failed()) return;
        editing = true;
        Look l = page.look() == null ? PlayerPages.defaultLook() : page.look();
        bio = page.bio() == null ? "" : page.bio();
        caption = page.caption() == null ? "" : page.caption();
        status = l.status() == null ? "" : l.status();
        showDiscord = page.showDiscord();
        privateMode = l.privateMode();
        showcase = l.showcase();
        bgType = Math.max(0, java.util.Arrays.asList(PlayerPages.BG_TYPES).indexOf(l.bgType()));
        bg1 = l.bg1() >= 0 ? l.bg1() : color1(l);
        bg2 = l.bg2() >= 0 ? l.bg2() : color2(l);
        frame = Math.max(0, java.util.Arrays.asList(PlayerPages.FRAMES).indexOf(l.frame()));
        accent = l.accent() >= 0 ? l.accent() : Theme.accent();
        cardStyle = l.cardStyle();
        cardAlpha = l.cardAlpha();
        radius = l.radius();
        titleIdx = 0;
        for (int i = 0; i < page.badges().size(); i++) if (page.badges().get(i).id().equals(l.title())) titleIdx = i + 1;
        links.clear();
        links.putAll(l.links());

        String[] titles = new String[page.badges().size() + 1];
        titles[0] = "None";
        for (int i = 0; i < page.badges().size(); i++) titles[i + 1] = page.badges().get(i).name();

        List<Rows.Row> rows = new ArrayList<>();
        rows.add(new Section("Background"));
        rows.add(new Rows.Choice("Style", new String[]{"Color", "Gradient", "Image"}, () -> bgType, i -> bgType = i));
        rows.add(new Rows.ColorRow("Color", () -> bg1, v -> bg1 = v));
        rows.add(new Rows.ColorRow("Second color", () -> bg2, v -> bg2 = v));
        rows.add(new Rows.Button(page.look() != null && page.look().background() != null ? "Replace image" : "Background image",
                "PNG or GIF, 512 KB", Icons.IMAGE, () -> upload("background")));
        rows.add(new Section("Style"));
        rows.add(new Rows.ColorRow("Accent", () -> accent, v -> accent = v));
        rows.add(new Rows.Choice("Cards", Look.CARD_STYLES, () -> cardStyle, i -> cardStyle = i));
        rows.add(new Rows.Slider("Card opacity", null, 0, 100, 5, () -> cardAlpha, v -> cardAlpha = (int) Math.round(v), v -> Math.round(v) + " %"));
        rows.add(new Rows.Choice("Corners", Look.RADII, () -> radius, i -> radius = i));
        rows.add(new Section("Avatar and title"));
        rows.add(new Rows.Choice("Frame", PlayerPages.FRAME_NAMES, () -> frame, i -> frame = i));
        rows.add(new Rows.Choice("Title", titles, () -> titleIdx, i -> titleIdx = i));
        rows.add(new Rows.TextField("Status", "What are you up to", 48, () -> status, v -> status = v));
        rows.add(new Rows.TextField("Bio", "A few words about you", 160, () -> bio, v -> bio = v));
        rows.add(new Rows.Choice("Skin pose", PlayerPages.POSE_NAMES, () -> pose, i -> pose = i));
        rows.add(new Section("Showcase"));
        rows.add(new Rows.Button(page.banner() == null ? "Banner" : "Replace banner", "PNG or GIF, 512 KB, 24 frames", Icons.IMAGE, () -> upload("banner")));
        if (page.banner() != null) rows.add(new Rows.Button("Remove banner", null, Icons.TRASH, () -> remove("banner")));
        rows.add(new Rows.Button(page.visual() == null ? "Picture" : "Replace picture", "Shown when there is no banner", Icons.IMAGE, () -> upload("visual")));
        if (page.visual() != null) rows.add(new Rows.Button("Remove picture", null, Icons.TRASH, () -> remove("visual")));
        rows.add(new Rows.TextField("Caption", "Under the picture", 60, () -> caption, v -> caption = v));
        rows.add(new Rows.Toggle("Cosmetics", null, () -> (showcase & Look.SHOW_COSMETICS) != 0, v -> showcase = v ? showcase | Look.SHOW_COSMETICS : showcase & ~Look.SHOW_COSMETICS));
        rows.add(new Rows.Toggle("Stats", null, () -> (showcase & Look.SHOW_STATS) != 0, v -> showcase = v ? showcase | Look.SHOW_STATS : showcase & ~Look.SHOW_STATS));
        rows.add(new Rows.Toggle("Badges", null, () -> (showcase & Look.SHOW_BADGES) != 0, v -> showcase = v ? showcase | Look.SHOW_BADGES : showcase & ~Look.SHOW_BADGES));
        rows.add(new Rows.Toggle("Skin history", null, () -> (showcase & Look.SHOW_SKINS) != 0, v -> showcase = v ? showcase | Look.SHOW_SKINS : showcase & ~Look.SHOW_SKINS));
        if (page.discord() != null) rows.add(new Rows.Toggle("Discord", "Name, avatar and badges", () -> showDiscord, v -> showDiscord = v));
        rows.add(new Section("Links"));
        for (int i = 0; i < PlayerPages.LINK_SITES.length; i++) {
            String site = PlayerPages.LINK_SITES[i];
            rows.add(new Rows.TextField(PlayerPages.LINK_NAMES[i], "Username", 30, () -> links.getOrDefault(site, ""), v -> {
                if (v.isBlank()) links.remove(site);
                else links.put(site, v.trim());
            }));
        }
        rows.add(new Section("Privacy"));
        rows.add(new Rows.Choice("Visible to", new String[]{"Everyone", "Friends"}, () -> privateMode ? 1 : 0, i -> privateMode = i == 1));
        editPanel.setRows(rows);
        click(1.1f);
    }

    private void upload(String kind) {
        PlayerPages.pickAndUpload(kind, err -> {
            say(err == null ? "Picture updated" : err, err != null);
            if (err == null) {
                boolean was = editing;
                page = null;
                if (was) reopenEditor = true;
            }
        });
    }

    private void remove(String kind) {
        PlayerPages.removeImage(kind, err -> {
            say(err == null ? "Picture removed" : err, err != null);
            if (err == null) page = null;
        });
    }

    private boolean reopenEditor;

    private void save() {
        Look l = look();
        PlayerPages.save(bio, PlayerPages.POSES[pose], caption, showDiscord, l, err -> {
            say(err == null ? "Profile saved" : err, err != null);
            if (err == null) page = null;
            else editing = true;
        });
        editing = false;
        editPanel.focus(null);
        Config.save();
        click(1.2f);
    }

    private void editDrawer(GuiGraphics g, int mx, int my, float a) {
        float e = Draw.clamp01(drawer.value);
        float dx = wx + ww - 8 - DRAWER_W * e, dy = wy + HEAD_Y + 60, dh = wy + wh - 8 - dy;
        Draw.rrect(g, dx, dy, DRAWER_W, dh, 8, Draw.withAlpha(Theme.PANEL, 0.97f * a * e));
        Draw.ring(g, dx, dy, DRAWER_W, dh, 8, Draw.withAlpha(0xFFFFFF, 0.08f * a * e));
        Draw.text(g, "Edit profile", dx + 10, dy + 7, 0.95f, Draw.withAlpha(Theme.TEXT, a * e));
        Draw.text(g, "Live preview", dx + DRAWER_W - 10 - Draw.width("Live preview", 0.68f), dy + 8.5f, 0.68f, Draw.withAlpha(Theme.TEXT_MUTED, a * e));
        hit(dx, dy, DRAWER_W, dh, () -> {});
        editPanel.render(g, dx + 2, dy + 20, DRAWER_W - 4, dh - 22, mx, my, a * e);
    }

    // ======================================================================= utilitaires

    private static String playtime(int minutes) {
        if (minutes < 60) return minutes + " min";
        int h = minutes / 60;
        return h < 100 ? h + " h " + (minutes % 60 > 0 ? minutes % 60 + " min" : "") : h + " h";
    }

    private static String ago(long t) {
        long s = Math.max(0, (System.currentTimeMillis() - t) / 1000);
        if (s < 3600) return Math.max(1, s / 60) + " min ago";
        if (s < 86400) return s / 3600 + " h ago";
        return s / 86400 + " d ago";
    }

    private static List<String> wrap(String text, float max, float scale, int maxLines) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String cand = line.isEmpty() ? word : line + " " + word;
            if (Draw.width(cand, scale) > max && !line.isEmpty()) {
                out.add(line.toString());
                line = new StringBuilder(word);
                if (out.size() == maxLines - 1) break;
            } else line = new StringBuilder(cand);
        }
        if (!line.isEmpty() && out.size() < maxLines) out.add(fit(line.toString(), scale, max));
        return out;
    }

    private static String fit(String s, float scale, float max) {
        if (Draw.width(s, scale) <= max) return s;
        while (!s.isEmpty() && Draw.width(s + "…", scale) > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    // ======================================================================= événements

    @Override
    public void tick() {
        super.tick();
        if (reopenEditor && page != null && !page.failed()) {
            reopenEditor = false;
            startEdit();
        }
    }

    @Override
    protected boolean clickPage(double mx, double my) {
        if (editing && drawer.value > 0.5f) {
            float dx = wx + ww - 8 - DRAWER_W, dy = wy + HEAD_Y + 60;
            if (inside(mx, my, dx, dy, DRAWER_W, wy + wh - 8 - dy)) {
                editPanel.click(mx, my);
                return true;
            }
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Object[] h = hits.get(i);
            if (inside(mx, my, (float) h[0], (float) h[1], (float) h[2], (float) h[3])) {
                ((Runnable) h[4]).run();
                return true;
            }
        }
        if (inside(mx, my, skinX, skinY - scroll.value * 0, skinW, skinH) && my >= bodyY && my < bodyY + bodyH) {
            draggingSkin = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (editing && editPanel.drag(event.x(), event.y())) return true;
        if (draggingSkin) {
            yaw.target += (float) dx * 2.2f;
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        editPanel.release();
        draggingSkin = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (editing && mx >= wx + ww - 8 - DRAWER_W) return editPanel.scroll(mx, my, sy);
        scrollTarget -= (float) sy * 30;
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (editing && event.isAllowedChatCharacter() && editPanel.charTyped(event.codepointAsString())) return true;
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (editing) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                if (editPanel.focused() != null) editPanel.focus(null);
                else editing = false;
                return true;
            }
            if (editPanel.keyPressed(event.key(), event.modifiers())) return true;
        }
        return super.keyPressed(event);
    }

    // ----------------------------------------------------------------------- outils de dev

    public void debugEdit() {
        if (page != null && !page.failed()) startEdit();
    }

    public void debugTab(int t) {
        scrollTarget = t * 400;
    }

    public void debugPose(int p) {
        pose = p;
    }

    public void debugSetBio(String b) {
        bio = b;
        caption = "Edited in dev";
        showDiscord = true;
        status = "Grinding crystal PvP";
        bgType = 1;
        bg1 = 0x3A1C6E;
        bg2 = 0x0B0A14;
        frame = 4;
        accent = 0x5EE6A8;
        cardStyle = 2;
        cardAlpha = 40;
        radius = 2;
        links.put("youtube", "paranoiaclient");
        links.put("twitch", "paranoia");
    }

    public void debugSave() {
        if (editing) save();
    }
}
