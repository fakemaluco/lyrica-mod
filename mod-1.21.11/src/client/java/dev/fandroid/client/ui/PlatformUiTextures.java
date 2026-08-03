package dev.fandroid.client.ui;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/** Minecraft 1.21.11 static GUI texture adapter. */
final class PlatformUiTextures {
    private PlatformUiTextures() { }

    static void draw(DrawContext context, Identifier id, int x, int y, int width, int height) {
        context.drawTexture(RenderPipelines.GUI_TEXTURED, id, x, y,
                0, 0, width, height, 128, 128, 0xFFFFFFFF);
    }
}
