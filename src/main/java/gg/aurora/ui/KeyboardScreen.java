package gg.aurora.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import gg.aurora.Config;
import gg.aurora.Settings;
import gg.aurora.module.Module;
import gg.aurora.module.Modules;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Clavier : chaque touche montre ce qu'elle fait (jeu, client, modules). Touches neutres, les touches
 * utilisées sont un ton plus claires, un point rouge signale un conflit. Clic sur une touche (ou appui) →
 * panneau : ce qu'elle fait, recherche d'une action à lui donner. La disposition suit le vrai clavier (AZERTY…).
 */
public class KeyboardScreen extends Screen {
    private final Screen parent;
    private final Screen classic;
    private long last = System.nanoTime();
    private float dt, open;
    private boolean pointer;
    private int selected = -1;
    private String search = "";
    private float listScroll;
    private int view, filter, cursor;
    private boolean searchFocus;
    private final Segmented viewSeg = new Segmented(), filterSeg = new Segmented();
    private static final String[] VIEWS = {"All", "Used", "Free"}, FILTERS = {"All", "Modules", "Game"};
    private static final int CONFLICT = 0xE0564B;

    /** Une touche : code GLFW, position et largeur (en unités de touche). */
    private record K(int code, float x, float y, float w) {}

    private static final List<K> KEYS = new ArrayList<>();

    static {
        int[] r0 = {GLFW.GLFW_KEY_ESCAPE, -1, GLFW.GLFW_KEY_F1, GLFW.GLFW_KEY_F2, GLFW.GLFW_KEY_F3, GLFW.GLFW_KEY_F4, -2, GLFW.GLFW_KEY_F5,
                GLFW.GLFW_KEY_F6, GLFW.GLFW_KEY_F7, GLFW.GLFW_KEY_F8, -2, GLFW.GLFW_KEY_F9, GLFW.GLFW_KEY_F10, GLFW.GLFW_KEY_F11, GLFW.GLFW_KEY_F12};
        float x = 0;
        for (int c : r0) {
            if (c == -1) x += 1;
            else if (c == -2) x += 0.5f;
            else {
                KEYS.add(new K(c, x, 0, 1));
                x += 1;
            }
        }
        row(1.3f, new int[]{GLFW.GLFW_KEY_GRAVE_ACCENT, GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_2, GLFW.GLFW_KEY_3, GLFW.GLFW_KEY_4, GLFW.GLFW_KEY_5,
                GLFW.GLFW_KEY_6, GLFW.GLFW_KEY_7, GLFW.GLFW_KEY_8, GLFW.GLFW_KEY_9, GLFW.GLFW_KEY_0, GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_EQUAL},
                0, GLFW.GLFW_KEY_BACKSPACE, 2f);
        row(2.3f, new int[]{GLFW.GLFW_KEY_Q, GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_E, GLFW.GLFW_KEY_R, GLFW.GLFW_KEY_T, GLFW.GLFW_KEY_Y, GLFW.GLFW_KEY_U,
                GLFW.GLFW_KEY_I, GLFW.GLFW_KEY_O, GLFW.GLFW_KEY_P, GLFW.GLFW_KEY_LEFT_BRACKET, GLFW.GLFW_KEY_RIGHT_BRACKET, GLFW.GLFW_KEY_BACKSLASH},
                GLFW.GLFW_KEY_TAB, -1, 1.5f);
        row(3.3f, new int[]{GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_F, GLFW.GLFW_KEY_G, GLFW.GLFW_KEY_H, GLFW.GLFW_KEY_J,
                GLFW.GLFW_KEY_K, GLFW.GLFW_KEY_L, GLFW.GLFW_KEY_SEMICOLON, GLFW.GLFW_KEY_APOSTROPHE},
                GLFW.GLFW_KEY_CAPS_LOCK, GLFW.GLFW_KEY_ENTER, 1.75f);
        row(4.3f, new int[]{GLFW.GLFW_KEY_Z, GLFW.GLFW_KEY_X, GLFW.GLFW_KEY_C, GLFW.GLFW_KEY_V, GLFW.GLFW_KEY_B, GLFW.GLFW_KEY_N, GLFW.GLFW_KEY_M,
                GLFW.GLFW_KEY_COMMA, GLFW.GLFW_KEY_PERIOD, GLFW.GLFW_KEY_SLASH},
                GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT, 2.25f);
        KEYS.add(new K(GLFW.GLFW_KEY_LEFT_CONTROL, 0, 5.3f, 1.5f));
        KEYS.add(new K(GLFW.GLFW_KEY_LEFT_ALT, 2.5f, 5.3f, 1.25f));
        KEYS.add(new K(GLFW.GLFW_KEY_SPACE, 3.75f, 5.3f, 6.25f));
        KEYS.add(new K(GLFW.GLFW_KEY_RIGHT_ALT, 10f, 5.3f, 1.25f));
        KEYS.add(new K(GLFW.GLFW_KEY_RIGHT_CONTROL, 13.5f, 5.3f, 1.5f));
        float ex = 15.4f;
        KEYS.add(new K(GLFW.GLFW_KEY_INSERT, ex, 1.3f, 1));
        KEYS.add(new K(GLFW.GLFW_KEY_HOME, ex + 1, 1.3f, 1));
        KEYS.add(new K(GLFW.GLFW_KEY_PAGE_UP, ex + 2, 1.3f, 1));
        KEYS.add(new K(GLFW.GLFW_KEY_DELETE, ex, 2.3f, 1));
        KEYS.add(new K(GLFW.GLFW_KEY_END, ex + 1, 2.3f, 1));
        KEYS.add(new K(GLFW.GLFW_KEY_PAGE_DOWN, ex + 2, 2.3f, 1));
        KEYS.add(new K(GLFW.GLFW_KEY_UP, ex + 1, 4.3f, 1));
        KEYS.add(new K(GLFW.GLFW_KEY_LEFT, ex, 5.3f, 1));
        KEYS.add(new K(GLFW.GLFW_KEY_DOWN, ex + 1, 5.3f, 1));
        KEYS.add(new K(GLFW.GLFW_KEY_RIGHT, ex + 2, 5.3f, 1));
    }

