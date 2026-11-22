package gg.aurora.module;

import gg.aurora.ui.Draw;
import gg.aurora.ui.HudKit;
import gg.aurora.ui.Icons;
import gg.aurora.ui.Rows;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Widgets HUD dessinés à la main : armure + compteurs (fusion Armor/Dura Counter) et effets de potion. */
public final class HudWidgets {
    private HudWidgets() {}

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    /** Case façon inventaire : bord noir, cadre gris éclairé en haut à gauche, fond sombre. */
    static void slot(GuiGraphics g, int x, int y, int s) {
        g.fill(x, y, x + s, y + s, 0xFF000000);
        g.fill(x + 1, y + 1, x + s - 1, y + s - 1, 0xFF8B8B8B);
        g.fill(x + 1, y + 1, x + s - 1, y + 2, 0xFFC6C6C6);
        g.fill(x + 1, y + 1, x + 2, y + s - 1, 0xFFC6C6C6);
        g.fill(x + 3, y + 3, x + s - 3, y + s - 3, 0xD0303018);
    }

    // =========================================================================== Armure

    /**
     * Armor HUD : pièces d'armure (durabilité) et objets en main (compteur sur tout l'inventaire),
     * dans un seul widget. Durabilité et compteur se règlent séparément.
     */
    public static final class Armor extends HudModule {
        private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        private static final Identifier[] EMPTY = {
                Identifier.withDefaultNamespace("container/slot/helmet"), Identifier.withDefaultNamespace("container/slot/chestplate"),
                Identifier.withDefaultNamespace("container/slot/leggings"), Identifier.withDefaultNamespace("container/slot/boots"),
                Identifier.withDefaultNamespace("container/slot/shield")};
        public static final String[] STYLES = {"Slots", "Paranoia"};
        public static final String[] LAYOUTS = {"Horizontal", "Vertical"};
        public static final String[] DURA = {"Bar", "Value", "%", "Bar + value", "None"};

        public Armor() {
            super("armor", "Armor HUD", Icons.ARMOR, true, 0.99f, 0.80f, () -> null);
            withSettings(() -> List.of(
                    new Rows.Choice("Style", STYLES, () -> (int) opt("style", 0), i -> set("style", i)),
                    new Rows.Choice("Layout", LAYOUTS, () -> (int) opt("layout", 0), i -> set("layout", i)),
                    new Rows.Toggle("Armor", "Helmet, chestplate, leggings, boots", () -> flag("armor", true), v -> set("armor", v)),
                    new Rows.Slider("Low durability alert", "Warns under this percentage (0 = off)", 0, 50, 1, () -> opt("alert", 15), v -> set("alert", v),
                            v -> v < 1 ? "Off" : Math.round(v) + " %"),
                    new Rows.Toggle("Alert sound", "Plays once per piece", () -> flag("alertSound", true), v -> set("alertSound", v)),
                    new Rows.Toggle("Alert notification", "Toast when a piece is almost broken, even if the HUD is hidden", () -> flag("toast", true), v -> set("toast", v)),
                    new Rows.Choice("Durability", DURA, () -> (int) opt("dura", 0), i -> set("dura", i)),
                    new Rows.Toggle("Held items", "Main hand and off hand", () -> flag("hands", true), v -> set("hands", v)),
                    new Rows.Toggle("Counter", "Total of this item in the whole inventory", () -> flag("counter", true), v -> set("counter", v))));
        }

        private record Entry(ItemStack stack, int emptyIndex, String count) {}

        /** Nombre total d'objets identiques : barre de raccourcis, inventaire, main gauche. */
        static int total(LocalPlayer p, ItemStack ref) {
            var inv = p.getInventory();
            int n = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack st = inv.getItem(i);
                if (!st.isEmpty() && ItemStack.isSameItem(st, ref)) n += st.getCount();
            }
            return n;
        }

