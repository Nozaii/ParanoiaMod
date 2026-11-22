package gg.aurora.module;

import gg.aurora.ui.Draw;
import gg.aurora.ui.Icons;
import gg.aurora.ui.Rows;
import gg.aurora.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.Locale;

/**
 * Mode stream, chat amélioré, animations 1.7, sons de combat, traînée, animation du totem, menu pause
 * et emotes.
 */
public final class ClientModules {
    private ClientModules() {}

    // ===================================================================== Mode stream

    public static final Module STREAM = Modules.register(new Module("stream_mode", "Stream mode", Category.MISC, Icons.EYE, false))
            .withSettings(() -> List.of(
                    new Rows.Info("Mask", "Server IP, coordinates (HUD, F3, deaths), private messages"),
                    new Rows.Toggle("Hide server IP", null, () -> ClientModules.STREAM.flag("ip", true), v -> ClientModules.STREAM.set("ip", v)),
                    new Rows.Toggle("Hide coordinates", null, () -> ClientModules.STREAM.flag("coords", true),
                            v -> ClientModules.STREAM.set("coords", v)),
                    new Rows.Toggle("Hide private messages", null, () -> ClientModules.STREAM.flag("pm", true), v -> ClientModules.STREAM.set("pm", v)),
                    new Rows.Toggle("On-screen indicator", "Small discreet « STREAM » at the top right", () -> ClientModules.STREAM.flag("badge", true),
                            v -> ClientModules.STREAM.set("badge", v)),
                    new Rows.Info("Key", "Assign it in the visual keyboard (Stream mode action)")));

    public static boolean hideIp() {
        return STREAM.enabled && STREAM.flag("ip", true);
    }

    public static boolean hideCoords() {
        return STREAM.enabled && STREAM.flag("coords", true);
    }

    public static boolean hidePm() {
        return STREAM.enabled && STREAM.flag("pm", true);
    }

    /** Pastille « STREAM » discrète en haut à droite. */
    public static void renderStreamBadge(GuiGraphics g) {
        if (!STREAM.enabled || !STREAM.flag("badge", true)) return;
        float pulse = 0.6f + 0.4f * (float) Math.sin(System.currentTimeMillis() / 400.0);
        String t = "STREAM";
        float w = Draw.width(t, 0.62f) + 15, x = g.guiWidth() - w - 4, y = 4;
        Draw.rrect(g, x, y, w, 10, 5, 0x99000000);
        Draw.rrect(g, x + 4, y + 3.5f, 3, 3, 1.5f, Draw.withAlpha(0xFF3B3B, pulse));
        Draw.text(g, t, x + 10, y + 2.6f, 0.62f, 0xCCFFFFFF);
    }

    // ===================================================================== Chat amélioré

    public static final String[] TIME_FORMATS = {"HH:mm", "HH:mm:ss", "h:mm a"};

    public static final Module CHAT = Modules.register(new Module("chat_plus", "Better chat", Category.MISC, Icons.LIST, true))
            .withSettings(() -> List.of(
                    new Rows.Toggle("Timestamps", "Time in front of each message", () -> ClientModules.CHAT.flag("time", true),
                            v -> ClientModules.CHAT.set("time", v)),
                    new Rows.Choice("Time format", TIME_FORMATS, () -> (int) ClientModules.CHAT.opt("timeFmt", 0),
                            i -> ClientModules.CHAT.set("timeFmt", i)),
                    new Rows.ColorRow("Time color", () -> (int) ClientModules.CHAT.opt("timeColor", 0x6E6A80),
                            v -> ClientModules.CHAT.set("timeColor", v & 0xFFFFFF)),
                    new Rows.Toggle("Right click to copy", "Copies the message to the clipboard", () -> ClientModules.CHAT.flag("copy", true),
                            v -> ClientModules.CHAT.set("copy", v)),
                    new Rows.Toggle("Tabs", "Global · DM · System above the chat", () -> ClientModules.CHAT.flag("tabs", true),
                            v -> ClientModules.CHAT.set("tabs", v)),
                    new Rows.Toggle("Player heads", "Small head next to each message", () -> ClientModules.CHAT.flag("heads", true),
                            v -> ClientModules.CHAT.set("heads", v))));

    // ===================================================================== Animations 1.7

