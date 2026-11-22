package gg.aurora.ui;

import gg.aurora.HotLoader;
import gg.aurora.ModManager;
import gg.aurora.ModManager.Hit;
import gg.aurora.ModManager.Installed;
import gg.aurora.ModManager.Kind;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mod manager, laid out like a launcher: a rail of pages on the left (Mods, Store, Updates, Pending, Back),
 * a header per page, then a dense list or a grid of cards with the real mod icons.
 * <ul>
 *   <li>Mods: every jar, a switch per mod, update and remove; right click opens a small menu.</li>
 *   <li>Store: Modrinth mods, resource packs and shaders, with categories and « Hide installed ».</li>
 *   <li>Updates: installed version in red, new one in green.</li>
 *   <li>Pending: what waits for the restart, with undo.</li>
 * </ul>
 * Updates and removals ask first in a flat dialog. A strip at the bottom offers the restart when one is needed.
 */
public class ModsScreen extends SubScreen {
    private static final int RED = 0xF2545B, GREEN = 0x5EE6A8, AMBER = 0xF5C84A;
    private static final float RAIL = 58;

    /** Pages by index; {@link #ORDER} is how the rail lists them (Installed comes right after Mods). */
    private static final int MODS = 0, STORE = 1, UPDATES = 2, PENDING = 3, LIBRARY = 4;
    private static final String[] PAGES = {"Mods", "Store", "Updates", "Pending", "Installed"};
    private static final String[] PAGE_ICONS = {Icons.LIST, Icons.BAG, Icons.DOWNLOAD, Icons.CLOCK, Icons.LAYERS};
    private static final int[] ORDER = {MODS, LIBRARY, STORE, UPDATES, PENDING};
    /** Built into the game or the loader: shown only with libraries. */
    private static final java.util.Set<String> BUILTIN = java.util.Set.of("java", "minecraft", "fabricloader", "mixinextras");

    private static int railPos(int page) {
        for (int i = 0; i < ORDER.length; i++) if (ORDER[i] == page) return i;
        return 0;
    }
    private static final String[] SHOW = {"All", "On", "Off"};
    private static final String[] KINDS, KINDS_SHORT = {"Mods", "Packs", "Shaders"};
    /** Store categories per kind: label, then Modrinth slug (null = all). */
    private static final String[][][] CHIPS = {
            {{"All", null}, {"Performance", "optimization"}, {"Gameplay", "game-mechanics"}, {"Worlds", "worldgen"}, {"Utility", "utility"}, {"Libraries", "library"}},
            {{"All", null}, {"Simplistic", "simplistic"}, {"Realistic", "realistic"}, {"Themed", "themed"}, {"Tweaks", "tweaks"}, {"Audio", "audio"}},
            {{"All", null}, {"Vanilla-like", "vanilla-like"}, {"Realistic", "realistic"}, {"Fantasy", "fantasy"}, {"Cartoon", "cartoon"}, {"Potato", "potato"}},
    };

    static {
        Kind[] k = Kind.values();
        KINDS = new String[k.length];
        for (int i = 0; i < k.length; i++) KINDS[i] = k[i].label;
    }

    // remembered while the game runs
    private static String filter = "", query = "";
    private static int tab, kind, show, chip;
    private static boolean hideInstalled, showLibraries;
    /** Store filter: 0 any, 1 starts without a restart, 2 needs a restart. */
    private static int restartFilter;
    private static final String[] RESTART_FILTER = {"Any", "No restart", "Restart"};
    /** The project page open over the store, or null. */
    private Hit detail;

    private final RowPanel panel;
    private final Spring railY = new Spring(-1, 260, 0.85f);
    private final Spring segSlide = new Spring(0, 220, 0.75f);
    private final Spring searchGlow = new Spring(0, 320, 1f);
    private final Spring dialogOpen = new Spring(0, 260, 0.85f);
    private final Spring menuOpen = new Spring(0, 320, 0.9f);
    private final Spring bannerIn = new Spring(0, 220, 0.9f);
    private final java.util.Map<Object, Spring> hovers = new java.util.HashMap<>();
    private final List<Btn> buttons = new ArrayList<>();
    private int hoveredSeg = -1;
    private boolean searchFocus;
    private float sx, sy, sw, sh;
    private String shownKey, shownPage;
    private long pageAt = System.nanoTime();
    private int pageDir;
    private Confirm confirm, leaving;
    private Menu menu;

    public ModsScreen(Screen parent) {
        super(parent, "Mods");
        panel = new RowPanel(() -> dt, new RowPanel.Callbacks() {
            public void pointer() {
                pointer = true;
            }

            public void sound(float pitch) {
                click(pitch);
            }

            public void changed() {}
        });
        segSlide.set(tab == 0 ? show : kind);
        ModManager.scan();
        if (ModManager.results.isEmpty()) ModManager.search(query, Kind.values()[kind], slug());
    }

    @Override
    protected float maxW() {
        return 900;
    }

    @Override
    protected float maxH() {
        return 520;
    }

    @Override
    protected boolean seeThrough() {
        return true;
    }

    @Override
    protected boolean ownChrome() {
        return true;
    }

    /** Player's own values, put back when the screen goes away (never written to options.txt while changed). */
    private Integer savedBlur;
    private Boolean savedHideGui;

    @Override
    protected void init() {
        super.init();
        if (minecraft.level == null) return;
        // frosted glass: the world behind is blurred even when the player turned menu blur off,
        // and the HUD (hotbar, chat) is hidden so nothing pixelated sits under the text
        var blur = minecraft.options.menuBackgroundBlurriness();
        if (savedBlur == null) {
            savedBlur = blur.get();
            if (savedBlur < 4) blur.set(5);
        }
        if (savedHideGui == null) {
            savedHideGui = minecraft.options.hideGui;
            minecraft.options.hideGui = true;
        }
    }

    @Override
    public void removed() {
        if (savedBlur != null) {
            minecraft.options.menuBackgroundBlurriness().set(savedBlur);
            savedBlur = null;
        }
        if (savedHideGui != null) {
            minecraft.options.hideGui = savedHideGui;
            savedHideGui = null;
        }
        super.removed();
    }

    private static String slug() {
        return CHIPS[kind][Math.min(chip, CHIPS[kind].length - 1)][1];
    }

    private Spring hv(Object key, boolean on) {
        Spring s = hovers.computeIfAbsent(key, k -> new Spring(0, 320, 1f));
        s.update(dt, on ? 1 : 0);
        return s;
    }

    // ================================================================ rows

    private void sync() {
        Kind want = Kind.values()[kind];
        if (tab == 1 && !ModManager.searching && (ModManager.resultsKind != want || !java.util.Objects.equals(ModManager.resultsCategory, slug()))) {
            ModManager.search(query, want, slug());
        }
        String page = tab + "|" + (tab == MODS ? show : tab == STORE ? kind + "/" + chip : tab == LIBRARY ? showLibraries : 0);
        Object list = tab == 1 ? ModManager.results : ModManager.installed;
        String key = page + "|" + filter + "|" + hideInstalled + "|" + System.identityHashCode(list) + "|" + ModManager.updateCount() + "|"
                + ModManager.pendingList().size() + "|" + columns() + "|" + restartFilter + (tab == STORE && restartFilter != 0 ? "|" + verdictSig() : "");
        if (key.equals(shownKey)) return;
        shownKey = key;
        panel.gap = tab == STORE ? 8 : 3;
        List<Rows.Row> rows = switch (tab) {
            case MODS -> installedRows();
            case STORE -> storeRows();
            case UPDATES -> updateRows();
            case LIBRARY -> libraryRows();
            default -> pendingRows();
        };
        if (page.equals(shownPage)) panel.replaceRows(rows);
        else panel.setRows(rows);
        shownPage = page;
    }

    private int columns() {
        return ww - RAIL - 24 >= 520 ? 3 : 2;
    }

    private List<Rows.Row> installedRows() {
        List<Rows.Row> rows = new ArrayList<>();
        String f = filter.trim().toLowerCase(Locale.ROOT);
        for (Installed i : ModManager.installed) {
            if (show == 1 && !i.enabled || show == 2 && i.enabled) continue;
            if (!f.isEmpty() && !i.name.toLowerCase(Locale.ROOT).contains(f) && (i.modId == null || !i.modId.contains(f))) continue;
            rows.add(new InstalledRow(i));
        }
        return rows;
    }

    /** Changes when a check ends, so a filtered list picks the newly checked mods up. */
    private static String verdictSig() {
        int hot = 0, restart = 0;
        for (Hit h : ModManager.results) {
            if (h.verdict == ModManager.Verdict.HOT) hot++;
            else if (h.verdict == ModManager.Verdict.RESTART) restart++;
        }
        return hot + "/" + restart;
    }

    private static boolean restartFilterShown() {
        return kind == 0 && ModManager.canCheck();
    }

    private static boolean passes(Hit h) {
        if (!restartFilterShown() || restartFilter == 0) return true;
        return restartFilter == 1 ? h.verdict == ModManager.Verdict.HOT : h.verdict == ModManager.Verdict.RESTART;
    }

    private static int checking() {
        int n = 0;
        for (Hit h : ModManager.results) if (h.verdict == ModManager.Verdict.CHECKING) n++;
        return n;
    }

    private List<Rows.Row> storeRows() {
        List<Rows.Row> rows = new ArrayList<>();
        List<Hit> hits = new ArrayList<>();
        for (Hit h : ModManager.results) if ((!hideInstalled || !h.installed()) && passes(h)) hits.add(h);
        int cols = columns();
        for (int i = 0; i < hits.size(); i += cols) rows.add(new CardRow(hits.subList(i, Math.min(hits.size(), i + cols)), cols));
        return rows;
    }

    private List<Rows.Row> updateRows() {
        List<Rows.Row> rows = new ArrayList<>();
        for (Installed i : ModManager.installed) if (i.update != null && i.pending == null && !i.self()) rows.add(new UpdateRow(i));
        return rows;
    }

    /** Every mod the game runs: top-level mods, plus libraries and built-ins when asked. */
    private static List<net.fabricmc.loader.api.ModContainer> loadedMods() {
        List<net.fabricmc.loader.api.ModContainer> out = new ArrayList<>();
        String f = filter.trim().toLowerCase(Locale.ROOT);
        for (var m : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) {
            var meta = m.getMetadata();
            boolean lib = library(m);
            if (lib && !showLibraries) continue;
            if (!f.isEmpty() && !meta.getName().toLowerCase(Locale.ROOT).contains(f) && !meta.getId().contains(f)) continue;
            out.add(m);
        }
        out.sort(java.util.Comparator.comparing((net.fabricmc.loader.api.ModContainer m) -> !ModConfigs.has(m.getMetadata().getId()))
                .thenComparing(m -> m.getMetadata().getName().toLowerCase(Locale.ROOT)));
        return out;
    }

    private static boolean library(net.fabricmc.loader.api.ModContainer m) {
        return m.getContainingMod().isPresent() || BUILTIN.contains(m.getMetadata().getId())
                || "builtin".equals(m.getMetadata().getType());
    }

    private List<Rows.Row> libraryRows() {
        List<Rows.Row> rows = new ArrayList<>();
        for (var m : loadedMods()) rows.add(new LibraryRow(m));
        return rows;
    }

