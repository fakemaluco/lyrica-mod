package dev.fandroid.client.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

/** Minecraft 1.21.1 dynamic gallery-texture adapter. */
final class PlatformGalleryTextures {
    private PlatformGalleryTextures() { }

    static Identifier install(String name, NativeImage image) {
        NativeImageBackedTexture texture = new NativeImageBackedTexture(image);
        texture.setFilter(true, false);
        return MinecraftClient.getInstance().getTextureManager().registerDynamicTexture(name, texture);
    }

    static void draw(DrawContext context, Identifier id, int x, int y, int width, int height,
                     int sourceWidth, int sourceHeight) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        context.drawTexture(id, x, y, width, height, 0, 0,
                sourceWidth, sourceHeight, sourceWidth, sourceHeight);
    }
}