    public static final Module OLD_ANIM = Modules.register(new Module("old_animations", "Animations 1.7", Category.VISUALS, Icons.SWORD, false))
            .withSettings(() -> List.of(
                    new Rows.Toggle("Sword blocking", "Right click with a sword: 1.7 blocking pose", () -> ClientModules.OLD_ANIM.flag("block", true),
                            v -> ClientModules.OLD_ANIM.set("block", v)),
                    new Rows.Toggle("Hand always raised", "No drop after a hit or when switching items",
                            () -> ClientModules.OLD_ANIM.flag("noDip", true), v -> ClientModules.OLD_ANIM.set("noDip", v)),
                    new Rows.Toggle("1.7 eating", "Closer item and stronger sway while eating",
                            () -> ClientModules.OLD_ANIM.flag("eat", true), v -> ClientModules.OLD_ANIM.set("eat", v)),
                    new Rows.Toggle("Hit while blocking", "The sword also hits while blocking (blockhit)",
                            () -> ClientModules.OLD_ANIM.flag("blockhit", true), v -> ClientModules.OLD_ANIM.set("blockhit", v))));

    /** Blocage 1.7 : épée en main et clic droit enfoncé. */
    public static boolean swordBlocking(net.minecraft.world.entity.LivingEntity p, net.minecraft.world.item.ItemStack stack) {
        if (!OLD_ANIM.enabled || !OLD_ANIM.flag("block", true) || p != Minecraft.getInstance().player) return false;
        return stack.is(net.minecraft.tags.ItemTags.SWORDS) && Minecraft.getInstance().options.keyUse.isDown() && Minecraft.getInstance().screen == null;
    }

    // ===================================================================== Sons de combat

    public static final String[] HIT_SOUND_NAMES = {"None", "Paranoia", "Ding", "Bell", "Pop", "Amethyst", "Experience", "Pling", "Low", "Wood"};
    public static final String[] KILL_SOUND_NAMES = {"None", "Level", "Totem", "Bell", "Lightning", "Wither", "Amethyst", "Challenge"};

    @SuppressWarnings("unchecked")
    private static Object hitSound(int i) {
        return switch (i) {
            case 1 -> gg.aurora.AuroraSounds.HIT; // 4 variantes tirées au hasard par sounds.json
            case 2 -> SoundEvents.ARROW_HIT_PLAYER;
            case 3 -> SoundEvents.NOTE_BLOCK_BELL;
            case 4 -> SoundEvents.ITEM_PICKUP;
            case 5 -> SoundEvents.AMETHYST_BLOCK_CHIME;
            case 6 -> SoundEvents.EXPERIENCE_ORB_PICKUP;
            case 7 -> SoundEvents.NOTE_BLOCK_PLING;
            case 8 -> SoundEvents.NOTE_BLOCK_BASS;
            case 9 -> SoundEvents.NOTE_BLOCK_HAT;
            default -> null;
        };
    }

    private static Object killSound(int i) {
        return switch (i) {
            case 1 -> SoundEvents.PLAYER_LEVELUP;
            case 2 -> SoundEvents.TOTEM_USE;
            case 3 -> SoundEvents.BELL_BLOCK;
            case 4 -> SoundEvents.LIGHTNING_BOLT_THUNDER;
            case 5 -> SoundEvents.WITHER_SPAWN;
            case 6 -> SoundEvents.AMETHYST_CLUSTER_BREAK;
            case 7 -> SoundEvents.UI_TOAST_CHALLENGE_COMPLETE;
            default -> null;
        };
    }

    public static final Module SOUNDS = Modules.register(new Module("combat_sounds", "Combat sounds", Category.PVP, Icons.BOLT, false))
            .withSettings(() -> List.of(
                    new Rows.Choice("Hit sound", HIT_SOUND_NAMES, () -> (int) ClientModules.SOUNDS.opt("hit", 1), i -> {
                        ClientModules.SOUNDS.set("hit", i);
                        play(hitSound(i));
                    }),
                    new Rows.Choice("Kill sound", KILL_SOUND_NAMES, () -> (int) ClientModules.SOUNDS.opt("kill", 1), i -> {
                        ClientModules.SOUNDS.set("kill", i);
                        play(killSound(i));
                    }),
                    new Rows.Slider("Volume", null, 0.1, 1, 0.05, () -> ClientModules.SOUNDS.opt("volume", 0.6), v -> ClientModules.SOUNDS.set("volume", v),
                            v -> Math.round(v * 100) + " %"),
                    new Rows.Slider("Height", null, 0.5, 2, 0.05, () -> ClientModules.SOUNDS.opt("pitch", 1), v -> ClientModules.SOUNDS.set("pitch", v),
                            v -> String.format(Locale.ROOT, "%.2f", v)),
                    new Rows.Toggle("Mobs too", "Otherwise only on players", () -> ClientModules.SOUNDS.flag("mobs", false),
                            v -> ClientModules.SOUNDS.set("mobs", v)),
                    new Rows.Toggle("Fall sounds", "Paranoia sound when you take fall damage", () -> ClientModules.SOUNDS.flag("fall", true), v -> {
                        ClientModules.SOUNDS.set("fall", v);
                        if (v) play(gg.aurora.AuroraSounds.FALL_SMALL);
                    })));

