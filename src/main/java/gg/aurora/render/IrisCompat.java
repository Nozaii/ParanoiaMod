package gg.aurora.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import gg.aurora.AuroraClient;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;

/**
 * Pont vers l'API d'Iris (sans dépendance de compilation) : savoir si un pack de shaders est actif, et
 * dire à Iris quel programme du pack utiliser pour nos rendus (sinon il les dessine « à côté » du pack).
 */
public final class IrisCompat {
    private IrisCompat() {}

    public static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("iris");
    private static Object api;
    private static Method inUse, assign;
    private static Class<?> programClass;
    private static boolean broken;

    private static boolean init() {
        if (!PRESENT || broken) return false;
        if (api != null) return true;
        try {
            Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            api = c.getMethod("getInstance").invoke(null);
            inUse = c.getMethod("isShaderPackInUse");
            programClass = Class.forName("net.irisshaders.iris.api.v0.IrisProgram");
            try {
                assign = c.getMethod("assignPipeline", RenderPipeline.class, programClass);
            } catch (NoSuchMethodException e) {
                assign = null; // Iris trop ancien : pas d'assignation possible
            }
            return true;
        } catch (Throwable t) {
            broken = true;
            AuroraClient.LOGGER.warn("API Iris indisponible : {}", t.toString());
            return false;
        }
    }

    /** Un pack de shaders Iris est-il en train de rendre le monde ? */
    public static boolean shaderPackInUse() {
        if (!init()) return false;
        try {
            return (boolean) inUse.invoke(api);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Associe notre pipeline à un programme du pack (ex. « SKY_TEXTURED », « ENTITIES_TRANSLUCENT »). */
    public static void assign(RenderPipeline pipeline, String program) {
        if (!init() || assign == null) return;
        try {
            Object p = programClass.getField(program).get(null);
            assign.invoke(api, pipeline, p);
        } catch (Throwable t) {
            AuroraClient.LOGGER.warn("Iris : programme {} non assigné à {} ({})", program, pipeline.getLocation(), t.toString());
        }
    }
}
