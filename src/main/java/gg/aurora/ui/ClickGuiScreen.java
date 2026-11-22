package gg.aurora.ui;

import gg.aurora.Boost;
import gg.aurora.Config;
import gg.aurora.Settings;
import gg.aurora.module.HudModule;
import gg.aurora.module.Module;
import gg.aurora.module.Modules;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Menu Paranoia (RShift). Une seule coque : barre latérale à gauche (catégories de modules, puis
 * Wardrobe / Friends / Settings), contenu au centre. Vue Modules : filtres, recherche, cartes compactes
 * sur trois colonnes ; un module ouvert fait glisser un tiroir de réglages depuis la droite (la grille
 * passe à deux colonnes). Vue Settings : les sections dans la barre latérale, les réglages au centre.
 * Il arrive en 3D (voir {@link gg.aurora.render.Panel3D}).
 */
public class ClickGuiScreen extends Screen {
    enum Tab {
        COMBAT("Combat", Icons.SWORD), COSMETIQUES("Visuals", Icons.SPARKLES), HUD("HUD", Icons.HUD), OPTIMISATION("Performance", Icons.BOLT), CONFORT("Utility", Icons.SLIDERS),
        GARDE_ROBE("Wardrobe", Icons.CAPE), AMIS("Friends", Icons.FRIENDS), PICTURES("Pictures", Icons.IMAGE), REGLAGES("Settings", Icons.GEAR);

        final String label, icon;

        Tab(String label, String icon) {
            this.label = label;
            this.icon = icon;
        }

        boolean modules() {
            return ordinal() <= 4;
        }
    }

    private static final String[] FILTERS = {"All", "Enabled", "Favorites", "Heavy"};
    private static final String[] PRESETS = {"Quality", "Balanced", "High FPS"};

    /** Modules d'optimisation (le reste des catégories se répartit entre cosmétique et confort). */
    private static final Set<String> OPTIM = Set.of("optimizer", "smooth_scroll", "hud_cache", "smart_culling", "adaptive_detail", "menu_fps", "adaptive_mode",
            "screen_limit", "scoreboard_cache", "crystal_optimizer", "gap_optimizer", "anchor_optimizer", "projectile_optimizer");
    private static final Set<String> COSMETIC = Set.of("shockwaves", "custom_sky", "weather", "block_outline", "draw_crosshair", "item_size",
            "item_physic", "hit_color", "hit_effects", "combat_sounds", "emotes", "custom_glint", "totem_anim", "no_totem_anim",
            "hand_pose", "hand_aura", "old_animations", "swing_animation", "low_fire");
    /** Coût de rendu indicatif (badge « Heavy » / « Medium ») ; le diagnostic mesure le réel. */
    private static final Set<String> HEAVY = Set.of("ground_mirror", "motion_blur", "custom_sky", "filters", "hologram", "friend_glow");
    private static final Set<String> MEDIUM = Set.of("hand_aura", "ambient_particles", "trail", "jump_circles", "target_esp", "item_flames",
            "bats", "hitboxes", "chat_plus", "now_playing");

    static Tab lastTab = Tab.COMBAT;
    private static String lastModule;
    private static int lastSection;

    private Tab tab = lastTab.modules() ? lastTab : Tab.COMBAT;
    /** Vrai : la barre latérale montre les sections de réglages et le contenu la section ouverte. */
    private boolean settingsView;
    /** Page intégrée (Wardrobe, Friends) affichée à la place des modules. */
    private SubScreen page;
    private Tab pageTab;
    private int section = lastSection;
    private List<SettingsSections.Section> sections;
    private int filter;
    private String query = "";
    private boolean searchFocus, presetOpen, closing;
    private Module selected;
    private final long openedAt = System.nanoTime();
    private long closeAt, contentShownAt = System.nanoTime();
    private long lastFrame = System.nanoTime();
    private float frameDt;
    private boolean pointer;

    private final Map<Object, Spring> hover = new HashMap<>();
    private final Map<Module, Spring> knobs = new HashMap<>();
    private final Spring sideY = new Spring(0, 260, 0.85f);
    private final Spring drawer = new Spring(0, 240, 0.9f);
    private final Segmented filters = new Segmented();
    private final RowPanel.Callbacks callbacks = new RowPanel.Callbacks() {
        public void pointer() {
            pointer = true;
        }

        public void sound(float pitch) {
            click(pitch);
        }

        public void changed() {
            Config.save();
        }
    };
    private final RowPanel detail = new RowPanel(() -> frameDt, callbacks);
    private final RowPanel settingsPanel = new RowPanel(() -> frameDt, callbacks);

    // géométrie (unités GUI), recalculée à chaque image
    private float px, py, pw, ph, sideX, sideW, cx, cw, gridY, gridH, drawerX, drawerW = 178;
    private static final float HEADER = 28, SIDE_ROW_MAX = 17, CARD_H = 21, CARD_GAP = 4, FOOTER = 15;

    /** Écran rendu à la fermeture (écran titre) ; null en jeu. */
    private final Screen back;

    public ClickGuiScreen() {
        this(null);
    }

    public ClickGuiScreen(Screen back) {
        super(Component.literal("Paranoia"));
        this.back = back;
    }

    /** Ouvre directement les réglages (bouton « Client » de l'écran titre). */
    public static ClickGuiScreen settings(Screen back) {
        ClickGuiScreen s = new ClickGuiScreen(back);
        s.startInSettings = true;
        return s;
    }

    private boolean startInSettings;

    /** HUD masqué tant que le menu est ouvert (rendu propre, et la 3D n'a pas de bord visible). */
    private Boolean savedHideGui;

