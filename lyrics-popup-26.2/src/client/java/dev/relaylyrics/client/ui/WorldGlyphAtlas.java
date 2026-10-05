package dev.relaylyrics.client.ui;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.renderer.rendertype.RenderType;

/** Readback is limited to changed font atlases during queued mesh preparation, never during drawing. */
public final class WorldGlyphAtlas {
    private static final Map<RenderType, Source> SOURCES = new LinkedHashMap<>();
    private WorldGlyphAtlas() { }

    public static void changed(RenderType layer, int textureId, boolean color) {
        Source source = SOURCES.get(layer);
        if (source == null || source.textureId != textureId) SOURCES.put(layer, new Source(textureId, color));
        else source.pixels = null;
        while (SOURCES.size() > 128) SOURCES.remove(SOURCES.keySet().iterator().next());
    }

    static Pixels snapshot(RenderType layer) {
        Source source = SOURCES.get(layer);
        if (source == null) return null;
        if (source.pixels != null) return source.pixels;
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int alignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int rowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int skipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int skipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        int packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        ByteBuffer buffer = null;
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, source.textureId);
            int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (width <= 0 || height <= 0 || width > 2048 || height > 2048) return null;
            buffer = MemoryUtil.memAlloc(width * height * 4);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
            byte[] mask = new byte[width * height];
            for (int i = 0; i < mask.length; i++) mask[i] = buffer.get(i * 4 + (source.color ? 3 : 0));
            return source.pixels = new Pixels(width, height, mask);
        } finally {
            if (buffer != null) MemoryUtil.memFree(buffer);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, alignment);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, rowLength);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, skipRows);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, skipPixels);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        }
    }

    record Pixels(int width, int height, byte[] mask) {
        boolean solid(int x, int y) {
            return x >= 0 && y >= 0 && x < width && y < height && (mask[y * width + x] & 255) >= 128;
        }
    }
    private static final class Source {
        final int textureId;
        final boolean color;
        Pixels pixels;
        Source(int textureId, boolean color) { this.textureId = textureId; this.color = color; }
    }
}
