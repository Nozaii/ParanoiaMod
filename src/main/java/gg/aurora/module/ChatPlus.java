package gg.aurora.module;

import com.mojang.authlib.GameProfile;
import gg.aurora.mixin.ChatComponentAccessor;
import gg.aurora.ui.Draw;
import gg.aurora.ui.Theme;
import net.minecraft.ChatFormatting;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.objects.PlayerSprite;
import net.minecraft.world.item.component.ResolvableProfile;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat amélioré : horodatage, têtes des joueurs, onglets Global / MP / Système (filtre d'affichage),
 * copie d'un message au clic droit, et masquage des messages privés en mode stream.
 */
public final class ChatPlus {
    private ChatPlus() {}

    public enum Cat { GLOBAL, PM, SYSTEM }

    public static final String[] TABS = {"All", "Global", "MP", "System"};
    /** 0 = tout, 1 = global, 2 = MP, 3 = système. */
    public static int tab = 0;

    /** Catégorie et texte brut (sans heure ni tête) de chaque message affiché. */
    private static final Map<GuiMessage, Cat> CATS = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<GuiMessage, String> RAW = Collections.synchronizedMap(new IdentityHashMap<>());

    // message en cours d'ajout (posé par les évènements de réception, lu par le mixin d'ajout)
    private static Cat pendingCat;
    private static GameProfile pendingSender;
    private static Cat currentCat = Cat.SYSTEM;
    private static String currentRaw = "";

    private static final Pattern PM_PATTERN = Pattern.compile(
            "(?i)(whispers to you|you whisper to|chuchote|te chuchote|vous chuchote|\\bmp\\b|\\[msg\\]|\\bfrom \\w+:|\\bto \\w+:|-> ?(me|moi)\\b|\\b(me|moi) ?->)");
    private static final Pattern NAME_PATTERN = Pattern.compile("^(?:\\[[^\\]]{1,24}\\] ?)*<?([A-Za-z0-9_]{3,16})>?(?: »|:| >| ») ");

    /** Message de joueur signé (évènement Fabric avant affichage). */
    public static boolean onChat(Component message, GameProfile sender, ChatType.Bound params) {
        if (gg.aurora.waypoint.WaypointShare.onMessage(message)) return false;
        boolean pm = params != null && params.chatType().unwrapKey()
                .map(k -> k == ChatType.MSG_COMMAND_INCOMING || k == ChatType.MSG_COMMAND_OUTGOING).orElse(false);
        if (pm && ClientModules.hidePm()) {
            maskedPm();
            return false;
        }
        pendingCat = pm ? Cat.PM : Cat.GLOBAL;
        pendingSender = sender;
        return true;
    }

    /** Message système / non signé (beaucoup de serveurs envoient le chat ainsi). */
    public static boolean onGame(Component message, boolean overlay) {
        if (overlay) return true;
        if (gg.aurora.waypoint.WaypointShare.onMessage(message)) return false;
        String s = message.getString();
        boolean pm = PM_PATTERN.matcher(s).find();
        if (pm && ClientModules.hidePm()) {
            maskedPm();
            return false;
        }
        pendingSender = null;
        if (pm) {
            pendingCat = Cat.PM;
        } else {
            Matcher m = NAME_PATTERN.matcher(s);
            PlayerInfo info = m.find() ? playerInfo(m.group(1)) : null;
            pendingCat = info != null ? Cat.GLOBAL : Cat.SYSTEM;
            if (info != null) pendingSender = info.getProfile();
        }
        return true;
    }

    private static void maskedPm() {
        Minecraft.getInstance().gui.getChat().addMessage(Component.literal("✉ Private message hidden (stream mode)").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static PlayerInfo playerInfo(String name) {
        var conn = Minecraft.getInstance().getConnection();
        return conn == null ? null : conn.getPlayerInfo(name);
    }

    /** Transforme le message au moment de l'ajout : heure + tête du joueur. */
    public static Component decorate(Component msg) {
        currentCat = pendingCat != null ? pendingCat : Cat.SYSTEM;
        GameProfile sender = pendingSender;
        pendingCat = null;
        pendingSender = null;
        currentRaw = msg == null ? "" : msg.getString();
        if (msg == null || !ClientModules.CHAT.enabled) return msg;
        MutableComponent out = Component.empty();
        if (ClientModules.CHAT.flag("time", true)) {
            int fmt = (int) ClientModules.CHAT.opt("timeFmt", 0);
            String pattern = ClientModules.TIME_FORMATS[Math.max(0, Math.min(ClientModules.TIME_FORMATS.length - 1, fmt))];
            String time = LocalTime.now().format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH));
            out.append(Component.literal("[" + time + "] ")
                    .withStyle(Style.EMPTY.withColor(TextColor.fromRgb((int) ClientModules.CHAT.opt("timeColor", 0x6E6A80)))));
        }
        if (sender != null && ClientModules.CHAT.flag("heads", true)) {
            out.append(Component.object(new PlayerSprite(ResolvableProfile.createResolved(sender), true))).append(" ");
        }
        return out.getSiblings().isEmpty() ? msg : out.append(msg);
    }

    /** Message ajouté à l'historique : retient sa catégorie et son texte brut. */
    public static void track(GuiMessage msg) {
        CATS.put(msg, currentCat);
        RAW.put(msg, currentRaw);
        if (CATS.size() > 400) {
            // l'historique vanilla garde 100 messages : on oublie les plus anciens
            synchronized (CATS) {
                var it = CATS.keySet().iterator();
                for (int i = 0; i < 100 && it.hasNext(); i++) {
                    RAW.remove(it.next());
                    it.remove();
                }
            }
        }
    }

    /** Le message doit-il être affiché avec l'onglet courant ? */
    public static boolean visible(GuiMessage msg) {
        if (!ClientModules.CHAT.enabled || !ClientModules.CHAT.flag("tabs", true) || tab == 0) return true;
        // le filtre peut passer avant l'enregistrement du message : on prend alors la catégorie en cours
        Cat c = CATS.containsKey(msg) ? CATS.get(msg) : currentCat;
        return switch (tab) {
            case 1 -> c == Cat.GLOBAL;
            case 2 -> c == Cat.PM;
            default -> c == Cat.SYSTEM;
        };
    }

    public static void setTab(int t) {
        tab = t;
        Minecraft.getInstance().gui.getChat().rescaleChat();
    }

    // ---------------------------------------------------------------- onglets (dans l'écran de chat)

    private static final float[][] TAB_RECTS = new float[TABS.length][4];

    public static void renderTabs(GuiGraphics g, int mx, int my) {
        if (!ClientModules.CHAT.enabled || !ClientModules.CHAT.flag("tabs", true)) return;
        float x = 2, y = g.guiHeight() - 27, h = 11;
        for (int i = 0; i < TABS.length; i++) {
            float w = Draw.width(TABS[i], 0.72f) + 12;
            boolean sel = i == tab, hv = mx >= x && mx < x + w && my >= y && my < y + h;
            Draw.rrect(g, x, y, w, h, 3, sel ? Draw.withAlpha(Theme.accent(), 0.85f) : hv ? 0xA0302838 : 0x90000000);
            Draw.centered(g, TABS[i], x + w / 2, y + 2.5f, 0.72f, sel ? 0xFFFFFFFF : 0xFFC8C4D8);
            TAB_RECTS[i] = new float[]{x, y, w, h};
            x += w + 2;
        }
    }

    /** Clic dans l'écran de chat : onglets (gauche) ou copie (droit). true = clic consommé. */
    public static boolean click(double mx, double my, int button) {
        if (!ClientModules.CHAT.enabled) return false;
        if (button == 0 && ClientModules.CHAT.flag("tabs", true)) {
            for (int i = 0; i < TABS.length; i++) {
                float[] r = TAB_RECTS[i];
                if (r[2] > 0 && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                    setTab(i);
                    Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.2f, 0.25f));
                    return true;
                }
            }
        }
        if (button == 1 && ClientModules.CHAT.flag("copy", true)) return copyAt(mx, my);
        return false;
    }

    /** Copie le message sous la souris (géométrie identique au rendu du chat vanilla). */
    private static boolean copyAt(double mx, double my) {
        Minecraft mc = Minecraft.getInstance();
        var chat = mc.gui.getChat();
        ChatComponentAccessor acc = (ChatComponentAccessor) chat;
        double scale = acc.aurora$scale();
        int guiH = mc.getWindow().getGuiScaledHeight();
        double bottom = Math.floor((guiH - 40) / scale);
        int lineH = acc.aurora$lineHeight();
        double cy = my / scale, cx = mx / scale;
        if (cx < 0 || cx > acc.aurora$width() / scale + 8) return false;
        int fromBottom = (int) Math.floor((bottom - cy) / lineH);
        if (fromBottom < 0 || fromBottom >= chat.getLinesPerPage()) return false;
        var lines = acc.aurora$trimmed();
        int idx = fromBottom + acc.aurora$scroll();
        if (idx < 0 || idx >= lines.size()) return false;
        GuiMessage.Line line = lines.get(idx);
        String text = null;
        for (GuiMessage m : acc.aurora$all()) {
            if (m.addedTime() == line.addedTime()) {
                text = RAW.getOrDefault(m, m.content().getString());
                break;
            }
        }
        if (text == null || text.isBlank()) return false;
        mc.keyboardHandler.setClipboard(text);
        gg.aurora.ui.Toasts.push(gg.aurora.ui.Icons.CHECK, "Message copied", text.length() > 40 ? text.substring(0, 40) + "…" : text, false);
        return true;
    }

    /** Joueur d'un UUID dans la liste des joueurs (pour les têtes). */
    public static GameProfile profile(UUID id) {
        var conn = Minecraft.getInstance().getConnection();
        if (conn == null) return null;
        PlayerInfo info = conn.getPlayerInfo(id);
        return info == null ? null : info.getProfile();
    }
}