    @Override
    protected void init() {
        if (page != null) page.embed(this, minecraft, width, height);
        if (savedHideGui == null && !WorldPanel.inHostedCall) {
            savedHideGui = minecraft.options.hideGui;
            minecraft.options.hideGui = true;
        }
        if (sections == null) {
            sections = SettingsSections.build(this);
            settingsPanel.setRows(sections.get(section).rows());
        }
        if (startInSettings) {
            startInSettings = false;
            settingsView = true;
            contentShownAt = System.nanoTime();
        }
        if (selected == null && lastModule != null) {
            Module m = Modules.byId(lastModule);
            if (m != null && pillar(m) == tab) select(m, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ===================================================================== modules affichés

    /** Where a module lives: by what it does for the player, one obvious place each. */
    static Tab pillar(Module m) {
        if (m.category == gg.aurora.module.Category.HUD) return Tab.HUD;
        if (m.category == gg.aurora.module.Category.PVP) return Tab.COMBAT;
        if (OPTIM.contains(m.id)) return Tab.OPTIMISATION;
        if (m.category == gg.aurora.module.Category.VISUALS || COSMETIC.contains(m.id)) return Tab.COSMETIQUES;
        return Tab.CONFORT;
    }

    private static Set<String> favorites() {
        Set<String> s = new LinkedHashSet<>();
        for (String id : Settings.favorites.split(",")) if (!id.isBlank()) s.add(id);
        return s;
    }

    private static void setFavorite(String id, boolean on) {
        Set<String> s = favorites();
        if (on) s.add(id);
        else s.remove(id);
        Settings.favorites = String.join(",", s);
        Config.save();
    }

    private List<Module> visible() {
        List<Module> out = new ArrayList<>();
        Set<String> fav = favorites();
        String q = query.toLowerCase(Locale.ROOT);
        for (Module m : Modules.ALL) {
            // la recherche parcourt toutes les catégories
            if (q.isEmpty() ? pillar(m) != tab : !m.name.toLowerCase(Locale.ROOT).contains(q) && !m.id.contains(q)) continue;
            if (filter == 1 && !m.enabled || filter == 2 && !fav.contains(m.id) || filter == 3 && !HEAVY.contains(m.id) && !MEDIUM.contains(m.id))
                continue;
            out.add(m);
        }
        // regroupés par ce qu'ils font, A-Z dans un groupe
        out.sort(java.util.Comparator.<Module>comparingInt(m -> m.groupOrder).thenComparing(m -> m.name.toLowerCase(Locale.ROOT)));
        return out;
    }

    private static String cost(Module m) {
        return HEAVY.contains(m.id) ? "Heavy" : MEDIUM.contains(m.id) ? "Medium" : "Light";
    }

    private static int costColor(Module m) {
        return HEAVY.contains(m.id) ? 0xF2545B : MEDIUM.contains(m.id) ? 0xF2B84A : 0x5EE6A8;
    }

    private void select(Module m, boolean sound) {
        selected = m;
        lastModule = m == null ? null : m.id;
        List<Rows.Row> rows = new ArrayList<>();
        if (m != null) {
            if (m instanceof HudModule h) rows.add(new HudPreviewRow(h));
            else {
                // aperçu capturé de l'effet (modules Visuals), s'il est dans le jar
                var pid = net.minecraft.resources.Identifier.fromNamespaceAndPath("aurora", "textures/preview/" + m.id + ".png");
                if (minecraft.getResourceManager().getResource(pid).isPresent()) rows.add(new Rows.Picture(pid, 384, 216, 6));
            }
            rows.addAll(m.settings());
            rows.add(new Rows.KeyBind("Shortcut", "Key to toggle", () -> m.key, v -> {
                m.key = v;
                Config.save();
            }));
            rows.add(new Rows.Button("Reset", "Back to its default settings", Icons.ARROW_LEFT, () -> {
                m.opts.clear();
                if (m instanceof HudModule h) h.resetStyle();
                Config.save();
                minecraft.execute(() -> {
                    if (selected == m) select(m, false);
                });
            }));
        }
        detail.setRows(rows);
        if (sound) click(1.1f);
    }

    private void showSection(int i) {
        section = lastSection = i;
        settingsPanel.setRows(sections.get(i).rows());
        contentShownAt = System.nanoTime();
    }

    private void showTab(Tab t) {
        tab = lastTab = t;
        query = "";
        gridScroll = gridTarget = 0;
        contentShownAt = System.nanoTime();
        if (selected != null && pillar(selected) != t) select(null, false);
    }

    // ===================================================================== animation

    /** 0 → 1 à l'ouverture, 1 → 0 à la fermeture. */
    /** Outil de dev : progression figée pour les captures (-1 = normal). */
    public static float devProgress = -1;

    private float progress() {
        if (devProgress >= 0) return devProgress;
        if (WorldPanel.hosted == this) return closing ? 0 : 1; // dans le monde : pas d'arrivée à l'écran
        if (!Settings.anim()) return closing ? 0 : 1;
        float f = Settings.animFactor();
        if (closing) return Math.max(0, 1 - (System.nanoTime() - closeAt) / (0.15e9f / f));
        return Math.min(1, (System.nanoTime() - openedAt) / ((cinema() ? 0.42e9f : 0.26e9f) / f));
    }

    /** Arrivée 3D en perspective seulement en style « Cinema » ; sinon fondu, légère montée et mise à l'échelle. */
    private static boolean cinema() {
        return Settings.menuAnim == 3;
    }

    /** Entrée du contenu après un changement de vue : fondu + 6 unités de montée, 180 ms. */
    /** Sens de l'entrée du contenu : 1 = il monte (élément plus bas choisi), -1 = il descend. */
    private float contentDir = 1;

    private float contentIn() {
        if (!Settings.anim()) return 1;
        return Draw.easeOutCubic((System.nanoTime() - contentShownAt) / (0.18e9f / Settings.animFactor()));
    }

    // ===================================================================== rendu

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // pas de flou : le monde reste visible derrière le panneau, légèrement assombri
        if (WorldPanel.rendering) return; // panneau dans le monde : fond transparent
        float p = progress();
        // pendant l'arrivée en 3D, c'est le shader qui assombrit (sinon le fond serait inégal)
        if (cinema() && p < 1 && gg.aurora.render.Panel3D.available()) return;
        // en jeu, voile léger : la vue du joueur reste lisible derrière le panneau
        g.fill(0, 0, width, height, Draw.withAlpha(0x05040C, (inWorld() ? 0.12f : 0.45f) * Draw.easeOutCubic(p)));
    }

    /** Ouvert en partie : panneau translucide, on voit le monde à travers. */
    private boolean inWorld() {
        return minecraft != null && minecraft.level != null;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        long nowNs = System.nanoTime();
        frameDt = Math.min(0.1f, (nowNs - lastFrame) / 1e9f);
        lastFrame = nowNs;
        float p = progress();
        if (closing && p <= 0) {
            gg.aurora.render.Panel3D.clear();
            minecraft.setScreen(back);
            return;
        }
        pointer = false;
        pw = Math.min(600, width - 24);
        ph = Math.min(340, height - 24);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        sideX = px + 8;
        sideW = 104;
        cx = sideX + sideW + 10;
        cw = px + pw - 8 - cx;

        // 3D : tant que l'animation tourne, le panneau dessiné à plat est reprojeté en perspective
        float scale = (float) minecraft.getWindow().getGuiScale();
        boolean flat = !cinema() || !gg.aurora.render.Panel3D.available();
        if (!flat && p < 1 && !WorldPanel.rendering) gg.aurora.render.Panel3D.set(px * scale, py * scale, pw * scale, ph * scale, 10 * scale, p, closing);
        else if (!WorldPanel.rendering) gg.aurora.render.Panel3D.clear();

        // arrivée à plat : fondu, montée de 10 unités, échelle 0,965 → 1 (ease-out) ; fermeture plus courte, sans montée
        float e = closing ? Draw.easeInOut(p) : 1 - (float) Math.pow(1 - p, 4);
        float a = flat && !WorldPanel.rendering ? Draw.clamp01(e * 1.15f) : 1;
        boolean posed = flat && !WorldPanel.rendering && p < 1;
        if (posed) {
            float sc = (closing ? 0.98f : 0.965f) + (1 - (closing ? 0.98f : 0.965f)) * e;
            float lift = closing ? 0 : (1 - e) * 10;
            g.pose().pushMatrix();
            g.pose().translate(width / 2f, height / 2f + lift);
            g.pose().scale(sc, sc);
            g.pose().translate(-width / 2f, -height / 2f);
        }
        menuAlpha = a;
        // dans le monde, la dalle 3D sert de fond : on ne dessine que le contenu (il garde son relief)
        if (!WorldPanel.rendering) {
            boolean see = inWorld();
            if (p >= 1 || flat) Draw.glow(g, px, py + 10, pw, ph, 24, Draw.withAlpha(0x000000, (see ? 0.3f : 0.6f) * a));
            // verre : le fond laisse passer le monde, les cartes et rangées gardent leur surface pour le texte
            Draw.rrect(g, px, py, pw, ph, 10, Draw.withAlpha(Theme.PANEL, (see ? 0.6f : 0.97f) * a));        }
        Draw.ring(g, px, py, pw, ph, 10, Draw.withAlpha(0xFFFFFF, (inWorld() ? 0.16f : 0.10f) * a));
        drawHeader(g, mx, my, a);
        drawSidebar(g, mx, my, a);
        if (page != null) drawPage(g, mx, my, a);
        else if (settingsView) drawSettings(g, mx, my, a);
        else drawModules(g, mx, my, a);
        drawFooter(g, a);
        if (presetOpen) drawPresets(g, mx, my, a);
        if (posed) g.pose().popMatrix();
    }

    /** Opacité globale du panneau pendant l'arrivée (les pages intégrées s'en servent). */
    private float menuAlpha = 1;

    private Spring hv(Object key, boolean on) {
        Spring s = hover.computeIfAbsent(key, k -> new Spring(0, 320, 1f));
        s.update(frameDt, on ? 1 : 0);
        return s;
    }

    private float presetX, presetY, presetW = 72;

    private void drawHeader(GuiGraphics g, int mx, int my, float a) {
        float hx = px + 14, hy = py + 8;
        Draw.icon(g, Icons.EYE_LOGO, hx + 6, hy + 6.5f, 13, Draw.withAlpha(Theme.accent(), a));
        Draw.text(g, "Paranoia", hx + 17, hy + 1.5f, 1.15f, Draw.withAlpha(Theme.TEXT, a));
        // fermer
        float bx = px + pw - 23, by = py + 7;
        boolean ch = inside(mx, my, bx, by, 15, 14);
        pointer |= ch;
        float e = hv("close", ch).value;
        Draw.rrect(g, bx, by, 15, 14, 4, Draw.withAlpha(Draw.lerpColor(Theme.ROW, 0x5A2430, e), (0.8f + 0.2f * e) * a));
        Draw.centered(g, "×", bx + 7.5f, by + 2.6f, 1.05f, Draw.withAlpha(Theme.TEXT, a));
        // préréglage de performance : étiquette + bouton secondaire
        presetX = bx - presetW - 8;
        presetY = by;
        boolean ph1 = inside(mx, my, presetX, presetY, presetW, 14);
        pointer |= ph1;
        float pe = hv("preset", ph1 || presetOpen).value;
        Draw.text(g, "PRESET", presetX - 4 - Draw.width("PRESET", 0.62f), presetY + 4, 0.62f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        Draw.rrect(g, presetX, presetY, presetW, 14, 4, Draw.withAlpha(Draw.lerpColor(Theme.ROW, Theme.ROW_HOVER, pe), a));
        Draw.icon(g, Icons.BOLT, presetX + 8, presetY + 7, 7, Draw.withAlpha(Theme.accent(), a));
        Draw.text(g, PRESETS[currentPreset()], presetX + 14, presetY + 3.5f, 0.8f, Draw.withAlpha(Theme.TEXT, a));
        Draw.icon(g, Icons.CARET_DOWN, presetX + presetW - 7, presetY + 7.3f, 6, Draw.withAlpha(Theme.TEXT_DIM, a));
    }

    private static int currentPreset() {
        if (Boost.active) return 2;
        return Settings.cosmeticQuality == 0 ? 0 : 1;
    }

    private void applyPreset(int i) {
        if (i == 2) {
            if (!Boost.active) Boost.toggle();
            Settings.cosmeticQuality = 2;
        } else {
            if (Boost.active) Boost.toggle();
            Settings.cosmeticQuality = i == 0 ? 0 : 1;
        }
        Config.save();
        Toasts.push(Icons.BOLT, "Preset " + PRESETS[i], i == 2 ? "Boost + light cosmetics" : i == 0 ? "Maximum quality" : "Quality / FPS balance", false);
    }

    private void drawPresets(GuiGraphics g, int mx, int my, float a) {
        float x = presetX - 30, y = presetY + 17, w = presetW + 30, h = 3 * 18 + 6;
        Draw.glow(g, x, y + 3, w, h, 10, Draw.withAlpha(0x000000, 0.5f * a));
        Draw.rrect(g, x, y, w, h, 6, Draw.withAlpha(Theme.ROW, 0.98f * a));
        String[] sub = {"All maxed", "Default", "Boost + light"};
        for (int i = 0; i < 3; i++) {
            float ry = y + 3 + i * 18;
            boolean h1 = inside(mx, my, x + 3, ry, w - 6, 17);
            pointer |= h1;
            boolean cur = currentPreset() == i;
            if (h1 || cur) Draw.rrect(g, x + 3, ry, w - 6, 17, 4, Draw.withAlpha(Theme.ROW_HOVER, (cur ? 1f : 0.7f) * a));
            if (cur) Draw.rrect(g, x + 5, ry + 4, 2, 9, 1, Draw.withAlpha(Theme.accent(), a));
            Draw.text(g, PRESETS[i], x + 11, ry + 2.5f, 0.85f, Draw.withAlpha(Theme.TEXT, a));
            Draw.text(g, sub[i], x + 11, ry + 10.3f, 0.62f, Draw.withAlpha(Theme.TEXT_DIM, a));
        }
    }

    // ---------------------------------------------------------------- barre latérale

    /** Entrées de la barre latérale : l'étiquette, l'icône, et ce qu'un clic fait. */
    private record SideItem(String label, String icon, boolean active, Runnable action) {}

    private List<SideItem> sideItems() {
        List<SideItem> out = new ArrayList<>();
        if (settingsView) {
            out.add(new SideItem("Modules", Icons.ARROW_LEFT, false, () -> {
                settingsView = false;
                SettingsSections.closed();
                contentShownAt = System.nanoTime();
            }));
            out.add(null); // séparateur
            for (int i = 0; i < sections.size(); i++) {
                int idx = i;
                SettingsSections.Section s = sections.get(i);
                out.add(new SideItem(s.name(), s.icon(), section == i, () -> showSection(idx)));
            }
            return out;
        }
        for (Tab t : Tab.values()) {
            if (t == Tab.GARDE_ROBE) out.add(null);
            Runnable r = switch (t) {
                case GARDE_ROBE, AMIS, PICTURES -> () -> openPage(t);
                case REGLAGES -> () -> {
                    closePage();
                    settingsView = true;
                    contentShownAt = System.nanoTime();
                    settingsPanel.setRows(sections.get(section).rows());
                };
                default -> () -> {
                    closePage();
                    showTab(t);
                };
            };
            boolean active = page != null ? pageTab == t : t.modules() && t == tab && query.isEmpty();
            out.add(new SideItem(t.label, t.icon, active, r));
        }
        return out;
    }

    /** Pas de la barre latérale : taille normale, resserrée quand la fenêtre est basse (Settings reste visible). */
    private float sideRow = SIDE_ROW_MAX, sideGap = 2, sideSep = 7;

    private void fitSidebar(List<SideItem> items) {
        int n = 0, seps = 0;
        for (SideItem it : items) if (it == null) seps++; else n++;
        float avail = ph - HEADER - 6 - FOOTER - 4;
        sideRow = SIDE_ROW_MAX;
        sideGap = 2;
        sideSep = 7;
        if (n * (sideRow + sideGap) + seps * sideSep <= avail || n == 0) return;
        sideSep = 5;
        sideGap = 1;
        sideRow = Math.max(12, (avail - seps * sideSep) / n - sideGap);
    }

    private float sideItemY(int index, List<SideItem> items) {
        float y = py + HEADER + 6;
        for (int i = 0; i < index; i++) y += items.get(i) == null ? sideSep : sideRow + sideGap;
        return y;
    }

    private void drawSidebar(GuiGraphics g, int mx, int my, float a) {
        List<SideItem> items = sideItems();
        fitSidebar(items);
        int active = -1;
        for (int i = 0; i < items.size(); i++) if (items.get(i) != null && items.get(i).active()) active = i;
        if (active >= 0) {
            float ty = sideItemY(active, items);
            if (sideY.value == 0 && sideY.target == 0) sideY.set(ty);
            sideY.update(frameDt, ty);
            // sélection : surface montée + barre d'accent, qui glissent ensemble vers le nouvel élément
            Draw.rrect(g, sideX, sideY.value, sideW, sideRow, 5, Draw.withAlpha(Theme.ROW_HOVER, 0.95f * a));
            Draw.rrect(g, sideX + 2, sideY.value + 4, 2, sideRow - 8, 1, Draw.withAlpha(Theme.accent(), a));
        }
        for (int i = 0; i < items.size(); i++) {
            SideItem it = items.get(i);
            float y = sideItemY(i, items);
            if (it == null) {
                Draw.rrect(g, sideX + 8, y + sideSep / 2 - 0.5f, sideW - 16, 0.8f, 0.4f, Draw.withAlpha(0xFFFFFF, 0.07f * a));
                continue;
            }
            boolean h1 = inside(mx, my, sideX, y, sideW, sideRow);
            pointer |= h1 && !it.active();
            float e = hv("side" + it.label(), h1 && !it.active()).value;
            if (e > 0.01f) Draw.rrect(g, sideX, y, sideW, sideRow, 5, Draw.withAlpha(Theme.ROW, 0.9f * e * a));
            int col = it.active() ? Theme.TEXT : Draw.lerpColor(Theme.TEXT_DIM, Theme.TEXT, 0.6f * e);
            Draw.icon(g, it.icon(), sideX + 13, y + sideRow / 2, 8, Draw.withAlpha(it.active() ? Theme.accent() : col, a));
            Draw.text(g, it.label(), sideX + 23, y + (sideRow - 7.4f) / 2, 0.85f, Draw.withAlpha(col, a));
        }
    }

    // ---------------------------------------------------------------- vue modules

    private float toolbarY() {
        return py + HEADER + 6;
    }

    private void drawModules(GuiGraphics g, int mx, int my, float a) {
        float y = toolbarY();
        // filtres (segmenté) + recherche
        filters.draw(g, cx, y, 14, FILTERS, filter, mx, my, frameDt, a);
        pointer |= filters.hovered(mx, my);
        float fx = cx + filters.width(FILTERS, 0.75f) + 6;
        // onglet HUD : l'action principale de la page (éditer la disposition) vit dans la barre d'outils
        float actionW = 0;
        if (tab == Tab.HUD && query.isEmpty()) {
            actionW = 58;
            float ax = cx + cw - actionW;
            boolean ah = inside(mx, my, ax, y, actionW, 14);
            pointer |= ah;
            float ae = hv("edithud", ah).value;
            Draw.rrect(g, ax, y, actionW, 14, 4, Draw.withAlpha(Draw.lerpColor(Theme.accent(), 0xFFFFFF, 0.12f * ae), a));
            Draw.icon(g, Icons.MOVE, ax + 8, y + 7, 7, Draw.withAlpha(0x0B0A12, a));
            Draw.text(g, "Edit HUD", ax + 14, y + 3.6f, 0.78f, Draw.withAlpha(0x0B0A12, a));
            actionW += 6;
        }
        float sw = Math.max(60, cx + cw - actionW - fx), sx = fx;
        boolean sh = inside(mx, my, sx, y, sw, 14);
        pointer |= sh;
        Draw.rrect(g, sx, y, sw, 14, 4, Draw.withAlpha(Theme.ROW, 0.95f * a));
        if (searchFocus) Draw.ring(g, sx, y, sw, 14, 4, Draw.withAlpha(Theme.accent(), 0.7f * a));
        Draw.icon(g, Icons.SEARCH, sx + 8, y + 7, 6.5f, Draw.withAlpha(searchFocus ? Theme.TEXT : Theme.TEXT_DIM, a));
        boolean caret = searchFocus && (System.currentTimeMillis() / 530) % 2 == 0;
        if (query.isEmpty()) Draw.text(g, "Search", sx + 14, y + 3.6f, 0.75f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        else Draw.text(g, query, sx + 14, y + 3.6f, 0.75f, Draw.withAlpha(Theme.TEXT, a));
        if (caret) Draw.rrect(g, sx + 14.5f + Draw.width(query, 0.75f), y + 3, 0.7f, 8, 0.35f, Draw.withAlpha(0xFFFFFF, a));
        if (!query.isEmpty()) {
            boolean xh = inside(mx, my, sx + sw - 13, y, 13, 14);
            Draw.centered(g, "×", sx + sw - 6.5f, y + 2.8f, 0.95f, Draw.withAlpha(xh ? Theme.TEXT : Theme.TEXT_DIM, a));
        }

        // tiroir : la grille lui laisse la place
        drawer.update(frameDt, selected != null ? 1 : 0);
        float d = Draw.clamp01(drawer.value);
        gridY = y + 20;
        gridH = py + ph - FOOTER - 4 - gridY;
        float gw = cw - (drawerW + 8) * d;
        drawGrid(g, mx, my, cx, gw, a);
        if (d > 0.01f) {
            drawerX = cx + cw - drawerW * d + (drawerW + 8) * 0; // glisse depuis le bord droit
            drawerX = cx + cw - drawerW + drawerW * (1 - d);
            g.enableScissor((int) (cx + gw + 4), (int) gridY - 2, (int) Math.ceil(cx + cw + 1), (int) Math.ceil(gridY + gridH + 2));
            drawDrawer(g, mx, my, drawerX, gridY - 1, drawerW, gridH + 1, a * Math.min(1, d * 1.5f));
            g.disableScissor();
        }
    }

    private static final class Cell {
        final Module m;
        final String title;
        final float x, y, w;

        Cell(Module m, String title, float x, float y, float w) {
            this.m = m;
            this.title = title;
            this.x = x;
            this.y = y;
            this.w = w;
        }
    }

    private final List<Cell> cells = new ArrayList<>();
    private float gridScroll, gridTarget, gridW;
    private int gridCount, gridOn;

    private void drawGrid(GuiGraphics g, int mx, int my, float x, float w, float a) {
        gridW = w;
        List<Module> mods = visible();
        // 3 colonnes, 2 quand le tiroir est ouvert ; les cartes gardent une largeur lisible
        int cols = w > 300 ? 3 : 2;
        float cwid = (w - (cols - 1) * CARD_GAP) / cols;
        cells.clear();
        float cy = gridY;
        String group = null;
        int col = 0;
        for (Module m : mods) {
            if (!java.util.Objects.equals(group, m.group)) {
                if (col != 0) cy += CARD_H + CARD_GAP;
                col = 0;
                group = m.group;
                if (cy > gridY) cy += 3;
                cells.add(new Cell(null, group, x, cy, w));
                cy += 12;
            }
            cells.add(new Cell(m, null, x + col * (cwid + CARD_GAP), cy, cwid));
            col++;
            if (col == cols) {
                col = 0;
                cy += CARD_H + CARD_GAP;
            }
        }
        if (col != 0) cy += CARD_H + CARD_GAP;
        // Performance : la page montre ce qu'elle optimise ; carte de mesure sous les modules
        perfY = -1;
        if (tab == Tab.OPTIMISATION && query.isEmpty() && filter == 0) {
            perfY = cy + 4;
            cy = perfY + 46 + CARD_GAP;
        }
        float maxScroll = Math.max(0, cy - gridY - gridH);
        gridTarget = Math.max(0, Math.min(maxScroll, gridTarget));
        gridScroll += (gridTarget - gridScroll) * (float) (1 - Math.exp(-frameDt * 20));
        if (Math.abs(gridTarget - gridScroll) < 0.3f) gridScroll = gridTarget;
        Set<String> fav = favorites();
        g.enableScissor((int) x - 2, (int) gridY - 2, (int) Math.ceil(x + w + 2), (int) Math.ceil(gridY + gridH));
        if (perfY >= 0) drawPerfCard(g, mx, my, x, perfY - gridScroll, w, a * contentIn());
        int shown = 0;
        for (Cell c : cells) {
            float ry = c.y - gridScroll;
            if (ry + CARD_H < gridY - 2 || ry > gridY + gridH) continue;
            // cascade d'entrée : 8 premières cartes décalées de 18 ms, le reste d'un coup
            float e = Settings.anim() ? Draw.easeOutCubic(((System.nanoTime() - contentShownAt) / 1e9f * Settings.animFactor() - Math.min(shown, 8) * 0.018f) / 0.18f) : 1;
            shown++;
            float ca = a * e;
            ry += (1 - e) * 6 * contentDir;
            if (c.m == null) {
                Draw.text(g, c.title.toUpperCase(Locale.ROOT), c.x + 1, ry + 2, 0.6f, Draw.withAlpha(Theme.TEXT_MUTED, ca));
                continue;
            }
            Module m = c.m;
            boolean h1 = inside(mx, my, c.x, ry, c.w, CARD_H) && my >= gridY && my < gridY + gridH && !presetOpen;
            pointer |= h1;
            float hvv = hv(m, h1).value;
            boolean sel = m == selected;
            // carte : surface bg-2, survol +1 niveau, sélection = surface haute + anneau accent fin
            int bg = sel ? Theme.ROW_HOVER : Draw.lerpColor(Theme.ROW, Theme.ROW_HOVER, 0.6f * hvv);
            Draw.rrect(g, c.x, ry, c.w, CARD_H, 5, Draw.withAlpha(bg, 0.95f * ca));
            if (sel) Draw.ring(g, c.x, ry, c.w, CARD_H, 5, Draw.withAlpha(Theme.accent(), 0.55f * ca));
            Spring kn = knobs.computeIfAbsent(m, mm -> new Spring(mm.enabled ? 1 : 0, 300, 0.55f));
            kn.update(frameDt, m.enabled ? 1 : 0);
            Draw.icon(g, m.icon, c.x + 10, ry + CARD_H / 2, 8, Draw.withAlpha(Draw.lerpColor(Theme.TEXT_DIM, Theme.accent(), Draw.clamp01(kn.value)), ca));
            boolean f = fav.contains(m.id);
            float starW = f || h1 ? 11 : 0;
            float maxName = c.w - 18 - 26 - starW;
            String name = m.name;
            while (Draw.width(name, 0.8f) > maxName && name.length() > 3) name = name.substring(0, name.length() - 2) + "…";
            Draw.text(g, name, c.x + 18, ry + 6.2f, 0.8f, Draw.withAlpha(Theme.TEXT, ca));
            if (f || h1) {
                boolean fh = inside(mx, my, c.x + c.w - 36, ry + 3, 11, 14);
                Draw.icon(g, f ? Icons.STAR_FILL : Icons.STAR, c.x + c.w - 31, ry + CARD_H / 2, 8, Draw.withAlpha(f ? 0xF5C84A : fh ? Theme.TEXT_DIM : Theme.TEXT_MUTED, ca));
            }
            Draw.toggle(g, c.x + c.w - 24, ry + (CARD_H - 10.5f) / 2, kn.value, kn.velocity, ca);
        }
        g.disableScissor();
        if (maxScroll > 0) {
            float bar = Math.max(14, gridH * gridH / (gridH + maxScroll));
            Draw.rrect(g, x + w + 1.5f, gridY + (gridH - bar) * (gridScroll / maxScroll), 1.5f, bar, 0.75f, Draw.withAlpha(0xFFFFFF, 0.18f * a));
        }
        if (mods.isEmpty()) {
            String t = !query.isEmpty() ? "No module matches \"" + query + "\"" : filter == 1 ? "No module enabled in " + tab.label
                    : filter == 2 ? "No favorites yet" : filter == 3 ? "No heavy module in " + tab.label : "No modules";
            String s = !query.isEmpty() ? "Press Escape to clear the search" : filter == 2 ? "Hover a module and click its star" : "Try another filter";
            Draw.centered(g, t, x + w / 2, gridY + 28, 0.9f, Draw.withAlpha(Theme.TEXT, a));
            Draw.centered(g, s, x + w / 2, gridY + 40, 0.72f, Draw.withAlpha(Theme.TEXT_DIM, a));
        }
        gridCount = mods.size();
        gridOn = 0;
        for (Module m : mods) if (m.enabled) gridOn++;
    }

    private float perfY = -1, benchX, benchY, benchW = 96;

    /** Mesure en direct (FPS, 1 % low) et lancement du benchmark : la page Performance montre son effet. */
    private void drawPerfCard(GuiGraphics g, int mx, int my, float x, float y, float w, float a) {
        Draw.rrect(g, x, y, w, 46, 6, Draw.withAlpha(Theme.ROW, 0.95f * a));
        int fps = minecraft.getFps(), low = gg.aurora.render.FrameStats.low1();
        Draw.text(g, "LIVE", x + 10, y + 6, 0.6f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        String f = String.valueOf(fps);
        Draw.text(g, f, x + 10, y + 13, 1.9f, Draw.withAlpha(Theme.TEXT, a));
        float fw = Draw.width(f, 1.9f);
        Draw.text(g, "FPS", x + 13 + fw, y + 23, 0.72f, Draw.withAlpha(Theme.TEXT_DIM, a));
        if (low > 0) {
            String l = "1% low " + low;
            // rouge seulement quand les creux sont vraiment sous le rythme moyen (moins de 30 %)
            int lc = low < fps * 0.3f ? 0xF2545B : Theme.TEXT_DIM;
            Draw.text(g, l, x + 44 + fw, y + 23, 0.72f, Draw.withAlpha(lc, a));
        }
        Draw.text(g, "Boost: " + (Boost.active ? "on" : "off") + "  ·  cosmetics " + new String[]{"high", "balanced", "performance"}[Math.max(0, Math.min(2, Settings.cosmeticQuality))],
                x + 10, y + 36, 0.62f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        benchX = x + w - benchW - 10;
        benchY = y + 15;
        boolean running = gg.aurora.Bench.running();
        boolean bh = !running && inside(mx, my, benchX, benchY, benchW, 16);
        pointer |= bh;
        float be = hv("bench", bh).value;
        Draw.rrect(g, benchX, benchY, benchW, 16, 4, Draw.withAlpha(Draw.lerpColor(Theme.ROW_HOVER, Theme.accent(), 0.25f * be), (running ? 0.5f : 1f) * a));
        Draw.centered(g, running ? "Benchmark running…" : "Run benchmark (~45 s)", benchX + benchW / 2, benchY + 4.4f, 0.75f, Draw.withAlpha(Theme.TEXT, a));
    }

    private void drawDrawer(GuiGraphics g, int mx, int my, float x, float y, float w, float h, float a) {
        Module m = selected;
        if (m == null) return;
        Draw.rrect(g, x, y, w, h, 7, Draw.withAlpha(Theme.TILE, 0.98f * a));
        // en-tête : icône, nom, coût ; à droite étoile, interrupteur, fermer
        Draw.icon(g, m.icon, x + 13, y + 12, 10, Draw.withAlpha(Theme.accent(), a));
        float maxName = w - 24 - 64;
        String name = m.name;
        while (Draw.width(name, 0.95f) > maxName && name.length() > 3) name = name.substring(0, name.length() - 2) + "…";
        Draw.text(g, name, x + 24, y + 5f, 0.95f, Draw.withAlpha(Theme.TEXT, a));
        String meta = m.group + "  ·  ";
        Draw.text(g, meta, x + 24, y + 15f, 0.65f, Draw.withAlpha(Theme.TEXT_DIM, a));
        Draw.text(g, cost(m), x + 24 + Draw.width(meta, 0.65f), y + 15f, 0.65f, Draw.withAlpha(costColor(m), a));
        boolean xh = inside(mx, my, x + w - 17, y + 4, 13, 13);
        pointer |= xh;
        Draw.rrect(g, x + w - 17, y + 4, 13, 13, 4, Draw.withAlpha(Theme.ROW_HOVER, (xh ? 0.95f : 0.5f) * a));
        Draw.centered(g, "×", x + w - 10.5f, y + 6.3f, 0.95f, Draw.withAlpha(Theme.TEXT, a));
        Spring kn = knobs.computeIfAbsent(m, mm -> new Spring(mm.enabled ? 1 : 0, 300, 0.55f));
        Draw.toggle(g, x + w - 43, y + 5.5f, kn.value, kn.velocity, a);
        boolean f = favorites().contains(m.id);
        Draw.icon(g, f ? Icons.STAR_FILL : Icons.STAR, x + w - 53, y + 11, 9, Draw.withAlpha(f ? 0xF5C84A : Theme.TEXT_MUTED, a));
        pointer |= inside(mx, my, x + w - 60, y + 3, 40, 16);
        if (m.conflict != null) Draw.text(g, "Duplicate: " + m.conflict, x + 8, y + 24, 0.64f, Draw.withAlpha(0xF2B84A, a));
        detail.render(g, x + 2, y + 25, w - 4, h - 27, mx, my, a);
    }

    // ---------------------------------------------------------------- vue réglages

    private void openPage(Tab t) {
        if (page != null && pageTab == t) return;
        closePage();
        settingsView = false;
        select(null, false);
        page = switch (t) {
            case GARDE_ROBE -> new WardrobeScreen(this);
            case AMIS -> new FriendsScreen(this);
            default -> new PicturesScreen(this);
        };
        page.embed(this, minecraft, width, height);
        pageTab = t;
        contentShownAt = System.nanoTime();
    }

    private void closePage() {
        if (page == null) return;
        page.leaveEmbedded();
        page = null;
        pageTab = null;
        contentShownAt = System.nanoTime();
    }

    /** Page intégrée : elle occupe la zone de contenu, sans en-tête ni fond propres. */
    private void drawPage(GuiGraphics g, int mx, int my, float a) {
        float y = toolbarY() - 4;
        gridY = y;
        gridH = py + ph - FOOTER - 2 - y;
        float in = contentIn();
        page.layout(cx - 6, y + (1 - in) * 8 * contentDir, cw + 12, gridH);
        page.renderEmbedded(g, mx, my, frameDt, a * in);
        pointer |= page.pointerWanted();
    }

    private void drawSettings(GuiGraphics g, int mx, int my, float a) {
        float y = toolbarY();
        SettingsSections.Section s = sections.get(section);
        float in = contentIn();
        float ca = a * in, lift = (1 - in) * 8 * contentDir;
        Draw.icon(g, s.icon(), cx + 7, y + 7 + lift, 10, Draw.withAlpha(Theme.accent(), ca));
        Draw.text(g, s.name(), cx + 17, y + 2 + lift, 1.05f, Draw.withAlpha(Theme.TEXT, ca));
        String count = s.rows().size() + " settings";
        Draw.text(g, count, cx + cw - 2 - Draw.width(count, 0.68f), y + 4.5f + lift, 0.68f, Draw.withAlpha(Theme.TEXT_MUTED, ca));
        gridY = y + 20;
        gridH = py + ph - FOOTER - 4 - gridY;
        settingsPanel.render(g, cx - 6, gridY, cw + 12, gridH, mx, my, a);
    }

    private void drawFooter(GuiGraphics g, float a) {
        float fy = py + ph - FOOTER + 2;
        String left = page != null ? pageTab.label : settingsView ? "Settings  ·  " + sections.get(section).name() : gridCount + " modules  ·  " + gridOn + " enabled";
        Draw.text(g, left, cx, fy, 0.68f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        String right = "RShift closes";
        Draw.text(g, right, px + pw - 10 - Draw.width(right, 0.68f), fy, 0.68f, Draw.withAlpha(Theme.TEXT_MUTED, a));
    }

    /** Rectangle du panneau (unités GUI), pour le menu dans le monde. */
    public float[] panelRect() {
        float w = Math.min(600, width - 24), h = Math.min(340, height - 24);
        return new float[]{(width - w) / 2 - 2, (height - h) / 2 - 2, w + 4, h + 4};
    }

    /** Le panneau attend du texte (recherche, champ de réglage) : le menu dans le monde ouvre la saisie. */
    public boolean wantsKeyboard() {
        return searchFocus || detail.focused() != null || settingsPanel.focused() != null || (page != null && page.typing());
    }

    public void endTyping() {
        searchFocus = false;
    }

    /** Outils de dev (captures automatiques). */
    public void debugSelect(String id) {
        Module m = Modules.byId(id);
        if (m != null) {
            settingsView = false;
            tab = lastTab = pillar(m);
            select(m, false);
        }
    }

    public void debugTab(int i) {
        Tab t = Tab.values()[i];
        if (t == Tab.GARDE_ROBE || t == Tab.AMIS || t == Tab.PICTURES) {
            openPage(t);
            return;
        }
        closePage();
        settingsView = false;
        tab = lastTab = t;
        select(null, false);
    }

    /** Page intégrée courante (outils de dev). */
    public SubScreen page() {
        return page;
    }

    public void debugSettings(int sectionIndex) {
        if (sections == null) sections = SettingsSections.build(this);
        settingsView = true;
        showSection(sectionIndex);
    }

    public void debugFilter(int i) {
        filter = i;
    }

    // ===================================================================== entrées

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (closing) return true;
        double mx = event.x(), my = event.y();
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubleClick);
        if (presetOpen) {
            float x = presetX - 30, y = presetY + 17, w = presetW + 30;
            for (int i = 0; i < 3; i++) {
                if (inside(mx, my, x + 3, y + 3 + i * 18, w - 6, 17)) {
                    click(1.2f);
                    applyPreset(i);
                }
            }
            presetOpen = false;
            return true;
        }
        if (!inside(mx, my, px, py, pw, ph)) {
            onClose();
            return true;
        }
        if (inside(mx, my, px + pw - 23, py + 7, 15, 14)) {
            click(0.9f);
            onClose();
            return true;
        }
        if (inside(mx, my, presetX, presetY, presetW, 14)) {
            click(1f);
            presetOpen = true;
            return true;
        }
        // barre latérale
        List<SideItem> items = sideItems();
        int was = -1;
        for (int i = 0; i < items.size(); i++) if (items.get(i) != null && items.get(i).active()) was = i;
        for (int i = 0; i < items.size(); i++) {
            SideItem it = items.get(i);
            if (it == null || !inside(mx, my, sideX, sideItemY(i, items), sideW, sideRow)) continue;
            if (!it.active()) {
                click(1.05f);
                contentDir = was < 0 || i > was ? 1 : -1;
                it.action().run();
            }
            return true;
        }
        if (page != null) {
            page.clickEmbedded(mx, my);
            return true;
        }
        if (settingsView) {
            settingsPanel.click(mx, my);
            return true;
        }
        float y = toolbarY();
        int fi = filters.hit(mx, my);
        if (fi >= 0) {
            if (fi != filter) {
                click(1f);
                filter = fi;
                gridTarget = 0;
                contentShownAt = System.nanoTime();
            }
            return true;
        }
        float actionW = tab == Tab.HUD && query.isEmpty() ? 64 : 0;
        if (actionW > 0 && inside(mx, my, cx + cw - 58, y, 58, 14)) {
            click(1.1f);
            minecraft.setScreen(new HudEditScreen(this));
            return true;
        }
        float fx = cx + filters.width(FILTERS, 0.75f) + 6, sw = Math.max(60, cx + cw - actionW - fx);
        if (inside(mx, my, fx, y, sw, 14)) {
            if (!query.isEmpty() && mx >= fx + sw - 13) {
                query = "";
                searchFocus = false;
                contentShownAt = System.nanoTime();
            } else searchFocus = true;
            return true;
        }
        searchFocus = false;
        // tiroir
        float d = Draw.clamp01(drawer.value);
        if (selected != null && d > 0.5f && inside(mx, my, drawerX, gridY - 1, drawerW, gridH + 1)) {
            float x = drawerX, w = drawerW, dy = gridY - 1;
            if (inside(mx, my, x + w - 17, dy + 4, 13, 13)) {
                click(0.9f);
                select(null, false);
                return true;
            }
            if (inside(mx, my, x + w - 45, dy + 3, 26, 16)) {
                selected.toggle();
                click(selected.enabled ? 1.25f : 0.85f);
                Config.save();
                return true;
            }
            if (inside(mx, my, x + w - 60, dy + 3, 14, 16)) {
                boolean on = !favorites().contains(selected.id);
                setFavorite(selected.id, on);
                click(on ? 1.4f : 0.8f);
                return true;
            }
            detail.click(mx, my);
            return true;
        }
        // grille
        if (inside(mx, my, cx, gridY, gridW, gridH)) {
            if (perfY >= 0 && !gg.aurora.Bench.running() && inside(mx, my, benchX, benchY, benchW, 16)) {
                click(1.1f);
                onClose();
                gg.aurora.Bench.start(20);
                return true;
            }
            for (Cell c : cells) {
                if (c.m == null || !inside(mx, my, c.x, c.y - gridScroll, c.w, CARD_H)) continue;
                Module m = c.m;
                if (mx >= c.x + c.w - 27) {
                    m.toggle();
                    click(m.enabled ? 1.25f : 0.85f);
                    Config.save();
                } else if (mx >= c.x + c.w - 38) {
                    boolean on = !favorites().contains(m.id);
                    setFavorite(m.id, on);
                    click(on ? 1.4f : 0.8f);
                } else {
                    select(m == selected ? null : m, true);
                }
                break;
            }
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (page != null) return page.mouseScrolled(mx, my, sx, sy);
        if (settingsView) return settingsPanel.scroll(mx, my, sy) || true;
        if (selected != null && detail.scroll(mx, my, sy)) return true;
        if (inside(mx, my, cx, gridY, gridW, gridH)) {
            gridTarget -= (float) sy * 30;
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (page != null) return page.mouseDragged(event, dx, dy);
        if (settingsPanel.drag(event.x(), event.y()) || detail.drag(event.x(), event.y())) return true;
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (page != null) return page.mouseReleased(event);
        if (settingsPanel.release() | detail.release()) return true;
        return super.mouseReleased(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (page != null) return page.charTyped(event);
        if (settingsView) {
            if (event.isAllowedChatCharacter() && settingsPanel.charTyped(event.codepointAsString())) return true;
            return super.charTyped(event);
        }
        if (detail.charTyped(event.codepointAsString())) return true;
        if (event.isAllowedChatCharacter() && query.length() < 32 && (searchFocus || !Character.isWhitespace(event.codepoint()))) {
            // taper n'importe où lance la recherche
            searchFocus = true;
            query += event.codepointAsString();
            gridTarget = 0;
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (page != null) {
            boolean esc = key == GLFW.GLFW_KEY_ESCAPE;
            if (esc && page.consumesEscape()) {
                page.keyPressed(event);
                return true;
            }
            // RShift ferme toujours le menu ; Échap sans champ actif revient aux modules
            if (!esc && key != GLFW.GLFW_KEY_RIGHT_SHIFT && !(page.typing() && key == GLFW.GLFW_KEY_RIGHT_SHIFT) && page.keyPressed(event)) return true;
        }
        if (settingsView && (settingsPanel.focused() != null || Rows.KeyBind.listening != null) && settingsPanel.keyPressed(key, event.modifiers())) return true;
        if (!settingsView && detail.keyPressed(key, event.modifiers())) return true;
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            // Échap remonte d'un niveau : menu de preset, recherche, tiroir, vue réglages, puis ferme
            if (presetOpen) presetOpen = false;
            else if (!query.isEmpty() || searchFocus) {
                query = "";
                searchFocus = false;
                contentShownAt = System.nanoTime();
            } else if (page != null) closePage();
            else if (selected != null && !settingsView) select(null, false);
            else if (settingsView) {
                settingsView = false;
                SettingsSections.closed();
                contentShownAt = System.nanoTime();
            } else onClose();
            return true;
        }
        if (!settingsView && key == GLFW.GLFW_KEY_BACKSPACE && !query.isEmpty()) {
            boolean ctrl = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
            query = ctrl ? "" : query.substring(0, query.length() - 1);
            gridTarget = 0;
            return true;
        }
        if (key == GLFW.GLFW_KEY_RIGHT_SHIFT) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        if (closing) return;
        Config.save();
        SettingsSections.closed();
        if (!Settings.anim()) {
            gg.aurora.render.Panel3D.clear();
            minecraft.setScreen(back);
            return;
        }
        closing = true;
        closeAt = System.nanoTime();
    }

    @Override
    public void removed() {
        if (page != null) page.leaveEmbedded(); // la page reste pour le retour depuis un sous-écran (Mini skin)
        gg.aurora.render.Panel3D.clear();
        // on remet le HUD en quittant (jeu, sous-pages, éditeur de HUD) ; il repart masqué au retour
        if (savedHideGui != null) {
            minecraft.options.hideGui = savedHideGui;
            savedHideGui = null;
        }
        Config.save();
    }

    private void click(float pitch) {
        if (!Settings.sounds) return;
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch, 0.3f));
    }

    private static boolean inside(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
