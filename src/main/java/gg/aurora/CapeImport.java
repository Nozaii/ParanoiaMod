package gg.aurora;

import com.mojang.blaze3d.platform.NativeImage;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Turns any picture or GIF into the cape layout, the same way tools/gen_photo_capes.py builds the photo capes:
 * the chosen part of the image becomes the front, a darker mirror the back, the elytra get the front too.
 * Animated capes are stored as a vertical strip of frames (name.png) next to their frame times (name.anim).
 */
public final class CapeImport {
    private CapeImport() {}

    /** Most frames kept from a GIF: beyond that, neighbouring frames are merged. */
    private static final int MAX_FRAMES = 160;

    /** Decoded picture: ARGB frames of w x h, and how long each one stays (ms). */
    public record Frames(int w, int h, List<int[]> argb, int[] delays) {
        public boolean animated() {
            return argb.size() > 1;
        }
    }

    public static Frames decode(Path src) throws IOException {
        String n = src.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".gif") ? decodeGif(src) : decodeStill(src);
    }

    private static Frames decodeStill(Path src) throws IOException {
        String n = src.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) {
            // Minecraft's reader only takes PNG: JPEG goes through Java's own decoder
            java.awt.image.BufferedImage bi = ImageIO.read(src.toFile());
            if (bi == null) throw new IOException("unreadable JPEG");
            int w = bi.getWidth(), h = bi.getHeight();
            if ((long) w * h > 64_000_000L) throw new IOException("image too large");
            return new Frames(w, h, List.of(bi.getRGB(0, 0, w, h, null, 0, w)), new int[]{100});
        }
        try (var in = Files.newInputStream(src); NativeImage img = NativeImage.read(in)) {
            return new Frames(img.getWidth(), img.getHeight(), List.of(img.getPixels()), new int[]{100});
        }
    }

    /** GIF already in memory (a cape front downloaded from the server). */
    public static Frames decodeGif(byte[] gif) throws IOException {
        return decodeGifFrom(new java.io.ByteArrayInputStream(gif));
    }

    private static Frames decodeGif(Path src) throws IOException {
        return decodeGifFrom(src.toFile());
    }

    /** GIF frames are often partial: each one is drawn over the previous canvas, following its disposal method. */
    private static Frames decodeGifFrom(Object source) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(source)) {
            if (in == null) throw new IOException("unreadable GIF");
            var readers = ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) throw new IOException("no GIF reader");
            ImageReader r = readers.next();
            try {
                r.setInput(in, false);
                int count = r.getNumImages(true);
                if (count <= 0) throw new IOException("empty GIF");
                int w = 0, h = 0;
                IIOMetadata stream = r.getStreamMetadata();
                if (stream != null) {
                    Node lsd = child(stream.getAsTree("javax_imageio_gif_stream_1.0"), "LogicalScreenDescriptor");
                    if (lsd != null) {
                        w = intAttr(lsd, "logicalScreenWidth", 0);
                        h = intAttr(lsd, "logicalScreenHeight", 0);
                    }
                }
                if (w <= 0 || h <= 0) {
                    w = r.getWidth(0);
                    h = r.getHeight(0);
                }
                if ((long) w * h > 4096L * 4096L) throw new IOException("GIF too large");
                int[] canvas = new int[w * h];
                List<int[]> frames = new ArrayList<>();
                List<Integer> delays = new ArrayList<>();
                for (int f = 0; f < count; f++) {
                    BufferedImage img = r.read(f);
                    Node root = r.getImageMetadata(f).getAsTree("javax_imageio_gif_image_1.0");
                    Node desc = child(root, "ImageDescriptor"), gce = child(root, "GraphicControlExtension");
                    int ox = desc == null ? 0 : intAttr(desc, "imageLeftPosition", 0), oy = desc == null ? 0 : intAttr(desc, "imageTopPosition", 0);
                    String disposal = gce == null ? "none" : gce.getAttributes().getNamedItem("disposalMethod").getNodeValue();
                    int delay = gce == null ? 10 : intAttr(gce, "delayTime", 10);
                    int[] before = "restoreToPrevious".equals(disposal) ? canvas.clone() : null;
                    int fw = img.getWidth(), fh = img.getHeight();
                    int[] px = img.getRGB(0, 0, fw, fh, null, 0, fw);
                    for (int y = 0; y < fh; y++) {
                        int cy = oy + y;
                        if (cy < 0 || cy >= h) continue;
                        for (int x = 0; x < fw; x++) {
                            int cx = ox + x, p = px[y * fw + x];
                            if (cx >= 0 && cx < w && (p >>> 24) != 0) canvas[cy * w + cx] = p;
                        }
                    }
                    frames.add(canvas.clone());
                    // browsers show 0-1 hundredths as 100 ms
                    delays.add(delay <= 1 ? 100 : delay * 10);
                    if ("restoreToBackgroundColor".equals(disposal)) {
                        for (int y = Math.max(0, oy); y < Math.min(h, oy + fh); y++) {
                            Arrays.fill(canvas, y * w + Math.max(0, ox), y * w + Math.min(w, ox + fw), 0);
                        }
                    } else if (before != null) {
                        canvas = before;
                    }
                }
                return merge(new Frames(w, h, frames, delays.stream().mapToInt(Integer::intValue).toArray()));
            } finally {
                r.dispose();
            }
        }
    }

    /** Keeps at most MAX_FRAMES frames; dropped frames give their time to the kept one before them. */
    private static Frames merge(Frames in) {
        int n = in.argb().size();
        if (n <= MAX_FRAMES) return in;
        List<int[]> out = new ArrayList<>();
        int[] delays = new int[MAX_FRAMES];
        for (int i = 0; i < n; i++) {
            int slot = (int) ((long) i * MAX_FRAMES / n);
            if (slot == out.size()) out.add(in.argb().get(i));
            delays[slot] += in.delays()[i];
        }
        return new Frames(in.w(), in.h(), out, delays);
    }

    private static Node child(Node n, String name) {
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) if (name.equals(c.getNodeName())) return c;
        return null;
    }

    private static int intAttr(Node n, String name, int def) {
        Node a = n.getAttributes().getNamedItem(name);
        try {
            return a == null ? def : Integer.parseInt(a.getNodeValue());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ------------------------------------------------------------------ building the cape layout

    /** Pixels per vanilla cape pixel: HD for a still image, less for long animations to keep memory low. */
    public static int scaleFor(int frames) {
        return frames <= 1 ? 16 : frames <= 48 ? 8 : 4;
    }

    /**
     * Builds the cape frames from the crop rectangle (source pixels, 10:16 like the cape front).
     * @return frames of 64k x 32k
     */
    public static Frames build(Frames src, int cx, int cy, int cw, int ch) {
        return build(src, cx, cy, cw, ch, scaleFor(src.argb().size()));
    }

    /** Same, at a given scale (k pixels per vanilla cape pixel). */
    public static Frames build(Frames src, int cx, int cy, int cw, int ch, int k) {
        List<int[]> out = new ArrayList<>(src.argb().size());
        for (int[] f : src.argb()) out.add(assemble(sample(f, src.w(), src.h(), cx, cy, cw, ch, 10 * k, 16 * k), k));
        return new Frames(64 * k, 32 * k, out, src.delays());
    }

    /** Box-filtered resize of a source rectangle (4x4 samples per output pixel, alpha-weighted). */
    static int[] sample(int[] src, int sw, int sh, double rx, double ry, double rw, double rh, int dw, int dh) {
        int[] out = new int[dw * dh];
        final int S = 4;
        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                long a = 0, r = 0, g = 0, b = 0;
                for (int j = 0; j < S; j++) {
                    int py = clamp((int) (ry + (y + (j + 0.5) / S) * rh / dh), sh);
                    for (int i = 0; i < S; i++) {
                        int px = clamp((int) (rx + (x + (i + 0.5) / S) * rw / dw), sw);
                        int p = src[py * sw + px], pa = p >>> 24;
                        a += pa;
                        r += (long) (p >> 16 & 255) * pa;
                        g += (long) (p >> 8 & 255) * pa;
                        b += (long) (p & 255) * pa;
                    }
                }
                if (a == 0) continue;
                out[y * dw + x] = (int) (a / (S * S)) << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
            }
        }
        return out;
    }

    private static int clamp(int v, int max) {
        return v < 0 ? 0 : v >= max ? max - 1 : v;
    }

    private static int[] assemble(int[] face, int k) {
        int W = 64 * k, H = 32 * k, FW = 10 * k, FH = 16 * k;
        int[] img = new int[W * H];
        int edge = shade(average(face), 0.35f);
        fill(img, W, 0, k, k, FH, edge);
        fill(img, W, 11 * k, k, k, FH, edge);
        fill(img, W, k, 0, FW, k, edge);
        fill(img, W, 11 * k, 0, FW, k, edge);
        paste(img, W, face, FW, FH, k, k, false, 1f);
        // back: mirrored and darker, like the inside of a cloth
        paste(img, W, face, FW, FH, 12 * k, k, true, 0.55f);
        // elytra block (22,0)-(46,22): only the vanilla wing silhouette is opaque (a full rectangle looks like a
        // plank). The visible wing face is (36,2) 10x20: it gets the front cut to 1:2, the rim gets the edge colour.
        int[] wing = sample(face, FW, FH, k, 0, 8 * k, FH, 10 * k, 20 * k);
        for (int y = 0; y < 22 * k; y++) {
            String row = ELYTRA_SHAPE[y / k];
            for (int x = 0; x < 24 * k; x++) {
                if (row.charAt(x / k) != '#') continue;
                int wx = x - 14 * k, wy = y - 2 * k;
                boolean onFace = wx >= 0 && wx < 10 * k && wy >= 0 && wy < 20 * k;
                img[y * W + 22 * k + x] = onFace ? wing[wy * 10 * k + wx] | 0xFF000000 : edge;
            }
        }
        return img;
    }

    /** Opaque pixels of the vanilla elytra texture, block (22,0)-(46,22). */
    private static final String[] ELYTRA_SHAPE = {
            ".........#########......",
            "..........##............",
            "............########....",
            "............#########...",
            ".............#########..",
            ".............#########..",
            ".............#########..",
            ".............##########.",
            ".............##########.",
            ".............##########.",
            ".............##########.",
            "#.............##########",
            "#.............##########",
            "#.............##########",
            "#.............##########",
            "#.............##########",
            "#..............#########",
            "#..............#########",
            "#..............#########",
            "#...............########",
            "#...............########",
            "#................#######"
    };

    private static void fill(int[] img, int W, int x, int y, int w, int h, int argb) {
        for (int j = y; j < y + h; j++) Arrays.fill(img, j * W + x, j * W + x + w, argb);
    }

    private static void paste(int[] img, int W, int[] src, int sw, int sh, int x, int y, boolean mirror, float light) {
        for (int j = 0; j < sh; j++) {
            for (int i = 0; i < sw; i++) {
                int p = src[j * sw + (mirror ? sw - 1 - i : i)];
                img[(y + j) * W + x + i] = light == 1f ? p : shade(p, light);
            }
        }
    }

    private static int average(int[] px) {
        long r = 0, g = 0, b = 0, n = 0;
        for (int p : px) {
            if ((p >>> 24) < 128) continue;
            r += p >> 16 & 255;
            g += p >> 8 & 255;
            b += p & 255;
            n++;
        }
        if (n == 0) return 0xFF141418;
        return 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }

    private static int shade(int p, float f) {
        return p & 0xFF000000 | Math.round((p >> 16 & 255) * f) << 16 | Math.round((p >> 8 & 255) * f) << 8 | Math.round((p & 255) * f);
    }

    // ------------------------------------------------------------------ files

    /**
     * Front of every frame of a built cape strip (the part the player chose, pasted unchanged at (k, k), 10k x 16k).
     * @param strip pixels of the cape file, frames stacked top to bottom, each w x w/2
     */
    public static Frames faces(int[] strip, int w, int[] delays) {
        int k = w / 64, fh = w / 2, n = delays.length, faceW = 10 * k, faceH = 16 * k;
        List<int[]> out = new ArrayList<>(n);
        for (int f = 0; f < n; f++) {
            int[] face = new int[faceW * faceH];
            for (int y = 0; y < faceH; y++) System.arraycopy(strip, (f * fh + k + y) * w + k, face, y * faceW, faceW);
            out.add(face);
        }
        return new Frames(faceW, faceH, out, delays.clone());
    }

    /** Writes name.png (frames stacked top to bottom) and, for an animation, name.anim with the frame times. */
    public static void save(Path folder, String slug, Frames cape) throws IOException {
        Files.createDirectories(folder);
        int n = cape.argb().size();
        try (NativeImage img = new NativeImage(cape.w(), cape.h() * n, false)) {
            for (int f = 0; f < n; f++) {
                int[] px = cape.argb().get(f);
                for (int y = 0; y < cape.h(); y++) {
                    for (int x = 0; x < cape.w(); x++) img.setPixel(x, f * cape.h() + y, px[y * cape.w() + x]);
                }
            }
            img.writeToFile(folder.resolve(slug + ".png"));
        }
        Path anim = folder.resolve(slug + ".anim");
        if (n > 1) {
            StringBuilder sb = new StringBuilder();
            for (int d : cape.delays()) sb.append(sb.isEmpty() ? "" : ",").append(d);
            Files.writeString(anim, sb.toString());
        } else {
            Files.deleteIfExists(anim);
        }
    }

    /** Frame times of an animated cape, or null for a still one. */
    public static int[] readDelays(Path folder, String pngFile) {
        Path anim = folder.resolve(pngFile.replaceAll("(?i)\\.png$", "") + ".anim");
        if (!Files.isRegularFile(anim)) return null;
        try {
            String[] parts = Files.readString(anim).trim().split(",");
            int[] d = new int[parts.length];
            for (int i = 0; i < parts.length; i++) d[i] = Math.max(20, Integer.parseInt(parts[i].trim()));
            return d;
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }
}
