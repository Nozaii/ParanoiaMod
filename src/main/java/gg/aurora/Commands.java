package gg.aurora;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import gg.aurora.ui.AuroraScreen;
import gg.aurora.ui.FriendsScreen;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Commandes client : /paranoia accept|ignore <pseudo>, /paranoia add <pseudo>,
 * /paranoia msg <pseudo> <message>, /paranoia friends (ouvre la page Amis).
 */
final class Commands {
    private Commands() {}

    private static void reply(FabricClientCommandSource src, String text, int color) {
        src.sendFeedback(Component.literal(text).withStyle(Style.EMPTY.withColor(color)));
    }

    static void register(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(literal("paranoia")
                .then(literal("wp").then(argument("code", StringArgumentType.word()).executes(c -> {
                    gg.aurora.waypoint.WaypointShare.save(StringArgumentType.getString(c, "code"));
                    return 1;
                })))
                .then(literal("bench").executes(c -> {
                    Bench.start(20);
                    return 1;
                }).then(argument("secondes", com.mojang.brigadier.arguments.IntegerArgumentType.integer(10, 120)).executes(c -> {
                    Bench.start(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "secondes"));
                    return 1;
                })))
                .then(literal("profile").executes(c -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> mc.execute(() -> mc.setScreen(new gg.aurora.ui.ProfileScreen(null))));
                    return 1;
                }).then(argument("player", StringArgumentType.word()).executes(c -> {
                    String who = StringArgumentType.getString(c, "player");
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> mc.execute(() -> mc.setScreen(new gg.aurora.ui.ProfileScreen(null, who))));
                    return 1;
                })))
                .then(literal("friends").executes(c -> {
                    Minecraft mc = Minecraft.getInstance();
                    // la page s'ouvre après la fermeture du chat
                    mc.execute(() -> mc.execute(() -> {
                        var gui = new gg.aurora.ui.ClickGuiScreen();
                        mc.setScreen(gui);
                        gui.debugTab(6); // page Amis dans le menu
                    }));
                    return 1;
                }))
                .then(literal("add").then(argument("pseudo", StringArgumentType.word()).executes(c -> {
                    String name = StringArgumentType.getString(c, "pseudo");
                    Friends.add(name, err -> reply(c.getSource(), err == null ? "Request sent to " + name : err,
                            err == null ? 0x5EE6A8 : 0xF2545B));
                    return 1;
                })))
                .then(literal("accept").then(argument("pseudo", StringArgumentType.word()).executes(c -> {
                    var r = Friends.findIncoming(StringArgumentType.getString(c, "pseudo"));
                    if (r == null) {
                        reply(c.getSource(), "No request from this player", 0xF2545B);
                        return 0;
                    }
                    Friends.respond(r.id(), true, err -> reply(c.getSource(),
                            err == null ? r.name() + " is now your friend" : err, err == null ? 0x5EE6A8 : 0xF2545B));
                    return 1;
                })))
                .then(literal("ignore").then(argument("pseudo", StringArgumentType.word()).executes(c -> {
                    var r = Friends.findIncoming(StringArgumentType.getString(c, "pseudo"));
                    if (r == null) {
                        reply(c.getSource(), "No request from this player", 0xF2545B);
                        return 0;
                    }
                    Friends.respond(r.id(), false, err -> reply(c.getSource(),
                            err == null ? "Request from " + r.name() + " ignored" : err, err == null ? 0xBDB8D6 : 0xF2545B));
                    return 1;
                })))
                .then(literal("msg").then(argument("pseudo", StringArgumentType.word())
                        .then(argument("message", StringArgumentType.greedyString()).executes(c -> {
                            var f = Friends.findFriend(StringArgumentType.getString(c, "pseudo"));
                            if (f == null) {
                                reply(c.getSource(), "This player is not your friend", 0xF2545B);
                                return 0;
                            }
                            String text = StringArgumentType.getString(c, "message");
                            Friends.send(f.id(), text, err -> reply(c.getSource(),
                                    err == null ? "✉ → " + f.name() + " : " + text : err, err == null ? 0xB07CFF : 0xF2545B));
                            return 1;
                        })))));
    }
}