    private static void row(float y, int[] keys, int first, int last, float edgeW) {
        float x = 0;
        if (first > 0) {
            KEYS.add(new K(first, 0, y, edgeW));
            x = edgeW;
        }
        for (int c : keys) {
            KEYS.add(new K(c, x, y, 1));
            x += 1;
        }
        if (last > 0) KEYS.add(new K(last, x, y, 15 - x));
    }

    public KeyboardScreen(Screen parent) {
        this(parent, null);
    }

    /** classic = liste vanilla des touches, à un clic. */
    public KeyboardScreen(Screen parent, Screen classic) {
        super(Component.literal("Keyboard"));
        this.parent = parent;
        this.classic = classic;
    }

    // ===================================================================== actions

    /** Une action attribuable : touche du jeu / du client, ou module. */
    private record Action(String name, String group, KeyMapping mapping, Module module) {
        int code() {
            if (module != null) return module.key;
            if (mapping.isUnbound()) return -1;
            try {
                var k = InputConstants.getKey(mapping.saveString());
                return k.getType() == InputConstants.Type.KEYSYM ? k.getValue() : -1;
            } catch (Exception e) {
                return -1;
            }
        }

        void bind(int code) {
            if (module != null) {
                module.key = code;
                Config.save();
            } else {
                mapping.setKey(code < 0 ? InputConstants.UNKNOWN : InputConstants.Type.KEYSYM.getOrCreate(code));
                KeyMapping.resetMapping();
                net.minecraft.client.Minecraft.getInstance().options.save();
            }
        }
    }

    private List<Action> cachedActions;
    private final java.util.Map<Integer, List<Action>> byCode = new java.util.HashMap<>();
    private long cachedAt;

    private List<Action> actions() {
        long now = System.currentTimeMillis();
        if (cachedActions != null && now - cachedAt < 250) return cachedActions;
        cachedAt = now;
        cachedActions = new ArrayList<>();
        for (KeyMapping km : minecraft.options.keyMappings) {
            cachedActions.add(new Action(Component.translatable(km.getName()).getString(), km.getCategory().label().getString(), km, null));
        }
        for (Module m : Modules.ALL) cachedActions.add(new Action(m.name, "Paranoia · " + m.category.label, null, m));
        byCode.clear();
        for (Action a : cachedActions) {
            int c = a.code();
            if (c >= 0) byCode.computeIfAbsent(c, k -> new ArrayList<>()).add(a);
        }
        return cachedActions;
    }

