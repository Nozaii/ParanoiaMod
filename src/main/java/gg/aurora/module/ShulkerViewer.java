package gg.aurora.module;

import com.mojang.blaze3d.platform.InputConstants;
import gg.aurora.ui.Icons;
import gg.aurora.ui.Rows;
import net.fabricmc.fabric.api.client.rendering.v1.TooltipComponentCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Shulker Viewer : au survol, le contenu regroupé (objets identiques empilés, total sous l'icône) ;
 * en tenant Ctrl gauche, la boîte entière (9 × 3 cases, à leur place), teintée de la couleur de la boîte.
 */
public final class ShulkerViewer {
    private ShulkerViewer() {}

    private static final int COLS = 9, ROWS = 3, SLOT = 18, PAD = 4, CELL = 24, CELL_H = 26, PER_ROW = 7;
    /** Outil de dev : force la vue complète (captures sans clavier). */
    public static boolean devFull;

    public static final Module MODULE = Modules.register(new Module("shulker_viewer", "Shulker Viewer", Category.MISC, Icons.BAG, true))
            .withSettings(() -> List.of(
                    new Rows.Toggle("Box color", "Tints the grid with the shulker color", () -> ShulkerViewer.MODULE.flag("tint", true), v -> ShulkerViewer.MODULE.set("tint", v)),
                    new Rows.Toggle("Hide text list", "Removes the vanilla \"Contains\" lines", () -> ShulkerViewer.MODULE.flag("hide_text", true), v -> ShulkerViewer.MODULE.set("hide_text", v))));

    /** Données du tooltip : les 27 cases dans l'ordre, la couleur de la boîte (null = boîte simple). */
    public record Contents(NonNullList<ItemStack> items, DyeColor color) implements TooltipComponent {}

    public static void init() {
        TooltipComponentCallback.EVENT.register(data -> data instanceof Contents c ? new Viewer(c) : null);
    }

    static boolean isShulker(ItemStack stack) {
        return stack.getItem() instanceof BlockItem b && b.getBlock() instanceof ShulkerBoxBlock;
    }

    /** Appelé par ItemStack#getTooltipImage. Une boîte vide garde son tooltip normal. */
    public static Optional<TooltipComponent> image(ItemStack stack) {
        if (!MODULE.enabled || !isShulker(stack)) return Optional.empty();
        var contents = stack.get(DataComponents.CONTAINER);
        if (contents == null || !contents.nonEmptyItems().iterator().hasNext()) return Optional.empty();
        NonNullList<ItemStack> items = NonNullList.withSize(COLS * ROWS, ItemStack.EMPTY);
        contents.copyInto(items);
        DyeColor color = ((ShulkerBoxBlock) ((BlockItem) stack.getItem()).getBlock()).getColor();
        return Optional.of(new Contents(items, color));
    }

    /** Vrai quand les lignes vanilla « Contains » doivent disparaître pour cette boîte. */
    public static boolean hideText(ItemStack stack) {
        return MODULE.enabled && MODULE.flag("hide_text", true) && image(stack).isPresent();
    }

    /** Même chose à partir des composants seuls (appel depuis ItemContainerContents#addToTooltip). */
    public static boolean hideTextFor(net.minecraft.core.component.DataComponentGetter getter) {
        if (!MODULE.enabled || !MODULE.flag("hide_text", true) || getter == null) return false;
        var model = getter.get(DataComponents.ITEM_MODEL);
        return model != null && model.getPath().endsWith("shulker_box");
    }

    static boolean full() {
        if (devFull) return true;
        var w = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(w, GLFW.GLFW_KEY_LEFT_CONTROL);
    }

    /** Objets identiques (même objet, mêmes composants) regroupés, le plus nombreux d'abord. */
    static List<ItemStack> grouped(NonNullList<ItemStack> items) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack it : items) {
            if (it.isEmpty()) continue;
            ItemStack same = null;
            for (ItemStack o : out) if (ItemStack.isSameItemSameComponents(o, it)) same = o;
            if (same != null) same.setCount(same.getCount() + it.getCount());
            else out.add(it.copy());
        }
        out.sort((a, b) -> Integer.compare(b.getCount(), a.getCount()));
        return out;
    }

    private record Viewer(Contents c) implements ClientTooltipComponent {
        private int cols(List<ItemStack> g) {
            return Math.min(PER_ROW, Math.max(1, g.size()));
        }

        @Override
        public int getHeight(Font font) {
            if (full()) return ROWS * SLOT + PAD * 2 + 2;
            List<ItemStack> g = grouped(c.items());
            int rows = (g.size() + PER_ROW - 1) / PER_ROW;
            return rows * CELL_H + PAD * 2 + 2 + 11;
        }

        @Override
        public int getWidth(Font font) {
            if (full()) return COLS * SLOT + PAD * 2;
            List<ItemStack> g = grouped(c.items());
            return Math.max(cols(g) * CELL + PAD * 2, font.width("Hold Ctrl: whole box") + PAD * 2);
        }

        @Override
        public void renderImage(Font font, int x, int y, int w, int h, GuiGraphics g) {
            int rgb = readable(c.color() != null && MODULE.flag("tint", true) ? c.color().getTextureDiffuseColor() & 0xFFFFFF : 0x9D7CFF);
            if (full()) {
                int bw = COLS * SLOT + PAD * 2, bh = ROWS * SLOT + PAD * 2;
                box(g, x, y, bw, bh, rgb);
                for (int i = 0; i < c.items().size(); i++) {
                    int sx = x + PAD + i % COLS * SLOT, sy = y + PAD + i / COLS * SLOT;
                    slot(g, sx + 1, sy + 1, SLOT - 2, SLOT - 2, rgb);
                    ItemStack it = c.items().get(i);
                    if (it.isEmpty()) continue;
                    g.renderItem(it, sx + 1, sy + 1);
                    g.renderItemDecorations(font, it, sx + 1, sy + 1);
                }
                return;
            }
            List<ItemStack> list = grouped(c.items());
            int cols = cols(list), rows = (list.size() + PER_ROW - 1) / PER_ROW;
            int bw = Math.max(cols * CELL + PAD * 2, font.width("Hold Ctrl: whole box") + PAD * 2), bh = rows * CELL_H + PAD * 2;
            box(g, x, y, bw, bh, rgb);
            for (int i = 0; i < list.size(); i++) {
                int cx = x + PAD + i % PER_ROW * CELL, cy = y + PAD + i / PER_ROW * CELL_H;
                ItemStack it = list.get(i);
                slot(g, cx + 1, cy + 1, CELL - 2, CELL_H - 2, rgb);
                g.renderItem(it, cx + (CELL - 16) / 2, cy + 1);
                // total réel (peut dépasser 64), en petit sous l'objet : jamais sur la case voisine
                String n = it.getCount() > 1 ? count(it.getCount()) : "1";
                g.pose().pushMatrix();
                g.pose().translate(cx + CELL / 2f, cy + 18.5f);
                g.pose().scale(0.75f, 0.75f);
                g.drawString(font, n, -font.width(n) / 2, 0, 0xFFFFFFFF, true);
                g.pose().popMatrix();
            }
            g.drawString(font, "Hold Ctrl: whole box", x + PAD, y + bh + 3, 0xFF8D87AA, true);
        }

        private static String count(int n) {
            return n >= 1000 ? (n / 1000) + "k" : String.valueOf(n);
        }

        /**
         * Same dark base for every box (a black or blue tint on the dark tooltip was invisible); the colour sits
         * where it reads: a solid bar on top, the border, and a faint wash in each slot.
         */
        private static void box(GuiGraphics g, int x, int y, int bw, int bh, int rgb) {
            int base = mix(0x0E0C18, rgb, 0.10f);
            g.fill(x, y, x + bw, y + bh, 0xF0000000 | base);
            g.fill(x, y, x + bw, y + 2, 0xFF000000 | rgb);
            g.fill(x, y + bh - 1, x + bw, y + bh, 0xB0000000 | rgb);
            g.fill(x, y + 2, x + 1, y + bh, 0xB0000000 | rgb);
            g.fill(x + bw - 1, y + 2, x + bw, y + bh, 0xB0000000 | rgb);
        }

        private static void slot(GuiGraphics g, int x, int y, int w, int h, int rgb) {
            g.fill(x, y, x + w, y + h, 0xFF000000 | mix(0x1A1728, rgb, 0.16f));
            g.fill(x, y, x + w, y + 1, 0x40000000); // inner shadow on top: the slot reads as a hollow
        }

        /** The box colour, lightened until it stands out on the dark base (black, gray, brown, blue, purple...). */
        private static int readable(int rgb) {
            for (int i = 0; i < 12 && luminance(rgb) < 0.42f; i++) rgb = mix(rgb, 0xFFFFFF, 0.14f);
            return rgb;
        }

        private static float luminance(int rgb) {
            return (0.2126f * (rgb >> 16 & 255) + 0.7152f * (rgb >> 8 & 255) + 0.0722f * (rgb & 255)) / 255f;
        }

        private static int mix(int a, int b, float t) {
            int r = Math.round((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * t);
            int gg = Math.round((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * t);
            int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
            return r << 16 | gg << 8 | bl;
        }
    }
}
