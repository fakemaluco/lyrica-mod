package dev.fandroid.client.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/** Minecraft 1.21.1 static GUI texture adapter. */
final class PlatformUiTextures {
    private PlatformUiTextures() { }

    static void draw(DrawContext context, Identifier id, int x, int y, int width, int height) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        context.drawTexture(id, x, y, 0, 0, width, height, 128, 128);
    }
}
