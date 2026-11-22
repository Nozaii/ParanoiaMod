package gg.aurora.ui;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import gg.aurora.Settings;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Sélecteur d'objet façon inventaire créatif : onglets (blocs de construction, nature, redstone…),
 * recherche par nom, grille d'objets. Un clic choisit l'objet et revient à l'écran précédent.
 */
public class ItemPickerScreen extends Screen {
    private final Screen parent;
    private final String current;
    private final Consumer<String> onPick;
    private final List<String> tabNames = new ArrayList<>();
    private final List<ItemStack> tabIcons = new ArrayList<>();
    private final List<List<Item>> tabItems = new ArrayList<>();
    private int tab = 0;
    private String search = "";
    private float scroll, scrollAnim;
    private long last = System.nanoTime();
    private float open;
    private List<Item> shown = List.of();

    // géométrie (calculée au rendu, réutilisée pour les clics)
    private float px, py, pw, ph, gx, gy, gh;
    private int cols;
    private static final float CELL = 20;

    public ItemPickerScreen(Screen parent, String current, Consumer<String> onPick) {
        super(Component.literal("Pick an item"));
        this.parent = parent;
        this.current = current;
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        if (!tabItems.isEmpty()) return;
        // « Tout » : chaque objet du jeu, dans l'ordre du registre
        List<Item> all = new ArrayList<>();
        for (Item it : BuiltInRegistries.ITEM) if (it != Items.AIR) all.add(it);
        tabNames.add("All");
        tabIcons.add(new ItemStack(Items.COMPASS));
        tabItems.add(all);
        // onglets du mode créatif (contenu reconstruit pour le monde courant)
        try {
            if (minecraft.level != null) {
                CreativeModeTabs.tryRebuildTabContents(minecraft.level.enabledFeatures(), false, minecraft.level.registryAccess());
            }
            for (CreativeModeTab t : CreativeModeTabs.tabs()) {
                if (t.getType() != CreativeModeTab.Type.CATEGORY || !t.hasAnyItems()) continue;
                Set<Item> items = new LinkedHashSet<>();
                for (ItemStack s : t.getDisplayItems()) items.add(s.getItem());
                if (items.isEmpty()) continue;
                tabNames.add(t.getDisplayName().getString());
                tabIcons.add(t.getIconItem());
                tabItems.add(new ArrayList<>(items));
            }
        } catch (RuntimeException e) {
            // hors d'un monde : seul l'onglet « Tout » reste disponible
        }
        refresh();
    }

