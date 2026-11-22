package gg.aurora.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.MappableRingBuffer;

/**
 * Bloc d'uniforms d'une passe plein écran. Le tampon CPU est réutilisé (plus d'allocation sur la pile
 * à chaque image) ; l'envoi au GPU suit {@link #mode}.
 * Usage : {@code ring.begin().putMat4f(...)...; rp.setUniform(name, ring.commit());}
 */
public final class UniformRing {
    /**
     * Mesuré en A/B (même session, médianes) : recréer un petit buffer à chaque image est le plus rapide ici
     * (persistant + écriture : +60 µs, anneau mappé : +144 µs, le pilote attend le GPU). 2 = défaut.
     * 0 = persistant + écriture, 1 = anneau mappé : gardés pour re-mesurer sur d'autres pilotes (VulkanMod).
     */
    public static int mode = 2;
    private final String label;
    private final int size;
    private MappableRingBuffer ring;
    private GpuBuffer persistent;
    /** Buffers already handed to the GPU. VulkanMod binds them in place with frames in flight: keep a few alive. */
    private final java.util.ArrayDeque<GpuBuffer> live = new java.util.ArrayDeque<>();
    private static final int KEEP = TexCopy.VULKAN ? 4 : 1;
    private java.nio.ByteBuffer staging;

    public UniformRing(String label, int size) {
        this.label = label;
        this.size = size;
    }

    /** Tampon CPU à remplir (Std140), puis {@link #commit()} pour l'envoyer et obtenir le buffer à lier. */
    public Std140Builder begin() {
        if (staging == null) staging = org.lwjgl.system.MemoryUtil.memAlloc(size);
        staging.clear();
        return Std140Builder.intoBuffer(staging);
    }

    public GpuBuffer commit() {
        staging.position(0).limit(size);
        var dev = RenderSystem.getDevice();
        while (live.size() >= KEEP) live.poll().close();
        switch (mode) {
            case 1 -> {
                if (ring == null) ring = new MappableRingBuffer(() -> label, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, size);
                ring.rotate();
                try (GpuBuffer.MappedView v = dev.createCommandEncoder().mapBuffer(ring.currentBuffer(), false, true)) {
                    v.data().put(staging);
                }
                return ring.currentBuffer();
            }
            case 2 -> {
                GpuBuffer b = dev.createBuffer(() -> label, GpuBuffer.USAGE_UNIFORM, staging);
                live.add(b);
                return b;
            }
            default -> {
                if (persistent == null) persistent = dev.createBuffer(() -> label, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, size);
                dev.createCommandEncoder().writeToBuffer(persistent.slice(), staging);
                return persistent;
            }
        }
    }
}
