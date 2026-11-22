package gg.aurora.ui;

import gg.aurora.Config;
import gg.aurora.Friends;
import gg.aurora.Settings;
import gg.aurora.nametag.NameStyler;
import gg.aurora.nametag.NametagStyle;
import gg.aurora.nametag.NametagSync;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Page Amis connectée au serveur : demandes (accepter / ignorer), amis avec présence en ligne,
 * conversation privée, bouton Rejoindre quand l'ami partage son serveur.
 */
public class FriendsScreen extends SubScreen {
    private enum Field { NONE, ADD, CHAT }

    private Field focus = Field.ADD;
    private String addInput = "", chatInput = "";
    private boolean sending;
    private String message;
    private boolean messageError;
    private float messageAt = -10;

    private int tab; // 0 = amis, 1 = demandes
    private final Spring tabSlide = new Spring(0, 260, 0.75f);
    private UUID selected;
    private long confirmRemoveUntil;

    private final Spring scroll = new Spring(0, 260, 1f), chatScroll = new Spring(0, 260, 1f);
    private float scrollTarget, chatScrollTarget;
    private final Map<UUID, Spring> rowHover = new HashMap<>();
    private final List<Object[]> hits = new ArrayList<>(); // {x, y, w, h, Runnable}
    private final RowPanel options;
    private long shownAt = System.nanoTime();

    private float fx, fy, fw, lx, ly, lw, lh, rx, ry, rw, rh;

    public FriendsScreen(net.minecraft.client.gui.screens.Screen parent) {
        super(parent, "Friends");
        options = new RowPanel(() -> dt, new RowPanel.Callbacks() {
            public void pointer() {
                pointer = true;
            }

            public void sound(float pitch) {
                click(pitch);
            }

            public void changed() {
                Config.save();
            }
        });
        options.setRows(List.of(
                new Rows.Toggle("Share my server", "Your friends see where you play", () -> Settings.shareServer, v -> Settings.shareServer = v),
                new Rows.Toggle("Friend requests", "Notification for each request", () -> Settings.notifyRequests, v -> Settings.notifyRequests = v),
                new Rows.Toggle("Messages", "Private message notification", () -> Settings.notifyMessages, v -> Settings.notifyMessages = v),
                new Rows.Toggle("Friend online", "Notification when they connect", () -> Settings.friendNotify, v -> Settings.friendNotify = v),
                new Rows.Toggle("Star in front of friends", "Tab and nametags", () -> Settings.friendStar, v -> Settings.friendStar = v)));
        Friends.sync(false);
    }

    public void debugSelect(UUID id) {
        select(id);
    }

    @Override
    public boolean consumesEscape() {
        return selected != null || gifOpen;
    }

    @Override
    public boolean typing() {
        return focus != Field.NONE || gifOpen && gifFocus;
    }

    public void debugTab(int t) {
        tab = t;
        selected = null;
    }

    private void hit(float x, float y, float w, float h, Runnable r) {
        hits.add(new Object[]{x, y, w, h, r});
    }

    private void flash(String text, boolean error) {
        message = text;
        messageError = error;
        messageAt = time();
    }

    // ======================================================================= rendu

