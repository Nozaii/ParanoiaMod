package gg.aurora.ui;

import gg.aurora.Config;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.List;

/**
 * Écran Réglages du menu titre : sections à gauche, réglages à droite. En jeu, les mêmes sections
 * vivent dans la vue Réglages du menu RShift ({@link ClickGuiScreen}).
 */
public class SettingsScreen extends SubScreen {
    private final List<SettingsSections.Section> sections;
    private int current;
    private final Spring sideSlide = new Spring(0, 220, 0.75f);
    private final RowPanel panel;

    public SettingsScreen(net.minecraft.client.gui.screens.Screen parent) {
        super(parent, "Settings");
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
        sections = SettingsSections.build(this);
        panel.setRows(sections.get(0).rows());
    }

    public int sectionCount() {
        return sections.size();
    }

    public void debugSection(int i) {
        current = i;
        panel.setRows(sections.get(i).rows());
    }

    @Override
    protected void renderPage(GuiGraphics g, int mx, int my, float a) {
        float tx = wx + ww / 2;
        Draw.icon(g, Icons.GEAR, tx + 6 - Draw.width("Settings", 1.1f) / 2 - 9, wy + 16, 10, Draw.withAlpha(Theme.accent(), a));
        Draw.centered(g, "Settings", tx + 6, wy + 11, 1.1f, Draw.withAlpha(Theme.TEXT, a));

        float sx = wx + 10, sy = wy + 38, sw = 104, rowH = 18;
        sideSlide.update(dt, current);
        float hy = sy + sideSlide.value * (rowH + 2);
        // sélection : surface montée + barre d'accent, qui glissent ensemble
        Draw.rrect(g, sx, hy, sw, rowH, 5, Draw.withAlpha(Theme.ROW_HOVER, 0.95f * a));
        Draw.rrect(g, sx + 2, hy + 4, 2, rowH - 8, 1, Draw.withAlpha(Theme.accent(), a));
        for (int i = 0; i < sections.size(); i++) {
            SettingsSections.Section s = sections.get(i);
            float ry = sy + i * (rowH + 2);
            boolean hv = inside(mx, my, sx, ry, sw, rowH);
            pointer |= hv && i != current;
            int col = i == current ? Theme.TEXT : hv ? Draw.lerpColor(Theme.TEXT_DIM, Theme.TEXT, 0.6f) : Theme.TEXT_DIM;
            Draw.icon(g, s.icon(), sx + 13, ry + rowH / 2, 8, Draw.withAlpha(i == current ? Theme.accent() : col, a));
            Draw.text(g, s.name(), sx + 23, ry + 5.2f, 0.88f, Draw.withAlpha(col, a));
        }

        float cx = sx + sw + 8, cy = wy + 36, cw = wx + ww - 10 - cx, ch = wy + wh - 10 - cy;
        panel.render(g, cx, cy, cw, ch, mx, my, a);
    }

    @Override
    protected boolean clickPage(double mx, double my) {
        float sx = wx + 10, sy = wy + 38, sw = 104, rowH = 18;
        for (int i = 0; i < sections.size(); i++) {
            if (inside(mx, my, sx, sy + i * (rowH + 2), sw, rowH)) {
                if (i != current) {
                    click(1.05f);
                    debugSection(i);
                }
                return true;
            }
        }
        return panel.click(mx, my);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return panel.drag(event.x(), event.y()) || super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (panel.release()) Config.save();
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
        if ((panel.focused() != null || Rows.KeyBind.listening != null) && panel.keyPressed(event.key(), event.modifiers())) return true;
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        SettingsSections.closed();
        super.onClose();
    }
}
