package dev.relaylyrics.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

/** Emits prepared vanilla glyphs into the same consumers as the world mesh. */
public final class FontCompat {
    private FontCompat() { }

    public static void drawInBatch(Font font, Component text, float x, float y, int color,
                            boolean shadow, Matrix4f matrix, MultiBufferSource buffers,
                            Font.DisplayMode mode, int background, int light) {
        font.prepareText(text.getVisualOrderText(), x, y, color, shadow, false, background)
                .visit(new Font.GlyphVisitor() {
                    @Override public void acceptRenderable(TextRenderable glyph) {
                        glyph.render(matrix, buffers.getBuffer(glyph.renderType(mode)), light, false);
                    }
                });
    }

    static void drawInBatch(Font font, String text, float x, float y, int color,
                            boolean shadow, Matrix4f matrix, MultiBufferSource buffers,
                            Font.DisplayMode mode, int background, int light) {
        font.prepareText(text, x, y, color, shadow, background)
                .visit(new Font.GlyphVisitor() {
                    @Override public void acceptRenderable(TextRenderable glyph) {
                        glyph.render(matrix, buffers.getBuffer(glyph.renderType(mode)), light, false);
                    }
                });
    }
}
