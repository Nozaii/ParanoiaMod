package gg.aurora.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gg.aurora.Config;
import gg.aurora.Settings;
import gg.aurora.module.HudModule;
import gg.aurora.module.Modules;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Partage du layout HUD : un code court (JSON compressé en base64) qui contient la position, la taille,
 * le style et les réglages de chaque widget, plus le style global du HUD.
 */
public final class HudLayout {
    private HudLayout() {}

    private static final String PREFIX = "PHUD1:";
    public static String status = "";

    /** Code du layout actuel, aussi copié dans le presse-papier. */
    public static String export() {
        JsonObject root = new JsonObject();
        root.addProperty("s", Settings.hudStyle.ordinal());
        root.addProperty("o", round(Settings.hudOpacity));
        root.addProperty("sh", Settings.hudShadow);
        root.addProperty("ai", Settings.hudAccentIcons);
        JsonArray w = new JsonArray();
        for (HudModule h : Modules.hudModules()) {
            if (!h.enabled) continue;
            JsonObject o = new JsonObject();
            o.addProperty("i", h.id);
            o.addProperty("x", round(h.x));
            o.addProperty("y", round(h.y));
            if (h.scale != 1f) o.addProperty("k", round(h.scale));
            int flags = (h.showIcon ? 1 : 0) | (h.showLabel ? 2 : 0) | (h.showBg ? 4 : 0);
            if (flags != 7) o.addProperty("f", flags);
            if (h.colorMode != 0) o.addProperty("c", h.colorMode);
            if (!h.opts.isEmpty()) {
                JsonObject opts = new JsonObject();
                h.opts.forEach((k, v) -> opts.addProperty(k, v));
                o.add("p", opts);
            }
            w.add(o);
        }
        root.add("w", w);
        String code = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(deflate(root.toString().getBytes(StandardCharsets.UTF_8)));
        Minecraft.getInstance().keyboardHandler.setClipboard(code);
        status = "Code copied to clipboard (" + w.size() + " widgets)";
        Toasts.push(Icons.CHECK, "HUD layout exported", "Code copied to clipboard", false);
        return code;
    }

    /** Applique un code ; renvoie null si tout va bien, sinon un message d'erreur clair. */
    public static String importCode(String raw) {
        String code = raw == null ? "" : raw.trim();
        if (code.isEmpty()) return fail("Paste a layout code first");
        if (!code.startsWith(PREFIX)) return fail("Invalid code: it must start with « " + PREFIX + " »");
        JsonObject root;
        try {
            byte[] data = inflate(Base64.getUrlDecoder().decode(code.substring(PREFIX.length())));
            JsonElement e = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
            if (!e.isJsonObject()) return fail("Invalid code: unreadable content");
            root = e.getAsJsonObject();
            if (!root.has("w") || !root.get("w").isJsonArray()) return fail("Invalid code: no widget inside");
        } catch (IllegalArgumentException | DataFormatException ex) {
            return fail("Invalid code: extra characters or cut off");
        } catch (RuntimeException ex) {
            return fail("Invalid code: unreadable content");
        }
        Map<String, HudModule> byId = new HashMap<>();
        for (HudModule h : Modules.hudModules()) byId.put(h.id, h);
        int applied = 0, unknown = 0;
        try {
            if (root.has("s")) {
                int s = root.get("s").getAsInt();
                if (s >= 0 && s < Settings.HudStyle.values().length) Settings.hudStyle = Settings.HudStyle.values()[s];
            }
            if (root.has("o")) Settings.hudOpacity = clamp(root.get("o").getAsFloat(), 0.2f, 1f);
            if (root.has("sh")) Settings.hudShadow = root.get("sh").getAsBoolean();
            if (root.has("ai")) Settings.hudAccentIcons = root.get("ai").getAsBoolean();
            for (HudModule h : Modules.hudModules()) h.enabled = false;
            for (JsonElement e : root.getAsJsonArray("w")) {
                JsonObject o = e.getAsJsonObject();
                HudModule h = byId.get(o.get("i").getAsString());
                if (h == null) {
                    unknown++;
                    continue;
                }
                h.enabled = true;
                h.x = clamp(o.get("x").getAsFloat(), 0, 1);
                h.y = clamp(o.get("y").getAsFloat(), 0, 1);
                h.scale = o.has("k") ? clamp(o.get("k").getAsFloat(), 0.5f, 2f) : 1f;
                int f = o.has("f") ? o.get("f").getAsInt() : 7;
                h.showIcon = (f & 1) != 0;
                h.showLabel = (f & 2) != 0;
                h.showBg = (f & 4) != 0;
                h.colorMode = o.has("c") ? Math.max(0, Math.min(2, o.get("c").getAsInt())) : 0;
                if (o.has("p") && o.get("p").isJsonObject()) {
                    for (var en : o.getAsJsonObject("p").entrySet()) {
                        if (en.getValue().isJsonPrimitive() && en.getValue().getAsJsonPrimitive().isNumber()) h.opts.put(en.getKey(), en.getValue().getAsDouble());
                    }
                }
                applied++;
            }
        } catch (RuntimeException ex) {
            return fail("Invalid code: damaged data");
        }
        Config.save();
        status = applied + " widgets applied" + (unknown > 0 ? " (" + unknown + " unknown ignored)" : "");
        Toasts.push(Icons.CHECK, "HUD layout imported", status, false);
        return null;
    }

    private static String fail(String msg) {
        status = msg;
        Toasts.push(Icons.INFO, "Import failed", msg, false);
        return msg;
    }

    private static float round(float v) {
        return Math.round(v * 1000) / 1000f;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static byte[] deflate(byte[] in) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION, true);
        d.setInput(in);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        d.end();
        return out.toByteArray();
    }

    private static byte[] inflate(byte[] in) throws DataFormatException {
        Inflater i = new Inflater(true);
        i.setInput(in);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int guard = 0;
        while (!i.finished()) {
            int n = i.inflate(buf);
            if (n == 0 && (i.needsInput() || i.needsDictionary())) throw new DataFormatException("truncated");
            out.write(buf, 0, n);
            if (++guard > 4096) throw new DataFormatException("too large");
        }
        i.end();
        return out.toByteArray();
    }
}