    private void refresh() {
        List<Item> base = tabItems.get(tab);
        if (search.isEmpty()) {
            shown = base;
        } else {
            String q = search.toLowerCase(Locale.ROOT);
            List<Item> r = new ArrayList<>();
            // la recherche porte sur tous les objets, pas seulement l'onglet ouvert
            for (Item it : tabItems.get(0)) {
                String name = it.getName(new ItemStack(it)).getString().toLowerCase(Locale.ROOT);
                if (name.contains(q) || BuiltInRegistries.ITEM.getKey(it).getPath().contains(q.replace(' ', '_'))) r.add(it);
            }
            shown = r;
        }
        scroll = 0;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        long now = System.nanoTime();
        float dt = Math.min(0.05f, (now - last) / 1e9f);
        last = now;
        open = Settings.anim() ? Math.min(1, open + dt * 6) : 1;
        float a = open;
        g.fill(0, 0, width, height, Draw.withAlpha(0x000000, 0.55f * a));
        boolean pointer = false;

        cols = (int) Math.max(6, Math.min(14, (width - 80) / CELL));
        pw = cols * CELL + 24;
        ph = Math.min(height - 30, 330);
        px = (width - pw) / 2;
        py = (height - ph) / 2 + (1 - Draw.easeOutCubic(a)) * 12;
        Draw.glow(g, px, py + 4, pw, ph, 18, Draw.withAlpha(0x000000, 0.5f * a));
        Draw.glass(g, px, py, pw, ph, 10, Draw.withAlpha(Theme.PANEL, 0.97f * a), Draw.withAlpha(0xFFFFFF, 0.18f * a));

        // titre + fermer
        Draw.text(g, "Pick the icon", px + 12, py + 10, 1.05f, Draw.withAlpha(Theme.TEXT, a));
        float cx = px + pw - 20, cy = py + 8;
        boolean hvClose = mx >= cx && mx < cx + 12 && my >= cy && my < cy + 12;
        pointer |= hvClose;
        Draw.centered(g, "×", cx + 6, cy + 1, 1.1f, Draw.withAlpha(hvClose ? 0xFFFFFF : Theme.TEXT_DIM, a));

        // recherche
        float sx = px + 12, sy = py + 26, sw = pw - 24, sh = 16;
        Draw.rrect(g, sx, sy, sw, sh, 8, Draw.withAlpha(0x000000, 0.35f * a));
        Draw.ring(g, sx, sy, sw, sh, 8, Draw.withAlpha(Theme.accent(), (search.isEmpty() ? 0.35f : 0.8f) * a));
        Draw.icon(g, Icons.SEARCH, sx + 10, sy + 8, 7, Draw.withAlpha(Theme.TEXT_DIM, a));
        boolean caret = (System.currentTimeMillis() / 500) % 2 == 0;
        Draw.text(g, search.isEmpty() ? "Search a block or item…" : search + (caret ? "|" : ""), sx + 20, sy + 4.5f, 0.85f,
                Draw.withAlpha(search.isEmpty() ? Theme.TEXT_MUTED : Theme.TEXT, a));

        // onglets
        float tx = px + 12, ty = sy + sh + 6, ts = 22;
        int perRow = (int) ((pw - 24) / (ts + 3));
        for (int i = 0; i < tabNames.size(); i++) {
            float x = tx + (i % perRow) * (ts + 3), y = ty + (i / perRow) * (ts + 3);
            boolean hv = mx >= x && mx < x + ts && my >= y && my < y + ts;
            pointer |= hv;
            boolean sel = i == tab && search.isEmpty();
            Draw.rrect(g, x, y, ts, ts, 6, Draw.withAlpha(sel ? Theme.accent() : hv ? Theme.ROW_HOVER : Theme.ROW, (sel ? 0.55f : 0.9f) * a));
            if (sel) Draw.ring(g, x, y, ts, ts, 6, Draw.withAlpha(Theme.accent(), a));
            g.renderItem(tabIcons.get(i), Math.round(x + 3), Math.round(y + 3));
        }
        int tabRows = (tabNames.size() + perRow - 1) / perRow;
        float labelY = ty + tabRows * (ts + 3) + 2;
        String header = search.isEmpty() ? tabNames.get(tab) : "Results for « " + search + " »";
        Draw.text(g, header + "  ·  " + shown.size(), px + 12, labelY, 0.8f, Draw.withAlpha(Theme.TEXT_DIM, a));

        // grille
        gx = px + 12;
        gy = labelY + 12;
        gh = py + ph - gy - 22;
        int rows = (shown.size() + cols - 1) / cols;
        float maxScroll = Math.max(0, rows * CELL - gh);
        scroll = Math.max(0, Math.min(maxScroll, scroll));
        scrollAnim += (scroll - scrollAnim) * Math.min(1, dt * 18);
        Item hovered = null;
        g.enableScissor((int) gx, (int) gy, (int) (gx + cols * CELL), (int) (gy + gh));
        int first = Math.max(0, (int) (scrollAnim / CELL) * cols), lastIdx = Math.min(shown.size(), first + (int) (gh / CELL + 2) * cols);
        for (int i = first; i < lastIdx; i++) {
            Item it = shown.get(i);
            float x = gx + (i % cols) * CELL, y = gy + (i / cols) * CELL - scrollAnim;
            boolean hv = mx >= x && mx < x + CELL && my >= y && my < y + CELL && my >= gy && my < gy + gh;
            boolean cur = BuiltInRegistries.ITEM.getKey(it).toString().equals(current);
            if (hv || cur) Draw.rrect(g, x + 0.5f, y + 0.5f, CELL - 1, CELL - 1, 4,
                    Draw.withAlpha(cur ? Theme.accent() : 0xFFFFFF, (cur ? 0.45f : 0.14f) * a));
            if (hv) {
                hovered = it;
                pointer = true;
            }
            g.renderItem(new ItemStack(it), Math.round(x + 2), Math.round(y + 2));
        }
        g.disableScissor();
        if (shown.isEmpty()) Draw.centered(g, "No items found", px + pw / 2, gy + gh / 2 - 4, 0.9f, Draw.withAlpha(Theme.TEXT_MUTED, a));
        // barre de défilement
        if (maxScroll > 0) {
            float bh = Math.max(16, gh * gh / (rows * CELL)), by = gy + (gh - bh) * (scrollAnim / maxScroll);
            Draw.rrect(g, px + pw - 8, by, 3, bh, 1.5f, Draw.withAlpha(0xFFFFFF, 0.25f * a));
        }
        // nom de l'objet survolé
        String foot = hovered != null ? hovered.getName(new ItemStack(hovered)).getString() + "   " + BuiltInRegistries.ITEM.getKey(hovered)
                : "Click an item to pick it · Esc to cancel";
        Draw.text(g, foot, px + 12, py + ph - 15, 0.8f, Draw.withAlpha(hovered != null ? Theme.TEXT : Theme.TEXT_MUTED, a));
        if (pointer) g.requestCursor(CursorTypes.POINTING_HAND);
    }

