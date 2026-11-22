package gg.aurora.nametag;

import gg.aurora.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import java.util.Optional;
import java.util.UUID;

/**
 * Applique le dégradé animé au pseudo dans un nom affiché (nametag, liste Tab), en gardant
 * le reste du texte (préfixes de grade, couleurs d'équipe), et ajoute le logo Paranoia devant
 * les joueurs qui ont le mod.
 */
public final class NameStyler {
    private NameStyler() {}

    private static final FontDescription LOGO_FONT = new FontDescription.Resource(Identifier.fromNamespaceAndPath("aurora", "logo"));
    private static final long START = System.nanoTime();

    public static float time() {
        return (System.nanoTime() - START) / 1e9f;
    }

    /** Glyphe du logo (police aurora:logo) suivi d'une espace. */
    public static MutableComponent logo() {
        // l'espace doit rester dans la police normale : un enfant hérite sinon de la police du logo
        return Component.empty()
                .append(Component.literal("\uE000").withStyle(Style.EMPTY.withFont(LOGO_FONT).withColor(0xFFFFFF)))
                .append(Component.literal(" "));
    }

    /** Style à appliquer pour ce joueur, ou null. */
    public static NametagStyle styleFor(UUID id) {
        Minecraft mc = Minecraft.getInstance();
        if (id == null) return null;
        if (id.equals(mc.getUser().getProfileId())) return Settings.nametagEnabled ? NametagStyle.own() : null;
        return Settings.showOthers ? NametagSync.get(id) : null;
    }

    public static boolean hasMod(UUID id) {
        if (id == null) return false;
        if (id.equals(Minecraft.getInstance().getUser().getProfileId())) return true;
        return NametagSync.hasMod(id) || gg.aurora.Friends.isFriend(id);
    }

    /** Nom stylé mis en cache par joueur : recalculé au plus 30 fois par seconde (le dégradé est animé). */
    private record Cached(UUID id, int bucket, Component out) {}

    private static final java.util.Map<Component, Cached> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public static Component apply(Component original, UUID id, String name) {
        if (original == null || name == null || name.isEmpty() || id == null) return original;
        int bucket = (int) (time() * 30);
        Cached c = CACHE.get(original);
        if (c != null && c.bucket() == bucket && c.id().equals(id)) return c.out();
        Component out = build(original, id, name);
        if (CACHE.size() > 512) CACHE.clear();
        CACHE.put(original, new Cached(id, bucket, out));
        return out;
    }

    private static Component build(Component original, UUID id, String name) {
        NametagStyle style = styleFor(id);
        boolean showLogo = Settings.showLogos && hasMod(id) && (style == null || style.logo());
        boolean star = Settings.friendStar && gg.aurora.Friends.isFriend(id);
        if (style == null && !showLogo && !star) return original;

        MutableComponent out = Component.empty();
        if (star) out.append(Component.literal("★ ").withStyle(Style.EMPTY.withColor(0xF5B54A)));
        if (showLogo) out.append(logo());
        if (style == null) {
            out.append(original);
            return out;
        }
        float t = time();
        boolean[] done = {false};
        original.visit((st, text) -> {
            int i = done[0] ? -1 : text.indexOf(name);
            if (i < 0) {
                out.append(Component.literal(text).withStyle(st));
            } else {
                if (i > 0) out.append(Component.literal(text.substring(0, i)).withStyle(st));
                out.append(gradient(name, style, st, t));
                if (i + name.length() < text.length()) out.append(Component.literal(text.substring(i + name.length())).withStyle(st));
                done[0] = true;
            }
            return Optional.empty();
        }, Style.EMPTY);
        if (!done[0]) return original; // pseudo introuvable (nom modifié par le serveur) : on ne touche à rien
        return out;
    }

    /** Texte coloré lettre par lettre selon le style, à l'instant t. */
    public static MutableComponent gradient(String text, NametagStyle style, Style base, float t) {
        MutableComponent c = Component.empty();
        int n = text.length();
        for (int k = 0; k < n; k++) {
            float u = n == 1 ? 0.5f : k / (float) (n - 1);
            c.append(Component.literal(String.valueOf(text.charAt(k))).withStyle(base.withColor(style.colorAt(u, t))));
        }
        return c;
    }
}