    private List<Rows.Row> pendingRows() {
        List<Rows.Row> rows = new ArrayList<>();
        for (Installed i : ModManager.pendingList()) rows.add(new PendingRow(i));
        return rows;
    }

    // ================================================================ render

    @Override
    protected void renderPage(GuiGraphics g, int mx, int my, float a) {
        sync();
        buttons.clear();
        if (detail != null && tab != STORE) detail = null;
        boolean modal = confirm != null || menu != null || detail != null;
        int pmx = modal ? -10000 : mx, pmy = modal ? -10000 : my; // a dialog or menu takes the mouse

        drawRail(g, pmx, pmy, a);

        // page change: the new page fades in and slides from the side of the rail item that was picked
        float e = gg.aurora.Settings.anim() ? Draw.easeOutCubic((System.nanoTime() - pageAt) / (0.22e9f / gg.aurora.Settings.animFactor())) : 1;
        float full = a;
        a *= 0.2f + 0.8f * e;
        g.pose().pushMatrix();
        g.pose().translate(0, (1 - e) * 10 * pageDir);

        float cx = wx + RAIL + 12, cw = ww - RAIL - 24, top = wy + 9;
        float listY = drawHeader(g, cx, top, cw, pmx, pmy, a);

        // restart strip, slides up from the bottom when a change waits for it
        boolean restart = ModManager.restartNeeded();
        float b = Draw.clamp01(bannerIn.update(dt, restart ? 1 : 0));
        float bottom = wy + wh - 8 - 26 * b;
        float lx = wx + RAIL + 4, lw = ww - RAIL - 8;
        panel.render(g, lx, listY, lw, bottom - listY, pmx, pmy, a);
        if (panel.rows().isEmpty()) empty(g, lx + lw / 2, listY + (bottom - listY) / 2, a);
        // the whole of Modrinth, page by page: the next one comes when the list nears its end (with a filter on, only
        // once most of what is there has been checked, so it does not run ahead of the checks)
        if (tab == STORE && detail == null && panel.remaining() < 160 && ModManager.more()
                && (restartFilter == 0 || !restartFilterShown() || checking() < 24)) ModManager.loadMore();
        if (tab == STORE && ModManager.loadingMore) {
            Draw.centered(g, "Loading more…", lx + lw / 2, bottom - 9, 0.7f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        }
        g.pose().popMatrix();
        if (b > 0.01f) drawBanner(g, cx, wy + wh - 8 - 22 * b, cw, detail != null ? -10000 : pmx, detail != null ? -10000 : pmy, full * b);
        renderDetail(g, confirm != null ? -10000 : mx, confirm != null ? -10000 : my, full);

        renderMenu(g, mx, my, full);
        renderDialog(g, mx, my, full);
    }

    private void drawRail(GuiGraphics g, int mx, int my, float a) {
        // only a hairline separates the rail from the page: one surface, no boxed strip
        Draw.rect(g, wx + RAIL, wy + 12, 0.5f, wh - 24, Draw.withAlpha(0xFFFFFF, 0.06f * a));

        int updates = ModManager.updateCount(), pending = ModManager.pendingList().size();
        // 5 pages + Add + Back must fit: items shrink on short windows, labels go when there is no room for them
        int items = ORDER.length + 1;
        float step = Math.min(42, (wh - 20) / (items + 1.3f)), itemH = step - 4;
        railLabels = itemH >= 32;
        // one indicator: a soft surface that glides to the selected page
        float ty = wy + 10 + railPos(tab) * step;
        if (railY.value < 0) railY.set(ty);
        railY.update(dt, ty);
        Draw.rrect(g, wx + 7, railY.value, RAIL - 14, itemH, 9, Draw.withAlpha(Theme.ROW_HOVER, 0.9f * a));

        for (int i = 0; i < ORDER.length; i++) {
            int page = ORDER[i];
            float y = wy + 10 + i * step;
            boolean dot = page == UPDATES && updates > 0 || page == PENDING && pending > 0;
            railItem(g, PAGES[page], PAGE_ICONS[page], y, itemH, page == tab, dot, mx, my, a, () -> {
                if (page != tab) turnTo(page);
            });
        }
        railItem(g, "Add", Icons.FOLDER, wy + 10 + ORDER.length * step, itemH, false, false, mx, my, a, this::openModsFolder);
        railItem(g, "Back", Icons.ARROW_LEFT, wy + wh - 10 - itemH, itemH, false, false, mx, my, a, this::leave);
    }

    /**
     * « Add »: the mods folder, opened in the system's file manager. Jars put there show up in Mods and can be loaded
     * from it; jars dropped onto this screen are copied in and started at once when they can be.
     */
    private void openModsFolder() {
        try {
            java.nio.file.Files.createDirectories(ModManager.modsFolder());
            net.minecraft.util.Util.getPlatform().openPath(ModManager.modsFolder());
        } catch (Exception e) {
            gg.aurora.AuroraClient.LOGGER.warn("Could not open the mods folder: {}", e.toString());
        }
        ModManager.toast(Icons.FOLDER, "Mods folder opened", "Or drop jars on this screen: they start without a restart when they can");
        openedFolder++;
    }

    /** Times « Add » opened the folder (a test reads it). */
    public int openedFolder;

    @Override
    public void onFilesDrop(List<java.nio.file.Path> files) {
        List<java.nio.file.Path> jars = new ArrayList<>();
        for (java.nio.file.Path f : files) if (f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) jars.add(f);
        if (jars.isEmpty()) {
            ModManager.toast(Icons.INFO, "Nothing to add", "Only mod jars (.jar) can be dropped here");
            return;
        }
        ModManager.addJars(jars);
        if (tab != MODS) turnTo(MODS);
    }

    /**
     * Hot mods: the Paranoia Dynamic Runtime's own manager (its F8 key). Not on the rail: the Store and the Mods page go
     * through the runtime by themselves. No link to its classes at build time: without it, nothing here changes.
     */
    public static final boolean HOT = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("paranoia_dynamic");

    public void openHot() {
        try {
            Screen s = (Screen) Class.forName("gg.paranoia.dynamic.runtime.ui.DynamicModsScreen").getConstructor(Screen.class).newInstance(this);
            minecraft.setScreen(s);
        } catch (ReflectiveOperationException | LinkageError e) {
            gg.aurora.AuroraClient.LOGGER.warn("Hot mods manager unavailable", e);
        }
    }

    private boolean railLabels = true;

    private void railItem(GuiGraphics g, String label, String icon, float y, float h, boolean active, boolean dot, int mx, int my, float a, Runnable run) {
        boolean hov = !active && inside(mx, my, wx + 7, y, RAIL - 14, h);
        float e = hv("rail" + label, hov).value;
        if (e > 0.01f) Draw.rrect(g, wx + 7, y, RAIL - 14, h, 9, Draw.withAlpha(Theme.ROW, 0.6f * e * a));
        pointer |= hov;
        int col = active ? Theme.TEXT : Draw.lerpColor(Theme.TEXT_MUTED, Theme.TEXT_DIM, 0.4f + 0.6f * e);
        float cx = wx + RAIL / 2;
        if (railLabels) {
            Draw.icon(g, icon, cx, y + h / 2 - 5, 12, Draw.withAlpha(active ? Theme.accent() : col, a));
            Draw.centered(g, label, cx, y + h / 2 + 6.5f, 0.75f, Draw.withAlpha(col, a));
        } else {
            Draw.icon(g, icon, cx, y + h / 2, 12, Draw.withAlpha(active ? Theme.accent() : col, a));
        }
        // something waits there: a small dot, no number to read at this size
        if (dot) Draw.rrect(g, cx + 6, y + 6, 5, 5, 2.5f, Draw.withAlpha(Theme.accent(), a));
        buttons.add(new Btn(wx + 7, y, RAIL - 14, h, 1.05f, run));
    }

    /** Title, subtitle and the page's own controls. Returns where the list starts. */
    private float drawHeader(GuiGraphics g, float x, float y, float w, int mx, int my, float a) {
        int updates = ModManager.updateCount(), pending = ModManager.pendingList().size();
        float right = x + w;
        String title, sub;
        hoveredSeg = -1;
        switch (tab) {
            case 0 -> {
                int on = 0;
                for (Installed i : ModManager.installed) if (i.enabled) on++;
                title = "Mods";
                sub = on + " of " + ModManager.installed.size() + " mods on";
                float segW = segWidth(SHOW);
                hoveredSeg = tabs(g, SHOW, show, right - segW, y + 1, segSlide, mx, my, a);
                sw = Math.min(170, w - segW - 6 - Math.max(Draw.width("Mods", 1.2f), Draw.width(sub, 0.75f)) - 16);
                search(g, right - segW - 6 - sw, y + 1, sw, 19, "Filter mods", mx, my, a);
            }
            case 1 -> {
                title = "Store";
                int total = ModManager.totalHits, busy = restartFilterShown() ? checking() : 0;
                sub = "Modrinth  ·  " + (total > 0 ? String.format(Locale.ROOT, "%,d", total) + " " + KINDS[kind].toLowerCase(Locale.ROOT) + "  ·  " : "")
                        + "Minecraft " + ModManager.gameVersion() + (busy > 0 ? "  ·  checking " + busy : "");
                String[] kinds = w - 120 < segWidth(KINDS) + 40 ? KINDS_SHORT : KINDS;
                float segW = segWidth(kinds);
                hoveredSeg = tabs(g, kinds, kind, right - segW, y + 1, segSlide, mx, my, a);
            }
            case 2 -> {
                title = "Updates on Modrinth";
                sub = updates == 0 ? "Everything is up to date" : updates + (updates == 1 ? " update" : " updates") + "  ·  Minecraft " + ModManager.gameVersion();
                float bx = right;
                if (updates > 0) {
                    float uw = Draw.width("Update all", 0.8f) + 18;
                    bx -= uw;
                    pill(g, "Update all", bx, y + 2, uw, 17, 0.8f, true, false, mx, my, a, this::confirmUpdateAll);
                    bx -= 6;
                }
                float cw = Draw.width("Check for updates", 0.8f) + 18;
                pill(g, ModManager.scanning ? "Checking…" : "Check for updates", bx - cw, y + 2, cw, 17, 0.8f, false, ModManager.scanning, mx, my, a, ModManager::scan);
            }
            case LIBRARY -> {
                List<net.fabricmc.loader.api.ModContainer> all = loadedMods();
                int settings = 0;
                for (var m : all) if (ModConfigs.has(m.getMetadata().getId())) settings++;
                title = "Installed";
                sub = all.size() + (all.size() == 1 ? " mod" : " mods") + "  ·  " + settings + " with settings";
                // « Libraries » switch, then the filter
                float libW = Draw.width("Libraries", 0.8f) + 30;
                float lx = right - libW;
                boolean lh = inside(mx, my, lx, y + 1, libW, 19);
                pointer |= lh;
                Spring k = hovers.computeIfAbsent("libKnob", kk -> new Spring(showLibraries ? 1 : 0, 300, 0.55f));
                k.update(dt, showLibraries ? 1 : 0);
                Draw.text(g, "Libraries", lx, y + 7, 0.8f, Draw.withAlpha(lh ? Theme.TEXT : Theme.TEXT_DIM, a));
                Draw.toggle(g, right - 20, y + 5.3f, k.value, k.velocity, a);
                buttons.add(new Btn(lx, y + 1, libW, 19, showLibraries ? 0.85f : 1.25f, () -> showLibraries = !showLibraries));
                sw = Math.min(170, w - libW - 170);
                search(g, lx - 10 - sw, y + 1, sw, 19, "Filter mods", mx, my, a);
            }
            default -> {
                title = "Pending tasks";
                sub = pending == 0 ? "Nothing waits for a restart" : "Applied when the game restarts. Undo any of them until then.";
                if (pending > 0) {
                    float uw = Draw.width("Undo all", 0.8f) + 18;
                    pill(g, "Undo all", right - uw, y + 2, uw, 17, 0.8f, false, false, mx, my, a, ModManager::undoAll);
                }
            }
        }
        Draw.text(g, title, x, y + 1, 1.2f, Draw.withAlpha(Theme.TEXT, a));
        Draw.text(g, fit(sub, 0.75f, w * 0.55f), x, y + 14.5f, 0.75f, Draw.withAlpha(Theme.TEXT_DIM, a));

        float line = y + 27;
        if (tab == 1) {
            // second row: search, categories, hide installed
            float sy2 = line + 4;
            sw = Math.min(190, w * 0.32f);
            search(g, x, sy2, sw, 17, "Search Modrinth", mx, my, a);
            float chx = x + sw + 8;
            String[][] chips = CHIPS[kind];
            float hideW = Draw.width("Hide installed", 0.75f) + 26;
            // restart filter, left of « Hide installed »: one group of three
            float fw = 0;
            if (restartFilterShown()) {
                for (String l : RESTART_FILTER) fw += Draw.width(l, 0.75f) + 14;
                fw += 4;
                float fx = right - hideW - 10 - fw;
                Draw.rrect(g, fx, sy2, fw, 17, 8.5f, Draw.withAlpha(Theme.ROW, 0.6f * a));
                float ox = fx + 2;
                for (int i = 0; i < RESTART_FILTER.length; i++) {
                    String l = RESTART_FILTER[i];
                    float cw = Draw.width(l, 0.75f) + 14;
                    boolean on = i == restartFilter, hov = !on && inside(mx, my, ox, sy2 + 1, cw, 15);
                    float e = hv("rf" + i, hov).value;
                    pointer |= hov;
                    int col = i == 1 ? GREEN : i == 2 ? AMBER : Theme.TEXT;
                    if (on) Draw.rrect(g, ox, sy2 + 1, cw, 15, 7.5f, Draw.withAlpha(Theme.tileHover(), 0.95f * a));
                    else if (e > 0.01f) Draw.rrect(g, ox, sy2 + 1, cw, 15, 7.5f, Draw.withAlpha(Theme.ROW_HOVER, 0.7f * e * a));
                    Draw.text(g, l, ox + 7, sy2 + 5, 0.75f, Draw.withAlpha(on ? col : Draw.lerpColor(Theme.TEXT_DIM, Theme.TEXT, e), a));
                    int idx = i;
                    buttons.add(new Btn(ox, sy2 + 1, cw, 15, 1.05f, () -> restartFilter = idx));
                    ox += cw;
                }
                fw += 10;
            }
            for (int i = 0; i < chips.length; i++) {
                String l = chips[i][0];
                float cw = Draw.width(l, 0.75f) + 14;
                if (chx + cw > right - hideW - 6 - fw) break;
                boolean on = i == chip, hov = !on && inside(mx, my, chx, sy2 + 1, cw, 15);
                float e = hv("chip" + i, hov).value;
                pointer |= hov;
                Draw.rrect(g, chx, sy2 + 1, cw, 15, 7.5f, Draw.withAlpha(on ? Theme.tileHover() : Draw.lerpColor(Theme.ROW, Theme.ROW_HOVER, e), (on ? 0.95f : 0.6f) * a));
                Draw.text(g, l, chx + 7, sy2 + 5, 0.75f, Draw.withAlpha(on ? Theme.TEXT : Draw.lerpColor(Theme.TEXT_DIM, Theme.TEXT, e), a));
                int idx = i;
                buttons.add(new Btn(chx, sy2 + 1, cw, 15, 1.05f, () -> {
                    if (chip != idx) {
                        chip = idx;
                        ModManager.search(query, Kind.values()[kind], slug());
                    }
                }));
                chx += cw + 4;
            }
            // « Hide installed » switch
            float hx = right - hideW;
            boolean hh = inside(mx, my, hx, sy2, hideW, 17);
            pointer |= hh;
            Spring k = hovers.computeIfAbsent("hideKnob", kk -> new Spring(hideInstalled ? 1 : 0, 300, 0.55f));
            k.update(dt, hideInstalled ? 1 : 0);
            Draw.text(g, "Hide installed", hx, sy2 + 5, 0.75f, Draw.withAlpha(hh ? Theme.TEXT : Theme.TEXT_DIM, a));
            Draw.toggle(g, right - 20, sy2 + 3.5f, k.value, k.velocity, a);
            buttons.add(new Btn(hx, sy2, hideW, 17, hideInstalled ? 0.85f : 1.25f, () -> hideInstalled = !hideInstalled));
            line = sy2 + 22;
        }
        Draw.rect(g, x - 4, line, w + 8, 0.6f, Draw.withAlpha(0xFFFFFF, 0.07f * a));
        return line + 3;
    }

    private void drawBanner(GuiGraphics g, float x, float y, float w, int mx, int my, float a) {
        Draw.rrect(g, x - 4, y, w + 8, 18, 5, Draw.withAlpha(Draw.lerpColor(Theme.TILE, Theme.accent(), 0.18f), 0.92f * a));
        Draw.icon(g, Icons.INFO, x + 6, y + 9, 8, Draw.withAlpha(Theme.accent(), a));
        int n = ModManager.pendingList().size();
        String t = n > 0 ? n + (n == 1 ? " change waits" : " changes wait") + " for a restart" : "Some mods load next time you start the game";
        Draw.text(g, t, x + 15, y + 5.2f, 0.75f, Draw.withAlpha(Theme.TEXT, a));
        float rw = Draw.width("Restart now", 0.75f) + 16;
        pill(g, "Restart now", x + w - rw, y + 2, rw, 14, 0.75f, true, false, mx, my, a, ModManager::restart);
        if (n > 0 && tab != 3) {
            float vw = Draw.width("Review", 0.75f) + 16;
            pill(g, "Review", x + w - rw - 4 - vw, y + 2, vw, 14, 0.75f, false, false, mx, my, a, () -> turnTo(3));
        }
    }

    /** A designed empty page: what is going on, and what to do. */
    private void empty(GuiGraphics g, float cx, float cy, float a) {
        String icon, title, sub;
        boolean busy = tab == LIBRARY ? false : tab == STORE ? ModManager.searching : ModManager.scanning;
        if (busy) {
            icon = Icons.CLOCK;
            title = tab == 1 ? "Searching Modrinth…" : tab == 2 ? "Checking Modrinth…" : "Reading the mods folder…";
            sub = "";
        } else if (ModManager.error != null && tab != PENDING && tab != LIBRARY) {
            icon = Icons.WIFI;
            title = ModManager.error;
            sub = "Check your connection, then try again";
        } else if (tab == LIBRARY) {
            icon = Icons.SEARCH;
            title = "No mod matches";
            sub = showLibraries ? "Try another name" : "Try another name, or show libraries";
        } else if (tab == 0) {
            icon = Icons.SEARCH;
            title = filter.isBlank() && show == 0 ? "No mods yet" : "No mod matches";
            sub = filter.isBlank() && show == 0 ? "Find some in the Store" : "Try another name or show All";
        } else if (tab == 1) {
            icon = Icons.SEARCH;
            if (restartFilterShown() && restartFilter != 0 && !ModManager.results.isEmpty()) {
                int n = checking();
                icon = n > 0 ? Icons.CLOCK : Icons.SEARCH;
                title = n > 0 ? "Checking " + n + " mods…" : restartFilter == 1 ? "None of these start without a restart" : "None of these need a restart";
                sub = n > 0 ? "Each one is read before it shows here" : ModManager.more() ? "More are on the way" : "Try another search";
            } else {
                title = hideInstalled && !ModManager.results.isEmpty() ? "You have all of these" : "Nothing found";
                sub = KINDS[kind] + " for Minecraft " + ModManager.gameVersion();
            }
        } else if (tab == 2) {
            icon = Icons.CHECK;
            title = "Everything is up to date";
            sub = "New versions show up here";
        } else {
            icon = Icons.CHECK;
            title = "Nothing waiting";
            sub = "Turn off, update or remove a running mod and it lands here";
        }
        Draw.rrect(g, cx - 14, cy - 34, 28, 28, 8, Draw.withAlpha(Theme.TILE, 0.9f * a));
        Draw.icon(g, icon, cx, cy - 20, 13, Draw.withAlpha(Theme.TEXT_DIM, a));
        Draw.centered(g, title, cx, cy + 2, 1f, Draw.withAlpha(Theme.TEXT, a));
        if (!sub.isEmpty()) Draw.centered(g, sub, cx, cy + 14, 0.8f, Draw.withAlpha(Theme.TEXT_DIM, a));
    }

    private void search(GuiGraphics g, float x, float y, float w, float h, String placeholder, int mx, int my, float a) {
        sx = x;
        sy = y;
        sw = w;
        sh = h;
        boolean hov = inside(mx, my, x, y, w, h);
        pointer |= hov && !searchFocus;
        searchGlow.update(dt, searchFocus ? 1 : hov ? 0.4f : 0);
        float f = searchGlow.value;
        Draw.glass(g, x, y, w, h, 6, Draw.withAlpha(Draw.lerpColor(Theme.ROW, Theme.ROW_HOVER, f * 0.6f), 0.9f * a),
                Draw.withAlpha(searchFocus ? Theme.accent() : 0xFFFFFF, (searchFocus ? 0.75f : 0.14f + 0.2f * f) * a));
        Draw.icon(g, Icons.SEARCH, x + 9, y + h / 2, 7.5f, Draw.withAlpha(searchFocus ? Theme.TEXT : Theme.TEXT_DIM, a));
        String v = filterTab() ? filter : query;
        String shown = v;
        while (!shown.isEmpty() && Draw.width(shown, 0.8f) > w - 26) shown = shown.substring(1);
        float ty = y + (h - 6.4f) / 2;
        if (v.isEmpty() && !searchFocus) Draw.text(g, fit(placeholder, 0.8f, w - 26), x + 17, ty, 0.8f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        else Draw.text(g, shown, x + 17, ty, 0.8f, Draw.withAlpha(Theme.TEXT, a));
        if (searchFocus && (System.currentTimeMillis() / 530) % 2 == 0) {
            Draw.rrect(g, x + 17.5f + Draw.width(shown, 0.8f), ty - 0.8f, 0.8f, 8, 0.4f, Draw.withAlpha(0xFFFFFF, a));
        }
    }

    /** Pill button, registered for clicks this frame. */
    private void pill(GuiGraphics g, String label, float x, float y, float w, float h, float scale, boolean primary, boolean off,
                      int mx, int my, float a, Runnable run) {
        boolean hov = !off && inside(mx, my, x, y, w, h);
        pointer |= hov;
        float e = hv("pill" + label, hov).value;
        int fill = off ? Theme.ROW : primary ? Draw.lerpColor(Theme.tileHover(), Theme.accent(), 0.45f + 0.3f * e)
                : Draw.lerpColor(Theme.TILE, Theme.ROW_HOVER, 0.4f + 0.6f * e);
        Draw.glass(g, x, y, w, h, 6, Draw.withAlpha(fill, 0.95f * a), Draw.withAlpha(primary && !off ? Theme.accent() : 0xFFFFFF, (off ? 0.12f : 0.5f) * a));
        Draw.centered(g, label, x + w / 2, y + (h - 8 * scale) / 2 + 0.6f, scale, Draw.withAlpha(off ? Theme.TEXT_MUTED : Theme.TEXT, a));
        if (!off) buttons.add(new Btn(x, y, w, h, primary ? 1.15f : 0.95f, run));
    }

    // ================================================================ right-click menu

    private record MenuItem(String label, boolean danger, Runnable run) {}

    private record Menu(float x, float y, List<MenuItem> items) {}

    private void openMenu(Installed i, double mx, double my) {
        if (i.self()) return;
        List<MenuItem> items = new ArrayList<>();
        boolean off = "Turns off at restart".equals(i.pending);
        if (off) items.add(new MenuItem("Keep it on", false, () -> ModManager.undo(i)));
        else if (i.pending == null && !i.working) items.add(new MenuItem(i.enabled ? "Turn off" : "Turn on", false, () -> ModManager.toggle(i)));
        if (i.update != null && i.pending == null && !i.working) items.add(new MenuItem("Update to " + i.update.versionNumber, false, () -> confirmUpdate(i)));
        if (i.enabled && !i.loaded && i.pending == null && !i.working) items.add(new MenuItem("Load now", false, () -> ModManager.loadNow(i)));
        if (ModManager.canUndo(i) && !off) items.add(new MenuItem("Undo pending change", false, () -> ModManager.undo(i)));
        items.add(new MenuItem("Show in folder", false, () -> net.minecraft.util.Util.getPlatform().openPath(i.path.getParent())));
        if (i.pending == null && !i.working) items.add(new MenuItem("Remove", true, () -> confirmRemove(i)));
        click(1.1f);
        searchFocus = false;
        menu = new Menu((float) mx, (float) my, items);
        menuOpen.set(0);
    }

    private float menuX, menuY, menuW;

    private void renderMenu(GuiGraphics g, int mx, int my, float a) {
        if (menu == null) return;
        float o = Draw.clamp01(menuOpen.update(dt, 1));
        menuW = 10;
        for (MenuItem it : menu.items()) menuW = Math.max(menuW, Draw.width(it.label(), 0.8f) + 22);
        float h = menu.items().size() * 15 + 6;
        menuX = Math.min(menu.x(), wx + ww - menuW - 4);
        menuY = Math.min(menu.y(), wy + wh - h - 4);
        float ma = a * o;
        g.pose().pushMatrix();
        g.pose().translate(menuX, menuY);
        g.pose().scale(0.96f + 0.04f * o, 0.96f + 0.04f * o);
        g.pose().translate(-menuX, -menuY);
        Draw.glow(g, menuX, menuY + 3, menuW, h, 10, Draw.withAlpha(0x000000, 0.6f * ma));
        Draw.glass(g, menuX, menuY, menuW, h, 5, Draw.withAlpha(Theme.PANEL, 0.98f * ma), Draw.withAlpha(0xFFFFFF, 0.3f * ma));
        for (int i = 0; i < menu.items().size(); i++) {
            MenuItem it = menu.items().get(i);
            float iy = menuY + 3 + i * 15;
            boolean hov = inside(mx, my, menuX + 3, iy, menuW - 6, 15);
            pointer |= hov;
            if (hov) Draw.rrect(g, menuX + 3, iy, menuW - 6, 15, 4, Draw.withAlpha(it.danger() ? Draw.lerpColor(Theme.ROW_HOVER, RED, 0.35f) : Theme.ROW_HOVER, ma));
            Draw.text(g, it.label(), menuX + 10, iy + 4.4f, 0.8f, Draw.withAlpha(it.danger() ? RED : Theme.TEXT, ma));
        }
        g.pose().popMatrix();
    }

    private boolean clickMenu(double mx, double my) {
        Menu m = menu;
        menu = null;
        for (int i = 0; i < m.items().size(); i++) {
            if (inside(mx, my, menuX + 3, menuY + 3 + i * 15, menuW - 6, 15)) {
                click(1f);
                m.items().get(i).run().run();
                return true;
            }
        }
        return true;
    }

    // ================================================================ dialog

    private record Version(Installed mod, String name, String sub, String version, boolean old) {}

    private record Confirm(String verb, String name, Version from, Version to, List<String> notes, String ok, boolean danger, Runnable run) {}

    private void ask(Confirm c) {
        click(1f);
        confirm = c;
        leaving = null;
        menu = null;
        searchFocus = false;
    }

    private void closeDialog() {
        leaving = confirm;
        confirm = null;
    }

    private void confirmUpdate(Installed i) {
        ModManager.Remote r = i.update;
        if (r == null) return;
        List<String> notes = i.loaded
                ? List.of("A running mod can't be swapped, so the new version loads next time you start the game.", "Until then you can undo it under Pending.")
                : List.of("The new version replaces the file now.");
        ask(new Confirm("Update", i.name, new Version(i, i.name, "Installed", i.version.isEmpty() ? "?" : i.version, true),
                new Version(i, i.name, r.fileName, r.versionNumber, false), notes, "Update", false, () -> ModManager.update(i)));
    }

    private void confirmUpdateAll() {
        List<String> names = new ArrayList<>();
        for (Installed i : ModManager.installed) if (i.update != null && i.pending == null && !i.self()) names.add(i.name);
        if (names.isEmpty()) return;
        String list = String.join(", ", names.subList(0, Math.min(5, names.size()))) + (names.size() > 5 ? " and " + (names.size() - 5) + " more" : "");
        ask(new Confirm("Update", names.size() + (names.size() == 1 ? " mod" : " mods"), null, null,
                List.of(list + ".", "Running mods switch at the next restart. Undo any of them under Pending."), "Update all", false, ModManager::updateAll));
    }

    private void confirmRemove(Installed i) {
        List<String> notes = i.loaded
                ? List.of("It keeps running until you restart, then it's gone.", "A copy stays in mods/.aurora-mods/old.")
                : List.of("It leaves the mods folder now.", "A copy stays in mods/.aurora-mods/old.");
        ask(new Confirm("Remove", i.name, null, null, notes, "Remove", true, () -> ModManager.remove(i)));
    }

    private float dx, dy, dw, dh, okX, okW, cancelX, cancelW, btnY;

    private void renderDialog(GuiGraphics g, int mx, int my, float a) {
        dialogOpen.update(dt, confirm != null ? 1 : 0);
        Confirm c = confirm != null ? confirm : leaving;
        if (c == null) return;
        float o = Draw.clamp01(dialogOpen.value);
        if (confirm == null && o < 0.02f) {
            leaving = null;
            return;
        }
        float da = a * o;
        Draw.rect(g, wx, wy, ww, wh, Draw.withAlpha(Theme.BG, 0.6f * da));

        dw = Math.min(320, ww - 40);
        List<String> lines = new ArrayList<>();
        for (String n : c.notes()) lines.addAll(wrap(n, 0.8f, dw - 24, 4));
        dh = 36 + (c.from() != null ? 68 : 0) + lines.size() * 10.5f + 40;
        dx = wx + (ww - dw) / 2;
        dy = wy + (wh - dh) / 2 + (1 - o) * 10;
        int edge = c.danger() ? RED : Theme.accent();

        // flat panel, a coloured bar along the top edge
        Draw.glow(g, dx, dy + 4, dw, dh, 16, Draw.withAlpha(0x000000, 0.7f * da));
        Draw.rrect(g, dx, dy, dw, dh, 4, Draw.withAlpha(Theme.PANEL, 0.98f * da));
        Draw.ring(g, dx, dy, dw, dh, 4, Draw.withAlpha(0xFFFFFF, 0.12f * da));
        Draw.rect(g, dx + 1, dy, dw - 2, 2, Draw.withAlpha(edge, da));

        float vx = Draw.width(c.verb() + " ", 1f);
        Draw.text(g, c.verb() + " ", dx + 12, dy + 13, 1f, Draw.withAlpha(Theme.TEXT, da));
        Draw.text(g, fit(c.name(), 1f, dw - 24 - vx), dx + 12 + vx, dy + 13, 1f, Draw.withAlpha(edge, da));
        if (c.from() != null && c.from().mod() != null) ModIcons.jar(g, c.from().mod().path, c.name(), dx + dw - 26, dy + 9, 14, da);
        Draw.rect(g, dx + 12, dy + 28, dw - 24, 0.6f, Draw.withAlpha(0xFFFFFF, 0.1f * da));

        float y = dy + 34;
        if (c.from() != null) {
            version(g, c.from(), dx + 12, y, dw - 24, da);
            Draw.icon(g, Icons.ARROW_DOWN, dx + dw / 2, y + 32, 7, Draw.withAlpha(Theme.TEXT_MUTED, da));
            version(g, c.to(), dx + 12, y + 38, dw - 24, da);
            y += 68;
        }
        for (String l : lines) {
            Draw.text(g, l, dx + 12, y + 2, 0.8f, Draw.withAlpha(Theme.TEXT_DIM, da));
            y += 10.5f;
        }

        // action on the left (underlined), Cancel on the right
        int bmx = confirm == null ? -10000 : mx, bmy = confirm == null ? -10000 : my;
        btnY = dy + dh - 27;
        okW = Math.max(80, Draw.width(c.ok(), 0.9f) + 30);
        cancelW = Math.max(80, Draw.width("Cancel", 0.9f) + 30);
        okX = dx + 12;
        cancelX = dx + dw - 12 - cancelW;
        boolean okH = inside(bmx, bmy, okX, btnY, okW, 18), cH = inside(bmx, bmy, cancelX, btnY, cancelW, 18);
        pointer |= okH || cH;
        float oe = hv("dlgOk", okH).value, ce = hv("dlgCancel", cH).value;
        Draw.rrect(g, okX, btnY, okW, 18, 3, Draw.withAlpha(Draw.lerpColor(Theme.TILE, edge, 0.18f + 0.22f * oe), 0.97f * da));
        Draw.ring(g, okX, btnY, okW, 18, 3, Draw.withAlpha(0xFFFFFF, 0.14f * da));
        Draw.rect(g, okX + 1, btnY + 16.5f, okW - 2, 1.5f, Draw.withAlpha(edge, da));
        Draw.centered(g, c.ok(), okX + okW / 2, btnY + 5.2f, 0.9f, Draw.withAlpha(0xFFFFFF, da));
        Draw.rrect(g, cancelX, btnY, cancelW, 18, 3, Draw.withAlpha(Draw.lerpColor(Theme.TILE, Theme.ROW_HOVER, ce), 0.95f * da));
        Draw.ring(g, cancelX, btnY, cancelW, 18, 3, Draw.withAlpha(0xFFFFFF, 0.14f * da));
        Draw.centered(g, "Cancel", cancelX + cancelW / 2, btnY + 5.2f, 0.9f, Draw.withAlpha(Draw.lerpColor(Theme.TEXT_DIM, Theme.TEXT, ce), da));
    }

    /** One side of an update: a stripe in red (installed) or green (new), the icon, the file, the version. */
    private static void version(GuiGraphics g, Version v, float x, float y, float w, float a) {
        int col = v.old() ? RED : GREEN;
        Draw.rect(g, x, y, w, 28, Draw.withAlpha(Theme.TILE, 0.95f * a));
        Draw.rect(g, x, y, 2, 28, Draw.withAlpha(col, 0.9f * a));
        ModIcons.jar(g, v.mod() == null ? null : v.mod().path, v.name(), x + 8, y + 5, 18, a);
        float vw = Draw.width(v.version(), 0.9f);
        float max = w - 40 - vw - 10;
        Draw.text(g, fit(v.name(), 0.9f, max), x + 32, y + 5.5f, 0.9f, Draw.withAlpha(Theme.TEXT, a));
        Draw.text(g, fit(v.sub(), 0.75f, max), x + 32, y + 16.5f, 0.75f, Draw.withAlpha(Theme.TEXT_DIM, a));
        Draw.text(g, v.version(), x + w - 8 - vw, y + 10, 0.9f, Draw.withAlpha(col, a));
    }

    private boolean clickDialog(double mx, double my) {
        Confirm c = confirm;
        if (inside(mx, my, okX, btnY, okW, 18)) {
            click(c.danger() ? 0.9f : 1.15f);
            closeDialog();
            c.run().run();
        } else if (inside(mx, my, cancelX, btnY, cancelW, 18) || !inside(mx, my, dx, dy, dw, dh)) {
            click(0.9f);
            closeDialog();
        }
        return true;
    }

    // ================================================================ input

    /** Pages whose search box filters the local list (the Store searches Modrinth). */
    private static boolean filterTab() {
        return tab == MODS || tab == LIBRARY;
    }

    private void turnTo(int page) {
        pageDir = Integer.signum(railPos(page) - railPos(tab));
        pageAt = System.nanoTime();
        tab = page;
        searchFocus = false;
        menu = null;
        segSlide.set(tab == 0 ? show : kind);
        if (tab == 0 || tab == 2) ModManager.scan();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && confirm == null) {
            menu = null;
            if (tab == 0) {
                for (Rows.Row r : panel.rows()) {
                    if (r instanceof InstalledRow ir && ir.under(event.x(), event.y())) {
                        openMenu(ir.i, event.x(), event.y());
                        return true;
                    }
                }
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    protected boolean clickPage(double mx, double my) {
        if (confirm != null) return clickDialog(mx, my);
        if (menu != null) return clickMenu(mx, my);
        if (detail != null) return clickDetail(mx, my);
        if (hoveredSeg >= 0) {
            if (tab == 0 && hoveredSeg != show) {
                click(1.05f);
                show = hoveredSeg;
            } else if (tab == 1 && hoveredSeg != kind) {
                click(1.05f);
                kind = hoveredSeg;
                chip = 0;
                ModManager.search(query, Kind.values()[kind], slug());
            }
            return true;
        }
        for (Btn b : buttons) {
            if (inside(mx, my, b.x, b.y, b.w, b.h)) {
                click(b.pitch);
                b.run.run();
                return true;
            }
        }
        if ((tab == MODS || tab == STORE || tab == LIBRARY) && inside(mx, my, sx, sy, sw, sh)) {
            searchFocus = true;
            return true;
        }
        searchFocus = false;
        return panel.click(mx, my);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return panel.drag(event.x(), event.y()) || super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        panel.release();
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        menu = null;
        if (confirm == null && detail != null) detailScroll -= (float) sy * 28;
        else if (confirm == null) panel.scroll(mx, my, sy);
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (searchFocus && confirm == null && event.isAllowedChatCharacter()) {
            type(event.codepointAsString());
            return true;
        }
        return super.charTyped(event);
    }

    private void type(String s) {
        if (filterTab()) {
            if (filter.length() + s.length() <= 40) filter += s;
        } else if (query.length() + s.length() <= 60) {
            query += s;
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        boolean enter = key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER;
        if (confirm != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                click(0.9f);
                closeDialog();
            } else if (enter) {
                Confirm c = confirm;
                click(1.15f);
                closeDialog();
                c.run().run();
            }
            return true;
        }
        if (menu != null && key == GLFW.GLFW_KEY_ESCAPE) {
            menu = null;
            return true;
        }
        if (detail != null && key == GLFW.GLFW_KEY_ESCAPE && !searchFocus) {
            click(0.9f);
            detail = null;
            return true;
        }
        if (searchFocus) {
            boolean ctrl = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
            if (key == GLFW.GLFW_KEY_ESCAPE) searchFocus = false;
            else if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (filterTab()) filter = ctrl || filter.isEmpty() ? "" : filter.substring(0, filter.length() - 1);
                else query = ctrl || query.isEmpty() ? "" : query.substring(0, query.length() - 1);
            } else if (key == GLFW.GLFW_KEY_V && ctrl) {
                String clip = minecraft.keyboardHandler.getClipboard();
                if (clip != null) type(clip.replaceAll("[\\r\\n\\t]", " "));
            } else if (enter) {
                if (tab == 1) ModManager.search(query, Kind.values()[kind], slug());
                searchFocus = false;
            }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean typing() {
        return searchFocus;
    }

    @Override
    public boolean consumesEscape() {
        return confirm != null || menu != null || searchFocus || detail != null;
    }

    // ================================================================ dev captures (ModShots)

    public void debugPage(int page, int seg) {
        turnTo(page);
        if (page == 0) show = seg;
        if (page == 1) kind = seg;
        segSlide.set(seg);
        if (page == 1) ModManager.search(query, Kind.values()[kind], slug());
    }

    /** Store filter (0 any, 1 no restart, 2 restart), for the store test. */
    public void debugRestartFilter(int f) {
        restartFilter = f;
    }

    /** Opens a store mod's page, for the store test. */
    public void debugDetail(Hit h) {
        openDetail(h);
    }

    public Hit debugShownDetail() {
        return detail;
    }

    public void debugCloseDetail() {
        detail = null;
    }

    /** Scrolls the store list to its end (as a player would), for the store test. */
    public void debugScrollEnd() {
        panel.scroll(wx + ww / 2, wy + wh / 2, -10000);
    }

    public void debugScrollTop() {
        panel.scroll(wx + ww / 2, wy + wh / 2, 10000);
    }

    public void debugScrollDetail(float by) {
        detailScroll += by;
    }

    /** Store cards shown now (after the filters). */
    public List<Hit> debugShownHits() {
        List<Hit> out = new ArrayList<>();
        for (Rows.Row r : panel.rows()) if (r instanceof CardRow c) out.addAll(c.hits);
        return out;
    }

    /** Opens the update dialog on the first mod that has one, else the remove dialog. */
    public void debugDialog() {
        Installed first = null;
        for (Installed i : ModManager.installed) {
            if (i.self()) continue;
            if (i.update != null) {
                confirmUpdate(i);
                return;
            }
            if (first == null) first = i;
        }
        if (first != null) confirmRemove(first);
    }

    /** Right-click menu on the first mod of the list. */
    public void debugMenu() {
        for (Rows.Row r : panel.rows()) {
            if (r instanceof InstalledRow ir && !ir.i.self()) {
                openMenu(ir.i, ir.lastX + ir.lastW * 0.45f, ir.lastY + 12);
                return;
            }
        }
    }

    // ================================================================ helpers

    private record Btn(float x, float y, float w, float h, float pitch, Runnable run) {}

    private static float segWidth(String[] labels) {
        float w = 2;
        for (String s : labels) w += Draw.width(s, 0.95f) + 18 + 2;
        return w;
    }

    static String fit(String s, float scale, float max) {
        if (max <= 0) return "";
        if (Draw.width(s, scale) <= max) return s;
        while (s.length() > 1 && Draw.width(s + "…", scale) > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    static List<String> wrap(String s, float scale, float max, int maxLines) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (Draw.width(next, scale) <= max || line.isEmpty()) {
                line.setLength(0);
                line.append(next);
            } else {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        if (out.size() > maxLines) {
            List<String> cut = new ArrayList<>(out.subList(0, maxLines));
            cut.set(maxLines - 1, fit(cut.get(maxLines - 1) + " " + out.get(maxLines), scale, max));
            return cut;
        }
        for (int i = 0; i < out.size(); i++) out.set(i, fit(out.get(i), scale, max));
        return out;
    }

    /** Two lines of different widths: as many words as fit on the first, the rest cut with « … » on the second. */
    static String[] twoLines(String s, float scale, float w1, float w2) {
        String[] words = s.trim().split("\\s+");
        StringBuilder first = new StringBuilder();
        int i = 0;
        for (; i < words.length; i++) {
            String next = first.isEmpty() ? words[i] : first + " " + words[i];
            if (Draw.width(next, scale) > w1 && !first.isEmpty()) break;
            first.setLength(0);
            first.append(next);
        }
        String one = fit(first.toString(), scale, w1);
        if (i >= words.length) return new String[]{one};
        return new String[]{one, fit(String.join(" ", java.util.Arrays.copyOfRange(words, i, words.length)), scale, w2)};
    }

    private static String downloads(long n) {
        if (n >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", n / 1e6);
        if (n >= 1_000) return String.format(Locale.ROOT, "%.0fk", n / 1e3);
        return Long.toString(n);
    }

    /** Small button drawn inside a row. */
    private static void rowPill(Rows.Host h, GuiGraphics g, String l, float x, float y, float w, float bh, boolean primary, boolean on, boolean rowHover,
                                int mx, int my, float a) {
        boolean hov = on && rowHover && Rows.inside(mx, my, x, y, w, bh);
        if (hov) h.pointer();
        int fill = !on ? Theme.ROW : primary ? Draw.lerpColor(Theme.tileHover(), Theme.accent(), hov ? 0.75f : 0.45f)
                : Draw.lerpColor(Theme.TILE, Theme.ROW_HOVER, hov ? 1f : 0.4f);
        Draw.glass(g, x, y, w, bh, 6, Draw.withAlpha(fill, 0.95f * a), Draw.withAlpha(primary && on ? Theme.accent() : 0xFFFFFF, (on ? 0.5f : 0.14f) * a));
        Draw.centered(g, l, x + w / 2, y + (bh - 6.2f) / 2, 0.75f, Draw.withAlpha(on ? Theme.TEXT : Theme.TEXT_MUTED, a));
    }

    /** List row: hover lifts the surface, a hairline separates rows. */
    private static void listBase(Rows.Row r, GuiGraphics g, float x, float y, float w, float a) {
        float e = r.hover.value;
        if (e > 0.01f) Draw.rrect(g, x, y, w, r.height(), 8, Draw.withAlpha(Theme.ROW_HOVER, 0.75f * e * a));
    }

    // ================================================================ badges

    /**
     * Whether a store mod starts without a restart, as a small pill ending at {@code right}. Returns its width (0 when
     * there is nothing to say).
     */
    private static float badge(GuiGraphics g, Hit h, float right, float y, float a, boolean compact) {
        if (h.installed()) return 0; // its button already says « Installed »
        String l;
        int col;
        String icon = null;
        switch (h.verdict) {
            case HOT -> {
                l = "No restart";
                col = GREEN;
                icon = Icons.BOLT;
            }
            case RESTART -> {
                l = "Restart";
                col = AMBER;
                icon = Icons.CLOCK;
            }
            case CHECKING -> {
                l = "Checking…";
                col = Theme.TEXT_MUTED;
            }
            case NONE -> {
                l = "No version";
                col = RED;
            }
            default -> {
                if (!ModManager.canCheck()) return 0;
                l = compact ? "?" : "Not checked";
                col = Theme.TEXT_MUTED;
            }
        }
        float sc = 0.68f;
        float w = Draw.width(l, sc) + 10 + (icon != null ? 9 : 0);
        float x = right - w;
        Draw.rrect(g, x, y, w, 12, 6, Draw.withAlpha(Draw.lerpColor(Theme.ROW, col, 0.16f), 0.95f * a));
        float tx = x + 5;
        if (icon != null) {
            Draw.icon(g, icon, tx + 3, y + 6, 6.5f, Draw.withAlpha(col, a));
            tx += 9;
        }
        Draw.text(g, l, tx, y + 3.6f, sc, Draw.withAlpha(col, a));
        return w;
    }

    /** A download under way: the bar fills, the percentage in the middle. */
    private static void progressPill(GuiGraphics g, float p, float x, float y, float w, float h, float a) {
        Draw.rrect(g, x, y, w, h, 6, Draw.withAlpha(Theme.ROW, 0.95f * a));
        float f = Draw.clamp01(p);
        if (f > 0.01f) Draw.rrect(g, x, y, Math.max(h, w * f), h, 6, Draw.withAlpha(Draw.lerpColor(Theme.tileHover(), Theme.accent(), 0.7f), 0.95f * a));
        Draw.centered(g, Math.round(f * 100) + "%", x + w / 2, y + (h - 6.2f) / 2, 0.75f, Draw.withAlpha(Theme.TEXT, a));
    }

    // ================================================================ project page

    private final Spring detailOpen = new Spring(0, 260, 0.85f);
    private final Spring detailSpring = new Spring(0, 260, 1f);
    private float detailScroll, detailMax;
    private int galleryAt;
    private final List<Btn> detailButtons = new ArrayList<>();
    private List<String> bodyLines = List.of();
    private String bodyKey;
    private float dpx, dpy, dpw, dph;

    private void openDetail(Hit h) {
        detail = h;
        detailScroll = 0;
        detailSpring.set(0);
        detailOpen.set(0);
        galleryAt = 0;
        bodyKey = null;
        searchFocus = false;
        ModManager.details(h);
        if (h.verdict == ModManager.Verdict.UNKNOWN) ModManager.recheck(h);
    }

    private void renderDetail(GuiGraphics g, int mx, int my, float a) {
        detailButtons.clear();
        Hit h = detail;
        if (h == null) return;
        ModManager.Details d = ModManager.details(h);
        float o = Draw.clamp01(detailOpen.update(dt, 1));
        float da = a * o;
        dpx = wx + RAIL + 6;
        dpy = wy + 6 + (1 - o) * 8;
        dpw = ww - RAIL - 12;
        dph = wh - 12;
        Draw.glow(g, dpx, dpy + 3, dpw, dph, 14, Draw.withAlpha(0x000000, 0.55f * da));
        Draw.rrect(g, dpx, dpy, dpw, dph, 8, Draw.withAlpha(Theme.PANEL, da));
        Draw.rrect(g, dpx, dpy, dpw, dph, 8, Draw.withAlpha(Theme.PANEL, da));
        Draw.ring(g, dpx, dpy, dpw, dph, 8, Draw.withAlpha(0xFFFFFF, 0.1f * da));

        // header: back, icon, name, numbers, what it takes to run it, install
        float x = dpx + 12, right = dpx + dpw - 12;
        float backW = Draw.width("Back", 0.8f) + 26;
        detailPill(g, "Back", Icons.ARROW_LEFT, x, dpy + 8, backW, 16, false, mx, my, da, () -> detail = null);
        float pageW = Draw.width("Modrinth page", 0.8f) + 18;
        String slug = h.slug != null ? h.slug : h.projectId;
        String kindPath = h.kind == Kind.MOD ? "mod" : h.kind == Kind.SHADER ? "shader" : "resourcepack";
        detailPill(g, "Modrinth page", null, right - pageW, dpy + 8, pageW, 16, false, mx, my, da,
                () -> net.minecraft.util.Util.getPlatform().openUri("https://modrinth.com/" + kindPath + "/" + slug));

        float hy = dpy + 32;
        ModIcons.remote(g, h.iconUrl, h.title, x, hy, 40, da);
        float tx = x + 50;
        boolean have = h.installed();
        String state = h.state != null ? h.state : have ? "Installed" : null;
        float iw = Math.max(86, Draw.width(state == null ? "Install" : state, 0.85f) + 24);
        float ix = right - iw;
        if (h.progress >= 0 && h.state != null && h.state.startsWith("Downloading")) {
            progressPill(g, h.progress, ix, hy + 4, iw, 20, da);
            Draw.centered(g, fit(h.state, 0.68f, iw + 40), ix + iw / 2, hy + 28, 0.68f, Draw.withAlpha(Theme.TEXT_MUTED, da));
        } else {
            String label = state != null ? state : h.kind == Kind.MOD ? "Install" : "Download";
            detailPill(g, label, state == null ? Icons.DOWNLOAD : null, ix, hy + 4, iw, 20, state == null, mx, my, da, state == null ? () -> ModManager.install(h) : null);
            if (state == null && h.latest != null) {
                Draw.centered(g, fit(h.latest.versionNumber, 0.68f, iw + 20), ix + iw / 2, hy + 28, 0.68f, Draw.withAlpha(Theme.TEXT_MUTED, da));
            }
        }
        float titleW = ix - 10 - tx;
        Draw.text(g, fit(h.title, 1.25f, titleW), tx, hy + 2, 1.25f, Draw.withAlpha(have ? Theme.aurora() : Theme.TEXT, da));
        String meta = "by " + h.author + "  ·  " + downloads(h.downloads) + " downloads" + (h.follows > 0 ? "  ·  " + downloads(h.follows) + " followers" : "")
                + (d.license != null && !d.license.isEmpty() ? "  ·  " + d.license : "");
        Draw.text(g, fit(meta, 0.75f, titleW), tx, hy + 17, 0.75f, Draw.withAlpha(Theme.TEXT_DIM, da));
        if (h.kind == Kind.MOD) badge(g, h, tx + Math.max(40, Draw.width(verdictLabel(h), 0.68f) + 20), hy + 28, da, false);

        // scrolled part: verdict note, pictures, description, versions
        float top = hy + 50, bottom = dpy + dph - 8, w = dpw - 24;
        Draw.rect(g, x, top - 4, w, 0.6f, Draw.withAlpha(0xFFFFFF, 0.07f * da));
        detailScroll = Math.max(0, Math.min(detailMax, detailScroll));
        detailSpring.update(dt, detailScroll);
        float sy0 = top + 4 - detailSpring.value;
        float y = sy0;
        g.enableScissor((int) Math.floor(dpx + 2), (int) Math.floor(top), (int) Math.ceil(dpx + dpw - 2), (int) Math.ceil(bottom));
        int smx = my >= top && my <= bottom ? mx : -10000, smy = my >= top && my <= bottom ? my : -10000;

        if (h.kind == Kind.MOD && !have && h.verdictWhy != null && !h.verdictWhy.isBlank()
                && (h.verdict == ModManager.Verdict.RESTART || h.verdict == ModManager.Verdict.HOT || h.verdict == ModManager.Verdict.NONE)) {
            int col = h.verdict == ModManager.Verdict.HOT ? GREEN : h.verdict == ModManager.Verdict.RESTART ? AMBER : RED;
            List<String> why = new ArrayList<>();
            for (String part : h.verdictWhy.split("; ")) why.addAll(wrap(part, 0.75f, w - 20, 3));
            if (why.size() > 6) why = new ArrayList<>(why.subList(0, 6));
            float bh = 8 + why.size() * 9.5f + 4;
            Draw.rrect(g, x, y, w, bh, 6, Draw.withAlpha(Draw.lerpColor(Theme.ROW, col, 0.1f), 0.9f * da));
            Draw.rect(g, x, y + 3, 2, bh - 6, Draw.withAlpha(col, 0.9f * da));
            for (int i = 0; i < why.size(); i++) Draw.text(g, why.get(i), x + 10, y + 6 + i * 9.5f, 0.75f, Draw.withAlpha(Theme.TEXT_DIM, da));
            y += bh + 10;
        }

        List<String> gallery = d.gallery;
        if (!gallery.isEmpty()) {
            float ph = Math.min(120, (w - 16) / 3 * 9 / 16 * 1.0f + 10), pw = ph * 16 / 9;
            int fit = Math.max(1, (int) ((w + 8) / (pw + 8)));
            galleryAt = Math.max(0, Math.min(galleryAt, Math.max(0, gallery.size() - fit)));
            for (int i = 0; i < fit && galleryAt + i < gallery.size(); i++) {
                ModIcons.picture(g, gallery.get(galleryAt + i), x + i * (pw + 8), y, pw, ph, da);
            }
            if (gallery.size() > fit) {
                float aw = 18;
                if (galleryAt > 0) detailPill(g, "‹", null, x - 2, y + ph / 2 - 9, aw, 18, false, smx, smy, da, () -> galleryAt = Math.max(0, galleryAt - fit));
                if (galleryAt + fit < gallery.size()) detailPill(g, "›", null, x + w - aw + 2, y + ph / 2 - 9, aw, 18, false, smx, smy, da, () -> galleryAt += fit);
                Draw.text(g, (galleryAt + 1) + "–" + Math.min(gallery.size(), galleryAt + fit) + " of " + gallery.size(), x, y + ph + 4, 0.68f,
                        Draw.withAlpha(Theme.TEXT_MUTED, da));
                y += 10;
            }
            y += ph + 12;
        }

        Draw.text(g, "About", x, y, 0.95f, Draw.withAlpha(Theme.TEXT, da));
        y += 14;
        String body = d.body.isEmpty() ? (d.loaded ? h.description : "Loading the description…") : d.body;
        String key = System.identityHashCode(body) + "|" + (int) w;
        if (!key.equals(bodyKey)) {
            List<String> lines = new ArrayList<>();
            for (String para : body.split("\n")) {
                if (para.isBlank()) lines.add("");
                else lines.addAll(wrap(para.trim(), 0.8f, w, 400));
            }
            bodyLines = lines;
            bodyKey = key;
        }
        for (String l : bodyLines) {
            if (y > top - 12 && y < bottom + 2) Draw.text(g, l, x, y, 0.8f, Draw.withAlpha(Theme.TEXT_DIM, da));
            y += l.isEmpty() ? 5 : 10;
        }
        y += 12;

        Draw.text(g, "Versions", x, y, 0.95f, Draw.withAlpha(Theme.TEXT, da));
        Draw.text(g, "Fabric  ·  this game is " + ModManager.gameVersion(), x + Draw.width("Versions", 0.95f) + 8, y + 1.5f, 0.7f, Draw.withAlpha(Theme.TEXT_MUTED, da));
        y += 15;
        if (!d.loaded) {
            Draw.text(g, "Loading versions…", x, y, 0.8f, Draw.withAlpha(Theme.TEXT_MUTED, da));
            y += 14;
        } else if (d.failed) {
            Draw.text(g, "Could not reach Modrinth", x, y, 0.8f, Draw.withAlpha(RED, da));
            y += 14;
        }
        for (ModManager.Version v : d.versions) {
            float rh = 24;
            if (y > top - rh && y < bottom) {
                boolean hov = Rows.inside(smx, smy, x, y, w, rh);
                if (hov) Draw.rrect(g, x - 4, y, w + 8, rh, 6, Draw.withAlpha(Theme.ROW_HOVER, 0.7f * da));
                int tcol = "release".equals(v.type) ? GREEN : "beta".equals(v.type) ? AMBER : RED;
                Draw.text(g, fit(v.name, 0.82f, w * 0.55f), x + 2, y + 4, 0.82f, Draw.withAlpha(v.fits ? Theme.TEXT : Theme.TEXT_MUTED, da));
                String games = v.gameVersions.isEmpty() ? "" : String.join(", ", v.gameVersions.subList(Math.max(0, v.gameVersions.size() - 3), v.gameVersions.size()))
                        + (v.gameVersions.size() > 3 ? " +" + (v.gameVersions.size() - 3) : "");
                float vx = x + 2;
                Draw.text(g, v.type, vx, y + 14.5f, 0.68f, Draw.withAlpha(tcol, da));
                vx += Draw.width(v.type, 0.68f) + 6;
                Draw.text(g, fit(v.number + "  ·  " + v.date + "  ·  " + games, 0.68f, w * 0.7f), vx, y + 14.5f, 0.68f, Draw.withAlpha(Theme.TEXT_MUTED, da));
                float bw2 = 64;
                if (!v.fits) {
                    Draw.text(g, "Not for " + ModManager.gameVersion(), right - Draw.width("Not for " + ModManager.gameVersion(), 0.7f), y + 9, 0.7f,
                            Draw.withAlpha(Theme.TEXT_MUTED, da));
                } else if (h.state == null && !have && h.kind == Kind.MOD) {
                    detailPill(g, "Install", null, right - bw2, y + 4, bw2, 16, false, smx, smy, da, () -> ModManager.installVersion(h, v.remote));
                }
            }
            y += rh + 2;
        }
        g.disableScissor();
        detailMax = Math.max(0, y + detailSpring.value - sy0 - (bottom - top) + 12);
        if (detailMax > 0) {
            float barH = Math.max(18, (bottom - top) * (bottom - top) / (bottom - top + detailMax));
            Draw.rrect(g, dpx + dpw - 5, top + (bottom - top - barH) * (detailSpring.value / detailMax), 2, barH, 1, Draw.withAlpha(Theme.accent(), 0.5f * da));
        }
    }

    private static String verdictLabel(Hit h) {
        return switch (h.verdict) {
            case HOT -> "No restart";
            case RESTART -> "Restart";
            case CHECKING -> "Checking…";
            case NONE -> "No version";
            default -> "Not checked";
        };
    }

    /** A button on the project page; {@code run} null draws it inert. */
    private void detailPill(GuiGraphics g, String label, String icon, float x, float y, float w, float h, boolean primary, int mx, int my, float a, Runnable run) {
        boolean on = run != null;
        boolean hov = on && inside(mx, my, x, y, w, h);
        pointer |= hov;
        float e = hv("dp" + label + (int) y, hov).value;
        int fill = !on ? Theme.ROW : primary ? Draw.lerpColor(Theme.tileHover(), Theme.accent(), 0.5f + 0.3f * e) : Draw.lerpColor(Theme.TILE, Theme.ROW_HOVER, 0.4f + 0.6f * e);
        Draw.glass(g, x, y, w, h, 6, Draw.withAlpha(fill, 0.95f * a), Draw.withAlpha(primary && on ? Theme.accent() : 0xFFFFFF, (on ? 0.4f : 0.12f) * a));
        float sc = h >= 20 ? 0.85f : 0.8f;
        float lw = Draw.width(label, sc) + (icon != null ? 12 : 0);
        float lx = x + (w - lw) / 2;
        if (icon != null) {
            Draw.icon(g, icon, lx + 4, y + h / 2, 7.5f, Draw.withAlpha(on ? Theme.TEXT : Theme.TEXT_MUTED, a));
            lx += 12;
        }
        Draw.text(g, label, lx, y + (h - 8 * sc) / 2 + 0.6f, sc, Draw.withAlpha(on ? Theme.TEXT : Theme.TEXT_MUTED, a));
        if (on) detailButtons.add(new Btn(x, y, w, h, primary ? 1.15f : 1f, run));
    }

    private boolean clickDetail(double mx, double my) {
        for (Btn b : detailButtons) {
            if (inside(mx, my, b.x, b.y, b.w, b.h)) {
                click(b.pitch);
                b.run.run();
                return true;
            }
        }
        if (!inside(mx, my, dpx, dpy, dpw, dph) && !inside(mx, my, wx, wy, RAIL, wh)) {
            click(0.9f);
            detail = null;
            return true;
        }
        if (inside(mx, my, wx, wy, RAIL, wh)) {
            detail = null;
            return clickPage(mx, my);
        }
        return true;
    }

    // ================================================================ row types

    /** Installed mod: icon, name and state, then update, remove and its switch. */
    final class InstalledRow extends Rows.Row {
        final Installed i;
        private final Spring knob;
        private float tx, ux, uw, rx, gx;
        float lastX, lastY, lastW;
        private boolean hasUpdate, hasSettings;

        InstalledRow(Installed i) {
            super(i.name, "");
            this.i = i;
            knob = new Spring(on() ? 1 : 0, 300, 0.55f);
        }

        boolean under(double mx, double my) {
            return Rows.inside(mx, my, lastX, lastY, lastW, height()) && my >= wy && my < wy + wh;
        }

        private boolean on() {
            return i.enabled && !"Turns off at restart".equals(i.pending);
        }

        private boolean busy() {
            return i.working || i.pending != null && !"Turns off at restart".equals(i.pending);
        }

        @Override
        public float height() {
            return 26;
        }

        @Override
        public void draw(Rows.Host h, GuiGraphics g, float x, float y, float w, int mx, int my, float a) {
            lastX = x;
            lastY = y;
            lastW = w;
            boolean hov = Rows.inside(mx, my, x, y, w, height()) && h.inView(my);
            hover.update(h.dt(), hov ? 1 : 0);
            listBase(this, g, x, y, w, a);
            float fa = i.enabled ? a : a * 0.5f;
            ModIcons.jar(g, i.path, i.name, x + 6, y + 4, 18, fa);

            float bx = x + w - 8;
            if (i.self()) {
                String s = "Updated by Paranoia";
                bx -= Draw.width(s, 0.75f);
                Draw.text(g, s, bx, y + 10, 0.75f, Draw.withAlpha(Theme.TEXT_MUTED, a));
            } else {
                bx -= 20;
                tx = bx;
                knob.update(h.dt(), on() ? 1 : 0);
                if (hov && !busy() && Rows.inside(mx, my, tx - 3, y + 3, 26, 20)) h.pointer();
                Draw.toggle(g, tx, y + (height() - 10.5f) / 2, knob.value, knob.velocity, busy() ? a * 0.45f : a);
                bx -= 6 + 15;
                rx = bx;
                boolean rh = hov && !busy() && Rows.inside(mx, my, rx, y + 5.5f, 15, 15);
                if (rh) h.pointer();
                // remove: only shows on hover, the row stays calm otherwise
                float hvv = hover.value;
                if (rh) Draw.rrect(g, rx, y + 5.5f, 15, 15, 5, Draw.withAlpha(Draw.lerpColor(Theme.TILE, RED, 0.4f), 0.95f * a));
                Draw.icon(g, Icons.TRASH, rx + 7.5f, y + 13, 7.5f, Draw.withAlpha(rh ? 0xFFFFFF : Theme.TEXT_DIM, (busy() ? 0.4f : 1f) * hvv * a));
                bx -= 4;
                // settings: always visible when the mod has a screen
                hasSettings = ModConfigs.has(i.modId) && i.loaded;
                if (hasSettings) {
                    bx -= 15;
                    gx = bx;
                    boolean gh = hov && Rows.inside(mx, my, gx, y + 5.5f, 15, 15);
                    if (gh) h.pointer();
                    if (gh) Draw.rrect(g, gx, y + 5.5f, 15, 15, 5, Draw.withAlpha(Theme.ROW_HOVER, a));
                    Draw.icon(g, Icons.GEAR, gx + 7.5f, y + 13, 8, Draw.withAlpha(gh ? Theme.TEXT : Theme.TEXT_DIM, a));
                    bx -= 4;
                }
                hasUpdate = i.update != null && i.pending == null && !i.working;
                if (hasUpdate) {
                    uw = Draw.width("Update", 0.75f) + 14;
                    bx -= uw;
                    ux = bx;
                    rowPill(h, g, "Update", ux, y + 5.5f, uw, 15, true, true, hov, mx, my, a);
                    bx -= 4;
                }
            }

            float max = bx - x - 40;
            Draw.text(g, fit(i.name, 0.9f, max), x + 30, y + 4.5f, 0.9f, Draw.withAlpha(i.enabled ? Theme.TEXT : Theme.TEXT_DIM, a));
            Draw.text(g, fit(status(), 0.75f, max), x + 30, y + 15, 0.75f, Draw.withAlpha(statusColor(), a));
        }

        private String status() {
            if (i.working) return "Working…";
            if (i.pending != null) return i.pending;
            String v = i.version.isEmpty() ? i.file : i.version;
            String why = ModManager.refused.get(i.path);
            if (!i.enabled) v += "  ·  Off";
            else if (!i.loaded) v += why != null ? "  ·  Restart needed: " + why : "  ·  Not running yet";
            else if (i.modId != null && HotLoader.LIVE.contains(i.modId)) v += "  ·  Loaded live";
            if (i.update != null) v += "  ·  " + i.update.versionNumber + " on Modrinth";
            return v;
        }

        private int statusColor() {
            if (i.pending != null || i.working) return Theme.accent();
            if (!i.loaded && i.enabled && ModManager.refused.containsKey(i.path)) return AMBER;
            return i.enabled ? Theme.TEXT_MUTED : Theme.TEXT_MUTED;
        }

        @Override
        public boolean click(Rows.Host h, double mx, double my, float x, float y, float w) {
            if (hasSettings && Rows.inside(mx, my, gx, y + 5.5f, 15, 15)) {
                h.sound(1.1f);
                ModConfigs.open(i.modId, ModsScreen.this);
                return true;
            }
            if (i.self() || i.working) return false;
            if (Rows.inside(mx, my, tx - 3, y + 3, 26, 20)) {
                if ("Turns off at restart".equals(i.pending)) {
                    h.sound(1.25f);
                    ModManager.undo(i);
                } else if (i.pending == null) {
                    h.sound(i.enabled ? 0.85f : 1.25f);
                    ModManager.toggle(i);
                }
                return true;
            }
            if (busy()) return false;
            if (Rows.inside(mx, my, rx, y + 5.5f, 15, 15)) {
                confirmRemove(i);
                return true;
            }
            if (hasUpdate && Rows.inside(mx, my, ux, y + 5.5f, uw, 15)) {
                confirmUpdate(i);
                return true;
            }
            return false;
        }
    }

    /** A mod the game runs (from the loader, libraries included): icon, name, version, what it is, and its settings. */
    final class LibraryRow extends Rows.Row {
        private final net.fabricmc.loader.api.ModContainer m;
        private final String id, version, desc;
        private final boolean lib, settings;
        private float bx, bw;

        LibraryRow(net.fabricmc.loader.api.ModContainer m) {
            super(m.getMetadata().getName(), "");
            this.m = m;
            var meta = m.getMetadata();
            id = meta.getId();
            version = meta.getVersion().getFriendlyString();
            String d = meta.getDescription() == null ? "" : meta.getDescription().replaceAll("\\s+", " ").trim();
            desc = d.isEmpty() ? id : d;
            lib = library(m);
            settings = ModConfigs.has(id);
        }

        @Override
        public float height() {
            return 32;
        }

        @Override
        public void draw(Rows.Host h, GuiGraphics g, float x, float y, float w, int mx, int my, float a) {
            boolean hov = Rows.inside(mx, my, x, y, w, height()) && h.inView(my);
            hover.update(h.dt(), hov ? 1 : 0);
            listBase(this, g, x, y, w, a);
            ModIcons.mod(g, m, x + 7, y + 6, 20, lib ? a * 0.7f : a);

            float right = x + w - 8;
            if (settings) {
                bw = Draw.width("Settings", 0.75f) + 26;
                bx = right - bw;
                boolean bh = hov && Rows.inside(mx, my, bx, y + 8, bw, 16);
                if (bh) h.pointer();
                float e = hv(this, bh).value;
                Draw.rrect(g, bx, y + 8, bw, 16, 8, Draw.withAlpha(Draw.lerpColor(Theme.ROW_HOVER, Theme.tileHover(), e), 0.95f * a));
                Draw.icon(g, Icons.GEAR, bx + 9, y + 16, 7.5f, Draw.withAlpha(Draw.lerpColor(Theme.TEXT_DIM, Theme.accent(), e), a));
                Draw.text(g, "Settings", bx + 16, y + 13.2f, 0.75f, Draw.withAlpha(Theme.TEXT, a));
                right = bx - 8;
            } else if (lib) {
                String t = m.getContainingMod().isPresent() ? "Library" : "Built in";
                float tw = Draw.width(t, 0.75f);
                Draw.text(g, t, right - tw, y + 12.5f, 0.75f, Draw.withAlpha(Theme.TEXT_MUTED, a));
                right -= tw + 8;
            }

            float tx = x + 35, max = right - tx;
            String name = fit(label, 0.9f, max * 0.7f);
            Draw.text(g, name, tx, y + 6, 0.9f, Draw.withAlpha(lib ? Theme.TEXT_DIM : Theme.TEXT, a));
            float nw = Draw.width(name, 0.9f);
            Draw.text(g, fit(version, 0.75f, max - nw - 8), tx + nw + 6, y + 7, 0.75f, Draw.withAlpha(Theme.TEXT_MUTED, a));
            Draw.text(g, fit(desc, 0.75f, max), tx, y + 18, 0.75f, Draw.withAlpha(Theme.TEXT_DIM, a));
        }

        @Override
        public boolean click(Rows.Host h, double mx, double my, float x, float y, float w) {
            if (!settings || !Rows.inside(mx, my, bx, y + 8, bw, 16)) return false;
            h.sound(1.1f);
            ModConfigs.open(id, ModsScreen.this);
            return true;
        }
    }

    /** A row of store cards (2 or 3 columns). */
    final class CardRow extends Rows.Row {
        final List<Hit> hits;
        private final int cols;
        private final float[] bx, bw;

        CardRow(List<Hit> hits, int cols) {
            super(hits.get(0).title, "");
            this.hits = hits;
            this.cols = cols;
            bx = new float[cols];
            bw = new float[cols];
        }

        @Override
        public float height() {
            return 70;
        }

        @Override
        public void draw(Rows.Host h, GuiGraphics g, float x, float y, float w, int mx, int my, float a) {
            hover.update(h.dt(), 0);
            float gap = 6, cw = (w - gap * (cols - 1)) / cols;
            for (int k = 0; k < hits.size(); k++) {
                Hit hit = hits.get(k);
                float cx = x + k * (cw + gap);
                boolean hov = Rows.inside(mx, my, cx, y, cw, height()) && h.inView(my);
                float e = hv(hit, hov).value;
                boolean have = hit.installed();
                Draw.rrect(g, cx, y, cw, height(), 10, Draw.withAlpha(Draw.lerpColor(Theme.ROW, Theme.ROW_HOVER, 0.8f * e), (0.7f + 0.2f * e) * a));
                ModIcons.remote(g, hit.iconUrl, hit.title, cx + 8, y + 8, 26, a);

                float badgeW = hit.kind == Kind.MOD ? badge(g, hit, cx + cw - 8, y + 8, a, true) : 0;
                float textW = cw - 50 - (badgeW > 0 ? badgeW + 4 : 0);
                Draw.text(g, fit(hit.title, 0.92f, textW), cx + 41, y + 9, 0.92f, Draw.withAlpha(have ? Theme.aurora() : Theme.TEXT, a));
                Draw.text(g, fit(hit.author + "  ·  " + downloads(hit.downloads), 0.75f, textW), cx + 41, y + 20.5f, 0.75f,
                        Draw.withAlpha(Theme.TEXT_MUTED, a));

                String state = hit.state != null ? hit.state : have ? "Installed" : null;
                boolean can = state == null;
                String label = can ? (hit.kind == Kind.MOD ? "Install" : "Download") : state;
                bw[k] = Math.max(52, Draw.width(label, 0.75f) + 16);
                bx[k] = cx + cw - 8 - bw[k];
                String[] desc = twoLines(hit.description, 0.75f, cw - 16, cw - 22 - bw[k]);
                for (int l = 0; l < desc.length; l++) {
                    Draw.text(g, desc[l], cx + 8, y + 40 + l * 9.5f, 0.75f, Draw.withAlpha(Theme.TEXT_DIM, a));
                }
                if (hit.progress >= 0 && hit.state != null && hit.state.startsWith("Downloading")) {
                    bw[k] = Math.max(bw[k], 64);
                    bx[k] = cx + cw - 8 - bw[k];
                    progressPill(g, hit.progress, bx[k], y + height() - 21, bw[k], 15, a);
                } else {
                    rowPill(h, g, label, bx[k], y + height() - 21, bw[k], 15, can, can, hov, mx, my, a);
                }
                if (hov && h.inView(my)) h.pointer();
            }
        }

        @Override
        public boolean click(Rows.Host h, double mx, double my, float x, float y, float w) {
            float gap = 6, cw = (w - gap * (cols - 1)) / cols;
            for (int k = 0; k < hits.size(); k++) {
                Hit hit = hits.get(k);
                if (hit.state == null && !hit.installed() && Rows.inside(mx, my, bx[k], y + height() - 21, bw[k], 15)) {
                    h.sound(1.15f);
                    ModManager.install(hit);
                    return true;
                }
                if (Rows.inside(mx, my, x + k * (cw + gap), y, cw, height())) {
                    h.sound(1.05f);
                    openDetail(hit);
                    return true;
                }
            }
            return false;
        }
    }

    /** A mod with a newer version: installed in red, new in green, on the right. */
    final class UpdateRow extends Rows.Row {
        private final Installed i;
        private float bx, bw;

        UpdateRow(Installed i) {
            super(i.name, "");
            this.i = i;
        }

        @Override
        public float height() {
            return 26;
        }

        @Override
        public void draw(Rows.Host h, GuiGraphics g, float x, float y, float w, int mx, int my, float a) {
            boolean hov = Rows.inside(mx, my, x, y, w, height()) && h.inView(my);
            hover.update(h.dt(), hov ? 1 : 0);
            listBase(this, g, x, y, w, a);
            ModIcons.jar(g, i.path, i.name, x + 6, y + 4, 18, a);
            ModManager.Remote r = i.update;
            boolean on = r != null && i.pending == null && !i.working;
            String label = i.working ? "Updating…" : "Update";
            bw = Draw.width(label, 0.75f) + 16;
            bx = x + w - 8 - bw;
            rowPill(h, g, label, bx, y + 5.5f, bw, 15, true, on, hov, mx, my, a);

            float vx = bx - 10;
            if (r != null) {
                String to = fit(r.versionNumber, 0.75f, 110), from = fit(i.version.isEmpty() ? "?" : i.version, 0.75f, 110);
                vx -= Draw.width(to, 0.75f);
                Draw.text(g, to, vx, y + 9.5f, 0.75f, Draw.withAlpha(GREEN, a));
                vx -= Draw.width("→", 0.75f) + 5;
                Draw.text(g, "→", vx, y + 9.5f, 0.75f, Draw.withAlpha(Theme.TEXT_MUTED, a));
                vx -= Draw.width(from, 0.75f) + 5;
                Draw.text(g, from, vx, y + 9.5f, 0.75f, Draw.withAlpha(RED, a));
            }
            Draw.text(g, fit(i.name, 0.9f, vx - x - 40), x + 30, y + 9, 0.9f, Draw.withAlpha(Theme.TEXT, a));
        }

        @Override
        public boolean click(Rows.Host h, double mx, double my, float x, float y, float w) {
            if (i.update == null || i.pending != null || i.working || !Rows.inside(mx, my, bx, y + 5.5f, bw, 15)) return false;
            confirmUpdate(i);
            return true;
        }
    }

    /** A change waiting for the restart, with its undo. */
    final class PendingRow extends Rows.Row {
        private final Installed i;
        private float bx, bw;

        PendingRow(Installed i) {
            super(i.name, "");
            this.i = i;
        }

        @Override
        public float height() {
            return 30;
        }

        @Override
        public void draw(Rows.Host h, GuiGraphics g, float x, float y, float w, int mx, int my, float a) {
            boolean hov = Rows.inside(mx, my, x, y, w, height()) && h.inView(my);
            hover.update(h.dt(), hov ? 1 : 0);
            listBase(this, g, x, y, w, a);
            ModIcons.jar(g, i.path, i.name, x + 6, y + 5, 20, a);
            boolean can = ModManager.canUndo(i);
            String label = i.working ? "Working…" : can ? "Undo" : "At restart";
            bw = Math.max(60, Draw.width(label, 0.75f) + 16);
            bx = x + w - 8 - bw;
            rowPill(h, g, label, bx, y + 7.5f, bw, 15, false, can, hov, mx, my, a);
            float max = bx - x - 46;
            Draw.text(g, fit(i.name, 0.9f, max), x + 32, y + 5, 0.9f, Draw.withAlpha(Theme.TEXT, a));
            String p = i.pending == null ? "Taken back" : i.pending;
            String v = i.version.isEmpty() ? "" : i.version + "  ·  ";
            Draw.text(g, fit(v + p, 0.75f, max), x + 32, y + 16.5f, 0.75f, Draw.withAlpha(i.pending == null ? Theme.TEXT_MUTED : Theme.accent(), a));
        }

        @Override
        public boolean click(Rows.Host h, double mx, double my, float x, float y, float w) {
            if (!ModManager.canUndo(i) || !Rows.inside(mx, my, bx, y + 7.5f, bw, 15)) return false;
            h.sound(1f);
            ModManager.undo(i);
            return true;
        }
    }
}
