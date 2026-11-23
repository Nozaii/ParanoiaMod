package gg.aurora;

import gg.aurora.ui.Icons;
import gg.aurora.ui.Toasts;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Kicked by a server because of a mod: the mods named in the kick message are turned off, then the game joins the
 * server again. With the hot mods runtime they stop right away; otherwise their jar is disabled and the game offers
 * to restart straight back onto the server (a mod Fabric loaded at startup cannot be unloaded from a running game).
 */
public final class ForbiddenMods {
    private ForbiddenMods() {}

    /** Words a mod-ban kick message carries (several anti-cheats and mod blockers, English and French). Not « banned »:
     *  a plain ban would then turn off any mod whose name is a common word of the message. */
    private static final Pattern BAN = Pattern.compile(
            "mod|forbidden|not allowed|disallowed|prohibited|blacklist|illegal|unauthori[sz]ed|interdit|autoris");
    /** Never turned off: the game, the loader, Fabric API, Paranoia and its runtime. */
    private static final Set<String> KEEP = Set.of("minecraft", "java", "fabricloader", "mixinextras", AuroraClient.MOD_ID, "paranoia_dynamic");

    /** Server being joined, remembered from {@link ConnectScreen#startConnecting} (the kick can come before login ends). */
    private static volatile ServerData lastServer;
    /** Last automatic rejoin: a server that keeps kicking is not joined again in a loop. */
    private static String rejoinedHost;
    private static long rejoinedAt;

    public static void connecting(ServerData server) {
        lastServer = server;
    }

    /** A jar to turn off, named after what the kick message matched. */
    private record Target(String modId, String name, Path jar) {}

    /** Called with the kick message when the disconnect screen opens, on the game thread. */
    public static void kicked(Screen parent, Component reason) {
        ServerData server = lastServer;
        if (server == null || reason == null) return;
        String text = reason.getString().toLowerCase(Locale.ROOT);
        if (!BAN.matcher(text).find()) return;
        List<Target> targets = find(text);
        if (targets.isEmpty()) return;

        List<String> names = targets.stream().map(Target::name).toList();
        Toasts.push(Icons.LOCK, "Forbidden on this server", String.join(", ", names), false);
        Thread t = new Thread(() -> turnOff(parent, server, targets), "Paranoia forbidden mods");
        t.setDaemon(true);
        t.start();
    }

    /** Mods of this instance whose id or name appears as a whole word in the message. */
    private static List<Target> find(String text) {
        Map<Path, Target> out = new LinkedHashMap<>();
        for (ModContainer m : FabricLoader.getInstance().getAllMods()) {
            String id = m.getMetadata().getId();
            if (KEEP.contains(id) || id.startsWith("fabric")) continue;
            if (!named(text, id) && !named(text, m.getMetadata().getName())) continue;
            Target t = jarOf(m);
            if (t != null) out.putIfAbsent(t.jar(), t);
        }
        if (!out.isEmpty()) addDependents(out);
        // mods started later by the hot mods runtime are not in Fabric's list
        for (ModManager.Installed i : ModManager.installed) {
            if (!i.live || !i.enabled || i.self() || i.modId == null || KEEP.contains(i.modId)) continue;
            if (named(text, i.modId) || named(text, i.name)) out.putIfAbsent(i.path.toAbsolutePath().normalize(), new Target(i.modId, i.name, i.path));
        }
        return new ArrayList<>(out.values());
    }

    /** The jar in the mods folder that holds this mod (a bundled mod: the jar it is bundled in), or null. */
    private static Target jarOf(ModContainer m) {
        ModContainer top = m;
        while (top.getContainingMod().isPresent()) top = top.getContainingMod().get();
        if (KEEP.contains(top.getMetadata().getId()) || top.getOrigin().getKind() != ModOrigin.Kind.PATH) return null;
        Path mods = ModManager.modsFolder().toAbsolutePath().normalize();
        for (Path p : top.getOrigin().getPaths()) {
            Path abs = p.toAbsolutePath().normalize();
            if (mods.equals(abs.getParent()) && abs.toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                return new Target(top.getMetadata().getId(), top.getMetadata().getName(), abs);
        }
        return null;
    }

    /**
     * Mods that require a mod being turned off go with it (e.g. an add-on of ViaFabricPlus): left alone, Fabric
     * would refuse to start at the next launch because their dependency is missing.
     */
    private static void addDependents(Map<Path, Target> out) {
        Collection<ModContainer> all = FabricLoader.getInstance().getAllMods();
        boolean grew = true;
        while (grew) {
            grew = false;
            Set<String> gone = new HashSet<>();
            for (ModContainer m : all) {
                Target t = jarOf(m);
                if (t == null || !out.containsKey(t.jar())) continue;
                gone.add(m.getMetadata().getId());
                gone.addAll(m.getMetadata().getProvides());
            }
            for (ModContainer m : all) {
                Target t = jarOf(m);
                if (t == null || out.containsKey(t.jar())) continue;
                for (ModDependency d : m.getMetadata().getDependencies()) {
                    if (d.getKind() == ModDependency.Kind.DEPENDS && gone.contains(d.getModId())) {
                        out.put(t.jar(), t);
                        grew = true;
                        break;
                    }
                }
            }
        }
    }

    private static boolean named(String text, String word) {
        if (word == null) return false;
        String w = word.toLowerCase(Locale.ROOT).trim();
        if (w.length() < 3) return false; // « ok », « ui »… would match anything
        return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(w) + "(?![a-z0-9])").matcher(text).find();
    }

    /** Off the game thread: the hot mods runtime waits for the game thread. */
    private static void turnOff(Screen parent, ServerData server, List<Target> targets) {
        List<String> atRestart = new ArrayList<>();
        for (Target t : targets) {
            boolean live = HotBridge.PRESENT && HotBridge.done(HotBridge.setEnabled(t.modId(), false));
            try {
                Path off = t.jar().resolveSibling(t.jar().getFileName() + ".disabled");
                if (live) Files.move(t.jar(), off);
                else ModManager.disableAtRestart(t.jar(), off);
            } catch (Exception e) {
                AuroraClient.LOGGER.warn("Forbidden mods: could not disable {}: {}", t.jar(), e.toString());
            }
            if (!live) atRestart.add(t.name());
        }
        ModManager.scan();
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (atRestart.isEmpty()) {
                Toasts.push(Icons.BOLT, "Turned off, no restart", "Joining " + server.name + " again", false);
                rejoin(mc, parent, server);
                return;
            }
            mc.setScreen(new ConfirmScreen(ok -> {
                if (ok) ModManager.restartAndJoin(server.ip);
                else mc.setScreen(parent);
            }, Component.literal("Forbidden on " + server.name),
                    Component.literal(String.join(", ", atRestart) + " cannot stop while the game runs. It is disabled from"
                            + " the next launch: restart now and join " + server.name + " again?"),
                    Component.literal("Restart and join"), Component.literal("Later")));
        });
    }

    private static void rejoin(Minecraft mc, Screen parent, ServerData server) {
        long now = System.currentTimeMillis();
        if (server.ip.equals(rejoinedHost) && now - rejoinedAt < 60_000L) return; // kicked again: let the player read why
        rejoinedHost = server.ip;
        rejoinedAt = now;
        ConnectScreen.startConnecting(parent, mc, ServerAddress.parseString(server.ip), server, false, null);
    }
}
