package gg.aurora.ui;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Settings screens of the other mods. Mods declare them for Mod Menu (« modmenu » entrypoint, ModMenuApi):
 * they are read here by reflection, so Paranoia does not depend on Mod Menu. Without Mod Menu installed,
 * those classes cannot load and only Paranoia's own settings are offered.
 */
public final class ModConfigs {
    private ModConfigs() {}

    private static Map<String, Object> factories;

    private static synchronized Map<String, Object> factories() {
        if (factories != null) return factories;
        Map<String, Object> out = new HashMap<>();
        try {
            for (EntrypointContainer<Object> c : FabricLoader.getInstance().getEntrypointContainers("modmenu", Object.class)) {
                try {
                    Object api = c.getEntrypoint();
                    String id = c.getProvider().getMetadata().getId();
                    Object f = call(api, "getModConfigScreenFactory");
                    if (f != null) out.put(id, f);
                    Object provided = call(api, "getProvidedConfigScreenFactories");
                    if (provided instanceof Map<?, ?> m) {
                        for (Map.Entry<?, ?> e : m.entrySet()) {
                            if (e.getKey() instanceof String k && e.getValue() != null) out.putIfAbsent(k, e.getValue());
                        }
                    }
                } catch (Throwable ignored) {
                    // a mod whose entrypoint needs Mod Menu classes that are not there
                }
            }
        } catch (Throwable ignored) {
            // no entrypoints at all
        }
        factories = out;
        return out;
    }

    /** Public interface method named {@code name} with {@code params} arguments: lambdas are hidden classes. */
    private static Method method(Class<?> c, String name, int params) {
        for (Class<?> i : c.getInterfaces()) {
            for (Method m : i.getMethods()) if (m.getName().equals(name) && m.getParameterCount() == params) return m;
        }
        for (Method m : c.getMethods()) if (m.getName().equals(name) && m.getParameterCount() == params) return m;
        return c.getSuperclass() == null ? null : method(c.getSuperclass(), name, params);
    }

    private static Object call(Object o, String name) throws Exception {
        Method m = method(o.getClass(), name, 0);
        return m == null ? null : m.invoke(o);
    }

    /** True when the mod offers a settings screen (Paranoia always does). */
    public static boolean has(String modId) {
        if (modId == null) return false;
        return gg.aurora.AuroraClient.MOD_ID.equals(modId) || factories().containsKey(modId);
    }

    /** Opens the mod's settings over {@code parent}. False when it has none after all. */
    public static boolean open(String modId, Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        if (gg.aurora.AuroraClient.MOD_ID.equals(modId)) {
            mc.setScreen(ClickGuiScreen.settings(parent));
            return true;
        }
        Object f = factories().get(modId);
        if (f == null) return false;
        try {
            Method create = method(f.getClass(), "create", 1);
            Object screen = create == null ? null : create.invoke(f, parent);
            if (screen instanceof Screen s) {
                mc.setScreen(s);
                return true;
            }
        } catch (Throwable t) {
            gg.aurora.AuroraClient.LOGGER.warn("Settings of {} could not open: {}", modId, t.toString());
        }
        Toasts.push(Icons.INFO, "No settings", "This mod has no settings screen", false);
        return false;
    }
}
