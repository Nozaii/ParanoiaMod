package gg.aurora;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Ré-encode un GIF animé pour qu'il tienne dans une taille donnée (messages : 1 Mo) : réduit les côtés,
 * puis espace les images si besoin, en gardant la durée totale de l'animation.
 */
public final class GifEncoder {
    private GifEncoder() {}

    /** GIF ≤ maxBytes, ou null si impossible même petit. delays en ms. */
    public static byte[] fit(CapeImport.Frames f, int maxSide, int maxBytes) throws IOException {
        int side = Math.min(maxSide, Math.max(f.w(), f.h())), step = 1;
        for (int attempt = 0; attempt < 7; attempt++) {
            double k = Math.min(1.0, side / (double) Math.max(f.w(), f.h()));
            int w = Math.max(1, (int) Math.round(f.w() * k)), h = Math.max(1, (int) Math.round(f.h() * k));
            List<int[]> frames = new ArrayList<>();
            List<Integer> delays = new ArrayList<>();
            for (int i = 0; i < f.argb().size(); i += step) {
                int[] px = w == f.w() && h == f.h() ? f.argb().get(i) : CapeImport.sample(f.argb().get(i), f.w(), f.h(), 0, 0, f.w(), f.h(), w, h);
                int d = 0;
                for (int j = i; j < Math.min(f.argb().size(), i + step); j++) d += f.delays()[j];
                frames.add(px);
                delays.add(Math.max(20, d));
            }
            byte[] out = encode(w, h, frames, delays);
            if (out.length <= maxBytes) return out;
            // d'abord plus petit, puis une image sur deux
            // d'abord un peu plus petit, puis une image sur deux, en alternant (garde l'animation fluide)
            if (attempt % 2 == 0) side = (int) (side * 0.82);
            else step *= 2;
        }
        return null;
    }

    static byte[] encode(int w, int h, List<int[]> frames, List<Integer> delays) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            for (int i = 0; i < frames.size(); i++) {
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                int[] px = frames.get(i);
                // pixels transparents posés sur un fond sombre (le GIF encodé ici est opaque)
                int[] rgb = new int[px.length];
                for (int p = 0; p < px.length; p++) rgb[p] = (px[p] >>> 24) < 128 ? 0x15122A : px[p] & 0xFFFFFF;
                img.setRGB(0, 0, w, h, rgb, 0, w);
                IIOMetadata meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), null);
                String fmt = meta.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(fmt);
                IIOMetadataNode gce = node(root, "GraphicControlExtension");
                gce.setAttribute("disposalMethod", "none");
                gce.setAttribute("userInputFlag", "FALSE");
                gce.setAttribute("transparentColorFlag", "FALSE");
                gce.setAttribute("delayTime", String.valueOf(Math.max(2, delays.get(i) / 10)));
                gce.setAttribute("transparentColorIndex", "0");
                if (i == 0) {
                    IIOMetadataNode apps = node(root, "ApplicationExtensions");
                    IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
                    app.setAttribute("applicationID", "NETSCAPE");
                    app.setAttribute("authenticationCode", "2.0");
                    app.setUserObject(new byte[]{1, 0, 0}); // boucle infinie
                    apps.appendChild(app);
                }
                meta.setFromTree(fmt, root);
                writer.writeToSequence(new IIOImage(img, null, meta), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static IIOMetadataNode node(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(name)) return (IIOMetadataNode) root.item(i);
        }
        IIOMetadataNode n = new IIOMetadataNode(name);
        root.appendChild(n);
        return n;
    }
}
