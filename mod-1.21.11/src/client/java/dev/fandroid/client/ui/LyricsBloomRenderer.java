package dev.fandroid.client.ui;

import net.minecraft.client.gui.DrawContext;

/**
 * 1.21.11 moved post effects to the frame graph. Relay keeps the enhanced lyric
 * pass on this version as a lightweight additive software pass, avoiding the
 * old forced Compatible downgrade.
 */
final class LyricsBloomRenderer implements AutoCloseable {
    private boolean clipped;

    boolean begin(DrawContext context, int clipLeft, int clipTop, int clipRight, int clipBottom,
                  boolean softwareClip) {
        if (!softwareClip) return false;
        clipped = softwareClip;
        if (clipped) context.enableScissor(clipLeft, clipTop, clipRight, clipBottom);
        return true;
    }

    boolean finish(DrawContext context, int clipLeft, int clipTop, int clipRight, int clipBottom,
                   float radius, boolean clip) {
        if (clipped) context.disableScissor();
        clipped = false;
        return true;
    }

    @Override
    public void close() { clipped = false; }
}