        /** Arc / arbalète : flèches disponibles. */
        static int arrows(LocalPlayer p) {
            var inv = p.getInventory();
            int n = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack st = inv.getItem(i);
                if (st.is(Items.ARROW) || st.is(Items.SPECTRAL_ARROW) || st.is(Items.TIPPED_ARROW)) n += st.getCount();
            }
            return n;
        }

        private List<Entry> entries(boolean preview) {
            List<Entry> out = new ArrayList<>();
            LocalPlayer p = mc().player;
            if (p == null) {
                if (preview) {
                    out.add(new Entry(new ItemStack(Items.DIAMOND_HELMET), -1, null));
                    out.add(new Entry(ItemStack.EMPTY, 1, null));
                    out.add(new Entry(new ItemStack(Items.NETHERITE_LEGGINGS), -1, null));
                    out.add(new Entry(new ItemStack(Items.GOLDEN_APPLE), -1, "24"));
                }
                return out;
            }
            if (flag("armor", true)) {
                for (int i = 0; i < ARMOR.length; i++) {
                    ItemStack st = p.getItemBySlot(ARMOR[i]);
                    // the four slots and the off hand always stay on screen, full or empty
                    out.add(st.isEmpty() ? new Entry(ItemStack.EMPTY, i, null) : new Entry(st, -1, null));
                }
            }
            if (flag("hands", true)) {
                if (!p.getMainHandItem().isEmpty()) out.add(new Entry(p.getMainHandItem(), -1, counter(p, p.getMainHandItem())));
                ItemStack off = p.getOffhandItem();
                out.add(off.isEmpty() ? new Entry(ItemStack.EMPTY, 4, null) : new Entry(off, -1, counter(p, off)));
            }
            return out;
        }

        private String counter(LocalPlayer p, ItemStack st) {
            if (!flag("counter", true)) return null;
            if (st.is(Items.BOW) || st.is(Items.CROSSBOW)) return String.valueOf(arrows(p));
            return st.getMaxStackSize() > 1 || !st.isDamageableItem() ? String.valueOf(total(p, st)) : null;
        }

        private final java.util.Set<String> warned = new java.util.HashSet<>();

        /** Under the threshold: true, and the warning sound plays once for this piece. */
        private boolean low(ItemStack st) {
            double limit = opt("alert", 15);
            if (limit < 1 || !st.isDamageableItem()) return false;
            String id = st.getItem().toString();
            float left = 100f * (st.getMaxDamage() - st.getDamageValue()) / st.getMaxDamage();
            if (left >= limit) {
                warned.remove(id);
                return false;
            }
            if (warned.add(id) && flag("alertSound", true)) {
                mc().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 0.7f, 1f));
            }
            return true;
        }

        /** Red pulsing marker on a slot. */
        private static void alert(GuiGraphics g, int x, int y, int s) {
            float p = 0.55f + 0.45f * (float) Math.abs(Math.sin(System.currentTimeMillis() / 220.0));
            g.fill(x, y, x + s, y + s, Draw.withAlpha(0xFF2D2D, 0.28f * p));
            Draw.rrect(g, x + s - 8, y - 1, 9, 9, 4.5f, Draw.withAlpha(0xE5252A, p + 0.1f));
            Draw.centered(g, "!", x + s - 3.5f, y + 0.8f, 0.72f, 0xFFFFFFFF);
        }

        private String duraText(ItemStack st, int mode) {
            if (!st.isDamageableItem()) return null;
            int left = st.getMaxDamage() - st.getDamageValue();
            if (mode == 1 || mode == 3) return String.valueOf(left);
            if (mode == 2) return Math.round(100f * left / st.getMaxDamage()) + "%";
            return null;
        }

        @Override
        public void render(GuiGraphics g, boolean preview) {
            List<Entry> list = entries(preview);
            if (list.isEmpty()) return;
            boolean horiz = opt("layout", 0) == 0;
            int dura = (int) opt("dura", 0);
            if ((int) opt("style", 0) == 0) cases(g, list, horiz, dura);
            else paranoia(g, list, horiz, dura);
        }

        /** Style « Cases » : comme l'inventaire, barre de durabilité et compteur façon vanilla. */
        private void cases(GuiGraphics g, List<Entry> list, boolean horiz, int dura) {
            final int s = 22, gap = 1;
            boolean text = dura == 1 || dura == 2 || dura == 3;
            int textH = text ? 9 : 0;
            int n = list.size();
            lastW = horiz ? n * s + (n - 1) * gap : s + (text ? 26 : 0);
            lastH = horiz ? s + textH : n * s + (n - 1) * gap;
            int x0 = px(g), y0 = py(g);
            var font = mc().font;
            for (int i = 0; i < n; i++) {
                Entry e = list.get(i);
                int x = horiz ? x0 + i * (s + gap) : x0, y = horiz ? y0 : y0 + i * (s + gap);
                if (HudKit.current == null || HudKit.current.showBg) slot(g, x, y, s);
                if (e.stack().isEmpty()) {
                    if (e.emptyIndex() >= 0) g.blitSprite(RenderPipelines.GUI_TEXTURED, EMPTY[e.emptyIndex()], x + 3, y + 3, 16, 16, 0x70FFFFFF);
                    continue;
                }
                g.renderItem(e.stack(), x + 3, y + 3);
                // barre vanilla si « Barre » ; compteur en bas à droite comme une pile
                ItemStack deco = dura == 0 || dura == 3 ? e.stack() : e.stack().copyWithCount(1);
                if (dura != 0 && dura != 3 && deco.isDamageableItem()) deco.setDamageValue(0);
                g.renderItemDecorations(font, deco, x + 3, y + 3, e.count());
                if (low(e.stack())) alert(g, x, y, s);
                String t = text ? duraText(e.stack(), dura) : null;
                if (t != null) {
                    float tw = Draw.width(t, 0.7f);
                    if (horiz) HudKit.text(g, t, x + s / 2f - tw / 2, y + s + 1.5f, 0.7f, duraColor(e.stack()));
                    else HudKit.text(g, t, x + s + 3, y + 7.5f, 0.7f, duraColor(e.stack()));
                }
            }
        }

        private static int duraColor(ItemStack st) {
            if (!st.isDamageableItem()) return HudKit.textColor();
            return 0xFF000000 | st.getBarColor();
        }

        /** Style « Paranoia » : fond du HUD, icône, valeur et barre fine. */
        private void paranoia(GuiGraphics g, List<Entry> list, boolean horiz, int dura) {
            final int cell = 19;
            float textW = 0;
            for (Entry e : list) {
                String t = label(e, dura);
                if (t != null) textW = Math.max(textW, Draw.width(t, 0.9f));
            }
            int n = list.size();
            if (horiz) {
                lastW = n * 24 + 6;
                lastH = 34;
            } else {
                lastW = (int) Math.ceil(5 + 16 + 5 + Math.max(textW, 26) + 7);
                lastH = n * cell + 5;
            }
            int x0 = px(g), y0 = py(g);
            HudKit.background(g, x0, y0, lastW, lastH, 4);
            for (int i = 0; i < n; i++) {
                Entry e = list.get(i);
                int ix = horiz ? x0 + 4 + i * 24 : x0 + 5, iy = horiz ? y0 + 4 : y0 + 3 + i * cell + 1;
                if (e.stack().isEmpty()) {
                    if (e.emptyIndex() >= 0) g.blitSprite(RenderPipelines.GUI_TEXTURED, EMPTY[e.emptyIndex()], ix, iy, 16, 16, 0x60FFFFFF);
                    continue;
                }
                g.renderItem(e.stack(), ix, iy);
                if (low(e.stack())) alert(g, ix - 1, iy - 1, 18);
                String t = label(e, dura);
                float bw = horiz ? 18 : lastW - 26 - 7, bx = horiz ? ix - 1 : x0 + 26, by = horiz ? iy + 26 : iy + 11.5f;
                if (t != null) {
                    if (horiz) HudKit.text(g, t, ix + 8 - Draw.width(t, 0.7f) / 2, iy + 17.5f, 0.7f, HudKit.textColor());
                    else HudKit.text(g, t, bx, iy + 1.5f, 0.9f, HudKit.textColor());
                }
                if ((dura == 0 || dura == 3) && e.stack().isDamageableItem()) {
                    float f = 1f - e.stack().getDamageValue() / (float) e.stack().getMaxDamage();
                    Draw.rrect(g, bx, by, bw, 2, 1, Draw.withAlpha(0xFFFFFF, 0.1f));
                    Draw.rrect(g, bx, by, Math.max(2, bw * f), 2, 1, 0xFF000000 | e.stack().getBarColor());
                }
            }
        }

        private String label(Entry e, int dura) {
            if (e.stack().isEmpty()) return null;
            if (e.count() != null) return "x" + e.count();
            return duraText(e.stack(), dura == 0 ? 1 : dura);
        }
    }

    // =========================================================================== Potions

    /** Potion HUD : liste texte, ou icônes (niveau, barre de durée, minuteur) en colonne ou en ligne. */
    public static final class Potions extends HudModule {
        public static final String[] STYLES = {"List", "Compact", "Timer", "Tiles"};
        public static final String[] LAYOUTS = {"Vertical", "Horizontal"};
        private final Map<Holder<MobEffect>, Integer> maxDur = new HashMap<>();

        public Potions() {
            super("potions", "Potion HUD", Icons.FLASK, true, 0.99f, 0.30f, Potions::lines);
            withSettings(() -> List.of(
                    new Rows.Choice("Style", STYLES, () -> (int) opt("style", 1), i -> set("style", i)),
                    new Rows.Choice("Layout", LAYOUTS, () -> (int) opt("layout", 0), i -> set("layout", i)),
                    new Rows.Toggle("Duration bar", "Empties with the time left", () -> flag("bar", true), v -> set("bar", v)),
                    new Rows.Toggle("Level on icon", "Roman numeral, top right", () -> flag("level", true), v -> set("level", v)),
                    new Rows.Toggle("Effect name", "Shown next to the icon", () -> flag("names", true), v -> set("names", v)),
                    new Rows.Toggle("Hide vanilla effects", "Removes the icons in the top right corner", () -> flag("hideVanilla", true), v -> set("hideVanilla", v)),
                    new Rows.Toggle("Blink near the end", "The last 10 seconds", () -> flag("blink", true), v -> set("blink", v)),
                    new Rows.Toggle("Infinite effects", "Beacon, permanent effects", () -> flag("infinite", true), v -> set("infinite", v))));
        }

        private static List<String> lines() {
            LocalPlayer p = mc().player;
            if (p == null || p.getActiveEffects().isEmpty()) return null;
            List<String> out = new ArrayList<>();
            for (MobEffectInstance e : p.getActiveEffects()) {
                String name = e.getEffect().value().getDisplayName().getString();
                if (e.getAmplifier() > 0) name += " " + roman(e.getAmplifier() + 1);
                out.add(name + "|" + time(e));
            }
            if (out.size() == 1) out.add(0, "Effects|" + out.size());
            return out;
        }

        static String roman(int n) {
            if (n <= 0 || n > 3999) return String.valueOf(n);
            int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
            String[] s = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < v.length; i++) {
                while (n >= v[i]) {
                    b.append(s[i]);
                    n -= v[i];
                }
            }
            return b.toString();
        }

        static String time(MobEffectInstance e) {
            if (e.isInfiniteDuration()) return "∞";
            int s = e.getDuration() / 20;
            return String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60);
        }

        private List<MobEffectInstance> effects(boolean preview) {
            LocalPlayer p = mc().player;
            List<MobEffectInstance> out = new ArrayList<>();
            if (p != null) {
                for (MobEffectInstance e : p.getActiveEffects()) {
                    if (e.isInfiniteDuration() && !flag("infinite", true)) continue;
                    out.add(e);
                }
                maxDur.keySet().removeIf(h -> !p.hasEffect(h));
            }
            if (out.isEmpty() && preview) {
                out.add(new MobEffectInstance(MobEffects.RESISTANCE, 1700, 0));
                out.add(new MobEffectInstance(MobEffects.SPEED, 1300, 0));
                out.add(new MobEffectInstance(MobEffects.REGENERATION, 900, 1));
            }
            for (MobEffectInstance e : out) maxDur.merge(e.getEffect(), e.getDuration(), Math::max);
            return out;
        }

        @Override
        public void render(GuiGraphics g, boolean preview) {
            int style = (int) opt("style", 1);
            if (style == 0) {
                super.render(g, preview);
                return;
            }
            List<MobEffectInstance> list = effects(preview);
            if (list.isEmpty()) return;
            boolean horiz = opt("layout", 0) == 1;
            boolean bar = flag("bar", true), level = flag("level", true);
            // tailles d'une entrée selon le style
            int box = 20, timerW = 34, gap = 3;
            boolean names = flag("names", true) && style != 3;
            int nameW = 0;
            if (names) {
                for (MobEffectInstance e : list) nameW = Math.max(nameW, (int) Math.ceil(Draw.width(e.getEffect().value().getDisplayName().getString(), 0.8f)) + 6);
            }
            int ew, eh;
            switch (style) {
                case 1 -> { ew = box + (bar ? 4 : 0); eh = box; }
                case 2 -> { ew = box + 2 + timerW; eh = box; }
                default -> { ew = Math.max(box, timerW); eh = (bar ? 4 : 0) + box + 2 + 12; }
            }
            ew += nameW;
            int n = list.size();
            lastW = horiz ? n * ew + (n - 1) * gap : ew;
            lastH = horiz ? eh : n * eh + (n - 1) * gap;
            int x0 = px(g), y0 = py(g);
            long ms = System.currentTimeMillis();
            for (int i = 0; i < n; i++) {
                MobEffectInstance e = list.get(i);
                int x = horiz ? x0 + i * (ew + gap) : x0, y = horiz ? y0 : y0 + i * (eh + gap);
                float frac = e.isInfiniteDuration() ? 1 : Math.min(1, e.getDuration() / (float) Math.max(1, maxDur.getOrDefault(e.getEffect(), e.getDuration())));
                boolean ending = flag("blink", true) && !e.isInfiniteDuration() && e.getDuration() < 200;
                float alpha = ending ? 0.55f + 0.45f * (float) Math.abs(Math.sin(ms / 180.0)) : 1;
                int barCol = 0xFF000000 | (e.getEffect().value().isBeneficial() ? 0x5EE6A8 : 0xF2545B);
                if (names) HudKit.text(g, e.getEffect().value().getDisplayName().getString(), x + ew - nameW + 4, y + eh / 2f - 3.2f, 0.8f,
                        Draw.withAlpha(HudKit.textColor() & 0xFFFFFF, alpha));
                switch (style) {
                    case 1 -> {
                        if (bar) vbar(g, x, y, 3, box, frac, barCol);
                        icon(g, e, x + (bar ? 4 : 0), y, box, level, alpha);
                    }
                    case 2 -> {
                        icon(g, e, x, y, box, level, alpha);
                        timer(g, e, x + box + 2, y, timerW, box, alpha, bar ? frac : -1, barCol);
                    }
                    default -> {
                        int cx = x + (ew - box) / 2;
                        if (bar) hbar(g, cx, y, box, 3, frac, barCol);
                        icon(g, e, cx, y + (bar ? 4 : 0), box, level, alpha);
                        timer(g, e, x + (ew - timerW) / 2, y + (bar ? 4 : 0) + box + 2, timerW, 12, alpha, -1, barCol);
                    }
                }
            }
        }

        private void icon(GuiGraphics g, MobEffectInstance e, int x, int y, int s, boolean level, float alpha) {
            HudKit.background(g, x, y, s, s, 3);
            int c = (Math.round(255 * alpha) << 24) | 0xFFFFFF;
            g.blitSprite(RenderPipelines.GUI_TEXTURED, Gui.getMobEffectSprite(e.getEffect()), x + 1, y + 1, s - 2, s - 2, c);
            if (level) {
                String l = roman(e.getAmplifier() + 1);
                HudKit.text(g, l, x + s - 1.5f - Draw.width(l, 0.62f), y + 1.2f, 0.62f, HudKit.textColor());
            }
        }

        private void timer(GuiGraphics g, MobEffectInstance e, int x, int y, int w, int h, float alpha, float frac, int barCol) {
            HudKit.background(g, x, y, w, h, 3);
            String t = time(e);
            HudKit.text(g, t, x + w / 2f - Draw.width(t, 0.8f) / 2, y + h / 2f - 3.2f, 0.8f, Draw.withAlpha(HudKit.textColor() & 0xFFFFFF, alpha));
            if (frac >= 0) hbar(g, x + 3, y + h - 3, w - 6, 1.5f, frac, barCol);
        }

        private static void vbar(GuiGraphics g, float x, float y, float w, float h, float f, int col) {
            Draw.rrect(g, x, y, w, h, 1, 0x40000000);
            float fh = Math.max(1, h * f);
            Draw.rrect(g, x, y + h - fh, w, fh, 1, col);
        }

        private static void hbar(GuiGraphics g, float x, float y, float w, float h, float f, int col) {
            Draw.rrect(g, x, y, w, h, 0.75f, 0x40000000);
            Draw.rrect(g, x, y, Math.max(1, w * f), h, 0.75f, col);
        }
    }
}
