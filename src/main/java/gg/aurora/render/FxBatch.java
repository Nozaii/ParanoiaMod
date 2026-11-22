package gg.aurora.render;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;

/**
 * Regroupe la lumière additive de tous les effets du monde (aura, familier, traînée, particules
 * d'ambiance, effets de coup, cercles de saut, bulles « en train d'écrire ») en un appel de dessin par
 * texture, vidé une seule fois à la fin de AFTER_ENTITIES. Avant, chaque système vidait son propre
 * lot : une passe de rendu (15 à 40 µs de CPU) par système.
 *
 * Le mélange additif ne dépend pas de l'ordre : regrouper ne change rien entre effets additifs. Seule
 * différence : la lumière située derrière un corps translucide (cloche du familier) est maintenant
 * cachée par lui, comme un vrai objet derrière.
 *
 * Les autres types de rendu passent tels quels à la source du monde. Sous un pack de shaders, Iris gère
 * lui-même les tampons : pas de regroupement.
 */
public final class FxBatch extends MultiBufferSource.BufferSource {
    /** Interrupteur de comparaison (AbTest) : true = ancien chemin, un lot par système. */
    public static boolean legacy;

    private static final Identifier[] TEXTURES = {
            Identifier.fromNamespaceAndPath("aurora", "textures/ui/glow.png"),
            Identifier.fromNamespaceAndPath("aurora", "textures/cosmetic/white.png")};

    private static FxBatch instance;
    private MultiBufferSource.BufferSource world;

    private FxBatch(LinkedHashMap<RenderType, ByteBufferBuilder> fixed) {
        super(new ByteBufferBuilder(256), fixed);
    }

    /** Début de la passe : renvoie la source à donner aux effets (ou celle du monde, inchangée). */
    public static MultiBufferSource begin(MultiBufferSource consumers) {
        if (legacy || !(consumers instanceof MultiBufferSource.BufferSource bs) || IrisCompat.shaderPackInUse()) return consumers;
        if (instance == null) {
            LinkedHashMap<RenderType, ByteBufferBuilder> fixed = new LinkedHashMap<>();
            for (Identifier t : TEXTURES) {
                RenderType type = FxRender.additive(t);
                fixed.put(type, new ByteBufferBuilder(type.bufferSize()));
            }
            instance = new FxBatch(fixed);
        }
        instance.world = bs;
        return instance;
    }

    /** Fin de la passe : un appel de dessin par texture additive utilisée. */
    public static void end(MultiBufferSource used) {
        if (used != instance || instance.world == null) return;
        for (RenderType t : instance.fixedBuffers.keySet()) instance.flush(t);
        instance.world = null;
    }

    private void flush(RenderType type) {
        super.endBatch(type);
    }

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        return fixedBuffers.containsKey(type) ? super.getBuffer(type) : world.getBuffer(type);
    }

    /** Lumière additive : différée jusqu'à end(). Le reste : vidé tout de suite, comme avant. */
    @Override
    public void endBatch(RenderType type) {
        if (!fixedBuffers.containsKey(type)) world.endBatch(type);
    }

    @Override
    public void endBatch() {
        world.endBatch();
    }

    @Override
    public void endLastBatch() {
        world.endLastBatch();
    }
}