    private void click(float pitch) {
        if (Settings.sounds) minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch, 0.3f));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean dbl) {
        double mx = event.x(), my = event.y();
        if (event.button() != 0) return super.mouseClicked(event, dbl);
        if (mx < px || mx > px + pw || my < py || my > py + ph) {
            onClose();
            return true;
        }
        float cx = px + pw - 20, cy = py + 8;
        if (mx >= cx && mx < cx + 12 && my >= cy && my < cy + 12) {
            onClose();
            return true;
        }
        float ts = 22, tx = px + 12, ty = py + 26 + 16 + 6;
        int perRow = (int) ((pw - 24) / (ts + 3));
        for (int i = 0; i < tabNames.size(); i++) {
            float x = tx + (i % perRow) * (ts + 3), y = ty + (i / perRow) * (ts + 3);
            if (mx >= x && mx < x + ts && my >= y && my < y + ts) {
                tab = i;
                search = "";
                refresh();
                scrollAnim = 0;
                click(1.1f);
                return true;
            }
        }
        if (mx >= gx && mx < gx + cols * CELL && my >= gy && my < gy + gh) {
            int col = (int) ((mx - gx) / CELL), row = (int) ((my - gy + scrollAnim) / CELL);
            int i = row * cols + col;
            if (i >= 0 && i < shown.size()) {
                click(1.25f);
                onPick.accept(BuiltInRegistries.ITEM.getKey(shown.get(i)).toString());
                onClose();
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scroll -= (float) sy * CELL * 2;
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (event.isAllowedChatCharacter() && search.length() < 32) {
            search += event.codepointAsString();
            refresh();
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (!search.isEmpty()) {
                search = "";
                refresh();
            } else {
                onClose();
            }
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) {
            search = search.substring(0, search.length() - 1);
            refresh();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_ENTER && !shown.isEmpty() && !search.isEmpty()) {
            onPick.accept(BuiltInRegistries.ITEM.getKey(shown.get(0)).toString());
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    /** Dev : ouvre un onglet / tape une recherche. */
    public void debugTab(int i) {
        tab = Math.min(i, tabItems.size() - 1);
        refresh();
    }

    public void debugSearch(String q) {
        search = q;
        refresh();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return parent == null || parent.isPauseScreen();
    }
}
