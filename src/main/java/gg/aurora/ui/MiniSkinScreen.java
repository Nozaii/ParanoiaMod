package gg.aurora.ui;

import gg.aurora.Capes;
import gg.aurora.Config;
import gg.aurora.Settings;
import gg.aurora.cosmetic.Cosmetics;
import gg.aurora.cosmetic.MiniSkinPet;
import gg.aurora.nametag.NametagSync;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Familier « Mini skin » : aperçu 3D à gauche (glisser pour tourner), à droite le pseudo, la taille,
 * l'inventaire (armure et mains) et les cosmétiques du mini.
 */
public class MiniSkinScreen extends SubScreen {
    private record Slot(String label, Supplier<String> get, Consumer<String> set) {}

    private record Cycle(String label, List<String[]> options, Supplier<String> get, Consumer<String> set) {}

    private final RowPanel panel;
    private final List<Slot> slots = List.of(
            new Slot("Helmet", () -> Settings.miniHelmet, v -> Settings.miniHelmet = v),
            new Slot("Chest", () -> Settings.miniChest, v -> Settings.miniChest = v),
            new Slot("Legs", () -> Settings.miniLegs, v -> Settings.miniLegs = v),
            new Slot("Boots", () -> Settings.miniBoots, v -> Settings.miniBoots = v),
            new Slot("Main hand", () -> Settings.miniMain, v -> Settings.miniMain = v),
            new Slot("Off hand", () -> Settings.miniOff, v -> Settings.miniOff = v));
    private final List<Cycle> cycles = new ArrayList<>();
    private final Spring[] slotHover = new Spring[6];
    private float yaw = 25, yawVel;
    private boolean turning;
    private double lastDragX;
    // géométrie (calculée au rendu, réutilisée pour les clics)
    private float cardX, cardY, cardW, cardH, colX, colW, slotY, slotSize, slotGap, cycleY;

