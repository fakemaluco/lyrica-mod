package dev.relaylyrics.client.ui;

import dev.relaylyrics.client.music.LyricsEffectCache;
import dev.relaylyrics.client.music.LyricsEffectRaster;
import dev.relaylyrics.client.music.PlatformTextures;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** One cached Gaussian glyph mask shared by all Minecraft rendering adapters. */
final class LyricsBloomRenderer implements AutoCloseable {
    private static final AtomicInteger TEXTURE_SEQUENCE = new AtomicInteger();
    private final LyricsEffectCache<Component, Identifier> cache = new LyricsEffectCache<>(
            LyricsGlyphRasterizer::capture,
            mask -> PlatformTextures.registerEffectMask("lyric_effect_" + TEXTURE_SEQUENCE.incrementAndGet(), mask),
            PlatformTextures::releaseEffectMask, 32 * 1024 * 1024);
    private final List<GlowGlyph> glyphs = new ArrayList<>();
    private boolean recording;

    static void register() { }
    void tick() { cache.tick(); }
    void clear() { cache.clear(); }

    /** Queue nearby lyrics before their glow starts; capture stays on the client tick. */
    void prepare(Component text, double radius) { cache.request(text, radius); }

    boolean begin(GuiGraphicsExtractor context, int left, int top, int right, int bottom,
                  boolean softwareClip) {
        glyphs.clear();
        recording = right > left && bottom > top;
        return recording;
    }

    void drawGlyph(GuiGraphicsExtractor context, Font renderer, Component text,
                   double x, double y, double scale, int color,
                   int clipLeft, int clipTop, int clipRight, int clipBottom) {
        drawGlyph(context, renderer, text, x, y, scale, color,
                clipLeft, clipTop, clipRight, clipBottom, Double.NaN);
    }

    void drawGlyph(GuiGraphicsExtractor context, Font renderer, Component text,
                   double x, double y, double scale, int color,
                   int clipLeft, int clipTop, int clipRight, int clipBottom,
                   double blurRadius) {
        if (!recording || glyphs.size() >= 128 || renderer == null || text == null
                || (color >>> 24) == 0 || scale <= 0) return;
        // CSS text-shadow uses the entire active word/glyph, including its unfilled face.
        glyphs.add(new GlowGlyph(text, x, y, scale, color, blurRadius));
    }

    boolean finish(GuiGraphicsExtractor context, int left, int top, int right, int bottom,
                   float radius, boolean clip, boolean additive) {
        recording = false;
        if (clip) GuiTransform.enableScissor(context, left, top, right, bottom);
        try {
            for (GlowGlyph glyph : glyphs) {
                double blur = Double.isFinite(glyph.blurRadius()) ? glyph.blurRadius() : radius;
                drawMask(context, glyph.text(), glyph.x(), glyph.y(), glyph.scale(), glyph.color(), blur);
            }
            return true;
        } finally {
            if (clip) context.disableScissor();
            glyphs.clear();
        }
    }

    void drawShadow(GuiGraphicsExtractor context, Font renderer, Component text,
                    double x, double y, double scale, int color,
                    double opacity, double distance, int softness) {
        int alpha = Math.clamp((int) Math.round((color >>> 24) * opacity), 0, 255);
        if (renderer == null || text == null || alpha == 0) return;
        double blur = softness <= 0 ? 0 : softness == 1 ? .65 : 1.25;
        drawMask(context, text, x + distance * scale, y + distance * scale,
                scale, alpha << 24, blur);
    }

    private void drawMask(GuiGraphicsExtractor context, Component text, double x, double y,
                          double scale, int color, double radius) {
        var surface = cache.request(text, radius);
        if (surface == null) return;
        LyricsEffectRaster.Mask mask = surface.mask();
        GuiTransform.push(context);
        try {
            GuiTransform.translate(context, (float) (x + mask.left() * scale),
                    (float) (y + mask.top() * scale));
            float pixelScale = (float) (scale / mask.pixelsPerUnit());
            GuiTransform.scale(context, pixelScale, pixelScale);
            PlatformTextures.drawTinted(context, surface.texture(), 0, 0, mask.width(), mask.height(),
                    0, 0, mask.width(), mask.height(), mask.width(), mask.height(), color);
        } finally {
            GuiTransform.pop(context);
        }
    }

    @Override public void close() { recording = false; glyphs.clear(); cache.close(); }

    private record GlowGlyph(Component text, double x, double y, double scale, int color, double blurRadius) { }
}
