package gg.aurora.ui;

import com.mojang.blaze3d.platform.NativeImage;
import gg.aurora.CapeImport;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/**
 * Picks the part of an image (or GIF) that goes on the cape front: a 10:16 frame to drag and resize
 * with the wheel, with a live preview of the front next to it.
 */
public final class CapeCropScreen extends Screen {
    private static final Identifier PREVIEW = Identifier.fromNamespaceAndPath("aurora", "cape_crop_preview");
    private static final float RATIO = 10f / 16f;

    private final Screen parent;
    private final CapeImport.Frames frames;
    private final Consumer<int[]> done;
    private final int texW, texH;
    /** Crop in source pixels. */
    private double cx, cy, cw, ch;
    private boolean dragging;
    private double grabX, grabY;
    private boolean pointer;

    // layout of the last frame, GUI units
    private float ix, iy, iw, ih;
    private float okX, okY, cancelX, cancelY;
    private static final float BW = 64, BH = 15;

    /** @param done gets {x, y, w, h} of the crop in source pixels, or is not called on cancel */
    public CapeCropScreen(Screen parent, CapeImport.Frames frames, Consumer<int[]> done) {
        super(Component.literal("Crop cape"));
        this.parent = parent;
        this.frames = frames;
        this.done = done;
        // preview texture: the first frame, at most 512 px on its long side
        float s = Math.min(1f, 512f / Math.max(frames.w(), frames.h()));
        texW = Math.max(1, Math.round(frames.w() * s));
        texH = Math.max(1, Math.round(frames.h() * s));
        NativeImage img = new NativeImage(texW, texH, false);
        int[] px = frames.argb().get(0);
        for (int y = 0; y < texH; y++) {
            for (int x = 0; x < texW; x++) img.setPixel(x, y, px[Math.min(frames.h() - 1, (int) ((y + 0.5) / s)) * frames.w() + Math.min(frames.w() - 1, (int) ((x + 0.5) / s))]);
        }
        net.minecraft.client.Minecraft.getInstance().getTextureManager().register(PREVIEW, new DynamicTexture(() -> "Paranoia cape crop", img));
        // start with the largest centred frame
        double fit = Math.min(frames.w(), frames.h() * RATIO);
        cw = fit;
        ch = fit / RATIO;
        cx = (frames.w() - cw) / 2;
        cy = (frames.h() - ch) / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        pointer = false;
        float pw = Math.min(width - 32, 420), ph = Math.min(height - 32, 270);
        float px = (width - pw) / 2, py = (height - ph) / 2;
        Draw.glass(g, px, py, pw, ph, 10, Draw.withAlpha(Theme.PANEL, 0.96f), Draw.withAlpha(0xFFFFFF, 0.08f));
        Draw.text(g, "Crop your cape", px + 12, py + 10, 1f, Draw.withAlpha(Theme.TEXT, 1f));
        String info = frames.w() + "x" + frames.h() + (frames.animated() ? " · " + frames.argb().size() + " frames" : "") + " · drag to move, scroll to zoom";
        Draw.text(g, info, px + 12, py + 22, 0.72f, Draw.withAlpha(Theme.TEXT_DIM, 1f));

        // image area on the left, preview of the front on the right
        float prevW = Math.min(90, pw * 0.25f), prevH = prevW / RATIO;
        float areaX = px + 12, areaY = py + 36, areaW = pw - 36 - prevW, areaH = ph - 36 - 30;
        float s = Math.min(areaW / frames.w(), areaH / frames.h());
        iw = frames.w() * s;
        ih = frames.h() * s;
        ix = areaX + (areaW - iw) / 2;
        iy = areaY + (areaH - ih) / 2;
        Draw.rect(g, areaX, areaY, areaW, areaH, Draw.withAlpha(0x000000, 0.35f));
        Draw.image(g, PREVIEW, ix, iy, iw, ih, texW, texH, 0, 0, texW, texH, 0x70FFFFFF);
        // crop: full brightness inside, dimmed outside
        float rx = (float) (ix + cx * s), ry = (float) (iy + cy * s), rw = (float) (cw * s), rh = (float) (ch * s);
        float u = (float) (cx * texW / frames.w()), v = (float) (cy * texH / frames.h());
        float uw = (float) (cw * texW / frames.w()), vh = (float) (ch * texH / frames.h());
        Draw.image(g, PREVIEW, rx, ry, rw, rh, texW, texH, u, v, uw, vh, 0xFFFFFFFF);
        Draw.ring(g, rx, ry, rw, rh, 0, Draw.withAlpha(Theme.accent(), 1f));
        boolean overCrop = mx >= rx && mx <= rx + rw && my >= ry && my <= ry + rh;
        pointer |= overCrop || dragging;

        float prevX = px + pw - 12 - prevW, prevY = areaY + (areaH - prevH) / 2;
        Draw.text(g, "Front", prevX, prevY - 11, 0.72f, Draw.withAlpha(Theme.TEXT_MUTED, 1f));
        Draw.image(g, PREVIEW, prevX, prevY, prevW, prevH, texW, texH, u, v, uw, vh, 0xFFFFFFFF);
        Draw.ring(g, prevX, prevY, prevW, prevH, 0, Draw.withAlpha(0xFFFFFF, 0.15f));

        okX = px + pw - 12 - BW;
        okY = py + ph - 12 - BH;
        cancelX = okX - 6 - BW;
        cancelY = okY;
        button(g, mx, my, cancelX, cancelY, "Cancel", false);
        button(g, mx, my, okX, okY, "Use", true);
        if (pointer) g.requestCursor(com.mojang.blaze3d.platform.cursor.CursorTypes.POINTING_HAND);
    }

