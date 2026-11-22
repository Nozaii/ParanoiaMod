package gg.aurora.nametag;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import gg.aurora.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Dégradé animé d'un nametag : 1 à 4 couleurs, un mode d'animation et une vitesse. */
public record NametagStyle(int[] colors, String mode, float speed, boolean logo) {
    public static final String[] MODES = {"static", "scroll", "pulse", "wave"};
    public static final String[] MODE_LABELS = {"Static", "Scrolling", "Pulse", "Wave"};

    public record Preset(String name, String colors) {}

    public static final Preset[] PRESETS = {
            new Preset("Black → White", "1C1C1C,FFFFFF"),
            new Preset("Paranoia", "9D7CFF,F27BD1"),
            new Preset("Feu", "FF3D2E,FFB23D,FFF1A8"),
            new Preset("Ocean", "1E3CFF,3FD7FF,B8FFF5"),
            new Preset("Mint", "0F9D6B,5EE6A8,E8FFF4"),
            new Preset("Rainbow", "FF4D4D,FFD34D,4DFF88,4DA6FF"),
            new Preset("Blood", "3A0008,D1102A,FF6A6A"),
            new Preset("Or", "8A5A00,FFD45E,FFF6D1"),
    };

    /** Style du joueur local, depuis les paramètres. */
    public static NametagStyle own() {
        return new NametagStyle(parseColors(Settings.nametagColors), Settings.nametagMode, Settings.nametagSpeed, Settings.nametagLogo);
    }

    public static int[] parseColors(String csv) {
        List<Integer> out = new ArrayList<>();
        for (String p : csv.split(",")) {
            try {
                if (p.trim().length() == 6) out.add(Integer.parseInt(p.trim(), 16));
            } catch (NumberFormatException ignored) {
            }
        }
        if (out.isEmpty()) out.add(0xFFFFFF);
        while (out.size() > 4) out.remove(out.size() - 1);
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    public static String toCsv(int[] colors) {
        StringBuilder sb = new StringBuilder();
        for (int c : colors) {
            if (!sb.isEmpty()) sb.append(',');
            sb.append(String.format(Locale.ROOT, "%06X", c & 0xFFFFFF));
        }
        return sb.toString();
    }

    public int modeIndex() {
        for (int i = 0; i < MODES.length; i++) if (MODES[i].equals(mode)) return i;
        return 1;
    }

    /** Couleur au point t (0..1) d'un texte, à l'instant time (secondes). */
    public int colorAt(float t, float time) {
        float phase = time * speed;
        float u;
        switch (mode) {
            case "static" -> u = t;
            case "pulse" -> u = 0.5f + 0.5f * (float) Math.sin(phase * 2.2f);
            case "wave" -> u = 0.5f + 0.5f * (float) Math.sin(t * 6.283f - phase * 3f);
            default -> {
                // défilement : aller-retour continu pour boucler sans saut
                float v = (t * 0.6f + phase * 0.35f) % 2f;
                if (v < 0) v += 2f;
                u = v > 1f ? 2f - v : v;
            }
        }
        return sample(u);
    }

    public int sample(float u) {
        if (colors.length == 1) return colors[0];
        u = Math.max(0f, Math.min(1f, u)) * (colors.length - 1);
        int i = Math.min(colors.length - 2, (int) Math.floor(u));
        return lerp(colors[i], colors[i + 1], u - i);
    }

    private static int lerp(int a, int b, float t) {
        int ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255;
        int br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return (Math.round(ar + (br - ar) * t) << 16) | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (int c : colors) arr.add(String.format(Locale.ROOT, "%06X", c & 0xFFFFFF));
        o.add("colors", arr);
        o.addProperty("mode", mode);
        o.addProperty("speed", speed);
        o.addProperty("logo", logo);
        return o;
    }

    public static NametagStyle fromJson(JsonObject o) {
        List<Integer> cs = new ArrayList<>();
        if (o.has("colors")) for (var e : o.getAsJsonArray("colors")) {
            try {
                cs.add(Integer.parseInt(e.getAsString(), 16));
            } catch (Exception ignored) {
            }
        }
        if (cs.isEmpty()) return null;
        String mode = o.has("mode") ? o.get("mode").getAsString() : "scroll";
        float speed = o.has("speed") ? o.get("speed").getAsFloat() : 1f;
        boolean logo = !o.has("logo") || o.get("logo").getAsBoolean();
        return new NametagStyle(cs.stream().mapToInt(Integer::intValue).toArray(), mode, speed, logo);
    }
}
