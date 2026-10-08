package dev.relaylyrics.client.ui;

import dev.relaylyrics.client.music.LyricsEffectRaster;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.*;
import net.minecraft.text.Text;
import org.joml.Matrix4f;
import java.util.*;

/** Captures the exact shaped font quads without drawing a second copy of the text. */
final class LyricsGlyphRasterizer {
    private LyricsGlyphRasterizer() { }

    static LyricsEffectRaster.Mask capture(Text text) {
        Map<RenderLayer, Capture> captures = new LinkedHashMap<>();
        MinecraftClient.getInstance().textRenderer.draw(text, 0, 0, 0xFFFFFFFF, false, new Matrix4f(),
                layer -> captures.computeIfAbsent(layer, ignored -> new Capture()),
                TextRenderer.TextLayerType.NORMAL, 0, LightmapTextureManager.MAX_LIGHT_COORDINATE);
        List<LyricsEffectRaster.Quad> quads = new ArrayList<>();
        for (var item : captures.entrySet()) {
            item.getValue().flush();
            var atlas = WorldGlyphAtlas.snapshot(item.getKey());
            if (atlas == null) return null;
            for (Quad quad : item.getValue().quads) {
                int x0 = Math.round(quad.u0 * atlas.width()), x1 = Math.round(quad.u1 * atlas.width());
                int y0 = Math.round(quad.v0 * atlas.height()), y1 = Math.round(quad.v1 * atlas.height());
                int width = x1 - x0, height = y1 - y0;
                if (width <= 0 || height <= 0 || width > 1024 || height > 1024) continue;
                byte[] alpha = new byte[width * height];
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
                    alpha[y * width + x] = (byte) atlas.alpha(x0 + x, y0 + y);
                quads.add(new LyricsEffectRaster.Quad(quad.x0, quad.y0, quad.x1, quad.y1,
                        width, height, alpha));
            }
        }
        return LyricsEffectRaster.compose(quads, 4);
    }

    private static final class Capture implements VertexConsumer {
        final List<Quad> quads = new ArrayList<>();
        float x0, y0, x1, y1, u0, v0, u1, v1;
        int count;
        public VertexConsumer vertex(float x, float y, float z) {
            if (count == 4) flush();
            if (count++ == 0) {
                x0 = x1 = x; y0 = y1 = y;
                u0 = v0 = Float.MAX_VALUE; u1 = v1 = -Float.MAX_VALUE;
            } else {
                x0 = Math.min(x0, x); y0 = Math.min(y0, y);
                x1 = Math.max(x1, x); y1 = Math.max(y1, y);
            }
            return this;
        }
        public VertexConsumer texture(float u, float v) {
            u0 = Math.min(u0, u); v0 = Math.min(v0, v);
            u1 = Math.max(u1, u); v1 = Math.max(v1, v);
            return this;
        }
        public VertexConsumer color(int r, int g, int b, int a) { return this; }
        public VertexConsumer color(int color) { return this; }
        public VertexConsumer overlay(int u, int v) { return this; }
        public VertexConsumer light(int u, int v) { return this; }
        public VertexConsumer normal(float x, float y, float z) { return this; }
        public VertexConsumer lineWidth(float width) { return this; }
        void flush() {
            if (count == 4) quads.add(new Quad(x0, y0, x1, y1, u0, v0, u1, v1));
            count = 0;
        }
    }
    private record Quad(float x0, float y0, float x1, float y1,
                        float u0, float v0, float u1, float v1) { }
}