    private void button(GuiGraphics g, int mx, int my, float x, float y, String label, boolean primary) {
        boolean hv = mx >= x && mx <= x + BW && my >= y && my <= y + BH;
        pointer |= hv;
        int bg = primary ? Theme.accent() : hv ? Theme.ROW_HOVER : Theme.ROW;
        Draw.rrect(g, x, y, BW, BH, 4, Draw.withAlpha(bg, primary && !hv ? 0.85f : 1f));
        Draw.centered(g, label, x + BW / 2, y + 4, 0.78f, Draw.withAlpha(Theme.TEXT, 1f));
    }

    private double scale() {
        return iw / frames.w();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        double mx = e.x(), my = e.y();
        if (e.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(e, doubleClick);
        if (mx >= okX && mx <= okX + BW && my >= okY && my <= okY + BH) {
            int x = (int) Math.round(cx), y = (int) Math.round(cy);
            int w = Math.max(1, Math.min(frames.w() - x, (int) Math.round(cw))), h = Math.max(1, Math.min(frames.h() - y, (int) Math.round(ch)));
            minecraft.setScreen(parent);
            done.accept(new int[]{x, y, w, h});
            return true;
        }
        if (mx >= cancelX && mx <= cancelX + BW && my >= cancelY && my <= cancelY + BH) {
            onClose();
            return true;
        }
        double s = scale();
        if (mx >= ix && mx <= ix + iw && my >= iy && my <= iy + ih) {
            dragging = true;
            double sx = (mx - ix) / s, sy = (my - iy) / s;
            // a click outside the frame moves it there first
            if (sx < cx || sx > cx + cw || sy < cy || sy > cy + ch) {
                cx = sx - cw / 2;
                cy = sy - ch / 2;
                clampCrop();
            }
            grabX = sx - cx;
            grabY = sy - cy;
            return true;
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (!dragging) return super.mouseDragged(e, dx, dy);
        double s = scale();
        cx = (e.x() - ix) / s - grabX;
        cy = (e.y() - iy) / s - grabY;
        clampCrop();
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        dragging = false;
        return super.mouseReleased(e);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (sy == 0) return false;
        double max = Math.min(frames.w(), frames.h() * RATIO), min = Math.min(max, 8);
        double w = Math.max(min, Math.min(max, cw * (sy > 0 ? 1 / 1.1 : 1.1)));
        // zoom around the frame centre
        cx += (cw - w) / 2;
        cy += (ch - w / RATIO) / 2;
        cw = w;
        ch = w / RATIO;
        clampCrop();
        return true;
    }

    private void clampCrop() {
        cx = Math.max(0, Math.min(frames.w() - cw, cx));
        cy = Math.max(0, Math.min(frames.h() - ch, cy));
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (e.key() == GLFW.GLFW_KEY_ENTER || e.key() == GLFW.GLFW_KEY_KP_ENTER) {
            return mouseClicked(new MouseButtonEvent(okX + 1, okY + 1, new net.minecraft.client.input.MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0)), false);
        }
        return super.keyPressed(e);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        minecraft.getTextureManager().release(PREVIEW);
    }
}