    // Chute : la distance est remise à zéro côté client dès l'atterrissage, avant l'arrivée du paquet de dégâts.
    // On garde donc le pic de la dernière chute pour choisir petite / grosse chute.
    private static double fallPeak, fallLanded;
    private static long fallLandedMs;

    public static void tickFall(Player p) {
        if (p == null) return;
        if (p.fallDistance > fallPeak) fallPeak = p.fallDistance;
        if (fallPeak > 0 && p.fallDistance <= 0) {
            fallLanded = fallPeak;
            fallLandedMs = System.currentTimeMillis();
            fallPeak = 0;
        }
    }

    /** Dégâts de chute reçus par le joueur local (mixin handleDamageEvent). */
    public static void onFall() {
        if (!SOUNDS.enabled || !SOUNDS.flag("fall", true)) return;
        double d = System.currentTimeMillis() - fallLandedMs < 1500 ? fallLanded : Math.max(fallPeak, 0);
        SoundEvent s = d > 7 ? gg.aurora.AuroraSounds.FALL_BIG : gg.aurora.AuroraSounds.FALL_SMALL;
        float vol = (float) SOUNDS.opt("volume", 0.6);
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(s, 1f, vol));
    }

    @SuppressWarnings("unchecked")
    private static void play(Object s) {
        if (s == null) return;
        float vol = (float) SOUNDS.opt("volume", 0.6), pitch = (float) SOUNDS.opt("pitch", 1);
        var mgr = Minecraft.getInstance().getSoundManager();
        if (s instanceof Holder<?> h) mgr.play(SimpleSoundInstance.forUI(((Holder<SoundEvent>) h).value(), pitch, vol));
        else if (s instanceof SoundEvent e) mgr.play(SimpleSoundInstance.forUI(e, pitch, vol));
    }

    public static void onHit(Entity e) {
        if (!SOUNDS.enabled || !(e instanceof net.minecraft.world.entity.LivingEntity)) return;
        if (!(e instanceof Player) && !SOUNDS.flag("mobs", false)) return;
        play(hitSound((int) SOUNDS.opt("hit", 1)));
    }

    public static void onKill(Entity e) {
        if (!SOUNDS.enabled) return;
        if (!(e instanceof Player) && !SOUNDS.flag("mobs", false)) return;
        play(killSound((int) SOUNDS.opt("kill", 1)));
    }

    // ===================================================================== Traînée

    public static final String[] TRAIL_STYLES = {"Ribbon", "Double ribbon", "Comet", "Smoke"};
    public static final String[] TRAIL_IDS = {"ribbon", "double", "comet", "smoke"};

    public static final Module TRAIL = Modules.register(new Module("trail", "Trail", Category.VISUALS, Icons.CHEVRONS, false) {
        @Override
        protected void onToggle() {
            gg.aurora.nametag.NametagSync.markDirty();
        }
    }).withSettings(() -> List.of(
            new Rows.Info("Visible", "By other players of the client"),
            new Rows.Choice("Style", TRAIL_STYLES, () -> (int) ClientModules.TRAIL.opt("style", 0), i -> {
                ClientModules.TRAIL.set("style", i);
                gg.aurora.nametag.NametagSync.markDirty();
            }),
            new Rows.ColorRow("Color", () -> (int) ClientModules.TRAIL.opt("color", 0x9D7CFF), v -> {
                ClientModules.TRAIL.set("color", v & 0xFFFFFF);
                gg.aurora.nametag.NametagSync.markDirty();
            }),
            new Rows.ColorRow("End color (gradient)", () -> (int) ClientModules.TRAIL.opt("color2", 0xFF6EC7), v -> {
                ClientModules.TRAIL.set("color2", v & 0xFFFFFF);
                gg.aurora.nametag.NametagSync.markDirty();
            }),
            new Rows.Slider("Length", null, 0.3, 2, 0.1, () -> ClientModules.TRAIL.opt("life", 0.8), v -> ClientModules.TRAIL.set("life", v),
                    v -> String.format(Locale.ROOT, "%.1f s", v)),
            new Rows.Slider("Width", null, 0.04, 0.4, 0.01, () -> ClientModules.TRAIL.opt("width", 0.14), v -> ClientModules.TRAIL.set("width", v),
                    v -> Math.round(v * 100) + " cm"),
            new Rows.Toggle("Also while walking", "Otherwise only when sprinting and with elytra", () -> ClientModules.TRAIL.flag("walk", false),
                    v -> ClientModules.TRAIL.set("walk", v))));

    /** Code publié dans le profil : « style_rrggbb_rrggbb » (couleur de début et de fin), vide si désactivé. */
    public static String trailCode() {
        if (!TRAIL.enabled) return "";
        int s = Math.max(0, Math.min(TRAIL_IDS.length - 1, (int) TRAIL.opt("style", 0)));
        return TRAIL_IDS[s] + "_" + String.format(Locale.ROOT, "%06x_%06x", (int) TRAIL.opt("color", 0x9D7CFF) & 0xFFFFFF,
                (int) TRAIL.opt("color2", 0xFF6EC7) & 0xFFFFFF);
    }

    /** Décode le code de traînée : {style, couleur, couleur de fin}, ou null. */
    public static int[] parseTrail(String code) {
        if (code == null || code.isEmpty()) return null;
        String[] p = code.split("_");
        if (p.length < 2) return null;
        for (int i = 0; i < TRAIL_IDS.length; i++) {
            if (!TRAIL_IDS[i].equals(p[0])) continue;
            try {
                int c1 = Integer.parseInt(p[1], 16);
                int c2 = p.length > 2 ? Integer.parseInt(p[2], 16) : c1;
                return new int[]{i, c1, c2};
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // ===================================================================== Animation du totem

    public static final String[] TOTEM_MODES = {"Vanilla tuned", "Subtle", "Hidden"};

    public static final Module TOTEM = Modules.register(new Module("totem_anim", "Totem animation", Category.VISUALS, Icons.TOTEM, false))
            .withSettings(() -> List.of(
                    new Rows.Choice("Mode", TOTEM_MODES, () -> (int) ClientModules.TOTEM.opt("mode", 1), i -> ClientModules.TOTEM.set("mode", i)),
                    new Rows.Slider("Size", "Vanilla mode", 0.2, 1.2, 0.05, () -> ClientModules.TOTEM.opt("scale", 0.6),
                            v -> ClientModules.TOTEM.set("scale", v), v -> Math.round(v * 100) + " %"),
                    new Rows.Slider("Duration", "Quiet mode", 0.3, 3, 0.1, () -> ClientModules.TOTEM.opt("duration", 1.2),
                            v -> ClientModules.TOTEM.set("duration", v), v -> String.format(Locale.ROOT, "%.1f s", v)),
                    new Rows.Slider("Opacity", "Quiet mode", 0.1, 1, 0.05, () -> ClientModules.TOTEM.opt("alpha", 0.8),
                            v -> ClientModules.TOTEM.set("alpha", v), v -> Math.round(v * 100) + " %")));

    private static long totemAt;

    /** Appelé quand un totem s'active : true = cacher l'animation vanilla. */
    public static boolean onTotem() {
        if (!TOTEM.enabled) return false;
        int mode = (int) TOTEM.opt("mode", 1);
        if (mode == 1) totemAt = System.currentTimeMillis();
        return mode != 0;
    }

    /** Mode discret : petit totem en haut de l'écran avec un anneau qui s'élargit, puis s'efface. */
    public static void renderTotem(GuiGraphics g) {
        if (totemAt == 0) return;
        float dur = (float) TOTEM.opt("duration", 1.2) * 1000;
        float t = (System.currentTimeMillis() - totemAt) / dur;
        if (t >= 1) {
            totemAt = 0;
            return;
        }
        float a = (float) TOTEM.opt("alpha", 0.8) * (1 - t * t);
        float cx = g.guiWidth() / 2f, cy = 34;
        float r = 8 + 22 * Draw.easeOutCubic(t);
        Draw.ring(g, cx - r, cy - r, 2 * r, 2 * r, r, Draw.withAlpha(0xFFD65E, 0.7f * a));
        Draw.glow(g, cx - 10, cy - 10, 20, 20, 10, Draw.withAlpha(0xFFE7A0, 0.5f * a));
        if (a > 0.35f) g.renderItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TOTEM_OF_UNDYING), Math.round(cx - 8), Math.round(cy - 8));
        Draw.centered(g, "Totem used", cx, cy + 14, 0.75f, Draw.withAlpha(Theme.TEXT, a));
    }

    // ===================================================================== Emotes

    public static final Module EMOTES = Modules.register(new Module("emotes", "Emotes", Category.PLAYER, Icons.PERSON, true))
            .withSettings(() -> List.of(
                    new Rows.Info("Wheel", "Hold the Emote wheel key (N by default)"),
                    new Rows.Info("Visible", "By other players of the client"),
                    new Rows.Toggle("Third person view", "Switches to back view during the emote", () -> ClientModules.EMOTES.flag("camera", true),
                            v -> ClientModules.EMOTES.set("camera", v))));

    /** Force le chargement (enregistrement des modules). */
    public static void init() {}
}
