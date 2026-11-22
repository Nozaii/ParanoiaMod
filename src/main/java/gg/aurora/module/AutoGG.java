package gg.aurora.module;

import gg.aurora.Settings;
import gg.aurora.ui.Icons;
import gg.aurora.ui.Rows;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Auto GG : message envoyé automatiquement après une victoire. La victoire est reconnue dans les
 * messages du serveur (pas ceux des joueurs) et dans les titres à l'écran, d'après une liste de
 * déclencheurs modifiable ({me} = ton pseudo). Un seul envoi par victoire.
 */
public final class AutoGG {
    private AutoGG() {}

    public static final String DEFAULT_TRIGGERS = "victory,victoire,you won,tu as gagné,vous avez gagné,won the game,won the duel,"
            + "winner: {me},{me} won,{me} a gagné,1st killer - {me}";

    public static final Module MODULE = Modules.register(new Module("auto_gg", "Auto GG", Category.MISC, Icons.HEART, false))
            .withSettings(() -> List.of(
                    new Rows.TextField("Message 1", "gg", 100, () -> Settings.autoGg1, v -> Settings.autoGg1 = v),
                    new Rows.TextField("Message 2", "Optional", 100, () -> Settings.autoGg2, v -> Settings.autoGg2 = v),
                    new Rows.TextField("Message 3", "Optional", 100, () -> Settings.autoGg3, v -> Settings.autoGg3 = v),
                    new Rows.Choice("Message order", new String[]{"Random", "In order"}, () -> (int) AutoGG.MODULE.opt("order", 0),
                            i -> AutoGG.MODULE.set("order", i)),
                    new Rows.Slider("Delay", null, 0, 5, 0.25, () -> AutoGG.MODULE.opt("delay", 0.75), v -> AutoGG.MODULE.set("delay", v),
                            v -> String.format(Locale.ROOT, "%.2f s", v)),
                    new Rows.Button("Kill messages", "Pick which death messages count as your kill", Icons.LIST, () -> {
                        var mc = Minecraft.getInstance();
                        mc.setScreen(new gg.aurora.ui.DeathMessagesScreen(mc.screen));
                    }),
                    new Rows.TextField("Extra triggers", "Comma separated, {me} = your name", 400,
                            () -> Settings.autoGgTriggers, v -> Settings.autoGgTriggers = v),
                    new Rows.Button("Default triggers", null, Icons.ARROW_LEFT, () -> Settings.autoGgTriggers = DEFAULT_TRIGGERS)));

    public record Deaths(String name, String... keys) {}

    /** Vanilla messages where the second player (%2$s) is the killer. */
    public static final Deaths[] DEATHS = {
            new Deaths("Melee", "death.attack.player", "death.attack.player.item", "death.attack.mace_smash.player", "death.attack.mace_smash.player.item",
                    "death.attack.sting.player", "death.attack.thorns", "death.attack.thorns.item"),
            new Deaths("Ranged", "death.attack.arrow", "death.attack.arrow.item", "death.attack.trident", "death.attack.trident.item", "death.attack.thrown",
                    "death.attack.thrown.item", "death.attack.fireball", "death.attack.fireball.item", "death.attack.fireworks.item"),
            new Deaths("Explosions", "death.attack.explosion.player", "death.attack.explosion.player.item", "death.attack.fireworks.player"),
            new Deaths("Falls", "death.attack.fall.player", "death.fell.assist", "death.fell.assist.item", "death.fell.finish", "death.fell.finish.item",
                    "death.attack.flyIntoWall.player", "death.attack.outOfWorld.player"),
            new Deaths("Fire and lava", "death.attack.inFire.player", "death.attack.onFire.player", "death.attack.onFire.item", "death.attack.lava.player",
                    "death.attack.lava.item", "death.attack.hotFloor.player"),
            new Deaths("Magic", "death.attack.magic.player", "death.attack.indirectMagic", "death.attack.indirectMagic.item", "death.attack.wither.player",
                    "death.attack.sonic_boom.player", "death.attack.sonic_boom.item"),
            new Deaths("Environment", "death.attack.drown.player", "death.attack.cactus.player", "death.attack.sweetBerryBush.player", "death.attack.freeze.player",
                    "death.attack.stalagmite.player", "death.attack.anvil.player", "death.attack.fallingBlock.player", "death.attack.fallingStalactite.player",
                    "death.attack.inWall.player", "death.attack.starve.player", "death.attack.dryout.player")};

    public static boolean deathEnabled(String key) {
        return !("," + Settings.autoGgOff + ",").contains("," + key + ",");
    }

    public static void setDeath(String key, boolean on) {
        java.util.Set<String> off = new java.util.LinkedHashSet<>(java.util.Arrays.asList(Settings.autoGgOff.split(",")));
        off.remove("");
        if (on) off.remove(key);
        else off.add(key);
        Settings.autoGgOff = String.join(",", off);
    }

    /** Message built from a translation key, with you as the killer. */
    private static boolean myKill(Component c, String me) {
        if (!(c.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc)) return false;
        String key = tc.getKey();
        if (!key.startsWith("death.") || !deathEnabled(key)) return false;
        Object[] args = tc.getArgs();
        if (args.length < 2) return false;
        String killer = args[1] instanceof Component k ? k.getString() : String.valueOf(args[1]);
        return killer.toLowerCase(Locale.ROOT).equals(me);
    }

    private static long lastSent;
    private static long sendAt = -1;
    private static String pending;
    private static int next;

    /** Message système du serveur ou titre affiché. */
    public static void onServerText(Component c) {
        if (!MODULE.enabled || c == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.isLocalServer() && !Boolean.getBoolean("aurora.devworld")) return;
        String text = c.getString().toLowerCase(Locale.ROOT);
        String me = mc.player.getGameProfile().name().toLowerCase(Locale.ROOT);
        boolean hit = myKill(c, me);
        for (String t : Settings.autoGgTriggers.split(",")) {
            String k = t.trim().toLowerCase(Locale.ROOT).replace("{me}", me);
            if (!k.isEmpty() && text.contains(k)) {
                hit = true;
                break;
            }
        }
        long now = System.currentTimeMillis();
        // une victoire = un message (plusieurs lignes ou titre + chat arrivent ensemble)
        if (!hit || now - lastSent < 15_000 || sendAt >= 0) return;
        List<String> msgs = new ArrayList<>();
        for (String m : new String[]{Settings.autoGg1, Settings.autoGg2, Settings.autoGg3}) if (m != null && !m.isBlank()) msgs.add(m.trim());
        if (msgs.isEmpty()) msgs.add("gg");
        pending = MODULE.opt("order", 0) == 1 ? msgs.get(next++ % msgs.size()) : msgs.get(ThreadLocalRandom.current().nextInt(msgs.size()));
        sendAt = now + (long) (MODULE.opt("delay", 0.75) * 1000);
        lastSent = now;
    }

    public static void tick(Minecraft mc) {
        if (sendAt < 0 || System.currentTimeMillis() < sendAt) return;
        sendAt = -1;
        if (mc.player != null && mc.getConnection() != null && pending != null) {
            if (pending.startsWith("/")) mc.getConnection().sendCommand(pending.substring(1));
            else mc.getConnection().sendChat(pending);
        }
        pending = null;
    }
}
