package gg.aurora.waypoint;

import gg.aurora.ui.Icons;
import gg.aurora.ui.Toasts;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.permissions.Permissions;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sharing waypoints through chat: the message carries a short code. Players with the mod get a card with
 * a Save button; everyone else only sees the readable line, so sending always asks for confirmation.
 */
public final class WaypointShare {
    private WaypointShare() {}

    private static final Pattern CODE = Pattern.compile("pwp1:([A-Za-z0-9_-]+)");

    public static String encode(Waypoint w) {
        String raw = String.join("|", w.name.replace("|", " "), String.valueOf(w.x), String.valueOf(w.y), String.valueOf(w.z), w.dim,
                Integer.toHexString(w.color));
        if (w.area) raw += "|" + w.x2 + "|" + w.y2 + "|" + w.z2;
        return "pwp1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Waypoint decode(String body) {
        try {
            String[] p = new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8).split("\\|");
            if (p.length < 6) return null;
            Waypoint w = new Waypoint();
            w.name = p[0].length() > 32 ? p[0].substring(0, 32) : p[0];
            w.x = Integer.parseInt(p[1]);
            w.y = Integer.parseInt(p[2]);
            w.z = Integer.parseInt(p[3]);
            w.dim = p[4];
            w.color = Integer.parseInt(p[5], 16) & 0xFFFFFF;
            if (p.length >= 9) {
                w.area = true;
                w.x2 = Integer.parseInt(p[6]);
                w.y2 = Integer.parseInt(p[7]);
                w.z2 = Integer.parseInt(p[8]);
            }
            return w;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** to = null: public chat, otherwise a private message to that player. Asks first in both cases. */
    public static void confirm(Screen back, Waypoint w, String to) {
        Minecraft mc = Minecraft.getInstance();
        boolean pub = to == null || to.isBlank();
        Component title = Component.literal(pub ? "Share « " + w.name + " » in public chat?" : "Send « " + w.name + " » to " + to + "?");
        Component body = Component.literal((pub ? "Everyone on this server can read these coordinates: " : "This player can read these coordinates: ")
                + w.x + " " + w.y + " " + w.z + ". Players with Paranoia can save the waypoint in one click.");
        mc.setScreen(new ConfirmScreen(ok -> {
            mc.setScreen(back);
            if (ok) send(w, pub ? null : to.trim());
        }, title, body));
    }

    private static void send(Waypoint w, String to) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;
        String text = w.name + " " + w.x + " " + w.y + " " + w.z + (w.area ? " to " + w.x2 + " " + w.y2 + " " + w.z2 : "") + " " + encode(w);
        if (to == null) mc.getConnection().sendChat(text);
        else mc.getConnection().sendCommand("msg " + to + " " + text);
        Toasts.push(Icons.PIN, "Waypoint shared", w.name, false);
    }

    /** Incoming message: shows the save card. Returns false to keep the message as it is. */
    public static boolean onMessage(Component message) {
        Matcher m = CODE.matcher(message.getString());
        if (!m.find()) return false;
        Waypoint w = decode(m.group(1));
        if (w == null) return false;
        Minecraft mc = Minecraft.getInstance();
        Component save = Component.literal("[Save]").withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent.RunCommand("/paranoia wp " + m.group(1)))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Add this waypoint"))));
        String who = message.getString().substring(0, m.start()).trim();
        mc.gui.getChat().addMessage(Component.literal("[Waypoint] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(who + "  ").withStyle(ChatFormatting.GRAY)).append(save));
        return true;
    }

    /** /paranoia wp <code>: stores it in the current world. */
    public static void save(String body) {
        Waypoint w = decode(body.startsWith("pwp1:") ? body.substring(5) : body);
        if (w == null) {
            Toasts.push(Icons.INFO, "Waypoint", "This code is not valid", false);
            return;
        }
        Waypoints.LIST.add(w);
        Waypoints.save();
        Toasts.push(Icons.PIN, "Waypoint saved", w.name, false);
    }

    public static boolean canTeleport() {
        var p = Minecraft.getInstance().player;
        return p != null && p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    public static void teleport(Waypoint w) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;
        String here = mc.level == null ? "" : mc.level.dimension().identifier().toString();
        if (here.equals(w.dim)) mc.getConnection().sendCommand("tp @s " + w.x + " " + w.y + " " + w.z);
        else mc.getConnection().sendCommand("execute in " + w.dim + " run tp @s " + w.x + " " + w.y + " " + w.z);
    }
}
