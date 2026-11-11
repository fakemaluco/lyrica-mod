package dev.relaylyrics.client.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.util.LinkedHashMap;
import java.util.Map;

/** Read back a changed font atlas on the render thread before preparing cached meshes. */
public final class WorldGlyphAtlas {
    private static final Map<RenderType, Source> SOURCES = new LinkedHashMap<>();
    private WorldGlyphAtlas() { }

    public static void releaseSnapshots() {
        for (Source source : SOURCES.values()) source.pixels = null;
    }

    public static void changed(RenderType layer, GpuTexture texture, boolean color) {
        SOURCES.put(layer, new Source(texture, color));
        while (SOURCES.size() > 128) SOURCES.remove(SOURCES.keySet().iterator().next());
    }

    static Pixels snapshot(RenderType layer) {
        Source source = SOURCES.get(layer);
        if (source == null || source.texture.isClosed()) return null;
        if (source.pixels != null) return source.pixels;
        var texture = source.texture;
        int width = texture.getWidth(0), height = texture.getHeight(0);
        int bytes = texture.getFormat().blockSize();
        var device = RenderSystem.getDevice();
        var encoder = device.createCommandEncoder();
        try (var buffer = device.createBuffer(() -> "Lyrica font atlas", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                (long) width * height * bytes)) {
            encoder.copyTextureToBuffer(texture, buffer, 0, () -> { }, 0);
            try (var fence = encoder.createFence()) {
                encoder.submit();
                if (!fence.awaitCompletion(100_000_000L)) return null;
                try (var mapped = buffer.map(true, false)) {
                    var pixels = mapped.data();
                    byte[] mask = new byte[width * height];
                    for (int index = 0; index < mask.length; index++)
                        mask[index] = pixels.get(index * bytes + (source.color ? 3 : 0));
                    return source.pixels = new Pixels(width, height, mask);
                }
            }
        }
    }

    record Pixels(int width, int height, byte[] mask) {
        int alpha(int x, int y) {
            return x >= 0 && y >= 0 && x < width && y < height ? mask[y * width + x] & 255 : 0;
        }
        boolean solid(int x, int y) { return alpha(x, y) >= 128; }
    }

    private static final class Source {
        final GpuTexture texture;
        final boolean color;
        Pixels pixels;
        Source(GpuTexture texture, boolean color) { this.texture = texture; this.color = color; }
    }
}