    @Override
    protected void renderPage(GuiGraphics g, int mx, int my, float a) {
        hits.clear();
        List<Friends.Friend> friends = Friends.all();
        List<Friends.Request> in = Friends.incoming(), out = Friends.outgoing();
        long online = friends.stream().filter(Friends.Friend::online).count();

        float tx = wx + ww / 2;
        Draw.icon(g, Icons.FRIENDS, tx - Draw.width("Friends", 1.1f) / 2 - 9, wy + 16, 10, Draw.withAlpha(Theme.accent(), a));
        Draw.centered(g, "Friends", tx + 3, wy + 11, 1.1f, Draw.withAlpha(Theme.TEXT, a));
        String st = Friends.connected ? online + " online" : Friends.status;
        int stc = Friends.connected ? 0x5EE6A8 : 0xF5B54A;
        float sw = Draw.width(st, 0.82f) + 20, sx = wx + ww - 10 - sw;
        Draw.glass(g, sx, wy + 9, sw, 15, 7.5f, Draw.withAlpha(Theme.ROW, 0.9f * a), Draw.withAlpha(stc, 0.4f * a));
        Draw.rrect(g, sx + 7, wy + 14.5f, 4, 4, 2, Draw.withAlpha(stc, a));
        Draw.text(g, st, sx + 14, wy + 13, 0.82f, Draw.withAlpha(Theme.TEXT, a));
        Draw.rrect(g, wx + 10, wy + 31, ww - 20, 0.8f, 0.4f, Draw.withAlpha(0xFFFFFF, 0.08f * a));

        float top = wy + 38;
        lx = wx + 10;
        lw = Math.min(200, ww * 0.44f);
        rx = lx + lw + 8;
        rw = wx + ww - 10 - rx;
        ry = top;
        rh = wy + wh - 10 - top;

        // ---- ajout par pseudo
        fx = lx;
        fy = top;
        float addW = 54;
        fw = lw - addW - 4;
        boolean ff = focus == Field.ADD;
        Draw.glass(g, fx, fy, fw, 16, 8, Draw.withAlpha(Theme.ROW, 0.95f * a), Draw.withAlpha(ff ? Theme.accent() : 0xFFFFFF, (ff ? 0.7f : 0.15f) * a));
        Draw.icon(g, Icons.PERSON, fx + 9, fy + 8, 8, Draw.withAlpha(Theme.TEXT_DIM, a));
        if (addInput.isEmpty()) Draw.text(g, "Add a username…", fx + 17, fy + 4.5f, 0.85f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        else Draw.text(g, addInput, fx + 17, fy + 4.5f, 0.85f, Draw.withAlpha(Theme.TEXT, a));
        if (ff && caret()) Draw.rrect(g, fx + 17.5f + Draw.width(addInput, 0.85f), fy + 4, 0.8f, 8, 0.4f, Draw.withAlpha(0xFFFFFF, a));
        pointer |= inside(mx, my, fx, fy, fw, 16);
        hit(fx, fy, fw, 16, () -> focus = Field.ADD);
        float bx = fx + fw + 4;
        boolean bh = inside(mx, my, bx, fy, addW, 16);
        pointer |= bh;
        Draw.glow(g, bx, fy, addW, 16, 6, Draw.withAlpha(Theme.accent(), (bh ? 0.35f : 0.15f) * a));
        Draw.glass(g, bx, fy, addW, 16, 8, Draw.withAlpha(Draw.lerpColor(Theme.accent(), 0xFFFFFF, bh ? 0.15f : 0), 0.95f * a), Draw.withAlpha(0xFFFFFF, 0.4f * a));
        Draw.centered(g, sending ? "…" : "Add", bx + addW / 2, fy + 4.5f, 0.85f, Draw.withAlpha(0xFFFFFF, a));
        hit(bx, fy, addW, 16, this::sendRequest);
        if (message != null && time() - messageAt < 4f) {
            float e = Draw.clamp01(Math.min((time() - messageAt) * 6, (4f - (time() - messageAt)) * 3));
            Draw.text(g, message, fx + 3, fy + 19.5f, 0.75f, Draw.withAlpha(messageError ? 0xF2545B : 0x5EE6A8, e * a));
        }

        // ---- onglets
        float ty = top + 30, tw = lw / 2;
        tabSlide.update(dt, tab);
        Draw.rrect(g, lx, ty, lw, 15, 7.5f, Draw.withAlpha(0x000000, 0.25f * a));
        Draw.rrect(g, lx + tabSlide.value * tw, ty, tw, 15, 7.5f, Draw.withAlpha(Theme.accent(), 0.9f * a));
        String[] labels = {"Friends (" + friends.size() + ")", "Requests"};
        for (int i = 0; i < 2; i++) {
            float x = lx + i * tw;
            boolean hv = inside(mx, my, x, ty, tw, 15);
            pointer |= hv && i != tab;
            Draw.centered(g, labels[i], x + tw / 2 - (i == 1 && !in.isEmpty() ? 5 : 0), ty + 4, 0.82f,
                    Draw.withAlpha(i == tab ? 0xFFFFFF : hv ? Theme.TEXT : Theme.TEXT_DIM, a));
            int ii = i;
            hit(x, ty, tw, 15, () -> {
                if (tab != ii) {
                    click(1.05f);
                    tab = ii;
                    scrollTarget = 0;
                    shownAt = System.nanoTime();
                }
            });
        }
        if (!in.isEmpty()) {
            float bxx = lx + tw + tw / 2 + Draw.width("Requests", 0.82f) / 2 - 1;
            float pulse = 0.85f + 0.15f * (float) Math.sin(time() * 5);
            Draw.rrect(g, bxx, ty + 2.5f, 10 * pulse, 10 * pulse, 5 * pulse, Draw.withAlpha(0xF2545B, a));
            Draw.centered(g, String.valueOf(in.size()), bxx + 5 * pulse, ty + 4.3f, 0.65f, Draw.withAlpha(0xFFFFFF, a));
        }

        // ---- liste
        ly = ty + 20;
        lh = wy + wh - 10 - ly;
        Draw.rrect(g, lx, ly, lw, lh, 8, Draw.withAlpha(0x000000, 0.18f * a));
        g.enableScissor((int) lx, (int) ly, (int) Math.ceil(lx + lw), (int) Math.ceil(ly + lh));
        float content = tab == 0 ? friendList(g, friends, mx, my, a) : requestList(g, in, out, mx, my, a);
        g.disableScissor();
        float maxScroll = Math.max(0, content - lh + 6);
        scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
        scroll.update(dt, scrollTarget);

        // ---- panneau de droite
        Draw.rrect(g, rx, ry, rw, rh, 8, Draw.withAlpha(0x000000, 0.18f * a));
        Friends.Friend sel = selected == null ? null : Friends.get(selected);
        if (sel != null) conversation(g, sel, mx, my, a);
        else {
            Draw.text(g, "Settings", rx + 9, ry + 7, 0.85f, Draw.withAlpha(Theme.TEXT_DIM, a));
            options.render(g, rx, ry + 15, rw, rh - 20, mx, my, a);
        }
    }

    private boolean caret() {
        return (System.currentTimeMillis() / 530) % 2 == 0;
    }

    private float rowAnim(int i) {
        float t = (System.nanoTime() - shownAt) / 1e9f;
        return animated() ? Draw.easeOutCubic((t - Math.min(i, 10) * 0.04f) / 0.35f) : 1;
    }

    private void avatar(GuiGraphics g, UUID id, String name, float x, float y, float a) {
        PlayerInfo info = minecraft.getConnection() == null ? null : minecraft.getConnection().getPlayerInfo(id);
        if (info != null) {
            PlayerFaceRenderer.draw(g, info.getSkin(), (int) x, (int) y, 16);
        } else {
            Draw.rrect(g, x, y, 16, 16, 5, Draw.withAlpha(Draw.lerpColor(Theme.accent(), Theme.TILE, 0.45f), a));
            var skin = gg.aurora.Friends.skin(id, name);
            if (skin != null) PlayerFaceRenderer.draw(g, skin, (int) x, (int) y, 16);
            else Draw.centered(g, name.substring(0, 1).toUpperCase(Locale.ROOT), x + 8, y + 4, 0.95f, Draw.withAlpha(Theme.TEXT, a));
        }
    }

    private Component styledName(UUID id, String name) {
        NametagStyle st = NametagSync.get(id);
        return st != null ? NameStyler.gradient(name, st, Style.EMPTY.withFont(Draw.UI_FONT), NameStyler.time()) : Draw.ui(name);
    }

    private float friendList(GuiGraphics g, List<Friends.Friend> list, int mx, int my, float a) {
        if (list.isEmpty()) {
            Draw.icon(g, Icons.FRIENDS, lx + lw / 2, ly + lh / 2 - 16, 20, Draw.withAlpha(Theme.TEXT_MUTED, a));
            Draw.centered(g, "No friends yet", lx + lw / 2, ly + lh / 2 + 2, 0.85f, Draw.withAlpha(Theme.TEXT_DIM, a));
            Draw.centered(g, "Add a player who has the mod", lx + lw / 2, ly + lh / 2 + 13, 0.75f, Draw.withAlpha(Theme.TEXT_MUTED, a));
            return 0;
        }
        List<Friends.Friend> sorted = new ArrayList<>(list);
        sorted.sort((p, q) -> p.online() != q.online() ? (p.online() ? -1 : 1) : p.name().compareToIgnoreCase(q.name()));
        float rowH = 26, gap = 3;
        for (int i = 0; i < sorted.size(); i++) {
            Friends.Friend f = sorted.get(i);
            float y = ly + 4 + i * (rowH + gap) - scroll.value;
            if (y + rowH < ly || y > ly + lh) continue;
            float e = rowAnim(i), ra = a * e, x = lx + 4 + (1 - e) * 12, w = lw - 8;
            boolean hv = inside(mx, my, x, y, w, rowH) && my >= ly && my < ly + lh;
            pointer |= hv;
            boolean isSel = f.id().equals(selected);
            Spring hs = rowHover.computeIfAbsent(f.id(), k -> new Spring(0, 320, 1f));
            hs.update(dt, hv || isSel ? 1 : 0);
            Draw.rrect(g, x, y, w, rowH, 6, Draw.withAlpha(Draw.lerpColor(Theme.ROW, Theme.ROW_HOVER, hs.value), 0.9f * ra));
            if (isSel) Draw.ring(g, x, y, w, rowH, 6, Draw.withAlpha(Theme.accent(), 0.8f * ra));
            avatar(g, f.id(), f.name(), x + 5, y + 5, ra);
            int dot = f.online() ? 0x5EE6A8 : 0x5E5978;
            Draw.rrect(g, x + 16, y + 16, 7, 7, 3.5f, Draw.withAlpha(Theme.ROW, ra));
            Draw.rrect(g, x + 17, y + 17, 5, 5, 2.5f, Draw.withAlpha(dot, ra));
            Draw.text(g, styledName(f.id(), f.name()), x + 27, y + 4.3f, 0.95f, Draw.withAlpha(Theme.TEXT, ra));
            String ls = Friends.lastSeen(f);
            while (ls.length() > 4 && Draw.width(ls, 0.72f) > w - 34) ls = ls.substring(0, ls.length() - 2);
            Draw.text(g, ls, x + 27, y + 15, 0.72f, Draw.withAlpha(f.online() ? 0x5EE6A8 : Theme.TEXT_MUTED, ra));
            int unread = Friends.unread(f.id());
            if (unread > 0) {
                Draw.rrect(g, x + w - 18, y + 8, 12, 10, 5, Draw.withAlpha(Theme.accent(), ra));
                Draw.centered(g, String.valueOf(unread), x + w - 12, y + 9.8f, 0.68f, Draw.withAlpha(0xFFFFFF, ra));
            }
            UUID id = f.id();
            hit(x, y, w, rowH, () -> {
                if (my >= ly && my < ly + lh) select(id);
            });
        }
        return sorted.size() * (rowH + gap);
    }

    private float requestList(GuiGraphics g, List<Friends.Request> in, List<Friends.Request> out, int mx, int my, float a) {
        float y = ly + 4 - scroll.value, rowH = 24;
        int i = 0;
        Draw.text(g, "Received", lx + 8, y + 2, 0.75f, Draw.withAlpha(Theme.TEXT_DIM, a));
        y += 12;
        if (in.isEmpty()) {
            Draw.text(g, "No requests received", lx + 8, y + 2, 0.75f, Draw.withAlpha(Theme.TEXT_MUTED, a));
            y += 14;
        }
        for (Friends.Request r : in) {
            float e = rowAnim(i++), ra = a * e, x = lx + 4 + (1 - e) * 12, w = lw - 8;
            Draw.glass(g, x, y, w, rowH, 6, Draw.withAlpha(Theme.ROW, 0.95f * ra), Draw.withAlpha(Theme.accent(), 0.35f * ra));
            avatar(g, r.id(), r.name(), x + 4, y + 4, ra);
            Draw.text(g, styledName(r.id(), r.name()), x + 25, y + 4, 0.9f, Draw.withAlpha(Theme.TEXT, ra));
            Draw.text(g, "wants to be your friend", x + 25, y + 14, 0.68f, Draw.withAlpha(Theme.TEXT_MUTED, ra));
            float b1 = x + w - 40, b2 = x + w - 20;
            boolean h1 = inside(mx, my, b1, y + 5, 16, 14), h2 = inside(mx, my, b2, y + 5, 16, 14);
            pointer |= h1 || h2;
            Draw.rrect(g, b1, y + 5, 16, 14, 7, Draw.withAlpha(h1 ? 0x5EE6A8 : Draw.lerpColor(Theme.ROW_HOVER, 0x5EE6A8, 0.35f), ra));
            Draw.icon(g, Icons.CHECK, b1 + 8, y + 12, 8, Draw.withAlpha(h1 ? 0x10301F : 0xFFFFFF, ra));
            Draw.rrect(g, b2, y + 5, 16, 14, 7, Draw.withAlpha(h2 ? 0xF2545B : Draw.lerpColor(Theme.ROW_HOVER, 0xF2545B, 0.3f), ra));
            Draw.centered(g, "×", b2 + 8, y + 7.5f, 1f, Draw.withAlpha(0xFFFFFF, ra));
            hit(b1, y + 5, 16, 14, () -> Friends.respond(r.id(), true, err -> {
                flash(err == null ? r.name() + " is now your friend" : err, err != null);
                click(err == null ? 1.3f : 0.7f);
            }));
            hit(b2, y + 5, 16, 14, () -> Friends.respond(r.id(), false, err -> {
                flash(err == null ? "Request from " + r.name() + " ignored" : err, err != null);
                click(0.85f);
            }));
            y += rowH + 3;
        }
        y += 6;
        Draw.text(g, "Sent", lx + 8, y + 2, 0.75f, Draw.withAlpha(Theme.TEXT_DIM, a));
        y += 12;
        if (out.isEmpty()) {
            Draw.text(g, "No pending requests", lx + 8, y + 2, 0.75f, Draw.withAlpha(Theme.TEXT_MUTED, a));
            y += 14;
        }
        for (Friends.Request r : out) {
            float e = rowAnim(i++), ra = a * e, x = lx + 4 + (1 - e) * 12, w = lw - 8;
            Draw.rrect(g, x, y, w, rowH, 6, Draw.withAlpha(Theme.ROW, 0.8f * ra));
            avatar(g, r.id(), r.name(), x + 4, y + 4, ra);
            Draw.text(g, r.name(), x + 25, y + 4, 0.9f, Draw.withAlpha(Theme.TEXT, ra));
            Draw.text(g, "Pending…", x + 25, y + 14, 0.68f, Draw.withAlpha(0xF5B54A, ra));
            float b2 = x + w - 20;
            boolean h2 = inside(mx, my, b2, y + 5, 16, 14);
            pointer |= h2;
            Draw.rrect(g, b2, y + 5, 16, 14, 7, Draw.withAlpha(h2 ? 0xF2545B : Theme.ROW_HOVER, ra));
            Draw.centered(g, "×", b2 + 8, y + 7.5f, 1f, Draw.withAlpha(0xFFFFFF, ra));
            hit(b2, y + 5, 16, 14, () -> {
                Friends.remove(r.id());
                flash("Request to " + r.name() + " cancelled", false);
                click(0.85f);
            });
            y += rowH + 3;
        }
        return y + scroll.value - ly;
    }

    private void conversation(GuiGraphics g, Friends.Friend f, int mx, int my, float a) {
        Friends.viewing = f.id();
        Friends.viewingAt = System.currentTimeMillis();
        Friends.markRead(f.id());
        Friends.loadHistory(f.id());
        // en-tête
        avatar(g, f.id(), f.name(), rx + 8, ry + 7, a);
        Draw.text(g, styledName(f.id(), f.name()), rx + 30, ry + 6, 1f, Draw.withAlpha(Theme.TEXT, a));
        String seen = Friends.lastSeen(f) + (f.discord() == null || f.discord().isEmpty() ? "" : "  ·  Discord : " + f.discord());
        Draw.text(g, seen, rx + 30, ry + 17, 0.72f, Draw.withAlpha(f.online() ? 0x5EE6A8 : Theme.TEXT_MUTED, a));
        float bx = rx + rw - 8;
        // retirer (avec confirmation)
        boolean confirm = System.currentTimeMillis() < confirmRemoveUntil;
        String rl = confirm ? "Confirm?" : "Remove";
        float rwid = Draw.width(rl, 0.78f) + 14;
        bx -= rwid;
        boolean rhv = inside(mx, my, bx, ry + 8, rwid, 14);
        pointer |= rhv;
        Draw.glass(g, bx, ry + 8, rwid, 14, 7, Draw.withAlpha(confirm || rhv ? 0xF2545B : Theme.ROW, 0.9f * a), Draw.withAlpha(0xF2545B, 0.5f * a));
        Draw.centered(g, rl, bx + rwid / 2, ry + 11.6f, 0.78f, Draw.withAlpha(0xFFFFFF, a));
        hit(bx, ry + 8, rwid, 14, () -> {
            if (System.currentTimeMillis() < confirmRemoveUntil) {
                Friends.remove(f.id());
                flash(f.name() + " removed from your friends", false);
                selected = null;
                click(0.7f);
            } else {
                confirmRemoveUntil = System.currentTimeMillis() + 3000;
                click(0.9f);
            }
        });
        {
            float pw = Draw.width("Profile", 0.78f) + 14;
            bx -= pw + 5;
            boolean ph = inside(mx, my, bx, ry + 8, pw, 14);
            pointer |= ph;
            Draw.glass(g, bx, ry + 8, pw, 14, 7, Draw.withAlpha(ph ? Theme.ROW_HOVER : Theme.ROW, 0.9f * a), Draw.withAlpha(0xFFFFFF, 0.15f * a));
            Draw.centered(g, "Profile", bx + pw / 2, ry + 11.6f, 0.78f, Draw.withAlpha(Theme.TEXT, a));
            hit(bx, ry + 8, pw, 14, () -> minecraft.setScreen(ProfileScreen.open(host(), f.id())));
        }
        if (f.server() != null) {
            float jw = Draw.width("Join", 0.78f) + 14;
            bx -= jw + 5;
            boolean jh = inside(mx, my, bx, ry + 8, jw, 14);
            pointer |= jh;
            Draw.glow(g, bx, ry + 8, jw, 14, 5, Draw.withAlpha(0x5EE6A8, (jh ? 0.4f : 0.2f) * a));
            Draw.glass(g, bx, ry + 8, jw, 14, 7, Draw.withAlpha(jh ? 0x5EE6A8 : Draw.lerpColor(Theme.ROW, 0x5EE6A8, 0.35f), 0.95f * a),
                    Draw.withAlpha(0x5EE6A8, 0.7f * a));
            Draw.centered(g, "Join", bx + jw / 2, ry + 11.6f, 0.78f, Draw.withAlpha(jh ? 0x0E2A1C : 0xFFFFFF, a));
            hit(bx, ry + 8, jw, 14, () -> Friends.join(f));
        }
        Draw.rrect(g, rx + 8, ry + 29, rw - 16, 0.8f, 0.4f, Draw.withAlpha(0xFFFFFF, 0.08f * a));

        // messages
        float cy = ry + 33, ch = rh - 33 - 24;
        List<Friends.Message> msgs = Friends.chat(f.id());
        float maxW = rw * 0.72f;
        List<Object[]> laid = new ArrayList<>(); // {message, lines}
        float total = 0;
        for (Friends.Message m : msgs) {
            String img = gg.aurora.Media.imageId(m.text());
            if (img != null) {
                float[] sz = imageSize(gg.aurora.Media.image(img), maxW - 8);
                laid.add(new Object[]{m, null, img, sz});
                total += sz[1] + 13 + 5;
                continue;
            }
            List<String> lines = wrap(m.text(), maxW - 12, 0.85f);
            laid.add(new Object[]{m, lines});
            total += lines.size() * 9 + 16;
        }
        float maxScroll = Math.max(0, total - ch + 4);
        chatScrollTarget = Math.max(0, Math.min(maxScroll, chatScrollTarget));
        chatScroll.update(dt, chatScrollTarget);
        g.enableScissor((int) rx, (int) cy, (int) Math.ceil(rx + rw), (int) Math.ceil(cy + ch));
        if (msgs.isEmpty()) {
            Draw.centered(g, "Say hi to " + f.name() + " !", rx + rw / 2, cy + ch / 2 - 4, 0.85f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        }
        float y = cy + 2 + Math.max(0, ch - total - 4) - (maxScroll - chatScroll.value);
        for (Object[] o : laid) {
            Friends.Message m = (Friends.Message) o[0];
            if (o[1] == null) {
                // image : vignette dans une bulle, heure dessous
                float[] sz = (float[]) o[3];
                gg.aurora.Media.Image im = gg.aurora.Media.image((String) o[2]);
                float bw = sz[0] + 8, bh = sz[1] + 13;
                float ibx = m.mine() ? rx + rw - 8 - bw : rx + 8;
                int bbg = m.mine() ? Draw.lerpColor(Theme.accent(), Theme.PANEL, 0.25f) : Theme.ROW_HOVER;
                Draw.rrect(g, ibx, y, bw, bh, 6, Draw.withAlpha(bbg, 0.95f * a));
                if (im.texture() != null) {
                    Draw.image(g, im.at(System.currentTimeMillis()), ibx + 4, y + 4, sz[0], sz[1], im.w(), im.h(), 0, 0, im.w(), im.h(), Draw.withAlpha(0xFFFFFF, a));
                } else {
                    Draw.centered(g, im.failed() ? "Picture expired" : "Loading…", ibx + bw / 2, y + 4 + sz[1] / 2 - 3, 0.75f,
                            Draw.withAlpha(0xFFFFFF, 0.7f * a));
                }
                String itm = Friends.time(m.created());
                Draw.text(g, itm, ibx + bw - 5 - Draw.width(itm, 0.6f), y + bh - 6.5f, 0.6f, Draw.withAlpha(0xFFFFFF, 0.55f * a));
                y += bh + 5;
                continue;
            }
            @SuppressWarnings("unchecked") List<String> lines = (List<String>) o[1];
            float w = 0;
            for (String l : lines) w = Math.max(w, Draw.width(l, 0.85f));
            w = Math.max(w + 12, Draw.width(Friends.time(m.created()), 0.6f) + 12);
            float h = lines.size() * 9 + 11;
            float x = m.mine() ? rx + rw - 8 - w : rx + 8;
            int bg = m.mine() ? Draw.lerpColor(Theme.accent(), Theme.PANEL, 0.25f) : Theme.ROW_HOVER;
            Draw.rrect(g, x, y, w, h, 6, Draw.withAlpha(bg, 0.95f * a));
            float ly2 = y + 3.5f;
            for (String l : lines) {
                Draw.text(g, l, x + 6, ly2, 0.85f, Draw.withAlpha(0xFFFFFF, a));
                ly2 += 9;
            }
            String tm = Friends.time(m.created());
            Draw.text(g, tm, x + w - 5 - Draw.width(tm, 0.6f), y + h - 6.5f, 0.6f, Draw.withAlpha(0xFFFFFF, 0.55f * a));
            y += h + 5;
        }
        g.disableScissor();

        // saisie
        float iy = ry + rh - 21, iw = rw - 16 - 22 - 20 - 26;
        boolean fc = focus == Field.CHAT;
        Draw.glass(g, rx + 8, iy, iw, 15, 7.5f, Draw.withAlpha(Theme.ROW, 0.95f * a), Draw.withAlpha(fc ? Theme.accent() : 0xFFFFFF, (fc ? 0.7f : 0.15f) * a));
        String shown = chatInput;
        while (!shown.isEmpty() && Draw.width(shown, 0.82f) > iw - 14) shown = shown.substring(1);
        if (chatInput.isEmpty()) Draw.text(g, "Message to " + f.name() + "…", rx + 15, iy + 4.3f, 0.82f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        else Draw.text(g, shown, rx + 15, iy + 4.3f, 0.82f, Draw.withAlpha(Theme.TEXT, a));
        if (fc && caret()) Draw.rrect(g, rx + 15.5f + Draw.width(shown, 0.82f), iy + 3.5f, 0.8f, 8, 0.4f, Draw.withAlpha(0xFFFFFF, a));
        pointer |= inside(mx, my, rx + 8, iy, iw, 15);
        hit(rx + 8, iy, iw, 15, () -> focus = Field.CHAT);
        // GIF : panneau de recherche au-dessus de la saisie
        float gbx = rx + rw - 8 - 18 - 20 - 26;
        boolean gh = inside(mx, my, gbx, iy, 24, 15);
        pointer |= gh;
        Draw.rrect(g, gbx, iy, 24, 15, 7.5f, Draw.withAlpha(gifOpen ? Theme.accent() : gh ? Theme.ROW_HOVER : Theme.ROW, 0.95f * a));
        Draw.centered(g, "GIF", gbx + 12, iy + 4, 0.72f, Draw.withAlpha(gifOpen ? 0xFFFFFF : Theme.TEXT, a));
        hit(gbx, iy, 24, 15, this::toggleGifs);
        if (gifOpen) gifPanel(g, mx, my, a, iy);
        float px = rx + rw - 8 - 18 - 20;
        boolean ph = inside(mx, my, px, iy, 18, 15);
        pointer |= ph;
        Draw.rrect(g, px, iy, 18, 15, 7.5f, Draw.withAlpha(ph ? Theme.ROW_HOVER : Theme.ROW, 0.95f * a));
        Draw.centered(g, sendingImage ? "…" : "+", px + 9, iy + 3.2f, 1f, Draw.withAlpha(Theme.TEXT, a));
        hit(px, iy, 18, 15, this::pickImage);
        if (ph) {
            String tip = "Send a picture  ·  Ctrl+V pastes a copied picture or link";
            float tw = Draw.width(tip, 0.72f) + 10;
            Draw.rrect(g, px + 18 - tw, iy - 15, tw, 12, 3, Draw.withAlpha(0x0B0A12, 0.96f * a));
            Draw.text(g, tip, px + 23 - tw, iy - 12, 0.72f, Draw.withAlpha(Theme.TEXT, a));
        }
        float sx = rx + rw - 8 - 18;
        boolean sh = inside(mx, my, sx, iy, 18, 15);
        pointer |= sh;
        Draw.rrect(g, sx, iy, 18, 15, 7.5f, Draw.withAlpha(Draw.lerpColor(Theme.accent(), 0xFFFFFF, sh ? 0.2f : 0), a));
        Draw.icon(g, Icons.CHEVRONS, sx + 9, iy + 7.5f, 8, Draw.withAlpha(0xFFFFFF, a));
        hit(sx, iy, 18, 15, this::sendChat);
    }

    private boolean sendingImage;

    // ---- recherche de GIF (Klipy, via le serveur)
    private boolean gifOpen, gifLoading, gifFocus;
    private String gifQuery = "", gifShownQuery = null, gifError;
    private List<gg.aurora.Media.Gif> gifs = List.of();
    private float gifScroll;
    private long gifTypedAt;

    private void toggleGifs() {
        gifOpen = !gifOpen;
        gifFocus = gifOpen;
        if (gifOpen && gifShownQuery == null) runGifSearch();
        click(gifOpen ? 1.1f : 0.9f);
    }

    private void runGifSearch() {
        String q = gifQuery;
        gifShownQuery = q;
        gifLoading = true;
        gifError = null;
        gg.aurora.Media.searchGifs(q, (list, err) -> {
            if (!q.equals(gifQuery)) return; // une recherche plus récente est partie
            gifLoading = false;
            gifs = list;
            gifError = err;
            gifScroll = 0;
        });
    }

    private void gifPanel(GuiGraphics g, int mx, int my, float a, float iy) {
        // relance la recherche 400 ms après la dernière frappe
        if (!gifQuery.equals(gifShownQuery) && System.currentTimeMillis() - gifTypedAt > 400) runGifSearch();
        float pw = rw - 16, ph = Math.min(150, rh - 60), px = rx + 8, py = iy - 4 - ph;
        hit(px, py, pw, ph, () -> {});
        Draw.glow(g, px, py + 3, pw, ph, 10, Draw.withAlpha(0x000000, 0.45f * a));
        Draw.rrect(g, px, py, pw, ph, 8, Draw.withAlpha(Theme.PANEL, 0.98f * a));
        Draw.ring(g, px, py, pw, ph, 8, Draw.withAlpha(0xFFFFFF, 0.08f * a));
        // recherche
        float sx = px + 6, sy = py + 6, sw = pw - 12;
        Draw.rrect(g, sx, sy, sw, 14, 5, Draw.withAlpha(0x000000, 0.3f * a));
        Draw.ring(g, sx, sy, sw, 14, 5, Draw.withAlpha(gifFocus ? Theme.accent() : 0xFFFFFF, (gifFocus ? 0.7f : 0.12f) * a));
        Draw.icon(g, Icons.SEARCH, sx + 9, sy + 7, 7, Draw.withAlpha(Theme.TEXT_MUTED, a));
        if (gifQuery.isEmpty()) Draw.text(g, "Search GIFs (Klipy)", sx + 17, sy + 3.6f, 0.78f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        else Draw.text(g, gifQuery + (gifFocus && caret() ? "|" : ""), sx + 17, sy + 3.6f, 0.78f, Draw.withAlpha(Theme.TEXT, a));
        hit(sx, sy, sw, 14, () -> gifFocus = true);
        // grille
        float gy = sy + 19, gh = py + ph - 5 - gy;
        if (gifError != null) {
            Draw.centered(g, gifError, px + pw / 2, gy + gh / 2 - 4, 0.8f, Draw.withAlpha(0xF07878, a));
            return;
        }
        if (gifLoading && gifs.isEmpty()) {
            Draw.centered(g, "Loading…", px + pw / 2, gy + gh / 2 - 4, 0.8f, Draw.withAlpha(Theme.TEXT_MUTED, a));
            return;
        }
        if (gifs.isEmpty()) {
            Draw.centered(g, "No GIF found", px + pw / 2, gy + gh / 2 - 4, 0.8f, Draw.withAlpha(Theme.TEXT_MUTED, a));
            return;
        }
        int cols = Math.max(2, (int) ((pw - 12) / 62));
        float cw = (pw - 12 - (cols - 1) * 4) / cols, chh = cw * 0.7f;
        float content = ((gifs.size() + cols - 1) / cols) * (chh + 4);
        gifScroll = Math.max(0, Math.min(Math.max(0, content - gh), gifScroll));
        g.enableScissor((int) px + 6, (int) gy, (int) (px + pw - 6), (int) (gy + gh));
        long now = System.currentTimeMillis();
        for (int i = 0; i < gifs.size(); i++) {
            gg.aurora.Media.Gif gf = gifs.get(i);
            float cx = px + 6 + (i % cols) * (cw + 4), cy = gy + (i / cols) * (chh + 4) - gifScroll;
            if (cy + chh < gy || cy > gy + gh) continue;
            boolean hv = inside(mx, my, cx, cy, cw, chh) && my >= gy && my < gy + gh;
            pointer |= hv;
            Draw.rrect(g, cx, cy, cw, chh, 4, Draw.withAlpha(Theme.ROW, a));
            var im = gg.aurora.PlayerPages.remoteImage(gf.preview());
            if (im != null && !im.failed() && !im.frames().isEmpty()) {
                float k = Math.max(cw / im.w(), chh / im.h()), iw = im.w() * k, ih = im.h() * k;
                g.enableScissor((int) Math.max(cx, px + 6), (int) Math.max(cy, gy), (int) Math.min(cx + cw, px + pw - 6), (int) Math.min(cy + chh, gy + gh));
                Draw.image(g, im.at(now), cx + (cw - iw) / 2, cy + (chh - ih) / 2, iw, ih, im.w(), im.h(), 0, 0, im.w(), im.h(), Draw.withAlpha(0xFFFFFF, a));
                g.disableScissor();
                g.enableScissor((int) px + 6, (int) gy, (int) (px + pw - 6), (int) (gy + gh));
            }
            if (hv) Draw.ring(g, cx, cy, cw, chh, 4, Draw.withAlpha(Theme.accent(), 0.9f * a));
            hit(cx, Math.max(gy, cy), cw, Math.min(chh, gy + gh - cy), () -> sendGif(gf));
        }
        g.disableScissor();
        Draw.text(g, "Powered by KLIPY", px + pw - 8 - Draw.width("Powered by KLIPY", 0.6f), py + ph + 2 - 9, 0.6f, Draw.withAlpha(Theme.TEXT_MUTED, 0.8f * a));
    }

    private void sendGif(gg.aurora.Media.Gif gf) {
        if (selected == null || sendingImage) return;
        sendingImage = true;
        gifOpen = false;
        gifFocus = false;
        click(1.2f);
        gg.aurora.Media.sendGifUrl(selected, gf.url(), this::imageDone);
    }

    /** Taille d'affichage d'une image de message : dans maxW x 110, sans agrandir. */
    private static float[] imageSize(gg.aurora.Media.Image im, float maxW) {
        if (im.texture() == null) return new float[]{90, 34};
        float k = Math.min(1f, Math.min(maxW / im.w(), 110f / im.h()));
        // unités GUI : une image de 512 px ne doit pas remplir l'écran
        k = Math.min(k, 160f / Math.max(im.w(), im.h()));
        return new float[]{Math.max(8, im.w() * k), Math.max(8, im.h() * k)};
    }

    private void imageDone(String err) {
        sendingImage = false;
        if (err != null) flash(err, true);
        else chatScrollTarget = 0;
    }

    private void pickImage() {
        if (selected == null || sendingImage) return;
        click(1.1f);
        UUID to = selected;
        gg.aurora.Media.pickAndSend(to, err -> {
            sendingImage = false;
            imageDone(err);
        });
    }

    /** Ctrl+V : image copiée (capture d'écran…) ou lien d'image -> envoyée ; sinon vrai texte collé. */
    private boolean pasteImage(String clip) {
        if (selected == null || sendingImage) return false;
        UUID to = selected;
        String link = clip == null ? "" : clip.trim();
        boolean looksImage = gg.aurora.Capes.looksLikeImageLink(link);
        if (looksImage) {
            sendingImage = true;
            gg.aurora.Media.sendLink(to, link, this::imageDone);
            return true;
        }
        if (link.isEmpty()) {
            var frames = gg.aurora.Media.clipboardImage();
            if (frames == null) return false;
            sendingImage = true;
            gg.aurora.Media.sendImage(to, frames.w(), frames.h(), frames.argb().get(0), this::imageDone);
            return true;
        }
        return false;
    }

    private static List<String> wrap(String text, float max, float scale) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String test = line.isEmpty() ? word : line + " " + word;
            if (Draw.width(test, scale) <= max || line.isEmpty()) {
                line.setLength(0);
                line.append(test);
                while (Draw.width(line.toString(), scale) > max && line.length() > 1) {
                    int cut = line.length() - 1;
                    while (cut > 1 && Draw.width(line.substring(0, cut), scale) > max) cut--;
                    out.add(line.substring(0, cut));
                    line.delete(0, cut);
                }
            } else {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    // ======================================================================= actions

    private void select(UUID id) {
        click(1.05f);
        selected = id.equals(selected) ? null : id;
        chatScrollTarget = 0;
        chatScroll.set(0);
        focus = selected != null ? Field.CHAT : Field.ADD;
        confirmRemoveUntil = 0;
    }

    private void sendRequest() {
        if (sending || addInput.isBlank()) {
            focus = Field.ADD;
            return;
        }
        sending = true;
        String name = addInput.trim();
        Friends.add(name, err -> {
            sending = false;
            if (err == null) {
                flash("Request sent to " + name, false);
                addInput = "";
                click(1.25f);
            } else {
                flash(err, true);
                click(0.7f);
            }
        });
    }

    private void sendChat() {
        if (selected == null || chatInput.isBlank()) return;
        String text = chatInput;
        chatInput = "";
        gg.aurora.module.Live.stoppedTyping();
        chatScrollTarget = 0;
        Friends.send(selected, text, err -> {
            if (err != null) flash(err, true);
        });
        click(1.15f);
    }

    @Override
    protected boolean clickPage(double mx, double my) {
        Field before = focus;
        focus = Field.NONE;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Object[] h = hits.get(i);
            if (inside(mx, my, (float) h[0], (float) h[1], (float) h[2], (float) h[3])) {
                ((Runnable) h[4]).run();
                if (focus == Field.NONE && before == Field.CHAT && selected != null) focus = Field.CHAT;
                return true;
            }
        }
        if (selected == null && options.click(mx, my)) return true;
        if (selected != null) focus = Field.CHAT;
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (gifOpen && inside(mx, my, rx, ry, rw, rh - 24)) {
            gifScroll -= (float) sy * 24;
            return true;
        }
        if (inside(mx, my, rx, ry, rw, rh)) {
            if (selected != null) chatScrollTarget += (float) sy * 24;
            else options.scroll(mx, my, sy);
        } else {
            scrollTarget -= (float) sy * 28;
        }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        String c = event.codepointAsString();
        if (gifOpen && gifFocus && event.isAllowedChatCharacter() && gifQuery.length() < 40) {
            gifQuery += c;
            gifTypedAt = System.currentTimeMillis();
            return true;
        }
        if (focus == Field.ADD && addInput.length() < 16 && c.matches("[A-Za-z0-9_]")) {
            addInput += c;
            return true;
        }
        if (focus == Field.CHAT && event.isAllowedChatCharacter() && chatInput.length() < 256) {
            chatInput += c;
            // « en train d'écrire » visible au-dessus de ma tête pour les autres joueurs du client
            gg.aurora.module.Live.typed();
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        boolean ctrl = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        if (gifOpen) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                gifOpen = false;
                gifFocus = false;
                return true;
            }
            if (gifFocus && key == GLFW.GLFW_KEY_BACKSPACE) {
                if (!gifQuery.isEmpty()) gifQuery = ctrl ? "" : gifQuery.substring(0, gifQuery.length() - 1);
                gifTypedAt = System.currentTimeMillis();
                return true;
            }
            if (gifFocus && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
                if (!gifs.isEmpty()) sendGif(gifs.get(0));
                return true;
            }
        }
        if (focus != Field.NONE) {
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (focus == Field.ADD && !addInput.isEmpty()) addInput = ctrl ? "" : addInput.substring(0, addInput.length() - 1);
                if (focus == Field.CHAT && !chatInput.isEmpty()) chatInput = ctrl ? "" : chatInput.substring(0, chatInput.length() - 1);
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                if (focus == Field.ADD) sendRequest();
                else sendChat();
                return true;
            }
            if (key == GLFW.GLFW_KEY_V && ctrl) {
                String clip = minecraft.keyboardHandler.getClipboard();
                if (focus == Field.CHAT && pasteImage(clip)) return true;
                if (focus == Field.CHAT && clip != null) chatInput = (chatInput + clip.replace('\n', ' ')).substring(0, Math.min(256, chatInput.length() + clip.length()));
                return true;
            }
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && selected != null) {
            selected = null;
            focus = Field.ADD;
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void removed() {
        gg.aurora.module.Live.stoppedTyping();
        super.removed();
    }
}
