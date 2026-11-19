package dev.relaylyrics.client.ui;

import dev.relaylyrics.client.music.LyricsEffectCache;
import dev.relaylyrics.client.music.LyricsEffectRaster;
import dev.relaylyrics.client.music.PlatformTextures;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;

import java.util.ArrayDeque;

/** Cached, hollow strokes for the front face of the world lyrics. */
final class WorldLyricsOutline {
    private static final ArrayDeque<Integer> FREE_SLOTS = new ArrayDeque<>();
    private static int nextSlot;
    private static boolean enabled;
    private static final LyricsEffectCache<OutlineKey, Texture> CACHE = new LyricsEffectCache<>(
            key -> {
                var mask = LyricsGlyphRasterizer.capture(key.text());
                return mask == null ? null : LyricsEffectRaster.outline(mask, key.square());
            },
            mask -> {
                int slot = FREE_SLOTS.isEmpty() ? nextSlot++ : FREE_SLOTS.removeFirst();
                Identifier id = PlatformTextures.registerEffectMask("world_lyric_outline_" + slot, mask);
                return new Texture(id, WorldOutlineLayer.create(id), slot);
            },
            texture -> {
                PlatformTextures.releaseEffectMask(texture.id());
                FREE_SLOTS.addLast(texture.slot());
            },
            8 * 1024 * 1024);

    private WorldLyricsOutline() { }

    static void clear() { CACHE.clear(); }

    static void tick(boolean active) {
        if (enabled && !active) clear();
        enabled = active;
        CACHE.tick();
    }

    static void draw(Text text, Matrix4f matrix, VertexConsumerProvider buffers, float x, int rgb, double opacity, boolean square) {
        int alpha = (int) Math.round(255 * Math.clamp(opacity, 0, 1));
        if (alpha < 4) return;
        var surface = CACHE.requestExact(new OutlineKey(text, square), 0);
        if (surface == null) return;

        var mask = surface.mask();
        float left = x + (float) mask.left(), top = (float) mask.top();
        float right = left + (float) (mask.width() / mask.pixelsPerUnit());
        float bottom = top + (float) (mask.height() / mask.pixelsPerUnit());
        int color = alpha << 24 | rgb;
        var vertices = buffers.getBuffer(surface.texture().layer());
        vertices.vertex(matrix, left, bottom, -.0125f).color(color).texture(0, 1).light(LightmapTextureManager.MAX_LIGHT_COORDINATE);
        vertices.vertex(matrix, right, bottom, -.0125f).color(color).texture(1, 1).light(LightmapTextureManager.MAX_LIGHT_COORDINATE);
        vertices.vertex(matrix, right, top, -.0125f).color(color).texture(1, 0).light(LightmapTextureManager.MAX_LIGHT_COORDINATE);
        vertices.vertex(matrix, left, top, -.0125f).color(color).texture(0, 0).light(LightmapTextureManager.MAX_LIGHT_COORDINATE);
    }

    private record OutlineKey(Text text, boolean square) { }

    private record Texture(Identifier id, RenderLayer layer, int slot) { }
}
