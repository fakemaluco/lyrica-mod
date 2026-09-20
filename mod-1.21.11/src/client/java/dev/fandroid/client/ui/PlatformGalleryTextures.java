package dev.fandroid.client.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

/** Minecraft 1.21.11 dynamic gallery-texture adapter. */
final class PlatformGalleryTextures {
    private PlatformGalleryTextures() { }

    static Identifier install(String name, NativeImage image) {
        NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> name, image);
        Identifier id = Identifier.of("fandroid", "dynamic/" + name);
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
        return id;
    }

    static void draw(DrawContext context, Identifier id, int x, int y, int width, int height,
                     int sourceWidth, int sourceHeight) {
        context.drawTexture(RenderPipelines.GUI_TEXTURED, id, x, y, 0, 0,
                width, height, sourceWidth, sourceHeight, sourceWidth, sourceHeight, 0xFFFFFFFF);
    }
}
