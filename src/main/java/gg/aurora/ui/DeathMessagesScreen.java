package gg.aurora.ui;

import gg.aurora.Config;
import gg.aurora.module.AutoGG;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.locale.Language;

import java.util.ArrayList;
import java.util.List;

/** Auto GG: every kill message of the game, by category, each with its own switch. */
public class DeathMessagesScreen extends SubScreen {
    private final RowPanel panel;
    private int section;

    public DeathMessagesScreen(Screen parent) {
        super(parent, "Kill messages");
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
        show(0);
    }

    private void show(int i) {
        section = i;
        List<Rows.Row> rows = new ArrayList<>();
        Language lang = Language.getInstance();
        for (String key : AutoGG.DEATHS[i].keys()) {
            String text = lang.getOrDefault(key).replace("%1$s", "Victim").replace("%2$s", "You").replace("%3$s", "[Sword]");
            rows.add(new Rows.Toggle(text, null, () -> AutoGG.deathEnabled(key), v -> AutoGG.setDeath(key, v)));
        }
        panel.setRows(rows);
    }

    @Override
    protected float maxW() {
        return 560;
    }

    @Override
    protected void renderPage(GuiGraphics g, int mx, int my, float a) {
        Draw.text(g, "Kill messages", wx + 8 + Draw.width("Back", 1f) + 36, wy + 11, 1.05f, Draw.withAlpha(Theme.TEXT, a));
        Draw.rrect(g, wx + 8, wy + 28, ww - 16, 0.6f, 0.3f, Draw.withAlpha(0xFFFFFF, 0.07f * a));
        float sx = wx + 10, sy = wy + 36, sw = 112, rh = 18;
        for (int i = 0; i < AutoGG.DEATHS.length; i++) {
            float y = sy + i * (rh + 2);
            boolean on = i == section, hv = inside(mx, my, sx, y, sw, rh);
            pointer |= hv && !on;
            if (on || hv) Draw.rrect(g, sx, y, sw, rh, 3, Draw.withAlpha(on ? Theme.ROW_HOVER : Theme.ROW, 0.9f * a));
            if (on) Draw.rrect(g, sx, y + 4, 1.5f, rh - 8, 0.75f, Draw.withAlpha(Theme.accent(), a));
            Draw.text(g, AutoGG.DEATHS[i].name(), sx + 8, y + 5, 0.85f, Draw.withAlpha(on ? Theme.TEXT : Theme.TEXT_DIM, a));
        }
        float cx = sx + sw + 10, cy = wy + 36, cw = wx + ww - 10 - cx, ch = wy + wh - 10 - cy;
        panel.render(g, cx, cy, cw, ch, mx, my, a);
    }

    @Override
    protected boolean clickPage(double mx, double my) {
        float sx = wx + 10, sy = wy + 36, sw = 112, rh = 18;
        for (int i = 0; i < AutoGG.DEATHS.length; i++) {
            if (inside(mx, my, sx, sy + i * (rh + 2), sw, rh)) {
                if (i != section) {
                    click(1.05f);
                    show(i);
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
        panel.release();
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        panel.scroll(mx, my, sy);
        return true;
    }
}
