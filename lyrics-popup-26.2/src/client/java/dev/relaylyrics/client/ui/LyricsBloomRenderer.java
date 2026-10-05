package dev.relaylyrics.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** The legacy post-effect renderer is unavailable on the 26.2 extraction pipeline. */
final class LyricsBloomRenderer implements AutoCloseable {
    static void register() { }

    boolean begin(GuiGraphicsExtractor context, int left, int top, int right, int bottom,
                  boolean softwareClip) {
        return false;
    }

    void drawGlyph(GuiGraphicsExtractor context, Font renderer, Component text,
                   double x, double y, double scale, int color,
                   int clipLeft, int clipTop, int clipRight, int clipBottom) { }

    boolean finish(GuiGraphicsExtractor context, int left, int top, int right, int bottom,
                   float radius, boolean clip, boolean additive) {
        return false;
    }

    @Override public void close() { }
}