    private List<Action> on(int code) {
        return byCode.getOrDefault(code, List.of());
    }

    // ===================================================================== rendu

    private record Hit(float x, float y, float w, float h, Runnable action) {}

    private final List<Hit> hits = new ArrayList<>();
    private float kx, ky, ku;
    private List<Action> lastList = List.of();

    private void hit(float x, float y, float w, float h, Runnable r) {
        hits.add(new Hit(x, y, w, h, r));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        long now = System.nanoTime();
        dt = Math.min(0.05f, (now - last) / 1e9f);
        last = now;
        open = Settings.anim() ? Math.min(1, open + dt * 6) : 1;
        pointer = false;
        hits.clear();
        g.fill(0, 0, width, height, Draw.withAlpha(0x000000, 0.62f * open));
        float a = open;
        actions();

        // ---- une seule échelle : le clavier garde sa taille, le panneau prend le reste
        ku = Math.min((width - 48) / 18.4f, (height * 0.46f) / 6.4f);
        float kbW = ku * 18.4f;
        kx = (width - kbW) / 2;
        ky = 34;

        // ---- en-tête : titre, sous-titre, vues, liste classique
        Draw.text(g, "Keyboard", kx, 10, 1.15f, Draw.withAlpha(Theme.TEXT, a));
        String sub = selected < 0 ? "Click a key or press it" : "Type to search an action for " + keyName(selected);
        Draw.text(g, sub, kx + Draw.width("Keyboard", 1.15f) + 10, 13, 0.78f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        float right = kx + kbW;
        if (classic != null) {
            String t = "Classic list";
            float cw = Draw.width(t, 0.8f) + 14;
            right -= cw;
            boolean hv = inside(mx, my, right, 8, cw, 16);
            Draw.rrect(g, right, 8, cw, 16, 4, Draw.withAlpha(hv ? Theme.ROW_HOVER : Theme.ROW, a));
            Draw.text(g, t, right + 7, 12, 0.8f, Draw.withAlpha(Theme.TEXT_DIM, a));
            hit(right, 8, cw, 16, () -> {
                WorldPanel.bypassPacks = true;
                try {
                    minecraft.setScreen(classic);
                } finally {
                    WorldPanel.bypassPacks = false;
                }
            });
            pointer |= hv;
            right -= 8;
        }
        float vw = viewSeg.width(VIEWS, 0.8f);
        viewSeg.draw(g, right - vw, 8, 16, VIEWS, view, mx, my, dt, a);
        pointer |= viewSeg.hovered(mx, my);

        // ---- touches
        String tip = null;
        int tipCount = 0;
        for (K k : KEYS) {
            float x = kx + k.x() * ku, y = ky + k.y() * ku, w = k.w() * ku - 3, h = ku - 3;
            List<Action> bound = on(k.code());
            int n = bound.size();
            boolean hv = inside(mx, my, x, y, w, h);
            boolean held = InputConstants.isKeyDown(minecraft.getWindow(), k.code());
            boolean sel = k.code() == selected;
            boolean faded = view == 1 && n == 0 || view == 2 && n > 0;
            pointer |= hv;
            // surfaces : libre = ROW, utilisée = un ton plus clair, choisie = accent ; conflit = point rouge
            int top = n == 0 ? Theme.ROW : Draw.lerpColor(Theme.ROW_HOVER, Theme.accent(), 0.12f);
            if (sel) top = Theme.accent();
            if (held) top = Draw.lerpColor(top, 0xFFFFFF, 0.25f);
            if (hv && !sel) top = Draw.lerpColor(top, 0xFFFFFF, 0.06f);
            float ka = (faded ? 0.35f : 1f) * a;
            // keycap : face + bord bas plus sombre (relief sans lueur)
            Draw.rrect(g, x, y + 1.5f, w, h - 1.5f, 3, Draw.withAlpha(Draw.lerpColor(top, 0x000000, 0.45f), ka));
            Draw.rrect(g, x, y, w, h - 2, 3, Draw.withAlpha(top, ka));
            if (sel) Draw.ring(g, x, y, w, h - 2, 3, Draw.withAlpha(0xFFFFFF, 0.6f * a));
            String name = keyName(k.code());
            float sc = Math.min(0.82f, (w - 5) / Math.max(1, Draw.width(name, 1f)));
            int labelCol = sel ? 0xFFFFFF : n == 0 ? Theme.TEXT_DIM : Theme.TEXT;
            Draw.text(g, name, x + 3.5f, y + 3f, sc, Draw.withAlpha(labelCol, ka));
            // nom de l'action sous le libellé seulement quand la touche est assez haute (sinon l'infobulle suffit)
            if (n > 0 && h >= 24) {
                String first = bound.get(0).name();
                float s2 = 0.6f;
                List<String> lines = wrap(first, s2, w - 6, h >= 34 ? 2 : 1);
                float ly0 = y + h - 4.5f - lines.size() * 6.2f;
                for (int li = 0; li < lines.size(); li++) {
                    Draw.text(g, lines.get(li), x + 3.5f, ly0 + li * 6.2f, s2, Draw.withAlpha(sel ? 0xFFFFFF : Theme.TEXT_DIM, (sel ? 0.9f : 1f) * ka));
                }
            }
            if (n > 1) Draw.rrect(g, x + w - 6.5f, y + 2.5f, 3.5f, 3.5f, 1.75f, Draw.withAlpha(CONFLICT, ka));
            if (hv && n > 0) {
                StringBuilder sb = new StringBuilder();
                for (Action ac : bound) sb.append(sb.length() == 0 ? "" : "  ·  ").append(ac.name());
                tip = sb.toString();
                tipCount = n;
            }
            int code = k.code();
            hit(x, y, w, h, () -> selectKey(selected == code ? -1 : code));
        }

        // ---- panneau
        float py = ky + 6.5f * ku + 6, ph = height - py - 8;
        Draw.rrect(g, kx, py, kbW, ph, 8, Draw.withAlpha(Theme.PANEL, 0.96f * a));
        Draw.ring(g, kx, py, kbW, ph, 8, Draw.withAlpha(0xFFFFFF, 0.07f * a));
        if (selected < 0) overview(g, mx, my, a, kbW, py, ph);
        else panel(g, mx, my, a, kbW, py, ph);

        if (tip != null) {
            float tw = Draw.width(tip, 0.78f) + 12, tx = Math.min(mx + 10, width - tw - 4), ty = my + 12;
            Draw.rrect(g, tx, ty, tw, 13, 4, Draw.withAlpha(0x0A0912, 0.96f));
            if (tipCount > 1) Draw.rrect(g, tx + 4, ty + 5, 3, 3, 1.5f, CONFLICT | 0xFF000000);
            Draw.text(g, tip, tx + (tipCount > 1 ? 10 : 6), ty + 3, 0.78f, Theme.TEXT | 0xFF000000);
        }
        if (pointer) g.requestCursor(CursorTypes.POINTING_HAND);
    }

    /** Petit cabochon de touche (texte dans le panneau). Retourne sa largeur. */
    private float keycap(GuiGraphics g, String name, float x, float y, boolean conflict, float a) {
        float w = Math.max(16, Draw.width(name, 0.72f) + 8), h = 12;
        Draw.rrect(g, x, y + 1, w, h - 1, 3, Draw.withAlpha(0x000000, 0.45f * a));
        Draw.rrect(g, x, y, w, h - 1.5f, 3, Draw.withAlpha(Theme.ROW_HOVER, a));
        Draw.centered(g, name, x + w / 2, y + 2.5f, 0.72f, Draw.withAlpha(Theme.TEXT, a));
        if (conflict) Draw.rrect(g, x + w - 4, y - 1, 3, 3, 1.5f, Draw.withAlpha(CONFLICT, a));
        return w;
    }

    private float button(GuiGraphics g, float x, float y, String label, boolean primary, boolean danger, int mx, int my, float a, Runnable r) {
        float w = Draw.width(label, 0.8f) + 14, h = 16;
        boolean hv = inside(mx, my, x, y, w, h);
        int bg = primary ? Draw.lerpColor(Theme.accent(), 0xFFFFFF, hv ? 0.1f : 0) : danger ? Draw.lerpColor(0x3A1C22, CONFLICT, hv ? 0.25f : 0) : hv ? Theme.ROW_HOVER : Theme.ROW;
        Draw.rrect(g, x, y, w, h, 4, Draw.withAlpha(bg, a));
        Draw.text(g, label, x + 7, y + 4, 0.8f, Draw.withAlpha(primary ? 0xFFFFFF : danger ? 0xF3B6B0 : Theme.TEXT, a));
        hit(x, y, w, h, r);
        pointer |= hv;
        return w;
    }

    /** Sans touche choisie : les raccourcis attribués, conflits d'abord. */
    private void overview(GuiGraphics g, int mx, int my, float a, float kbW, float py, float ph) {
        float x0 = kx + 12, right = kx + kbW - 12;
        List<Integer> keys = new ArrayList<>(byCode.keySet());
        keys.sort((p, q) -> Integer.compare(byCode.get(q).size() > 1 ? 1 : 0, byCode.get(p).size() > 1 ? 1 : 0));
        int conflicts = 0;
        for (int k : keys) if (byCode.get(k).size() > 1) conflicts++;
        Draw.text(g, "Assigned keys", x0, py + 9, 0.95f, Draw.withAlpha(Theme.TEXT, a));
        String info = keys.size() + " keys in use" + (conflicts == 0 ? "" : "  ·  " + conflicts + (conflicts > 1 ? " conflicts" : " conflict"));
        Draw.text(g, info, right - Draw.width(info, 0.75f), py + 10.5f, 0.75f, Draw.withAlpha(conflicts == 0 ? Theme.TEXT_MUTED : CONFLICT, a));
        float ly = py + 26, rh = 15, colW = (right - x0 - 12) / 2;
        int perCol = Math.max(1, (int) ((py + ph - 20 - ly) / rh));
        int i = 0;
        for (int k : keys) {
            if (i >= perCol * 2) break;
            float cx = x0 + (i / perCol) * (colW + 12), cy = ly + (i % perCol) * rh;
            List<Action> acts = byCode.get(k);
            boolean conflict = acts.size() > 1;
            boolean hv = inside(mx, my, cx - 4, cy - 1, colW + 8, rh);
            pointer |= hv;
            if (hv) Draw.rrect(g, cx - 4, cy - 1, colW + 8, rh - 1, 4, Draw.withAlpha(Theme.ROW, a));
            float kw = keycap(g, keyName(k), cx, cy, conflict, a);
            StringBuilder sb = new StringBuilder();
            for (Action ac : acts) sb.append(sb.isEmpty() ? "" : "  ·  ").append(ac.name());
            String t = fit(sb.toString(), 0.78f, colW - kw - 10);
            Draw.text(g, t, cx + kw + 8, cy + 2.5f, 0.78f, Draw.withAlpha(conflict ? Theme.TEXT : Theme.TEXT_DIM, a));
            int code = k;
            hit(cx - 4, cy - 1, colW + 8, rh - 1, () -> selectKey(code));
            i++;
        }
        Draw.text(g, "Mouse buttons are never assigned here", x0, py + ph - 13, 0.72f, Draw.withAlpha(Theme.TEXT_MUTED, a));
    }

    /** Touche choisie : ce qu'elle fait, recherche et liste des actions à lui donner. */
    private void panel(GuiGraphics g, int mx, int my, float a, float kbW, float py, float ph) {
        float x0 = kx + 12, right = kx + kbW - 12;
        List<Action> bound = on(selected);
        // ligne 1 : grande touche, état, recherche, filtre
        float ty = py + 8;
        String kn = keyName(selected);
        float bw = Math.max(26, Draw.width(kn, 1.05f) + 14);
        Draw.rrect(g, x0, ty + 2, bw, 20, 4, Draw.withAlpha(Draw.lerpColor(Theme.accent(), 0x000000, 0.45f), a));
        Draw.rrect(g, x0, ty, bw, 19, 4, Draw.withAlpha(Theme.accent(), a));
        Draw.centered(g, kn, x0 + bw / 2, ty + 5, 1.05f, 0xFFFFFFFF);
        String state = bound.isEmpty() ? "Free key" : bound.size() == 1 ? "1 action" : bound.size() + " actions  ·  conflict";
        Draw.text(g, state, x0 + bw + 8, ty + 6, 0.8f, Draw.withAlpha(bound.size() > 1 ? CONFLICT : Theme.TEXT_DIM, a));
        float fx = right;
        float fw = filterSeg.width(FILTERS, 0.8f);
        fx -= fw;
        filterSeg.draw(g, fx, ty + 3, 16, FILTERS, filter, mx, my, dt, a);
        pointer |= filterSeg.hovered(mx, my);
        float sx = x0 + bw + 8 + Draw.width(state, 0.8f) + 14, sw = fx - 10 - sx;
        boolean sh = inside(mx, my, sx, ty + 3, sw, 16);
        Draw.rrect(g, sx, ty + 3, sw, 16, 5, Draw.withAlpha(0x000000, 0.3f * a));
        Draw.ring(g, sx, ty + 3, sw, 16, 5, Draw.withAlpha(searchFocus ? Theme.accent() : 0xFFFFFF, (searchFocus ? 0.7f : 0.12f) * a));
        Draw.icon(g, Icons.SEARCH, sx + 10, ty + 11, 7, Draw.withAlpha(Theme.TEXT_MUTED, a));
        boolean caret = searchFocus && (System.currentTimeMillis() / 530) % 2 == 0;
        if (search.isEmpty()) Draw.text(g, fit("Search an action", 0.82f, sw - 24), sx + 19, ty + 7.5f, 0.82f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        else Draw.text(g, search + (caret ? "|" : ""), sx + 19, ty + 7.5f, 0.82f, Draw.withAlpha(Theme.TEXT, a));
        if (search.isEmpty() && caret) Draw.rrect(g, sx + 19, ty + 7, 0.8f, 8, 0.4f, Draw.withAlpha(0xFFFFFF, a));
        hit(sx, ty + 3, sw, 16, () -> searchFocus = true);
        pointer |= sh;

        // ligne 2 : actions déjà sur la touche, chacune retirable
        float by = ty + 28;
        if (!bound.isEmpty()) {
            Draw.text(g, "On this key", x0, by + 3, 0.72f, Draw.withAlpha(Theme.TEXT_MUTED, a));
            float bx = x0 + Draw.width("On this key", 0.72f) + 10;
            for (Action ac : bound) {
                float w = Draw.width(ac.name(), 0.8f) + 22;
                if (bx + w > right - 70) break;
                boolean hv = inside(mx, my, bx, by, w, 14);
                pointer |= hv;
                Draw.rrect(g, bx, by, w, 14, 4, Draw.withAlpha(hv ? Theme.ROW_HOVER : Theme.ROW, a));
                Draw.text(g, ac.name(), bx + 6, by + 3.5f, 0.8f, Draw.withAlpha(Theme.TEXT, a));
                Draw.text(g, "×", bx + w - 10, by + 3, 0.85f, Draw.withAlpha(hv ? 0xF3B6B0 : Theme.TEXT_MUTED, a));
                hit(bx, by, w, 14, () -> {
                    ac.bind(-1);
                    cachedActions = null;
                    click(0.9f);
                });
                bx += w + 4;
            }
            if (bound.size() > 1) {
                float cw = Draw.width("Clear key", 0.8f) + 14;
                button(g, right - cw, by - 1, "Clear key", false, true, mx, my, a, () -> {
                    for (Action ac : new ArrayList<>(bound)) ac.bind(-1);
                    cachedActions = null;
                    click(0.85f);
                });
            }
            by += 20;
        }

        // liste des actions (résultats)
        List<Action> list = filtered();
        lastList = list;
        cursor = Math.max(0, Math.min(list.size() - 1, cursor));
        boolean footer = ph > 140;
        float ly = by + 2, lh = py + ph - ly - (footer ? 18 : 6), rh = 17;
        float maxScroll = Math.max(0, list.size() * rh - lh);
        if (cursor * rh < listScroll) listScroll = cursor * rh;
        if (cursor * rh + rh > listScroll + lh) listScroll = cursor * rh + rh - lh;
        listScroll = Math.max(0, Math.min(maxScroll, listScroll));
        g.enableScissor((int) x0 - 4, (int) ly, (int) right + 4, (int) (ly + lh));
        for (int i = 0; i < list.size(); i++) {
            float y = ly + i * rh - listScroll;
            if (y + rh < ly || y > ly + lh) continue;
            Action ac = list.get(i);
            boolean hv = inside(mx, my, x0 - 4, y, right - x0 + 8, rh - 1) && my >= ly && my < ly + lh;
            pointer |= hv;
            if (hv) cursor = i;
            boolean here = ac.code() == selected, active = i == cursor;
            if (active || here) Draw.rrect(g, x0 - 4, y, right - x0 + 8, rh - 1, 4, Draw.withAlpha(here ? Draw.lerpColor(Theme.ROW, Theme.accent(), 0.18f) : Theme.ROW, a));
            Draw.text(g, ac.name(), x0 + 4, y + 4.5f, 0.85f, Draw.withAlpha(Theme.TEXT, a));
            float gx = x0 + 4 + Math.min(190, Draw.width(ac.name(), 0.85f)) + 10;
            Draw.text(g, ac.group(), gx, y + 5.5f, 0.7f, Draw.withAlpha(Theme.TEXT_MUTED, a));
            // à droite : touche actuelle, puis l'action du clic sur la ligne active
            int code = ac.code();
            String act = here ? "Remove" : active ? (code < 0 ? "Assign" : "Move here") : "";
            float aw = act.isEmpty() ? 0 : Draw.width(act, 0.75f) + 12;
            if (!act.isEmpty()) {
                Draw.rrect(g, right - aw, y + 2, aw, rh - 5, 4, Draw.withAlpha(here ? 0x3A1C22 : Theme.accent(), a));
                Draw.centered(g, act, right - aw / 2, y + 4.5f, 0.75f, Draw.withAlpha(here ? 0xF3B6B0 : 0xFFFFFF, a));
            }
            if (code >= 0) {
                String cur = keyName(code);
                float cw = Math.max(16, Draw.width(cur, 0.72f) + 8);
                keycap(g, cur, right - aw - (aw > 0 ? 8 : 0) - cw, y + 2.5f, on(code).size() > 1, a);
            } else if (!active) {
                Draw.text(g, "—", right - 8, y + 4.5f, 0.8f, Draw.withAlpha(Theme.TEXT_MUTED, a));
            }
            hit(x0 - 4, Math.max(ly, y), right - x0 + 8, Math.min(y + rh - 1, ly + lh) - Math.max(ly, y), () -> assign(ac));
        }
        g.disableScissor();
        if (list.isEmpty()) Draw.centered(g, "No action matches", kx + kbW / 2, ly + lh / 2 - 4, 0.9f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        if (footer) {
            Draw.text(g, "Up / Down to pick  ·  Enter to assign  ·  Tab to filter  ·  Esc to go back", x0, py + ph - 13, 0.72f, Draw.withAlpha(Theme.TEXT_MUTED, a));
            String count = list.size() + (list.size() == 1 ? " action" : " actions");
            Draw.text(g, count, right - Draw.width(count, 0.72f), py + ph - 13, 0.72f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        }
    }

    // ===================================================================== utilitaires

    private static List<String> wrap(String s, float scale, float w, int max) {
        List<String> out = new ArrayList<>();
        String rest = s;
        while (!rest.isEmpty() && out.size() < max) {
            if (Draw.width(rest, scale) <= w) {
                out.add(rest);
                return out;
            }
            int cut = rest.length();
            while (cut > 1 && Draw.width(rest.substring(0, cut), scale) > w) cut--;
            int sp = rest.lastIndexOf(' ', cut);
            if (sp > 0 && out.size() < max - 1) cut = sp;
            String line = rest.substring(0, cut).trim();
            rest = rest.substring(cut).trim();
            if (out.size() == max - 1 && !rest.isEmpty()) {
                while (line.length() > 1 && Draw.width(line + "…", scale) > w) line = line.substring(0, line.length() - 1);
                line += "…";
                rest = "";
            }
            out.add(line);
        }
        return out;
    }

    private static String fit(String s, float scale, float max) {
        if (Draw.width(s, scale) <= max) return s;
        while (s.length() > 1 && Draw.width(s + "…", scale) > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private static boolean inside(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void selectKey(int code) {
        selected = code;
        search = "";
        searchFocus = code >= 0;
        cursor = 0;
        listScroll = 0;
        click(1.1f);
    }

    /** Clic sur une action : l'attribue à la touche choisie (ou la retire si elle y est déjà). */
    private void assign(Action ac) {
        ac.bind(ac.code() == selected ? -1 : selected);
        cachedActions = null;
        click(1.2f);
    }

    private List<Action> filtered() {
        String q = search.toLowerCase(Locale.ROOT).trim();
        List<Action> starts = new ArrayList<>(), contains = new ArrayList<>();
        for (Action a : actions()) {
            if (filter == 1 && a.module() == null || filter == 2 && a.module() != null) continue;
            String n = a.name().toLowerCase(Locale.ROOT);
            if (q.isEmpty() || n.startsWith(q)) starts.add(a);
            else if (n.contains(q) || a.group().toLowerCase(Locale.ROOT).contains(q)) contains.add(a);
        }
        if (q.isEmpty()) {
            // sans recherche : ce qui est déjà sur la touche d'abord, puis le reste
            List<Action> here = new ArrayList<>(), rest = new ArrayList<>();
            for (Action a : starts) (a.code() == selected ? here : rest).add(a);
            here.addAll(rest);
            return here;
        }
        starts.addAll(contains);
        return starts;
    }

    private static String keyName(int code) {
        if (code < 0) return "—";
        return switch (code) {
            case GLFW.GLFW_KEY_SPACE -> "Space";
            case GLFW.GLFW_KEY_LEFT_SHIFT -> "Shift";
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> "R Shift";
            case GLFW.GLFW_KEY_LEFT_CONTROL -> "Ctrl";
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> "R Ctrl";
            case GLFW.GLFW_KEY_LEFT_ALT -> "Alt";
            case GLFW.GLFW_KEY_RIGHT_ALT -> "Alt Gr";
            case GLFW.GLFW_KEY_ESCAPE -> "Esc";
            case GLFW.GLFW_KEY_ENTER -> "Enter";
            case GLFW.GLFW_KEY_BACKSPACE -> "Back";
            case GLFW.GLFW_KEY_TAB -> "Tab";
            case GLFW.GLFW_KEY_CAPS_LOCK -> "Caps";
            case GLFW.GLFW_KEY_INSERT -> "Ins";
            case GLFW.GLFW_KEY_DELETE -> "Del";
            case GLFW.GLFW_KEY_PAGE_UP -> "PgUp";
            case GLFW.GLFW_KEY_PAGE_DOWN -> "PgDn";
            case GLFW.GLFW_KEY_UP -> "↑";
            case GLFW.GLFW_KEY_DOWN -> "↓";
            case GLFW.GLFW_KEY_LEFT -> "←";
            case GLFW.GLFW_KEY_RIGHT -> "→";
            default -> InputConstants.Type.KEYSYM.getOrCreate(code).getDisplayName().getString();
        };
    }

    // ===================================================================== entrées

    private void click(float pitch) {
        if (Settings.sounds) minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch, 0.3f));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean dbl) {
        double mx = event.x(), my = event.y();
        if (event.button() != 0) return super.mouseClicked(event, dbl);
        int v = viewSeg.hit(mx, my);
        if (v >= 0) {
            view = v;
            click(1.05f);
            return true;
        }
        if (selected >= 0) {
            int f = filterSeg.hit(mx, my);
            if (f >= 0) {
                filter = f;
                cursor = 0;
                listScroll = 0;
                click(1.05f);
                return true;
            }
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (inside(mx, my, h.x(), h.y(), h.w(), h.h())) {
                h.action().run();
                return true;
            }
        }
        return super.mouseClicked(event, dbl);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (selected >= 0 && !lastList.isEmpty()) cursor = Math.max(0, Math.min(lastList.size() - 1, cursor - (int) Math.signum(sy) * 3));
        return true;
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (selected >= 0 && event.isAllowedChatCharacter() && search.length() < 32) {
            search += event.codepointAsString();
            searchFocus = true;
            cursor = 0;
            listScroll = 0;
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (!search.isEmpty()) search = "";
            else if (selected >= 0) selected = -1;
            else onClose();
            return true;
        }
        if (selected >= 0) {
            switch (key) {
                case GLFW.GLFW_KEY_BACKSPACE -> {
                    if (!search.isEmpty()) search = search.substring(0, search.length() - 1);
                    cursor = 0;
                }
                case GLFW.GLFW_KEY_DOWN -> cursor = Math.min(lastList.size() - 1, cursor + 1);
                case GLFW.GLFW_KEY_UP -> cursor = Math.max(0, cursor - 1);
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    if (cursor >= 0 && cursor < lastList.size()) assign(lastList.get(cursor));
                }
                case GLFW.GLFW_KEY_TAB -> {
                    filter = (filter + 1) % FILTERS.length;
                    cursor = 0;
                }
                default -> {
                    return super.keyPressed(event);
                }
            }
            return true;
        }
        for (K k : KEYS) {
            if (k.code() == key) {
                selectKey(key);
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    public void debugSelect(int code) {
        selected = code;
        searchFocus = true;
    }

    public void debugSearch(String s) {
        search = s;
    }
}
