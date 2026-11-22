package gg.aurora;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

/**
 * The Paranoia Dynamic Runtime, when it is installed: it starts, stops, updates and removes mods without a restart,
 * applying their mixins to classes that are already loaded. Reached by reflection (no build-time link): without it,
 * every answer is "not available" and the mod manager works as before.
 * <p>
 * Answers: {@code DONE}, or {@code RESTART}, {@code REFUSED} or {@code FAILED} followed by a tab and the reason. Never
 * called on the game thread (the runtime would wait for it).
 */
final class HotBridge {
    private HotBridge() {}

    static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("paranoia_dynamic");
    private static final String API = "gg.paranoia.dynamic.runtime.HotModsApi";

    static String installForSession(List<Path> jars) {
        return call("installForSession", new Class<?>[]{List.class}, jars);
    }

    static String remove(String modId) {
        return call("remove", new Class<?>[]{String.class}, modId);
    }

    static String setEnabled(String modId, boolean on) {
        return call("setEnabled", new Class<?>[]{String.class, boolean.class}, modId, on);
    }

    static String update(Path jar) {
        return call("update", new Class<?>[]{Path.class}, jar);
    }

    /**
     * Reads a jar without loading it: {@code HOT\t<note>}, {@code RESTART\t<reasons>}, {@code INGAME\t<id>} or
     * {@code FAILED\t<why>}. Off the game thread (slow).
     */
    static String check(Path jar) {
        return call("check", new Class<?>[]{Path.class}, jar);
    }

    /** The runtime's state for the mod (e.g. ACTIVE, DISABLED), or null when it does not run it. */
    static String state(String modId) {
        if (!PRESENT || modId == null) return null;
        String s = call("state", new Class<?>[]{String.class}, modId);
        return s == null || s.startsWith("FAILED\t") ? null : s;
    }

    static boolean done(String answer) {
        return "DONE".equals(answer);
    }

    static boolean restart(String answer) {
        return answer != null && answer.startsWith("RESTART\t");
    }

    static String reason(String answer) {
        if (answer == null) return "the hot mods runtime did not answer";
        int t = answer.indexOf('\t');
        return t < 0 ? answer : answer.substring(t + 1);
    }

    private static String call(String name, Class<?>[] types, Object... args) {
        if (!PRESENT) return "REFUSED\tthe hot mods runtime is not installed";
        try {
            Method m = Class.forName(API).getMethod(name, types);
            return (String) m.invoke(null, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            AuroraClient.LOGGER.warn("Hot mods runtime: {} failed", name, e.getCause());
            return "FAILED\t" + e.getCause();
        } catch (ReflectiveOperationException | LinkageError e) {
            AuroraClient.LOGGER.warn("Hot mods runtime unavailable ({})", e.toString());
            return "FAILED\tthe hot mods runtime does not offer " + name + " (an older version?)";
        }
    }
}