    public MiniSkinScreen(Screen parent) {
        super(parent, "Mini skin");
        for (int i = 0; i < slotHover.length; i++) slotHover[i] = new Spring(0, 320, 1f);
        panel = new RowPanel(() -> dt, new RowPanel.Callbacks() {
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
        panel.setRows(List.of(
                new Rows.TextField("Username", "Empty = your own skin", 16, () -> Settings.petSkin,
                        v -> Settings.petSkin = v.replaceAll("[^A-Za-z0-9_]", "")),
                new Rows.Slider("Head size", null, 1.0, 1.8, 0.05, () -> Settings.miniHead, v -> Settings.miniHead = v,
                        v -> Math.round(v * 100) + " %"),
                new Rows.Slider("Size", null, 0.25, 0.7, 0.05, () -> Settings.miniSize, v -> Settings.miniSize = v,
                        v -> Math.round(v * 100) + " %")));
        List<String[]> capes = new ArrayList<>();
        for (Capes.Cape c : Capes.ALL) capes.add(new String[]{c.id(), c.name()});
        cycles.add(new Cycle("Cape", capes, () -> Settings.miniCape, v -> Settings.miniCape = v));
        cycles.add(new Cycle("Wings", of(Cosmetics.WINGS), () -> Settings.miniWings, v -> Settings.miniWings = v));
        cycles.add(new Cycle("Head", of(Cosmetics.HALOS), () -> Settings.miniHalo, v -> Settings.miniHalo = v));
        cycles.add(new Cycle("Back", of(Cosmetics.BACKS), () -> Settings.miniBack, v -> Settings.miniBack = v));
    }

    private static List<String[]> of(List<Cosmetics.Item> items) {
        List<String[]> out = new ArrayList<>();
        for (Cosmetics.Item i : items) out.add(new String[]{i.id(), i.name()});
        return out;
    }

    @Override
    protected float maxW() {
        return 480;
    }

    @Override
    protected float maxH() {
        return 290;
    }

    private static ItemStack stack(String id) {
        Identifier key = id == null || id.isBlank() ? null : Identifier.tryParse(id);
        return key == null ? ItemStack.EMPTY : BuiltInRegistries.ITEM.getOptional(key).map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    @Override
    protected void renderPage(GuiGraphics g, int mx, int my, float a) {
        Draw.centered(g, "Mini skin", wx + ww / 2, wy + 11, 1.1f, Draw.withAlpha(Theme.TEXT, a));

        // ---- aperçu
        cardX = wx + 10;
        cardY = wy + 34;
        cardW = Math.min(150, ww * 0.33f);
        cardH = wh - 44;
        Draw.glass(g, cardX, cardY, cardW, cardH, 8, Draw.withAlpha(Theme.ROW, 0.9f * a), Draw.withAlpha(0xFFFFFF, 0.14f * a));
        if (!turning) {
            // reprend doucement sa rotation lente quand on lâche
            yawVel += (14 - yawVel) * Math.min(1, dt * 2);
            yaw += yawVel * dt;
        }
        MiniSkinPet.Mini mini = MiniSkinPet.preview();
        if (mini == null) {
            Draw.centered(g, "Join a world", cardX + cardW / 2, cardY + cardH / 2 - 8, 0.9f, Draw.withAlpha(Theme.TEXT, a));
            Draw.centered(g, "to see the preview", cardX + cardW / 2, cardY + cardH / 2 + 3, 0.78f, Draw.withAlpha(Theme.TEXT_DIM, a));
        } else if (a > 0.6f) {
            renderMini(g, mini, Math.round(cardX + 4), Math.round(cardY + 4), Math.round(cardX + cardW - 4), Math.round(cardY + cardH - 30));
        }
        String name = Settings.petSkin.isBlank() ? minecraft.getUser().getName() : Settings.petSkin;
        boolean missing = !Settings.petSkin.isBlank() && MiniSkinPet.missing(Settings.petSkin);
        Draw.centered(g, name, cardX + cardW / 2, cardY + cardH - 25, 0.95f, Draw.withAlpha(Theme.TEXT, a));
        Draw.centered(g, missing ? "Unknown username" : "Drag to rotate", cardX + cardW / 2, cardY + cardH - 13, 0.72f,
                Draw.withAlpha(missing ? 0xFF7A7A : Theme.TEXT_DIM, a));
        if (inside(mx, my, cardX, cardY, cardW, cardH - 30) && mini != null) pointer = true;

        // ---- réglages
        colX = cardX + cardW + 8;
        colW = wx + ww - 10 - colX;
        float panelH = 100;
        panel.render(g, colX - 6, cardY - 6, colW + 12, panelH, mx, my, a);

        // ---- inventaire
        float y = cardY + panelH - 2;
        Draw.text(g, "INVENTORY", colX + 2, y, 0.68f, Draw.withAlpha(Theme.TEXT_DIM, a));
        String note = "Gear and cosmetics: on your screen only";
        Draw.text(g, note, colX + colW - 2 - Draw.width(note, 0.68f), y, 0.68f, Draw.withAlpha(Theme.TEXT_DIM, 0.8f * a));
        slotY = y + 10;
        slotGap = 5;
        slotSize = Math.min(28, (colW - slotGap * 5) / 6);
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            float sx = colX + i * (slotSize + slotGap);
            boolean hv = inside(mx, my, sx, slotY, slotSize, slotSize);
            slotHover[i].update(dt, hv ? 1 : 0);
            float h = slotHover[i].value;
            pointer |= hv;
            ItemStack st = stack(s.get().get());
            Draw.glass(g, sx, slotY, slotSize, slotSize, 6, Draw.withAlpha(Draw.lerpColor(Theme.ROW, Theme.ROW_HOVER, h), 0.95f * a),
                    Draw.withAlpha(st.isEmpty() ? 0xFFFFFF : Theme.accent(), (st.isEmpty() ? 0.12f : 0.55f) * a + 0.2f * h * a));
            if (st.isEmpty()) {
                Draw.centered(g, "+", sx + slotSize / 2, slotY + slotSize / 2 - 4.5f, 1.2f, Draw.withAlpha(Theme.TEXT_DIM, (0.6f + 0.4f * h) * a));
            } else if (a > 0.6f) {
                float k = (slotSize - 10) / 16f;
                g.pose().pushMatrix();
                g.pose().translate(sx + 5, slotY + 5);
                g.pose().scale(k, k);
                g.renderItem(st, 0, 0);
                g.pose().popMatrix();
                // croix : vider l'emplacement
                boolean xh = inside(mx, my, sx + slotSize - 10, slotY, 10, 10);
                if (hv) {
                    Draw.rrect(g, sx + slotSize - 10, slotY, 10, 10, 5, Draw.withAlpha(xh ? 0xD0384A : 0x0B0A12, 0.92f * a));
                    Draw.centered(g, "x", sx + slotSize - 5, slotY + 1.2f, 0.75f, Draw.withAlpha(0xFFFFFF, a));
                }
            }
            Draw.centered(g, s.label(), sx + slotSize / 2, slotY + slotSize + 3, 0.62f, Draw.withAlpha(Theme.TEXT_DIM, a));
        }

        // ---- cosmétiques
        y = slotY + slotSize + 15;
        Draw.text(g, "COSMETICS", colX + 2, y, 0.68f, Draw.withAlpha(Theme.TEXT_DIM, a));
        cycleY = y + 10;
        float cw = (colW - 6) / 2;
        for (int i = 0; i < cycles.size(); i++) {
            Cycle c = cycles.get(i);
            float cx = colX + (i % 2) * (cw + 6), cy = cycleY + (i / 2) * 26;
            boolean hv = inside(mx, my, cx, cy, cw, 22);
            pointer |= hv;
            Draw.rrect(g, cx, cy, cw, 22, 6, Draw.withAlpha(hv ? Theme.ROW_HOVER : Theme.ROW, 0.9f * a));
            Draw.text(g, c.label(), cx + 8, cy + 7, 0.85f, Draw.withAlpha(Theme.TEXT_DIM, a));
            String cur = c.get().get(), shown = "None";
            for (String[] o : c.options()) if (o[0].equals(cur)) shown = o[1];
            float vx = cx + 44, vw = cw - 44 - 4;
            boolean lh = hv && mx < vx + 14, rh = hv && mx >= cx + cw - 16;
            Draw.centered(g, "<", vx + 6, cy + 6.5f, 0.95f, Draw.withAlpha(lh ? Theme.accent() : Theme.TEXT_DIM, a));
            Draw.centered(g, ">", cx + cw - 9, cy + 6.5f, 0.95f, Draw.withAlpha(rh ? Theme.accent() : Theme.TEXT_DIM, a));
            float sc = Math.min(0.9f, (vw - 26) / Math.max(1, Draw.width(shown, 1f)));
            Draw.centered(g, shown, vx + vw / 2, cy + 11 - 4 * sc, sc, Draw.withAlpha("None".equals(shown) ? Theme.TEXT_DIM : Theme.TEXT, a));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void renderMini(GuiGraphics g, MiniSkinPet.Mini mini, int x0, int y0, int x1, int y1) {
        EntityRenderer renderer = minecraft.getEntityRenderDispatcher().getRenderer(mini.entity());
        EntityRenderState state = renderer.createRenderState(mini.entity(), 1f);
        state.lightCoords = 15728880;
        state.shadowPieces.clear();
        state.outlineColor = 0;
        state.nameTag = null;
        state.distanceToCameraSq = 0;
        float tall = 1.8f;
        if (state instanceof AvatarRenderState as) {
            as.bodyRot = 180f + yaw;
            as.yRot = 0;
            as.xRot = 0;
            as.scoreText = null;
            as.ageInTicks = time() * 20;
            as.walkAnimationSpeed = 0;
            // le modèle garde sa taille de mini : on cadre sur sa vraie hauteur (la tête agrandie dépasse)
            tall = (1.8f + 0.5f * ((float) Settings.miniHead - 1)) * as.scale;
        }
        Quaternionf rot = new Quaternionf().rotateZ((float) Math.PI);
        Quaternionf cam = new Quaternionf().rotateX((float) Math.toRadians(8));
        rot.mul(cam);
        float scale = (y1 - y0) / (tall * 1.45f);
        g.submitEntityRenderState(state, scale, new Vector3f(0, tall / 2f + 0.0625f, 0), rot, cam, x0, y0, x1, y1);
    }

    private void changed() {
        Config.save();
        gg.aurora.nametag.NametagSync.markDirty(); // les amis voient la taille, la tête et l'équipement du mini
        NametagSync.markDirty();
    }

    @Override
    protected boolean clickPage(double mx, double my) {
        if (panel.click(mx, my)) return true;
        if (inside(mx, my, cardX, cardY, cardW, cardH - 30)) {
            turning = true;
            lastDragX = mx;
            yawVel = 0;
            return true;
        }
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            float sx = colX + i * (slotSize + slotGap);
            if (!inside(mx, my, sx, slotY, slotSize, slotSize)) continue;
            if (!s.get().get().isBlank() && inside(mx, my, sx + slotSize - 10, slotY, 10, 10)) {
                s.set().accept("");
                click(0.8f);
                changed();
                return true;
            }
            click(1.1f);
            minecraft.setScreen(new ItemPickerScreen(this, s.get().get(), id -> {
                s.set().accept(id);
                changed();
            }));
            return true;
        }
        float cw = (colW - 6) / 2;
        for (int i = 0; i < cycles.size(); i++) {
            Cycle c = cycles.get(i);
            float cx = colX + (i % 2) * (cw + 6), cy = cycleY + (i / 2) * 26;
            if (!inside(mx, my, cx, cy, cw, 22)) continue;
            // ordre : aucun, puis chaque choix ; la moitié gauche recule, la droite avance
            int n = c.options().size() + 1, at = 0;
            for (int k = 0; k < c.options().size(); k++) if (c.options().get(k)[0].equals(c.get().get())) at = k + 1;
            at = Math.floorMod(at + (mx < cx + 44 + 14 ? -1 : 1), n);
            c.set().accept(at == 0 ? "" : c.options().get(at - 1)[0]);
            click(1.15f);
            changed();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (turning) {
            yaw += (float) (event.x() - lastDragX) * 1.2f;
            lastDragX = event.x();
            return true;
        }
        return panel.drag(event.x(), event.y()) || super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        turning = false;
        if (panel.release()) changed();
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        panel.scroll(mx, my, sy);
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (event.isAllowedChatCharacter() && panel.charTyped(event.codepointAsString())) return true;
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (panel.focused() != null && panel.keyPressed(event.key(), event.modifiers())) return true;
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        NametagSync.markDirty();
        super.onClose();
    }
}
